#pragma once
#include <cstring>

// Native GPU-only port of the legacy route's bounded pre-SR history.
class RtrLegacySrHistory {
    nvrhi::TextureHandle colors[2], geometry[2];
    nvrhi::BindingSetHandle bindings[2];
    nvrhi::BindingLayoutHandle layout;
    nvrhi::ComputePipelineHandle pipeline;
    uint32_t width=0,height=0,ping=0;
    bool valid=false;
public:
    void Apply(nvrhi::IDevice* device, donut::engine::ShaderFactory& factory,
        nvrhi::ICommandList* command, RenderTargets& targets, nvrhi::BindingSetHandle globalBindings,
        nvrhi::BindingLayoutHandle globalLayout, bool reset, const float2 jitterDelta) {
        if(!pipeline) {
            nvrhi::BindingLayoutDesc desc;
            desc.visibility=nvrhi::ShaderType::Compute;
            desc.registerSpace=100;
            desc.bindings={nvrhi::BindingLayoutItem::Texture_SRV(0),nvrhi::BindingLayoutItem::Texture_SRV(1),
                nvrhi::BindingLayoutItem::Texture_UAV(0),nvrhi::BindingLayoutItem::Texture_UAV(1)};
            layout=device->createBindingLayout(desc);
            auto shader=factory.CreateShader("app/ProcessingPasses/RtrLegacySrHistory.hlsl","main",nullptr,nvrhi::ShaderType::Compute);
            if(!shader)throw std::runtime_error("Legacy SR history shader unavailable");
            nvrhi::ComputePipelineDesc pso;
            pso.bindingLayouts={globalLayout,layout};pso.CS=shader;
            pipeline=device->createComputePipeline(pso);
            if(!pipeline)throw std::runtime_error("Legacy SR history pipeline unavailable");
        }
        const auto& output=targets.OutputColor->getDesc();
        if(width!=output.width || height!=output.height) {
            width=output.width;height=output.height;valid=false;ping=0;
            for(uint32_t i=0;i<2;++i) {
                bindings[i]=nullptr;
                auto desc=nvrhi::TextureDesc().setWidth(width).setHeight(height).setFormat(nvrhi::Format::RGBA16_FLOAT)
                    .setIsUAV(true).setInitialState(nvrhi::ResourceStates::ShaderResource).setKeepInitialState(true);
                desc.debugName="LegacySrHistoryColor";colors[i]=device->createTexture(desc);
                desc.debugName="LegacySrHistoryGeometry";geometry[i]=device->createTexture(desc);
            }
            for(uint32_t i=0;i<2;++i) {
                nvrhi::BindingSetDesc desc;
                desc.bindings={nvrhi::BindingSetItem::Texture_SRV(0,colors[1-i]),
                    nvrhi::BindingSetItem::Texture_SRV(1,geometry[1-i]),
                    nvrhi::BindingSetItem::Texture_UAV(0,colors[i]),
                    nvrhi::BindingSetItem::Texture_UAV(1,geometry[i])};
                bindings[i]=device->createBindingSet(desc,layout);
            }
            donut::log::info("RTXPT legacy pre-SR history ACTIVE %ux%u maxFrames=8 GPU-only",width,height);
        }
        SampleMiniConstants constants={};
        constants.params.x=uint32_t(valid&&!reset);
        static_assert(sizeof(jitterDelta)==sizeof(uint32_t)*2);
        std::memcpy(&constants.params1.x,&jitterDelta,sizeof(jitterDelta));
        nvrhi::ComputeState state;state.pipeline=pipeline;state.bindings={globalBindings,bindings[ping]};
        command->setComputeState(state);
        command->setPushConstants(&constants,sizeof(constants));
        command->dispatch((width+7)/8,(height+7)/8);
        valid=true;ping=1-ping;
    }
};
