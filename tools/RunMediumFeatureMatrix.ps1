param([Parameter(Mandatory=$true)][string]$TrialDirectory,
      [Parameter(Mandatory=$true)][string]$RuntimeClasspathFile,
      [ValidateSet('off','timings')][string]$Mode='timings',
      [ValidateSet('micro','micro-checks','all','focused')][string]$Suite='micro',
      [string]$Label='first',[string]$Cases='',
      [ValidateRange(0,2147483647)][long]$FirstSequence=0,
      [ValidateRange(1,100000)][int]$Warmup=1200,
      [ValidateRange(1,100000)][int]$Frames=240,[ValidateRange(1,100)][int]$Rounds=3)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$OutputEncoding=[Text.UTF8Encoding]::new($false)
$trialRoot=[IO.Path]::GetFullPath($TrialDirectory)
$repoRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$coreJar=Join-Path $trialRoot 'candidate-core.jar'
$matrixJar=Join-Path $trialRoot 'matrix-test-classes.jar'
foreach($required in @($coreJar,$matrixJar,$RuntimeClasspathFile)) {
    if(!(Test-Path -LiteralPath $required -PathType Leaf)){throw "Missing frozen artifact: $required"}
}
$runDir=Join-Path $trialRoot "$Label-matrix-$Mode"
if(Test-Path -LiteralPath $runDir){throw "Use a new label; refusing to overwrite matrix evidence: $runDir"}
New-Item -ItemType Directory -Path $runDir | Out-Null
Get-FileHash -Algorithm SHA256 $coreJar,$matrixJar | ConvertTo-Json |
    Set-Content -Encoding UTF8 (Join-Path $runDir 'artifacts.json')
nvidia-smi --query-gpu=index,name,utilization.gpu,power.draw,clocks.gr,temperature.gpu --format=csv,noheader |
    Set-Content -Encoding UTF8 (Join-Path $runDir 'initial-gpu.csv')
$runtime=(Get-Content -Raw -Encoding UTF8 $RuntimeClasspathFile).Trim()
$runtime=($runtime.Split(';') | Where-Object { [IO.Path]::GetFileName($_) -notlike 'renderer-core-*.jar' }) -join ';'
$matrixClasspath=$matrixJar+';'+$coreJar+';'+$runtime
$enabled=($Mode -eq 'timings').ToString().ToLowerInvariant()
$matrixArguments=@('-Xmx768m','-XX:-UseJVMCICompiler','--enable-native-access=ALL-UNNAMED',
    '--sun-misc-unsafe-memory-access=allow','-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8',
    '-Dtop.ceroxe.rt.medium.scatteringInlineQuery=true',
    "-Dtop.ceroxe.rt.medium.benchmarkFirstSequence=$FirstSequence",
    '-cp',$matrixClasspath,'demo.MediumGalleryPerformanceBenchmark',"--suite=$Suite",
    "--warmup=$Warmup","--frames=$Frames","--rounds=$Rounds","--profile=$enabled","--wallProfile=$enabled",
    "--output=$runDir")
if($Cases){$matrixArguments+="--cases=$Cases"}
Push-Location (Join-Path $repoRoot 'demos/hex-ball')
try {
    $ErrorActionPreference='Continue'
    & java @matrixArguments *> (Join-Path $runDir 'run.log')
    $runCode=$LASTEXITCODE
    $ErrorActionPreference='Stop'
    if($runCode -ne 0){Get-Content (Join-Path $runDir 'run.log') -Tail 30;throw "Medium feature matrix failed: exit $runCode"}
    $suffix=if($Mode -eq 'timings'){'profiled'}else{'unprofiled'}
    $coverage=Get-Content -Raw -Encoding UTF8 (Join-Path $runDir "$Suite-$suffix-coverage.json") | ConvertFrom-Json
    if(!$Cases -and $Suite -in @('all','micro-checks') -and !$coverage.complete){throw 'Full medium matrix did not establish complete feature coverage'}
    Write-Output "Matrix completed: mode=$Mode cases=$($coverage.cases.Count) complete=$($coverage.complete)"
    Write-Output "Evidence: $runDir"
} finally {Pop-Location}
