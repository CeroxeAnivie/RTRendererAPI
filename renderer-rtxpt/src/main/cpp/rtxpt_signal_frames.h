#pragma once
#include <nvrhi/d3d12.h>
#include <wrl/client.h>

// HDR/AOV packets have a separate descriptor and ownership protocol from the
// legacy SDR SharedFrameProducer. All planes in a packet share one fence pair.
template<size_t PlaneCount=4>
class RtrSignalFrameRing {
    struct Slot {
        std::array<nvrhi::TextureHandle,PlaneCount> textures;
        std::array<uint64_t,PlaneCount> allocation{};
        Microsoft::WRL::ComPtr<ID3D12Fence> fence;
        HANDLE fenceHandle=nullptr;
        uint64_t generation=0,completion=0,sequence=0,revision=0;
        uint64_t attributes=0;int64_t time=0,delta=0;
        uint32_t encoding=0,maximumGiBits=0x3f800000;
        std::array<jlong,4> provenance{};
        uint32_t width=0,height=0,mask=0;
        bool published=false,leased=false,returned=false,consumerImported=false;
        ~Slot(){if(fenceHandle)CloseHandle(fenceHandle);}
    };
    std::array<Slot,3> slots;
    nvrhi::CommandListHandle copy;
    uint64_t adapter=0;
    inline static std::atomic<uint64_t> identities{1};
    int latest=-1;
    static void Check(HRESULT hr,const char* operation){if(FAILED(hr))throw std::runtime_error(std::string(operation)+" failed: "+std::to_string(uint32_t(hr)));}
    bool Available(Slot& slot){
        if(!slot.published)return true;
        if(!slot.leased&&slot.fence->GetCompletedValue()>=slot.completion-1)return true;
        if(slot.returned&&slot.fence->GetCompletedValue()>=slot.completion){slot.published=slot.leased=slot.returned=false;return true;}
        return false;
    }
public:
    bool CanPublish(){for(auto& slot:slots)if(Available(slot))return true;return false;}
    bool Outstanding() const {for(auto& slot:slots)if(slot.published)return true;return false;}
    bool ConsumersRetired(){for(auto& slot:slots)if(slot.leased&&(!slot.returned||(slot.consumerImported&&slot.fence->GetCompletedValue()<slot.completion)))return false;return true;}
    void Publish(nvrhi::IDevice* device,const std::array<nvrhi::TextureHandle,PlaneCount>& sources,uint64_t sequence,uint64_t revision,uint64_t attributes,int64_t time,int64_t delta,uint32_t encoding=0,float maximumGi=1,const std::array<jlong,4>& provenance={}){
        int index=-1;for(int i=0;i<3;i++)if(Available(slots[i])){index=i;break;}
        if(index<0)throw std::runtime_error("All native signal slots are awaiting consumer completion");
        auto& slot=slots[index];uint32_t mask=0,width=0,height=0;
        for(unsigned i=0;i<PlaneCount;i++)if(sources[i]){mask|=1u<<i;width=sources[i]->getDesc().width;height=sources[i]->getDesc().height;}
        if(mask==0)throw std::logic_error("Cannot publish an empty signal packet");
        auto* native=static_cast<ID3D12Device*>(device->getNativeObject(nvrhi::ObjectTypes::D3D12_Device).pointer);
        auto* queue=static_cast<ID3D12CommandQueue*>(device->getNativeQueue(nvrhi::ObjectTypes::D3D12_CommandQueue,nvrhi::CommandQueue::Graphics).pointer);
        bool formatsChanged=false;
        for(unsigned i=0;i<PlaneCount;i++)if(sources[i]){
            if(sources[i]->getDesc().width!=width||sources[i]->getDesc().height!=height)throw std::invalid_argument("All output planes must have one atomic packet extent");
            if(slot.textures[i]&&slot.textures[i]->getDesc().format!=sources[i]->getDesc().format)formatsChanged=true;
        }
        if(slot.width!=width||slot.height!=height||slot.mask!=mask||formatsChanged){
            std::array<nvrhi::TextureHandle,PlaneCount> prepared{};std::array<uint64_t,PlaneCount> sizes{};
            for(unsigned i=0;i<PlaneCount;i++)if(sources[i]){
                auto desc=sources[i]->getDesc();desc.isUAV=false;desc.isShaderResource=true;
                desc.sharedResourceFlags=nvrhi::SharedResourceFlags::Shared;desc.initialState=nvrhi::ResourceStates::Common;desc.keepInitialState=true;
                desc.debugName="Shared HDR lighting signal";prepared[i]=device->createTexture(desc);
                if(!prepared[i])throw std::runtime_error("Could not allocate shared HDR signal");
                auto* resource=static_cast<ID3D12Resource*>(prepared[i]->getNativeObject(nvrhi::ObjectTypes::D3D12_Resource).pointer);
                auto allocation=resource->GetDesc();sizes[i]=native->GetResourceAllocationInfo(0,1,&allocation).SizeInBytes;
            }
            // This slot has no in-flight consumer; the previous producer copy is
            // ordered before its completion fence. Other slots remain untouched.
            slot.textures=std::move(prepared);slot.allocation=sizes;slot.width=width;slot.height=height;slot.mask=mask;
            slot.generation=identities.fetch_add(1);auto luid=native->GetAdapterLuid();std::memcpy(&adapter,&luid,8);
        }
        if(!slot.fence){
            Check(native->CreateFence(0,D3D12_FENCE_FLAG_SHARED,IID_PPV_ARGS(&slot.fence)),"Create signal packet fence");
            Check(native->CreateSharedHandle(slot.fence.Get(),nullptr,GENERIC_ALL,nullptr,&slot.fenceHandle),"Export signal packet fence");
        }
        if(!copy)copy=device->createCommandList();copy->open();
        for(unsigned i=0;i<PlaneCount;i++)if(sources[i]){
            copy->copyTexture(slot.textures[i],nvrhi::TextureSlice(),sources[i],nvrhi::TextureSlice());
            copy->setTextureState(slot.textures[i],nvrhi::AllSubresources,nvrhi::ResourceStates::Common);
        }
        copy->commitBarriers();copy->close();device->executeCommandList(copy);
        slot.sequence=sequence;slot.revision=revision;slot.completion+=2;
        slot.attributes=attributes;slot.time=time;slot.delta=delta;
        slot.encoding=encoding;std::memcpy(&slot.maximumGiBits,&maximumGi,sizeof(maximumGi));
        slot.provenance=provenance;
        Check(queue->Signal(slot.fence.Get(),slot.completion-1),"Publish signal packet readiness");
        slot.published=true;slot.returned=slot.leased=slot.consumerImported=false;latest=index;
    }
    // [abi,slot,generation,sequence,revision,LUID,width,height,mask,fence,ready,done,
    //  attributes,time,delta,count,{signal,format(1=RGBA16F/2=RGBA32UI),allocation,resource}...]
    std::vector<jlong> Acquire(){
        if(latest<0||!slots[latest].published||slots[latest].leased)return {};
        auto& slot=slots[latest];
        std::vector<jlong> result={1,latest,jlong(slot.generation),jlong(slot.sequence),jlong(slot.revision),jlong(adapter),slot.width,slot.height,slot.mask,
            jlong(reinterpret_cast<uintptr_t>(slot.fenceHandle)),jlong(slot.completion-1),jlong(slot.completion),jlong(slot.attributes),slot.time,slot.delta,0};
        for(unsigned i=0;i<PlaneCount;i++)if(slot.textures[i]){
            auto handle=slot.textures[i]->getNativeObject(nvrhi::ObjectTypes::SharedHandle).pointer;
            if(!handle)throw std::runtime_error("Shared signal resource has no Win32 handle");
            const auto format=slot.textures[i]->getDesc().format;
            int encoding=format==nvrhi::Format::RGBA16_FLOAT?1:format==nvrhi::Format::RGBA32_UINT?2:format==nvrhi::Format::RGBA32_FLOAT?3:format==nvrhi::Format::RGBA8_UNORM?4:0;
            if(!encoding)throw std::runtime_error("Unsupported shared HDR plane format");
            result.insert(result.end(),{jlong(i),encoding,jlong(slot.allocation[i]),jlong(reinterpret_cast<uintptr_t>(handle))});++result[15];
        }
        if constexpr(PlaneCount==4){result[0]=2;result.push_back(slot.encoding);result.push_back(slot.maximumGiBits);}
        if constexpr(PlaneCount==8)result.insert(result.end(),slot.provenance.begin(),slot.provenance.end());
        slot.leased=true;return result;
    }
    void Release(nvrhi::IDevice* device,int index,uint64_t generation,uint64_t sequence,bool imported){
        if(index<0||index>=3)throw std::invalid_argument("Invalid signal slot");auto& slot=slots[index];
        if(!slot.published||!slot.leased||slot.returned||slot.generation!=generation||slot.sequence!=sequence)
            throw std::invalid_argument("Signal lease identity does not match the active packet");
        if(!imported){auto* queue=static_cast<ID3D12CommandQueue*>(device->getNativeQueue(nvrhi::ObjectTypes::D3D12_CommandQueue,nvrhi::CommandQueue::Graphics).pointer);
            Check(queue->Signal(slot.fence.Get(),slot.completion),"Abandon unimported signal lease");}
        slot.returned=true;slot.consumerImported=imported;
    }
    void RequireRetired(){for(auto& slot:slots)if(!Available(slot))throw std::runtime_error("Return native signal leases and complete consumer GPU work before closing");}
    void Clear(){RequireRetired();for(auto& slot:slots){slot.textures={};slot.fence.Reset();if(slot.fenceHandle)CloseHandle(slot.fenceHandle);slot.fenceHandle=nullptr;}copy=nullptr;latest=-1;}
};

using RtrSignalFrames=RtrSignalFrameRing<4>;
using RtrSurfaceSignalFrames=RtrSignalFrameRing<8>;
