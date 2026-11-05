#include "rtxpt_fence.h"
#include "rtxpt_resource_reclaimer.h"
#include <iostream>
#include <atomic>
#include <stdexcept>
#include <future>
#include <chrono>
static void require(bool value,const char* message){if(!value)throw std::runtime_error(message);}
int main(){
    RtrDeviceFailure.store(0);RtrProtocolTrace::Reset();
    bool reasonRead=false;auto completed=RtrFenceObservation(7,[&]{reasonRead=true;return E_FAIL;});
    require(completed==7&&!reasonRead,"Normal fence observation must not call device-fault lookup");
    uint64_t accepted=7;bool rejected=false;
    try{accepted=RtrFenceObservation(UINT64_MAX,[]{return DXGI_ERROR_DEVICE_HUNG;});}catch(const std::exception&){rejected=true;}
    require(rejected&&accepted==7,"Device error cannot advance the completion identity");
    auto first=RtrDeviceFailure.load();RtrLatchDeviceFailure(E_FAIL);require(first==RtrDeviceFailure.load(),"Preserve first failure");
    rejected=false;try{RtrRequireHealthy();}catch(const std::exception&){rejected=true;}require(rejected,"Failed session refuses more publication");
    auto token=RtrProtocolTrace::Token();auto snapshot=RtrProtocolTrace::Snapshot(token);require(snapshot[2]==1&&snapshot[5]==4,"Fault remains independently observable");
    RtrProtocolTrace::enabled=false;RtrProtocolTrace::Record(2,0,0,0,0,0,0,0,0,0,0);require(RtrProtocolTrace::Snapshot(token)[2]==1,"OFF records no diagnostic events");
    RtrProtocolTrace::Reset();rejected=false;try{RtrProtocolTrace::Snapshot(token);}catch(const std::exception&){rejected=true;}require(rejected,"Replacement session expires an old observer");
    std::atomic<unsigned> destroyed{0};
    struct Item{std::atomic<unsigned>* value;explicit Item(std::atomic<unsigned>* v):value(v){}~Item(){++*value;}};
    {RtrResourceReclaimer release;for(unsigned i=0;i<16;i++){auto object=std::make_shared<Item>(&destroyed);require(release.Enqueue(object),"Bounded release queue accepts available capacity");}
        require(release.pending.load()<=RtrResourceReclaimer::Capacity,"Release backlog remains bounded");}
    require(destroyed==16,"Close joins and drains every accepted release");
    // Hold one actual destructor while saturating every fixed slot. Rejection
    // must preserve ownership and cannot wait for the driver's release thread.
    RtrDiagnostics::Reset();RtrDiagnostics::Configure(1);
    std::promise<void> entered,unblock;auto gate=unblock.get_future().share();
    std::atomic<unsigned> workerDestroyed{0},wrongThread{0};const auto owner=std::this_thread::get_id();
    struct Held {
        std::promise<void>* entered=nullptr;std::shared_future<void> gate;
        std::atomic<unsigned>* count=nullptr;std::atomic<unsigned>* wrong=nullptr;std::thread::id owner;
        Held()=default;Held(Held&& other)noexcept:entered(other.entered),gate(std::move(other.gate)),count(other.count),wrong(other.wrong),owner(other.owner){other.count=nullptr;}
        ~Held(){if(!count)return;if(entered){entered->set_value();gate.wait();}if(std::this_thread::get_id()==owner)++*wrong;++*count;}
    };
    {
        RtrResourceReclaimer release;
        struct ReleaseGate{std::promise<void>* promise;~ReleaseGate(){try{promise->set_value();}catch(...){}}} releaseGate{&unblock};
        Held first;first.entered=&entered;first.gate=gate;first.count=&workerDestroyed;first.wrong=&wrongThread;first.owner=owner;
        require(release.Enqueue(first,4096),"First fixed slot accepted");entered.get_future().wait();
        for(size_t i=1;i<RtrResourceReclaimer::Capacity;++i){Held next;next.count=&workerDestroyed;next.wrong=&wrongThread;next.owner=owner;require(release.Enqueue(next,4096),"All fixed slots accepted");}
        Held rejected;rejected.count=&workerDestroyed;rejected.wrong=&wrongThread;rejected.owner=owner;
        const auto start=std::chrono::steady_clock::now();const auto accepted=release.Enqueue(rejected,4096);
        const auto elapsed=std::chrono::steady_clock::now()-start;
        // Unblock before asserting, so a failing test never deadlocks its cleanup.
        const bool kept=rejected.count!=nullptr;rejected.count=nullptr;unblock.set_value();
        require(!accepted&&kept,"Full queue leaves the rejected object untouched");
        require(elapsed<std::chrono::milliseconds(50),"Full queue must not wait for worker");
    }
    require(workerDestroyed==RtrResourceReclaimer::Capacity&&wrongThread==0,"Every accepted final destructor runs on worker");
    RtrProtocolTrace::Record(2,0,0,0,0,0,0,0,0,0,0);
    require(RtrProtocolTrace::Snapshot(RtrProtocolTrace::Token()).back()==1,"Protocol sample retains its issuing diagnostic level");
    std::cout<<"RESOURCE_RELEASE_PRESSURE PASS slots=32 rejectionPreservesOwnership=true destructionOnWorker=true\n";
    std::cout<<"RESOURCE_PROTOCOL PASS faultNoAdvance=true firstFault=true observer=true off=true releaseDrained=16\n";
}
