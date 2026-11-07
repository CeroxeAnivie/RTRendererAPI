// The sample implementation is compiled unchanged into this external adapter.
// Its WinMain is an unused ordinary symbol in this DLL; no macro renaming or
// link interception changes any upstream path, window or rendering behavior.
#include RTR_RTXPT_ADVANCED_SOURCE
#include <jni.h>
#include "rtxpt_upstream_session.h"
#include "rtxpt_signal_frames.h"
#include <donut/engine/TextureCache.h>
#include <filesystem>
#include <string>
#include <vector>
#include <unordered_map>
#include <memory>
#include <mutex>
#include <fstream>
#define GLFW_EXPOSE_NATIVE_WIN32
#include <GLFW/glfw3native.h>

namespace {
std::string String(JNIEnv* env, jstring value) {
    if (!value) throw std::invalid_argument("Null UTF-8 string");
    const auto length=env->GetStringLength(value);
    const jchar* chars=env->GetStringChars(value,nullptr);
    if(!chars)throw std::runtime_error("JNI string conversion failed");
    struct Release {JNIEnv* env;jstring value;const jchar* chars;~Release(){env->ReleaseStringChars(value,chars);}} release{env,value,chars};
    if(!length)return {};
    static_assert(sizeof(jchar)==sizeof(wchar_t));
    const auto* wide=reinterpret_cast<const wchar_t*>(chars);
    const int count=WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,wide,length,nullptr,0,nullptr,nullptr);
    if(!count)throw std::invalid_argument("Invalid Unicode input");
    std::string result(size_t(count),'\0');
    if(WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,wide,length,result.data(),count,nullptr,nullptr)!=count)
        throw std::runtime_error("UTF-8 string conversion failed");
    return result;
}
void Throw(JNIEnv* env, const std::exception& exception) {
    if (!env->ExceptionCheck())
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), exception.what());
}

struct Renderer {
    std::unique_ptr<rtr::upstream::Session> session;
    RtrSignalFrames signals;
    uint64_t sequence = 0, revision = 0;
};
std::mutex rendererMutex;
std::unordered_map<jlong, std::shared_ptr<Renderer>> renderers;
jlong nextRenderer = 1;

std::shared_ptr<Renderer> Get(jlong handle) {
    std::lock_guard lock(rendererMutex);
    auto found = renderers.find(handle);
    if (found == renderers.end()) throw std::invalid_argument("Unknown or closed renderer handle");
    return found->second;
}

std::vector<std::string> Strings(JNIEnv* env, jobjectArray values) {
    std::vector<std::string> result;
    if (!values) return result;
    for (jsize i = 0; i < env->GetArrayLength(values); ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(values, i));
        result.push_back(String(env, value));
        env->DeleteLocalRef(value);
    }
    return result;
}
std::vector<float> Floats(JNIEnv* env, jfloatArray values) {
    if (!values) throw std::invalid_argument("Null float publication");
    std::vector<float> result(env->GetArrayLength(values));
    env->GetFloatArrayRegion(values, 0, jsize(result.size()), result.data());
    if (env->ExceptionCheck()) throw std::runtime_error("Cannot copy float publication");
    for (auto value : result) if (!std::isfinite(value))
        throw std::invalid_argument("Non-finite float publication");
    return result;
}
std::vector<int64_t> Longs(JNIEnv* env, jlongArray values) {
    if (!values) throw std::invalid_argument("Null identity publication");
    std::vector<int64_t> result(env->GetArrayLength(values));
    env->GetLongArrayRegion(values, 0, jsize(result.size()), reinterpret_cast<jlong*>(result.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("Cannot copy identity publication");
    return result;
}
std::vector<uint8_t> Bytes(JNIEnv* env, jbyteArray values) {
    if (!values) throw std::invalid_argument("Null application publication");
    const auto count = env->GetArrayLength(values);
    if (count > 16*1024*1024 || count%4) throw std::invalid_argument("Application publication exceeds aligned capacity");
    std::vector<uint8_t> result(count);
    if (count) env->GetByteArrayRegion(values, 0, count, reinterpret_cast<jbyte*>(result.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("Cannot copy application publication");
    return result;
}
jlongArray LongArray(JNIEnv* env, const std::vector<jlong>& values) {
    auto result = env->NewLongArray(jsize(values.size()));
    if (result && !values.empty()) env->SetLongArrayRegion(result, 0, jsize(values.size()), values.data());
    return result;
}
template<size_t Count> jfloatArray FloatArray(JNIEnv* env,const std::array<float,Count>& values){
    auto result=env->NewFloatArray(jsize(Count));
    if(result)env->SetFloatArrayRegion(result,0,jsize(Count),values.data());
    return result;
}
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptUpstreamProof_runNative(
    JNIEnv* env, jclass, jobjectArray arguments, jstring captureFile, jint frames) {
    try {
        if (frames < 1 || frames > 10000) throw std::invalid_argument("frames must be 1..10000");
        std::vector<std::string> storage{"rtrenderer-upstream"};
        if (arguments) {
            const auto count = env->GetArrayLength(arguments);
            for (jsize i = 0; i < count; ++i) {
                auto argument = static_cast<jstring>(env->GetObjectArrayElement(arguments, i));
                storage.push_back(String(env, argument));
                env->DeleteLocalRef(argument);
            }
        }
        const auto capture = String(env, captureFile);
        rtr::upstream::Session session(std::move(storage));
        jlong readyFrames = 0;
        jlong width = 0, height = 0;
        bool captured = false;
        std::string captureError;
        for (jint index = 0; index < frames; ++index) {
            const auto completed = session.Frame([&](auto& application) {
                auto output = application.Renderer().Output(rtr::upstream::Signal::DisplayColor);
                if (!output) throw std::runtime_error("Original RTXPT rendered no display target");
                ++readyFrames;
                width = output->getDesc().width;
                height = output->getDesc().height;
                if (index + 1 != frames) return;
                captured = donut::engine::SaveTextureToFile(application.DeviceManager().GetDevice(),
                    application.Renderer().GetCommonPasses().get(),
                    application.DeviceManager().GetCurrentBackBuffer(),
                    nvrhi::ResourceStates::Present, capture.c_str());
                if (!captured) captureError = "Original Donut SaveTextureToFile failed";
            });
            if (!completed) throw std::runtime_error("Original window closed before this request completed");
        }
        session.Close();
        if (!captureError.empty()) throw std::runtime_error(captureError);
        if (!captured) throw std::runtime_error("Original sample ended before a frame was captured");
        const jlong values[]{readyFrames, width, height, captured ? 1 : 0};
        auto result = env->NewLongArray(4);
        if (result) env->SetLongArrayRegion(result, 0, 4, values);
        return result;
    } catch (const std::exception& error) {
        Throw(env, error);
        return nullptr;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_bridgeAbiVersion(JNIEnv*, jclass) {
    return 8;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_progressWindowProof(JNIEnv* env,jclass) {
    try {
        std::lock_guard lock(rendererMutex);
        if(!renderers.empty()||HelpersIsNonInteractive())
            throw std::logic_error("Progress proof requires a fresh process before renderer initialization");
        auto windows=[](){
            struct State{DWORD process;int count;} state{GetCurrentProcessId(),0};
            EnumWindows([](HWND window,LPARAM parameter)->BOOL {
                auto& state=*reinterpret_cast<State*>(parameter);DWORD process=0;
                GetWindowThreadProcessId(window,&process);char name[128]{};
                if(process==state.process&&GetClassNameA(window,name,sizeof(name))
                    &&std::string_view(name)=="ProgressWindowClass")++state.count;
                return TRUE;
            },reinterpret_cast<LPARAM>(&state));
            return state.count;
        };
        std::array<jlong,8> values{};values[1]=windows();
        const int interactive=ProgressBarStart("RTRenderer original interactive progress proof");values[0]=interactive;
        const auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(2);
        while(windows()==values[1]&&std::chrono::steady_clock::now()<deadline)
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        values[2]=windows();if(interactive>=0)ProgressBarStop(interactive);values[3]=windows();
        HelpersSetNonInteractive();values[5]=windows();
        const int quiet=ProgressBarStart("RTRenderer noninteractive progress proof");values[4]=quiet;
        if(quiet>=0)ProgressBarStop(quiet);values[6]=windows();values[7]=HelpersIsNonInteractive()?1:0;
        auto result=env->NewLongArray(static_cast<jsize>(values.size()));
        if(result)env->SetLongArrayRegion(result,0,static_cast<jsize>(values.size()),values.data());
        return result;
    }catch(const std::exception& error){Throw(env,error);return nullptr;}
}

extern "C" JNIEXPORT jlong JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_create(JNIEnv* env, jclass, jstring runtime,
    jstring scene, jint width, jint height, jboolean externalPresentation, jobjectArray options) {
    try {
        if (width < 1 || height < 1 || width > 16384 || height > 16384)
            throw std::invalid_argument("Invalid render extent");
        if (!std::filesystem::is_directory(std::filesystem::u8path(String(env, runtime))))
            throw std::invalid_argument("Missing original RTXPT runtime directory");
        std::vector<std::string> arguments{"rtrenderer-upstream"};
        auto requested = Strings(env, options);
        arguments.insert(arguments.end(), requested.begin(), requested.end());
        arguments.push_back("--width=" + std::to_string(width));
        arguments.push_back("--height=" + std::to_string(height));
        const auto scenePath = scene ? String(env, scene) : std::string();
        if (!scenePath.empty()) arguments.push_back("--scene=" + scenePath);
        RtrDiagnostics::Reset();
        auto renderer = std::make_shared<Renderer>();
        renderer->session = std::make_unique<rtr::upstream::Session>(std::move(arguments), scenePath.empty());
        if (externalPresentation) renderer->session->Invoke([](auto& app) {
            glfwHideWindow(app.DeviceManager().GetWindow());
        });
        std::lock_guard lock(rendererMutex);
        const auto handle = nextRenderer++;
        renderers.emplace(handle, std::move(renderer));
        return handle;
    } catch (const std::exception& error) { Throw(env, error); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_frameContext(JNIEnv* env, jclass, jlong handle, jlong sequence, jlong revision) {
    try {
        if (sequence < 0 || revision < 0) throw std::invalid_argument("Invalid frame identity");
        auto renderer = Get(handle);
        renderer->sequence = uint64_t(sequence); renderer->revision = uint64_t(revision);
        renderer->session->Enqueue([sequence, revision](auto& app) {
            RtrDiagnostics::Context(uint64_t(sequence),uint64_t(revision));
            app.Renderer().lighting.sequence = uint64_t(sequence);
            app.Renderer().lighting.sceneRevision = uint64_t(revision);
        });
    } catch (const std::exception& error) { Throw(env, error); }
}

extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_diagnosticLevel(JNIEnv* env,jclass,jlong handle,jint level){
    try {Get(handle);RtrDiagnostics::Configure(unsigned(level));}
    catch(const std::exception& error){Throw(env,error);}
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_boundaryDiagnostics(JNIEnv* env,jclass,jlong handle,jlong generation,jlong next){
    try {
        Get(handle);
        if(generation<0||next<1)throw std::invalid_argument("Invalid boundary diagnostic cursor");
        const auto snapshot=RtrDiagnostics::Snapshot(uint64_t(generation),UINT64_MAX,uint64_t(next),UINT64_MAX);
        return LongArray(env,std::vector<jlong>(snapshot.begin(),snapshot.end()));
    }catch(const std::exception& error){Throw(env,error);return nullptr;}
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_meshResourceState(JNIEnv* env,jclass,jlong handle,jstring name){
    try{
        auto renderer=Get(handle);auto id=String(env,name);std::array<double,2> state{};
        renderer->session->Invoke([&](auto& app){state=app.Renderer().MeshResourceState(id);});
        auto result=env->NewDoubleArray(2);if(result)env->SetDoubleArrayRegion(result,0,2,state.data());return result;
    }catch(const std::exception& error){Throw(env,error);return nullptr;}
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_resourceWorkState(JNIEnv* env,jclass,jlong handle) {
    try {
        std::array<uint64_t,9> state{};
        Get(handle)->session->Invoke([&](auto& app){state=app.Renderer().ResourceWorkState();});
        return LongArray(env,std::vector<jlong>(state.begin(),state.end()));
    }catch(const std::exception& error){Throw(env,error);return nullptr;}
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_commandWorkState(JNIEnv* env,jclass,jlong handle) {
    try {
        std::array<uint64_t,10> state{};
        Get(handle)->session->Invoke([&](auto& app){state=app.Renderer().CommandWorkState();});
        return LongArray(env,std::vector<jlong>(state.begin(),state.end()));
    }catch(const std::exception& error){Throw(env,error);return nullptr;}
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_lightingMetadataState(
        JNIEnv* env,jclass,jlong handle,jstring instanceName,jstring geometryName) {
    try {
        const auto instance=String(env,instanceName),geometry=String(env,geometryName);
        std::array<double,17> state{};
        Get(handle)->session->Invoke([&](auto& app) {
            const auto& pass=app.Renderer().lighting;
            const auto slot=pass.instanceSlots.find(instance);
            const auto mapping=pass.geometryIndices.find(geometry);
            const auto attributes=pass.geometrySlots.find(geometry);
            if(slot==pass.instanceSlots.end()||mapping==pass.geometryIndices.end()
                ||attributes==pass.geometrySlots.end())
                throw std::invalid_argument("No published lighting metadata for the requested identities");
            const auto& value=attributes->second;
            state[0]=slot->second;state[1]=mapping->second.index;
            state[2]=value.offset;state[3]=value.triangles;
            for(size_t i=0;i<4;++i)state[4+i]=value.texturePolicies[i];
            state[8]=value.maskedOpacity;
            const auto found=pass.instanceAttributes.find(instance);
            const auto roles=found==pass.instanceAttributes.end()
                ?RtrNativeLightingPass::InstanceAttributes{}:found->second;
            for(size_t i=0;i<4;++i){state[9+i]=roles.rolesVisibility[i];state[13+i]=roles.baseline[i];}
        });
        auto result=env->NewDoubleArray(static_cast<jsize>(state.size()));
        if(result)env->SetDoubleArrayRegion(result,0,static_cast<jsize>(state.size()),state.data());
        return result;
    }catch(const std::exception& error){Throw(env,error);return nullptr;}
}

extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_resourcesMemory(JNIEnv* env, jclass, jlong handle, jlong, jobject packet) {
    try {
        if (!packet) throw std::invalid_argument("Null resource packet");
        const auto size = env->GetDirectBufferCapacity(packet);
        auto* bytes = static_cast<const uint8_t*>(env->GetDirectBufferAddress(packet));
        if (!bytes || size <= 0 || size > 512ll * 1024 * 1024)
            throw std::invalid_argument("Resource transport must be a bounded direct ByteBuffer");
        std::vector<uint8_t> owned(bytes, bytes + size);
        Get(handle)->session->Enqueue([owned = std::move(owned)](auto& app) { app.Renderer().ApplyResources(owned); });
    } catch (const std::exception& error) { Throw(env, error); }
}

extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLighting(JNIEnv* env, jclass, jlong handle, jfloatArray parameters) {
    try { auto values = Floats(env, parameters);
        Get(handle)->session->Enqueue([values = std::move(values)](auto& app) { app.Renderer().ConfigureLighting(values); });
    } catch (const std::exception& error) { Throw(env, error); }
}

extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingAttributes(JNIEnv* env, jclass, jlong handle, jlong generation,
    jobjectArray names, jfloatArray attributes, jfloatArray lookup, jbyteArray application) {
    try {
        auto keys = Strings(env, names); auto values = Floats(env, attributes);
        if (generation < 0 || values.size() != keys.size()*8) throw std::invalid_argument("Invalid instance attribute publication");
        auto table = lookup ? Floats(env, lookup) : std::vector<float>();
        if (lookup && table.size()!=512 && table.size()!=1280) throw std::invalid_argument("Invalid host response lookup size");
        for (auto value : table) if (value<0 || value>1) throw std::invalid_argument("Host response outside [0,1]");
        auto bytes = application ? Bytes(env, application) : std::vector<uint8_t>();
        Get(handle)->session->Enqueue([keys=std::move(keys), values=std::move(values), table=std::move(table), bytes=std::move(bytes), generation,
                hasLookup=lookup!=nullptr, hasApplication=application!=nullptr](auto& app) {
            auto& renderer=app.Renderer(); auto& pass=renderer.lighting;
            if (uint64_t(generation)<pass.publication) throw std::invalid_argument("Stale attribute publication");
            std::unordered_set<std::string> seen;
            for(size_t i=0;i<keys.size();++i) {
                if(!seen.insert(keys[i]).second || !renderer.HasInstance(keys[i])) throw std::invalid_argument("Unknown or duplicate instance attribute");
                const auto* words=values.data()+i*8;
                if(words[0]<0 || words[0]>31 || words[0]!=float(uint32_t(words[0])) || words[1]<0 || words[1]>1 || words[2]<0 || words[2]>1)
                    throw std::invalid_argument("Invalid role or visibility attribute");
            }
            for(size_t i=0;i<keys.size();++i) {
                RtrNativeLightingPass::InstanceAttributes value; std::memcpy(&value,values.data()+i*8,sizeof(value));
                pass.instanceAttributes.insert_or_assign(keys[i],value); pass.dirtyInstances.insert(keys[i]); pass.TrackInstanceRole(keys[i],value);
            }
            if(hasLookup) { std::memcpy(pass.baselineLookup.data(),table.data(),table.size()*sizeof(float)); pass.lookupDirty=true; }
            if(hasApplication) { pass.applicationData=bytes; pass.applicationDirty=true; }
            pass.publication=uint64_t(generation);
        });
    } catch(const std::exception& error) { Throw(env,error); }
}

extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingAttributeBatch(JNIEnv* env, jclass, jlong handle, jlong revision,
    jobjectArray names, jobjectArray baselines, jobjectArray colors) {
    try {
        auto keys=Strings(env,names);
        if(revision<0 || !baselines || !colors || env->GetArrayLength(baselines)!=keys.size() || env->GetArrayLength(colors)!=keys.size())
            throw std::invalid_argument("Invalid geometry attribute batch");
        struct Record {std::string key;std::vector<float2> baseline;std::vector<uint32_t> colors;bool hasBaseline=false,hasColors=false;};
        std::vector<Record> records;std::unordered_set<std::string> seen;
        for(jsize i=0;i<jsize(keys.size());++i) {
            Record record;record.key=keys[i];if(!seen.insert(record.key).second)throw std::invalid_argument("Duplicate attribute geometry");
            auto b=static_cast<jfloatArray>(env->GetObjectArrayElement(baselines,i));
            auto c=static_cast<jintArray>(env->GetObjectArrayElement(colors,i));
            if(b){auto values=Floats(env,b);if(values.size()%6)throw std::invalid_argument("Invalid baseline triangle layout");
                for(auto value:values)if(value<0||value>1)throw std::invalid_argument("Baseline outside [0,1]");
                record.baseline.resize(values.size()/2);std::memcpy(record.baseline.data(),values.data(),values.size()*4);record.hasBaseline=true;env->DeleteLocalRef(b);}
            if(c){const auto count=env->GetArrayLength(c);if(count%3)throw std::invalid_argument("Invalid color triangle layout");record.colors.resize(count);
                env->GetIntArrayRegion(c,0,count,reinterpret_cast<jint*>(record.colors.data()));record.hasColors=true;env->DeleteLocalRef(c);}
            if(env->ExceptionCheck())throw std::runtime_error("Cannot copy geometry attributes");records.push_back(std::move(record));
        }
        Get(handle)->session->Enqueue([records=std::move(records)](auto& app) {
            auto& renderer=app.Renderer();auto& pass=renderer.lighting;
            for(const auto& record:records){const auto corners=renderer.GeometryCorners(record.key);
                if((record.hasBaseline&&record.baseline.size()!=corners)||(record.hasColors&&record.colors.size()!=corners))
                    throw std::invalid_argument("Geometry attribute layout mismatch");}
            for(const auto& record:records){
                if(record.hasBaseline){pass.meshBaselines[record.key]=record.baseline;pass.dirtyBaselines.insert(record.key);}
                if(record.hasColors){pass.meshColors[record.key]=record.colors;pass.dirtyColors.insert(record.key);
                    if(std::any_of(record.colors.begin(),record.colors.end(),[](auto color){return (color>>24)!=255;}))pass.alphaMeshes.insert(record.key);else pass.alphaMeshes.erase(record.key);}
            }
        });
    } catch(const std::exception& error) { Throw(env,error); }
}

extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingApplicationPatch(JNIEnv* env,jclass,jlong handle,jlong generation,jint offset,jbyteArray bytes) {
    try {if(generation<0||offset<0)throw std::invalid_argument("Invalid application patch identity");auto values=Bytes(env,bytes);
        Get(handle)->session->Enqueue([generation,offset,values=std::move(values)](auto& app){app.Renderer().lighting.PatchApplication(uint64_t(generation),size_t(offset),values);});
    }catch(const std::exception& error){Throw(env,error);}
}
extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingClearAttributes(JNIEnv* env,jclass,jlong handle) {
    try {Get(handle)->session->Enqueue([](auto& app){auto& pass=app.Renderer().lighting;
        pass.instanceAttributes.clear();pass.meshBaselines.clear();pass.meshColors.clear();pass.alphaMeshes.clear();
        pass.dirtyInstances.clear();pass.dirtyBaselines.clear();pass.dirtyColors.clear();pass.specialInstances.clear();pass.specialRoleCount=0;
        pass.metadataDirty=true;pass.metadataInitialized=false;
    });}catch(const std::exception& error){Throw(env,error);}
}
extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingEncoding(JNIEnv* env,jclass,jlong handle,jint encoding) {
    try {if(encoding<0||encoding>2)throw std::invalid_argument("Unknown lighting encoding");
        Get(handle)->session->Enqueue([encoding](auto& app){auto& pass=app.Renderer().lighting;pass.compactEncoding=encoding!=0;pass.compactResponseEncoding=encoding==2;});
    }catch(const std::exception& error){Throw(env,error);}
}
extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingReconstruction(JNIEnv* env,jclass,jlong handle,jboolean enabled,jboolean reset) {
    try {Get(handle)->session->Enqueue([enabled,reset](auto& app){auto& rr=app.Renderer().lighting.reconstruction;
        if(rr.requested!=(enabled!=0)||reset){rr.reset=true;++rr.resets;}rr.requested=enabled!=0;
    });}catch(const std::exception& error){Throw(env,error);}
}
extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingReconstructionScale(JNIEnv* env,jclass,jlong handle,jint divisor) {
    try {if(divisor!=1&&divisor!=2)throw std::invalid_argument("GI RR divisor must be 1 or 2");
        Get(handle)->session->Enqueue([divisor](auto& app){auto& rr=app.Renderer().lighting.reconstruction;if(rr.divisor!=divisor){rr.divisor=divisor;rr.reset=true;++rr.resets;}});
    }catch(const std::exception& error){Throw(env,error);}
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingTelemetry(JNIEnv* env,jclass,jlong handle) {
    try {std::vector<jlong> values;Get(handle)->session->Invoke([&](auto& app){const auto& pass=app.Renderer().lighting;
        values={pass.active?1:0,pass.width,pass.height,pass.mask,jlong(pass.executedFrames),jlong(pass.beautyFrames),jlong(pass.metadataUploads),jlong(pass.publication)};
    });return LongArray(env,values);}catch(const std::exception& error){Throw(env,error);return nullptr;}
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingReconstructionState(JNIEnv* env,jclass,jlong handle) {
    try {std::vector<jlong> values;Get(handle)->session->Invoke([&](auto& app){const auto& rr=app.Renderer().lighting.reconstruction;
        values={rr.requested?1:0,rr.requested&&rr.valid?1:0,rr.width,rr.height,jlong(rr.evaluated),jlong(rr.resets),rr.valid?1:0};
    });return LongArray(env,values);}catch(const std::exception& error){Throw(env,error);return nullptr;}
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_sparseLightingTelemetry(JNIEnv* env,jclass,jlong handle) {
    try {std::vector<jlong> values;Get(handle)->session->Invoke([&](auto& app){const auto& work=app.Renderer().sparse.work;values.assign(work.begin(),work.end());});
        return LongArray(env,values);}catch(const std::exception& error){Throw(env,error);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_resetHistory(JNIEnv* env,jclass,jlong handle) {
    try {Get(handle)->session->Enqueue([](auto& app){app.Renderer().ResetHistory();});}catch(const std::exception& error){Throw(env,error);}
}
extern "C" JNIEXPORT jlong JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_window(JNIEnv* env,jclass,jlong handle) {
    try {jlong result=0;Get(handle)->session->Invoke([&](auto& app){result=jlong(reinterpret_cast<uintptr_t>(glfwGetWin32Window(app.DeviceManager().GetWindow())));});return result;
    }catch(const std::exception& error){Throw(env,error);return 0;}
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_windowState(JNIEnv* env,jclass,jlong handle) {
    try {
        std::array<int64_t,12> state{};
        Get(handle)->session->Invoke([&](auto& app){state=app.WindowState();});
        auto result=env->NewLongArray(static_cast<jsize>(state.size()));
        if(result)env->SetLongArrayRegion(result,0,static_cast<jsize>(state.size()),state.data());
        return result;
    } catch(const std::exception& error){Throw(env,error);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_title(JNIEnv* env,jclass,jlong handle,jstring title) {
    try {auto text=String(env,title);Get(handle)->session->Invoke([text=std::move(text)](auto& app){app.Renderer().SetConsumerTitle(text);});}
    catch(const std::exception& error){Throw(env,error);}
}
extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingClock(JNIEnv* env, jclass, jlong handle, jlong time, jlong delta) {
    try { if (delta < 0) throw std::invalid_argument("Invalid lighting clock");
        Get(handle)->session->Enqueue([time, delta](auto& app) { auto& pass = app.Renderer().lighting; pass.timeNanos = time; pass.deltaNanos = delta; });
    } catch (const std::exception& error) { Throw(env, error); }
}
extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_sparseLighting(JNIEnv* env, jclass, jlong handle, jlongArray ids, jfloatArray records, jlongArray removals, jboolean reset) {
    try { auto changed = Longs(env, ids); auto values = Floats(env, records); auto removed = Longs(env, removals);
        Get(handle)->session->Enqueue([changed = std::move(changed), values = std::move(values), removed = std::move(removed), reset](auto& app) {
            app.Renderer().sparse.Apply(changed, values, removed, reset != 0);
        });
    } catch (const std::exception& error) { Throw(env, error); }
}

extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_lightingPatch(JNIEnv* env,jclass,jlong handle,jlongArray ids,jfloatArray records,
    jlongArray removals,jfloatArray sun,jfloatArray sky) {
    try {auto changed=Longs(env,ids);auto values=Floats(env,records);auto removed=Longs(env,removals);auto daylight=Floats(env,sun);auto background=Floats(env,sky);
        Get(handle)->session->Enqueue([changed=std::move(changed),values=std::move(values),removed=std::move(removed),daylight=std::move(daylight),background=std::move(background)](auto& app){
            app.Renderer().ApplyLights(changed,values,removed,daylight,background);
        });
    }catch(const std::exception& error){Throw(env,error);}
}
extern "C" JNIEXPORT jint JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_frame(JNIEnv* env, jclass, jlong handle) {
    try { auto renderer = Get(handle);
        RtrDiagnostics::Scope frameScope(RtrDiagnostics::Frame,0,0,1,0,0,0,0,0,renderer->sequence,renderer->revision);
        const bool completed = renderer->session->Frame([&](auto& app) {
            auto& pass = app.Renderer().lighting;
            if (pass.active) renderer->signals.Publish(app.DeviceManager().GetDevice(), pass.Outputs(),
                renderer->sequence, renderer->revision, pass.publication, pass.timeNanos, pass.deltaNanos,
                pass.compactEncoding ? (pass.compactResponseEncoding ? 2 : 1) : 0, pass.maximumGi());
        });
        return completed ? 1 : 0;
    } catch (const std::exception& error) { Throw(env, error); return 0; }
}
extern "C" JNIEXPORT jboolean JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingCanPublish(JNIEnv* env, jclass, jlong handle) {
    try { auto renderer = Get(handle); bool result = false; renderer->session->Invoke([&](auto&) { result = renderer->signals.CanPublish(); }); return result;
    } catch (const std::exception& error) { Throw(env, error); return false; }
}
extern "C" JNIEXPORT jboolean JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingConsumersRetired(JNIEnv* env, jclass, jlong handle) {
    try { auto renderer = Get(handle); bool result = false;
        try {renderer->session->Invoke([&](auto&) { result = renderer->signals.ConsumersRetired(); });}
        catch (...) {
            if(!renderer->session->Stopped())throw;
            // No owner can mutate the ring after Run has finished. Preserve the
            // external completion fence contract even when rendering failed.
            result=renderer->signals.ConsumersRetired();
        }
        return result;
    } catch (const std::exception& error) { Throw(env, error); return false; }
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingFrame(JNIEnv* env, jclass, jlong handle) {
    try { auto renderer = Get(handle); std::vector<jlong> result; renderer->session->Invoke([&](auto&) { result = renderer->signals.Acquire(); }); return LongArray(env, result);
    } catch (const std::exception& error) { Throw(env, error); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingRelease(JNIEnv* env, jclass, jlong handle, jint slot, jlong generation, jlong sequence, jboolean imported) {
    try { auto renderer = Get(handle); renderer->session->Invoke([&](auto& app) { renderer->signals.Release(app.DeviceManager().GetDevice(), slot, uint64_t(generation), uint64_t(sequence), imported != 0); });
    } catch (const std::exception& error) { Throw(env, error); }
}
extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_presentation(JNIEnv* env,jclass,jlong handle,
    jint maxBounces,jint diffuseBounces,jboolean reflex,jint generatedFrames) {
    try {
        if(maxBounces<1||maxBounces>64||diffuseBounces<0||diffuseBounces>maxBounces
                ||generatedFrames<0||generatedFrames>3)throw std::invalid_argument("Invalid presentation configuration");
        Get(handle)->session->Enqueue([maxBounces,diffuseBounces,reflex,generatedFrames](auto& app){
            app.Renderer().ConfigurePresentation(maxBounces,diffuseBounces,reflex!=0,generatedFrames);
        });
    } catch(const std::exception& error){Throw(env,error);}
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_presentationState(JNIEnv* env,jclass,jlong handle) {
    try {std::vector<jlong> result;Get(handle)->session->Invoke([&](auto& app){
        const auto state=app.Renderer().PresentationState();result.assign(state.begin(),state.end());
    });return LongArray(env,result);}catch(const std::exception& error){Throw(env,error);return nullptr;}
}
extern "C" JNIEXPORT jstring JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_frameStatistics(JNIEnv* env,jclass,jlong handle) {
    try {std::string value;Get(handle)->session->Invoke([&](auto& app){value=app.Renderer().GetFPSInfo();});
        return env->NewStringUTF(value.c_str());}
    catch(const std::exception& error){Throw(env,error);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_exposure(JNIEnv* env,jclass,jlong handle,jfloat compensation) {
    try {
        if(!std::isfinite(compensation)||compensation < -12.f||compensation > 12.f)
            throw std::invalid_argument("Exposure compensation must be finite and in [-12,12]");
        Get(handle)->session->Enqueue([compensation](auto& app){app.Renderer().ConfigureExposure(compensation);});
    }catch(const std::exception& error){Throw(env,error);}
}
extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_overlayText(JNIEnv* env,jclass,jlong handle,jstring text) {
    try {auto value=String(env,text);Get(handle)->session->Invoke([value=std::move(value)](auto& app){app.Renderer().SetOverlayText(value);});}
    catch(const std::exception& error){Throw(env,error);}
}
extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_overlayChoices(JNIEnv* env,jclass,jlong handle,jobjectArray labels,jint selected) {
    try {
        if(!labels)throw std::invalid_argument("Overlay labels must not be null");
        auto values=Strings(env,labels);
        if(values.size()>64||(values.empty()?selected!=-1:selected<0||size_t(selected)>=values.size()))
            throw std::invalid_argument("Invalid overlay selection");
        Get(handle)->session->Invoke([values=std::move(values),selected](auto& app){app.Renderer().SetOverlayChoices(values,selected);});
    }catch(const std::exception& error){Throw(env,error);}
}
extern "C" JNIEXPORT jint JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_overlaySelection(JNIEnv* env,jclass,jlong handle) {
    try {int result=-1;Get(handle)->session->Invoke([&](auto& app){result=app.Renderer().OverlaySelection();});return result;}
    catch(const std::exception& error){Throw(env,error);return -1;}
}
extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_denoiser(JNIEnv* env,jclass,jlong handle,jboolean relax) {
    try {Get(handle)->session->Enqueue([relax](auto& app){app.Renderer().ConfigureDenoiser(relax!=0);});}
    catch(const std::exception& error){Throw(env,error);}
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_renderingState(JNIEnv* env,jclass,jlong handle) {
    try {std::vector<jlong> result;Get(handle)->session->Invoke([&](auto& app){
        const auto state=app.Renderer().RenderingState();result.assign(state.begin(),state.end());
    });return LongArray(env,result);}catch(const std::exception& error){Throw(env,error);return nullptr;}
}
extern "C" JNIEXPORT jint JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_denoiserState(JNIEnv* env,jclass,jlong handle) {
    try {int result=0;Get(handle)->session->Invoke([&](auto& app){result=app.Renderer().DenoiserState();});return result;}
    catch(const std::exception& error){Throw(env,error);return -1;}
}
extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_quality(JNIEnv* env,jclass,jlong handle,
    jint sampler,jint samples,jint candidates,jint adaptiveQuality,jfloat localRatio,
    jint layers,jint maxDepth,jint nestedQuality,jboolean halfPrecision,jfloat lodBias,
    jboolean bloom,jfloat bloomRadius,jfloat bloomIntensity,jboolean toneMapping,jint beautyTransport){
    try {
        const rtr::upstream::PathTracer::Quality quality{sampler,samples,candidates,adaptiveQuality,localRatio,
            layers,maxDepth,nestedQuality,halfPrecision!=0,lodBias,bloom!=0,bloomRadius,bloomIntensity,toneMapping!=0,beautyTransport};
        quality.Validate();
        Get(handle)->session->Enqueue([quality](auto& app){app.Renderer().ConfigureQuality(quality);});
    }catch(const std::exception& error){Throw(env,error);}
}
extern "C" JNIEXPORT jfloatArray JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_qualityState(JNIEnv* env,jclass,jlong handle){
    try {std::array<float,14> state{};Get(handle)->session->Invoke([&](auto& app){state=app.Renderer().QualityState();});
        return FloatArray(env,state);
    }catch(const std::exception& error){Throw(env,error);return nullptr;}
}
extern "C" JNIEXPORT jfloatArray JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_exposureState(JNIEnv* env,jclass,jlong handle){
    try {std::array<float,3> state{};Get(handle)->session->Invoke([&](auto& app){state=app.Renderer().ExposureState();});
        return FloatArray(env,state);
    }catch(const std::exception& error){Throw(env,error);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_camera(JNIEnv* env, jclass, jlong handle, jstring pose, jfloat fov) {
    try { const auto value = String(env, pose); if (!std::isfinite(fov) || fov <= 0 || fov >= 3.14159265f) throw std::invalid_argument("Invalid vertical field of view");
        Get(handle)->session->Enqueue([value, fov](auto& app) { auto& renderer = app.Renderer(); if (!renderer.SetCurrentCameraPosDirUp(value)) throw std::invalid_argument("Invalid original camera pose"); renderer.SetCameraVerticalFOV(fov); });
    } catch (const std::exception& error) { Throw(env, error); }
}
extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_loadScene(JNIEnv* env, jclass, jlong handle, jstring path) {
    try { auto value = path ? String(env, path) : std::string(); Get(handle)->session->Enqueue([value](auto& app) {
        if (value.empty()) app.Renderer().ClearResources(); else app.Renderer().SetCurrentScene(value, true);
    }); } catch (const std::exception& error) { Throw(env, error); }
}
extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_resize(JNIEnv* env, jclass, jlong handle, jint width, jint height) {
    try { if (width < 1 || height < 1 || width > 16384 || height > 16384) throw std::invalid_argument("Invalid render extent");
        Get(handle)->session->Enqueue([width, height](auto& app) { glfwSetWindowSize(app.DeviceManager().GetWindow(), width, height); });
    } catch (const std::exception& error) { Throw(env, error); }
}
extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_awaitIdle(JNIEnv* env, jclass, jlong handle) {
    try { Get(handle)->session->Invoke([](auto& app) { app.DeviceManager().GetDevice()->waitForIdle(); }); }
    catch (const std::exception& error) { Throw(env, error); }
}
extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_capture(JNIEnv* env, jclass, jlong handle, jstring path) {
    try { const auto value = String(env, path); Get(handle)->session->Invoke([value](auto& app) {
        auto texture=app.Renderer().WindowOutput();
        if(!texture)throw std::logic_error("No completed final window frame is available");
        if (!donut::engine::SaveTextureToFile(app.DeviceManager().GetDevice(),app.Renderer().GetCommonPasses().get(),
            texture,nvrhi::ResourceStates::Present,value.c_str()))
            throw std::runtime_error("Original Donut final window capture failed");
    }); } catch (const std::exception& error) { Throw(env, error); }
}
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_instanceTransformState(JNIEnv* env,jclass,jlong handle,jstring name) {
    try {
        auto value=String(env,name);std::array<double,24> state{};
        Get(handle)->session->Invoke([&](auto& app){state=app.Renderer().InstanceTransformState(value);});
        auto result=env->NewDoubleArray(static_cast<jsize>(state.size()));
        if(result)env->SetDoubleArrayRegion(result,0,static_cast<jsize>(state.size()),state.data());
        return result;
    }catch(const std::exception& error){Throw(env,error);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_nativeLightingCapture(JNIEnv* env,jclass,jlong handle,jstring path,jint signal) {
    try {const auto filename=String(env,path);if(signal<0||signal>22)throw std::invalid_argument("Unknown lighting capture signal");
        Get(handle)->session->Invoke([filename,signal](auto& app){
            auto& pass=app.Renderer().lighting;auto& rr=pass.reconstruction;
            auto source=signal==0?pass.direct:signal==1?(rr.requested&&rr.valid?rr.result:pass.indirect):pass.geometry;
            if(signal>=3&&signal<=8)source=rr.guides[signal-3];
            if(signal==9||signal==15)source=pass.indirect;
            if(signal==10)source=rr.reducedRaw;
            if(signal==11)source=rr.reducedGuides[0];if(signal==12)source=rr.reducedGuides[2];
            if(signal==13)source=rr.reducedGuides[3];if(signal==14)source=rr.reducedGuides[4];
            if(signal==16)source=rr.result;if(signal==17)source=rr.reducedGuides[1];if(signal==18)source=rr.reducedGuides[5];
            // Original beauty execution signals; diagnostic readback only.
            if(signal==19)source=app.Renderer().Output(rtr::upstream::Signal::MotionVectors);
            if(signal==20)source=app.Renderer().Output(rtr::upstream::Signal::Depth);
            if(signal==21)source=app.Renderer().Output(rtr::upstream::Signal::RawRadiance);
            if(signal==22)source=app.Renderer().Output(rtr::upstream::Signal::LinearColor);
            if(!source)throw std::logic_error("The requested lighting signal is not allocated");
            const auto desc=source->getDesc();
            const unsigned channels=desc.format==nvrhi::Format::RG16_FLOAT?2:desc.format==nvrhi::Format::R32_FLOAT?1:4;
            const bool float32=desc.format==nvrhi::Format::RGBA32_FLOAT||desc.format==nvrhi::Format::R32_FLOAT;
            if(!float32&&desc.format!=nvrhi::Format::RGBA16_FLOAT&&desc.format!=nvrhi::Format::RG16_FLOAT)
                throw std::logic_error("PFM capture requires an actual floating signal");
            auto* device=app.DeviceManager().GetDevice();auto staging=device->createStagingTexture(desc,nvrhi::CpuAccessMode::Read);
            auto commands=device->createCommandList();commands->open();commands->copyTexture(staging,nvrhi::TextureSlice(),source,nvrhi::TextureSlice());
            commands->setTextureState(source,nvrhi::AllSubresources,nvrhi::ResourceStates::UnorderedAccess);commands->close();device->executeCommandList(commands);
            size_t pitch=0;auto* data=static_cast<const uint8_t*>(device->mapStagingTexture(staging,nvrhi::TextureSlice(),nvrhi::CpuAccessMode::Read,&pitch));
            if(!data)throw std::runtime_error("Lighting capture readback failed");
            struct Unmap{nvrhi::IDevice* device;nvrhi::IStagingTexture* texture;~Unmap(){device->unmapStagingTexture(texture);}} unmap{device,staging};
            std::ofstream output(std::filesystem::u8path(filename),std::ios::binary);output<<"PF\n"<<desc.width<<" "<<desc.height<<"\n-1.0\n";
            for(int y=int(desc.height)-1;y>=0;--y)for(uint32_t x=0;x<desc.width;++x)for(unsigned c=0;c<3;++c){
                const auto component=(signal==15||signal==16)?3:std::min(c,channels-1);float value;
                if(float32)std::memcpy(&value,data+size_t(y)*pitch+(x*channels+component)*4,4);
                else {uint16_t half;std::memcpy(&half,data+size_t(y)*pitch+(x*channels+component)*2,2);const auto exponent=(half>>10)&31,mantissa=half&1023;
                    value=exponent==31?(mantissa?NAN:INFINITY):std::ldexp(float(exponent?mantissa+1024:mantissa),exponent?exponent-25:-24);if(half&0x8000)value=-value;}
                output.write(reinterpret_cast<const char*>(&value),4);
            }
            if(!output)throw std::runtime_error("Could not write lighting capture");
        });
    }catch(const std::exception& error){Throw(env,error);}
}
extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_rtxpt_RtxptNative_close(JNIEnv* env, jclass, jlong handle) {
    try {
        auto renderer = Get(handle); bool stoppedNormally = false;
        try {
            renderer->session->Invoke([&](auto& app) {
                renderer->signals.RequireRetired(); app.DeviceManager().GetDevice()->waitForIdle(); renderer->signals.Clear();
            });
        } catch (...) {
            if (!renderer->session->Stopped()) throw;
            renderer->signals.RequireRetired();
            stoppedNormally = true;
        }
        renderer->session->Close();
        // The ordinary window loop already ended and destroyed its application.
        // Join before inspecting the remaining leases; never bypass retirement.
        if (stoppedNormally) renderer->signals.Clear();
        std::lock_guard lock(rendererMutex); renderers.erase(handle);
    } catch (const std::exception& error) { Throw(env, error); }
}
