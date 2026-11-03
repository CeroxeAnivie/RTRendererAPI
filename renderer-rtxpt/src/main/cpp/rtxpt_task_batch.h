#pragma once
#include <donut/engine/ThreadPool.h>
#include "rtxpt_host_resources.h"
#include "rtxpt_diagnostics.h"
#include <algorithm>
#include <condition_variable>
#include <exception>
#include <mutex>

// Each invocation owns its completion predicate and exception. Queued functions
// borrow the caller's closure only until this invocation has drained its batch.
inline uint32_t ParallelForChunkCount(const donut::engine::ThreadPool& pool,size_t count,size_t grain=64){
    if(count==0)return 1;
    return uint32_t(std::min<size_t>(pool.GetThreadCount()+1,std::max<size_t>(1,count/std::max<size_t>(1,grain))));
}
template<class Func> inline void ParallelForChunkedIdx(donut::engine::ThreadPool& pool,size_t count,Func&& func){
    RtrDiagnostics::Scope dispatch(RtrDiagnostics::Dispatch,count);
    const uint32_t chunks=ParallelForChunkCount(pool,count);
    dispatch.event[9]=count?chunks:0;
    if(count==0)return;
    if(chunks==1){RtrDiagnostics::Scope work(RtrDiagnostics::Caller,count,1,2);func(0,count,0u);return;}
    const size_t step=(count+chunks-1)/chunks;
    struct Batch {
        std::mutex mutex;std::condition_variable done;size_t pending=0;std::exception_ptr error;
    } batch;
    auto fail=[&]{std::lock_guard lock(batch.mutex);if(!batch.error)batch.error=std::current_exception();};
    for(uint32_t chunk=1;chunk<chunks;++chunk){
        const size_t first=size_t(chunk)*step,last=std::min(count,first+step);if(first>=last)break;
        {std::lock_guard lock(batch.mutex);++batch.pending;}
        try{pool.AddTask([&,chunk,first,last]{
            {RtrDiagnostics::Scope work(RtrDiagnostics::Worker,last-first,1,2,dispatch.event[2]);
            try{func(first,last,chunk);}catch(...){fail();}}
            {std::lock_guard lock(batch.mutex);--batch.pending;batch.done.notify_one();}
        });}catch(...){fail();std::lock_guard lock(batch.mutex);--batch.pending;break;}
    }
    try{RtrDiagnostics::Scope work(RtrDiagnostics::Caller,std::min(count,step),1,2,dispatch.event[2]);func(0,std::min(count,step),0u);}catch(...){fail();}
    {
        std::unique_lock lock(batch.mutex);
        RtrDiagnostics::Scope wait(RtrDiagnostics::TaskWait,count,batch.pending,1,dispatch.event[2]);
        while(batch.pending){
            lock.unlock();RtrHostResources::PumpEvents();lock.lock();
            // Completion wakes immediately. The timeout only bounds owner event latency.
            batch.done.wait_for(lock,std::chrono::milliseconds(8),[&]{return batch.pending==0;});
        }
    }
    if(batch.error)std::rethrow_exception(batch.error);
}
template<class Func> inline void ParallelForChunked(donut::engine::ThreadPool& pool,size_t count,Func&& func){
    ParallelForChunkedIdx(pool,count,[&](size_t first,size_t last,uint32_t){func(first,last);});
}
