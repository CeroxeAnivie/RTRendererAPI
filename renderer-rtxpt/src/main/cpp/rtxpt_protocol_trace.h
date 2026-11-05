#pragma once
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <array>
#include <mutex>
#include <vector>
#include <cstdint>
#include <stdexcept>
#include <algorithm>
#include <Windows.h>
#include <atomic>
#include "rtxpt_diagnostics.h"

// State observations only: no driver calls under this mutex, including snapshots.
namespace RtrProtocolTrace {
inline std::mutex mutex;
inline std::array<std::array<uint64_t,16>,256> records{};
inline uint64_t written=0,token=1;
inline std::atomic<bool> enabled{true};
inline void Reset(){std::lock_guard lock(mutex);++token;written=0;enabled.store(true,std::memory_order_relaxed);}
inline uint64_t Token(){std::lock_guard lock(mutex);return token;}
inline void Record(uint64_t kind,uint64_t adapter,uint64_t queue,uint64_t slot,uint64_t generation,
        uint64_t sequence,uint64_t revision,uint64_t ready,uint64_t done,uint64_t observed,uint64_t flags,uint64_t error=0){
    if(!error&&!enabled.load(std::memory_order_relaxed))return;
    std::lock_guard lock(mutex);auto& row=records[written%records.size()];
    row={++written,kind,adapter,queue,slot,generation,sequence,revision,ready,done,observed,flags,error,
        GetTickCount64(),GetCurrentThreadId(),RtrDiagnostics::level.load(std::memory_order_relaxed)};
}
inline std::vector<uint64_t> Snapshot(uint64_t expected){
    std::vector<uint64_t> result;result.reserve(4+records.size()*16);
    std::lock_guard lock(mutex);if(expected!=token)throw std::runtime_error("Protocol monitor session expired");
    const auto count=std::min<uint64_t>(written,records.size());result.insert(result.end(),{token,written-count,count,16});
    for(uint64_t i=written-count;i<written;++i)result.insert(result.end(),records[i%records.size()].begin(),records[i%records.size()].end());return result;
}
}
