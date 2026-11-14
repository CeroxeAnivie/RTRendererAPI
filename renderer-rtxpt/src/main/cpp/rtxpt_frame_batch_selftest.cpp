#include "rtxpt_frame_batch.h"
#include <atomic>
#include <vector>
#include <iostream>
#include <mutex>
#include <set>
#include <stdexcept>

static void Require(bool value,const char* message){if(!value)throw std::runtime_error(message);}
int main(){
    donut::engine::ThreadPool workers(3);
    std::vector<unsigned> visits(96);std::set<std::thread::id> threads;std::mutex mutex;
    rtr::upstream::RunFrameBatch(&workers,visits.size(),[&](size_t i){
        ++visits[i];std::lock_guard lock(mutex);threads.insert(std::this_thread::get_id());
    });
    for(auto value:visits)Require(value==1,"Batch lost or repeated an input");
    Require(threads.size()==1&&*threads.begin()==std::this_thread::get_id(),"Resource batch left the device owner");
    std::fill(visits.begin(),visits.end(),0);
    bool failed=false;
    try{rtr::upstream::RunFrameBatch(&workers,visits.size(),[&](size_t i){
        ++visits[i];if(i==3)throw std::runtime_error("first-input-error");if(i==17)throw std::runtime_error("later-input-error");
    });}catch(const std::runtime_error& error){failed=std::string(error.what())=="first-input-error";}
    Require(failed,"Worker error did not reach its frame owner in input order");
    for(auto value:visits)Require(value==1,"Batch escaped before its jobs joined");
    const auto owner=std::this_thread::get_id();
    rtr::upstream::RunFrameBatch(&workers,3,[&](size_t){Require(std::this_thread::get_id()==owner,"Small batch left its owner");});
    rtr::upstream::RunFrameBatch(nullptr,96,[&](size_t){Require(std::this_thread::get_id()==owner,"Serial backend left its owner");});
    std::cout<<"FRAME_BATCH PASS exactInputs=96 ownerSerialized=true deterministicFailure=true joined=true serialFallback=true\n";
}
