#pragma once
#include <array>
#include <cmath>
#include <stdexcept>

// JNI transport only. Estimators, cache allocation/training and denoising stay upstream.
struct RtrPathQuality {
    std::array<int,19> values{0,0,1,5,0,4,0,2*1024*1024,5,8,4,1,3,9,1,0,0,10,64};
    std::array<float,6> constants{.65f,3.5f,-1.f,1.f,35.f,.4f};
    void Validate() const {
        const auto& v=values;const auto& f=constants;
        auto range=[](int x,int a,int b){return x>=a&&x<=b;};
        if(!range(v[0],0,1)||!range(v[1],0,3)||!range(v[2],1,63)||!range(v[3],1,63)
            ||!range(v[4],0,2)||!range(v[5],0,4096)||!range(v[6],0,1)
            ||!range(v[7],65536,16777216)||(v[7]&(v[7]-1))||!range(v[8],2,5)
            ||!range(v[9],1,64)||!range(v[10],0,v[9])||!range(v[11],0,63)
            ||!range(v[12],1,3)||!range(v[13],1,15)||!range(v[14],0,2)
            ||!range(v[15],0,3)||!range(v[16],0,1)||!range(v[17],1,255)||!range(v[18],8,128))
            throw std::invalid_argument("Invalid managed path-tracing quality budget");
        for(float x:f)if(!std::isfinite(x))throw std::invalid_argument("Path-tracing quality constants must be finite");
        if(f[0]<0||f[0]>1||f[1]<.01f||f[1]>10||f[2]<-8||f[2]>8||f[3]<-8||f[3]>8
            ||f[4]<.0001f||f[4]>1e6f||f[5]<0||f[5]>1||(v[0]==1&&v[6]))
            throw std::invalid_argument("Invalid managed path-tracing quality constants");
    }
    void Apply(SampleUIData& ui) const {
        const auto& v=values;
        if(v[1]!=0)ui.NEEType=v[1]-1;
        ui.NEEFullSamples=v[2];ui.NEECandidateSamples=v[3];ui.NEEAT_QualityPreset=v[4];
        ui.NEEAT_LocalToGlobalSampleRatio=constants[0];
        ui.DenoisingLayersActiveCount=v[12];ui.DenoisingLayersMaxVertexDepth=v[13];ui.NestedDielectricsQuality=v[14];
        ui.RealtimeSubSamplingMode=v[15];ui.UseFp16Types=v[16]!=0;ui.TexLODBias=constants[2];
    }
    void Apply(SharcSettings& cache) const {
        cache.DownScaleFactor=values[8];cache.BounceCount=values[9];cache.DiffuseBounceCount=values[10];
        cache.NEEFullSamples=values[11];cache.AccumulationFrameNum=values[17];cache.StaleFrameFrameNum=values[18];
        cache.TerminateThreshold=constants[1];cache.AdditionalTexLODBias=constants[3];
        cache.SceneScale=constants[4];cache.MinRoughnessOverride=constants[5];
    }
};
