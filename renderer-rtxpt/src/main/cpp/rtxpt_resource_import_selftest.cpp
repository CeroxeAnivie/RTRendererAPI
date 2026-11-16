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
#include "rtxpt_attribute_capacity.h"
#include "rtxpt_memory_resources.h"
#include "rtxpt_host_response_lookup.h"
#include "rtxpt_flat_normals.h"
#include "rtxpt_diagnostics.h"
#include "rtxpt_mesh_input_lease.h"
#include "rtxpt_receiver_upload.h"

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
static void FragmentedAllocations(){
    // Thousands of holes too small for the next batch used to be walked for
    // every allocation. First-fit offsets and arena high-water must not change.
    constexpr uint32_t holes=16384,requests=4096;
    Reference old;rtr::AttributeRanges actual;
    for(uint32_t i=0;i<holes;++i){old.Allocate(3);actual.Allocate(3);old.Allocate(1);actual.Allocate(1);}
    for(uint32_t i=0;i<holes;++i){old.Release(i*4,3);actual.Release(i*4,3);}
    std::vector<uint32_t> expected,observed;expected.reserve(requests);observed.reserve(requests);
    const auto before=Time([&]{for(uint32_t i=0;i<requests;++i)expected.push_back(old.Allocate(4));});
    const auto after=Time([&]{for(uint32_t i=0;i<requests;++i)observed.push_back(actual.Allocate(4));});
    Check(expected==observed,"Fragmented batch changed allocation offsets");Same(actual,old);
    for(uint32_t i=0;i<holes;++i){Check(actual.Allocate(3)==old.Allocate(3),"Indexed first-fit skipped an exact hole");}
    Same(actual,old);
    bool overlap=false;try{actual.Release(0,4);actual.Release(1,1);}catch(const std::logic_error&){overlap=true;}
    Check(overlap,"Overlapping range release accepted");
    std::cout<<"ATTRIBUTE_FRAGMENTATION PASS holes="<<holes<<" requests="<<requests<<" oldMs="<<before<<" newMs="<<after<<" stableFirstFit=1 overlap=1\n";
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
static void PositionBounds(){
    std::mt19937 random(723);
    for(size_t count=1;count<2048;count+=7){
        std::vector<uint8_t> storage(count*12+3);const auto data=storage.data()+3;
        std::array<float,3> low,high;low.fill(INFINITY);high.fill(-INFINITY);
        for(size_t v=0;v<count;++v)for(unsigned axis=0;axis<3;++axis){
            const float value=(random()%7==0)?std::copysign(0.f,(random()%2)?-1.f:1.f):float(int(random()%8001)-4000)*.125f;
            std::memcpy(data+(v*3+axis)*4,&value,4);
            low[axis]=low[axis]<value?low[axis]:value;high[axis]=high[axis]>value?high[axis]:value;
        }
        const auto result=RtrValidatedPositionBounds({data,count*3});
        Check(std::memcmp(low.data(),result.minimum.data(),12)==0&&std::memcmp(high.data(),result.maximum.data(),12)==0,"SIMD bounds differ from scalar union");
        for(size_t v=0;v<count;v+=std::max(size_t(1),count/13)){
            float before;std::memcpy(&before,data+v*12,4);const float invalid=std::numeric_limits<float>::infinity();
            std::memcpy(data+v*12,&invalid,4);bool rejected=false;
            try{RtrValidatedPositionBounds({data,count*3});}catch(const std::invalid_argument&){rejected=true;}
            Check(rejected,"SIMD bounds accepted nonfinite position");std::memcpy(data+v*12,&before,4);
        }
    }
    std::cout<<"POSITION_BOUNDS PASS unaligned=1 exactScalar=1 nonfinite=1\n";
}
static void PackedNormals(){
    const auto pack=[](const std::array<float,3>& normal){uint32_t result=0;for(unsigned axis=0;axis<3;++axis){uint32_t bits;std::memcpy(&bits,&normal[axis],4);result=result*31+bits;}return result;};
    std::mt19937 random(724);
    for(unsigned trial=0;trial<600;++trial){
        const size_t quads=1+random()%64;std::vector<float> positions(quads*12);std::vector<uint32_t> indices(quads*6);std::vector<float> uv;
        for(size_t quad=0;quad<quads;++quad){
            const float x=float(int(random()%100)-50),y=float(int(random()%100)-50),z=float(int(random()%100)-50);
            const std::array<float,12> points{x,y,z,x+1,y,z,x+1,y+1,z,x,y+1,z};
            std::memcpy(positions.data()+quad*12,points.data(),48);
            constexpr uint32_t q[6]{0,1,2,2,3,0};for(unsigned c=0;c<6;++c)indices[quad*6+c]=uint32_t(quad*4)+q[c];
            if(trial%4==0)positions[quad*12+8]+=.25f;
            if(trial%11==0)positions[quad*12+4]=positions[quad*12+1];
        }
        if(trial%3==0)uv.resize(quads*8);
        auto view=[](const std::vector<float>& values){return RtrResourceReader::FloatView(reinterpret_cast<const uint8_t*>(values.data()),values.size());};
        auto index=[&](uint32_t corner){return indices[corner];};
        const auto old=RtrFlatNormals::PlanCanonical(view(positions),view(uv),uint32_t(indices.size()),index);
        for(bool proof:{true,false}){
            const auto current=RtrFlatNormals::PlanPackedCanonical(view(positions),view(uv),uint32_t(indices.size()),index,proof,pack);
            Check(current.expanded==old.expanded&&current.normals.size()==old.normals.size()/3,"Packed plan changed canonical identity");
            for(size_t n=0;n<current.normals.size();++n)Check(current.normals[n]==pack({old.normals[n*3],old.normals[n*3+1],old.normals[n*3+2]}),"Packed plan changed normal bits");
        }
    }
    std::cout<<"PACKED_NORMALS PASS cases=1200 exactFloatDecision=1 uvExpanded=1 generic=1\n";
}
static void ImportAggregation(){
    RtrDiagnostics::Reset();RtrDiagnostics::Configure(2);
    {RtrDiagnostics::Scope parent(RtrDiagnostics::Import);
     {RtrDiagnostics::AggregateScopes<2> parts({RtrDiagnostics::ImportNormals,RtrDiagnostics::ImportOwnership});
      for(unsigned i=0;i<1000;++i){parts.Next(0,1);parts.Next(1,1);}}}
    Check(RtrDiagnostics::written==3,"Import aggregation emitted per-mesh events");
    uint64_t sum=0;for(unsigned i=0;i<2;++i){const auto& part=RtrDiagnostics::events[i];Check(part[8]==1000&&(part[11]&RtrDiagnostics::Aggregated)&&part[16]==UINT64_MAX,"Import aggregation lost totals or CPU-unavailable flag");sum+=part[6];}
    const auto& parent=RtrDiagnostics::events[2];Check(sum<=parent[6]&&parent[7]==parent[6]-sum,"Import aggregate children double-counted");
    RtrDiagnostics::Reset();
    {RtrDiagnostics::AggregateScopes<1> off({RtrDiagnostics::ImportNormals});off.Next(0,1);}
    Check(!RtrDiagnostics::written,"OFF import aggregation recorded events");
    std::cout<<"IMPORT_AGGREGATION PASS repeatedSpans=2000 events=3 off=1\n";
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
static void AttributeCapacity(){
    constexpr size_t MiB=1024*1024,Page=64*1024,Limit=512*MiB;
    constexpr size_t corners=3318040ull*3,live=corners*16;
    const auto cold=rtr::AttributeBufferCapacity(live,0,16);
    Check(cold>=live&&cold-live<Page,"Cold attributes reserve a scene-sized tail");
    size_t capacity=0,growths=0,migrations=0;
    for(size_t required=16;required<=300*MiB;required+=310123){auto next=rtr::AttributeBufferCapacity(required,capacity,16);
        Check(next>=required&&next>=capacity&&next<=Limit,"Attribute growth lost capacity");
        if(next!=capacity){migrations+=capacity;++growths;if(required>=16*MiB)Check(next-required<=16*MiB+Page,"Attribute growth headroom exceeds bound");}capacity=next;}
    Check(rtr::AttributeBufferCapacity(Limit,0,16)==Limit,"Maximum attributes rejected");
    bool rejected=false;try{rtr::AttributeBufferCapacity(Limit+1,0,16);}catch(const std::runtime_error&){rejected=true;}
    Check(rejected,"Oversized attributes accepted");
    std::cout<<"ATTRIBUTE_CAPACITY PASS liveBytes="<<live<<" oldColdBytes="<<256*MiB<<" newColdBytes="<<cold
        <<" oldColdSlackBytes="<<256*MiB-live<<" newColdSlackBytes="<<cold-live<<" growths="<<growths<<" migrationBytes="<<migrations<<" limitBytes="<<Limit<<"\n";
}
static void CombinedMeshInputOwnership(){
    using namespace rtr::upstream;
    auto accounting=std::make_shared<MeshInputRetention>();
    std::array<std::vector<uint8_t>,7> channels;
    std::array<MeshInputLease::Range,7> ranges{};size_t total=0;
    for(size_t i=0;i<channels.size();++i){
        channels[i].resize(i*13);for(size_t j=0;j<channels[i].size();++j)channels[i][j]=uint8_t(i*17+j);
        ranges[i]={channels[i].data(),channels[i].size()};total+=channels[i].size();
    }
    std::shared_ptr<const MeshInputLease> receiver;
    {auto lease=std::make_shared<const MeshInputLease>(ranges,accounting);receiver=lease;
     Check(lease->bytes.size()==total&&accounting->bytes==total&&accounting->packets==1,"Combined input accounting differs");
     size_t offset=0;for(size_t i=0;i<ranges.size();++i){
        Check(!ranges[i].size||std::memcmp(lease->bytes.data()+offset,channels[i].data(),ranges[i].size)==0,"Combined input channel bits differ");offset+=ranges[i].size;
     }
     for(auto& channel:channels)std::fill(channel.begin(),channel.end(),0);
     Check(lease->bytes[13]==uint8_t(2*17),"Combined lease borrowed temporary transport");}
    Check(accounting->bytes==total&&accounting->packets==1,"Receiver lost live geometry owner");receiver.reset();
    Check(accounting->bytes==0&&accounting->packets==0,"Combined owner not released by last user");
    auto generated=std::make_shared<const std::vector<uint32_t>>(std::initializer_list<uint32_t>{1,2,3});
    {auto lease=std::make_shared<const MeshInputLease>(ranges,generated,12,accounting);
     Check(accounting->bytes==total+12,"Combined generated normal bytes missing");}
    Check(accounting->bytes==0,"Generated combined owner leaked");
    bool rejected=false;ranges[0]={nullptr,1};try{MeshInputLease invalid(ranges,accounting);}catch(const std::invalid_argument&){rejected=true;}
    Check(rejected&&accounting->bytes==0,"Missing combined input accepted");
    std::cout<<"COMBINED_INPUT_OWNER PASS channels=7 exactBits=1 independentTransport=1 lastOwnerRelease=1 generatedAccounting=1\n";
}
static void ReceiverUpload(){
    struct Pair{float x,y;};struct Vertex{Pair baseline;uint32_t color,reserved;};static_assert(sizeof(Vertex)==16);
    rtr::ReceiverUploadStorage<Vertex> storage;std::mt19937 random(727);
    for(unsigned trial=0;trial<1600;++trial){
        const size_t count=1+random()%1200,vertices=1+random()%300;
        std::vector<uint32_t> indices(count),colors(vertices),overrideColors(count);
        std::vector<float> baseline(vertices*2),overrides(count*2);
        for(auto& index:indices)index=random()%vertices;
        for(auto& value:colors)value=random();for(auto& value:overrideColors)value=random();
        for(auto& value:baseline)value=float(int(random()%200)-100)/100;
        for(auto& value:overrides)value=float(int(random()%200)-100)/100;
        baseline[0]=-0.f;overrides[0]=-0.f;
        // Deliberately offset all indexed input channels to exercise unaligned wire reads.
        std::vector<uint8_t> rawIndices(count*4+1),rawBaseline(vertices*8+1),rawColors(vertices*4+1);
        std::memcpy(rawIndices.data()+1,indices.data(),count*4);std::memcpy(rawBaseline.data()+1,baseline.data(),vertices*8);std::memcpy(rawColors.data()+1,colors.data(),vertices*4);
        const bool indexed=(trial&1)!=0,hasBaseline=(trial&2)!=0,hasColors=(trial&4)!=0,publicBaseline=(trial&8)!=0,publicColors=(trial&16)!=0;
        // Unindexed channels use corner extents, matching legacy publications.
        if(!indexed){baseline=overrides;colors=overrideColors;rawBaseline.resize(count*8+1);rawColors.resize(count*4+1);
            std::memcpy(rawBaseline.data()+1,baseline.data(),count*8);std::memcpy(rawColors.data()+1,colors.data(),count*4);}
        storage.reserve(count);std::memset(storage.data(),0xcd,count*sizeof(Vertex));
        rtr::PackReceiverUpload(storage.data(),count,RtrInputSpan<uint32_t>{rawIndices.data()+1,indexed?count:0},
            RtrInputSpan<float>{rawBaseline.data()+1,hasBaseline?baseline.size():0},
            RtrInputSpan<uint32_t>{rawColors.data()+1,hasColors?colors.size():0},
            publicBaseline?overrides.data():nullptr,publicColors?overrideColors.data():nullptr);
        for(size_t i=0;i<count;++i){const auto vertex=indexed?indices[i]:i;
            const Vertex expected{{publicBaseline?overrides[i*2]:hasBaseline?baseline[vertex*2]:0,
                publicBaseline?overrides[i*2+1]:hasBaseline?baseline[vertex*2+1]:0},
                publicColors?overrideColors[i]:hasColors?colors[vertex]:0xffffffff,0};
            Check(std::memcmp(storage.data()+i,&expected,sizeof(Vertex))==0,"Final receiver upload differs from initialized reference");}
    }
    std::cout<<"RECEIVER_UPLOAD PASS cases=1600 allChannels=1 overrides=1 exactBits=1 unaligned=1 initializedPadding=1 reusedStorage=1\n";
}
static void TransferredMeshInputOwnership(){
    using namespace rtr::upstream;auto accounting=std::make_shared<MeshInputRetention>();
    auto first=std::make_shared<const MeshInputBlock>(8192),second=std::make_shared<const MeshInputBlock>(4096);
    std::weak_ptr<const MeshInputBlock> weakFirst=first,weakSecond=second;
    for(size_t i=0;i<first->size;++i)first->data[i]=uint8_t(i*17);
    const auto* address=first->data.get();std::shared_ptr<const MeshInputLease> receiver;
    {MeshInputBlocks transaction{first,second};
        auto generated=std::make_shared<const std::vector<uint32_t>>(std::initializer_list<uint32_t>{1,2,3});
        auto lease=std::make_shared<const MeshInputLease>(first,generated,12,accounting);receiver=lease;
        Check(lease->bytes.data()==address&&lease->bytes[119]==uint8_t(119*17),"Transferred geometry was copied or changed");
        Check(accounting->bytes==8204&&accounting->packets==1,"Transferred allocation accounting differs");
        first.reset();second.reset();transaction.clear();
        Check(weakSecond.expired()&&!weakFirst.expired(),"A retained mesh pinned unrelated transaction allocation");
    }
    Check(accounting->bytes==8204&&!weakFirst.expired(),"Receiver lost transferred geometry lifetime");receiver.reset();
    Check(accounting->bytes==0&&accounting->packets==0&&weakFirst.expired(),"Transferred mesh final owner leaked");
    std::cout<<"TRANSFERRED_INPUT_OWNER PASS sameAddress=1 independentMeshLifetime=1 lastOwnerRelease=1 exactAccounting=1\n";
}
static void VectorChannelValidation(){
    std::mt19937 random(51693);size_t invalidCases=0;
    for(unsigned trial=0;trial<4000;++trial){
        const size_t count=random()%1001;std::vector<uint8_t> bytes(count*4+1);
        bool finite=true,baseline=true;
        for(size_t i=0;i<count;++i){uint32_t bits=random();if(trial%3==0)bits=std::bit_cast<uint32_t>(float(random()%101)/100);
            if(i==0&&trial%11==0)bits=0x80000000u;
            float value;std::memcpy(&value,&bits,4);finite&=std::isfinite(value);baseline&=value>=0&&value<=1;
            std::memcpy(bytes.data()+1+i*4,&bits,4);}
        bool accepted=true;try{RtrValidateFiniteChannel(bytes.data()+1,count);}catch(const std::invalid_argument&){accepted=false;}Check(accepted==finite,"Vector finite validator differs");
        accepted=true;try{RtrValidateBaselineChannel(bytes.data()+1,count);}catch(const std::invalid_argument&){accepted=false;}Check(accepted==baseline,"Vector baseline validator differs");
        invalidCases+=!finite+!baseline;
    }
    for(unsigned trial=0;trial<4000;++trial){
        const uint32_t vertices=4*(1+random()%1000),count=vertices/4*6;
        std::vector<uint32_t> indices(count);constexpr std::array<uint32_t,6> quad{0,1,2,2,3,0};
        for(uint32_t i=0;i<count;++i)indices[i]=i/6*4+quad[i%6];
        if(trial%4==1)indices[random()%count]=vertices;
        else if(trial%4==2)indices[random()%count]=UINT32_MAX;
        else if(trial%4==3)indices[random()%count]=random()%vertices;
        bool valid=true,independent=true;for(uint32_t i=0;i<count;++i){valid&=indices[i]<vertices;independent&=indices[i]==i/6*4+quad[i%6];}
        std::vector<uint8_t> bytes(size_t(count)*4+1);std::memcpy(bytes.data()+1,indices.data(),count*4);
        bool accepted=true,actual=false;try{actual=RtrValidateTriangleIndices(bytes.data()+1,count,vertices);}catch(const std::invalid_argument&){accepted=false;}
        Check(valid==accepted&&(!valid||independent==actual),"Vector index validator differs");invalidCases+=!valid;
    }
    std::cout<<"VECTOR_CHANNEL_VALIDATION PASS cases=8000 unaligned=1 scalarOracle=1 NaNInf=1 signedZero=1 invalidIndices=1 rejected="<<invalidCases<<"\n";
}
int main(){std::cout.setf(std::ios::unitbuf);try{TransferredMeshInputOwnership();VectorChannelValidation();CombinedMeshInputOwnership();ReceiverUpload();PositionBounds();PackedNormals();ImportAggregation();AttributeCapacity();Ranges();FragmentedAllocations();Reader();Lookup();Benchmark();return 0;}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
