#pragma once
#include "rtxpt_cached_bindings.h"
#include "rtxpt_optical_triangle_emitters.h"

// Bridge-only transport. Receivers and the acceleration structure are borrowed
// from the lighting frame; none of the upstream path-tracer resources are used.
class RtrDirectionalFlux {
    struct alignas(16) Job {
        dm::float4 originReach,stepXArea,stepY,direction,fluxPerArea;
        std::array<uint32_t,4> extentSeed{};
    };
    struct alignas(16) Parameters {
        dm::float4x4 worldToClip;
        dm::float4 scale,inverseScale;
        std::array<Job,2> jobs{};
        std::array<uint32_t,4> finite{}; // source count, sample grid width
    };
    static_assert(sizeof(Job)==96&&sizeof(Parameters)==304);
    static constexpr uint32_t BinSlot=16,EmitterSlot=16,ConstantSlot=1,IndirectSlot=1,SampleBudget=65536,MaxAxis=512;
    nvrhi::BindingLayoutHandle layout;
    nvrhi::ComputePipelineHandle deposit,resolve;
    nvrhi::BufferHandle constants,emptyEmitterBuffer,emitterBuffer;
    std::array<RtrCachedBindings,2> bindings;

    static nvrhi::BufferHandle MakeEmitterBuffer(nvrhi::IDevice* device,size_t rows){
        nvrhi::BufferDesc desc;
        desc.structStride=sizeof(RtrOpticalTriangleEmitters::Row);
        desc.byteSize=rows*desc.structStride;
        desc.initialState=nvrhi::ResourceStates::ShaderResource;desc.keepInitialState=true;
        desc.debugName="Optical triangle emitter samples";
        auto buffer=device->createBuffer(desc);
        if(!buffer)throw std::runtime_error("Could not allocate optical triangle emitter samples");
        return buffer;
    }

    static bool MakeJob(const float* directional,const float* color,const dm::box3& bounds,
            float epsilon,unsigned seed,Job& job,std::array<double,3>& totalFlux){
        const auto toward=dm::float3(directional[0],directional[1],directional[2]);
        const float len=dm::length(toward);
        if(!(len>0&&std::isfinite(len)&&directional[3]>0))return false;
        const dm::float3 flux(
            std::max(0.f,color[0])*directional[3],std::max(0.f,color[1])*directional[3],
            std::max(0.f,color[2])*directional[3]);
        if(!dm::any(flux>0.f))return false;
        const auto direction=toward*(-1.f/len);
        const auto axisX=dm::normalize(dm::cross(direction,std::abs(direction.y)<.9f?dm::float3(0,1,0):dm::float3(1,0,0)));
        const auto axisY=dm::cross(direction,axisX);
        const auto center=bounds.center(),half=bounds.diagonal()*.5f;
        const float padding=std::max(4*epsilon,dm::length(center)*1.9073486e-6f);
        const float radiusX=dm::dot(half,dm::abs(axisX))+padding,radiusY=dm::dot(half,dm::abs(axisY))+padding;
        const float spanX=2*radiusX,spanY=2*radiusY;
        if(!(spanX>0&&spanY>0&&std::isfinite(spanX)&&std::isfinite(spanY)))return false;
        auto axisSamples=[](double estimate){return uint32_t(std::clamp(estimate,8.,double(MaxAxis)));};
        const uint32_t nx=axisSamples(std::sqrt(double(SampleBudget)*spanX/spanY));
        const uint32_t ny=std::min(axisSamples(std::sqrt(double(SampleBudget)*spanY/spanX)),SampleBudget/nx);
        const auto origin=center-radiusX*axisX-radiusY*axisY-(dm::dot(half,dm::abs(direction))+padding)*direction;
        const float stepArea=(spanX/float(nx))*(spanY/float(ny));
        if(!std::isfinite(stepArea)||!(stepArea>0)||!dm::all(dm::isfinite(origin)))return false;
        job.originReach=dm::float4(origin,1e20f);
        job.stepXArea=dm::float4(axisX*(spanX/float(nx)),stepArea);
        job.stepY=dm::float4(axisY*(spanY/float(ny)),0);
        job.direction=dm::float4(direction,0);
        job.fluxPerArea=dm::float4(flux,0);
        job.extentSeed={nx,ny,seed,0};
        for(unsigned c=0;c<3;++c)totalFlux[c]+=double(flux[c])*stepArea*nx*ny;
        return true;
    }

public:
    nvrhi::TextureHandle bins;
    RtrOpticalTriangleEmitters triangles;
    bool Prepared()const{return bool(deposit)&&bool(resolve);}
    static bool ValidBounds(const dm::box3& bounds){
        return !bounds.isempty()&&dm::all(dm::isfinite(bounds.m_mins))&&dm::all(dm::isfinite(bounds.m_maxs));
    }

    void Prepare(nvrhi::IDevice* device,donut::engine::ShaderFactory& factory,
            nvrhi::IBindingLayout* sourceLayout,nvrhi::IBindingLayout* bindless){
        if(Prepared())return;
        auto desc=*sourceLayout->getDesc();
        desc.bindings.push_back(nvrhi::BindingLayoutItem::ConstantBuffer(ConstantSlot));
        desc.bindings.push_back(nvrhi::BindingLayoutItem::Texture_UAV(BinSlot));
        desc.bindings.push_back(nvrhi::BindingLayoutItem::StructuredBuffer_SRV(EmitterSlot));
        auto nextLayout=device->createBindingLayout(desc);
        if(!nextLayout)throw std::runtime_error("Could not allocate directional flux binding layout");
        auto pipeline=[&](const char* path){
            nvrhi::ComputePipelineDesc pso;pso.bindingLayouts={nextLayout,bindless};
            pso.CS=factory.CreateShader(path,"main",nullptr,nvrhi::ShaderType::Compute);
            if(!pso.CS)throw std::runtime_error(std::string("Missing precompiled directional flux shader: ")+path);
            auto result=device->createComputePipeline(pso);
            if(!result)throw std::runtime_error(std::string("Could not prepare directional flux pipeline: ")+path);
            return result;
        };
        auto nextDeposit=pipeline("app/ProcessingPasses/RtrNativeFluxDeposit.hlsl");
        auto nextResolve=pipeline("app/ProcessingPasses/RtrNativeFluxResolve.hlsl");
        nvrhi::BufferDesc cb;cb.byteSize=sizeof(Parameters);cb.isConstantBuffer=true;
        cb.initialState=nvrhi::ResourceStates::ConstantBuffer;cb.keepInitialState=true;cb.debugName="Directional flux parameters";
        auto nextConstants=device->createBuffer(cb);
        if(!nextConstants)throw std::runtime_error("Could not allocate directional flux constants");
        emptyEmitterBuffer=MakeEmitterBuffer(device,1);
        layout=std::move(nextLayout);deposit=std::move(nextDeposit);resolve=std::move(nextResolve);
        constants=std::move(nextConstants);
    }

    void RetireBindingFor(const nvrhi::IBuffer* buffer,const nvrhi::ITexture* texture,std::vector<nvrhi::BindingSetHandle>& retired){
        for(auto& cached:bindings)if(cached.value)for(const auto& item:cached.value->getDesc()->bindings)
            if((buffer&&item.resourceHandle==buffer)||(texture&&item.resourceHandle==texture)){
                retired.push_back(std::move(cached.value));break;
            }
    }

    void Dispatch(nvrhi::IDevice* device,nvrhi::ICommandList* command,nvrhi::IBindingSet* sceneTable,
            const nvrhi::BindingSetDesc& frameBindings,const float* words,const dm::box3& bounds,uint32_t width,uint32_t height,
            nvrhi::ITexture* finiteOutput=nullptr){
        const bool finite=finiteOutput!=nullptr;
        if(!Prepared()||(finite?triangles.Empty():!ValidBounds(bounds)))return;
        Parameters params{};
        std::array<double,3> totalFlux{};
        uint32_t count=0,nx=0,ny=0;
        auto add=[&](const float* light,const float* color,unsigned seed){
            auto& job=params.jobs[count];
            if(!MakeJob(light,color,bounds,words[58],seed,job,totalFlux))return;
            nx=std::max(nx,job.extentSeed[0]);ny=std::max(ny,job.extentSeed[1]);++count;
        };
        if(finite){
            nx=ny=RtrOpticalTriangleEmitters::SampleAxis;count=1;
            params.finite={uint32_t(triangles.Rows().size()),nx,0,0};
            totalFlux=triangles.FluxBound();
        }else{
            add(words+44,words+48,0x45d9f3bu);add(words+52,words+64,0x9e3779b9u);
        }
        if(!count)return;
        dm::float4x4 inverseClip,cameraToWorld;
        std::memcpy(&inverseClip,words,64);std::memcpy(&cameraToWorld,words+16,64);
        params.worldToClip=dm::inverse(cameraToWorld*inverseClip);
        for(unsigned c=0;c<3;++c){
            // Budget an extra factor of two for floating point roundoff. This
            // bound assumes every sample for every source lands in one bin.
            int exponent=totalFlux[c]>0?std::ilogb(totalFlux[c])+1:0;
            const int shift=std::clamp(30-exponent,-126,126);
            if(totalFlux[c]*std::ldexp(1.,shift)>1073741824.)return;
            params.scale[c]=std::ldexp(1.f,shift);params.inverseScale[c]=std::ldexp(1.f,-shift);
        }
        if(!bins){
            auto desc=nvrhi::TextureDesc().setDimension(nvrhi::TextureDimension::Texture2DArray).setArraySize(3)
                .setWidth(width).setHeight(height).setFormat(nvrhi::Format::R32_UINT).setIsUAV(true)
                .setInitialState(nvrhi::ResourceStates::UnorderedAccess).setKeepInitialState(true);
            desc.debugName="Optical flux RGB bins";bins=device->createTexture(desc);
            if(!bins)throw std::runtime_error("Could not allocate optical flux bins");
        }
        auto desc=frameBindings;
        if(finite){
            const auto& rows=triangles.Rows();
            const size_t bytes=rows.size()*sizeof(rows[0]);
            const bool grow=!emitterBuffer||emitterBuffer->getDesc().byteSize<bytes;
            if(grow)emitterBuffer=MakeEmitterBuffer(device,rows.size());
            if(grow||triangles.NeedsUpload()){
                command->writeBuffer(emitterBuffer,rows.data(),bytes);
                triangles.Uploaded();
            }
            for(auto& item:desc.bindings)if(item.slot==IndirectSlot&&item.type==nvrhi::ResourceType::Texture_UAV){
                item=nvrhi::BindingSetItem::Texture_UAV(IndirectSlot,finiteOutput);break;
            }
        }
        desc.bindings.push_back(nvrhi::BindingSetItem::ConstantBuffer(ConstantSlot,constants));
        desc.bindings.push_back(nvrhi::BindingSetItem::Texture_UAV(BinSlot,bins));
        desc.bindings.push_back(nvrhi::BindingSetItem::StructuredBuffer_SRV(EmitterSlot,finite?emitterBuffer:emptyEmitterBuffer));
        auto set=bindings[finite].Get(device,desc,layout);
        const auto scope=RtrGpuProfile::Begin(command,finite?"FiniteOpticalFlux":"DirectionalOpticalFlux",-1);
        command->writeBuffer(constants,&params,sizeof(params));
        command->clearTextureUInt(bins,nvrhi::AllSubresources,0);
        nvrhi::ComputeState state;state.pipeline=deposit;state.bindings={set,sceneTable};
        command->setTextureState(bins,nvrhi::AllSubresources,nvrhi::ResourceStates::UnorderedAccess);
        command->setComputeState(state);command->dispatch((nx+7)/8,(ny+7)/8,count);
        // The two passes share a binding set, but the resolve reads other
        // workgroups' atomic writes. Order the bins even when bindings are cached.
        command->setTextureState(bins,nvrhi::AllSubresources,nvrhi::ResourceStates::UnorderedAccess);
        if(finite)command->setTextureState(finiteOutput,nvrhi::AllSubresources,nvrhi::ResourceStates::UnorderedAccess);
        state.pipeline=resolve;command->setComputeState(state);command->dispatch((width+7)/8,(height+7)/8);
        RtrGpuProfile::End(command,scope);
    }
};
