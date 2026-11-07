#pragma once
#include <array>
#include <atomic>
#include <cstdint>

namespace RtrUploadStatistics {
inline std::atomic<uint64_t> createdBytes{0},releasedBytes{0},retainedBytes{0},peakBytes{0},createdChunks{0},releasedChunks{0},retiredBytes{0},pendingBytes{0};
inline void Created(uint64_t bytes){
    createdBytes.fetch_add(bytes,std::memory_order_relaxed);createdChunks.fetch_add(1,std::memory_order_relaxed);
    auto value=retainedBytes.fetch_add(bytes,std::memory_order_relaxed)+bytes,peak=peakBytes.load(std::memory_order_relaxed);
    while(peak<value&&!peakBytes.compare_exchange_weak(peak,value,std::memory_order_relaxed)){}
}
inline void Released(uint64_t bytes){
    if(!bytes)return;releasedBytes.fetch_add(bytes,std::memory_order_relaxed);releasedChunks.fetch_add(1,std::memory_order_relaxed);
    retainedBytes.fetch_sub(bytes,std::memory_order_relaxed);
}
inline std::array<uint64_t,8> Snapshot(){return {createdBytes.load(),releasedBytes.load(),retainedBytes.load(),peakBytes.load(),createdChunks.load(),releasedChunks.load(),retiredBytes.load(),pendingBytes.load()};}
}
