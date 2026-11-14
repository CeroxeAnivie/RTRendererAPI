#pragma once
#include <nvrhi/d3d12.h>
#include <wrl/client.h>
#include <mutex>
#include "rtxpt_fence.h"
#include "rtxpt_gpu_profile.h"

// HDR/AOV packets have a separate descriptor and ownership protocol from the
// legacy SDR SharedFrameProducer. All planes in a packet share one fence pair.
template<size_t PlaneCount=4>
class RtrSignalFrameRing {
    struct Slot {
        std::array<nvrhi::TextureHandle,PlaneCount> textures;
        std::array<uint64_t,PlaneCount> allocation{};
        std::array<jlong,PlaneCount> handles{};
        std::array<int,PlaneCount> formats{};
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
    mutable std::mutex mutex;
    nvrhi::CommandListHandle copy;
    uint64_t adapter=0;
    inline static std::atomic<uint64_t> identities{1};
    int latest=-1,recording=-1;
    static void Check(HRESULT hr,const char* operation){if(FAILED(hr)){RtrLatchDeviceFailure(hr);RtrProtocolTrace::Record(4,0,0,0,0,0,0,0,0,0,0,uint32_t(hr));throw std::runtime_error(std::string(operation)+" failed: "+std::to_string(uint32_t(hr)));}}
    bool Available(Slot& slot){
        if(!slot.published)return true;
        const auto completed=RtrCompletedFence(slot.fence.Get());
        RtrProtocolTrace::Record(1,adapter,0,uint64_t(&slot-slots.data()),slot.generation,slot.sequence,slot.revision,slot.completion-1,slot.completion,completed,
            1|(slot.leased?2:0)|(slot.returned?4:0)|(slot.consumerImported?8:0));
        if(!slot.leased&&completed>=slot.completion-1)return true;
        // An unimported returned lease has no external GPU reader. Release
        // already enqueued its completion on this producer graphics queue, so
        // the next copy is ordered after it without waiting for a CPU poll.
        if(slot.returned&&(!slot.consumerImported||completed>=slot.completion)){
            slot.published=slot.leased=slot.returned=false;return true;
        }
        return false;
    }
    void RequireRetiredLocked(){for(auto& slot:slots)if(!Available(slot))throw std::runtime_error("Return native signal leases and complete consumer GPU work before closing");}
public:
    bool CanPublish(){std::lock_guard lock(mutex);RtrRequireHealthy();for(size_t i=0;i<slots.size();++i)if(int(i)!=recording&&Available(slots[i]))return true;return false;}
    bool Outstanding() const {std::lock_guard lock(mutex);for(auto& slot:slots)if(slot.published)return true;return false;}
    bool ConsumersRetired(){std::lock_guard lock(mutex);for(auto& slot:slots)if(slot.leased&&(!slot.returned||(slot.consumerImported&&RtrCompletedFence(slot.fence.Get())<slot.completion)))return false;return true;}
    void Record(nvrhi::IDevice* device,nvrhi::ICommandList* commands,
        const std::array<nvrhi::TextureHandle,PlaneCount>& sources,uint64_t sequence,uint64_t revision,uint64_t attributes,int64_t time,int64_t delta,uint32_t encoding=0,float maximumGi=1,const std::array<jlong,4>& provenance={}){
        std::lock_guard lock(mutex);
        RtrRequireHealthy();
        if(recording>=0)throw std::logic_error("A shared signal recording is already pending");
        int index=-1;for(int i=0;i<3;i++)if(Available(slots[i])){index=i;break;}
        if(index<0)throw std::runtime_error("All native signal slots are awaiting consumer completion");
        auto& slot=slots[index];uint32_t mask=0,width=0,height=0;
        for(unsigned i=0;i<PlaneCount;i++)if(sources[i]){mask|=1u<<i;width=sources[i]->getDesc().width;height=sources[i]->getDesc().height;}
        if(mask==0)throw std::logic_error("Cannot publish an empty signal packet");
        auto* native=static_cast<ID3D12Device*>(device->getNativeObject(nvrhi::ObjectTypes::D3D12_Device).pointer);
        bool formatsChanged=false;
        for(unsigned i=0;i<PlaneCount;i++)if(sources[i]){
            if(sources[i]->getDesc().width!=width||sources[i]->getDesc().height!=height)throw std::invalid_argument("All output planes must have one atomic packet extent");
            if(slot.textures[i]&&slot.textures[i]->getDesc().format!=sources[i]->getDesc().format)formatsChanged=true;
        }
        if(slot.width!=width||slot.height!=height||slot.mask!=mask||formatsChanged){
            std::array<nvrhi::TextureHandle,PlaneCount> prepared{};std::array<uint64_t,PlaneCount> sizes{};
            std::array<jlong,PlaneCount> handles{};std::array<int,PlaneCount> formats{};
            for(unsigned i=0;i<PlaneCount;i++)if(sources[i]){
                auto desc=sources[i]->getDesc();desc.isUAV=false;desc.isShaderResource=true;
                desc.sharedResourceFlags=nvrhi::SharedResourceFlags::Shared;desc.initialState=nvrhi::ResourceStates::Common;desc.keepInitialState=true;
                desc.debugName="Shared HDR lighting signal";prepared[i]=device->createTexture(desc);
                if(!prepared[i])throw std::runtime_error("Could not allocate shared HDR signal");
                auto* resource=static_cast<ID3D12Resource*>(prepared[i]->getNativeObject(nvrhi::ObjectTypes::D3D12_Resource).pointer);
                auto allocation=resource->GetDesc();sizes[i]=native->GetResourceAllocationInfo(0,1,&allocation).SizeInBytes;
                // Export immutable descriptors on the native owner. Acquiring a
                // packet later only copies bridge state, never queries NVRHI.
                auto handle=prepared[i]->getNativeObject(nvrhi::ObjectTypes::SharedHandle).pointer;
                if(!handle)throw std::runtime_error("Shared signal resource has no Win32 handle");
                handles[i]=jlong(reinterpret_cast<uintptr_t>(handle));
                const auto format=desc.format;
                formats[i]=format==nvrhi::Format::RGBA16_FLOAT?1:format==nvrhi::Format::RGBA32_UINT?2:format==nvrhi::Format::RGBA32_FLOAT?3:format==nvrhi::Format::RGBA8_UNORM?4:0;
                if(!formats[i])throw std::runtime_error("Unsupported shared HDR plane format");
            }
            // This slot has no in-flight consumer; the previous producer copy is
            // ordered before its completion fence. Other slots remain untouched.
            slot.textures=std::move(prepared);slot.allocation=sizes;slot.width=width;slot.height=height;slot.mask=mask;
            slot.handles=handles;slot.formats=formats;
            slot.generation=identities.fetch_add(1);auto luid=native->GetAdapterLuid();std::memcpy(&adapter,&luid,8);
        }
        if(!slot.fence){
            Check(native->CreateFence(0,D3D12_FENCE_FLAG_SHARED,IID_PPV_ARGS(&slot.fence)),"Create signal packet fence");
            Check(native->CreateSharedHandle(slot.fence.Get(),nullptr,GENERIC_ALL,nullptr,&slot.fenceHandle),"Export signal packet fence");
        }
        const auto copyScope=RtrGpuProfile::Begin(commands,"SharedSignalCopy",-1);
        for(unsigned i=0;i<PlaneCount;i++)if(sources[i]){
            commands->copyTexture(slot.textures[i],nvrhi::TextureSlice(),sources[i],nvrhi::TextureSlice());
            commands->setTextureState(slot.textures[i],nvrhi::AllSubresources,nvrhi::ResourceStates::Common);
        }
        commands->commitBarriers();RtrGpuProfile::End(commands,copyScope);
        slot.sequence=sequence;slot.revision=revision;slot.attributes=attributes;slot.time=time;slot.delta=delta;
        slot.encoding=encoding;std::memcpy(&slot.maximumGiBits,&maximumGi,sizeof(maximumGi));
        slot.provenance=provenance;
        // Until SignalSubmitted, Acquire must not return this generation. The
        // old slot was unleased or its consumer already signalled completion.
        slot.published=slot.returned=slot.leased=slot.consumerImported=false;
        if(latest==index)latest=-1;
        recording=index;
    }
    void SignalSubmitted(nvrhi::IDevice* device){
        std::lock_guard lock(mutex);
        if(recording<0)throw std::logic_error("No shared signal recording to publish");
        auto& slot=slots[recording];
        auto* queue=static_cast<ID3D12CommandQueue*>(device->getNativeQueue(nvrhi::ObjectTypes::D3D12_CommandQueue,nvrhi::CommandQueue::Graphics).pointer);
        const auto completion=slot.completion+2;
        Check(queue->Signal(slot.fence.Get(),completion-1),"Publish signal packet readiness");
        slot.completion=completion;slot.published=true;latest=recording;recording=-1;
        RtrProtocolTrace::Record(2,adapter,0,latest,slot.generation,slot.sequence,slot.revision,completion-1,completion,0,1);
    }
    void Publish(nvrhi::IDevice* device,const std::array<nvrhi::TextureHandle,PlaneCount>& sources,uint64_t sequence,uint64_t revision,
        uint64_t attributes,int64_t time,int64_t delta,uint32_t encoding=0,float maximumGi=1,const std::array<jlong,4>& provenance={}){
        if(!copy)copy=device->createCommandList();
        if(!copy)throw std::runtime_error("Could not allocate shared signal command list");
        copy->open();Record(device,copy,sources,sequence,revision,attributes,time,delta,encoding,maximumGi,provenance);
        copy->close();const auto submitted=device->executeCommandList(copy);
        // The complete renderer can launch its next compute prefix against
        // these source planes. Its graphics submit did not include this copy.
        device->queueWaitForCommandList(nvrhi::CommandQueue::Compute,nvrhi::CommandQueue::Graphics,submitted);
        SignalSubmitted(device);
    }
    // [abi,slot,generation,sequence,revision,LUID,width,height,mask,fence,ready,done,
    //  attributes,time,delta,count,{signal,format(1=RGBA16F/2=RGBA32UI),allocation,resource}...]
    std::vector<jlong> Acquire(){
        std::lock_guard lock(mutex);
        RtrRequireHealthy();
        if(latest<0||!slots[latest].published||slots[latest].leased)return {};
        auto& slot=slots[latest];
        std::vector<jlong> result={1,latest,jlong(slot.generation),jlong(slot.sequence),jlong(slot.revision),jlong(adapter),slot.width,slot.height,slot.mask,
            jlong(reinterpret_cast<uintptr_t>(slot.fenceHandle)),jlong(slot.completion-1),jlong(slot.completion),jlong(slot.attributes),slot.time,slot.delta,0};
        for(unsigned i=0;i<PlaneCount;i++)if(slot.textures[i]){
            result.insert(result.end(),{jlong(i),slot.formats[i],jlong(slot.allocation[i]),slot.handles[i]});++result[15];
        }
        if constexpr(PlaneCount==4){result[0]=2;result.push_back(slot.encoding);result.push_back(slot.maximumGiBits);}
        if constexpr(PlaneCount==8)result.insert(result.end(),slot.provenance.begin(),slot.provenance.end());
        slot.leased=true;RtrProtocolTrace::Record(5,adapter,0,latest,slot.generation,slot.sequence,slot.revision,slot.completion-1,slot.completion,0,3);return result;
    }
    void Release(nvrhi::IDevice* device,int index,uint64_t generation,uint64_t sequence,bool imported){
        std::lock_guard lock(mutex);
        if(index<0||index>=3)throw std::invalid_argument("Invalid signal slot");auto& slot=slots[index];
        if(!slot.published||!slot.leased||slot.returned||slot.generation!=generation||slot.sequence!=sequence)
            throw std::invalid_argument("Signal lease identity does not match the active packet");
        if(!imported){auto* queue=static_cast<ID3D12CommandQueue*>(device->getNativeQueue(nvrhi::ObjectTypes::D3D12_CommandQueue,nvrhi::CommandQueue::Graphics).pointer);
            Check(queue->Signal(slot.fence.Get(),slot.completion),"Abandon unimported signal lease");}
        slot.returned=true;slot.consumerImported=imported;
        RtrProtocolTrace::Record(3,adapter,0,index,slot.generation,sequence,slot.revision,slot.completion-1,slot.completion,0,1|2|4|(imported?8:0));
    }
    void RequireRetired(){std::lock_guard lock(mutex);RequireRetiredLocked();}
    void Clear(bool deviceRemoved=false){std::lock_guard lock(mutex);if(!deviceRemoved)RequireRetiredLocked();for(auto& slot:slots){slot.textures={};slot.published=slot.leased=slot.returned=false;slot.width=slot.height=slot.mask=0;slot.completion=0;slot.fence.Reset();if(slot.fenceHandle)CloseHandle(slot.fenceHandle);slot.fenceHandle=nullptr;}copy=nullptr;latest=recording=-1;}
};

using RtrSignalFrames=RtrSignalFrameRing<4>;
using RtrSurfaceSignalFrames=RtrSignalFrameRing<8>;
