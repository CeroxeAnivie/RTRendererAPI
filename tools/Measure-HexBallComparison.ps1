param(
    [ValidateSet('Vulkan','Rtxpt')][string]$Backend='Rtxpt',
    [Parameter(Mandatory)][string]$Tag,
    [int]$Seconds=30,
    [int]$Width=2560,
    [int]$Height=1440,
    [switch]$StageProfile,
    [switch]$FrameGeneration,
    [switch]$PresentationTrace,
    [switch]$WorkTrace,
    [switch]$PresentMon,
    [switch]$SmallPointCandidate,
    [switch]$ControlLighting,
    [switch]$DisableResidualPointSampling,
    [switch]$ExactMis,
    [switch]$DirectionalStrata,
    [switch]$CombinedBsdf,
    [switch]$LegacySrHistory,
    [switch]$LegacyLighting,
    [switch]$ApproximateMis,
    [ValidateSet('Default','Power','NeeAt')][string]$LightSampler='Default',
    [switch]$Glass,
    [switch]$AllowDirtySource,
    [switch]$DisableTransmissionGuides,
    [ValidateSet('Upstream','D','E','F')][string]$RrPreset='Upstream',
    [ValidateSet('Upstream','J','K','M')][string]$SrPreset='Upstream',
    [ValidateRange(0.0,64.0)][double]$FixedSrExposure=0,
    [switch]$DisableBloom,
    [ValidateSet('Upstream','On','Off')][string]$AsyncPrefix='Upstream',
    [string[]]$ExtraArguments=@(),
    [string]$OutputDirectory='docs/quality/data/hexball-native-20261101'
)
$ErrorActionPreference='Stop'
if($SmallPointCandidate -and $ControlLighting){throw 'Choose candidate or original lighting control, not both.'}
if($ExactMis -and $ApproximateMis){throw 'Choose exact or approximate MIS, not both.'}
if($Backend -ne 'Rtxpt' -and $FrameGeneration){throw 'This FG measurement is for the RTXPT path.'}
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$repo=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location -LiteralPath $repo
$opticalHeader=Join-Path $repo 'build/rtxpt-sdk/Rtxpt/Shaders/PathTracer/RtrLegacyTransportTrace.hlsli'
$opticalSource=[IO.File]::ReadAllText($opticalHeader)
if($Backend -eq 'Rtxpt' -and ($opticalSource -match '#define RTR_LEGACY_TRANSPORT_TRACE 1' -or
        $opticalSource -match '#define RTR_LEGACY_RAW_OPTICAL_ROUGHNESS 1')) {
    throw 'Disable optical tracing and diagnostic roughness controls before measuring FPS: tools/Build-RtxptOpticalTrace.ps1'
}
if(!$AllowDirtySource -and (& git status --porcelain | Where-Object {$_ -notmatch '^\?\? docs/quality/data/' -and $_ -notmatch '^.. docs/quality/data/'})) {
    throw 'Commit source changes before measuring hardware.'
}
$sourcePaths=@((& git diff --name-only HEAD -- . ':(exclude)docs/quality/data/**') +
    (& git ls-files --others --exclude-standard | Where-Object {$_ -notlike 'docs/quality/data/*'})) | Sort-Object -Unique
$sourceManifest=@($sourcePaths | ForEach-Object {
    if(Test-Path -LiteralPath $_ -PathType Leaf){"$_=$((Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash)"}
})
$sourceDigestHasher=[Security.Cryptography.SHA256]::Create()
try {
    $sourceDigestInput=[Text.Encoding]::UTF8.GetBytes(($sourceManifest -join "`n"))
    $sourceDigest=([BitConverter]::ToString($sourceDigestHasher.ComputeHash($sourceDigestInput))).Replace('-','')
}
finally {$sourceDigestHasher.Dispose()}
$output=Join-Path $repo $OutputDirectory
New-Item -ItemType Directory -Path $output -Force | Out-Null
$prefix=Join-Path $output $Tag
if(Test-Path -LiteralPath ($prefix+'.log')){throw 'Use a new evidence tag; do not overwrite an earlier measurement.'}
$java='C:\Program Files\Java\graalvm-jdk-25.0.5.0.2+1.1\bin\java.exe'
$classpath=@(
    'demos/hex-ball/build/classes/java/main','demos/hex-ball/build/resources/main',
    'renderer-api/build/classes/java/main',
    'renderer-core/build/classes/java/main','renderer-core/build/resources/main','renderer-core/build/libs/renderer-core-7.0.0.jar',
    'renderer-nvidia/build/libs/renderer-nvidia-7.0.0.jar',
    'renderer-rtxpt/build/classes/java/main','renderer-rtxpt/build/resources/main','renderer-rtxpt/build/libs/renderer-rtxpt-7.0.0.jar',
    'demos/hex-ball/build/libs/RTRendererAPI-HexBallDemo-7.0.0.jar'
) -join ';'
$jvm=@('-Dfile.encoding=UTF-8',('-Ddemo.disable-fg='+(!$FrameGeneration).ToString().ToLowerInvariant()),
    ('-Dtop.ceroxe.rt.rtxpt.runtime='+(Join-Path $repo 'build/rtxpt-sdk/bin')),
    '--enable-native-access=ALL-UNNAMED','--sun-misc-unsafe-memory-access=allow',
    '-Xmx3g','-XX:-UseJVMCICompiler','-cp',$classpath)
if ($Backend -eq 'Vulkan' -and $StageProfile) {
    $jvm = @('-Dtop.ceroxe.rt.rt.gpuTimestamps.frameStages=true') + $jvm
}
$entry=if($Backend -eq 'Rtxpt'){'demo.RtxptHexBallDemo'}else{'demo.HexBallDemo'}
$demo=@('--windowed',"--width=$Width","--height=$Height",'--spp=1',"--duration-seconds=$Seconds")
if($Backend -eq 'Rtxpt' -and !$Glass){$demo+='--original-balls'}
if($Backend -eq 'Rtxpt' -and !$StageProfile){$demo+='--api.gpuTimingsEnabled=false'}
$demo+=$ExtraArguments
$oldProfile=$env:RTR_RTXPT_GPU_PROFILE
$oldPresentationTrace=$env:RTR_RTXPT_PRESENTATION_TRACE
$oldOpticalTrace=$env:RTR_RTXPT_DIAGNOSTIC_OPTICAL_TRACE
$oldAsync=$env:RTR_RTXPT_DIAGNOSTIC_ASYNC_PREFIX
$oldPreset=$env:RTR_RTXPT_DIAGNOSTIC_RR_PRESET
$oldSrPreset=$env:RTR_RTXPT_DIAGNOSTIC_SR_PRESET
$oldExposure=$env:RTR_RTXPT_DIAGNOSTIC_SR_EXPOSURE
$oldBloom=$env:RTR_RTXPT_DIAGNOSTIC_DISABLE_BLOOM
$oldSmallPoints=$env:RTR_RTXPT_DIAGNOSTIC_SMALL_POINTS
$oldResidual=$env:RTR_RTXPT_DIAGNOSTIC_DISABLE_RESIDUAL_SAMPLING
$oldMis=$env:RTR_RTXPT_DIAGNOSTIC_EXACT_MIS
$oldDirectionalStrata=$env:RTR_RTXPT_DIAGNOSTIC_DIRECTIONAL_STRATA
$oldCombinedBsdf=$env:RTR_RTXPT_DIAGNOSTIC_MIXTURE_BSDF
$oldHistory=$env:RTR_RTXPT_DIAGNOSTIC_LEGACY_SR_HISTORY
$oldLegacyLighting=$env:RTR_RTXPT_DIAGNOSTIC_LEGACY_LIGHTING
$oldSampler=$env:RTR_RTXPT_DIAGNOSTIC_LIGHT_SAMPLER
$oldTransmissionGuides=$env:RTR_RTXPT_DIAGNOSTIC_DISABLE_TRANSMISSION_GUIDES
try {
    $env:RTR_RTXPT_DIAGNOSTIC_OPTICAL_TRACE=$null
    $env:RTR_RTXPT_PRESENTATION_TRACE=$(if($PresentationTrace){'1'}else{$null})
    if($WorkTrace -ne ($opticalSource -match '#define RTR_LEGACY_WORK_TRACE 1')) {
        throw 'Work-trace option must match the configured shader artifact.'
    }
    $env:RTR_RTXPT_GPU_PROFILE=if($StageProfile){$prefix+'-gpu.csv'}else{$null}
    $env:RTR_RTXPT_DIAGNOSTIC_ASYNC_PREFIX=switch($AsyncPrefix){'On'{'1'} 'Off'{'0'} default{$null}}
    $env:RTR_RTXPT_DIAGNOSTIC_RR_PRESET=if($RrPreset -eq 'Upstream'){$null}else{$RrPreset}
    $env:RTR_RTXPT_DIAGNOSTIC_SR_PRESET=if($SrPreset -eq 'Upstream'){$null}else{$SrPreset}
    $env:RTR_RTXPT_DIAGNOSTIC_SR_EXPOSURE=$(if($FixedSrExposure -gt 0){$FixedSrExposure.ToString([Globalization.CultureInfo]::InvariantCulture)}else{$null})
    $env:RTR_RTXPT_DIAGNOSTIC_DISABLE_BLOOM=$(if($DisableBloom){'1'}else{$null})
    $env:RTR_RTXPT_DIAGNOSTIC_SMALL_POINTS=$(if($ControlLighting){'0'}elseif($SmallPointCandidate){'1'}else{$null})
    $env:RTR_RTXPT_DIAGNOSTIC_DIRECTIONAL_STRATA=$(if($DirectionalStrata){'1'}else{$null})
    $env:RTR_RTXPT_DIAGNOSTIC_MIXTURE_BSDF=$(if($CombinedBsdf){'1'}else{$null})
    $env:RTR_RTXPT_DIAGNOSTIC_LEGACY_SR_HISTORY=$(if($LegacySrHistory){'1'}else{$null})
    $env:RTR_RTXPT_DIAGNOSTIC_LEGACY_LIGHTING=$(if($LegacyLighting){'1'}else{$null})
    $env:RTR_RTXPT_DIAGNOSTIC_DISABLE_RESIDUAL_SAMPLING=$(if($DisableResidualPointSampling){'1'}else{$null})
    $env:RTR_RTXPT_DIAGNOSTIC_EXACT_MIS=$(if($ExactMis){'1'}elseif($ApproximateMis){'0'}else{$null})
    $env:RTR_RTXPT_DIAGNOSTIC_LIGHT_SAMPLER=$(switch($LightSampler){'Power'{'power'} 'NeeAt'{'neeat'} default{$null}})
    $env:RTR_RTXPT_DIAGNOSTIC_DISABLE_TRANSMISSION_GUIDES=$(if($DisableTransmissionGuides){'1'}else{$null})
    & nvidia-smi --query-gpu=name,driver_version,utilization.gpu,memory.used,power.draw,clocks.current.graphics,temperature.gpu --format=csv > ($prefix+'-initial-gpu.csv')
    $initialGpu=(& nvidia-smi --id=0 --query-gpu=utilization.gpu --format=csv,noheader,nounits).Trim()
    if($initialGpu -ne '0'){throw "GPU 0 is busy ($initialGpu%); wait for the user's idle notification before absolute FPS measurement."}
    $settings=[ordered]@{
        SourceCommit=(& git rev-parse HEAD).Trim();Backend=$Backend;Entry=$entry;
        WorkingTreeSourceSha256=$sourceDigest;WorkingTreeSourceFiles=$sourcePaths;
    DirectionalStrata=[bool]$DirectionalStrata;
    CombinedBsdf=[bool]$CombinedBsdf;
    LegacySrHistory=[bool]$LegacySrHistory;
    LegacyLighting=[bool]$LegacyLighting;
        Width=$Width;Height=$Height;Spp=1;FrameGeneration=[bool]$FrameGeneration;StageProfile=[bool]$StageProfile;
        OpticalTransportTrace=$false;NativeGpuTimings=[bool]$StageProfile;
        OpticalWorkTrace=[bool]$WorkTrace;PresentationTrace=[bool]$PresentationTrace;
        PresentMon=[bool]$PresentMon;
        OpticalTransportTraceSha256=(Get-FileHash -LiteralPath $opticalHeader).Hash;
        ExactOpticalInterfaces=([regex]::Match($opticalSource,'#define RTR_LEGACY_EXACT_INTERFACE_COUNT (\d+)u')).Groups[1].Value;
        ReplayPrefixCache=($opticalSource -notmatch '#define RTR_LEGACY_REPLAY_PREFIX_CACHE 0');
        ReplayPrefixCacheSha256=(Get-FileHash -LiteralPath 'build/rtxpt-sdk/Rtxpt/Shaders/PathTracer/RtrLegacyReplayCache.hlsli').Hash;
        NativeRoughnessPolicy=($opticalSource -notmatch '#define RTR_LEGACY_RAW_OPTICAL_ROUGHNESS 1');
        FinalMergeShaderSha256=(Get-FileHash -LiteralPath 'build/rtxpt-sdk/bin/ShaderPrecompiled/Rtxpt/dxil/ProcessingPasses/PostProcess.bin').Hash;
        RrPreset=$RrPreset;AsyncPrefix=$AsyncPrefix;
        SrPreset=$SrPreset;
        FixedSrExposure=$FixedSrExposure;DisableBloom=[bool]$DisableBloom;
        SmallPointCandidate=[bool]$SmallPointCandidate;
        ControlLighting=[bool]$ControlLighting;
        DisableResidualPointSampling=[bool]$DisableResidualPointSampling;ExactMis=[bool]$ExactMis;Glass=[bool]$Glass;
        ApproximateMis=[bool]$ApproximateMis;LightSampler=$LightSampler;
        DisableTransmissionGuides=[bool]$DisableTransmissionGuides;
        Java=$java;Jvm=$jvm;Arguments=$demo;StartedAt=(Get-Date -Format o);
        NativeSha256=(Get-FileHash -LiteralPath 'build/rtxpt-sdk/bin/rtrenderer_rtxpt.dll' -Algorithm SHA256).Hash
    }
    $settings | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath ($prefix+'-settings.json') -Encoding utf8
    & python (Join-Path $PSScriptRoot 'Run-HexBallProcess.py') $prefix
    $code=$LASTEXITCODE
    $runLog=Get-Content -LiteralPath ($prefix+'.log') -Raw
    if((!$WorkTrace -and $runLog -match '\[unformatted args\]') -or $runLog -match 'single queue to preserve the final SR-input readback'){
        throw 'Shader diagnostic output was unexpectedly active; discard this FPS run.'
    }
    if($LegacyLighting -and $runLog -notmatch 'RTXPT legacy lighting inputs ACTIVE'){
        throw 'Requested legacy lighting inputs did not activate.'
    }
    if($FixedSrExposure -gt 0 -and $runLog -notmatch 'RTXPT explicit SR exposure texture='){
        throw 'Fixed SR exposure was requested but was not tagged; discard this run.'
    }
    if($Glass -and $DisableTransmissionGuides -and $runLog -notmatch 'disabled for diagnostic control'){
        throw 'Transmission-guide control was not active; discard this run as invalid evidence.'
    }
    if($Glass -and !$DisableTransmissionGuides -and $runLog -notmatch 'defaultedToTransmission=[1-9][0-9]*'){
        throw 'RTXPT did not configure any transmissive material guide; discard this run as invalid evidence.'
    }
    if($SmallPointCandidate -and $runLog -notmatch 'RTXPT exact point sum ACTIVE'){
        throw 'Candidate was not active for this scene; discard this run as invalid evidence.'
    }
    if($ControlLighting -and $runLog -match 'RTXPT exact point sum ACTIVE'){
        throw 'Lighting control unexpectedly enabled exact point sampling.'
    }
    Get-Content -LiteralPath ($prefix+'.log') -Tail 5
    if($code -ne 0){throw "Hex-ball run exited $code; inspect $prefix.log"}
} finally {
    $env:RTR_RTXPT_PRESENTATION_TRACE=$oldPresentationTrace
    $env:RTR_RTXPT_DIAGNOSTIC_OPTICAL_TRACE=$oldOpticalTrace
    $env:RTR_RTXPT_GPU_PROFILE=$oldProfile
    $env:RTR_RTXPT_DIAGNOSTIC_ASYNC_PREFIX=$oldAsync
    $env:RTR_RTXPT_DIAGNOSTIC_RR_PRESET=$oldPreset
    $env:RTR_RTXPT_DIAGNOSTIC_SR_PRESET=$oldSrPreset
    $env:RTR_RTXPT_DIAGNOSTIC_SR_EXPOSURE=$oldExposure
    $env:RTR_RTXPT_DIAGNOSTIC_DISABLE_BLOOM=$oldBloom
    $env:RTR_RTXPT_DIAGNOSTIC_SMALL_POINTS=$oldSmallPoints
    $env:RTR_RTXPT_DIAGNOSTIC_DISABLE_RESIDUAL_SAMPLING=$oldResidual
    $env:RTR_RTXPT_DIAGNOSTIC_EXACT_MIS=$oldMis
    $env:RTR_RTXPT_DIAGNOSTIC_DIRECTIONAL_STRATA=$oldDirectionalStrata
    $env:RTR_RTXPT_DIAGNOSTIC_MIXTURE_BSDF=$oldCombinedBsdf
    $env:RTR_RTXPT_DIAGNOSTIC_LEGACY_SR_HISTORY=$oldHistory
    $env:RTR_RTXPT_DIAGNOSTIC_LEGACY_LIGHTING=$oldLegacyLighting
    $env:RTR_RTXPT_DIAGNOSTIC_LIGHT_SAMPLER=$oldSampler
    $env:RTR_RTXPT_DIAGNOSTIC_DISABLE_TRANSMISSION_GUIDES=$oldTransmissionGuides
}
