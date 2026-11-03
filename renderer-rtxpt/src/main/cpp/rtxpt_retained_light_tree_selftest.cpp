#include "rtxpt_retained_light_tree.h"
#include <array>
#include <iostream>
#include <random>
#include <chrono>
#include <set>

struct Light {std::array<float,4> positionType,directionRange,colorIntensity,coneShadow;};
static void Require(bool value,const char* why){if(!value)throw std::runtime_error(why);}
static std::pair<size_t,double> Check(const rtr::RetainedLightTree& tree,const std::vector<Light>& lights,uint32_t index,uint32_t parent,std::set<uint32_t>& seen){
    Require(index<tree.nodes.size()&&seen.insert(index).second,"Reachable tree indices unique and valid");
    Require(tree.parents[index]==parent,"Parent mapping matches topology");auto& n=tree.nodes[index];
    if(n.light>=0){uint32_t slot=uint32_t(n.light);Require(slot<lights.size()&&tree.leaves[slot]==index,"Dense slot maps to exact leaf");
        for(int a=0;a<3;a++)Require(n.minimum[a]==lights[slot].positionType[a]&&n.maximum[a]==n.minimum[a],"Leaf bounds match current record");
        Require(n.leaves==1&&tree.heights[index]==0,"Leaf cardinality/height");return {1,n.power};}
    Require(n.light== -1,"Retired nodes are unreachable");
    auto a=Check(tree,lights,uint32_t(n.left),index,seen),b=Check(tree,lights,uint32_t(n.right),index,seen);
    Require(n.leaves==a.first+b.first,"Tree sampling cardinality matches exhaustive set");
    Require(std::abs(double(n.power)-a.second-b.second)<=std::max(1e-6,double(n.power)*1e-5),"Power aggregate matches exhaustive sum");
    int l=tree.heights[uint32_t(n.left)],r=tree.heights[uint32_t(n.right)];Require(std::abs(l-r)<=1&&tree.heights[index]==1+std::max(l,r),"AVL traversal depth bound");
    for(int axis=0;axis<3;axis++)Require(n.minimum[axis]==std::min(tree.nodes[uint32_t(n.left)].minimum[axis],tree.nodes[uint32_t(n.right)].minimum[axis])&&n.maximum[axis]==std::max(tree.nodes[uint32_t(n.left)].maximum[axis],tree.nodes[uint32_t(n.right)].maximum[axis]),"Bounds match exhaustive child union");
    return {a.first+b.first,a.second+b.second};
}
int main(){try{
    std::mt19937 random(706);using Clock=std::chrono::steady_clock;
    for(uint32_t count:{0u,1u,64u,1000u,10000u,100000u,160000u}){
        std::vector<Light> lights;lights.reserve(count+128);for(uint32_t i=0;i<count;i++)lights.push_back({{float(i%97),float(i%17),float(i/97),1},{0,-1,0,20},{.2f,.5f,.8f,float(1+i%9)},{0,0,1,0}});
        rtr::RetainedLightTree tree;double normalization=1;tree.Rebuild(lights,&normalization);tree.Reserve(lights.size()+128);std::vector<uint32_t> dirty;dirty.reserve(4096);std::vector<double> times;
        for(int batch=0;batch<80;batch++){
            auto begin=Clock::now();
            for(int edit=0;edit<18;edit++){
                if(lights.empty()||random()%3==0){Light light{{float(random()%500),2,float(random()%500),float(random()%3)},{0,-1,0,20},{1,.5f,.25f,2},{0,0,1,0}};lights.push_back(light);tree.Insert(lights,uint32_t(lights.size()-1),normalization,dirty);}
                else if(random()%2){uint32_t slot=random()%lights.size();tree.Remove(slot,dirty);lights[slot]=lights.back();lights.pop_back();}
                else{uint32_t slot=random()%lights.size();lights[slot].positionType[0]+=3;lights[slot].colorIntensity[3]=5;tree.Refit(lights,slot,normalization,dirty);}
            }
            times.push_back(std::chrono::duration<double,std::milli>(Clock::now()-begin).count());
            if(batch%20==0||batch==79){std::set<uint32_t> seen;if(lights.empty())Require(tree.root==rtr::RetainedLightTree::None,"Empty root");else{Check(tree,lights,tree.root,rtr::RetainedLightTree::None,seen);Require(seen.size()==2*lights.size()-1,"Every accepted light has exactly one reachable leaf");Require(tree.heights[tree.root]<32,"Shader traversal covers full tree");}for(auto i:tree.free)Require(!seen.count(i),"Free and resident nodes disjoint");}
            dirty.clear();
        }
        std::sort(times.begin(),times.end());std::cout<<"RTXPT_RETAINED_TREE count="<<count<<" 18-edits P50ms="<<times[40]<<" P95ms="<<times[76]<<" maxMs="<<times.back()<<" visited="<<tree.visited<<" highWater="<<tree.nodes.size()<<"\n";
        while(!lights.empty()){uint32_t slot=uint32_t(lights.size()/2);tree.Remove(slot,dirty);lights[slot]=lights.back();lights.pop_back();dirty.clear();}
        Require(tree.root==rtr::RetainedLightTree::None,"Clear-all completes without rebuilding");
    }
    std::cout<<"RTXPT_RETAINED_TREE PASS mixed insert/remove/refit, slot compaction, AVL bounds, exhaustive aggregates, full retirement\n";return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<"\n";return 1;}}
