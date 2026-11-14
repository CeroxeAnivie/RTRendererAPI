#pragma once
#include <cstddef>
#include <exception>
#include <donut/engine/ThreadPool.h>

namespace rtr::upstream {
// Resource allocation/preparation stays on its device owner, as in the 5.0.0
// publication lane. Batch data layout is preserved without worker admission,
// extra fences, or frame-local task allocations. Native GPU commands remain ordered.
template<class Work> void RunFrameBatch(donut::engine::ThreadPool*,size_t count,Work&& work){
    std::exception_ptr firstFailure;
    for(size_t i=0;i<count;++i){
        try{work(i);}catch(...){if(!firstFailure)firstFailure=std::current_exception();}
    }
    if(firstFailure)std::rethrow_exception(firstFailure);
}
}
