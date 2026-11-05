#pragma once
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <functional>
#include <exception>
#include <mutex>
#include <optional>
#include <stdexcept>
#include <thread>
#include <vector>
#include <string_view>
#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#else
#include <time.h>
#endif

// Process-global bounded evidence for the one RTXPT session. Recording performs
// no allocations, filesystem I/O, or GPU waits. Snapshot construction is an
// explicit caller operation, never part of frame recording.
namespace RtrDiagnostics {
enum Stage : uint64_t { Frame=1, InstanceCache, SubInstances, Dispatch, Caller,
    Worker, TaskWait, MessagePump, ShaderCache, ShaderCompile, ShaderLibrary,
    Pipeline, ShaderTable, Import, Textures, Materials, Acceleration, Retirement,
    SharedPublish, SharedReuseWait, Resize, PoolWait, Lighting, Preparation, Upload, Submit,
    LightValidation, LightObjects, LightGraph, LightTree, LightUpload, LightingPolicy,
    SubmitValidation, Translation, Resources, SubmitRetirement, AttributePreparation, AttributeValidation, ResourceStaging, ResourceIndex, ResourceRetirement,
    ResourcePlan, BufferAllocate, DescriptorAllocate, PayloadPack, BufferUpload, BlasAllocate, BlasClone, BlasBuild, BlasRefit,
    CommandAcquire, CommandSubmit, CompletionPoll, ReclaimEnqueue, DriverRelease, IndexRebuild, TextureAllocate, TextureUpload,
    RuntimeInitialize, DeviceInitialize, SceneInitialize, LightingInitialize, PoolAcquire, TlasAllocate };
enum Flag : uint64_t { QueueWaitEnqueue=1, Failed=2, CacheHit=4, CacheMiss=8,
    Active=256, Truncated=512, Cancelled=1024, EnteredNativeFrame=2048 };
enum SubmissionKind : uint64_t { Initial=0, Update, Steady, Resized, Waited, KindCount };
constexpr size_t Capacity=8192, Width=25, Header=15, ActiveCapacity=512, SubmissionCapacity=1024;
using Event=std::array<uint64_t,Width>;
using SubmissionSample=std::array<uint64_t,6>;
enum Trait : uint64_t { HasFrame=1, HasUpdate=2, HasResize=4, HasWait=8, IsInitial=16 };
// Bit mask shared by planned and executed resource events. Generation is the
// immutable transaction revision; identity hashes the stable native resource ID.
enum ResourceReason : uint64_t { Inflow=1, Deformation=2, Topology=4, BuildFlags=8,
    Pose=16, Coverage=32, Material=64, Removal=128, Texture=256,
    PoolHit=512, CapacityGrowth=1024, RefitRefresh=2048, IndexMigration=4096 };
struct Live { Event event{};uint64_t children=0,traits=0;bool occupied=false;uint64_t cpuChildren=0; };
inline std::atomic<unsigned> level{0};
inline std::atomic<uint64_t> frame{0},revision{0},inputGeneration{0},outputGeneration{0},nextId{1},generation{1},monitorGeneration{1};
inline std::mutex mutex;
inline std::array<Event,Capacity> events{};
inline uint64_t written=0,dropped=0;
inline std::array<Event,2048> slowEvents{};
inline uint64_t slowCount=0,slowTriggers=0;
inline std::array<Live,ActiveCapacity> live{};
inline uint64_t activeDropped=0;
inline std::array<SubmissionSample,SubmissionCapacity> submissions{};
inline uint64_t submissionCount=0,submissionDropped=0;
inline bool seenSubmission=false;
inline thread_local uint64_t parentId=0,parentGeneration=0;
inline thread_local uint64_t attempt=0;
inline uint64_t Identity(std::string_view value){
    if(!level.load(std::memory_order_relaxed))return 0;uint64_t hash=14695981039346656037ull;
    for(unsigned char c:value){hash^=c;hash*=1099511628211ull;}return hash;
}
#ifdef RTR_DIAGNOSTICS_SELFTEST
inline std::atomic<uint64_t> clockCalls{0};
#endif
inline uint64_t Now(){
#ifdef RTR_DIAGNOSTICS_SELFTEST
    ++clockCalls;
#endif
    return uint64_t(std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count());
}
inline uint64_t ThreadCpuNow(){
#ifdef _WIN32
    FILETIME creation,exit,kernel,user;if(!GetThreadTimes(GetCurrentThread(),&creation,&exit,&kernel,&user))return UINT64_MAX;
    ULARGE_INTEGER k{},u{};k.LowPart=kernel.dwLowDateTime;k.HighPart=kernel.dwHighDateTime;u.LowPart=user.dwLowDateTime;u.HighPart=user.dwHighDateTime;
    return (k.QuadPart+u.QuadPart)*100;
#else
    timespec value{};if(clock_gettime(CLOCK_THREAD_CPUTIME_ID,&value)!=0)return UINT64_MAX;
    return uint64_t(value.tv_sec)*1000000000+value.tv_nsec;
#endif
}
inline uint64_t ThreadIdentity(){
#ifdef _WIN32
    return GetCurrentThreadId();
#else
    return uint64_t(std::hash<std::thread::id>{}(std::this_thread::get_id()));
#endif
}
inline Live* Find(uint64_t id){
    if(!id)return nullptr;
    auto& entry=live[id%ActiveCapacity];
    return entry.occupied&&entry.event[12]==id?&entry:nullptr;
}
inline void RetainTriggerLocked(const Event& event){
    ++slowTriggers;slowCount=0;
    for(uint64_t i=written-std::min<uint64_t>(written,Capacity);i<written;++i){
        const auto& candidate=events[i%Capacity];
        if(candidate[0]==event[0]&&candidate[1]==event[1]&&candidate[5]>=event[5]){
            slowEvents[slowCount%slowEvents.size()]=candidate;++slowCount;
        }
    }
}
inline void RecordLocked(Event event,uint64_t traits=0){
    event[24]=written+1;
    if(event[4]==Submit){
        if(traits&HasFrame)event[11]|=EnteredNativeFrame;
        const uint64_t kind=traits&IsInitial?Initial:traits&HasResize?Resized:traits&HasUpdate?Update:traits&HasWait?Waited:Steady;
        submissions[submissionCount%SubmissionCapacity]={event[0],event[1],event[6],event[11],kind,event[12]};
        ++submissionCount;if(submissionCount>SubmissionCapacity)++submissionDropped;
    }
    events[written%Capacity]=event;++written;if(written>Capacity)++dropped;
    if(((event[4]==Submit||event[4]==Frame)&&event[6]>50'000'000)||(event[4]==Submit&&(event[11]&(Failed|Cancelled)))){
        // Preserve the latest slow or failed submission, including stages before
        // native FRAME. Scope IDs disambiguate batches shared by caller/workers.
        RetainTriggerLocked(event);
    }
}
inline uint64_t Traits(Stage stage){
    switch(stage){
        case Frame:return HasFrame;
        case Resources:case Lighting:case LightObjects:case LightTree:case Import:return HasUpdate;
        case Resize:return HasResize;
        default:return 0;
    }
}
inline void MarkAncestors(uint64_t id,uint64_t traits){
    // Same-thread ancestors only. Worker batch IDs never become CPU self-time
    // children of work running concurrently on another thread.
    for(size_t depth=0;id&&depth<ActiveCapacity;++depth){
        auto* p=Find(id);if(!p)break;p->traits|=traits;id=p->event[13];
    }
}
inline void Configure(unsigned value){
    if(value>2)throw std::invalid_argument("Diagnostic level must be 0..2");
    std::lock_guard lock(mutex);
    const auto old=level.exchange(value,std::memory_order_acq_rel);
    if(value||!old)return;
    // End active evidence at the switch boundary, once. Destructors from the old
    // epoch do not read a clock or write samples after OFF. Truncation is explicit
    // and excluded from completed-duration distributions.
    const bool anyActive=std::any_of(live.begin(),live.end(),[](const auto& entry){return entry.occupied;});
    const uint64_t stopped=anyActive?Now():0;
    std::array<Event,ActiveCapacity> ending{};
    std::array<uint64_t,ActiveCapacity> children{};
    for(size_t i=0;i<live.size();++i)if(live[i].occupied){
        ending[i]=live[i].event;ending[i][6]=stopped-ending[i][5];children[i]=live[i].children;
    }
    for(size_t i=0;i<live.size();++i)if(live[i].occupied&&ending[i][13])
        for(size_t j=0;j<live.size();++j)if(live[j].occupied&&ending[j][12]==ending[i][13]){children[j]+=ending[i][6];break;}
    // Children precede FRAME/SUBMIT so trigger retention includes the closing
    // stages, even when the switch happened inside a long operation.
    for(unsigned pass=0;pass<3;++pass)for(size_t i=0;i<live.size();++i)if(live[i].occupied){
        auto& event=ending[i];unsigned order=event[4]==Submit?2:event[4]==Frame?1:0;if(order!=pass)continue;
        event[7]=event[6]-std::min(event[6],children[i]);event[11]|=Truncated;
        RecordLocked(event,live[i].traits);
    }
    for(auto& entry:live)entry.occupied=false;
    ++generation;
}
inline void Reset(){
    std::lock_guard lock(mutex);level=0;++generation;++monitorGeneration;
    written=dropped=slowCount=slowTriggers=activeDropped=submissionCount=submissionDropped=0;
    seenSubmission=false;frame=revision=inputGeneration=outputGeneration=0;
    for(auto& entry:live)entry.occupied=false;
}
inline void Context(uint64_t f,uint64_t r,uint64_t input=0,uint64_t output=0){
    if(!level.load(std::memory_order_acquire))return;
    std::lock_guard lock(mutex);frame=f;revision=r;inputGeneration=input;outputGeneration=output;
}
inline void Record(Event event){
    if(!level.load(std::memory_order_acquire))return;
    std::lock_guard lock(mutex);if(level.load(std::memory_order_relaxed))RecordLocked(event);
}
inline uint64_t MonitorToken(){return monitorGeneration.load(std::memory_order_acquire);}
inline std::vector<uint64_t> Snapshot(uint64_t expectedGeneration=0){
    // Allocate outside the recording lock. Capacity bounds every possible copy,
    // including scopes that enter while this observer allocates its storage.
    std::vector<uint64_t> result;
    result.reserve(Header+(Capacity+slowEvents.size()+ActiveCapacity)*Width+SubmissionCapacity*6);
    std::lock_guard lock(mutex);
    if(expectedGeneration&&expectedGeneration!=monitorGeneration.load())throw std::runtime_error("Diagnostic monitor generation expired; acquire a monitor from the current session");
    const uint64_t count=std::min<uint64_t>(written,Capacity),retainedSlow=std::min<uint64_t>(slowCount,slowEvents.size());
    const uint64_t retainedSubmissions=std::min<uint64_t>(submissionCount,SubmissionCapacity);
    uint64_t activeCount=0;for(const auto& entry:live)activeCount+=entry.occupied;
    const uint64_t now=activeCount?Now():0;
    result.insert(result.end(),{5,Width,dropped,count,slowTriggers,retainedSlow,slowCount-retainedSlow,
        activeCount,activeDropped,retainedSubmissions,submissionDropped,monitorGeneration.load(),level.load(),written-count+1,written+1});
    for(uint64_t i=written-count;i<written;++i)result.insert(result.end(),events[i%Capacity].begin(),events[i%Capacity].end());
    for(uint64_t i=slowCount-retainedSlow;i<slowCount;++i)result.insert(result.end(),slowEvents[i%slowEvents.size()].begin(),slowEvents[i%slowEvents.size()].end());
    for(const auto& entry:live)if(entry.occupied){auto event=entry.event;event[6]=now-event[5];event[7]=0;event[11]|=Active;
        result.insert(result.end(),event.begin(),event.end());}
    for(uint64_t i=submissionCount-retainedSubmissions;i<submissionCount;++i)result.insert(result.end(),submissions[i%SubmissionCapacity].begin(),submissions[i%SubmissionCapacity].end());
    return result;
}
struct Scope {
    Event event{};uint64_t previousId=0,epoch=0,cpuBegin=UINT64_MAX;int slot=-1,exceptions=0;bool enabled=false;
    Scope(Stage stage,uint64_t items=0,uint64_t tasks=0,unsigned minimum=1,uint64_t batch=0,
            uint64_t resource=0,uint64_t resourceGeneration=0,uint64_t reason=0,uint64_t bytes=0,
            uint64_t sequence=UINT64_MAX,uint64_t rev=UINT64_MAX){
        if(level.load(std::memory_order_acquire)<minimum)return;
        std::lock_guard lock(mutex);if(level.load(std::memory_order_relaxed)<minimum)return;
        for(size_t i=0;i<live.size();++i)if(!live[i].occupied){slot=int(i);break;}
        if(slot<0){++activeDropped;return;}
        enabled=true;epoch=generation.load();exceptions=std::uncaught_exceptions();
        previousId=parentGeneration==epoch&&Find(parentId)?parentId:0;
        const uint64_t id=nextId.fetch_add(1)*ActiveCapacity+uint64_t(slot);
        event={sequence==UINT64_MAX?frame.load():sequence,rev==UINT64_MAX?revision.load():rev,batch?batch:id,ThreadIdentity(),
            uint64_t(stage),Now(),0,0,items,tasks,previousId?Find(previousId)->event[2]:0,0,
            id,previousId,inputGeneration.load(),outputGeneration.load(),UINT64_MAX,UINT64_MAX,
            resource,resourceGeneration,reason,bytes,attempt,uint64_t(level.load()),0};
        cpuBegin=ThreadCpuNow();
        auto traits=Traits(stage);if(stage==Submit&&!seenSubmission){traits|=IsInitial;seenSubmission=true;}
        live[slot]={event,0,traits,true};MarkAncestors(previousId,traits);
        parentId=id;parentGeneration=epoch;
    }
    ~Scope(){
        if(!enabled)return;
        if(epoch!=generation.load(std::memory_order_acquire)){
            if(parentGeneration==epoch&&parentId==event[12]){parentId=0;parentGeneration=generation.load();}
            return;
        }
        std::lock_guard lock(mutex);
        if(epoch==generation.load()&&slot>=0&&live[slot].occupied&&live[slot].event[12]==event[12]){
            event[6]=Now()-event[5];event[7]=event[6]-std::min(event[6],live[slot].children);
            const auto cpuEnd=ThreadCpuNow();if(cpuBegin!=UINT64_MAX&&cpuEnd!=UINT64_MAX&&cpuEnd>=cpuBegin){event[16]=cpuEnd-cpuBegin;event[17]=event[16]-std::min(event[16],live[slot].cpuChildren);}
            if((event[4]==TaskWait||event[4]==PoolWait)&&event[9]){
                live[slot].traits|=HasWait;MarkAncestors(previousId,HasWait);
            }
            if(std::uncaught_exceptions()>exceptions)event[11]|=Failed;
            if(auto* previous=Find(previousId)){previous->children+=event[6];if(event[16]!=UINT64_MAX)previous->cpuChildren+=event[16];}
            RecordLocked(event,live[slot].traits);live[slot].occupied=false;
        }
        if(parentGeneration==epoch&&parentId==event[12]){parentId=Find(previousId)?previousId:0;parentGeneration=generation.load();}
    }
    Scope(const Scope&)=delete;Scope& operator=(const Scope&)=delete;
};
inline thread_local std::optional<Scope> submission;
inline void BeginSubmit(uint64_t sequence,uint64_t rev,uint64_t elapsed){
    if(submission)submission->event[11]|=Cancelled;
    submission.reset();if(!level.load(std::memory_order_acquire))return;
    Context(sequence,rev);++attempt;submission.emplace(Submit);
    if(submission->enabled){std::lock_guard lock(mutex);
        submission->event[5]-=std::min(submission->event[5],elapsed);
        if(submission->epoch==generation.load()&&submission->slot>=0)live[submission->slot].event=submission->event;
    }
}
inline void EndSubmit(bool failed,bool cancelled=false){
    if(!submission)return;
    const uint64_t flags=(failed?Failed:0)|(cancelled?Cancelled:0);
    if(flags){
        submission->event[11]|=flags;
        // Close can switch diagnostics OFF before Java's finally reaches here.
        // Annotate the retained truncated record without collecting new timing.
        if(submission->epoch!=generation.load()){
            std::lock_guard lock(mutex);const uint64_t id=submission->event[12];
            const Event* retained=nullptr;
            for(uint64_t i=written-std::min<uint64_t>(written,Capacity);i<written;++i){auto& event=events[i%Capacity];if(event[12]==id){event[11]|=flags;retained=&event;}}
            for(uint64_t i=slowCount-std::min<uint64_t>(slowCount,slowEvents.size());i<slowCount;++i){auto& event=slowEvents[i%slowEvents.size()];if(event[12]==id)event[11]|=flags;}
            for(uint64_t i=submissionCount-std::min<uint64_t>(submissionCount,SubmissionCapacity);i<submissionCount;++i){auto& sample=submissions[i%SubmissionCapacity];if(sample[5]==id)sample[3]|=flags;}
            if((failed||cancelled)&&retained)RetainTriggerLocked(*retained);
        }
    }
    submission.reset();
}
inline void SubmitStage(Stage stage,uint64_t offset,uint64_t duration,uint64_t items,uint64_t tasks){
    if(!submission||!submission->enabled||!level.load(std::memory_order_acquire))return;
    std::lock_guard lock(mutex);
    if(submission->epoch!=generation.load()||submission->slot<0||!live[submission->slot].occupied)return;
    const auto& outer=submission->event;const auto id=nextId.fetch_add(1)*ActiveCapacity;
    Event event={outer[0],outer[1],id,outer[3],uint64_t(stage),outer[5]+offset,duration,duration,items,tasks,outer[2],0,id,outer[12],outer[14],outer[15],UINT64_MAX,UINT64_MAX,
        0,0,0,0,outer[22],outer[23],0};
    live[submission->slot].children+=duration;RecordLocked(event);
}
}
