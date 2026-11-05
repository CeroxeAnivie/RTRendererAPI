// Optional, sampled export of upstream RTXPT GPU scopes. Rendering stays upstream.
// Mirrors GpuProfiler.cpp's per-queue timestamp/resolve/fence design, without Tracy.
#pragma once
#include <nvrhi/d3d12.h>
#include "rtxpt_async_output.h"
#include "rtxpt_diagnostics.h"
#include "rtxpt_fence.h"
#include <string>
#include <vector>
#include <array>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>

namespace RtrGpuProfile {
constexpr uint32_t SlotCount=8, ZoneCount=512, NameCount=256;
struct Token { int queue=-1; uint32_t slot=0, zone=0; };
struct Event { uint32_t name=0; int parent=-1; bool ended=false; uint64_t mode=0,resource=0,generation=0,reason=0; };
struct Slot {
    std::array<Event,ZoneCount> events{};
    uint32_t count=0;
    uint64_t frame=0, fence=0, sequence=0, revision=0;
    bool pending=false;
};
struct Queue {
    nvrhi::IDevice* device=nullptr;
    nvrhi::CommandQueue type=nvrhi::CommandQueue::Graphics;
    ID3D12CommandQueue* native=nullptr;
    nvrhi::RefCountPtr<ID3D12QueryHeap> heap;
    nvrhi::RefCountPtr<ID3D12Resource> readback;
    nvrhi::RefCountPtr<ID3D12Fence> fence;
    nvrhi::CommandListHandle resolve;
    std::array<Slot,SlotCount> slots{};
    uint64_t frequency=0, signaled=0;
    int active=-1;
};
struct State {
    std::array<Queue,2> queues;
    std::array<std::array<char,160>,NameCount> names{};
    uint32_t namesUsed=0, stride=16, warmup=240;
    uint64_t frame=0, dropped=0;
    std::unique_ptr<RtrAsyncOutput> output;
    std::array<std::array<char,512>,4096> completed{};
    uint64_t completedCount=0, overwritten=0;
    bool enabled=true,draining=false;
    std::mutex mutex;
};
inline std::unique_ptr<State> state;
inline uint32_t EnvNumber(const char* name,uint32_t fallback) {
    if(const char* value=std::getenv(name)) {
        char* end=nullptr; const auto n=std::strtoul(value,&end,10);
        if(end!=value && *end=='\0' && n>0 && n<1000000) return uint32_t(n);
    }
    return fallback;
}
inline void Collect(Queue& q,int queueIndex) {
    if(std::none_of(q.slots.begin(),q.slots.end(),[](const auto& slot){return slot.pending;}))return;
    const uint64_t completed=RtrCompletedFence(q.fence.Get());
    for(auto& s:q.slots) {
        if(!s.pending || s.fence>completed) continue;
        const uint32_t slotIndex=uint32_t(&s-q.slots.data());
        const size_t offset=size_t(slotIndex)*ZoneCount*2;
        const D3D12_RANGE range{offset*sizeof(uint64_t),(offset+s.count*2)*sizeof(uint64_t)};
        void* mapped=nullptr;
        if(FAILED(q.readback->Map(0,&range,&mapped))) {state->dropped+=s.count;s.pending=false;continue;}
        const auto* ticks=static_cast<const uint64_t*>(mapped)+offset;
        for(uint32_t i=0;i<s.count;++i) {
            if(!s.events[i].ended || ticks[i*2+1]<ticks[i*2]) {++state->dropped; continue;}
            const auto& e=s.events[i];
            auto& line=state->completed[state->completedCount%state->completed.size()];
            std::snprintf(line.data(),line.size(),"%llu,%s,%u,%d,%s,%.9f,%llu,%llu,%llu,%llu,%llu,%llu,%llu,%llu,%llu\n",
                static_cast<unsigned long long>(s.frame),queueIndex==0?"graphics":"compute",i,e.parent,
                state->names[e.name].data(),double(ticks[i*2+1]-ticks[i*2])*1000.0/double(q.frequency),
                static_cast<unsigned long long>(ticks[i*2]),static_cast<unsigned long long>(ticks[i*2+1]),
                static_cast<unsigned long long>(q.frequency),static_cast<unsigned long long>(s.sequence),static_cast<unsigned long long>(s.revision),static_cast<unsigned long long>(e.mode),static_cast<unsigned long long>(e.resource),static_cast<unsigned long long>(e.generation),static_cast<unsigned long long>(e.reason));
            ++state->completedCount;if(state->completedCount>state->completed.size())++state->overwritten;
            if(state->output)state->output->Write(line.data());
        }
        const D3D12_RANGE written{0,0}; q.readback->Unmap(0,&written);
        s.pending=false;
    }
}
inline bool InitQueue(Queue& q,nvrhi::IDevice* device,nvrhi::CommandQueue type) {
    auto* nativeDevice=static_cast<ID3D12Device*>(device->getNativeObject(nvrhi::ObjectTypes::D3D12_Device));
    q.native=device->getNativeQueue(nvrhi::ObjectTypes::D3D12_CommandQueue,type);
    if(!nativeDevice || !q.native || FAILED(q.native->GetTimestampFrequency(&q.frequency))) return false;
    q.device=device; q.type=type;
    D3D12_QUERY_HEAP_DESC h{}; h.Type=D3D12_QUERY_HEAP_TYPE_TIMESTAMP; h.Count=SlotCount*ZoneCount*2;
    if(FAILED(nativeDevice->CreateQueryHeap(&h,IID_PPV_ARGS(&q.heap)))) return false;
    D3D12_HEAP_PROPERTIES hp{}; hp.Type=D3D12_HEAP_TYPE_READBACK;
    D3D12_RESOURCE_DESC r{}; r.Dimension=D3D12_RESOURCE_DIMENSION_BUFFER;
    r.Width=uint64_t(h.Count)*sizeof(uint64_t); r.Height=1; r.DepthOrArraySize=1; r.MipLevels=1;
    r.SampleDesc.Count=1; r.Layout=D3D12_TEXTURE_LAYOUT_ROW_MAJOR;
    if(FAILED(nativeDevice->CreateCommittedResource(&hp,D3D12_HEAP_FLAG_NONE,&r,
        D3D12_RESOURCE_STATE_COPY_DEST,nullptr,IID_PPV_ARGS(&q.readback)))) return false;
    if(FAILED(nativeDevice->CreateFence(0,D3D12_FENCE_FLAG_NONE,IID_PPV_ARGS(&q.fence)))) return false;
    nvrhi::CommandListParameters p; p.queueType=type; q.resolve=device->createCommandList(p);
    return q.resolve!=nullptr;
}
inline void Init(nvrhi::IDevice* device,bool explicitEnable=false) {
    if(state){
        state->enabled=explicitEnable||state->output!=nullptr;
        // DEEP is an immediate diagnostic request, including when an export
        // path created the profiler earlier with its automatic warmup.
        if(explicitEnable){state->warmup=0;state->stride=EnvNumber("RTR_RTXPT_GPU_PROFILE_STRIDE",1);}
        return;
    }
    const char* path=std::getenv("RTR_RTXPT_GPU_PROFILE");
    if(((!path || !*path)&&!explicitEnable) || device->getGraphicsAPI()!=nvrhi::GraphicsAPI::D3D12) return;
    state=std::make_unique<State>();
    if(path&&*path)state->output=std::make_unique<RtrAsyncOutput>(path);
    state->stride=EnvNumber("RTR_RTXPT_GPU_PROFILE_STRIDE",explicitEnable?1:16);
    state->warmup=explicitEnable?0:EnvNumber("RTR_RTXPT_GPU_PROFILE_WARMUP",240);
    if(state->output)state->output->Write("frame,queue,event,parent,name,inclusive_ms,begin_tick,end_tick,frequency,sequence,revision,mode,resource,generation,reason\n");
    if(!InitQueue(state->queues[0],device,nvrhi::CommandQueue::Graphics)
        || !InitQueue(state->queues[1],device,nvrhi::CommandQueue::Compute)) {
        state.reset();
    }
}
inline Token Begin(nvrhi::ICommandList* list,const char* name,int parent,uint64_t resource=0,uint64_t generation=0,uint64_t reason=0) {
    if(!state || !state->enabled || state->frame<state->warmup || (state->frame-state->warmup)%state->stride) return {};
    const auto type=list->getDesc().queueType;
    const int qi=type==nvrhi::CommandQueue::Graphics?0:type==nvrhi::CommandQueue::Compute?1:-1;
    if(qi<0) return {};
    std::lock_guard lock(state->mutex);
    auto& q=state->queues[qi];
    if(q.active<0) {
        for(uint32_t i=0;i<SlotCount;++i) if(!q.slots[i].pending) {
            q.active=int(i); q.slots[i].count=0; q.slots[i].frame=state->frame;q.slots[i].sequence=RtrDiagnostics::frame.load();q.slots[i].revision=RtrDiagnostics::revision.load(); break;
        }
    }
    if(q.active<0 || q.slots[q.active].count==ZoneCount) {++state->dropped; return {};}
    auto* nativeList=static_cast<ID3D12GraphicsCommandList*>(list->getNativeObject(nvrhi::ObjectTypes::D3D12_GraphicsCommandList));
    if(!nativeList) {++state->dropped; return {};}
    uint32_t ni=0;
    while(ni<state->namesUsed && std::strcmp(state->names[ni].data(),name)!=0) ++ni;
    if(ni==state->namesUsed) {
        if(ni==NameCount || std::strlen(name)>=state->names[ni].size()) {++state->dropped; return {};}
        strcpy_s(state->names[ni].data(),state->names[ni].size(),name); ++state->namesUsed;
    }
    auto& s=q.slots[q.active]; const uint32_t i=s.count++;
    s.events[i]={ni,parent,false,RtrDiagnostics::level.load(),resource,generation,reason};
    nativeList->EndQuery(q.heap,D3D12_QUERY_TYPE_TIMESTAMP,(uint32_t(q.active)*ZoneCount+i)*2);
    return {qi,uint32_t(q.active),i};
}
inline void End(nvrhi::ICommandList* list,Token token) {
    if(!state || token.queue<0) return;
    auto& q=state->queues[token.queue];
    auto* nativeList=static_cast<ID3D12GraphicsCommandList*>(list->getNativeObject(nvrhi::ObjectTypes::D3D12_GraphicsCommandList));
    if(!nativeList) {++state->dropped; return;}
    nativeList->EndQuery(q.heap,D3D12_QUERY_TYPE_TIMESTAMP,(token.slot*ZoneCount+token.zone)*2+1);
    q.slots[token.slot].events[token.zone].ended=true;
}
inline void AfterPresent() {
    if(!state||(!state->enabled&&!state->draining)) return;
    for(int i=0;i<2;++i) {
        auto& q=state->queues[i]; Collect(q,i);
        if(q.active<0) continue;
        auto& s=q.slots[q.active];
        const uint32_t start=uint32_t(q.active)*ZoneCount*2;
        q.resolve->open();
        auto* nativeList=static_cast<ID3D12GraphicsCommandList*>(q.resolve->getNativeObject(nvrhi::ObjectTypes::D3D12_GraphicsCommandList));
        nativeList->ResolveQueryData(q.heap,D3D12_QUERY_TYPE_TIMESTAMP,start,s.count*2,q.readback,uint64_t(start)*sizeof(uint64_t));
        q.resolve->close(); q.device->executeCommandList(q.resolve,q.type);
        s.fence=++q.signaled;
        const auto result=q.native->Signal(q.fence,s.fence);
        if(SUCCEEDED(result)) s.pending=true;
        else{++state->dropped;RtrLatchDeviceFailure(result);RtrRequireHealthy();}
        q.active=-1;
    }
    if(state->enabled)++state->frame;
    if(!state->enabled){
        state->draining=false;
        for(const auto& q:state->queues)for(const auto& slot:q.slots)state->draining|=slot.pending;
    }
}
inline void Configure(nvrhi::IDevice* device,unsigned level,bool allowed){
    const char* path=std::getenv("RTR_RTXPT_GPU_PROFILE");
    const bool enabled=allowed&&level!=0&&(level==2||(path&&*path));
    if(enabled)Init(device,level==2);
    if(state){
        state->enabled=enabled;state->draining=false;
        if(!enabled)for(const auto& q:state->queues){
            state->draining|=q.active>=0;
            for(const auto& slot:q.slots)state->draining|=slot.pending;
        }
    }
}
inline std::vector<std::string> Snapshot(){
    if(!state)return {"0,0,0,0,0"};
    std::lock_guard lock(state->mutex);
    // An explicit snapshot also publishes the final submitted frame. There may
    // be no next present to collect its already-completed timestamps.
    if(state->enabled||state->draining){
        for(int i=0;i<2;++i)Collect(state->queues[i],i);
        if(!state->enabled){
            state->draining=false;
            for(const auto& q:state->queues){
                state->draining|=q.active>=0;
                for(const auto& slot:q.slots)state->draining|=slot.pending;
            }
        }
    }
    std::vector<std::string> result;
    uint64_t pending=0;for(auto& q:state->queues)for(auto& slot:q.slots)pending+=slot.pending?slot.count:0;
    result.push_back(std::to_string(state->dropped)+","+std::to_string(state->overwritten)+","+std::to_string(pending)+","+std::to_string(state->stride)+","+std::to_string(state->enabled));
    const uint64_t count=std::min<uint64_t>(state->completedCount,state->completed.size());
    result.reserve(size_t(count)+1);
    for(uint64_t i=state->completedCount-count;i<state->completedCount;++i)result.emplace_back(state->completed[i%state->completed.size()].data());
    return result;
}
inline void Shutdown() {
    if(!state) return;
    // Only shutdown may wait. Sampling never waits for a GPU query.
    for(int i=0;i<2;++i) {
        auto& q=state->queues[i];
        if(q.device&&RtrDeviceRemoved(static_cast<ID3D12Device*>(q.device->getNativeObject(nvrhi::ObjectTypes::D3D12_Device))))continue;
        if(q.signaled && RtrCompletedFence(q.fence.Get())<q.signaled) {
            HANDLE event=CreateEventW(nullptr,FALSE,FALSE,nullptr);
            if(event) {
                if(SUCCEEDED(q.fence->SetEventOnCompletion(q.signaled,event))) WaitForSingleObject(event,5000);
                CloseHandle(event);
            }
        }
        Collect(q,i);
    }
    if(state->output&&(state->enabled||state->draining)){char line[256];std::snprintf(line,sizeof(line),"# frames=%llu,stride=%u,warmup=%u,dropped=%llu,outputDropped=%llu\n",
        static_cast<unsigned long long>(state->frame),state->stride,state->warmup,
        static_cast<unsigned long long>(state->dropped),static_cast<unsigned long long>(state->output->dropped));state->output->Write(line);}
    state.reset();
}
}
