<#
.SYNOPSIS
Incrementally build one lighting candidate, validate changed paths, then benchmark and compare saved results.
.DESCRIPTION
Uses an existing exported test classpath and reference HDR captures. Reference captures and old
benchmarks are never regenerated. Each child process tree is bounded to 10 GiB. Stop on a failed
build or HDR comparison; performance results cannot turn a failed image check into a pass.
#>
param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-z][a-z0-9-]{0,47}$')][string]$Identity,
    [string]$ClasspathFile='build/high-framerate-classpath.txt',
    [string]$RuntimeDirectory='build/rtxpt-sdk/bin',
    [string]$NativeBuildDirectory='build/rtxpt-sdk/build-host',
    [string]$Cmake='',
    [ValidateSet('rtrenderer_rtxpt','rtxpt_shaders')][string]$BuildTarget='rtrenderer_rtxpt',
    [string]$ReferenceDirectory='build/reports/rtxpt-native-matrix',
    [string]$OutputDirectory='build/reports/high-framerate',
    [string]$BaselineAnalysis='build/reports/high-framerate/retained-signals-analysis.json',
    [ValidateRange(1,8192)][int]$Meshes=977,
    [ValidateRange(1,300)][int]$Seconds=30,
    [ValidateSet('OFF','SUMMARY','DEEP')][string]$Level='SUMMARY',
    [ValidateSet('signals','oracle','none')][string]$Validation='signals',
    [switch]$SkipBuild,
    [switch]$SkipSignalCapture,
    [switch]$DeviceDiagnostics,
    [switch]$CheckHistory,
    [switch]$CheckAugmentation
)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
function Full([string]$path){[IO.Path]::GetFullPath($path,$repo)}
$runtime=Full $RuntimeDirectory
$output=Full $OutputDirectory
$reference=Full $ReferenceDirectory
$classpath=[IO.File]::ReadAllText((Full $ClasspathFile)).Trim()
$java=(Get-Command java.exe -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
$python=(Get-Command python.exe -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
$bounded=Join-Path $PSScriptRoot 'Invoke-BoundedBuild.ps1'
$compare=Join-Path $PSScriptRoot 'Compare-HdrSignals.py'
New-Item -ItemType Directory -Path $output -Force | Out-Null
$progressFile=Join-Path $output "$Identity-progress.json"
$progress=if(Test-Path -LiteralPath $progressFile){Get-Content -LiteralPath $progressFile -Raw -Encoding UTF8 | ConvertFrom-Json -AsHashtable}else{@{}}
function Stage-Key([string]$executable,[string[]]$arguments){
    $inventory=[Collections.Generic.List[string]]::new()
    $inventory.Add($executable);foreach($argument in $arguments){$inventory.Add($argument)}
    $inventory.Add($classpath)
    if($arguments -contains '--build'){
        $inputs=@(Get-ChildItem -LiteralPath (Join-Path $repo 'renderer-rtxpt/src/main/cpp') -Recurse -File)
        $inputs+=Get-ChildItem -LiteralPath $runtime -Filter '*.dll' -File
        $inputs+=Get-ChildItem -LiteralPath (Join-Path $runtime 'ShaderPrecompiled/Rtxpt/dxil/ProcessingPasses') -Filter '*.bin' -File
    }else{
        # Include every processing pass used by validation, not just the selected
        # production shader. A different binary or test class invalidates receipts.
        $inputs=@(Get-ChildItem -LiteralPath $runtime -Filter '*.dll' -File)
        $inputs+=Get-ChildItem -LiteralPath (Join-Path $runtime 'ShaderPrecompiled/Rtxpt/dxil/ProcessingPasses') -Filter '*.bin' -File
        foreach($entry in $classpath.Split([IO.Path]::PathSeparator)){
            if(Test-Path -LiteralPath $entry -PathType Leaf){$inputs+=Get-Item -LiteralPath $entry}
            elseif(Test-Path -LiteralPath $entry -PathType Container){$inputs+=Get-ChildItem -LiteralPath $entry -Recurse -Filter '*.class' -File}
        }
    }
    foreach($stageInput in ($inputs | Sort-Object FullName -Unique)){
        $inventory.Add($stageInput.FullName+'='+(Get-FileHash -LiteralPath $stageInput.FullName -Algorithm SHA256).Hash)
    }
    [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes(($inventory -join "`n"))))
}
function Run([string]$executable,[string[]]$arguments,[string]$log){
    $key=Stage-Key $executable $arguments
    if($progress[$log] -eq $key){Write-Output "Completed stage retained: $([IO.Path]::GetFileName($log))";return}
    # The bounded launcher inherits OS console handles. Redirect inside its child
    # PowerShell so native stdout/stderr are actually saved in the stage log.
    $quote={param([string]$value) "'"+$value.Replace("'","''")+"'"}
    $driver=Join-Path $output "$Identity-run.ps1"
    $command='& '+(& $quote $executable)+' @('+ (($arguments | ForEach-Object {& $quote $_}) -join ',') +') *> '+(& $quote $log)
    [IO.File]::WriteAllText($driver,('$ErrorActionPreference=''Continue'''+[Environment]::NewLine+$command+[Environment]::NewLine+'exit $LASTEXITCODE'),[Text.UTF8Encoding]::new($false))
    $shell=(Get-Process -Id $PID).Path
    & $bounded -FilePath $shell -ArgumentList @('-NoProfile','-File',$driver) -WorkingDirectory $repo -MemoryLimitGB 10
    if($LASTEXITCODE){Get-Content -LiteralPath $log -Tail 25;throw "Candidate stage failed: $log (exit $LASTEXITCODE)"}
    $progress[$log]=if($arguments -contains '--build'){Stage-Key $executable $arguments}else{$key}
    $progress | ConvertTo-Json | Set-Content -LiteralPath $progressFile -Encoding utf8
}
function Compare-Hdr([string]$prefix,[string[]]$cases,[string]$actualPrefix="$prefix-$Identity",[string]$referencePrefix="$prefix-reference"){
    $result=Join-Path $output "$Identity-$prefix-check.json"
    & $python $compare (Join-Path $reference $referencePrefix) (Join-Path $reference $actualPrefix) --cases @cases --tolerance 0 --output $result > "$result.log"
    if($LASTEXITCODE){throw "Exact HDR comparison failed: $result"}
    $check=Get-Content -LiteralPath $result -Raw -Encoding UTF8 | ConvertFrom-Json
    if(-not $check.passed){throw "HDR comparison did not pass: $result"}
    Write-Output "$prefix HDR PASS ($(@($check.signals.PSObject.Properties).Count) signals; tolerance=0)"
}
$saved=@{}
foreach($name in @('JAVA_TOOL_OPTIONS','RTR_RTXPT_BACKGROUND_WINDOW','RTR_RTXPT_DISABLE_SIGNAL_REUSE','RTR_RTXPT_GPU_PROFILE','RTR_RTXPT_GPU_PROFILE_STRIDE','RTR_RTXPT_GPU_PROFILE_WARMUP','RTR_RTXPT_GPU_VALIDATION','_CL_','VSLANG')){
    $saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')
}
try{
    $env:JAVA_TOOL_OPTIONS='-XX:-UseJVMCICompiler -Dfile.encoding=UTF-8'
    $env:VSLANG='1033'
    $env:RTR_RTXPT_BACKGROUND_WINDOW='1'
    $env:RTR_RTXPT_DISABLE_SIGNAL_REUSE='0'
    $env:RTR_RTXPT_GPU_VALIDATION=if($DeviceDiagnostics){'0'}else{$null}
    $env:RTR_RTXPT_GPU_PROFILE_STRIDE='1'
    $env:RTR_RTXPT_GPU_PROFILE_WARMUP='1'
    if(-not $SkipBuild){
        $shaderSources=Join-Path $repo 'renderer-rtxpt/src/main/cpp'
        $shaderStage=Join-Path $repo 'build/rtxpt-sdk/Rtxpt/ProcessingPasses'
        foreach($source in (Get-ChildItem -LiteralPath $shaderSources -Filter 'RtrNative*.hlsl' -File)){
            $destination=Join-Path $shaderStage $source.Name
            if(-not (Test-Path -LiteralPath $destination) -or (Get-FileHash -LiteralPath $source.FullName).Hash -ne (Get-FileHash -LiteralPath $destination).Hash){
                Copy-Item -LiteralPath $source.FullName -Destination $destination
            }
        }
        if(-not $Cmake){
            $candidate=Get-ChildItem -Path (Join-Path $repo 'build/native-build-tools/cmake-*/bin/cmake.exe') -File | Select-Object -First 1
            $Cmake=if($candidate){$candidate.FullName}else{(Get-Command cmake.exe -CommandType Application).Source}
        }
        $env:_CL_='/Ob1 /d2ReducedOptimizeHugeFunctions /cgthreads1'
        Write-Output 'Incremental native build'
        $targets=@($BuildTarget)
        Run $Cmake (@('--build',(Full $NativeBuildDirectory),'--config','Release','--target')+$targets+@('--parallel','1','--','/p:MultiProcessorCompilation=false','/p:BuildInParallel=false','/p:CL_MPCount=1','/m:1')) (Join-Path $output "$Identity-build.log")
    }
    $common=@('-Xmx2g','-XX:-UseJVMCICompiler','--enable-native-access=ALL-UNNAMED','-Dfile.encoding=UTF-8',"-Dtop.ceroxe.rt.rtxpt.runtime=$runtime","-Dtop.ceroxe.rt.rtxpt.testOutput=$reference",'-cp',$classpath)
    $env:RTR_RTXPT_GPU_PROFILE=Join-Path $output "$Identity-validation-gpu.csv"
    if($Validation -eq 'signals'){
        Write-Output 'Changed-path signal and guide validation'
        if(-not $SkipSignalCapture){
            Run $java ($common+@('top.ceroxe.rt.renderer.rtxpt.RtxptHighFramerateCompositionNativeSelfTest',"signal-reuse-$Identity")) (Join-Path $output "$Identity-validation.log")
        }
        $cases=foreach($mode in 0..1){foreach($stage in 0..6){foreach($repeat in 0..2){foreach($signal in @('gain','gi')){"$mode-$stage-$repeat-$signal"}}}}
        Compare-Hdr 'signal-reuse' $cases
    }elseif($Validation -eq 'oracle'){
        Run $java ($common+@('top.ceroxe.rt.renderer.rtxpt.RtxptSparseQueryOrderingNativeSelfTest')) (Join-Path $output "$Identity-query-order-gpu.log")
        Write-Output 'Exhaustive sparse-light HDR oracle PASS'
    }
    if($CheckHistory){
        Run $java ($common+@('top.ceroxe.rt.renderer.rtxpt.RtxptHighFramerateCompositionNativeSelfTest',"history-$Identity")) (Join-Path $output "$Identity-history.log")
        Compare-Hdr 'history' @('initial-gain','initial-gi','reuse-gain','reuse-gi','edited-gain','edited-gi','single-gain','single-gi','growth-gain','growth-gi')
    }
    if($CheckAugmentation){
        Run $java ($common+@('top.ceroxe.rt.renderer.rtxpt.RtxptHostAugmentationNativeSelfTest')) (Join-Path $output "$Identity-augmentation.log")
        Write-Output 'Host augmentation compatibility PASS'
    }
    $env:RTR_RTXPT_GPU_PROFILE=Join-Path $output "$Identity-$Meshes-$Level-gpu.csv"
    $env:RTR_RTXPT_GPU_VALIDATION=$null
    $env:RTR_RTXPT_GPU_PROFILE_WARMUP='12'
    $files=@('rtrenderer_rtxpt.dll','ShaderPrecompiled/Rtxpt/dxil/ProcessingPasses/RtrNativeLighting.bin','ShaderPrecompiled/Rtxpt/dxil/ProcessingPasses/RtrNativeLightingReconstruct.bin','ShaderPrecompiled/Rtxpt/dxil/ProcessingPasses/RtrNativeDynamicLighting.bin','ShaderPrecompiled/Rtxpt/dxil/ProcessingPasses/RtrNativeDynamicLightingReconstruct.bin')
    $hashes=[ordered]@{};foreach($file in $files){$hashes[$file]=(Get-FileHash -LiteralPath (Join-Path $runtime $file) -Algorithm SHA256).Hash}
    $gpu=& nvidia-smi --query-gpu=name,driver_version,utilization.gpu,memory.used,power.draw,clocks.sm,clocks.mem --format=csv,noheader
    if($LASTEXITCODE){throw 'Cannot record GPU identity and initial utilization'}
    [ordered]@{files=$hashes;gpuBefore=$gpu;time=(Get-Date -Format o);meshes=$Meshes;seconds=$Seconds;level=$Level} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $output "$Identity-$Meshes-$Level-runtime.json") -Encoding utf8
    Write-Output "Production benchmark: $Meshes meshes, $Seconds seconds, $Level"
    Run $java ($common+@("-DhighFramerate.meshes=$Meshes","-DhighFramerate.seconds=$Seconds","-DhighFramerate.level=$Level","-DhighFramerate.identity=$Identity","-DhighFramerate.output=$output",'top.ceroxe.rt.renderer.rtxpt.RtxptHighFramerateBenchmark')) (Join-Path $output "$Identity-benchmark.log")
    $analysis=Join-Path $output "$Identity-analysis.json"
    & $python (Join-Path $PSScriptRoot 'Analyze-HighFramerate.py') (Join-Path $output "production-$Meshes-$Level-$Identity.json") --gpu $env:RTR_RTXPT_GPU_PROFILE --output $analysis > "$analysis.log"
    if($LASTEXITCODE){throw 'Candidate analysis failed'}
    $current=Get-Content -LiteralPath $analysis -Raw -Encoding UTF8 | ConvertFrom-Json
    $baseline=Get-Content -LiteralPath (Full $BaselineAnalysis) -Raw -Encoding UTF8 | ConvertFrom-Json
    $table=@(
        [pscustomobject]@{Metric='FPS';Before=$baseline.run.fps;After=$current.run.fps},
        [pscustomobject]@{Metric='Full GPU P50 ms';Before=$baseline.gpu.NativeLightingFrameFull.p50Ms;After=$current.gpu.NativeLightingFrameFull.p50Ms},
        [pscustomobject]@{Metric='Full GPU P95 ms';Before=$baseline.gpu.NativeLightingFrameFull.p95Ms;After=$current.gpu.NativeLightingFrameFull.p95Ms},
        [pscustomobject]@{Metric='Updated full GPU P50 ms';Before=$baseline.fullGpuByEvaluation.recomputed.p50Ms;After=$current.fullGpuByEvaluation.recomputed.p50Ms},
        [pscustomobject]@{Metric='Updated full GPU P95 ms';Before=$baseline.fullGpuByEvaluation.recomputed.p95Ms;After=$current.fullGpuByEvaluation.recomputed.p95Ms},
        [pscustomobject]@{Metric='Lighting dispatch P50 ms';Before=$baseline.gpu.NativeLightingDispatch.p50Ms;After=$current.gpu.NativeLightingDispatch.p50Ms}
    )
    $table | Export-Csv -LiteralPath (Join-Path $output "$Identity-comparison.csv") -NoTypeInformation -Encoding utf8
    $table | Format-Table -AutoSize
    Write-Output "Evidence: $analysis"
}finally{
    foreach($name in $saved.Keys){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
}
