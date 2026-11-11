#pragma once
#include <nvrhi/nvrhi.h>
#include <bit>
#include <limits>
#include <stdexcept>
#include <unordered_map>
#include <vector>
#include "SampleCommon/BlasPool.h"
#include "rtxpt_diagnostics.h"

namespace rtr::upstream {
// Cache of completed ordinary native AS objects, not a GPU allocator.
// RTXPT BlasPool handles are compacted/address-only: NVRHI cannot rebuild
// them. Only the dual-queue retirement owner calls Retain. No AS wrappers,
// copied build algorithms, byte allocation, or upstream default changes.
class BlasStorage {
    struct Entry {nvrhi::rt::AccelStructHandle structure;uint64_t bytes=0;};
    std::unordered_multimap<uint64_t,Entry> m_ready;
    uint64_t m_bytes=0;
    static nvrhi::rt::AccelStructDesc Capacity(const nvrhi::rt::AccelStructDesc& source){
        auto result=source;
        // Prebuild counts size storage only; actual builds still use their
        // exact authored counts. Stable size classes let completed native
        // allocations serve nearby geometries rather than miss by one vertex.
        const auto rounded=[](uint32_t count){
            return count>0x80000000u?count:std::bit_ceil(count);
        };
        for(auto& geometry:result.bottomLevelGeometries){
            auto& triangles=geometry.geometryData.triangles;
            triangles.vertexCount=rounded(triangles.vertexCount);
            if(triangles.indexCount){
                const auto primitives=rounded(triangles.indexCount/3);
                if(primitives<=std::numeric_limits<uint32_t>::max()/3)
                    triangles.indexCount=primitives*3;
            }
        }
        return result;
    }
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
    struct Result {nvrhi::rt::AccelStructHandle structure;bool reused=false;};
    static bool CanRebuild(nvrhi::rt::IAccelStruct* structure,const nvrhi::rt::AccelStructDesc& desc){
        if(!structure||bvh::PooledAccelStruct::IsPooled(structure)||structure->isCompacted())return false;
        uint64_t capacityShape=0,inputShape=0;
        return Shape(structure->getDesc(),capacityShape)&&Shape(desc,inputShape)
            &&capacityShape==inputShape&&Fits(structure->getDesc(),desc);
    }
    Result Create(nvrhi::IDevice* device,nvrhi::ICommandList*,const nvrhi::rt::AccelStructDesc& desc){
        uint64_t hash=0;
        if(Shape(desc,hash)){
            auto [first,last]=m_ready.equal_range(hash);
            auto best=last;
            for(auto i=first;i!=last;++i)if(Fits(i->second.structure->getDesc(),desc)
                &&(best==last||i->second.bytes<best->second.bytes))best=i;
            if(best!=last){
                // Allocation inputs bound native storage capacity. This is a
                // complete rebuild with the caller's actual geometry, never an
                // update/refit with changed primitive counts. Keep each original
                // geometry's type, flags and formats and stay within both of its
                // count limits; no larger allocation or native descriptor packing
                // is invented here. Native build/scratch validation stays enabled.
                Result result{std::move(best->second.structure),true};m_bytes-=best->second.bytes;m_ready.erase(best);return result;
            }
        }
        RtrDiagnostics::Scope allocate(RtrDiagnostics::BlasAllocate,1);
        // Keep the original committed native resource path and its enhanced
        // barrier flags. No virtual resource, aliasing, or heap replacement.
        auto structure=device->createAccelStruct(Shape(desc,hash)?Capacity(desc):desc);
        if(!structure)throw std::runtime_error("Native streaming BLAS allocation failed");
        return {std::move(structure),false};
    }
    void Retain(nvrhi::IDevice* device,const nvrhi::rt::AccelStructHandle& structure){
        uint64_t hash=0;
        if(!structure||bvh::PooledAccelStruct::IsPooled(structure)||structure->isCompacted()||!Shape(structure->getDesc(),hash))return;
        auto [first,last]=m_ready.equal_range(hash);
        for(auto i=first;i!=last;++i)if(i->second.structure.Get()==structure.Get())return;
        const auto bytes=device->getAccelStructMemoryRequirements(structure).size;
        constexpr uint64_t limit=64ull<<20;constexpr size_t handles=1024;
        if(!bytes||bytes>limit||m_ready.size()>=handles||m_bytes+bytes>limit)return;
        m_ready.emplace(hash,Entry{structure,bytes});m_bytes+=bytes;
    }
    void Clear(){m_ready.clear();m_bytes=0;}
};
}
