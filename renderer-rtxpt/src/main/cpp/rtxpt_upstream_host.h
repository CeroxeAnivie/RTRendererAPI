#pragma once

#include <functional>
#include <chrono>
#include <stdexcept>
#include <utility>
#include <vector>
#include <cstdio>
#include <d3d12sdklayers.h>
#include "OpacityMicroMap/OmmBaker.h"
#include "SampleCommon/AccelerationStructureUtil.h"
#include "SampleCommon/BlasCompactor.h"
#include "SampleCommon/BlasPool.h"
#include "SampleCommon/CaptureScriptManager.h"
#include "rtxpt_upstream_resources.h"
#include "rtxpt_gpu_profile.h"
#include "rtxpt_sparse_lighting.h"
#include "rtxpt_native_lighting.h"
#include "rtxpt_upstream_lights.h"
#include "rtxpt_precompiled_runtime.h"

// AdvancedPathTracer is defined by NVIDIA in AdvancedSample.cpp rather than an
// exported header. The owning translation unit includes that unchanged file.
// This is a source-level adapter, not an NVIDIA-supported binary ABI.
namespace rtr::upstream {

// Internal backend names only. Public consumers continue to use GpuFrame and
// feature capabilities; NVIDIA resource types never cross the Java API.
enum class Signal { DisplayColor, LinearColor, RawRadiance, Depth, MotionVectors, DirectRadiance };

// Consumer state belongs to the adapter; upstream SampleUIData stays unchanged.
struct OverlayState {
    std::string text;
    std::vector<std::string> choices;
    int selection = -1;
};

// Keep the original quality initialization and the previously accepted native
// HUD layout, without adding fields or drawing code to upstream SampleUI.
class ConsumerUI final : public SampleUI {
public:
    ConsumerUI(donut::app::DeviceManager* manager,SampleBaseApp& app,Sample& sample,
        SampleUIData& ui,bool ser,const CommandLineOptions& options,OverlayState& overlay)
        : SampleUI(manager,app,sample,ui,ser,options),m_overlay(overlay) { }
protected:
    void buildUI() override {
        if (m_overlay.text.empty()) return;
        ImGui::PushFont(m_defaultFont->GetScaledFont());
        ImGui::SetNextWindowPos(ImVec2(14.f,14.f),ImGuiCond_Always);
        ImGui::SetNextWindowBgAlpha(.69f);
        ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding,ImVec2(12.f,8.f));
        ImGui::Begin("RTRenderer host HUD",nullptr,ImGuiWindowFlags_NoDecoration|ImGuiWindowFlags_AlwaysAutoResize
            |ImGuiWindowFlags_NoSavedSettings|(m_overlay.choices.empty()?ImGuiWindowFlags_NoInputs:0)
            |ImGuiWindowFlags_NoFocusOnAppearing|ImGuiWindowFlags_NoNav);
        ImGui::TextUnformatted(m_overlay.text.c_str());
        if (!m_overlay.choices.empty()) {
            ImGui::SetNextItemWidth(330.f);
            const auto& labels=m_overlay.choices;
            if (ImGui::BeginCombo("Scene",labels.at(m_overlay.selection).c_str())) {
                for (int i=0;i<int(labels.size());++i) {
                    ImGui::PushID(i);
                    if (ImGui::Selectable(labels[i].c_str(),i==m_overlay.selection))m_overlay.selection=i;
                    if (i==m_overlay.selection)ImGui::SetItemDefaultFocus();
                    ImGui::PopID();
                }
                ImGui::EndCombo();
            }
        }
        ImGui::End();
        ImGui::PopStyleVar();
        ImGui::PopFont();
    }
private:
    OverlayState& m_overlay;
};

class PathTracer final : public AdvancedPathTracer {
public:
    PathTracer(donut::app::DeviceManager& manager, const CommandLineOptions& options,
        bool managedScene) : AdvancedPathTracer(manager, options),
        m_adapterOptions(options), m_managedScene(managedScene) {
        if(options.debug||options.gpuValidation){
            auto* native=static_cast<ID3D12Device*>(GetDevice()->getNativeObject(nvrhi::ObjectTypes::D3D12_Device));
            if(native&&SUCCEEDED(native->QueryInterface(IID_PPV_ARGS(&m_validationQueue))))
                m_validationQueue->RegisterMessageCallback([](D3D12_MESSAGE_CATEGORY,
                    D3D12_MESSAGE_SEVERITY severity,D3D12_MESSAGE_ID id,LPCSTR description,void*){
                    if(severity<=D3D12_MESSAGE_SEVERITY_WARNING){
                        std::fprintf(stderr,"D3D12 validation id=%u: %s\n",unsigned(id),description);
                        std::fflush(stderr);
                    }
                },D3D12_MESSAGE_CALLBACK_FLAG_NONE,nullptr,&m_validationCookie);
        }
    }
    const char* GetWindowTitle() override {
        return m_consumerTitle.empty() ? AdvancedPathTracer::GetWindowTitle() : m_consumerTitle.c_str();
    }
    void SetConsumerTitle(std::string title) {
        m_consumerTitle = std::move(title);
        GetDeviceManager()->SetWindowTitle(GetWindowTitle());
    }
    std::array<int64_t,4> SurfaceExtent() const {
        return {m_renderSize.x,m_renderSize.y,m_displaySize.x,m_displaySize.y};
    }
    ~PathTracer() override {
        if(m_validationQueue){m_validationQueue->UnregisterMessageCallback(m_validationCookie);m_validationQueue->Release();}
        if(!m_skyPath.empty()){std::error_code ignored;std::filesystem::remove(m_skyPath,ignored);}
    }

    RtrNativeLightingPass lighting;
    RtrSparseLighting sparse;
    Lights analytic;
    struct Quality {
        int sampler,samples,candidates,adaptiveQuality;float localRatio;
        int layers,maxDepth,nestedQuality;bool halfPrecision;float lodBias;
        bool bloom;float bloomRadius,bloomIntensity;bool toneMapping;int beautyTransport;
        void Validate() const {
            if(sampler < -1||sampler>2||samples<1||samples>63||candidates<1||candidates>63
                ||adaptiveQuality<0||adaptiveQuality>2||!std::isfinite(localRatio)||localRatio<0||localRatio>1
                ||layers<1||layers>3||maxDepth<1||maxDepth>15||nestedQuality<0||nestedQuality>2
                ||!std::isfinite(lodBias)||lodBias < -8||lodBias>8
                ||!std::isfinite(bloomRadius)||bloomRadius<0||bloomRadius>128
                ||!std::isfinite(bloomIntensity)||bloomIntensity<0||bloomIntensity>100||beautyTransport<0||beautyTransport>1)
                throw std::invalid_argument("Invalid original RTXPT quality configuration");
        }
    };
    void ConfigureQuality(const Quality& quality){
        quality.Validate();m_quality=quality;m_qualityConfigured=true;ApplyQuality();ResetHistory();
    }
    std::array<float,14> QualityState() const {
        return {float(m_ui.NEEType),float(m_ui.NEEFullSamples),float(m_ui.NEECandidateSamples),
            float(m_ui.NEEAT_QualityPreset),m_ui.NEEAT_LocalToGlobalSampleRatio,
            float(m_ui.DenoisingLayersActiveCount),float(m_ui.DenoisingLayersMaxVertexDepth),
            float(m_ui.NestedDielectricsQuality),m_ui.UseFp16Types?1.f:0.f,m_ui.TexLODBias,
            m_ui.EnableBloom?1.f:0.f,m_ui.BloomRadius,m_ui.BloomIntensity,m_ui.EnableToneMapping?1.f:0.f};
    }
    std::array<float,3> ExposureState() const {
        return {m_ui.ToneMappingParams.autoExposure?1.f:0.f,m_ui.ToneMappingParams.exposureValue,
            m_ui.ToneMappingParams.exposureCompensation};
    }
    void SetOverlayText(std::string text) { m_overlay.text=std::move(text); }
    void SetOverlayChoices(std::vector<std::string> choices,int selection) {
        if(choices.size()>64||(choices.empty()?selection!=-1:selection<0||size_t(selection)>=choices.size()))
            throw std::invalid_argument("Invalid overlay selection");
        m_overlay.choices=std::move(choices);m_overlay.selection=selection;
    }
    int OverlaySelection() const { return m_overlay.selection; }
    std::array<int64_t,17> RenderingState() const {
        int64_t nrdIntegrations=0;
        for(const auto& integration:m_nrd)if(integration)++nrdIntegrations;
        const bool dlss=m_ui.RealtimeMode&&(m_ui.RealtimeAA==2||m_ui.RealtimeAA==3);
        const bool nativeTransport=!(m_qualityConfigured&&m_quality.beautyTransport==1)&&!lighting.active;
        return {m_ui.RealtimeMode?1:0,m_ui.RealtimeAA,dlss?int64_t(m_ui.DLSSLastMode):0,
            m_ui.ActualSamplesPerPixel(),m_ui.ActualUseStandaloneDenoiser()?1:0,int64_t(m_ui.NRDMethod),nrdIntegrations,
            m_ui.ActualRadianceCacheMode(),m_ui.ActualUseReSTIRDI()?1:0,m_ui.ActualUseReSTIRGI()?1:0,
            (nativeTransport&&m_ui.DXHitObjectExtension)?1:0,(nativeTransport&&m_ui.DXHitObjectExtension&&m_ui.DXMaybeReorderThreads)?1:0,
            (nativeTransport&&m_ui.NVAPIHitObjectExtension)?1:0,(nativeTransport&&m_ui.NVAPIHitObjectExtension&&m_ui.NVAPIReorderThreads)?1:0,
            m_ui.ActualSubSamplingMode(),lighting.active?1:0,m_analyticBeautyFrames&&m_quality.beautyTransport==1?1:0};
    }
    int DenoiserState() const { return int(m_ui.NRDMethod); }
    void ConfigureDenoiser(bool relax) {
        const auto method=relax?NrdConfig::DenoiserMethod::RELAX:NrdConfig::DenoiserMethod::REBLUR;
        if(m_ui.NRDMethod==method)return;
        m_ui.NRDMethod=method;m_ui.NRDModeChanged=true;ResetHistory();
    }
    void ResetHistory(){m_ui.ResetAccumulation=true;m_ui.ResetRealtimeCaches=true;lighting.reconstruction.reset=true;++lighting.reconstruction.resets;}
    void ConfigurePresentation(int maxBounces,int diffuseBounces,bool reflex,int generatedFrames) {
        if(maxBounces<1||maxBounces>64||diffuseBounces<0||diffuseBounces>maxBounces
                ||generatedFrames<0||generatedFrames>3)throw std::invalid_argument("Invalid presentation configuration");
        m_presentation={maxBounces,diffuseBounces,reflex?1:0,generatedFrames};
        m_presentationConfigured=true;ApplyPresentation();ResetHistory();
    }
    std::array<int64_t,10> PresentationState() const {
        return {m_ui.BounceCount,m_ui.DiffuseBounceCount,m_ui.ReflexMode,
            int64_t(m_ui.DLSSFGMode),m_ui.DLSSFGNumFramesToGenerate,m_ui.ActualReflexMode(),
            int64_t(m_ui.ActualDLSSFGMode()),m_ui.DLSSFGMultiplier,
            m_ui.IsReflexSupported?1:0,m_ui.IsDLSSFGSupported?1:0};
    }
    void ConfigureExposure(float compensation){
        if(!std::isfinite(compensation)||compensation < -12.f||compensation > 12.f)
            throw std::invalid_argument("Exposure compensation must be finite and in [-12,12]");
        m_exposure=compensation;m_exposureConfigured=true;ApplyExposure();
    }
    void SceneUnloading() override {
        if(m_resourceCommands||m_structureCommands||!m_resourceRetirement.empty()){
            GetDevice()->waitForIdle();m_resourceRetirement.clear();
            m_resourceCommands=nullptr;m_structureCommands=nullptr;
        }
        AdvancedPathTracer::SceneUnloading();
        m_importedMaterials.clear();
    }
    std::array<double,2> MeshResourceState(const std::string& name)const{return m_resources.MeshResourceState(name);}
    std::array<uint64_t,9> ResourceWorkState()const{
        const auto& textures=m_resources.TextureWork();
        const auto& references=m_resources.ReferenceWork();
        return {textures[0],textures[1],textures[2],textures[3],textures[4],textures[5],textures[6],
            references[0],references[1]};
    }
    // Separate from the established nine texture/reference counters so existing
    // diagnostic consumers and fixed workload classes retain their wire contract.
    std::array<uint64_t,10> CommandWorkState()const{
        const auto& poses=m_resources.PoseWork();
        return {m_commandWork[0],m_commandWork[1],m_commandWork[2],m_commandWork[3],
            poses[0],poses[1],poses[2],m_nativeFrameWork[0],m_nativeFrameWork[1],m_nativeFrameWork[2]};
    }
    void SceneLoaded() override {
        AdvancedPathTracer::SceneLoaded();
        // Sample sets ShaderReloadRequested for its initial PT hit tables.
        // An independent-lighting scene has no such tables; its own shaders
        // are loaded fresh by Prepare. Later explicit reload requests retain
        // the normal complete-render fallback.
        m_nativeSceneBootstrapPending=true;
        if(m_presentationConfigured)ApplyPresentation();
        if(m_exposureConfigured)ApplyExposure();
        if(m_qualityConfigured)ApplyQuality();
    }

    void CreateRTPipelines() override {
        // Native lighting publishes its own signals from SampleRenderCode and
        // never invokes AdvancedPathTracer::PathTrace or Denoise. Use the
        // documented Sample extension hooks without creating Advanced's unused
        // BUILD/FILL/reference variants or its unconditional SHaRC buffers.
        // Shared post-processing variants remain owned by the original Sample.
        if(lighting.active)Sample::CreateRTPipelines();
        else {AdvancedPathTracer::CreateRTPipelines();m_advancedPipelineOwner=true;}
    }
    void DestroyRTPipelines() override {
        // A direct-only session can unload without ever creating a PT baker.
        // Pair the same original owner used by CreateRTPipelines when it exists.
        if(!m_ptPipelineBaker)return;
        if(m_advancedPipelineOwner)AdvancedPathTracer::DestroyRTPipelines();
        else Sample::DestroyRTPipelines();
        m_advancedPipelineOwner=false;
    }
    void CreateCustomRenderPasses() override {
        if(!lighting.active)AdvancedPathTracer::CreateCustomRenderPasses();
    }
    void UpdateCustomResources() override {
        if(!lighting.active)AdvancedPathTracer::UpdateCustomResources();
    }
    void UpdateCustomConstants(SampleConstants& constants,nvrhi::ICommandList* commands) override {
        if(!lighting.active)AdvancedPathTracer::UpdateCustomConstants(constants,commands);
    }

    void GetCustomPTPipelineGlobalMacros(std::vector<donut::engine::ShaderMacro>& macros) override {
        // The original StandardBSDF stores material values and textures in GPU
        // data. Only its public, explicitly non-deterministic debug export label
        // is normalized, so a scene rename does not create a new shader binary.
        if(m_materialsBaker) {
            const auto& table=m_materialsBaker->GetShaderPermutationTable();
            if(table.size()>1)throw std::runtime_error("Unsupported upstream material shader permutation table");
            for(const auto& permutation:table) {
                const auto& values=permutation->Macros;
                if(permutation->IndexInTable!=0||values.size()!=2
                    ||values[0].name!="RTXPT_MATERIAL_PERMUTATIONS_ENABLED"||values[0].definition!="1"
                    ||values[1].name!="ActiveBSDF"||values[1].definition!="StandardBSDF")
                    throw std::runtime_error("Upstream material shader permutation is not the precompiled StandardBSDF");
                permutation->UniqueMaterialName="RtrStandard";
            }
        }
        m_precompiledGlobals.Check(macros);
    }

    void ApplyLights(const std::vector<int64_t>& ids,const std::vector<float>& records,
        const std::vector<int64_t>& removals,const std::vector<float>& sun,const std::vector<float>& sky) {
        if(!m_scene||!m_managedScene||(!sky.empty()&&sky.size()<4))throw std::invalid_argument("Lighting requires the managed scene");
        if(!sky.empty()&&sky.size()!=4){
            if(lighting.active)throw std::invalid_argument("Environment maps require native beauty rendering");
            if(sky.size()<6||sky[4]<2||sky[4]>8192||sky[5]<1||sky[5]>4096
                ||sky[4]!=std::floor(sky[4])||sky[5]!=std::floor(sky[5])
                ||sky.size()!=6+size_t(sky[4])*size_t(sky[5])*3)
                throw std::invalid_argument("Invalid equirectangular environment dimensions");
        }
        for(auto value:sky)if(!std::isfinite(value)||value<0)throw std::invalid_argument("Invalid sky color");
        const bool adapterLighting=lighting.active||(m_qualityConfigured&&m_quality.beautyTransport==1);
        const bool structural=analytic.Apply(ids,records,removals,sun,*m_scene,adapterLighting);
        auto commands=GetDevice()->createCommandList();commands->open();
        if(!lighting.active&&!sky.empty()) ConfigureSky(sky,commands);
        m_scene->Refresh(commands,GetFrameIndex());commands->close();GetDevice()->executeCommandList(commands);
        m_lights=m_scene->GetSceneGraph()->GetLights();
        if(structural&&m_lightsBaker)m_lightsBaker->SceneReloaded();
        ++lighting.analyticGeneration;
        m_ui.ResetAccumulation=true;
    }
    bool HasInstance(const std::string& name) const {
        return m_resources.HasInstance(name);
    }
    std::array<double,24> InstanceTransformState(const std::string& name) const {
        return m_resources.InstanceTransformState(name);
    }
    size_t GeometryCorners(const std::string& name) const {
        return m_resources.GeometryCorners(name);
    }

    void ConfigureLighting(const std::vector<float>& parameters) {
        lighting.Configure(parameters);
        // This consumer pass performs its own per-hit opacity evaluation. The
        // sample's optional OMM bake is redundant here, and DXR 1.2 cannot build
        // its mixed OMM/non-OMM geometry linkage (NVRHI dereferences a null OMM).
        // Use the sample's ordinary BLAS/alpha path without changing upstream.
        // UIData.Enable alone only changes TLAS selection: CreateAccelStructs
        // queues OMM work again on every structural rebuild regardless of it.
        // A null optional baker is an ordinary supported sample path (also used
        // on devices without OMM); all BLAS/TLAS ownership stays with Sample.
        if(m_ommBaker) {
            m_ommBaker->SceneUnloading();
            m_ommBaker->UIData().Enable=false;
            m_ommBaker.reset();
        }
        // All direct requests are freshly evaluated on the original frame.
        lighting.retainSignalsAllowed = false;
        m_resources.SetExternalTextureSampling(true);
    }

    void RecordWindowOutput(nvrhi::ITexture* texture) { m_windowOutput=texture; }
    nvrhi::TextureHandle WindowOutput() const { return m_windowOutput; }
    void BackBufferResizing() override {
        m_windowOutput=nullptr;
        AdvancedPathTracer::BackBufferResizing();
    }
    void Render(nvrhi::IFramebuffer* framebuffer) override {
        // Original Render refreshes the graph and uploads current/previous poses.
        // Keep publication-side lifecycle refreshes from consuming these poses.
        PollResourceRetirement();
        if(m_managedScene&&m_scene&&IsSceneLoaded())m_resources.ApplyPendingTransforms();
        if (!m_managedScene || !IsSceneLoaded() || !m_scene) {
            if(lighting.active)++m_nativeFrameWork[1];
            AdvancedPathTracer::Render(framebuffer);
            m_nativeSceneBootstrapPending=false;
            return;
        }
        if(!m_scene->GetSceneGraph()->GetMeshInstances().empty()) {
            if(CanRenderNativeLightingLean(framebuffer)) {
                RenderNativeLightingLean(framebuffer);
                return;
            }
            if(lighting.active)++m_nativeFrameWork[1];
            AdvancedPathTracer::Render(framebuffer);
            m_nativeSceneBootstrapPending=false;
            return;
        }
        // The sample's CreateTlas allocates a zero-byte SubInstance buffer for
        // an empty graph. Its Render override is a public render-pass hook:
        // handle this no-geometry boundary without a fake mesh or changing AS
        // allocation policy. The DeviceManager still owns frame/present timing.
        m_progressLoading.Stop();
        m_asyncLoadingInProgress=false; // no geometry, OMM or PT work is pending
        auto commands=GetDevice()->createCommandList();commands->open();
        if (lighting.active) {
            SampleRenderCode(framebuffer,commands,SampleConstants{},false);
            ++m_nativeFrameWork[0];++m_nativeFrameWork[2];
        }
        const auto& attachments=framebuffer->getDesc().colorAttachments;
        if (!attachments.empty()) commands->clearTextureFloat(attachments[0].texture,
            nvrhi::AllSubresources,nvrhi::Color(0));
        commands->close();GetDevice()->executeCommandList(commands);
    }

    void ClearResources() {
        if (!m_managedScene || !m_scene) throw std::logic_error("No managed scene");
        GetDevice()->waitForIdle();m_resourceRetirement.clear();
        m_resources.Reset();
        analytic.Reset(m_scene.get());
        m_lights.clear();
        m_scene->GetSceneGraph()->SetRootNode(
            std::make_shared<donut::engine::SceneGraphNode>());
        auto commands=OpenResourceCommands();
        m_scene->Refresh(commands, GetFrameIndex());
        commands->close();
        GetDevice()->executeCommandList(commands);
        ReimportMaterials();
        lighting.metadataDirty = true;
        lighting.metadataInitialized = false;
        ++lighting.geometryGeneration;
    }

    void SampleRenderCode(nvrhi::IFramebuffer* framebuffer,
        nvrhi::CommandListHandle commands, const SampleConstants& constants,
        bool needNewPasses) override {
        // Base Render has already consumed this method-change latch and rebuilt
        // its integrations. Do not trigger another rebuild on the next frame.
        m_ui.NRDModeChanged=false;
        if(m_qualityConfigured&&m_quality.beautyTransport==1&&!lighting.active){
            if(needNewPasses)m_analyticBeautyPipeline=nullptr;
            RenderAnalyticBeauty(commands);++lighting.beautyFrames;return;
        }
        if (!lighting.active) {
            ++lighting.beautyFrames;
            AdvancedPathTracer::SampleRenderCode(framebuffer, commands, constants, needNewPasses);
            // Realtime BUILD/FILL write the denoising layers, not OutputColor.
            // Without NRD, upstream PostProcessAA merges those layers only after
            // AA has already consumed OutputColor. Supply the current radiance
            // before AA by invoking the original merge, without changing shading.
            if (m_ui.RealtimeMode && m_ui.RealtimeAA < 3 && !m_ui.ActualUseStandaloneDenoiser()) {
                SampleMiniConstants miniConstants = { uint4(0,0,0,0) };
                const auto desc = m_renderTargets->OutputColor->getDesc();
                GpuProfiler::Begin(commands,"PrepareCurrentRadiance");
                m_postProcess->Apply(commands,PostProcess::ComputePassType::NoDenoiserFinalMerge,
                    m_constantBuffer,miniConstants,m_bindingSet,m_bindingLayout,desc.width,desc.height);
                GpuProfiler::End(commands);
            }
            return;
        }
        if (!m_adapterShaders) {
            auto fs = std::make_shared<donut::vfs::NativeFileSystem>();
            m_adapterShaders = std::make_shared<donut::engine::ShaderFactory>(GetDevice(),
                fs, GetLocalPath("AdapterShaders"));
        }
        lighting.reconstruction.streamline = &GetDeviceManager()->GetStreamline();
        if (!lighting.texturePolicies) lighting.texturePolicies = [this](const std::string& name) {
            return m_resources.TextureSamplingPolicies(name);
        };
        for (unsigned policy=0;policy<8;++policy) if (!lighting.textureSamplers[policy]) {
            if (policy==0) lighting.textureSamplers[policy]=m_CommonPasses->m_LinearWrapSampler;
            else if (policy==3) lighting.textureSamplers[policy]=m_CommonPasses->m_LinearClampSampler;
            else if (policy==7) lighting.textureSamplers[policy]=m_CommonPasses->m_PointClampSampler;
            else {
                nvrhi::SamplerDesc desc;
                desc.setAllFilters((policy&4)==0);
                desc.setAddressU((policy&1)?nvrhi::SamplerAddressMode::Clamp:nvrhi::SamplerAddressMode::Wrap);
                desc.setAddressV((policy&2)?nvrhi::SamplerAddressMode::Clamp:nvrhi::SamplerAddressMode::Wrap);
                lighting.textureSamplers[policy]=GetDevice()->createSampler(desc);
            }
        }
        sparse.Ensure(GetDevice());
        lighting.Prepare(GetDevice(), *m_adapterShaders, m_bindlessLayout, &sparse);
        analytic.Upload(GetDevice(),commands);
        if (!m_scene || m_scene->GetSceneGraph()->GetMeshInstances().empty()) {
            if (lighting.direct) commands->clearTextureFloat(lighting.direct, nvrhi::AllSubresources, nvrhi::Color(1,1,1,0));
            if (lighting.indirect) commands->clearTextureFloat(lighting.indirect, nvrhi::AllSubresources, nvrhi::Color(0));
            if (lighting.geometry) commands->clearTextureFloat(lighting.geometry, nvrhi::AllSubresources, nvrhi::Color(0));
            if (lighting.identity) commands->clearTextureUInt(lighting.identity, nvrhi::AllSubresources, 0);
            lighting.EncodeOutputs(GetDevice(), commands);
            ++lighting.executedFrames;
        } else {
            lighting.Dispatch(GetDevice(), commands, m_scene, m_topLevelAS, sparse,
                m_CommonPasses->m_LinearWrapSampler, analytic.buffer, uint32_t(analytic.records.size()),
                analytic.tree.root==rtr::RetainedLightTree::None?0:analytic.tree.root,analytic.treeBase);
        }
        // Compact adapter signals are separate resources. Initialized direct
        // frames use RenderNativeLightingLean and do not execute the sample's
        // beauty AA / tone-map / blit tail. Initialization and special native
        // requests keep the original complete Render lifecycle.
    }

    bool LoadScene(std::shared_ptr<donut::vfs::IFileSystem> fs,
        const std::filesystem::path& path) override {
        if (!m_managedScene) {
            const auto requested = m_firstScene && !m_adapterOptions.scene.empty()
                ? std::filesystem::path(m_adapterOptions.scene) : path;
            m_firstScene = false;
            return AdvancedPathTracer::LoadScene(std::move(fs), requested);
        }
        // The supported virtual scene-loading hook creates an ordinary empty
        // ExtendedScene. Sample retains SceneLoaded/AS/render/shutdown ownership.
        m_currentSceneName = "API managed scene";
        m_scene = std::make_shared<ManagedScene>(GetDevice(), *m_shaderFactory,
            std::move(fs), m_TextureCache, m_DescriptorTable,
            std::make_shared<ExtendedSceneTypeFactory>());
        auto graph = m_scene->CreateSceneGraph();
        graph->SetRootNode(std::make_shared<donut::engine::SceneGraphNode>());
        // A newly attached empty root is structurally dirty, but Donut's
        // FinishedLoading unconditionally writes its still-null zero-count
        // buffers when that flag is set. Finalize this empty CPU graph using
        // the public graph lifecycle before the ordinary SceneLoaded callback.
        // Real resource publications keep their structure changes and allocate
        // through the unchanged Scene::RefreshBuffers implementation.
        graph->Refresh(GetFrameIndex());
        m_progressLoading.Set(10);
        return true;
    }

    void ApplyResources(const std::vector<uint8_t>& packet) {
        if (!m_managedScene || !m_scene || !IsSceneLoaded())
            throw std::logic_error("In-memory resources require the loaded managed scene");
        RtrDiagnostics::Scope resourceScope(RtrDiagnostics::ResourcePlan);
        PollResourceRetirement();
        m_resources.SetTextureResources(GetDevice(),m_DescriptorTable);
        nvrhi::CommandListHandle commands;
        bool retainRemoved=false;
        auto changed = m_resources.Apply(packet.data(), packet.size(),
            *m_scene, [&]()->nvrhi::ICommandList*{commands=OpenResourceCommands();return commands;},
            GetFrameIndex(),[&](bool geometryOnly){
                retainRemoved=geometryOnly&&lighting.active&&!m_ommBaker;
                if(!retainRemoved){RtrDiagnostics::Scope waitScope(RtrDiagnostics::Retirement);GetDevice()->waitForIdle();m_resourceRetirement.clear();}
            });
        // A real domain edit below requires the original complete AS rebuild.
        // Do not build unrelated stable meshes once here and again in that pass.
        if(!changed.materialDomainsChanged&&(!changed.deformedMeshes.empty()||!changed.reshapedMeshes.empty()))
            RefreshDeformedDirectStructures(changed,commands);
        if(retainRemoved&&(!changed.retiredMeshes.empty()||!changed.retiredBuffers.empty()
            ||!changed.retiredStructures.empty()||!changed.retiredTextures.empty())){
            if(m_resourceRetirement.size()>=64){GetDevice()->waitForIdle();m_resourceRetirement.clear();}
            RetiredResources retired;
            retired.graphics=GetDevice()->createEventQuery();retired.compute=GetDevice()->createEventQuery();
            GetDevice()->setEventQuery(retired.graphics,nvrhi::CommandQueue::Graphics);
            GetDevice()->setEventQuery(retired.compute,nvrhi::CommandQueue::Compute);
            retired.meshes=changed.retiredMeshes;retired.buffers=changed.retiredBuffers;
            retired.structures=changed.retiredStructures;retired.textures=changed.retiredTextures;
            m_resourceRetirement.push_back(std::move(retired));
        }
        for(const auto& name:changed.retiredInstances)lighting.RetireInstance(name);
        for(const auto& name:changed.retiredGeometries)lighting.RetireGeometry(name);
        if(commands)commands->close();
        if(changed.topology||changed.materials||changed.textures||changed.attributes){
            const auto submitted=GetDevice()->executeCommandList(commands);
            // These uploads are outside Sample::Render's own lastGraphicsSubmit.
            // Its async prefix must observe them before reading the scene tables.
            GetDevice()->queueWaitForCommandList(nvrhi::CommandQueue::Compute,
                nvrhi::CommandQueue::Graphics,submitted);
        }
        if (changed.topology || changed.materials || changed.textures) {
            // Register new/reconfigured materials through the original baker.
            // A texture publication is not a material-definition publication:
            // existing PTTexture slots have their own public rebinding contract.
            const auto& currentMaterials=m_scene->GetSceneGraph()->GetMaterials();
            // Removing users/materials cannot invalidate the surviving baker rows
            // or shader permutations. Only an unregistered object needs a new
            // native bake; graph materialID compaction does not change PT indices.
            bool hasUnregisteredMaterial=false;
            for(const auto& material:currentMaterials)
                if(!m_importedMaterials.contains(material)){hasUnregisteredMaterial=true;break;}
            if(hasUnregisteredMaterial) ReimportMaterials();
            else if(changed.materials)m_resources.ConfigureMaterials();
            if(!hasUnregisteredMaterial&&changed.topology) {
                InvalidateBindingSet();
                m_ui.AccelerationStructRebuildRequested=true;
            }
            if(changed.textures||changed.materials)m_resources.RebindMaterialTextures(GetLocalPath(c_AssetsFolder));
            // These original scene consumers cache geometry-sized state. A
            // host graph edit does not pass through Sample::SceneLoaded again.
            if(m_ommBaker){m_ommBaker->SceneLoaded(*m_scene);m_ui.AccelerationStructRebuildRequested=true;}
            if(m_lightsBaker)m_lightsBaker->SceneReloaded();
            if(auto* sharc=GetSharc())sharc->NotifySceneReloaded();
            // Direct consumers use ordinary, non-OMM BLASes. The sample's
            // scene-wide rebuild discards every pooled handle; its public
            // compactor can instead build just the newly published meshes.
            // Keep policy edits on the original complete rebuild path.
            if(changed.topology&&!changed.materialDomainsChanged&&!changed.requiresCoverageRebuild) {
                if(!RefreshRetainedDirectStructures(changed)){
                    GetDevice()->waitForIdle();m_resourceRetirement.clear();
                }
            }
        }
        if (changed.topology || !changed.reshapedMeshes.empty()) lighting.metadataDirty = true;
        lighting.InvalidateMaterialMetadata(changed.materialPolicyChanges,changed.materialOpacityChanges);
        if (changed.topology || changed.transforms || changed.attributes) ++lighting.geometryGeneration;
        if(changed.topology||changed.materials||changed.textures||changed.transforms||changed.attributes)
            m_ui.ResetAccumulation = true;
    }

    std::unique_ptr<SampleUI> CreateUI(donut::app::DeviceManager* manager,
        SampleBaseApp& app, SampleUIData& data, bool ser,
        const CommandLineOptions&) override {
        if (m_managedScene)
            return std::make_unique<ConsumerUI>(manager, app, *this, data, ser, m_adapterOptions, m_overlay);
        return AdvancedPathTracer::CreateUI(manager, app, data, ser, m_adapterOptions);
    }

    nvrhi::TextureHandle Output(Signal signal) const {
        if (!m_renderTargets) return nullptr;
        switch (signal) {
        case Signal::DisplayColor: return m_renderTargets->LdrColor;
        case Signal::LinearColor: return m_renderTargets->ProcessedOutputColor;
        case Signal::RawRadiance: return m_renderTargets->OutputColor;
        case Signal::Depth: return m_renderTargets->Depth;
        case Signal::MotionVectors: return m_renderTargets->ScreenMotionVectors;
        case Signal::DirectRadiance: return m_renderTargets->DirectLighting;
        }
        throw std::invalid_argument("Unknown upstream output signal");
    }

private:
    ID3D12InfoQueue1* m_validationQueue=nullptr;
    DWORD m_validationCookie=0;
    // NVRHI command lists own their upload and DXR scratch allocators. Reopening
    // a retained list uses its native fence-aware internal pool, just as Sample
    // reuses its own frame lists; a fresh wrapper per publication rebuilds those
    // allocators and discards their reusable chunks on every transaction.
    nvrhi::CommandListHandle m_resourceCommands,m_structureCommands;
    // Lifetime counts of successful creation and reopening of retained native lists.
    std::array<uint64_t,4> m_commandWork{};
    // Executed direct frames: lightweight / complete native fallback / empty.
    // These counters advance at the actual dispatch/Render choice, not at request time.
    std::array<uint64_t,3> m_nativeFrameWork{};
    bool m_nativeSceneBootstrapPending=false;
    bool m_advancedPipelineOwner=false;
    bool CanRenderNativeLightingLean(nvrhi::IFramebuffer* framebuffer)const{
        if(!lighting.active||!m_materialsBaker||!m_graphicsCommandList)return false;
        // The independent direct pass owns its camera, motion and reconstruction.
        // GPU frustum culling, beauty AA/FG, picking and capture need Sample's
        // private view/postprocess lifecycle and must stay on its complete path.
        if(m_ui.RealtimeAA!=0||m_ui.AS.GPUTLASCull||m_ommBaker||m_lastFrameAsyncPrefix
            ||m_dlssgInputsFence||m_ui.ActualDLSSFGMode()!=SI::DLSSGMode::eOff
            ||m_ui.ActualUseRTXDIPasses()||(m_ui.ShaderReloadRequested&&!m_nativeSceneBootstrapPending)
            ||m_ui.DebugView!=DebugViewType::Disabled||m_pick||m_ui.ContinuousDebugFeedback
            ||m_ui.ShowDebugLines||m_ui.PostProcessTestPassHDR||m_ui.PostProcessEdgeDetection
            ||m_ui.ExperimentalPhotoModeScreenshot||m_captureScriptManager->IsDoingWork()
            ||m_materialsBaker->HasDeferredTexturesLoad())return false;
        return true;
    }
    void RenderNativeLightingLean(nvrhi::IFramebuffer* framebuffer){
        // Compose only exposed original lifecycle methods: no copied scene/AS,
        // camera, material, shader or post-processing implementation lives here.
        // Direct signals own their output resources. Requiring Sample's beauty
        // targets here forced its first full Render to retain denoising/GBuffer,
        // environment baking, light sampling and shader-debug allocations that
        // this pass never reads. Bootstrap AS through the original entry point
        // instead; real beauty/postprocess requests still use the complete path.
        m_progressLoading.Stop();m_asyncLoadingInProgress=false;
        if(m_nativeSceneBootstrapPending){m_ui.ShaderReloadRequested=false;m_nativeSceneBootstrapPending=false;}
        const auto& size=framebuffer->getFramebufferInfo();
        m_displaySize=m_renderSize=uint2(size.width,size.height);
        m_displayAspectRatio=float(size.width)/float(size.height);
        if(!m_view){m_view=std::make_shared<PlanarView>();m_viewPrevious=std::make_shared<PlanarView>();}
        m_viewPrevious->SetViewport(nvrhi::Viewport(float(size.width),float(size.height)));
        PreRender();
        RAII_SCOPE(;,PostRender(););
        m_commandList=m_graphicsCommandList;
        if(!m_topLevelAS)m_ui.AccelerationStructRebuildRequested=true;
        // Original material/coverage rules and BLAS/TLAS construction remain
        // authoritative, including genuine rebuilds after policy changes.
        RecreateAccelStructs(m_commandList);
        m_commandList->open();
        GpuProfiler::BeginFrameZone(m_commandList);
        m_scene->Refresh(m_commandList,GetFrameIndex());
        m_sceneRenderCache.Open(m_scene,&m_frameWorkerPool);
        m_materialsBaker->Update(m_commandList,m_scene,m_subInstanceData,m_sceneRenderCache);
        UploadSubInstanceData(m_commandList);
        UpdateSkinnedBLASs(m_commandList,GetFrameIndex());
        TransitionMeshBuffersToReadOnly(m_commandList);
        UpdateTLAS(m_commandList);
        // Dispatch binds the live Scene buffers and its descriptor table itself;
        // Sample's global beauty bindings and accumulation/debug setup are unused.
        SampleRenderCode(framebuffer,m_commandList,SampleConstants{},false);
        const auto& attachments=framebuffer->getDesc().colorAttachments;
        if(!attachments.empty())m_commandList->clearTextureFloat(attachments[0].texture,
            nvrhi::AllSubresources,nvrhi::Color(0));
        GpuProfiler::End(m_commandList);
        m_commandList->close();
        m_lastGraphicsSubmit=GetDevice()->executeCommandList(m_commandList);
        m_lastFrameAsyncPrefix=false;
        GetDeviceManager()->SetVsyncEnabled(m_ui.ActualEnableVsync());
        PostUpdatePathTracing();
        ++m_nativeFrameWork[0];
    }
    nvrhi::CommandListHandle OpenResourceCommands(){
        if(!m_resourceCommands){
            m_resourceCommands=GetDevice()->createCommandList();
            if(!m_resourceCommands)throw std::runtime_error("Native resource command-list allocation failed");
            ++m_commandWork[0];
        }else ++m_commandWork[1];
        m_resourceCommands->open();return m_resourceCommands;
    }
    struct RetiredResources {
        nvrhi::EventQueryHandle graphics,compute;
        std::vector<std::shared_ptr<donut::engine::MeshInfo>> meshes;
        std::vector<std::shared_ptr<donut::engine::BufferGroup>> buffers;
        std::vector<nvrhi::rt::AccelStructHandle> structures;
        std::vector<std::shared_ptr<donut::engine::LoadedTexture>> textures;
    };
    std::vector<RetiredResources> m_resourceRetirement;
    void PollResourceRetirement(){
        for(auto i=m_resourceRetirement.begin();i!=m_resourceRetirement.end();)
            if(GetDevice()->pollEventQuery(i->graphics)&&GetDevice()->pollEventQuery(i->compute))i=m_resourceRetirement.erase(i);
            else ++i;
    }
    void RefreshDeformedDirectStructures(Resources::Changes& changed,nvrhi::ICommandList* commands){
        if(!lighting.active||m_ommBaker||!commands)
            throw std::logic_error("Stable geometry updates require the native direct command list");
        auto rebuild=[&](const std::shared_ptr<donut::engine::MeshInfo>& mesh,bool immutableTopology) {
            auto description=bvh::GetMeshBlasDesc(
                bvh::Config{.excludeTransmissive=m_ui.AS.ExcludeTransmissive},*mesh,nullptr,false);
            description.buildFlags=description.buildFlags&~nvrhi::rt::AccelStructBuildFlags::AllowCompaction;
            auto structure=mesh->accelStruct;
            // Only an ordinary, uncompressed NVRHI allocation of the same
            // immutable topology can be rebuilt in place. Pooled handles are
            // address-only and must never be passed into NVRHI build methods.
            bool reusable=immutableTopology&&structure&&!m_lastFrameAsyncPrefix&&!bvh::PooledAccelStruct::IsPooled(structure)
                &&!structure->isCompacted()&&structure->getDesc().buildFlags==description.buildFlags;
            // An earlier complete frame can have a compute prefix in flight.
            // Give that case a new BLAS and retire its predecessor with both
            // native queues instead of assuming a completed CPU future fences it.
#ifdef NVRHI_WITH_RTXMU
            // Its update entry point requires separately authored update-capable
            // builds; retain the ordinary allocation path for this build policy.
            reusable=false;
#endif
            if(!reusable) {
                structure=GetDevice()->createAccelStruct(description);
                if(!structure)throw std::runtime_error("Native deformed BLAS allocation failed");
                if(mesh->accelStruct)changed.retiredStructures.push_back(mesh->accelStruct);
                mesh->accelStruct=structure;
            }
            RtrDiagnostics::Scope blasScope(RtrDiagnostics::BlasBuild,1);
            commands->setAccelStructState(structure,nvrhi::ResourceStates::AccelStructWrite);
            nvrhi::utils::BuildBottomLevelAccelStruct(commands,structure,description);
            // The native TLAS builder reads device addresses through its table;
            // just as in UpdateSkinnedBLASs, explicitly order the preceding build.
            commands->setAccelStructState(structure,nvrhi::ResourceStates::AccelStructBuildBlas);
        };
        for(const auto& mesh:changed.deformedMeshes)rebuild(mesh,true);
        // A changed index/range/count can require a larger native allocation.
        // Preserve graph identities, never reuse an AS sized for old geometry.
        for(const auto& mesh:changed.reshapedMeshes)rebuild(mesh,false);
        commands->commitBarriers();
        // Bounds changed even when BLAS addresses did not. Reuse the original
        // table refresh/UpdateTLAS, not CreateTlas's new TLAS and sub-instance pair.
        m_tlasInstanceDataDirty=true;
    }
    bool RefreshRetainedDirectStructures(const Resources::Changes& changed) {
        RtrDiagnostics::Scope structureScope(RtrDiagnostics::Acceleration);
        if(!lighting.active||m_ommBaker||!m_blasPool||m_scene->GetSceneGraph()->GetMeshInstances().empty())return false;
        for(const auto& mesh:changed.buildMeshes)
            if(!mesh->isSkinPrototype&&bvh::IsSkinnedMesh(*mesh))return false;
        if(!m_structureCommands){
            m_structureCommands=GetDevice()->createCommandList();
            if(!m_structureCommands)throw std::runtime_error("Native structure command-list allocation failed");
            ++m_commandWork[2];
        }else ++m_commandWork[3];
        auto commands=m_structureCommands;commands->open();
        for(const auto& mesh:changed.buildMeshes){
            if(mesh->isSkinPrototype||mesh->accelStruct)continue;
            auto description=bvh::GetMeshBlasDesc(
                bvh::Config{.excludeTransmissive=m_ui.AS.ExcludeTransmissive},*mesh,nullptr,false);
            // The native load-time compactor documents two GPU round trips per
            // batch. Streaming uses the sample's ordinary NVRHI build path,
            // avoiding those CPU readbacks. Existing BLASes are not rebuilt.
            description.buildFlags=description.buildFlags&~nvrhi::rt::AccelStructBuildFlags::AllowCompaction;
            RtrDiagnostics::Scope blasScope(RtrDiagnostics::BlasBuild,1);
            auto structure=GetDevice()->createAccelStruct(description);
            if(!structure)throw std::runtime_error("Native streaming BLAS allocation failed");
            nvrhi::utils::BuildBottomLevelAccelStruct(commands,structure,description);
            mesh->accelStruct=std::move(structure);
        }
        InvalidateBindingSet();m_bindingCache->Clear();
        const auto& instances=m_scene->GetSceneGraph()->GetMeshInstances();
        size_t subInstances=0;
        for(const auto& instance:instances)subInstances+=instance->GetMesh()->geometries.size();
        // A graph content replacement can keep both native table dimensions.
        // Rebuild current TLAS contents below, but retain the original exact-
        // capacity allocation and sub-instance buffer when neither size changed.
        // No custom spare capacity, pool, growth policy or index table is added.
        const bool sameLayout=m_topLevelAS&&m_subInstanceBuffer
            &&m_topLevelAS->getDesc().topLevelMaxInstances==instances.size()
            &&m_subInstanceData.size()==subInstances;
        if(!sameLayout){RtrDiagnostics::Scope tlasScope(RtrDiagnostics::TlasAllocate);CreateTlas(commands);}
        else std::fill(m_subInstanceData.begin(),m_subInstanceData.end(),SubInstanceData::Default());
        m_tlasInstanceDataDirty=true;
        commands->close();
        const auto submitted=GetDevice()->executeCommandList(commands);
        // UpdateTLAS runs in the original async compute prefix. Its ordinary
        // frame fence predates this external build; add the missing GPU edge.
        GetDevice()->queueWaitForCommandList(nvrhi::CommandQueue::Compute,
            nvrhi::CommandQueue::Graphics,submitted);
        // The retained command list owns its scratch for the full renderer
        // lifetime. Its native submission versions, not a bridge retirement
        // record per batch, govern reuse while previous builds are in flight.
        m_ui.AccelerationStructRebuildRequested=false;
        return true;
    }
    void RenderAnalyticBeauty(nvrhi::ICommandList* commands){
        if(!m_adapterShaders){
            auto fs=std::make_shared<donut::vfs::NativeFileSystem>();
            m_adapterShaders=std::make_shared<donut::engine::ShaderFactory>(GetDevice(),fs,GetLocalPath("AdapterShaders"));
        }
        if(!m_analyticBeautyPipeline){
            // Recreated layouts/constants invalidate the cached binding set even
            // when the retained light buffer did not change (e.g. output resize).
            m_analyticBeautyBindings=nullptr;m_analyticBeautyLights=nullptr;
            nvrhi::BindingLayoutDesc layout;layout.visibility=nvrhi::ShaderType::Compute;layout.registerSpace=3;
            layout.bindings={nvrhi::BindingLayoutItem::VolatileConstantBuffer(0),nvrhi::BindingLayoutItem::StructuredBuffer_SRV(0)};
            m_analyticBeautyLayout=GetDevice()->createBindingLayout(layout);
            nvrhi::BufferDesc buffer;buffer.byteSize=32;buffer.isConstantBuffer=true;buffer.isVolatile=true;buffer.maxVersions=16;
            buffer.debugName="API analytic beauty inputs";m_analyticBeautyConstants=GetDevice()->createBuffer(buffer);
            std::vector<donut::engine::ShaderMacro> macros{
                {"RTXPT_NESTED_DIELECTRICS_QUALITY",std::to_string(m_quality.nestedQuality)},
                {"RTXPT_LP_TYPES_USE_16BIT_PRECISION",m_quality.halfPrecision?"1":"0"}};
            auto shader=m_adapterShaders->CreateShader("app/ProcessingPasses/RtrAnalyticBeauty.hlsl","main",&macros,nvrhi::ShaderType::Compute);
            if(!shader)throw std::runtime_error("Missing external analytic beauty shader");
            nvrhi::ComputePipelineDesc pipeline;pipeline.CS=shader;
            pipeline.bindingLayouts={m_bindingLayout,m_bindlessLayout,m_analyticBeautyLayout};
            m_analyticBeautyPipeline=GetDevice()->createComputePipeline(pipeline);
            if(!m_analyticBeautyPipeline)throw std::runtime_error("Could not create external analytic beauty pipeline");
        }
        analytic.Upload(GetDevice(),commands);
        if(!m_analyticBeautyBindings||m_analyticBeautyLights!=analytic.buffer){
            nvrhi::BindingSetDesc bindings;
            bindings.bindings={nvrhi::BindingSetItem::ConstantBuffer(0,m_analyticBeautyConstants),nvrhi::BindingSetItem::StructuredBuffer_SRV(0,analytic.buffer)};
            m_analyticBeautyBindings=GetDevice()->createBindingSet(bindings,m_analyticBeautyLayout);m_analyticBeautyLights=analytic.buffer;
        }
        struct Inputs{dm::float4 ambient;dm::uint4 counts;} inputs{};
        if(m_skyInput.size()>=4)inputs.ambient=dm::float4(m_skyInput[0]*m_skyInput[3],m_skyInput[1]*m_skyInput[3],m_skyInput[2]*m_skyInput[3],0);
        inputs.counts.x=uint32_t(analytic.records.size());
        commands->writeBuffer(m_analyticBeautyConstants,&inputs,sizeof(inputs));
        nvrhi::ComputeState state;state.pipeline=m_analyticBeautyPipeline;
        state.bindings={m_bindingSet,m_DescriptorTable->GetDescriptorTable(),m_analyticBeautyBindings};
        GpuProfiler::Begin(commands,"AnalyticBeauty");commands->setComputeState(state);
        SampleMiniConstants mini{};commands->setPushConstants(&mini,sizeof(mini));
        commands->dispatch((uint32_t(m_renderSize.x)+7)/8,(uint32_t(m_renderSize.y)+7)/8);
        GpuProfiler::End(commands);++m_analyticBeautyFrames;
    }
    void ApplyQuality(){
        if(m_quality.sampler>=0)m_ui.NEEType=m_quality.sampler;
        m_ui.NEEFullSamples=m_quality.samples;m_ui.NEECandidateSamples=m_quality.candidates;
        m_ui.NEEAT_QualityPreset=m_quality.adaptiveQuality;m_ui.NEEAT_LocalToGlobalSampleRatio=m_quality.localRatio;
        m_ui.DenoisingLayersActiveCount=m_quality.layers;m_ui.DenoisingLayersMaxVertexDepth=m_quality.maxDepth;
        m_ui.NestedDielectricsQuality=m_quality.nestedQuality;m_ui.UseFp16Types=m_quality.halfPrecision;
        m_ui.TexLODBias=m_quality.lodBias;m_ui.EnableBloom=m_quality.bloom;
        m_ui.BloomRadius=m_quality.bloomRadius;m_ui.BloomIntensity=m_quality.bloomIntensity;
        m_ui.EnableToneMapping=m_quality.toneMapping;
    }
    void ApplyExposure(){
        m_ui.ToneMappingParams.autoExposure=false;m_ui.ToneMappingParams.exposureValue=0.f;
        m_ui.ToneMappingParams.exposureCompensation=m_exposure;
    }
    void ApplyPresentation(){
        m_ui.BounceCount=m_presentation[0];m_ui.DiffuseBounceCount=m_presentation[1];
        m_ui.ReflexMode=m_presentation[2]?SI::ReflexMode::eLowLatency:SI::ReflexMode::eOff;
        m_ui.DLSSFGMode=m_presentation[3]?SI::DLSSGMode::eOn:SI::DLSSGMode::eOff;
        m_ui.DLSSFGNumFramesToGenerate=m_presentation[3]?uint32_t(m_presentation[3]):1u;
    }
    class SkyBlob final : public donut::vfs::IBlob {
        std::vector<uint8_t> m_bytes;
        void Put(size_t offset,uint32_t value){std::memcpy(m_bytes.data()+offset,&value,4);}
    public:
        SkyBlob(uint32_t width,uint32_t height,const float* rgb,bool constant):m_bytes(148+size_t(width)*height*16){
            Put(0,0x20534444);Put(4,124);Put(8,0x2100f);Put(12,height);Put(16,width);Put(20,width*16);Put(28,1);
            Put(76,32);Put(80,4);Put(84,0x30315844);Put(108,0x1000);Put(128,2);Put(132,3);Put(140,1);
            for(size_t pixel=0;pixel<size_t(width)*height;++pixel){
                const float* source=constant?rgb:rgb+pixel*3;const float rgba[]{source[0],source[1],source[2],1.f};
                std::memcpy(m_bytes.data()+148+pixel*16,rgba,16);
            }
        }
        const void* data()const override{return m_bytes.data();}
        size_t size()const override{return m_bytes.size();}
    };
    void ConfigureSky(const std::vector<float>& sky,nvrhi::ICommandList* commands){
        const bool mapped=sky.size()!=4;
        std::array<float,4> color=mapped?std::array<float,4>{1,1,1,1}
            :std::array<float,4>{sky[0]*sky[3],sky[1]*sky[3],sky[2]*sky[3],1};
        for(auto value:color)if(!std::isfinite(value))throw std::invalid_argument("Sky radiance overflow");
        if(!m_skyTexture||(sky!=m_skyInput&&(mapped||m_skyInput.size()!=4))){
            // EnvMapBaker loads by path and looks up the original TextureCache.
            // LoadTextureFromMemory is explicitly uncached, so handing its name
            // to the baker silently substituted black. Constant colors reuse a
            // white source; authored HDR maps replace it only on content changes.
            const auto path=std::filesystem::temp_directory_path()/("rtrenderer-sky-"
                +std::to_string(std::chrono::steady_clock::now().time_since_epoch().count())+".dds");
            const float white[]{1,1,1};
            const SkyBlob blob(mapped?uint32_t(sky[4]):2u,mapped?uint32_t(sky[5]):1u,
                mapped?sky.data()+6:white,!mapped);donut::vfs::NativeFileSystem fs;
            if(!fs.writeFile(path,blob.data(),blob.size()))
                throw std::runtime_error("Cannot create original sky texture source");
            auto texture=m_TextureCache->LoadTextureFromFile(path,
                donut::engine::TextureLoadOptions{},m_CommonPasses.get(),commands);
            if(!texture||!texture->texture){std::error_code ignored;std::filesystem::remove(path,ignored);
                throw std::runtime_error("Original TextureCache rejected environment map");}
            if(m_skyTexture)m_TextureCache->UnloadTexture(m_skyTexture);
            if(!m_skyPath.empty()){std::error_code ignored;std::filesystem::remove(m_skyPath,ignored);}
            m_skyTexture=std::move(texture);m_skyPath=path;
        }
        m_skyInput=sky;m_envMapLocalPath=m_skyPath.generic_string();m_envMapOverride=c_EnvMapSceneDefault;
        m_ui.EnvironmentMapParams.Enabled=true;
        m_ui.EnvironmentMapParams.TintColor=dm::float3(color[0],color[1],color[2]);
        // The original baker's internal radiance scale cancels its sampler's
        // ColorMultiplier; unit UI intensity preserves this DDS radiance.
        m_ui.EnvironmentMapParams.Intensity=1.f;
    }
    void ReimportMaterials() {
        RtrDiagnostics::Scope importScope(RtrDiagnostics::Materials);
        m_materialsBaker->SceneReloaded();
        m_materialsBaker->CreateRenderPassesAndLoadMaterials(m_bindlessLayout,
            m_CommonPasses, m_scene, m_currentScenePath, GetLocalPath(c_AssetsFolder));
        m_resources.ConfigureMaterials(true);
        m_importedMaterials.clear();
        for(const auto& material:m_scene->GetSceneGraph()->GetMaterials())
            m_importedMaterials.insert(material);
        InvalidateBindingSet();
        m_ui.AccelerationStructRebuildRequested = true;
    }
    CommandLineOptions m_adapterOptions;
    PrecompiledGlobals m_precompiledGlobals;
    bool m_managedScene = false, m_firstScene = true;
    bool m_presentationConfigured=false;
    std::array<int,4> m_presentation{};
    bool m_exposureConfigured=false;
    float m_exposure=0.f;
    bool m_qualityConfigured=false;
    Quality m_quality{};
    Resources m_resources;
    // The original PTMaterial keeps a raw DonutCounterpart pointer. Keep the
    // last bake's bounded set alive when graph-only removals skip a new bake;
    // release the set only after the baker clears it on reload/unload.
    std::unordered_set<std::shared_ptr<donut::engine::Material>> m_importedMaterials;
    std::shared_ptr<donut::engine::ShaderFactory> m_adapterShaders;
    std::shared_ptr<donut::engine::LoadedTexture> m_skyTexture;
    std::vector<float> m_skyInput;
    std::filesystem::path m_skyPath;
    std::string m_consumerTitle;
    OverlayState m_overlay;
    nvrhi::TextureHandle m_windowOutput;
    nvrhi::BindingLayoutHandle m_analyticBeautyLayout;
    nvrhi::ComputePipelineHandle m_analyticBeautyPipeline;
    nvrhi::BufferHandle m_analyticBeautyConstants,m_analyticBeautyLights;
    nvrhi::BindingSetHandle m_analyticBeautyBindings;
    uint64_t m_analyticBeautyFrames=0;
};

class Application final : public SampleBaseApp {
public:
    using FrameCallback = std::function<void(donut::app::DeviceManager&, uint32_t)>;

    Application(const std::vector<std::string>& options, bool managedScene)
        : m_managedScene(managedScene) {
        std::vector<const char*> argv;
        for (const auto& option : options) argv.push_back(option.c_str());
        if (!m_options.InitFromCommandLine(static_cast<int>(argv.size()), argv.data()))
            throw std::invalid_argument("Invalid original RTXPT renderer options");
        if (m_options.noWindow)
            throw std::invalid_argument("The original RTXPT sample has no headless path");
    }

    PathTracer& Renderer() const {
        if (!m_renderer) throw std::logic_error("The upstream sample is not initialized");
        return *m_renderer;
    }

    donut::app::DeviceManager& DeviceManager() const {
        if (!m_deviceManager) throw std::logic_error("The upstream device is not initialized");
        return *m_deviceManager;
    }

    std::array<int64_t,12> WindowState() const {
        auto* window = DeviceManager().GetWindow();
        int width=0,height=0,framebufferWidth=0,framebufferHeight=0;
        glfwGetWindowSize(window,&width,&height);
        glfwGetFramebufferSize(window,&framebufferWidth,&framebufferHeight);
        const auto extent=Renderer().SurfaceExtent();
        return {m_options.width,m_options.height,width,height,framebufferWidth,framebufferHeight,
            extent[0],extent[1],extent[2],extent[3],
            glfwGetWindowAttrib(window,GLFW_MAXIMIZED),glfwGetWindowMonitor(window)?1:0};
    }

    // Install after SampleBaseApp::Init so the sample has installed its own
    // callbacks. Chain them instead of replacing frame pacing or presentation.
    void ObserveFrames(FrameCallback beforeFrame, FrameCallback afterRender) {
        if (m_observing) throw std::logic_error("Frame callbacks are already installed");
        auto& callbacks = DeviceManager().m_callbacks;
        auto previousBefore = callbacks.beforeFrame;
        auto previousAfter = callbacks.afterRender;
        callbacks.beforeFrame = [previousBefore, callback = std::move(beforeFrame)](auto& manager, auto frame) {
            if (previousBefore) previousBefore(manager, frame);
            if (callback) callback(manager, frame);
        };
        callbacks.afterRender = [this,previousAfter, callback = std::move(afterRender)](auto& manager, auto frame) {
            if (previousAfter) previousAfter(manager, frame);
            // All registered render passes, including ConsumerUI, have finished.
            // Retain the actual final buffer rather than the pre-UI DisplayColor.
            Renderer().RecordWindowOutput(manager.GetCurrentBackBuffer());
            if (callback) callback(manager, frame);
        };
        m_observing = true;
    }

private:
    std::unique_ptr<Sample> CreateMainRenderPass(donut::app::DeviceManager& manager,
        const CommandLineOptions&) override {
        auto renderer = std::make_unique<PathTracer>(manager, m_options, m_managedScene);
        m_deviceManager = &manager;
        m_renderer = renderer.get();
        return renderer;
    }

    void ConfigureDefaultDeviceParams(
        donut::app::DeviceCreationParameters& parameters) const override {
        parameters.backBufferWidth = m_options.width;
        parameters.backBufferHeight = m_options.height;
        parameters.startMaximized = false;
        parameters.resizeWindowWithDisplayScale = false;
        parameters.startFullscreen = m_options.fullscreen;
        parameters.startBorderless = m_options.borderless;
        parameters.adapterIndex = m_options.adapterIndex;
        parameters.vsyncEnabled = false;
    }

    donut::app::DeviceManager* m_deviceManager = nullptr;
    PathTracer* m_renderer = nullptr;
    CommandLineOptions m_options;
    bool m_managedScene = false;
    bool m_observing = false;
};

} // namespace rtr::upstream
