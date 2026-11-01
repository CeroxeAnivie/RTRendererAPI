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
#include <donut/engine/TextureCache.h>
#include <json/json.h>
#include "StreamlineIntegration.h"
#define WinMain RtrUnusedSampleEntryPoint
#include RTR_RTXPT_ADVANCED_SOURCE
#undef WinMain
#include <Materials/MaterialsBaker.h>
#define GLFW_EXPOSE_NATIVE_WIN32
#include <GLFW/glfw3native.h>
#include "rtxpt_shutdown.h"
#include "rtxpt_shared_frames.h"
extern SampleUIData g_sampleUIData;

namespace {
class HostPathTracer final : public AdvancedPathTracer {
    bool smallPointLighting=false;
    bool lightingSceneDirty=true;
    bool eligibleLightingScene=false;
    int previousNeeType=2;
    bool smallPointScene() const {
        if(!GetScene())return false;
        const auto graph=GetScene()->GetSceneGraph();
        const auto& lights=graph->GetLights();
        size_t analyticCount=0;
        bool pointFound=false;
        for(const auto& light:lights) {
            const auto type=light->GetLightType();
            if(type==LightType_Point || type==LightType_Spot || type==LightType_Directional)++analyticCount;
            if(type==LightType_Point
                && static_cast<const donut::engine::PointLight*>(light.get())->radius==0.f)
                pointFound=true;
            if(type==LightType_Spot
                && static_cast<const donut::engine::SpotLight*>(light.get())->radius==0.f)
                pointFound=true;
        }
        if(!pointFound || analyticCount>4)return false;
        for(const auto& material:graph->GetMaterials())
            if(material->emissiveColor.x!=0.f || material->emissiveColor.y!=0.f || material->emissiveColor.z!=0.f)
                return false;
        return true;
    }
public:
    using AdvancedPathTracer::AdvancedPathTracer;
    nvrhi::ITexture* HostDisplayColor() const { return m_renderTargets ? m_renderTargets->LdrColor.Get() : nullptr; }
    void SceneLoaded() override {
        AdvancedPathTracer::SceneLoaded();
        lightingSceneDirty=true;
    }
    void UpdateLightingPolicy() {
        if(lightingSceneDirty) {
            eligibleLightingScene=smallPointScene();
            lightingSceneDirty=!GetScene();
        }
        const char* overrideValue=std::getenv("RTR_RTXPT_DIAGNOSTIC_SMALL_POINTS");
        const bool rawPolicy=g_sampleUIData.RealtimeMode && g_sampleUIData.RealtimeAA==2
            && !g_sampleUIData.StandaloneDenoiser && !g_sampleUIData.ActualUseRTXDIPasses()
            && g_sampleUIData.ActualRadianceCacheMode()==0 && g_sampleUIData.ActualSamplesPerPixel()==1;
        const bool enabled=eligibleLightingScene && rawPolicy && !(overrideValue && std::string(overrideValue)=="0");
        if(enabled==smallPointLighting)return;
        if(enabled) {previousNeeType=g_sampleUIData.NEEType;g_sampleUIData.NEEType=1;}
        else g_sampleUIData.NEEType=previousNeeType;
        smallPointLighting=enabled;
        donut::log::info("RTXPT native small-point lighting %s; standalone denoising unchanged",enabled?"ACTIVE":"DISABLED");
    }
    void GetCustomPTPipelineGlobalMacros(std::vector<donut::engine::ShaderMacro>& macros) override {
        AdvancedPathTracer::GetCustomPTPipelineGlobalMacros(macros);
        if(smallPointLighting)
            macros.push_back({"RTR_EXACT_SMALL_POINTS","1"});
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
    std::unique_ptr<Sample> CreateMainRenderPass(donut::app::DeviceManager& dm,const CommandLineOptions& options) override {
        return std::make_unique<HostPathTracer>(dm,options);
    }
    void ConfigureDefaultDeviceParams(donut::app::DeviceCreationParameters& p) const override {
        p.vsyncEnabled = false;
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
    uint64_t rendered=0;
    struct GpuSample { nvrhi::TimerQueryHandle query; bool pending=false; };
    std::array<GpuSample,8> gpuSamples;
    nvrhi::CommandListHandle timerCommands;
    GpuSample* currentGpuSample=nullptr;
    uint64_t gpuSampleCount=0,gpuMaxNs=0;
    double gpuAverageNs=0;
    void beginGpuSample(uint32_t index) {
        currentGpuSample=nullptr;
        if(g_sampleUIData.HostOverlayText.empty())return;
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
            for(auto& sample:gpuSamples)sample.query=nullptr;
            app->End();
        }
        // Never leave the sample's [this] log callback installed during member teardown.
        donut::log::SetCallback(previousLog);
        app.reset();
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
    JNIEnv* e, jclass, jstring runtime, jstring scene, jint width, jint height, jboolean externalPresentation, jobjectArray options) {
    std::lock_guard lock(lifecycle);
    try {
        if (active) throw std::runtime_error("The upstream RTXPT core supports one resident session per process");
        if (width < 1 || height < 1) throw std::invalid_argument("Invalid output dimensions");
        auto root = utf8(e, runtime);
        if (_putenv_s("RTR_RTXPT_RUNTIME", root.c_str())) throw std::runtime_error("Cannot configure runtime root");
#if defined(RTXPT_D3D_AGILITY_SDK_VERSION)
        configureAgility(std::filesystem::u8path(root));
#endif
        std::vector<std::string> args {"RTRenderer-RTXPT", "--nonInteractive", "--scene", utf8(e, scene),
            "--width", std::to_string(width), "--height", std::to_string(height), "--overrideToRealtimeMode"};
        // A/B the upstream scheduler without changing its shaders, SPP, or quality policy.
        if(const char* async=std::getenv("RTR_RTXPT_DIAGNOSTIC_ASYNC_PREFIX")) {
            if(std::string(async)!="0" && std::string(async)!="1")
                throw std::invalid_argument("Diagnostic async prefix must be 0 or 1");
            args.emplace_back(std::string("--asyncPrefix=")+(async[0]=='1'?"true":"false"));
            donut::log::info("RTXPT diagnostic upstream asyncPrefix=%s",async);
        }
        for (jsize i=0; options && i<e->GetArrayLength(options); ++i) {
            auto s = static_cast<jstring>(e->GetObjectArrayElement(options, i));
            args.push_back(utf8(e, s)); e->DeleteLocalRef(s);
        }
        std::vector<const char*> argv; for (const auto& a : args) argv.push_back(a.c_str());
        auto s = std::make_unique<Session>();
        s->externalPresentation=externalPresentation;
        donut::log::ConsoleApplicationMode();
        g_sampleUIData = SampleUIData{};
        s->app = std::make_unique<HostApp>();
        s->app->HostDeviceManager()->HostExternalPresentation=externalPresentation;
        auto status = s->app->Init(static_cast<int>(argv.size()), argv.data());
        if (status != SampleBaseApp::InitReturnCodes::Success)
            throw std::runtime_error("RTXPT initialization failed with status " + std::to_string(static_cast<int>(status)));
        s->initialized = true;
        s->app->HostDeviceManager()->HostExternalPresentation=externalPresentation;
        if(externalPresentation)glfwHideWindow(s->app->HostDeviceManager()->GetWindow());
        donut::app::StreamlineIntegration::Get().hostFrameGeneration={};
        if(!s->app->HostRenderPass()->GetScene()) throw std::runtime_error("RTXPT scene failed to load");
        auto& afterPresent=s->app->HostDeviceManager()->m_callbacks.afterPresent;
        auto upstreamAfterPresent=afterPresent;
        afterPresent = [ptr=s.get(),upstreamAfterPresent](auto& dm, uint32_t index) {
            if(upstreamAfterPresent)upstreamAfterPresent(dm,index);
            if(!ptr->externalPresentation)++ptr->presented;
        };
        auto& beforeRender=s->app->HostDeviceManager()->m_callbacks.beforeRender;
        auto upstreamBeforeRender=beforeRender;
        beforeRender=[ptr=s.get(),upstreamBeforeRender](auto& dm,uint32_t index) {
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
            if(ptr->externalPresentation)ptr->sharedFrames.publish(dm.GetDevice(),
                static_cast<HostPathTracer*>(ptr->app->HostRenderPass())->HostDisplayColor());
            ++ptr->rendered;
        };
        indexNodes(*s);
        s->app->HostDeviceManager()->SetWindowTitle("RTRenderer 7 - NVIDIA RTXPT");
        active = s.release(); return reinterpret_cast<jlong>(active);
    } catch (const std::exception& x) { fail(e, x); return 0; }
}

extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_overlay(
    JNIEnv* e,jclass,jlong h,jstring text) {
    try { session(h);g_sampleUIData.HostOverlayText=utf8(e,text); }
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
            g_sampleUIData.ToneMappingParams.exposureValue,double(g_sampleUIData.EnableBloom)};
        auto result=e->NewDoubleArray(5);if(result)e->SetDoubleArrayRegion(result,0,5,values);return result;
    }catch(const std::exception& x){fail(e,x);return nullptr;}
}

extern "C" JNIEXPORT jint JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_frame(
    JNIEnv* e, jclass, jlong h) {
    try {
        auto& s = session(h); auto dm = s.app->HostDeviceManager();
        if (glfwWindowShouldClose(dm->GetWindow())) return 0;
        static_cast<HostPathTracer*>(s.app->HostRenderPass())->UpdateLightingPolicy();
        auto before=s.externalPresentation?s.rendered:s.presented;
        if (!dm->HostFrame()) throw std::runtime_error("RTXPT render/present failed");
        return (s.externalPresentation?s.rendered:s.presented)>before ? 2 : 1;
    } catch (const std::exception& x) { fail(e, x); return 0; }
}

extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_awaitIdle(
    JNIEnv* e, jclass, jlong h) {
    try { session(h).app->HostDeviceManager()->GetDevice()->waitForIdle(); }
    catch(const std::exception& x) { fail(e,x); }
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
            jint(g_sampleUIData.ActualUseStandaloneDenoiser()&&g_sampleUIData.NRDMethod==NrdConfig::DenoiserMethod::RELAX)};
        auto result=e->NewIntArray(20); if(result)e->SetIntArrayRegion(result,0,20,values);return result;
    }catch(const std::exception& x){fail(e,x);return nullptr;}
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
        s.app->HostRenderPass()->SetCurrentScene(utf8(e, path), true); indexNodes(s);
    }
    catch (const std::exception& x) { fail(e, x); }
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
        if(!g_sampleUIData.HostOverlayText.empty() && s.lastPresentedTexture)
            saved=donut::engine::SaveTextureToFile(s.app->HostDeviceManager()->GetDevice(),
                s.app->HostRenderPass()->GetCommonPasses().get(),s.lastPresentedTexture,nvrhi::ResourceStates::Present,output.c_str());
        else saved=static_cast<HostPathTracer*>(s.app->HostRenderPass())->Capture(output.c_str());
        if(!saved)
            throw std::runtime_error("RTXPT capture failed");
    } catch(const std::exception& x) { fail(e,x); }
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_settings(
    JNIEnv* e, jclass, jlong h, jint depth, jint diffuse, jboolean reflex,
    jint frameGenerationMode, jint generatedFrames, jint samplesPerPixel, jint realtimeAa,
    jint dlssMode, jboolean standaloneDenoiser, jboolean relaxDenoiser, jboolean restirDi, jboolean restirGi, jboolean sharc) {
    try {
        auto& s=session(h);
        if(frameGenerationMode<0 || frameGenerationMode>1 || generatedFrames<1 || generatedFrames>3)
            throw std::invalid_argument("Invalid fixed DLSS frame-generation configuration");
        if(depth<1 || depth>64 || diffuse<0 || diffuse>depth || samplesPerPixel<1 || samplesPerPixel>64
            || realtimeAa<0 || realtimeAa>3 || dlssMode<1 || dlssMode>6)
            throw std::invalid_argument("Invalid RTXPT render settings");
        s.requestedFrameGenerationMode=frameGenerationMode;
        s.requestedGeneratedFrames=generatedFrames;
        g_sampleUIData.RealtimeMode=true;
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
