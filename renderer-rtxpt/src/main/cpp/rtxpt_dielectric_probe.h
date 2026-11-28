#pragma once
#include "rtxpt_dielectric_probe_projection.h"
#include <nvrhi/nvrhi.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <bit>
#include <charconv>
#include <cinttypes>
#include <cstdio>
#include <cstdlib>
#include <optional>
#include <stdexcept>
#include <string_view>

class RtrDielectricProbe {
    static constexpr uint32_t MaxFrames=64;
    static constexpr uint32_t HeaderWords=4,RecordWords=8,RecordCapacity=256;
    static constexpr size_t BufferBytes=(HeaderWords+RecordWords*RecordCapacity)*sizeof(uint32_t);
    struct Selection {
        std::array<double,3> coordinates{};
        uint64_t firstSequence=0;
        uint32_t count=1;
        bool motion=false;
    };
    struct Frame {
        uint64_t sequence=0,revision=0;
        uint32_t width=0,height=0;
        rtr::dielectric_probe::Texel texel;
        nvrhi::BufferHandle gpu,staging;
        nvrhi::TimerQueryHandle done;
        bool recorded=false;
    };
public:
    static constexpr uint32_t UavSlot=16;
    nvrhi::BindingLayoutHandle layout;
    nvrhi::ComputePipelineHandle pipeline;

    ~RtrDielectricProbe(){
        // Normal teardown drains the device; do not add a second wait here.
        Poll();
        for(;read<scheduled;++read)ReportFailure(frames[read],"incomplete");
    }

    bool Pending(uint64_t sequence)const{
        const auto& target=Target();
        return target&&sequence>=target->firstSequence&&scheduled<target->count
            &&(target->motion?(!scheduled||sequence>frames[scheduled-1].sequence):!claimed.load());
    }

    void Poll()noexcept{
        // Each submitted capture owns a distinct UAV, staging buffer and query.
        // Preserve frame order even when an offscreen frame needs no GPU work.
        for(;read<scheduled;++read){
            auto& frame=frames[read];
            if(frame.texel.status){
                Output(frame,frame.texel.status);
                continue;
            }
            if(!frame.recorded)return;
            try{
                if(!device->pollTimerQuery(frame.done))return;
                const auto* words=static_cast<const uint32_t*>(device->mapBuffer(frame.staging,nvrhi::CpuAccessMode::Read));
                if(words){
                    Log(frame,words);
                    device->unmapBuffer(frame.staging);
                }else ReportFailure(frame,"map-failed");
            }catch(const std::exception& error){
                ReportFailure(frame,"readback-failed",error.what());
            }
            frame.done=nullptr;frame.staging=nullptr;frame.gpu=nullptr;
        }
        if(scheduled&&scheduled==Target()->count){pipeline=nullptr;layout=nullptr;device=nullptr;}
    }

    bool Select(uint64_t sequence,uint64_t revision,uint32_t width,uint32_t height,const float* camera,bool emptyScene=false){
        if(!Pending(sequence))return false;
        const auto& target=*Target();
        if(!target.motion&&(emptyScene||claimed.exchange(true)))return false;
        auto& frame=frames[scheduled++];
        frame.sequence=sequence;frame.revision=revision;frame.width=width;frame.height=height;
        frame.texel=target.motion?rtr::dielectric_probe::Project(target.coordinates,camera,width,height)
            :rtr::dielectric_probe::Texel{std::min(int32_t(width)-1,int32_t(target.coordinates[0]*width)),
                std::min(int32_t(height)-1,int32_t(target.coordinates[1]*height))};
        if(emptyScene&&!frame.texel.status)frame.texel.status="empty-scene";
        if(frame.texel.status){Poll();return false;}
        return true;
    }

    nvrhi::IBuffer* Allocate(nvrhi::IDevice* renderer){
        auto& frame=frames[scheduled-1];
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
        frame.gpu=renderer->createBuffer(gpuDesc);
        frame.staging=renderer->createBuffer(stagingDesc);
        frame.done=renderer->createTimerQuery();
        if(!frame.gpu||!frame.staging||!frame.done)throw std::runtime_error("Could not allocate dielectric probe readback");
        device=renderer;
        return frame.gpu.Get();
    }

    void Begin(nvrhi::ICommandList* command){
        const auto& frame=frames[scheduled-1];
        const std::array<uint32_t,HeaderWords> header{uint32_t(frame.texel.x),uint32_t(frame.texel.y),0,0};
        command->writeBuffer(frame.gpu,header.data(),sizeof(header));
    }

    void End(nvrhi::ICommandList* command){
        auto& frame=frames[scheduled-1];
        // Submission arms this query on the same list as the copy. We only
        // poll its completion; no extra submission or timestamp read is needed.
        command->beginTimerQuery(frame.done);
        command->copyBuffer(frame.staging,0,frame.gpu,0,BufferBytes);
        command->endTimerQuery(frame.done);
        frame.recorded=true;
    }

private:
    inline static std::atomic_bool claimed=false; // Legacy one-shot is process-wide.
    std::array<Frame,MaxFrames> frames{};
    uint32_t read=0,scheduled=0;
    nvrhi::DeviceHandle device;

    template<class T> static bool Parse(std::string_view field,T& value){
        if(field.empty())return false;
        const auto* end=field.data()+field.size();
        const auto result=std::from_chars(field.data(),end,value);
        return result.ec==std::errc{}&&result.ptr==end;
    }

    static bool ParseCoordinate(std::string_view field,double& value){
        if(field.starts_with('+')){
            field.remove_prefix(1);
            if(field.starts_with('-'))return false;
        }
        return Parse(field,value)&&std::isfinite(value);
    }

    static const std::optional<Selection>& Target(){
        static const auto target=[]()->std::optional<Selection>{
            if(const char* motion=std::getenv("RTR_DIELECTRIC_PROBE_MOTION")){
                Selection selected;
                selected.motion=true;
                std::string_view remaining(motion);
                auto next=[&](){
                    const auto end=remaining.find(',');
                    const auto field=remaining.substr(0,end);
                    remaining=end==remaining.npos?std::string_view{}:remaining.substr(end+1);
                    return field;
                };
                if(ParseCoordinate(next(),selected.coordinates[0])&&ParseCoordinate(next(),selected.coordinates[1])
                    &&ParseCoordinate(next(),selected.coordinates[2])
                    &&Parse(next(),selected.firstSequence)&&Parse(remaining,selected.count)
                    &&selected.count>=1&&selected.count<=MaxFrames)return selected;
                std::fprintf(stderr,"RTXPT_DIELECTRIC_PROBE invalid RTR_DIELECTRIC_PROBE_MOTION; expected x,y,z,firstSequence,count (1..%u)\n",MaxFrames);
            }
            const char* env=std::getenv("RTR_DIELECTRIC_PROBE");
            if(!env)return std::nullopt;
            const std::string_view value(env);
            const auto first=value.find(',');
            const auto second=first==value.npos?value.npos:value.find(',',first+1);
            Selection selected;
            auto& xy=selected.coordinates;
            if(second!=value.npos&&Parse(value.substr(0,first),xy[0])
                &&Parse(value.substr(first+1,second-first-1),xy[1])
                &&xy[0]>=0&&xy[0]<1&&xy[1]>=0&&xy[1]<1
                &&Parse(value.substr(second+1),selected.firstSequence))return selected;
            std::fputs("RTXPT_DIELECTRIC_PROBE invalid RTR_DIELECTRIC_PROBE; expected normalizedX,normalizedY,firstFrameSequence\n",stderr);
            return std::nullopt;
        }();
        return target;
    }

    static void Log(const Frame& frame,const uint32_t* words)noexcept{
        const uint32_t count=std::min(words[2],RecordCapacity);
        const unsigned overflow=words[3]!=0||words[2]>RecordCapacity;
        static constexpr const char* names[]={"unknown","launch-origin","launch-direction","hit","normal","media",
            "fresnel","reflected-direction","transmitted-direction","path-choice","terminal-light","terminal-emission",
            "terminal-contribution","escape-contribution","path-stop","resume-branch","signal","front-blocker","legacy-first-choice",
            "contact-receiver","terminal-position","terminal-uv","terminal-albedo","terminal-incident",
            "segment-transmittance","segment-power","terminal-texture-nearest","terminal-texture-linear",
            "terminal-emitter-support","terminal-local-light","terminal-dynamic-light","terminal-sun-visibility","terminal-moon-visibility"};
        std::fprintf(stderr,"RTXPT_DIELECTRIC_PROBE sequence=%" PRIu64 " revision=%" PRIu64
            " width=%" PRIu32 " height=%" PRIu32 " x=%" PRIu32 " y=%" PRIu32 " count=%" PRIu32 " overflow=%u\n",
            frame.sequence,frame.revision,frame.width,frame.height,words[0],words[1],count,overflow);
        const uint32_t* signal=nullptr;
        for(uint32_t slot=0;slot<count;++slot){
            const auto* row=words+HeaderWords+slot*RecordWords;
            const auto* name=row[0]<sizeof(names)/sizeof(names[0])?names[row[0]]:names[0];
            std::fprintf(stderr,"RTXPT_DIELECTRIC_PROBE sequence=%" PRIu64 " slot=%" PRIu32 " event=%" PRIu32
                " name=%s i0=%" PRIu32 " i1=%" PRIu32 " i2=%" PRIu32 " f0=%.9g f1=%.9g f2=%.9g f3=%.9g\n",
                frame.sequence,slot,row[0],name,row[1],row[2],row[3],std::bit_cast<float>(row[4]),
                std::bit_cast<float>(row[5]),std::bit_cast<float>(row[6]),std::bit_cast<float>(row[7]));
            if(row[0]==16)signal=row+4;
        }
        if(Target()->motion)Output(frame,signal?"ok":"missing-signal",signal,overflow);
        std::fflush(stderr);
    }

    static void Output(const Frame& frame,const char* status,const uint32_t* signal=nullptr,unsigned overflow=0,const char* error="")noexcept{
        const auto& world=Target()->coordinates;
        const auto channel=[&](unsigned i){return signal?std::bit_cast<float>(signal[i]):0.f;};
        std::fprintf(stderr,"RTXPT_DIELECTRIC_PROBE output sequence=%" PRIu64 " revision=%" PRIu64
            " worldX=%.17g worldY=%.17g worldZ=%.17g width=%" PRIu32 " height=%" PRIu32
            " x=%" PRId32 " y=%" PRId32 " status=%s r=%.9g g=%.9g b=%.9g a=%.9g overflow=%u%s%s\n",
            frame.sequence,frame.revision,world[0],world[1],world[2],frame.width,frame.height,frame.texel.x,frame.texel.y,
            status,channel(0),channel(1),channel(2),channel(3),overflow,*error?" error=":"",error);
        std::fflush(stderr);
    }

    static void ReportFailure(const Frame& frame,const char* status,const char* error="")noexcept{
        if(Target()->motion)Output(frame,status,nullptr,0,error);
        else std::fprintf(stderr,"RTXPT_DIELECTRIC_PROBE sequence=%" PRIu64 " revision=%" PRIu64 " status=%s%s%s\n",
            frame.sequence,frame.revision,status,*error?" error=":"",error);
        std::fflush(stderr);
    }
};
