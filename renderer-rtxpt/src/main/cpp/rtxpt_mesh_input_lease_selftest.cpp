#include "rtxpt_mesh_input_lease.h"
#include <algorithm>
#include <iostream>
#include <vector>

using namespace rtr::upstream;
static void require(bool value,const char* message){if(!value)throw std::runtime_error(message);}
int main(){
    auto accounting=std::make_shared<MeshInputRetention>();
    auto packet=std::make_shared<std::vector<uint8_t>>(8*1024*1024,0xa5);
    std::weak_ptr<std::vector<uint8_t>> transport=packet;
    // Two meshes live in one large transaction with unrelated texture bytes.
    // Preserve four independent attribute ranges and canonical indices exactly.
    const std::array<MeshInputLease::Range,5> a{{
        {packet->data()+3,48},{packet->data()+129,48},{nullptr,0},
        {packet->data()+301,32},{packet->data()+455,24}}};
    const std::array<MeshInputLease::Range,5> b{{
        {packet->data()+1003,24},{packet->data()+1129,24},{nullptr,0},
        {nullptr,0},{packet->data()+1455,12}}};
    const auto patterned=[&](const auto& ranges,uint8_t first){
        std::vector<uint8_t> expected;
        for(size_t i=0;i<ranges.size();++i)if(ranges[i].size){
            const auto begin=size_t(ranges[i].data-packet->data());
            std::fill_n(packet->begin()+begin,ranges[i].size,uint8_t(first+i));
            expected.insert(expected.end(),ranges[i].size,uint8_t(first+i));
        }
        return expected;
    };
    const auto expectedA=patterned(a,40),expectedB=patterned(b,80);
    auto first=std::make_shared<const MeshInputLease>(a,accounting);
    auto second=std::make_shared<const MeshInputLease>(b,accounting);
    require(first->bytes.size()==152&&second->bytes.size()==60,"Canonical input size included unrelated transaction bytes");
    require(accounting->bytes==212&&accounting->peakBytes==212&&accounting->packets==2,"Retained-byte accounting is not exact");
    require(std::ranges::equal(first->bytes,expectedA)&&std::ranges::equal(second->bytes,expectedB),"Retained channel order or exact bytes changed");
    std::fill(packet->begin(),packet->end(),0);packet.reset();
    require(transport.expired(),"A retained mesh pins its original transaction");
    require(std::ranges::equal(first->bytes,expectedA)&&std::ranges::equal(second->bytes,expectedB),"Copied input lifetime depends on transaction bytes");
    auto inherited=first;first.reset();second.reset();
    require(accounting->bytes==152&&accounting->packets==1,"Deleted neighbour bytes remain retained");
    require(inherited->bytes.size()==152&&accounting->peakBytes==212,"Inherited input changed its source or double-counted ownership");
    inherited.reset();
    require(accounting->bytes==0&&accounting->packets==0&&accounting->peakBytes==212,"Final mesh deletion did not release exact bytes");
    bool rejected=false;try{
        auto invalid=a;invalid[0]={nullptr,1};
        auto ignored=std::make_shared<const MeshInputLease>(invalid,accounting);
    }catch(const std::invalid_argument&){rejected=true;}
    require(rejected&&accounting->bytes==0&&accounting->packets==0,"Invalid input corrupted retention accounting");
    std::cout<<"MESH_INPUT_LEASE_CPU PASS transportBytes=8388608 liveBothBytes=212 survivorBytes=152 finalBytes=0 exactChannels=true inheritedShared=true"<<std::endl;
}
