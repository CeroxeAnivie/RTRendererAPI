param(
    [string]$CMake = 'D:/Engineering/code/APIs/RTRendererAPI/build/native-build-tools/cmake-4.1.2-windows-x86_64/bin/cmake.exe',
    [string]$BaselineCommit = '6eacb19',
    [string]$Output = 'build/reports/retained-update',
    [switch]$CostOnly,
    [switch]$AffectedOnly
)
$ErrorActionPreference='Stop'
$OutputEncoding=[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false)
$utf8=[System.Text.UTF8Encoding]::new($false)
$repo=Split-Path $PSScriptRoot -Parent
Set-Location $repo
$out=[IO.Path]::GetFullPath((Join-Path $repo $Output))
$baseline=Join-Path $out 'baseline/source'
New-Item -ItemType Directory -Force $out,$baseline | Out-Null
function Invoke-Logged([string]$Name,[string]$Program,[string[]]$Arguments){
    $watch=[Diagnostics.Stopwatch]::StartNew()
    $log=Join-Path $out ($Name+'.log');if(Test-Path -LiteralPath $log){$log=Join-Path $out ($Name+'-'+[DateTime]::UtcNow.Ticks+'.log')}
    $script:LastLog=$log
    $writer=[IO.StreamWriter]::new($log,$false,$utf8)
    try{
        $ErrorActionPreference='Continue'
        & $Program @Arguments 2>&1 | ForEach-Object {$writer.WriteLine([string]$_);Write-Output $_}
        $code=$LASTEXITCODE
    }finally{$writer.Dispose()}
    if($code -ne 0){throw ($Name+' failed: '+$code)}
    Write-Output ($Name+' wallSeconds='+$watch.Elapsed.TotalSeconds.ToString('F3',[Globalization.CultureInfo]::InvariantCulture))
}
# Evidence fixture only: the baseline was already committed and pushed. No
# branch/worktree switch, project backup, or alternative production path.
foreach($name in @('rtxpt_retained_light_tree.h','rtxpt_sparse_regions.h')){
    $text=& git show ($BaselineCommit+':renderer-rtxpt/src/main/cpp/'+$name)
    if($LASTEXITCODE -ne 0){throw 'Cannot read committed baseline'}
    [IO.File]::WriteAllLines((Join-Path $baseline $name),[string[]]$text,$utf8)
}
Copy-Item -LiteralPath (Join-Path $repo 'renderer-rtxpt/src/main/cpp/rtxpt_sparse_regions_selftest.cpp') -Destination $baseline
$cpp=(Join-Path $repo 'renderer-rtxpt/src/main/cpp').Replace([char]92,[char]47)
$cmakeText=@"
cmake_minimum_required(VERSION 3.24)
project(RetainedUpdateBaseline LANGUAGES CXX)
add_executable(retained_update_baseline rtxpt_sparse_regions_selftest.cpp)
target_include_directories(retained_update_baseline PRIVATE "$cpp")
target_compile_features(retained_update_baseline PRIVATE cxx_std_17)
"@
[IO.File]::WriteAllText((Join-Path $baseline 'CMakeLists.txt'),$cmakeText,$utf8)
if(!$CostOnly){
Invoke-Logged 'native-build' $CMake @('--build','build/rtxpt-sdk/build-host','--config','Release','--target','rtrenderer_rtxpt','rtxpt_sparse_regions_selftest','rtxpt_memory_diagnostics_selftest','--parallel','1')
if(!$AffectedOnly){Invoke-Logged 'journal' (Join-Path $repo 'build/rtxpt-sdk/bin/rtxpt_sparse_regions_selftest.exe') @('journal')}
Invoke-Logged 'regions-oracle' (Join-Path $repo 'build/rtxpt-sdk/bin/rtxpt_sparse_regions_selftest.exe') @()
if(!$AffectedOnly){
Invoke-Logged 'memory-off' (Join-Path $repo 'build/rtxpt-sdk/bin/rtxpt_memory_diagnostics_selftest.exe') @()
Invoke-Logged 'memory-enabled' (Join-Path $repo 'build/rtxpt-sdk/bin/rtxpt_memory_diagnostics_selftest.exe') @('enabled')
}
}else{Invoke-Logged 'cost-candidate-build' $CMake @('--build','build/rtxpt-sdk/build-host','--config','Release','--target','rtxpt_sparse_regions_selftest','--parallel','1')}
Invoke-Logged 'baseline-configure' $CMake @('-S',$baseline,'-B',(Join-Path $out 'baseline/build'),'-G','Visual Studio 17 2022','-A','x64')
Invoke-Logged 'baseline-build' $CMake @('--build',(Join-Path $out 'baseline/build'),'--config','Release','--parallel','1')
$gpu=Get-Command nvidia-smi -ErrorAction SilentlyContinue
if($gpu){Invoke-Logged 'initial-gpu' $gpu.Source @('--query-gpu=name,utilization.gpu','--format=csv,noheader')}else{[IO.File]::WriteAllText((Join-Path $out 'initial-gpu.log'),'unavailable',$utf8)}
Invoke-Logged 'candidate-mixed' (Join-Path $repo 'build/rtxpt-sdk/bin/rtxpt_sparse_regions_selftest.exe') @('bench')
Invoke-Logged 'baseline-refit' (Join-Path $out 'baseline/build/Release/retained_update_baseline.exe') @('bench-refit')
Invoke-Logged 'candidate-refit' (Join-Path $repo 'build/rtxpt-sdk/bin/rtxpt_sparse_regions_selftest.exe') @('bench-refit')
# The exact same mixed fixture found a released baseline correctness defect.
# Keep its failure; do not calculate speedups from an incorrect workload.
$baselineFailed=$false
try{Invoke-Logged 'baseline-mixed-regression' (Join-Path $out 'baseline/build/Release/retained_update_baseline.exe') @('bench')}catch{
    if((Get-Content -LiteralPath $script:LastLog -Raw -Encoding UTF8) -notmatch 'Regional exact-once source=2114 visits=0'){throw}
    $baselineFailed=$true;Write-Output ('EXPECTED_BASELINE_REGRESSION '+$_.Exception.Message)
}
if(!$baselineFailed){throw 'The known baseline mixed regression unexpectedly passed; inspect the fixture'}
