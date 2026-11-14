#pragma once
#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <stdexcept>
#include <utility>
#include <span>

namespace rtr::upstream {
struct MeshInputRetention {
    std::atomic<uint64_t> bytes{0},peakBytes{0},packets{0};
};
// One immutable backing segment per newly authored mesh. Inherited exact
// inputs share this owner; unrelated meshes and texture bytes cannot pin it.
struct MeshInputLease {
    struct Range {const uint8_t* data;size_t size;};
private:
    struct Storage {std::unique_ptr<uint8_t[]> data;size_t size;};
    const Storage storage;
public:
    const std::span<const uint8_t> bytes;
    std::shared_ptr<MeshInputRetention> accounting;
    MeshInputLease(const std::array<Range,5>& ranges,std::shared_ptr<MeshInputRetention> work)
        :storage(Copy(ranges)),bytes(storage.data.get(),storage.size),accounting(std::move(work)){
        const auto current=accounting->bytes.fetch_add(bytes.size(),std::memory_order_relaxed)+bytes.size();
        accounting->packets.fetch_add(1,std::memory_order_relaxed);
        auto peak=accounting->peakBytes.load(std::memory_order_relaxed);
        while(peak<current&&!accounting->peakBytes.compare_exchange_weak(peak,current,std::memory_order_relaxed)){}
    }
    MeshInputLease(const MeshInputLease&)=delete;
    MeshInputLease& operator=(const MeshInputLease&)=delete;
    ~MeshInputLease(){
        accounting->bytes.fetch_sub(bytes.size(),std::memory_order_relaxed);
        accounting->packets.fetch_sub(1,std::memory_order_relaxed);
    }
private:
    static Storage Copy(const std::array<Range,5>& ranges){
        size_t total=0;
        for(const auto& range:ranges){
            if(range.size>std::numeric_limits<size_t>::max()-total)
                throw std::length_error("Retained mesh input size overflow");
            if(range.size&&!range.data)throw std::invalid_argument("Missing retained mesh input range");
            total+=range.size;
        }
        auto result=std::make_unique_for_overwrite<uint8_t[]>(total);size_t offset=0;
        for(const auto& range:ranges){
            if(range.size)std::memcpy(result.get()+offset,range.data,range.size);
            offset+=range.size;
        }
        return {std::move(result),total};
    }
};
}
