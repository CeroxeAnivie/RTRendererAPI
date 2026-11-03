#pragma once
#include <algorithm>
#include <cstddef>
#include <stdexcept>
#include <vector>

struct RtrNativeApplicationRange {size_t offset=0,size=0;};
// Returns prepared metadata before the caller changes any accepted bytes.
inline std::vector<RtrNativeApplicationRange> RtrMergeApplicationRange(
    const std::vector<RtrNativeApplicationRange>& old,size_t offset,size_t bytes,size_t capacity){
    if(offset%4||bytes==0||bytes%4||capacity>16*1024*1024||offset>capacity||bytes>capacity-offset)
        throw std::invalid_argument("Application patch exceeds its existing aligned publication range");
    std::vector<RtrNativeApplicationRange> result;result.reserve(old.size()+1);
    size_t begin=offset,end=offset+bytes;bool inserted=false;
    for(const auto& range:old){
        const size_t finish=range.offset+range.size;
        if(finish<begin)result.push_back(range);
        else if(range.offset>end){if(!inserted){result.push_back({begin,end-begin});inserted=true;}result.push_back(range);}
        else{begin=(std::min)(begin,range.offset);end=(std::max)(end,finish);}
    }
    if(!inserted)result.push_back({begin,end-begin});return result;
}
