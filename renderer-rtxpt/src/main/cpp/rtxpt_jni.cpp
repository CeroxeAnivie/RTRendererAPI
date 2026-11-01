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
#include <donut/engine/TextureCache.h>
#include <json/json.h>
#include "StreamlineIntegration.h"
#define WinMain RtrUnusedSampleEntryPoint
#include RTR_RTXPT_ADVANCED_SOURCE
#undef WinMain
#include <Materials/MaterialsBaker.h>
#define GLFW_EXPOSE_NATIVE_WIN32
#include <GLFW/glfw3native.h>
extern SampleUIData g_sampleUIData;

namespace {
// SL 2.14 queues RR resource-destruction functors in sl.common. Keep their
// defining module loaded until common has drained them during slShutdown.
// Resolve the actual plugin (including NGX updates) via SL's public API.
class ShutdownModuleReference {
    HMODULE module = nullptr;
public:
    ShutdownModuleReference() {
        void* callback = nullptr;
        if (slGetFeatureFunction(sl::kFeatureDLSS_RR, "slDLSSDGetState", callback) == sl::Result::eOk && callback) {
            if (!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS,
                    reinterpret_cast<LPCWSTR>(callback), &module))
                throw std::runtime_error("Cannot retain Streamline RR module for shutdown");
        }
    }
    ~ShutdownModuleReference() { if (module) FreeLibrary(module); }
    ShutdownModuleReference(const ShutdownModuleReference&) = delete;
    ShutdownModuleReference& operator=(const ShutdownModuleReference&) = delete;
};
class HostPathTracer final : public AdvancedPathTracer {
public:
    using AdvancedPathTracer::AdvancedPathTracer;
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
    factory->ApplyToGlobalState();
    selected = directory;
}
}

extern "C" JNIEXPORT jlong JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_create(
    JNIEnv* e, jclass, jstring runtime, jstring scene, jint width, jint height, jobjectArray options) {
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
        for (jsize i=0; options && i<e->GetArrayLength(options); ++i) {
            auto s = static_cast<jstring>(e->GetObjectArrayElement(options, i));
            args.push_back(utf8(e, s)); e->DeleteLocalRef(s);
        }
        std::vector<const char*> argv; for (const auto& a : args) argv.push_back(a.c_str());
        auto s = std::make_unique<Session>();
        donut::log::ConsoleApplicationMode();
        g_sampleUIData = SampleUIData{};
        s->app = std::make_unique<HostApp>();
        auto status = s->app->Init(static_cast<int>(argv.size()), argv.data());
        if (status != SampleBaseApp::InitReturnCodes::Success)
            throw std::runtime_error("RTXPT initialization failed with status " + std::to_string(static_cast<int>(status)));
        s->initialized = true;
        if(!s->app->HostRenderPass()->GetScene()) throw std::runtime_error("RTXPT scene failed to load");
        auto& afterPresent=s->app->HostDeviceManager()->m_callbacks.afterPresent;
        auto upstreamAfterPresent=afterPresent;
        afterPresent = [ptr=s.get(),upstreamAfterPresent](auto& dm, uint32_t index) {
            if(upstreamAfterPresent)upstreamAfterPresent(dm,index);
            ++ptr->presented;
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
        };
        indexNodes(*s);
        s->app->HostDeviceManager()->SetWindowTitle("RTRenderer 7 - NVIDIA RTXPT");
        active = s.release(); return reinterpret_cast<jlong>(active);
    } catch (const std::exception& x) { fail(e, x); return 0; }
}

extern "C" JNIEXPORT jint JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_frame(
    JNIEnv* e, jclass, jlong h) {
    try {
        auto& s = session(h); auto dm = s.app->HostDeviceManager();
        if (glfwWindowShouldClose(dm->GetWindow())) return 0;
        auto before=s.presented;
        if (!dm->HostFrame()) throw std::runtime_error("RTXPT render/present failed");
        return s.presented>before ? 2 : 1;
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
            jint(g_sampleUIData.IsDLSSFGSupported),jint(g_sampleUIData.IsReflexSupported),jint(session(h).app->IsSERSupported())};
        auto result=e->NewIntArray(19); if(result)e->SetIntArrayRegion(result,0,19,values);return result;
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
        if(!static_cast<HostPathTracer*>(s.app->HostRenderPass())->Capture(utf8(e,path).c_str()))
            throw std::runtime_error("RTXPT capture failed");
    } catch(const std::exception& x) { fail(e,x); }
}
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_settings(
    JNIEnv* e, jclass, jlong h, jint depth, jint diffuse, jboolean reflex,
    jint frameGenerationMode, jint generatedFrames, jint samplesPerPixel, jint realtimeAa,
    jint dlssMode, jboolean standaloneDenoiser, jboolean restirDi, jboolean restirGi, jboolean sharc) {
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
        g_sampleUIData.RealtimeSamplesPerPixel=samplesPerPixel;
        g_sampleUIData.RealtimeAA=realtimeAa;
        g_sampleUIData.StandaloneDenoiser=standaloneDenoiser;
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
