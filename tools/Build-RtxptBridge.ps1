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
    '-DCMAKE_CXX_FLAGS=/DWIN32 /D_WINDOWS /EHsc /utf-8' '-DCMAKE_C_FLAGS=/DWIN32 /D_WINDOWS /utf-8' @localDependencies
if($LASTEXITCODE -ne 0){throw 'RTXPT native configure failed'}
if($ConfigureOnly){return}
& (Join-Path $PSScriptRoot 'Invoke-BoundedBuild.ps1') -FilePath $cmake `
    -ArgumentList @('--build',$build,'--config','Release','--target','rtrenderer_rtxpt','--parallel','1','--',('/bl:'+(Join-Path $build 'build.binlog'))) `
    -WorkingDirectory $sdk -MemoryLimitGB $MemoryLimitGB
if($LASTEXITCODE -ne 0){throw 'RTXPT JNI bridge build failed'}
