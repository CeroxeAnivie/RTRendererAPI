param(
    [Parameter(Mandatory)][ValidateSet('Lighting','Generation')][string]$Suite,
    [Parameter(Mandatory)][string]$Tag,
    [ValidateSet('all','points','spots','many','area','environment','sun','mixed','points-environment','points-sun','point-dominated','environment-dominated')][string]$Scene='all',
    [ValidateRange(1,64)][int]$Spp=1,
    [switch]$ControlLighting,
    [switch]$ControlDirectional,
    [switch]$Reuse,
    [switch]$SplitPointEnvironment,
    [switch]$ExactMis,
    [switch]$DirectOnly
)
$ErrorActionPreference='Stop'
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
$settings=[ordered]@{
    SourceCommit=(& git rev-parse HEAD).Trim()
    Java='C:\Program Files\Java\graalvm-jdk-25.0.5.0.2+1.1\bin\java.exe'
    Jvm=@('-Dfile.encoding=UTF-8','--enable-native-access=ALL-UNNAMED','--sun-misc-unsafe-memory-access=allow',
        '-Xmx3g','-XX:-UseJVMCICompiler','-cp',$classpath)
    Entry=$(if($Suite -eq 'Generation'){'demo.RtxptHexBallGenerationSelfTest'}else{'top.ceroxe.rt.renderer.rtxpt.RtxptLightingNativeSelfTest'})
    Arguments=@(if($Suite -eq 'Generation'){$output}else{$output;$Scene;[string]$Spp;$(if($Reuse){'reuse'}elseif($DirectOnly){'direct'}else{'raw'})})
    RuntimeSource='PackagedJar';JarSha256=(Get-FileHash -LiteralPath $jar).Hash
    ControlLighting=[bool]$ControlLighting;ControlDirectional=[bool]$ControlDirectional;Reuse=[bool]$Reuse
    SplitPointEnvironment=[bool]$SplitPointEnvironment;ExactMis=[bool]$ExactMis;DirectOnly=[bool]$DirectOnly
    Spp=$Spp;StartedAt=(Get-Date -Format o)
}
$settings | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath ($prefix+'-settings.json') -Encoding utf8
& nvidia-smi --query-gpu=name,utilization.gpu,memory.used --format=csv > ($prefix+'-initial-gpu.csv')
$oldPoints=$env:RTR_RTXPT_DIAGNOSTIC_SMALL_POINTS
$oldDirectional=$env:RTR_RTXPT_DIAGNOSTIC_DIRECTIONAL_CONTROL
$oldProfile=$env:RTR_RTXPT_GPU_PROFILE
$oldSplit=$env:RTR_RTXPT_DIAGNOSTIC_SPLIT_POINT_ENVIRONMENT
$oldMis=$env:RTR_RTXPT_DIAGNOSTIC_EXACT_MIS
try {
    $env:RTR_RTXPT_DIAGNOSTIC_SMALL_POINTS=$(if($ControlLighting){'0'}else{$null})
    $env:RTR_RTXPT_DIAGNOSTIC_DIRECTIONAL_CONTROL=$(if($ControlDirectional){'1'}else{$null})
    $env:RTR_RTXPT_GPU_PROFILE=$null
    $env:RTR_RTXPT_DIAGNOSTIC_SPLIT_POINT_ENVIRONMENT=$(if($SplitPointEnvironment){'1'}else{'0'})
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
    $env:RTR_RTXPT_DIAGNOSTIC_SPLIT_POINT_ENVIRONMENT=$oldSplit
    $env:RTR_RTXPT_DIAGNOSTIC_EXACT_MIS=$oldMis
}
