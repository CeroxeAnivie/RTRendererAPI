#pragma once
#include <d3d12.h>
#include <wrl/client.h>
#include <nvrhi/nvrhi.h>
#include <algorithm>
#include <memory>
#include <vector>
#include <stdexcept>
#include "rtxpt_diagnostics.h"

namespace rtr::upstream {
// Address-only handles never enter an NVRHI AS entry point. Native DXR builds
// address disjoint ranges of ordinary, correctly flagged committed AS buffers.
// This does not use NVRHI's virtual/placed-resource path. Range retirement is
// governed by the host's existing graphics+compute completion ownership.
class DirectBlasStorage {
    struct Range {uint64_t offset,size;};
    struct Page {
        nvrhi::BufferHandle buffer;uint64_t capacity=0;std::vector<Range> free;
        void Release(Range range){
            auto at=std::lower_bound(free.begin(),free.end(),range.offset,[](const auto& a,uint64_t b){return a.offset<b;});
            at=free.insert(at,range);
            if(at!=free.begin()){auto before=at-1;if(before->offset+before->size==at->offset){before->size+=at->size;at=free.erase(at)-1;}}
            if(at+1!=free.end()&&at->offset+at->size==(at+1)->offset){at->size+=(at+1)->size;free.erase(at+1);}
        }
    };
    class Structure final : public nvrhi::RefCounter<nvrhi::rt::IAccelStruct> {
    public:
        std::shared_ptr<Page> page;Range range{};nvrhi::rt::AccelStructDesc description;
        ~Structure()override{if(page)page->Release(range);}
        const nvrhi::rt::AccelStructDesc& getDesc()const override{return description;}
        bool isCompacted()const override{return false;}
        uint64_t getDeviceAddress()const override{return page->buffer->getGpuVirtualAddress()+range.offset;}
        nvrhi::Object getNativeObject(nvrhi::ObjectType)override{return nullptr;}
    };
    std::vector<std::shared_ptr<Page>> m_pages;
    nvrhi::BufferHandle m_scratch;
    static uint64_t Align(uint64_t value){return (value+255)&~uint64_t(255);}
    static std::vector<D3D12_RAYTRACING_GEOMETRY_DESC> Geometry(const nvrhi::rt::AccelStructDesc& desc){
        std::vector<D3D12_RAYTRACING_GEOMETRY_DESC> rows;rows.reserve(desc.bottomLevelGeometries.size());
        for(const auto& source:desc.bottomLevelGeometries){
            const auto& t=source.geometryData.triangles;D3D12_RAYTRACING_GEOMETRY_DESC row{};
            row.Type=D3D12_RAYTRACING_GEOMETRY_TYPE_TRIANGLES;row.Flags=D3D12_RAYTRACING_GEOMETRY_FLAGS(source.flags);
            row.Triangles.IndexFormat=t.indexFormat==nvrhi::Format::R16_UINT?DXGI_FORMAT_R16_UINT:DXGI_FORMAT_R32_UINT;
            row.Triangles.VertexFormat=DXGI_FORMAT_R32G32B32_FLOAT;row.Triangles.IndexCount=t.indexCount;row.Triangles.VertexCount=t.vertexCount;
            row.Triangles.IndexBuffer=t.indexBuffer?t.indexBuffer->getGpuVirtualAddress()+t.indexOffset:0;
            row.Triangles.VertexBuffer={t.vertexBuffer->getGpuVirtualAddress()+t.vertexOffset,t.vertexStride};
            rows.push_back(row);
        }return rows;
    }
    static D3D12_BUILD_RAYTRACING_ACCELERATION_STRUCTURE_INPUTS Inputs(const nvrhi::rt::AccelStructDesc& desc,
            const std::vector<D3D12_RAYTRACING_GEOMETRY_DESC>& rows){
        D3D12_BUILD_RAYTRACING_ACCELERATION_STRUCTURE_INPUTS result{};
        result.Type=D3D12_RAYTRACING_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL;
        result.Flags=D3D12_RAYTRACING_ACCELERATION_STRUCTURE_BUILD_FLAGS(desc.buildFlags);
        result.DescsLayout=D3D12_ELEMENTS_LAYOUT_ARRAY;result.NumDescs=UINT(rows.size());result.pGeometryDescs=rows.data();return result;
    }
    static D3D12_RAYTRACING_ACCELERATION_STRUCTURE_PREBUILD_INFO Requirements(nvrhi::IDevice* device,
            const nvrhi::rt::AccelStructDesc& desc){
        auto rows=Geometry(desc);auto inputs=Inputs(desc,rows);
        auto* base=static_cast<ID3D12Device*>(device->getNativeObject(nvrhi::ObjectTypes::D3D12_Device));
        Microsoft::WRL::ComPtr<ID3D12Device5> native;
        if(!base||FAILED(base->QueryInterface(IID_PPV_ARGS(&native))))throw std::runtime_error("Direct BLAS requires the native DXR device");
        D3D12_RAYTRACING_ACCELERATION_STRUCTURE_PREBUILD_INFO result{};native->GetRaytracingAccelerationStructurePrebuildInfo(&inputs,&result);
        if(!result.ResultDataMaxSizeInBytes)throw std::runtime_error("Invalid native BLAS prebuild requirements");return result;
    }
    static void Barrier(nvrhi::IDevice* device,ID3D12GraphicsCommandList4* commands){
        if(device->queryFeatureSupport(nvrhi::Feature::EnhancedBarriers)){
            Microsoft::WRL::ComPtr<ID3D12GraphicsCommandList7> enhanced;
            if(FAILED(commands->QueryInterface(IID_PPV_ARGS(&enhanced))))throw std::runtime_error("Missing enhanced-barrier command list");
            D3D12_GLOBAL_BARRIER barrier{};barrier.SyncBefore=barrier.SyncAfter=D3D12_BARRIER_SYNC_ALL;
            barrier.AccessBefore=barrier.AccessAfter=D3D12_BARRIER_ACCESS_RAYTRACING_ACCELERATION_STRUCTURE_READ
                |D3D12_BARRIER_ACCESS_RAYTRACING_ACCELERATION_STRUCTURE_WRITE|D3D12_BARRIER_ACCESS_UNORDERED_ACCESS;
            D3D12_BARRIER_GROUP group{};group.Type=D3D12_BARRIER_TYPE_GLOBAL;group.NumBarriers=1;group.pGlobalBarriers=&barrier;enhanced->Barrier(1,&group);
        }else{D3D12_RESOURCE_BARRIER barrier{};barrier.Type=D3D12_RESOURCE_BARRIER_TYPE_UAV;commands->ResourceBarrier(1,&barrier);}
    }
public:
    static bool Is(const nvrhi::rt::IAccelStruct* value){return dynamic_cast<const Structure*>(value)!=nullptr;}
    static bool Supports(const nvrhi::rt::AccelStructDesc& desc){
        using namespace nvrhi::rt;
        if(desc.isTopLevel||desc.bottomLevelGeometries.empty()
            ||(desc.buildFlags&(AccelStructBuildFlags::AllowCompaction|AccelStructBuildFlags::PerformUpdate))!=0)return false;
        for(const auto& g:desc.bottomLevelGeometries){
            if(g.geometryType!=GeometryType::Triangles||g.useTransform)return false;const auto& t=g.geometryData.triangles;
            if(!t.vertexBuffer||t.vertexFormat!=nvrhi::Format::RGB32_FLOAT
                ||(t.indexFormat!=nvrhi::Format::R16_UINT&&t.indexFormat!=nvrhi::Format::R32_UINT)
                ||!t.indexBuffer||t.opacityMicromap||t.ommIndexBuffer)return false;
        }return true;
    }
    static bool CanRebuild(nvrhi::IDevice* device,nvrhi::rt::IAccelStruct* value,const nvrhi::rt::AccelStructDesc& desc){
        const auto* structure=dynamic_cast<Structure*>(value);
        return structure&&Supports(desc)&&Requirements(device,desc).ResultDataMaxSizeInBytes<=structure->range.size;
    }
    nvrhi::rt::AccelStructHandle Create(nvrhi::IDevice* device,const nvrhi::rt::AccelStructDesc& desc){
        const uint64_t bytes=Align(Requirements(device,desc).ResultDataMaxSizeInBytes);
        std::shared_ptr<Page> page;size_t slot=0;uint64_t best=UINT64_MAX;
        for(const auto& candidate:m_pages)for(size_t i=0;i<candidate->free.size();++i)
            if(candidate->free[i].size>=bytes&&candidate->free[i].size<best){page=candidate;slot=i;best=candidate->free[i].size;}
        if(!page){
            // Drop empty surplus pages only at allocation boundaries. Live ranges
            // own their page independently of the allocator and command list.
            std::erase_if(m_pages,[](const auto& p){return p->free.size()==1&&p->free[0].size==p->capacity;});
            page=std::make_shared<Page>();page->capacity=std::max<uint64_t>(8ull<<20,bytes);
            nvrhi::BufferDesc buffer;buffer.byteSize=page->capacity;buffer.canHaveUAVs=true;buffer.isAccelStructStorage=true;
            buffer.initialState=nvrhi::ResourceStates::AccelStructBuildBlas;buffer.keepInitialState=true;buffer.debugName="Direct BLAS storage page";
            {RtrDiagnostics::Scope allocation(RtrDiagnostics::DefaultBufferCommitted,1,buffer.byteSize,1,0,
                RtrDiagnostics::Identity("Direct BLAS storage page"));
                page->buffer=device->createBuffer(buffer);}
            if(!page->buffer)throw std::runtime_error("Direct BLAS page allocation failed");
            page->free.push_back({0,page->capacity});m_pages.push_back(page);slot=0;
        }
        auto range=page->free[slot];range.size=bytes;page->free[slot].offset+=bytes;page->free[slot].size-=bytes;
        if(!page->free[slot].size)page->free.erase(page->free.begin()+slot);
        auto* structure=new Structure();structure->page=std::move(page);structure->range=range;structure->description=desc;
        for(auto& g:structure->description.bottomLevelGeometries){g.geometryData.triangles.indexBuffer=nullptr;g.geometryData.triangles.vertexBuffer=nullptr;}
        return nvrhi::rt::AccelStructHandle::Create(structure);
    }
    struct Build {nvrhi::rt::IAccelStruct* structure;const nvrhi::rt::AccelStructDesc* description;bool reused=false;};
    void BuildBatch(nvrhi::IDevice* device,nvrhi::ICommandList* commands,const std::vector<Build>& builds){
        if(builds.empty())return;
        std::vector<uint64_t> scratchOffsets;uint64_t scratchSize=0;
        for(const auto& build:builds){scratchOffsets.push_back(scratchSize);scratchSize+=Align(Requirements(device,*build.description).ScratchDataSizeInBytes);}
        if(!m_scratch||m_scratch->getDesc().byteSize<scratchSize){
            nvrhi::BufferDesc desc;desc.byteSize=std::max<uint64_t>(scratchSize,1ull<<20);desc.canHaveUAVs=true;
            desc.initialState=nvrhi::ResourceStates::UnorderedAccess;desc.keepInitialState=true;desc.debugName="Direct BLAS batch scratch";
            {RtrDiagnostics::Scope allocation(RtrDiagnostics::DefaultBufferCommitted,1,desc.byteSize,1,0,
                RtrDiagnostics::Identity("Direct BLAS batch scratch"));
                m_scratch=device->createBuffer(desc);}
            if(!m_scratch)throw std::runtime_error("Direct BLAS scratch allocation failed");
        }
        commands->setBufferState(m_scratch,nvrhi::ResourceStates::UnorderedAccess);
        for(const auto& build:builds)commands->setBufferState(static_cast<Structure*>(build.structure)->page->buffer,nvrhi::ResourceStates::AccelStructBuildBlas);
        commands->commitBarriers();
        auto* native=static_cast<ID3D12GraphicsCommandList*>(commands->getNativeObject(nvrhi::ObjectTypes::D3D12_GraphicsCommandList));
        Microsoft::WRL::ComPtr<ID3D12GraphicsCommandList4> dxr;
        if(!native||FAILED(native->QueryInterface(IID_PPV_ARGS(&dxr))))throw std::runtime_error("Direct BLAS requires native DXR commands");
        Barrier(device,dxr.Get());
        for(size_t i=0;i<builds.size();++i){const auto& build=builds[i];auto geometry=Geometry(*build.description);
            D3D12_BUILD_RAYTRACING_ACCELERATION_STRUCTURE_DESC desc{};desc.Inputs=Inputs(*build.description,geometry);
            desc.DestAccelerationStructureData=build.structure->getDeviceAddress();desc.ScratchAccelerationStructureData=m_scratch->getGpuVirtualAddress()+scratchOffsets[i];
            RtrDiagnostics::Scope scope(RtrDiagnostics::BlasBuild,1);
            if(build.reused)scope.event[11]|=RtrDiagnostics::CacheHit;
            dxr->BuildRaytracingAccelerationStructure(&desc,0,nullptr);
        }
        Barrier(device,dxr.Get());
    }
    void Clear(){m_pages.clear();m_scratch=nullptr;}
};
}
