param([Parameter(Mandatory=$true)][string]$TrialDirectory,
      [Parameter(Mandatory=$true)][string]$RuntimeClasspathFile,
      [ValidateSet('control','candidate')][string]$Variant='candidate',
      [ValidateSet('profile','timings','off','image')][string]$Mode='profile', [string]$Label='first', [int]$Warmup=360)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$OutputEncoding=[Text.UTF8Encoding]::new($false)
$trialRoot=[IO.Path]::GetFullPath($TrialDirectory)
$repoRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$classpath=(Get-Content -Raw -Encoding UTF8 $RuntimeClasspathFile).Trim()
$classpath=(Join-Path $trialRoot 'benchmark-test-classes.jar')+';'+$classpath
$classpath=(Join-Path $trialRoot "$Variant-core.jar")+';'+
    (($classpath.Split(';') | Where-Object { [IO.Path]::GetFileName($_) -notlike 'renderer-core-*.jar' }) -join ';')
# A frozen core JAR and benchmark classes must exist before either variant runs.
foreach($required in @((Join-Path $trialRoot "$Variant-core.jar"),(Join-Path $trialRoot 'benchmark-test-classes.jar'))) {
    if(!(Test-Path -LiteralPath $required -PathType Leaf)){throw "Missing frozen artifact: $required"}
}
$runDir=Join-Path $trialRoot "$Label-$Variant-$Mode"
if(Test-Path -LiteralPath (Join-Path $runDir 'run.log')){throw "Use a new label; refusing to overwrite evidence: $runDir"}
New-Item -ItemType Directory -Force $runDir | Out-Null
Get-FileHash -Algorithm SHA256 (Join-Path $trialRoot "$Variant-core.jar"),(Join-Path $trialRoot 'benchmark-test-classes.jar') |
    ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $runDir 'artifacts.json')
Start-Sleep -Seconds 2
nvidia-smi --query-gpu=index,name,utilization.gpu,power.draw,clocks.gr,temperature.gpu --format=csv,noheader |
    Set-Content -Encoding UTF8 (Join-Path $runDir 'initial-gpu.csv')
$javaOptions=@('-Xmx512m','-XX:-UseJVMCICompiler','--enable-native-access=ALL-UNNAMED',
    '--sun-misc-unsafe-memory-access=allow','-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8',
    '-Duser.language=en','-Duser.country=US','-Dtop.ceroxe.rt.medium.scatteringInlineQuery=true')
Push-Location (Join-Path $repoRoot 'demos/hex-ball')
try {
    if($Mode -eq 'image') {
        $invokeArgs=$javaOptions+@("-Dtop.ceroxe.rt.gallery.output=$runDir",'-Dtop.ceroxe.rt.gallery.rawHdr=true',
            '-Dtop.ceroxe.rt.gallery.onlyPreset=pbr-direct-uniform',
            "-Dtop.ceroxe.rt.validation.logPath=$runDir/validation.jsonl",'-cp',$classpath,'demo.MediumGalleryNativeSelfTest')
    } else {
        $enabled=if($Mode -eq 'profile'){'true'}else{'false'}
        $timings=if($Mode -eq 'timings'){'true'}else{'false'}
        $invokeArgs=$javaOptions+@('-cp',$classpath,'demo.MediumGalleryPerformanceBenchmark',
            "--warmup=$Warmup",'--frames=120','--rounds=3',"--profile=$timings","--shaderProfile=$enabled","--wallProfile=$timings")
    }
    # Windows PowerShell reports normal native stderr as ErrorRecord under Stop.
    $ErrorActionPreference='Continue'
    & java @invokeArgs *> (Join-Path $runDir 'run.log')
    $runCode=$LASTEXITCODE
    $ErrorActionPreference='Stop'
    if($runCode -ne 0){Get-Content (Join-Path $runDir 'run.log') -Tail 30; throw "$Variant $Mode failed with exit $runCode"}
    if($Mode -ne 'image') {
        $report=if($Mode -eq 'profile'){'shader-sampled'}elseif($Mode -eq 'timings'){'profiled'}else{'unprofiled'}
        Copy-Item "build/reports/medium-performance/pbr-direct-uniform-1080p-$report.csv" $runDir -Force
        if($Mode -eq 'timings'){Copy-Item 'build/reports/medium-performance/*wall-timeline-round-*.json' $runDir -Force}
        if($Mode -eq 'profile'){Copy-Item 'build/reports/medium-performance/*shader-profile-round-*.json' $runDir -Force}
    }
    Get-Content (Join-Path $runDir 'run.log') | Select-String 'GalleryPerf round=|frames=8|验收通过' | ForEach-Object { $_.Line -replace ' profile=.*$', '' }
} finally { Pop-Location }
