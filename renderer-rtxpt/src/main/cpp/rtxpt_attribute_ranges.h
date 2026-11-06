#pragma once
#include <cstdint>
#include <iterator>
#include <map>
#include <stdexcept>

namespace rtr {
// Stable first-fit placement; release touches only the two address neighbours.
// A transaction deleting many geometries must not sort the whole free list per geometry.
struct AttributeRanges {
    uint64_t end=0;
    std::map<uint32_t,uint32_t> free;
    void Release(uint32_t offset,uint32_t count){
        if(!count)return;
        if(uint64_t(offset)+count>end)throw std::logic_error("Attribute release exceeds arena");
        auto next=free.lower_bound(offset);
        if(next!=free.end()&&uint64_t(offset)+count>next->first)throw std::logic_error("Overlapping attribute release");
        auto previous=next==free.begin()?free.end():std::prev(next);
        if(previous!=free.end()&&uint64_t(previous->first)+previous->second>offset)throw std::logic_error("Overlapping attribute release");
        // Allocate the map entry before changing existing free ranges.
        auto inserted=free.emplace_hint(next,offset,count);
        if(previous!=free.end()&&uint64_t(previous->first)+previous->second==offset){
            previous->second+=count;free.erase(inserted);inserted=previous;
        }
        next=std::next(inserted);
        if(next!=free.end()&&uint64_t(inserted->first)+inserted->second==next->first){
            inserted->second+=next->second;free.erase(next);
        }
        if(uint64_t(inserted->first)+inserted->second==end){end=inserted->first;free.erase(inserted);}
    }
    uint32_t Allocate(uint32_t count){
        if(!count)return uint32_t(end);
        for(auto at=free.begin();at!=free.end();++at)if(at->second>=count){
            const auto offset=at->first,remaining=at->second-count;
            // Node handles change the key without another allocation or a throwing gap.
            auto node=free.extract(at);
            if(remaining){node.key()=offset+count;node.mapped()=remaining;free.insert(std::move(node));}
            return offset;
        }
        if(end+count>UINT32_MAX)throw std::runtime_error("Attribute arena exceeds index capacity");
        const auto offset=uint32_t(end);end+=count;return offset;
    }
};
}
