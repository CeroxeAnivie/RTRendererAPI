param(
    [Parameter(Mandatory)][ValidateSet('Lighting','Generation')][string]$Suite,
    [Parameter(Mandatory)][string]$Tag,
    [ValidateSet('all','points','spots','many','area','environment','sun','mixed','points-environment','points-sun','point-dominated','environment-dominated','double-sun','overlapping-suns')][string]$Scene='all',
    [ValidateRange(1,64)][int]$Spp=1,
    [switch]$ControlLighting,
    [switch]$ControlDirectional,
    [switch]$Reuse,
    [switch]$DisableResidualPointSampling,
    [switch]$ExactMis,
    [switch]$DirectionalStrata,
    [switch]$CombinedBsdf,
    [switch]$DirectOnly,
    [switch]$Relax,
    [switch]$RR,
    [switch]$RestirGI,
    [switch]$Sharc,
    [ValidateSet('AUTO','ULTRA_QUALITY','QUALITY','BALANCED','PERFORMANCE','ULTRA_PERFORMANCE')]
    [string]$DLSSQuality='AUTO'
)
$ErrorActionPreference='Stop'
if($Relax -and $RR){throw 'Choose either NRD RELAX or DLSS Ray Reconstruction.'}
if($DirectOnly -and ($Reuse -or $RestirGI -or $Sharc)){throw 'Direct-only cannot be combined with light reuse.'}
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$repo=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location -LiteralPath $repo
if((& git status --porcelain | Where-Object {$_ -notmatch '^.. docs/quality/data/'})) {
    throw 'Commit source changes before hardware verification.'
}
$evidence=Join-Path $repo 'docs/quality/data/hexball-native-20261101'
New-Item -ItemType Directory -Path $evidence -Force | Out-Null
$prefix=Join-Path $evidence $Tag
if(Test-Path -LiteralPath ($prefix+'.log')){throw 'Use a new evidence tag.'}
$output=Join-Path $repo ('build/reports/rtxpt-lighting/'+$Tag)
$jar=Join-Path $repo 'demos/hex-ball/build/libs/RTRendererAPI-HexBallDemo-7.0.0.jar'
$classpath='demos/hex-ball/build/classes/java/test;renderer-rtxpt/build/classes/java/test;'+$jar
$lightingFeatures=@()
if($Reuse){$lightingFeatures+='reuse'}
if($RestirGI){$lightingFeatures+='restir-gi'}
if($Sharc){$lightingFeatures+='sharc'}
if($DirectOnly){$lightingFeatures+='direct'}
$lightingMode=if($lightingFeatures.Count){$lightingFeatures -join '+'}else{'raw'}
$settings=[ordered]@{
    SourceCommit=(& git rev-parse HEAD).Trim()
    Java='C:\Program Files\Java\graalvm-jdk-25.0.5.0.2+1.1\bin\java.exe'
    Jvm=@('-Dfile.encoding=UTF-8','--enable-native-access=ALL-UNNAMED','--sun-misc-unsafe-memory-access=allow',
        '-Xmx3g','-XX:-UseJVMCICompiler','-cp',$classpath)
    Entry=$(if($Suite -eq 'Generation'){'demo.RtxptHexBallGenerationSelfTest'}else{'top.ceroxe.rt.renderer.rtxpt.RtxptLightingNativeSelfTest'})
    Arguments=@(if($Suite -eq 'Generation'){$output}else{$output;$Scene;[string]$Spp;$lightingMode;$(if($Relax){'relax'}elseif($RR){'rr'}else{'none'});$DLSSQuality})
    RuntimeSource='PackagedJar';JarSha256=(Get-FileHash -LiteralPath $jar).Hash
    ControlLighting=[bool]$ControlLighting;ControlDirectional=[bool]$ControlDirectional;Reuse=[bool]$Reuse
    DisableResidualPointSampling=[bool]$DisableResidualPointSampling;ExactMis=[bool]$ExactMis;DirectOnly=[bool]$DirectOnly
    Relax=[bool]$Relax;RayReconstruction=[bool]$RR;RestirGI=[bool]$RestirGI;Sharc=[bool]$Sharc;DLSSQuality=$DLSSQuality
    DirectionalStrata=[bool]$DirectionalStrata;
    CombinedBsdf=[bool]$CombinedBsdf;
    Spp=$Spp;StartedAt=(Get-Date -Format o)
}
$settings | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath ($prefix+'-settings.json') -Encoding utf8
& nvidia-smi --query-gpu=name,utilization.gpu,memory.used --format=csv > ($prefix+'-initial-gpu.csv')
$oldPoints=$env:RTR_RTXPT_DIAGNOSTIC_SMALL_POINTS
$oldDirectional=$env:RTR_RTXPT_DIAGNOSTIC_DIRECTIONAL_CONTROL
$oldProfile=$env:RTR_RTXPT_GPU_PROFILE
$oldResidual=$env:RTR_RTXPT_DIAGNOSTIC_DISABLE_RESIDUAL_SAMPLING
$oldMis=$env:RTR_RTXPT_DIAGNOSTIC_EXACT_MIS
$oldDirectionalStrata=$env:RTR_RTXPT_DIAGNOSTIC_DIRECTIONAL_STRATA
$oldCombinedBsdf=$env:RTR_RTXPT_DIAGNOSTIC_MIXTURE_BSDF
try {
    $env:RTR_RTXPT_DIAGNOSTIC_SMALL_POINTS=$(if($ControlLighting){'0'}else{$null})
    $env:RTR_RTXPT_DIAGNOSTIC_DIRECTIONAL_STRATA=$(if($DirectionalStrata){'1'}else{$null})
    $env:RTR_RTXPT_DIAGNOSTIC_MIXTURE_BSDF=$(if($CombinedBsdf){'1'}else{$null})
    $env:RTR_RTXPT_DIAGNOSTIC_DIRECTIONAL_CONTROL=$(if($ControlDirectional){'1'}else{$null})
    $env:RTR_RTXPT_GPU_PROFILE=$null
    $env:RTR_RTXPT_DIAGNOSTIC_DISABLE_RESIDUAL_SAMPLING=$(if($DisableResidualPointSampling){'1'}else{$null})
    $env:RTR_RTXPT_DIAGNOSTIC_EXACT_MIS=$(if($ExactMis){'1'}else{$null})
    & python (Join-Path $PSScriptRoot 'Run-HexBallProcess.py') $prefix
    $code=$LASTEXITCODE
    Get-Content -LiteralPath ($prefix+'.log') -Tail 8
    if(Test-Path -LiteralPath (Join-Path $output 'generation.csv')) {
        Copy-Item -LiteralPath (Join-Path $output 'generation.csv') -Destination ($prefix+'-generation.csv')
    }
    if(Test-Path -LiteralPath (Join-Path $output 'timings.csv')) {
        Copy-Item -LiteralPath (Join-Path $output 'timings.csv') -Destination ($prefix+'-timings.csv')
    }
    if($code -ne 0){throw "Native regression exited $code; inspect $prefix.log"}
} finally {
    $env:RTR_RTXPT_DIAGNOSTIC_SMALL_POINTS=$oldPoints
    $env:RTR_RTXPT_DIAGNOSTIC_DIRECTIONAL_CONTROL=$oldDirectional
    $env:RTR_RTXPT_GPU_PROFILE=$oldProfile
    $env:RTR_RTXPT_DIAGNOSTIC_DISABLE_RESIDUAL_SAMPLING=$oldResidual
    $env:RTR_RTXPT_DIAGNOSTIC_EXACT_MIS=$oldMis
    $env:RTR_RTXPT_DIAGNOSTIC_DIRECTIONAL_STRATA=$oldDirectionalStrata
    $env:RTR_RTXPT_DIAGNOSTIC_MIXTURE_BSDF=$oldCombinedBsdf
}
