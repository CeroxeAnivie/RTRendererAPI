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
#include <unordered_set>
#include <set>
#include <cmath>
#include <sstream>
#include <filesystem>
#include <array>
#include <cstdio>
#include <fstream>
#include <chrono>
#include <d3d12sdklayers.h>
#include <donut/engine/TextureCache.h>
#include <donut/engine/HostSceneTableState.h>
#include <json/json.h>
#include "StreamlineIntegration.h"
#define WinMain RtrUnusedSampleEntryPoint
#include RTR_RTXPT_ADVANCED_SOURCE
#undef WinMain
#include <Materials/MaterialsBaker.h>
#include <OpacityMicroMap/OmmBaker.h>
#include <SampleCommon/AccelerationStructureUtil.h>
#define GLFW_EXPOSE_NATIVE_WIN32
#include <GLFW/glfw3native.h>
#include "rtxpt_shutdown.h"
#include "rtxpt_shared_frames.h"
#include "rtxpt_material_policy.h"
#include "rtxpt_sr_history.h"
#include "rtxpt_path_quality.h"
#include "rtxpt_host_resources.h"
#include "rtxpt_memory_diagnostics.h"
#include "rtxpt_upload_retention.h"
#include "rtxpt_retained_light_tree.h"
#include "rtxpt_sparse_lighting.h"
#include "rtxpt_native_extension.h"
#include "rtxpt_native_lighting.h"
#include "rtxpt_signal_frames.h"
#include "rtxpt_surface_inputs.h"
#include "rtxpt_host_surface.h"
#include "rtxpt_host_outputs.h"
#include "rtxpt_host_augmentation.h"
#include "rtxpt_gpu_profile.h"
#include "rtxpt_instance_transform.h"
#include "rtxpt_memory_resources.h"
#include "rtxpt_resource_arena.h"
#include "rtxpt_upload_arena.h"
#include "rtxpt_capacity.h"
#include <map>
extern SampleUIData g_sampleUIData;

namespace {
void CheckRtrDevice(nvrhi::IDevice* device){
        auto* nativeDevice=static_cast<ID3D12Device*>(device->getNativeObject(nvrhi::ObjectTypes::D3D12_Device));
        const auto reason=nativeDevice->GetDeviceRemovedReason();
        if(FAILED(reason)){
            if(std::getenv("RTR_RTXPT_GPU_VALIDATION")){
                Microsoft::WRL::ComPtr<ID3D12InfoQueue> messages;
                if(SUCCEEDED(nativeDevice->QueryInterface(IID_PPV_ARGS(&messages)))){
                    const auto count=messages->GetNumStoredMessagesAllowedByRetrievalFilter();
                    for(UINT64 index=count>64?count-64:0;index<count;++index){
                        SIZE_T bytes=0;if(FAILED(messages->GetMessage(index,nullptr,&bytes))||bytes>65536)continue;
                        std::vector<uint8_t> storage(bytes);auto* message=reinterpret_cast<D3D12_MESSAGE*>(storage.data());
                        if(SUCCEEDED(messages->GetMessage(index,message,&bytes))&&message->Severity<=D3D12_MESSAGE_SEVERITY_WARNING)
                            std::fprintf(stderr,"D3D12 validation id=%u severity=%u: %s\n",unsigned(message->ID),unsigned(message->Severity),message->pDescription);
                    }
                }
            }
            RtrLatchDeviceFailure(reason);
            RtrProtocolTrace::Record(4,0,0,0,0,0,0,0,0,0,0,uint32_t(reason));
            Microsoft::WRL::ComPtr<ID3D12DeviceRemovedExtendedData1> dred;
            if(SUCCEEDED(nativeDevice->QueryInterface(IID_PPV_ARGS(&dred)))){
                D3D12_DRED_PAGE_FAULT_OUTPUT1 fault{};
                if(SUCCEEDED(dred->GetPageFaultAllocationOutput1(&fault))){
                    std::fprintf(stderr,"DRED fault=%llx\n",fault.PageFaultVA);
                    for(auto p=fault.pHeadExistingAllocationNode;p;p=p->pNext)std::fprintf(stderr,"DRED existing %s type=%u\n",p->ObjectNameA?p->ObjectNameA:"?",p->AllocationType);
                    for(auto p=fault.pHeadRecentFreedAllocationNode;p;p=p->pNext)std::fprintf(stderr,"DRED freed %s type=%u\n",p->ObjectNameA?p->ObjectNameA:"?",p->AllocationType);
                }
                D3D12_DRED_AUTO_BREADCRUMBS_OUTPUT1 output{};
                if(SUCCEEDED(dred->GetAutoBreadcrumbsOutput1(&output)))for(auto p=output.pHeadAutoBreadcrumbNode;p;p=p->pNext){
                    uint32_t last=p->pLastBreadcrumbValue?*p->pLastBreadcrumbValue:0;
                    std::fprintf(stderr,"DRED command=%s last=%u count=%u\n",p->pCommandListDebugNameA?p->pCommandListDebugNameA:"?",last,p->BreadcrumbCount);
                    for(uint32_t k=last>4?last-4:0;k<std::min(last+5,p->BreadcrumbCount);k++)std::fprintf(stderr,"DRED op[%u]=%u\n",k,p->pCommandHistory[k]);
                }
            }
            RtrRequireHealthy();
        }
        RtrRequireHealthy();
}
class HostPathTracer final : public AdvancedPathTracer {
    struct SceneLight { float4 positionType,directionRange,colorIntensity,coneShadow; };
    static_assert(sizeof(SceneLight)==64);
    std::vector<SceneLight> sceneLights;
    rtr::RetainedLightTree lightTree;
    std::vector<rtr::LightTreeNode>& sceneLightTree=lightTree.nodes;
    uint64_t lightIndexBuilds=0,lightIndexBuildNs=0;
    double lightPowerNormalization=1;
    bool uploadSunOnly=false;
    std::vector<uint32_t> dirtyLightIndices,dirtyTreeIndices;
    std::unordered_map<int64_t,uint32_t> lightIndices;
    std::vector<std::shared_ptr<donut::engine::Light>> managedLightObjects;
    uint64_t lightDeltaRecords=0,lightTreeRefits=0;
    int lightingContinuity=0;
    bool continuousPending=false,independentBackground=false,transparentBackground=false;
    float3 primaryBackground={};
    uint64_t executedAccumulationResets=0,executedReconstructionResets=0,executedDenoiserResets=0,cacheNotifications=0,continuousUploads=0;
    uint64_t cacheGeneration=1;
    uint64_t nativeMotionGeneration=1;
    uint64_t HostSceneCacheGeneration() const override { return managedLighting ? cacheGeneration : 0; }
public:
    RtrSparseLighting sparseLighting;
    RtrNativeLightingPass nativeLighting;
    RtrHostSurface hostSurface;
    RtrHostOutputs hostOutputs;
    RtrHostAugmentation hostAugmentation;
    RtrSurfaceInputs* surfaceInputs=nullptr;
    std::optional<RtrSurfaceInputs::Read> surfaceRead;
    bool hostReadSubmitted=false;
    std::array<dm::affine3,2> hostView;
    std::array<dm::float4x4,2> hostProjection;
    std::array<dm::float2,2> hostJitter;
    bool hostViewConfigured=false;
    uint64_t hostInputGeneration=0,hostResourceGeneration=0;
    int64_t hostTimeNanos=0,hostDeltaNanos=0;
    bool ConfigureHostViews(donut::engine::PlanarView& current,donut::engine::PlanarView& previous) override {
        if(!hostSurface.active||!hostViewConfigured)return false;
        const float2 scale=float2(float(m_renderSize.x)/m_displaySize.x,float(m_renderSize.y)/m_displaySize.y);
        current.SetMatrices(hostView[0],hostProjection[0]);current.SetPixelOffset(hostJitter[0]*scale);
        previous.SetMatrices(hostView[1],hostProjection[1]);previous.SetPixelOffset(hostJitter[1]*scale);return true;
    }
    void ConfigureHostView(const std::vector<float>& values){
        if(values.size()!=70)throw std::invalid_argument("Host view ABI requires 70 floats");
        for(float value:values)if(!std::isfinite(value))throw std::invalid_argument("Non-finite host view");
        for(size_t index=0;index<2;index++){
            const float* value=values.data()+index*35;dm::float4x4 world,projection;
            for(int row=0;row<4;row++)for(int column=0;column<4;column++)world[row][column]=value[column*4+row]*(row==2?-1.f:1.f);
            // Homogeneous row is translation, not the view-space Z basis.
            hostView[index]=dm::inverse(dm::homogeneousToAffine(world));
            int depth=int(value[34]);if(depth<0||depth>3||float(depth)!=value[34])throw std::invalid_argument("Unknown host depth convention");
            for(int row=0;row<4;row++)for(int column=0;column<4;column++){
                float element=value[16+column*4+row];
                if(column==2){float z=value[24+row],w=value[28+row];
                    if(depth==1||depth==3)z=(z+w)*.5f;
                    element=depth<2?w-z:z;}
                projection[row][column]=element*(row==2?-1.f:1.f);
            }
            hostProjection[index]=projection;
            // API jitter offsets the sample position; Donut offsets the projection.
            hostJitter[index]=-float2(value[32],value[33]);
        }
        hostViewConfigured=true;
    }
    void BeginHostRead(RtrSurfaceInputs& inputs,const RtrSurfaceInputs::Identity& identity){
        if(surfaceRead)throw std::logic_error("A host surface read is already pending");
        surfaceRead=inputs.BeginRead(identity);hostReadSubmitted=false;surfaceInputs=&inputs;std::copy_n(surfaceRead->textures.begin(),8,hostSurface.inputs.begin());
        const auto& plan=surfaceRead->packet.plan;hostSurface.inputWidth=plan.width;hostSurface.inputHeight=plan.height;
        hostSurface.composition=plan.composition;hostSurface.hostComponents=plan.preservedHostComponents;hostSurface.motionIncludesJitter=plan.motionIncludesJitter;
        hostSurface.sequence=identity.sequence;hostSurface.revision=identity.revision;
        hostInputGeneration=surfaceRead->packet.token.generation;hostResourceGeneration=identity.resourceGeneration;
        hostTimeNanos=identity.timeNanos;hostDeltaNanos=identity.deltaNanos;
        hostSurface.active=true;GetBindingCache().Clear();InvalidateBindingSet();
    }
    void AbortHostRead(){if(surfaceRead){
        if(hostReadSubmitted){FinishHostRead(false);return;}
        surfaceInputs->AbortRead(surfaceRead->packet.token);surfaceRead.reset();
    }}
    void FinishHostRead(bool completed=true){if(!surfaceRead)return;
        auto command=GetDevice()->createCommandList();command->open();surfaceInputs->RestoreCommon(command,*surfaceRead);command->close();
        GetDevice()->executeCommandList(command);surfaceInputs->EndRead(surfaceRead->packet.token);surfaceRead.reset();hostReadSubmitted=false;if(completed)hostSurface.RecordWork(GetDevice());
    }
    const donut::engine::Scene* nativePreparedScene=nullptr;
    bool nativeTlasDirty=true;
    bool nativeOmmDebugStale=false;
    void ActivateLightingExtension(const RtrNativeExtension& extension){nativeLighting.Prepare(GetDevice(),*m_shaderFactory,m_bindlessLayout,&sparseLighting);nativeLighting.Activate(GetDevice(),m_bindlessLayout,extension);}
    void Render(nvrhi::IFramebuffer* framebuffer) override {
        const bool augmentation=hostSurface.active&&hostSurface.composition==0;
        if(!nativeLighting.active&&!augmentation){
            if(hostSurface.active&&!surfaceRead)throw std::logic_error("Host mode requires a matching published primary surface input");
            // Independent lighting never reads the beauty OMM debug table.
            // Restore it before the next beauty consumer after a mode switch.
            if(nativeOmmDebugStale&&m_scene&&m_ommBaker){m_ommBaker->SceneLoaded(*m_scene);nativeOmmDebugStale=false;}
            ++nativeLighting.beautyFrames;
            try{hostReadSubmitted=bool(surfaceRead);AdvancedPathTracer::Render(framebuffer);
                if(hostSurface.active)hostOutputs.Resolve(GetDevice(),HostSurfaceHdr(),hostSurface);
            }catch(...){if(surfaceRead)FinishHostRead(false);throw;}
            FinishHostRead();return;
        }
        if(!m_scene)throw std::runtime_error("Native lighting requires a resident scene");
        if(augmentation&&!surfaceRead)throw std::logic_error("Host augmentation requires a matching published surface input");
        const auto& framebufferInfo=framebuffer->getFramebufferInfo();m_displaySize=uint2(framebufferInfo.width,framebufferInfo.height);
        if(augmentation){hostAugmentation.Prepare(GetDevice(),*m_shaderFactory,m_bindlessLayout,nativeLighting);m_renderSize=uint2(hostAugmentation.plan.width,hostAugmentation.plan.height);}
        else{const auto* dimensions=reinterpret_cast<const uint32_t*>(nativeLighting.constants.words.data());m_renderSize=uint2(dimensions[40],dimensions[41]);}
        m_progressLoading.Stop();m_asyncLoadingInProgress=false;
        if(nativePreparedScene!=m_scene.get()){
            nativePreparedScene=m_scene.get();nativeLighting.metadataDirty=true;nativeLighting.metadataInitialized=false;nativeTlasDirty=true;
        }
        if(!augmentation)nativeLighting.Prepare(GetDevice(),*m_shaderFactory,m_bindlessLayout,&sparseLighting);
        if(m_scene->GetSceneGraph()->GetMeshInstances().empty()){
            nativeLighting.retainedSignals=false;
            m_commandList->open();
            if(augmentation){
                hostSurface.Prepare(GetDevice(),m_scene,m_commandList);
                for(unsigned i=0;i<8;i++)if(hostAugmentation.outputs[i]){
                    if(i==0)m_commandList->copyTexture(hostAugmentation.outputs[i],nvrhi::TextureSlice(),surfaceRead->textures[9],nvrhi::TextureSlice());
                    else m_commandList->clearTextureFloat(hostAugmentation.outputs[i],nvrhi::AllSubresources,i==1?nvrhi::Color(1,1,1,0):nvrhi::Color(0));
                }
                if(nativeLighting.workStatisticsEnabled)m_commandList->clearBufferUInt(nativeLighting.work,0);
                m_commandList->close();hostReadSubmitted=true;GetDevice()->executeCommandList(m_commandList);nativeLighting.RecordWork(GetDevice());FinishHostRead();++hostAugmentation.executedFrames;++m_frameIndex;return;
            }
            if(nativeLighting.direct)m_commandList->clearTextureFloat(nativeLighting.direct,nvrhi::AllSubresources,nvrhi::Color(1,1,1,0));
            if(nativeLighting.indirect)m_commandList->clearTextureFloat(nativeLighting.indirect,nvrhi::AllSubresources,nvrhi::Color(0));
            nativeLighting.reconstruction.valid=false;nativeLighting.reconstruction.reset=true;
            if(nativeLighting.geometry)m_commandList->clearTextureFloat(nativeLighting.geometry,nvrhi::AllSubresources,nvrhi::Color(0));
            if(nativeLighting.identity)m_commandList->clearTextureUInt(nativeLighting.identity,nvrhi::AllSubresources,0);
            if(nativeLighting.workStatisticsEnabled)m_commandList->clearBufferUInt(nativeLighting.work,0);
            nativeLighting.EncodeOutputs(GetDevice(),m_commandList);
            m_commandList->close();GetDevice()->executeCommandList(m_commandList);nativeLighting.RecordWork(GetDevice());++nativeLighting.executedFrames;++m_frameIndex;return;
        }
        RecreateAccelStructs(m_commandList);
        PrepareHostLightResources();
        m_commandList->open();
        UploadSceneLighting(m_commandList);
        // Managed resource commits refresh changed materials directly. Stable,
        // camera-only and analytic-light frames need no scene-buffer scan.
        auto graph=m_scene->GetSceneGraph();
        const bool nativeTransformsChanged=graph->HasPendingTransformChanges()||graph->HasPendingStructureChanges();
        if(!managedLighting||graph->HasPendingStructureChanges()||graph->HasPendingTransformChanges())
            m_scene->Refresh(m_commandList,GetFrameIndex());
        m_sceneRenderCache.Open(m_scene,&m_frameWorkerPool,HostSceneCacheGeneration());
        RAII_SCOPE(;,PostRender(););
        UpdateSkinnedBLASs(m_commandList,GetFrameIndex());
        PrepareNativeCandidateFlags(m_commandList);
        TransitionMeshBuffersToReadOnly(m_commandList);
        // The native receiver queries do not use view-dependent instance culling.
        // Reuse the built TLAS until an actual input, address or transform changes.
        if(nativeTlasDirty||nativeTransformsChanged||(m_ui.AS.GPUTLASBuild&&m_tlasInstanceDataDirty)||!graph->GetSkinnedMeshInstances().empty()){
            const bool savedCull=m_ui.AS.GPUTLASCull;m_ui.AS.GPUTLASCull=false;
            RAII_SCOPE(;,m_ui.AS.GPUTLASCull=savedCull;);
            UpdateTLAS(m_commandList);++actualNativeTlasBuilds;nativeTlasDirty=false;}
        nativeLighting.retainSignalsAllowed=managedLighting&&!augmentation&&!nativeTransformsChanged&&graph->GetSkinnedMeshInstances().empty();
        nativeLighting.inputGeneration=cacheGeneration;
        nativeLighting.analyticGeneration=lightingBatchCount;
        if(nativeTransformsChanged||!managedLighting||!graph->GetSkinnedMeshInstances().empty())++nativeMotionGeneration;
        nativeLighting.geometryGeneration=nativeMotionGeneration;
        if(augmentation){nativeLighting.retainedSignals=false;hostAugmentation.Dispatch(GetDevice(),m_commandList,m_scene,m_topLevelAS,sparseLighting,m_CommonPasses->m_LinearWrapSampler,sceneLightBuffer,uint32_t(sceneLights.size()),lightTree.root==rtr::RetainedLightTree::None?0:lightTree.root,lightSlotCapacity,nativeLighting,hostSurface,surfaceRead->textures,hostTimeNanos,hostDeltaNanos);}
        else nativeLighting.Dispatch(GetDevice(),m_commandList,m_scene,m_topLevelAS,sparseLighting,m_CommonPasses->m_LinearWrapSampler,sceneLightBuffer,uint32_t(sceneLights.size()),lightTree.root==rtr::RetainedLightTree::None?0:lightTree.root,lightSlotCapacity);
        m_commandList->close();if(augmentation)hostReadSubmitted=true;GetDevice()->executeCommandList(m_commandList);nativeLighting.RecordWork(GetDevice());if(augmentation)FinishHostRead();++m_frameIndex;
    }
    void ApplySparseLighting(const std::vector<int64_t>& ids,const std::vector<float>& values,const std::vector<int64_t>& removals,bool reset){
        sparseLighting.Apply(ids,values,removals,reset);LightingHistory(true);InvalidateFrameCache();
    }
    void InvalidateFrameCache(){++cacheGeneration;}
private:
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
    bool supportsManagedSceneLighting() const {
        return pathQuality.values[0]==1 && managedLighting
            && !g_sampleUIData.ActualUseRTXDIPasses() && g_sampleUIData.ActualRadianceCacheMode()==0;
    }
    bool sceneLightingActive() const {
        return supportsManagedSceneLighting() && !sceneLights.empty();
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
    bool pendingEmptyScene=false,currentMemoryScene=false;
    bool LoadScene(std::shared_ptr<donut::vfs::IFileSystem> fs,const std::filesystem::path& path) override {
        currentMemoryScene=std::exchange(pendingEmptyScene,false);
        if(!currentMemoryScene)return AdvancedPathTracer::LoadScene(fs,path);
        // Donut owns graph construction and Sample owns the unchanged SceneLoaded/
        // FinishedLoading lifecycle. Retained resources never need a dummy glTF.
        m_scene=std::make_shared<ExtendedScene>(GetDevice(),*m_shaderFactory,fs,m_TextureCache,m_DescriptorTable,std::make_shared<ExtendedSceneTypeFactory>());
        auto graph=m_scene->CreateSceneGraph();
        graph->SetRootNode(std::make_shared<donut::engine::SceneGraphNode>());
        m_progressLoading.Set(10);return true;
    }
    #include "rtxpt_retained_scene.inl"
    #include "rtxpt_memory_resources.inl"
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
        if(currentMemoryScene){
            managedLighting=true;preSrHistoryReset=true;ConfigureManagedBackground();return;
        }
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
    #include "rtxpt_retained_lighting.inl"
    void SetLightingContinuity(int policy){
        if(policy<0||policy>1)throw std::invalid_argument("Invalid lighting continuity");
        // The policy is installed before the first light publication and after
        // each empty bootstrap/reset. Eligibility is not a nonempty workload.
        if(policy==1&&!supportsManagedSceneLighting())throw std::invalid_argument("Continuous lighting requires SCENE_ANALYTIC without ReSTIR or radiance cache");
        lightingContinuity=policy;
    }
    bool HostTransparentBackground() const override { return transparentBackground; }
    void SetBackground(bool independent,float3 color,bool transparent){
        if(transparentBackground!=transparent || independentBackground!=independent || (independent&&any(primaryBackground!=color))){g_sampleUIData.ResetRealtimeCaches=true;preSrHistoryReset=true;}
        transparentBackground=transparent;independentBackground=independent;primaryBackground=color;
    }
    std::array<jlong,16> ExecutionTelemetry() {auto p=GetRTPipelineBaker()?GetRTPipelineBaker()->HostTelemetry():std::array<uint64_t,3>{};return {jlong(importerNs),jlong(textureFinalizeNs),jlong(materialSyncNs),jlong(accelerationNs),jlong(nativeRetirementNs),jlong(lightDeltaRecords),jlong(lightTreeRefits),jlong(executedAccumulationResets),jlong(executedReconstructionResets),jlong(executedDenoiserResets),jlong(cacheNotifications),jlong(continuousUploads),jlong(p[0]),jlong(p[1]),jlong(p[2]),jlong(RtrHostResources::maximumPollIntervalNanos)};}
    void ApplyLightDelta(const std::vector<int64_t>& ids,const std::vector<float>& values){
        for(auto id:ids)if(!lightIndices.count(id))throw std::invalid_argument("Unknown light delta ID");
        std::array<float,16> sun{};std::memcpy(sun.data(),&sceneLights.at(0),sizeof(SceneLight));
        ApplyLightPatch(ids,values,{},sun,{sceneSky.x,sceneSky.y,sceneSky.z,sceneSky.w});
    }
    void UpdateCustomResources() override {
        AdvancedPathTracer::UpdateCustomResources();PrepareHostLightResources();
        hostSurface.Prepare(GetDevice(),m_scene,m_commandList);
        hostOutputs.Ensure(GetDevice(),m_commandList);
        if(hostSurface.active)hostOutputs.Prepare(GetDevice(),*m_shaderFactory,m_commandList,m_renderSize.x,m_renderSize.y,m_displaySize.x,m_displaySize.y);
    }
    void PrepareHostLightResources() {
        if(sparseLighting.Ensure(GetDevice())){GetBindingCache().Clear();InvalidateBindingSet();}
        if(lightStatsEnabled&&!lightStatsBuffer){
            nvrhi::BufferDesc desc;desc.byteSize=16;desc.canHaveUAVs=true;desc.canHaveRawViews=true;
            desc.initialState=nvrhi::ResourceStates::UnorderedAccess;desc.keepInitialState=true;desc.debugName="RtrLightWork";
            lightStatsBuffer=GetDevice()->createBuffer(desc);
        }
        for(auto it=retiredLighting.begin();it!=retiredLighting.end();) {
            if(GetDevice()->pollEventQuery(it->graphics)&&GetDevice()->pollEventQuery(it->compute))it=retiredLighting.erase(it);else ++it;
        }
        ConfigureManagedBackground();
        if(!sceneLightBuffer || sceneLights.size()>lightSlotCapacity || sceneLightTree.size()>treeSlotCapacity) {
            uint32_t lightCapacity=std::max(lightSlotCapacity,1u),treeCapacity=std::max(treeSlotCapacity,1u);
            while(lightCapacity<sceneLights.size())lightCapacity*=2;
            while(treeCapacity<sceneLightTree.size())treeCapacity*=2;
            nvrhi::BufferDesc desc;desc.byteSize=(size_t(lightCapacity)+treeCapacity)*sizeof(SceneLight);desc.structStride=sizeof(SceneLight);
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
            sceneLightBuffer=replacement;lightSlotCapacity=lightCapacity;treeSlotCapacity=treeCapacity;
            ++lightingWork[12];sceneLightsDirty=true;uploadSunOnly=false;
        }
    }
    void AddCustomBindings(nvrhi::BindingSetDesc& desc) override {
        AdvancedPathTracer::AddCustomBindings(desc);
        hostSurface.Bind(desc);
        hostOutputs.Bind(desc);
        desc.bindings.push_back(nvrhi::BindingSetItem::StructuredBuffer_SRV(97,sparseLighting.sourceBuffer));
        desc.bindings.push_back(nvrhi::BindingSetItem::StructuredBuffer_SRV(98,sparseLighting.treeBuffer));
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
    void UploadSceneLighting(nvrhi::ICommandList* commandList) {
        if(sceneLightsDirty && sceneLightBuffer) {
            const auto begin=RtrDiagnostics::level.load(std::memory_order_relaxed)?RtrDiagnostics::Now():0;
            RtrDiagnostics::Scope uploadScope(RtrDiagnostics::LightUpload,sceneLights.size(),dirtyLightIndices.size()+dirtyTreeIndices.size());
            auto uploadRanges=[&](auto& records,std::vector<uint32_t>& dirty,uint64_t base,unsigned counter){
                if(!uploadSunOnly){
                    if(!records.empty()){commandList->writeBuffer(sceneLightBuffer,records.data(),records.size()*64,base*64);lightingWork[counter]+=records.size();lightingWork[11]+=records.size()*64;}
                    return;
                }
                std::sort(dirty.begin(),dirty.end());dirty.erase(std::unique(dirty.begin(),dirty.end()),dirty.end());
                dirty.erase(std::lower_bound(dirty.begin(),dirty.end(),uint32_t(records.size())),dirty.end());
                for(size_t i=0;i<dirty.size();){size_t end=i+1;while(end<dirty.size()&&dirty[end]==dirty[end-1]+1)++end;
                    uint32_t first=dirty[i];size_t count=end-i;
                    if(size_t(first)+count>records.size())throw std::logic_error("Invalid dirty lighting span");
                    commandList->writeBuffer(sceneLightBuffer,&records[first],count*64,(base+first)*64);lightingWork[counter]+=count;lightingWork[11]+=count*64;i=end;}
            };
            uploadRanges(sceneLights,dirtyLightIndices,0,9);uploadRanges(sceneLightTree,dirtyTreeIndices,lightSlotCapacity,10);
            uploadSunOnly=false;dirtyLightIndices.clear();dirtyTreeIndices.clear();
            sceneLightsDirty=false;
            if(lightingCommitPending) {
                ++lightingUploadCount;lightingCommitPending=false;if(continuousPending)++continuousUploads;continuousPending=false;
                if(begin)lightingUploadNs+=RtrDiagnostics::Now()-begin;
                const char* diagnostics=std::getenv("RTR_RTXPT_LIGHTING_DIAGNOSTICS");
                if(diagnostics&&std::string(diagnostics)=="1")donut::log::info("RTXPT lighting executed loads=%llu batches=%llu uploads=%llu uploadRecordingNs=%llu historyResets=%llu lights=%zu",sceneLoadCount,lightingBatchCount,lightingUploadCount,lightingUploadNs,lightingHistoryResets,sceneLights.size());
            }
        }
    }
    void UpdateCustomConstants(SampleConstants& constants, nvrhi::ICommandList* commandList) override {
        AdvancedPathTracer::UpdateCustomConstants(constants,commandList);
        sparseLighting.Upload(commandList);
        if(lightStatsEnabled)commandList->clearBufferUInt(lightStatsBuffer,0);
        auto& pt=constants.ptConsts;
        pt.RtrSceneAmbient=float4(sceneSky.xyz()*sceneSky.w,transparentBackground?2.f:independentBackground?1.f:0.f);
        pt.RtrSceneSky=float4(sceneSky.xyz()*std::max(sceneSky.w,.05f),0);
        std::memcpy(&pt.RtrDirectionalPadding,&primaryBackground,sizeof(float3)); // bit-exact RGB in reserved ABI words
        pt.RtrSceneLightCount=uint32_t(sceneLights.size());
        pt.RtrScenePadding=uint3(lightTree.root==rtr::RetainedLightTree::None?0:lightTree.root,lightSlotCapacity,uint32_t(pathQuality.values[5]));
        UploadSceneLighting(commandList);
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
    nvrhi::ITexture* HostSurfaceHdr() const {return m_renderTargets?m_renderTargets->ProcessedOutputColor.Get():nullptr;}
    nvrhi::ITexture* HostDisplayColor() const { return m_renderTargets ? m_renderTargets->LdrColor.Get() : nullptr; }
    void SceneUnloading() override {
        AdvancedPathTracer::SceneUnloading();
        // Drop the buffers and their binding-set readers together. The existing
        // BeginLoadingScene idle boundary follows this hook; indexNodes releases
        // their arena leases only after that boundary, never during an update.
        m_tlasCullBindingSet=nullptr;
        m_tlasInstanceBuffer=nullptr;m_tlasInstanceDataBuffer=nullptr;m_tlasMeshBoundsBuffer=nullptr;
        m_tlasCullBoundInstanceData=m_tlasCullBoundInstanceAccel=m_tlasCullBoundMeshBounds=m_tlasCullBoundOutput=nullptr;
        m_tlasInstanceCount=0;m_tlasInstanceDataDirty=true;
    }
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
        RtrDiagnostics::Scope policyScope(RtrDiagnostics::LightingPolicy,sceneLights.size());
        m_bakeDirectionalLightsIntoEnvMap=!sceneLightingActive();
        if(lightingSceneDirty) {
            eligibleLightingScene=!sceneLightingActive() && smallPointScene();
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
        const bool enabled=!sceneLightingActive() && pathQuality.values[5]>0 && supportedRealtimePolicy
            && !(overrideValue && std::string(overrideValue)=="0");
        const char* samplerControl=std::getenv("RTR_RTXPT_DIAGNOSTIC_LIGHT_SAMPLER");
        // Retain the accepted SR policy for small, non-emissive scenes. Other
        // content and reconstruction paths keep upstream adaptive sampling.
        const bool usePower=pathQuality.values[1]==0 && enabled && eligibleLightingScene && (samplerControl
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
        if(hostSurface.active)macros.push_back({"RTR_HOST_PRIMARY","1"});
        if(sceneLightingActive()) {
            macros.push_back({"RTR_SCENE_LIGHTING","1"});
            // Exact/tree selection uses the runtime threshold, never a scene-dependent pipeline key.
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
            macros.push_back({"RTR_EXACT_POINT_LIGHT_LIMIT","4096"});
            if(!residualControl || std::string(residualControl)!="1")
                macros.push_back({"RTR_CONDITIONAL_POINT_SAMPLING","1"});
        }
        const char* misControl=std::getenv("RTR_RTXPT_DIAGNOSTIC_EXACT_MIS");
        const bool approximateMis=misControl && std::string(misControl)=="0";
        const bool exactMis=!approximateMis && (sceneLightingActive() || smallPointLighting || (misControl && std::string(misControl)=="1"));
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
    bool emptyScene=false;
    RtrPathQuality pathQuality;
    std::unique_ptr<Sample> CreateMainRenderPass(donut::app::DeviceManager& dm,const CommandLineOptions& options) override {
        auto pass=std::make_unique<HostPathTracer>(dm,options);
        pass->pendingEmptyScene=emptyScene;
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
    RtrSignalFrames signalFrames;
    RtrSurfaceInputs surfaceInputs;
    RtrSurfaceSignalFrames surfaceFrames;
    uint64_t signalSequence=0,signalRevision=0;
    bool externalPresentation=false;
    bool gpuTimingsEnabled=true,gpuTimingsAllowed=true;
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
    // Independent fixture timing is opt-in for benchmark processes, including OFF.
    // It never enables product events, work atomics, GPU scope exports or JFR.
    bool benchmarkTimings=[] {const char* v=std::getenv("RTR_RTXPT_BENCHMARK_TIMING");return v&&std::string(v)=="1";}();
    struct GpuSample {nvrhi::TimerQueryHandle query,requestQuery;bool pending=false,requestStarted=false;uint64_t sequence=0,revision=0,mode=0;};
    std::array<std::array<uint64_t,7>,8192> benchmarkSamples{};
    uint64_t benchmarkWritten=0,benchmarkDropped=0;
    std::array<GpuSample,8> gpuSamples;
    nvrhi::CommandListHandle timerCommands;
    GpuSample* currentGpuSample=nullptr;
    GpuSample* currentRequestGpuSample=nullptr;
    RtrGpuProfile::Token fullGpuScope,requestGpuScope;
    uint64_t gpuSampleCount=0,gpuMaxNs=0;
    double gpuAverageNs=0;
    void collectGpuSamples(){
        auto* device=app->HostDeviceManager()->GetDevice();CheckRtrDevice(device);
        for(auto& sample:gpuSamples)if(sample.pending&&device->pollTimerQuery(sample.query)
                &&(!sample.requestStarted||device->pollTimerQuery(sample.requestQuery))){
            const double ns=device->getTimerQueryTime(sample.query)*1e9;
            if(sample.mode!=0){++gpuSampleCount;gpuAverageNs=gpuSampleCount==1?ns:gpuAverageNs*.9+ns*.1;gpuMaxNs=std::max(gpuMaxNs,uint64_t(ns));}
            if(benchmarkTimings){
                const double requestNs=sample.requestStarted?device->getTimerQueryTime(sample.requestQuery)*1e9:ns;
                benchmarkSamples[benchmarkWritten%benchmarkSamples.size()]={sample.sequence,sample.revision,uint64_t(requestNs),sample.mode,benchmarkWritten+1,uint64_t(ns),sample.requestStarted?1u:0u};++benchmarkWritten;
            }
            sample.pending=false;
        }
    }
    void beginBenchmarkGpuRequest(uint64_t sequence,uint64_t revision){
        if(!benchmarkTimings)throw std::logic_error("Independent benchmark timing was not enabled before creating the session");
        if(currentRequestGpuSample||currentGpuSample)throw std::logic_error("A GPU request interval is already active");
        auto* device=app->HostDeviceManager()->GetDevice();collectGpuSamples();
        auto& sample=gpuSamples[rendered%gpuSamples.size()];
        if(sample.pending){++benchmarkDropped;throw std::runtime_error("No free independent GPU request timer");}
        sample.sequence=sequence;sample.revision=revision;sample.mode=RtrDiagnostics::level.load();sample.requestStarted=true;
        if(!sample.requestQuery)sample.requestQuery=device->createTimerQuery();
        if(!timerCommands)timerCommands=device->createCommandList();
        device->resetTimerQuery(sample.requestQuery);
        timerCommands->open();timerCommands->beginTimerQuery(sample.requestQuery);
        requestGpuScope=RtrGpuProfile::Begin(timerCommands,"NativeLightingRequestFull",-1);timerCommands->close();
        device->executeCommandList(timerCommands);currentRequestGpuSample=&sample;
    }
    void beginGpuSample(uint32_t index) {
        currentGpuSample=nullptr;
        auto* device=app->HostDeviceManager()->GetDevice();
        CheckRtrDevice(device);
        collectGpuSamples();
        if(!gpuTimingsEnabled&&!benchmarkTimings)return;
        auto& sample=currentRequestGpuSample?*currentRequestGpuSample:gpuSamples[index%gpuSamples.size()];
        if(sample.pending){if(benchmarkTimings)++benchmarkDropped;return;}
        if(currentRequestGpuSample&&(sample.sequence!=signalSequence||sample.revision!=signalRevision))
            throw std::logic_error("GPU request timer identity changed before rendering");
        if(!currentRequestGpuSample)sample.requestStarted=false;
        sample.sequence=signalSequence;sample.revision=signalRevision;sample.mode=RtrDiagnostics::level.load();
        if(!sample.query)sample.query=device->createTimerQuery();
        if(!timerCommands)timerCommands=device->createCommandList();
        device->resetTimerQuery(sample.query);
        timerCommands->open();timerCommands->beginTimerQuery(sample.query);
        fullGpuScope=RtrGpuProfile::Begin(timerCommands,"NativeLightingFrameFull",-1);timerCommands->close();
        device->executeCommandList(timerCommands);currentGpuSample=&sample;
    }
    void endGpuSample() {
        if(!currentGpuSample)return;
        timerCommands->open();RtrGpuProfile::End(timerCommands,fullGpuScope);timerCommands->endTimerQuery(currentGpuSample->query);
        if(currentGpuSample->requestStarted){RtrGpuProfile::End(timerCommands,requestGpuScope);timerCommands->endTimerQuery(currentGpuSample->requestQuery);}
        timerCommands->close();
        app->HostDeviceManager()->GetDevice()->executeCommandList(timerCommands);
        currentGpuSample->pending=true;currentGpuSample=nullptr;currentRequestGpuSample=nullptr;
    }
    std::unordered_map<std::string, donut::engine::SceneGraphNode*> nodes;
    void shutdown() {
        RtrDiagnostics::Configure(0);
        RtrMemoryDiagnostics::Checkpoint("shutdown.begin");
        if (app) {
            if(auto* pass=static_cast<HostPathTracer*>(app->HostRenderPass())){
                pass->uploadArena.Stop();pass->resourceArena.Stop();
            }
            auto* dm=app->HostDeviceManager();
            auto* nativeDevice=dm->GetDevice()?static_cast<ID3D12Device*>(dm->GetDevice()->getNativeObject(nvrhi::ObjectTypes::D3D12_Device)):nullptr;
            const bool deviceRemoved=RtrDeviceRemoved(nativeDevice);
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
            sharedFrames.clear(deviceRemoved);signalFrames.Clear(deviceRemoved);surfaceFrames.Clear(deviceRemoved);surfaceInputs.Clear(deviceRemoved);
            if(compositor)compositorEnv->DeleteGlobalRef(compositor);
            if(compositorFrame)compositorEnv->DeleteGlobalRef(compositorFrame);
            compositor=nullptr;compositorFrame=nullptr;composeMethod=nullptr;compositorEnv=nullptr;
            for(auto& sample:gpuSamples){sample.query=nullptr;sample.requestQuery=nullptr;sample.pending=false;sample.requestStarted=false;}
            currentGpuSample=nullptr;currentRequestGpuSample=nullptr;
            app->End();
            RtrHostResources::eventPump=nullptr;
        }
        // Never leave the sample's [this] log callback installed during member teardown.
        donut::log::SetCallback(previousLog);
        app.reset();
        RtrMemoryDiagnostics::Checkpoint("shutdown.end");RtrMemoryDiagnostics::Configure(false);
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
    auto& host=*static_cast<HostPathTracer*>(s.app->HostRenderPass());
    host.memoryTextures.clear();host.spareMemoryTextures.clear();host.spareMemoryTextureBytes=0;host.uploadPreparationPending=false;
    host.resourceRetirement.clear();host.resourceCommandPool.clear();host.resourceComputeOrdering=nullptr;host.resourceQueryPool.clear();host.resourceBlasAllocationBytes.clear();host.resourceBufferAllocations.clear();host.preparedMeshBlases.clear();host.meshUploadScratch.clear();
    host.spareMeshBuffers.clear();host.spareMeshBlases.clear();host.spareMeshBufferBytes=host.spareMeshBlasBytes=0;
    host.spareResourceTlas={};host.spareResourceTlasBytes=0;host.resourceTlasAllocationBytes=0;host.resourceTlasAllocationIdentity=nullptr;
    host.activeResourceTlas={};host.preparedResourceTlas={};host.preparedTlasTables={};host.activeTlasTableStorage={};host.preparedTlasTableStorage={};host.preparedTlasAliases=false;
    // A complete scene replacement invalidates every borrowed graph-node view.
    // Incremental commits retain and patch these indices in their existing path.
    host.residentInstanceNodes.clear();host.residentMeshNodes.clear();
    host.residentMeshes.clear();host.residentMaterials.clear();host.residentMaterialMeshes.clear();host.residentTextureUsers.clear();
    host.IndexRetainedResources();
}
std::mutex lifecycle;
bool diagnosticsPrepared=false;
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

#include "rtxpt_native_application.inl"
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
    JNIEnv* e, jclass, jstring runtime, jstring scene, jint width, jint height, jboolean externalPresentation, jboolean gpuTimingsEnabled,jint maxFramesInFlight,jint workerThreads,jint shaderCompilerThreads,jobjectArray options,jintArray qualityIntegers,jfloatArray qualityConstants,jint diagnosticLevel) {
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
        if(std::getenv("RTR_RTXPT_GPU_VALIDATION")){
            Microsoft::WRL::ComPtr<ID3D12DeviceRemovedExtendedDataSettings> settings;
            if(SUCCEEDED(D3D12GetDebugInterface(IID_PPV_ARGS(&settings)))){
                settings->SetAutoBreadcrumbsEnablement(D3D12_DRED_ENABLEMENT_FORCED_ON);
                settings->SetPageFaultEnablement(D3D12_DRED_ENABLEMENT_FORCED_ON);
            }
        }
        const std::string scenePath=scene?utf8(e,scene):"__rtr_memory_scene__";
        std::vector<std::string> args {"RTRenderer-RTXPT", "--nonInteractive", "--scene", scenePath,
            "--width", std::to_string(width), "--height", std::to_string(height), "--overrideToRealtimeMode"};
        if(const char* validation=std::getenv("RTR_RTXPT_GPU_VALIDATION");validation&&std::string(validation)=="1")args.emplace_back("--gpuValidation");
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
        if(diagnosticLevel<0||diagnosticLevel>2)throw std::invalid_argument("Invalid initial diagnostics");
        if(!diagnosticsPrepared){RtrDiagnostics::Reset();RtrProtocolTrace::Reset();}
        diagnosticsPrepared=false;RtrDiagnostics::Configure(unsigned(diagnosticLevel));RtrProtocolTrace::enabled.store(diagnosticLevel!=0);
        RtrDeviceFailure.store(0);
        RtrDiagnostics::Scope initialization(RtrDiagnostics::RuntimeInitialize);
        RtrMemoryDiagnostics::Configure(diagnosticLevel!=0);
        auto s = std::make_unique<Session>();
        s->externalPresentation=externalPresentation;
        s->gpuTimingsEnabled=s->gpuTimingsAllowed=gpuTimingsEnabled;
        donut::log::ConsoleApplicationMode();
        g_sampleUIData = SampleUIData{};
        s->app = std::make_unique<HostApp>();
        s->app->emptyScene=scene==nullptr;
        s->app->framesInFlight=uint32_t(maxFramesInFlight);
        s->app->pathQuality=initialQuality;
        s->app->HostDeviceManager()->HostExternalPresentation=externalPresentation;
        s->app->HostDeviceManager()->HostFixedWindowTitle=true;
        RtrHostResources::lastPollNanos=RtrHostResources::maximumPollIntervalNanos=0;
        RtrHostResources::eventPump=[] { RtrHostResources::NoteEventPoll();glfwPollEvents(); };
        auto status = [&]{RtrDiagnostics::Scope initialize(RtrDiagnostics::DeviceInitialize);
            return s->app->Init(static_cast<int>(argv.size()), argv.data());}();
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
        {RtrDiagnostics::Scope sceneInitialization(RtrDiagnostics::SceneInitialize);
            static_cast<HostPathTracer*>(s->app->HostRenderPass())->LoadSceneLighting(scenePath);}
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
            ptr->lastPresentedTexture=dm.GetCurrentBackBuffer();
            ptr->probePhase(2);
            if(static_cast<HostPathTracer*>(ptr->app->HostRenderPass())->nativeLighting.active) {
                auto& lighting=static_cast<HostPathTracer*>(ptr->app->HostRenderPass())->nativeLighting;
                ptr->signalFrames.Publish(dm.GetDevice(),lighting.Outputs(),ptr->signalSequence,ptr->signalRevision,lighting.publication,lighting.timeNanos,lighting.deltaNanos,lighting.compactResponseEncoding?2:lighting.compactEncoding?1:0,lighting.maximumGi());
            } else if(static_cast<HostPathTracer*>(ptr->app->HostRenderPass())->hostSurface.active){
                auto* host=static_cast<HostPathTracer*>(ptr->app->HostRenderPass());
                int reconstruction=host->hostSurface.composition==0?0:g_sampleUIData.RealtimeMode?(g_sampleUIData.RealtimeAA==3?2:g_sampleUIData.RealtimeAA==2?1:0):0;
                ptr->surfaceFrames.Publish(dm.GetDevice(),host->hostSurface.composition==0?host->hostAugmentation.Outputs():host->hostOutputs.outputs,ptr->signalSequence,ptr->signalRevision,host->hostInputGeneration,host->hostTimeNanos,host->hostDeltaNanos,0,1.f,
                    {jlong(host->hostInputGeneration),jlong(host->hostResourceGeneration),reconstruction,host->hostSurface.composition==0?jlong(host->hostAugmentation.plan.colorDomain):0});
            } else if(ptr->externalPresentation)ptr->sharedFrames.publish(dm.GetDevice(),
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
            ptr->endGpuSample();++ptr->rendered;
        };
        indexNodes(*s);
        s->app->HostDeviceManager()->SetWindowTitle("RTRendererAPI | RTXPT (D3D12)");
        if(!externalPresentation){
            auto window=s->app->HostDeviceManager()->GetWindow();glfwShowWindow(window);
            const char* background=std::getenv("RTR_RTXPT_BACKGROUND_WINDOW");
            if(!background||std::string(background)!="1")glfwFocusWindow(window);
            glfwPollEvents();
        }
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
        RtrDiagnostics::Scope diagnostic(RtrDiagnostics::Frame);
        auto& s = session(h); auto dm = s.app->HostDeviceManager();
        auto& nativePass=static_cast<HostPathTracer*>(s.app->HostRenderPass())->nativeLighting;nativePass.sequence=s.signalSequence;nativePass.sceneRevision=s.signalRevision;
        if (glfwWindowShouldClose(dm->GetWindow())) {static_cast<HostPathTracer*>(s.app->HostRenderPass())->AbortHostRead();return 0;}
        static_cast<HostPathTracer*>(s.app->HostRenderPass())->UpdateLightingPolicy();
        auto before=s.externalPresentation?s.rendered:s.presented;
        if(s.presentationTrace)s.probeMark=Session::ProbeClock::now();
        if (!dm->HostFrame()) throw std::runtime_error("RTXPT render/present failed");
        if(static_cast<HostPathTracer*>(s.app->HostRenderPass())->hostSurface.active&&s.rendered==before){
            static_cast<HostPathTracer*>(s.app->HostRenderPass())->AbortHostRead();
            throw std::runtime_error("Host-surface input was not consumed; the published input remains available for retry");
        }
        if(RtrMemoryDiagnostics::Enabled()) {
            auto now=std::chrono::steady_clock::now();
            if(s.rendered<=3 || now-s.memoryReport>=std::chrono::seconds(1)) {
                RtrMemoryDiagnostics::Checkpoint(s.rendered<=3?"frame.startup":"frame.steady",
                    static_cast<ID3D12Device*>(dm->GetDevice()->getNativeObject(nvrhi::ObjectTypes::D3D12_Device)));
                auto& pass=*static_cast<HostPathTracer*>(s.app->HostRenderPass());auto& sparse=pass.sparseLighting;auto& attributes=pass.nativeLighting;
                RtrMemoryDiagnostics::Capacity("sparse.records","cpu_payload",sparse.records.size()*sizeof(RtrSparseLighting::Record),sparse.records.capacity()*sizeof(RtrSparseLighting::Record));
                RtrMemoryDiagnostics::Capacity("sparse.tree","cpu_payload",sparse.tree.nodes.size()*64,sparse.tree.CpuBytes());
                RtrMemoryDiagnostics::Capacity("sparse.dirty","cpu_payload",(sparse.dirtyNodes.size()+sparse.dirtyRecords.size())*4,(sparse.dirtyNodes.capacity()+sparse.dirtyRecords.capacity())*4);
                RtrMemoryDiagnostics::Capacity("sparse.regions","cpu_payload",sparse.regions.packed.size()*64,sparse.regions.CpuBytes());
                uint64_t positionLive=0,positionCapacity=0;
                std::unordered_set<const donut::engine::BufferGroup*> positionOwners;
                for(const auto& [name,mesh]:pass.residentMeshes)if(mesh->buffers&&positionOwners.insert(mesh->buffers.get()).second){
                    const auto& positions=mesh->buffers->positionData;positionLive+=positions.size()*sizeof(positions[0]);positionCapacity+=positions.capacity()*sizeof(positions[0]);
                }
                RtrMemoryDiagnostics::Capacity("mesh.positions","cpu_payload",positionLive,positionCapacity);
                auto bufferBytes=[](const nvrhi::BufferHandle& b)->uint64_t{return b?b->getDesc().byteSize:0;};
                uint64_t retiredBytes=0;for(const auto& old:sparse.retired)retiredBytes+=bufferBytes(old.sources)+bufferBytes(old.nodes);
                RtrMemoryDiagnostics::Capacity("sparse.buffers","device_local",sparse.records.size()*96+(sparse.queryNodes.size()+sparse.regions.packed.size())*64,bufferBytes(sparse.sourceBuffer)+bufferBytes(sparse.treeBuffer),retiredBytes);
                RtrMemoryDiagnostics::Capacity("attributes.vertices","device_local",attributes.attributeRanges.end*sizeof(RtrNativeLightingPass::VertexAttributes),bufferBytes(attributes.baselineCoordinates));
                const auto resource=pass.resourceArena.Snapshot();const auto upload=pass.uploadArena.Statistics();
                RtrMemoryDiagnostics::Capacity("resource.arena","device_local",resource[2],resource[1],0,pass.resourceArena.IdlePageBytes());
                RtrMemoryDiagnostics::Capacity("upload.arena","mapped_upload",upload[2],upload[0],0,pass.uploadArena.IdlePageBytes());
                s.memoryReport=now;
            }
        }
        s.reportPresentation();
        return (s.externalPresentation?s.rendered:s.presented)>before ? 2 : 1;
    } catch (const std::exception& x) {
        // A readiness wait alone is not a GPU read. Restore publication if Render was never entered.
        try{CheckRtrDevice(session(h).app->HostDeviceManager()->GetDevice());}catch(...){}
        try{static_cast<HostPathTracer*>(session(h).app->HostRenderPass())->AbortHostRead();}catch(...){}
        fail(e,x);return 0;
    }
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
        int width=0,height=0;s.app->HostDeviceManager()->GetWindowDimensions(width,height);
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
        const std::string scenePath=path?utf8(e,path):"__rtr_memory_scene__";
        static_cast<HostPathTracer*>(s.app->HostRenderPass())->pendingEmptyScene=path==nullptr;
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
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_sparseLighting(
    JNIEnv* e,jclass,jlong h,jlongArray ids,jfloatArray records,jlongArray removals,jboolean reset){
    try{
        if(!ids||!records||!removals)throw std::invalid_argument("Missing sparse source arrays");
        size_t count=e->GetArrayLength(ids),removed=e->GetArrayLength(removals);
        if(count>RtrSparseLighting::Maximum||removed>RtrSparseLighting::Maximum||e->GetArrayLength(records)!=count*RtrSparseLighting::Stride)
            throw std::invalid_argument("Invalid sparse source array sizes");
        std::vector<int64_t> keys(count),dead(removed);std::vector<float> values(count*RtrSparseLighting::Stride);
        e->GetLongArrayRegion(ids,0,jsize(count),reinterpret_cast<jlong*>(keys.data()));
        e->GetLongArrayRegion(removals,0,jsize(removed),reinterpret_cast<jlong*>(dead.data()));
        e->GetFloatArrayRegion(records,0,jsize(values.size()),values.data());if(e->ExceptionCheck())return;
        static_cast<HostPathTracer*>(session(h).app->HostRenderPass())->ApplySparseLighting(keys,values,dead,reset);
    }catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT jlongArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_sparseLightingTelemetry(JNIEnv* e,jclass,jlong h){
    try{const auto& sparse=static_cast<HostPathTracer*>(session(h).app->HostRenderPass())->sparseLighting;
        std::array<uint64_t,17> values;std::copy(sparse.work.begin(),sparse.work.end(),values.begin());std::copy(sparse.regions.work.begin(),sparse.regions.work.end(),values.begin()+8);
        auto result=e->NewLongArray(jsize(values.size()));if(result)e->SetLongArrayRegion(result,0,jsize(values.size()),reinterpret_cast<const jlong*>(values.data()));return result;
    }catch(const std::exception& x){fail(e,x);return nullptr;}
}
extern "C" JNIEXPORT jlongArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_uploadMemory(JNIEnv* e,jclass,jlong h){
    try{auto& pass=*static_cast<HostPathTracer*>(session(h).app->HostRenderPass());
        std::array<uint64_t,12> values{};const auto pool=RtrUploadRetention::Snapshot();const auto arena=pass.uploadArena.Statistics();
        std::copy(pool.begin(),pool.end(),values.begin());std::copy(arena.begin(),arena.end(),values.begin()+8);
        auto result=e->NewLongArray(jsize(values.size()));if(result)e->SetLongArrayRegion(result,0,jsize(values.size()),reinterpret_cast<const jlong*>(values.data()));return result;
    }catch(const std::exception& x){fail(e,x);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLighting(JNIEnv* e,jclass,jlong h,jfloatArray parameters){
    try{auto& s=session(h);if(!s.externalPresentation)throw std::invalid_argument("Native lighting requires an offscreen RTXPT session");
        if(!parameters||e->GetArrayLength(parameters)!=80)throw std::invalid_argument("Invalid native-lighting ABI v1 parameters");
        std::vector<float> values(80);e->GetFloatArrayRegion(parameters,0,80,values.data());if(e->ExceptionCheck())return;
        static_cast<HostPathTracer*>(s.app->HostRenderPass())->nativeLighting.Configure(values);
    }catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingReconstruction(JNIEnv* e,jclass,jlong h,jboolean enabled,jboolean reset){
    try{auto& pass=static_cast<HostPathTracer*>(session(h).app->HostRenderPass())->nativeLighting;
        if(enabled){
            const auto* words=reinterpret_cast<const uint32_t*>(pass.constants.words.data());
            if(!pass.active||!(words[42]&2)||pass.diagnosticDecomposition||!pass.extensionIdentity.empty())throw std::invalid_argument("GI RR requires the default fused native INDIRECT_DIFFUSE evaluator");
            if(!donut::app::StreamlineIntegration::Get().IsDLSSRRAvailable())throw std::runtime_error("Native diffuse GI RR unavailable on this device");
        }
        auto& rr=pass.reconstruction;if(rr.requested!=bool(enabled)||reset)rr.reset=true;rr.requested=enabled;
        if(!enabled){rr.valid=false;rr.guides={};rr.reducedGuides={};rr.surfaces=nullptr;rr.reducedRaw=nullptr;
            rr.reconstructed=nullptr;rr.result=nullptr;rr.resolveBindings.value=nullptr;rr.reduceBindings.value=nullptr;rr.upsampleBindings.value=nullptr;
            rr.width=rr.height=rr.evaluationWidth=rr.evaluationHeight=rr.allocatedDivisor=0;rr.resourceBytes=0;}
    }catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT jlongArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingReconstructionState(JNIEnv* e,jclass,jlong h){
    try{const auto& rr=static_cast<HostPathTracer*>(session(h).app->HostRenderPass())->nativeLighting.reconstruction;
        const jlong values[]{rr.requested&&rr.valid?1:0,rr.width,rr.height,jlong(rr.evaluated),jlong(rr.resets),rr.valid?1:0,rr.requested?1:0};
        auto result=e->NewLongArray(7);if(result)e->SetLongArrayRegion(result,0,7,values);return result;
    }catch(const std::exception& x){fail(e,x);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingReconstructionScale(JNIEnv* e,jclass,jlong h,jint divisor){
    try{if(divisor!=1&&divisor!=2)throw std::invalid_argument("GI RR divisor must be 1 or 2");
        auto& pass=static_cast<HostPathTracer*>(session(h).app->HostRenderPass())->nativeLighting;auto& rr=pass.reconstruction;
        if(rr.divisor!=uint32_t(divisor)){rr.divisor=uint32_t(divisor);rr.valid=false;rr.reset=true;pass.retainedSignals=false;}
    }catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT jlongArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingReconstructionGrid(JNIEnv* e,jclass,jlong h){
    try{const auto& rr=static_cast<HostPathTracer*>(session(h).app->HostRenderPass())->nativeLighting.reconstruction;
        const jlong values[]{rr.divisor,rr.evaluationWidth,rr.evaluationHeight,rr.width,rr.height,jlong(rr.resourceBytes)};
        auto result=e->NewLongArray(6);if(result)e->SetLongArrayRegion(result,0,6,values);return result;
    }catch(const std::exception& x){fail(e,x);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingBaseline(JNIEnv* e,jclass,jlong h,jstring name,jfloatArray coordinates){
    try{if(!name||!coordinates)throw std::invalid_argument("Missing native baseline coordinates");
        const auto key=utf8(e,name);auto count=e->GetArrayLength(coordinates);if(count%6!=0)throw std::invalid_argument("Baseline requires three float2 coordinates per triangle");
        std::vector<float2> values(size_t(count)/2);if(count)e->GetFloatArrayRegion(coordinates,0,count,reinterpret_cast<float*>(values.data()));if(e->ExceptionCheck())return;
        for(auto value:values)if(!std::isfinite(value.x)||!std::isfinite(value.y)||value.x<0||value.x>1||value.y<0||value.y>1)throw std::invalid_argument("Baseline coordinates must be in [0,1]");
        auto& host=*static_cast<HostPathTracer*>(session(h).app->HostRenderPass());bool found=false;auto& pass=host.nativeLighting;
        if(values.size()!=host.ValidateGeometry(key))throw std::invalid_argument("Baseline layout mismatch");
        host.nativeLighting.meshBaselines[key]=std::move(values);host.nativeLighting.dirtyBaselines.insert(key);
    }catch(const std::exception& x){fail(e,x);}
}
static thread_local std::unique_ptr<RtrDiagnostics::Scope> attributePreparationScope;
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_diagnosticAttributesBegin(JNIEnv* e,jclass,jlong h){
    try{session(h);if(RtrDiagnostics::level.load()!=0)attributePreparationScope=std::make_unique<RtrDiagnostics::Scope>(RtrDiagnostics::AttributePreparation);}catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_diagnosticAttributesEnd(JNIEnv*,jclass){attributePreparationScope.reset();}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingAttributeBatch(
    JNIEnv* e,jclass,jlong h,jlong revision,jobjectArray names,jobjectArray baselines,jobjectArray colors){
    try{RtrDiagnostics::Scope scope(RtrDiagnostics::AttributeValidation);
        auto& host=*static_cast<HostPathTracer*>(session(h).app->HostRenderPass());auto& pass=host.nativeLighting;
        if(revision<0||uint64_t(revision)<host.resourceRevision||!names||!baselines||!colors)throw std::invalid_argument("Stale attribute resource revision");
        const auto count=e->GetArrayLength(names);
        if(e->GetArrayLength(baselines)!=count||e->GetArrayLength(colors)!=count)throw std::invalid_argument("Attribute batch size mismatch");
        struct Record{std::string key;std::vector<float2> baseline;std::vector<uint32_t> color;bool b=false,c=false;};
        std::vector<Record> records;records.reserve(count);std::unordered_set<std::string> seen;
        for(jsize i=0;i<count;i++){
            auto name=static_cast<jstring>(e->GetObjectArrayElement(names,i));if(!name)throw std::invalid_argument("Missing geometry identity");
            Record record;record.key=utf8(e,name);e->DeleteLocalRef(name);
            if(!seen.insert(record.key).second)throw std::invalid_argument("Duplicate batch geometry");
            const auto corners=host.ValidateGeometry(record.key);
            auto b=static_cast<jfloatArray>(e->GetObjectArrayElement(baselines,i));auto c=static_cast<jintArray>(e->GetObjectArrayElement(colors,i));
            if(b){record.b=true;if(uint64_t(e->GetArrayLength(b))!=uint64_t(corners)*2)throw std::invalid_argument("Baseline layout mismatch");
                record.baseline.resize(corners);e->GetFloatArrayRegion(b,0,jsize(corners*2),reinterpret_cast<float*>(record.baseline.data()));e->DeleteLocalRef(b);
                for(auto value:record.baseline)if(!std::isfinite(value.x)||!std::isfinite(value.y)||value.x<0||value.x>1||value.y<0||value.y>1)throw std::invalid_argument("Invalid baseline value");}
            if(c){record.c=true;if(uint32_t(e->GetArrayLength(c))!=corners)throw std::invalid_argument("Color layout mismatch");
                record.color.resize(corners);e->GetIntArrayRegion(c,0,jsize(corners),reinterpret_cast<jint*>(record.color.data()));e->DeleteLocalRef(c);}
            if(e->ExceptionCheck())return;records.push_back(std::move(record));
        }
        // Validation covers the entire batch before any accepted attribute is replaced.
        for(auto& r:records){if(r.b){pass.meshBaselines[r.key]=std::move(r.baseline);pass.dirtyBaselines.insert(r.key);}
            if(r.c){bool alpha=std::any_of(r.color.begin(),r.color.end(),[](uint32_t v){return (v>>24)!=255;});
                pass.meshColors[r.key]=std::move(r.color);pass.dirtyColors.insert(r.key);if(alpha)pass.alphaMeshes.insert(r.key);else pass.alphaMeshes.erase(r.key);}}
        if(RtrDiagnostics::level.load()!=0)++host.dynamicWork[4];
    }catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingAttributes(
    JNIEnv* e,jclass,jlong h,jlong generation,jobjectArray names,jfloatArray attributes,jfloatArray lookup,jbyteArray application){
    try{
        auto& s=session(h);auto& host=*static_cast<HostPathTracer*>(s.app->HostRenderPass());auto& pass=host.nativeLighting;
        if(generation<0||uint64_t(generation)<pass.publication||!names||!attributes)throw std::invalid_argument("Invalid native attribute generation or arrays");
        const auto count=e->GetArrayLength(names);if(e->GetArrayLength(attributes)!=count*8)throw std::invalid_argument("Instance attributes require eight words each");
        std::vector<RtrNativeLightingPass::InstanceAttributes> values(count);if(count)e->GetFloatArrayRegion(attributes,0,count*8,reinterpret_cast<float*>(values.data()));if(e->ExceptionCheck())return;
        std::vector<std::string> keys;keys.reserve(count);std::unordered_set<std::string> seen;
        for(jsize i=0;i<count;i++){
            auto name=static_cast<jstring>(e->GetObjectArrayElement(names,i));if(!name)throw std::invalid_argument("Missing native attribute instance name");
            auto key=utf8(e,name);e->DeleteLocalRef(name);if(!seen.insert(key).second)throw std::invalid_argument("Duplicate native attribute instance");
            if(!s.nodes.count(key))throw std::invalid_argument("Unknown native attribute instance: "+key);
            auto* words=reinterpret_cast<const float*>(&values[i]);for(unsigned j=0;j<8;j++)if(!std::isfinite(words[j]))throw std::invalid_argument("Nonfinite instance attribute");
            if(words[0]<0||words[0]>31||words[0]!=float(uint32_t(words[0]))||words[1]<0||words[1]>1||words[2]<0||words[2]>1)throw std::invalid_argument("Invalid role or visibility attributes");
            keys.push_back(std::move(key));
        }
        auto nextLookup=pass.baselineLookup;
        if(lookup){const auto length=e->GetArrayLength(lookup);
            if(length!=512&&length!=1280)throw std::invalid_argument("Baseline lookup requires 512 floats, optionally followed by 768 host RGB response floats");
            e->GetFloatArrayRegion(lookup,0,length,reinterpret_cast<float*>(nextLookup.data()));if(e->ExceptionCheck())return;
            for(auto value:nextLookup)if(!std::isfinite(value.x)||!std::isfinite(value.y)||value.x<0||value.x>1||value.y<0||value.y>1)throw std::invalid_argument("Invalid baseline or host response lookup value");}
        std::vector<uint8_t> bytes;
        if(application){const auto length=e->GetArrayLength(application);if(length%4!=0||length>16*1024*1024)throw std::invalid_argument("Application publication exceeds its aligned capacity");
            bytes.resize(length);if(length)e->GetByteArrayRegion(application,0,length,reinterpret_cast<jbyte*>(bytes.data()));if(e->ExceptionCheck())return;}
        for(jsize i=0;i<count;i++){auto previous=pass.instanceAttributes.find(keys[i]);if(previous!=pass.instanceAttributes.end()&&previous->second.rolesVisibility.x!=23)--pass.specialRoleCount;
            if(values[i].rolesVisibility.x!=23)++pass.specialRoleCount;pass.instanceAttributes[keys[i]]=values[i];pass.dirtyInstances.insert(keys[i]);}
        if(lookup){pass.baselineLookup=nextLookup;pass.lookupDirty=true;}
        if(application){pass.applicationData=std::move(bytes);pass.applicationDirty=true;}
        pass.publication=uint64_t(generation);
    }catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingVertexColors(JNIEnv* e,jclass,jlong h,jstring name,jintArray colors){
    try{if(!name||!colors)throw std::invalid_argument("Missing native vertex colors");auto key=utf8(e,name);auto count=e->GetArrayLength(colors);
        if(count%3!=0)throw std::invalid_argument("Vertex colors require three RGBA8 values per triangle");
        auto& host=*static_cast<HostPathTracer*>(session(h).app->HostRenderPass());auto& pass=host.nativeLighting;
        if(uint32_t(count)!=host.ValidateGeometry(key))throw std::invalid_argument("Vertex color layout mismatch");
        std::vector<uint32_t> values(count);if(count)e->GetIntArrayRegion(colors,0,count,reinterpret_cast<jint*>(values.data()));if(e->ExceptionCheck())return;
        bool alpha=std::any_of(values.begin(),values.end(),[](uint32_t value){return (value>>24)!=255;});
        pass.meshColors[key]=std::move(values);pass.dirtyColors.insert(key);if(alpha)pass.alphaMeshes.insert(key);else pass.alphaMeshes.erase(key);
    }catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingClearAttributes(JNIEnv* e,jclass,jlong h){
    try{auto& pass=static_cast<HostPathTracer*>(session(h).app->HostRenderPass())->nativeLighting;
        if(RtrDiagnostics::level.load()!=0)++static_cast<HostPathTracer*>(session(h).app->HostRenderPass())->dynamicWork[15];
        pass.instanceAttributes.clear();pass.meshBaselines.clear();pass.meshColors.clear();pass.alphaMeshes.clear();pass.dirtyInstances.clear();pass.dirtyBaselines.clear();pass.dirtyColors.clear();pass.metadataDirty=true;pass.metadataInitialized=false;pass.specialInstances.clear();pass.specialRoleCount=0;
    }catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingClock(JNIEnv* e,jclass,jlong h,jlong time,jlong delta){
    try{if(delta<0)throw std::invalid_argument("Frame delta must be nonnegative");auto& pass=static_cast<HostPathTracer*>(session(h).app->HostRenderPass())->nativeLighting;
        pass.timeNanos=time;pass.deltaNanos=delta;}catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingExtensionValidate(JNIEnv* e,jclass,jstring manifest,jint signals,jint bytes){
    try{auto extension=RtrNativeExtension::Load(std::filesystem::u8path(utf8(e,manifest)),uint32_t(signals),size_t(bytes));
        auto result=e->NewObjectArray(3,e->FindClass("java/lang/String"),nullptr);
        std::array<std::string,3> values={extension.identity,std::to_string(extension.signals),std::to_string(extension.minimumBytes)};
        for(jsize i=0;i<3;i++){auto text=e->NewStringUTF(values[i].c_str());e->SetObjectArrayElement(result,i,text);e->DeleteLocalRef(text);}return result;
    }catch(const std::exception& x){fail(e,x);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingExtension(JNIEnv* e,jclass,jlong h,jstring manifest,jstring expected){
    try{auto& s=session(h);auto& host=*static_cast<HostPathTracer*>(s.app->HostRenderPass());
        if(!manifest){host.nativeLighting.ResetExtension(s.app->HostDeviceManager()->GetDevice());return;}
        if(host.nativeLighting.reconstruction.requested)throw std::invalid_argument("Disable GI RR before activating a raw extension evaluator");
        auto& pass=host.nativeLighting;auto* words=reinterpret_cast<const uint32_t*>(pass.constants.words.data());
        auto extension=RtrNativeExtension::Load(std::filesystem::u8path(utf8(e,manifest)),words[42],pass.applicationData.size());
        if(extension.identity!=utf8(e,expected))throw std::invalid_argument("Extension artifact changed after validation");host.ActivateLightingExtension(extension);
    }catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT jlongArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingTelemetry(JNIEnv* e,jclass,jlong h){
    try{const auto& pass=static_cast<HostPathTracer*>(session(h).app->HostRenderPass())->nativeLighting;
        std::array<jlong,8> values={pass.active?1:0,pass.width,pass.height,pass.mask,jlong(pass.executedFrames),jlong(pass.beautyFrames),jlong(pass.metadataUploads),jlong(pass.publication)};
        auto result=e->NewLongArray(jsize(values.size()));if(result)e->SetLongArrayRegion(result,0,jsize(values.size()),values.data());return result;
    }catch(const std::exception& x){fail(e,x);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingWorkEnabled(JNIEnv* e,jclass,jlong h,jboolean enabled){
    try{auto& pass=static_cast<HostPathTracer*>(session(h).app->HostRenderPass())->nativeLighting;pass.workStatisticsRequested=enabled;pass.workStatisticsEnabled=enabled&&pass.diagnosticsEnabled&&RtrDiagnostics::level.load()==2;}catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT jlongArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingUpdateStatistics(JNIEnv* e,jclass,jlong h){
    try{const auto& pass=static_cast<HostPathTracer*>(session(h).app->HostRenderPass())->nativeLighting;
        jlong values[11]{};for(size_t i=0;i<5;i++)values[i]=jlong(pass.attributeWork[i]);values[5]=pass.workStatisticsRequested;values[6]=pass.workStatisticsEnabled;
        for(const auto& slot:pass.workReadbacks)values[7]+=slot.pending;values[8]=jlong(pass.workCopies);values[9]=jlong(pass.workQueries);values[10]=jlong(pass.workPolls);
        auto result=e->NewLongArray(11);if(result)e->SetLongArrayRegion(result,0,11,values);return result;
    }catch(const std::exception& x){fail(e,x);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingDecomposition(JNIEnv* e,jclass,jlong h,jboolean enabled){
    try{auto& pass=static_cast<HostPathTracer*>(session(h).app->HostRenderPass())->nativeLighting;if(enabled&&(!pass.extensionIdentity.empty()||pass.reconstruction.requested))throw std::invalid_argument("Diagnostic decomposition requires raw default evaluator");pass.diagnosticDecomposition=enabled;}catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT jlongArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingWork(JNIEnv* e,jclass,jlong h){
    try{auto& s=session(h);auto& pass=static_cast<HostPathTracer*>(s.app->HostRenderPass())->nativeLighting;pass.PollWork(s.app->HostDeviceManager()->GetDevice());
        auto result=e->NewLongArray(jsize(pass.latestWork.size()));if(result)e->SetLongArrayRegion(result,0,jsize(pass.latestWork.size()),reinterpret_cast<const jlong*>(pass.latestWork.data()));return result;
    }catch(const std::exception& x){fail(e,x);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_hostSurfacePlan(JNIEnv* e,jclass,jlong h,jint width,jint height,jint composition,jint components,jboolean jitter,jlong budget){
    try{auto& s=session(h);if(!s.externalPresentation)throw std::invalid_argument("Host surfaces require external presentation");
        auto* host=static_cast<HostPathTracer*>(s.app->HostRenderPass());if(host->nativeLighting.active)throw std::logic_error("Independent primary tracing is already selected");
        RtrSurfaceInputs::Plan plan{uint32_t(width),uint32_t(height),uint32_t(composition),uint32_t(components),bool(jitter),uint64_t(budget)};
        s.surfaceInputs.Configure(s.app->HostDeviceManager()->GetDevice(),plan);
        host->hostSurface.inputWidth=width;host->hostSurface.inputHeight=height;host->hostSurface.composition=composition;host->hostSurface.hostComponents=components;
    }catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT jlongArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_hostSurfaceAcquire(JNIEnv* e,jclass,jlong h,jlong sequence,jlong revision,jlong resource,jlong view,jlong time,jlong delta){
    try{auto packet=session(h).surfaceInputs.TryAcquire({uint64_t(sequence),uint64_t(revision),uint64_t(resource),uint64_t(view),time,delta});
        auto words=packet?RtrSurfaceInputs::Encode(*packet):std::vector<int64_t>{};auto result=e->NewLongArray(jsize(words.size()));if(result&&!words.empty())e->SetLongArrayRegion(result,0,jsize(words.size()),words.data());return result;
    }catch(const std::exception& x){fail(e,x);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_hostSurfaceInputAction(JNIEnv* e,jclass,jlong h,jlong generation,jint slot,jlong ticket,jint action){
    try{auto& inputs=session(h).surfaceInputs;RtrSurfaceInputs::Token token{uint64_t(generation),uint32_t(slot),uint64_t(ticket)};
        switch(action){case 0:inputs.MarkImported(token);break;case 1:inputs.Publish(token);break;case 2:inputs.CancelUnimported(token);break;case 3:inputs.AbandonReady(token);break;default:throw std::invalid_argument("Unknown host input lease action");}
    }catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_hostSurfaceDiscard(JNIEnv* e,jclass,jlong h,jlong generation,jlong sequence){
    try{session(h).surfaceInputs.DiscardPublished(uint64_t(generation),uint64_t(sequence));}catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT jlongArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_hostSurfaceStatus(JNIEnv* e,jclass,jlong h){
    try{auto words=session(h).surfaceInputs.Status();auto result=e->NewLongArray(jsize(words.size()));if(result)e->SetLongArrayRegion(result,0,jsize(words.size()),reinterpret_cast<const jlong*>(words.data()));return result;}catch(const std::exception& x){fail(e,x);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_hostSurfaceRead(JNIEnv* e,jclass,jlong h,jlong sequence,jlong revision,jlong resource,jlong view,jlong time,jlong delta){
    try{auto& s=session(h);static_cast<HostPathTracer*>(s.app->HostRenderPass())->BeginHostRead(s.surfaceInputs,{uint64_t(sequence),uint64_t(revision),uint64_t(resource),uint64_t(view),time,delta});}catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_hostSurfaceTriangles(JNIEnv* e,jclass,jlong h,jstring mesh,jintArray mapping){
    try{if(!mapping||e->GetArrayLength(mapping)%2)throw std::invalid_argument("Malformed original-triangle mapping");
        std::vector<RtrHostSurface::Triangle> values(size_t(e->GetArrayLength(mapping))/2);if(!values.empty())e->GetIntArrayRegion(mapping,0,e->GetArrayLength(mapping),reinterpret_cast<jint*>(values.data()));if(e->ExceptionCheck())return;
        auto& state=static_cast<HostPathTracer*>(session(h).app->HostRenderPass())->hostSurface;state.meshTriangles.insert_or_assign(utf8(e,mesh),std::move(values));state.dirty=true;
    }catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_hostSurfaceMappingDirty(JNIEnv* e,jclass,jlong h){
    try{static_cast<HostPathTracer*>(session(h).app->HostRenderPass())->hostSurface.dirty=true;}catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT jlongArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_hostSurfaceFrame(JNIEnv* e,jclass,jlong h){
    try{auto& s=session(h);auto words=s.surfaceFrames.Acquire();
        auto result=e->NewLongArray(jsize(words.size()));if(result&&!words.empty())e->SetLongArrayRegion(result,0,jsize(words.size()),words.data());return result;
    }catch(const std::exception& x){fail(e,x);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_hostSurfaceRelease(JNIEnv* e,jclass,jlong h,jint slot,jlong generation,jlong sequence,jboolean imported){
    try{auto& s=session(h);s.surfaceFrames.Release(s.app->HostDeviceManager()->GetDevice(),slot,uint64_t(generation),uint64_t(sequence),imported);}catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT jboolean JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_hostSurfaceCanPublish(JNIEnv* e,jclass,jlong h){
    try{return session(h).surfaceFrames.CanPublish();}catch(const std::exception& x){fail(e,x);return false;}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_hostSurfaceOutputs(JNIEnv* e,jclass,jlong h,jint mask){
    try{if(mask<1||mask>255)throw std::invalid_argument("Unknown host output signal mask");
        auto* host=static_cast<HostPathTracer*>(session(h).app->HostRenderPass());
        if(host->hostOutputs.mask!=uint32_t(mask)){host->hostOutputs.mask=uint32_t(mask);host->InvalidateFrameCache();}}
    catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_hostSurfaceCapture(JNIEnv* e,jclass,jlong h,jint signal,jstring path){
    try{static_cast<HostPathTracer*>(session(h).app->HostRenderPass())->CaptureLinear(std::filesystem::u8path(utf8(e,path)),0,signal);}
    catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_hostSurfaceAugmentation(JNIEnv* e,jclass,jlong h,jfloatArray parameters,jint width,jint height,jint outputs,jint domain){
    try{if(!parameters||e->GetArrayLength(parameters)!=80)throw std::invalid_argument("Host augmentation requires the complete native style ABI");
        std::vector<float> values(80);e->GetFloatArrayRegion(parameters,0,80,values.data());if(e->ExceptionCheck())return;
        auto* host=static_cast<HostPathTracer*>(session(h).app->HostRenderPass());
        RtrHostAugmentation::Plan plan;plan.width=uint32_t(width);plan.height=uint32_t(height);plan.outputs=uint32_t(outputs);plan.colorDomain=uint32_t(domain);
        host->hostAugmentation.Configure(host->nativeLighting,plan,values);
    }catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT jboolean JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_hostSurfaceConsumersRetired(JNIEnv* e,jclass,jlong h){
    try{return session(h).surfaceFrames.ConsumersRetired();}catch(const std::exception& x){fail(e,x);return false;}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_hostSurfaceRequireRetired(JNIEnv* e,jclass,jlong h){
    try{session(h).surfaceInputs.RequireReleased();}catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_hostSurfaceWorkEnabled(JNIEnv* e,jclass,jlong h,jboolean enabled){
    try{auto& pass=static_cast<HostPathTracer*>(session(h).app->HostRenderPass())->hostSurface;pass.workStatisticsRequested=enabled;pass.workStatisticsEnabled=enabled&&pass.diagnosticsEnabled&&RtrDiagnostics::level.load()==2;}catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT jlongArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_hostSurfaceWork(JNIEnv* e,jclass,jlong h){
    try{auto& s=session(h);auto& state=static_cast<HostPathTracer*>(s.app->HostRenderPass())->hostSurface;state.PollWork(s.app->HostDeviceManager()->GetDevice());
        auto& words=state.latestWork;auto result=e->NewLongArray(jsize(words.size()));if(result)e->SetLongArrayRegion(result,0,jsize(words.size()),reinterpret_cast<const jlong*>(words.data()));return result;
    }catch(const std::exception& x){fail(e,x);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_hostSurfaceView(JNIEnv* e,jclass,jlong h,jfloatArray matrices){
    try{if(!matrices||e->GetArrayLength(matrices)!=70)throw std::invalid_argument("Malformed exact host view");std::vector<float> values(70);e->GetFloatArrayRegion(matrices,0,70,values.data());if(e->ExceptionCheck())return;
        static_cast<HostPathTracer*>(session(h).app->HostRenderPass())->ConfigureHostView(values);
    }catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT jboolean JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingCanPublish(JNIEnv* e,jclass,jlong h){
    try{return session(h).signalFrames.CanPublish();}catch(const std::exception& x){fail(e,x);return false;}
}
extern "C" JNIEXPORT jboolean JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingConsumersRetired(JNIEnv* e,jclass,jlong h){
    try{return session(h).signalFrames.ConsumersRetired();}catch(const std::exception& x){fail(e,x);return false;}
}
extern "C" JNIEXPORT jlongArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingFrame(JNIEnv* e,jclass,jlong h){
    try{auto values=session(h).signalFrames.Acquire();auto result=e->NewLongArray(jsize(values.size()));if(result&&!values.empty())e->SetLongArrayRegion(result,0,jsize(values.size()),values.data());return result;}
    catch(const std::exception& x){fail(e,x);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingRelease(JNIEnv* e,jclass,jlong h,jint slot,jlong generation,jlong sequence,jboolean imported){
    try{auto& s=session(h);s.signalFrames.Release(s.app->HostDeviceManager()->GetDevice(),slot,uint64_t(generation),uint64_t(sequence),imported);}
    catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingCapture(JNIEnv* e,jclass,jlong h,jstring path,jint signal){
    try{if(signal<0||signal>18)throw std::invalid_argument("Unknown native-lighting diagnostic signal");
        static_cast<HostPathTracer*>(session(h).app->HostRenderPass())->CaptureLinear(std::filesystem::u8path(utf8(e,path)),signal);
    }catch(const std::exception& x){fail(e,x);}
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
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_lightingPatch(
    JNIEnv* e,jclass,jlong h,jlongArray ids,jfloatArray records,jlongArray removals,jfloatArray sun,jfloatArray sky){
    try{auto& s=session(h);if(!ids||!records||!removals||!sun||!sky)throw std::invalid_argument("Lighting patch arrays must not be null");
        size_t count=e->GetArrayLength(ids),removed=e->GetArrayLength(removals);
        if(count>size_t(NEEAT_MAX_LIGHTS)||removed>size_t(NEEAT_MAX_LIGHTS)||e->GetArrayLength(records)!=count*16||e->GetArrayLength(sun)!=16||e->GetArrayLength(sky)!=4)throw std::invalid_argument("Malformed lighting patch");
        std::vector<int64_t> keys(count),deleted(removed);std::vector<float> values(count*16);std::array<float,16> solar{};std::array<float,4> ambient{};
        e->GetLongArrayRegion(ids,0,jsize(count),reinterpret_cast<jlong*>(keys.data()));e->GetLongArrayRegion(removals,0,jsize(removed),reinterpret_cast<jlong*>(deleted.data()));
        e->GetFloatArrayRegion(records,0,jsize(values.size()),values.data());e->GetFloatArrayRegion(sun,0,16,solar.data());e->GetFloatArrayRegion(sky,0,4,ambient.data());if(e->ExceptionCheck())return;
        auto* host=static_cast<HostPathTracer*>(s.app->HostRenderPass());host->ApplyLightPatch(keys,values,deleted,solar,ambient);
        for(auto id:deleted)s.nodes.erase("l"+std::to_string(id));
        for(auto id:keys){auto slot=host->ManagedLightNode(id);s.nodes.insert_or_assign("l"+std::to_string(id),slot);}
    }catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT jlongArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_lightUpdateTelemetry(JNIEnv* e,jclass,jlong h){
    try{auto* host=static_cast<HostPathTracer*>(session(h).app->HostRenderPass());auto values=host->LightingWork();auto result=e->NewLongArray(jsize(values.size()));
        if(result)e->SetLongArrayRegion(result,0,jsize(values.size()),reinterpret_cast<const jlong*>(values.data()));return result;
    }catch(const std::exception& x){fail(e,x);return nullptr;}
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
    try{RtrDiagnostics::Scope resourceScope(RtrDiagnostics::Resources);auto& s=session(h);static_cast<HostPathTracer*>(s.app->HostRenderPass())->ApplyResources(std::filesystem::u8path(utf8(e,manifest)),s.nodes);}
    catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT jlongArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_resourceTelemetry(
    JNIEnv* e,jclass,jlong h) {
    try{auto* p=static_cast<HostPathTracer*>(session(h).app->HostRenderPass());
        auto t=p->ResourceTelemetry();
        auto result=e->NewLongArray(jsize(t.size()));if(result)e->SetLongArrayRegion(result,0,jsize(t.size()),t.data());return result;
    }catch(const std::exception& x){fail(e,x);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_resourcesMemory(
    JNIEnv* e,jclass,jlong h,jlong revision,jobject packet){
    try{RtrDiagnostics::Scope scope(RtrDiagnostics::Resources);auto& s=session(h);
        auto data=e->GetDirectBufferAddress(packet);auto size=e->GetDirectBufferCapacity(packet);
        if(!data||size<=0||size>512LL*1024*1024||revision<0)throw std::invalid_argument("Invalid resource packet");
        static_cast<HostPathTracer*>(s.app->HostRenderPass())->ApplyMemoryResources(data,size,uint64_t(revision),s.nodes);
    }catch(const RtrResourcePending& x){e->ThrowNew(e->FindClass("top/ceroxe/rt/renderer/rtxpt/RtxptResourcePendingException"),x.what());}
    catch(const std::exception& x){fail(e,x);}
}
static jint ResourceAdmission(jlong h){
    auto& p=*static_cast<HostPathTracer*>(session(h).app->HostRenderPass());
    {RtrDiagnostics::Scope observation(RtrDiagnostics::CompletionPoll);RtrRequireHealthy();CheckRtrDevice(p.GetDevice());}
    p.PollResourceRetirement();p.nativeLighting.PollRetired(p.GetDevice());
    if(p.resourceRetirement.size()>=16)return 1;
    if(!p.nativeLighting.reclaimer->Available())return 2;
    if(p.nativeLighting.retired.size()>=32)return 3;
    return 0;
}
extern "C" JNIEXPORT jint JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_resourcesAdmission(JNIEnv* e,jclass,jlong h){
    try{return ResourceAdmission(h);}catch(const std::exception& x){fail(e,x);return -1;}
}
extern "C" JNIEXPORT jboolean JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_resourcesCanUpdate(JNIEnv* e,jclass,jlong h){
    try{return ResourceAdmission(h)==0;}catch(const std::exception& x){fail(e,x);return false;}
}
extern "C" JNIEXPORT jlongArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_resourcePressure(JNIEnv* e,jclass,jlong h){
    try{auto& p=*static_cast<HostPathTracer*>(session(h).app->HostRenderPass());auto& q=*p.nativeLighting.reclaimer;
        uint64_t age=0;if(RtrDiagnostics::level.load()!=0&&!p.resourceRetirement.empty()){
            const auto tick=p.resourceRetirement.front().createdNanos;if(tick)age=RtrDiagnostics::Now()-tick;
        }
        jlong values[]={jlong(p.resourceRetirement.size()),jlong(q.pending.load()),jlong(q.pendingBytes.load()),jlong(q.peakPending.load()),
            jlong(q.peakBytes.load()),jlong(q.enqueueRejected.load()),jlong(p.spareMeshBufferBytes),jlong(p.spareMeshBlasBytes),jlong(q.OldestAge()),jlong(age),jlong(p.spareResourceTlasBytes)};
        auto result=e->NewLongArray(11);if(result)e->SetLongArrayRegion(result,0,11,values);return result;
    }catch(const std::exception& x){fail(e,x);return nullptr;}
}
extern "C" JNIEXPORT jboolean JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_deviceRemoved(JNIEnv* e,jclass,jlong h){
    try{return RtrDeviceRemoved(static_cast<ID3D12Device*>(session(h).app->HostDeviceManager()->GetDevice()->getNativeObject(nvrhi::ObjectTypes::D3D12_Device)));}
    catch(const std::exception& x){fail(e,x);return false;}
}
extern "C" JNIEXPORT jlongArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_resourceMaintenance(JNIEnv* e,jclass,jlong h){
    try{auto& p=*static_cast<HostPathTracer*>(session(h).app->HostRenderPass());auto& r=*p.nativeLighting.reclaimer;
        const jlong values[]={jlong(p.resourceBlases),jlong(p.resourceRefits),jlong(p.actualNativeTlasBuilds),jlong(p.textureGpuCopiedBytes),
            jlong(r.pending.load()),jlong(r.released.load()),jlong(r.releaseNanos.load())};
        auto result=e->NewLongArray(7);if(result)e->SetLongArrayRegion(result,0,7,values);return result;
    }catch(const std::exception& x){fail(e,x);return nullptr;}
}
extern "C" JNIEXPORT jlongArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_resourceArenaStatistics(JNIEnv* e,jclass,jlong h){
    try{auto& p=*static_cast<HostPathTracer*>(session(h).app->HostRenderPass());const auto arena=p.resourceArena.Snapshot();
        std::array<jlong,21> values{};std::copy(arena.begin(),arena.end(),values.begin());std::copy(p.arenaWork.begin(),p.arenaWork.end(),values.begin()+11);
        values[19]=p.spareMeshBuffers.size();values[20]=p.spareMeshBlases.size();
        auto result=e->NewLongArray(values.size());if(result)e->SetLongArrayRegion(result,0,values.size(),values.data());return result;
    }catch(const std::exception& x){fail(e,x);return nullptr;}
}
extern "C" JNIEXPORT jlongArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_dynamicResourceStatistics(JNIEnv* e,jclass,jlong h){
    try{auto& p=*static_cast<HostPathTracer*>(session(h).app->HostRenderPass());
        if(RtrDiagnostics::level.load()!=0){p.dynamicWork[16]=p.nativeLighting.attributeMigrationBytes;p.dynamicWork[17]=p.nativeLighting.attributeTableVisits;}
        auto result=e->NewLongArray(jsize(p.dynamicWork.size()));
        if(result)e->SetLongArrayRegion(result,0,jsize(p.dynamicWork.size()),reinterpret_cast<const jlong*>(p.dynamicWork.data()));return result;
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
        static_cast<HostPathTracer*>(session(h).app->HostRenderPass())->InvalidateFrameCache();
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
        static_cast<HostPathTracer*>(session(h).app->HostRenderPass())->InvalidateFrameCache();
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
            RtrSetInstanceTransform(checked[i],data+i*12);
        }
        if(count)static_cast<HostPathTracer*>(s.app->HostRenderPass())->InvalidateFrameCache();
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
        if(w>16384 || height>16384)throw std::invalid_argument("Output dimensions exceed D3D12 2D texture limit 16384");
        RtrDiagnostics::Scope diagnostic(RtrDiagnostics::Resize,uint64_t(w)*height);
        auto* dm=s.app->HostDeviceManager();
        if(s.externalPresentation){dm->HostOutputWidth=uint32_t(w);dm->HostOutputHeight=uint32_t(height);}
        else glfwSetWindowSize(dm->GetWindow(), w, height);
    } catch (const std::exception& x) { fail(e, x); }
}

extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_close(
    JNIEnv* e, jclass, jlong h) {
    std::lock_guard lock(lifecycle);
    try {
        auto& s=session(h);
        auto* nativeDevice=static_cast<ID3D12Device*>(s.app->HostDeviceManager()->GetDevice()->getNativeObject(nvrhi::ObjectTypes::D3D12_Device));
        if(!RtrDeviceRemoved(nativeDevice))s.surfaceInputs.RequireReleased();
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

extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_diagnosticSubmitBegin(JNIEnv* e,jclass,jlong h,jlong sequence,jlong revision,jlong elapsed){
    try{session(h);RtrDiagnostics::BeginSubmit(uint64_t(sequence),uint64_t(revision),uint64_t(std::max<jlong>(elapsed,0)));}catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_diagnosticSubmitDeferred(JNIEnv*,jclass){RtrDiagnostics::EndSubmit(false,true);}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_diagnosticSubmitEnd(JNIEnv*,jclass,jboolean failed){RtrDiagnostics::EndSubmit(failed);}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_diagnosticSubmitStage(JNIEnv*,jclass,jint stage,jlong offset,jlong duration,jlong items,jlong tasks){
    if(stage>=RtrDiagnostics::SubmitValidation&&stage<=RtrDiagnostics::SubmitRetirement)
        RtrDiagnostics::SubmitStage(RtrDiagnostics::Stage(stage),uint64_t(offset),uint64_t(duration),uint64_t(items),uint64_t(tasks));
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_diagnosticContext(JNIEnv* e,jclass,jlong h,jlong frame,jlong revision) {
    try {auto& s=session(h);s.signalSequence=uint64_t(frame);s.signalRevision=uint64_t(revision);RtrDiagnostics::Context(uint64_t(frame),uint64_t(revision));}catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_diagnosticLevel(JNIEnv* e,jclass,jlong h,jint level) {
    try {auto& s=session(h);if(level<0||level>2)throw std::invalid_argument("Diagnostic level must be 0..2");RtrDiagnostics::Configure(unsigned(level));RtrMemoryDiagnostics::Configure(level!=0);s.gpuTimingsEnabled=level!=0&&s.gpuTimingsAllowed;RtrGpuProfile::Configure(s.app->HostDeviceManager()->GetDevice(),unsigned(level),s.gpuTimingsAllowed);
        auto& host=*static_cast<HostPathTracer*>(s.app->HostRenderPass());host.nativeLighting.diagnosticsEnabled=host.hostSurface.diagnosticsEnabled=level!=0;
        RtrProtocolTrace::enabled.store(level!=0,std::memory_order_relaxed);
        host.nativeLighting.workStatisticsEnabled=level==2&&host.nativeLighting.workStatisticsRequested;
        host.hostSurface.workStatisticsEnabled=level==2&&host.hostSurface.workStatisticsRequested;}
    catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT jlongArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_diagnosticSnapshot(JNIEnv* e,jclass,jlong h) {
    try {session(h);auto values=RtrDiagnostics::Snapshot();auto result=e->NewLongArray(jsize(values.size()));
        if(result)e->SetLongArrayRegion(result,0,jsize(values.size()),reinterpret_cast<const jlong*>(values.data()));return result;}
    catch(const std::exception& x){fail(e,x);return nullptr;}
}

extern "C" JNIEXPORT jlong JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_diagnosticMonitorToken(JNIEnv* e,jclass,jlong h) {
    try {if(h)session(h);else if(!diagnosticsPrepared)throw std::logic_error("Prepare diagnostics before observing initialization");return jlong(RtrDiagnostics::MonitorToken());}catch(const std::exception& x){fail(e,x);return 0;}
}
extern "C" JNIEXPORT jlong JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_protocolMonitorToken(JNIEnv* e,jclass,jlong h){try{session(h);return jlong(RtrProtocolTrace::Token());}catch(const std::exception& x){fail(e,x);return 0;}}
extern "C" JNIEXPORT jlongArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_protocolMonitorSnapshot(JNIEnv* e,jclass,jlong token){
    try{auto values=RtrProtocolTrace::Snapshot(uint64_t(token));auto result=e->NewLongArray(jsize(values.size()));if(result)e->SetLongArrayRegion(result,0,jsize(values.size()),reinterpret_cast<const jlong*>(values.data()));return result;}
    catch(const std::exception& x){fail(e,x);return nullptr;}}
extern "C" JNIEXPORT jlongArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_diagnosticMonitorSnapshot(JNIEnv* e,jclass,jlong token) {
    try {if(token==0)throw std::invalid_argument("Diagnostic monitor token must be nonzero");auto values=RtrDiagnostics::Snapshot(uint64_t(token));
        auto result=e->NewLongArray(jsize(values.size()));if(result)e->SetLongArrayRegion(result,0,jsize(values.size()),reinterpret_cast<const jlong*>(values.data()));return result;
    }catch(const std::exception& x){fail(e,x);return nullptr;}
}
extern "C" JNIEXPORT jlongArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_diagnosticMonitorSnapshotAfter(JNIEnv* e,jclass,jlong token,jlong afterTrigger) {
    try {if(token==0||afterTrigger<0)throw std::invalid_argument("Invalid diagnostic monitor cursor");
        auto values=RtrDiagnostics::Snapshot(uint64_t(token),uint64_t(afterTrigger));
        auto result=e->NewLongArray(jsize(values.size()));if(result)e->SetLongArrayRegion(result,0,jsize(values.size()),reinterpret_cast<const jlong*>(values.data()));return result;
    }catch(const std::exception& x){fail(e,x);return nullptr;}
}
extern "C" JNIEXPORT jlongArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_diagnosticMonitorPoll(JNIEnv* e,jclass,jlong token,jlong nextEvent,jlong nextSubmission,jlong afterTrigger) {
    try {if(token==0||nextEvent<1||nextSubmission<1||afterTrigger<0)throw std::invalid_argument("Invalid incremental diagnostic cursor");
        auto values=RtrDiagnostics::Snapshot(uint64_t(token),uint64_t(afterTrigger),uint64_t(nextEvent),uint64_t(nextSubmission));
        auto result=e->NewLongArray(jsize(values.size()));if(result)e->SetLongArrayRegion(result,0,jsize(values.size()),reinterpret_cast<const jlong*>(values.data()));return result;
    }catch(const std::exception& x){fail(e,x);return nullptr;}
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_diagnosticGpuSnapshot(JNIEnv* e,jclass,jlong h) {
    try {session(h);auto values=RtrGpuProfile::Snapshot();auto type=e->FindClass("java/lang/String");auto result=e->NewObjectArray(jsize(values.size()),type,nullptr);e->DeleteLocalRef(type);
        if(result)for(jsize i=0;i<jsize(values.size());++i){auto text=e->NewStringUTF(values[i].c_str());e->SetObjectArrayElement(result,i,text);e->DeleteLocalRef(text);}return result;}
    catch(const std::exception& x){fail(e,x);return nullptr;}
}

extern "C" JNIEXPORT jobjectArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_diagnosticGpuSnapshotPacked(JNIEnv* e,jclass,jlong h,jlong nextOrdinal) {
    try {session(h);if(nextOrdinal<0)throw std::invalid_argument("Invalid GPU diagnostic cursor");
        auto packet=RtrGpuProfile::SnapshotPacked(uint64_t(nextOrdinal));
        auto objectType=e->FindClass("java/lang/Object"),stringType=e->FindClass("java/lang/String");
        auto result=e->NewObjectArray(2,objectType,nullptr);e->DeleteLocalRef(objectType);
        if(!result){e->DeleteLocalRef(stringType);return nullptr;}
        auto words=e->NewLongArray(jsize(packet.words.size()));
        if(words)e->SetLongArrayRegion(words,0,jsize(packet.words.size()),reinterpret_cast<const jlong*>(packet.words.data()));
        auto names=e->NewObjectArray(jsize(packet.names.size()),stringType,nullptr);e->DeleteLocalRef(stringType);
        if(names)for(jsize i=0;i<jsize(packet.names.size());++i){auto name=e->NewStringUTF(packet.names[i].c_str());e->SetObjectArrayElement(names,i,name);e->DeleteLocalRef(name);}
        if(words){e->SetObjectArrayElement(result,0,words);e->DeleteLocalRef(words);}if(names){e->SetObjectArrayElement(result,1,names);e->DeleteLocalRef(names);}return result;
    }catch(const std::exception& x){fail(e,x);return nullptr;}
}

extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_prepareDiagnostics(JNIEnv* e,jclass,jint level){
    try{std::lock_guard lock(lifecycle);
        if(active)throw std::logic_error("A resident renderer already owns diagnostics");
        if(level<0||level>2)throw std::invalid_argument("Invalid diagnostic level");
        if(!diagnosticsPrepared){RtrDiagnostics::Reset();RtrProtocolTrace::Reset();diagnosticsPrepared=true;}
        RtrDiagnostics::Configure(unsigned(level));RtrProtocolTrace::enabled.store(level!=0);
    }catch(const std::exception& x){fail(e,x);}
}

extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_benchmarkGpuBegin(JNIEnv* e,jclass,jlong h,jlong sequence,jlong revision){
    try{auto& s=session(h);RtrDiagnostics::Context(uint64_t(sequence),uint64_t(revision));s.beginBenchmarkGpuRequest(uint64_t(sequence),uint64_t(revision));}
    catch(const std::exception& x){fail(e,x);}
}
extern "C" JNIEXPORT jlongArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingInputSnapshot(JNIEnv* e,jclass,jlong h){
    try{const auto& pass=static_cast<HostPathTracer*>(session(h).app->HostRenderPass())->nativeLighting;
        // Explicit test snapshot only; no per-frame recording or GPU readback.
        std::array<uint32_t,116> bits{};
        static_assert(sizeof(pass.constants)==80*sizeof(uint32_t)&&sizeof(pass.reconstruction.parameters)==36*sizeof(uint32_t));
        std::memcpy(bits.data(),&pass.constants,sizeof(pass.constants));
        std::memcpy(bits.data()+80,&pass.reconstruction.parameters,sizeof(pass.reconstruction.parameters));
        std::array<jlong,116> values{};std::copy(bits.begin(),bits.end(),values.begin());
        auto result=e->NewLongArray(jsize(values.size()));if(result)e->SetLongArrayRegion(result,0,jsize(values.size()),values.data());return result;
    }catch(const std::exception& x){fail(e,x);return nullptr;}
}
extern "C" JNIEXPORT jlongArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_sceneTableUploadStatistics(JNIEnv* e,jclass,jlong h){
    try{auto values=session(h).app->HostRenderPass()->GetScene()->HostTableUploadStatistics();
        std::array<jlong,8> words{};std::copy(values.begin(),values.end(),words.begin());
        auto result=e->NewLongArray(jsize(words.size()));if(result)e->SetLongArrayRegion(result,0,jsize(words.size()),words.data());return result;
    }catch(const std::exception& x){fail(e,x);return nullptr;}
}
extern "C" JNIEXPORT jlongArray JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_benchmarkGpuSnapshot(JNIEnv* e,jclass,jlong h){
    try{auto& s=session(h);if(!s.benchmarkTimings)throw std::logic_error("Independent benchmark timing was not enabled before creating the session");
        s.collectGpuSamples();const uint64_t count=std::min<uint64_t>(s.benchmarkWritten,s.benchmarkSamples.size());
        std::vector<jlong> values{jlong(s.benchmarkWritten-count),jlong(s.benchmarkDropped),jlong(count),7};values.reserve(4+count*7);
        for(uint64_t i=s.benchmarkWritten-count;i<s.benchmarkWritten;++i){auto& row=s.benchmarkSamples[i%s.benchmarkSamples.size()];values.insert(values.end(),row.begin(),row.end());}
        auto result=e->NewLongArray(jsize(values.size()));if(result)e->SetLongArrayRegion(result,0,jsize(values.size()),values.data());return result;
    }catch(const std::exception& x){fail(e,x);return nullptr;}
}
