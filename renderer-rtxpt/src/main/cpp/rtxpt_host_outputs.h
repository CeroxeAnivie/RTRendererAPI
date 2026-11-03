#pragma once
class RtrHostOutputs {
public:
    uint32_t mask=1,rawMask=0,width=0,height=0,outputWidth=0,outputHeight=0;
    nvrhi::BufferHandle raw,constants,resolveConstants;
    nvrhi::TextureHandle dummy;
    std::array<nvrhi::TextureHandle,8> outputs{};
    nvrhi::BindingLayoutHandle layout;
    nvrhi::ComputePipelineHandle pipeline;
    void Ensure(nvrhi::IDevice* device,nvrhi::ICommandList* command){
        if(constants&&raw)return;
        nvrhi::BufferDesc cb;cb.byteSize=16;cb.isConstantBuffer=true;cb.initialState=nvrhi::ResourceStates::ConstantBuffer;cb.keepInitialState=true;
        auto nextConstants=device->createBuffer(cb);
        nvrhi::BufferDesc bd;bd.byteSize=16;bd.structStride=16;bd.canHaveUAVs=true;bd.initialState=nvrhi::ResourceStates::UnorderedAccess;bd.keepInitialState=true;
        auto nextRaw=device->createBuffer(bd);
        if(!nextConstants||!nextRaw)throw std::runtime_error("Could not allocate host lighting bindings");
        uint32_t disabled[4]{};command->writeBuffer(nextConstants,disabled,sizeof(disabled));
        constants=nextConstants;raw=nextRaw;
    }
    void Prepare(nvrhi::IDevice* device,donut::engine::ShaderFactory& factory,nvrhi::ICommandList* command,
            uint32_t w,uint32_t h,uint32_t ow,uint32_t oh){
        Ensure(device,command);
        if(!w||!h||!ow||!oh)throw std::invalid_argument("Host lighting extents must be positive");
        if(!pipeline){
            nvrhi::BufferDesc cb;cb.byteSize=32;cb.isConstantBuffer=true;cb.initialState=nvrhi::ResourceStates::ConstantBuffer;cb.keepInitialState=true;
            auto nextConstants=device->createBuffer(cb);
            auto td=nvrhi::TextureDesc().setWidth(1).setHeight(1).setFormat(nvrhi::Format::RGBA16_FLOAT).setIsUAV(true)
                .setInitialState(nvrhi::ResourceStates::UnorderedAccess).setKeepInitialState(true);auto nextDummy=device->createTexture(td);
            if(!nextConstants||!nextDummy)throw std::runtime_error("Could not allocate host lighting resolve bindings");
            nvrhi::BindingLayoutDesc desc;desc.visibility=nvrhi::ShaderType::Compute;desc.bindings.push_back(nvrhi::BindingLayoutItem::ConstantBuffer(0));
            desc.bindings.push_back(nvrhi::BindingLayoutItem::StructuredBuffer_SRV(0));
            for(unsigned i=1;i<9;i++)desc.bindings.push_back(nvrhi::BindingLayoutItem::Texture_SRV(i));
            desc.bindings.push_back(nvrhi::BindingLayoutItem::StructuredBuffer_SRV(9));
            for(unsigned i=0;i<8;i++)desc.bindings.push_back(nvrhi::BindingLayoutItem::Texture_UAV(i));
            auto nextLayout=device->createBindingLayout(desc);
            if(!nextLayout)throw std::runtime_error("Could not prepare host lighting resolve layout");
            auto shader=factory.CreateShader("app/ProcessingPasses/RtrHostResolve.hlsl","main",nullptr,nvrhi::ShaderType::Compute);
            if(!shader)throw std::runtime_error("Host lighting resolve shader is unavailable");
            nvrhi::ComputePipelineDesc pso;pso.bindingLayouts={nextLayout};pso.CS=shader;auto nextPipeline=device->createComputePipeline(pso);
            if(!nextPipeline)throw std::runtime_error("Could not prepare host lighting resolve pipeline");
            resolveConstants=nextConstants;dummy=nextDummy;layout=nextLayout;pipeline=nextPipeline;
        }
        uint32_t nextRaw=mask&124;
        bool changed=width!=w||height!=h||outputWidth!=ow||outputHeight!=oh||rawMask!=nextRaw;
        for(unsigned i=0;i<8;i++)if(bool(outputs[i])!=bool(mask&(1u<<i)))changed=true;
        if(changed){
            std::array<nvrhi::TextureHandle,8> next{};
            for(unsigned i=0;i<8;i++)if(mask&(1u<<i)){
                if(outputWidth==ow&&outputHeight==oh&&outputs[i]){next[i]=outputs[i];continue;}
                auto td=nvrhi::TextureDesc().setWidth(ow).setHeight(oh).setFormat(nvrhi::Format::RGBA16_FLOAT).setIsUAV(true)
                    .setInitialState(nvrhi::ResourceStates::UnorderedAccess).setKeepInitialState(true);next[i]=device->createTexture(td);
                if(!next[i])throw std::runtime_error("Could not allocate requested host lighting signal");
            }
            auto nextBuffer=raw;
            if(rawMask!=nextRaw||(nextRaw&&(width!=w||height!=h))){
                unsigned planes=0;for(uint32_t bits=nextRaw;bits;bits&=bits-1)++planes;
                nvrhi::BufferDesc bd;bd.byteSize=std::max<uint64_t>(16,uint64_t(w)*h*planes*16);bd.structStride=16;bd.canHaveUAVs=true;
                bd.initialState=nvrhi::ResourceStates::UnorderedAccess;bd.keepInitialState=true;
                nextBuffer=device->createBuffer(bd);if(!nextBuffer)throw std::runtime_error("Could not allocate host lighting contribution storage");
            }
            outputs=next;raw=nextBuffer;width=w;height=h;outputWidth=ow;outputHeight=oh;rawMask=nextRaw;
        }
        uint32_t values[]={mask,rawMask,w,h};command->writeBuffer(constants,values,sizeof(values));
        if(rawMask)command->clearBufferUInt(raw,0);
    }
    void Bind(nvrhi::BindingSetDesc& bindings){
        bindings.bindings.push_back(nvrhi::BindingSetItem::ConstantBuffer(3,constants));
        bindings.bindings.push_back(nvrhi::BindingSetItem::StructuredBuffer_UAV(98,raw));
    }
    void Resolve(nvrhi::IDevice* device,nvrhi::ITexture* combined,const RtrHostSurface& host){
        if(!pipeline||!combined||!outputWidth||!outputHeight)throw std::logic_error("Host lighting outputs have not been prepared");
        auto command=device->createCommandList();command->open();
        const auto& input=host.inputs;
        uint32_t values[]={width,height,outputWidth,outputHeight,mask,rawMask,uint32_t(host.instanceData.size()),uint32_t(host.triangleData.size())};command->writeBuffer(resolveConstants,values,sizeof(values));
        nvrhi::BindingSetDesc bindings;bindings.bindings={nvrhi::BindingSetItem::ConstantBuffer(0,resolveConstants),
            nvrhi::BindingSetItem::StructuredBuffer_SRV(0,raw),nvrhi::BindingSetItem::Texture_SRV(1,combined),
            nvrhi::BindingSetItem::Texture_SRV(2,input[0]),nvrhi::BindingSetItem::Texture_SRV(3,input[1]),
            nvrhi::BindingSetItem::Texture_SRV(4,input[7]),nvrhi::BindingSetItem::Texture_SRV(5,input[3]),
            nvrhi::BindingSetItem::Texture_SRV(6,input[2]),nvrhi::BindingSetItem::Texture_SRV(7,input[4]),
            nvrhi::BindingSetItem::Texture_SRV(8,input[5]),nvrhi::BindingSetItem::StructuredBuffer_SRV(9,host.instances)};
        for(unsigned i=0;i<8;i++)bindings.bindings.push_back(nvrhi::BindingSetItem::Texture_UAV(i,outputs[i]?outputs[i]:dummy));
        auto bindingSet=device->createBindingSet(bindings,layout);
        if(!bindingSet)throw std::runtime_error("Could not prepare host lighting resolve resources");
        nvrhi::ComputeState state;state.pipeline=pipeline;state.bindings={bindingSet};
        auto profile=RtrGpuProfile::Begin(command,"HostLightingResolve",-1);command->setComputeState(state);command->dispatch((outputWidth+7)/8,(outputHeight+7)/8);
        RtrGpuProfile::End(command,profile);command->close();device->executeCommandList(command);
    }
};
