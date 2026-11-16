#include <directx/d3d12.h>
#include <dxgi1_6.h>
#include <wrl/client.h>
#include <nvrhi/d3d12.h>
#include <iostream>
#include <array>
#include <vector>
#include <stdexcept>
#include "rtxpt_receiver_arena.h"
using Microsoft::WRL::ComPtr;
extern "C" {__declspec(dllexport) extern const UINT D3D12SDKVersion=616;__declspec(dllexport) extern const char* D3D12SDKPath=".\\D3D12\\";}
struct Messages:nvrhi::IMessageCallback {bool failed=false;void message(nvrhi::MessageSeverity severity,const char* text)override{
    if(severity==nvrhi::MessageSeverity::Error||severity==nvrhi::MessageSeverity::Fatal)failed=true;
    std::cerr<<text<<'\n';
}};
static void require(bool v,const char* text){if(!v)throw std::runtime_error(text);}
int main(){try{
    ComPtr<IDXGIFactory6> factory;require(SUCCEEDED(CreateDXGIFactory2(0,IID_PPV_ARGS(&factory))),"Factory");
    ComPtr<IDXGIAdapter1> adapter;require(SUCCEEDED(factory->EnumAdapterByGpuPreference(0,DXGI_GPU_PREFERENCE_HIGH_PERFORMANCE,IID_PPV_ARGS(&adapter))),"Adapter");
    ComPtr<ID3D12Device> native;require(SUCCEEDED(D3D12CreateDevice(adapter.Get(),D3D_FEATURE_LEVEL_12_1,IID_PPV_ARGS(&native))),"Device");
    D3D12_COMMAND_QUEUE_DESC q{};q.Type=D3D12_COMMAND_LIST_TYPE_DIRECT;ComPtr<ID3D12CommandQueue> queue;
    require(SUCCEEDED(native->CreateCommandQueue(&q,IID_PPV_ARGS(&queue))),"Queue");
    Messages messages;nvrhi::d3d12::DeviceDesc deviceDesc;deviceDesc.errorCB=&messages;deviceDesc.pDevice=native.Get();deviceDesc.pGraphicsCommandQueue=queue.Get();
    auto device=nvrhi::d3d12::createDevice(deviceDesc);require(bool(device),"Native NVRHI device");
    RtrReceiverArena arena;nvrhi::BufferHandle buffer;
    require(arena.Ensure(device,buffer,16,16,"Receiver growth test"),"Tiled buffers unsupported on this adapter");
    const auto* identity=buffer.Get();const auto address=buffer->getGpuVirtualAddress();
    auto readback=device->createBuffer(nvrhi::BufferDesc().setByteSize(16).setCpuAccess(nvrhi::CpuAccessMode::Read).setInitialState(nvrhi::ResourceStates::CopyDest).setKeepInitialState(true));
    auto commands=device->createCommandList();
    const std::array<uint32_t,4> first{0x3e800000,0x3f800000,0xf1a3b5d7,0};
    commands->open();commands->writeBuffer(buffer,first.data(),16,0);commands->close();device->executeCommandList(commands);device->waitForIdle();device->runGarbageCollection();
    unsigned steps=0;
    for(uint64_t required:{4ull*1024*1024+16,20ull*1024*1024+16,288670752ull}){
        require(arena.Ensure(device,buffer,required,16,"Receiver growth test"),"Grow tiled arena");
        require(buffer.Get()==identity&&buffer->getGpuVirtualAddress()==address,"Growth moved the resource");
        auto last=first;last[2]+=++steps;
        commands->open();commands->writeBuffer(buffer,last.data(),16,required-16);commands->copyBuffer(readback,0,buffer,required-16,16);commands->close();device->executeCommandList(commands);device->waitForIdle();device->runGarbageCollection();
        auto* bytes=device->mapBuffer(readback,nvrhi::CpuAccessMode::Read);require(bytes&&memcmp(bytes,last.data(),16)==0,"Newly mapped tile lost data");device->unmapBuffer(readback);
        commands->open();commands->copyBuffer(readback,0,buffer,0,16);commands->close();device->executeCommandList(commands);device->waitForIdle();device->runGarbageCollection();
        bytes=device->mapBuffer(readback,nvrhi::CpuAccessMode::Read);require(bytes&&memcmp(bytes,first.data(),16)==0,"Growth changed old data");device->unmapBuffer(readback);
        require(arena.mappedBytes()>=required&&arena.mappedBytes()-required<4*1024*1024,"Physical headroom exceeds one chunk");
    }
    require(!messages.failed,"NVRHI validation failure");
    std::cout<<"RECEIVER_ARENA PASS growths="<<steps<<" stableResource=true oldAndNewBytesExact=true mappedBytes="<<arena.mappedBytes()<<" fullArenaCopies=0\n";
    device->waitForIdle();return 0;
}catch(const std::exception& e){std::cerr<<"RECEIVER_ARENA FAIL "<<e.what()<<'\n';return 1;}}
