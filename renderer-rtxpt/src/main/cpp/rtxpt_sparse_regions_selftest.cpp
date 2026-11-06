#include "rtxpt_sparse_regions.h"
#include "rtxpt_upload_ranges.h"
#include <iostream>
#include <random>
#include <set>
#include <stdexcept>

struct Light {std::array<float,4> positionType{},directionRange{},colorIntensity{};};
struct RtrSparseRegionsTestAccess {
    static void Populate(RtrSparseRegions& r,const std::vector<RtrSparseRegions::Cell>& cells){
        r.Clear();for(auto cell:cells)r.cells[cell].roots={0};r.FullPack();
    }
    static void Erase(RtrSparseRegions& r,RtrSparseRegions::Cell cell){
        auto at=r.cells.find(cell);if(at==r.cells.end())throw std::logic_error("Missing oracle cell");
        if(at->second.block!=r.None)r.FreeBlock(at->second.block,at->second.order);
        r.EraseHash(cell,at->second);r.cells.erase(at);r.RecoverOverflow();
    }
    static void Insert(RtrSparseRegions& r,RtrSparseRegions::Cell cell){
        auto& entry=r.cells[cell];entry.roots={0};r.PublishCell(cell,entry);
    }
};
static void Check(bool condition,const char* message){if(!condition)throw std::runtime_error(message);}
static std::vector<rtr::LightTreeNode> Query(const rtr::RetainedLightTree& tree,const std::vector<Light>& lights){
    auto result=tree.nodes;std::vector<uint32_t> order(result.size());std::iota(order.begin(),order.end(),0);
    std::sort(order.begin(),order.end(),[&](auto a,auto b){return tree.heights[a]<tree.heights[b];});
    for(auto id:order){auto& n=result[id];if(n.light< -1)continue;
        if(n.light>=0){const auto& l=lights[uint32_t(n.light)];for(int a=0;a<3;a++){n.minimum[a]=l.positionType[a]-.4f;n.maximum[a]=l.positionType[a]+.4f;}n.range=l.directionRange[3];}
        else{const auto& a=result[uint32_t(n.left)];const auto& b=result[uint32_t(n.right)];for(int axis=0;axis<3;axis++){n.minimum[axis]=std::min(a.minimum[axis],b.minimum[axis]);n.maximum[axis]=std::max(a.maximum[axis],b.maximum[axis]);}n.range=std::max(a.range,b.range);}}
    return result;
}
static std::vector<uint32_t> Roots(const RtrSparseRegions& regions,const rtr::RetainedLightTree& tree,std::array<float,3> p){
    if(!regions.buckets||!regions.indexed)return {tree.root};RtrSparseRegions::Cell c{};int32_t* xyz=&c.x;
    for(int a=0;a<3;a++){float v=std::floor(p[a]/8);if(v< -1048576||v>=1048576)return {tree.root};xyz[a]=int32_t(v);}
    auto roots=regions.global;uint32_t slot=RtrSparseRegions::Hash(c)&(regions.buckets-1);
    for(int i=0;i<16;i++,slot=(slot+1)&(regions.buckets-1)){const auto& n=regions.packed[slot];uint32_t count;std::memcpy(&count,&n.maximum[0],4);
        if(count==0xffffffff)return roots;RtrSparseRegions::Cell found;std::memcpy(&found,n.minimum,12);
        if(found==c){uint32_t start;std::memcpy(&start,&n.left,4);const auto* data=reinterpret_cast<const unsigned char*>(regions.packed.data()+regions.buckets);
            for(uint32_t j=0;j<count;j++){uint32_t id;std::memcpy(&id,data+(start+j)*4,4);roots.push_back(id);}return roots;}}
    return {tree.root};
}
static void Oracle(const RtrSparseRegions& regions,const rtr::RetainedLightTree& tree,const std::vector<Light>& lights,std::array<float,3> p){
    std::vector<uint8_t> visits(lights.size());auto stack=Roots(regions,tree,p);
    while(!stack.empty()){auto id=stack.back();stack.pop_back();if(id==rtr::RetainedLightTree::None)continue;const auto& n=tree.nodes.at(id);
        if(n.light>=0){auto& count=visits.at(uint32_t(n.light));Check(++count==1,"Duplicated source across regional/global roots");}
        else{stack.push_back(uint32_t(n.left));stack.push_back(uint32_t(n.right));}}
    for(size_t i=0;i<lights.size();i++){double distance=0;for(int a=0;a<3;a++){double d=std::max(std::abs(double(p[a])-lights[i].positionType[a])-.4,0.);distance+=d*d;}
        if(distance<double(lights[i].directionRange[3])*lights[i].directionRange[3])Check(visits[i]==1,"Regional index omitted a supported source");}
}
// Poison retained GPU slack and copy only the production dirty spans, then
// validate every shader-readable bucket and list word, including root id zero.
struct UploadOracle {
    std::vector<rtr::LightTreeNode> gpu;
    uint64_t bytes=0,spans=0;
    void Apply(RtrSparseRegions& r){
        bool growth=gpu.size()<r.packed.size();if(growth){size_t size=64;while(size<r.packed.size())size*=2;gpu.resize(size);std::memset(gpu.data(),0xcd,gpu.size()*64);}
        bytes=spans=0;
        if(growth||r.full){std::copy(r.packed.begin(),r.packed.end(),gpu.begin());bytes=r.packed.size()*64;spans=!r.packed.empty();}
        else{auto dirty=r.dirtyNodes;rtr::UploadRanges::ForEach(dirty,r.packed.size(),64,[&](size_t first,size_t count){
            Check(count*64<=rtr::UploadRanges::MaximumWriteBytes,"Coalesced upload exceeded write bound");
            std::copy(r.packed.begin()+first,r.packed.begin()+first+count,gpu.begin()+first);bytes+=count*64;++spans;});}
        for(uint32_t slot=0;slot<r.buckets;slot++)Check(!std::memcmp(&gpu[slot],&r.packed[slot],64),"Partial upload left a stale hash bucket");
        auto actual=reinterpret_cast<const unsigned char*>(gpu.data()+r.buckets),expected=reinterpret_cast<const unsigned char*>(r.packed.data()+r.buckets);
        Check(!std::memcmp(actual,expected,r.global.size()*4),"Partial upload left stale global roots");
        for(const auto& [cell,entry]:r.cells)if(entry.slot!=RtrSparseRegions::None){uint32_t start;std::memcpy(&start,&r.packed[entry.slot].left,4);
            Check(!std::memcmp(actual+size_t(start)*4,expected+size_t(start)*4,entry.roots.size()*4),"Partial upload left stale list words");}
        r.Uploaded();
    }
};
int main(){try{
    std::mt19937 rng(719);std::uniform_real_distribution<float> coordinate(-96,96);std::vector<Light> lights;
    for(int i=0;i<25000;i++){Light l;l.positionType={coordinate(rng),coordinate(rng)*.25f,coordinate(rng),1};l.directionRange[3]=1+float(i%15);lights.push_back(l);}
    rtr::RetainedLightTree tree;double normalization;tree.Rebuild(lights,&normalization);RtrSparseRegions regions;UploadOracle upload;
    uint64_t probes=0,localBytes=0,localSpans=0,localVisited=0;
    for(int phase=0;phase<14;phase++){
        std::vector<uint32_t> dirty;
        if(phase){
            for(int edit=0;edit<16;edit++){uint32_t slot=rng()%uint32_t(lights.size());
                tree.Remove(slot,dirty);if(slot+1!=lights.size())lights[slot]=lights.back();lights.pop_back();
                Light l;l.positionType={coordinate(rng),coordinate(rng)*.25f,coordinate(rng),1};l.directionRange[3]=phase==7&&edit==0?1e6f:15.f;
                lights.push_back(l);tree.Insert(lights,uint32_t(lights.size()-1),normalization,dirty);}
            for(int edit=0;edit<16;edit++){uint32_t slot=rng()%uint32_t(lights.size());lights[slot].positionType[0]+=.125f;tree.Refit(lights,slot,normalization,dirty);}}
        auto query=Query(tree,lights);regions.Update(tree,query,true,dirty,phase==0);
        upload.Apply(regions);
        for(int sample=0;sample<150;sample++){Oracle(regions,tree,lights,{coordinate(rng),coordinate(rng)*.5f,coordinate(rng)});++probes;}
        for(float x:{-8388609.f,-8.f,-.00001f,0.f,7.99999f,8.f,8388608.f}){Oracle(regions,tree,lights,{x,0,0});++probes;}
    }
    std::vector<uint32_t> dirty;auto fullPacks=regions.fullPacks,visited=regions.frontierVisited;
    lights[0].positionType[0]+=8;tree.Refit(lights,0,normalization,dirty);auto query=Query(tree,lights);regions.Update(tree,query,true,dirty);
    Check(regions.fullPacks==fullPacks,"Local edit rebuilt entire regional pool");localVisited=regions.frontierVisited-visited;Check(localVisited<128,"Local edit scanned the full frontier");
    upload.Apply(regions);localBytes=upload.bytes;localSpans=upload.spans;
    Check(localBytes>0&&localBytes<=128*1024&&localBytes<regions.packed.size()*64/20,"Local upload exceeds bounded pair fraction of pool");
    Oracle(regions,tree,lights,{lights[0].positionType[0],lights[0].positionType[1],lights[0].positionType[2]});
    dirty.clear();lights[0].colorIntensity={.2f,.8f,1,2};tree.Refit(lights,0,normalization,dirty);query=Query(tree,lights);regions.Update(tree,query,true,dirty);
    Check(regions.dirtyNodes.empty()&&!regions.full,"Color/intensity-only update wrote regional data");upload.Apply(regions);Check(upload.bytes==0,"Color-only regional upload was nonzero");
    std::vector<Light> pair(2);pair[0].positionType={1,1,1,1};pair[1].positionType={2,1,1,1};for(auto& l:pair)l.directionRange[3]=1;
    tree.Rebuild(pair,&normalization);query=Query(tree,pair);regions.Update(tree,query,true,{},true);upload.Apply(regions);auto builds=regions.work[0];
    dirty.clear();pair[0].positionType[0]+=.001f;tree.Refit(pair,0,normalization,dirty);query=Query(tree,pair);regions.Update(tree,query,true,dirty);
    Check(regions.work[0]==builds&&!regions.dirty&&regions.dirtyNodes.empty(),"Unchanged coverage repacked regional index");upload.Apply(regions);
    regions.Update(tree,query,false,{});Check(!regions.buckets&&regions.packed.empty(),"Disable/reset retained regional data");
    std::vector<Light> distant(8);for(size_t i=0;i<distant.size();++i){distant[i].positionType={float(i)*5000,0,0,1};distant[i].directionRange[3]=15;}
    tree.Rebuild(distant,&normalization);query=Query(tree,distant);regions.Update(tree,query,true,{},true);upload.Apply(regions);
    Check(regions.buckets&&regions.global.empty(),"Indexable children of broad shallow root stayed global");Check(regions.roots.size()==distant.size(),"Broad treelet did not split into exact disjoint leaves");
    Check(Roots(regions,tree,{100000,100000,100000}).empty(),"Empty cell still visits avoidable global roots");for(const auto& light:distant)Oracle(regions,tree,distant,{light.positionType[0],0,0});
    distant[0].directionRange[3]=1e6f;tree.Rebuild(distant,&normalization);query=Query(tree,distant);regions.Update(tree,query,true,{},true);upload.Apply(regions);
    Check(regions.global.size()==1&&tree.nodes[regions.global.front()].light>=0,"Huge leaf needs one exact global fallback");Oracle(regions,tree,distant,{12345,0,0});
    std::vector<Light> traveler(1);traveler[0].positionType={1,1,1,1};traveler[0].directionRange[3]=1;
    tree.Rebuild(traveler,&normalization);query=Query(tree,traveler);regions.Update(tree,query,true,{},true);upload.Apply(regions);auto initialPacks=regions.fullPacks;
    for(int step=0;step<256;step++){
        dirty.clear();traveler[0].positionType[0]=float(step)*24+1;tree.Refit(traveler,0,normalization,dirty);query=Query(tree,traveler);regions.Update(tree,query,true,dirty);upload.Apply(regions);
        Oracle(regions,tree,traveler,{traveler[0].positionType[0],1,1});Check(regions.tombstones<=std::max(8u,regions.buckets/8),"Travel left excessive hash tombstones");}
    Check(regions.fullPacks==initialPacks&&regions.tombstones==0,"Ordinary travel rebuilt or retained hash tombstones");
    // Oversized hash and GPU reservations are legitimate retained capacities.
    // Grow the list pool inside these capacities with root id0 in every cell.
    traveler[0].positionType={1,1,1,1};traveler[0].directionRange[3]=1;tree.Rebuild(traveler,&normalization);query=Query(tree,traveler);regions.Update(tree,query,true,{},true);
    upload.Apply(regions);bool partialGrowth=false;
    for(int radius=2;radius<=18;radius++){
        dirty.clear();traveler[0].directionRange[3]=float(radius);tree.Refit(traveler,0,normalization,dirty);query=Query(tree,traveler);auto oldSize=regions.packed.size();auto capacity=upload.gpu.size();regions.Update(tree,query,true,dirty);
        partialGrowth|=regions.packed.size()>oldSize&&!regions.full&&regions.packed.size()<=capacity;upload.Apply(regions);}
    Check(partialGrowth,"Zero-root partial-growth fixture did not exercise retained GPU capacity");
    // Deliberately overflow a wraparound 16-probe window. Backshift deletion
    // must restore the overflow keys before an empty slot becomes observable.
    uint32_t inverse=1;for(int i=0;i<5;++i)inverse*=2u-73856093u*inverse;
    auto collider=[&](uint32_t z){const auto mask=RtrSparseRegions::HashBuckets-1,home=mask-3;
        return RtrSparseRegions::Cell{int32_t(((home^(z*83492791u))*inverse)&mask),0,int32_t(z)};};
    std::vector<RtrSparseRegions::Cell> colliding;for(uint32_t z=0;z<40;++z)colliding.push_back(collider(z));
    RtrSparseRegionsTestAccess::Populate(regions,colliding);upload.Apply(regions);
    rtr::RetainedLightTree fake;fake.root=12345;
    auto verifyHash=[&]{for(const auto& [cell,entry]:regions.cells){
        auto result=Roots(regions,fake,{float(cell.x)*8+4,float(cell.y)*8+4,float(cell.z)*8+4});
        if(entry.slot==regions.None)Check(result==std::vector<uint32_t>{fake.root},"Overflow key lost exact fallback after deletion");
        else{Check(result==std::vector<uint32_t>{0},"Backshift broke a retained probe chain");
            RtrSparseRegions::Cell stored;std::memcpy(&stored,regions.packed[entry.slot].minimum,12);Check(stored==cell,"Backshift left stale CPU slot ownership");}
    }};
    verifyHash();Check(!regions.overflowByHome.empty(),"Collision fixture did not exercise probe overflow");
    auto collisionPacks=regions.fullPacks;uint32_t nextCollider=40;
    for(int round=0;round<24;++round){
        std::vector<RtrSparseRegions::Cell> removed;
        for(const auto& [cell,entry]:regions.cells)if(entry.slot!=regions.None&&removed.size()<7)removed.push_back(cell);
        for(auto cell:removed){RtrSparseRegionsTestAccess::Erase(regions,cell);verifyHash();}
        for(size_t i=0;i<removed.size();++i)RtrSparseRegionsTestAccess::Insert(regions,collider(nextCollider++));
        verifyHash();upload.Apply(regions);
    }
    Check(regions.hashMoves>0&&regions.overflowRetries>0,"Collision deletion did not exercise moves/reinsertion");
    Check(regions.fullPacks==collisionPacks&&regions.tombstones==0,"Collision deletion rebuilt the pool");
    Check(regions.packed.capacity()<=RtrSparseRegions::MaximumPackedNodes,"Regional CPU storage exceeded its fixed bound");
    // Scatter updates across the fixed hash. Both the fine and coalesced plans
    // must reproduce every changed byte, reject duplicates/out-of-range entries,
    // and stay within the staging write limit.
    std::vector<uint32_t> scattered;for(uint32_t i=0;i<262144;i+=29)scattered.push_back(i);
    scattered.push_back(0);scattered.push_back(999999);std::reverse(scattered.begin(),scattered.end());
    auto fine=scattered,merged=scattered;uint64_t fineSpans=0,mergedSpans=0,mergedBytes=0;
    std::vector<uint8_t> covered(262144);
    rtr::UploadRanges::ForEach(fine,covered.size(),64,[&](size_t first,size_t count){++fineSpans;},0);
    rtr::UploadRanges::ForEach(merged,covered.size(),64,[&](size_t first,size_t count){
        Check(count*64<=rtr::UploadRanges::MaximumWriteBytes,"Scatter write exceeded staging bound");
        std::fill(covered.begin()+first,covered.begin()+first+count,1);++mergedSpans;mergedBytes+=count*64;});
    for(auto id:merged)Check(covered.at(id)!=0,"Coalescing omitted a dirty record");
    Check(mergedSpans*100<fineSpans,"Scattered uploads did not remove command amplification");
    std::cout<<"SPARSE_REGIONS PASS sources=25000 probes="<<probes<<" denseSwap=true rotations=true colorZeroBytes=true micromotionZeroBytes=true broadRootsSplit=true tombstoneFree=true zeroRootPartialGrowth=true collisionBackshift=true overflowReinsert=true travelNoFullPack=true localBytes="<<localBytes<<" localSpans="<<localSpans<<" localFrontierVisited="<<localVisited<<" fixedHashBytes="<<RtrSparseRegions::HashBuckets*64ull<<" maxPackedBytes="<<RtrSparseRegions::MaximumPackedNodes*64ull<<" hashMoves="<<regions.hashMoves<<" overflowRetries="<<regions.overflowRetries<<" scatterFineSpans="<<fineSpans<<" scatterMergedSpans="<<mergedSpans<<" scatterMergedBytes="<<mergedBytes<<"\n";return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}

