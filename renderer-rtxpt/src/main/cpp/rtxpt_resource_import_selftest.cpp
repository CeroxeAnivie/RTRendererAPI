#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <iostream>
#include <limits>
#include <random>
#include <stdexcept>
#include <string>
#include <vector>
#include "rtxpt_attribute_ranges.h"
#include "rtxpt_memory_resources.h"
#include "rtxpt_host_response_lookup.h"

static void Check(bool ok,const char* message){if(!ok)throw std::runtime_error(message);}
struct Reference {
    uint64_t end=0;struct Range{uint32_t offset,count;};std::vector<Range> free;
    void Release(uint32_t offset,uint32_t count){
        if(!count)return;free.push_back({offset,count});
        std::sort(free.begin(),free.end(),[](auto a,auto b){return a.offset<b.offset;});
        size_t out=0;for(auto value:free){if(out&&uint64_t(free[out-1].offset)+free[out-1].count==value.offset)free[out-1].count+=value.count;else free[out++]=value;}free.resize(out);
        while(!free.empty()&&uint64_t(free.back().offset)+free.back().count==end){end=free.back().offset;free.pop_back();}
    }
    uint32_t Allocate(uint32_t count){for(auto at=free.begin();at!=free.end();++at)if(at->count>=count){auto result=at->offset;at->offset+=count;at->count-=count;if(!at->count)free.erase(at);return result;}auto result=uint32_t(end);end+=count;return result;}
};
static void Same(const rtr::AttributeRanges& actual,const Reference& old){
    Check(actual.end==old.end&&actual.free.size()==old.free.size(),"Range state differs from stable first-fit reference");
    auto at=actual.free.begin();for(auto value:old.free){Check(at->first==value.offset&&at->second==value.count,"Range extent differs from reference");++at;}
}
static void Ranges(){
    rtr::AttributeRanges actual;Reference old;std::mt19937 random(722);std::vector<Reference::Range> live;
    for(int i=0;i<50000;++i){
        if(live.empty()||(random()%3)!=0){uint32_t count=1+random()%128;auto a=actual.Allocate(count),b=old.Allocate(count);Check(a==b,"First-fit placement changed");live.push_back({a,count});}
        else{size_t index=random()%live.size();auto value=live[index];actual.Release(value.offset,value.count);old.Release(value.offset,value.count);live[index]=live.back();live.pop_back();}
        Same(actual,old);
    }
    std::shuffle(live.begin(),live.end(),random);for(auto value:live){actual.Release(value.offset,value.count);old.Release(value.offset,value.count);Same(actual,old);}
    Check(!actual.end&&actual.free.empty(),"Complete release did not trim arena");
    actual.end=UINT32_MAX-1;bool failed=false;try{actual.Allocate(2);}catch(const std::runtime_error&){failed=true;}Check(failed&&actual.end==UINT32_MAX-1,"Overflow changed arena state");
    std::cout<<"ATTRIBUTE_RANGES PASS randomOperations=50000 stableFirstFit=1 completeTrim=1 overflow=1\n";
}
template<class F> static double Time(F&& work){auto begin=std::chrono::steady_clock::now();work();return std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-begin).count();}
static void Benchmark(){
    constexpr uint32_t n=8192;Reference old;rtr::AttributeRanges actual;for(uint32_t i=0;i<n;++i){old.Allocate(3);actual.Allocate(3);}
    double before=Time([&]{for(uint32_t i=0;i<n;i+=2)old.Release(i*3,3);for(uint32_t i=1;i<n;i+=2)old.Release(i*3,3);});
    double after=Time([&]{for(uint32_t i=0;i<n;i+=2)actual.Release(i*3,3);for(uint32_t i=1;i<n;i+=2)actual.Release(i*3,3);});Same(actual,old);
    std::cout<<"ATTRIBUTE_RELEASE_OBSERVATION oldMs="<<before<<" newMs="<<after<<" releases="<<n<<"\n";
}
static void Reader(){
    constexpr uint32_t n=4096;std::vector<uint8_t> packet(1+4+n*4);packet[0]=0x7f;std::memcpy(packet.data()+1,&n,4);
    for(uint32_t i=0;i<n;++i){float value=float(i)*.125f;std::memcpy(packet.data()+5+i*4,&value,4);}
    RtrResourceReader viewReader(packet.data(),packet.size()),copyReader(packet.data(),packet.size());viewReader.take(1);copyReader.take(1);auto view=viewReader.floatView();auto copy=copyReader.floats();
    Check(view.size()==n&&view.data()==packet.data()+5,"View did not borrow unaligned packet");for(uint32_t i=0;i<n;++i)Check(view[i]==copy[i],"Unaligned float view changed values");viewReader.finish();copyReader.finish();
    float nan=std::numeric_limits<float>::quiet_NaN();std::memcpy(packet.data()+5,&nan,4);bool bad=false;try{RtrResourceReader r(packet.data()+1,packet.size()-1);r.floatView();}catch(const std::invalid_argument&){bad=true;}Check(bad,"View accepted nonfinite input");
    bad=false;try{RtrResourceReader r(packet.data()+1,packet.size()-2);r.floatView();}catch(const std::invalid_argument&){bad=true;}Check(bad,"View accepted truncated packet");
    std::cout<<"RESOURCE_FLOAT_VIEW PASS unalignedValues=4096 nonfinite=1 truncated=1 borrowed=1\n";
}
static void Lookup(){
    struct PoisonedPair {
        float x,y;
        PoisonedPair():x(std::numeric_limits<float>::quiet_NaN()),y(x){}
        PoisonedPair(float a,float b):x(a),y(b){}
    };
    auto values=rtr::DefaultHostResponseLookup<PoisonedPair>();
    for(size_t i=0;i<values.size();++i){
        Check(values[i].x==(i<256?float(i%16)/15:0)&&values[i].y==(i<256?float(i/16)/15:0),
              "Legacy lookup left uninitialized host response lanes");
    }
    std::cout<<"HOST_RESPONSE_DEFAULTS PASS poisonedConstructor=1 baselinePairs=256 initializedTailPairs=384\n";
}
int main(){try{Ranges();Reader();Lookup();Benchmark();return 0;}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
