#pragma once
#include "rtxpt_light_tree.h"
#include <limits>
#include <stdexcept>

namespace rtr {
// Dense light slots and independently allocated BVH nodes. AVL rotations bound
// traversal depth; free node reuse keeps repeated insert/remove cycles bounded.
class RetainedLightTree {
public:
    static constexpr uint32_t None=std::numeric_limits<uint32_t>::max();
    std::vector<LightTreeNode> nodes;
    std::vector<uint32_t> parents,leaves;
    std::vector<int> heights;
    std::vector<uint32_t> free;
    uint32_t root=None;
    uint64_t visited=0;
    void Reserve(size_t lights){
        size_t capacity=1;while(capacity<lights)capacity*=2;
        const auto n=std::max<size_t>(1,capacity*2);
        nodes.reserve(n);parents.reserve(n);heights.reserve(n);free.reserve(n);leaves.reserve(capacity);
    }
    template<class Light> void Rebuild(const std::vector<Light>& lights,double* normalization){
        nodes=BuildLightTree(lights,normalization);parents.assign(nodes.size(),None);
        leaves.assign(lights.size(),None);heights.assign(nodes.size(),0);free.clear();root=nodes.empty()?None:0;
        for(uint32_t i=0;i<nodes.size();++i){auto& n=nodes[i];if(n.light>=0)leaves.at(uint32_t(n.light))=i;
            else{parents.at(uint32_t(n.left))=i;parents.at(uint32_t(n.right))=i;}}
        for(size_t i=nodes.size();i--;)if(nodes[i].light<0)heights[i]=1+std::max(heights.at(uint32_t(nodes[i].left)),heights.at(uint32_t(nodes[i].right)));
    }
    template<class Light> void Refit(const std::vector<Light>& lights,uint32_t slot,double normalization,std::vector<uint32_t>& dirty){
        uint32_t leaf=leaves.at(slot);SetLeaf(nodes.at(leaf),lights.at(slot),slot,normalization);dirty.push_back(leaf);Fix(parents[leaf],dirty);
    }
    template<class Light> void Insert(const std::vector<Light>& lights,uint32_t slot,double normalization,std::vector<uint32_t>& dirty){
        if(slot!=leaves.size())throw std::logic_error("Light tree slots must append densely");
        uint32_t leaf=Allocate();SetLeaf(nodes[leaf],lights.at(slot),slot,normalization);leaves.push_back(leaf);dirty.push_back(leaf);
        if(root==None){root=leaf;return;}
        uint32_t sibling=root;
        while(nodes[sibling].light<0){
            ++visited;auto& n=nodes[sibling];uint32_t a=uint32_t(n.left),b=uint32_t(n.right);
            double ca=Growth(nodes[a],nodes[leaf]),cb=Growth(nodes[b],nodes[leaf]);
            sibling=ca<cb||(ca==cb&&nodes[a].leaves<nodes[b].leaves)?a:b;
        }
        uint32_t oldParent=parents[sibling],branch=Allocate();
        nodes[branch].light=-1;nodes[branch].left=float(sibling);nodes[branch].right=float(leaf);
        parents[branch]=oldParent;parents[sibling]=parents[leaf]=branch;
        dirty.push_back(sibling);
        Replace(oldParent,sibling,branch);Fix(branch,dirty);
    }
    void Remove(uint32_t slot,std::vector<uint32_t>& dirty){
        uint32_t leaf=leaves.at(slot),branch=parents[leaf];
        if(branch==None){root=None;Release(leaf,dirty);}
        else{
            auto& p=nodes[branch];uint32_t sibling=uint32_t(p.left)==leaf?uint32_t(p.right):uint32_t(p.left),grand=parents[branch];
            Replace(grand,branch,sibling);parents[sibling]=grand;dirty.push_back(sibling);Release(leaf,dirty);Release(branch,dirty);Fix(grand,dirty);
        }
        if(slot+1!=leaves.size()){
            leaves[slot]=leaves.back();nodes[leaves[slot]].light=float(slot);dirty.push_back(leaves[slot]);
        }
        leaves.pop_back();
    }
    size_t ActiveNodes() const{return leaves.empty()?0:leaves.size()*2-1;}
private:
    template<class Light> static void SetLeaf(LightTreeNode& node,const Light& l,uint32_t slot,double normalization){
        node={};for(int a=0;a<3;a++)node.minimum[a]=node.maximum[a]=l.positionType[a];
        node.light=float(slot);node.leaves=1;node.directional=l.positionType[3]==0?1.f:0.f;node.range=l.directionRange[3];
        node.power=float(double(l.colorIntensity[3])*(.2126*l.colorIntensity[0]+.7152*l.colorIntensity[1]+.0722*l.colorIntensity[2])/normalization);
    }
    static double Growth(const LightTreeNode& a,const LightTreeNode& b){
        double before=0,after=0;
        for(int axis=0;axis<3;axis++){
            double x=double(a.maximum[axis])-a.minimum[axis];before+=x*x;
            double y=double(std::max(a.maximum[axis],b.maximum[axis]))-std::min(a.minimum[axis],b.minimum[axis]);after+=y*y;
        }
        return after-before;
    }
    uint32_t Allocate(){
        uint32_t i;
        if(free.empty()){i=uint32_t(nodes.size());nodes.push_back({});parents.push_back(None);heights.push_back(0);}
        else{i=free.back();free.pop_back();nodes[i]={};parents[i]=None;heights[i]=0;}
        return i;
    }
    void Release(uint32_t i,std::vector<uint32_t>& dirty){nodes[i]={};nodes[i].light=-2;parents[i]=None;heights[i]=-1;free.push_back(i);dirty.push_back(i);}
    void Replace(uint32_t parent,uint32_t oldChild,uint32_t child){
        if(parent==None)root=child;
        else if(uint32_t(nodes[parent].left)==oldChild)nodes[parent].left=float(child);
        else if(uint32_t(nodes[parent].right)==oldChild)nodes[parent].right=float(child);
        else throw std::logic_error("Invalid light tree parent");
    }
    void Refresh(uint32_t i,std::vector<uint32_t>& dirty){
        ++visited;auto& n=nodes[i];if(n.light>=0){heights[i]=0;return;}
        uint32_t l=uint32_t(n.left),r=uint32_t(n.right);auto& a=nodes[l];auto& b=nodes[r];
        for(int axis=0;axis<3;axis++){n.minimum[axis]=std::min(a.minimum[axis],b.minimum[axis]);n.maximum[axis]=std::max(a.maximum[axis],b.maximum[axis]);}
        n.power=a.power+b.power;n.directional=std::max(a.directional,b.directional);n.range=std::max(a.range,b.range);n.leaves=a.leaves+b.leaves;
        heights[i]=1+std::max(heights[l],heights[r]);dirty.push_back(i);
    }
    uint32_t Left(uint32_t a,std::vector<uint32_t>& dirty){
        uint32_t b=uint32_t(nodes[a].right),beta=uint32_t(nodes[b].left),p=parents[a];
        Replace(p,a,b);parents[b]=p;nodes[b].left=float(a);parents[a]=b;nodes[a].right=float(beta);parents[beta]=a;
        dirty.push_back(beta);
        Refresh(a,dirty);Refresh(b,dirty);return b;
    }
    uint32_t Right(uint32_t a,std::vector<uint32_t>& dirty){
        uint32_t b=uint32_t(nodes[a].left),beta=uint32_t(nodes[b].right),p=parents[a];
        Replace(p,a,b);parents[b]=p;nodes[b].right=float(a);parents[a]=b;nodes[a].left=float(beta);parents[beta]=a;
        dirty.push_back(beta);
        Refresh(a,dirty);Refresh(b,dirty);return b;
    }
    void Fix(uint32_t i,std::vector<uint32_t>& dirty){
        while(i!=None){
            Refresh(i,dirty);auto& n=nodes[i];int balance=heights[uint32_t(n.left)]-heights[uint32_t(n.right)];
            if(balance>1){uint32_t l=uint32_t(n.left);if(heights[uint32_t(nodes[l].left)]<heights[uint32_t(nodes[l].right)])Left(l,dirty);i=Right(i,dirty);}
            else if(balance< -1){uint32_t r=uint32_t(n.right);if(heights[uint32_t(nodes[r].right)]<heights[uint32_t(nodes[r].left)])Right(r,dirty);i=Left(i,dirty);}
            i=parents[i];
        }
    }
};
}
