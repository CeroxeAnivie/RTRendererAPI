#include <jni.h>
#include <windows.h>
#include <luxcore/luxcore.h>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>
#include <algorithm>
#include <cmath>

// This file only marshals the public LuxCore API. No renderer, kernels or queue scheduler live here.
namespace {
struct Context {
    luxcore::ScenePtr scene = luxcore::Scene::Create();
    luxcore::RenderConfigRPtr config;
    luxcore::RenderSessionRPtr session;
    std::vector<float> display;
};
std::once_flag initialized;
void fail(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
std::string utf8(JNIEnv* env, jbyteArray bytes) {
    if (!bytes) throw std::invalid_argument("missing UTF-8 property data");
    std::string value(static_cast<size_t>(env->GetArrayLength(bytes)), '\0');
    env->GetByteArrayRegion(bytes, 0, static_cast<jsize>(value.size()), reinterpret_cast<jbyte*>(value.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("cannot read property data");
    return value;
}
auto properties(JNIEnv* env, jbyteArray text) {
    auto p = std::make_unique<luxrays::Properties>();
    p->SetFromString(utf8(env, text));
    return p;
}
Context& context(jlong handle) {
    if (!handle) throw std::invalid_argument("closed LuxCore session");
    return *reinterpret_cast<Context*>(handle);
}
void* direct(JNIEnv* env, jobject buffer, size_t required) {
    if (!buffer || env->GetDirectBufferCapacity(buffer) < 0
            || static_cast<size_t>(env->GetDirectBufferCapacity(buffer)) < required)
        throw std::invalid_argument("direct buffer is missing or too small");
    return env->GetDirectBufferAddress(buffer);
}
void quietLog(const char*) { }
}
#define JNI(name) extern "C" JNIEXPORT name JNICALL
#define TRY try
#define CATCH_VOID catch (const std::exception& e) { fail(env, e.what()); }
#define CATCH_RETURN(value) catch (const std::exception& e) { fail(env, e.what()); return value; }

JNI(void) Java_top_ceroxe_rt_renderer_luxcore_LuxCoreNative_initialize(JNIEnv* env, jclass, jstring bin) {
    TRY {
        const jchar* chars = env->GetStringChars(bin, nullptr);
        if (!chars) return;
        std::wstring directory(reinterpret_cast<const wchar_t*>(chars), env->GetStringLength(bin));
        env->ReleaseStringChars(bin, chars);
        std::call_once(initialized, [&] {
            // Explicit DLL paths keep the SDK independent of PATH and the working directory.
            for (const wchar_t* name : {L"nvrtc-builtins64_128.dll", L"nvrtc64_120_0.dll", L"luxcore.dll"}) {
                const auto path = directory + L"/" + name;
                if (!LoadLibraryExW(path.c_str(), nullptr, LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_DEFAULT_DIRS))
                    throw std::runtime_error("Cannot load the pinned LuxCore runtime; Windows error=" + std::to_string(GetLastError()));
            }
            luxcore::Init(quietLog);
        });
    } CATCH_VOID
}
JNI(jlong) Java_top_ceroxe_rt_renderer_luxcore_LuxCoreNative_create(JNIEnv* env, jclass) {
    TRY { return reinterpret_cast<jlong>(new Context()); } CATCH_RETURN(0)
}
JNI(void) Java_top_ceroxe_rt_renderer_luxcore_LuxCoreNative_close(JNIEnv* env, jclass, jlong handle) {
    TRY { std::unique_ptr<Context> owned(reinterpret_cast<Context*>(handle));
        if (owned && owned->session && owned->session->IsStarted()) owned->session->Stop();
    } CATCH_VOID
}
JNI(void) Java_top_ceroxe_rt_renderer_luxcore_LuxCoreNative_beginEdit(JNIEnv* env, jclass, jlong handle) {
    TRY { auto& c = context(handle); if (c.session) c.session->BeginSceneEdit(); } CATCH_VOID
}
JNI(void) Java_top_ceroxe_rt_renderer_luxcore_LuxCoreNative_endEdit(JNIEnv* env, jclass, jlong handle) {
    TRY { auto& c = context(handle); if (c.session) c.session->EndSceneEdit(); } CATCH_VOID
}
JNI(void) Java_top_ceroxe_rt_renderer_luxcore_LuxCoreNative_parse(JNIEnv* env, jclass, jlong handle, jbyteArray text) {
    TRY { context(handle).scene->Parse(properties(env, text)); } CATCH_VOID
}
JNI(void) Java_top_ceroxe_rt_renderer_luxcore_LuxCoreNative_deleteObjects(JNIEnv* env, jclass, jlong handle, jbyteArray names) {
    TRY { auto& scene = *context(handle).scene; std::string all = utf8(env, names); size_t first = 0;
        while (first < all.size()) { size_t end = all.find('\n', first); if (end == std::string::npos) end = all.size();
            if (end > first) scene.DeleteObject(all.substr(first, end-first)); first = end+1; }
    } CATCH_VOID
}
JNI(void) Java_top_ceroxe_rt_renderer_luxcore_LuxCoreNative_deleteLights(JNIEnv* env, jclass, jlong handle, jbyteArray names) {
    TRY { auto& scene = *context(handle).scene; std::string all = utf8(env, names); size_t first = 0;
        while (first < all.size()) { size_t end = all.find('\n', first); if (end == std::string::npos) end = all.size();
            if (end > first) scene.DeleteLight(all.substr(first, end-first)); first = end+1; }
    } CATCH_VOID
}
JNI(void) Java_top_ceroxe_rt_renderer_luxcore_LuxCoreNative_defineMesh(JNIEnv* env, jclass, jlong handle,
        jbyteArray name, jint vertices, jint triangles, jobject positions, jobject indices, jobject normals, jobject uvs) {
    TRY {
        if (vertices < 1 || triangles < 1) throw std::invalid_argument("empty mesh");
        const size_t vb = static_cast<size_t>(vertices)*3*sizeof(float), ib = static_cast<size_t>(triangles)*3*sizeof(unsigned int);
        auto* sourceP = direct(env, positions, vb); auto* sourceI = direct(env, indices, ib);
        auto* sourceN = normals ? direct(env, normals, vb) : nullptr;
        auto* sourceUV = uvs ? direct(env, uvs, static_cast<size_t>(vertices)*2*sizeof(float)) : nullptr;
        auto p = std::unique_ptr<float[]>(luxcore::Scene::AllocVerticesBuffer(vertices));
        auto i = std::unique_ptr<unsigned int[]>(luxcore::Scene::AllocTrianglesBuffer(triangles));
        auto n = sourceN ? std::make_unique<float[]>(vertices*3) : nullptr;
        auto uv = sourceUV ? std::make_unique<float[]>(vertices*2) : nullptr;
        std::memcpy(p.get(), sourceP, vb); std::memcpy(i.get(), sourceI, ib);
        if (sourceN) std::memcpy(n.get(), sourceN, vb);
        if (sourceUV) std::memcpy(uv.get(), sourceUV, static_cast<size_t>(vertices)*2*sizeof(float));
        auto& scene = *context(handle).scene;
        scene.SetDeleteMeshData(true);
        scene.DefineMesh(utf8(env, name), vertices, triangles, p.get(), i.get(), n.get(), uv.get(), nullptr, nullptr);
        p.release(); i.release(); n.release(); uv.release();
    } CATCH_VOID
}
JNI(void) Java_top_ceroxe_rt_renderer_luxcore_LuxCoreNative_defineImage(JNIEnv* env, jclass, jlong handle,
        jbyteArray name, jint width, jint height, jobject pixels) {
    TRY {
        if (width < 1 || height < 1) throw std::invalid_argument("empty image");
        auto* data = static_cast<float*>(direct(env, pixels, static_cast<size_t>(width)*height*4*sizeof(float)));
        context(handle).scene->DefineImageMap<float>(utf8(env, name), data, 1.f, 4, width, height);
    } CATCH_VOID
}
JNI(void) Java_top_ceroxe_rt_renderer_luxcore_LuxCoreNative_start(JNIEnv* env, jclass, jlong handle, jbyteArray text) {
    TRY { auto& c = context(handle);
        if (c.session) throw std::logic_error("session already started");
        c.config = luxcore::RenderConfig::Create(properties(env, text), c.scene);
        c.session = luxcore::RenderSession::Create(c.config); c.session->Start();
    } CATCH_VOID
}
JNI(void) Java_top_ceroxe_rt_renderer_luxcore_LuxCoreNative_resize(JNIEnv* env, jclass, jlong handle, jbyteArray text) {
    TRY { auto& c = context(handle); if (c.session) c.session->Parse(properties(env, text)); } CATCH_VOID
}
JNI(jdouble) Java_top_ceroxe_rt_renderer_luxcore_LuxCoreNative_samples(JNIEnv* env, jclass, jlong handle) {
    TRY { auto& c = context(handle); if (!c.session) return 0;
        return c.session->GetFilm().GetTotalSampleCount();
    } CATCH_RETURN(0)
}
JNI(jdouble) Java_top_ceroxe_rt_renderer_luxcore_LuxCoreNative_readFilm(JNIEnv* env, jclass, jlong handle, jobject target) {
    TRY { auto& c = context(handle); auto& film = c.session->GetFilm();
        auto* data = static_cast<float*>(direct(env, target, film.GetOutputSize(luxcore::Film::OUTPUT_RGBA)*sizeof(float)));
        film.GetOutput<float>(luxcore::Film::OUTPUT_RGBA, data);
        return film.GetTotalSampleCount();
    } CATCH_RETURN(0)
}
JNI(jdouble) Java_top_ceroxe_rt_renderer_luxcore_LuxCoreNative_readDisplay(JNIEnv* env, jclass, jlong handle, jobject target) {
    TRY { auto& c = context(handle); auto& film = c.session->GetFilm();
        const size_t count = film.GetOutputSize(luxcore::Film::OUTPUT_RGBA_IMAGEPIPELINE);
        c.display.resize(count);
        film.GetOutput<float>(luxcore::Film::OUTPUT_RGBA_IMAGEPIPELINE, c.display.data());
        auto* output = static_cast<unsigned char*>(direct(env, target, count));
        const size_t row = film.GetWidth() * 4;
        for (size_t y=0; y<film.GetHeight(); ++y) for (size_t x=0; x<row; ++x) {
            float v = c.display[(film.GetHeight()-1-y)*row+x];
            output[y*row+x] = static_cast<unsigned char>(std::isfinite(v) ? std::clamp(v, 0.f, 1.f)*255.f+.5f : 0);
        }
        return film.GetTotalSampleCount();
    } CATCH_RETURN(0)
}
JNI(jstring) Java_top_ceroxe_rt_renderer_luxcore_LuxCoreNative_statistics(JNIEnv* env, jclass, jlong handle) {
    TRY { auto& c=context(handle); c.session->UpdateStats();
        const auto text=c.session->GetStats()->ToString();
        return env->NewStringUTF(text.c_str());
    } CATCH_RETURN(nullptr)
}
