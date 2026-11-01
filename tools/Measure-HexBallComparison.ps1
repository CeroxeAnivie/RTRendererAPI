param(
    [ValidateSet('Vulkan','Rtxpt')][string]$Backend='Rtxpt',
    [Parameter(Mandatory)][string]$Tag,
    [int]$Seconds=30,
    [int]$Width=2560,
    [int]$Height=1440,
    [switch]$StageProfile,
    [string[]]$ExtraArguments=@(),
    [string]$OutputDirectory='docs/quality/data/hexball-native-20261101'
)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$repo=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location -LiteralPath $repo
if((& git status --porcelain | Where-Object {$_ -notmatch '^\?\? docs/quality/data/' -and $_ -notmatch '^.. docs/quality/data/'})) {
    throw 'Commit source changes before measuring hardware.'
}
$output=Join-Path $repo $OutputDirectory
New-Item -ItemType Directory -Path $output -Force | Out-Null
$prefix=Join-Path $output $Tag
if(Test-Path -LiteralPath ($prefix+'.log')){throw 'Use a new evidence tag; do not overwrite an earlier measurement.'}
$java='C:\Program Files\Java\graalvm-jdk-25.0.5.0.2+1.1\bin\java.exe'
$classpath=@(
    'demos/hex-ball/build/classes/java/main','demos/hex-ball/build/resources/main',
    'renderer-api/build/classes/java/main','renderer-core/build/libs/renderer-core-7.0.0.jar',
    'renderer-nvidia/build/libs/renderer-nvidia-7.0.0.jar','renderer-rtxpt/build/libs/renderer-rtxpt-7.0.0.jar',
    'demos/hex-ball/build/libs/RTRendererAPI-HexBallDemo-7.0.0.jar'
) -join ';'
$jvm=@('-Dfile.encoding=UTF-8','-Ddemo.disable-fg=true',
    ('-Dtop.ceroxe.rt.rtxpt.runtime='+(Join-Path $repo 'build/rtxpt-sdk/bin')),
    '--enable-native-access=ALL-UNNAMED','--sun-misc-unsafe-memory-access=allow',
    '-Xmx3g','-XX:-UseJVMCICompiler','-cp',$classpath)
$entry=if($Backend -eq 'Rtxpt'){'demo.RtxptHexBallDemo'}else{'demo.HexBallDemo'}
$demo=@('--windowed',"--width=$Width","--height=$Height",'--spp=1',"--duration-seconds=$Seconds")
if($Backend -eq 'Rtxpt'){$demo+='--original-balls'}
$demo+=$ExtraArguments
$oldProfile=$env:RTR_RTXPT_GPU_PROFILE
try {
    $env:RTR_RTXPT_GPU_PROFILE=if($StageProfile){$prefix+'-gpu.csv'}else{$null}
    & nvidia-smi --query-gpu=name,driver_version,utilization.gpu,memory.used,power.draw,clocks.current.graphics,temperature.gpu --format=csv > ($prefix+'-initial-gpu.csv')
    $settings=[ordered]@{
        SourceCommit=(& git rev-parse HEAD).Trim();Backend=$Backend;Entry=$entry;
        Width=$Width;Height=$Height;Spp=1;FrameGeneration=$false;StageProfile=[bool]$StageProfile;
        Java=$java;Jvm=$jvm;Arguments=$demo;StartedAt=(Get-Date -Format o);
        NativeSha256=(Get-FileHash -LiteralPath 'build/rtxpt-sdk/bin/rtrenderer_rtxpt.dll' -Algorithm SHA256).Hash
    }
    $settings | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath ($prefix+'-settings.json') -Encoding utf8
    & $java @jvm $entry @demo *> ($prefix+'.log')
    $code=$LASTEXITCODE
    @{ExitCode=$code;EndedAt=(Get-Date -Format o)} | ConvertTo-Json | Set-Content -LiteralPath ($prefix+'-exit.json') -Encoding utf8
    Get-Content -LiteralPath ($prefix+'.log') -Tail 5
    if($code -ne 0){throw "Hex-ball run exited $code; inspect $prefix.log"}
} finally {
    $env:RTR_RTXPT_GPU_PROFILE=$oldProfile
}
