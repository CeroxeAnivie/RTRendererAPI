// The host owns lifecycle and presentation. All rendering stays in the pinned RTXPT core.
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
#define WinMain RtrUnusedSampleEntryPoint
#include RTR_RTXPT_ADVANCED_SOURCE
#undef WinMain
#define GLFW_EXPOSE_NATIVE_WIN32
#include <GLFW/glfw3native.h>

namespace {
class HostApp final : public AdvancedSample {
    void ConfigureDefaultDeviceParams(donut::app::DeviceCreationParameters& p) const override {
        p.vsyncEnabled = false;
    }
};
struct Session {
    std::unique_ptr<HostApp> app;
    std::thread::id owner = std::this_thread::get_id();
    bool initialized = false;
    std::unordered_map<std::string, donut::engine::SceneGraphNode*> nodes;
    ~Session() {
        if (initialized) { app->HostDeviceManager()->GetDevice()->waitForIdle(); app->End(); }
    }
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
        // java.exe has no Agility exports: select the SDK explicitly before upstream creates D3D12.
        Microsoft::WRL::ComPtr<ID3D12SDKConfiguration> sdk;
        HRESULT hr=D3D12GetInterface(CLSID_D3D12SDKConfiguration,IID_PPV_ARGS(&sdk));
        if(FAILED(hr) || FAILED(sdk->SetSDKVersion(RTXPT_D3D_AGILITY_SDK_VERSION,(root+"/D3D12/").c_str())))
            throw std::runtime_error("Cannot select the RTXPT D3D12 Agility runtime in the Java process");
#endif
        std::vector<std::string> args {"RTRenderer-RTXPT", "--nonInteractive", "--scene", utf8(e, scene),
            "--width", std::to_string(width), "--height", std::to_string(height), "--overrideToRealtimeMode"};
        for (jsize i=0; options && i<e->GetArrayLength(options); ++i) {
            auto s = static_cast<jstring>(e->GetObjectArrayElement(options, i));
            args.push_back(utf8(e, s)); e->DeleteLocalRef(s);
        }
        std::vector<const char*> argv; for (const auto& a : args) argv.push_back(a.c_str());
        auto s = std::make_unique<Session>();
        s->app = std::make_unique<HostApp>();
        auto status = s->app->Init(static_cast<int>(argv.size()), argv.data());
        if (status != SampleBaseApp::InitReturnCodes::Success)
            throw std::runtime_error("RTXPT initialization failed with status " + std::to_string(static_cast<int>(status)));
        s->initialized = true;
        indexNodes(*s);
        s->app->HostDeviceManager()->SetWindowTitle("RTRenderer 7 - NVIDIA RTXPT");
        active = s.release(); return reinterpret_cast<jlong>(active);
    } catch (const std::exception& x) { fail(e, x); return 0; }
}

extern "C" JNIEXPORT jboolean JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_frame(
    JNIEnv* e, jclass, jlong h) {
    try {
        auto& s = session(h); auto dm = s.app->HostDeviceManager();
        if (glfwWindowShouldClose(dm->GetWindow())) return false;
        if (!dm->HostFrame()) throw std::runtime_error("RTXPT render/present failed");
        return true;
    } catch (const std::exception& x) { fail(e, x); return false; }
}

extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_camera(
    JNIEnv* e, jclass, jlong h, jstring pose, jfloat verticalFov) {
    try {
        auto sample = session(h).app->HostRenderPass();
        if (!sample->SetCurrentCameraPosDirUp(utf8(e, pose))) throw std::invalid_argument("Invalid camera pose");
        sample->SetCameraVerticalFOV(verticalFov);
    } catch (const std::exception& x) { fail(e, x); }
}

extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_loadScene(
    JNIEnv* e, jclass, jlong h, jstring path) {
    try { auto& s=session(h); s.app->HostRenderPass()->SetCurrentScene(utf8(e, path), true); indexNodes(s); }
    catch (const std::exception& x) { fail(e, x); }
}

extern SampleUIData g_sampleUIData;
extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_settings(
    JNIEnv* e, jclass, jlong h, jint depth, jint diffuse, jboolean reflex) {
    try {
        session(h);
        g_sampleUIData.BounceCount=depth;
        g_sampleUIData.DiffuseBounceCount=diffuse;
        g_sampleUIData.ShowUI=false;
        g_sampleUIData.RenderWhenOutOfFocus=true;
        g_sampleUIData.EnableVsync=false;
        g_sampleUIData.FPSLimiter=0;
        g_sampleUIData.ReflexMode=reflex ? SI::ReflexMode::eLowLatency : SI::ReflexMode::eOff;
        g_sampleUIData.DLSSFGMode=SI::DLSSGMode::eOff;
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
        glfwSetWindowSize(session(h).app->HostDeviceManager()->GetWindow(), w, height);
    } catch (const std::exception& x) { fail(e, x); }
}

extern "C" JNIEXPORT void JNICALL Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_close(
    JNIEnv* e, jclass, jlong h) {
    std::lock_guard lock(lifecycle);
    try { auto& s=session(h); active=nullptr; delete &s; }
    catch (const std::exception& x) { fail(e, x); }
}
