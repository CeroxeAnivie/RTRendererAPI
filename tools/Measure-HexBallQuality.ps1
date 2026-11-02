param(
    [Parameter(Mandatory)][ValidateSet('raw','relax','default','reuse-raw','reuse-relax','rr','reference','direct','direct-reference')][string]$Mode,
    [Parameter(Mandatory)][string]$Tag,
    [switch]$Glass,
    [switch]$PackagedRuntime,
    [switch]$SmallPointCandidate,
    [switch]$ControlLighting,
    [switch]$DisableResidualPointSampling,
    [switch]$ExactMis,
    [switch]$DirectionalStrata,
    [switch]$ApproximateMis,
    [ValidateSet('Default','Power','NeeAt')][string]$LightSampler='Default',
    [switch]$Sharc,
    [switch]$RestirGi,
    [ValidateSet('AUTO','ULTRA_QUALITY','QUALITY','BALANCED','PERFORMANCE','ULTRA_PERFORMANCE','DLAA')][string]$Quality='AUTO',
    [ValidateSet('Upstream','D','E','F')][string]$RrPreset='Upstream',
    [ValidateSet('Upstream','J','K','M')][string]$SrPreset='Upstream',
    [ValidateRange(0.0,64.0)][double]$FixedSrExposure=0,
    [switch]$DisableBloom,
    [ValidateSet('none','original-lights','quarter-lights','no-points','no-sun','no-sky','no-environment')][string]$LightingProbe='none',
    [switch]$AllowDirtySource,
    [switch]$DisableTransmissionGuides
)
$ErrorActionPreference='Stop'
if($SmallPointCandidate -and $ControlLighting){throw 'Choose candidate or original lighting control, not both.'}
if($ExactMis -and $ApproximateMis){throw 'Choose exact or approximate MIS, not both.'}
if($RestirGi -and $Mode -ne 'raw'){throw 'ReSTIR GI isolation requires raw mode.'}
if($Sharc -and $RestirGi){throw 'Choose SHaRC or ReSTIR GI for isolated testing.'}
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$repo=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location -LiteralPath $repo
$effectiveQuality=$Quality
if($effectiveQuality -eq 'AUTO'){$effectiveQuality='BALANCED'}
if(!$AllowDirtySource -and (& git status --porcelain | Where-Object {$_ -notmatch '^.. docs/quality/data/'})) {
    throw 'Commit source changes before hardware verification.'
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
$output=Join-Path $repo 'docs/quality/data/hexball-native-20261101'
New-Item -ItemType Directory -Path $output -Force | Out-Null
$prefix=Join-Path $output $Tag
if(Test-Path -LiteralPath ($prefix+'.log')){throw 'Use a new evidence tag.'}
$images=Join-Path $repo ('build/reports/hexball-quality/'+$Tag)
$classpath=@(
    'demos/hex-ball/build/classes/java/test','demos/hex-ball/build/classes/java/main',
    'demos/hex-ball/build/resources/main','renderer-api/build/classes/java/main',
    'renderer-core/build/classes/java/main','renderer-core/build/resources/main',
    'renderer-nvidia/build/libs/renderer-nvidia-7.0.0.jar',
    'renderer-rtxpt/build/classes/java/main','renderer-rtxpt/build/resources/main',
    'demos/hex-ball/build/libs/RTRendererAPI-HexBallDemo-7.0.0.jar'
) -join ';'
if($PackagedRuntime) {
    $classpath='demos/hex-ball/build/classes/java/test;demos/hex-ball/build/libs/RTRendererAPI-HexBallDemo-7.0.0.jar'
}
$settings=[ordered]@{
    SourceCommit=(& git rev-parse HEAD).Trim();
    Java='C:\Program Files\Java\graalvm-jdk-25.0.5.0.2+1.1\bin\java.exe';
    Jvm=@('-Dfile.encoding=UTF-8',('-Dtop.ceroxe.rt.rtxpt.runtime='+(Join-Path $repo 'build/rtxpt-sdk/bin')),
        '--enable-native-access=ALL-UNNAMED','--sun-misc-unsafe-memory-access=allow',
        '-Xmx3g','-XX:-UseJVMCICompiler','-cp',$classpath);
    Entry='demo.RtxptHexBallQualitySelfTest';Arguments=@($Mode,$(if($Glass){'glass'}else{'metal'}),$images,$LightingProbe,$(if($Sharc){'sharc'}elseif($RestirGi){'restirgi'}else{'none'}),$effectiveQuality);
    StartedAt=(Get-Date -Format o);NativeSha256=(Get-FileHash 'build/rtxpt-sdk/bin/rtrenderer_rtxpt.dll').Hash;
    WorkingTreeSourceSha256=$sourceDigest;WorkingTreeSourceFiles=$sourcePaths;
    Width=2560;Height=1440;Spp=$(if($Mode -in @('reference','direct-reference')){32}else{1});FrameGeneration=$false;StageProfile=$false
    RuntimeSource=$(if($PackagedRuntime){'PackagedJar'}else{'BuildDirectory'})
    DirectionalStrata=[bool]$DirectionalStrata;
    SmallPointCandidate=[bool]$SmallPointCandidate
    ControlLighting=[bool]$ControlLighting
    DisableResidualPointSampling=[bool]$DisableResidualPointSampling
    ExactMis=[bool]$ExactMis
    ApproximateMis=[bool]$ApproximateMis
    LightSampler=$LightSampler
    Sharc=[bool]$Sharc
    RestirGi=[bool]$RestirGi
    DisableTransmissionGuides=[bool]$DisableTransmissionGuides
    Quality=$effectiveQuality
    RrPreset=$RrPreset
    SrPreset=$SrPreset
    FixedSrExposure=$FixedSrExposure;DisableBloom=[bool]$DisableBloom
    LightingProbe=$LightingProbe
}
if($PackagedRuntime) {
    $settings.Jvm=@($settings.Jvm | Where-Object {$_ -notlike '-Dtop.ceroxe.rt.rtxpt.runtime=*'})
    $settings.JarSha256=(Get-FileHash 'demos/hex-ball/build/libs/RTRendererAPI-HexBallDemo-7.0.0.jar').Hash
}
$settings | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath ($prefix+'-settings.json') -Encoding utf8
& nvidia-smi --query-gpu=name,utilization.gpu,memory.used --format=csv > ($prefix+'-initial-gpu.csv')
$oldProfile=$env:RTR_RTXPT_GPU_PROFILE
$oldSmallPoints=$env:RTR_RTXPT_DIAGNOSTIC_SMALL_POINTS
$oldResidual=$env:RTR_RTXPT_DIAGNOSTIC_DISABLE_RESIDUAL_SAMPLING
$oldMis=$env:RTR_RTXPT_DIAGNOSTIC_EXACT_MIS
$oldDirectionalStrata=$env:RTR_RTXPT_DIAGNOSTIC_DIRECTIONAL_STRATA
$oldSampler=$env:RTR_RTXPT_DIAGNOSTIC_LIGHT_SAMPLER
$oldTransmissionGuides=$env:RTR_RTXPT_DIAGNOSTIC_DISABLE_TRANSMISSION_GUIDES
$oldRrPreset=$env:RTR_RTXPT_DIAGNOSTIC_RR_PRESET
$oldSrPreset=$env:RTR_RTXPT_DIAGNOSTIC_SR_PRESET
$oldExposure=$env:RTR_RTXPT_DIAGNOSTIC_SR_EXPOSURE
$oldBloom=$env:RTR_RTXPT_DIAGNOSTIC_DISABLE_BLOOM
try {
    $env:RTR_RTXPT_GPU_PROFILE=$null
    $env:RTR_RTXPT_DIAGNOSTIC_SMALL_POINTS=$(if($ControlLighting){'0'}elseif($SmallPointCandidate){'1'}else{$null})
    $env:RTR_RTXPT_DIAGNOSTIC_DIRECTIONAL_STRATA=$(if($DirectionalStrata){'1'}else{$null})
    $env:RTR_RTXPT_DIAGNOSTIC_DISABLE_RESIDUAL_SAMPLING=$(if($DisableResidualPointSampling){'1'}else{$null})
    $env:RTR_RTXPT_DIAGNOSTIC_EXACT_MIS=$(if($ExactMis){'1'}elseif($ApproximateMis){'0'}else{$null})
    $env:RTR_RTXPT_DIAGNOSTIC_LIGHT_SAMPLER=$(switch($LightSampler){'Power'{'power'} 'NeeAt'{'neeat'} default{$null}})
    $env:RTR_RTXPT_DIAGNOSTIC_DISABLE_TRANSMISSION_GUIDES=$(if($DisableTransmissionGuides){'1'}else{$null})
    $env:RTR_RTXPT_DIAGNOSTIC_RR_PRESET=$(if($RrPreset -eq 'Upstream'){$null}else{$RrPreset})
    $env:RTR_RTXPT_DIAGNOSTIC_SR_PRESET=$(if($SrPreset -eq 'Upstream'){$null}else{$SrPreset})
    $env:RTR_RTXPT_DIAGNOSTIC_SR_EXPOSURE=$(if($FixedSrExposure -gt 0){$FixedSrExposure.ToString([Globalization.CultureInfo]::InvariantCulture)}else{$null})
    $env:RTR_RTXPT_DIAGNOSTIC_DISABLE_BLOOM=$(if($DisableBloom){'1'}else{$null})
    & python (Join-Path $PSScriptRoot 'Run-HexBallProcess.py') $prefix
    $code=$LASTEXITCODE
    $runLog=Get-Content -LiteralPath ($prefix+'.log') -Raw
    if($DirectionalStrata -and $LightingProbe -notin @('no-sun','no-environment') -and
        $runLog -notmatch 'RTXPT directional environment strata ACTIVE count=[1-9]'){
        throw 'Directional strata were requested but not active; discard this run.'
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
    if($code -ne 0){throw "Quality verification exited $code; inspect $prefix.log"}
} finally {
    $env:RTR_RTXPT_GPU_PROFILE=$oldProfile
    $env:RTR_RTXPT_DIAGNOSTIC_SMALL_POINTS=$oldSmallPoints
    $env:RTR_RTXPT_DIAGNOSTIC_DISABLE_RESIDUAL_SAMPLING=$oldResidual
    $env:RTR_RTXPT_DIAGNOSTIC_EXACT_MIS=$oldMis
    $env:RTR_RTXPT_DIAGNOSTIC_DIRECTIONAL_STRATA=$oldDirectionalStrata
    $env:RTR_RTXPT_DIAGNOSTIC_LIGHT_SAMPLER=$oldSampler
    $env:RTR_RTXPT_DIAGNOSTIC_DISABLE_TRANSMISSION_GUIDES=$oldTransmissionGuides
    $env:RTR_RTXPT_DIAGNOSTIC_RR_PRESET=$oldRrPreset
    $env:RTR_RTXPT_DIAGNOSTIC_SR_PRESET=$oldSrPreset
    $env:RTR_RTXPT_DIAGNOSTIC_SR_EXPOSURE=$oldExposure
    $env:RTR_RTXPT_DIAGNOSTIC_DISABLE_BLOOM=$oldBloom
}
