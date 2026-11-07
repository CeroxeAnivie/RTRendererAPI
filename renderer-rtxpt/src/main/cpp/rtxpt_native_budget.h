#pragma once
#include <array>
#include <atomic>
#include <cstdint>
#include <stdexcept>

class RtrResourcePending : public std::runtime_error {
public: explicit RtrResourcePending(const char* reason="GPU resource heap preparation is pending"):std::runtime_error(reason){}
};

// Admission accounting only. Native NVRHI/RTXMU remain the sole allocators.
// Resource blocks share the pre-existing retained-resource cap; upload blocks
// retain the pre-existing upload cap across all command lists and sessions.
namespace RtrNativeBudget {
enum Domain : unsigned {Resource,Upload};
inline constexpr uint64_t Limit=2ull*1024*1024*1024;
inline std::array<std::atomic<uint64_t>,2> current{},peak{},rejected{};
inline void Acquire(Domain domain,uint64_t bytes){
    if(bytes>Limit)throw std::length_error("Native allocation exceeds its 2 GiB domain budget");
    auto value=current[domain].load(std::memory_order_relaxed);
    do{if(value>Limit-bytes){rejected[domain].fetch_add(1,std::memory_order_relaxed);
        throw RtrResourcePending("Native resource allocation budget is temporarily full");}}
    while(!current[domain].compare_exchange_weak(value,value+bytes,std::memory_order_relaxed));
    const auto allocated=value+bytes;auto high=peak[domain].load(std::memory_order_relaxed);
    while(high<allocated&&!peak[domain].compare_exchange_weak(high,allocated,std::memory_order_relaxed)){}
}
inline void Release(Domain domain,uint64_t bytes){current[domain].fetch_sub(bytes,std::memory_order_relaxed);}
inline std::array<uint64_t,6> Snapshot(){return {current[Resource].load(),peak[Resource].load(),rejected[Resource].load(),
    current[Upload].load(),peak[Upload].load(),rejected[Upload].load()};}
}
