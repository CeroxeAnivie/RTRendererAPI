#pragma once
#include <nvrhi/nvrhi.h>
#include <list>
#include <map>
#include <stdexcept>
#include <vector>
#include <exception>
#include <span>
#include <donut/engine/ThreadPool.h>
#include "SampleCommon/BlasPool.h"
#include "rtxpt_diagnostics.h"
#include "rtxpt_frame_batch.h"

namespace rtr::upstream {
// Cache of completed ordinary native AS objects, not a GPU allocator.
// RTXPT BlasPool handles are compacted/address-only: NVRHI cannot rebuild
// them. Only the dual-queue retirement owner calls Retain. No AS wrappers,
// copied build algorithms, byte allocation, or upstream default changes.
class BlasStorage {
    struct Entry;
    using Retained=std::list<Entry>;
    using Index=std::multimap<uint64_t,Retained::iterator>;
    struct Entry {nvrhi::rt::AccelStructHandle structure;uint64_t bytes=0;Index::iterator index;};
    Retained m_retained;
    Index m_ready;
    uint64_t m_bytes=0;
    static bool Shape(const nvrhi::rt::AccelStructDesc& desc,uint64_t& hash){
        using namespace nvrhi::rt;
        if(desc.isTopLevel||desc.isVirtual||!desc.trackLiveness
            ||(desc.buildFlags&(AccelStructBuildFlags::AllowCompaction|AccelStructBuildFlags::PerformUpdate))!=0)return false;
        hash=14695981039346656037ull;const auto add=[&](uint64_t v){hash=(hash^v)*1099511628211ull;};
        add(uint64_t(desc.buildFlags));add(desc.bottomLevelGeometries.size());
        for(const auto& g:desc.bottomLevelGeometries){
            if(g.geometryType!=GeometryType::Triangles)return false;
            const auto& t=g.geometryData.triangles;
            if(t.opacityMicromap||t.ommIndexBuffer||t.ommIndexBufferOffset
                ||t.ommIndexFormat!=nvrhi::Format::UNKNOWN||t.pOmmUsageCounts||t.numOmmUsageCounts)return false;
            add(uint64_t(g.flags));add(g.useTransform);add(uint64_t(t.indexFormat));
            add(uint64_t(t.vertexFormat));add(t.vertexStride);
        }return true;
    }
    static bool Fits(const nvrhi::rt::AccelStructDesc& a,const nvrhi::rt::AccelStructDesc& b){
        if(a.buildFlags!=b.buildFlags||a.bottomLevelGeometries.size()!=b.bottomLevelGeometries.size())return false;
        for(size_t i=0;i<a.bottomLevelGeometries.size();++i){
            const auto& x=a.bottomLevelGeometries[i];const auto& y=b.bottomLevelGeometries[i];
            const auto& u=x.geometryData.triangles;const auto& v=y.geometryData.triangles;
            if(x.geometryType!=y.geometryType||x.flags!=y.flags||x.useTransform!=y.useTransform
                ||u.indexFormat!=v.indexFormat||u.indexCount<v.indexCount||u.vertexFormat!=v.vertexFormat
                ||u.vertexCount<v.vertexCount||u.vertexStride!=v.vertexStride)return false;
        }return true;
    }
public:
    BlasStorage()=default;
    BlasStorage(const BlasStorage&)=delete;
    BlasStorage& operator=(const BlasStorage&)=delete;
    struct Result {nvrhi::rt::AccelStructHandle structure;bool reused=false;};
    static bool CanRebuild(nvrhi::rt::IAccelStruct* structure,const nvrhi::rt::AccelStructDesc& desc){
        if(!structure||bvh::PooledAccelStruct::IsPooled(structure)||structure->isCompacted())return false;
        uint64_t capacityShape=0,inputShape=0;
        return Shape(structure->getDesc(),capacityShape)&&Shape(desc,inputShape)
            &&capacityShape==inputShape&&Fits(structure->getDesc(),desc);
    }
    Result Take(const nvrhi::rt::AccelStructDesc& desc){
        uint64_t hash=0;
        if(Shape(desc,hash)){
            auto [first,last]=m_ready.equal_range(hash);
            auto best=last;
            for(auto i=first;i!=last;++i)if(Fits(i->second->structure->getDesc(),desc)
                &&(best==last||i->second->bytes<best->second->bytes))best=i;
            if(best!=last){
                // Allocation inputs bound native storage capacity. This is a
                // complete rebuild with the caller's actual geometry, never an
                // update/refit with changed primitive counts. Keep each original
                // geometry's type, flags and formats and stay within both of its
                // count limits; no larger allocation or native descriptor packing
                // is invented here. Native build/scratch validation stays enabled.
                const auto retained=best->second;
                Result result{std::move(retained->structure),true};m_bytes-=retained->bytes;
                m_ready.erase(best);m_retained.erase(retained);return result;
            }
        }
        return {};
    }
    static Result Allocate(nvrhi::IDevice* device,const nvrhi::rt::AccelStructDesc& desc,uint64_t batch=0){
        RtrDiagnostics::Scope allocate(RtrDiagnostics::BlasAllocate,1,0,1,batch);
        auto structure=device->createAccelStruct(desc);
        if(!structure)throw std::runtime_error("Native streaming BLAS allocation failed");
        return {std::move(structure),false};
    }
    Result Create(nvrhi::IDevice* device,nvrhi::ICommandList*,const nvrhi::rt::AccelStructDesc& desc){
        auto result=Take(desc);return result.structure?std::move(result):Allocate(device,desc);
    }
    std::vector<Result> CreateBatch(nvrhi::IDevice* device,donut::engine::ThreadPool& workers,
            std::span<const nvrhi::rt::AccelStructDesc> descriptions){
        std::vector<Result> results(descriptions.size());std::vector<size_t> missing;
        missing.reserve(descriptions.size());
        // Cache ownership stays serial. Workers only create independent ordinary
        // committed objects; no graph, cache, command list or GPU queue access.
        for(size_t i=0;i<descriptions.size();++i){
            results[i]=Take(descriptions[i]);if(!results[i].structure)missing.push_back(i);
        }
        const auto batch=RtrDiagnostics::parentId;
#ifdef NVRHI_WITH_RTXMU
        auto* allocationWorkers=static_cast<donut::engine::ThreadPool*>(nullptr);
#else
        auto* allocationWorkers=&workers;
#endif
        RunFrameBatch(allocationWorkers,missing.size(),[&](size_t at){
            const auto i=missing[at];results[i]=Allocate(device,descriptions[i],batch);
        });
        // All jobs have joined before descriptors/resources or errors escape.
        // Report the first input failure, never a partial batch to the graph.
        return results;
    }
    void Retain(nvrhi::IDevice* device,const nvrhi::rt::AccelStructHandle& structure){
        uint64_t hash=0;
        if(!structure||bvh::PooledAccelStruct::IsPooled(structure)||structure->isCompacted()||!Shape(structure->getDesc(),hash))return;
        auto [first,last]=m_ready.equal_range(hash);
        for(auto i=first;i!=last;++i)if(i->second->structure.Get()==structure.Get())return;
        const auto bytes=device->getAccelStructMemoryRequirements(structure).size;
        constexpr uint64_t limit=64ull<<20;constexpr size_t handles=1024;
        if(!bytes||bytes>limit)return;
        // A full cache must not permanently keep its first small allocations
        // while rejecting every newly retired larger one. Evict the oldest
        // completed handles; the caller has already retired both native queues.
        // Native allocation sizes and the original byte/handle bounds stay exact.
        while(m_ready.size()>=handles||m_bytes+bytes>limit){
            auto& oldest=m_retained.front();m_bytes-=oldest.bytes;
            m_ready.erase(oldest.index);m_retained.pop_front();
        }
        m_retained.push_back({structure,bytes,{}});
        const auto retained=std::prev(m_retained.end());
        try{retained->index=m_ready.emplace(hash,retained);}
        catch(...){m_retained.erase(retained);throw;}
        m_bytes+=bytes;
    }
    void Clear(){m_ready.clear();m_retained.clear();m_bytes=0;}
};
}
