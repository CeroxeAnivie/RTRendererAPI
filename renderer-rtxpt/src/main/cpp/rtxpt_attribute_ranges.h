#pragma once
#include <algorithm>
#include <bit>
#include <cstdint>
#include <iterator>
#include <map>
#include <stdexcept>
#include <vector>

namespace rtr {
// Exact address-first placement, with a compressed radix maximum index. A burst
// of allocations does not scan every smaller hole in the address-ordered map.
// Each hole owns one leaf and at most one branch; released index nodes recycle.
struct AttributeRanges {
    uint64_t end=0;
    std::map<uint32_t,uint32_t> free;
private:
    struct Node {uint32_t child[2]{},prefix=0,maximum=0,bits=32;};
    std::vector<Node> m_nodes{Node{}};
    uint32_t m_root=0,m_unused=0;
    void Reserve(){
        if(m_nodes.capacity()-m_nodes.size()<2)
            m_nodes.reserve(std::max(m_nodes.size()+2,m_nodes.capacity()*2));
    }
    uint32_t New(Node value){
        if(m_unused){const auto index=m_unused;m_unused=m_nodes[index].child[0];m_nodes[index]=value;return index;}
        m_nodes.push_back(value);return uint32_t(m_nodes.size()-1);
    }
    void Recycle(uint32_t index){m_nodes[index]={};m_nodes[index].child[0]=m_unused;m_unused=index;}
    uint32_t Maximum(uint32_t index)const{return index?m_nodes[index].maximum:0;}
    static unsigned Side(uint32_t key,unsigned bits){return (key>>(31-bits))&1u;}
    void Set(uint32_t& index,uint32_t offset,uint32_t count){
        if(!index){index=New({{},offset,count,32});return;}
        auto& node=m_nodes[index];const unsigned common=std::countl_zero(offset^node.prefix);
        if(common<node.bits){
            const auto leaf=New({{},offset,count,32});
            Node branch{{},offset,std::max(count,node.maximum),common};
            const auto side=Side(offset,common);branch.child[side]=leaf;branch.child[side^1]=index;
            index=New(branch);return;
        }
        if(node.bits==32){node.maximum=count;return;}
        Set(node.child[Side(offset,node.bits)],offset,count);
        node.maximum=std::max(Maximum(node.child[0]),Maximum(node.child[1]));
    }
    void Erase(uint32_t& index,uint32_t offset){
        auto& node=m_nodes[index];
        if(node.bits==32){const auto removed=index;index=0;Recycle(removed);return;}
        Erase(node.child[Side(offset,node.bits)],offset);
        if(!node.child[0]||!node.child[1]){
            const auto removed=index;index=node.child[0]?node.child[0]:node.child[1];Recycle(removed);
        }else node.maximum=std::max(Maximum(node.child[0]),Maximum(node.child[1]));
    }
    auto First(uint32_t count){
        auto index=m_root;
        if(Maximum(index)<count)return free.end();
        while(m_nodes[index].bits!=32){const auto& node=m_nodes[index];index=node.child[Maximum(node.child[0])>=count?0:1];}
        return free.find(m_nodes[index].prefix);
    }
public:
    void Release(uint32_t offset,uint32_t count){
        if(!count)return;
        if(uint64_t(offset)+count>end)throw std::logic_error("Attribute release exceeds arena");
        auto next=free.lower_bound(offset);
        if(next!=free.end()&&uint64_t(offset)+count>next->first)throw std::logic_error("Overlapping attribute release");
        auto previous=next==free.begin()?free.end():std::prev(next);
        if(previous!=free.end()&&uint64_t(previous->first)+previous->second>offset)throw std::logic_error("Overlapping attribute release");
        // All potentially throwing allocations precede changes to either index.
        Reserve();auto inserted=free.emplace_hint(next,offset,count);
        if(previous!=free.end()&&uint64_t(previous->first)+previous->second==offset){
            Erase(m_root,previous->first);previous->second+=count;free.erase(inserted);inserted=previous;
        }
        next=std::next(inserted);
        if(next!=free.end()&&uint64_t(inserted->first)+inserted->second==next->first){
            Erase(m_root,next->first);inserted->second+=next->second;free.erase(next);
        }
        if(uint64_t(inserted->first)+inserted->second==end){end=inserted->first;free.erase(inserted);}
        else Set(m_root,inserted->first,inserted->second);
    }
    uint32_t Allocate(uint32_t count){
        if(!count)return uint32_t(end);
        auto at=First(count);
        if(at!=free.end()){
            const auto offset=at->first,remaining=at->second-count;
            Reserve();Erase(m_root,offset);auto node=free.extract(at);
            if(remaining){
                node.key()=offset+count;node.mapped()=remaining;free.insert(std::move(node));
                Set(m_root,offset+count,remaining);
            }
            return offset;
        }
        if(end+count>UINT32_MAX)throw std::runtime_error("Attribute arena exceeds index capacity");
        const auto offset=uint32_t(end);end+=count;return offset;
    }
};
}
