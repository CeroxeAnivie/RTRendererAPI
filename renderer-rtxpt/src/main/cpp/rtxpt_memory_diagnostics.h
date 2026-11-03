#pragma once

// Explicit, process-local diagnostics. No recording, queries or resource census by default.
#include <windows.h>
#include <psapi.h>
#include <share.h>
#include <directx/d3d12.h>
#include <dxgi1_4.h>
#include <wrl/client.h>
#include <chrono>
#include <cstdint>
#include <cwchar>
#include <cstdio>
#include <cstdlib>
#include <mutex>
#include <string>
#include <string_view>
#include <unordered_map>
#include <atomic>
#include <cstdarg>
#include "rtxpt_async_output.h"

namespace RtrMemoryDiagnostics {
inline std::atomic<bool> recording{true};
inline std::atomic<uint64_t> epoch{1};
#ifdef RTR_MEMORY_DIAGNOSTICS_SELFTEST
inline std::atomic<uint64_t> processQueries{0},clockCalls{0},writes{0};
#endif
inline bool Enabled() {
    static const bool enabled=[] {wchar_t value[2]{};return GetEnvironmentVariableW(L"RTR_RTXPT_MEMORY_DIAGNOSTICS",value,2)==1 && value[0]==L'1';}();
    return enabled && recording.load(std::memory_order_relaxed);
}
inline bool Current(uint64_t value){return Enabled()&&epoch.load(std::memory_order_acquire)==value;}
inline FILE* Output() {
    static FILE* output=[] {
        FILE* file=nullptr;
        const DWORD size=GetEnvironmentVariableW(L"RTR_RTXPT_MEMORY_LOG",nullptr,0);
        if(size) {
            std::wstring path(size,L'\0');
            if(GetEnvironmentVariableW(L"RTR_RTXPT_MEMORY_LOG",path.data(),size))file=_wfsopen(path.c_str(),L"wb",_SH_DENYWR);
        }
        return file?file:stderr;
    }();
    return output;
}
inline RtrAsyncOutput& Writer(){static RtrAsyncOutput output(Output(),Output()!=stderr);return output;}
inline void Write(const char* format,...){
#ifdef RTR_MEMORY_DIAGNOSTICS_SELFTEST
    ++writes;
#endif
    char line[512];va_list args;va_start(args,format);vsnprintf(line,sizeof(line),format,args);va_end(args);
    Writer().Write(line);
}
inline unsigned long long Timestamp() {
#ifdef RTR_MEMORY_DIAGNOSTICS_SELFTEST
    ++clockCalls;
#endif
    FILETIME time;GetSystemTimeAsFileTime(&time);
    ULARGE_INTEGER value;value.LowPart=time.dwLowDateTime;value.HighPart=time.dwHighDateTime;
    return (value.QuadPart-116444736000000000ULL)/10000;
}
inline PROCESS_MEMORY_COUNTERS_EX ProcessMemory() {
#ifdef RTR_MEMORY_DIAGNOSTICS_SELFTEST
    ++processQueries;
#endif
    PROCESS_MEMORY_COUNTERS_EX memory{};memory.cb=sizeof(memory);
    GetProcessMemoryInfo(GetCurrentProcess(),reinterpret_cast<PROCESS_MEMORY_COUNTERS*>(&memory),sizeof(memory));
    return memory;
}
struct Resource {std::string name,kind;UINT heap;UINT64 logical,allocated;};
inline std::mutex mutex;
inline std::unordered_map<const void*,Resource> resources;
inline void Configure(bool enabled){
    std::lock_guard lock(mutex);
    recording.store(enabled,std::memory_order_release);++epoch;
    if(!enabled)resources.clear();
}

inline void Created(const void* object,const char* kind,std::string_view name,ID3D12Device* device,ID3D12Resource* resource,UINT64 logical) {
    if(!Enabled() || !resource)return;
    const auto started=epoch.load(std::memory_order_acquire);
    std::lock_guard lock(mutex);
    if(!Current(started))return;
    auto desc=resource->GetDesc();
    auto info=device->GetResourceAllocationInfo(0,1,&desc);
    D3D12_HEAP_PROPERTIES heap{};D3D12_HEAP_FLAGS flags{};
    const HRESULT heapResult=resource->GetHeapProperties(&heap,&flags);
    const UINT type=SUCCEEDED(heapResult)?UINT(heap.Type):0;
    resources[object]={std::string(name),kind,type,logical,info.SizeInBytes};
    Write("RTXPT_MEMORY_RESOURCE time=%llu action=create object=%p kind=%s heap=%u logical=%llu allocated=%llu name=\"%.*s\"\n",
        Timestamp(),object,kind,type,(unsigned long long)logical,(unsigned long long)info.SizeInBytes,int(name.size()),name.data());
    if(info.SizeInBytes>=4*1024*1024) {
        void* frames[24]{};USHORT count=CaptureStackBackTrace(1,24,frames,nullptr);
        Write("RTXPT_MEMORY_STACK time=%llu object=%p",Timestamp(),object);
        for(USHORT i=0;i<count;++i) {
            HMODULE module=nullptr;wchar_t path[MAX_PATH]{};
            if(GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS|GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                reinterpret_cast<LPCWSTR>(frames[i]),&module) && GetModuleFileNameW(module,path,MAX_PATH)) {
                const wchar_t* filename=wcsrchr(path,L'\\');filename=filename?filename+1:path;
                Write(" %ls+0x%llx",filename,(unsigned long long)(reinterpret_cast<uintptr_t>(frames[i])-reinterpret_cast<uintptr_t>(module)));
            } else Write(" %p",frames[i]);
        }
        Write("\n");
    }
}
inline void Released(const void* object) {
    if(!Enabled())return;
    std::lock_guard lock(mutex);
    if(!Enabled())return;
    if(resources.erase(object)) {
        Write("RTXPT_MEMORY_RESOURCE time=%llu action=release object=%p\n",Timestamp(),object);
        }
}
inline void Checkpoint(const char* phase,ID3D12Device* device=nullptr) {
    if(!Enabled())return;
    const auto started=epoch.load(std::memory_order_acquire);
    std::lock_guard lock(mutex);
    if(!Current(started))return;
    const auto memory=ProcessMemory();
    DXGI_QUERY_VIDEO_MEMORY_INFO local{},shared{};HRESULT gpuResult=E_NOINTERFACE;
    if(device) {
        Microsoft::WRL::ComPtr<IDXGIFactory4> factory;
        Microsoft::WRL::ComPtr<IDXGIAdapter3> adapter;
        gpuResult=CreateDXGIFactory1(IID_PPV_ARGS(&factory));
        if(SUCCEEDED(gpuResult))gpuResult=factory->EnumAdapterByLuid(device->GetAdapterLuid(),IID_PPV_ARGS(&adapter));
        if(SUCCEEDED(gpuResult))gpuResult=adapter->QueryVideoMemoryInfo(0,DXGI_MEMORY_SEGMENT_GROUP_LOCAL,&local);
        if(SUCCEEDED(gpuResult))gpuResult=adapter->QueryVideoMemoryInfo(0,DXGI_MEMORY_SEGMENT_GROUP_NON_LOCAL,&shared);
    }
    UINT64 totals[5]{};
    for(const auto& entry:resources)if(entry.second.heap<5)totals[entry.second.heap]+=entry.second.allocated;
    Write("RTXPT_MEMORY_PHASE time=%llu phase=%s private=%llu workingSet=%llu gpuQueryStatus=0x%08lx dedicated=%llu shared=%llu trackedDefault=%llu trackedUpload=%llu trackedReadback=%llu liveResources=%zu outputDropped=%llu\n",
        Timestamp(),phase,(unsigned long long)memory.PrivateUsage,(unsigned long long)memory.WorkingSetSize,
        (unsigned long)gpuResult,(unsigned long long)local.CurrentUsage,(unsigned long long)shared.CurrentUsage,
        (unsigned long long)totals[1],(unsigned long long)totals[2],(unsigned long long)totals[3],resources.size(),(unsigned long long)Writer().dropped);
}
class Scope {
    const char* phase;
    SIZE_T before=0;
    uint64_t started=0;
public:
    explicit Scope(const char* value):phase(Enabled()?value:nullptr) {
        if(phase){
            started=epoch.load(std::memory_order_acquire);std::lock_guard lock(mutex);
            if(Current(started))before=ProcessMemory().PrivateUsage;else phase=nullptr;
        }
    }
    ~Scope() {
        if(!phase||!Current(started))return;
        std::lock_guard lock(mutex);
        if(!Current(started))return;
        auto after=ProcessMemory().PrivateUsage;
        Write("RTXPT_MEMORY_SCOPE time=%llu phase=%s before=%llu after=%llu delta=%lld\n",
            Timestamp(),phase,(unsigned long long)before,(unsigned long long)after,(long long)after-(long long)before);
        }
};
}
