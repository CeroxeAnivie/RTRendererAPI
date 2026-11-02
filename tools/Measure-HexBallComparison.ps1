param(
    [ValidateSet('Vulkan','Rtxpt')][string]$Backend='Rtxpt',
    [Parameter(Mandatory)][string]$Tag,
    [int]$Seconds=30,
    [int]$Width=2560,
    [int]$Height=1440,
    [switch]$StageProfile,
    [switch]$SmallPointCandidate,
    [switch]$ControlLighting,
    [switch]$DisableResidualPointSampling,
    [switch]$ExactMis,
    [switch]$ApproximateMis,
    [ValidateSet('Upstream','Power')][string]$LightSampler='Upstream',
    [switch]$Glass,
    [switch]$AllowDirtySource,
    [switch]$DisableTransmissionGuides,
    [ValidateSet('Upstream','D','E','F')][string]$RrPreset='Upstream',
    [ValidateSet('Upstream','On','Off')][string]$AsyncPrefix='Upstream',
    [string[]]$ExtraArguments=@(),
    [string]$OutputDirectory='docs/quality/data/hexball-native-20261101'
)
$ErrorActionPreference='Stop'
if($SmallPointCandidate -and $ControlLighting){throw 'Choose candidate or original lighting control, not both.'}
if($ExactMis -and $ApproximateMis){throw 'Choose exact or approximate MIS, not both.'}
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$repo=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location -LiteralPath $repo
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
$jvm=@('-Dfile.encoding=UTF-8','-Ddemo.disable-fg=true',
    ('-Dtop.ceroxe.rt.rtxpt.runtime='+(Join-Path $repo 'build/rtxpt-sdk/bin')),
    '--enable-native-access=ALL-UNNAMED','--sun-misc-unsafe-memory-access=allow',
    '-Xmx3g','-XX:-UseJVMCICompiler','-cp',$classpath)
if ($Backend -eq 'Vulkan' -and $StageProfile) {
    $jvm = @('-Dtop.ceroxe.rt.rt.gpuTimestamps.frameStages=true') + $jvm
}
$entry=if($Backend -eq 'Rtxpt'){'demo.RtxptHexBallDemo'}else{'demo.HexBallDemo'}
$demo=@('--windowed',"--width=$Width","--height=$Height",'--spp=1',"--duration-seconds=$Seconds")
if($Backend -eq 'Rtxpt' -and !$Glass){$demo+='--original-balls'}
$demo+=$ExtraArguments
$oldProfile=$env:RTR_RTXPT_GPU_PROFILE
$oldAsync=$env:RTR_RTXPT_DIAGNOSTIC_ASYNC_PREFIX
$oldPreset=$env:RTR_RTXPT_DIAGNOSTIC_RR_PRESET
$oldSmallPoints=$env:RTR_RTXPT_DIAGNOSTIC_SMALL_POINTS
$oldResidual=$env:RTR_RTXPT_DIAGNOSTIC_DISABLE_RESIDUAL_SAMPLING
$oldMis=$env:RTR_RTXPT_DIAGNOSTIC_EXACT_MIS
$oldSampler=$env:RTR_RTXPT_DIAGNOSTIC_LIGHT_SAMPLER
$oldTransmissionGuides=$env:RTR_RTXPT_DIAGNOSTIC_DISABLE_TRANSMISSION_GUIDES
try {
    $env:RTR_RTXPT_GPU_PROFILE=if($StageProfile){$prefix+'-gpu.csv'}else{$null}
    $env:RTR_RTXPT_DIAGNOSTIC_ASYNC_PREFIX=switch($AsyncPrefix){'On'{'1'} 'Off'{'0'} default{$null}}
    $env:RTR_RTXPT_DIAGNOSTIC_RR_PRESET=if($RrPreset -eq 'Upstream'){$null}else{$RrPreset}
    $env:RTR_RTXPT_DIAGNOSTIC_SMALL_POINTS=$(if($ControlLighting){'0'}elseif($SmallPointCandidate){'1'}else{$null})
    $env:RTR_RTXPT_DIAGNOSTIC_DISABLE_RESIDUAL_SAMPLING=$(if($DisableResidualPointSampling){'1'}else{$null})
    $env:RTR_RTXPT_DIAGNOSTIC_EXACT_MIS=$(if($ExactMis){'1'}elseif($ApproximateMis){'0'}else{$null})
    $env:RTR_RTXPT_DIAGNOSTIC_LIGHT_SAMPLER=$(if($LightSampler -eq 'Power'){'power'}else{$null})
    $env:RTR_RTXPT_DIAGNOSTIC_DISABLE_TRANSMISSION_GUIDES=$(if($DisableTransmissionGuides){'1'}else{$null})
    & nvidia-smi --query-gpu=name,driver_version,utilization.gpu,memory.used,power.draw,clocks.current.graphics,temperature.gpu --format=csv > ($prefix+'-initial-gpu.csv')
    $settings=[ordered]@{
        SourceCommit=(& git rev-parse HEAD).Trim();Backend=$Backend;Entry=$entry;
        WorkingTreeSourceSha256=$sourceDigest;WorkingTreeSourceFiles=$sourcePaths;
        Width=$Width;Height=$Height;Spp=1;FrameGeneration=$false;StageProfile=[bool]$StageProfile;
        RrPreset=$RrPreset;AsyncPrefix=$AsyncPrefix;
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
    $env:RTR_RTXPT_GPU_PROFILE=$oldProfile
    $env:RTR_RTXPT_DIAGNOSTIC_ASYNC_PREFIX=$oldAsync
    $env:RTR_RTXPT_DIAGNOSTIC_RR_PRESET=$oldPreset
    $env:RTR_RTXPT_DIAGNOSTIC_SMALL_POINTS=$oldSmallPoints
    $env:RTR_RTXPT_DIAGNOSTIC_DISABLE_RESIDUAL_SAMPLING=$oldResidual
    $env:RTR_RTXPT_DIAGNOSTIC_EXACT_MIS=$oldMis
    $env:RTR_RTXPT_DIAGNOSTIC_LIGHT_SAMPLER=$oldSampler
    $env:RTR_RTXPT_DIAGNOSTIC_DISABLE_TRANSMISSION_GUIDES=$oldTransmissionGuides
}
