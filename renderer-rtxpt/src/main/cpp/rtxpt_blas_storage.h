#pragma once
#include <nvrhi/nvrhi.h>
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
            add(uint64_t(g.flags));add(g.useTransform);add(uint64_t(t.indexFormat));add(t.indexCount);
            add(uint64_t(t.vertexFormat));add(t.vertexCount);add(t.vertexStride);
        }return true;
    }
    static bool Same(const nvrhi::rt::AccelStructDesc& a,const nvrhi::rt::AccelStructDesc& b){
        if(a.buildFlags!=b.buildFlags||a.bottomLevelGeometries.size()!=b.bottomLevelGeometries.size())return false;
        for(size_t i=0;i<a.bottomLevelGeometries.size();++i){
            const auto& x=a.bottomLevelGeometries[i];const auto& y=b.bottomLevelGeometries[i];
            const auto& u=x.geometryData.triangles;const auto& v=y.geometryData.triangles;
            if(x.geometryType!=y.geometryType||x.flags!=y.flags||x.useTransform!=y.useTransform
                ||u.indexFormat!=v.indexFormat||u.indexCount!=v.indexCount||u.vertexFormat!=v.vertexFormat
                ||u.vertexCount!=v.vertexCount||u.vertexStride!=v.vertexStride)return false;
        }return true;
    }
public:
    struct Result {nvrhi::rt::AccelStructHandle structure;bool reused=false;};
    Result Create(nvrhi::IDevice* device,nvrhi::ICommandList*,const nvrhi::rt::AccelStructDesc& desc){
        uint64_t hash=0;
        if(Shape(desc,hash)){
            auto [first,last]=m_ready.equal_range(hash);
            for(auto i=first;i!=last;++i)if(Same(i->second.structure->getDesc(),desc)){
                Result result{std::move(i->second.structure),true};m_bytes-=i->second.bytes;m_ready.erase(i);return result;
            }
        }
        RtrDiagnostics::Scope allocate(RtrDiagnostics::BlasAllocate,1);
        auto structure=device->createAccelStruct(desc);
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