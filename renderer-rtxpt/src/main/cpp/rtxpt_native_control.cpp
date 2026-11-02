// Acceptance executable: upstream RTXPT in a native process, without JVM/JNI.
// Only supplies the same window policy, settings and timing boundaries as Java.
#include <chrono>
#include <cstdio>
#include "StreamlineIntegration.h"
#define WinMain RtrUnusedSampleEntryPoint
#include RTR_RTXPT_ADVANCED_SOURCE
#undef WinMain
#include "rtxpt_shutdown.h"
#include "rtxpt_memory_diagnostics.h"
extern SampleUIData g_sampleUIData;

class NativeControlApp final : public AdvancedSample {
    void ConfigureDefaultDeviceParams(donut::app::DeviceCreationParameters& p) const override { p.vsyncEnabled=false; }
};
int main(int argc, const char** argv) {
    donut::log::ConsoleApplicationMode();
    auto previousLog=donut::log::GetCallback();
    try {
        bool resourceLifecycle=false;
        std::vector<const char*> arguments;
        for(int i=0;i<argc;++i) {
            if(std::string_view(argv[i])=="--resource-lifecycle-selftest")resourceLifecycle=true;
            else arguments.push_back(argv[i]);
        }
        auto app=std::make_unique<NativeControlApp>();
        if(app->Init(int(arguments.size()),arguments.data())!=SampleBaseApp::InitReturnCodes::Success)
            throw std::runtime_error("Native control initialization failed");
        auto* dm=app->HostDeviceManager();
        g_sampleUIData.ShowUI=false;
        g_sampleUIData.RenderWhenOutOfFocus=true;
        g_sampleUIData.EnableVsync=false;
        g_sampleUIData.FPSLimiter=0;
        g_sampleUIData.ReflexMode=donut::app::StreamlineInterface::ReflexMode::eLowLatency;
        g_sampleUIData.DLSSFGMode=donut::app::StreamlineInterface::DLSSGMode::eOff;
        g_sampleUIData.BounceCount=20;
        g_sampleUIData.DiffuseBounceCount=2;
        // Same fixture FOV as the Java camera; its pose is passed via upstream CLI.
        app->HostRenderPass()->SetCameraVerticalFOV(float(52.0*3.14159265358979323846/180.0));
        uint64_t presents=0;
        auto afterPresent=dm->m_callbacks.afterPresent;
        dm->m_callbacks.afterPresent=[&](auto& manager,uint32_t index) {
            if(afterPresent)afterPresent(manager,index);
            ++presents;
        };
        if(resourceLifecycle) {
            if(!RtrMemoryDiagnostics::Enabled())throw std::runtime_error("Resource lifecycle test requires opt-in memory census");
            auto bytes=[](std::string_view name) {
                std::lock_guard lock(RtrMemoryDiagnostics::mutex);
                uint64_t total=0;
                for(const auto& item:RtrMemoryDiagnostics::resources)
                    if(item.second.name==name)total+=item.second.allocated;
                return total;
            };
            auto frames=[&] {
                for(int i=0;i<6;++i)if(!dm->HostFrame())throw std::runtime_error("Resource lifecycle frame failed");
                dm->GetDevice()->waitForIdle();
                // Poll retirement after both queue fences have actually completed.
                for(int i=0;i<2;++i)if(!dm->HostFrame())throw std::runtime_error("Resource retirement frame failed");
                dm->GetDevice()->waitForIdle();
            };
            g_sampleUIData.RealtimeMode=true;
            g_sampleUIData.RadianceCacheMode=0;
            frames();
            if(bytes("SharcHashEntriesBuffer")||bytes("ShaderDebugBufferGPUCopy")||bytes("ShaderDebugBufferGPU")>=1024*1024)
                throw std::runtime_error("Disabled resources must start compact and unallocated");
            for(int cycle=0;cycle<2;++cycle) {
                g_sampleUIData.RadianceCacheMode=1;
                frames();
                auto* cache=app->HostRenderPass()->GetSharc();
                if(!cache||!cache->GetHashEntriesBuffer()||!bytes("SharcAccumulationBuffer")||!bytes("SharcResolvedBuffer"))
                    throw std::runtime_error("Enabled SHaRC must recreate all cache buffers");
                // The next re-enable must initialize a fresh cache even with training frozen.
                cache->HostSettings().EnableUpdate=false;
                g_sampleUIData.RadianceCacheMode=0;
                frames();
                if(cache->GetHashEntriesBuffer()||bytes("SharcHashEntriesBuffer")||bytes("SharcAccumulationBuffer")||bytes("SharcResolvedBuffer")||bytes("SharcPathStateBuffer"))
                    throw std::runtime_error("Disabled SHaRC must retire buffers after both queues complete");
            }
            g_sampleUIData.ShowDebugLines=true;
            frames();
            if(bytes("ShaderDebugBufferGPU")<50*1024*1024||bytes("ShaderDebugBufferGPUCopy")<50*1024*1024)
                throw std::runtime_error("Debug geometry must restore full GPU storage and output copy");
            g_sampleUIData.ShowDebugLines=false;
            frames();
            if(bytes("ShaderDebugBufferGPU")>=1024*1024||bytes("ShaderDebugBufferGPUCopy"))
                throw std::runtime_error("Disabling debug geometry must restore compact storage");
            std::printf("RTXPT_RESOURCE_LIFECYCLE PASS sharc=off/on/off/on/off debug=compact/full/compact\n");
        } else {
        for(int i=0;i<180;++i)if(!dm->HostFrame())throw std::runtime_error("Native warmup failed");
        dm->GetDevice()->waitForIdle();
        auto first=presents;
        auto begin=std::chrono::steady_clock::now();
        do { if(!dm->HostFrame())throw std::runtime_error("Native frame failed"); }
        while(std::chrono::steady_clock::now()-begin<std::chrono::seconds(4));
        dm->GetDevice()->waitForIdle();
        double seconds=std::chrono::duration<double>(std::chrono::steady_clock::now()-begin).count();
        auto frames=presents-first;
        auto size=app->HostRenderPass()->GetRenderSize();
        std::printf("RTXPT_PARITY native frames=%llu seconds=%.9f fps=%.6f ms=%.9f input=%ux%u aa=%d sharc=%d fg=%d\n",
            static_cast<unsigned long long>(frames),seconds,frames/seconds,1000*seconds/frames,size.x,size.y,
            g_sampleUIData.RealtimeAA,g_sampleUIData.ActualRadianceCacheMode(),int(g_sampleUIData.ActualDLSSFGMode()));
        if(g_sampleUIData.RealtimeAA!=3 || g_sampleUIData.ActualDLSSFGMode()!=donut::app::StreamlineInterface::DLSSGMode::eOff)
            throw std::runtime_error("Native benchmark did not activate the requested configuration");
        }
        {
            ShutdownModuleReference retainCallbacks;
            auto& sl=donut::app::StreamlineIntegration::Get();sl.Shutdown();
            if(sl.HostShutdownResult()!=sl::Result::eOk)throw std::runtime_error("Native Streamline shutdown failed");
        }
        dm->m_callbacks={};app->End();donut::log::SetCallback(previousLog);app.reset();
        return 0;
    } catch(const std::exception& x) { std::fprintf(stderr,"RTXPT native control failed: %s\n",x.what());return 1; }
}
