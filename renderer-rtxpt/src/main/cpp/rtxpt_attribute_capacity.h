#pragma once
#include <algorithm>
#include <cstddef>
#include <stdexcept>

namespace rtr {
// Cold publications already know the complete layout. Do not reserve another
// scene-sized power-of-two tail. Incremental growth retains bounded headroom.
inline size_t AttributeBufferCapacity(size_t required,size_t current,size_t stride){
    constexpr size_t MiB=1024*1024,Limit=512*MiB,Page=64*1024;
    required=std::max(required,stride);
    if(required>Limit)throw std::runtime_error("Attribute arena exceeds 512 MiB capacity");
    if(current>=required)return current;
    if(required<16*MiB){size_t capacity=std::max<size_t>(256,stride);while(capacity<required)capacity*=2;return capacity;}
    size_t target=required;
    if(current)target=std::max(required,std::min(current+current/2,required+16*MiB));
    target=std::min(target,Limit);
    return (target+Page-1)/Page*Page;
}
}
