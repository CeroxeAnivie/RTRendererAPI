param(
    [Parameter(Mandatory)][ValidateSet('raw','relax','default','reuse-raw','reuse-relax','rr','reference','direct','direct-reference')][string]$Mode,
    [Parameter(Mandatory)][string]$Tag,
    [switch]$Glass,
    [switch]$PackagedRuntime,
    [switch]$SmallPointCandidate,
    [switch]$ControlLighting,
    [switch]$DisableResidualPointSampling,
    [switch]$ExactMis,
    [switch]$ApproximateMis,
    [switch]$Sharc,
    [switch]$RestirGi,
    [ValidateSet('none','original-lights','quarter-lights','no-points','no-sun','no-sky','no-environment')][string]$LightingProbe='none'
)
$ErrorActionPreference='Stop'
if($SmallPointCandidate -and $ControlLighting){throw 'Choose candidate or original lighting control, not both.'}
if($ExactMis -and $ApproximateMis){throw 'Choose exact or approximate MIS, not both.'}
if($Sharc -and $Mode -ne 'raw'){throw 'SHaRC isolation requires raw mode.'}
if($RestirGi -and $Mode -ne 'raw'){throw 'ReSTIR GI isolation requires raw mode.'}
if($Sharc -and $RestirGi){throw 'Choose SHaRC or ReSTIR GI for isolated testing.'}
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$repo=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location -LiteralPath $repo
if((& git status --porcelain | Where-Object {$_ -notmatch '^.. docs/quality/data/'})) {
    throw 'Commit source changes before hardware verification.'
}
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
    Entry='demo.RtxptHexBallQualitySelfTest';Arguments=@($Mode,$(if($Glass){'glass'}else{'metal'}),$images,$LightingProbe,$(if($Sharc){'sharc'}elseif($RestirGi){'restirgi'}else{'none'}));
    StartedAt=(Get-Date -Format o);NativeSha256=(Get-FileHash 'build/rtxpt-sdk/bin/rtrenderer_rtxpt.dll').Hash;
    Width=2560;Height=1440;Spp=$(if($Mode -in @('reference','direct-reference')){32}else{1});FrameGeneration=$false;StageProfile=$false
    RuntimeSource=$(if($PackagedRuntime){'PackagedJar'}else{'BuildDirectory'})
    SmallPointCandidate=[bool]$SmallPointCandidate
    ControlLighting=[bool]$ControlLighting
    DisableResidualPointSampling=[bool]$DisableResidualPointSampling
    ExactMis=[bool]$ExactMis
    ApproximateMis=[bool]$ApproximateMis
    Sharc=[bool]$Sharc
    RestirGi=[bool]$RestirGi
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
try {
    $env:RTR_RTXPT_GPU_PROFILE=$null
    $env:RTR_RTXPT_DIAGNOSTIC_SMALL_POINTS=$(if($ControlLighting){'0'}elseif($SmallPointCandidate){'1'}else{$null})
    $env:RTR_RTXPT_DIAGNOSTIC_DISABLE_RESIDUAL_SAMPLING=$(if($DisableResidualPointSampling){'1'}else{$null})
    $env:RTR_RTXPT_DIAGNOSTIC_EXACT_MIS=$(if($ExactMis){'1'}elseif($ApproximateMis){'0'}else{$null})
    & python (Join-Path $PSScriptRoot 'Run-HexBallProcess.py') $prefix
    $code=$LASTEXITCODE
    $runLog=[IO.File]::ReadAllText($prefix+'.log')
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
}
