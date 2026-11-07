#pragma once
#include <windows.h>
#include <psapi.h>
#include <directx/d3d12.h>
#include <dxgi1_4.h>
#include <wrl/client.h>
#include <sstream>
#include <string>
#include <cstdint>

// Exceptional path only: an E_OUTOFMEMORY message must distinguish the
// requested resource, process/job commit and adapter budget. Never infer one
// memory domain from another or query these counters on successful frames.
inline std::string RtrAllocationFailure(nvrhi::IDevice* device,const char* name,size_t bytes){
    PROCESS_MEMORY_COUNTERS_EX process{};process.cb=sizeof(process);
    const bool processOk=GetProcessMemoryInfo(GetCurrentProcess(),reinterpret_cast<PROCESS_MEMORY_COUNTERS*>(&process),sizeof(process))!=FALSE;
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION job{};
    const bool jobOk=QueryInformationJobObject(nullptr,JobObjectExtendedLimitInformation,&job,sizeof(job),nullptr)!=FALSE;
    JOBOBJECT_LIMIT_VIOLATION_INFORMATION violation{};
    const bool violationOk=QueryInformationJobObject(nullptr,JobObjectLimitViolationInformation,&violation,sizeof(violation),nullptr)!=FALSE;
    PERFORMANCE_INFORMATION system{};system.cb=sizeof(system);
    const bool systemOk=GetPerformanceInfo(&system,sizeof(system))!=FALSE;
    DXGI_QUERY_VIDEO_MEMORY_INFO local{},shared{};HRESULT localResult=E_NOINTERFACE,sharedResult=E_NOINTERFACE;
    auto* native=static_cast<ID3D12Device*>(device->getNativeObject(nvrhi::ObjectTypes::D3D12_Device));
    if(native){
        Microsoft::WRL::ComPtr<IDXGIFactory4> factory;Microsoft::WRL::ComPtr<IDXGIAdapter3> adapter;
        localResult=CreateDXGIFactory1(IID_PPV_ARGS(&factory));
        if(SUCCEEDED(localResult))localResult=factory->EnumAdapterByLuid(native->GetAdapterLuid(),IID_PPV_ARGS(&adapter));
        if(SUCCEEDED(localResult)){localResult=adapter->QueryVideoMemoryInfo(0,DXGI_MEMORY_SEGMENT_GROUP_LOCAL,&local);sharedResult=adapter->QueryVideoMemoryInfo(0,DXGI_MEMORY_SEGMENT_GROUP_NON_LOCAL,&shared);}
    }
    std::ostringstream out;out<<"Could not allocate "<<name<<" requestedBytes="<<bytes
        <<" processQuery="<<processOk<<" privateBytes="<<process.PrivateUsage<<" workingSetBytes="<<process.WorkingSetSize
        <<" jobQuery="<<jobOk<<" jobFlags="<<job.BasicLimitInformation.LimitFlags<<" jobLimitBytes="<<job.JobMemoryLimit
        <<" jobPeakBytes="<<job.PeakJobMemoryUsed<<" processLimitBytes="<<job.ProcessMemoryLimit
        <<" jobUsageQuery="<<violationOk<<" jobCurrentBytes="<<violation.JobMemory<<" jobViolatedFlags="<<violation.ViolationLimitFlags
        <<" systemQuery="<<systemOk<<" systemCommitBytes="<<uint64_t(system.CommitTotal)*system.PageSize
        <<" systemCommitLimitBytes="<<uint64_t(system.CommitLimit)*system.PageSize
        <<" localQuery="<<long(localResult)<<" localUsageBytes="<<local.CurrentUsage<<" localBudgetBytes="<<local.Budget
        <<" nonLocalQuery="<<long(sharedResult)<<" nonLocalUsageBytes="<<shared.CurrentUsage<<" nonLocalBudgetBytes="<<shared.Budget;
    return out.str();
}
