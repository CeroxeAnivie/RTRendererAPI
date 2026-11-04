#pragma once
#include "rtxpt_retained_light_tree.h"
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
    double normalization=1;
    std::vector<uint32_t> dirtyRecords,dirtyNodes;
    bool full=true,headerDirty=true;
    std::array<uint32_t,32> groupCounts{};
    uint32_t groupMask=0;
    size_t visibleMaxCount=0,dynamicCount=0;
    void Group(const Record& record,bool add){if(uint32_t(record.identity[3])&1){if(add)++dynamicCount;else --dynamicCount;}if(record.cone[3]==1){if(add)++visibleMaxCount;else --visibleMaxCount;}if(record.cone[3]==0)return;const auto group=uint32_t(record.cone[2]);
        if(add){++groupCounts[group];groupMask|=1u<<group;}else if(--groupCounts[group]==0)groupMask&=~(1u<<group);}
    bool SmallGroups()const{return (groupMask&~15u)==0;}
    bool PairGroups()const{return (groupMask&~3u)==0;}
    bool VisibleMax()const{return !records.empty()&&visibleMaxCount==records.size();}
    bool SingleGroup()const{return (groupMask&(groupMask-1))==0;}
    uint32_t recordCapacity=0,nodeCapacity=0;
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
            ||r.extent[3]<=0||r.extent[3]>32||r.cone[0]<r.cone[1]||r.cone[0]>1||r.cone[1]<-1
            ||(r.identity[0]!=0&&r.identity[0]!=1)||r.identity[3]<0||r.identity[3]>3||r.identity[3]!=float(uint32_t(r.identity[3])))throw std::invalid_argument("Invalid sparse source record");
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
        records.reserve(capacity);proxies.reserve(capacity);ids.reserve(capacity);slots.reserve(capacity);tree.Reserve(capacity);
        dirtyRecords.reserve(dirtyRecords.size()+changed.size()+removed.size());dirtyNodes.reserve(dirtyNodes.size()+(changed.size()+removed.size())*128);
        if(reset){records.clear();proxies.clear();ids.clear();slots.clear();tree.Rebuild(proxies,&normalization);dirtyRecords.clear();dirtyNodes.clear();groupCounts.fill(0);groupMask=0;visibleMaxCount=dynamicCount=0;full=true;}
        uint64_t visited=tree.visited;
        for(auto id:removed){uint32_t slot=slots.at(id),last=uint32_t(records.size()-1);Group(records[slot],false);tree.Remove(slot,dirtyNodes);slots.erase(id);
            if(slot!=last){records[slot]=records[last];proxies[slot]=proxies[last];ids[slot]=ids[last];slots.at(ids[slot])=slot;dirtyRecords.push_back(slot);}
            records.pop_back();proxies.pop_back();ids.pop_back();}
        for(size_t i=0;i<changed.size();i++){
            auto old=slots.find(changed[i]);uint32_t slot=old==slots.end()?uint32_t(records.size()):old->second;
            if(old!=slots.end())Group(records[slot],false);Group(prepared[i],true);
            if(old==slots.end()){slots.emplace(changed[i],slot);records.push_back(prepared[i]);proxies.push_back(MakeProxy(prepared[i]));ids.push_back(changed[i]);if(!reset)tree.Insert(proxies,slot,normalization,dirtyNodes);}
            else{records[slot]=prepared[i];proxies[slot]=MakeProxy(prepared[i]);tree.Refit(proxies,slot,normalization,dirtyNodes);}
            dirtyRecords.push_back(slot);
        }
        // Bulk publication has every source available. Spatial partitioning avoids
        // insertion-order-dependent overlap in the AVL hierarchy; later small
        // edits keep the existing bounded incremental path.
        if(reset){tree.Rebuild(proxies,&normalization);dirtyNodes.clear();}
        PrepareQueryNodes(reset);
        // No probability normalization is needed for exact spatial queries. Tree powers
        // are intentionally unused; the geometry/support bounds are authoritative.
        headerDirty=true;work[0]=records.size();++work[1];work[2]+=changed.size();work[3]+=removed.size();work[4]+=tree.visited-visited;
    }
    void PrepareQueryNodes(bool rebuild){
        queryNodes.resize(tree.nodes.size());
        auto changed=dirtyNodes;
        if(rebuild){changed.resize(tree.nodes.size());std::iota(changed.begin(),changed.end(),0);}
        std::sort(changed.begin(),changed.end());changed.erase(std::unique(changed.begin(),changed.end()),changed.end());
        // Rotations can reorder indices. Heights, rather than allocation order,
        // guarantee that every changed child is aggregated before its parent.
        std::sort(changed.begin(),changed.end(),[&](auto a,auto b){return tree.heights[a]<tree.heights[b];});
        for(auto index:changed){
            auto& n=queryNodes[index];n=tree.nodes[index];if(n.light< -1)continue;
            uint32_t mask=0;
            if(n.light>=0){const auto& r=records.at(uint32_t(n.light));
                // Query bounds enclose the emitting volume itself. Expanding a
                // center bound and also its radius paid twice for BOX extents.
                for(int a=0;a<3;a++){n.minimum[a]=r.position[a]-r.extent[a];n.maximum[a]=r.position[a]+r.extent[a];}
                n.range=r.direction[3];
                n.power=r.color[0]*r.color[3];n.directional=r.color[1]*r.color[3];n.leaves=r.color[2]*r.color[3];
                n.unused[0]=(uint32_t(r.identity[3])&2)?-1.f:r.extent[3];
                n.unused[1]=0;mask=1u<<uint32_t(r.cone[2]);
            }else{const auto& a=queryNodes[uint32_t(n.left)];const auto& b=queryNodes[uint32_t(n.right)];
                for(int axis=0;axis<3;axis++){n.minimum[axis]=std::min(a.minimum[axis],b.minimum[axis]);n.maximum[axis]=std::max(a.maximum[axis],b.maximum[axis]);}
                n.range=std::max(a.range,b.range);
                n.power=std::max(a.power,b.power);n.directional=std::max(a.directional,b.directional);n.leaves=std::max(a.leaves,b.leaves);
                n.unused[0]=std::min(std::abs(a.unused[0]),std::abs(b.unused[0]))*((a.unused[0]<0||b.unused[0]<0)?-1.f:1.f);
                n.unused[1]=0;uint32_t x,y;std::memcpy(&x,&a.unused[2],4);std::memcpy(&y,&b.unused[2],4);mask=x|y;
            }
            std::memcpy(&n.unused[2],&mask,4);
            std::memcpy(&n.unused[1],&tree.parents[index],4);
        }
    }
    bool Ensure(nvrhi::IDevice* device){
        for(auto i=retired.begin();i!=retired.end();)if(device->pollEventQuery(i->graphics)&&device->pollEventQuery(i->compute))i=retired.erase(i);else ++i;
        if(sourceBuffer&&records.size()<=recordCapacity&&tree.nodes.size()<=nodeCapacity)return false;
        uint32_t rc=std::max(64u,recordCapacity),nc=std::max(128u,nodeCapacity);
        while(rc<records.size())rc*=2;while(nc<tree.nodes.size())nc*=2;
        nvrhi::BufferDesc d;d.structStride=sizeof(Record);d.byteSize=(size_t(rc)+1)*sizeof(Record);d.debugName="Rtr sparse sources";
        d.initialState=nvrhi::ResourceStates::ShaderResource;d.keepInitialState=true;auto sources=device->createBuffer(d);
        d.structStride=64;d.byteSize=size_t(nc)*64;d.debugName="Rtr sparse support index";auto nodes=device->createBuffer(d);
        if(!sources||!nodes)throw std::runtime_error("Could not allocate sparse lighting buffers");
        if(sourceBuffer){Retired old;old.sources=sourceBuffer;old.nodes=treeBuffer;old.graphics=device->createEventQuery();old.compute=device->createEventQuery();
            device->setEventQuery(old.graphics,nvrhi::CommandQueue::Graphics);device->setEventQuery(old.compute,nvrhi::CommandQueue::Compute);retired.push_back(std::move(old));}
        sourceBuffer=sources;treeBuffer=nodes;recordCapacity=rc;nodeCapacity=nc;full=headerDirty=true;++work[7];return true;
    }
    template<class T> void UploadSpans(nvrhi::ICommandList* command,nvrhi::IBuffer* buffer,const std::vector<T>& data,std::vector<uint32_t>& dirty,size_t offset,int counter){
        if(full){if(!data.empty()){command->writeBuffer(buffer,data.data(),data.size()*sizeof(T),offset*sizeof(T));work[counter]+=data.size()*sizeof(T);}return;}
        std::sort(dirty.begin(),dirty.end());dirty.erase(std::unique(dirty.begin(),dirty.end()),dirty.end());
        dirty.erase(std::lower_bound(dirty.begin(),dirty.end(),uint32_t(data.size())),dirty.end());
        for(size_t i=0;i<dirty.size();){size_t end=i+1;while(end<dirty.size()&&dirty[end]==dirty[end-1]+1)++end;
            command->writeBuffer(buffer,&data.at(dirty[i]),(end-i)*sizeof(T),(offset+dirty[i])*sizeof(T));work[counter]+=(end-i)*sizeof(T);i=end;}
    }
    void Upload(nvrhi::ICommandList* command){
        if(!headerDirty)return;
        Record header{};header.position={float(records.size()),float(tree.root==rtr::RetainedLightTree::None?0:tree.root),1,0};
        header.direction[0]=dynamicCount==records.size()?1.f:0.f;header.direction[1]=dynamicCount==0?1.f:0.f;
        // Bounds are an exact early rejection for max composition only. Mixed
        // and additive compositions retain the general traversal unchanged.
        const char* disable=std::getenv("RTR_RTXPT_DISABLE_SPARSE_NODE_BOUNDS");
        header.position[3]=!records.empty()&&visibleMaxCount==records.size()&&(!disable||std::string(disable)!="1")?1.f:0.f;
        command->writeBuffer(sourceBuffer,&header,sizeof(header));
        UploadSpans(command,sourceBuffer,records,dirtyRecords,1,5);UploadSpans(command,treeBuffer,queryNodes,dirtyNodes,0,6);
        dirtyRecords.clear();dirtyNodes.clear();full=headerDirty=false;
    }
};
