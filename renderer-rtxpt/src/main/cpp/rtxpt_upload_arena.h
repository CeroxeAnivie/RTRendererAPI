#pragma once
#include <nvrhi/nvrhi.h>
#include <algorithm>
#include <array>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>
#include "rtxpt_resource_arena.h"

// Session-owned upload storage. Creation and the one persistent Map happen on
// the worker. Reservations precede scene mutation and never wait for a GPU fence.
// The complete transaction lease is retained until its copy readers complete.
class RtrUploadArena {
    static constexpr uint64_t Unit=4096,PageBytes=2*1024*1024,WarmBytes=4*1024*1024,Budget=2ull*1024*1024*1024;
    struct Page {
        nvrhi::DeviceHandle device;
        nvrhi::BufferHandle buffer;
        uint8_t* data=nullptr;
        std::vector<uint8_t> used;
        uint64_t free=0,largest=0;
        void Refresh(){largest=0;uint64_t run=0;for(auto value:used){run=value?0:run+1;largest=std::max(largest,run);}}
        ~Page(){if(data)device->unmapBuffer(buffer);}
    };
    struct State {
        std::mutex mutex;std::condition_variable changed;
        nvrhi::DeviceHandle device;
        std::vector<std::shared_ptr<Page>> pages;
        uint64_t allocated=0,wanted=PageBytes,peakAllocated=0,leased=0,peakLeased=0;
        bool stopping=false;
        std::exception_ptr failure;
        std::chrono::steady_clock::time_point pending{};
    };
public:
    struct Lease {
        std::shared_ptr<State> state;std::shared_ptr<Page> page;
        uint64_t first=0,count=0;
        uint8_t* Data()const{return page->data+first*Unit;}
        nvrhi::IBuffer* Buffer()const{return page->buffer;}
        uint64_t Offset()const{return first*Unit;}
        uint64_t Capacity()const{return count*Unit;}
        ~Lease(){if(!state)return;std::vector<std::shared_ptr<Page>> discarded;{
            std::lock_guard lock(state->mutex);
            std::fill_n(page->used.begin()+first,count,uint8_t(0));page->free+=count;page->Refresh();state->leased-=count*Unit;
            uint64_t idle=0;for(const auto& item:state->pages)if(item->free==item->used.size())idle+=item->used.size()*Unit;
            for(auto at=state->pages.begin();at!=state->pages.end()&&idle>WarmBytes;){
                if((*at)->free!=(*at)->used.size()){++at;continue;}
                const auto bytes=(*at)->used.size()*Unit;discarded.push_back(std::move(*at));at=state->pages.erase(at);idle-=bytes;state->allocated-=bytes;
            }
        }state->changed.notify_one();}
    };
    using Storage=std::shared_ptr<Lease>;
private:
    std::shared_ptr<State> state=std::make_shared<State>();
    std::thread worker;
    static bool Ready(const State& s){for(auto& page:s.pages)if(page->largest*Unit>=s.wanted)return true;return false;}
    void Run(){auto s=state;for(;;){
        uint64_t bytes=0;std::shared_ptr<Page> discarded;
        {
            std::unique_lock lock(s->mutex);
            s->changed.wait(lock,[&]{return s->stopping||!Ready(*s);});if(s->stopping)return;
            bytes=std::max(PageBytes,(s->wanted+PageBytes-1)/PageBytes*PageBytes);
            if(s->allocated+bytes>Budget){
                auto idle=std::find_if(s->pages.begin(),s->pages.end(),[](const auto& page){return page->free==page->used.size();});
                if(idle!=s->pages.end()){discarded=std::move(*idle);s->allocated-=discarded->used.size()*Unit;s->pages.erase(idle);}
                else{s->changed.wait_for(lock,std::chrono::milliseconds(2));continue;}
                if(s->allocated+bytes>Budget){lock.unlock();discarded.reset();continue;}
            }
        }
        discarded.reset();
        try{
            RtrDiagnostics::Scope allocation(RtrDiagnostics::StagingAllocate,1,1,2,0,0,0,RtrDiagnostics::CapacityGrowth|RtrDiagnostics::MappedBatch,bytes);
            auto page=std::make_shared<Page>();page->device=s->device;
            page->used.resize(bytes/Unit);page->free=page->largest=bytes/Unit;
            nvrhi::BufferDesc desc;desc.byteSize=bytes;desc.cpuAccess=nvrhi::CpuAccessMode::Write;
            desc.initialState=nvrhi::ResourceStates::CopySource;desc.keepInitialState=true;desc.debugName="Persistent transaction upload page";
            page->buffer=s->device->createBuffer(desc);if(!page->buffer)throw std::runtime_error("Transaction upload page creation failed");
            {RtrDiagnostics::Scope map(RtrDiagnostics::StagingMap,1,1,2,0,0,0,0,bytes);
                page->data=static_cast<uint8_t*>(s->device->mapBuffer(page->buffer,nvrhi::CpuAccessMode::Write));}
            if(!page->data)throw std::runtime_error("Transaction upload page mapping failed");
            {std::lock_guard lock(s->mutex);s->pages.push_back(std::move(page));s->allocated+=bytes;s->peakAllocated=std::max(s->peakAllocated,s->allocated);}
        }catch(...){std::lock_guard lock(s->mutex);s->failure=std::current_exception();return;}
    }}
public:
    RtrUploadArena()=default;RtrUploadArena(const RtrUploadArena&)=delete;
    std::array<uint64_t,4> Statistics()const{std::lock_guard lock(state->mutex);return {state->allocated,state->peakAllocated,state->leased,state->peakLeased};}
    uint64_t IdlePageBytes()const{std::lock_guard lock(state->mutex);uint64_t bytes=0;for(const auto& page:state->pages)if(page->free==page->used.size())bytes+=page->used.size()*Unit;return bytes;}
    ~RtrUploadArena(){Stop();}
    void Prepare(nvrhi::IDevice* device){
        if(worker.joinable())return;
        {std::lock_guard lock(state->mutex);if(state->stopping)throw std::logic_error("Upload arena is stopped");state->device=device;}
        worker=std::thread([this]{Run();});
    }
    void Stop(){if(worker.joinable()){{std::lock_guard lock(state->mutex);state->stopping=true;}state->changed.notify_one();worker.join();}}
    bool PreparationReady()const{
        std::lock_guard lock(state->mutex);if(state->failure)std::rethrow_exception(state->failure);
        if(state->pending!=std::chrono::steady_clock::time_point{}&&std::chrono::steady_clock::now()-state->pending>std::chrono::seconds(30))
            throw std::length_error("Transaction upload capacity did not recover within 30 seconds");
        return Ready(*state);
    }
    bool Reserve(uint64_t bytes,Storage& result){
        if(result)throw std::logic_error("Upload reservation output must be empty");if(!bytes)return true;
        if(bytes>Budget)throw std::length_error("Transaction exceeds 2 GiB upload capacity");
        const auto count=(bytes+Unit-1)/Unit;
        auto next=std::make_shared<Lease>();
        std::lock_guard lock(state->mutex);if(state->failure)std::rethrow_exception(state->failure);
        for(auto& page:state->pages){if(page->largest<count)continue;
            uint64_t run=0;for(uint64_t i=0;i<page->used.size();++i){run=page->used[i]?0:run+1;if(run<count)continue;
                const auto first=i+1-count;std::fill_n(page->used.begin()+first,count,uint8_t(1));page->free-=count;page->Refresh();
                state->leased+=count*Unit;state->peakLeased=std::max(state->peakLeased,state->leased);
                next->state=state;next->page=page;next->first=first;next->count=count;result=std::move(next);
                state->wanted=PageBytes;state->pending={};state->changed.notify_one();return true;
            }
        }
        state->wanted=count*Unit;if(state->pending==std::chrono::steady_clock::time_point{})state->pending=std::chrono::steady_clock::now();
        state->changed.notify_one();return false;
    }
};
