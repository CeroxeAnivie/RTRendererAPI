#pragma once
#include <nvrhi/nvrhi.h>
#include <rtxmu/Suballocator.h>
#include <algorithm>
#include <array>
#include <chrono>
#include <map>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <vector>
#include "rtxpt_diagnostics.h"
#include "rtxpt_native_budget.h"

// RTXMU owns allocation, free-range selection and block release. This adapter
// supplies NVRHI heaps and carries a slice lease through its last GPU reader.
class RtrResourceArena {
public:
    static constexpr uint64_t Unit=65536,PageBytes=16*1024*1024,Budget=RtrNativeBudget::Limit;
private:
    struct State;
    struct HeapAllocation {
        nvrhi::HeapHandle heap;
        uint64_t bytes=0;
        ~HeapAllocation(){heap=nullptr;if(bytes)RtrNativeBudget::Release(RtrNativeBudget::Resource,bytes);}
    };
    struct HeapBlock {
        inline static thread_local State* allocator=nullptr;
        std::shared_ptr<HeapAllocation> allocation;
        static void setAllocator(State* value){allocator=value;}
        void allocate(uint64_t bytes,const std::string& name);
        void free(){allocation.reset();}
        uint64_t getVMA(){return reinterpret_cast<uint64_t>(static_cast<nvrhi::IHeap*>(allocation->heap));}
        uint64_t getAlignment(){return Unit;}
    };
    using Pool=rtxmu::Suballocator<State,HeapBlock>;
    struct State {
        std::mutex mutex;
        nvrhi::DeviceHandle device;
        std::map<uint64_t,std::unique_ptr<Pool>> pools;
        bool stopping=false;
        uint64_t liveBytes=0,peakLiveBytes=0,heapCreates=0,heapNanos=0;
        uint64_t reservations=0,deferred=0,capacityRejected=0;
        std::chrono::steady_clock::time_point pendingSince{};
        Pool& GetPool(uint64_t alignment){
            auto& pool=pools[alignment];
            if(!pool)pool=std::make_unique<Pool>(std::max(PageBytes,alignment),alignment,this);
            HeapBlock::setAllocator(this);
            return *pool;
        }
    };
public:
    struct Lease {
        std::shared_ptr<State> state;
        Pool* pool=nullptr;
        Pool::SubAllocation allocation;
        nvrhi::IHeap* Heap() const {return allocation.block.allocation->heap;}
        uint64_t Offset() const {return allocation.offset;}
        uint64_t Capacity() const {return allocation.subBlock->getSize();}
        ~Lease(){if(!state)return;std::lock_guard lock(state->mutex);
            state->liveBytes-=Capacity();pool->release(allocation.subBlock);allocation.block.free();}
    };
    using Storage=std::shared_ptr<Lease>;
private:
    std::shared_ptr<State> state=std::make_shared<State>();
    static void Rollback(std::vector<Storage>& leases){
        for(auto& lease:leases)if(lease->allocation.subBlock){
            lease->pool->release(lease->allocation.subBlock);lease->allocation.subBlock=nullptr;lease->allocation.block.free();}
    }
public:
    RtrResourceArena()=default;
    RtrResourceArena(const RtrResourceArena&)=delete;
    ~RtrResourceArena(){Stop();}
    void Stop(){std::lock_guard lock(state->mutex);state->stopping=true;}
    void Prepare(nvrhi::IDevice* device){
        std::lock_guard lock(state->mutex);
        if(state->stopping)throw std::logic_error("Resource arena is stopped");
        if(state->device&&state->device!=device)throw std::logic_error("Resource arena device cannot change");
        state->device=device;
    }
    bool PreparationReady() const {
        std::lock_guard lock(state->mutex);
        if(state->pendingSince!=std::chrono::steady_clock::time_point{}
            &&std::chrono::steady_clock::now()-state->pendingSince>std::chrono::seconds(30))
            throw std::length_error("Resource capacity did not recover within 30 seconds; reduce resident or transaction capacity");
        return true; // Retry upstream after outstanding readers retire.
    }
    bool Reserve(const std::vector<nvrhi::MemoryRequirements>& requirements,std::vector<Storage>& result){
        if(!result.empty())throw std::logic_error("Resource reservation output must be empty");
        std::vector<Storage> next;next.reserve(requirements.size());uint64_t requested=0;
        for(auto requirement:requirements){
            if(!requirement.size||!requirement.alignment||(requirement.alignment&(requirement.alignment-1)))throw std::invalid_argument("Invalid resource allocation requirements");
            const auto alignment=std::max(Unit,requirement.alignment);
            if(requirement.size>Budget||alignment>Budget)throw std::length_error("Resource transaction exceeds the 2 GiB native resource budget");
            const auto bytes=(requirement.size+alignment-1)/alignment*alignment;
            if(bytes>Budget||requested>Budget-bytes)throw std::length_error("Resource transaction exceeds the 2 GiB native resource budget");
            requested+=bytes;next.push_back(std::make_shared<Lease>());
        }
        std::lock_guard lock(state->mutex);
        if(state->stopping||!state->device)throw std::logic_error("Resource arena is not prepared");
        uint64_t allocated=0;
        try{
            for(size_t index=0;index<requirements.size();++index){
                auto& lease=*next[index];lease.pool=&state->GetPool(std::max(Unit,requirements[index].alignment));
                lease.allocation=lease.pool->allocate(requirements[index].size);allocated+=lease.Capacity();
            }
        }catch(const RtrResourcePending&){
            Rollback(next);++state->deferred;++state->capacityRejected;
            if(state->pendingSince==std::chrono::steady_clock::time_point{})state->pendingSince=std::chrono::steady_clock::now();
            return false;
        }catch(...){Rollback(next);throw;}
        for(auto& lease:next)lease->state=state;
        state->liveBytes+=allocated;state->peakLiveBytes=std::max(state->peakLiveBytes,state->liveBytes);++state->reservations;
        state->pendingSince={};result=std::move(next);return true;
    }
    std::array<uint64_t,11> Snapshot() const {
        std::lock_guard lock(state->mutex);uint64_t heap=0,unused=0,largest=0;
        for(auto& entry:state->pools){const auto stats=entry.second->getStats();heap+=stats.totalResidentMemorySize;unused+=stats.unusedSize;largest=std::max(largest,stats.largestUnusedSize);}
        return {Budget,heap,state->liveBytes,state->peakLiveBytes,unused,largest,state->heapCreates,state->heapNanos,state->reservations,state->deferred,state->capacityRejected};
    }
    uint64_t IdlePageBytes()const{std::lock_guard lock(state->mutex);uint64_t bytes=0;
        for(auto& entry:state->pools)bytes+=entry.second->getStats().idleResidentMemorySize;return bytes;}
};
inline void RtrResourceArena::HeapBlock::allocate(uint64_t bytes,const std::string& name){
    if(!allocator||!allocator->device)throw std::logic_error("Native heap allocator context is missing");
    RtrDiagnostics::Scope scope(RtrDiagnostics::HeapAllocate,1,0,1,0,0,0,RtrDiagnostics::CapacityGrowth,bytes);
    const bool collecting=RtrDiagnostics::level.load()!=0;const auto begin=collecting?RtrDiagnostics::Now():0;
    auto next=std::make_shared<HeapAllocation>();
    RtrNativeBudget::Acquire(RtrNativeBudget::Resource,bytes);next->bytes=bytes;
    nvrhi::HeapDesc desc;desc.capacity=bytes;desc.type=nvrhi::HeapType::DeviceLocal;desc.debugName="Retained native heap "+name;
    next->heap=allocator->device->createHeap(desc);
    if(!next->heap)throw std::runtime_error("NVRHI retained heap allocation failed");
    allocation=std::move(next);++allocator->heapCreates;if(collecting)allocator->heapNanos+=RtrDiagnostics::Now()-begin;
}
