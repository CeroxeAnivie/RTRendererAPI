#pragma once
#include <directx/d3d12.h>
#include <wrl/client.h>
#include <nvrhi/nvrhi.h>
#include <algorithm>
#include <vector>
#include <stdexcept>
#include "rtxpt_allocation_failure.h"

// The receiver table keeps its original byte layout and single SRV. A reserved
// buffer provides stable addresses while physical backing grows by tiles. This
// avoids a second scene-sized committed allocation and old-capacity GPU copy at
// each small high-water-mark increase. No shader or upstream allocator changes.
class RtrReceiverArena {
    static constexpr uint64_t Tile=64*1024,Chunk=4*1024*1024,Limit=512ull*1024*1024;
    Microsoft::WRL::ComPtr<ID3D12Resource> resource;
    std::vector<Microsoft::WRL::ComPtr<ID3D12Heap>> heaps;
    uint64_t mapped=0;
    bool checked=false,supported=false;
public:
    uint64_t mappedBytes()const{return mapped;}
    bool Ensure(nvrhi::IDevice* device,nvrhi::BufferHandle& buffer,size_t bytes,size_t stride,const char* name){
        if(bytes>Limit)throw std::runtime_error("Attribute arena exceeds 512 MiB capacity");
        auto* native=static_cast<ID3D12Device*>(device->getNativeObject(nvrhi::ObjectTypes::D3D12_Device));
        if(!checked){
            D3D12_FEATURE_DATA_D3D12_OPTIONS options{};
            supported=native&&SUCCEEDED(native->CheckFeatureSupport(D3D12_FEATURE_D3D12_OPTIONS,&options,sizeof(options)))
                &&options.TiledResourcesTier>=D3D12_TILED_RESOURCES_TIER_2;
            checked=true;
        }
        if(!supported)return false;
        auto* queue=static_cast<ID3D12CommandQueue*>(device->getNativeQueue(nvrhi::ObjectTypes::D3D12_CommandQueue,nvrhi::CommandQueue::Graphics));
        if(!queue)throw std::runtime_error("Receiver arena requires its native graphics queue");
        if(!resource){
            D3D12_RESOURCE_DESC desc{};desc.Dimension=D3D12_RESOURCE_DIMENSION_BUFFER;
            desc.Width=Limit;desc.Height=1;desc.DepthOrArraySize=1;desc.MipLevels=1;
            desc.SampleDesc.Count=1;desc.Layout=D3D12_TEXTURE_LAYOUT_ROW_MAJOR;
            const auto result=native->CreateReservedResource(&desc,D3D12_RESOURCE_STATE_COMMON,nullptr,IID_PPV_ARGS(&resource));
            if(FAILED(result))throw std::runtime_error(RtrAllocationFailure(device,name,bytes)+" reservedResourceResult="+std::to_string(long(result)));
            resource->SetName(L"Native receiver attributes tiled arena");
            nvrhi::BufferDesc view;view.byteSize=Limit;view.structStride=uint32_t(stride);view.debugName=name;
            view.canHaveRawViews=true;view.initialState=nvrhi::ResourceStates::ShaderResource;view.keepInitialState=true;
            auto wrapped=device->createHandleForNativeBuffer(nvrhi::ObjectTypes::D3D12_Resource,resource.Get(),view);
            if(!wrapped)throw std::runtime_error("Could not import receiver arena into native resource tracking");
            buffer=std::move(wrapped);
        }
        if(bytes<=mapped)return true;
        const uint64_t target=std::min<uint64_t>(Limit,(std::max<uint64_t>(bytes,1)+Chunk-1)/Chunk*Chunk);
        const uint64_t growth=target-mapped;
        D3D12_HEAP_DESC desc{};desc.SizeInBytes=growth;desc.Alignment=Tile;
        desc.Properties.Type=D3D12_HEAP_TYPE_DEFAULT;desc.Properties.CreationNodeMask=desc.Properties.VisibleNodeMask=1;
        desc.Flags=D3D12_HEAP_FLAG_ALLOW_ONLY_BUFFERS;
        Microsoft::WRL::ComPtr<ID3D12Heap> next;
        const auto result=native->CreateHeap(&desc,IID_PPV_ARGS(&next));
        if(FAILED(result))throw std::runtime_error(RtrAllocationFailure(device,name,growth)+" mappedBytes="+std::to_string(mapped));
        // Reserve before enqueueing: a throwing CPU vector growth must not drop
        // a heap after UpdateTileMappings has published its physical pages.
        heaps.reserve(heaps.size()+1);
        D3D12_TILED_RESOURCE_COORDINATE start{};start.X=UINT(mapped/Tile);
        D3D12_TILE_REGION_SIZE region{};region.NumTiles=UINT(growth/Tile);region.UseBox=FALSE;
        const D3D12_TILE_RANGE_FLAGS flags=D3D12_TILE_RANGE_FLAG_NONE;const UINT offset=0,count=region.NumTiles;
        // Only previously unmapped tiles are added. Existing readers and tile
        // bindings stay untouched; the next write/read executes on this queue.
        queue->UpdateTileMappings(resource.Get(),1,&start,&region,next.Get(),1,&flags,&offset,&count,D3D12_TILE_MAPPING_FLAG_NONE);
        heaps.push_back(std::move(next));mapped=target;
        return true;
    }
};
