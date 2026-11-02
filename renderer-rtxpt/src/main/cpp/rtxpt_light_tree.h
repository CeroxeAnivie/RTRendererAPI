#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <numeric>
#include <vector>

// GPU records share the light buffer's 64-byte stride. Child indices are relative
// to the tree (stored after the light records), so resource growth needs no ABI slot.
namespace rtr {
struct LightTreeNode {
    float minimum[3]; float left;
    float maximum[3]; float right;
    float power, directional, range, light;
    float leaves, unused[3];
};
static_assert(sizeof(LightTreeNode)==64);
template<class Light> std::vector<LightTreeNode> BuildLightTree(const std::vector<Light>& lights,double* normalization=nullptr) {
    std::vector<LightTreeNode> nodes;
    if(lights.empty())return nodes;
    std::vector<uint32_t> order(lights.size());std::iota(order.begin(),order.end(),0);
    nodes.reserve(lights.size()*2-1);
    double maximumPower=0;
    for(const auto& l:lights)maximumPower=std::max(maximumPower,double(l.colorIntensity.w)*
        (.2126*l.colorIntensity.x+.7152*l.colorIntensity.y+.0722*l.colorIntensity.z));
    if(normalization)*normalization=std::max(maximumPower,1e-30);
    auto build=[&](auto&& self,size_t first,size_t end)->uint32_t {
        uint32_t index=uint32_t(nodes.size());nodes.push_back({});
        LightTreeNode node{};node.light=-1;node.leaves=float(end-first);
        for(int a=0;a<3;a++)node.minimum[a]=node.maximum[a]=lights[order[first]].positionType[a];
        for(size_t i=first;i<end;i++) {
            const auto& l=lights[order[i]];
            for(int a=0;a<3;a++){node.minimum[a]=std::min(node.minimum[a],l.positionType[a]);node.maximum[a]=std::max(node.maximum[a],l.positionType[a]);}
            node.directional=std::max(node.directional,l.positionType.w==0.f?1.f:0.f);
            node.range=std::max(node.range,l.directionRange.w);
        }
        if(end-first==1) {
            const auto& l=lights[order[first]];
            node.light=float(order[first]);
            double p=double(l.colorIntensity.w)*(.2126*l.colorIntensity.x+.7152*l.colorIntensity.y+.0722*l.colorIntensity.z);
            node.power=maximumPower>0?float(p/maximumPower):0;
        } else {
            int axis=0;for(int a=1;a<3;a++)if(double(node.maximum[a])-node.minimum[a]>double(node.maximum[axis])-node.minimum[axis])axis=a;
            size_t middle=first+(end-first)/2;
            std::nth_element(order.begin()+first,order.begin()+middle,order.begin()+end,[&](uint32_t a,uint32_t b){
                float x=lights[a].positionType[axis],y=lights[b].positionType[axis];return x==y?a<b:x<y;
            });
            uint32_t left=self(self,first,middle),right=self(self,middle,end);
            node.left=float(left);node.right=float(right);node.power=nodes[left].power+nodes[right].power;
        }
        nodes[index]=node;return index;
    };
    build(build,0,order.size());return nodes;
}
}
