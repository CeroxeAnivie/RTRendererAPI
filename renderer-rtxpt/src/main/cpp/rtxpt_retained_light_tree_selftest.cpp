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
static void SpatialPublication(){
    std::vector<Light> lights;std::vector<uint32_t> dirty;double scale=1;
    rtr::RetainedLightTree inserted,spatial;inserted.Reserve(22000);
    for(uint32_t i=0;i<22000;i++){
        lights.push_back({{float(i%163)*.4f-32,float(i/163)*.4f-32,2,1},{0,-1,0,3.208f},{1,.7f,.4f,.02f},{}});
        inserted.Insert(lights,i,scale,dirty);dirty.clear();
    }
    spatial.Rebuild(lights,&scale);uint64_t oldVisits=0,newVisits=0;
    auto query=[](const auto& tree,float x,float y,uint64_t& visits){
        std::set<uint32_t> hits;std::vector<uint32_t> stack{tree.root};
        while(!stack.empty()){
            auto index=stack.back();stack.pop_back();++visits;const auto& n=tree.nodes[index];
            float d2=0;const float p[]={x,y,0};for(int a=0;a<3;a++){float d=std::max({n.minimum[a]-p[a],p[a]-n.maximum[a],0.f});d2+=d*d;}
            if(d2>n.range*n.range)continue;
            if(n.light>=0)hits.insert(uint32_t(n.light));else{stack.push_back(uint32_t(n.left));stack.push_back(uint32_t(n.right));}
        }return hits;
    };
    for(int y=0;y<16;y++)for(int x=0;x<20;x++){
        const float px=x*3.f-29,py=y*3.f-29;auto before=query(inserted,px,py,oldVisits),after=query(spatial,px,py,newVisits);
        std::set<uint32_t> exact;for(uint32_t i=0;i<lights.size();i++){const auto& l=lights[i];float dx=px-l.positionType[0],dy=py-l.positionType[1];if(dx*dx+dy*dy+4<=l.directionRange[3]*l.directionRange[3])exact.insert(i);}
        Require(before==exact&&after==exact,"Spatial rebuild preserves every supported source");
    }
    Require(newVisits<oldVisits,"Spatial bulk build reduces traversal work");
    std::cout<<"SPARSE_SPATIAL_PUBLICATION PASS sources=22000 receivers=320 insertedVisits="<<oldVisits<<" spatialVisits="<<newVisits<<"\n";
}
static void ParentPublication(){
    std::mt19937 random(12071);rtr::RetainedLightTree tree;std::vector<Light> lights;std::vector<uint32_t> dirty;double scale=1;
    for(int batch=0;batch<1200;batch++){
        const auto before=tree.parents;dirty.clear();
        if(lights.size()<8||random()%3==0){lights.push_back({{float(random()%200),float(random()%100),float(random()%70),1},{0,-1,0,3},{1,.7f,.4f,.02f},{}});tree.Insert(lights,uint32_t(lights.size()-1),scale,dirty);}
        else if(random()%2){uint32_t slot=random()%lights.size();tree.Remove(slot,dirty);lights[slot]=lights.back();lights.pop_back();}
        else{uint32_t slot=random()%lights.size();lights[slot].positionType[0]+=2;tree.Refit(lights,slot,scale,dirty);}
        std::set<uint32_t> published(dirty.begin(),dirty.end());
        for(uint32_t i=0;i<tree.nodes.size();i++)if(tree.nodes[i].light>=-1&&(i>=before.size()||tree.parents[i]!=before[i]))
            Require(published.count(i)!=0,"Every changed parent link is published, including rotations and root replacement");
    }
    std::cout<<"SPARSE_PARENT_PUBLICATION PASS batches=1200 insert=true remove=true rotations=true\n";
}
static void BitstackTraversal(){
    std::vector<Light> lights;for(uint32_t i=0;i<977;i++)lights.push_back({{float(i%31),float(i/31),2,1},{0,-1,0,3},{1,.7f,.4f,.02f},{}});
    rtr::RetainedLightTree tree;double scale=1;tree.Rebuild(lights,&scale);
    for(uint32_t seed=0;seed<128;seed++){
        std::vector<uint32_t> expected,actual,stack{tree.root};
        auto supported=[&](uint32_t index){return seed==0||tree.nodes[index].light>=0||(index*13+seed)%11!=0;};
        auto leftFirst=[&](uint32_t index){return (index+seed)%2==0;};
        while(!stack.empty()){
            auto index=stack.back();stack.pop_back();expected.push_back(index);const auto& n=tree.nodes[index];
            if(supported(index)&&n.light<0){stack.push_back(uint32_t(leftFirst(index)?n.right:n.left));stack.push_back(uint32_t(leftFirst(index)?n.left:n.right));}
        }
        uint32_t next=tree.root,depth=0,pending=0,deferredLeft=0;
        while(next!=rtr::RetainedLightTree::None){
            const auto index=next;const auto& node=tree.nodes[index];actual.push_back(index);
            Require(actual.size()<=tree.ActiveNodes(),"Bitstack never repeats or loops");
            if(supported(index)&&node.light<0){
                uint32_t bit=1u<<depth;pending|=bit;deferredLeft=(deferredLeft&~bit)|(leftFirst(index)?0:bit);++depth;
                next=uint32_t(leftFirst(index)?node.left:node.right);continue;
            }
            next=rtr::RetainedLightTree::None;uint32_t parent=tree.parents[index];
            while(depth){uint32_t bit=1u<<--depth;const auto& ancestor=tree.nodes.at(parent);
                if(pending&bit){pending&=~bit;next=uint32_t((deferredLeft&bit)?ancestor.left:ancestor.right);++depth;break;}
                parent=tree.parents[parent];}
        }
        Require(actual==expected,"Bitstack preserves exact near-first traversal with rejected subtrees");
    }
    std::cout<<"SPARSE_BITSTACK_TRAVERSAL PASS lights=977 rejectionPatterns=128 exactVisitOrder=true\n";
}
int main(int argc,char** argv){try{
    if(argc>1&&std::string(argv[1])=="bitstack"){BitstackTraversal();return 0;}
    if(argc>1&&std::string(argv[1])=="parents"){ParentPublication();return 0;}
    if(argc>1&&std::string(argv[1])=="spatial"){SpatialPublication();return 0;}
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
