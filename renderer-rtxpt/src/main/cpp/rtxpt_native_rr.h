#pragma once
#include "rtxpt_streamline_cache.h"
#include "rtxpt_cached_bindings.h"
#include <sl_dlss_d.h>

// One diffuse radiance layer, at the lighting-grid extent. Spatial composition
// samples the independent full-resolution host base afterwards.
class RtrNativeRR {
public:
    bool requested=false,valid=false,reset=true,guidesNeedClear=false;
    uint64_t evaluated=0,resets=0,lastSequence=0;
    uint32_t width=0,height=0;
    nvrhi::BindingLayoutHandle layout,resolveLayout;
    nvrhi::ComputePipelineHandle pipeline,singleGroupPipeline,sparseOnlyPipeline,sparseOnlySingleGroupPipeline,smallGroupPipeline,sparseOnlySmallGroupPipeline,resolvePipeline;
    std::array<nvrhi::ComputePipelineHandle,8> uninstrumentedPipelines;
    std::array<nvrhi::ComputePipelineHandle,16> maxPipelines;
    nvrhi::BufferHandle constants;
    std::array<nvrhi::TextureHandle,6> guides;
    nvrhi::TextureHandle reconstructed,result;
    RtrCachedBindings resolveBindings;
    struct alignas(16) Parameters {dm::float4x4 worldToClip,previousWorldToClip;float4 depth;};
    Parameters parameters{};
    dm::float4x4 previous=dm::float4x4::identity();
    static void Check(sl::Result result,const char* stage){
        if(result!=sl::Result::eOk)throw std::runtime_error(std::string("Native GI RR ")+stage+" failed: "+std::to_string(int(result)));
    }
    void Prepare(nvrhi::IDevice* device,donut::engine::ShaderFactory& factory,nvrhi::IBindingLayout* base,nvrhi::IBindingLayout* bindless,nvrhi::IBindingLayout* sourceHistory,uint32_t w,uint32_t h,unsigned groupVariant,bool maximum,bool allDynamic){
        if(!requested)return;
        if(!donut::app::StreamlineIntegration::Get().IsDLSSRRAvailable())throw std::runtime_error("Native diffuse GI RR is not available on this device");
        if(!pipeline){
            auto desc=*base->getDesc();desc.bindings.push_back(nvrhi::BindingLayoutItem::ConstantBuffer(1));
            for(uint32_t i=5;i<=10;i++)desc.bindings.push_back(nvrhi::BindingLayoutItem::Texture_UAV(i));
            layout=device->createBindingLayout(desc);std::vector<donut::engine::ShaderMacro> defaultMacros{{"RTR_LIGHT_GROUP_CAPACITY","32"},{"RTR_ANALYTIC_LIGHTS","1"},{"RTR_WORK_STATISTICS","1"},{"RTR_MAX_COMPOSITION","0"}};
            auto shader=factory.CreateShader("app/ProcessingPasses/RtrNativeLightingReconstruct.hlsl","main",&defaultMacros,nvrhi::ShaderType::Compute);
            if(!layout||!shader)throw std::runtime_error("Native GI RR guide shader unavailable");
            nvrhi::ComputePipelineDesc pso;pso.bindingLayouts={layout,bindless};pso.CS=shader;pipeline=device->createComputePipeline(pso);
            std::vector<donut::engine::ShaderMacro> macros{{"RTR_LIGHT_GROUP_CAPACITY","1"},{"RTR_ANALYTIC_LIGHTS","1"},{"RTR_WORK_STATISTICS","1"},{"RTR_MAX_COMPOSITION","0"}};
            pso.CS=factory.CreateShader("app/ProcessingPasses/RtrNativeLightingReconstruct.hlsl","main",&macros,nvrhi::ShaderType::Compute);singleGroupPipeline=device->createComputePipeline(pso);
            macros={{"RTR_LIGHT_GROUP_CAPACITY","32"},{"RTR_ANALYTIC_LIGHTS","0"},{"RTR_WORK_STATISTICS","1"},{"RTR_MAX_COMPOSITION","0"}};
            pso.CS=factory.CreateShader("app/ProcessingPasses/RtrNativeLightingReconstruct.hlsl","main",&macros,nvrhi::ShaderType::Compute);sparseOnlyPipeline=device->createComputePipeline(pso);
            macros[0].definition="1";
            pso.CS=factory.CreateShader("app/ProcessingPasses/RtrNativeLightingReconstruct.hlsl","main",&macros,nvrhi::ShaderType::Compute);sparseOnlySingleGroupPipeline=device->createComputePipeline(pso);
            for(unsigned analytic=0;analytic<2;++analytic){
                macros={{"RTR_LIGHT_GROUP_CAPACITY","4"},{"RTR_ANALYTIC_LIGHTS",analytic?"1":"0"},{"RTR_WORK_STATISTICS","1"},{"RTR_MAX_COMPOSITION","0"}};
                pso.CS=factory.CreateShader("app/ProcessingPasses/RtrNativeLightingReconstruct.hlsl","main",&macros,nvrhi::ShaderType::Compute);
                auto prepared=device->createComputePipeline(pso);if(!prepared)throw std::runtime_error("Small-group lighting pipeline unavailable");
                (analytic?smallGroupPipeline:sparseOnlySmallGroupPipeline)=prepared;
            }
            for(unsigned i=0;i<uninstrumentedPipelines.size();i++){
                macros={{"RTR_LIGHT_GROUP_CAPACITY",i>=6?"2":i>=4?"4":(i&1)?"1":"32"},{"RTR_ANALYTIC_LIGHTS",(i>=4?(i&1)!=0:(i&2)!=0)?"0":"1"},{"RTR_WORK_STATISTICS","0"},{"RTR_MAX_COMPOSITION","0"}};
                pso.CS=factory.CreateShader("app/ProcessingPasses/RtrNativeLightingReconstruct.hlsl","main",&macros,nvrhi::ShaderType::Compute);
                uninstrumentedPipelines[i]=device->createComputePipeline(pso);
                if(!uninstrumentedPipelines[i])throw std::runtime_error("Uninstrumented native GI RR pipeline unavailable");
            }
            nvrhi::BufferDesc cb;cb.byteSize=sizeof(Parameters);cb.isConstantBuffer=true;cb.initialState=nvrhi::ResourceStates::ConstantBuffer;cb.keepInitialState=true;constants=device->createBuffer(cb);
            nvrhi::BindingLayoutDesc rd;rd.visibility=nvrhi::ShaderType::Compute;rd.bindings={nvrhi::BindingLayoutItem::Texture_SRV(0),nvrhi::BindingLayoutItem::Texture_SRV(1),nvrhi::BindingLayoutItem::Texture_UAV(0)};
            resolveLayout=device->createBindingLayout(rd);pso.bindingLayouts={resolveLayout};pso.CS=factory.CreateShader("app/ProcessingPasses/RtrNativeLightingResolve.hlsl","main",nullptr,nvrhi::ShaderType::Compute);resolvePipeline=device->createComputePipeline(pso);
            if(!pipeline||!singleGroupPipeline||!sparseOnlyPipeline||!sparseOnlySingleGroupPipeline||!constants||!resolvePipeline)throw std::runtime_error("Native GI RR pipeline allocation failed");
        }
        const unsigned maxOffset=allDynamic?8:0;
        if(maximum)for(unsigned i:{groupVariant,groupVariant+(groupVariant<4?2u:1u)})if(!maxPipelines[maxOffset+i]){
            std::vector<donut::engine::ShaderMacro> macros{{"RTR_LIGHT_GROUP_CAPACITY",i>=6?"2":i>=4?"4":(i&1)?"1":"32"},{"RTR_ANALYTIC_LIGHTS",(i>=4?(i&1)!=0:(i&2)!=0)?"0":"1"},{"RTR_WORK_STATISTICS","0"},{"RTR_MAX_COMPOSITION","1"}};
            nvrhi::ComputePipelineDesc pso;pso.bindingLayouts={layout,bindless,sourceHistory};
            pso.CS=factory.CreateShader(allDynamic?"app/ProcessingPasses/RtrNativeDynamicLightingReconstruct.hlsl":"app/ProcessingPasses/RtrNativeLightingReconstruct.hlsl","main",&macros,nvrhi::ShaderType::Compute);maxPipelines[maxOffset+i]=device->createComputePipeline(pso);
            if(!maxPipelines[maxOffset+i])throw std::runtime_error("Max-composition native GI RR pipeline unavailable");
        }
        if(width==w&&height==h)return;
        // NVRHI command-list references retain old textures until GPU completion.
        std::array<nvrhi::TextureHandle,8> next;
        for(unsigned i=0;i<next.size();i++){
            auto format=i==3?nvrhi::Format::RG16_FLOAT:(i==4||i==5)?nvrhi::Format::R32_FLOAT:nvrhi::Format::RGBA16_FLOAT;
            auto td=nvrhi::TextureDesc().setWidth(w).setHeight(h).setFormat(format).setIsUAV(true).setInitialState(nvrhi::ResourceStates::UnorderedAccess).setKeepInitialState(true);
            td.debugName="Native diffuse GI RR";next[i]=device->createTexture(td);if(!next[i])throw std::runtime_error("Native GI RR texture allocation failed");
        }
        for(unsigned i=0;i<6;i++)guides[i]=next[i];reconstructed=next[6];result=next[7];width=w;height=h;valid=false;reset=true;guidesNeedClear=true;
    }
    void Bind(nvrhi::ICommandList* command,nvrhi::BindingSetDesc& bindings,const float* words,uint64_t sequence){
        // Diffuse-only reconstruction has invariant zero specular inputs. Clear
        // once per allocation instead of writing twelve MB of zeros every frame.
        if(guidesNeedClear){for(unsigned i:{1u,5u})command->clearTextureFloat(guides[i],nvrhi::AllSubresources,nvrhi::Color(0));guidesNeedClear=false;}
        dm::float4x4 inverseClip,cameraToWorld;std::memcpy(&inverseClip,words,64);std::memcpy(&cameraToWorld,words+16,64);
        parameters.worldToClip=dm::inverse(cameraToWorld*inverseClip);
        if(sequence!=lastSequence+1)reset=true;
        parameters.previousWorldToClip=reset?parameters.worldToClip:previous;
        bool negative=words[35]<0||words[36]<0;parameters.depth=float4(negative?.5f:1.f,negative?.5f:0.f,0,0);
        command->writeBuffer(constants,&parameters,sizeof(parameters));bindings.bindings.push_back(nvrhi::BindingSetItem::ConstantBuffer(1,constants));
        for(uint32_t i=0;i<6;i++)bindings.bindings.push_back(nvrhi::BindingSetItem::Texture_UAV(i+5,guides[i]));
    }
    void Evaluate(nvrhi::IDevice* device,nvrhi::ICommandList* command,nvrhi::ITexture* raw,const float* words,uint64_t sequence){
        auto& slIntegration=donut::app::StreamlineIntegration::Get();auto* token=slIntegration.HostFrameToken();
        if(!token)throw std::runtime_error("Native GI RR has no Streamline frame token");
        dm::float4x4 inverseClip,cameraToWorld;std::memcpy(&inverseClip,words,64);std::memcpy(&cameraToWorld,words+16,64);
        auto toSl=[](const dm::float4x4& value){sl::float4x4 out;std::memcpy(&out,&value,64);return out;};
        auto projection=dm::inverse(inverseClip);
        if(parameters.depth.x==.5f)for(unsigned i=0;i<4;i++)projection[2][i]=.5f*(projection[2][i]+projection[3][i]);
        auto hwInverseClip=dm::inverse(projection);
        sl::Constants c;c.cameraViewToClip=toSl(dm::transpose(projection));c.clipToCameraView=toSl(dm::transpose(hwInverseClip));
        auto previousHardware=parameters.previousWorldToClip;
        if(parameters.depth.x==.5f)for(unsigned i=0;i<4;i++)previousHardware[2][i]=.5f*(previousHardware[2][i]+previousHardware[3][i]);
        const auto reprojection=dm::transpose(cameraToWorld*hwInverseClip)*dm::transpose(previousHardware);
        c.clipToPrevClip=toSl(reprojection);c.prevClipToClip=toSl(dm::inverse(reprojection));c.clipToLensClip=toSl(dm::float4x4::identity());
        c.cameraPos={words[32],words[33],words[34]};c.cameraRight={words[16],words[20],words[24]};c.cameraUp={words[17],words[21],words[25]};c.cameraFwd={-words[18],-words[22],-words[26]};
        c.cameraNear=.001f;c.cameraFar=1e7f;c.cameraFOV=2*std::atan(std::abs(words[5]));c.cameraAspectRatio=std::abs(words[0]/words[5]);
        c.jitterOffset={-words[37]*width*.5f,words[38]*height*.5f};c.mvecScale={1,1};c.cameraPinholeOffset={0,0};
        c.depthInverted=words[35]>words[36]?sl::Boolean::eTrue:sl::Boolean::eFalse;c.cameraMotionIncluded=sl::Boolean::eTrue;
        c.motionVectors3D=sl::Boolean::eFalse;c.motionVectorsDilated=sl::Boolean::eFalse;c.motionVectorsJittered=sl::Boolean::eFalse;c.orthographicProjection=sl::Boolean::eFalse;c.motionVectorsInvalidValue=FLT_MIN;c.reset=reset?sl::Boolean::eTrue:sl::Boolean::eFalse;
        const sl::ViewportHandle viewport(0);Check(slSetConstants(c,*token,viewport),"constants");
        sl::DLSSDOptions options;options.mode=sl::DLSSMode::eDLAA;options.outputWidth=width;options.outputHeight=height;
        options.colorBuffersHDR=sl::Boolean::eTrue;options.preExposure=1;options.exposureScale=1;options.normalRoughnessMode=sl::DLSSDNormalRoughnessMode::ePacked;
        options.worldToCameraView=toSl(dm::transpose(dm::inverse(cameraToWorld)));options.cameraViewToWorld=toSl(dm::transpose(cameraToWorld));
        Check(slDLSSDSetOptions(viewport,options),"options");
        std::array<nvrhi::ITexture*,8> textures{raw,guides[0],guides[1],guides[2],guides[3],guides[4],guides[5],reconstructed};
        const std::array<sl::BufferType,8> types{sl::kBufferTypeScalingInputColor,sl::kBufferTypeAlbedo,sl::kBufferTypeSpecularAlbedo,sl::kBufferTypeNormalRoughness,sl::kBufferTypeMotionVectors,sl::kBufferTypeDepth,sl::kBufferTypeSpecularHitDistance,sl::kBufferTypeScalingOutputColor};
        sl::Extent extent{0,0,width,height};std::array<sl::Resource,8> resources;std::array<sl::ResourceTag,8> tags;
        for(unsigned i=0;i<8;i++){
            command->setTextureState(textures[i],nvrhi::AllSubresources,nvrhi::ResourceStates::Common);
            resources[i]=sl::Resource{sl::ResourceType::eTex2d,textures[i]->getNativeObject(nvrhi::ObjectTypes::D3D12_Resource),nullptr,nullptr,D3D12_RESOURCE_STATE_COMMON};
            tags[i]=sl::ResourceTag{&resources[i],types[i],sl::ResourceLifecycle::eOnlyValidNow,&extent};
        }
        command->commitBarriers();void* native=command->getNativeObject(nvrhi::ObjectTypes::D3D12_GraphicsCommandList);
        const auto scope=RtrGpuProfile::Begin(command,"NativeDiffuseGiDLSSRR",-1);
        Check(slSetTag(viewport,tags.data(),uint32_t(tags.size()),native),"resource tags");
        const sl::BaseStructure* inputs[]{&viewport};Check(slEvaluateFeature(sl::kFeatureDLSS_RR,*token,inputs,1,native),"evaluate");
        command->clearState();RtrGpuProfile::End(command,scope);
        nvrhi::BindingSetDesc bindings;bindings.bindings={nvrhi::BindingSetItem::Texture_SRV(0,reconstructed),nvrhi::BindingSetItem::Texture_SRV(1,raw),nvrhi::BindingSetItem::Texture_UAV(0,result)};
        auto binding=resolveBindings.Get(device,bindings,resolveLayout);nvrhi::ComputeState state;state.pipeline=resolvePipeline;state.bindings={binding};command->setComputeState(state);command->dispatch((width+7)/8,(height+7)/8);
        ++evaluated;if(reset)++resets;reset=false;valid=true;previous=parameters.worldToClip;lastSequence=sequence;
    }
};
