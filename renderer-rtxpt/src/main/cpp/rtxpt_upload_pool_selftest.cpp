#include <nvrhi/d3d12.h>
#include <wrl/client.h>
#include "rtxpt_upload_statistics.h"
#include "rtxpt_native_budget.h"
#include <array>
#include <chrono>
#include <iostream>
#include <stdexcept>
using Microsoft::WRL::ComPtr;
static void Check(bool value,const char* reason){if(!value)throw std::runtime_error(reason);}
struct Messages final:nvrhi::IMessageCallback {
    bool failed=false;
    void message(nvrhi::MessageSeverity severity,const char* text)override{
        if(severity>=nvrhi::MessageSeverity::Error){failed=true;std::cerr<<text<<'\n';}
    }
};
int main(){try{
    ComPtr<ID3D12Device> native;Check(SUCCEEDED(D3D12CreateDevice(nullptr,D3D_FEATURE_LEVEL_12_0,IID_PPV_ARGS(&native))),"D3D12 device creation failed");
    D3D12_COMMAND_QUEUE_DESC queueDesc{};ComPtr<ID3D12CommandQueue> queue;
    Check(SUCCEEDED(native->CreateCommandQueue(&queueDesc,IID_PPV_ARGS(&queue))),"D3D12 queue creation failed");
    Messages messages;nvrhi::d3d12::DeviceDesc desc;desc.errorCB=&messages;desc.pDevice=native.Get();desc.pGraphicsCommandQueue=queue.Get();
    auto device=nvrhi::d3d12::createDevice(desc);Check(bool(device),"NVRHI device creation failed");
    constexpr uint64_t Bytes=18ull*1024*1024,WriteBytes=4096,Chunks=Bytes/WriteBytes;
    nvrhi::BufferDesc bufferDesc;bufferDesc.byteSize=Bytes;bufferDesc.keepInitialState=true;
    auto buffer=device->createBuffer(bufferDesc);bufferDesc.cpuAccess=nvrhi::CpuAccessMode::Read;bufferDesc.initialState=nvrhi::ResourceStates::CopyDest;
    auto readback=device->createBuffer(bufferDesc);Check(bool(buffer)&&bool(readback),"Fixture buffers were not created");
    auto command=device->createCommandList(nvrhi::CommandListParameters().setUploadChunkSize(1024*1024));
    {
        nvrhi::rt::GeometryDesc geometry;auto& triangles=geometry.geometryData.triangles;
        triangles.vertexBuffer=buffer;triangles.vertexFormat=nvrhi::Format::RGB32_FLOAT;
        triangles.vertexStride=12;triangles.vertexCount=3;
        auto acceleration=nvrhi::rt::AccelStructDesc().addBottomLevelGeometry(geometry)
            .setBuildFlags(nvrhi::rt::AccelStructBuildFlags::AllowUpdate);
        const auto before=RtrNativeBudget::Snapshot();
        RtrNativeBudget::Acquire(RtrNativeBudget::Resource,RtrNativeBudget::Limit-before[0]);
        bool rejected=false;
        try{auto unavailable=device->createAccelStruct(acceleration);}
        catch(const RtrResourcePending&){rejected=true;}
        RtrNativeBudget::Release(RtrNativeBudget::Resource,RtrNativeBudget::Limit-before[0]);
        Check(rejected,"AS prepare bypassed the resource domain budget");
        Check(RtrNativeBudget::Snapshot()[0]==before[0],"Rejected AS preparation leaked a budget charge");
        auto recovered=device->createAccelStruct(acceleration);
        Check(bool(recovered)&&recovered->getDeviceAddress()!=0,"AS preparation did not recover after budget rejection");
        std::cout<<"AS_BUDGET PASS rejected=true unpublished=true recovered=true\n";
    }
    {
        const auto before=RtrNativeBudget::Snapshot();
        RtrNativeBudget::Acquire(RtrNativeBudget::Upload,RtrNativeBudget::Limit-before[3]);
        command->open();bool rejected=false;std::array<uint32_t,4> data{};
        bool prepareRejected=false;
        try{command->prepareUploadCapacity(Bytes);}
        catch(const RtrResourcePending&){prepareRejected=true;}
        try{command->writeBuffer(buffer,data.data(),sizeof(data));}catch(const RtrResourcePending&){rejected=true;}
        command->close();
        RtrNativeBudget::Release(RtrNativeBudget::Upload,RtrNativeBudget::Limit-before[3]);
        Check(rejected&&prepareRejected,"Upload budget refusal did not abort preparation and recording");
        Check(RtrNativeBudget::Snapshot()[3]==before[3],"Rejected upload leaked a budget charge");
        std::cout<<"UPLOAD_BUDGET PASS rejected=true submitted=false retry=sameCommandList\n";
    }
    for(unsigned trial=0;trial<8;++trial){Check(bool(command),"Command creation failed");
        const auto before=RtrUploadStatistics::Snapshot();const auto start=std::chrono::steady_clock::now();
        command->open();std::array<uint32_t,WriteBytes/4> data{};
        for(uint64_t chunk=0;chunk<Chunks;++chunk){
            data.fill(uint32_t(chunk)+trial*10000+1);command->writeBuffer(buffer,data.data(),WriteBytes,chunk*WriteBytes);
        }
        command->copyBuffer(readback,0,buffer,0,Bytes);command->close();
        const auto recorded=std::chrono::steady_clock::now();device->executeCommandList(command);
        Check(device->waitForIdle(),"GPU completion failed");const auto completed=std::chrono::steady_clock::now();
        const auto after=RtrUploadStatistics::Snapshot();
        const auto* actual=static_cast<const uint32_t*>(device->mapBuffer(readback,nvrhi::CpuAccessMode::Read));
        Check(actual!=nullptr,"GPU readback map failed");
        for(uint64_t word=0;word<Bytes/4;++word)Check(actual[word]==uint32_t(word/(WriteBytes/4))+trial*10000+1,"GPU readback differs from current recording");
        device->unmapBuffer(readback);Check(!messages.failed,"NVRHI reported an error");
        const auto allocations=after[4]-before[4];
        Check(trial==0?allocations==18:allocations==0,"Native completed chunks were not reused");
        std::cout<<"UPLOAD_POOL_FRAME trial="<<trial<<" nativeReuse=true"
            <<" writes="<<Chunks<<" payloadBytes="<<Bytes<<" allocations="<<allocations<<" allocatedBytes="<<(after[0]-before[0])
            <<" recordNanos="<<std::chrono::duration_cast<std::chrono::nanoseconds>(recorded-start).count()
            <<" completeNanos="<<std::chrono::duration_cast<std::chrono::nanoseconds>(completed-start).count()<<'\n';
        device->runGarbageCollection();
    }
    std::cout<<"UPLOAD_POOL_GPU PASS trials=8 writesPerTrial=4608 exactReadbackBytesPerTrial=18874368 window=false\n";return 0;
}catch(const std::exception& error){std::cerr<<error.what()<<'\n';return 1;}}
