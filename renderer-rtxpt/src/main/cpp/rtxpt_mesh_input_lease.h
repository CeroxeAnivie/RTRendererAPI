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
#include <vector>

namespace rtr::upstream {
// Each block contains one privately serialized geometry generation. Java writes it
// before handoff; a retained mesh owns only this allocation, never its transaction.
struct MeshInputBlock {
    const std::unique_ptr<uint8_t[]> data;const size_t size;
    explicit MeshInputBlock(size_t bytes):data(std::make_unique_for_overwrite<uint8_t[]>(bytes)),size(bytes){
        if(!bytes||bytes>512ull*1024*1024)throw std::invalid_argument("Invalid mesh input block size");
    }
};
using MeshInputBlocks=std::vector<std::shared_ptr<const MeshInputBlock>>;
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
    const std::shared_ptr<const void> generated;
    const std::shared_ptr<const MeshInputBlock> imported;
    const size_t ownedBytes;
    void Register(){
        const auto current=accounting->bytes.fetch_add(ownedBytes,std::memory_order_relaxed)+ownedBytes;
        accounting->packets.fetch_add(1,std::memory_order_relaxed);
        auto peak=accounting->peakBytes.load(std::memory_order_relaxed);
        while(peak<current&&!accounting->peakBytes.compare_exchange_weak(peak,current,std::memory_order_relaxed)){}
    }
public:
    const std::span<const uint8_t> bytes;
    std::shared_ptr<MeshInputRetention> accounting;
    template<size_t N> MeshInputLease(const std::array<Range,N>& ranges,std::shared_ptr<MeshInputRetention> work)
        :storage(Copy(ranges)),ownedBytes(storage.size),bytes(storage.data.get(),storage.size),accounting(std::move(work)){Register();}
    template<size_t N> MeshInputLease(const std::array<Range,N>& ranges,std::shared_ptr<const void> owner,size_t extraBytes,std::shared_ptr<MeshInputRetention> work)
        :storage(Copy(ranges)),generated(std::move(owner)),ownedBytes(storage.size+extraBytes),
        bytes(storage.data.get(),storage.size),accounting(std::move(work)){
        if(!generated||!extraBytes)throw std::invalid_argument("Missing generated mesh channel owner");
        Register();
    }
    // Generated channels already own their final vectors. Retain those exact
    // allocations instead of copying them into a second contiguous segment.
    MeshInputLease(std::shared_ptr<const void> owner,size_t size,std::shared_ptr<MeshInputRetention> work)
        :storage{nullptr,0},generated(std::move(owner)),ownedBytes(size),bytes{},accounting(std::move(work)){
        if(!generated||!size)throw std::invalid_argument("Missing generated mesh input owner");
        Register();
    }
    // The decoder already views the final per-mesh allocation. Counts remain
    // in the block as small bookkeeping bytes; retained accounting includes them.
    MeshInputLease(std::shared_ptr<const MeshInputBlock> block,std::shared_ptr<const void> normals,size_t extraBytes,
            std::shared_ptr<MeshInputRetention> work)
        :storage{nullptr,0},generated(std::move(normals)),imported(std::move(block)),
        ownedBytes((imported?imported->size:0)+extraBytes),
        bytes(imported?imported->data.get():nullptr,imported?imported->size:0),accounting(std::move(work)){
        if(!imported||!imported->size||(extraBytes&&!generated))throw std::invalid_argument("Invalid transferred mesh input owner");
        Register();
    }
    MeshInputLease(const MeshInputLease&)=delete;
    MeshInputLease& operator=(const MeshInputLease&)=delete;
    ~MeshInputLease(){
        accounting->bytes.fetch_sub(ownedBytes,std::memory_order_relaxed);
        accounting->packets.fetch_sub(1,std::memory_order_relaxed);
    }
private:
    static Storage Copy(std::span<const Range> ranges){
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
