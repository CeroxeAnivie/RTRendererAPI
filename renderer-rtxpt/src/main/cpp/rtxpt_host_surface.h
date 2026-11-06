#pragma once
#include <algorithm>
#include <array>
#include <cstdint>
#include <memory>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>
#include <donut/engine/Scene.h>
#if defined(_MSC_VER)
#define RTR_HOST_SURFACE_NOINLINE __declspec(noinline)
#else
#define RTR_HOST_SURFACE_NOINLINE
#endif

// Shader-side lookup is sorted by stable public ID, never by transient Donut order.
class RtrHostSurface {
public:
    struct Instance {uint32_t low,high,instanceIndex,triangleOffset,triangleCount,reserved[3]{};};
    struct Triangle {uint32_t geometry,primitive;};
    struct Constants {uint32_t extent[4]{},counts[4]{};};
    static_assert(sizeof(Instance)==32&&sizeof(Constants)==32);
    bool active=false,dirty=true,motionIncludesJitter=false,workStatisticsEnabled=false,workStatisticsRequested=false,diagnosticsEnabled=true;
    uint64_t sequence=0,revision=0;
    uint32_t inputWidth=0,inputHeight=0,composition=1,hostComponents=0;
    nvrhi::BufferHandle constantBuffer,instances,triangles,statistics;
    std::array<nvrhi::TextureHandle,8> inputs{},dummy{};
    std::unordered_map<std::string,std::vector<Triangle>> meshTriangles;
    std::vector<Instance> instanceData;
    std::vector<Triangle> triangleData;
    struct WorkReadback {nvrhi::BufferHandle staging;nvrhi::EventQueryHandle done;uint64_t sequence=0,revision=0;bool pending=false;};
    std::array<WorkReadback,3> readbacks;
    std::array<uint64_t,9> latestWork{};
    // Keep asynchronous readback code out of the already large host render TU.
    // MSVC 19.44 has hit /O2 optimizer AVs in that translation unit.
    RTR_HOST_SURFACE_NOINLINE void PollWork(nvrhi::IDevice* device){
        for(auto& slot:readbacks)if(slot.pending&&device->pollEventQuery(slot.done)){
            auto* words=static_cast<const uint32_t*>(device->mapBuffer(slot.staging,nvrhi::CpuAccessMode::Read));
            if(words){if(!latestWork[0]||slot.sequence>=latestWork[1])latestWork={1,slot.sequence,slot.revision,words[0],words[1],words[2],words[3],words[4],words[5]};device->unmapBuffer(slot.staging);}
            slot.pending=false;
        }
    }
    RTR_HOST_SURFACE_NOINLINE void RecordWork(nvrhi::IDevice* device){
        PollWork(device);if(!active||!workStatisticsEnabled||!statistics)return;
        for(auto& slot:readbacks)if(!slot.pending){
            if(!slot.staging){nvrhi::BufferDesc desc;desc.byteSize=32;desc.cpuAccess=nvrhi::CpuAccessMode::Read;desc.initialState=nvrhi::ResourceStates::CopyDest;
                slot.staging=device->createBuffer(desc);slot.done=device->createEventQuery();
                if(!slot.staging||!slot.done)throw std::runtime_error("Could not allocate host-surface work-statistics readback");}
            auto command=device->createCommandList();command->open();command->copyBuffer(slot.staging,0,statistics,0,32);command->close();device->executeCommandList(command);
            device->setEventQuery(slot.done,nvrhi::CommandQueue::Graphics);slot.sequence=sequence;slot.revision=revision;slot.pending=true;break;
        }
    }
    void Prepare(nvrhi::IDevice* device,const std::shared_ptr<donut::engine::Scene>& scene,nvrhi::ICommandList* command){
        if(!constantBuffer){
            nvrhi::BufferDesc cb;cb.byteSize=32;cb.isConstantBuffer=true;cb.initialState=nvrhi::ResourceStates::ConstantBuffer;cb.keepInitialState=true;
            constantBuffer=device->createBuffer(cb);
            instances=Buffer(device,sizeof(Instance),sizeof(Instance));triangles=Buffer(device,sizeof(Triangle),sizeof(Triangle));
            nvrhi::BufferDesc counters;counters.byteSize=32;counters.canHaveRawViews=counters.canHaveUAVs=true;
            counters.initialState=nvrhi::ResourceStates::UnorderedAccess;counters.keepInitialState=true;statistics=device->createBuffer(counters);
            for(unsigned i=0;i<8;i++){
                nvrhi::TextureDesc td;td.width=td.height=1;td.format=i==0?nvrhi::Format::RGBA32_FLOAT:i==6?nvrhi::Format::RG32_FLOAT:i==7?nvrhi::Format::RGBA32_UINT:nvrhi::Format::RGBA16_FLOAT;
                td.initialState=nvrhi::ResourceStates::ShaderResource;td.keepInitialState=true;dummy[i]=device->createTexture(td);
            }
        }
        if(active&&dirty){
            instanceData.clear();triangleData.clear();
            std::unordered_map<std::string,uint32_t> offsets;
            for(const auto& instance:scene->GetSceneGraph()->GetMeshInstances()){
                const auto& name=instance->GetNode()->GetName();
                if(name.size()<2||name[0]!='i')throw std::runtime_error("Host input requires stable retained instance IDs");
                size_t parsed=0;uint64_t id=std::stoull(name.substr(1),&parsed);
                if(parsed!=name.size()-1)throw std::runtime_error("Malformed retained instance identity");
                const auto& meshName=instance->GetMesh()->name;
                auto mapping=meshTriangles.find(meshName);if(mapping==meshTriangles.end())throw std::runtime_error("Missing original-triangle mapping: "+meshName);
                auto [offset,inserted]=offsets.emplace(meshName,uint32_t(triangleData.size()));
                if(inserted)triangleData.insert(triangleData.end(),mapping->second.begin(),mapping->second.end());
                Instance record{};record.low=uint32_t(id);record.high=uint32_t(id>>32);record.instanceIndex=instance->GetInstanceIndex();
                record.triangleOffset=offset->second;record.triangleCount=uint32_t(mapping->second.size());instanceData.push_back(record);
            }
            std::sort(instanceData.begin(),instanceData.end(),[](const Instance& a,const Instance& b){return a.high<b.high||(a.high==b.high&&a.low<b.low);});
            auto nextInstances=Buffer(device,std::max<size_t>(sizeof(Instance),instanceData.size()*sizeof(Instance)),sizeof(Instance));
            auto nextTriangles=Buffer(device,std::max<size_t>(sizeof(Triangle),triangleData.size()*sizeof(Triangle)),sizeof(Triangle));
            // Binding sets/command lists retain the old buffers until their submitted work retires.
            instances=nextInstances;triangles=nextTriangles;
            if(!instanceData.empty())command->writeBuffer(instances,instanceData.data(),instanceData.size()*sizeof(Instance));
            if(!triangleData.empty())command->writeBuffer(triangles,triangleData.data(),triangleData.size()*sizeof(Triangle));
            dirty=false;
        }
        Constants constants;constants.extent[0]=inputWidth;constants.extent[1]=inputHeight;constants.extent[2]=active?1:0;constants.extent[3]=composition;
        constants.counts[0]=uint32_t(instanceData.size());constants.counts[1]=hostComponents;constants.counts[2]=motionIncludesJitter?1:0;constants.counts[3]=workStatisticsEnabled?1:0;
        command->writeBuffer(constantBuffer,&constants,sizeof(constants));
        if(active&&workStatisticsEnabled)command->clearBufferUInt(statistics,0);
    }
    void Bind(nvrhi::BindingSetDesc& desc){
        desc.bindings.push_back(nvrhi::BindingSetItem::ConstantBuffer(2,constantBuffer));
        for(unsigned i=0;i<8;i++)desc.bindings.push_back(nvrhi::BindingSetItem::Texture_SRV(99+i,active?inputs[i]:dummy[i]));
        desc.bindings.push_back(nvrhi::BindingSetItem::StructuredBuffer_SRV(107,instances));
        desc.bindings.push_back(nvrhi::BindingSetItem::StructuredBuffer_SRV(108,triangles));
        desc.bindings.push_back(nvrhi::BindingSetItem::RawBuffer_UAV(97,statistics));
    }
private:
    static nvrhi::BufferHandle Buffer(nvrhi::IDevice* device,size_t bytes,uint32_t stride){
        nvrhi::BufferDesc desc;desc.byteSize=bytes;desc.structStride=stride;desc.initialState=nvrhi::ResourceStates::ShaderResource;desc.keepInitialState=true;
        auto result=device->createBuffer(desc);if(!result)throw std::runtime_error("Could not allocate host-surface identity mapping");return result;
    }
};
#undef RTR_HOST_SURFACE_NOINLINE
