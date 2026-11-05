#include "rtxpt_capacity.h"
#include <cstdio>
#include <initializer_list>
int main(){
    auto require=[](bool valid){if(!valid)throw std::runtime_error("Resource capacity self-test failed");};
    size_t capacity=0,allocations=0;
    for(size_t count:{719,977,831,685,977,978,1024,1025,719,1025}){
        if(capacity<count){capacity=RtrCapacity::Elements(count);++allocations;}
        require(capacity>=count);
    }
    require(allocations==2&&capacity==2048);
    require(RtrCapacity::Elements(0)==256);
    require(RtrCapacity::Elements(4096,256,4096)==4096);
    require(RtrCapacity::Elements(3000,256,3000)==3000);
    bool rejected=false;try{RtrCapacity::Elements(4097,256,4096);}catch(const std::length_error&){rejected=true;}
    require(rejected);
    std::puts("Resource growth/shrink/oscillation capacity PASS");
}
