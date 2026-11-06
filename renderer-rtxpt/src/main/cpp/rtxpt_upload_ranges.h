#pragma once
#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace rtr {
// A write has fixed command/staging/barrier cost. Copying a bounded gap from
// the authoritative CPU image is cheaper than recording thousands of tiny
// writes; no GPU readback or temporary repacking is needed.
struct UploadRanges {
    static constexpr size_t MergeGapBytes=4096,MaximumWriteBytes=1024*1024;
    template<class Write> static void ForEach(std::vector<uint32_t>& dirty,size_t count,size_t stride,Write&& write,
            size_t mergeGapBytes=MergeGapBytes){
        std::sort(dirty.begin(),dirty.end());dirty.erase(std::unique(dirty.begin(),dirty.end()),dirty.end());
        dirty.erase(std::lower_bound(dirty.begin(),dirty.end(),uint32_t(count)),dirty.end());
        const size_t gap=mergeGapBytes/stride,maximum=std::max<size_t>(1,MaximumWriteBytes/stride);
        for(size_t i=0;i<dirty.size();){
            size_t end=i+1;
            while(end<dirty.size()&&size_t(dirty[end])-dirty[end-1]-1<=gap
                    &&size_t(dirty[end])-dirty[i]+1<=maximum)++end;
            const size_t first=dirty[i],length=size_t(dirty[end-1])-first+1;
            write(first,length);i=end;
        }
    }
};
}
