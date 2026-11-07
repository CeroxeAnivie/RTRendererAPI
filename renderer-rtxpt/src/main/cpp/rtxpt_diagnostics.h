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
    RuntimeInitialize, DeviceInitialize, SceneInitialize, LightingInitialize, PoolAcquire, TlasAllocate,
    HeapAllocate, ResourcePrebuild, AllocationInfo, PlacedBind, PoolEvict, StagingAcquire, StagingAllocate, StagingMap, StagingCopy, StagingBarrier, StagingRecord, SparseRegions, SceneGraphRefresh, SceneFullRefresh, SceneTopologyFallback, SceneMeshPreparation, SceneMaterialRows, SceneGeometryRows, SceneInstanceRows, SceneTableUpload, SparseQueryPreparation, SparseRegionInitialize, SparseHashErase, SparseHashRecover, SparseRegionPublish, SparseUpload,
    DefaultBufferAllocate, DefaultBufferCommitted, DefaultBufferPostCreate, SceneCpuResize, SceneMaterialPrepare, SceneOmmDebugRefresh, DiagnosticMutexWait, DiagnosticMutexHold, TlasBuild, MeshBufferUpload };
enum Flag : uint64_t { QueueWaitEnqueue=1, Failed=2, CacheHit=4, CacheMiss=8,
    Active=256, Truncated=512, Cancelled=1024, EnteredNativeFrame=2048 };
enum SubmissionKind : uint64_t { Initial=0, Update, Steady, Resized, Waited, KindCount };
constexpr size_t Capacity=8192, Width=27, Header=19, ActiveCapacity=512, SubmissionCapacity=1024;
constexpr size_t CaptureCapacity=65536, CaptureSlots=2, SlowGroupCapacity=4, SlowDescriptorWidth=10;
using Event=std::array<uint64_t,Width>;
using SubmissionSample=std::array<uint64_t,6>;
enum Trait : uint64_t { HasFrame=1, HasUpdate=2, HasResize=4, HasWait=8, IsInitial=16 };
// Bit mask shared by planned and executed resource events. Generation is the
// immutable transaction revision; identity hashes the stable native resource ID.
enum ResourceReason : uint64_t { Inflow=1, Deformation=2, Topology=4, BuildFlags=8,
    Pose=16, Coverage=32, Material=64, Removal=128, Texture=256,
    PoolHit=512, CapacityGrowth=1024, RefitRefresh=2048, IndexMigration=4096, Attributes=8192, MappedBatch=16384 };
struct Live { Event event{};uint64_t children=0,traits=0;bool occupied=false;uint64_t cpuChildren=0; };
inline std::atomic<unsigned> level{0};
inline std::atomic<uint64_t> frame{0},revision{0},inputGeneration{0},outputGeneration{0},nextId{1},generation{1},monitorGeneration{1};
inline std::mutex mutex;
inline std::array<Event,Capacity> events{};
inline uint64_t written=0,dropped=0;
struct Capture {std::array<Event,CaptureCapacity> events;uint64_t root=0,count=0,dropped=0;};
struct SlowGroup {std::array<Event,CaptureCapacity> events;std::array<uint64_t,SlowDescriptorWidth> description{};};
inline std::array<Capture,CaptureSlots> captures{};
inline std::array<SlowGroup,SlowGroupCapacity> slowGroups{};
inline uint64_t slowCount=0,slowTriggers=0,slowGroupOverwrites=0,slowEventOverwrites=0;
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
inline void (*snapshotChunkHook)()=nullptr;
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
inline void RecordLocked(Event event,uint64_t traits=0);
// Lock samples are overlapping attribution records, not extra work to add to
// enclosing scopes. Never recurse through Scope while holding this mutex.
enum LockOperation : uint64_t { ScopeEnter=1,ScopeLeave,RecordEvent,SetContext,SnapshotMetadata,SnapshotRing,SnapshotSlow,SnapshotFinish };
struct DiagnosticLock {
    std::unique_lock<std::mutex> guard{mutex,std::defer_lock};
    uint64_t operation=0,bytes=0,subject=0,waitBegin=0,waitNanos=0,holdBegin=0,epoch=0;
    Event context{};
    DiagnosticLock(uint64_t op,uint64_t copiedBytes=0,bool measureHold=false,uint64_t stage=0):operation(op),bytes(copiedBytes),subject(stage){
        const bool enabled=level.load(std::memory_order_relaxed)!=0;
        if(!guard.try_lock()){
            if(enabled)waitBegin=Now();
            guard.lock();
            if(waitBegin&&level.load(std::memory_order_relaxed))waitNanos=Now()-waitBegin;
        }
        if(!measureHold&&waitNanos<100'000)return;
        if(!level.load(std::memory_order_relaxed))return;
        epoch=generation.load();if(measureHold)holdBegin=Now();
        context[0]=frame.load();context[1]=revision.load();context[3]=ThreadIdentity();
        context[14]=inputGeneration.load();context[15]=outputGeneration.load();
        context[16]=context[17]=UINT64_MAX;context[19]=epoch;context[20]=operation;
        context[21]=bytes;context[22]=attempt;context[23]=level.load();context[8]=subject;
        if(parentGeneration==epoch)if(auto* parent=Find(parentId)){
            context[0]=parent->event[0];context[1]=parent->event[1];
            context[25]=parent->event[25];context[26]=parent->event[26];
        }
    }
    ~DiagnosticLock(){
        if(!epoch||!level.load(std::memory_order_relaxed)||epoch!=generation.load())return;
        const uint64_t holdNanos=holdBegin?Now()-holdBegin:0;
        auto record=[&](Stage stage,uint64_t start,uint64_t nanos){
            if(nanos<100'000)return;
            auto event=context;event[2]=event[12]=nextId.fetch_add(1)*ActiveCapacity+ActiveCapacity-1;
            event[4]=stage;event[5]=start;event[6]=event[7]=nanos;RecordLocked(event);
        };
        record(DiagnosticMutexWait,waitBegin,waitNanos);
        record(DiagnosticMutexHold,holdBegin,holdNanos);
    }
};
inline void RetainTriggerLocked(const Event& event){
    auto& retained=slowGroups[slowTriggers%SlowGroupCapacity];
    if(slowTriggers>=SlowGroupCapacity){++slowGroupOverwrites;slowEventOverwrites+=retained.description[7];}
    ++slowTriggers;uint64_t count=0,lost=0;bool found=false;
    for(const auto& capture:captures)if(capture.root&&capture.root==event[25]){
        found=true;lost=capture.dropped;
        for(uint64_t i=0;i<capture.count;++i){const auto& candidate=capture.events[i];
            if(event[4]==Submit||candidate[26]==event[12])retained.events[count++]=candidate;
        }
        break;
    }
    // Retain the trigger even when the bounded capture was exhausted. The
    // descriptor reports missing children explicitly; never present a tail as
    // a complete tree or mix unrelated attempts sharing sequence/revision.
    if(!count||retained.events[count-1][12]!=event[12]){
        if(count==CaptureCapacity){--count;++lost;}retained.events[count++]=event;
    }
    if(!found)++lost;
    retained.description={slowTriggers,event[0],event[1],event[12],event[4],event[11]|(lost?uint64_t(Truncated):0),count+lost,count,lost,0};
    slowCount=count;
}
inline void RecordLocked(Event event,uint64_t traits){
    if(event[4]==Submit&&(traits&HasFrame))event[11]|=EnteredNativeFrame;
    event[24]=written+1;
    for(auto& capture:captures)if(capture.root&&capture.root==event[25]){
        if(capture.count<CaptureCapacity)capture.events[capture.count++]=event;else ++capture.dropped;break;
    }
    if(event[4]==Submit){
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
    if(event[12]==event[25])for(auto& capture:captures)if(capture.root==event[12]){capture.root=0;break;}
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
    written=dropped=slowCount=slowTriggers=slowGroupOverwrites=slowEventOverwrites=activeDropped=submissionCount=submissionDropped=0;
    for(auto& capture:captures)capture.root=capture.count=capture.dropped=0;
    seenSubmission=false;frame=revision=inputGeneration=outputGeneration=0;
    for(auto& entry:live)entry.occupied=false;
}
inline void Context(uint64_t f,uint64_t r,uint64_t input=0,uint64_t output=0){
    if(!level.load(std::memory_order_acquire))return;
    DiagnosticLock lock(SetContext);frame=f;revision=r;inputGeneration=input;outputGeneration=output;
}
inline void Record(Event event){
    if(!level.load(std::memory_order_acquire))return;
    DiagnosticLock lock(RecordEvent,0,true,event[4]);if(level.load(std::memory_order_relaxed))RecordLocked(event);
}
inline uint64_t MonitorToken(){return monitorGeneration.load(std::memory_order_acquire);}
inline void SnapshotCopyYield(){
#ifdef RTR_DIAGNOSTICS_SELFTEST
    if(snapshotChunkHook)snapshotChunkHook();
#endif
    std::this_thread::yield();
}
inline std::vector<uint64_t> Snapshot(uint64_t expectedGeneration=0,uint64_t afterTrigger=0,uint64_t nextEvent=0,uint64_t nextSubmission=0){
    // Allocate outside the recorder lock. Copy ring/slow data in bounded slices,
    // validating their immutable ordinals after each reacquisition. A concurrent
    // overwrite drops an explicit prefix; never splice different trigger trees.
    constexpr size_t CopyChunk=64;
    const bool incremental=nextEvent||nextSubmission;
    const size_t headerWords=Header+(incremental?2:0);
    std::vector<uint64_t> result;
    result.resize(headerWords);
    std::array<Event,ActiveCapacity> active;size_t activeCount=0;
    std::array<SubmissionSample,SubmissionCapacity> samples;size_t sampleCount=0;
    std::array<std::array<uint64_t,SlowDescriptorWidth>,SlowGroupCapacity> descriptions{};
    std::array<uint64_t,Header+2> header{};
    uint64_t session=0,cut=0,first=0,groupCount=0;
    {
        DiagnosticLock lock(SnapshotMetadata,0,true);
        session=monitorGeneration.load();
        if(expectedGeneration&&expectedGeneration!=session)throw std::runtime_error("Diagnostic monitor generation expired; acquire a monitor from the current session");
        cut=written;first=cut-std::min<uint64_t>(cut,Capacity)+1;
        if(incremental)first=std::max(first,std::min(nextEvent,cut+1));
        const auto firstGroup=std::max(slowTriggers-std::min<uint64_t>(slowTriggers,SlowGroupCapacity),std::min(afterTrigger,slowTriggers));
        for(uint64_t i=firstGroup;i<slowTriggers;++i)descriptions[groupCount++]=slowGroups[i%SlowGroupCapacity].description;
        const uint64_t firstSubmission=std::max(submissionCount-std::min<uint64_t>(submissionCount,SubmissionCapacity)+1,
            incremental?std::min(nextSubmission,submissionCount+1):uint64_t(1));
        const uint64_t retainedSubmissions=submissionCount+1-firstSubmission;
        const bool anyActive=std::any_of(live.begin(),live.end(),[](const auto& entry){return entry.occupied;});
        const uint64_t now=anyActive?Now():0;
        for(const auto& entry:live)if(entry.occupied){auto event=entry.event;event[6]=now-event[5];event[7]=0;event[11]|=Active;active[activeCount++]=event;}
        for(uint64_t i=firstSubmission-1;i<submissionCount;++i)samples[sampleCount++]=submissions[i%SubmissionCapacity];
        header={incremental?uint64_t(7):uint64_t(6),Width,dropped,0,slowTriggers,0,slowEventOverwrites,uint64_t(activeCount),activeDropped,
            retainedSubmissions,submissionDropped,session,level.load(),first,cut+1,0,slowGroupOverwrites,slowEventOverwrites,CaptureCapacity,firstSubmission,submissionCount+1};
    }
    // The incremental cursor usually consumes every slow group. Reserve only
    // this snapshot's captured upper bound, outside the recorder lock. Any
    // concurrent overwrite can only reduce the number of copied events.
    size_t words=headerWords+size_t(cut+1-first+activeCount)*Width+sampleCount*6+size_t(groupCount)*SlowDescriptorWidth;
    for(uint64_t group=0;group<groupCount;++group)words+=size_t(descriptions[group][7])*Width;
    result.reserve(words);
    auto checkSession=[&]{if(session!=monitorGeneration.load())throw std::runtime_error("Diagnostic session changed during snapshot; reacquire the monitor");};
    uint64_t cursor=first;
    while(cursor<=cut){
        {
        DiagnosticLock lock(SnapshotRing,CopyChunk*sizeof(Event),true);checkSession();
        const uint64_t oldest=written-std::min<uint64_t>(written,Capacity)+1;
        if(cursor<oldest){cursor=std::min(oldest,cut+1);first=cursor;result.resize(headerWords);}
        const uint64_t end=std::min(cut+1,cursor+CopyChunk);
        for(;cursor<end;++cursor){const auto& event=events[(cursor-1)%Capacity];result.insert(result.end(),event.begin(),event.end());}
        }
        SnapshotCopyYield();
    }
    header[3]=cut+1-first;header[13]=first;
    const size_t slowStart=result.size();
    std::array<std::array<uint64_t,SlowDescriptorWidth>,SlowGroupCapacity> retained{};
    uint64_t retainedGroups=0,retainedEvents=0;
    for(uint64_t group=0;group<groupCount;++group){
        auto description=descriptions[group];const uint64_t trigger=description[0];bool valid=true;
        for(uint64_t offset=0;offset<description[7];offset+=CopyChunk){
            {
            DiagnosticLock lock(SnapshotSlow,CopyChunk*sizeof(Event),true);checkSession();
            const auto& source=slowGroups[(trigger-1)%SlowGroupCapacity];
            if(source.description[0]!=trigger){valid=false;break;}
            const uint64_t end=std::min(description[7],offset+CopyChunk);
            for(uint64_t i=offset;i<end;++i)result.insert(result.end(),source.events[i].begin(),source.events[i].end());
            }
            SnapshotCopyYield();
        }
        if(!valid){result.resize(slowStart);retainedGroups=retainedEvents=0;continue;}
        description[9]=retainedEvents;retainedEvents+=description[7];retained[retainedGroups++]=description;
    }
    for(size_t i=0;i<activeCount;++i)result.insert(result.end(),active[i].begin(),active[i].end());
    for(size_t i=0;i<sampleCount;++i)result.insert(result.end(),samples[i].begin(),samples[i].end());
    for(uint64_t i=0;i<retainedGroups;++i)result.insert(result.end(),retained[i].begin(),retained[i].end());
    {
        DiagnosticLock lock(SnapshotFinish,0,true);checkSession();
        header[2]=dropped;
        header[6]=header[17]=slowEventOverwrites;header[16]=slowGroupOverwrites;
    }
    header[5]=retainedEvents;header[15]=retainedGroups;
    std::copy_n(header.begin(),headerWords,result.begin());return result;
}

struct Scope {
    Event event{};uint64_t previousId=0,epoch=0,cpuBegin=UINT64_MAX;int slot=-1,exceptions=0;bool enabled=false;
    Scope(Stage stage,uint64_t items=0,uint64_t tasks=0,unsigned minimum=1,uint64_t batch=0,
            uint64_t resource=0,uint64_t resourceGeneration=0,uint64_t reason=0,uint64_t bytes=0,
            uint64_t sequence=UINT64_MAX,uint64_t rev=UINT64_MAX){
        if(level.load(std::memory_order_acquire)<minimum)return;
        DiagnosticLock lock(ScopeEnter,0,false,stage);if(level.load(std::memory_order_relaxed)<minimum)return;
        for(size_t i=0;i<live.size();++i)if(!live[i].occupied){slot=int(i);break;}
        if(slot<0){++activeDropped;return;}
        enabled=true;epoch=generation.load();exceptions=std::uncaught_exceptions();
        previousId=parentGeneration==epoch&&Find(parentId)?parentId:0;
        const uint64_t id=nextId.fetch_add(1)*ActiveCapacity+uint64_t(slot);
        event={sequence==UINT64_MAX?frame.load():sequence,rev==UINT64_MAX?revision.load():rev,batch?batch:id,ThreadIdentity(),
            uint64_t(stage),Now(),0,0,items,tasks,previousId?Find(previousId)->event[2]:0,0,
            id,previousId,inputGeneration.load(),outputGeneration.load(),UINT64_MAX,UINT64_MAX,
            resource,resourceGeneration,reason,bytes,attempt,uint64_t(level.load()),0,0,0};
        const auto* ancestor=Find(previousId?previousId:batch);
        event[25]=ancestor?ancestor->event[25]:0;event[26]=stage==Frame?id:ancestor?ancestor->event[26]:0;
        if(ancestor){if(sequence==UINT64_MAX)event[0]=ancestor->event[0];if(rev==UINT64_MAX)event[1]=ancestor->event[1];}
        if(stage==Submit||(!event[25]&&stage==Frame)){
            event[25]=id;for(auto& capture:captures)if(!capture.root){capture.root=id;capture.count=capture.dropped=0;break;}
        }
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
        DiagnosticLock lock(ScopeLeave,0,event[4]==Submit||event[4]==Frame,event[4]);
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
            for(uint64_t i=slowTriggers-std::min<uint64_t>(slowTriggers,SlowGroupCapacity);i<slowTriggers;++i){auto& group=slowGroups[i%SlowGroupCapacity];
                for(uint64_t j=0;j<group.description[7];++j)if(group.events[j][12]==id)group.events[j][11]|=flags;}
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
        0,0,0,0,outer[22],outer[23],0,outer[12],0};
    live[submission->slot].children+=duration;RecordLocked(event);
}
}
