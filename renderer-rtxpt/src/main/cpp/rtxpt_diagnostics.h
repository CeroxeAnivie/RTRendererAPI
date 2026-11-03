#pragma once
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <functional>
#include <exception>
#include <mutex>
#include <thread>
#include <vector>

// One bounded recorder for the process-global RTXPT session. No filesystem I/O,
// GPU waits or allocations on the recording path. Times are steady-clock ns.
namespace RtrDiagnostics {
enum Stage : uint64_t { Frame=1, InstanceCache, SubInstances, Dispatch, Caller,
    Worker, TaskWait, MessagePump, ShaderCache, ShaderCompile, ShaderLibrary,
    Pipeline, ShaderTable, Import, Textures, Materials, Acceleration, Retirement,
    SharedPublish, SharedReuseWait, Resize, PoolWait, Lighting, Preparation, Upload, Submit };
constexpr size_t Capacity=8192, Width=12;
using Event=std::array<uint64_t,Width>;
inline std::atomic<unsigned> level{0}; // 0 off, 1 summary, 2 detailed
inline std::atomic<uint64_t> frame{0},revision{0},nextId{1};
inline std::mutex mutex;
inline std::array<Event,Capacity> events{};
inline uint64_t written=0,dropped=0;
inline std::array<Event,2048> slowEvents{};
inline uint64_t slowCount=0,slowTriggers=0;
inline uint64_t Now(){return uint64_t(std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count());}
inline void Configure(unsigned value){level.store(value,std::memory_order_release);}
inline void Reset(){Configure(0);std::lock_guard lock(mutex);written=dropped=slowCount=slowTriggers=0;frame=revision=0;}
inline void Context(uint64_t f,uint64_t r){frame=f;revision=r;}
inline void Record(Event event){
    std::lock_guard lock(mutex);
    events[written%Capacity]=event;++written;if(written>Capacity)++dropped;
    if(event[4]==Frame&&event[6]>50'000'000){
        ++slowTriggers;slowCount=0;
        // Preserve the last slow frame even after the rolling window wraps.
        for(uint64_t i=written-std::min<uint64_t>(written,Capacity);i<written;++i){
            const auto& candidate=events[i%Capacity];
            if(candidate[0]==event[0]&&candidate[5]>=event[5]){
                slowEvents[slowCount%slowEvents.size()]=candidate;++slowCount;
            }
        }
    }
}
inline std::vector<uint64_t> Snapshot(){
    std::lock_guard lock(mutex);
    const uint64_t count=std::min<uint64_t>(written,Capacity);
    const auto retainedSlow=std::min<uint64_t>(slowCount,slowEvents.size());
    std::vector<uint64_t> result;result.reserve(7+size_t(count+retainedSlow)*Width);
    result.insert(result.end(),{2,Width,dropped,count,slowTriggers,retainedSlow,slowCount-retainedSlow});
    for(uint64_t i=written-count;i<written;++i)result.insert(result.end(),events[i%Capacity].begin(),events[i%Capacity].end());
    for(uint64_t i=slowCount-retainedSlow;i<slowCount;++i)result.insert(result.end(),slowEvents[i%slowEvents.size()].begin(),slowEvents[i%slowEvents.size()].end());
    return result;
}
struct Scope;
inline thread_local Scope* parent=nullptr;
struct Scope {
    Event event{};Scope* previous=nullptr;uint64_t children=0;bool enabled=false;int exceptions=0;
    Scope(Stage stage,uint64_t items=0,uint64_t tasks=0,unsigned minimum=1,uint64_t batch=0){
        enabled=level.load(std::memory_order_acquire)>=minimum;if(!enabled)return;
        previous=parent;
        exceptions=std::uncaught_exceptions();
        event={frame.load(),revision.load(),batch?batch:nextId.fetch_add(1),uint64_t(std::hash<std::thread::id>{}(std::this_thread::get_id())),uint64_t(stage),Now(),0,0,items,tasks,previous?previous->event[2]:0,0};
        parent=this;
    }
    ~Scope(){
        if(!enabled)return;
        event[6]=Now()-event[5];event[7]=event[6]-std::min(event[6],children);
        if(std::uncaught_exceptions()>exceptions)event[11]|=2;
        if(previous)previous->children+=event[6];parent=previous;Record(event);
    }
    Scope(const Scope&)=delete;Scope& operator=(const Scope&)=delete;
};
}
