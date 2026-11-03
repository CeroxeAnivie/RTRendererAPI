#include "rtxpt_native_application_ranges.h"
#include <cstdint>
#include <cstdio>
#include <limits>
#include <random>

int main(){try{
    constexpr size_t capacity=4096;std::vector<RtrNativeApplicationRange> ranges;
    ranges=RtrMergeApplicationRange(ranges,16,4,capacity);ranges=RtrMergeApplicationRange(ranges,24,4,capacity);
    if(ranges.size()!=2)throw std::runtime_error("Disjoint ranges were expanded across unchanged bytes");
    ranges=RtrMergeApplicationRange(ranges,20,4,capacity);
    if(ranges.size()!=1||ranges[0].offset!=16||ranges[0].size!=12)throw std::runtime_error("Adjacent ranges were not merged");
    ranges=RtrMergeApplicationRange(ranges,12,8,capacity);
    if(ranges.size()!=1||ranges[0].offset!=12||ranges[0].size!=16)throw std::runtime_error("Overlapping writes were not merged");
    const auto saved=ranges;
    for(const auto invalid:std::vector<RtrNativeApplicationRange>{{0,0},{1,4},{0,3},{4096,4},{size_t(-4),8}}){
        bool rejected=false;try{auto next=RtrMergeApplicationRange(ranges,invalid.offset,invalid.size,capacity);(void)next;}catch(const std::invalid_argument&){rejected=true;}
        if(!rejected||ranges.size()!=saved.size()||ranges[0].offset!=saved[0].offset||ranges[0].size!=saved[0].size)
            throw std::runtime_error("Invalid patch changed dirty metadata");
    }
    // Compare the merged upload union with a byte-addressed reference over random overlaps.
    ranges.clear();std::vector<bool> expected(capacity);std::mt19937 random(710);
    for(unsigned sample=0;sample<400;sample++){
        size_t offset=(random()%1024)*4,bytes=((random()%16)+1)*4;bytes=(std::min)(bytes,capacity-offset);
        ranges=RtrMergeApplicationRange(ranges,offset,bytes,capacity);for(size_t i=offset;i<offset+bytes;i++)expected[i]=true;
        std::vector<bool> actual(capacity);size_t previousEnd=0;
        for(const auto& range:ranges){if(range.offset%4||range.size%4||range.size==0||range.offset<previousEnd)throw std::runtime_error("Merged ranges lost ordering/alignment");
            for(size_t i=range.offset;i<range.offset+range.size;i++)actual[i]=true;previousEnd=range.offset+range.size;}
        if(actual!=expected)throw std::runtime_error("Merged uploads include unchanged bytes or omit changed bytes");
    }
    std::printf("NATIVE_LIGHTING_APPLICATION_RANGES_SELFTEST PASS randomPatches=400 exactUnion=true invalidAtomic=true\n");return 0;
}catch(const std::exception& failure){std::fprintf(stderr,"Application range selftest failed: %s\n",failure.what());return 1;}}
