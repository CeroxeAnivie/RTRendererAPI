#pragma once
#include <array>
#include <cstdint>
#include <limits>
#include <stdexcept>

// The allocation-independent protocol is also compiled by the standalone test.
namespace RtrSurfaceInputProtocol {
inline constexpr uint32_t BasePlaneCount=8,AugmentationPlaneCount=10;
inline constexpr uint32_t RequiredPlaneCount(uint32_t composition){
    return composition==0?AugmentationPlaneCount:composition==1?BasePlaneCount:throw std::invalid_argument("Unknown host-surface composition");
}
inline constexpr uint32_t BytesPerPixel(uint32_t composition){return RequiredPlaneCount(composition)==BasePlaneCount?80:96;}
enum class State : uint8_t { Idle,Acquired,Published,Reading,InFlight };
struct Identity {
    uint64_t sequence=0,revision=0,resourceGeneration=0,viewIdentity=0;
    int64_t timeNanos=0,deltaNanos=0;
    void Validate()const {
        constexpr auto limit=uint64_t((std::numeric_limits<int64_t>::max)());
        if(sequence>limit||revision>limit||resourceGeneration>limit||deltaNanos<0)
            throw std::invalid_argument("Invalid host-surface frame/revision/resource/clock identity");
    }
    bool operator==(const Identity& other)const {return sequence==other.sequence&&revision==other.revision
        &&resourceGeneration==other.resourceGeneration&&viewIdentity==other.viewIdentity&&timeNanos==other.timeNanos&&deltaNanos==other.deltaNanos;}
};
struct Reservation {
    State state=State::Idle;Identity identity;
    uint64_t ticket=0,lastDone=0,previousDone=0,ready=0,done=0;
    bool imported=false;
    void Acquire(const Identity& value){
        if(state!=State::Idle)throw std::logic_error("Host-surface slot is already owned");value.Validate();
        if(lastDone>uint64_t((std::numeric_limits<int64_t>::max)())-2||ticket==uint64_t((std::numeric_limits<int64_t>::max)()))
            throw std::overflow_error("Host-surface fence or reservation identity exhausted");
        identity=value;++ticket;previousDone=lastDone;ready=lastDone+1;done=lastDone+2;imported=false;state=State::Acquired;
    }
    void Require(uint64_t expected,State expectedState)const {
        if(expected!=ticket||state!=expectedState)throw std::invalid_argument("Stale or invalid host-surface reservation state");
    }
    void Imported(uint64_t expected){Require(expected,State::Acquired);if(imported)throw std::logic_error("Host-surface reservation already imported");imported=true;}
    void Publish(uint64_t expected){Require(expected,State::Acquired);if(!imported)throw std::logic_error("Import and enqueue host readiness before publication");state=State::Published;}
    void CancelUnimported(uint64_t expected){Require(expected,State::Acquired);if(imported)throw std::logic_error("Imported host writes require a ready signal before abandonment");state=State::Idle;}
    void Begin(uint64_t expected,const Identity& value){Require(expected,State::Published);if(!(identity==value))throw std::invalid_argument("Host-surface input does not match the submitted frame facts");state=State::Reading;}
    void Abort(uint64_t expected){Require(expected,State::Reading);state=State::Published;}
    void Finish(uint64_t expected){Require(expected,State::Reading);lastDone=done;state=State::InFlight;}
    void DiscardQueued(uint64_t expected){
        if(expected!=ticket||((state!=State::Acquired||!imported)&&state!=State::Published))throw std::invalid_argument("Host-surface input cannot be discarded in its current state");
        lastDone=done;state=State::InFlight;
    }
    void Complete(uint64_t completed){if(state==State::InFlight&&completed>=done)state=State::Idle;}
};
}

#ifndef RTR_SURFACE_INPUT_PROTOCOL_ONLY
#include <windows.h>
#include <directx/d3d12.h>
#include <nvrhi/d3d12.h>
#include <wrl/client.h>
#include <deque>
#include <memory>
#include <optional>
#include <thread>
#include <vector>
#include <atomic>
#include <cstring>

// Same-device GPU input allocation and reversed ready/done ownership. No CPU
// pixels, shader compilation, fence wait or waitIdle occurs in this class.
class RtrSurfaceInputs {
public:
    static constexpr uint32_t AbiVersion=1,PlaneCount=RtrSurfaceInputProtocol::AugmentationPlaneCount,SlotCount=3;
    static constexpr uint64_t MaximumBudget=2ull*1024*1024*1024;
    enum Format : uint32_t { Rgba16Float=0,Rgba32Float=1,Rg32Float=2,Rgba32Uint=3 };
    inline static constexpr std::array<Format,PlaneCount> Formats={Rgba32Float,Rgba16Float,Rgba16Float,Rgba16Float,Rgba16Float,Rgba16Float,Rg32Float,Rgba32Uint,Rgba16Float,Rgba16Float};
    inline static constexpr std::array<uint32_t,PlaneCount> PixelBytes={16,8,8,8,8,8,8,16,8,8};
    struct Plan {
        uint32_t width=0,height=0,composition=1,preservedHostComponents=0;
        bool motionIncludesJitter=false;uint64_t maximumResidentBytes=MaximumBudget;
        uint32_t PlaneCount()const{return RtrSurfaceInputProtocol::RequiredPlaneCount(composition);}
        uint32_t BytesPerPixel()const{return RtrSurfaceInputProtocol::BytesPerPixel(composition);}
        void Validate()const {
            if(width<1||height<1||width>16384||height>16384||composition>1||preservedHostComponents>31
                ||maximumResidentBytes<65536||maximumResidentBytes>MaximumBudget
                ||uint64_t(width)*height*BytesPerPixel()*SlotCount>maximumResidentBytes)
                throw std::invalid_argument("Invalid host-surface plan or three-slot texture budget");
        }
        bool operator==(const Plan& other)const{return width==other.width&&height==other.height&&composition==other.composition
            &&preservedHostComponents==other.preservedHostComponents&&motionIncludesJitter==other.motionIncludesJitter&&maximumResidentBytes==other.maximumResidentBytes;}
    };
    using Identity=RtrSurfaceInputProtocol::Identity;
    struct Token {uint64_t generation=0;uint32_t slot=0;uint64_t ticket=0;};
    struct Plane {uint32_t kind=0;Format format=Rgba16Float;uint64_t allocatedBytes=0,sharedHandle=0;};
    struct Packet {
        Token token;Identity identity;Plan plan;uint64_t adapterLuid=0,fenceHandle=0,previousDone=0,ready=0,done=0;
        std::array<Plane,PlaneCount> planes{};
    };
    struct Read {Packet packet;std::array<nvrhi::TextureHandle,PlaneCount> textures;};
private:
    using State=RtrSurfaceInputProtocol::State;
    struct Slot {
        std::array<nvrhi::TextureHandle,PlaneCount> textures;
        std::array<uint64_t,PlaneCount> handles{};
        Microsoft::WRL::ComPtr<ID3D12Fence> fence;HANDLE fenceHandle=nullptr;
        RtrSurfaceInputProtocol::Reservation ownership;
        ~Slot(){if(fenceHandle)CloseHandle(fenceHandle);}
    };
    struct Generation {
        uint64_t id=0,bytes=0;Plan plan;std::array<uint64_t,PlaneCount> allocation{};std::array<Slot,SlotCount> slots;
        bool Idle()const {for(const auto& slot:slots)if(slot.ownership.state!=State::Idle)return false;return true;}
    };
    std::thread::id owner=std::this_thread::get_id();nvrhi::IDevice* device=nullptr;uint64_t adapter=0;
    std::deque<std::unique_ptr<Generation>> generations;
    inline static std::atomic<uint64_t> identities{1};
    uint64_t publications=0,consumed=0,abandoned=0,lastConsumed=0;bool hasConsumed=false;
    void Owner()const{if(std::this_thread::get_id()!=owner)throw std::logic_error("Use host-surface inputs on their renderer owner thread");}
    static void Check(HRESULT result,const char* action){if(FAILED(result))throw std::runtime_error(action);}
    ID3D12CommandQueue* Queue()const {return static_cast<ID3D12CommandQueue*>(device->getNativeQueue(nvrhi::ObjectTypes::D3D12_CommandQueue,nvrhi::CommandQueue::Graphics).pointer);}
    static DXGI_FORMAT NativeFormat(Format format){switch(format){case Rgba16Float:return DXGI_FORMAT_R16G16B16A16_FLOAT;case Rgba32Float:return DXGI_FORMAT_R32G32B32A32_FLOAT;
        case Rg32Float:return DXGI_FORMAT_R32G32_FLOAT;case Rgba32Uint:return DXGI_FORMAT_R32G32B32A32_UINT;}throw std::invalid_argument("Unknown host-surface format");}
    static nvrhi::Format TextureFormat(Format format){switch(format){case Rgba16Float:return nvrhi::Format::RGBA16_FLOAT;case Rgba32Float:return nvrhi::Format::RGBA32_FLOAT;
        case Rg32Float:return nvrhi::Format::RG32_FLOAT;case Rgba32Uint:return nvrhi::Format::RGBA32_UINT;}throw std::invalid_argument("Unknown host-surface format");}
    Generation& Find(uint64_t id){for(auto& generation:generations)if(generation->id==id)return *generation;throw std::invalid_argument("Host-surface allocation generation expired");}
    Slot& Find(const Token& token){auto& generation=Find(token.generation);if(token.slot>=SlotCount)throw std::invalid_argument("Invalid host-surface slot");
        auto& slot=generation.slots[token.slot];if(slot.ownership.ticket!=token.ticket)throw std::invalid_argument("Host-surface reservation ticket expired");return slot;}
    Packet Describe(Generation& generation,uint32_t index){auto& slot=generation.slots[index];auto& state=slot.ownership;
        Packet result;result.token={generation.id,index,state.ticket};result.identity=state.identity;result.plan=generation.plan;result.adapterLuid=adapter;
        result.fenceHandle=uint64_t(reinterpret_cast<uintptr_t>(slot.fenceHandle));result.previousDone=state.previousDone;result.ready=state.ready;result.done=state.done;
        for(uint32_t i=0;i<generation.plan.PlaneCount();i++)result.planes[i]={i,Formats[i],generation.allocation[i],slot.handles[i]};return result;
    }
    uint64_t Resident()const{uint64_t bytes=0;for(const auto& generation:generations)bytes+=generation->bytes;return bytes;}
    void Discard(Slot& slot,const Token& token){
        auto& state=slot.ownership;
        if(state.state!=State::Published&&(state.state!=State::Acquired||!state.imported))throw std::logic_error("Host-surface input is not ready to discard");
        Check(Queue()->Wait(slot.fence.Get(),state.ready),"Could not enqueue host-surface discard readiness wait");
        Check(Queue()->Signal(slot.fence.Get(),state.done),"Could not enqueue host-surface discard completion");state.DiscardQueued(token.ticket);++abandoned;
    }
public:
    void Poll(){
        Owner();for(auto& generation:generations)for(auto& slot:generation->slots)if(slot.ownership.state==State::InFlight){
            const auto completed=slot.fence->GetCompletedValue();if(completed==UINT64_MAX)throw std::runtime_error("Device lost while retiring host-surface inputs");slot.ownership.Complete(completed);}
        while(generations.size()>1&&generations.front()->Idle())generations.pop_front();
    }
    void Configure(nvrhi::IDevice* selectedDevice,const Plan& requested){
        Owner();requested.Validate();if(!selectedDevice)throw std::invalid_argument("Host-surface inputs require a D3D12 device");
        if(device&&device!=selectedDevice)throw std::invalid_argument("Host-surface inputs cannot migrate to another device");Poll();
        if(!generations.empty()&&generations.back()->plan==requested)return;
        if(generations.size()>=2)throw std::runtime_error("Retire the preceding host-surface generation before another resize");
        auto* native=static_cast<ID3D12Device*>(selectedDevice->getNativeObject(nvrhi::ObjectTypes::D3D12_Device).pointer);
        if(!native)throw std::invalid_argument("Host-surface input allocation requires native D3D12");
        auto prepared=std::make_unique<Generation>();prepared->plan=requested;
        for(uint32_t i=0;i<requested.PlaneCount();i++){
            D3D12_RESOURCE_DESC desc{};desc.Dimension=D3D12_RESOURCE_DIMENSION_TEXTURE2D;desc.Width=requested.width;desc.Height=requested.height;
            desc.DepthOrArraySize=1;desc.MipLevels=1;desc.Format=NativeFormat(Formats[i]);desc.SampleDesc.Count=1;desc.Layout=D3D12_TEXTURE_LAYOUT_UNKNOWN;
            desc.Flags=D3D12_RESOURCE_FLAG_ALLOW_RENDER_TARGET|D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS;
            const auto info=native->GetResourceAllocationInfo(0,1,&desc);if(info.SizeInBytes==UINT64_MAX||info.SizeInBytes==0)throw std::runtime_error("Unsupported host-surface texture allocation");
            if(info.SizeInBytes>requested.maximumResidentBytes/SlotCount
                ||prepared->bytes>requested.maximumResidentBytes-info.SizeInBytes*SlotCount)
                throw std::runtime_error("Host-surface texture allocation exceeds the requested budget");
            prepared->allocation[i]=info.SizeInBytes;prepared->bytes+=info.SizeInBytes*SlotCount;
        }
        if(prepared->bytes>requested.maximumResidentBytes||Resident()>requested.maximumResidentBytes-prepared->bytes)
            throw std::runtime_error("Atomic host-surface resize exceeds current plus retired texture budget");
        for(auto& slot:prepared->slots){
            for(uint32_t i=0;i<requested.PlaneCount();i++){
                auto desc=nvrhi::TextureDesc().setWidth(requested.width).setHeight(requested.height).setFormat(TextureFormat(Formats[i]))
                    .setIsRenderTarget(true).setIsUAV(true).setInitialState(nvrhi::ResourceStates::Common).setKeepInitialState(true);
                desc.isShaderResource=true;desc.sharedResourceFlags=nvrhi::SharedResourceFlags::Shared;desc.debugName="Shared host primary surface input";
                slot.textures[i]=selectedDevice->createTexture(desc);if(!slot.textures[i])throw std::runtime_error("Could not allocate host-surface input plane");
                auto handle=slot.textures[i]->getNativeObject(nvrhi::ObjectTypes::SharedHandle).pointer;
                if(!handle)throw std::runtime_error("Could not export host-surface input plane");slot.handles[i]=uint64_t(reinterpret_cast<uintptr_t>(handle));
            }
            Check(native->CreateFence(0,D3D12_FENCE_FLAG_SHARED,IID_PPV_ARGS(&slot.fence)),"Could not create host-surface input fence");
            Check(native->CreateSharedHandle(slot.fence.Get(),nullptr,GENERIC_ALL,nullptr,&slot.fenceHandle),"Could not export host-surface input fence");
        }
        const auto identity=identities.fetch_add(1);if(identity>uint64_t((std::numeric_limits<int64_t>::max)()))throw std::overflow_error("Host-surface allocation identity exhausted");
        prepared->id=identity;device=selectedDevice;auto luid=native->GetAdapterLuid();std::memcpy(&adapter,&luid,8);
        generations.push_back(std::move(prepared));Poll();
    }
    std::optional<Packet> TryAcquire(const Identity& identity){
        Owner();identity.Validate();Poll();if(generations.empty())throw std::logic_error("Configure host-surface input slots first");
        if(hasConsumed&&identity.sequence<=lastConsumed)throw std::invalid_argument("Host-surface frame sequence was already consumed");
        for(const auto& generation:generations)for(const auto& slot:generation->slots)
            if(slot.ownership.state!=State::Idle&&slot.ownership.identity.sequence==identity.sequence)throw std::invalid_argument("Host-surface sequence already has an active input reservation");
        auto& generation=*generations.back();for(uint32_t i=0;i<SlotCount;i++)if(generation.slots[i].ownership.state==State::Idle){generation.slots[i].ownership.Acquire(identity);return Describe(generation,i);}return {};
    }
    void MarkImported(const Token& token){Owner();auto& slot=Find(token);slot.ownership.Imported(token.ticket);}
    void Publish(const Token& token){Owner();auto& slot=Find(token);slot.ownership.Publish(token.ticket);++publications;}
    void CancelUnimported(const Token& token){Owner();auto& slot=Find(token);slot.ownership.CancelUnimported(token.ticket);++abandoned;Poll();}
    void AbandonReady(const Token& token){Owner();auto& slot=Find(token);slot.ownership.Require(token.ticket,State::Acquired);Discard(slot,token);}
    void DiscardPublished(uint64_t generation,uint64_t sequence){
        Owner();auto& selected=Find(generation);for(uint32_t i=0;i<SlotCount;i++){auto& slot=selected.slots[i];
            if(slot.ownership.identity.sequence==sequence&&slot.ownership.state==State::Published){Discard(slot,{generation,i,slot.ownership.ticket});return;}}
        throw std::invalid_argument("Host-surface publication is absent or already being read");
    }
    Read BeginRead(const Identity& identity){
        Owner();identity.Validate();Poll();if(hasConsumed&&identity.sequence<=lastConsumed)throw std::invalid_argument("Host-surface input sequence already consumed");
        for(auto& generation:generations)for(uint32_t i=0;i<SlotCount;i++){auto& slot=generation->slots[i];
            if(slot.ownership.identity.sequence!=identity.sequence||slot.ownership.state!=State::Published)continue;
            if(!(slot.ownership.identity==identity))throw std::invalid_argument("Published host-surface facts differ from submitted frame/revision/resource/view/clock");
            Check(Queue()->Wait(slot.fence.Get(),slot.ownership.ready),"Could not enqueue GPU host-surface readiness wait");
            slot.ownership.Begin(slot.ownership.ticket,identity);return {Describe(*generation,i),slot.textures};}
        throw std::invalid_argument("Publish a complete matching host-surface input before submission");
    }
    // Call only when no command reading these textures has been submitted.
    void AbortRead(const Token& token){Owner();auto& slot=Find(token);slot.ownership.Abort(token.ticket);}
    void RestoreCommon(nvrhi::ICommandList* command,const Read& read){
        Owner();auto& slot=Find(read.packet.token);slot.ownership.Require(read.packet.token.ticket,State::Reading);
        if(!command)throw std::invalid_argument("Host-surface completion requires a command list");
        for(uint32_t i=0;i<read.packet.plan.PlaneCount();i++)command->setTextureState(slot.textures[i],nvrhi::AllSubresources,nvrhi::ResourceStates::Common);command->commitBarriers();
    }
    // The caller must execute its read commands and RestoreCommon before this.
    void EndRead(const Token& token){
        Owner();auto& slot=Find(token);slot.ownership.Require(token.ticket,State::Reading);
        Check(Queue()->Signal(slot.fence.Get(),slot.ownership.done),"Could not enqueue host-surface GPU read completion");
        slot.ownership.Finish(token.ticket);if(!hasConsumed||slot.ownership.identity.sequence>lastConsumed)lastConsumed=slot.ownership.identity.sequence;hasConsumed=true;++consumed;
    }
    std::array<uint64_t,9> Status(){
        Poll();std::array<uint64_t,9> values={generations.empty()?0:generations.back()->id,generations.size(),0,0,0,Resident(),publications,consumed,abandoned};
        for(const auto& generation:generations)for(const auto& slot:generation->slots){values[2]+=slot.ownership.state==State::Acquired;
            values[3]+=slot.ownership.state==State::Published;values[4]+=slot.ownership.state==State::Reading||slot.ownership.state==State::InFlight;}return values;
    }
    // Shutdown admission: queued renderer completion may still be in flight.
    // The shutdown path waits its own GPU queue before Clear/RequireRetired.
    void RequireReleased(){
        Poll();for(const auto& generation:generations)for(const auto& slot:generation->slots)
            if(slot.ownership.state!=State::Idle&&slot.ownership.state!=State::InFlight)
                throw std::runtime_error("Return host-surface leases and publish/discard pending input before closing");
    }
    void RequireRetired(){Poll();for(const auto& generation:generations)if(!generation->Idle())throw std::runtime_error("Return host-surface leases and retire published/read input before closing");}
    void Clear(){Owner();RequireRetired();generations.clear();device=nullptr;adapter=0;}
    static std::vector<int64_t> Encode(const Packet& packet){
        const auto& token=packet.token;const auto& id=packet.identity;
        std::vector<int64_t> words={AbiVersion,token.slot,int64_t(token.generation),int64_t(token.ticket),int64_t(id.sequence),int64_t(id.revision),int64_t(id.resourceGeneration),int64_t(id.viewIdentity),
            int64_t(packet.adapterLuid),packet.plan.width,packet.plan.height,int64_t(packet.fenceHandle),int64_t(packet.previousDone),int64_t(packet.ready),int64_t(packet.done),id.timeNanos,id.deltaNanos,packet.plan.PlaneCount()};
        for(uint32_t i=0;i<packet.plan.PlaneCount();i++){const auto& plane=packet.planes[i];words.insert(words.end(),{plane.kind,plane.format,int64_t(plane.allocatedBytes),int64_t(plane.sharedHandle)});}return words;
    }
};
#endif
