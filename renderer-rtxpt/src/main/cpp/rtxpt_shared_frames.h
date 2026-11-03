#include "rtxpt_diagnostics.h"
#include "rtxpt_host_resources.h"
#pragma once
#include <array>
#include <atomic>
#include <cstring>
#include <stdexcept>
#include <string>
#include <nvrhi/d3d12.h>
#include <wrl/client.h>

// NVRHI owns shared texture allocation. D3D12/Vulkan exchange ownership through
// per-slot timeline fences (Khronos VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_D3D12_FENCE_BIT).
// No CPU pixels or per-frame host fence waits occur in this transport.
class SharedFrameProducer {
    struct Slot {
        nvrhi::TextureHandle texture;
        Microsoft::WRL::ComPtr<ID3D12Fence> fence;
        HANDLE fenceHandle=nullptr;
        uint64_t identity=0,allocationSize=0,completion=0;
        bool premultipliedAlpha=false;
        ~Slot(){if(fenceHandle)CloseHandle(fenceHandle);}
    };
    std::array<Slot,3> slots;
    nvrhi::CommandListHandle commands;
    uint32_t width=0,height=0,next=0;
    int latest=-1;
    uint64_t adapter=0;
    inline static std::atomic<uint64_t> identities{1};
    static void check(HRESULT hr,const char* operation) {
        if(FAILED(hr))throw std::runtime_error(std::string(operation)+" failed: "+std::to_string(uint32_t(hr)));
    }
public:
    // Lifecycle only: the caller MUST first drain/retire every Vulkan consumer.
    // Queue signals preserve the producer copy order, including descriptors rejected before import.
    void retire(nvrhi::IDevice* device) {
        RtrDiagnostics::Scope diagnostic(RtrDiagnostics::Retirement);
        auto* queue=static_cast<ID3D12CommandQueue*>(device->getNativeQueue(
            nvrhi::ObjectTypes::D3D12_CommandQueue,nvrhi::CommandQueue::Graphics).pointer);
        for(auto& slot:slots)if(slot.completion && slot.fence->GetCompletedValue()<slot.completion) {
            check(queue->Signal(slot.fence.Get(),slot.completion),"Retire abandoned shared frame");
            // NVRHI's last submission fence can precede this native queue signal.
            // Wait for the actual shared fence before clearing its generation.
            HANDLE event=CreateEventW(nullptr,FALSE,FALSE,nullptr);
            if(!event)throw std::runtime_error("Cannot create shared-frame retirement event");
            const HRESULT result=slot.fence->SetEventOnCompletion(slot.completion,event);
            if(FAILED(result)){CloseHandle(event);check(result,"Arm shared-frame retirement");}
            DWORD waited=WAIT_TIMEOUT;
            for(unsigned polls=0;polls<625&&waited==WAIT_TIMEOUT;++polls){
                waited=WaitForSingleObject(event,8);RtrHostResources::PumpEvents();
            }
            CloseHandle(event);
            if(waited!=WAIT_OBJECT_0)throw std::runtime_error("Shared-frame retirement did not complete within 5 seconds");
        }
        device->waitForIdle();
        clear();
    }
    void requireRetired() const {
        for(const auto& slot:slots)if(slot.completion && slot.fence->GetCompletedValue()<slot.completion)
            throw std::runtime_error("Retire the Vulkan consumer before resizing or closing shared RTXPT frames");
    }
    void clear() {
        requireRetired();
        for(auto& slot:slots) {
            slot.texture=nullptr;slot.fence.Reset();
            if(slot.fenceHandle)CloseHandle(slot.fenceHandle);
            slot.fenceHandle=nullptr;slot.completion=slot.identity=slot.allocationSize=0;
        }
        commands=nullptr;width=height=next=0;latest=-1;
    }
    void publish(nvrhi::IDevice* device,nvrhi::ITexture* source,bool premultipliedAlpha=false) {
        RtrDiagnostics::Scope diagnostic(RtrDiagnostics::SharedPublish);
        if(!source)throw std::runtime_error("RTXPT has no completed display-color target");
        const auto& sourceDesc=source->getDesc();
        if(sourceDesc.format!=nvrhi::Format::SRGBA8_UNORM && sourceDesc.format!=nvrhi::Format::RGBA8_UNORM)
            throw std::runtime_error("Shared RTXPT output requires its native 8-bit display color");
        ID3D12Device* nativeDevice=device->getNativeObject(nvrhi::ObjectTypes::D3D12_Device);
        ID3D12CommandQueue* queue=device->getNativeQueue(nvrhi::ObjectTypes::D3D12_CommandQueue,nvrhi::CommandQueue::Graphics);
        if(!nativeDevice||!queue)throw std::runtime_error("RTXPT sharing requires the D3D12 graphics queue");
        if(width!=sourceDesc.width||height!=sourceDesc.height) {
            clear();width=sourceDesc.width;height=sourceDesc.height;
            auto luid=nativeDevice->GetAdapterLuid();static_assert(sizeof(luid)==sizeof(adapter));
            std::memcpy(&adapter,&luid,sizeof(adapter));
            nvrhi::TextureDesc desc;
            desc.width=width;desc.height=height;desc.format=nvrhi::Format::RGBA8_UNORM;
            desc.initialState=nvrhi::ResourceStates::Common;desc.keepInitialState=true;
            // Keep NVRHI's color-texture default: DENY_SHADER_RESOURCE is legal only for
            // depth/stencil resources in D3D12. No SRV is created for this copy-only texture.
            desc.isShaderResource=true;desc.sharedResourceFlags=nvrhi::SharedResourceFlags::Shared;
            desc.debugName="RTR D3D12-Vulkan display frame";
            for(auto& slot:slots) {
                slot.texture=device->createTexture(desc);
                if(!slot.texture)throw std::runtime_error("Failed to allocate a shared display texture");
                ID3D12Resource* resource=slot.texture->getNativeObject(nvrhi::ObjectTypes::D3D12_Resource);
                const auto resourceDesc=resource->GetDesc();
                slot.allocationSize=nativeDevice->GetResourceAllocationInfo(0,1,&resourceDesc).SizeInBytes;
                check(nativeDevice->CreateFence(0,D3D12_FENCE_FLAG_SHARED,IID_PPV_ARGS(&slot.fence)),"Create shared display fence");
                check(nativeDevice->CreateSharedHandle(slot.fence.Get(),nullptr,GENERIC_ALL,nullptr,&slot.fenceHandle),"Export display fence");
                slot.identity=identities.fetch_add(1);
            }
            commands=device->createCommandList();
        }
        auto& slot=slots[next];slot.premultipliedAlpha=premultipliedAlpha;
        if(slot.completion){RtrDiagnostics::Scope diagnostic(RtrDiagnostics::SharedReuseWait);diagnostic.event[11]=1;check(queue->Wait(slot.fence.Get(),slot.completion),"Wait for Vulkan frame consumption");}
        commands->open();
        commands->copyTexture(slot.texture,nvrhi::TextureSlice(),source,nvrhi::TextureSlice());
        commands->setTextureState(slot.texture,nvrhi::AllSubresources,nvrhi::ResourceStates::Common);
        commands->commitBarriers();commands->close();device->executeCommandList(commands);
        slot.completion+=2;
        // 0 -> ready 1 -> consumed 2 -> ready 3, independently for each ring slot.
        const auto ready=slot.completion-1;
        check(queue->Signal(slot.fence.Get(),ready),"Signal shared display frame");
        latest=int(next);next=(next+1)%uint32_t(slots.size());
    }
    std::array<int64_t,11> descriptor(int64_t sequence) const {
        if(latest<0)throw std::runtime_error("Render an external frame before requesting its descriptor");
        const auto& slot=slots[latest];
        HANDLE memory=slot.texture->getNativeObject(nvrhi::ObjectTypes::SharedHandle);
        if(!memory)throw std::runtime_error("NVRHI did not export the shared texture handle");
        return {int64_t(slot.identity),sequence,int64_t(adapter),width,height,int64_t(slot.allocationSize),
            int64_t(reinterpret_cast<uintptr_t>(memory)),int64_t(reinterpret_cast<uintptr_t>(slot.fenceHandle)),
            int64_t(slot.completion-1),int64_t(slot.completion),int64_t(slot.premultipliedAlpha)};
    }
    void copyBack(nvrhi::IDevice* device,nvrhi::ITexture* backBuffer) {
        if(latest<0)throw std::runtime_error("No composed display frame");
        auto& slot=slots[latest];
        ID3D12CommandQueue* queue=device->getNativeQueue(nvrhi::ObjectTypes::D3D12_CommandQueue,nvrhi::CommandQueue::Graphics);
        check(queue->Wait(slot.fence.Get(),slot.completion),"Wait for Vulkan UI composition");
        commands->open();
        commands->copyTexture(backBuffer,nvrhi::TextureSlice(),slot.texture,nvrhi::TextureSlice());
        commands->setTextureState(backBuffer,nvrhi::AllSubresources,nvrhi::ResourceStates::Present);
        commands->commitBarriers();commands->close();device->executeCommandList(commands);
    }
    nvrhi::ITexture* latestTexture() const {return latest<0?nullptr:slots[latest].texture.Get();}
};
