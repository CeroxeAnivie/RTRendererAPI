#pragma once
#include <algorithm>
#include <exception>
#include <thread>
#include <vector>
#include <donut/engine/ThreadPool.h>

namespace rtr::upstream {
// Frame-local independent work only. Native command recording and ownership
// publication remain on the caller. Always join, including admission failure.
template<class Work> void RunFrameBatch(donut::engine::ThreadPool* workers,size_t count,Work&& work){
    if(!workers||count<4){for(size_t i=0;i<count;++i)work(i);return;}
    static const size_t concurrency=std::min<size_t>(8,std::max(1u,std::thread::hardware_concurrency()));
    const size_t lanes=std::min(concurrency,count);
    std::vector<std::exception_ptr> failures(count);
    const auto run=[&](size_t lane){
        for(size_t i=lane;i<count;i+=lanes){
            try{work(i);}catch(...){failures[i]=std::current_exception();}
        }
    };
    try{for(size_t lane=1;lane<lanes;++lane)workers->AddTask([&,lane]{run(lane);});}
    catch(...){workers->WaitForTasks();throw;}
    run(0);workers->WaitForTasks();
    for(const auto& failure:failures)if(failure)std::rethrow_exception(failure);
}
}
