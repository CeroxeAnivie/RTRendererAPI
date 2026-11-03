// The host owns lifecycle and presentation. All rendering stays in the pinned RTXPT core.
// This software contains source code provided by NVIDIA Corporation.
#include <jni.h>
#include <atomic>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>
#include <unordered_map>
#include <cmath>
#include <sstream>
#include <filesystem>
#include <array>
#include <cstdio>
#include <fstream>
#include <chrono>
#include <donut/engine/TextureCache.h>
#include <json/json.h>
#include "StreamlineIntegration.h"
#define WinMain RtrUnusedSampleEntryPoint
#include RTR_RTXPT_ADVANCED_SOURCE
#undef WinMain
#include <Materials/MaterialsBaker.h>
#include <OpacityMicroMap/OmmBaker.h>
#define GLFW_EXPOSE_NATIVE_WIN32
#include <GLFW/glfw3native.h>
#include "rtxpt_shutdown.h"
#include "rtxpt_shared_frames.h"
#include "rtxpt_material_policy.h"
#include "rtxpt_sr_history.h"
#include "rtxpt_path_quality.h"
#include "rtxpt_host_resources.h"
#include "rtxpt_memory_diagnostics.h"
#include "rtxpt_light_tree.h"
extern SampleUIData g_sampleUIData;

namespace {
class HostPathTracer final : public AdvancedPathTracer {
    struct SceneLight { float4 positionType,directionRange,colorIntensity,coneShadow; };
    static_assert(sizeof(SceneLight)==64);
    std::vector<SceneLight> sceneLights;
    std::vector<rtr::LightTreeNode> sceneLightTree;
    uint64_t lightIndexBuilds=0,lightIndexBuildNs=0;
    double lightPowerNormalization=1;
    bool uploadSunOnly=false;
    std::vector<uint32_t> sunTreePath,treeParents,lightLeaves,dirtyLightIndices,dirtyTreeIndices;
    std::unordered_map<int64_t,uint32_t> lightIndices;
    std::vector<std::shared_ptr<donut::engine::Light>> managedLightObjects;
    uint64_t lightDeltaRecords=0,lightTreeRefits=0;
    int lightingContinuity=0;
    bool continuousPending=false,independentBackground=false,transparentBackground=false;
    float3 primaryBackground={};
    uint64_t executedAccumulationResets=0,executedReconstructionResets=0,executedDenoiserResets=0,cacheNotifications=0,continuousUploads=0;
    void HostHistoryAction(int scope,bool reset) override {
        if(!reset)return;
        if(scope==0)++executedAccumulationResets;
        else if(scope==1)++executedReconstructionResets;
        else if(scope==2)++executedDenoiserResets;
    }
    static bool Abrupt(const SceneLight& old,const SceneLight& next) {
        if(old.positionType.w!=next.positionType.w||old.coneShadow.z!=next.coneShadow.z)return true;
        for(int a=0;a<4;a++)if(std::abs(next.colorIntensity[a]-old.colorIntensity[a])>std::max(.001f,std::max(next.colorIntensity[a],old.colorIntensity[a])*.1f))return true;
        float distance=0;for(int a=0;a<3;a++){float d=next.positionType[a]-old.positionType[a];distance+=d*d;}
        if(distance>std::pow(std::max(.02f,next.directionRange.w*.05f),2.f))return true;
        if(next.positionType.w!=1 && dot(old.directionRange.xyz(),next.directionRange.xyz())<.995f)return true;
        return std::abs(next.directionRange.w-old.directionRange.w)>std::max(.001f,std::max(next.directionRange.w,old.directionRange.w)*.1f)
            || std::abs(next.coneShadow.x-old.coneShadow.x)>.05f || std::abs(next.coneShadow.y-old.coneShadow.y)>.05f;
    }
    void LightingHistory(bool abrupt) {
        g_sampleUIData.ResetAccumulation=true;
        const bool reset=lightingContinuity==0||abrupt;
        if(reset){
            g_sampleUIData.ResetRealtimeCaches=true;preSrHistoryReset=true;++lightingHistoryResets;
            if(auto cache=GetSharc();cache&&g_sampleUIData.ActualRadianceCacheMode()!=0){cache->NotifySceneReloaded();++cacheNotifications;}
        }
        if(UsesBakedLighting())if(auto baker=GetLightsBaker())baker->SceneReloaded();
        continuousPending=!reset;
    }

    float4 sceneSky={};
    nvrhi::BufferHandle sceneLightBuffer;
    bool lightStatsEnabled=[] {const char* value=std::getenv("RTR_RTXPT_LIGHT_STATS");return value&&value[0]=='1';}();
    nvrhi::BufferHandle lightStatsBuffer;
    bool sceneLightsDirty=false, managedLighting=false;
    std::shared_ptr<donut::engine::SceneGraphNode> managedLightRoot;
    struct RetiredLightingBuffer {
        nvrhi::BufferHandle buffer;
        nvrhi::EventQueryHandle graphics,compute;
    };
    std::vector<RetiredLightingBuffer> retiredLighting;
    uint64_t sceneLoadCount=0,lightingBatchCount=0,lightingUploadCount=0,lightingUploadNs=0,lightingHistoryResets=0;
    bool lightingCommitPending=false;
    class ManagedDirectional final : public donut::engine::DirectionalLight, public LightExtension {
    public:
        std::shared_ptr<donut::engine::SceneGraphLeaf> Clone() override {
            auto copy=std::make_shared<ManagedDirectional>();copy->color=color;copy->irradiance=irradiance;
            copy->angularSize=angularSize;copy->Copy(*this);return copy;
        }
    };
    void ConfigureManagedBackground() {
        if(auto baker=GetEnvMapBaker()) {
            if(managedLighting)baker->SetHostConstantBackground(sceneSky.xyz()*sceneSky.w);
            else baker->ClearHostConstantBackground();
        }
    }
    bool sceneLightingActive() const {
        return pathQuality.values[0]==1 && managedLighting && !sceneLights.empty()
            && !g_sampleUIData.ActualUseRTXDIPasses() && g_sampleUIData.ActualRadianceCacheMode()==0;
    }
    std::unique_ptr<RtrPreSrHistory> preSrHistory;
    bool preSrHistoryReset=true;
    bool directionalStrataLogged=false;
    bool useDirectionalStrata() const {
        if(!pathQuality.values[6] || !GetScene()
            || !g_sampleUIData.RealtimeMode || g_sampleUIData.RealtimeAA!=2
            || g_sampleUIData.ActualUseStandaloneDenoiser() || g_sampleUIData.ActualUseRTXDIPasses()
            || g_sampleUIData.ActualRadianceCacheMode()!=0)return false;
        for(const auto& light:GetScene()->GetSceneGraph()->GetLights())
            if(light->GetLightType()==LightType_Directional
                && static_cast<const donut::engine::DirectionalLight&>(*light).irradiance>0)return true;
        return false;
    }
    bool smallPointLighting=false;
    int previousNeeType=2;
    bool powerSampling=false;
    bool eligiblePowerScene=false;
    bool lightingSceneDirty=true;
    bool lightingPolicyLogged=false;
    bool eligibleLightingScene=false;
    bool smallPointScene() const {
        if(!GetScene())return false;
        const auto graph=GetScene()->GetSceneGraph();
        const auto& lights=graph->GetLights();
        size_t analyticCount=0;
        bool pointFound=false;
        for(const auto& light:lights) {
            const auto type=light->GetLightType();
            if(type==LightType_Point) {
                ++analyticCount;
                if(static_cast<const donut::engine::PointLight*>(light.get())->radius==0.f)
                    pointFound=true;
            } else if(type==LightType_Spot) {
                ++analyticCount;
            }
        }
        const bool eligible=pointFound && analyticCount<=size_t(pathQuality.values[5]);
        donut::log::info("RTXPT exact-point scene gate: graphLights=%zu neeAtAnalytic=%zu zeroRadiusPoints=%s eligible=%s",
            lights.size(),analyticCount,pointFound?"yes":"no",eligible?"yes":"no");
        return eligible;
    }
public:
    using AdvancedPathTracer::AdvancedPathTracer;
    #include "rtxpt_retained_scene.inl"
    int exactOpticalInterfaces=4;
    RtrPathQuality pathQuality;
    uint32_t HostRadianceCacheEntries() const override {return uint32_t(pathQuality.values[7]);}
    void ConfigureHostRadianceCache(SharcSettings& settings) const override {pathQuality.Apply(settings);}
    void ConfigurePathQuality(const RtrPathQuality& quality) {
        quality.Validate();
        if(auto* cache=GetSharc();cache && cache->GetEntryCount()!=uint32_t(quality.values[7]))
            throw std::logic_error("Radiance cache capacity changes require a new renderer");
        pathQuality=quality;pathQuality.Apply(g_sampleUIData);lightingSceneDirty=true;
        if(auto* cache=GetSharc()) {
            pathQuality.Apply(cache->HostSettings());
        }
    }
    bool SceneLightingActive() const {return sceneLightingActive();}
    bool UsesBakedLighting() const override {
        // Use precisely the shader's route; directional strata still consume the baked cube.
        return !sceneLightingActive() || useDirectionalStrata();
    }
    bool ExactPointLightingActive() const {return smallPointLighting;}
    bool DirectionalStrataActive() const {return useDirectionalStrata();}
    size_t HostShaderCompilerThreads() const {return GetRTPipelineBaker()?GetRTPipelineBaker()->HostCompilerThreadCount():0;}
    void LoadSceneLighting(const std::string& path) {
        sceneLights.clear();sceneSky={};sceneLightsDirty=true;managedLighting=false;managedLightRoot.reset();
        std::ifstream input(std::filesystem::u8path(path));
        Json::Value root;if(!input || !(input>>root))return;
        const auto& data=root["rtrSceneLighting"];
        if(!data.isObject()){ConfigureManagedBackground();return;}
        managedLighting=true;
        const auto& sky=data["sky"];
        if(sky.size()!=4)throw std::runtime_error("Scene lighting sky must have four components");
        for(uint32_t i=0;i<4;i++)sceneSky[i]=sky[i].asFloat();
        for(const auto& record:data["lights"]) {
            if(record.size()!=16)throw std::runtime_error("Scene lighting record must have sixteen components");
            SceneLight light{};
            for(uint32_t i=0;i<4;i++) {
                light.positionType[i]=record[i].asFloat();light.directionRange[i]=record[i+4].asFloat();
                light.colorIntensity[i]=record[i+8].asFloat();light.coneShadow[i]=record[i+12].asFloat();
            }
            sceneLights.push_back(light);
        }
        preSrHistoryReset=true;ConfigureManagedBackground();
        if(sceneLightingActive())donut::log::info("RTXPT scene lighting inputs ACTIVE lights=%zu; analytic ambient, exact direct, analytic sky, stable metal, split first interface",sceneLights.size());
    }
    void CreateCustomRenderPasses() override {
        AdvancedPathTracer::CreateCustomRenderPasses();ConfigureManagedBackground();
    }
    std::array<uint64_t,5> LightingTelemetry() const {
        return {sceneLoadCount,lightingBatchCount,lightingUploadCount,lightingUploadNs,lightingHistoryResets};
    }
    void RebuildLightTree() {
        const auto begin=std::chrono::steady_clock::now();
        sceneLightTree=rtr::BuildLightTree(sceneLights,&lightPowerNormalization);++lightIndexBuilds;
        sunTreePath.clear();treeParents.assign(sceneLightTree.size(),0);lightLeaves.assign(sceneLights.size(),0);
        for(uint32_t i=0;i<sceneLightTree.size();i++){
            auto& node=sceneLightTree[i];
            if(node.light<0){treeParents[uint32_t(node.left)]=i;treeParents[uint32_t(node.right)]=i;}
            else lightLeaves.at(uint32_t(node.light))=i;
        }
        if(!sceneLightTree.empty())for(uint32_t i=lightLeaves[0];;i=treeParents[i]){sunTreePath.push_back(i);if(i==0)break;}
        dirtyLightIndices.clear();dirtyTreeIndices.clear();
        lightIndexBuildNs+=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-begin).count();
        uploadSunOnly=false;
    }
    void ApplyEnvironment(const std::array<float,16>& sun,const std::array<float,4>& sky) {
        if(!managedLighting||sceneLights.empty()||!managedLightRoot)throw std::logic_error("Environment requires a resident managed scene");
        for(float value:sun)if(!std::isfinite(value))throw std::invalid_argument("Invalid environment sun");
        for(float value:sky)if(!std::isfinite(value)||value<0)throw std::invalid_argument("Invalid environment sky");
        if(sun[3]!=0||sun[8]<0||sun[9]<0||sun[10]<0||sun[11]<0||std::abs(sun[4]*sun[4]+sun[5]*sun[5]+sun[6]*sun[6]-1.f)>.003f)
            throw std::invalid_argument("Invalid environment sun values");
        SceneLight nextSun;std::memcpy(&nextSun,sun.data(),sizeof(SceneLight));bool abrupt=Abrupt(sceneLights[0],nextSun);
        for(int a=0;a<4;a++)abrupt|=std::abs(sceneSky[a]-sky[a])>std::max(.001f,std::max(sceneSky[a],sky[a])*.1f);
        if(!sceneLightsDirty)uploadSunOnly=true;
        std::memcpy(&sceneLights[0],sun.data(),sizeof(SceneLight));sceneSky=float4(sky[0],sky[1],sky[2],sky[3]);
        auto* light=dynamic_cast<donut::engine::DirectionalLight*>(managedLightObjects.at(0).get());
        if(!light)throw std::logic_error("Managed sun is missing");
        light->color=float3(sun[8],sun[9],sun[10]);light->irradiance=sun[11];light->SetDirection(double3(sun[4],sun[5],sun[6]));
        double power=double(sun[11])*(.2126*sun[8]+.7152*sun[9]+.0722*sun[10])/lightPowerNormalization;
        if(!std::isfinite(float(power))||power>1e25)RebuildLightTree();
        else {rtr::RefitLightTree(sceneLightTree,sceneLights,treeParents,lightLeaves[0],lightPowerNormalization,dirtyTreeIndices);dirtyLightIndices.push_back(0);++lightTreeRefits;}
        sceneLightsDirty=true;lightingCommitPending=true;ConfigureManagedBackground();
        LightingHistory(abrupt);
        directionalStrataLogged=false;++lightingBatchCount;
    }
    void ApplyLighting(const std::vector<int64_t>& ids,const std::vector<float>& records,const std::array<float,4>& sky) {
        if(!GetScene() || !managedLighting)throw std::logic_error("Lighting batches require a retained API scene");
        const size_t count=ids.size();
        if(count==0 || records.size()!=count*16 || ids[0]!=-1)throw std::invalid_argument("Malformed complete lighting batch");
        if(count>size_t(NEEAT_MAX_LIGHTS-RTXPT_NEEAT_ENVMAP_QT_TOTAL_NODE_COUNT))throw std::invalid_argument("Lighting batch exceeds native sampler capacity");
        std::unordered_map<int64_t,bool> unique;
        for(float v:sky)if(!std::isfinite(v)||v<0)throw std::invalid_argument("Invalid lighting sky");
        std::vector<SceneLight> next(count);
        std::vector<std::shared_ptr<donut::engine::Light>> nextObjects;nextObjects.reserve(count);
        auto candidate=std::make_shared<donut::engine::SceneGraph>();
        auto candidateRoot=std::make_shared<donut::engine::SceneGraphNode>();candidateRoot->SetName("RTRLighting");candidate->SetRootNode(candidateRoot);
        for(size_t i=0;i<count;i++) {
            if(i%256==0)RtrHostResources::PumpEvents();
            const float* v=&records[i*16];
            for(size_t j=0;j<16;j++)if(!std::isfinite(v[j]))throw std::invalid_argument("Non-finite light record");
            if((i>0&&ids[i]<0)||!unique.emplace(ids[i],true).second)throw std::invalid_argument("Duplicate or invalid light id");
            const int type=int(v[3]);
            if(float(type)!=v[3]||type<0||type>2 || v[7]<0 || v[8]<0||v[9]<0||v[10]<0||v[11]<0
                || (v[14]!=0&&v[14]!=1) || (type!=0&&v[7]<=0)
                || (type==2&&(v[12]<v[13]||v[12]>1||v[13]<-1)))throw std::invalid_argument("Invalid light record");
            if(type!=1 && std::abs(v[4]*v[4]+v[5]*v[5]+v[6]*v[6]-1.f)>.003f)throw std::invalid_argument("Light direction must be normalized");
            std::memcpy(&next[i],v,sizeof(SceneLight));
            std::shared_ptr<donut::engine::Light> light;
            if(type==0) {
                std::shared_ptr<donut::engine::DirectionalLight> l=i==0?std::make_shared<donut::engine::DirectionalLight>():std::make_shared<ManagedDirectional>();
                l->irradiance=v[11];l->angularSize=i==0?.53f:0.f;light=l;
            }else if(type==1) {
                auto l=std::make_shared<PointLightEx>();l->intensity=v[11];l->range=v[7];l->radius=0;light=l;
            }else {
                auto l=std::make_shared<SpotLightEx>();l->intensity=v[11];l->range=v[7];l->radius=0;
                l->innerAngle=degrees(std::acos(std::clamp(v[12],-1.f,1.f)));l->outerAngle=degrees(std::acos(std::clamp(v[13],-1.f,1.f)));light=l;
            }
            if(auto* metadata=dynamic_cast<LightExtension*>(light.get())){metadata->HostManaged=true;metadata->CastsShadow=v[14]!=0;}
            nextObjects.push_back(light);light->color=float3(v[8],v[9],v[10]);
            auto node=candidate->AttachLeafNode(candidateRoot,light);node->SetName(i==0?"Sun":"l"+std::to_string(ids[i]));
            light->SetPosition(double3(v[0],v[1],v[2]));if(type!=1)light->SetDirection(double3(v[4],v[5],v[6]));
        }
        candidate->Detach(candidateRoot);
        auto graph=GetScene()->GetSceneGraph();
        // Allocate and validate the whole replacement before touching the resident graph.
        // An allocation failure during the commit propagates to Java, which closes this session before another render.
        if(managedLightRoot)graph->Detach(managedLightRoot);
        else {
            auto previousLights=graph->GetLights();
            size_t retiredLightCount=0;
            for(auto& light:previousLights)if(light->GetLightType()!=LightType_Environment){
                if(retiredLightCount++%256==0)RtrHostResources::PumpEvents();graph->Detach(light->GetNode()->shared_from_this());
            }
        }
        managedLightRoot=graph->Attach(graph->GetRootNode(),candidateRoot);
        // Consume only the lighting structure flags before Java applies this frame's transforms.
        // Scene::Refresh must not interpret adding a light as changing mesh topology.
        graph->Refresh(GetFrameIndex());m_lights=graph->GetLights();
        managedLightObjects=std::move(nextObjects);lightIndices.clear();for(uint32_t i=0;i<count;i++)lightIndices.emplace(ids[i],i);
        sceneLights=std::move(next);sceneSky=float4(sky[0],sky[1],sky[2],sky[3]);sceneLightsDirty=true;
        RebuildLightTree();
        lightingSceneDirty=true;lightingPolicyLogged=false;directionalStrataLogged=false;preSrHistoryReset=true;
        ConfigureManagedBackground();
        LightingHistory(true);
        const char* freezeNoise=std::getenv("RTR_RTXPT_DIAGNOSTIC_FREEZE_NOISE");
        if(freezeNoise&&std::string(freezeNoise)=="1")g_sampleUIData.DbgFreezeRealtimeNoiseSeed=true;
        ++lightingBatchCount;lightingCommitPending=true;
    }
    void SetLightingContinuity(int policy){
        if(policy<0||policy>1)throw std::invalid_argument("Invalid lighting continuity");
        if(policy==1&&!sceneLightingActive())throw std::invalid_argument("Continuous lighting requires SCENE_ANALYTIC without ReSTIR or radiance cache");
        lightingContinuity=policy;
    }
    bool HostTransparentBackground() const override { return transparentBackground; }
    void SetBackground(bool independent,float3 color,bool transparent){
        if(transparentBackground!=transparent || independentBackground!=independent || (independent&&any(primaryBackground!=color))){g_sampleUIData.ResetRealtimeCaches=true;preSrHistoryReset=true;}
        transparentBackground=transparent;independentBackground=independent;primaryBackground=color;
    }
    std::array<jlong,16> ExecutionTelemetry() {auto p=GetRTPipelineBaker()?GetRTPipelineBaker()->HostTelemetry():std::array<uint64_t,3>{};return {jlong(importerNs),jlong(textureFinalizeNs),jlong(materialSyncNs),jlong(accelerationNs),jlong(nativeRetirementNs),jlong(lightDeltaRecords),jlong(lightTreeRefits),jlong(executedAccumulationResets),jlong(executedReconstructionResets),jlong(executedDenoiserResets),jlong(cacheNotifications),jlong(continuousUploads),jlong(p[0]),jlong(p[1]),jlong(p[2]),jlong(RtrHostResources::maximumPollIntervalNanos)};}
    void ApplyLightDelta(const std::vector<int64_t>& ids,const std::vector<float>& values){
        if(values.size()!=ids.size()*16)throw std::invalid_argument("Invalid light delta");
        std::vector<SceneLight> next(ids.size());std::unordered_map<int64_t,bool> seen;bool abrupt=false;
        for(size_t i=0;i<ids.size();i++){
            auto entry=lightIndices.find(ids[i]);if(entry==lightIndices.end()||ids[i]<0||!seen.emplace(ids[i],true).second)throw std::invalid_argument("Unknown or duplicate light delta ID");
            const float* v=&values[i*16];for(int a=0;a<16;a++)if(!std::isfinite(v[a]))throw std::invalid_argument("Non-finite light delta");
            auto& old=sceneLights[entry->second];if(v[3]!=old.positionType.w||v[7]<0||v[8]<0||v[9]<0||v[10]<0||v[11]<0||(v[14]!=0&&v[14]!=1)
                ||(v[3]!=0&&v[7]<=0)||(v[3]!=1&&std::abs(v[4]*v[4]+v[5]*v[5]+v[6]*v[6]-1.f)>.003f)
                ||(v[3]==2&&(v[12]<v[13]||v[12]>1||v[13]<-1)))throw std::invalid_argument("Invalid light delta values");
            std::memcpy(&next[i],v,sizeof(SceneLight));abrupt|=Abrupt(old,next[i]);
        }
        if(!sceneLightsDirty)uploadSunOnly=true;
        for(size_t i=0;i<ids.size();i++){
            uint32_t index=lightIndices.at(ids[i]);sceneLights[index]=next[i];auto& l=managedLightObjects.at(index);const auto& v=next[i];
            l->color=v.colorIntensity.xyz();l->SetPosition(double3(v.positionType.xyz()));
            if(v.positionType.w==0){auto& d=static_cast<donut::engine::DirectionalLight&>(*l);d.irradiance=v.colorIntensity.w;l->SetDirection(double3(v.directionRange.xyz()));}
            else if(v.positionType.w==1){auto& d=static_cast<PointLightEx&>(*l);d.intensity=v.colorIntensity.w;d.range=v.directionRange.w;}
            else{auto& d=static_cast<SpotLightEx&>(*l);d.intensity=v.colorIntensity.w;d.range=v.directionRange.w;d.innerAngle=degrees(std::acos(std::clamp(v.coneShadow.x,-1.f,1.f)));d.outerAngle=degrees(std::acos(std::clamp(v.coneShadow.y,-1.f,1.f)));l->SetDirection(double3(v.directionRange.xyz()));}
            if(auto* metadata=dynamic_cast<LightExtension*>(l.get()))metadata->CastsShadow=v.coneShadow.z!=0;
            double power=double(v.colorIntensity.w)*(.2126*v.colorIntensity.x+.7152*v.colorIntensity.y+.0722*v.colorIntensity.z)/lightPowerNormalization;
            if(!std::isfinite(float(power))||power>1e25)RebuildLightTree();
            else{rtr::RefitLightTree(sceneLightTree,sceneLights,treeParents,lightLeaves.at(index),lightPowerNormalization,dirtyTreeIndices);dirtyLightIndices.push_back(index);++lightTreeRefits;}
        }
        lightDeltaRecords+=ids.size();sceneLightsDirty=true;lightingCommitPending=true;++lightingBatchCount;LightingHistory(abrupt);
    }
    void UpdateCustomResources() override {
        AdvancedPathTracer::UpdateCustomResources();
        if(lightStatsEnabled&&!lightStatsBuffer){
            nvrhi::BufferDesc desc;desc.byteSize=16;desc.canHaveUAVs=true;desc.canHaveRawViews=true;
            desc.initialState=nvrhi::ResourceStates::UnorderedAccess;desc.keepInitialState=true;desc.debugName="RtrLightWork";
            lightStatsBuffer=GetDevice()->createBuffer(desc);
        }
        for(auto it=retiredLighting.begin();it!=retiredLighting.end();) {
            if(GetDevice()->pollEventQuery(it->graphics)&&GetDevice()->pollEventQuery(it->compute))it=retiredLighting.erase(it);else ++it;
        }
        ConfigureManagedBackground();
        const size_t bytes=std::max<size_t>(sizeof(SceneLight),(sceneLights.size()+sceneLightTree.size())*sizeof(SceneLight));
        if(!sceneLightBuffer || sceneLightBuffer->getDesc().byteSize<bytes || sceneLightBuffer->getDesc().byteSize>bytes*4) {
            size_t capacity=sizeof(SceneLight);while(capacity<bytes)capacity*=2;
            nvrhi::BufferDesc desc;desc.byteSize=capacity;desc.structStride=sizeof(SceneLight);
            desc.debugName="RtrSceneLightingInputs";desc.initialState=nvrhi::ResourceStates::ShaderResource;desc.keepInitialState=true;
            auto replacement=GetDevice()->createBuffer(desc);
            if(!replacement)throw std::runtime_error("Could not allocate scene light buffer");
            if(sceneLightBuffer) {
                RetiredLightingBuffer retired;retired.buffer=sceneLightBuffer;
                retired.graphics=GetDevice()->createEventQuery();retired.compute=GetDevice()->createEventQuery();
                GetDevice()->setEventQuery(retired.graphics,nvrhi::CommandQueue::Graphics);
                GetDevice()->setEventQuery(retired.compute,nvrhi::CommandQueue::Compute);
                retiredLighting.push_back(std::move(retired));
            }
            GetBindingCache().Clear();InvalidateBindingSet();
            sceneLightBuffer=replacement;sceneLightsDirty=true;uploadSunOnly=false;
        }
    }
    void AddCustomBindings(nvrhi::BindingSetDesc& desc) override {
        AdvancedPathTracer::AddCustomBindings(desc);
        if(lightStatsEnabled)desc.bindings.push_back(nvrhi::BindingSetItem::RawBuffer_UAV(96,lightStatsBuffer));
        for(auto& item:desc.bindings)if(item.slot==96 && item.type==nvrhi::ResourceType::StructuredBuffer_SRV)
            item=nvrhi::BindingSetItem::StructuredBuffer_SRV(96,sceneLightBuffer);
    }
    void FilterReconstructionInput(nvrhi::ICommandList* command, bool reset) override {
        const char* history=std::getenv("RTR_RTXPT_DIAGNOSTIC_PRE_SR_HISTORY");
        const bool enabled=history && std::string(history)=="1"
            && g_sampleUIData.RealtimeMode && g_sampleUIData.RealtimeAA==2
            && !g_sampleUIData.ActualUseStandaloneDenoiser() && !g_sampleUIData.ActualUseRTXDIPasses()
            && g_sampleUIData.ActualRadianceCacheMode()==0;
        if(!enabled) {preSrHistory.reset();preSrHistoryReset=true;return;}
        if(!preSrHistory)preSrHistory=std::make_unique<RtrPreSrHistory>();
        GpuProfiler::Begin(command,"PreSrHistory");
        // RTXPT traces at pixel center - view jitter. Reproject that sample
        // into the previous raster grid with previous jitter - current jitter.
        preSrHistory->Apply(GetDevice(),*GetShaderFactory(),command,*m_renderTargets,m_bindingSet,m_bindingLayout,
            reset||preSrHistoryReset,m_viewPrevious->GetPixelOffset()-m_view->GetPixelOffset());
        GpuProfiler::End(command);
        preSrHistoryReset=false;
    }
    void UpdateCustomConstants(SampleConstants& constants, nvrhi::ICommandList* commandList) override {
        AdvancedPathTracer::UpdateCustomConstants(constants,commandList);
        if(lightStatsEnabled)commandList->clearBufferUInt(lightStatsBuffer,0);
        auto& pt=constants.ptConsts;
        pt.RtrSceneAmbient=float4(sceneSky.xyz()*sceneSky.w,transparentBackground?2.f:independentBackground?1.f:0.f);
        pt.RtrSceneSky=float4(sceneSky.xyz()*std::max(sceneSky.w,.05f),0);
        std::memcpy(&pt.RtrDirectionalPadding,&primaryBackground,sizeof(float3)); // bit-exact RGB in reserved ABI words
        pt.RtrSceneLightCount=uint32_t(sceneLights.size());
        if(sceneLightsDirty && sceneLightBuffer) {
            const auto begin=std::chrono::steady_clock::now();
            if(uploadSunOnly){
                std::sort(dirtyLightIndices.begin(),dirtyLightIndices.end());dirtyLightIndices.erase(std::unique(dirtyLightIndices.begin(),dirtyLightIndices.end()),dirtyLightIndices.end());
                std::sort(dirtyTreeIndices.begin(),dirtyTreeIndices.end());dirtyTreeIndices.erase(std::unique(dirtyTreeIndices.begin(),dirtyTreeIndices.end()),dirtyTreeIndices.end());
                for(uint32_t index:dirtyLightIndices)commandList->writeBuffer(sceneLightBuffer,&sceneLights[index],sizeof(SceneLight),index*sizeof(SceneLight));
                for(uint32_t index:dirtyTreeIndices)commandList->writeBuffer(sceneLightBuffer,&sceneLightTree[index],sizeof(rtr::LightTreeNode),(sceneLights.size()+index)*sizeof(SceneLight));
            }else{
                if(!sceneLights.empty())commandList->writeBuffer(sceneLightBuffer,sceneLights.data(),sceneLights.size()*sizeof(SceneLight));
                if(!sceneLightTree.empty())commandList->writeBuffer(sceneLightBuffer,sceneLightTree.data(),sceneLightTree.size()*sizeof(rtr::LightTreeNode),sceneLights.size()*sizeof(SceneLight));
            }
            uploadSunOnly=false;dirtyLightIndices.clear();dirtyTreeIndices.clear();
            sceneLightsDirty=false;
            if(lightingCommitPending) {
                ++lightingUploadCount;lightingCommitPending=false;if(continuousPending)++continuousUploads;continuousPending=false;
                lightingUploadNs+=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-begin).count();
                const char* diagnostics=std::getenv("RTR_RTXPT_LIGHTING_DIAGNOSTICS");
                if(diagnostics&&std::string(diagnostics)=="1")donut::log::info("RTXPT lighting executed loads=%llu batches=%llu uploads=%llu uploadRecordingNs=%llu historyResets=%llu lights=%zu",sceneLoadCount,lightingBatchCount,lightingUploadCount,lightingUploadNs,lightingHistoryResets,sceneLights.size());
            }
        }
        pt.RtrDirectionalCount=0;
        if(!useDirectionalStrata() || !GetEnvMapBaker())return;
        const float cubeDim=float(GetEnvMapBaker()->GetTargetCubeResolution());
        if(cubeDim<=0 || !m_envMapSceneParams.Enabled)return;
        for(const auto& light:GetScene()->GetSceneGraph()->GetLights()) {
            if(light->GetLightType()!=LightType_Directional || pt.RtrDirectionalCount>=16)continue;
            const auto& data=static_cast<const donut::engine::DirectionalLight&>(*light);
            if(data.irradiance<=0)continue;
            // Cover the native sun's raster footprint, including cube filtering.
            // Any energy outside this cap remains in the residual estimator.
            const float angle=std::min(PI_f*.5f,
                std::max(radians(std::clamp(data.angularSize,0.f,90.f)),2*PI_f/cubeDim)*.5f+4.f/cubeDim);
            pt.RtrDirectionalCaps[pt.RtrDirectionalCount++]=float4(float3(-normalize(data.GetDirection())),std::cos(angle));
        }
        if(!directionalStrataLogged) {
            donut::log::info("RTXPT directional environment strata ACTIVE count=%u cubeDim=%.0f; baked illumination retained",pt.RtrDirectionalCount,cubeDim);
            directionalStrataLogged=true;
        }
    }
    nvrhi::ITexture* HostDisplayColor() const { return m_renderTargets ? m_renderTargets->LdrColor.Get() : nullptr; }
    void SceneLoaded() override {
        AdvancedPathTracer::SceneLoaded();++sceneLoadCount;
        preSrHistoryReset=true;
        directionalStrataLogged=false;
        const char* disableGuides=std::getenv("RTR_RTXPT_DIAGNOSTIC_DISABLE_TRANSMISSION_GUIDES");
        const bool applyGuides=!(disableGuides && std::string(disableGuides)=="1");
        const auto guideStats=ApplyDefaultTransmissionDenoisingGuides(GetScene()->GetSceneGraph().get(),applyGuides);
        if(applyGuides) {
            donut::log::info("RTXPT transmission denoising guides: transmissiveMaterials=%zu decompositionEnabled=%zu defaultedToTransmission=%zu gpuMaterialsUpdated=%zu",
                guideStats.transmissiveMaterials,guideStats.decompositionEnabled,guideStats.defaultedToTransmission,guideStats.gpuMaterialsUpdated);
        } else {
            donut::log::info("RTXPT transmission denoising guides: disabled for diagnostic control");
        }
        lightingSceneDirty=true;
        lightingPolicyLogged=false;
    }
    void UpdateLightingPolicy() {
        m_bakeDirectionalLightsIntoEnvMap=!sceneLightingActive();
        if(lightingSceneDirty) {
            eligibleLightingScene=smallPointScene();
            eligiblePowerScene=eligibleLightingScene;
            if(GetScene())for(const auto& material:GetScene()->GetSceneGraph()->GetMaterials())
                if(material->emissiveColor.x!=0.f || material->emissiveColor.y!=0.f || material->emissiveColor.z!=0.f)
                    eligiblePowerScene=false;
            lightingSceneDirty=!GetScene();
        }
        const char* overrideValue=std::getenv("RTR_RTXPT_DIAGNOSTIC_SMALL_POINTS");
        const bool standaloneDenoisingActive=g_sampleUIData.ActualUseStandaloneDenoiser();
        // This light estimator is upstream of both RR and NRD; denoising does not change its PDF.
        const bool supportedRealtimePolicy=g_sampleUIData.RealtimeMode
            && (g_sampleUIData.RealtimeAA==2 || g_sampleUIData.RealtimeAA==3)
            && !g_sampleUIData.ActualUseRTXDIPasses()
            && g_sampleUIData.ActualRadianceCacheMode()==0 && g_sampleUIData.ActualSamplesPerPixel()==1;
        if(!lightingPolicyLogged && GetScene()) {
            donut::log::info("RTXPT small-point policy: realtime=%d aa=%d standaloneRequested=%d standaloneActive=%d rtxdi=%d sharc=%d spp=%u eligible=%d override=%s accepted=%d",
                int(g_sampleUIData.RealtimeMode),g_sampleUIData.RealtimeAA,int(g_sampleUIData.StandaloneDenoiser),
                int(standaloneDenoisingActive),int(g_sampleUIData.ActualUseRTXDIPasses()),
                g_sampleUIData.ActualRadianceCacheMode(),g_sampleUIData.ActualSamplesPerPixel(),
                int(eligibleLightingScene),overrideValue?overrideValue:"default",int(supportedRealtimePolicy));
            lightingPolicyLogged=true;
        }
        const bool enabled=eligibleLightingScene && supportedRealtimePolicy
            && !(overrideValue && std::string(overrideValue)=="0");
        const char* samplerControl=std::getenv("RTR_RTXPT_DIAGNOSTIC_LIGHT_SAMPLER");
        // Retain the accepted SR policy for small, non-emissive scenes. Other
        // content and reconstruction paths keep upstream adaptive sampling.
        const bool usePower=pathQuality.values[1]==0 && enabled && (samplerControl
            ? std::string(samplerControl)=="power"
            : eligiblePowerScene && g_sampleUIData.RealtimeAA==2 && !standaloneDenoisingActive);
        if(enabled==smallPointLighting && usePower==powerSampling)return;
        if(usePower && !powerSampling) {
            previousNeeType=g_sampleUIData.NEEType;
            g_sampleUIData.NEEType=1;
        } else if(!usePower && powerSampling) {
            g_sampleUIData.NEEType=previousNeeType;
        }
        powerSampling=usePower;
        smallPointLighting=enabled;
        donut::log::info("RTXPT exact point sum %s; native light sampler=%d (1=power, 2=NEE-AT)",
            enabled?"ACTIVE":"INACTIVE",g_sampleUIData.NEEType);
    }
    void GetCustomPTPipelineGlobalMacros(std::vector<donut::engine::ShaderMacro>& macros) override {
        AdvancedPathTracer::GetCustomPTPipelineGlobalMacros(macros);
        if(sceneLightingActive()) {
            macros.push_back({"RTR_SCENE_LIGHTING","1"});
            macros.push_back({"RTR_SCENE_EXACT_LIGHT_LIMIT",std::to_string(pathQuality.values[5])});
            if(lightStatsEnabled)macros.push_back({"RTR_SCENE_LIGHT_STATS","1"});
            macros.push_back({"RTR_SCENE_EXACT_INTERFACE_COUNT",std::to_string(exactOpticalInterfaces)});
        }
        const char* mixtureBsdf=std::getenv("RTR_RTXPT_DIAGNOSTIC_MIXTURE_BSDF");
        if(mixtureBsdf && std::string(mixtureBsdf)=="1")
            macros.push_back({"RTR_COMBINED_BSDF_WEIGHT","1"});
        if(useDirectionalStrata()) {
            macros.push_back({"RTR_DIRECTIONAL_STRATA","1"});
            for(auto& macro:macros) {
                if(macro.name=="RTXPT_USE_APPROXIMATE_MIS")macro.definition="0";
            }
        }
        const char* residualControl=std::getenv("RTR_RTXPT_DIAGNOSTIC_DISABLE_RESIDUAL_SAMPLING");
        if(smallPointLighting) {
            macros.push_back({"RTR_EXACT_SMALL_POINTS","1"});
            macros.push_back({"RTR_EXACT_POINT_LIGHT_LIMIT",std::to_string(pathQuality.values[5])});
            if(!residualControl || std::string(residualControl)!="1")
                macros.push_back({"RTR_CONDITIONAL_POINT_SAMPLING","1"});
        }
        const char* misControl=std::getenv("RTR_RTXPT_DIAGNOSTIC_EXACT_MIS");
        const bool approximateMis=misControl && std::string(misControl)=="0";
        const bool exactMis=!approximateMis && (smallPointLighting || (misControl && std::string(misControl)=="1"));
        if(exactMis) {
            for(auto& macro:macros)
                if(macro.name=="RTXPT_USE_APPROXIMATE_MIS")macro.definition="0";
        }
        // Measurement control only: reproduce the pinned general-NEE directional bug.
        const char* directionalControl=std::getenv("RTR_RTXPT_DIAGNOSTIC_DIRECTIONAL_CONTROL");
        if(directionalControl && std::string(directionalControl)=="1")
            macros.push_back({"RTR_ORIGINAL_DIRECTIONAL_POSITION","1"});
    }
    bool Capture(const char* path) {
        if(!m_renderTargets || !m_renderTargets->LdrColor) return false;
        donut::log::info("RTXPT capture: meshInstances=%zu camera=%s",GetScene()->GetSceneGraph()->GetMeshInstances().size(),GetCurrentCameraPosDirUp().c_str());
        return donut::engine::SaveTextureToFile(GetDevice(),GetCommonPasses().get(),
            m_renderTargets->LdrColor,nvrhi::ResourceStates::ShaderResource,path);
    }
};
class HostApp final : public AdvancedSample {
public:
    uint32_t framesInFlight=2;
    RtrPathQuality pathQuality;
    std::unique_ptr<Sample> CreateMainRenderPass(donut::app::DeviceManager& dm,const CommandLineOptions& options) override {
        auto pass=std::make_unique<HostPathTracer>(dm,options);
        pass->ConfigurePathQuality(pathQuality);
        return pass;
    }
    void ConfigureDefaultDeviceParams(donut::app::DeviceCreationParameters& p) const override {
        p.vsyncEnabled = false;
        p.maxFramesInFlight=framesInFlight;
    }
};
struct Session {
    donut::log::Callback previousLog = donut::log::GetCallback();
    std::unique_ptr<HostApp> app;
    std::thread::id owner = std::this_thread::get_id();
    bool initialized = false;
    uint64_t presented = 0;
    bool restoreFrameGenerationBeforeRender = false;
    int requestedFrameGenerationMode = 0;
    int requestedGeneratedFrames = 1;
    sl::Result shutdownResult = sl::Result::eOk;
    nvrhi::TextureHandle lastPresentedTexture;
    SharedFrameProducer sharedFrames;
    bool externalPresentation=false;
    bool gpuTimingsEnabled=true;
    uint64_t rendered=0;
    std::chrono::steady_clock::time_point memoryReport{};
    bool presentationTrace=[] {const char* v=std::getenv("RTR_RTXPT_PRESENTATION_TRACE");return v && std::string(v)=="1";}();
    using ProbeClock=std::chrono::steady_clock;
    ProbeClock::time_point probeMark{},probeReport{};
    std::array<double,4> probeMs{};
    uint64_t probeFrames=0,probePreviousNative=0,probePreviousActual=0;
    void probePhase(size_t phase) {
        if(!presentationTrace)return;
        auto now=ProbeClock::now();
        probeMs[phase]+=std::chrono::duration<double,std::milli>(now-probeMark).count();probeMark=now;
    }
    void reportPresentation() {
        if(!presentationTrace)return;
        auto now=ProbeClock::now();
        if(probeReport.time_since_epoch().count()==0){
            probeReport=now;probeMs={};probeFrames=0;probePreviousNative=presented;
            probePreviousActual=donut::app::StreamlineIntegration::Get().hostFrameGeneration.presented;return;
        }
        ++probeFrames;
        double seconds=std::chrono::duration<double>(now-probeReport).count();
        if(seconds<1)return;
        const auto& t=donut::app::StreamlineIntegration::Get().hostFrameGeneration;
        HWND window=glfwGetWin32Window(app->HostDeviceManager()->GetWindow());
        donut::log::info("RTXPT_FG_TIMELINE seconds=%.6f native=%llu actual=%llu intervalNative=%llu intervalActual=%llu status=%u failures=%llu active=%d requested=%d foreground=%d iconic=%d glfwFocus=%d simStartMs=%.6f eventsAnimateBeginMs=%.6f renderSubmitMs=%.6f presentMs=%.6f gpuTimingEnabled=%d gpuSamples=%llu",
            seconds,(unsigned long long)presented,(unsigned long long)t.presented,
            (unsigned long long)(presented-probePreviousNative),(unsigned long long)(t.presented-probePreviousActual),
            t.status,(unsigned long long)t.failures,int(g_sampleUIData.ActualDLSSFGMode()!=SI::DLSSGMode::eOff),requestedGeneratedFrames,
            int(GetForegroundWindow()==window),int(IsIconic(window)),glfwGetWindowAttrib(app->HostDeviceManager()->GetWindow(),GLFW_FOCUSED),
            probeMs[0]/probeFrames,probeMs[1]/probeFrames,probeMs[2]/probeFrames,probeMs[3]/probeFrames,
            int(gpuTimingsEnabled),(unsigned long long)gpuSampleCount);
        probeReport=now;probePreviousNative=presented;probePreviousActual=t.presented;probeFrames=0;probeMs={};
    }
    JNIEnv* compositorEnv=nullptr;
    jobject compositor=nullptr;
    jlongArray compositorFrame=nullptr;
    jmethodID composeMethod=nullptr;
    struct GpuSample { nvrhi::TimerQueryHandle query; bool pending=false; };
    std::array<GpuSample,8> gpuSamples;
    nvrhi::CommandListHandle timerCommands;
    GpuSample* currentGpuSample=nullptr;
    uint64_t gpuSampleCount=0,gpuMaxNs=0;
    double gpuAverageNs=0;
    void beginGpuSample(uint32_t index) {
        currentGpuSample=nullptr;
        if(!gpuTimingsEnabled)return;
        auto* device=app->HostDeviceManager()->GetDevice();
        for(auto& sample:gpuSamples)if(sample.pending && device->pollTimerQuery(sample.query)) {
            double ns=device->getTimerQueryTime(sample.query)*1e9;
            ++gpuSampleCount;gpuAverageNs=gpuSampleCount==1?ns:gpuAverageNs*.9+ns*.1;
            gpuMaxNs=std::max(gpuMaxNs,uint64_t(ns));sample.pending=false;
        }
        auto& sample=gpuSamples[index%gpuSamples.size()];if(sample.pending)return;
        if(!sample.query)sample.query=device->createTimerQuery();
        if(!timerCommands)timerCommands=device->createCommandList();
        device->resetTimerQuery(sample.query);
        timerCommands->open();timerCommands->beginTimerQuery(sample.query);timerCommands->close();
        device->executeCommandList(timerCommands);currentGpuSample=&sample;
    }
    void endGpuSample() {
        if(!currentGpuSample)return;
        timerCommands->open();timerCommands->endTimerQuery(currentGpuSample->query);timerCommands->close();
        app->HostDeviceManager()->GetDevice()->executeCommandList(timerCommands);
        currentGpuSample->pending=true;currentGpuSample=nullptr;
    }
    std::unordered_map<std::string, donut::engine::SceneGraphNode*> nodes;
    void shutdown() {
        RtrMemoryDiagnostics::Checkpoint("shutdown.begin");
        if (app) {
            auto* dm=app->HostDeviceManager();
            if(auto* device=dm->GetDevice())device->waitForIdle();
            // Streamline ProgrammingGuideManualHooking 3.0 and DLSS-RR 1.0:
            // stop SDK work and release its tags before destroying tagged render targets.
            {
                ShutdownModuleReference retainCallbacks;
                auto& streamline=donut::app::StreamlineIntegration::Get();
                streamline.Shutdown();
                shutdownResult=streamline.HostShutdownResult();
            }
            dm->m_callbacks={};
            lastPresentedTexture=nullptr;timerCommands=nullptr;
            sharedFrames.clear();
            if(compositor)compositorEnv->DeleteGlobalRef(compositor);
            if(compositorFrame)compositorEnv->DeleteGlobalRef(compositorFrame);
            compositor=nullptr;compositorFrame=nullptr;composeMethod=nullptr;compositorEnv=nullptr;
            for(auto& sample:gpuSamples)sample.query=nullptr;
            app->End();
            RtrHostResources::eventPump=nullptr;
        }
        // Never leave the sample's [this] log callback installed during member teardown.
        donut::log::SetCallback(previousLog);
        app.reset();
        RtrMemoryDiagnostics::Checkpoint("shutdown.end");
        // The JVM can exit without flushing the native CRT's redirected streams.
        // Flush once at teardown so final SDK/diagnostic results survive in logs.
        std::fflush(stdout);
        std::fflush(stderr);
    }
    ~Session() { if(app) { try { shutdown(); } catch (const std::exception& x) { donut::log::error("RTXPT shutdown: %s",x.what()); } } }
};
void indexNodes(Session& s) {
    s.nodes.clear();
    auto graph = s.app->HostRenderPass()->GetScene()->GetSceneGraph();
    donut::engine::SceneGraphWalker w(graph->GetRootNode().get());
    while (w) { s.nodes.emplace(w->GetName(), w.Get()); w.Next(true); }
}
std::mutex lifecycle;
Session* active = nullptr; // Upstream globals have one process-wide owner.
std::string utf8(JNIEnv* e, jstring s) {
    if (!s) throw std::invalid_argument("null string");
    const char* p = e->GetStringUTFChars(s, nullptr);
    if (!p) throw std::runtime_error("Cannot access Java string");
    std::string result(p); e->ReleaseStringUTFChars(s, p); return result;
}
Session& session(jlong h) {
    if (!h || reinterpret_cast<Session*>(h) != active) throw std::runtime_error("RTXPT session is closed");
    if (active->owner != std::this_thread::get_id()) throw std::runtime_error("RTXPT must be called on its creating thread");
    return *active;
}
void fail(JNIEnv* e, const std::exception& x) {
    if (!e->ExceptionCheck()) e->ThrowNew(e->FindClass("java/lang/IllegalStateException"), x.what());
}
void configureAgility(const std::filesystem::path& runtime) {
    // Microsoft supports this factory path in retail Java hosts, including cross-volume SDKs.
    // Apply once before Donut/Streamline creates a D3D12 device; keep the factory resident.
    static Microsoft::WRL::ComPtr<ID3D12DeviceFactory> factory;
    static std::filesystem::path selected;
    const auto directory = std::filesystem::canonical(runtime / L"D3D12");
    if (!std::filesystem::is_regular_file(directory / L"D3D12Core.dll"))
        throw std::runtime_error("RTXPT Agility runtime is missing D3D12Core.dll");
    if (factory) {
        if (directory != selected) throw std::runtime_error("An RTXPT Agility runtime is already selected for this process");
        return;
    }
    Microsoft::WRL::ComPtr<ID3D12SDKConfiguration1> configuration;
    auto hr = D3D12GetInterface(CLSID_D3D12SDKConfiguration, IID_PPV_ARGS(&configuration));
    if (FAILED(hr)) throw std::runtime_error("D3D12 SDK configuration is unavailable: " + std::to_string(static_cast<uint32_t>(hr)));
    auto path = directory.string() + "\\";
    hr = configuration->CreateDeviceFactory(RTXPT_D3D_AGILITY_SDK_VERSION, path.c_str(), IID_PPV_ARGS(&factory));
    if (FAILED(hr)) throw std::runtime_error("CreateDeviceFactory failed: " + std::to_string(static_cast<uint32_t>(hr)) + " path=" + path);
    hr=factory->ApplyToGlobalState();
    if(FAILED(hr))throw std::runtime_error("ApplyToGlobalState failed: "+std::to_string(static_cast<uint32_t>(hr)));
    selected = directory;
}
}

extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_prepare(
    JNIEnv* e,jclass,jstring runtime) {
    std::lock_guard lock(lifecycle);
    try {
#if defined(RTXPT_D3D_AGILITY_SDK_VERSION)
        configureAgility(std::filesystem::u8path(utf8(e,runtime)));
#endif
    } catch(const std::exception& x){fail(e,x);}
}

extern "C" JNIEXPORT jlong JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_create(
    JNIEnv* e, jclass, jstring runtime, jstring scene, jint width, jint height, jboolean externalPresentation, jboolean gpuTimingsEnabled,jint maxFramesInFlight,jint workerThreads,jint shaderCompilerThreads,jobjectArray options,jintArray qualityIntegers,jfloatArray qualityConstants) {
    std::lock_guard lock(lifecycle);
    try {
        if (active) throw std::runtime_error("The upstream RTXPT core supports one resident session per process");
        RtrMemoryDiagnostics::Checkpoint("create.begin");
        if (width < 1 || height < 1 || maxFramesInFlight<2 || maxFramesInFlight>3
            ||workerThreads<1||workerThreads>64||shaderCompilerThreads<1||shaderCompilerThreads>16)
            throw std::invalid_argument("Invalid dimensions, frame budget or CPU concurrency");
        if(!qualityIntegers||!qualityConstants||e->GetArrayLength(qualityIntegers)!=21||e->GetArrayLength(qualityConstants)!=8)
            throw std::invalid_argument("Invalid initial path quality payload size");
        RtrPathQuality initialQuality;
        e->GetIntArrayRegion(qualityIntegers,0,21,initialQuality.values.data());
        e->GetFloatArrayRegion(qualityConstants,0,8,initialQuality.constants.data());
        if(e->ExceptionCheck())return 0;
        initialQuality.Validate();
        RtrHostResources::workerThreads=unsigned(workerThreads);
        RtrHostResources::shaderCompilerThreads=unsigned(shaderCompilerThreads);
        auto root = utf8(e, runtime);
        if (_putenv_s("RTR_RTXPT_RUNTIME", root.c_str())) throw std::runtime_error("Cannot configure runtime root");
#if defined(RTXPT_D3D_AGILITY_SDK_VERSION)
        configureAgility(std::filesystem::u8path(root));
#endif
        std::vector<std::string> args {"RTRenderer-RTXPT", "--nonInteractive", "--scene", utf8(e, scene),
            "--width", std::to_string(width), "--height", std::to_string(height), "--overrideToRealtimeMode"};
        // The pinned upstream RR async-prefix path repeatedly loses the device in this host.
        // Use its supported single-graphics-queue scheduler; SR/RR, SHaRC and FG stay enabled.
        // Keep an explicit diagnostic override for investigating upstream scheduling separately.
        if(const char* async=std::getenv("RTR_RTXPT_DIAGNOSTIC_ASYNC_PREFIX")) {
            if(std::string(async)!="0" && std::string(async)!="1")
                throw std::invalid_argument("Diagnostic async prefix must be 0 or 1");
            args.emplace_back(std::string("--asyncPrefix=")+(async[0]=='1'?"true":"false"));
            donut::log::info("RTXPT diagnostic upstream asyncPrefix=%s",async);
        } else args.emplace_back("--asyncPrefix=false");
        for (jsize i=0; options && i<e->GetArrayLength(options); ++i) {
            auto s = static_cast<jstring>(e->GetObjectArrayElement(options, i));
            args.push_back(utf8(e, s)); e->DeleteLocalRef(s);
        }
        std::vector<const char*> argv; for (const auto& a : args) argv.push_back(a.c_str());
        auto s = std::make_unique<Session>();
        s->externalPresentation=externalPresentation;
        s->gpuTimingsEnabled=gpuTimingsEnabled;
        donut::log::ConsoleApplicationMode();
        g_sampleUIData = SampleUIData{};
        s->app = std::make_unique<HostApp>();
        s->app->framesInFlight=uint32_t(maxFramesInFlight);
        s->app->pathQuality=initialQuality;
        s->app->HostDeviceManager()->HostExternalPresentation=externalPresentation;
        s->app->HostDeviceManager()->HostFixedWindowTitle=true;
        RtrHostResources::lastPollNanos=RtrHostResources::maximumPollIntervalNanos=0;
        RtrHostResources::eventPump=[] { RtrHostResources::NoteEventPoll();glfwPollEvents(); };
        auto status = s->app->Init(static_cast<int>(argv.size()), argv.data());
        if(RtrMemoryDiagnostics::Enabled() && status==SampleBaseApp::InitReturnCodes::Success)
            RtrMemoryDiagnostics::Checkpoint("create.initialized",static_cast<ID3D12Device*>(s->app->HostDeviceManager()->GetDevice()->getNativeObject(nvrhi::ObjectTypes::D3D12_Device)));
        if (status != SampleBaseApp::InitReturnCodes::Success)
            throw std::runtime_error("RTXPT initialization failed with status " + std::to_string(static_cast<int>(status)));
        s->initialized = true;
        RtrHostResources::lastPollNanos=RtrHostResources::maximumPollIntervalNanos=0;
        RtrHostResources::eventPump=[] { RtrHostResources::NoteEventPoll();glfwPollEvents(); };
        s->app->HostDeviceManager()->HostExternalPresentation=externalPresentation;
        if(externalPresentation)glfwHideWindow(s->app->HostDeviceManager()->GetWindow());
        donut::app::StreamlineIntegration::Get().hostFrameGeneration={};
        if(!s->app->HostRenderPass()->GetScene()) throw std::runtime_error("RTXPT scene failed to load");
        static_cast<HostPathTracer*>(s->app->HostRenderPass())->LoadSceneLighting(utf8(e,scene));
        auto& afterPresent=s->app->HostDeviceManager()->m_callbacks.afterPresent;
        auto upstreamAfterPresent=afterPresent;
        afterPresent = [ptr=s.get(),upstreamAfterPresent](auto& dm, uint32_t index) {
            if(upstreamAfterPresent)upstreamAfterPresent(dm,index);
            if(!ptr->externalPresentation)++ptr->presented;
            ptr->probePhase(3);
        };
        auto upstreamBeforeFrame=s->app->HostDeviceManager()->m_callbacks.beforeFrame;
        s->app->HostDeviceManager()->m_callbacks.beforeFrame=[ptr=s.get(),upstreamBeforeFrame](auto& dm,uint32_t index) {
            ptr->probePhase(0);if(upstreamBeforeFrame)upstreamBeforeFrame(dm,index);
        };
        auto& beforeRender=s->app->HostDeviceManager()->m_callbacks.beforeRender;
        auto upstreamBeforeRender=beforeRender;
        beforeRender=[ptr=s.get(),upstreamBeforeRender](auto& dm,uint32_t index) {
            ptr->probePhase(1);
            if(ptr->restoreFrameGenerationBeforeRender) {
                g_sampleUIData.DLSSFGMode=ptr->requestedFrameGenerationMode
                    ? SI::DLSSGMode::eOn : SI::DLSSGMode::eOff;
                g_sampleUIData.DLSSFGNumFramesToGenerate=ptr->requestedGeneratedFrames;
                ptr->restoreFrameGenerationBeforeRender=false;
            }
            if(upstreamBeforeRender)upstreamBeforeRender(dm,index);
            ptr->beginGpuSample(index);
        };
        auto& afterRender=s->app->HostDeviceManager()->m_callbacks.afterRender;
        auto upstreamAfterRender=afterRender;
        afterRender=[ptr=s.get(),upstreamAfterRender](auto& dm,uint32_t index) {
            if(upstreamAfterRender)upstreamAfterRender(dm,index);
            ptr->endGpuSample();ptr->lastPresentedTexture=dm.GetCurrentBackBuffer();
            ptr->probePhase(2);
            if(ptr->externalPresentation)ptr->sharedFrames.publish(dm.GetDevice(),
                static_cast<HostPathTracer*>(ptr->app->HostRenderPass())->HostDisplayColor(),
                static_cast<HostPathTracer*>(ptr->app->HostRenderPass())->HostTransparentBackground());
            else if(ptr->compositor) {
                // Keep RTXPT's upstream depth/MV/PreUIColor tags and its DXGI FG/Reflex path.
                // Only the final UI image crosses APIs, after all render passes and before Present.
                ptr->sharedFrames.publish(dm.GetDevice(),dm.GetCurrentBackBuffer(),static_cast<HostPathTracer*>(ptr->app->HostRenderPass())->HostTransparentBackground());
                const auto values=ptr->sharedFrames.descriptor(ptr->rendered);
                auto* env=ptr->compositorEnv;
                env->SetLongArrayRegion(ptr->compositorFrame,0,jsize(values.size()),reinterpret_cast<const jlong*>(values.data()));
                if(!env->ExceptionCheck())env->CallVoidMethod(ptr->compositor,ptr->composeMethod,ptr->compositorFrame);
                if(env->ExceptionCheck())throw std::runtime_error("Vulkan UI composition callback failed");
                ptr->sharedFrames.copyBack(dm.GetDevice(),dm.GetCurrentBackBuffer());
            }
            ++ptr->rendered;
        };
        indexNodes(*s);
        s->app->HostDeviceManager()->SetWindowTitle("RTRendererAPI | RTXPT (D3D12)");
        active = s.release(); return reinterpret_cast<jlong>(active);
    } catch (const std::exception& x) { fail(e, x); return 0; }
}

extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_overlay(
    JNIEnv* e,jclass,jlong h,jstring text) {
    try { session(h);g_sampleUIData.HostOverlayText=utf8(e,text); }
    catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_compositor(
    JNIEnv* e,jclass,jlong h,jobject callback) {
    try {
        auto& s=session(h);
        if(s.externalPresentation)throw std::runtime_error("A native-present compositor requires RTXPT presentation");
        jobject next=nullptr;jlongArray frame=nullptr;jmethodID method=nullptr;
        if(callback) {
            auto type=e->GetObjectClass(callback);method=e->GetMethodID(type,"compose","([J)V");e->DeleteLocalRef(type);
            if(!method)return;
            next=e->NewGlobalRef(callback);
            auto local=e->NewLongArray(11);
            if(local){frame=static_cast<jlongArray>(e->NewGlobalRef(local));e->DeleteLocalRef(local);}
            if(!next||!frame){if(next)e->DeleteGlobalRef(next);if(frame)e->DeleteGlobalRef(frame);return;}
        }
        if(s.compositor)e->DeleteGlobalRef(s.compositor);
        if(s.compositorFrame)e->DeleteGlobalRef(s.compositorFrame);
        s.compositorEnv=e;s.compositor=next;s.compositorFrame=frame;s.composeMethod=method;
    }catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_title(
    JNIEnv* e,jclass,jlong h,jstring title) {
    try{auto text=utf8(e,title);session(h).app->HostDeviceManager()->SetWindowTitle(text.c_str());}
    catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT jlongArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_sharedFrame(
    JNIEnv* e,jclass,jlong h,jlong sequence) {
    try {
        auto& s=session(h);
        if(!s.externalPresentation)throw std::runtime_error("This RTXPT session owns native window presentation");
        const auto values=s.sharedFrames.descriptor(sequence);
        auto result=e->NewLongArray(jsize(values.size()));
        if(result)e->SetLongArrayRegion(result,0,jsize(values.size()),reinterpret_cast<const jlong*>(values.data()));
        return result;
    }catch(const std::exception& x){fail(e,x);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_retireSharedFrames(
    JNIEnv* e,jclass,jlong h) {
    try{auto& s=session(h);s.sharedFrames.retire(s.app->HostDeviceManager()->GetDevice());}
    catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_resetHistory(
    JNIEnv* e,jclass,jlong h) {
    try{session(h);g_sampleUIData.ResetAccumulation=true;g_sampleUIData.ResetRealtimeCaches=true;}
    catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_overlayChoices(
    JNIEnv* e,jclass,jlong h,jobjectArray labels,jint selected) {
    try {
        session(h);
        if(!labels)throw std::invalid_argument("Overlay labels must not be null");
        const auto count=e->GetArrayLength(labels);
        if(count>64 || (count==0?selected!=-1:selected<0||selected>=count))
            throw std::invalid_argument("Invalid overlay selection");
        std::vector<std::string> choices;
        for(jsize i=0;i<count;i++) {
            auto label=static_cast<jstring>(e->GetObjectArrayElement(labels,i));
            choices.push_back(utf8(e,label));e->DeleteLocalRef(label);
        }
        g_sampleUIData.HostOverlayChoices=std::move(choices);
        g_sampleUIData.HostOverlayChoice=selected;
    }catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT jint JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_overlaySelection(
    JNIEnv* e,jclass,jlong h) {
    try{session(h);return g_sampleUIData.HostOverlayChoice;}
    catch(const std::exception& x){fail(e,x);return -1;}
}
extern "C" JNIEXPORT jlongArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_gpuTiming(
    JNIEnv* e,jclass,jlong h) {
    try { auto& s=session(h);jlong values[]={jlong(s.gpuSampleCount),jlong(s.gpuAverageNs),jlong(s.gpuMaxNs)};
        auto result=e->NewLongArray(3);if(result)e->SetLongArrayRegion(result,0,3,values);return result;
    }catch(const std::exception& x){fail(e,x);return nullptr;}
}
extern "C" JNIEXPORT jlongArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_generationTelemetry(
    JNIEnv* e,jclass,jlong h) {
    try {auto& s=session(h);const auto& t=donut::app::StreamlineIntegration::Get().hostFrameGeneration;
        jlong values[]={jlong(s.presented),jlong(t.presented),jlong(t.queries),jlong(t.samples),jlong(t.failures),
            jlong(t.lastPresented),jlong(t.status),jlong(t.querySucceeded),jlong(t.maximumGenerated)};
        auto result=e->NewLongArray(9);if(result)e->SetLongArrayRegion(result,0,9,values);return result;
    }catch(const std::exception& x){fail(e,x);return nullptr;}
}
extern "C" JNIEXPORT jdoubleArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_presentationStatus(
    JNIEnv* e,jclass,jlong h) {
    try {auto size=session(h).app->HostRenderPass()->GetDisplaySize();
        jdouble values[]={double(size.x),double(size.y),g_sampleUIData.ToneMappingParams.exposureCompensation,
            g_sampleUIData.ToneMappingParams.exposureValue,double(g_sampleUIData.EnableBloom),double(g_sampleUIData.EnableToneMapping),
            g_sampleUIData.BloomRadius,g_sampleUIData.BloomIntensity};
        auto result=e->NewDoubleArray(8);if(result)e->SetDoubleArrayRegion(result,0,8,values);return result;
    }catch(const std::exception& x){fail(e,x);return nullptr;}
}

extern "C" JNIEXPORT jint JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_frame(
    JNIEnv* e, jclass, jlong h) {
    try {
        auto& s = session(h); auto dm = s.app->HostDeviceManager();
        if (glfwWindowShouldClose(dm->GetWindow())) return 0;
        static_cast<HostPathTracer*>(s.app->HostRenderPass())->UpdateLightingPolicy();
        auto before=s.externalPresentation?s.rendered:s.presented;
        if(s.presentationTrace)s.probeMark=Session::ProbeClock::now();
        if (!dm->HostFrame()) throw std::runtime_error("RTXPT render/present failed");
        if(RtrMemoryDiagnostics::Enabled()) {
            auto now=std::chrono::steady_clock::now();
            if(s.rendered<=3 || now-s.memoryReport>=std::chrono::seconds(1)) {
                RtrMemoryDiagnostics::Checkpoint(s.rendered<=3?"frame.startup":"frame.steady",
                    static_cast<ID3D12Device*>(dm->GetDevice()->getNativeObject(nvrhi::ObjectTypes::D3D12_Device)));
                s.memoryReport=now;
            }
        }
        s.reportPresentation();
        return (s.externalPresentation?s.rendered:s.presented)>before ? 2 : 1;
    } catch (const std::exception& x) { fail(e, x); return 0; }
}

extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_exposureCompensation(
    JNIEnv* e,jclass,jlong h,jfloat stops) {
    try {
        session(h);
        if(!std::isfinite(stops))throw std::invalid_argument("Exposure compensation must be finite");
        g_sampleUIData.ToneMappingParams.exposureCompensation=stops;
    } catch(const std::exception& x){fail(e,x);}
}

extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_awaitIdle(
    JNIEnv* e, jclass, jlong h) {
    try { session(h).app->HostDeviceManager()->GetDevice()->waitForIdle(); }
    catch(const std::exception& x) { fail(e,x); }
}
extern "C" JNIEXPORT jlongArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_lightSamplingTelemetry(JNIEnv* e,jclass,jlong h) {
    try {auto values=static_cast<HostPathTracer*>(session(h).app->HostRenderPass())->LightSamplingTelemetry();
        auto result=e->NewLongArray(8);if(result)e->SetLongArrayRegion(result,0,8,values.data());return result;
    }catch(const std::exception& x){fail(e,x);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_captureLinear(JNIEnv* e,jclass,jlong h,jstring path) {
    try {static_cast<HostPathTracer*>(session(h).app->HostRenderPass())->CaptureLinear(std::filesystem::u8path(utf8(e,path)));}
    catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_windowState(JNIEnv* e,jclass,jlong h,jint state) {
    try {auto window=session(h).app->HostDeviceManager()->GetWindow();
        if(state==1)glfwMaximizeWindow(window);else if(state==2)glfwIconifyWindow(window);else glfwRestoreWindow(window);
        glfwPollEvents();
    }catch(const std::exception& x){fail(e,x);}
}

extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_pathQuality(
    JNIEnv* e,jclass,jlong h,jintArray integers,jfloatArray constants) {
    try {
        if(!integers||!constants||e->GetArrayLength(integers)!=21||e->GetArrayLength(constants)!=8)
            throw std::invalid_argument("Invalid path quality payload size");
        RtrPathQuality quality;
        e->GetIntArrayRegion(integers,0,21,quality.values.data());
        e->GetFloatArrayRegion(constants,0,8,quality.constants.data());
        if(e->ExceptionCheck())return;
        quality.Validate();
        if(quality.values[0]==1&&(g_sampleUIData.ActualUseRTXDIPasses()||g_sampleUIData.ActualRadianceCacheMode()!=0))
            throw std::invalid_argument("Scene analytic lighting cannot use native ReSTIR/cache transport");
        static_cast<HostPathTracer*>(session(h).app->HostRenderPass())->ConfigurePathQuality(quality);
    }catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT jdoubleArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_pathQualityStatus(
    JNIEnv* e,jclass,jlong h) {
    try {
        auto& s=session(h);auto* host=static_cast<HostPathTracer*>(s.app->HostRenderPass());
        auto q=host->pathQuality;
        q.values[2]=g_sampleUIData.NEEFullSamples;q.values[3]=g_sampleUIData.NEECandidateSamples;
        q.values[4]=g_sampleUIData.NEEAT_QualityPreset;q.constants[0]=g_sampleUIData.NEEAT_LocalToGlobalSampleRatio;
        q.values[12]=g_sampleUIData.DenoisingLayersActiveCount;q.values[13]=g_sampleUIData.DenoisingLayersMaxVertexDepth;
        q.values[14]=g_sampleUIData.NestedDielectricsQuality;q.values[15]=g_sampleUIData.RealtimeSubSamplingMode;
        q.values[16]=g_sampleUIData.UseFp16Types;q.constants[2]=g_sampleUIData.TexLODBias;
        auto* cache=host->GetSharc();
        if(cache) {
            const auto& c=cache->GetSettings();q.values[8]=c.DownScaleFactor;q.values[9]=c.BounceCount;
            q.values[10]=c.DiffuseBounceCount;q.values[11]=c.NEEFullSamples;q.values[17]=c.AccumulationFrameNum;
            q.values[18]=c.StaleFrameFrameNum;q.constants[1]=c.TerminateThreshold;
            q.constants[3]=c.AdditionalTexLODBias;q.constants[4]=c.SceneScale;q.constants[5]=c.MinRoughnessOverride;
        }
        jdouble values[36]{};
        for(size_t i=0;i<19;++i)values[i]=q.values[i];
        for(size_t i=0;i<6;++i)values[19+i]=q.constants[i];
        values[25]=g_sampleUIData.NEEType;values[26]=cache&&cache->GetHashEntriesBuffer()?cache->GetEntryCount():0;values[27]=s.app->framesInFlight;
        values[28]=host->HostWorkerThreadCount();values[29]=host->HostShaderCompilerThreads();
        values[30]=host->ExactPointLightingActive();values[31]=host->DirectionalStrataActive();
        values[32]=g_sampleUIData.EnableBloom;values[33]=g_sampleUIData.EnableToneMapping;
        values[34]=g_sampleUIData.BloomRadius;values[35]=g_sampleUIData.BloomIntensity;
        auto output=e->NewDoubleArray(36);if(output)e->SetDoubleArrayRegion(output,0,36,values);return output;
    }catch(const std::exception& x){fail(e,x);return nullptr;}
}
extern "C" JNIEXPORT jintArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_runtimeStatus(
    JNIEnv* e, jclass, jlong h) {
    try {
        auto size=session(h).app->HostRenderPass()->GetRenderSize();
        const auto mode=g_sampleUIData.ActualDLSSFGMode();
        jint values[]={jint(size.x),jint(size.y),g_sampleUIData.RealtimeAA,
            jint(g_sampleUIData.ActualUseStandaloneDenoiser()),jint(g_sampleUIData.RealtimeAA==3||g_sampleUIData.ActualUseStandaloneDenoiser()),
            jint(g_sampleUIData.ActualUseReSTIRDI()),jint(g_sampleUIData.ActualUseReSTIRGI()),g_sampleUIData.ActualRadianceCacheMode(),
            jint(mode),jint(g_sampleUIData.DXHitObjectExtension||g_sampleUIData.NVAPIHitObjectExtension),
            jint(g_sampleUIData.ActualReflexMode()!=SI::ReflexMode::eOff),jint(g_sampleUIData.DLSSMode),
            jint(g_sampleUIData.DLSSFGNumFramesToGenerate),jint(g_sampleUIData.DLSSFGMaxNumFramesToGenerate),
            jint(g_sampleUIData.IsDLSSSuported),jint(g_sampleUIData.IsDLSSRRSupported),
            jint(g_sampleUIData.IsDLSSFGSupported),jint(g_sampleUIData.IsReflexSupported),jint(session(h).app->IsSERSupported()),
            jint(g_sampleUIData.ActualUseStandaloneDenoiser()&&g_sampleUIData.NRDMethod==NrdConfig::DenoiserMethod::RELAX),
            jint(static_cast<HostPathTracer*>(session(h).app->HostRenderPass())->exactOpticalInterfaces),
            jint(static_cast<HostPathTracer*>(session(h).app->HostRenderPass())->SceneLightingActive())};
        auto result=e->NewIntArray(22); if(result)e->SetIntArrayRegion(result,0,22,values);return result;
    }catch(const std::exception& x){fail(e,x);return nullptr;}
}

extern "C" JNIEXPORT jdoubleArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_cameraState(
    JNIEnv* e,jclass,jlong h) {
    try {
        auto& s=session(h);
        const auto* sample=static_cast<HostPathTracer*>(s.app->HostRenderPass());
        const auto& camera=sample->GetCurrentCamera();
        const auto p=camera.GetPosition(),d=camera.GetDir(),u=camera.GetUp();
        int width=0,height=0;glfwGetFramebufferSize(s.app->HostDeviceManager()->GetWindow(),&width,&height);
        const jdouble values[]={p.x,p.y,p.z,d.x,d.y,d.z,u.x,u.y,u.z,sample->GetCameraVerticalFOV(),
            double(std::max(width,1)),double(std::max(height,1))};
        auto result=e->NewDoubleArray(12);
        if(result)e->SetDoubleArrayRegion(result,0,12,values);
        return result;
    } catch(const std::exception& x){fail(e,x);return nullptr;}
}

extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_camera(
    JNIEnv* e, jclass, jlong h, jstring pose, jfloat verticalFov) {
    try {
        auto sample = session(h).app->HostRenderPass();
        sample->SelectedCameraIndex()=0;
        if (!sample->SetCurrentCameraPosDirUp(utf8(e, pose))) throw std::invalid_argument("Invalid camera pose");
        sample->SetCameraVerticalFOV(verticalFov);
        g_sampleUIData.ResetAccumulation=true;
    } catch (const std::exception& x) { fail(e, x); }
}

extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_loadScene(
    JNIEnv* e, jclass, jlong h, jstring path) {
    try {
        auto& s=session(h);
        if(g_sampleUIData.ActualDLSSFGMode()!=SI::DLSSGMode::eOff) {
            auto options=donut::app::StreamlineInterface::DLSSGOptions{};
            options.mode=SI::DLSSGMode::eOff;
            s.app->HostDeviceManager()->GetStreamline().SetDLSSGOptions(options);
            g_sampleUIData.DLSSFGMode=SI::DLSSGMode::eOff;
            s.restoreFrameGenerationBeforeRender=true;
        }
        const auto scenePath=utf8(e,path);
        s.app->HostRenderPass()->SetCurrentScene(scenePath, true);
        static_cast<HostPathTracer*>(s.app->HostRenderPass())->LoadSceneLighting(scenePath);indexNodes(s);
    }
    catch (const std::exception& x) { fail(e, x); }
}

extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_lighting(
    JNIEnv* e,jclass,jlong h,jlongArray ids,jfloatArray records,jfloatArray sky) {
    try {
        auto& s=session(h);
        if(!ids||!records||!sky)throw std::invalid_argument("Lighting arrays must not be null");
        const size_t count=e->GetArrayLength(ids);
        if(count==0||count>size_t(NEEAT_MAX_LIGHTS)||e->GetArrayLength(records)!=count*16||e->GetArrayLength(sky)!=4)
            throw std::invalid_argument("Invalid complete lighting array sizes");
        std::vector<int64_t> lightIds(count);std::vector<float> values(count*16);std::array<float,4> background{};
        e->GetLongArrayRegion(ids,0,jsize(count),reinterpret_cast<jlong*>(lightIds.data()));
        e->GetFloatArrayRegion(records,0,jsize(values.size()),values.data());e->GetFloatArrayRegion(sky,0,4,background.data());
        if(e->ExceptionCheck())return;
        auto graph=s.app->HostRenderPass()->GetScene()->GetSceneGraph();
        std::vector<std::string> oldNames;for(auto& light:graph->GetLights())if(light->GetLightType()!=LightType_Environment)oldNames.push_back(light->GetNode()->GetName());
        static_cast<HostPathTracer*>(s.app->HostRenderPass())->ApplyLighting(lightIds,values,background);
        for(auto& name:oldNames)s.nodes.erase(name);
        for(auto& light:graph->GetLights())if(light->GetLightType()!=LightType_Environment)s.nodes.insert_or_assign(light->GetNode()->GetName(),light->GetNode());
    }catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT jlongArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_lightingTelemetry(
    JNIEnv* e,jclass,jlong h) {
    try {
        auto values=static_cast<HostPathTracer*>(session(h).app->HostRenderPass())->LightingTelemetry();
        auto result=e->NewLongArray(jsize(values.size()));if(result)e->SetLongArrayRegion(result,0,jsize(values.size()),reinterpret_cast<const jlong*>(values.data()));return result;
    }catch(const std::exception& x){fail(e,x);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_environment(
    JNIEnv* e,jclass,jlong h,jfloatArray sun,jfloatArray sky) {
    try{if(!sun||!sky||e->GetArrayLength(sun)!=16||e->GetArrayLength(sky)!=4)throw std::invalid_argument("Invalid environment arrays");
        std::array<float,16> values;std::array<float,4> background;
        e->GetFloatArrayRegion(sun,0,16,values.data());e->GetFloatArrayRegion(sky,0,4,background.data());if(e->ExceptionCheck())return;
        static_cast<HostPathTracer*>(session(h).app->HostRenderPass())->ApplyEnvironment(values,background);
    }catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_lightingPolicy(JNIEnv* e,jclass,jlong h,jint policy){
    try{static_cast<HostPathTracer*>(session(h).app->HostRenderPass())->SetLightingContinuity(policy);}catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_background(JNIEnv* e,jclass,jlong h,jfloatArray color,jboolean transparent){
    try{std::array<float,3> v{};if(color){if(e->GetArrayLength(color)!=3)throw std::invalid_argument("Invalid background");e->GetFloatArrayRegion(color,0,3,v.data());if(e->ExceptionCheck())return;for(float c:v)if(!std::isfinite(c)||c<0)throw std::invalid_argument("Invalid background color");}
        static_cast<HostPathTracer*>(session(h).app->HostRenderPass())->SetBackground(color!=nullptr,float3(v[0],v[1],v[2]),transparent);}catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_lightingDelta(JNIEnv* e,jclass,jlong h,jlongArray ids,jfloatArray records){
    try{if(!ids||!records||e->GetArrayLength(records)!=e->GetArrayLength(ids)*16)throw std::invalid_argument("Invalid light delta arrays");
        size_t count=e->GetArrayLength(ids);std::vector<int64_t> keys(count);std::vector<float> values(count*16);e->GetLongArrayRegion(ids,0,jsize(count),reinterpret_cast<jlong*>(keys.data()));e->GetFloatArrayRegion(records,0,jsize(values.size()),values.data());if(e->ExceptionCheck())return;
        static_cast<HostPathTracer*>(session(h).app->HostRenderPass())->ApplyLightDelta(keys,values);}catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_resetWindowPollInterval(JNIEnv* e,jclass,jlong h){
    try{session(h);RtrHostResources::maximumPollIntervalNanos=0;RtrHostResources::NoteEventPoll();RtrHostResources::maximumPollIntervalNanos=0;}catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT jlongArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_executionTelemetry(JNIEnv* e,jclass,jlong h){
    try{auto v=static_cast<HostPathTracer*>(session(h).app->HostRenderPass())->ExecutionTelemetry();auto out=e->NewLongArray(jsize(v.size()));if(out)e->SetLongArrayRegion(out,0,jsize(v.size()),v.data());return out;}catch(const std::exception& x){fail(e,x);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_resources(
    JNIEnv* e,jclass,jlong h,jstring manifest) {
    try{auto& s=session(h);static_cast<HostPathTracer*>(s.app->HostRenderPass())->ApplyResources(std::filesystem::u8path(utf8(e,manifest)));indexNodes(s);}
    catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT jlongArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_resourceTelemetry(
    JNIEnv* e,jclass,jlong h) {
    try{auto* p=static_cast<HostPathTracer*>(session(h).app->HostRenderPass());
        auto t=p->ResourceTelemetry();
        auto result=e->NewLongArray(jsize(t.size()));if(result)e->SetLongArrayRegion(result,0,jsize(t.size()),t.data());return result;
    }catch(const std::exception& x){fail(e,x);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_snapshotMaterials(
    JNIEnv* e, jclass, jlong h, jstring directory) {
    try {
        auto path=std::filesystem::u8path(utf8(e,directory));
        std::filesystem::create_directories(path);
        for(auto& material:session(h).app->HostRenderPass()->GetScene()->GetSceneGraph()->GetMaterials()) {
            auto* m=PTMaterial::SafeCast(material);
            Json::Value json;m->Write(json);
            auto name=m->ModelName+"."+m->Name+".material.json";
            if(std::filesystem::path(name).filename()!=name)throw std::runtime_error("Invalid diagnostic material name");
            if(!SaveJsonToFile(path/name,json))throw std::runtime_error("Failed to snapshot native material: "+name);
        }
    }catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_capture(
    JNIEnv* e, jclass, jlong h, jstring path) {
    try {
        auto& s=session(h);
        const auto output=utf8(e,path);
        bool saved=false;
        if(s.compositor&&s.sharedFrames.latestTexture())
            saved=donut::engine::SaveTextureToFile(s.app->HostDeviceManager()->GetDevice(),
                s.app->HostRenderPass()->GetCommonPasses().get(),s.sharedFrames.latestTexture(),nvrhi::ResourceStates::Common,output.c_str());
        else if(!g_sampleUIData.HostOverlayText.empty() && s.lastPresentedTexture)
            saved=donut::engine::SaveTextureToFile(s.app->HostDeviceManager()->GetDevice(),
                s.app->HostRenderPass()->GetCommonPasses().get(),s.lastPresentedTexture,nvrhi::ResourceStates::Present,output.c_str());
        else saved=static_cast<HostPathTracer*>(s.app->HostRenderPass())->Capture(output.c_str());
        if(!saved)
            throw std::runtime_error("RTXPT capture failed");
    } catch(const std::exception& x) { fail(e,x); }
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_captureSource(
    JNIEnv* e,jclass,jlong h,jstring path) {
    try{auto& s=session(h);auto output=utf8(e,path);
        if(!static_cast<HostPathTracer*>(s.app->HostRenderPass())->Capture(output.c_str()))throw std::runtime_error("RTXPT source capture failed");}
    catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_settings(
    JNIEnv* e, jclass, jlong h, jint depth, jint diffuse, jboolean reflex,
    jint frameGenerationMode, jint generatedFrames, jint samplesPerPixel, jint realtimeAa,
    jint dlssMode, jboolean standaloneDenoiser, jboolean relaxDenoiser, jboolean restirDi, jboolean restirGi, jboolean sharc,jint exactOpticalInterfaces) {
    try {
        auto& s=session(h);
        if(frameGenerationMode<0 || frameGenerationMode>1 || generatedFrames<1 || generatedFrames>3)
            throw std::invalid_argument("Invalid fixed DLSS frame-generation configuration");
        if(depth<1 || depth>64 || diffuse<0 || diffuse>depth || samplesPerPixel<1 || samplesPerPixel>64
            || realtimeAa<0 || realtimeAa>3 || dlssMode<1 || dlssMode>6 || exactOpticalInterfaces<1 || exactOpticalInterfaces>6)
            throw std::invalid_argument("Invalid RTXPT render settings");
        s.requestedFrameGenerationMode=frameGenerationMode;
        s.requestedGeneratedFrames=generatedFrames;
        g_sampleUIData.RealtimeMode=true;
        if(const char* exposure=std::getenv("RTR_RTXPT_DIAGNOSTIC_SR_EXPOSURE")) {
            const float value=std::stof(exposure);
            if(!std::isfinite(value) || value<=0.f)
                throw std::invalid_argument("Diagnostic SR exposure must be finite and positive");
            g_sampleUIData.HostDLSSFixedExposure=value;
        }
        if(const char* bloom=std::getenv("RTR_RTXPT_DIAGNOSTIC_DISABLE_BLOOM"); bloom && std::string(bloom)=="1")
            g_sampleUIData.EnableBloom=false;
        if(const char* preset=std::getenv("RTR_RTXPT_DIAGNOSTIC_SR_PRESET")) {
            const std::string selected(preset);
            if(selected=="J")g_sampleUIData.HostDLSSSRPreset=SI::DLSSPreset::ePresetJ;
            else if(selected=="K")g_sampleUIData.HostDLSSSRPreset=SI::DLSSPreset::ePresetK;
            // SL 2.14.16 adds M; Donut's enum predates it but forwards the enum value unchanged.
            else if(selected=="M")g_sampleUIData.HostDLSSSRPreset=static_cast<SI::DLSSPreset>(13);
            else throw std::invalid_argument("Diagnostic SR preset must be J, K or M");
            donut::log::info("RTXPT diagnostic upstream DLSS-SR preset=%s",preset);
        }
        // Explicit measurement-only model selection; normal callers retain upstream preset F.
        if(const char* preset=std::getenv("RTR_RTXPT_DIAGNOSTIC_RR_PRESET")) {
            const std::string selected(preset);
            if(selected=="D") g_sampleUIData.DLSRRPreset=SI::DLSSRRPreset::ePresetD;
            else if(selected=="E") g_sampleUIData.DLSRRPreset=SI::DLSSRRPreset::ePresetE;
            else if(selected=="F") g_sampleUIData.DLSRRPreset=SI::DLSSRRPreset::ePresetF;
            else throw std::invalid_argument("Diagnostic RR preset must be D, E or F");
            donut::log::info("RTXPT diagnostic upstream DLSS-RR preset=%s",preset);
        }
        g_sampleUIData.RealtimeSamplesPerPixel=samplesPerPixel;
        g_sampleUIData.RealtimeAA=realtimeAa;
        g_sampleUIData.StandaloneDenoiser=standaloneDenoiser;
        // Method changes require a host rebuild (RtxptFeatureController), so no per-frame UI
        // NRDModeChanged latch is needed; leaving it set would rebuild the denoiser every frame.
        g_sampleUIData.NRDMethod=relaxDenoiser?NrdConfig::DenoiserMethod::RELAX:NrdConfig::DenoiserMethod::REBLUR;
        if(const char* candidate=std::getenv("RTR_RTXPT_DIAGNOSTIC_SMALL_POINTS"); candidate && std::string(candidate)=="1") {
            if(standaloneDenoiser || realtimeAa!=2 || restirDi || restirGi || sharc || samplesPerPixel!=1)
                throw std::invalid_argument("Small-point diagnostic requires the unchanged raw 1-SPP SR policy");
            donut::log::info("RTXPT diagnostic: native power sampling + exact small delta-light sum; no denoiser added");
        }
        g_sampleUIData.UseReSTIRDI=restirDi;
        g_sampleUIData.UseReSTIRGI=restirGi;
        g_sampleUIData.RadianceCacheMode=sharc?1:0;
        g_sampleUIData.DLSSMode=static_cast<SI::DLSSMode>(dlssMode);
        g_sampleUIData.DLSSLastRealtimeAA=realtimeAa;
        g_sampleUIData.BounceCount=depth;
        g_sampleUIData.DiffuseBounceCount=diffuse;
        static_cast<HostPathTracer*>(s.app->HostRenderPass())->exactOpticalInterfaces=exactOpticalInterfaces;
        g_sampleUIData.ResetAccumulation=true;
        g_sampleUIData.ShowUI=false;
        g_sampleUIData.RenderWhenOutOfFocus=true;
        g_sampleUIData.EnableVsync=false;
        g_sampleUIData.FPSLimiter=0;
        g_sampleUIData.ReflexMode=reflex ? SI::ReflexMode::eLowLatency : SI::ReflexMode::eOff;
        if(!s.restoreFrameGenerationBeforeRender)
            g_sampleUIData.DLSSFGMode=frameGenerationMode?SI::DLSSGMode::eOn:SI::DLSSGMode::eOff;
        g_sampleUIData.DLSSFGNumFramesToGenerate=generatedFrames;
    } catch (const std::exception& x) { fail(e,x); }
}

extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_interiors(
    JNIEnv* e, jclass, jlong h, jobjectArray names, jobject values) {
    try {
        auto& s=session(h); auto* data=static_cast<float*>(e->GetDirectBufferAddress(values));
        const auto count=e->GetArrayLength(names);
        if(!data || e->GetDirectBufferCapacity(values)<jlong(count)*5) throw std::invalid_argument("Interior buffer too small");
        std::unordered_map<std::string, PTMaterial*> materials;
        for(auto& m : s.app->HostRenderPass()->GetScene()->GetSceneGraph()->GetMaterials())
            materials.emplace(m->name, PTMaterial::SafeCast(m));
        std::vector<PTMaterial*> checked;
        for(jsize i=0;i<count;++i) {
            auto n=static_cast<jstring>(e->GetObjectArrayElement(names,i));
            auto name=utf8(e,n); e->DeleteLocalRef(n);
            auto it=materials.find(name); if(it==materials.end())throw std::runtime_error("Missing imported material: "+name);
            checked.push_back(it->second);
        }
        for(jsize i=0;i<count;++i) {
            auto& m=*checked[i]; const auto p=data+i*5;
            m.VolumeAttenuationColor=donut::math::float3(std::exp(-p[0]),std::exp(-p[1]),std::exp(-p[2]));
            m.VolumeAttenuationDistance=1.0f;
            m.IoR=p[3]; m.NestedPriority=static_cast<int>(p[4]); m.GPUDataDirty=true;
        }
    } catch (const std::exception& x) { fail(e,x); }
}

extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_materialIors(
    JNIEnv* e, jclass, jlong h, jobjectArray names, jobject values) {
    try {
        auto& s=session(h); auto* data=static_cast<float*>(e->GetDirectBufferAddress(values));
        const auto count=e->GetArrayLength(names);
        if(!data || e->GetDirectBufferCapacity(values)<count)throw std::invalid_argument("Material IOR buffer too small");
        std::unordered_map<std::string, PTMaterial*> materials;
        for(auto& material:s.app->HostRenderPass()->GetScene()->GetSceneGraph()->GetMaterials())
            materials.emplace(material->name,PTMaterial::SafeCast(material));
        std::vector<PTMaterial*> checked;
        for(jsize i=0;i<count;++i) {
            auto n=static_cast<jstring>(e->GetObjectArrayElement(names,i));
            auto name=utf8(e,n);e->DeleteLocalRef(n);
            auto found=materials.find(name);
            if(found==materials.end())throw std::runtime_error("Missing imported material: "+name);
            if(!std::isfinite(data[i]) || data[i]<1.f)throw std::invalid_argument("Invalid material IOR");
            checked.push_back(found->second);
        }
        for(jsize i=0;i<count;++i) {
            checked[i]->IoR=data[i];checked[i]->GPUDataDirty=true;
        }
    } catch(const std::exception& x) { fail(e,x); }
}

extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_transforms(
    JNIEnv* e, jclass, jlong h, jobjectArray names, jobject values) {
    try {
        auto& s=session(h); auto* data=static_cast<float*>(e->GetDirectBufferAddress(values));
        auto count=e->GetArrayLength(names);
        if(!data || e->GetDirectBufferCapacity(values)<jlong(count)*12)throw std::invalid_argument("Transform buffer too small");
        std::vector<donut::engine::SceneGraphNode*> checked;
        for(jsize i=0;i<count;++i) {
            auto n=static_cast<jstring>(e->GetObjectArrayElement(names,i));
            auto name=utf8(e,n);e->DeleteLocalRef(n);
            auto it=s.nodes.find(name);if(it==s.nodes.end())throw std::runtime_error("Missing imported instance: "+name);
            checked.push_back(it->second);
        }
        for(jsize i=0;i<count;++i) {
            const auto p=data+i*12;
            using namespace donut::math;
            daffine3 transform(double3(p[0],p[4],p[8]),double3(p[1],p[5],p[9]),
                double3(p[2],p[6],p[10]),double3(p[3],p[7],p[11]));
            double3 translation,scaling;dquat rotation;
            decomposeAffine(transform,&translation,&rotation,&scaling);
            checked[i]->SetTransform(&translation,&rotation,&scaling);
        }
    } catch(const std::exception& x) { fail(e,x); }
}

extern "C" JNIEXPORT jlong JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_window(
    JNIEnv* e, jclass, jlong h) {
    try { return reinterpret_cast<jlong>(glfwGetWin32Window(session(h).app->HostDeviceManager()->GetWindow())); }
    catch (const std::exception& x) { fail(e, x); return 0; }
}

extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_resize(
    JNIEnv* e, jclass, jlong h, jint w, jint height) {
    try {
        if(w < 1 || height < 1) throw std::invalid_argument("Invalid output dimensions");
        auto& s=session(h);
        if(g_sampleUIData.ActualDLSSFGMode()!=SI::DLSSGMode::eOff) {
            auto options=donut::app::StreamlineInterface::DLSSGOptions{};
            options.mode=SI::DLSSGMode::eOff;
            s.app->HostDeviceManager()->GetStreamline().SetDLSSGOptions(options);
            g_sampleUIData.DLSSFGMode=SI::DLSSGMode::eOff;
            s.restoreFrameGenerationBeforeRender=true;
        }
        glfwSetWindowSize(s.app->HostDeviceManager()->GetWindow(), w, height);
    } catch (const std::exception& x) { fail(e, x); }
}

extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_close(
    JNIEnv* e, jclass, jlong h) {
    std::lock_guard lock(lifecycle);
    try {
        auto& s=session(h);
        if(s.app && g_sampleUIData.ActualDLSSFGMode()!=SI::DLSSGMode::eOff) {
            auto options=donut::app::StreamlineInterface::DLSSGOptions{};
            options.mode=SI::DLSSGMode::eOff;
            s.app->HostDeviceManager()->GetStreamline().SetDLSSGOptions(options);
            g_sampleUIData.DLSSFGMode=SI::DLSSGMode::eOff;
        }
        s.shutdown();
        auto shutdownResult=s.shutdownResult;
        active=nullptr; delete &s;
        if(shutdownResult!=sl::Result::eOk)
            throw std::runtime_error("Streamline shutdown failed, result="+std::to_string(static_cast<int>(shutdownResult)));
    }
    catch (const std::exception& x) { fail(e, x); }
}
