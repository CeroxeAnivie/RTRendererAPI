<#
.SYNOPSIS
Build the pinned RTXPT JNI runtime from source.
.DESCRIPTION
Relative SDK/tool paths resolve from the repository root, regardless of the caller's working directory.
Use -CheckPaths to validate dependencies without patching sources, configuring CMake, or starting a build.
#>
param(
    [string]$SdkRoot='build/rtxpt-sdk',
    [string]$CMakeExecutable='build/native-build-tools/cmake-4.1.2-windows-x86_64/bin/cmake.exe',
    [string]$StreamlineSdk=$env:STREAMLINE_SDK,
    [string]$RtxmuRoot=$env:RTXMU_ROOT,
    [double]$MemoryLimitGB=10,
    [switch]$ConfigureOnly,
    [switch]$CheckPaths,
    [switch]$SkipSelfTests,
    [switch]$OpticalTransportTrace,
    [switch]$OpticalWorkTrace,
    [uint32]$OpticalBranchSeedXor=0
)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
function Resolve-ProjectPath([string]$Value){
    (Resolve-Path -LiteralPath ([IO.Path]::GetFullPath($Value,$repo))).Path
}
$sdk=Resolve-ProjectPath $SdkRoot
$cmake=Resolve-ProjectPath $CMakeExecutable
$streamline=if($StreamlineSdk){Resolve-ProjectPath $StreamlineSdk}else{$null}
$pin='303b408ab81790a5051abd7573830b7a4112eb8e'
$actualPin=& git -C $sdk rev-parse HEAD
if($LASTEXITCODE -ne 0 -or ($actualPin -join '').Trim() -ne $pin){throw 'RTXPT source must match the pinned 1.9.0 commit'}
if($CheckPaths){
    [pscustomobject]@{Repository=$repo;RtxptSdk=$sdk;CMake=$cmake;StreamlineSdk=$streamline;PinnedCommit=$pin}
    return
}
$utf8=[Text.UTF8Encoding]::new($false)
$nativeManagedPatch=Join-Path $PSScriptRoot 'patches/rtxpt-native-managed-resources.patch'
& git -C $sdk apply --reverse --check --ignore-space-change $nativeManagedPatch 2>$null
if($LASTEXITCODE -eq 0){
    & git -C $sdk apply --reverse --ignore-space-change $nativeManagedPatch
    if($LASTEXITCODE -ne 0){throw 'RTXPT native-managed overlay could not be removed'}
}

[IO.File]::Copy((Join-Path $repo 'renderer-rtxpt/src/main/cpp/rtxpt_scene_delta.h'),(Join-Path $sdk 'External/Donut/include/donut/engine/rtxpt_scene_delta.h'),$true)
$growthDiagnosticsPatch=Join-Path $PSScriptRoot 'patches/rtxpt-growth-diagnostics.patch'
& git -C $sdk apply --reverse --check --ignore-space-change $growthDiagnosticsPatch 2>$null
if($LASTEXITCODE -eq 0){
    & git -C $sdk apply --reverse --ignore-space-change $growthDiagnosticsPatch
    if($LASTEXITCODE -ne 0){throw 'RTXPT growth diagnostics overlay could not be removed'}
}
$topologyJournalPatch=Join-Path $PSScriptRoot 'patches/rtxpt-topology-journal.patch'
& git -C $sdk apply --reverse --check --ignore-space-change $topologyJournalPatch 2>$null
if($LASTEXITCODE -eq 0){
    & git -C $sdk apply --reverse --ignore-space-change $topologyJournalPatch
    if($LASTEXITCODE -ne 0){throw 'RTXPT topology-journal overlay could not be removed'}
}
$uploadStatisticsPatch=Join-Path $PSScriptRoot 'patches/rtxpt-upload-statistics.patch'
& git -C $sdk apply --reverse --check --ignore-space-change $uploadStatisticsPatch 2>$null
if($LASTEXITCODE -eq 0){
    & git -C $sdk apply --reverse --ignore-space-change $uploadStatisticsPatch
    if($LASTEXITCODE -ne 0){throw 'RTXPT upload-statistics overlay could not be removed'}
}
$stagingDiagnosticsPatch=Join-Path $PSScriptRoot 'patches/rtxpt-staging-diagnostics.patch'
& git -C $sdk apply --reverse --check --ignore-space-change $stagingDiagnosticsPatch 2>$null
if($LASTEXITCODE -eq 0){
    & git -C $sdk apply --reverse --ignore-space-change $stagingDiagnosticsPatch
    if($LASTEXITCODE -ne 0){throw 'RTXPT staging diagnostics overlay could not be removed'}
}
$tlasCapacityPatch=Join-Path $PSScriptRoot 'patches/rtxpt-tlas-capacity.patch'
& git -C $sdk apply --reverse --check --ignore-space-change $tlasCapacityPatch 2>$null
if($LASTEXITCODE -eq 0){
    & git -C $sdk apply --reverse --ignore-space-change $tlasCapacityPatch
    if($LASTEXITCODE -ne 0){throw 'RTXPT TLAS capacity overlay could not be removed'}
}
$metadataDeltaPatch=Join-Path $PSScriptRoot 'patches/rtxpt-metadata-delta.patch'
& git -C $sdk apply --reverse --check --ignore-space-change $metadataDeltaPatch 2>$null
if($LASTEXITCODE -eq 0){
    & git -C $sdk apply --reverse --ignore-space-change $metadataDeltaPatch
    if($LASTEXITCODE -ne 0){throw 'RTXPT metadata-delta overlay could not be removed'}
}
$maskedAlphaPatch=Join-Path $PSScriptRoot 'patches/rtxpt-masked-alpha.patch'
& git -C $sdk apply --reverse --check --ignore-space-change $maskedAlphaPatch 2>$null
if($LASTEXITCODE -eq 0){
    & git -C $sdk apply --reverse --ignore-space-change $maskedAlphaPatch
    if($LASTEXITCODE -ne 0){throw 'RTXPT masked-alpha correction could not be removed'}
}
$sceneTablesPatch=Join-Path $PSScriptRoot 'patches/rtxpt-scene-tables.patch'
& git -C $sdk apply --reverse --check --ignore-space-change $sceneTablesPatch 2>$null
if($LASTEXITCODE -eq 0){
    & git -C $sdk apply --reverse --ignore-space-change $sceneTablesPatch
    if($LASTEXITCODE -ne 0){throw 'RTXPT scene-table overlay could not be removed'}
}
$highFrameratePatch=Join-Path $PSScriptRoot 'patches/rtxpt-high-framerate-resources.patch'
& git -C $sdk apply --reverse --check --ignore-space-change $highFrameratePatch 2>$null
if($LASTEXITCODE -eq 0){
    & git -C $sdk apply --reverse --ignore-space-change $highFrameratePatch
    if($LASTEXITCODE -ne 0){throw 'RTXPT high-framerate resource overlay could not be removed'}
}
$dynamicResourcesPatch=Join-Path $PSScriptRoot 'patches/rtxpt-dynamic-resources.patch'
& git -C $sdk apply --reverse --check --ignore-space-change $dynamicResourcesPatch 2>$null
if($LASTEXITCODE -eq 0){
    & git -C $sdk apply --reverse --ignore-space-change $dynamicResourcesPatch
    if($LASTEXITCODE -ne 0){throw 'RTXPT dynamic-resources overlay could not be removed'}
}
$localPreparationPatch=Join-Path $PSScriptRoot 'patches/rtxpt-local-preparation.patch'
& git -C $sdk apply --reverse --check --ignore-space-change $localPreparationPatch 2>$null
if($LASTEXITCODE -eq 0){
    & git -C $sdk apply --reverse --ignore-space-change $localPreparationPatch
    if($LASTEXITCODE -ne 0){throw 'RTXPT local-preparation overlay could not be removed'}
}
# Restore this final lifecycle edit before reversing the layered SDK overlays.
$windowSource=Join-Path $sdk 'External/Donut/src/app/DeviceManager.cpp'
$windowText=[IO.File]::ReadAllText($windowSource)
$deferredWindow='    // RTR defer visibility until the JNI host finishes scene and shader initialization.'
if($windowText.Contains($deferredWindow)){
    $originalWindow=if($windowText.Contains('const bool hostBackgroundWindow')){'    if (!HostExternalPresentation) { glfwShowWindow(m_Window); if (!hostBackgroundWindow) glfwFocusWindow(m_Window); }'}else{'    if (!HostExternalPresentation) { glfwShowWindow(m_Window); glfwFocusWindow(m_Window); }'}
    [IO.File]::WriteAllText($windowSource,$windowText.Replace($deferredWindow,$originalWindow),$utf8)
}
$hostSurfacePatch=Join-Path $PSScriptRoot 'patches/rtxpt-host-surface.patch'
& git -C $sdk apply --reverse --check --ignore-space-change $hostSurfacePatch 2>$null
if($LASTEXITCODE -eq 0){
    & git -C $sdk apply --reverse --ignore-space-change $hostSurfacePatch
    if($LASTEXITCODE -ne 0){throw 'RTXPT host-surface overlay could not be removed'}
}
$sparsePatch=Join-Path $PSScriptRoot 'patches/rtxpt-sparse-lighting.patch'
& git -C $sdk apply --reverse --check --ignore-space-change $sparsePatch 2>$null
if($LASTEXITCODE -eq 0){
    & git -C $sdk apply --reverse --ignore-space-change $sparsePatch
    if($LASTEXITCODE -ne 0){throw 'RTXPT sparse-lighting overlay could not be removed'}
}
$registryPatch=Join-Path $PSScriptRoot 'patches/rtxpt-light-registry.patch'
& git -C $sdk apply --reverse --check --ignore-space-change $registryPatch 2>$null
if($LASTEXITCODE -eq 0){
    & git -C $sdk apply --reverse --ignore-space-change $registryPatch
    if($LASTEXITCODE -ne 0){throw 'RTXPT light-registry overlay could not be removed'}
}
$framePatch=Join-Path $PSScriptRoot 'patches/rtxpt-frame-preparation.patch'
& git -C $sdk apply --reverse --check --ignore-space-change $framePatch 2>$null
if($LASTEXITCODE -eq 0){
    & git -C $sdk apply --reverse --ignore-space-change $framePatch
    if($LASTEXITCODE -ne 0){throw 'RTXPT frame-preparation overlay could not be removed'}
}
$transparentPatch=Join-Path $PSScriptRoot 'patches/rtxpt-transparent-background.patch'
& git -C $sdk apply --reverse --check --ignore-space-change $transparentPatch 2>$null
if($LASTEXITCODE -eq 0){
    & git -C $sdk apply --reverse --ignore-space-change $transparentPatch
    if($LASTEXITCODE -ne 0){throw 'RTXPT transparent-background overlay could not be removed'}
}
$retainedPatch=Join-Path $PSScriptRoot 'patches/rtxpt-retained-resources.patch'
& git -C $sdk apply --reverse --check --ignore-space-change $retainedPatch 2>$null
if($LASTEXITCODE -eq 0){
    & git -C $sdk apply --reverse --ignore-space-change $retainedPatch
    if($LASTEXITCODE -ne 0){throw 'RTXPT retained-resource overlay could not be removed'}
}
# Remove a complete lighting overlay before checking/rebuilding its pinned base.
# A partial overlay is never accepted: final apply --check below must match every hunk.
$lightingPatch=Join-Path $PSScriptRoot 'patches/rtxpt-incremental-lighting.patch'
& git -C $sdk apply --reverse --check --ignore-space-change $lightingPatch 2>$null
if($LASTEXITCODE -eq 0){
    & git -C $sdk apply --reverse --ignore-space-change $lightingPatch
    if($LASTEXITCODE -ne 0){throw 'RTXPT lighting overlay could not be removed for base verification'}
    foreach($line in [IO.File]::ReadAllLines($lightingPatch)){
        if($line.StartsWith('+++ b/')){
            $path=Join-Path $sdk $line.Substring(6)
            $text=[IO.File]::ReadAllText($path)
            [IO.File]::WriteAllText($path,[regex]::Replace($text,"`r?`n","`r`n"),$utf8)
        }
    }
}

function Patch-Once([string]$Relative,[string]$Anchor,[string]$Replacement,[string]$Marker){
    $path=Join-Path $sdk $Relative
    $text=[IO.File]::ReadAllText($path)
    if($text.Contains($Marker)){return}
    $Anchor=[regex]::Replace($Anchor,"`r?`n","`r`n")
    $Replacement=[regex]::Replace($Replacement,"`r?`n","`r`n")
    if(!$text.Contains($Anchor)){throw "Pinned host integration anchor changed: $Relative :: $Anchor"}
    [IO.File]::WriteAllText($path,$text.Replace($Anchor,$Replacement),$utf8)
}
Patch-Once 'External/Donut/nvrhi/src/d3d12/d3d12-buffer.cpp' "        if (d.isVirtual)`r`n        {" @'
        // RTR virtual AS storage needs the same flags before the early return.
        if (d.isAccelStructStorage)
            resourceDesc.Flags |= D3D12_RESOURCE_FLAG_RAYTRACING_ACCELERATION_STRUCTURE;
        if (d.isVirtual)
        {
'@ 'RTR virtual AS storage needs the same flags'
# Retain the upstream pipeline baker; make its binaries relocatable for the JAR
# and optionally export the exact upstream command for offline six-budget builds.
# Keep the census inert unless RTR_RTXPT_MEMORY_DIAGNOSTICS=1 is explicitly set.
foreach($source in @('d3d12-buffer.cpp','d3d12-texture.cpp','d3d12-compute.cpp','d3d12-graphics.cpp','d3d12-raytracing.cpp','d3d12-upload.cpp')) {
    Patch-Once "External/Donut/nvrhi/src/d3d12/$source" '#include "d3d12-backend.h"' @'
#include "d3d12-backend.h"
#include "rtxpt_memory_diagnostics.h" // RTR opt-in native resource census
'@ 'RTR opt-in native resource census'
}
Patch-Once 'External/Donut/nvrhi/src/d3d12/d3d12-upload.cpp' '        chunk->bufferSize = size;' @'
        RtrMemoryDiagnostics::Created(chunk.get(),"pool",m_IsScratchBuffer?"DXR scratch pool":"Upload pool",m_Context.device,chunk->buffer,size); // RTR attribute internal pools
        chunk->bufferSize = size;
'@ 'RTR attribute internal pools'
Patch-Once 'External/Donut/nvrhi/src/d3d12/d3d12-upload.cpp' "    BufferChunk::~BufferChunk()`r`n    {" @'
    BufferChunk::~BufferChunk()
    {
        RtrMemoryDiagnostics::Released(this); // RTR retire pool census
'@ 'RTR retire pool census'
Patch-Once 'External/Donut/src/app/streamline/StreamlineIntegration.cpp' '#include <StreamlineIntegration.h>' @'
#include <StreamlineIntegration.h>
#include "rtxpt_memory_diagnostics.h" // RTR Streamline resource census
'@ 'RTR Streamline resource census'
Patch-Once 'External/Donut/src/app/streamline/StreamlineIntegration.cpp' '            bool success = SUCCEEDED(pd3d12Device->CreateCommittedResource(heap, D3D12_HEAP_FLAG_NONE, desc, state, nullptr, IID_PPV_ARGS(&pbuffer)));' @'
            bool success = SUCCEEDED(pd3d12Device->CreateCommittedResource(heap, D3D12_HEAP_FLAG_NONE, desc, state, nullptr, IID_PPV_ARGS(&pbuffer)));
            if(success)RtrMemoryDiagnostics::Created(pbuffer,"streamline","Streamline buffer",pd3d12Device,pbuffer,desc->Width); // RTR attribute SDK buffers
'@ 'RTR attribute SDK buffers'
Patch-Once 'External/Donut/src/app/streamline/StreamlineIntegration.cpp' '            bool success = SUCCEEDED(pd3d12Device->CreateCommittedResource(heap, D3D12_HEAP_FLAG_NONE, desc, state, pClearValue, IID_PPV_ARGS(&ptexture)));' @'
            bool success = SUCCEEDED(pd3d12Device->CreateCommittedResource(heap, D3D12_HEAP_FLAG_NONE, desc, state, pClearValue, IID_PPV_ARGS(&ptexture)));
            if(success)RtrMemoryDiagnostics::Created(ptexture,"streamline","Streamline texture",pd3d12Device,ptexture,0); // RTR attribute SDK textures
'@ 'RTR attribute SDK textures'
Patch-Once 'External/Donut/src/app/streamline/StreamlineIntegration.cpp' '            ID3D12Resource* pbuffer;' @'
            ID3D12Resource* pbuffer = nullptr; // RTR empty SL allocation failure
'@ 'RTR empty SL allocation failure'
Patch-Once 'External/Donut/src/app/streamline/StreamlineIntegration.cpp' '            ID3D12Resource* ptexture;' @'
            ID3D12Resource* ptexture = nullptr; // RTR empty SL texture allocation failure
'@ 'RTR empty SL texture allocation failure'
Patch-Once 'External/Donut/src/app/streamline/StreamlineIntegration.cpp' '            bool success = SUCCEEDED(pd3d12Device->CreateCommittedResource(heap, D3D12_HEAP_FLAG_NONE, desc, state, nullptr, IID_PPV_ARGS(&pbuffer)));' @'
            const HRESULT allocationResult = pd3d12Device->CreateCommittedResource(heap, D3D12_HEAP_FLAG_NONE, desc, state, nullptr, IID_PPV_ARGS(&pbuffer));
            const bool success = SUCCEEDED(allocationResult);
            if (!success) { // RTR preserve SL buffer allocation error
                log::error("SL buffer allocation failed: HRESULT=0x%08lx width=%llu heap=%u flags=%u removed=0x%08lx", (unsigned long)allocationResult, (unsigned long long)desc->Width, unsigned(heap->Type), unsigned(desc->Flags), (unsigned long)pd3d12Device->GetDeviceRemovedReason());
                return res;
            }
'@ 'RTR preserve SL buffer allocation error'
Patch-Once 'External/Donut/src/app/streamline/StreamlineIntegration.cpp' '            bool success = SUCCEEDED(pd3d12Device->CreateCommittedResource(heap, D3D12_HEAP_FLAG_NONE, desc, state, pClearValue, IID_PPV_ARGS(&ptexture)));' @'
            const HRESULT allocationResult = pd3d12Device->CreateCommittedResource(heap, D3D12_HEAP_FLAG_NONE, desc, state, pClearValue, IID_PPV_ARGS(&ptexture));
            const bool success = SUCCEEDED(allocationResult);
            if (!success) { // RTR preserve SL texture allocation error
                log::error("SL texture allocation failed: HRESULT=0x%08lx width=%llu height=%u heap=%u flags=%u removed=0x%08lx", (unsigned long)allocationResult, (unsigned long long)desc->Width, desc->Height, unsigned(heap->Type), unsigned(desc->Flags), (unsigned long)pd3d12Device->GetDeviceRemovedReason());
                return res;
            }
'@ 'RTR preserve SL texture allocation error'
Patch-Once 'External/Donut/src/app/streamline/StreamlineIntegration.cpp' '    if (resource)' @'
    if (resource && resource->native) // RTR tolerate failed SL allocation release
'@ 'RTR tolerate failed SL allocation release'
Patch-Once 'External/Donut/src/app/streamline/StreamlineIntegration.cpp' '        auto i = (IUnknown*)resource->native;' @'
        RtrMemoryDiagnostics::Released(resource->native); // RTR retire SDK census
        auto i = (IUnknown*)resource->native;
'@ 'RTR retire SDK census'
Patch-Once 'External/Donut/nvrhi/src/d3d12/d3d12-buffer.cpp' '        buffer->postCreate();' @'
        RtrMemoryDiagnostics::Created(buffer,"buffer",buffer->desc.debugName,m_Context.device,buffer->resource,buffer->desc.byteSize); // RTR attribute buffer allocation
        buffer->postCreate();
'@ 'RTR attribute buffer allocation'
Patch-Once 'External/Donut/nvrhi/src/d3d12/d3d12-texture.cpp' '        texture->postCreate();' @'
        RtrMemoryDiagnostics::Created(texture,"texture",texture->desc.debugName,m_Context.device,texture->resource,0); // RTR attribute texture allocation
        texture->postCreate();
'@ 'RTR attribute texture allocation'
Patch-Once 'External/Donut/nvrhi/src/d3d12/d3d12-buffer.cpp' "    Buffer::~Buffer()`r`n    {" @'
    Buffer::~Buffer()
    {
        RtrMemoryDiagnostics::Released(this); // RTR retire buffer census
'@ 'RTR retire buffer census'
Patch-Once 'External/Donut/nvrhi/src/d3d12/d3d12-texture.cpp' "    Texture::~Texture()`r`n    {" @'
    Texture::~Texture()
    {
        RtrMemoryDiagnostics::Released(this); // RTR retire texture census
'@ 'RTR retire texture census'
Patch-Once 'External/Donut/nvrhi/src/d3d12/d3d12-compute.cpp' "    RefCountPtr<ID3D12PipelineState> Device::createPipelineState(const ComputePipelineDesc & state, RootSignature* pRS) const`r`n    {" @'
    RefCountPtr<ID3D12PipelineState> Device::createPipelineState(const ComputePipelineDesc & state, RootSignature* pRS) const
    {
        RtrMemoryDiagnostics::Scope memory("pipeline.compute"); // RTR compiler memory phase
'@ 'RTR compiler memory phase'
Patch-Once 'External/Donut/nvrhi/src/d3d12/d3d12-graphics.cpp' "    RefCountPtr<ID3D12PipelineState> Device::createPipelineState(const GraphicsPipelineDesc & state, RootSignature* pRS, const FramebufferInfo& fbinfo) const`r`n    {" @'
    RefCountPtr<ID3D12PipelineState> Device::createPipelineState(const GraphicsPipelineDesc & state, RootSignature* pRS, const FramebufferInfo& fbinfo) const
    {
        RtrMemoryDiagnostics::Scope memory("pipeline.graphics"); // RTR graphics compiler memory phase
'@ 'RTR graphics compiler memory phase'
Patch-Once 'External/Donut/nvrhi/src/d3d12/d3d12-raytracing.cpp' "    rt::PipelineHandle Device::createRayTracingPipeline(const rt::PipelineDesc& desc)`r`n    {" @'
    rt::PipelineHandle Device::createRayTracingPipeline(const rt::PipelineDesc& desc)
    {
        RtrMemoryDiagnostics::Scope memory("pipeline.raytracing"); // RTR RT compiler memory phase
'@ 'RTR RT compiler memory phase'
Patch-Once 'Rtxpt/SampleCommon/SampleBaseApp.cpp' '#include "SampleBaseApp.h"' @'
#include "SampleBaseApp.h"
#include "rtxpt_memory_diagnostics.h" // RTR initialization memory phases
'@ 'RTR initialization memory phases'
Patch-Once 'Rtxpt/SampleCommon/SampleBaseApp.cpp' '    HelpersRegisterActiveWindow();' @'
    if(RtrMemoryDiagnostics::Enabled())RtrMemoryDiagnostics::Checkpoint("init.device",static_cast<ID3D12Device*>(m_DeviceManager->GetDevice()->getNativeObject(nvrhi::ObjectTypes::D3D12_Device))); // RTR device memory boundary
    HelpersRegisterActiveWindow();
'@ 'RTR device memory boundary'
Patch-Once 'Rtxpt/SampleCommon/SampleBaseApp.cpp' '    m_MainSceneRender->Init(preferredScene, m_ShaderFactory);' @'
    m_MainSceneRender->Init(preferredScene, m_ShaderFactory);
    if(RtrMemoryDiagnostics::Enabled())RtrMemoryDiagnostics::Checkpoint("init.scene",static_cast<ID3D12Device*>(m_DeviceManager->GetDevice()->getNativeObject(nvrhi::ObjectTypes::D3D12_Device))); // RTR scene memory boundary
'@ 'RTR scene memory boundary'
Patch-Once 'Rtxpt/SampleCommon/PTPipelineBaker.cpp' '#include "ShaderCompilerUtils.h"' @'
#include "ShaderCompilerUtils.h"
#include "rtxpt_shader_bundle.h" // RTR relocatable PT shader cache
#include "rtxpt_host_resources.h"
'@ 'RTR relocatable PT shader cache'
Patch-Once 'Rtxpt/SampleCommon/PTPipelineBaker.cpp' '    , m_hitGroupMacros(std::move(hitGroupMacros))' @'
    , m_hitGroupMacros(std::move(hitGroupMacros))
    , m_threadPool(RtrHostResources::shaderCompilerThreads.load()) // RTR bounded PT compile pool
'@ 'RTR bounded PT compile pool'
Patch-Once 'Rtxpt/SampleCommon/ComputePipelineBaker.cpp' '#include "ShaderCompilerUtils.h"' @'
#include "ShaderCompilerUtils.h"
#include "rtxpt_host_resources.h" // RTR bounded compute compile pool
'@ 'RTR bounded compute compile pool'
Patch-Once 'Rtxpt/SampleCommon/ComputePipelineBaker.cpp' '    : m_device(device)' @'
    : m_device(device), m_threadPool(RtrHostResources::shaderCompilerThreads.load()) // RTR compiler thread count
'@ 'RTR compiler thread count'
Patch-Once 'Rtxpt/Sample.cpp' '    , m_ui(g_sampleUIData)' @'
    , m_ui(g_sampleUIData), m_frameWorkerPool(RtrHostResources::workerThreads.load()) // RTR frame worker count
'@ 'RTR frame worker count'
Patch-Once 'Rtxpt/Sample.cpp' '#include "Sample.h"' @'
#include "Sample.h"
#include "rtxpt_host_resources.h" // RTR bounded scene CPU pool
'@ 'RTR bounded scene CPU pool'
Patch-Once 'External/Donut/include/donut/engine/ThreadPool.h' '    ~ThreadPool();' @'
    ~ThreadPool();
    size_t GetThreadCount() const {return m_threads.size();} // RTR actual pool size
'@ 'RTR actual pool size'
Patch-Once 'Rtxpt/SampleCommon/PTPipelineBaker.h' '    ~PTPipelineBaker();' @'
    ~PTPipelineBaker();
    size_t HostCompilerThreadCount() const {return m_threadPool.GetThreadCount();} // RTR pool readback
'@ 'RTR pool readback'
Patch-Once 'Rtxpt/Sample.h' '    mutable donut::engine::ThreadPool           m_frameWorkerPool;' @'
public:
    size_t HostWorkerThreadCount() const {return m_frameWorkerPool.GetThreadCount();} // RTR CPU pool readback
protected:
    mutable donut::engine::ThreadPool           m_frameWorkerPool;
'@ 'RTR CPU pool readback'
Patch-Once 'Rtxpt/SampleCommon/PTPipelineBaker.cpp' '        std::string newHash = ShaderCompilerUtils::ComputeSha256Hex(command);' @'
        const std::string portableCommand=RtrShaderBundle::PortableCommand(command,srcFullPath,permutation->ShaderSrcFileName,baker->GetIncludePaths());
        std::string newHash = ShaderCompilerUtils::ComputeSha256Hex(portableCommand); // RTR portable permutation key
        RtrShaderBundle::Export(permutation->ShaderOutFileName.generic_string(),newHash,command,portableCommand);
'@ 'RTR portable permutation key'
Patch-Once 'Rtxpt/SampleCommon/PTPipelineBaker.cpp' '            command = commandBase + command ' @'
            RtrShaderBundle::RequirePrecompiled(permutation->CompiledFullPath); // RTR packaged-only acceptance
            command = commandBase + command
'@ 'RTR packaged-only acceptance'
Patch-Once 'Rtxpt/SampleCommon/ComputePipelineBaker.cpp' '#include "rtxpt_host_resources.h" // RTR bounded compute compile pool' @'
#include "rtxpt_host_resources.h" // RTR bounded compute compile pool
#include "rtxpt_shader_bundle.h" // RTR portable compute cache
'@ 'RTR portable compute cache'
Patch-Once 'Rtxpt/SampleCommon/ComputePipelineBaker.cpp' '    auto cmdResult = ShaderCompilerUtils::BuildDxcCommand(baker->GetCompilerConfig(), options);' @'
    auto cmdResult = ShaderCompilerUtils::BuildDxcCommand(baker->GetCompilerConfig(), options);
    const std::string portableCommand=RtrShaderBundle::PortableCommand(cmdResult.CommandBase,srcFullPath,m_shaderSrcFileName,baker->GetCompilerConfig().IncludePaths);
    cmdResult.HashHex=ShaderCompilerUtils::ComputeSha256Hex(portableCommand); // RTR relocatable compute key
    RtrShaderBundle::Export(m_shaderSrcFileName.stem().string()+"_"+m_entryPoint,cmdResult.HashHex,cmdResult.CommandBase,portableCommand);
'@ 'RTR relocatable compute key'
Patch-Once 'Rtxpt/SampleCommon/ComputePipelineBaker.cpp' '        // Need to recompile' @'
        RtrShaderBundle::RequirePrecompiled(m_compiledFullPath); // RTR precompiled compute acceptance
        // Need to recompile
'@ 'RTR precompiled compute acceptance'
Patch-Once 'Rtxpt/Sharc/SharcIntegration.h' 'const std::shared_ptr<donut::engine::DescriptorTableManager>& bindlessHeapTable);' 'const std::shared_ptr<donut::engine::DescriptorTableManager>& bindlessHeapTable,uint32_t entries=4*1024*1024); // RTR public cache capacity' 'RTR public cache capacity'
Patch-Once 'Rtxpt/Sharc/SharcIntegration.cpp' 'const std::shared_ptr<donut::engine::DescriptorTableManager>& bindlessHeapTable)' 'const std::shared_ptr<donut::engine::DescriptorTableManager>& bindlessHeapTable,uint32_t entries) // RTR configured cache capacity' 'RTR configured cache capacity'
Patch-Once 'Rtxpt/Sharc/SharcIntegration.cpp' '        m_entriesNum = 4 * 1024 * 1024;' '        m_entriesNum = entries; // RTR allocated cache entry budget' 'RTR allocated cache entry budget'
Patch-Once 'Rtxpt/Sharc/SharcIntegration.h' '    const SharcSettings& GetSettings() const { return m_settings; }' @'
    const SharcSettings& GetSettings() const { return m_settings; }
    SharcSettings& HostSettings() { return m_settings; } // RTR host cache settings
    uint32_t GetEntryCount() const { return m_entriesNum; }
'@ 'RTR host cache settings'
Patch-Once 'Rtxpt/AdvancedSample.cpp' '    void EnsureSharcCreated()' @'
    virtual uint32_t HostRadianceCacheEntries() const {return 4*1024*1024;} // RTR cache host budget
    virtual void ConfigureHostRadianceCache(SharcSettings&) const {}
    void EnsureSharcCreated()
'@ 'RTR cache host budget'
Patch-Once 'Rtxpt/AdvancedSample.cpp' @'
        if (m_sharc == nullptr)
            m_sharc = std::make_unique<SharcIntegration>(GetDevice(), m_bindingLayout, m_bindlessLayout,
                                                        m_bindlessHeapLayout, m_bindlessHeapDescriptorTable);
'@ @'
        if (m_sharc == nullptr) {
            m_sharc = std::make_unique<SharcIntegration>(GetDevice(), m_bindingLayout, m_bindlessLayout,
                m_bindlessHeapLayout, m_bindlessHeapDescriptorTable,HostRadianceCacheEntries()); // RTR allocate requested cache
            ConfigureHostRadianceCache(m_sharc->HostSettings());
        }
'@ 'RTR allocate requested cache'
# A failed NVRHI map (for example after device removal) must reach the JNI error boundary,
# rather than dereferencing a null readback pointer and crashing the entire JVM.
Patch-Once 'Rtxpt/Misc/ShaderDebug.cpp' '#include "ShaderDebug.h"' @'
#include "ShaderDebug.h"
#include <stdexcept> // RTR failed-map exception boundary
'@ 'RTR failed-map exception boundary'
Patch-Once 'Rtxpt/Misc/ShaderDebug.cpp' '    static uint32_t s_breakpointReportCount = 0;' @'
    if (!pData)
        throw std::runtime_error("RTXPT shader-debug readback map failed; inspect the preceding device error"); // RTR reject failed readback map
    static uint32_t s_breakpointReportCount = 0;
'@ 'RTR reject failed readback map'
# Bound nested shader compilers as well as the top-level MSBuild process.
Patch-Once 'Rtxpt/SampleUI.h' '    //float                               DLSSSharpness = 0.f;' @'
    SI::DLSSPreset                      HostDLSSSRPreset = SI::DLSSPreset::eDefault; // RTR explicit upstream SR model
    //float                               DLSSSharpness = 0.f;
'@ 'RTR explicit upstream SR model'
Patch-Once 'Rtxpt/Sample.cpp' '                dlssOptions.preset = StreamlineInterface::DLSSPreset::eDefault;' @'
                dlssOptions.preset = m_ui.HostDLSSSRPreset; // RTR forward upstream SR model without changing resolution
'@ 'RTR forward upstream SR model'
# Isolate the old Vulkan route's documented 1x1 exposure contract without changing
# the SR model, color, tone mapper, sample count, or render resolution.
Patch-Once 'Rtxpt/SampleUI.h' '    //float                               DLSSSharpness = 0.f;' @'
    float                              HostDLSSFixedExposure = 0.f; // RTR isolated SR exposure, zero keeps upstream auto
    //float                               DLSSSharpness = 0.f;
'@ 'RTR isolated SR exposure'
Patch-Once 'Rtxpt/Sample.h' '    void                                    PostProcessPreToneMapping(nvrhi::ICommandList* commandList, const donut::engine::ICompositeView& compositeView);' @'
    nvrhi::TextureHandle                     m_hostDLSSExposure; // RTR optional one-texel exposure
    void                                    PostProcessPreToneMapping(nvrhi::ICommandList* commandList, const donut::engine::ICompositeView& compositeView);
'@ 'RTR optional one-texel exposure'
Patch-Once 'Rtxpt/Sample.cpp' 'dlssOptions.useAutoExposure = true;' 'dlssOptions.useAutoExposure = m_ui.HostDLSSFixedExposure <= 0.f || m_ui.RealtimeAA != 2; /* RTR explicit SR exposure contract */' 'RTR explicit SR exposure contract'
Patch-Once 'External/Donut/include/donut/app/StreamlineInterface.h' '        nvrhi::ITexture* input) = 0;' @'
        nvrhi::ITexture* input,
        nvrhi::ITexture* exposure = nullptr) = 0; // RTR optional DLSS exposure tag
'@ 'RTR optional DLSS exposure tag'
Patch-Once 'External/Donut/src/app/streamline/StreamlineIntegration.h' '        nvrhi::ITexture* input) override;' @'
        nvrhi::ITexture* input,
        nvrhi::ITexture* exposure = nullptr) override; // RTR optional DLSS exposure tag
'@ 'RTR optional DLSS exposure tag'
Patch-Once 'External/Donut/src/app/streamline/StreamlineIntegration.cpp' '    nvrhi::ITexture* Input)' @'
    nvrhi::ITexture* Input,
    nvrhi::ITexture* Exposure) // RTR optional DLSS exposure tag
'@ 'RTR optional DLSS exposure tag'
Patch-Once 'External/Donut/src/app/streamline/StreamlineIntegration.cpp' '    sl::ResourceTag inputs[] = { inputResourceTag, outputResourceTag };' @'
    sl::Resource exposureResource{};
    sl::Extent exposureExtent{0, 0, 1, 1};
    if (Exposure) GetSLResource(commandList, exposureResource, Exposure, view);
    // A null tag removes previous fixed exposure when returning to upstream auto.
    sl::ResourceTag exposureTag{ Exposure ? &exposureResource : nullptr, sl::kBufferTypeExposure,
        sl::ResourceLifecycle::eValidUntilPresent, &exposureExtent };
    sl::ResourceTag inputs[] = { inputResourceTag, outputResourceTag, exposureTag }; // RTR explicit exposure input
'@ 'RTR explicit exposure input'
Patch-Once 'Rtxpt/Sample.cpp' @'
        // TAG STREAMLINE RESOURCES
        GetDeviceManager()->GetStreamline().TagResourcesDLSSNIS(m_commandList,
'@ @'
        if (m_ui.HostDLSSFixedExposure > 0.f && m_ui.RealtimeAA == 2 && !m_hostDLSSExposure)
        {
            nvrhi::TextureDesc desc;
            desc.width = desc.height = 1;
            desc.format = nvrhi::Format::R32_FLOAT;
            desc.isUAV = true;
            desc.initialState = nvrhi::ResourceStates::ShaderResource;
            desc.keepInitialState = true;
            desc.debugName = "HostDLSSExposure";
            m_hostDLSSExposure = GetDevice()->createTexture(desc);
            m_commandList->clearTextureFloat(m_hostDLSSExposure, nvrhi::AllSubresources, nvrhi::Color(m_ui.HostDLSSFixedExposure));
            log::info("RTXPT explicit SR exposure texture=%.3f (1x1 R32F)", m_ui.HostDLSSFixedExposure);
        }
        // TAG STREAMLINE RESOURCES
        GetDeviceManager()->GetStreamline().TagResourcesDLSSNIS(m_commandList,
'@ 'RTXPT explicit SR exposure texture='
Patch-Once 'Rtxpt/Sample.cpp' @'
            m_renderTargets->ProcessedOutputColor,
            m_renderTargets->OutputColor);
'@ @'
            m_renderTargets->ProcessedOutputColor,
            m_renderTargets->OutputColor,
            m_ui.HostDLSSFixedExposure > 0.f && m_ui.RealtimeAA == 2 ? m_hostDLSSExposure.Get() : nullptr); // RTR tag fixed exposure
'@ 'RTR tag fixed exposure'
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
# SR/TAA must consume this frame's merged radiance, not last frame's OutputColor.
# RR already merges its own guides in the render prefix; NRD already merges before AA.
$samplePath=Join-Path $sdk 'Rtxpt/Sample.cpp'
$sampleText=[IO.File]::ReadAllText($samplePath)
if(!$sampleText.Contains('RTR current radiance before temporal reconstruction')){
    $lateMerge='(?s)        else if \( !m_ui\.ActualUseStandaloneDenoiser\(\) \)\s*\{\s*// If all denoisers disabled, this is a pass-through that just merges and outputs noisy data\s*SampleMiniConstants miniConstants = \{ uint4\(0, 0, 0, 0\) \};\s*nvrhi::TextureDesc tdesc = m_renderTargets->OutputColor->getDesc\(\);\s*GpuProfiler::Begin\(m_commandList, "NoDenoiserFinalMerge"\);\s*m_postProcess->Apply\(m_commandList, PostProcess::ComputePassType::NoDenoiserFinalMerge, m_constantBuffer, miniConstants, m_bindingSet, m_bindingLayout, tdesc.width, tdesc.height\);\s*GpuProfiler::End\(m_commandList\);\s*\}'
    $match=[regex]::Match($sampleText,$lateMerge)
    if(!$match.Success){throw 'Pinned late no-denoiser merge anchor changed'}
    $body=$match.Value.Substring($match.Value.IndexOf('{'))
    $sampleText=$sampleText.Remove($match.Index,$match.Length)
    $anchor='(void Sample::PostProcessAA\(nvrhi::IFramebuffer\* framebuffer, bool reset\)\s*\{\s*ZoneScopedN\("PostProcessAA"\);\s*if \(m_ui.RealtimeMode\)\s*\{)'
    if(![regex]::IsMatch($sampleText,$anchor)){throw 'Pinned pre-AA merge anchor changed'}
    $insert='$1'+"`n        // RTR current radiance before temporal reconstruction`n        if (m_ui.RealtimeAA != 3 && !m_ui.ActualUseStandaloneDenoiser())`n        "+$body+"`n"
    $sampleText=[regex]::Replace($sampleText,$anchor,$insert,1)
    [IO.File]::WriteAllText($samplePath,$sampleText,$utf8)
}
# RTXDI writes directly into OutputColor when no denoiser is active. Initialize
# that color from the current path-tracing layers BEFORE RTXDI adds its lighting;
# merging afterwards discards the entire direct/indirect reuse contribution.
Patch-Once 'Rtxpt/AdvancedSample.cpp' '    // this is a performance optimization where final 2 passes from ReSTIR DI and ReSTIR GI are combined to avoid loading GBuffer twice' @'
    // RTR initialize raw radiance before RTXDI accumulation
    if (m_ui.RealtimeMode && m_ui.RealtimeAA != 3
        && !m_ui.ActualUseStandaloneDenoiser() && m_ui.ActualUseRTXDIPasses())
    {
        GpuProfiler::Begin(m_commandList, "NoDenoiserFinalMerge");
        const auto& desc = m_renderTargets->OutputColor->getDesc();
        m_postProcess->Apply(m_commandList, PostProcess::ComputePassType::NoDenoiserFinalMerge,
            m_constantBuffer, miniConstants, m_bindingSet, m_bindingLayout, desc.width, desc.height);
        GpuProfiler::End(m_commandList);
    }

    // this is a performance optimization where final 2 passes from ReSTIR DI and ReSTIR GI are combined to avoid loading GBuffer twice
'@ 'RTR initialize raw radiance before RTXDI accumulation'
Patch-Once 'Rtxpt/Sample.cpp' 'if (m_ui.RealtimeAA != 3 && !m_ui.ActualUseStandaloneDenoiser())' @'
if (m_ui.RealtimeAA != 3 && !m_ui.ActualUseStandaloneDenoiser()
            && !m_ui.ActualUseRTXDIPasses()) // RTR preserve RTXDI accumulated radiance
'@ 'RTR preserve RTXDI accumulated radiance'
# The filtered hit-distance texture is consumed by NRD, RR guides and debug views.
# Scene-selected quality path: reuse RTXPT point sampling/shadow/BSDF and keep
# the residual-light PDFs and candidate budget coherent with exact delta sums.
[IO.File]::WriteAllText((Join-Path $sdk 'Rtxpt/Shaders/PathTracer/Lighting/RtrLightSamplerStrata.hlsli'),
    [IO.File]::ReadAllText((Join-Path $repo 'renderer-rtxpt/src/main/cpp/RtrLightSamplerStrata.hlsli')),$utf8)
Patch-Once 'Rtxpt/Shaders/PathTracer/Lighting/LightSampler.hlsli' '    // returned value is in [0, ControlBuffer[0].TotalLightCount) range used to read from LightsBuffer' @'
    #include "RtrLightSamplerStrata.hlsli" // RTR conditional environment stratum
    // returned value is in [0, ControlBuffer[0].TotalLightCount) range used to read from LightsBuffer
'@ 'RTR conditional environment stratum'
Patch-Once 'Rtxpt/Shaders/PathTracer/Lighting/LightSampler.hlsli' '        uint totalProxyCount = ControlBuffer[0].SamplingProxyCount;' @'
        uint totalProxyCount = ControlBuffer[0].SamplingProxyCount;
        #if defined(RTR_SPLIT_POINT_ENVIRONMENT) && RTR_SPLIT_POINT_ENVIRONMENT
        if (RtrSplitPointEnvironment()) totalProxyCount = RtrEnvironmentProxyCount(); // RTR conditional proxy range
        #endif
'@ 'RTR conditional proxy range'
Patch-Once 'Rtxpt/Shaders/PathTracer/Lighting/LightSampler.hlsli' '        ReadLocal(indexInIndex, lightIndex, proxyCount);' @'
        #if defined(RTR_SPLIT_POINT_ENVIRONMENT) && RTR_SPLIT_POINT_ENVIRONMENT
        if (RtrSplitPointEnvironment())
        {
            if (RtrEnvironmentLocalProxyCount() == 0) { pdf = 0; return NEEAT_INVALID_LIGHT_INDEX; }
            lightIndex = RtrSampleEnvironmentLocal(rnd, pdf); // RTR conditional NEE-AT local stratum
            return lightIndex;
        }
        #endif
        ReadLocal(indexInIndex, lightIndex, proxyCount);
'@ 'RTR conditional NEE-AT local stratum'
Patch-Once 'Rtxpt/Shaders/PathTracer/Lighting/LightSampler.hlsli' '        float pdf = proxyCountPerLight / float(float(ControlBuffer[0].SamplingProxyCount));' @'
        uint totalProxyCount = ControlBuffer[0].SamplingProxyCount;
        #if defined(RTR_SPLIT_POINT_ENVIRONMENT) && RTR_SPLIT_POINT_ENVIRONMENT
        if (RtrSplitPointEnvironment())
        {
            if (lightIndex >= ControlBuffer[0].EnvmapQuadNodeCount) return 0;
            totalProxyCount = RtrEnvironmentProxyCount();
        }
        #endif
        float pdf = totalProxyCount > 0 ? proxyCountPerLight / float(totalProxyCount) : 0; // RTR matching conditional BSDF PDF
'@ 'RTR matching conditional BSDF PDF'
Patch-Once 'Rtxpt/Shaders/PathTracer/Lighting/LightSampler.hlsli' '        float globalPDFModifier = clamp( globalPdf, 1e-6, 1e6 );' @'
        float feedbackPdf = globalPdf;
        #if defined(RTR_SPLIT_POINT_ENVIRONMENT) && RTR_SPLIT_POINT_ENVIRONMENT
        if (RtrSplitPointEnvironment() && lightIndex < ControlBuffer[0].EnvmapQuadNodeCount)
            feedbackPdf = RtrNativeGlobalPdfForFeedback(lightIndex);
        #endif
        float globalPDFModifier = clamp( feedbackPdf, 1e-6, 1e6 ); // RTR preserve native feedback PDF semantics
'@ 'RTR preserve native feedback PDF semantics'
Patch-Once 'Rtxpt/Shaders/PathTracer/Lighting/LightSampler.hlsli' '        uint packedValue = LocalLightBinarySearch( LocalSamplingBuffer, LocalSamplingTilePos, lightIndex, localProxyCount, RTXPT_LIGHTING_LOCAL_PROXY_BINARY_SEARCH_STEPS );' @'
        #if defined(RTR_SPLIT_POINT_ENVIRONMENT) && RTR_SPLIT_POINT_ENVIRONMENT
        if (RtrSplitPointEnvironment())
        {
            if (lightIndex >= ControlBuffer[0].EnvmapQuadNodeCount) return 0;
            const uint environmentProxyCount = RtrEnvironmentLocalProxyCount();
            if (environmentProxyCount == 0) return 0;
            uint environmentPacked = LocalLightBinarySearch(LocalSamplingBuffer, LocalSamplingTilePos,
                lightIndex, RTXPT_LIGHTING_LOCAL_PROXY_COUNT, RTXPT_LIGHTING_LOCAL_PROXY_BINARY_SEARCH_STEPS);
            if (environmentPacked == NEEAT_INVALID_LIGHT_INDEX) return 0;
            uint environmentIndex, environmentLightProxies;
            NEEAT::UnpackMiniListLightAndCount(environmentPacked, environmentIndex, environmentLightProxies);
            return float(environmentLightProxies) / float(environmentProxyCount);
        }
        #endif
        uint packedValue = LocalLightBinarySearch( LocalSamplingBuffer, LocalSamplingTilePos, lightIndex, localProxyCount, RTXPT_LIGHTING_LOCAL_PROXY_BINARY_SEARCH_STEPS ); // RTR conditional local MIS PDF fallback
'@ 'RTR conditional local MIS PDF'
[IO.File]::WriteAllText((Join-Path $sdk 'Rtxpt/Shaders/PathTracer/RtrSmallPointNee.hlsli'),
    [IO.File]::ReadAllText((Join-Path $repo 'renderer-rtxpt/src/main/cpp/RtrSmallPointNee.hlsli')),$utf8)
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '    inline NEEResult HandleNEE_MultipleSamples(' @'
    #include "RtrSmallPointNee.hlsli" // RTR opt-in exact small-point sum
    inline NEEResult HandleNEE_MultipleSamples(
'@ 'RTR opt-in exact small-point sum'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '                                inout UniformSampleSequenceGenerator sampleGenerator, const WorkingContext workingContext)' @'
                                inout UniformSampleSequenceGenerator sampleGenerator, const WorkingContext workingContext, const bool updateNeeAtFeedback) // RTR feedback policy parameter
'@ 'RTR feedback policy parameter'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '            if( lightSample.LightIndex != NEEAT_INVALID_LIGHT_INDEX && lightSampler.IsTemporalFeedbackRequired() )' @'
            if( updateNeeAtFeedback && lightSample.LightIndex != NEEAT_INVALID_LIGHT_INDEX && lightSampler.IsTemporalFeedbackRequired() ) // RTR exact lights do not train NEE-AT
'@ 'RTR exact lights do not train NEE-AT'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '        [unroll] for (uint sampleIndex = 0; sampleIndex < fullSamples; sampleIndex++)' @'
        // RTR deterministic delta contributions; do not add them again below.
        #if defined(RTR_EXACT_SMALL_POINTS) && RTR_EXACT_SMALL_POINTS
        const bool rtrSmallPoints = RtrSmallPointSet(lightSampler);
        if (rtrSmallPoints)
            RtrSumPointLights(result, lightSampler, shadingData, bsdf, preScatterPath, sampleGenerator, workingContext);
        #endif
        [unroll] for (uint sampleIndex = 0; sampleIndex < fullSamples; sampleIndex++)
'@ 'RTR deterministic delta contributions'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '        lightSampler.GetCandidateSampleCounts(candidateSampleCount, localCount, globalCount);' @'
        // RTR conditional NEE sample budget
        lightSampler.GetCandidateSampleCounts(candidateSampleCount, localCount, globalCount);
        #if defined(RTR_SPLIT_POINT_ENVIRONMENT) && RTR_SPLIT_POINT_ENVIRONMENT
        if (lightSampler.RtrSplitPointEnvironment())
        {
            const uint environmentProxyCount = lightSampler.RtrEnvironmentProxyCount();
            const uint localEnvironmentProxyCount = lightSampler.RtrEnvironmentLocalProxyCount();
            if (localEnvironmentProxyCount == 0) { globalCount += localCount; localCount = 0; }
            else if (environmentProxyCount == 0) { localCount += globalCount; globalCount = 0; }
        }
        #endif
'@ 'RTR conditional NEE sample budget'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '            RtrSumPointLights(result, lightSampler, shadingData, bsdf, preScatterPath, sampleGenerator, workingContext);' @'
            RtrSumPointLights(result, lightSampler, shadingData, bsdf, preScatterPath, sampleGenerator, workingContext);
        #if defined(RTR_SPLIT_POINT_ENVIRONMENT) && RTR_SPLIT_POINT_ENVIRONMENT
        if (rtrSmallPoints && lightSampler.RtrSplitPointEnvironment()
            && lightSampler.RtrEnvironmentProxyCount() == 0 && lightSampler.RtrEnvironmentLocalProxyCount() == 0)
            return result; // RTR no stochastic stratum: avoid zero-sized proxy lookup
        #endif
'@ 'RTR no stochastic stratum'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '            // this computes the BSDF throughput and (if throughput>0) then casts shadow ray and handles radiance summing up & weighted averaging for ''sample distance'' used by denoiser' @'
            #if defined(RTR_EXACT_SMALL_POINTS) && RTR_EXACT_SMALL_POINTS
            if (rtrSmallPoints && lightSample.Valid() && RtrCoveredPoint(lightSampler, lightSample.LightIndex))
                continue; // RTR avoid double-counting exact point contributions
            #endif
            // this computes the BSDF throughput and (if throughput>0) then casts shadow ray and handles radiance summing up & weighted averaging for 'sample distance' used by denoiser
'@ 'RTR avoid double-counting exact point contributions'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '            ProcessLightSample(result, lightSample, candidateSampleCount, fullSamples, localCount, globalCount, shadingData, bsdf, preScatterPath, lightSampler, sampleGenerator, workingContext);' @'
            ProcessLightSample(result, lightSample, candidateSampleCount, fullSamples, localCount, globalCount, shadingData, bsdf, preScatterPath, lightSampler, sampleGenerator, workingContext, true); // RTR sampled candidates train NEE-AT
'@ 'RTR sampled candidates train NEE-AT'
# Keep exact delta points out of both NEE-AT strata and use matching conditional PDFs.
Patch-Once 'Rtxpt/Shaders/PathTracer/Lighting/LightSampler.hlsli' '        globalCount = totalCandidateSamples - localCount;' @'
        globalCount = totalCandidateSamples - localCount;
        #if defined(RTR_EXACT_SMALL_POINTS) && RTR_EXACT_SMALL_POINTS && defined(RTR_CONDITIONAL_POINT_SAMPLING) && RTR_CONDITIONAL_POINT_SAMPLING
        if (RtrSmallPointSet())
        {
            const uint residualGlobal = RtrResidualProxyCount();
            uint residualLocal = 0;
            if (localCount > 0) residualLocal = RtrResidualLocalSlotCount();
            if (residualLocal == 0 && residualGlobal == 0)
            {
                localCount = 0;
                globalCount = 0;
            }
            else
            {
                if (localCount > 0 && residualLocal == 0) { globalCount += localCount; localCount = 0; }
                if (globalCount > 0 && residualGlobal == 0) { localCount += globalCount; globalCount = 0; }
            }
        }
        #endif // RTR residual candidate counts
'@ 'RTR residual candidate counts'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' 'const LightSampler lightSampler)' @'
const LightSampler lightSampler, const bool conditionalPointSampling,
const uint residualGlobalProxyCount, const uint residualLocalSlotCount) // RTR residual sample parameters
'@ 'RTR residual sample parameters'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' @'
            if( sampleIsLocal )
                lightIndex = lightSampler.SampleLocal( rnd, selectionPdf );
            else
                lightIndex = lightSampler.SampleGlobal( rnd, selectionPdf );
'@ @'
            if( sampleIsLocal )
            {
                if (conditionalPointSampling && residualLocalSlotCount < RTXPT_LIGHTING_LOCAL_PROXY_COUNT)
                    lightIndex = lightSampler.RtrSampleResidualLocal(rnd, residualLocalSlotCount, selectionPdf);
                else
                    lightIndex = lightSampler.SampleLocal(rnd, selectionPdf);
            }
            else if (conditionalPointSampling && residualGlobalProxyCount < lightSampler.ControlBuffer[0].SamplingProxyCount)
                lightIndex = lightSampler.RtrSampleResidualGlobal(rnd, residualGlobalProxyCount, selectionPdf);
            else
                lightIndex = lightSampler.SampleGlobal(rnd, selectionPdf);
            // RTR conditional residual selection
'@ 'RTR conditional residual selection'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '            lightSampler.ComputeLightSelectionPdfs(lightSample, localCount, globalCount, thisPdf, otherPdf, thisCount, otherCount);' @'
            bool conditionalPointSampling = false;
            #if defined(RTR_EXACT_SMALL_POINTS) && RTR_EXACT_SMALL_POINTS && defined(RTR_CONDITIONAL_POINT_SAMPLING) && RTR_CONDITIONAL_POINT_SAMPLING
            conditionalPointSampling = lightSampler.RtrSmallPointSet();
            #endif
            lightSampler.RtrComputeLightSelectionPdfs(lightSample, localCount, globalCount, conditionalPointSampling,
                thisPdf, otherPdf, thisCount, otherCount); // RTR matching residual NEE MIS PDFs
'@ 'RTR matching residual NEE MIS PDFs'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '        result.BSDFMISInfo.FullSamples          = fullSamples;' @'
        result.BSDFMISInfo.FullSamples          = fullSamples;
        bool rtrSmallPoints = false;
        bool rtrConditionalPointSampling = false;
        uint rtrResidualGlobalProxyCount = 0;
        uint rtrResidualLocalSlotCount = 0; // RTR residual sampler state
'@ 'RTR residual sampler state'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '        const bool rtrSmallPoints = RtrSmallPointSet(lightSampler);' @'
        rtrSmallPoints = RtrSmallPointSet(lightSampler);
        #if defined(RTR_CONDITIONAL_POINT_SAMPLING) && RTR_CONDITIONAL_POINT_SAMPLING
        rtrConditionalPointSampling = rtrSmallPoints;
        if (rtrConditionalPointSampling)
        {
            rtrResidualGlobalProxyCount = lightSampler.RtrResidualProxyCount();
            rtrResidualLocalSlotCount = lightSampler.RtrResidualLocalSlotCount();
        }
        #endif // RTR residual point activation
'@ 'RTR residual point activation'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '            rtrResidualLocalSlotCount = lightSampler.RtrResidualLocalSlotCount();' @'
            // Power sampling has no local table; never read stale NEE-AT slots.
            if (localCount > 0) rtrResidualLocalSlotCount = lightSampler.RtrResidualLocalSlotCount(); // RTR only active local stratum
'@ 'RTR only active local stratum'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '            RtrSumPointLights(result, lightSampler, shadingData, bsdf, preScatterPath, sampleGenerator, workingContext);' @'
            RtrSumPointLights(result, lightSampler, shadingData, bsdf, preScatterPath, sampleGenerator, workingContext);
        if (rtrConditionalPointSampling && rtrResidualGlobalProxyCount == 0 && rtrResidualLocalSlotCount == 0)
            return result; // RTR every stochastic proxy belongs to an exactly summed point
'@ 'RTR every stochastic proxy belongs to an exactly summed point'
# Repair repeated insertions made by the old marker, which was absent from its
# own replacement. Keep the generated shader stable across repeated builds.
$neePath=Join-Path $sdk 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli'
$neeText=[IO.File]::ReadAllText($neePath)
$emptyResidual='        if \(rtrConditionalPointSampling && rtrResidualGlobalProxyCount == 0 && rtrResidualLocalSlotCount == 0\)\r?\n            return result; // RTR every stochastic proxy belongs to an exactly summed point'
$neeText=[regex]::Replace($neeText,'('+ $emptyResidual +')(?:\r?\n'+ $emptyResidual +')+','$1')
[IO.File]::WriteAllText($neePath,$neeText,$utf8)
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' 'GenerateLightSample(workingContext, shadingData, bsdf, candidateSampleCount, localCount, globalCount, sampleGenerator, lightSampler);' @'
GenerateLightSample(workingContext, shadingData, bsdf, candidateSampleCount, localCount, globalCount,
                sampleGenerator, lightSampler, rtrConditionalPointSampling, rtrResidualGlobalProxyCount, rtrResidualLocalSlotCount); // RTR residual sample call
'@ 'RTR residual sample call'
Patch-Once 'Rtxpt/Shaders/PathTracer/Lighting/LightSampler.hlsli' '        float globalPdf = SampleGlobalPDF(lightIndex);' @'
        bool conditionalPointSampling = false;
        #if defined(RTR_EXACT_SMALL_POINTS) && RTR_EXACT_SMALL_POINTS && defined(RTR_CONDITIONAL_POINT_SAMPLING) && RTR_CONDITIONAL_POINT_SAMPLING
        conditionalPointSampling = RtrSmallPointSet();
        #endif
        float globalPdf = RtrSampleGlobalPDF(lightIndex, conditionalPointSampling); // RTR matching residual BSDF global PDF
'@ 'RTR matching residual BSDF global PDF'
Patch-Once 'Rtxpt/Shaders/PathTracer/Lighting/LightSampler.hlsli' '        float localPdf = (localCount>0)?(SampleLocalPDF(lightIndex)):(0);' @'
        float localPdf = (localCount>0)?(RtrSampleLocalPDF(lightIndex, conditionalPointSampling)):(0); // RTR matching residual BSDF local PDF
'@ 'RTR matching residual BSDF local PDF'
$feedbackSource=Join-Path $sdk 'Rtxpt/Shaders/PathTracer/Lighting/LightSampler.hlsli'
$feedbackText=[IO.File]::ReadAllText($feedbackSource)
$feedbackBlocks=[regex]::Matches($feedbackText,'(?m)^[ \t]*#if defined\(RTR_EXACT_SMALL_POINTS\)[^\r\n]*\r?\n[ \t]*if \(RtrSmallPointSet\(\)\) feedbackPdf = RtrNativeGlobalPdfForFeedback\(lightIndex\);\r?\n[ \t]*#endif // RTR restore native NEE-AT feedback weights\r?\n')
if($feedbackBlocks.Count -gt 1){
    for($i=$feedbackBlocks.Count-1;$i -ge 1;$i--){$feedbackText=$feedbackText.Remove($feedbackBlocks[$i].Index,$feedbackBlocks[$i].Length)}
    [IO.File]::WriteAllText($feedbackSource,$feedbackText,$utf8)
}
Patch-Once 'Rtxpt/Shaders/PathTracer/Lighting/LightSampler.hlsli' '        float feedbackPdf = globalPdf;' @'
        float feedbackPdf = globalPdf;
        #if defined(RTR_EXACT_SMALL_POINTS) && RTR_EXACT_SMALL_POINTS && defined(RTR_CONDITIONAL_POINT_SAMPLING) && RTR_CONDITIONAL_POINT_SAMPLING
        if (RtrSmallPointSet()) feedbackPdf = RtrNativeGlobalPdfForFeedback(lightIndex);
        #endif // RTR restore native NEE-AT feedback weights
'@ 'RTR restore native NEE-AT feedback weights'
# DirectionalLight::CalcSample returns a to-light direction, not a world position.
# Match the pinned upstream IntroRTXDIBridge::RAB_GetLightDirDistance convention.
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '            candidateSample.Direction = surfToLight / max( candidateSample.Distance, 1e-7 );' @'
            candidateSample.Direction = surfToLight / max( candidateSample.Distance, 1e-7 );
            // RTR directional samples carry directions, not world positions.
            #if !defined(RTR_ORIGINAL_DIRECTIONAL_POSITION)
            if (PolymorphicLight::DecodeType(packedLightInfo) == PolymorphicLightType::kDirectional)
            {
                candidateSample.Direction = lightSample.Position;
                candidateSample.Distance = 1e6;
            }
            #endif
'@ 'RTR directional samples carry directions'
# Match the Vulkan route's summed continuous surface response without deleting
# Full scene lighting-input parity uses the same scene values at every receiver.
foreach($header in @('RtrSceneLightingInputs.hlsli','RtrSceneDirect.hlsli','RtrSparseDirect.hlsli','RtrSceneTransportTrace.hlsli','RtrSceneReplayCache.hlsli')) {
    $headerText=[IO.File]::ReadAllText((Join-Path $repo ('renderer-rtxpt/src/main/cpp/'+$header)))
    if($header -eq 'RtrSceneTransportTrace.hlsli' -and $OpticalTransportTrace) {
        $headerText="#define RTR_SCENE_TRANSPORT_TRACE 1 // explicitly enabled by configure`r`n"+$headerText
    }
    if($header -eq 'RtrSceneTransportTrace.hlsli' -and $OpticalWorkTrace) {
        $headerText="#define RTR_SCENE_WORK_TRACE 1`r`n"+$headerText
    }
    if($header -eq 'RtrSceneTransportTrace.hlsli' -and $OpticalBranchSeedXor -ne 0) {
        if(!$OpticalTransportTrace){throw 'Optical branch seed control requires explicit transport diagnostics'}
        $headerText="#define RTR_SCENE_OPTICAL_BRANCH_SEED_XOR $($OpticalBranchSeedXor)u // diagnostic control only`r`n"+$headerText
    }
    [IO.File]::WriteAllText((Join-Path $sdk ('Rtxpt/Shaders/PathTracer/'+$header)),$headerText,$utf8)
}
# Scene lighting performs cross-material queries inside a receiver shader. Do
# not let future material-specific compile constants discard another hit's data.
Patch-Once 'Rtxpt/Shaders/PathTracer/Config.h' '#define __CONFIG_H__' @'
#define __CONFIG_H__
#if defined(RTR_SCENE_LIGHTING) && RTR_SCENE_LIGHTING
#undef RTXPT_MATERIAL_HAS_TRANSMISSION
#undef RTXPT_MATERIAL_USE_NORMAL_TEXTURE
#endif // RTR cross-material lighting queries require runtime material data
'@ 'RTR cross-material lighting queries require runtime material data'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerShared.h' '    PathTracerCameraData camera;' @'
    float4  RtrSceneAmbient;
    float4  RtrSceneSky;
    uint    RtrSceneLightCount;
    uint3   RtrScenePadding; // RTR complete scene lighting inputs
    PathTracerCameraData camera;
'@ 'RTR complete scene lighting inputs'
Patch-Once 'Rtxpt/Shaders/Bindings/ShaderResourceBindings.hlsli' '#endif // #ifndef __SHADER_RESOURCE_BINDINGS_HLSLI__' @'
struct RtrSceneLight { float4 PositionType; float4 DirectionRange; float4 ColorIntensity; float4 ConeShadow; };
StructuredBuffer<RtrSceneLight> t_RtrSceneLights : register(t96); // RTR original API light records
#endif // #ifndef __SHADER_RESOURCE_BINDINGS_HLSLI__
'@ 'RTR original API light records'
Patch-Once 'Rtxpt/Sample.cpp' '    globalBindingLayoutDesc.bindings.push_back(nvrhi::BindingLayoutItem::Texture_SRV(95));' @'
    globalBindingLayoutDesc.bindings.push_back(nvrhi::BindingLayoutItem::StructuredBuffer_SRV(96)); // RTR original lighting buffer layout
    globalBindingLayoutDesc.bindings.push_back(nvrhi::BindingLayoutItem::Texture_SRV(95));
'@ 'RTR original lighting buffer layout'
Patch-Once 'Rtxpt/Sample.cpp' '    globalBindingLayoutDesc.bindings.push_back(nvrhi::BindingLayoutItem::StructuredBuffer_SRV(96)); // RTR original lighting buffer layout' @'
    globalBindingLayoutDesc.bindings.push_back(nvrhi::BindingLayoutItem::StructuredBuffer_SRV(96)); // RTR original lighting buffer layout
    if(const char* stats=std::getenv("RTR_RTXPT_LIGHT_STATS");stats&&stats[0]=='1')
        globalBindingLayoutDesc.bindings.push_back(nvrhi::BindingLayoutItem::RawBuffer_UAV(96)); // RTR actual light work counters
'@ 'RTR actual light work counters'
Patch-Once 'Rtxpt/Sample.cpp' '        bindingSetDesc.bindings.push_back(nvrhi::BindingSetItem::Texture_SRV(95, m_CommonPasses->m_BlackTexture));' @'
        bindingSetDesc.bindings.push_back(nvrhi::BindingSetItem::StructuredBuffer_SRV(96, m_lightsBaker->GetLightBuffer())); // RTR original lighting buffer placeholder
        bindingSetDesc.bindings.push_back(nvrhi::BindingSetItem::Texture_SRV(95, m_CommonPasses->m_BlackTexture));
'@ 'RTR original lighting buffer placeholder'
Patch-Once 'Rtxpt/Shaders/PathTracer/BSDFs/StandardBSDF.hlsli' '#endif // __STANDARD_BSDF_HLSLI__' @'
#if defined(RTR_SCENE_LIGHTING) && RTR_SCENE_LIGHTING
#include "../RtrSceneLightingInputs.hlsli" // RTR scene sampling and sky input
#endif
#endif // __STANDARD_BSDF_HLSLI__
'@ 'RTR scene sampling and sky input'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracer.hlsli' '#include "PathTracerTypes.hlsli"' @'
#include "PathTracerTypes.hlsli"
#if defined(RTR_SCENE_LIGHTING) && RTR_SCENE_LIGHTING
#include "RtrSceneTransportTrace.hlsli" // RTR shared trace in raygen and hit shaders
#endif
'@ 'RTR shared trace in raygen and hit shaders'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '    inline NEEResult HandleNEE(const PathState preScatterPath,' @'
    #if defined(RTR_SCENE_LIGHTING) && RTR_SCENE_LIGHTING
    #include "RtrSceneDirect.hlsli" // RTR scene direct shading at every receiver
    #endif
    inline NEEResult HandleNEE(const PathState preScatterPath,
'@ 'RTR scene direct shading at every receiver'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '        LightSampler lightSampler = Bridge::CreateLightSampler( preScatterPath.GetPixelPos(), preScatterPath.rayCone.getWidth(), preScatterPath.GetSceneLength() );' @'
        #if defined(RTR_SCENE_LIGHTING) && RTR_SCENE_LIGHTING
        return RtrSceneDirect(preScatterPath,shadingData,bsdf,workingContext); // RTR replace complete stochastic light input
        #endif
        LightSampler lightSampler = Bridge::CreateLightSampler( preScatterPath.GetPixelPos(), preScatterPath.rayCone.getWidth(), preScatterPath.GetSceneLength() );
'@ 'RTR replace complete stochastic light input'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerHandleHit.hlsli' '        // only ActiveBSDF::cRandomNumberCountForSampling are actually used;' @'
        #if defined(RTR_SCENE_LIGHTING) && RTR_SCENE_LIGHTING
        BSDFSample sceneSample=(BSDFSample)0;bool sceneValid=false;
        if(bsdf.data.SpecularTransmission()>0)
        {
            #if PATH_TRACER_MODE==PT_DENOISING_LAYERS_FILL
            if(path.getVertexIndex()==1)
            {
                // gsEvaluateSecondaryTransport traces both first-interface branches.
                // PSD queues alone contain guides, not completed lighting paths.
                path.setCounter((PackedCounters)3,path.getCounter((PackedCounters)3)|4u);
                DeltaLobe lobes[cMaxDeltaLobes];uint count;float nonDeltaPart;
                RtrSceneFirstInterface(shadingData,bsdf.data,path.GetPixelPos(),workingContext.PtConsts.frameIndex,g_MiniConst.params.x,
                    uint(max(1.0,rcp(workingContext.PtConsts.invSubSampleCount))),lobes,count,nonDeltaPart);
                uint selected=2u-(path.getCounter((PackedCounters)3)&3u);
                sceneSample=(BSDFSample)0;sceneSample.wo=lobes[selected].dir;sceneSample.weight=lobes[selected].thp;
                sceneSample.lobe=(uint)(selected==0?LobeType::DeltaTransmission:LobeType::DeltaReflection);
                sceneSample.lobeP=lobes[selected].probability;
                sceneValid=any(sceneSample.weight>0);
            }
            else
            #endif
                sceneValid=RtrSceneOpticalSample(shadingData,bsdf.data,path.GetPixelPos(),workingContext.PtConsts.frameIndex,
                    path.getVertexIndex(),max(1u,path.getCounter((PackedCounters)3)&3u),sceneSample);
        }
        else if(path.getVertexIndex()==1)
            sceneValid=RtrSceneMetalSample(shadingData,bsdf.data,path.GetPixelPos(),g_MiniConst.params.x,
                uint(max(1.0,rcp(workingContext.PtConsts.invSubSampleCount))),sceneSample);
        return sceneValid && GenerateScatterRay(sceneSample,shadingData,bsdf,path,workingContext); // RTR scene optical continuation
        #endif
        // only ActiveBSDF::cRandomNumberCountForSampling are actually used;
'@ 'RTR scene optical continuation'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerHandleHit.hlsli' '        return sceneValid && GenerateScatterRay(sceneSample,shadingData,bsdf,path,workingContext); // RTR scene optical continuation' @'
        #if defined(RTR_SCENE_TRANSPORT_TRACE) && RTR_SCENE_TRANSPORT_TRACE
        if(RtrSceneTracePixel(path.GetPixelPos(),workingContext.PtConsts.frameIndex) && ((path.getCounter((PackedCounters)3)&4u)!=0 || bsdf.data.SpecularTransmission()>0))
        {
            RtrSceneTracePrint("scatter",100u,RtrSceneTraceKey(path.GetPixelPos(),(path.getCounter((PackedCounters)3)&3u)-1u,path.getVertexIndex(),workingContext.PtConsts.frameIndex),
                float4(shadingData.MaterialID(),shadingData.frontFacing,shadingData.GetEta(),sceneValid),path.GetThp(),shadingData.N,sceneSample.wo,sceneSample.weight,sceneSample.lobe);
        }
        #endif // RTR optional optical transport trace
        return sceneValid && GenerateScatterRay(sceneSample,shadingData,bsdf,path,workingContext); // RTR scene optical continuation
'@ 'RTR optional optical transport trace'
$rtrTracePath=Join-Path $sdk 'Rtxpt/Shaders/PathTracer/PathTracerHandleHit.hlsli'
$rtrTraceText=[IO.File]::ReadAllText($rtrTracePath)
$rtrTraceText=$rtrTraceText.Replace('uint4(path.GetPixelPos(),(path.getCounter((PackedCounters)3)&3u)-1u,path.getVertexIndex())','RtrSceneTraceKey(path.GetPixelPos(),(path.getCounter((PackedCounters)3)&3u)-1u,path.getVertexIndex(),workingContext.PtConsts.frameIndex)')
$rtrTraceText=$rtrTraceText.Replace('BSDFSample sceneSample;bool sceneValid=false;','BSDFSample sceneSample=(BSDFSample)0;bool sceneValid=false;')
$rtrTraceText=$rtrTraceText.Replace('(path.getCounter((PackedCounters)3)>0 || bsdf.data.SpecularTransmission()>0)', '((path.getCounter((PackedCounters)3)&4u)!=0 || bsdf.data.SpecularTransmission()>0)')
$rtrTraceText=$rtrTraceText.Replace('DebugPrint("RTR_TRANSPORT','RtrSceneTracePrint("RTR_TRANSPORT')
$rtrTraceText=[regex]::Replace($rtrTraceText,'(?s)            RtrSceneTracePrint\("RTR_TRANSPORT scatter.*?sceneSample\.lobe\);',@'
            RtrSceneTracePrint("scatter",100u,RtrSceneTraceKey(path.GetPixelPos(),(path.getCounter((PackedCounters)3)&3u)-1u,path.getVertexIndex(),workingContext.PtConsts.frameIndex),
                float4(shadingData.MaterialID(),shadingData.frontFacing,shadingData.GetEta(),sceneValid),path.GetThp(),shadingData.N,sceneSample.wo,sceneSample.weight,sceneSample.lobe);
'@)
[IO.File]::WriteAllText($rtrTracePath,$rtrTraceText,$utf8)
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerDenoisingLayersHandleHit.hlsli' '            surface.bsdf.evalDeltaLobes(surface.shadingData, deltaLobes, deltaLobeCount, nonDeltaPart);' @'
            #if defined(RTR_SCENE_LIGHTING) && RTR_SCENE_LIGHTING
            if(vertexIndex==1 && surface.bsdf.data.SpecularTransmission()>0)
            {
                deltaLobeCount=0;nonDeltaPart=1; // RTR first interface stays the fill starting point
            }
            else
            #endif
            surface.bsdf.evalDeltaLobes(surface.shadingData, deltaLobes, deltaLobeCount, nonDeltaPart);
'@ 'RTR first interface stays the fill starting point'
# Older integrations used a marker absent from the replacement, nesting this
# conditional once per configure. Keep one copy, including in existing SDKs.
$rtrLayerPath=Join-Path $sdk 'Rtxpt/Shaders/PathTracer/PathTracerDenoisingLayersHandleHit.hlsli'
$rtrLayerText=[IO.File]::ReadAllText($rtrLayerPath)
$rtrLayerBlock=@'
            #if defined(RTR_SCENE_LIGHTING) && RTR_SCENE_LIGHTING
            if(vertexIndex==1 && surface.bsdf.data.SpecularTransmission()>0)
            {
                deltaLobeCount=0;nonDeltaPart=1; // RTR first interface stays the fill starting point
            }
            else
            #endif
'@
$rtrLayerBlock=[regex]::Replace($rtrLayerBlock,"`r?`n","`r`n")+"`r`n"
while($rtrLayerText.Contains($rtrLayerBlock+$rtrLayerBlock)) {
    $rtrLayerText=$rtrLayerText.Replace($rtrLayerBlock+$rtrLayerBlock,$rtrLayerBlock)
}
[IO.File]::WriteAllText($rtrLayerPath,$rtrLayerText,$utf8)
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracer.hlsli' '    workingContext.DenoisingLayers.CommitDenoiserRadiance(path);' @'
    #if defined(RTR_SCENE_TRANSPORT_TRACE) && RTR_SCENE_TRANSPORT_TRACE
    float3 rtrBeforeCommit=0;
    bool rtrTraceCommit=RtrSceneTracePixel(path.GetPixelPos(),workingContext.PtConsts.frameIndex) && (path.getCounter((PackedCounters)3)&4u)!=0;
    if(rtrTraceCommit)rtrBeforeCommit=workingContext.DenoisingLayers.GetAllRadiance(path.GetPixelPos());
    #endif
    workingContext.DenoisingLayers.CommitDenoiserRadiance(path);
    #if defined(RTR_SCENE_TRANSPORT_TRACE) && RTR_SCENE_TRANSPORT_TRACE
    if(rtrTraceCommit)
        RtrSceneTracePrint("commit",102u,RtrSceneTraceKey(path.GetPixelPos(),(path.getCounter((PackedCounters)3)&3u)-1u,path.getVertexIndex(),workingContext.PtConsts.frameIndex),
            path.getDenoisingLayerIndex(),rtrBeforeCommit,workingContext.DenoisingLayers.GetAllRadiance(path.GetPixelPos()));
    #endif // RTR optional final optical radiance trace
'@ 'RTR optional final optical radiance trace'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracer.hlsli' '                path.SetL( L + newL );' @'
                path.SetL( L + newL );
                #if defined(RTR_SCENE_TRANSPORT_TRACE) && RTR_SCENE_TRANSPORT_TRACE
                if(RtrSceneTracePixel(path.GetPixelPos(),workingContext.PtConsts.frameIndex) && (path.getCounter((PackedCounters)3)&4u)!=0)
                    RtrSceneTracePrint("accumulate",107u,RtrSceneTraceKey(path.GetPixelPos(),(path.getCounter((PackedCounters)3)&3u)-1u,path.getVertexIndex(),workingContext.PtConsts.frameIndex),
                        float4(radiance,specularRadianceAvg),L,newL,path.GetL(),uint2(path.flagsAndVertexIndex,path.packedCounters));
                #endif // RTR optical contribution before and after accumulation
'@ 'RTR optical contribution before and after accumulation'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracer.hlsli' '        SharcHandleMiss(environmentEmission);' @'
        #if defined(RTR_SCENE_TRANSPORT_TRACE) && RTR_SCENE_TRANSPORT_TRACE
        if(RtrSceneTracePixel(path.GetPixelPos(),workingContext.PtConsts.frameIndex) && (path.getCounter((PackedCounters)3)&4u)!=0)
            RtrSceneTracePrint("miss",108u,RtrSceneTraceKey(path.GetPixelPos(),(path.getCounter((PackedCounters)3)&3u)-1u,path.getVertexIndex(),workingContext.PtConsts.frameIndex),
                rayDir,path.GetThp(),float3(environmentEmission),path.GetL(),uint2(path.flagsAndVertexIndex,path.packedCounters));
        #endif // RTR optical miss emission evidence
        SharcHandleMiss(environmentEmission);
'@ 'RTR optical miss emission evidence'
Patch-Once 'Rtxpt/ProcessingPasses/PostProcess.hlsl' '#include "../Shaders/Libraries/ShaderDebug/ShaderDebug.hlsl"' @'
#include "../Shaders/Libraries/ShaderDebug/ShaderDebug.hlsl"
#include "../Shaders/PathTracer/RtrSceneTransportTrace.hlsli" // RTR final merge trace
'@ 'RTR final merge trace'
Patch-Once 'Rtxpt/ProcessingPasses/PostProcess.hlsl' '    u_OutputColor[pixelPos] = float4(denoisingLayers.GetAllRadiance(pixelPos), 1.0);' @'
    u_OutputColor[pixelPos] = float4(denoisingLayers.GetAllRadiance(pixelPos), 1.0);
    #if defined(RTR_SCENE_TRANSPORT_TRACE) && RTR_SCENE_TRANSPORT_TRACE
    if(g_Const.ptConsts.RtrSceneLightCount>0 && RtrSceneTracePixel(pixelPos,g_Const.ptConsts.frameIndex))
        RtrSceneTracePrint("merge",109u,RtrSceneTraceKey(pixelPos,0,0,g_Const.ptConsts.frameIndex),
            denoisingLayers.GetAllRadiance(pixelPos),u_OutputColor[pixelPos],g_Const.ptConsts.invSubSampleCount);
    #endif // RTR final radiance at SR input
'@ 'RTR final radiance at SR input'
Patch-Once 'Rtxpt/ProcessingPasses/PostProcess.hlsl' '    #endif // RTR final radiance at SR input' @'
    if(g_Const.ptConsts.RtrSceneLightCount>0 && RtrSceneTracePixel(pixelPos,g_Const.ptConsts.frameIndex))
        RtrSceneTracePrint("reconstruction-input",113u,RtrSceneTraceKey(pixelPos,0,0,g_Const.ptConsts.frameIndex),
            float4(u_Depth[pixelPos],denoisingLayers.LoadFirstHitRayLength(pixelPos),denoisingLayers.LoadDominantIndex(pixelPos),g_Const.ptConsts.camera.ApertureRadius),
            u_MotionVectors[pixelPos],float4(g_Const.ptConsts.camera.Jitter,g_Const.ptConsts.perPixelJitterAAScale,g_Const.ptConsts.invSubSampleCount),
            cameraRay.dir,u_OutputColor[pixelPos]); // RTR optical reconstruction input evidence
    #endif // RTR final radiance at SR input
'@ 'RTR optical reconstruction input evidence'
Patch-Once 'Rtxpt/ProcessingPasses/PostProcess.hlsl' '    u_OutputColor[pixelPos] = float4(denoisingLayers.GetAllRadiance(pixelPos), 1.0);' @'
    u_OutputColor[pixelPos] = float4(denoisingLayers.GetAllRadiance(pixelPos), 1.0);
    #if defined(RTR_SCENE_TRANSPORT_TRACE) && RTR_SCENE_TRANSPORT_TRACE
    if(all(pixelPos==uint2(0,0)))
        RtrSceneTracePrint("merge-stage",110u,uint4(g_Const.ptConsts.frameIndex,g_Const.ptConsts.imageWidth,g_Const.ptConsts.imageHeight,g_Const.ptConsts.RtrSceneLightCount),
            u_OutputColor[pixelPos],g_Const.ptConsts.invSubSampleCount);
    #endif // RTR verify final merge invocation and constants
'@ 'RTR verify final merge invocation and constants'
Patch-Once 'Rtxpt/Sample.cpp' 'void Sample::CloseShaderDebug()' @'
static bool RtrLateOpticalTrace()
{
    static const bool enabled=[] {
        const char* value=std::getenv("RTR_RTXPT_DIAGNOSTIC_OPTICAL_TRACE");
        return value && value[0]=='1' && value[1]=='\0';
    }();
    return enabled;
} // RTR diagnostic-only debug lifetime through final merge

void Sample::CloseShaderDebug()
'@ 'RTR diagnostic-only debug lifetime through final merge'
Patch-Once 'Rtxpt/Sample.cpp' '    const bool asyncPrefix = ShouldRecordPrefixAsync(needNewPasses);' @'
    const bool asyncPrefix = !RtrLateOpticalTrace() && ShouldRecordPrefixAsync(needNewPasses); // RTR trace owns one queue through readback
'@ 'RTR trace owns one queue through readback'
Patch-Once 'Rtxpt/Sample.cpp' @'
        CloseShaderDebug();

        if (asyncPrefix)
'@ @'
        if(!RtrLateOpticalTrace())CloseShaderDebug(); // RTR defer optical trace readback past final merge

        if (asyncPrefix)
'@ 'RTR defer optical trace readback past final merge'
Patch-Once 'Rtxpt/Sample.cpp' '        PostProcessAA(framebuffer, needNewPasses || m_ui.ResetRealtimeCaches);' @'
        PostProcessAA(framebuffer, needNewPasses || m_ui.ResetRealtimeCaches);
        if(RtrLateOpticalTrace())CloseShaderDebug(); // RTR include final merge records in readback
'@ 'RTR include final merge records in readback'
$rtrCommitPath=Join-Path $sdk 'Rtxpt/Shaders/PathTracer/PathTracer.hlsli'
$rtrCommitText=[IO.File]::ReadAllText($rtrCommitPath)
$rtrCommitText=[regex]::Replace($rtrCommitText,
    '#if defined\(RTR_SCENE_TRANSPORT_TRACE\) && RTR_SCENE_TRANSPORT_TRACE(?! && PATH_TRACER_MODE!=PT_DENOISING_LAYERS_BUILD)',
    '#if defined(RTR_SCENE_TRANSPORT_TRACE) && RTR_SCENE_TRANSPORT_TRACE && PATH_TRACER_MODE!=PT_DENOISING_LAYERS_BUILD')
$rtrCommitText=$rtrCommitText.Replace('uint4(path.GetPixelPos(),(path.getCounter((PackedCounters)3)&3u)-1u,path.getVertexIndex())','RtrSceneTraceKey(path.GetPixelPos(),(path.getCounter((PackedCounters)3)&3u)-1u,path.getVertexIndex(),workingContext.PtConsts.frameIndex)')
[IO.File]::WriteAllText($rtrCommitPath,$rtrCommitText,$utf8)
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerTypes.hlsli' '        RWTexture2D<float4>     OutputColor;' @'
        #if defined(RTR_SCENE_LIGHTING) && RTR_SCENE_LIGHTING
        uint RtrSceneFirstBranch; // RTR actual first-interface branch selector
        #endif
        RWTexture2D<float4>     OutputColor;
'@ 'RTR actual first-interface branch selector'
Patch-Once 'Rtxpt/Shaders/PathTracerBridgeDonut.hlsli' '    ret.PtConsts = g_Const.ptConsts;' @'
    #if defined(RTR_SCENE_LIGHTING) && RTR_SCENE_LIGHTING
    ret.RtrSceneFirstBranch=0; // RTR default branch context
    #endif
    ret.PtConsts = g_Const.ptConsts;
'@ 'RTR default branch context'
Patch-Once 'Rtxpt/Shaders/PathTracerSample.hlsl' '    //float3 lastOrigin = float3(1e30f,1e30f,1e30f);' @'
    #if defined(RTR_SCENE_LIGHTING) && RTR_SCENE_LIGHTING && PATH_TRACER_MODE==PT_DENOISING_LAYERS_FILL
    [loop] for(uint rtrFirstBranch=0;rtrFirstBranch<2;rtrFirstBranch++) // RTR trace both real dielectric branches
    {
    #endif
    //float3 lastOrigin = float3(1e30f,1e30f,1e30f);
'@ 'RTR exact first two dielectric junctions'
Patch-Once 'Rtxpt/Shaders/PathTracerSample.hlsl' '    PathTracer::WorkingContext workingContext = Bridge::GetWorkingContext();' @'
    PathTracer::WorkingContext workingContext = Bridge::GetWorkingContext();
    #if defined(RTR_SCENE_LIGHTING) && RTR_SCENE_LIGHTING && PATH_TRACER_MODE==PT_DENOISING_LAYERS_FILL
    path.setCounter((PackedCounters)3,rtrFirstBranch+1u); // RTR first-interface selector in ray payload
    #endif
'@ 'RTR two-junction selectors in ray payload'
Patch-Once 'Rtxpt/Shaders/PathTracerSample.hlsl' '    PathTracer::CommitPixel( path, workingContext );' @'
    PathTracer::CommitPixel( path, workingContext );
    #if defined(RTR_SCENE_LIGHTING) && RTR_SCENE_LIGHTING && PATH_TRACER_MODE==PT_DENOISING_LAYERS_FILL
    // Counter 3 is unused upstream. Only first-interface dielectrics set it;
    // opaque pixels keep their single path and never repeat direct lighting.
    if((path.getCounter((PackedCounters)3)&4u)==0)break;
    } // RTR first-interface paths committed and summed
    #endif
'@ 'RTR first-interface paths committed and summed'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerHandleHit.hlsli' '        if (!hasSharcCachedRadiance && any(surfaceEmission>0))' @'
        #if defined(RTR_SCENE_LIGHTING) && RTR_SCENE_LIGHTING
        if(path.getVertexIndex()==1 && (path.getCounter((PackedCounters)3)&3u)==2u)surfaceEmission=0; // RTR shared primary emission once
        #endif
        if (!hasSharcCachedRadiance && any(surfaceEmission>0))
'@ 'RTR shared primary emission once'
# Migrate already-patched SDKs. WorkingContext is recreated by closest-hit;
# only PathPayload survives SER_INVOKE_HIT/TraceRay shader boundaries.
$rtrPayloadMigrations=@{
    'Rtxpt/Shaders/PathTracer/PathTracerHandleHit.hlsli'=@(
        @('path.setCounter((PackedCounters)3,workingContext.RtrSceneFirstBranch+1);','path.setCounter((PackedCounters)3,path.getCounter((PackedCounters)3)|4u);'),
        @('uint selected=1-workingContext.RtrSceneFirstBranch;','uint selected=2u-(path.getCounter((PackedCounters)3)&3u);'),
        @('max(1u,path.getCounter((PackedCounters)3)),sceneSample);','max(1u,path.getCounter((PackedCounters)3)&3u),sceneSample);'),
        @('workingContext.RtrSceneFirstBranch>0','(path.getCounter((PackedCounters)3)&3u)==2u'),
        @('uint4(path.GetPixelPos(),workingContext.RtrSceneFirstBranch,path.getVertexIndex())','RtrSceneTraceKey(path.GetPixelPos(),(path.getCounter((PackedCounters)3)&3u)-1u,path.getVertexIndex(),workingContext.PtConsts.frameIndex)'))
    'Rtxpt/Shaders/PathTracerSample.hlsl'=@(
        @('workingContext.RtrSceneFirstBranch=rtrFirstBranch; // RTR first-interface selector','path.setCounter((PackedCounters)3,rtrFirstBranch+1u); // RTR first-interface selector in ray payload'),
        @('if(path.getCounter((PackedCounters)3)==0)break;','if((path.getCounter((PackedCounters)3)&4u)==0)break;'))
}
foreach($relative in $rtrPayloadMigrations.Keys) {
    $path=Join-Path $sdk $relative;$text=[IO.File]::ReadAllText($path)
    foreach($pair in $rtrPayloadMigrations[$relative]){$text=$text.Replace($pair[0],$pair[1])}
    [IO.File]::WriteAllText($path,$text,$utf8)
}
Patch-Once 'Rtxpt/Shaders/PathTracerSample.hlsl' '    [loop] for(uint rtrFirstBranch=0;rtrFirstBranch<2;rtrFirstBranch++) // RTR trace both real dielectric branches' @'
    [loop] for(uint rtrOpticalReplay=0;rtrOpticalReplay<4;rtrOpticalReplay++) // RTR exact first two dielectric junctions
'@ 'RTR exact first two dielectric junctions'
Patch-Once 'Rtxpt/Shaders/PathTracerSample.hlsl' '    path.setCounter((PackedCounters)3,rtrFirstBranch+1u); // RTR first-interface selector in ray payload' @'
    path.setCounter((PackedCounters)3,((rtrOpticalReplay>>1u)+1u)|((rtrOpticalReplay&1u)<<3u)); // RTR two-junction selectors in ray payload
'@ 'RTR two-junction selectors in ray payload'
Patch-Once 'Rtxpt/Shaders/PathTracerSample.hlsl' '    if((path.getCounter((PackedCounters)3)&4u)==0)break;' @'
    if((path.getCounter((PackedCounters)3)&4u)==0)break;
    if((path.getCounter((PackedCounters)3)&16u)==0)rtrOpticalReplay++; // RTR skip absent second junction, no duplicated energy
'@ 'RTR skip absent second junction, no duplicated energy'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerHandleHit.hlsli' @'
                sceneValid=RtrSceneOpticalSample(shadingData,bsdf.data,path.GetPixelPos(),workingContext.PtConsts.frameIndex,
                    path.getVertexIndex(),max(1u,path.getCounter((PackedCounters)3)&3u),sceneSample);
'@ @'
            {
                #if PATH_TRACER_MODE==PT_DENOISING_LAYERS_FILL
                if(path.getVertexIndex()==2 && (path.getCounter((PackedCounters)3)&4u)!=0)
                    path.setCounter((PackedCounters)3,path.getCounter((PackedCounters)3)|16u);
                #endif
                sceneValid=RtrSceneOpticalSample(shadingData,bsdf.data,path.GetPixelPos(),workingContext.PtConsts.frameIndex,
                    path.getVertexIndex(),path.getCounter((PackedCounters)3),sceneSample); // RTR exact second-interface conditional expectation
            }
'@ 'RTR exact second-interface conditional expectation'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerHandleHit.hlsli' '        if(path.getVertexIndex()==1 && (path.getCounter((PackedCounters)3)&3u)==2u)surfaceEmission=0; // RTR shared primary emission once' @'
        if((path.getVertexIndex()==1 && (path.getCounter((PackedCounters)3)&3u)==2u)
            || (path.getVertexIndex()<=2 && (path.getCounter((PackedCounters)3)&8u)!=0))surfaceEmission=0; // RTR shared replay prefix emission once
'@ 'RTR shared replay prefix emission once'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerHandleHit.hlsli' '        if (!scatterValid)' @'
        #if defined(RTR_SCENE_TRANSPORT_TRACE) && RTR_SCENE_TRANSPORT_TRACE && PATH_TRACER_MODE!=PT_DENOISING_LAYERS_BUILD
        if(RtrSceneTracePixel(path.GetPixelPos(),workingContext.PtConsts.frameIndex) && (path.getCounter((PackedCounters)3)&4u)!=0)
            RtrSceneTracePrint("termination",111u,RtrSceneTraceKey(path.GetPixelPos(),RtrSceneTraceBranch(path.getCounter((PackedCounters)3)),path.getVertexIndex(),workingContext.PtConsts.frameIndex),
                float4(scatterValid,HasFinishedSurfaceBounces(path.getVertexIndex()+1,path.getCounter(PackedCounters::DiffuseBounces)),bsdf.data.SpecularTransmission(),bsdf.data.Roughness()),
                path.GetThp(),path.GetL(),path.GetDir(),uint2(path.flagsAndVertexIndex,path.packedCounters));
        #endif // RTR complete scatter rejection and budget evidence
        if (!scatterValid)
'@ 'RTR complete scatter rejection and budget evidence'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerHandleHit.hlsli' '        if (pathStopping)' @'
        #if defined(RTR_SCENE_TRANSPORT_TRACE) && RTR_SCENE_TRANSPORT_TRACE && PATH_TRACER_MODE!=PT_DENOISING_LAYERS_BUILD
        if(pathStopping && RtrSceneTracePixel(path.GetPixelPos(),workingContext.PtConsts.frameIndex) && (path.getCounter((PackedCounters)3)&4u)!=0)
            RtrSceneTracePrint("terminal",112u,RtrSceneTraceKey(path.GetPixelPos(),RtrSceneTraceBranch(path.getCounter((PackedCounters)3)),path.getVertexIndex(),workingContext.PtConsts.frameIndex),
                float4(shadingData.MaterialID(),shadingData.frontFacing,shadingData.GetEta(),bsdf.data.SpecularTransmission()),
                path.GetThp(),path.GetL(),uint2(path.flagsAndVertexIndex,path.packedCounters));
        #endif // RTR terminal receiver before truncation evidence
        if (pathStopping)
'@ 'RTR terminal receiver before truncation evidence'
foreach($relative in @('Rtxpt/Shaders/PathTracer/PathTracerHandleHit.hlsli','Rtxpt/Shaders/PathTracer/PathTracer.hlsli')) {
    $path=Join-Path $sdk $relative;$text=[IO.File]::ReadAllText($path)
    $text=$text.Replace('(path.getCounter((PackedCounters)3)&3u)-1u','RtrSceneTraceBranch(path.getCounter((PackedCounters)3))')
    [IO.File]::WriteAllText($path,$text,$utf8)
}
# Extend the two-junction fix without increasing PathPayload or borrowing the
# nested-dielectric counters. Unvisited suffixes are skipped, rather than traced
# repeatedly and counted multiple times. This is a finite Whitted tree prefix.
$rtrReplayPath=Join-Path $sdk 'Rtxpt/Shaders/PathTracerSample.hlsl'
$rtrReplayText=[IO.File]::ReadAllText($rtrReplayPath)
# Remove obsolete scaffolding from SDKs configured by the old non-idempotent
# migration. The canonical replay loop and selector below replace both blocks.
$rtrReplayText=[regex]::Replace($rtrReplayText,
    '(?m)^    #if defined\(RTR_SCENE_LIGHTING\) && RTR_SCENE_LIGHTING && PATH_TRACER_MODE==PT_DENOISING_LAYERS_FILL\r?\n    \[loop\] for\(uint rtrFirstBranch=0;rtrFirstBranch<2;rtrFirstBranch\+\+\) // RTR trace both real dielectric branches\r?\n    \{\r?\n    #endif\r?\n','')
$rtrReplayText=[regex]::Replace($rtrReplayText,
    '(?m)^    #if defined\(RTR_SCENE_LIGHTING\) && RTR_SCENE_LIGHTING && PATH_TRACER_MODE==PT_DENOISING_LAYERS_FILL\r?\n    path\.setCounter\(\(PackedCounters\)3,rtrFirstBranch\+1u\); // RTR first-interface selector in ray payload\r?\n    #endif\r?\n','')
$rtrReplayText=$rtrReplayText.Replace('rtrOpticalReplay<4;','rtrOpticalReplay<(1u<<RTR_SCENE_EXACT_INTERFACE_COUNT);')
$rtrReplayText=$rtrReplayText.Replace('((rtrOpticalReplay>>1u)+1u)|((rtrOpticalReplay&1u)<<3u)',
    '((rtrOpticalReplay>>(RTR_SCENE_EXACT_INTERFACE_COUNT-1u))+1u)|((rtrOpticalReplay&((1u<<(RTR_SCENE_EXACT_INTERFACE_COUNT-1u))-1u))<<3u)')
$rtrReplayText=$rtrReplayText.Replace('if((path.getCounter((PackedCounters)3)&16u)==0)rtrOpticalReplay++; // RTR skip absent second junction, no duplicated energy',
    'rtrOpticalReplay+=(1u<<(RTR_SCENE_EXACT_INTERFACE_COUNT-min(rtrOpticalDepth,RTR_SCENE_EXACT_INTERFACE_COUNT)))-1u; // RTR skip absent second junction, no duplicated energy')
[IO.File]::WriteAllText($rtrReplayPath,$rtrReplayText,$utf8)
Patch-Once 'Rtxpt/Shaders/PathTracerSample.hlsl' '    //float3 lastOrigin = float3(1e30f,1e30f,1e30f);' @'
    #if defined(RTR_SCENE_LIGHTING) && RTR_SCENE_LIGHTING && PATH_TRACER_MODE==PT_DENOISING_LAYERS_FILL
    uint rtrOpticalDepth=0;uint rtrFirstOpticalVertex=0; // RTR visited optical prefix depth
    #endif
    //float3 lastOrigin = float3(1e30f,1e30f,1e30f);
'@ 'RTR visited optical prefix depth'
Patch-Once 'Rtxpt/Shaders/PathTracerSample.hlsl' '        nextHit(path, tMinMax, workingContext);' @'
        #if defined(RTR_SCENE_LIGHTING) && RTR_SCENE_LIGHTING && PATH_TRACER_MODE==PT_DENOISING_LAYERS_FILL
        path.flagsAndVertexIndex&=~RTR_SCENE_OPTICAL_VERTEX_FLAG;
        #endif
        nextHit(path, tMinMax, workingContext);
        #if defined(RTR_SCENE_LIGHTING) && RTR_SCENE_LIGHTING && PATH_TRACER_MODE==PT_DENOISING_LAYERS_FILL
        if((path.flagsAndVertexIndex&RTR_SCENE_OPTICAL_VERTEX_FLAG)!=0)
        {
            rtrOpticalDepth=max(rtrOpticalDepth,path.getVertexIndex());
            if(rtrFirstOpticalVertex==0)rtrFirstOpticalVertex=path.getVertexIndex();
        } // RTR count actual optical junctions across native hit calls
        #endif
'@ 'RTR count actual optical junctions across native hit calls'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerHandleHit.hlsli' @'
        if(bsdf.data.SpecularTransmission()>0)
        {
'@ @'
        if(bsdf.data.SpecularTransmission()>0)
        {
            #if PATH_TRACER_MODE==PT_DENOISING_LAYERS_FILL
            path.flagsAndVertexIndex|=RTR_SCENE_OPTICAL_VERTEX_FLAG; // RTR report actual optical junction to raygen
            path.setCounter((PackedCounters)3,path.getCounter((PackedCounters)3)|4u); // RTR glass also reached through a primary metal reflection
            #endif
'@ 'RTR report actual optical junction to raygen'
$rtrReplayHitPath=Join-Path $sdk 'Rtxpt/Shaders/PathTracer/PathTracerHandleHit.hlsli'
$rtrReplayHitText=[IO.File]::ReadAllText($rtrReplayHitPath)
$rtrReplayHitText=[regex]::Replace($rtrReplayHitText,
    'if\(path\.getVertexIndex\(\)==2 && \(path\.getCounter\(\(PackedCounters\)3\)&4u\)!=0\)\s*path\.setCounter\(\(PackedCounters\)3,path\.getCounter\(\(PackedCounters\)3\)\|16u\);',
    '// RTR junction activity is carried in the otherwise unused payload flag.')
$rtrReplayHitText=[regex]::Replace($rtrReplayHitText,
    'if\(\(path\.getVertexIndex\(\)==1 && \(path\.getCounter\(\(PackedCounters\)3\)&3u\)==2u\)\s*\|\| \(path\.getVertexIndex\(\)<=2 && \(path\.getCounter\(\(PackedCounters\)3\)&8u\)!=0\)\)surfaceEmission=0;',
    'if(RtrSceneReplayPrefixDuplicate(path.getCounter((PackedCounters)3),path.getVertexIndex()))surfaceEmission=0;')
[IO.File]::WriteAllText($rtrReplayHitPath,$rtrReplayHitText,$utf8)
Patch-Once 'Rtxpt/Shaders/PathTracerSample.hlsl' '    } // RTR first-interface paths committed and summed' @'
    // A non-glass primary has no first-interface glass branch to enumerate.
    if(rtrFirstOpticalVertex>1 && rtrOpticalReplay>=((1u<<(RTR_SCENE_EXACT_INTERFACE_COUNT-1u))-1u))break;
    } // RTR first-interface paths committed and summed
'@ 'A non-glass primary has no first-interface glass branch to enumerate.'
Patch-Once 'Rtxpt/Shaders/PathTracerSample.hlsl' '#include "PathTracer/PathTracer.hlsli"' @'
#include "PathTracer/PathTracer.hlsli"
#if defined(RTR_SCENE_LIGHTING) && RTR_SCENE_LIGHTING && PATH_TRACER_MODE==PT_DENOISING_LAYERS_FILL && RTR_SCENE_REPLAY_PREFIX_CACHE
#include "PathTracer/RtrSceneReplayCache.hlsli" // RTR shader-local shared optical prefixes
#endif
'@ 'RTR shader-local shared optical prefixes'
Patch-Once 'Rtxpt/Shaders/PathTracerSample.hlsl' '    [loop] for(uint rtrOpticalReplay=0;' @'
    #if RTR_SCENE_REPLAY_PREFIX_CACHE
    RtrSceneReplayCache rtrReplayCache;
    rtrReplayCache.valid=0;rtrReplayCache.previous=0;rtrReplayCache.firstOpticalVertex=0;
    #endif // RTR initialize shared prefix cache
    [loop] for(uint rtrOpticalReplay=0;
'@ 'RTR initialize shared prefix cache'
Patch-Once 'Rtxpt/Shaders/PathTracerSample.hlsl' '        path.flagsAndVertexIndex&=~RTR_SCENE_OPTICAL_VERTEX_FLAG;' @'
        path.flagsAndVertexIndex&=~RTR_SCENE_OPTICAL_VERTEX_FLAG;
        #if RTR_SCENE_REPLAY_PREFIX_CACHE
        rtrReplayCache.Save(path); // RTR cache native ray state before traversal
        #endif
'@ 'RTR cache native ray state before traversal'
Patch-Once 'Rtxpt/Shaders/PathTracerSample.hlsl' '    if((path.getCounter((PackedCounters)3)&4u)==0)break;' @'
    #if RTR_SCENE_REPLAY_PREFIX_CACHE
    rtrReplayCache.previous=rtrOpticalReplay;
    rtrReplayCache.firstOpticalVertex=rtrFirstOpticalVertex;
    #endif // RTR keep actual previous mask before skipping absent suffixes
    if((path.getCounter((PackedCounters)3)&4u)==0)break;
'@ 'RTR keep actual previous mask before skipping absent suffixes'
Patch-Once 'Rtxpt/Shaders/PathTracerSample.hlsl' '    SER_HIT_OBJECT hit;' @'
    #if defined(RTR_SCENE_LIGHTING) && RTR_SCENE_LIGHTING && PATH_TRACER_MODE==PT_DENOISING_LAYERS_FILL && RTR_SCENE_WORK_TRACE
    if(RtrSceneWorkPixel(path.GetPixelPos(),workingContext.PtConsts.frameIndex))
    {
        uint4 identity=uint4(rayQuery.CommittedStatus(),0xffffffffu,0xffffffffu,0xffffffffu);
        float distance=kMaxRayTravel;
        if(rayQuery.CommittedStatus()==COMMITTED_TRIANGLE_HIT) {
            identity.yzw=uint3(rayQuery.CommittedInstanceIndex(),rayQuery.CommittedGeometryIndex(),rayQuery.CommittedPrimitiveIndex());
            distance=rayQuery.CommittedRayT();
        }
        RtrSceneWorkPrint(210u,uint4(path.GetPixelPos(),workingContext.PtConsts.frameIndex,path.getVertexIndex()+1u),
            path.getCounter((PackedCounters)3),rayQuery.WorldRayOrigin(),rayQuery.WorldRayDirection(),identity,distance);
    }
    #endif // RTR sampled actual traversal identity
    SER_HIT_OBJECT hit;
'@ 'RTR sampled actual traversal identity'
Patch-Once 'Rtxpt/Shaders/PathTracerSample.hlsl' '    float2 tMinMax = FirstHitFromVBuffer(path, 0, workingContext);' @'
    float2 tMinMax = FirstHitFromVBuffer(path, 0, workingContext);
    #if defined(RTR_SCENE_LIGHTING) && RTR_SCENE_LIGHTING && RTR_SCENE_WORK_TRACE
    if(RtrSceneWorkPixel(pixelPos,workingContext.PtConsts.frameIndex))
        RtrSceneWorkPrint(230u,uint4(pixelPos,workingContext.PtConsts.frameIndex,rtrOpticalReplay),path.getCounter((PackedCounters)3));
    #endif // RTR sampled first-hit initialization
'@ 'RTR sampled first-hit initialization'
# Migrate existing SDK checkouts as well as fresh ones. Cache hits must not
# reconstruct the primary ray/VBuffer first.
$replayRaygen=Join-Path $sdk 'Rtxpt/Shaders/PathTracerSample.hlsl'
$replaySource=[IO.File]::ReadAllText($replayRaygen)
if(!$replaySource.Contains('RTR initialize committed-intersection cache')) {
    $oldInit='rtrReplayCache.valid=0;rtrReplayCache.previous=0;rtrReplayCache.firstOpticalVertex=0;'
    if(!$replaySource.Contains($oldInit)){throw 'Replay cache initialization anchor changed'}
    $newInit=@'
rtrReplayCache.valid=0;rtrReplayCache.previous=0;rtrReplayCache.firstOpticalVertex=0;
    rtrReplayCache.resumeVertex=0;
    // RTR initialize committed-intersection cache
'@
    $replaySource=$replaySource.Replace($oldInit,([regex]::Replace($newInit,"`r?`n","`r`n")))
}
if(!$replaySource.Contains('RTR initialize only cache misses')) {
    $begin=$replaySource.IndexOf('    PathState path;', $replaySource.IndexOf('void RAYGEN_ENTRY()'))
    $end=$replaySource.IndexOf('    // Main path tracing loop',$begin)
    if($begin -lt 0 -or $end -lt $begin){throw 'Replay path initialization region changed'}
    $initializer=@'
    PathState path;
    PathTracer::WorkingContext workingContext = Bridge::GetWorkingContext();
    float2 tMinMax=float2(0,kMaxRayTravel);
    #if defined(RTR_SCENE_LIGHTING) && RTR_SCENE_LIGHTING && PATH_TRACER_MODE==PT_DENOISING_LAYERS_FILL && RTR_SCENE_REPLAY_PREFIX_CACHE
    uint rtrReplayControl=((rtrOpticalReplay>>(RTR_SCENE_EXACT_INTERFACE_COUNT-1u))+1u)|((rtrOpticalReplay&((1u<<(RTR_SCENE_EXACT_INTERFACE_COUNT-1u))-1u))<<3u);
    if(rtrReplayCache.Restore(rtrOpticalReplay,rtrReplayControl,path))
    {
        rtrOpticalDepth=path.getVertexIndex();
        rtrFirstOpticalVertex=rtrReplayCache.firstOpticalVertex;
    }
    else
    #endif
    { // RTR initialize only cache misses
        path=PathTracer::EmptyPathInitialize(pixelPos,g_Const.ptConsts.camera.PixelConeSpreadAngle);
        PathTracer::SetupPathPrimaryRay(path,Bridge::computeCameraRay(pixelPos));
        #if defined(RTR_SCENE_LIGHTING) && RTR_SCENE_LIGHTING && PATH_TRACER_MODE==PT_DENOISING_LAYERS_FILL
        path.setCounter((PackedCounters)3,((rtrOpticalReplay>>(RTR_SCENE_EXACT_INTERFACE_COUNT-1u))+1u)|((rtrOpticalReplay&((1u<<(RTR_SCENE_EXACT_INTERFACE_COUNT-1u))-1u))<<3u)); // RTR two-junction selectors in ray payload
        #endif
        PathTracer::StartPixel(path,workingContext);
        #if PATH_TRACER_MODE==PT_DENOISING_LAYERS_FILL
        tMinMax=FirstHitFromVBuffer(path,0,workingContext);
        #if defined(RTR_SCENE_LIGHTING) && RTR_SCENE_LIGHTING && RTR_SCENE_WORK_TRACE
        if(RtrSceneWorkPixel(pixelPos,workingContext.PtConsts.frameIndex))
            RtrSceneWorkPrint(230u,uint4(pixelPos,workingContext.PtConsts.frameIndex,rtrOpticalReplay),path.getCounter((PackedCounters)3));
        #endif // RTR sampled first-hit initialization
        #endif
    }

'@
    $replaySource=$replaySource.Substring(0,$begin)+([regex]::Replace($initializer,"`r?`n","`r`n"))+"`r`n"+$replaySource.Substring($end)
}
[IO.File]::WriteAllText($replayRaygen,$replaySource,$utf8)
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracer.hlsli' '        const float rrVal = sqrt(Luminance(path.GetThp())); // closer to perceptual' @'
        #if defined(RTR_SCENE_LIGHTING) && RTR_SCENE_LIGHTING
        // gsOpticalContinuation uses its event budget, not throughput roulette.
        // Applying roulette to a deterministic split kills dim eta^2 entry
        // branches before the exit interface restores their radiance weight.
        if((path.getCounter((PackedCounters)3)&4u)!=0)return false; // RTR bounded optical transport without roulette
        #endif
        const float rrVal = sqrt(Luminance(path.GetThp())); // closer to perceptual
'@ 'RTR bounded optical transport without roulette'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerHandleHit.hlsli' '            SharcHandlePathLimit(surfaceEmission);' @'
            #if defined(RTR_SCENE_LIGHTING) && RTR_SCENE_LIGHTING && PATH_TRACER_MODE!=PT_DENOISING_LAYERS_BUILD
            // gsOpticalContinuation shades the final receiver before ending its budget.
            NEEResult terminal=RtrSceneDirect(path,shadingData,bsdf,workingContext);
            float4 terminalRadiance=terminal.GetRadianceAndSpecAvg();
            AccumulatePathRadiance(workingContext,path,terminalRadiance.rgb,terminalRadiance.w,false,false); // RTR shade final scene receiver
            #endif
            SharcHandlePathLimit(surfaceEmission);
'@ 'RTR shade final scene receiver'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracer.hlsli' '        NEEBSDFMISInfo misInfo = NEEBSDFMISInfo::Unpack16bit(path.GetPackedMISInfo());' @'
        #if defined(RTR_SCENE_LIGHTING) && RTR_SCENE_LIGHTING
        float horizon=.35+.65*saturate(rayDir.y*.5+.5);
        RtrSceneLight sun=t_RtrSceneLights[0];
        float disk=smoothstep(.9995,.9999,dot(normalize(rayDir),-normalize(sun.DirectionRange.xyz)));
        environmentEmission=ToLpRadiance(workingContext.PtConsts.RtrSceneSky.rgb*horizon+sun.ColorIntensity.rgb*sun.ColorIntensity.a*disk); // RTR old horizon and solar sky
        #else
        NEEBSDFMISInfo misInfo = NEEBSDFMISInfo::Unpack16bit(path.GetPackedMISInfo());
'@ 'RTR old horizon and solar sky'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracer.hlsli' '#if RTXPT_FIREFLY_FILTER && PATH_TRACER_MODE!=PT_DENOISING_LAYERS_BUILD' @'
        #endif // RTR old horizon and solar sky
#if RTXPT_FIREFLY_FILTER && PATH_TRACER_MODE!=PT_DENOISING_LAYERS_BUILD
'@ '#endif // RTR old horizon and solar sky'
# The bounded pre-SR history runs after current radiance merge, before SR tags.
[IO.File]::WriteAllText((Join-Path $sdk 'Rtxpt/ProcessingPasses/RtrPreSrHistory.hlsl'),
    [IO.File]::ReadAllText((Join-Path $repo 'renderer-rtxpt/src/main/cpp/RtrPreSrHistory.hlsl')),$utf8)
foreach($nativeLightingFile in @('RtrNativeLighting.hlsl','RtrNativeDynamicLighting.hlsl','RtrNativeDynamicLightingReconstruct.hlsl','RtrNativeLightingDeep.hlsl','RtrNativeLightingReconstruct.hlsl','RtrNativeLightingResolve.hlsl','RtrNativeGiReduce.hlsl','RtrNativeGiUpsample.hlsl','RtrNativeLightingStyle.hlsli','RtrNativeLightingEncode.hlsl','RtrHostResolve.hlsl','RtrHostAugmentation.hlsl')) {
    [IO.File]::WriteAllText((Join-Path $sdk "Rtxpt/ProcessingPasses/$nativeLightingFile"),
        [IO.File]::ReadAllText((Join-Path $repo "renderer-rtxpt/src/main/cpp/$nativeLightingFile")),$utf8)
}
# Keep exactly one current project-owned history entry. Existing SDK checkouts
# can contain the same pass under an earlier injected file name; Patch-Once
# alone would append the renamed pass and leave the missing file in the build.
$shaderConfigPath=Join-Path $sdk 'Rtxpt/Shaders.cfg'
$shaderConfig=[IO.File]::ReadAllText($shaderConfigPath)
$shaderNewline=if($shaderConfig.Contains("`r`n")){"`r`n"}else{"`n"}
$historyAnchor='ProcessingPasses/PostProcess.hlsl -T cs -E main -D NO_DENOISER_FINAL_MERGE=1'
if(!$shaderConfig.Contains($historyAnchor)){throw 'Pinned reconstruction shader configuration anchor changed'}
$normalizedShaderConfig=[regex]::Replace($shaderConfig,'(?m)^ProcessingPasses/Rtr[A-Za-z]*SrHistory\.hlsl -T cs -E main\r?\n?','')
$normalizedShaderConfig=$normalizedShaderConfig.Replace($historyAnchor,
    $historyAnchor+$shaderNewline+'ProcessingPasses/RtrPreSrHistory.hlsl -T cs -E main')
# Remove the complete generated line, including permutation flags. Removing
# only the entry-point prefix left orphan -D lines on a repeated source build.
$normalizedShaderConfig=[regex]::Replace($normalizedShaderConfig,'(?m)^[ \t]+-D RTR_LIGHT_GROUP_CAPACITY=[^\r\n]*(?:\r?\n|$)','')
foreach($pass in @('RtrNativeLightingEncode','RtrNativeLightingDeep','RtrNativeDynamicLighting','RtrNativeDynamicLightingReconstruct','RtrNativeLightingReconstruct','RtrNativeLightingResolve','RtrNativeGiReduce','RtrNativeGiUpsample','RtrHostResolve','RtrHostAugmentation','RtrNativeLighting')){
    $normalizedShaderConfig=[regex]::Replace($normalizedShaderConfig,"(?m)^ProcessingPasses/$pass\.hlsl -T cs -E main[^\r\n]*(?:\r?\n|$)",'')
}
foreach($pass in @('RtrNativeLightingEncode','RtrNativeLightingDeep','RtrNativeLightingResolve','RtrNativeGiReduce','RtrNativeGiUpsample','RtrHostResolve','RtrHostAugmentation','RtrNativeLighting','RtrNativeLightingReconstruct','RtrNativeDynamicLighting','RtrNativeDynamicLightingReconstruct')){
    $flags=if($pass -in @('RtrNativeLighting','RtrNativeLightingReconstruct')){' -D RTR_LIGHT_GROUP_CAPACITY={1,2,4,32} -D RTR_ANALYTIC_LIGHTS={0,1} -D RTR_WORK_STATISTICS={0,1} -D RTR_MAX_COMPOSITION={0,1,2}'}elseif($pass -in @('RtrNativeDynamicLighting','RtrNativeDynamicLightingReconstruct')){' -D RTR_LIGHT_GROUP_CAPACITY={1,2,4,32} -D RTR_ANALYTIC_LIGHTS={0,1} -D RTR_WORK_STATISTICS=0 -D RTR_MAX_COMPOSITION=1'}else{''}
    $normalizedShaderConfig+="ProcessingPasses/$pass.hlsl -T cs -E main"+$flags+$shaderNewline
}
if($normalizedShaderConfig -cne $shaderConfig){
    [IO.File]::WriteAllText($shaderConfigPath,$normalizedShaderConfig,$utf8)
}
Patch-Once 'Rtxpt/Sample.h' '    virtual void UpdateCustomResources() { }' @'
    virtual void UpdateCustomResources() { }
    virtual void FilterReconstructionInput(nvrhi::ICommandList*, bool) { } // RTR pre-SR history hook
'@ 'RTR pre-SR history hook'
Patch-Once 'Rtxpt/Sample.cpp' '        if (m_ui.RealtimeAA == 0)' @'
        FilterReconstructionInput(m_commandList, reset); // RTR current radiance bounded history
        if (m_ui.RealtimeAA == 0)
'@ 'RTR current radiance bounded history'
# Match the Vulkan route's summed continuous surface response without deleting
# any lobe or bounce. PBRT-v3 BSDF::Sample_f sums f after forming the mixture PDF.
# Ideal delta events retain native weights; their density is not a finite PDF.
Patch-Once 'Rtxpt/Shaders/PathTracer/BSDFs/StandardBSDF.hlsli' @'
        bool valid = bsdf.sample(wiLocal, woLocal, result.pdf, result.weight, result.lobe, result.lobeP, preGeneratedSamples.xyzw);
#endif
        result.wo = shadingData.FromLocal(woLocal);
'@ @'
        bool valid = bsdf.sample(wiLocal, woLocal, result.pdf, result.weight, result.lobe, result.lobeP, preGeneratedSamples.xyzw);
#endif
        #if defined(RTR_COMBINED_BSDF_WEIGHT) && RTR_COMBINED_BSDF_WEIGHT
        if (valid && result.pdf > 0)
        {
            // Only overlapping continuous lobes need another BSDF evaluation.
            uint overlapping = woLocal.z >= 0
                ? uint(bsdf.pDiffuseReflection > 0)
                    + uint(bsdf.pSpecularReflection > 0 && bsdf.specularReflection.alpha > 0)
                    + uint(bsdf.pSpecularReflectionTransmission > 0 && bsdf.specularReflectionTransmission.alpha > 0)
                : uint(bsdf.pDiffuseTransmission > 0)
                    + uint(bsdf.pSpecularReflectionTransmission > 0 && bsdf.specularReflectionTransmission.alpha > 0);
            if (overlapping > 1)
                result.weight = bsdf.eval(wiLocal, woLocal).rgb / result.pdf;
        }
        #endif // RTR summed continuous BSDF over native mixture PDF
        result.wo = shadingData.FromLocal(woLocal);
'@ 'RTR summed continuous BSDF over native mixture PDF'
# Raw SR/TAA/reference output does not read it; retain upstream filtering for every consumer.
# Partition the EXISTING baked environment instead of adding duplicate suns or
# replacing visibility/indirect lighting with a constant ambient approximation.
foreach($header in @('RtrDirectionalStrata.hlsli','RtrDirectionalNee.hlsli')) {
    [IO.File]::WriteAllText((Join-Path $sdk ('Rtxpt/Shaders/PathTracer/'+$header)),
        [IO.File]::ReadAllText((Join-Path $repo ('renderer-rtxpt/src/main/cpp/'+$header))),$utf8)
}
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerShared.h' '    PathTracerCameraData camera;' @'
    float4  RtrDirectionalCaps[16];
    uint    RtrDirectionalCount;
    uint3   RtrDirectionalPadding; // RTR directional sampling domains
    PathTracerCameraData camera;
'@ 'RTR directional sampling domains'
foreach($header in @('PathTracer.hlsli','PathTracerNEE.hlsli')) {
    Patch-Once ('Rtxpt/Shaders/PathTracer/'+$header) '#include "PathTracerTypes.hlsli"' @'
#include "PathTracerTypes.hlsli"
#include "RtrDirectionalStrata.hlsli" // RTR shared environment partition
'@ 'RTR shared environment partition'
}
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '    return envMap.EvalLocal(localDir, Bridge::DiffuseEnvironmentMapMIPOffset());' @'
    #if defined(RTR_DIRECTIONAL_STRATA) && RTR_DIRECTIONAL_STRATA
    return envMap.EvalLocal(localDir, 0); // RTR one environment target for both estimators
    #else
    return envMap.EvalLocal(localDir, Bridge::DiffuseEnvironmentMapMIPOffset());
    #endif
'@ 'RTR one environment target for both estimators'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '            float wrsWeight = EvalSampleWeight( candidateSample, shadingData, bsdf );' @'
            #if defined(RTR_DIRECTIONAL_STRATA) && RTR_DIRECTIONAL_STRATA
            if (workingContext.PtConsts.RtrDirectionalCount > 0
                && PolymorphicLight::DecodeType(packedLightInfo) == PolymorphicLightType::kEnvironmentQuad)
            {
                EnvMap environment = Bridge::CreateEnvMap();
                candidateSample.Li = environment.Eval(candidateSample.Direction, 0) / pdf;
                if (RtrDirectionalOwner(candidateSample.Direction, workingContext.PtConsts) != 0xffffffffu)
                    candidateSample.Li = 0; // RTR residual excludes separately sampled domains
            }
            #endif
            float wrsWeight = EvalSampleWeight( candidateSample, shadingData, bsdf );
'@ 'RTR residual excludes separately sampled domains'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '    #include "RtrSmallPointNee.hlsli"' @'
    #include "RtrDirectionalNee.hlsli" // RTR native direct-light evaluation for distant strata
    #include "RtrSmallPointNee.hlsli"
'@ 'RTR native direct-light evaluation for distant strata'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '        // RTR deterministic delta contributions; do not add them again below.' @'
        #if defined(RTR_DIRECTIONAL_STRATA) && RTR_DIRECTIONAL_STRATA
        RtrSumDirectionalStrata(result, lightSampler, shadingData, bsdf, preScatterPath, sampleGenerator, workingContext);
        #endif // RTR sample every distant stratum once
        // RTR deterministic delta contributions; do not add them again below.
'@ 'RTR sample every distant stratum once'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracer.hlsli' '            float3 Le = envMap.EvalLocal(localDir, mipLevel);' @'
            #if defined(RTR_DIRECTIONAL_STRATA) && RTR_DIRECTIONAL_STRATA
            if (workingContext.PtConsts.RtrDirectionalCount > 0)
                mipLevel = 0; // RTR preserve environment target across escape and NEE
            #endif
            float3 Le = envMap.EvalLocal(localDir, mipLevel);
'@ 'RTR preserve environment target across escape and NEE'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracer.hlsli' '                misWeight = lightSampler.ComputeBSDFMISForEnvironmentQuad(environmentQuadLightIndex, bsdfScatterPdf, misInfo.CandidateSamples, misInfo.FullSamples);' @'
                misWeight = lightSampler.ComputeBSDFMISForEnvironmentQuad(environmentQuadLightIndex, bsdfScatterPdf, misInfo.CandidateSamples, misInfo.FullSamples);
                #if defined(RTR_DIRECTIONAL_STRATA) && RTR_DIRECTIONAL_STRATA
                const uint owner = RtrDirectionalOwner(rayDir, workingContext.PtConsts);
                if (owner != 0xffffffffu)
                    misWeight = EvalMIS(RTXPT_NEE_MIS_HEURISTIC, 1, bsdfScatterPdf, 1,
                        RtrDirectionalPdf(owner, workingContext.PtConsts)); // RTR reciprocal BSDF MIS for distant stratum
                #endif
'@ 'RTR reciprocal BSDF MIS for distant stratum'
Patch-Once 'Rtxpt/AdvancedSample.cpp' '        m_denoisingGuidesBaker->DenoiseSpecHitT(m_commandList, m_bindingSet);' @'
        // RTR filter hit distance only when a consumer is active
        if (m_ui.ActualUseStandaloneDenoiser()
            || (m_ui.RealtimeMode && m_ui.RealtimeAA == 3)
            || m_ui.DebugView != DebugViewType::Disabled)
            m_denoisingGuidesBaker->DenoiseSpecHitT(m_commandList, m_bindingSet);
'@ 'RTR filter hit distance only when a consumer is active'
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
# SDK inline helpers retain addresses after slShutdown unloads the plugins.
# Match sl.h's cached hot path, with lifecycle-only invalidation on embedded close.
Patch-Once 'External/Donut/src/app/streamline/StreamlineIntegration.cpp' '// Streamline Features' @'
#include "rtxpt_streamline_cache.h" // RTR session-bound feature function caches
// Streamline Features
'@ 'RTR session-bound feature function caches'
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
Patch-Once 'Rtxpt/SampleUI.h' 'std::string                         HostOverlayText; // RTR GPU-native host HUD' @'
std::string                         HostOverlayText; // RTR GPU-native host HUD
    std::vector<std::string>             HostOverlayChoices; // RTR host choice selector
    int                                 HostOverlayChoice = -1;
'@ 'RTR host choice selector'
$hostUiPath=Join-Path $sdk 'Rtxpt/SampleUI.cpp'
$hostUiText=[IO.File]::ReadAllText($hostUiPath)
$hostUiText=$hostUiText.Replace('ImGuiWindowFlags_NoSavedSettings | ImGuiWindowFlags_NoInputs | ImGuiWindowFlags_NoFocusOnAppearing',
    'ImGuiWindowFlags_NoSavedSettings | (m_ui.HostOverlayChoices.empty() ? ImGuiWindowFlags_NoInputs : 0) | ImGuiWindowFlags_NoFocusOnAppearing')
[IO.File]::WriteAllText($hostUiPath,$hostUiText,$utf8)
Patch-Once 'Rtxpt/SampleUI.cpp' 'ImGui::TextUnformatted(m_ui.HostOverlayText.c_str());' @'
ImGui::TextUnformatted(m_ui.HostOverlayText.c_str());
        // RTR host choice selector: upstream input, drawing and GPU composition.
        if (!m_ui.HostOverlayChoices.empty()) {
            ImGui::SetNextItemWidth(330.f);
            const auto& labels=m_ui.HostOverlayChoices;
            if (ImGui::BeginCombo("Scene", labels.at(m_ui.HostOverlayChoice).c_str())) {
                for (int i=0; i<int(labels.size()); ++i) {
                    ImGui::PushID(i);
                    if (ImGui::Selectable(labels[i].c_str(), i==m_ui.HostOverlayChoice)) m_ui.HostOverlayChoice=i;
                    if (i==m_ui.HostOverlayChoice) ImGui::SetItemDefaultFocus();
                    ImGui::PopID();
                }
                ImGui::EndCombo();
            }
        }
'@ 'RTR host choice selector:'
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
Patch-Once 'External/Donut/src/app/streamline/StreamlineIntegration.cpp' 'm_hostShutdownResult = slShutdown(); // RTR observable embedded shutdown' @'
m_hostShutdownResult = slShutdown(); // RTR observable embedded shutdown
        rtr::streamline::FunctionCaches::reset(); // RTR invalidate unloaded plugin addresses
'@ 'RTR invalidate unloaded plugin addresses'
Patch-Once 'Rtxpt/Sample.cpp' 'if (available.empty())' 'if (std::filesystem::path(preferred).is_absolute() && std::filesystem::is_regular_file(preferred)) return preferred; // RTR absolute host scene
    if (available.empty())' 'RTR absolute host scene'
Patch-Once 'Rtxpt/Sample.cpp' 'if (std::filesystem::path(preferred).is_absolute()' 'if (preferred == "__rtr_memory_scene__") return preferred; // RTR framework empty retained scene
    if (std::filesystem::path(preferred).is_absolute()' 'RTR framework empty retained scene'
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
Patch-Once 'External/Donut/include/donut/app/DeviceManager.h' 'bool HostFrame();' @'
bool HostFrame();
        bool HostExternalPresentation = false; // RTR external GPU compositor
'@ 'RTR external GPU compositor'
Patch-Once 'External/Donut/include/donut/app/DeviceManager.h' 'bool HostExternalPresentation = false; // RTR external GPU compositor' @'
bool HostExternalPresentation = false; // RTR external GPU compositor
        bool HostFixedWindowTitle = false; // RTR host owns viewport title
'@ 'RTR host owns viewport title'
Patch-Once 'External/Donut/src/app/DeviceManager.cpp' "void DeviceManager::SetInformativeWindowTitle(const char* applicationName, bool includeFramerate, const char* extraInfo)`r`n{" @'
void DeviceManager::SetInformativeWindowTitle(const char* applicationName, bool includeFramerate, const char* extraInfo)
{
    if (HostFixedWindowTitle) return; // RTR explicit titles survive automatic sample updates
'@ 'RTR explicit titles survive automatic sample updates'
Patch-Once 'External/Donut/src/app/DeviceManager.cpp' "    glfwShowWindow(m_Window);`r`n    glfwFocusWindow(m_Window);" @'
    // RTR compositor keeps the upstream host hidden from its creation onward.
    if (!HostExternalPresentation) { glfwShowWindow(m_Window); glfwFocusWindow(m_Window); }
'@ 'RTR compositor keeps the upstream host hidden'
Patch-Once 'External/Donut/src/app/DeviceManager.cpp' '            return AnimateRenderPresent();' @'
            // RTR external compositor owns the visible window; the hidden host still renders.
            if (HostExternalPresentation) { m_windowVisible=true; m_windowIsInFocus=true; }
            return AnimateRenderPresent();
'@ 'RTR external compositor owns'
Patch-Once 'External/Donut/src/app/dx12/DeviceManager_DX12.cpp' "bool DeviceManager_DX12::BeginFrame()`r`n{" @'
bool DeviceManager_DX12::BeginFrame()
{
    if (HostExternalPresentation) return true; // RTR external timeline owns frame retirement
'@ 'RTR external timeline owns'
Patch-Once 'External/Donut/src/app/dx12/DeviceManager_DX12.cpp' "bool DeviceManager_DX12::Present()`r`n{" @'
bool DeviceManager_DX12::Present()
{
    if (HostExternalPresentation) return true; // RTR external compositor presents, not DXGI
'@ 'RTR external compositor presents'
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
# Apply after the host adaptations above: this patch is relative to their pinned output.
# A reverse check makes repeated configuration idempotent without accepting partial patches.
$resourcePatch=Join-Path $PSScriptRoot 'patches/rtxpt-demand-resources.patch'
& git -C $sdk apply --reverse --check --ignore-space-change $resourcePatch 2>$null
if($LASTEXITCODE -ne 0){
    & git -C $sdk apply --check --ignore-space-change $resourcePatch
    if($LASTEXITCODE -ne 0){throw 'RTXPT demand-resource patch does not match the pinned host integration'}
    & git -C $sdk apply --ignore-space-change $resourcePatch
    if($LASTEXITCODE -ne 0){throw 'RTXPT demand-resource patch failed'}
}
& git -C $sdk apply --check --ignore-space-change $lightingPatch
if($LASTEXITCODE -ne 0){throw 'RTXPT lighting patch does not match the verified pinned base'}
& git -C $sdk apply --ignore-space-change $lightingPatch
if($LASTEXITCODE -ne 0){throw 'RTXPT lighting patch failed'}
& git -C $sdk apply --check --ignore-space-change $retainedPatch
if($LASTEXITCODE -ne 0){throw 'RTXPT retained-resource patch does not match the verified pinned base'}
& git -C $sdk apply --ignore-space-change $retainedPatch
if($LASTEXITCODE -ne 0){throw 'RTXPT retained-resource patch failed'}
& git -C $sdk apply --check --ignore-space-change $transparentPatch
if($LASTEXITCODE -ne 0){throw 'RTXPT transparent-background patch does not match the verified pinned base'}
& git -C $sdk apply --ignore-space-change $transparentPatch
if($LASTEXITCODE -ne 0){throw 'RTXPT transparent-background patch failed'}

& git -C $sdk apply --check --ignore-space-change $framePatch
if($LASTEXITCODE -ne 0){throw 'RTXPT frame-preparation overlay does not match the verified base'}
& git -C $sdk apply --ignore-space-change $framePatch
if($LASTEXITCODE -ne 0){throw 'RTXPT frame-preparation overlay failed'}
& git -C $sdk apply --check --ignore-space-change $registryPatch
if($LASTEXITCODE -ne 0){throw 'RTXPT light-registry overlay does not match the pinned base'}
& git -C $sdk apply --ignore-space-change $registryPatch
if($LASTEXITCODE -ne 0){throw 'RTXPT light-registry overlay failed'}
& git -C $sdk apply --check --ignore-space-change $sparsePatch
if($LASTEXITCODE -ne 0){throw 'RTXPT sparse-lighting overlay does not match the pinned base'}
& git -C $sdk apply --ignore-space-change $sparsePatch
if($LASTEXITCODE -ne 0){throw 'RTXPT sparse-lighting overlay failed'}
& git -C $sdk apply --check --ignore-space-change $hostSurfacePatch
if($LASTEXITCODE -ne 0){throw 'RTXPT host-surface overlay does not match the pinned base'}
& git -C $sdk apply --ignore-space-change $hostSurfacePatch
if($LASTEXITCODE -ne 0){throw 'RTXPT host-surface overlay failed'}
& git -C $sdk apply --check --ignore-space-change $localPreparationPatch
if($LASTEXITCODE -ne 0){throw 'RTXPT local-preparation overlay does not match the verified base'}
& git -C $sdk apply --ignore-space-change $localPreparationPatch
if($LASTEXITCODE -ne 0){throw 'RTXPT local-preparation overlay failed'}
& git -C $sdk apply --check --ignore-space-change $dynamicResourcesPatch
if($LASTEXITCODE -ne 0){throw 'RTXPT dynamic-resources overlay does not match the verified base'}
& git -C $sdk apply --ignore-space-change $dynamicResourcesPatch
if($LASTEXITCODE -ne 0){throw 'RTXPT dynamic-resources overlay failed'}
& git -C $sdk apply --check --ignore-space-change $highFrameratePatch
if($LASTEXITCODE -ne 0){throw 'RTXPT high-framerate resource overlay does not match the verified base'}
& git -C $sdk apply --ignore-space-change $highFrameratePatch
if($LASTEXITCODE -ne 0){throw 'RTXPT high-framerate resource overlay failed'}
& git -C $sdk apply --check --ignore-space-change $sceneTablesPatch
if($LASTEXITCODE -ne 0){throw 'RTXPT scene-table overlay does not match the verified base'}
& git -C $sdk apply --ignore-space-change $sceneTablesPatch
if($LASTEXITCODE -ne 0){throw 'RTXPT scene-table overlay could not be applied'}
& git -C $sdk apply --check --ignore-space-change $maskedAlphaPatch
if($LASTEXITCODE -ne 0){throw 'RTXPT masked-alpha correction does not match its base'}
& git -C $sdk apply --ignore-space-change $maskedAlphaPatch
if($LASTEXITCODE -ne 0){throw 'RTXPT masked-alpha correction failed'}
& git -C $sdk apply --check --ignore-space-change $metadataDeltaPatch
if($LASTEXITCODE -ne 0){throw 'RTXPT metadata-delta overlay does not match its pinned base'}
& git -C $sdk apply --ignore-space-change $metadataDeltaPatch
if($LASTEXITCODE -ne 0){throw 'RTXPT metadata-delta overlay could not be applied'}
& git -C $sdk apply --check --ignore-space-change $tlasCapacityPatch
if($LASTEXITCODE -ne 0){throw 'RTXPT TLAS capacity overlay does not match its pinned base'}
& git -C $sdk apply --ignore-space-change $tlasCapacityPatch
if($LASTEXITCODE -ne 0){throw 'RTXPT TLAS capacity overlay could not be applied'}
& git -C $sdk apply --check --ignore-space-change $stagingDiagnosticsPatch
if($LASTEXITCODE -ne 0){throw 'RTXPT staging diagnostics overlay does not match its pinned base'}
& git -C $sdk apply --ignore-space-change $stagingDiagnosticsPatch
if($LASTEXITCODE -ne 0){throw 'RTXPT staging diagnostics overlay could not be applied'}
& git -C $sdk apply --check --ignore-space-change $uploadStatisticsPatch
if($LASTEXITCODE -ne 0){throw 'RTXPT upload-statistics overlay does not match its pinned base'}
& git -C $sdk apply --ignore-space-change $uploadStatisticsPatch
if($LASTEXITCODE -ne 0){throw 'RTXPT upload-statistics overlay could not be applied'}
& git -C $sdk apply --check --ignore-space-change $topologyJournalPatch
if($LASTEXITCODE -ne 0){throw 'RTXPT topology-journal overlay does not match its pinned base'}
& git -C $sdk apply --ignore-space-change $topologyJournalPatch
if($LASTEXITCODE -ne 0){throw 'RTXPT topology-journal overlay could not be applied'}
& git -C $sdk apply --check --ignore-space-change $growthDiagnosticsPatch
if($LASTEXITCODE -ne 0){throw 'RTXPT growth diagnostics overlay does not match its pinned base'}
& git -C $sdk apply --ignore-space-change $growthDiagnosticsPatch
if($LASTEXITCODE -ne 0){throw 'RTXPT growth diagnostics overlay could not be applied'}
& git -C $sdk apply --check --ignore-space-change $nativeManagedPatch
if($LASTEXITCODE -ne 0){throw 'RTXPT native-managed overlay does not match its pinned base'}
& git -C $sdk apply --ignore-space-change $nativeManagedPatch
if($LASTEXITCODE -ne 0){throw 'RTXPT native-managed overlay could not be applied'}


Patch-Once 'External/Donut/nvrhi/src/d3d12/d3d12-device.cpp' "    void Device::runGarbageCollection()`r`n    {" @'
    void Device::runGarbageCollection()
    {
        // RTR failed devices cannot provide completion evidence. Leave queued
        // objects for device teardown instead of throwing from app destruction.
        if(RtrDeviceRemoved(m_Context.device))return;
'@ 'RTR failed devices cannot provide completion evidence'

[IO.File]::Copy((Join-Path $repo 'renderer-rtxpt/src/main/cpp/RtrHostSurface.hlsli'),(Join-Path $sdk 'Rtxpt/Shaders/RtrHostSurface.hlsli'),$true)
[IO.File]::Copy((Join-Path $repo 'renderer-rtxpt/src/main/cpp/RtrHostAov.hlsli'),(Join-Path $sdk 'Rtxpt/Shaders/PathTracer/RtrHostAov.hlsli'),$true)
Patch-Once 'External/Donut/src/app/DeviceManager.cpp' '    if (!HostExternalPresentation) { glfwShowWindow(m_Window); if (!hostBackgroundWindow) glfwFocusWindow(m_Window); }' @'
    // RTR defer visibility until the JNI host finishes scene and shader initialization.
'@ 'RTR defer visibility until the JNI host'


$rootCMake=Join-Path $sdk 'CMakeLists.txt'
Patch-Once 'External/Donut/src/app/streamline/StreamlineIntegration.h' '    sl::FrameToken* m_currentFrame = nullptr;' @'
    sl::FrameToken* m_currentFrame = nullptr;
public:
    sl::FrameToken* HostFrameToken() const { return m_currentFrame; } // RTR checked native-layer evaluation
private:
'@ 'RTR checked native-layer evaluation'
$cmakeText=[IO.File]::ReadAllText($rootCMake)
# The command-line OptiX screenshot utility is not used by the embedded realtime host.
$cmakeText=$cmakeText.Replace('if (NOT _denoiser_hash STREQUAL RTXPT_OPTIX_DENOISER_SHA256)',
    'if (RTR_RTXPT_FETCH_CAPTURE_TOOL AND NOT _denoiser_hash STREQUAL RTXPT_OPTIX_DENOISER_SHA256)')
$marker='# RTR_RTXPT_HOST_TARGET'
if($cmakeText.Contains($marker)){$cmakeText=$cmakeText.Substring(0,$cmakeText.IndexOf($marker))}
$hostCMake=(Join-Path $repo 'renderer-rtxpt/src/main/cpp/CMakeLists.txt').Replace('\','/')
[IO.File]::WriteAllText($rootCMake,$cmakeText+"`n$marker`ninclude(`"$hostCMake`")`n",$utf8)
# Stage the pinned dependency as build input; do not modify a shared SDK checkout.
if(!$RtxmuRoot){throw 'Set RTXMU_ROOT or pass -RtxmuRoot for the pinned RTXMU v1.4 source'}
$rtxmuSource=Resolve-ProjectPath $RtxmuRoot
$rtxmuPin=(& git -C $rtxmuSource rev-parse HEAD) -join ''
if($LASTEXITCODE -ne 0 -or $rtxmuPin.Trim() -ne '0c9ce1177000d5923e2cc6a35ae9cb7ff03748d2'){throw 'RTXMU source must match pinned v1.4'}
$rtxmuStage=Join-Path $sdk 'External/Rtxmu'
New-Item -ItemType Directory -Path $rtxmuStage -Force | Out-Null
foreach($folder in @('include','src')){
    $target=Join-Path $rtxmuStage $folder
    New-Item -ItemType Directory -Path $target -Force | Out-Null
    Copy-Item -Path (Join-Path $rtxmuSource ($folder+'/*')) -Destination $target -Recurse -Force
}
foreach($file in @('CMakeLists.txt','LICENSE.txt')){
    if(Test-Path -LiteralPath (Join-Path $rtxmuSource $file)){Copy-Item -LiteralPath (Join-Path $rtxmuSource $file) -Destination $rtxmuStage -Force}
}
& git -C $sdk apply --directory=External/Rtxmu --check (Join-Path $PSScriptRoot 'patches/rtxmu-prepared-allocation.patch')
if($LASTEXITCODE -ne 0){throw 'RTXMU prepared-allocation patch does not match pinned source'}
& git -C $sdk apply --directory=External/Rtxmu (Join-Path $PSScriptRoot 'patches/rtxmu-prepared-allocation.patch')
if($LASTEXITCODE -ne 0){throw 'RTXMU prepared-allocation patch could not be applied'}
$build=Join-Path $sdk 'build-host'
$localDependencies=@('-DNVRHI_WITH_RTXMU=ON','-DRTXMU_WITH_D3D12=ON','-DRTXMU_WITH_VULKAN=OFF',('-DNVRHI_RTXMU_FETCH_DIR='+$rtxmuStage.Replace('\','/')),('-DFETCHCONTENT_SOURCE_DIR_RTXMU='+$rtxmuStage.Replace('\','/')))
if($StreamlineSdk){
    $localDependencies+=('-DFETCHCONTENT_SOURCE_DIR_STREAMLINE='+$streamline.Replace('\','/'))
}
& $cmake -S $sdk -B $build -G 'Visual Studio 17 2022' -A x64 `
    -DDONUT_WITH_VULKAN=OFF -DDONUT_WITH_KTX=OFF -DDONUT_WITH_AFTERMATH=OFF `
    -DDONUT_EMBED_SHADER_PDBS=OFF -DCMAKE_COMPILE_WARNING_AS_ERROR=OFF `
    -DTRACY_ENABLE=OFF -DSTREAMLINE_FEATURE_NIS=OFF `
    '-DCMAKE_CXX_FLAGS=/DWIN32 /D_WINDOWS /EHsc /utf-8' '-DCMAKE_C_FLAGS=/DWIN32 /D_WINDOWS /utf-8' @localDependencies
if($LASTEXITCODE -ne 0){throw 'RTXPT native configure failed'}
if($ConfigureOnly){return}
& (Join-Path $PSScriptRoot 'Invoke-BoundedBuild.ps1') -FilePath $cmake `
    -ArgumentList @('--build',$build,'--config','Release','--target','rtrenderer_rtxpt','rtxpt_native_control','rtxpt_streamline_cache_selftest','rtxpt_memory_diagnostics_selftest','rtxpt_task_batch_selftest','rtxpt_capacity_selftest','rtxpt_upload_pool_selftest','rtxpt_sparse_regions_selftest','--parallel','1','--','/p:MultiProcessorCompilation=false',('/bl:'+(Join-Path $build 'build.binlog')),('/flp:logfile='+(Join-Path $build 'build.log')+';verbosity=normal;encoding=UTF-8')) `
    -WorkingDirectory $sdk -MemoryLimitGB $MemoryLimitGB
if($LASTEXITCODE -ne 0){throw 'RTXPT JNI bridge build failed'}
if(!$SkipSelfTests){
& (Join-Path $sdk 'bin/rtxpt_streamline_cache_selftest.exe')
if($LASTEXITCODE -ne 0){throw 'Streamline function-cache self-test failed'}
& (Join-Path $sdk 'bin/rtxpt_task_batch_selftest.exe')
if($LASTEXITCODE -ne 0){throw 'RTXPT task-batch self-test failed'}
& (Join-Path $sdk 'bin/rtxpt_memory_diagnostics_selftest.exe')
if($LASTEXITCODE -ne 0){throw 'Disabled memory diagnostics self-test failed'}
& (Join-Path $sdk 'bin/rtxpt_memory_diagnostics_selftest.exe') enabled
if($LASTEXITCODE -ne 0){throw 'Enabled memory diagnostics self-test failed'}
}
& (Join-Path $sdk 'bin/rtxpt_capacity_selftest.exe')
if($LASTEXITCODE -ne 0){throw 'Resource capacity self-test failed'}
# Runtime redistribution keeps the exact upstream notices alongside the native payload.
$licenses=Join-Path $sdk 'bin/ThirdPartyLicenses'
New-Item -ItemType Directory -Path $licenses -Force | Out-Null
$noticeFiles=@{
    'LICENSE.txt'='RTXPT.txt'; 'External/Donut/LICENSE.txt'='Donut.txt';
    'External/Donut/ThirdPartyLicenses.txt'='Donut-ThirdParty.txt';
    'External/Donut/nvrhi/LICENSE.txt'='NVRHI.txt'; 'External/Donut/nvrhi/ThirdPartyLicenses.txt'='NVRHI-ThirdParty.txt';
    'External/Donut/ShaderMake/LICENSE.txt'='ShaderMake.txt'; 'External/Donut/ShaderMake/ThirdPartyLicenses.txt'='ShaderMake-ThirdParty.txt';
    'External/Nrd/LICENSE.txt'='NRD.txt'; 'External/Rtxdi/LICENSE.txt'='RTXDI.txt';
    'External/Rtxmu/LICENSE.txt'='RTXMU.txt';
    'External/SHARC/License.md'='SHARC.md'; 'External/Rtxtf/LICENSE.txt'='RTXTF.txt';
    'External/NVAPI/License.txt'='NVAPI.txt'; 'External/Omm/LICENSE.txt'='OMM.txt';
    'External/cxxopts/LICENSE'='cxxopts.txt'; 'External/dxc/LICENCE-MIT.txt'='DXC-MIT.txt';
    'External/dxc/LICENSE-LLVM.txt'='DXC-LLVM.txt'; 'External/dxc/LICENSE-MS.txt'='DXC-MS.txt';
    'External/AgilitySDK/1.619.6/LICENSE.txt'='D3D12.txt'; 'External/AgilitySDK/1.619.6/LICENSE-CODE.txt'='D3D12-Code.txt'
}
foreach($entry in $noticeFiles.GetEnumerator()){Copy-Item -LiteralPath (Join-Path $sdk $entry.Key) -Destination (Join-Path $licenses $entry.Value) -Force}
$streamlineRoot=if($StreamlineSdk){$streamline}else{Join-Path $build '_deps/streamline-src'}
if(!(Test-Path -LiteralPath (Join-Path $streamlineRoot 'license.txt'))) {
    # Reuse CMake's resolved dependency when this invocation omits the optional
    # SDK argument. Never embed a machine-specific SDK path in the project.
    $configured=Select-String -LiteralPath (Join-Path $build 'CMakeCache.txt') -Pattern '^FETCHCONTENT_SOURCE_DIR_STREAMLINE:PATH=(.+)$'
    if($configured){$streamlineRoot=$configured.Matches[0].Groups[1].Value}
}
Copy-Item -LiteralPath (Join-Path $streamlineRoot 'license.txt') -Destination (Join-Path $licenses 'Streamline.txt') -Force
Copy-Item -LiteralPath (Join-Path $streamlineRoot '3rd-party-licenses.md') -Destination (Join-Path $licenses 'Streamline-ThirdParty.md') -Force
