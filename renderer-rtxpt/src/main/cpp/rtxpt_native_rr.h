#pragma once
#include "rtxpt_cached_bindings.h"
#include <donut/app/StreamlineInterface.h>
#include <donut/engine/View.h>

// One diffuse radiance layer. Optional lower-grid RR never changes direct gain
// or the published lighting extent; all resampling uses this frame's receivers.
class RtrNativeRR {
public:
    donut::app::StreamlineInterface* streamline = nullptr;
    bool requested=false,valid=false,reset=true,guidesNeedClear=false;
    uint64_t evaluated=0,resets=0,lastSequence=0;
    uint32_t width=0,height=0;
    uint32_t divisor=1,allocatedDivisor=0,evaluationWidth=0,evaluationHeight=0;
    uint64_t resourceBytes=0;
    nvrhi::BindingLayoutHandle layout,resolveLayout,reduceLayout,upsampleLayout;
    nvrhi::ComputePipelineHandle pipeline,singleGroupPipeline,sparseOnlyPipeline,sparseOnlySingleGroupPipeline,pairGroupPipeline,sparseOnlyPairGroupPipeline,smallGroupPipeline,sparseOnlySmallGroupPipeline,resolvePipeline;
    std::array<nvrhi::ComputePipelineHandle,8> uninstrumentedPipelines;
    std::array<nvrhi::ComputePipelineHandle,16> maxPipelines;
    std::array<nvrhi::ComputePipelineHandle,8> countedMaxPipelines;
    std::array<nvrhi::ComputePipelineHandle,16> channelMaxPipelines;
    nvrhi::BufferHandle constants;
    std::array<nvrhi::TextureHandle,6> guides;
    nvrhi::TextureHandle reconstructed,result;
    nvrhi::TextureHandle surfaces,reducedRaw;
    std::array<nvrhi::TextureHandle,6> reducedGuides;
    nvrhi::ComputePipelineHandle reducePipeline,upsamplePipeline;
    RtrCachedBindings resolveBindings,reduceBindings,upsampleBindings;
    struct alignas(16) Parameters {dm::float4x4 worldToClip,previousWorldToClip;float4 depth;};
    Parameters parameters{};
    dm::float4x4 previous=dm::float4x4::identity();
    void Prepare(nvrhi::IDevice* device,donut::engine::ShaderFactory& factory,nvrhi::IBindingLayout* base,nvrhi::IBindingLayout* bindless,nvrhi::IBindingLayout* sourceHistory,uint32_t w,uint32_t h,unsigned groupVariant,bool maximum,bool allDynamic,bool channelMaximum){
        if(!requested)return;
        if(!streamline || !streamline->IsDLSSRRAvailable())throw std::runtime_error("Native diffuse GI RR is not available on this device");
        if(!pipeline){
            auto desc=*base->getDesc();desc.bindings.push_back(nvrhi::BindingLayoutItem::ConstantBuffer(1));
            for(uint32_t i=5;i<=11;i++)desc.bindings.push_back(nvrhi::BindingLayoutItem::Texture_UAV(i));
            layout=device->createBindingLayout(desc);std::vector<donut::engine::ShaderMacro> defaultMacros{{"RTR_LIGHT_GROUP_CAPACITY","32"},{"RTR_ANALYTIC_LIGHTS","1"},{"RTR_WORK_STATISTICS","1"},{"RTR_MAX_COMPOSITION","0"}};
            auto shader=factory.CreateShader("app/ProcessingPasses/RtrNativeLightingReconstruct.hlsl","main",&defaultMacros,nvrhi::ShaderType::Compute);
            if(!layout||!shader)throw std::runtime_error("Native GI RR guide shader unavailable");
            nvrhi::ComputePipelineDesc pso;pso.bindingLayouts={layout,bindless};pso.CS=shader;pipeline=device->createComputePipeline(pso);
            nvrhi::BufferDesc cb;cb.byteSize=sizeof(Parameters);cb.isConstantBuffer=true;cb.initialState=nvrhi::ResourceStates::ConstantBuffer;cb.keepInitialState=true;constants=device->createBuffer(cb);
            nvrhi::BindingLayoutDesc rd;rd.visibility=nvrhi::ShaderType::Compute;rd.bindings={nvrhi::BindingLayoutItem::Texture_SRV(0),nvrhi::BindingLayoutItem::Texture_SRV(1),nvrhi::BindingLayoutItem::Texture_UAV(0)};
            resolveLayout=device->createBindingLayout(rd);pso.bindingLayouts={resolveLayout};pso.CS=factory.CreateShader("app/ProcessingPasses/RtrNativeLightingResolve.hlsl","main",nullptr,nvrhi::ShaderType::Compute);resolvePipeline=device->createComputePipeline(pso);
            if(!pipeline||!constants||!resolvePipeline)throw std::runtime_error("Native GI RR pipeline allocation failed");
        }
        const unsigned instrumentedCapacity=groupVariant==1?1:groupVariant==6?2:groupVariant==4?4:32;
        for(unsigned analytic=0;analytic<2;++analytic){
            auto& target=instrumentedCapacity==1?(analytic?singleGroupPipeline:sparseOnlySingleGroupPipeline):
                instrumentedCapacity==2?(analytic?pairGroupPipeline:sparseOnlyPairGroupPipeline):
                instrumentedCapacity==4?(analytic?smallGroupPipeline:sparseOnlySmallGroupPipeline):(analytic?pipeline:sparseOnlyPipeline);
            if(RtrDiagnostics::level.load()==2&&!target){
                RtrDiagnostics::Scope prepare(RtrDiagnostics::Pipeline,1);
                std::vector<donut::engine::ShaderMacro> macros{{"RTR_LIGHT_GROUP_CAPACITY",std::to_string(instrumentedCapacity)},
                    {"RTR_ANALYTIC_LIGHTS",analytic?"1":"0"},{"RTR_WORK_STATISTICS","1"},{"RTR_MAX_COMPOSITION","0"}};
                nvrhi::ComputePipelineDesc pso;pso.bindingLayouts={layout,bindless};
                pso.CS=factory.CreateShader("app/ProcessingPasses/RtrNativeLightingReconstruct.hlsl","main",&macros,nvrhi::ShaderType::Compute);
                target=device->createComputePipeline(pso);if(!target)throw std::runtime_error("Diagnostic lighting pipeline unavailable");
            }
        }
        for(unsigned i:{groupVariant,groupVariant+(groupVariant<4?2u:1u)})if(!uninstrumentedPipelines[i]){
            RtrDiagnostics::Scope prepare(RtrDiagnostics::Pipeline,1);
            std::vector<donut::engine::ShaderMacro> macros{{"RTR_LIGHT_GROUP_CAPACITY",i>=6?"2":i>=4?"4":(i&1)?"1":"32"},
                {"RTR_ANALYTIC_LIGHTS",(i>=4?(i&1)!=0:(i&2)!=0)?"0":"1"},{"RTR_WORK_STATISTICS","0"},{"RTR_MAX_COMPOSITION","0"}};
            nvrhi::ComputePipelineDesc pso;pso.bindingLayouts={layout,bindless};
            pso.CS=factory.CreateShader("app/ProcessingPasses/RtrNativeLightingReconstruct.hlsl","main",&macros,nvrhi::ShaderType::Compute);
            uninstrumentedPipelines[i]=device->createComputePipeline(pso);
            if(!uninstrumentedPipelines[i])throw std::runtime_error("Production lighting pipeline unavailable");
        }
        const unsigned maxOffset=allDynamic?8:0;
        if(maximum)for(unsigned i:{groupVariant,groupVariant+(groupVariant<4?2u:1u)})if(!maxPipelines[maxOffset+i]){
            std::vector<donut::engine::ShaderMacro> macros{{"RTR_LIGHT_GROUP_CAPACITY",i>=6?"2":i>=4?"4":(i&1)?"1":"32"},{"RTR_ANALYTIC_LIGHTS",(i>=4?(i&1)!=0:(i&2)!=0)?"0":"1"},{"RTR_WORK_STATISTICS","0"},{"RTR_MAX_COMPOSITION","1"}};
            nvrhi::ComputePipelineDesc pso;pso.bindingLayouts={layout,bindless,sourceHistory};
            pso.CS=factory.CreateShader(allDynamic?"app/ProcessingPasses/RtrNativeDynamicLightingReconstruct.hlsl":"app/ProcessingPasses/RtrNativeLightingReconstruct.hlsl","main",&macros,nvrhi::ShaderType::Compute);maxPipelines[maxOffset+i]=device->createComputePipeline(pso);
            if(!maxPipelines[maxOffset+i])throw std::runtime_error("Max-composition native GI RR pipeline unavailable");
        }
        // Diagnostic observation must not force a different composition
        // executor. Count actual work in the exact maximum-only path, with no
        // previous-frame winner seed or extra history binding.
        if(maximum&&RtrDiagnostics::level.load()==2)for(unsigned i:{groupVariant,groupVariant+(groupVariant<4?2u:1u)})if(!countedMaxPipelines[i]){
            std::vector<donut::engine::ShaderMacro> macros{{"RTR_LIGHT_GROUP_CAPACITY",i>=6?"2":i>=4?"4":(i&1)?"1":"32"},
                {"RTR_ANALYTIC_LIGHTS",(i>=4?(i&1)!=0:(i&2)!=0)?"0":"1"},{"RTR_WORK_STATISTICS","1"},{"RTR_MAX_COMPOSITION","1"}};
            nvrhi::ComputePipelineDesc pso;pso.bindingLayouts={layout,bindless};
            pso.CS=factory.CreateShader("app/ProcessingPasses/RtrNativeLightingReconstruct.hlsl","main",&macros,nvrhi::ShaderType::Compute);
            countedMaxPipelines[i]=device->createComputePipeline(pso);
            if(!countedMaxPipelines[i])throw std::runtime_error("Counted maximum-composition lighting pipeline unavailable");
        }
        // CHANNEL_MAX has independent RGB winners and needs neither scalar
        // luminance/ID arrays nor a dynamically indexed per-ray stack.
        if(channelMaximum)for(unsigned instrumented=0;instrumented<2;++instrumented)
            for(unsigned i:{groupVariant,groupVariant+(groupVariant<4?2u:1u)})if(!channelMaxPipelines[instrumented*8+i]){
                std::vector<donut::engine::ShaderMacro> macros{{"RTR_LIGHT_GROUP_CAPACITY",i>=6?"2":i>=4?"4":(i&1)?"1":"32"},
                    {"RTR_ANALYTIC_LIGHTS",(i>=4?(i&1)!=0:(i&2)!=0)?"0":"1"},{"RTR_WORK_STATISTICS",instrumented?"1":"0"},{"RTR_MAX_COMPOSITION","2"}};
                nvrhi::ComputePipelineDesc pso;pso.bindingLayouts={layout,bindless};
                pso.CS=factory.CreateShader("app/ProcessingPasses/RtrNativeLightingReconstruct.hlsl","main",&macros,nvrhi::ShaderType::Compute);
                channelMaxPipelines[instrumented*8+i]=device->createComputePipeline(pso);
                if(!channelMaxPipelines[instrumented*8+i])throw std::runtime_error("Channel-maximum lighting pipeline unavailable");
            }
        if(divisor>1&&!reducePipeline){
            nvrhi::BindingLayoutDesc desc;desc.visibility=nvrhi::ShaderType::Compute;
            for(uint32_t i=0;i<5;i++)desc.bindings.push_back(nvrhi::BindingLayoutItem::Texture_SRV(i));
            for(uint32_t i=0;i<5;i++)desc.bindings.push_back(nvrhi::BindingLayoutItem::Texture_UAV(i));
            reduceLayout=device->createBindingLayout(desc);nvrhi::ComputePipelineDesc pso;pso.bindingLayouts={reduceLayout};
            pso.CS=factory.CreateShader("app/ProcessingPasses/RtrNativeGiReduce.hlsl","main",nullptr,nvrhi::ShaderType::Compute);reducePipeline=device->createComputePipeline(pso);
            desc.bindings.clear();for(uint32_t i=0;i<5;i++)desc.bindings.push_back(nvrhi::BindingLayoutItem::Texture_SRV(i));
            desc.bindings.push_back(nvrhi::BindingLayoutItem::Texture_UAV(0));upsampleLayout=device->createBindingLayout(desc);pso.bindingLayouts={upsampleLayout};
            pso.CS=factory.CreateShader("app/ProcessingPasses/RtrNativeGiUpsample.hlsl","main",nullptr,nvrhi::ShaderType::Compute);upsamplePipeline=device->createComputePipeline(pso);
            if(!reducePipeline||!upsamplePipeline)throw std::runtime_error("Independent GI RR resampling pipeline unavailable");
        }
        if(width==w&&height==h&&allocatedDivisor==divisor)return;
        // NVRHI command-list references retain old textures until GPU completion.
        const uint32_t ew=(w+divisor-1)/divisor,eh=(h+divisor-1)/divisor;
        std::array<nvrhi::TextureHandle,8> next;
        auto texture=[&](uint32_t x,uint32_t y,nvrhi::Format format,const char* name){
            auto desc=nvrhi::TextureDesc().setWidth(x).setHeight(y).setFormat(format).setIsUAV(true)
                .setInitialState(nvrhi::ResourceStates::UnorderedAccess).setKeepInitialState(true);desc.debugName=name;
            auto value=device->createTexture(desc);if(!value)throw std::runtime_error("Native GI RR texture allocation failed");return value;
        };
        for(unsigned i=0;i<next.size();i++){
            auto format=i==3?nvrhi::Format::RG16_FLOAT:(i==4||i==5)?nvrhi::Format::R32_FLOAT:nvrhi::Format::RGBA16_FLOAT;
            next[i]=texture(i==6?ew:w,i==6?eh:h,format,"Native diffuse GI RR");
        }
        auto nextSurfaces=texture(divisor>1?w:1,divisor>1?h:1,nvrhi::Format::RGBA32_UINT,"Current GI receiver identity and distance");
        std::array<nvrhi::TextureHandle,6> nextReduced; nvrhi::TextureHandle nextRaw;
        if(divisor>1){
            for(unsigned i=0;i<6;i++)nextReduced[i]=texture(ew,eh,next[i]->getDesc().format,"Reduced current GI RR guide");
            nextRaw=texture(ew,eh,nvrhi::Format::RGBA16_FLOAT,"Reduced current GI radiance");
        }
        for(unsigned i=0;i<6;i++)guides[i]=next[i];reconstructed=next[6];result=next[7];surfaces=nextSurfaces;
        reducedGuides=std::move(nextReduced);reducedRaw=nextRaw;
        resolveBindings.value=nullptr;reduceBindings.value=nullptr;upsampleBindings.value=nullptr;
        width=w;height=h;evaluationWidth=ew;evaluationHeight=eh;allocatedDivisor=divisor;
        // Logical texture bytes, excluding opaque Streamline network allocations.
        resourceBytes=uint64_t(w)*h*44+uint64_t(ew)*eh*8+uint64_t(divisor>1?w:1)*(divisor>1?h:1)*16;
        if(divisor>1)resourceBytes+=uint64_t(ew)*eh*44;
        valid=false;reset=true;guidesNeedClear=true;
    }
    void Bind(nvrhi::ICommandList* command,nvrhi::BindingSetDesc& bindings,const float* words,uint64_t sequence){
        // Diffuse-only reconstruction has invariant zero specular inputs. Clear
        // once per allocation instead of writing twelve MB of zeros every frame.
        if(guidesNeedClear){for(unsigned i:{1u,5u}){command->clearTextureFloat(guides[i],nvrhi::AllSubresources,nvrhi::Color(0));
                if(divisor>1)command->clearTextureFloat(reducedGuides[i],nvrhi::AllSubresources,nvrhi::Color(0));}guidesNeedClear=false;}
        dm::float4x4 inverseClip,cameraToWorld;std::memcpy(&inverseClip,words,64);std::memcpy(&cameraToWorld,words+16,64);
        parameters.worldToClip=dm::inverse(cameraToWorld*inverseClip);
        if(sequence!=lastSequence+1)reset=true;
        parameters.previousWorldToClip=reset?parameters.worldToClip:previous;
        bool negative=words[35]<0||words[36]<0;parameters.depth=float4(negative?.5f:1.f,negative?.5f:0.f,divisor>1?float(divisor):0.f,0);
        command->writeBuffer(constants,&parameters,sizeof(parameters));bindings.bindings.push_back(nvrhi::BindingSetItem::ConstantBuffer(1,constants));
        for(uint32_t i=0;i<6;i++)bindings.bindings.push_back(nvrhi::BindingSetItem::Texture_UAV(i+5,guides[i]));
        bindings.bindings.push_back(nvrhi::BindingSetItem::Texture_UAV(11,surfaces));
    }
    void Evaluate(nvrhi::IDevice* device,nvrhi::ICommandList* command,nvrhi::ITexture* raw,const float* words,uint64_t sequence){
        if(divisor>1){
            const auto scope=RtrGpuProfile::Begin(command,"NativeGiRRCurrentFrameReduce",-1,0,0,divisor);
            nvrhi::BindingSetDesc bindings;bindings.bindings={nvrhi::BindingSetItem::Texture_SRV(0,raw),
                nvrhi::BindingSetItem::Texture_SRV(1,guides[0]),nvrhi::BindingSetItem::Texture_SRV(2,guides[2]),
                nvrhi::BindingSetItem::Texture_SRV(3,guides[3]),nvrhi::BindingSetItem::Texture_SRV(4,guides[4]),
                nvrhi::BindingSetItem::Texture_UAV(0,reducedRaw),nvrhi::BindingSetItem::Texture_UAV(1,reducedGuides[0]),
                nvrhi::BindingSetItem::Texture_UAV(2,reducedGuides[2]),nvrhi::BindingSetItem::Texture_UAV(3,reducedGuides[3]),
                nvrhi::BindingSetItem::Texture_UAV(4,reducedGuides[4])};
            nvrhi::ComputeState state;state.pipeline=reducePipeline;state.bindings={reduceBindings.Get(device,bindings,reduceLayout)};
            command->setComputeState(state);command->dispatch((evaluationWidth+7)/8,(evaluationHeight+7)/8);RtrGpuProfile::End(command,scope);
        }
        if(!streamline)throw std::runtime_error("Native GI RR has no public Streamline integration");
        using Interface = donut::app::StreamlineInterface;
        // Keep the independent diffuse layer on its own public viewport. The
        // original sample continues to own frame tokens, SDK calls and errors.
        streamline->SetViewport(1);
        struct ViewportGuard { Interface& value; ~ViewportGuard(){value.SetViewport(0);} } viewportGuard{*streamline};
        dm::float4x4 inverseClip,cameraToWorld;std::memcpy(&inverseClip,words,64);std::memcpy(&cameraToWorld,words+16,64);
        auto projection=dm::inverse(inverseClip);
        if(parameters.depth.x==.5f)for(unsigned i=0;i<4;i++)projection[2][i]=.5f*(projection[2][i]+projection[3][i]);
        auto hwInverseClip=dm::inverse(projection);
        Interface::Constants c{};c.cameraViewToClip=dm::transpose(projection);c.clipToCameraView=dm::transpose(hwInverseClip);
        auto previousHardware=parameters.previousWorldToClip;
        if(parameters.depth.x==.5f)for(unsigned i=0;i<4;i++)previousHardware[2][i]=.5f*(previousHardware[2][i]+previousHardware[3][i]);
        const auto reprojection=dm::transpose(cameraToWorld*hwInverseClip)*dm::transpose(previousHardware);
        c.clipToPrevClip=reprojection;c.prevClipToClip=dm::inverse(reprojection);c.clipToLensClip=dm::float4x4::identity();
        c.cameraPos={words[32],words[33],words[34]};c.cameraRight={words[16],words[20],words[24]};c.cameraUp={words[17],words[21],words[25]};c.cameraFwd={-words[18],-words[22],-words[26]};
        c.cameraNear=.001f;c.cameraFar=1e7f;c.cameraFOV=2*std::atan(std::abs(words[5]));c.cameraAspectRatio=std::abs(words[0]/words[5]);
        c.jitterOffset={-words[37]*evaluationWidth*.5f,words[38]*evaluationHeight*.5f};c.mvecScale={1,1};c.cameraPinholeOffset={0,0};
        c.depthInverted=words[35]>words[36];c.cameraMotionIncluded=true;
        c.motionVectors3D=false;c.motionVectorsDilated=false;c.motionVectorsJittered=false;c.orthographicProjection=false;c.motionVectorsInvalidValue=FLT_MIN;c.reset=reset;
        streamline->SetConstants(c);
        Interface::DLSSRROptions options;options.mode=Interface::DLSSMode::eDLAA;options.outputWidth=evaluationWidth;options.outputHeight=evaluationHeight;
        options.colorBuffersHDR=true;options.preExposure=1;options.exposureScale=1;options.normalRoughnessMode=Interface::DLSSRRNormalRoughnessMode::ePacked;
        options.worldToCameraView=dm::transpose(dm::inverse(cameraToWorld));options.cameraViewToWorld=dm::transpose(cameraToWorld);
        // Options carry the current camera matrices as well as the extent.
        streamline->SetDLSSRROptions(options);
        auto& actualGuides=divisor>1?reducedGuides:guides;
        donut::engine::PlanarView view;
        view.SetViewport(nvrhi::Viewport(float(evaluationWidth),float(evaluationHeight)));
        view.SetMatrices(dm::homogeneousToAffine(options.worldToCameraView),c.cameraViewToClip);view.UpdateCache();
        streamline->TagResourcesGeneral(command,&view,actualGuides[3],actualGuides[4],divisor>1?reducedRaw.Get():raw);
        streamline->TagResourcesDLSSRR(command,&view,dm::int2(evaluationWidth,evaluationHeight),
            dm::int2(evaluationWidth,evaluationHeight),divisor>1?reducedRaw.Get():raw,
            actualGuides[0],actualGuides[1],actualGuides[2],nullptr,actualGuides[5],nullptr,reconstructed);
        command->commitBarriers();
        const auto scope=RtrGpuProfile::Begin(command,"NativeDiffuseGiDLSSRR",-1,0,0,divisor);
        streamline->EvaluateDLSSRR(command);
        command->clearState();RtrGpuProfile::End(command,scope);
        nvrhi::BindingSetDesc bindings;bindings.bindings={nvrhi::BindingSetItem::Texture_SRV(0,reconstructed),nvrhi::BindingSetItem::Texture_SRV(1,raw),nvrhi::BindingSetItem::Texture_UAV(0,result)};
        nvrhi::ComputeState state;
        const auto resolveScope=divisor>1?RtrGpuProfile::Begin(command,"NativeGiRRGeometryUpsample",-1,0,0,divisor):RtrGpuProfile::Token{};
        if(divisor>1){
            bindings.bindings.push_back(nvrhi::BindingSetItem::Texture_SRV(2,guides[0]));
            bindings.bindings.push_back(nvrhi::BindingSetItem::Texture_SRV(3,guides[2]));
            bindings.bindings.push_back(nvrhi::BindingSetItem::Texture_SRV(4,surfaces));
            state.pipeline=upsamplePipeline;state.bindings={upsampleBindings.Get(device,bindings,upsampleLayout)};
        }else{state.pipeline=resolvePipeline;state.bindings={resolveBindings.Get(device,bindings,resolveLayout)};}
        command->setComputeState(state);command->dispatch((width+7)/8,(height+7)/8);if(divisor>1)RtrGpuProfile::End(command,resolveScope);
        ++evaluated;if(reset)++resets;reset=false;valid=true;previous=parameters.worldToClip;lastSequence=sequence;
    }
};
