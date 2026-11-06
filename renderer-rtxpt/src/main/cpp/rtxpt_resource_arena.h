#pragma once
#include <nvrhi/nvrhi.h>
#include <algorithm>
#include <array>
#include <condition_variable>
#include <chrono>
#include <exception>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <thread>
#include <vector>
#include "rtxpt_diagnostics.h"

class RtrResourcePending : public std::runtime_error {
public: RtrResourcePending():std::runtime_error("GPU resource heap preparation is pending"){}
};

// Only this worker creates/destroys shared heaps. The owner reserves a whole
// transaction before changing the scene. Leases follow the existing two-queue
// retirement payload (and reusable resource pools), never the request's CPU life.
class RtrResourceArena {
public:
    static constexpr uint64_t Unit=65536,PageBytes=16*1024*1024,Budget=2ull*1024*1024*1024;
private:
    struct Page {
        nvrhi::HeapHandle heap;
        std::vector<uint8_t> used;
        uint64_t freeUnits=0;
        uint64_t largest=0;
        void RefreshLargest(){largest=0;uint64_t run=0;for(auto value:used){run=value?0:run+1;largest=std::max(largest,run);}}
    };
    struct State {
        std::mutex mutex;
        std::condition_variable changed;
        std::vector<std::shared_ptr<Page>> pages;
        nvrhi::DeviceHandle device;
        bool stopping=false,forceHeap=false;
        uint64_t wantedFree=2*PageBytes,wantedLargest=0;
        uint64_t heapBytes=0,liveBytes=0,peakLiveBytes=0,heapCreates=0,heapNanos=0;
        uint64_t reservations=0,deferred=0,capacityRejected=0;
        std::chrono::steady_clock::time_point pendingSince{};
        std::exception_ptr failure;
        uint64_t FreeBytes() const {uint64_t value=0;for(auto& p:pages)value+=p->freeUnits*Unit;return value;}
        uint64_t LargestFree() const {uint64_t value=0;for(auto& p:pages)value=std::max(value,p->largest*Unit);return value;}
    };
public:
    struct Lease {
        std::shared_ptr<State> state;
        std::shared_ptr<Page> page;
        uint64_t first=0,count=0;
        nvrhi::IHeap* Heap() const {return page->heap;}
        uint64_t Offset() const {return first*Unit;}
        uint64_t Capacity() const {return count*Unit;}
        ~Lease(){if(!state)return;{
            std::lock_guard lock(state->mutex);
            std::fill_n(page->used.begin()+first,count,uint8_t(0));
            page->freeUnits+=count;page->RefreshLargest();state->liveBytes-=count*Unit;
        }state->changed.notify_one();}
    };
    using Storage=std::shared_ptr<Lease>;
private:
    std::shared_ptr<State> state=std::make_shared<State>();
    std::thread worker;
    void Run(){
        auto s=state;
        for(;;){
            uint64_t bytes=0;
            std::shared_ptr<Page> discarded;
            {
                std::unique_lock lock(s->mutex);
                s->changed.wait(lock,[&]{return s->stopping||s->forceHeap||s->FreeBytes()<s->wantedFree;});
                if(s->stopping)return;
                bytes=std::max(PageBytes,(s->wantedLargest+PageBytes-1)/PageBytes*PageBytes);
                if(s->heapBytes+bytes>Budget){
                    // Fragmentation is observable; drop a completely idle page
                    // on this worker, outside the owner and outside the lock.
                    auto found=std::find_if(s->pages.begin(),s->pages.end(),[](const auto& p){return p->freeUnits==p->used.size();});
                    if(found!=s->pages.end()&&s->forceHeap){discarded=std::move(*found);s->heapBytes-=discarded->used.size()*Unit;s->pages.erase(found);}
                    else{s->forceHeap=false;s->wantedFree=s->FreeBytes();s->changed.wait_for(lock,std::chrono::milliseconds(2));continue;}
                    if(s->heapBytes+bytes>Budget){lock.unlock();discarded.reset();continue;}
                }
                s->forceHeap=false;
            }
            discarded.reset();
            try{
                RtrDiagnostics::Scope allocation(RtrDiagnostics::HeapAllocate,1,0,1,0,0,0,RtrDiagnostics::CapacityGrowth,bytes);
                const auto collecting=RtrDiagnostics::level.load()!=0;
                const auto begin=collecting?RtrDiagnostics::Now():0;
                nvrhi::HeapDesc desc;desc.capacity=bytes;desc.type=nvrhi::HeapType::DeviceLocal;desc.debugName="Retained resource arena";
                auto page=std::make_shared<Page>();page->used.resize(bytes/Unit);page->freeUnits=page->largest=bytes/Unit;
                page->heap=s->device->createHeap(desc);
                if(!page->heap)throw std::runtime_error("Retained resource heap allocation failed");
                std::lock_guard lock(s->mutex);s->pages.push_back(std::move(page));s->heapBytes+=bytes;++s->heapCreates;
                if(collecting)s->heapNanos+=RtrDiagnostics::Now()-begin;
            }catch(...){std::lock_guard lock(s->mutex);s->failure=std::current_exception();return;}
        }
    }
public:
    RtrResourceArena()=default;
    RtrResourceArena(const RtrResourceArena&)=delete;
    ~RtrResourceArena(){Stop();}
    void Stop(){if(worker.joinable()){{std::lock_guard lock(state->mutex);state->stopping=true;}state->changed.notify_one();worker.join();}}
    void Prepare(nvrhi::IDevice* device){
        if(worker.joinable())return;
        {std::lock_guard lock(state->mutex);if(state->stopping)throw std::logic_error("Resource arena is stopped");state->device=device;state->pages.reserve(Budget/PageBytes);}
        worker=std::thread([this]{Run();});
    }
    bool PreparationReady() const {
        std::lock_guard lock(state->mutex);
        if(state->failure)std::rethrow_exception(state->failure);
        if(state->heapBytes==Budget&&state->pendingSince!=std::chrono::steady_clock::time_point{}
            &&std::chrono::steady_clock::now()-state->pendingSince>std::chrono::seconds(30))
            throw std::length_error("Resource arena capacity did not recover within 30 seconds; reduce resident or transaction capacity");
        return state->FreeBytes()>=state->wantedFree&&state->LargestFree()>=state->wantedLargest;
    }
    // Capacity and alignment are allocation requirements, not geometry/refit
    // compatibility. No resource is bound, uploaded, or published on failure.
    bool Reserve(const std::vector<nvrhi::MemoryRequirements>& requirements,std::vector<Storage>& result){
        if(!result.empty())throw std::logic_error("Resource reservation output must be empty");
        std::vector<Storage> next;next.reserve(requirements.size());
        uint64_t total=0,largest=0;
        for(auto requirement:requirements){
            if(!requirement.size||!requirement.alignment||(requirement.alignment&(requirement.alignment-1)))throw std::invalid_argument("Invalid resource allocation requirements");
            const auto bytes=(requirement.size+Unit-1)/Unit*Unit;
            if(requirement.size>Budget||bytes>Budget||total>Budget-bytes)throw std::length_error("Resource transaction exceeds the 2 GiB retained arena budget");
            total+=bytes;largest=std::max(largest,bytes);next.push_back(std::make_shared<Lease>());
        }
        std::lock_guard lock(state->mutex);
        if(state->failure)std::rethrow_exception(state->failure);
        for(size_t index=0;index<requirements.size();++index){
            const auto count=(requirements[index].size+Unit-1)/Unit;
            const auto alignment=std::max(Unit,requirements[index].alignment)/Unit;
            std::shared_ptr<Page> best;uint64_t first=0,bestRun=UINT64_MAX;
            for(auto& p:state->pages){if(p->largest<count)continue;for(uint64_t begin=0;begin<p->used.size();){
                while(begin<p->used.size()&&p->used[begin])++begin;
                uint64_t end=begin;while(end<p->used.size()&&!p->used[end])++end;
                const auto aligned=(begin+alignment-1)/alignment*alignment;
                if(aligned+count<=end&&end-begin<bestRun){best=p;first=aligned;bestRun=end-begin;}
                begin=end+1;
            }}
            if(!best){
                for(auto& lease:next)if(lease->page){std::fill_n(lease->page->used.begin()+lease->first,lease->count,uint8_t(0));lease->page->freeUnits+=lease->count;lease->page->RefreshLargest();}
                ++state->deferred;if(state->heapBytes==Budget)++state->capacityRejected;
                if(state->pendingSince==std::chrono::steady_clock::time_point{})state->pendingSince=std::chrono::steady_clock::now();
                state->wantedFree=std::max(2*PageBytes,total);state->wantedLargest=largest;state->forceHeap=true;state->changed.notify_one();return false;
            }
            auto& lease=next[index];lease->page=best;lease->first=first;lease->count=count;
            std::fill_n(best->used.begin()+first,count,uint8_t(1));best->freeUnits-=count;best->RefreshLargest();
        }
        for(auto& lease:next)lease->state=state;
        state->liveBytes+=total;state->peakLiveBytes=std::max(state->peakLiveBytes,state->liveBytes);++state->reservations;
        state->pendingSince={};state->wantedFree=2*PageBytes;state->wantedLargest=0;state->changed.notify_one();result=std::move(next);return true;
    }
    std::array<uint64_t,11> Snapshot() const {
        std::lock_guard lock(state->mutex);
        return {Budget,state->heapBytes,state->liveBytes,state->peakLiveBytes,state->FreeBytes(),state->LargestFree(),state->heapCreates,state->heapNanos,state->reservations,state->deferred,state->capacityRejected};
    }
    uint64_t IdlePageBytes()const{std::lock_guard lock(state->mutex);uint64_t bytes=0;for(const auto& page:state->pages)if(page->freeUnits==page->used.size())bytes+=page->used.size()*Unit;return bytes;}
};
