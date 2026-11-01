param(
    [string]$SdkRoot='build/rtxpt-sdk',
    [string]$CMakeExecutable='build/native-build-tools/cmake-4.1.2-windows-x86_64/bin/cmake.exe',
    [string]$StreamlineSdk=$env:STREAMLINE_SDK,
    [double]$MemoryLimitGB=10.5,
    [switch]$ConfigureOnly
)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$repo=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$sdk=(Resolve-Path (Join-Path $repo $SdkRoot)).Path
$pin='303b408ab81790a5051abd7573830b7a4112eb8e'
if((& git -C $sdk rev-parse HEAD).Trim() -ne $pin){throw 'RTXPT source must match the pinned 1.9.0 commit'}
$utf8=[Text.UTF8Encoding]::new($false)
function Patch-Once([string]$Relative,[string]$Anchor,[string]$Replacement,[string]$Marker){
    $path=Join-Path $sdk $Relative
    $text=[IO.File]::ReadAllText($path)
    if($text.Contains($Marker)){return}
    if(!$text.Contains($Anchor)){throw "Pinned host integration anchor changed: $Relative"}
    [IO.File]::WriteAllText($path,$text.Replace($Anchor,$Replacement),$utf8)
}
# Bound nested shader compilers as well as the top-level MSBuild process.
Patch-Once 'External/Donut/compileshaders.cmake' 'set(compilerCommand ${SHADERMAKE_PATH}' 'set(compilerCommand ${SHADERMAKE_PATH} --jobs 1' '${SHADERMAKE_PATH} --jobs 1'
Patch-Once 'External/Nrd/CMakeLists.txt' 'set(SHADERMAKE_GENERAL_ARGS' "set(SHADERMAKE_GENERAL_ARGS --jobs 1" 'SHADERMAKE_GENERAL_ARGS --jobs 1'
# Only expose host control and runtime file roots. Rendering, scheduling and shaders stay upstream.
# Observe existing upstream GPU scopes without enabling Tracy or disabling async prefix.
Patch-Once 'Rtxpt/SampleCommon/GpuProfiler.cpp' '#include "GpuProfiler.h"' @'
#include "GpuProfiler.h"
#include "rtxpt_gpu_profile.h" // RTR sampled per-queue timestamp export
'@ 'RTR sampled per-queue timestamp export'
Patch-Once 'Rtxpt/SampleCommon/GpuProfiler.cpp' 'bool                    EmitMarker;' @'
bool                    EmitMarker;
        RtrGpuProfile::Token HostToken; // RTR sampled scope token
'@ 'RTR sampled scope token'
Patch-Once 'Rtxpt/SampleCommon/GpuProfiler.cpp' '        t_openZones.push_back(zone);' @'
        int hostParent=-1;
        for(auto it=t_openZones.rbegin();it!=t_openZones.rend();++it)
            if(it->HostToken.queue>=0 && it->CommandList->getDesc().queueType==commandList->getDesc().queueType) {
                hostParent=int(it->HostToken.zone); break;
            }
        zone.HostToken=RtrGpuProfile::Begin(commandList,name,hostParent); // RTR sampled scope begin
        t_openZones.push_back(zone);
'@ 'RTR sampled scope begin'
Patch-Once 'Rtxpt/SampleCommon/GpuProfiler.cpp' '    t_openZones.pop_back();' @'
    t_openZones.pop_back();
    RtrGpuProfile::End(commandList,zone.HostToken); // RTR sampled scope end
'@ 'RTR sampled scope end'
$profilerPath=Join-Path $sdk 'Rtxpt/SampleCommon/GpuProfiler.cpp'
$profilerText=[IO.File]::ReadAllText($profilerPath)
foreach($entry in @(@('Init(nvrhi::IDevice* device)','RtrGpuProfile::Init(device);'),@('Shutdown()','RtrGpuProfile::Shutdown();'),@('AfterPresent()','RtrGpuProfile::AfterPresent();'))){
    $tag='// RTR host '+$entry[0]
    if(!$profilerText.Contains($tag)){
        $pattern='(void GpuProfiler::'+[regex]::Escape($entry[0])+'\s*\{)'
        if(![regex]::IsMatch($profilerText,$pattern)){throw ('GPU profiler lifecycle anchor changed: '+$entry[0])}
        $profilerText=[regex]::Replace($profilerText,$pattern,('$1'+"`n    "+$entry[1]+' '+$tag),1)
    }
}
[IO.File]::WriteAllText($profilerPath,$profilerText,$utf8)
Patch-Once 'Rtxpt/SampleCommon/SampleBaseApp.cpp' 'm_CmdLine.InitFromCommandLine(__argc, __argv)' 'm_CmdLine.InitFromCommandLine(argc, argv)' 'm_CmdLine.InitFromCommandLine(argc, argv)'
Patch-Once 'Rtxpt/SampleCommon/SampleCommon.cpp' '    // progress bar currently interfering with fullscreen so disable' @'
    // RTR embedded host: compilation/loading progress belongs in the host log.
    if (HelpersIsNonInteractive()) {
        donut::log::info("%s", windowText);
        return -1;
    }
    // progress bar currently interfering with fullscreen so disable
'@ 'RTR embedded host: compilation/loading progress'
Patch-Once 'External/Donut/src/app/streamline/StreamlineIntegration.cpp' '#include <donut/core/log.h>' @'
#include <donut/core/log.h>
#include <donut/app/ApplicationBase.h>
'@ '#include <donut/app/ApplicationBase.h>'
Patch-Once 'External/Donut/src/app/streamline/StreamlineIntegration.cpp' 'auto basePath = std::filesystem::path(path).parent_path();' 'auto basePath = donut::app::GetDirectoryWithExecutable(); // RTR signed runtime root' 'RTR signed runtime root'
# RTXPT selects Streamline manual hooking. Upgrade the DXGI factory before any
# swapchain calls so Streamline can observe CreateSwapChainForHwnd and own Present.
$swapchainPath=Join-Path $sdk 'External/Donut/src/app/dx12/DeviceManager_DX12.cpp'
$swapchainText=[IO.File]::ReadAllText($swapchainPath)
if($swapchainText.Contains('RTR manual-hook present path')) {
    $swapchainText=[regex]::Replace($swapchainText,'(?m)^\s*#if DONUT_WITH_STREAMLINE\r?\n\s*if \(!StreamlineIntegration::UpgradeInterface\(pSwapChain1\)\) return false; // RTR manual-hook present path\r?\n\s*#endif\r?\n','')
    [IO.File]::WriteAllText($swapchainPath,$swapchainText,$utf8)
}
Patch-Once 'External/Donut/src/app/dx12/DeviceManager_DX12.cpp' 'HRESULT hres = CreateDXGIFactory2(m_DeviceParams.enableDebugRuntime ? DXGI_CREATE_FACTORY_DEBUG : 0, IID_PPV_ARGS(&m_DxgiFactory2));' @'
HRESULT hres = CreateDXGIFactory2(m_DeviceParams.enableDebugRuntime ? DXGI_CREATE_FACTORY_DEBUG : 0, IID_PPV_ARGS(&m_DxgiFactory2));
#if DONUT_WITH_STREAMLINE
        if (hres == S_OK && !StreamlineIntegration::UpgradeInterface(m_DxgiFactory2)) return false; // RTR manual-hook factory path
#endif
'@ 'RTR manual-hook factory path'
# The public API can request 3x/4x MFG on devices with a lower Streamline limit.
Patch-Once 'Rtxpt/SampleUI.h' 'bool                                ShowUI                                  = true;' @'
std::string                         HostOverlayText; // RTR GPU-native host HUD
    bool                                ShowUI                                  = true;
'@ 'RTR GPU-native host HUD'
Patch-Once 'Rtxpt/SampleUI.cpp' "void SampleUI::buildUI(void)`r`n{" @'
void SampleUI::buildUI(void)
{
    // RTR GPU-native host HUD: same upstream ImGui pass, independent of the settings panel.
    if (!m_ui.HostOverlayText.empty()) {
        ImGui::PushFont(m_defaultFont->GetScaledFont());
        ImGui::SetNextWindowPos(ImVec2(14.f, 14.f), ImGuiCond_Always);
        ImGui::SetNextWindowBgAlpha(0.69f);
        ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding, ImVec2(12.f, 8.f));
        ImGui::Begin("RTRenderer host HUD", nullptr, ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_AlwaysAutoResize
            | ImGuiWindowFlags_NoSavedSettings | ImGuiWindowFlags_NoInputs | ImGuiWindowFlags_NoFocusOnAppearing
            | ImGuiWindowFlags_NoNav);
        ImGui::TextUnformatted(m_ui.HostOverlayText.c_str());
        ImGui::End();
        ImGui::PopStyleVar();
        ImGui::PopFont();
    }
'@ 'RTR GPU-native host HUD:'
# The public API can request 3x/4x MFG on devices with a lower Streamline limit.
# Follow RTXPT's UI negotiation: keep native presentation when the requested count is unavailable.
Patch-Once 'Rtxpt/Sample.cpp' 'm_ui.DLSSFGMaxNumFramesToGenerate = state.numFramesToGenerateMax;' @'
m_ui.DLSSFGMaxNumFramesToGenerate = state.numFramesToGenerateMax;
            // RTR bounded DLSS frame-generation cadence
            if (dlssgOptions.mode != StreamlineInterface::DLSSGMode::eOff
                && dlssgOptions.numFramesToGenerate > state.numFramesToGenerateMax) {
                log::warning("Requested DLSS frame generation cadence is unavailable; keeping native presentation.");
                dlssgOptions.mode = StreamlineInterface::DLSSGMode::eOff;
                m_ui.DLSSFGMode = StreamlineInterface::DLSSGMode::eOff;
            }
'@ 'RTR bounded DLSS frame-generation cadence'
# The embedded host shuts SL down while its tagged render targets are still alive.
# The sample's later device teardown calls Shutdown again; it must not call unloaded plugins.
$slPath=Join-Path $sdk 'External/Donut/src/app/streamline/StreamlineIntegration.cpp'
$slText=[IO.File]::ReadAllText($slPath)
if(!$slText.Contains('RTR idempotent embedded teardown')){
    $slText=[regex]::Replace($slText,'void StreamlineIntegration::Shutdown\(\)\s*\{',
        "void StreamlineIntegration::Shutdown()`n{`n    if (!m_slInitialized) return; // RTR idempotent embedded teardown",1)
    if(!$slText.Contains('RTR idempotent embedded teardown')){throw 'Pinned Streamline shutdown anchor changed'}
    [IO.File]::WriteAllText($slPath,$slText,$utf8)
}
Patch-Once 'External/Donut/src/app/streamline/StreamlineIntegration.h' 'void Shutdown();' @'
void Shutdown();
    sl::Result HostShutdownResult() const { return m_hostShutdownResult; }
private:
    sl::Result m_hostShutdownResult = sl::Result::eOk;
public:
'@ 'HostShutdownResult()'
Patch-Once 'External/Donut/src/app/streamline/StreamlineIntegration.h' 'void Shutdown();' @'
void Shutdown();
    struct HostFrameGenerationTelemetry {
        uint64_t queries=0, samples=0, failures=0, presented=0;
        uint32_t lastPresented=0, status=0, maximumGenerated=0;
        bool querySucceeded=false;
    };
    HostFrameGenerationTelemetry hostFrameGeneration;
'@ 'HostFrameGenerationTelemetry'
Patch-Once 'External/Donut/src/app/streamline/StreamlineIntegration.cpp' 'successCheck(slDLSSGGetState(m_viewport, slState, &slOptions), "slDLSSGGetState");' @'
const auto hostQueryResult = slDLSSGGetState(m_viewport, slState, &slOptions);
    successCheck(hostQueryResult, "slDLSSGGetState");
    // RTR observes the existing query: this counter resets on every SDK GetState call.
    auto& host = hostFrameGeneration;
    ++host.queries;
    host.querySucceeded = hostQueryResult == sl::Result::eOk;
    if (host.querySucceeded) {
        host.status = static_cast<uint32_t>(slState.status);
        host.lastPresented = slState.numFramesActuallyPresented;
        host.maximumGenerated = slState.numFramesToGenerateMax;
        if (host.lastPresented) { ++host.samples; host.presented += host.lastPresented; }
    } else { ++host.failures; }
'@ 'RTR observes the existing query'
Patch-Once 'External/Donut/src/app/streamline/StreamlineIntegration.cpp' 'successCheck(slShutdown(), "slShutdown");' @'
m_hostShutdownResult = slShutdown(); // RTR observable embedded shutdown
        successCheck(m_hostShutdownResult, "slShutdown");
        log::info("RTXPT Streamline shutdown result=%d", int(m_hostShutdownResult));
'@ 'RTR observable embedded shutdown'
Patch-Once 'Rtxpt/Sample.cpp' 'if (available.empty())' 'if (std::filesystem::path(preferred).is_absolute() && std::filesystem::is_regular_file(preferred)) return preferred; // RTR absolute host scene
    if (available.empty())' 'RTR absolute host scene'
Patch-Once 'Rtxpt/SampleCommon/SampleBaseApp.h' 'private:' @'
public:
    donut::app::DeviceManager* HostDeviceManager() { return m_DeviceManager.get(); }
    Sample* HostRenderPass() { return m_MainSceneRender.get(); }
private:
'@ 'HostDeviceManager'
Patch-Once 'External/Donut/include/donut/app/DeviceManager.h' 'void RunMessageLoop();' @'
void RunMessageLoop();
        bool HostFrame();
'@ 'bool HostFrame()'
Patch-Once 'External/Donut/src/app/DeviceManager.cpp' 'void DeviceManager::RunMessageLoop()' @'
bool DeviceManager::HostFrame() {
            if (m_PreviousFrameTimestamp == 0) m_PreviousFrameTimestamp = glfwGetTime();
#if DONUT_WITH_STREAMLINE
            StreamlineIntegration::Get().SimStart(*this);
#endif
            if (m_callbacks.beforeFrame) m_callbacks.beforeFrame(*this, m_FrameIndex);
            glfwPollEvents();
            UpdateWindowSize();
            return AnimateRenderPresent();
}
void DeviceManager::RunMessageLoop()
'@ 'bool DeviceManager::HostFrame()'
$function="std::filesystem::path donut::app::GetDirectoryWithExecutable()`r`n{"
$path=Join-Path $sdk 'External/Donut/src/app/ApplicationBase.cpp'
$text=[IO.File]::ReadAllText($path)
if(!$text.Contains('RTR_RTXPT_RUNTIME')){
    $newline=if($text.Contains("`r`n")){"`r`n"}else{"`n"}
    $anchor='std::filesystem::path donut::app::GetDirectoryWithExecutable()'+$newline+'{'
    if(!$text.Contains($anchor)){throw 'Pinned Donut runtime-root anchor changed'}
    $replacement=$anchor+$newline+'    if (const char* runtime = std::getenv("RTR_RTXPT_RUNTIME")) return std::filesystem::path(runtime);'
    [IO.File]::WriteAllText($path,"#include <cstdlib>`n"+$text.Replace($anchor,$replacement),$utf8)
}
$rootCMake=Join-Path $sdk 'CMakeLists.txt'
$cmakeText=[IO.File]::ReadAllText($rootCMake)
# The command-line OptiX screenshot utility is not used by the embedded realtime host.
$cmakeText=$cmakeText.Replace('if (NOT _denoiser_hash STREQUAL RTXPT_OPTIX_DENOISER_SHA256)',
    'if (RTR_RTXPT_FETCH_CAPTURE_TOOL AND NOT _denoiser_hash STREQUAL RTXPT_OPTIX_DENOISER_SHA256)')
$marker='# RTR_RTXPT_HOST_TARGET'
if($cmakeText.Contains($marker)){$cmakeText=$cmakeText.Substring(0,$cmakeText.IndexOf($marker))}
$hostCMake=(Join-Path $repo 'renderer-rtxpt/src/main/cpp/CMakeLists.txt').Replace('\','/')
[IO.File]::WriteAllText($rootCMake,$cmakeText+"`n$marker`ninclude(`"$hostCMake`")`n",$utf8)
$cmake=(Resolve-Path (Join-Path $repo $CMakeExecutable)).Path
$build=Join-Path $sdk 'build-host'
$localDependencies=@()
if($StreamlineSdk){
    $streamline=(Resolve-Path -LiteralPath $StreamlineSdk).Path.Replace('\','/')
    $localDependencies+=('-DFETCHCONTENT_SOURCE_DIR_STREAMLINE='+$streamline)
}
& $cmake -S $sdk -B $build -G 'Visual Studio 17 2022' -A x64 `
    -DDONUT_WITH_VULKAN=OFF -DDONUT_WITH_KTX=OFF -DDONUT_WITH_AFTERMATH=OFF `
    -DDONUT_EMBED_SHADER_PDBS=OFF -DCMAKE_COMPILE_WARNING_AS_ERROR=OFF `
    -DTRACY_ENABLE=OFF `
    '-DCMAKE_CXX_FLAGS=/DWIN32 /D_WINDOWS /EHsc /utf-8' '-DCMAKE_C_FLAGS=/DWIN32 /D_WINDOWS /utf-8' @localDependencies
if($LASTEXITCODE -ne 0){throw 'RTXPT native configure failed'}
if($ConfigureOnly){return}
& (Join-Path $PSScriptRoot 'Invoke-BoundedBuild.ps1') -FilePath $cmake `
    -ArgumentList @('--build',$build,'--config','Release','--target','rtrenderer_rtxpt','rtxpt_native_control','--parallel','1','--',('/bl:'+(Join-Path $build 'build.binlog')),('/flp:logfile='+(Join-Path $build 'build.log')+';verbosity=normal;encoding=UTF-8')) `
    -WorkingDirectory $sdk -MemoryLimitGB $MemoryLimitGB
if($LASTEXITCODE -ne 0){throw 'RTXPT JNI bridge build failed'}
# Runtime redistribution keeps the exact upstream notices alongside the native payload.
$licenses=Join-Path $sdk 'bin/ThirdPartyLicenses'
New-Item -ItemType Directory -Path $licenses -Force | Out-Null
$noticeFiles=@{
    'LICENSE.txt'='RTXPT.txt'; 'External/Donut/LICENSE.txt'='Donut.txt';
    'External/Donut/ThirdPartyLicenses.txt'='Donut-ThirdParty.txt';
    'External/Donut/nvrhi/LICENSE.txt'='NVRHI.txt'; 'External/Donut/nvrhi/ThirdPartyLicenses.txt'='NVRHI-ThirdParty.txt';
    'External/Donut/ShaderMake/LICENSE.txt'='ShaderMake.txt'; 'External/Donut/ShaderMake/ThirdPartyLicenses.txt'='ShaderMake-ThirdParty.txt';
    'External/Nrd/LICENSE.txt'='NRD.txt'; 'External/Rtxdi/LICENSE.txt'='RTXDI.txt';
    'External/SHARC/License.md'='SHARC.md'; 'External/Rtxtf/LICENSE.txt'='RTXTF.txt';
    'External/NVAPI/License.txt'='NVAPI.txt'; 'External/Omm/LICENSE.txt'='OMM.txt';
    'External/cxxopts/LICENSE'='cxxopts.txt'; 'External/dxc/LICENCE-MIT.txt'='DXC-MIT.txt';
    'External/dxc/LICENSE-LLVM.txt'='DXC-LLVM.txt'; 'External/dxc/LICENSE-MS.txt'='DXC-MS.txt';
    'External/AgilitySDK/1.619.6/LICENSE.txt'='D3D12.txt'; 'External/AgilitySDK/1.619.6/LICENSE-CODE.txt'='D3D12-Code.txt'
}
foreach($entry in $noticeFiles.GetEnumerator()){Copy-Item -LiteralPath (Join-Path $sdk $entry.Key) -Destination (Join-Path $licenses $entry.Value) -Force}
$streamlineRoot=if($StreamlineSdk){(Resolve-Path -LiteralPath $StreamlineSdk).Path}else{Join-Path $build '_deps/streamline-src'}
Copy-Item -LiteralPath (Join-Path $streamlineRoot 'license.txt') -Destination (Join-Path $licenses 'Streamline.txt') -Force
Copy-Item -LiteralPath (Join-Path $streamlineRoot '3rd-party-licenses.md') -Destination (Join-Path $licenses 'Streamline-ThirdParty.md') -Force
