#pragma once
#include <array>
#include <cstdint>
#include <stdexcept>
#include <vector>
#include "rtxpt_native_lighting.h"
#include "rtxpt_host_surface.h"

// Include after the existing RTXPT/Donut host headers, like rtxpt_native_lighting.h.
// This owns only a compute PSO and requested outputs. Scene/BLAS/TLAS, material
// descriptors, native attributes, source trees and all input leases stay external.
class RtrHostAugmentation {
public:
    static constexpr uint32_t SupportedOutputs=1|2|8|64|128;
    struct Plan {
        uint32_t width=0,height=0,outputs=2|8,colorDomain=0;
        bool hostColorPresent=true;
        void Validate()const{
            if(width<1||height<1||width>16384||height>16384||outputs==0||(outputs&~SupportedOutputs)!=0||colorDomain>1)
                throw std::invalid_argument("Invalid host augmentation dimensions, outputs or working color domain");
            if((outputs&1)!=0&&!hostColorPresent)throw std::invalid_argument("Combined augmentation requires the actual host-lit color input");
        }
        uint32_t NativeMask()const{return ((outputs&(1|2))?1:0)|((outputs&(1|8))?2:0);}
    };
    struct Constants {uint32_t extents[4]{},options[4]{},counts[4]{};};
    static_assert(sizeof(Constants)==48);
    Plan plan;
    RtrNativeLightingPass::Constants lightingConstants;
    Plan pendingPlan;
    RtrNativeLightingPass::Constants pendingLightingConstants;
    nvrhi::BufferHandle constants;
    nvrhi::BindingLayoutHandle layout;
    nvrhi::ComputePipelineHandle pipeline;
    std::array<nvrhi::TextureHandle,8> outputs{};
    uint64_t executedFrames=0;
    bool configured=false,pending=false;

    void Configure(RtrNativeLightingPass& shared,const Plan& requested,const std::vector<float>& nativeParameters){
        requested.Validate();
        const auto previous=shared.constants;const bool wasActive=shared.active;
        try{
            // Reuse the complete public native-style parameter validation and bit packing.
            shared.Configure(nativeParameters);auto prepared=shared.constants;
            auto* words=reinterpret_cast<uint32_t*>(prepared.words.data());
            if(words[40]!=requested.width||words[41]!=requested.height)throw std::invalid_argument("Augmentation grid differs from lighting parameter grid");
            words[42]=requested.NativeMask();
            shared.constants=previous;shared.active=wasActive;
            pendingPlan=requested;pendingLightingConstants=prepared;pending=true;
        }catch(...){shared.constants=previous;shared.active=wasActive;throw;}
    }
    void DiscardPendingConfiguration(){pending=false;}
    void Prepare(nvrhi::IDevice* device,donut::engine::ShaderFactory& factory,nvrhi::IBindingLayout* bindless,RtrNativeLightingPass& shared){
        if(!configured&&!pending)throw std::logic_error("Configure host augmentation before preparing it");
        const auto nextPlan=pending?pendingPlan:plan;
        const auto nextLighting=pending?pendingLightingConstants:lightingConstants;
        // Prepare shared native buffers/layout without allocating a second set of
        // full-grid native output textures. Only this pass's requested planes exist.
        const auto previous=shared.constants;shared.constants=nextLighting;
        reinterpret_cast<uint32_t*>(shared.constants.words.data())[42]=0;
        try{shared.Prepare(device,factory,bindless);}catch(...){shared.constants=previous;throw;}
        shared.constants=previous;
        if(!pipeline){
            nvrhi::BindingLayoutDesc desc;desc.visibility=nvrhi::ShaderType::Compute;
            desc.bindings={nvrhi::BindingLayoutItem::ConstantBuffer(1)};
            for(uint32_t i=12;i<=20;i++)desc.bindings.push_back(nvrhi::BindingLayoutItem::Texture_SRV(i));
            desc.bindings.push_back(nvrhi::BindingLayoutItem::StructuredBuffer_SRV(21));
            desc.bindings.push_back(nvrhi::BindingLayoutItem::StructuredBuffer_SRV(22));
            for(uint32_t slot:{5u,6u,8u,11u,12u})desc.bindings.push_back(nvrhi::BindingLayoutItem::Texture_UAV(slot));
            desc.bindings.push_back(nvrhi::BindingLayoutItem::RawBuffer_UAV(13));
            auto preparedLayout=device->createBindingLayout(desc);
            auto shader=factory.CreateShader("app/ProcessingPasses/RtrHostAugmentation.hlsl","main",nullptr,nvrhi::ShaderType::Compute);
            if(!shader||!preparedLayout)throw std::runtime_error("Host augmentation shader/layout is unavailable");
            nvrhi::ComputePipelineDesc pso;pso.bindingLayouts={shared.layout,preparedLayout,bindless};pso.CS=shader;
            auto preparedPipeline=device->createComputePipeline(pso);
            if(!preparedPipeline)throw std::runtime_error("Could not prepare host augmentation compute pipeline");
            nvrhi::BufferDesc cb;cb.byteSize=sizeof(Constants);cb.isConstantBuffer=true;
            cb.initialState=nvrhi::ResourceStates::ConstantBuffer;cb.keepInitialState=true;cb.debugName="Host augmentation parameters";
            auto preparedConstants=device->createBuffer(cb);
            if(!preparedConstants)throw std::runtime_error("Could not allocate host augmentation parameters");
            layout=preparedLayout;pipeline=preparedPipeline;constants=preparedConstants;
        }
        auto next=outputs;bool changed=false;
        for(uint32_t i=0;i<8;i++){
            if(!(nextPlan.outputs&(1u<<i))){if(next[i]){next[i]=nullptr;changed=true;}continue;}
            if(next[i]&&next[i]->getDesc().width==nextPlan.width&&next[i]->getDesc().height==nextPlan.height)continue;
            auto desc=nvrhi::TextureDesc().setWidth(nextPlan.width).setHeight(nextPlan.height).setFormat(nvrhi::Format::RGBA16_FLOAT).setIsUAV(true)
                .setInitialState(nvrhi::ResourceStates::UnorderedAccess).setKeepInitialState(true);
            desc.debugName="Requested host augmentation signal";next[i]=device->createTexture(desc);
            if(!next[i])throw std::runtime_error("Could not allocate requested host augmentation signal");changed=true;
        }
        if(changed){shared.Retire(device,{},std::vector<nvrhi::TextureHandle>(outputs.begin(),outputs.end()));outputs=std::move(next);}
        plan=nextPlan;lightingConstants=nextLighting;configured=true;pending=false;
    }
    const std::array<nvrhi::TextureHandle,8>& Outputs()const{return outputs;}
    void Dispatch(nvrhi::IDevice* device,nvrhi::ICommandList* command,const std::shared_ptr<donut::engine::Scene>& scene,
            nvrhi::rt::IAccelStruct* tlas,RtrSparseLighting& sources,nvrhi::ISampler* sampler,nvrhi::IBuffer* analytic,
            uint32_t lightCount,uint32_t lightRoot,uint32_t treeBase,RtrNativeLightingPass& shared,RtrHostSurface& host,
            const std::array<nvrhi::TextureHandle,10>& inputs,int64_t timeNanos,int64_t deltaNanos){
        if(!configured||!pipeline||!scene||!tlas||!host.active)throw std::logic_error("Host augmentation resources and matching input must be prepared");
        for(uint32_t i=0;i<10;i++){
            if(i==9&&!plan.hostColorPresent)continue;
            if(!inputs[i]||inputs[i]->getDesc().width!=host.inputWidth||inputs[i]->getDesc().height!=host.inputHeight)
                throw std::invalid_argument("Host augmentation requires complete matching surface/baseline/color planes");
        }
        const auto uploadScope=RtrGpuProfile::Begin(command,"HostAugmentationUpload",-1);
        host.Prepare(device,scene,command);shared.UploadAttributes(device,command,scene);sources.Upload(command);
        shared.sequence=host.sequence;shared.sceneRevision=host.revision;
        auto packed=lightingConstants;auto* words=reinterpret_cast<uint32_t*>(packed.words.data());
        words[39]=lightCount;words[51]=lightRoot;words[59]=treeBase;
        words[72]=uint32_t(shared.applicationData.size());words[73]=uint32_t(shared.publication);words[74]=uint32_t(shared.publication>>32);
        words[75]=(shared.workStatisticsEnabled?1:0)|(shared.specialRoleCount?2:0);
        words[76]=uint32_t(timeNanos);words[77]=uint32_t(uint64_t(timeNanos)>>32);words[78]=uint32_t(deltaNanos);words[79]=uint32_t(uint64_t(deltaNanos)>>32);
        command->writeBuffer(shared.constantBuffer,&packed,sizeof(packed));
        Constants parameters;parameters.extents[0]=host.inputWidth;parameters.extents[1]=host.inputHeight;parameters.extents[2]=plan.width;parameters.extents[3]=plan.height;
        parameters.options[0]=plan.outputs;parameters.options[1]=plan.colorDomain;parameters.options[2]=plan.hostColorPresent?1:0;parameters.options[3]=host.workStatisticsEnabled?1:0;
        parameters.counts[0]=uint32_t(host.instanceData.size());parameters.counts[1]=uint32_t(host.triangleData.size());parameters.counts[2]=host.hostComponents;
        command->writeBuffer(constants,&parameters,sizeof(parameters));
        if(shared.workStatisticsEnabled)command->clearBufferUInt(shared.work,0);
        RtrGpuProfile::End(command,uploadScope);
        nvrhi::BindingSetDesc native;native.bindings={nvrhi::BindingSetItem::ConstantBuffer(0,shared.constantBuffer),
            nvrhi::BindingSetItem::RayTracingAccelStruct(0,tlas),nvrhi::BindingSetItem::StructuredBuffer_SRV(1,scene->GetInstanceBuffer()),
            nvrhi::BindingSetItem::StructuredBuffer_SRV(2,scene->GetGeometryBuffer()),nvrhi::BindingSetItem::StructuredBuffer_SRV(3,scene->GetMaterialBuffer()),
            nvrhi::BindingSetItem::StructuredBuffer_SRV(4,sources.sourceBuffer),nvrhi::BindingSetItem::StructuredBuffer_SRV(5,sources.treeBuffer),
            nvrhi::BindingSetItem::StructuredBuffer_SRV(6,shared.instances),nvrhi::BindingSetItem::StructuredBuffer_SRV(7,shared.baselineCoordinates),
            nvrhi::BindingSetItem::StructuredBuffer_SRV(8,shared.geometryBaselines),nvrhi::BindingSetItem::RawBuffer_SRV(9,shared.application),
            nvrhi::BindingSetItem::StructuredBuffer_SRV(10,shared.lookup),nvrhi::BindingSetItem::StructuredBuffer_SRV(11,analytic),nvrhi::BindingSetItem::Sampler(0,sampler),
            nvrhi::BindingSetItem::Texture_UAV(0,shared.dummyColor),nvrhi::BindingSetItem::Texture_UAV(1,shared.dummyColor),
            nvrhi::BindingSetItem::Texture_UAV(2,shared.dummyColor),nvrhi::BindingSetItem::Texture_UAV(3,shared.dummyIdentity),nvrhi::BindingSetItem::RawBuffer_UAV(4,shared.work)};
        nvrhi::BindingSetDesc augmentation;augmentation.bindings={nvrhi::BindingSetItem::ConstantBuffer(1,constants)};
        constexpr uint32_t sourcePlanes[]={0,1,2,3,4,5,8,9,7};
        for(uint32_t i=0;i<9;i++)augmentation.bindings.push_back(nvrhi::BindingSetItem::Texture_SRV(12+i,inputs[sourcePlanes[i]]?inputs[sourcePlanes[i]]:shared.dummyColor));
        augmentation.bindings.push_back(nvrhi::BindingSetItem::StructuredBuffer_SRV(21,host.instances));
        augmentation.bindings.push_back(nvrhi::BindingSetItem::StructuredBuffer_SRV(22,host.triangles));
        for(uint32_t i:{0u,1u,3u,6u,7u})augmentation.bindings.push_back(nvrhi::BindingSetItem::Texture_UAV(5+i,outputs[i]?outputs[i]:shared.dummyColor));
        augmentation.bindings.push_back(nvrhi::BindingSetItem::RawBuffer_UAV(13,host.statistics));
        auto nativeBindings=device->createBindingSet(native,shared.layout);auto hostBindings=device->createBindingSet(augmentation,layout);
        if(!nativeBindings||!hostBindings)throw std::runtime_error("Could not bind host augmentation resources");
        nvrhi::ComputeState state;state.pipeline=pipeline;state.bindings={nativeBindings,hostBindings,scene->GetDescriptorTable()};command->setComputeState(state);
        const auto dispatchScope=RtrGpuProfile::Begin(command,"HostAugmentationDispatch",-1);
        command->dispatch((plan.width+7)/8,(plan.height+7)/8);RtrGpuProfile::End(command,dispatchScope);++executedFrames;
    }
};
