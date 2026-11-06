#pragma once
#include "rtxpt_retained_light_tree.h"
#include "rtxpt_sparse_regions.h"
#include "rtxpt_upload_ranges.h"
#include <array>
#include <cstring>
#include <cstdlib>
#include <string>
#include <unordered_map>
#include <unordered_set>

// Finite-support light-field sources. No SceneGraph object is allocated per source.
class RtrSparseLighting {
public:
    static constexpr size_t Maximum=262144,Stride=24;
    struct Record {std::array<float,4> position, direction, color, extent, cone, identity;};
    struct Proxy {std::array<float,4> positionType,directionRange,colorIntensity,unused;};
    static_assert(sizeof(Record)==96);
    std::vector<Record> records;
    std::vector<Proxy> proxies;
    std::vector<int64_t> ids;
    std::unordered_map<int64_t,uint32_t> slots;
    rtr::RetainedLightTree tree;
    std::vector<rtr::LightTreeNode> queryNodes;
    RtrSparseRegions regions;
    double normalization=1;
    std::vector<uint32_t> dirtyRecords,dirtyNodes;
    bool full=true,headerDirty=true;
    std::array<uint32_t,32> groupCounts{};
    uint32_t groupMask=0;
    size_t visibleMaxCount=0,channelMaxCount=0,dynamicCount=0,linearCount=0;
    void Group(const Record& record,bool add){if(record.cone[3]==2){if(add)++channelMaxCount;else --channelMaxCount;}if(uint32_t(record.identity[3])&2){if(add)++linearCount;else --linearCount;}if(uint32_t(record.identity[3])&1){if(add)++dynamicCount;else --dynamicCount;}if(record.cone[3]==1){if(add)++visibleMaxCount;else --visibleMaxCount;}if(record.cone[3]==0)return;const auto group=uint32_t(record.cone[2]);
        if(add){++groupCounts[group];groupMask|=1u<<group;}else if(--groupCounts[group]==0)groupMask&=~(1u<<group);}
    bool SmallGroups()const{return (groupMask&~15u)==0;}
    bool PairGroups()const{return (groupMask&~3u)==0;}
    bool VisibleMax()const{return !records.empty()&&visibleMaxCount==records.size();}
    bool ChannelMax()const{return !records.empty()&&channelMaxCount==records.size();}
    bool ChannelExecutor()const{const char* disabled=std::getenv("RTR_RTXPT_DISABLE_SPARSE_CHANNEL_EXECUTOR");return ChannelMax()&&(!disabled||std::string(disabled)!="1");}
    bool SingleGroup()const{return (groupMask&(groupMask-1))==0;}
    uint32_t recordCapacity=0,nodeCapacity=0,regionCapacity=0;
    nvrhi::BufferHandle sourceBuffer,treeBuffer;
    struct Retired {nvrhi::BufferHandle sources,nodes;nvrhi::EventQueryHandle graphics,compute;};
    std::vector<Retired> retired;
    std::array<uint64_t,8> work{}; // represented,patches,upserts,removals,nodesVisited,recordBytes,nodeBytes,growths
    static void Validate(const Record& r){
        const auto* p=reinterpret_cast<const float*>(&r);
        // Two identity words carry uint bits, not floating-point values.
        for(size_t i=0;i<Stride;i++)if(i!=21&&i!=22&&!std::isfinite(p[i]))throw std::invalid_argument("Non-finite sparse source field");
        int shape=int(r.position[3]),group=int(r.cone[2]),composition=int(r.cone[3]);
        if(shape<0||shape>2||float(shape)!=r.position[3]||group<0||group>=32||float(group)!=r.cone[2]
            ||composition<0||composition>2||float(composition)!=r.cone[3]||r.direction[3]<=0
            ||r.extent[3]<=0||r.extent[3]>32
            ||(r.identity[0]!=0&&r.identity[0]!=1)||r.identity[3]<0||r.identity[3]>27||r.identity[3]!=float(uint32_t(r.identity[3])))throw std::invalid_argument("Invalid sparse source record");
        const auto flags=uint32_t(r.identity[3]),visibility=(flags>>2)&3u;const bool radius=(flags&16u)!=0;
        if(visibility>2||(radius&&(visibility!=2||r.cone[0]<0)))throw std::invalid_argument("Invalid independent visibility radius");
        if(!radius&&(r.cone[0]<r.cone[1]||r.cone[0]>1||r.cone[1]<-1))throw std::invalid_argument("Invalid sparse source cone");
        if(visibility&&shape==2)throw std::invalid_argument("Center support visibility applies to POINT or BOX");
        if(visibility==2&&!radius&&(r.extent[0]!=r.extent[1]||r.extent[0]!=r.extent[2]))throw std::invalid_argument("Sphere visibility requires equal half extents or an explicit radius");
        double length=0;for(int a=0;a<3;a++){
            if(r.extent[a]<0||r.color[a]<0||(shape!=1&&r.extent[a]!=0))throw std::invalid_argument("Invalid sparse source extent/color");
            length+=double(r.direction[a])*r.direction[a];
        }
        if(r.color[3]<0||std::abs(length-1)>.002)throw std::invalid_argument("Invalid sparse source intensity/direction");
        if(!std::isfinite(SupportRadius(r)))throw std::invalid_argument("Sparse source support overflow");
    }
    static float SupportRadius(const Record& r){return float(double(r.direction[3])+std::sqrt(double(r.extent[0])*r.extent[0]+double(r.extent[1])*r.extent[1]+double(r.extent[2])*r.extent[2]));}
    static Proxy MakeProxy(const Record& r){Proxy p{};p.positionType=r.position;p.positionType[3]=1;p.directionRange[3]=SupportRadius(r);return p;}
    void Apply(const std::vector<int64_t>& changed,const std::vector<float>& values,const std::vector<int64_t>& removed,bool reset){
        if(values.size()!=changed.size()*Stride||(reset&&!removed.empty()))throw std::invalid_argument("Invalid sparse source batch");
        std::unordered_set<int64_t> touched;std::vector<Record> prepared(changed.size());size_t added=0;
        for(auto id:removed)if(id<0||!slots.count(id)||!touched.insert(id).second)throw std::invalid_argument("Unknown or duplicate sparse source removal");
        for(size_t i=0;i<changed.size();i++){
            auto id=changed[i];if(id<0||!touched.insert(id).second)throw std::invalid_argument("Duplicate sparse source ID");
            std::memcpy(&prepared[i],&values[i*Stride],sizeof(Record));Validate(prepared[i]);
            // JNI long IDs are authoritative. Float NaN payloads cannot safely
            // transport arbitrary identity bits through every supported JVM.
            uint64_t encoded=uint64_t(id);std::memcpy(&prepared[i].identity[1],&encoded,sizeof(encoded));
            added+=reset||!slots.count(id);
        }
        size_t count=(reset?0:records.size())+added-removed.size();if(count>Maximum)throw std::invalid_argument("Sparse source capacity exceeded");
        size_t capacity=std::max<size_t>(64,records.capacity());while(capacity<count)capacity*=2;
        records.reserve(capacity);proxies.reserve(capacity);ids.reserve(capacity);slots.reserve(capacity);
        // Bulk reset builds its spatial tree once after all records are present.
        // Incremental ancestor logs and a soon-replaced tree reserve are unused.
        if(!reset){tree.Reserve(capacity);dirtyRecords.reserve(dirtyRecords.size()+changed.size()+removed.size());dirtyNodes.reserve(dirtyNodes.size()+(changed.size()+removed.size())*128);}
        if(reset){records.clear();proxies.clear();ids.clear();slots.clear();dirtyRecords.clear();dirtyNodes.clear();groupCounts.fill(0);groupMask=0;visibleMaxCount=channelMaxCount=dynamicCount=linearCount=0;regions.Clear();full=true;}
        uint64_t visited=tree.visited;
        for(auto id:removed){uint32_t slot=slots.at(id),last=uint32_t(records.size()-1);Group(records[slot],false);tree.Remove(slot,dirtyNodes);slots.erase(id);
            if(slot!=last){records[slot]=records[last];proxies[slot]=proxies[last];ids[slot]=ids[last];slots.at(ids[slot])=slot;dirtyRecords.push_back(slot);}
            records.pop_back();proxies.pop_back();ids.pop_back();}
        for(size_t i=0;i<changed.size();i++){
            auto old=slots.find(changed[i]);uint32_t slot=old==slots.end()?uint32_t(records.size()):old->second;
            if(old!=slots.end())Group(records[slot],false);Group(prepared[i],true);
            if(old==slots.end()){slots.emplace(changed[i],slot);records.push_back(prepared[i]);proxies.push_back(MakeProxy(prepared[i]));ids.push_back(changed[i]);if(!reset)tree.Insert(proxies,slot,normalization,dirtyNodes);}
            else{records[slot]=prepared[i];proxies[slot]=MakeProxy(prepared[i]);tree.Refit(proxies,slot,normalization,dirtyNodes);}
            if(!reset)dirtyRecords.push_back(slot);
        }
        // Bulk publication has every source available. Spatial partitioning avoids
        // insertion-order-dependent overlap in the AVL hierarchy; later small
        // edits keep the existing bounded incremental path.
        if(reset){tree.Rebuild(proxies,&normalization);dirtyNodes.clear();}
        const auto spatialChanges=PrepareQueryNodes(reset);
        const char* disableRegions=std::getenv("RTR_RTXPT_DISABLE_SPARSE_REGIONS");
        {RtrDiagnostics::Scope spatial(RtrDiagnostics::SparseRegions,1);
            regions.Update(tree,queryNodes,records.size()>=128&&(VisibleMax()||ChannelMax())&&(!disableRegions||std::string(disableRegions)!="1"),spatialChanges,reset);}
        // No probability normalization is needed for exact spatial queries. Tree powers
        // are intentionally unused; the geometry/support bounds are authoritative.
        headerDirty=true;work[0]=records.size();++work[1];work[2]+=changed.size();work[3]+=removed.size();work[4]+=tree.visited-visited;
    }
    std::vector<uint32_t> PrepareQueryNodes(bool rebuild){
        RtrDiagnostics::Scope evidence(RtrDiagnostics::SparseQueryPreparation,rebuild?tree.nodes.size():dirtyNodes.size(),0,1,0,0,RtrDiagnostics::revision.load(),rebuild?RtrDiagnostics::CapacityGrowth:0);
        queryNodes.resize(tree.nodes.size());
        auto changed=dirtyNodes;
        if(rebuild){changed.resize(tree.nodes.size());std::iota(changed.begin(),changed.end(),0);}
        std::sort(changed.begin(),changed.end());changed.erase(std::unique(changed.begin(),changed.end()),changed.end());
        // Rotations can reorder indices. Heights, rather than allocation order,
        // guarantee that every changed child is aggregated before its parent.
        std::sort(changed.begin(),changed.end(),[&](auto a,auto b){return tree.heights[a]<tree.heights[b];});
        for(auto index:changed){
            auto& n=queryNodes[index];n=tree.nodes[index];if(n.light< -1)continue;
            uint32_t mask=0,dynamic=0;
            if(n.light>=0){const auto& r=records.at(uint32_t(n.light));
                // Query bounds enclose the emitting volume itself. Expanding a
                // center bound and also its radius paid twice for BOX extents.
                for(int a=0;a<3;a++){n.minimum[a]=r.position[a]-r.extent[a];n.maximum[a]=r.position[a]+r.extent[a];}
                n.range=r.direction[3];
                n.power=r.color[0]*r.color[3];n.directional=r.color[1]*r.color[3];n.leaves=r.color[2]*r.color[3];
                n.unused[0]=(uint32_t(r.identity[3])&2)?-1.f:r.extent[3];
                dynamic=(uint32_t(r.identity[3])&1)?0x80000000u:0;mask=1u<<uint32_t(r.cone[2]);
            }else{const auto& a=queryNodes[uint32_t(n.left)];const auto& b=queryNodes[uint32_t(n.right)];
                for(int axis=0;axis<3;axis++){n.minimum[axis]=std::min(a.minimum[axis],b.minimum[axis]);n.maximum[axis]=std::max(a.maximum[axis],b.maximum[axis]);}
                n.range=std::max(a.range,b.range);
                n.power=std::max(a.power,b.power);n.directional=std::max(a.directional,b.directional);n.leaves=std::max(a.leaves,b.leaves);
                n.unused[0]=std::min(std::abs(a.unused[0]),std::abs(b.unused[0]))*((a.unused[0]<0||b.unused[0]<0)?-1.f:1.f);
                uint32_t x,y;std::memcpy(&x,&a.unused[1],4);std::memcpy(&y,&b.unused[1],4);dynamic=(x|y)&0x80000000u;
                std::memcpy(&x,&a.unused[2],4);std::memcpy(&y,&b.unused[2],4);mask=x|y;
            }
            std::memcpy(&n.unused[2],&mask,4);
            // Low 31 bits = parent (None=0x7fffffff); high bit = any dynamic
            // descendant. Static-only nodes never depend on a dynamic winner.
            uint32_t parent=(tree.parents[index]&0x7fffffffu)|dynamic;std::memcpy(&n.unused[1],&parent,4);
        }
        return changed;
    }
    bool Ensure(nvrhi::IDevice* device){
        for(auto i=retired.begin();i!=retired.end();)if(device->pollEventQuery(i->graphics)&&device->pollEventQuery(i->compute))i=retired.erase(i);else ++i;
        if(sourceBuffer&&records.size()<=recordCapacity&&tree.nodes.size()<=nodeCapacity&&regions.packed.size()<=regionCapacity)return false;
        // These are the existing public source/index budgets, not scene-sized
        // guesses. A stable node prefix and region capacity avoid relocating
        // every region word when a moving scene crosses a power of two.
        uint32_t rc=std::max(64u,recordCapacity),nc=std::max(128u,nodeCapacity),gc=std::max(64u,regionCapacity);
        if(regions.buckets){rc=uint32_t(Maximum);nc=uint32_t(Maximum)*2;while(gc<RtrSparseRegions::MaximumPackedNodes)gc*=2;}
        else{while(rc<records.size())rc*=2;while(nc<tree.nodes.size())nc*=2;while(gc<regions.packed.size())gc*=2;}
        nvrhi::BufferDesc d;d.structStride=sizeof(Record);d.byteSize=(size_t(rc)+1)*sizeof(Record);d.debugName="Rtr sparse sources";
        d.initialState=nvrhi::ResourceStates::ShaderResource;d.keepInitialState=true;auto sources=device->createBuffer(d);
        d.structStride=64;d.byteSize=(size_t(nc)+gc)*64;d.debugName="Rtr sparse support index";auto nodes=device->createBuffer(d);
        if(!sources||!nodes)throw std::runtime_error("Could not allocate sparse lighting buffers");
        if(sourceBuffer){Retired old;old.sources=sourceBuffer;old.nodes=treeBuffer;old.graphics=device->createEventQuery();old.compute=device->createEventQuery();
            device->setEventQuery(old.graphics,nvrhi::CommandQueue::Graphics);device->setEventQuery(old.compute,nvrhi::CommandQueue::Compute);retired.push_back(std::move(old));}
        sourceBuffer=sources;treeBuffer=nodes;recordCapacity=rc;nodeCapacity=nc;regionCapacity=gc;full=headerDirty=true;++work[7];return true;
    }
    static constexpr size_t UploadChunkBytes=1024*1024;
    static bool CoalesceUploads(){const char* disabled=std::getenv("RTR_RTXPT_DISABLE_SPARSE_UPLOAD_COALESCING");return !disabled||std::string(disabled)!="1";}
    static void WriteBounded(nvrhi::ICommandList* command,nvrhi::IBuffer* buffer,const void* data,size_t bytes,size_t offset=0){
        auto input=static_cast<const unsigned char*>(data);
        for(size_t done=0;done<bytes;){const size_t count=std::min(UploadChunkBytes,bytes-done);
            command->writeBuffer(buffer,input+done,count,offset+done);done+=count;}
    }
    template<class T> void UploadSpans(nvrhi::ICommandList* command,nvrhi::IBuffer* buffer,const std::vector<T>& data,std::vector<uint32_t>& dirty,size_t offset,int counter){
        if(full){if(!data.empty()){WriteBounded(command,buffer,data.data(),data.size()*sizeof(T),offset*sizeof(T));work[counter]+=data.size()*sizeof(T);}return;}
        rtr::UploadRanges::ForEach(dirty,data.size(),sizeof(T),[&](size_t first,size_t count){
            WriteBounded(command,buffer,&data.at(first),count*sizeof(T),(offset+first)*sizeof(T));work[counter]+=count*sizeof(T);},
            CoalesceUploads()?rtr::UploadRanges::MergeGapBytes:0);
    }
    void Upload(nvrhi::ICommandList* command){
        if(!headerDirty)return;
        RtrDiagnostics::Scope evidence(RtrDiagnostics::SparseUpload,dirtyRecords.size(),regions.dirtyNodes.size(),1,0,0,0,
            full||regions.full?RtrDiagnostics::CapacityGrowth:RtrDiagnostics::PoolHit,
            full||regions.full?regions.packed.size()*64:regions.dirtyNodes.size()*64);
        Record header{};header.position={float(records.size()),float(tree.root==rtr::RetainedLightTree::None?0:tree.root),1,0};
        header.direction[0]=dynamicCount==records.size()?1.f:0.f;header.direction[1]=dynamicCount==0?1.f:0.f;
        // Uniform L1 attenuation admits a tighter conservative AABB metric.
        // Mixed batches retain the general Euclidean support/score bounds.
        header.extent[0]=!records.empty()&&linearCount==records.size()?1.f:0.f;
        // 1 = luminance winner; 2 = component-wise RGB winner. The latter
        // cannot use the former's scalar score/mask without changing colors.
        const char* disable=std::getenv("RTR_RTXPT_DISABLE_SPARSE_NODE_BOUNDS");
        header.position[3]=(!disable||std::string(disable)!="1")?(VisibleMax()?1.f:ChannelMax()?2.f:0.f):0.f;
        if(regions.buckets&&regions.indexed){header.cone={float(nodeCapacity),float(regions.buckets-1),float(nodeCapacity+regions.buckets),float(RtrSparseRegions::CellSize)};header.extent[1]=float(regions.global.size());}
        command->writeBuffer(sourceBuffer,&header,sizeof(header));
        UploadSpans(command,sourceBuffer,records,dirtyRecords,1,5);UploadSpans(command,treeBuffer,queryNodes,dirtyNodes,0,6);
        if(!regions.packed.empty()){
            if(full||regions.full){const auto bytes=regions.packed.size()*64;WriteBounded(command,treeBuffer,regions.packed.data(),bytes,size_t(nodeCapacity)*64);work[6]+=bytes;regions.work[8]+=bytes;}
            else{
                rtr::UploadRanges::ForEach(regions.dirtyNodes,regions.packed.size(),64,[&](size_t first,size_t count){
                    const auto bytes=count*64;WriteBounded(command,treeBuffer,&regions.packed[first],bytes,(size_t(nodeCapacity)+first)*64);work[6]+=bytes;regions.work[8]+=bytes;},
                    CoalesceUploads()?rtr::UploadRanges::MergeGapBytes:4*64);
            }
        }
        regions.Uploaded();
        dirtyRecords.clear();dirtyNodes.clear();full=headerDirty=false;
    }
};
