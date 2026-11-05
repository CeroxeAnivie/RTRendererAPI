#pragma once
#include <cstddef>
#include <stdexcept>

// Capacity is independent of the number of live rows. Retaining spare rows
// avoids synchronous GPU object creation when a scene shrinks or oscillates.
namespace RtrCapacity {
inline size_t Elements(size_t required,size_t minimum=256,size_t limit=size_t(-1)){
    if(!minimum||required>limit||minimum>limit)throw std::length_error("Resource capacity limit exceeded");
    size_t result=minimum;
    while(result<required){
        if(result>limit/2)return limit;
        result*=2;
    }
    return result;
}
}
