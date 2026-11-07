// Optional timing of existing adapter command-list scopes, through NVRHI only.
// A duration is elapsed GPU seconds from NVRHI, never an absolute timestamp.
#pragma once
#include <nvrhi/nvrhi.h>
#include "rtxpt_async_output.h"
#include "rtxpt_diagnostics.h"
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <memory>
#include <mutex>

namespace RtrGpuProfile {
// One shared bounded ring, not a per-queue timestamp heap. Leave capacity for
// other NVRHI users (the locked D3D12 DeviceDesc defaults to 256 timer queries).
constexpr uint32_t QueryCount=128,NameCount=128,NameBytes=96;
enum class Phase : uint8_t { Free, Recording, Pending };
struct Token { int queue=-1;uint32_t slot=QueryCount,zone=0;uint64_t session=0,serial=0; };
struct Event {
    nvrhi::TimerQueryHandle query;
    nvrhi::ICommandList* command=nullptr;
    Phase phase=Phase::Free;
    uint32_t name=0;int queue=-1,parent=-1;
    uint64_t serial=0,frame=0,sequence=0,revision=0,mode=0,resource=0,generation=0,reason=0,beginNative=0;
    bool ended=false,incompleteReported=false;
};
struct Counters {
    bool initialized=false,requested=false,enabled=false;
    uint32_t capacity=0,stride=16,warmup=0;
    uint64_t frames=0,completed=0,dropped=0,incomplete=0,pending=0,unclosed=0,invalidEnds=0,queryCreateFailures=0,outputDropped=0;
};
struct State {
    nvrhi::IDevice* device=nullptr;
    std::array<Event,QueryCount> events{};
    std::array<std::array<char,NameBytes>,NameCount> names{};
    uint32_t namesUsed=0,next=0;
    uint64_t session=0,nextSerial=1,lastExportDropped=0,lastExportIncomplete=0;
    bool prepared=false;
    std::atomic<bool> enabled{false};
    Counters counters;
    std::unique_ptr<RtrAsyncOutput> output;
    std::mutex mutex;
};
// Lifecycle belongs to the renderer owner; Shutdown follows command-recording
// worker termination and precedes releasing the NVRHI device.
inline std::unique_ptr<State> state;
inline uint64_t nextSession=1;
inline uint32_t EnvNumber(const char* name,uint32_t fallback,bool zero=false){
    if(const char* value=std::getenv(name)){
        char* end=nullptr;const auto n=std::strtoul(value,&end,10);
        if(end!=value&&*end=='\0'&&(zero||n>0)&&n<1000000)return uint32_t(n);
    }
    return fallback;
}
inline uint64_t Pending(const State& s){
    uint64_t count=0;for(const auto& event:s.events)count+=event.phase!=Phase::Free;return count;
}
inline void WriteRecord(State& s,const Event& e,const char* status,double milliseconds=0,bool duration=false){
    if(!s.output)return;
    std::array<char,512> line{};std::array<char,48> elapsed{};
    if(duration)std::snprintf(elapsed.data(),elapsed.size(),"%.9f",milliseconds);
    const int size=std::snprintf(line.data(),line.size(),
        "%llu,%s,%llu,%d,%s,%s,%llu,%llu,%llu,%llu,%llu,%llu,%s,%llu,%llu\n",
        static_cast<unsigned long long>(e.frame),e.queue==0?"graphics":"compute",
        static_cast<unsigned long long>(e.serial),e.parent,s.names[e.name].data(),elapsed.data(),
        static_cast<unsigned long long>(e.sequence),static_cast<unsigned long long>(e.revision),
        static_cast<unsigned long long>(e.mode),static_cast<unsigned long long>(e.resource),
        static_cast<unsigned long long>(e.generation),static_cast<unsigned long long>(e.reason),status,
        static_cast<unsigned long long>(s.session),static_cast<unsigned long long>(e.beginNative));
    if(size<0||size>=int(line.size())){++s.counters.dropped;return;}
    s.output->Write(line.data());
}
inline void WriteStatus(State& s,const char* status){
    if(!s.output)return;
    // Status rows share the event CSV schema. Counter evidence is a comment,
    // not a fabricated duration or a completed GPU sample.
    std::array<char,512> line{};
    std::snprintf(line.data(),line.size(),
        "# status=%s,frames=%llu,capacity=%u,completed=%llu,dropped=%llu,incomplete=%llu,pending=%llu,unclosed=%llu,invalidEnds=%llu,queryCreateFailures=%llu,outputDropped=%llu,enabled=%u,stride=%u,warmup=%u\n",
        status,static_cast<unsigned long long>(s.counters.frames),s.counters.capacity,
        static_cast<unsigned long long>(s.counters.completed),static_cast<unsigned long long>(s.counters.dropped),
        static_cast<unsigned long long>(s.counters.incomplete),static_cast<unsigned long long>(Pending(s)),
        static_cast<unsigned long long>(s.counters.unclosed),static_cast<unsigned long long>(s.counters.invalidEnds),
        static_cast<unsigned long long>(s.counters.queryCreateFailures),static_cast<unsigned long long>(s.output->dropped),
        unsigned(s.enabled.load(std::memory_order_relaxed)),s.counters.stride,s.counters.warmup);
    s.output->Write(line.data());
}
inline void Collect(State& s){
    for(auto& event:s.events){
        if(event.phase!=Phase::Pending)continue;
        // pollTimerQuery never waits. The locked NVRHI D3D12 implementation
        // clears its completed fence here; only then is getTimerQueryTime safe.
        if(!s.device->pollTimerQuery(event.query))continue;
        if(event.ended){
            const double seconds=s.device->getTimerQueryTime(event.query);
            if(std::isfinite(seconds)&&seconds>=0){++s.counters.completed;WriteRecord(s,event,"complete",seconds*1000,true);}
            else {if(!event.incompleteReported)++s.counters.incomplete;WriteRecord(s,event,"invalid_duration");}
        }else WriteRecord(s,event,"unclosed");
        s.device->resetTimerQuery(event.query);
        event.phase=Phase::Free;event.command=nullptr;
    }
}
inline void Init(nvrhi::IDevice* device,bool explicitEnable=false){
    if(state||!device)return;
    const char* path=std::getenv("RTR_RTXPT_GPU_PROFILE");
    const char* control=std::getenv("RTR_RTXPT_GPU_TIMING");
    const bool requested=control?std::strcmp(control,"1")==0:explicitEnable||(path&&*path);
    auto next=std::make_unique<State>();next->device=device;next->session=nextSession++;
    next->counters.initialized=true;next->counters.requested=requested;
    next->counters.stride=EnvNumber("RTR_RTXPT_GPU_PROFILE_STRIDE",explicitEnable?1:16);
    next->counters.warmup=EnvNumber("RTR_RTXPT_GPU_PROFILE_WARMUP",0,true);
    // File creation is an explicit cold lifecycle operation, never Begin/End or
    // FrameBoundary. A path is required even when timing is explicitly enabled.
    if(requested&&path&&*path){
        const bool hasHeader=std::filesystem::exists(path)&&std::filesystem::file_size(path)>0;
        next->output=std::make_unique<RtrAsyncOutput>(path,true);
        if(!hasHeader)next->output->Write("frame,queue,event,parent,name,duration_ms,sequence,revision,mode,resource,generation,reason,status,session,begin_native_ns\n");
        next->output->Write("# time_semantics=NVRHI_elapsed_seconds_times_1000; absolute_timestamps_unavailable; only_status_complete_has_a_duration\n");
    }
    state=std::move(next);
}
inline void Configure(nvrhi::IDevice* device,unsigned level,bool allowed){
    if(!state||state->device!=device)return;
    std::lock_guard lock(state->mutex);auto& s=*state;
    const bool enabled=allowed&&level!=0&&s.counters.requested;
    // Creating queries is a diagnostic configuration transition, not frame
    // recording. OFF never creates queries; completed objects are reused.
    if(enabled&&!s.prepared){
        s.prepared=true;
        for(auto& event:s.events){
            event.query=device->createTimerQuery();
            if(!event.query){++s.counters.queryCreateFailures;break;}
            ++s.counters.capacity;
        }
    }
    s.counters.enabled=enabled;s.enabled.store(enabled,std::memory_order_relaxed);
}
inline Token Begin(nvrhi::ICommandList* list,const char* name,int parent,uint64_t resource=0,uint64_t generation=0,uint64_t reason=0){
    if(!state||!list||!state->enabled.load(std::memory_order_relaxed)||RtrDiagnostics::level.load(std::memory_order_relaxed)==0)return {};
    std::lock_guard lock(state->mutex);auto& s=*state;
    if(!s.enabled.load(std::memory_order_relaxed)||s.counters.frames<s.counters.warmup||(s.counters.frames-s.counters.warmup)%s.counters.stride)return {};
    const auto type=list->getDesc().queueType;
    const int queue=type==nvrhi::CommandQueue::Graphics?0:type==nvrhi::CommandQueue::Compute?1:-1;
    if(queue<0||!name){++s.counters.dropped;return {};}
    size_t length=0;while(length<NameBytes&&name[length]){
        if(name[length]==','||name[length]=='"'||name[length]=='\r'||name[length]=='\n'){++s.counters.dropped;return {};}
        ++length;
    }
    if(length==NameBytes){++s.counters.dropped;return {};}
    uint32_t nameIndex=0;while(nameIndex<s.namesUsed&&std::strcmp(s.names[nameIndex].data(),name)!=0)++nameIndex;
    if(nameIndex==s.namesUsed){
        if(nameIndex==NameCount){++s.counters.dropped;return {};}
        std::memcpy(s.names[nameIndex].data(),name,length+1);++s.namesUsed;
    }
    for(uint32_t probe=0;probe<QueryCount;++probe){
        const uint32_t index=(s.next+probe)%QueryCount;auto& event=s.events[index];
        if(!event.query||event.phase!=Phase::Free)continue;
        event.command=list;event.phase=Phase::Recording;event.name=nameIndex;event.queue=queue;event.parent=parent;
        event.serial=s.nextSerial++;event.frame=s.counters.frames;event.beginNative=RtrDiagnostics::Now();
        event.sequence=RtrDiagnostics::frame.load(std::memory_order_relaxed);event.revision=RtrDiagnostics::revision.load(std::memory_order_relaxed);
        event.mode=RtrDiagnostics::level.load(std::memory_order_relaxed);event.resource=resource;event.generation=generation;event.reason=reason;
        event.ended=false;event.incompleteReported=false;s.next=(index+1)%QueryCount;
        list->beginTimerQuery(event.query);return {queue,index,0,s.session,event.serial};
    }
    ++s.counters.dropped;return {};
}
inline void End(nvrhi::ICommandList* list,Token token){
    if(!state||token.queue<0)return;
    std::lock_guard lock(state->mutex);auto& s=*state;
    if(token.session!=s.session||token.slot>=QueryCount){++s.counters.invalidEnds;return;}
    auto& event=s.events[token.slot];
    // NVRHI explicitly requires begin/end on the SAME command list. An outer
    // scope spanning separate command lists cannot be represented as one timer.
    if(event.serial!=token.serial||event.phase!=Phase::Recording||event.ended||event.command!=list){++s.counters.invalidEnds;return;}
    list->endTimerQuery(event.query);event.ended=true;event.command=nullptr;
}
// Call after all timed command lists for this owner frame have been submitted,
// including shared-output copies. It neither submits work nor waits for a query.
inline void FrameBoundary(){
    if(!state)return;
    std::lock_guard lock(state->mutex);auto& s=*state;
    const auto previousPending=Pending(s);
    for(auto& event:s.events)if(event.phase==Phase::Recording){
        event.phase=Phase::Pending;event.command=nullptr;
        if(!event.ended){event.incompleteReported=true;++s.counters.incomplete;++s.counters.unclosed;}
    }
    Collect(s);
    if(previousPending||Pending(s)||s.lastExportDropped!=s.counters.dropped||s.lastExportIncomplete!=s.counters.incomplete){
        WriteStatus(s,"frame_boundary");s.lastExportDropped=s.counters.dropped;s.lastExportIncomplete=s.counters.incomplete;
    }
    ++s.counters.frames;
}
inline void AfterPresent(){FrameBoundary();}
// Numeric availability/loss counters only: no snapshot allocation, hidden poll,
// elapsed-as-tick packing, or extension into upstream algorithm internals.
inline Counters GetCounters(){
    if(!state)return {};
    std::lock_guard lock(state->mutex);auto counters=state->counters;
    counters.pending=Pending(*state);counters.enabled=state->enabled.load(std::memory_order_relaxed);
    if(state->output)counters.outputDropped=state->output->dropped;return counters;
}
inline void Shutdown(){
    if(!state)return;
    {
        std::lock_guard lock(state->mutex);auto& s=*state;s.enabled.store(false,std::memory_order_relaxed);s.counters.enabled=false;
        // One nonblocking final poll. A caller may separately finish its normal
        // device lifecycle; this diagnostic path never adds waitForIdle/fences.
        for(auto& event:s.events)if(event.phase==Phase::Recording){event.phase=Phase::Pending;event.command=nullptr;
            if(!event.ended&&!event.incompleteReported){event.incompleteReported=true;++s.counters.incomplete;++s.counters.unclosed;}}
        Collect(s);
        for(auto& event:s.events)if(event.phase!=Phase::Free){
            if(!event.incompleteReported){event.incompleteReported=true;++s.counters.incomplete;}
            WriteRecord(s,event,"shutdown_pending");
        }
        WriteStatus(s,"shutdown");
    }
    // RtrAsyncOutput drains its bounded CPU queue only at cold shutdown. NVRHI
    // owns the GPU lifetime of timer references retained by submitted lists.
    state.reset();
}
}
