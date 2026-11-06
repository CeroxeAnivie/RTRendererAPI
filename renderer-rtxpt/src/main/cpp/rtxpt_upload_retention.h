#pragma once
#include <algorithm>
#include <array>
#include <atomic>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <utility>

namespace RtrUploadRetention {
inline constexpr uint64_t WarmBytes=4ull*1024*1024;
inline bool AdaptiveEnabled(){
    static const bool enabled=[] {
#if defined(_MSC_VER)
        char* disabled=nullptr;size_t length=0;
        _dupenv_s(&disabled,&length,"RTR_RTXPT_DISABLE_ADAPTIVE_UPLOAD");
        const bool result=!disabled||std::strcmp(disabled,"1")!=0;
        std::free(disabled);return result;
#else
        const char* disabled=std::getenv("RTR_RTXPT_DISABLE_ADAPTIVE_UPLOAD");
        return !disabled||std::strcmp(disabled,"1")!=0;
#endif
    }();
    return enabled;
}
// A long recording must not allocate hundreds of default 64 KiB committed
// resources. Grow only after exhausting a page in this recording; small work
// stays small, scratch is unchanged and the retained warm budget stays 4 MiB.
inline uint64_t NextChunkSize(uint64_t defaultSize,uint64_t previous,bool exhaustedCurrent){
    if(!exhaustedCurrent)return defaultSize;
    const auto limit=std::max<uint64_t>(defaultSize,1024*1024);
    const auto size=std::max(defaultSize,previous);
    return std::min(limit,size>limit/2?limit:size*2);
}
inline std::atomic<uint64_t> createdBytes{0},releasedBytes{0},retainedBytes{0},peakBytes{0},createdChunks{0},releasedChunks{0},retiredBytes{0},pendingBytes{0};
inline void Created(uint64_t bytes){
    createdBytes.fetch_add(bytes,std::memory_order_relaxed);createdChunks.fetch_add(1,std::memory_order_relaxed);
    auto value=retainedBytes.fetch_add(bytes,std::memory_order_relaxed)+bytes,peak=peakBytes.load(std::memory_order_relaxed);
    while(peak<value&&!peakBytes.compare_exchange_weak(peak,value,std::memory_order_relaxed)){}
}
inline void Released(uint64_t bytes,bool retired){
    if(!bytes)return;releasedBytes.fetch_add(bytes,std::memory_order_relaxed);releasedChunks.fetch_add(1,std::memory_order_relaxed);
    retainedBytes.fetch_sub(bytes,std::memory_order_relaxed);if(retired)pendingBytes.fetch_sub(bytes,std::memory_order_relaxed);
}
template<class Pool,class Owners> void RetireExcess(Pool& pool,Owners& owners){
    uint64_t bytes=0;for(const auto& chunk:pool)bytes+=chunk->uploadAllocationBytes;
    for(auto at=pool.begin();at!=pool.end()&&bytes>WarmBytes;){
        const auto size=(*at)->uploadAllocationBytes;
        owners.push_back(std::move(*at));at=pool.erase(at);bytes-=size;
        owners.back()->uploadFenceRetired=true;
        retiredBytes.fetch_add(size,std::memory_order_relaxed);pendingBytes.fetch_add(size,std::memory_order_relaxed);
    }
}
inline std::array<uint64_t,8> Snapshot(){return {createdBytes.load(),releasedBytes.load(),retainedBytes.load(),peakBytes.load(),createdChunks.load(),releasedChunks.load(),retiredBytes.load(),pendingBytes.load()};}
}
