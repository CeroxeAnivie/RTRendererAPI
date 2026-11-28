#pragma once
#include <nvrhi/nvrhi.h>
#include <algorithm>
#include <array>
#include <bit>
#include <charconv>
#include <cinttypes>
#include <cstdio>
#include <cstdlib>
#include <optional>
#include <stdexcept>
#include <string_view>

class RtrDielectricProbe {
public:
    static constexpr uint32_t UavSlot=16;
    nvrhi::BindingLayoutHandle layout;
    nvrhi::ComputePipelineHandle pipeline;

    ~RtrDielectricProbe(){
        // The render loop already drains the device before normal teardown.
        // This delivers probes on the final frame without adding a wait.
        Poll();
        if(pending)ReportFailure("incomplete");
    }

    bool Pending(uint64_t sequence)const{
        const auto& target=Target();
        return target&&!claimed&&sequence>=target->firstSequence;
    }

    void Poll()noexcept{
        if(!pending)return;
        try{
            if(!device->pollTimerQuery(done))return;
            const auto* words=static_cast<const uint32_t*>(device->mapBuffer(staging,nvrhi::CpuAccessMode::Read));
            if(words){
                Log(words);
                device->unmapBuffer(staging);
            }else ReportFailure("map-failed");
        }catch(const std::exception& error){
            ReportFailure("readback-failed",error.what());
        }
        pending=false;
        done=nullptr;staging=nullptr;gpu=nullptr;pipeline=nullptr;layout=nullptr;device=nullptr;
    }

    nvrhi::IBuffer* Allocate(nvrhi::IDevice* renderer){
        nvrhi::BufferDesc gpuDesc;
        gpuDesc.byteSize=BufferBytes;
        gpuDesc.canHaveRawViews=true;
        gpuDesc.canHaveUAVs=true;
        gpuDesc.initialState=nvrhi::ResourceStates::UnorderedAccess;
        gpuDesc.keepInitialState=true;
        gpuDesc.debugName="Dielectric probe trace";
        nvrhi::BufferDesc stagingDesc;
        stagingDesc.byteSize=BufferBytes;
        stagingDesc.cpuAccess=nvrhi::CpuAccessMode::Read;
        stagingDesc.initialState=nvrhi::ResourceStates::CopyDest;
        stagingDesc.debugName="Dielectric probe readback";
        gpu=renderer->createBuffer(gpuDesc);
        staging=renderer->createBuffer(stagingDesc);
        done=renderer->createTimerQuery();
        if(!gpu||!staging||!done)throw std::runtime_error("Could not allocate dielectric probe readback");
        device=renderer;
        return gpu.Get();
    }

    void Begin(nvrhi::ICommandList* command,uint64_t sequence,uint64_t revision,uint32_t width,uint32_t height){
        const auto& target=*Target();
        frame={sequence,revision,width,height,
            std::min(width-1,uint32_t(target.x*width)),std::min(height-1,uint32_t(target.y*height))};
        const std::array<uint32_t,HeaderWords> header{frame.x,frame.y,0,0};
        command->writeBuffer(gpu,header.data(),sizeof(header));
    }

    void End(nvrhi::ICommandList* command){
        // Submission arms this query on the same list as the copy. We only
        // poll its completion; no extra submission or timestamp read is needed.
        command->beginTimerQuery(done);
        command->copyBuffer(staging,0,gpu,0,BufferBytes);
        command->endTimerQuery(done);
        claimed=true;
        pending=true;
    }

private:
    static constexpr uint32_t HeaderWords=4,RecordWords=8,RecordCapacity=256;
    static constexpr size_t BufferBytes=(HeaderWords+RecordWords*RecordCapacity)*sizeof(uint32_t);
    struct Selection {double x=0,y=0;uint64_t firstSequence=0;};
    struct Frame {uint64_t sequence=0,revision=0;uint32_t width=0,height=0,x=0,y=0;};
    // The process environment selects one capture across serialized renderer sessions.
    inline static bool claimed=false;
    bool pending=false;
    Frame frame;
    nvrhi::DeviceHandle device;
    nvrhi::BufferHandle gpu,staging;
    nvrhi::TimerQueryHandle done;

    template<class T> static bool Parse(std::string_view field,T& value){
        if(field.empty())return false;
        const auto* end=field.data()+field.size();
        const auto result=std::from_chars(field.data(),end,value);
        return result.ec==std::errc{}&&result.ptr==end;
    }

    static const std::optional<Selection>& Target(){
        static const auto target=[]()->std::optional<Selection>{
            const char* env=std::getenv("RTR_DIELECTRIC_PROBE");
            if(!env)return std::nullopt;
            const std::string_view value(env);
            const auto first=value.find(',');
            const auto second=first==value.npos?value.npos:value.find(',',first+1);
            Selection selected;
            if(second!=value.npos&&Parse(value.substr(0,first),selected.x)
                &&Parse(value.substr(first+1,second-first-1),selected.y)
                &&selected.x>=0&&selected.x<1&&selected.y>=0&&selected.y<1
                &&Parse(value.substr(second+1),selected.firstSequence))return selected;
            std::fputs("RTXPT_DIELECTRIC_PROBE invalid RTR_DIELECTRIC_PROBE; expected normalizedX,normalizedY,firstFrameSequence\n",stderr);
            return std::nullopt;
        }();
        return target;
    }

    void Log(const uint32_t* words)const{
        const uint32_t count=std::min(words[2],RecordCapacity);
        const unsigned overflow=words[3]!=0||words[2]>RecordCapacity;
        static constexpr const char* names[]={"unknown","launch-origin","launch-direction","hit","normal","media",
            "fresnel","reflected-direction","transmitted-direction","path-choice","terminal-light","terminal-emission",
            "terminal-contribution","escape-contribution","path-stop","resume-branch","signal","front-blocker","legacy-first-choice",
            "contact-receiver"};
        std::fprintf(stderr,"RTXPT_DIELECTRIC_PROBE sequence=%" PRIu64 " revision=%" PRIu64
            " width=%" PRIu32 " height=%" PRIu32 " x=%" PRIu32 " y=%" PRIu32 " count=%" PRIu32 " overflow=%u\n",
            frame.sequence,frame.revision,frame.width,frame.height,words[0],words[1],count,overflow);
        for(uint32_t slot=0;slot<count;++slot){
            const auto* row=words+HeaderWords+slot*RecordWords;
            const auto* name=row[0]<sizeof(names)/sizeof(names[0])?names[row[0]]:names[0];
            std::fprintf(stderr,"RTXPT_DIELECTRIC_PROBE sequence=%" PRIu64 " slot=%" PRIu32 " event=%" PRIu32
                " name=%s i0=%" PRIu32 " i1=%" PRIu32 " i2=%" PRIu32 " f0=%.9g f1=%.9g f2=%.9g f3=%.9g\n",
                frame.sequence,slot,row[0],name,row[1],row[2],row[3],std::bit_cast<float>(row[4]),
                std::bit_cast<float>(row[5]),std::bit_cast<float>(row[6]),std::bit_cast<float>(row[7]));
        }
        std::fflush(stderr);
    }

    void ReportFailure(const char* status,const char* error="")const{
        std::fprintf(stderr,"RTXPT_DIELECTRIC_PROBE sequence=%" PRIu64 " revision=%" PRIu64 " status=%s%s%s\n",
            frame.sequence,frame.revision,status,*error?" error=":"",error);
        std::fflush(stderr);
    }
};
