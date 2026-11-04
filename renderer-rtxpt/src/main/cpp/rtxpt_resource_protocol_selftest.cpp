#include "rtxpt_fence.h"
#include "rtxpt_resource_reclaimer.h"
#include <iostream>
#include <atomic>
#include <stdexcept>
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
    std::cout<<"RESOURCE_PROTOCOL PASS faultNoAdvance=true firstFault=true observer=true off=true releaseDrained=16\n";
}
