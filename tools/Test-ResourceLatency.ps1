<# Sequential affected-path build and acceptance. Receipts bind each pass to its real inputs. #>
param(
    [ValidateSet('All','Build','Correctness','Performance')][string]$Phase='All',
    [string]$RuntimeDirectory='build/rtxpt-sdk/bin',
    [string]$OutputDirectory='build/reports/resource-latency',
    [int[]]$MeshCounts=@(719,977,4096),
    [string[]]$Levels=@('OFF','SUMMARY','DEEP'),
    [string[]]$Scenarios=@('stable','animation','local','stream'),
    [string[]]$CorrectnessCases=@(),
    [string]$RetryReason='',
    [ValidateRange(30,1800)][int]$StageTimeoutSeconds=300,
    [ValidateRange(1,300)][int]$Seconds=30
)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runtime=[IO.Path]::GetFullPath($RuntimeDirectory,$repo)
$output=[IO.Path]::GetFullPath($OutputDirectory,$repo)
[IO.Directory]::CreateDirectory($output)|Out-Null
$classpathFile=Join-Path $repo 'build/resource-latency-classpath.txt'
$receiptsFile=Join-Path $output 'receipts.json'
$receipts=if(Test-Path -LiteralPath $receiptsFile){Get-Content -LiteralPath $receiptsFile -Raw -Encoding UTF8|ConvertFrom-Json -AsHashtable}else{@{}}
$shell=(Get-Process -Id $PID).Path
$cmake=(Get-ChildItem -Path (Join-Path $repo 'build/native-build-tools/cmake-*/bin/cmake.exe') -File|Select-Object -First 1).FullName
$java=(Get-Command java.exe -CommandType Application|Select-Object -First 1).Source
$python=(Get-Command python.exe -CommandType Application|Select-Object -First 1).Source
function Quote([string]$v){"'"+$v.Replace("'","''")+"'"}
function Digest([string[]]$paths,[string[]]$arguments){
    $inventory=[Collections.Generic.List[string]]::new();foreach($a in $arguments){$inventory.Add($a)}
    foreach($p in ($paths|Sort-Object -Unique)){if(Test-Path -LiteralPath $p -PathType Leaf){$inventory.Add($p+'='+(Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash)}}
    [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes(($inventory -join "`n"))))
}
function Run([string]$name,[string]$executable,[string[]]$arguments,[string[]]$inputs){
    $key=Digest $inputs (@($executable)+$arguments)
    if($receipts[$name] -and $receipts[$name].identity -eq $key){
        if($receipts[$name].passed){Write-Output "Retained pass: $name";return}
        if([string]::IsNullOrWhiteSpace($RetryReason)){throw "This exact failed input is already recorded: $name. Fix the cause or record a changed environment with RetryReason before repeating it."}
    }
    $log=Join-Path $output "$name.log";$driver=Join-Path $output 'run-stage.ps1'
    if($receipts[$name] -and -not $receipts[$name].passed -and (Test-Path -LiteralPath $log)){
        $stamp=([DateTime]$receipts[$name].utc).ToUniversalTime().ToString('yyyyMMddTHHmmss')
        Copy-Item -LiteralPath $log -Destination (Join-Path $output ($name+'-failed-'+$receipts[$name].identity.Substring(0,12).ToLowerInvariant()+'-'+$stamp+'.log'))
        $receipts[$name]|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $output ($name+'-failed-'+$stamp+'.json')) -Encoding utf8
    }
    $command='& '+(Quote $executable)+' @('+ (($arguments|ForEach-Object {Quote $_}) -join ',') +') *> '+(Quote $log)
    [IO.File]::WriteAllText($driver,('$ErrorActionPreference=''Stop'''+[Environment]::NewLine+'try {'+[Environment]::NewLine+$command+[Environment]::NewLine+'exit $LASTEXITCODE'+[Environment]::NewLine+'} catch { $_ | Out-String | Set-Content -LiteralPath '+(Quote $log)+' -Encoding utf8; exit 1 }'),[Text.UTF8Encoding]::new($false))
    Write-Output "Running $name"
    $timeout=if($name -like '*-build'){0}else{$StageTimeoutSeconds}
    & (Join-Path $PSScriptRoot 'Invoke-BoundedBuild.ps1') -FilePath $shell -ArgumentList @('-NoProfile','-File',$driver) -WorkingDirectory $repo -MemoryLimitGB 10 -TimeoutSeconds $timeout
    $code=$LASTEXITCODE
    $receipts[$name]=@{identity=(Digest $inputs (@($executable)+$arguments));passed=($code -eq 0);exit=$code;utc=[DateTime]::UtcNow.ToString('o');log=$log;retryReason=$RetryReason}
    $receipts|ConvertTo-Json -Depth 6|Set-Content -LiteralPath $receiptsFile -Encoding utf8
    Get-Content -LiteralPath $log -Tail 5
    if($code){throw "Stage failed: $name ($code). Evidence: $log"}
}
$env:JAVA_TOOL_OPTIONS='-XX:-UseJVMCICompiler -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8'
$env:VSLANG='1033';$env:_CL_='/Ob1 /d2ReducedOptimizeHugeFunctions /cgthreads1'
$env:RTR_RTXPT_BACKGROUND_WINDOW='1';$env:RTR_RTXPT_DISABLE_SIGNAL_REUSE='1';$env:RTR_RTXPT_BENCHMARK_TIMING='1'
$env:RTR_RTXPT_GPU_PROFILE=$null;$env:RTR_RTXPT_GPU_VALIDATION=$null
$sourceInputs=@(Get-ChildItem -LiteralPath (Join-Path $repo 'renderer-rtxpt/src') -Recurse -File|ForEach-Object FullName)
$sourceInputs+=Get-ChildItem -LiteralPath (Join-Path $repo 'tools/patches') -Filter '*.patch' -File|ForEach-Object FullName
$sourceInputs+=Join-Path $repo 'tools/Build-RtxptBridge.ps1'
if($Phase -in @('All','Build')){
    $stage=Join-Path $repo 'build/rtxpt-sdk/Rtxpt/ProcessingPasses'
    foreach($source in (Get-ChildItem -LiteralPath (Join-Path $repo 'renderer-rtxpt/src/main/cpp') -File|Where-Object {$_.Name -like 'RtrNative*.hlsl*' -or $_.Name -eq 'RtrHostAugmentation.hlsl'})){
        $target=Join-Path $stage $source.Name
        if(-not(Test-Path -LiteralPath $target) -or (Get-FileHash -LiteralPath $source.FullName).Hash -ne (Get-FileHash -LiteralPath $target).Hash){Copy-Item -LiteralPath $source.FullName -Destination $target}
    }
    Run 'native-build' $cmake @('--build',(Join-Path $repo 'build/rtxpt-sdk/build-host'),'--config','Release','--target','rtrenderer_rtxpt','rtxpt_diagnostics_selftest','rtxpt_resource_protocol_selftest','--parallel','1','--','/p:MultiProcessorCompilation=false','/p:BuildInParallel=false','/p:CL_MPCount=1','/m:1') $sourceInputs
    Run 'java-build' (Join-Path $repo 'gradlew.bat') @(':renderer-rtxpt:rtxptResourceLatencyClasspath','--offline','--no-daemon','--max-workers=1','-x',':renderer-nvidia:configureNvidiaBridge','-x',':renderer-nvidia:compileNvidiaBridge') ($sourceInputs+@((Join-Path $repo 'gradle.properties'),(Join-Path $repo 'renderer-rtxpt/build.gradle.kts')))
}
if($Phase -eq 'Build'){return}
$classpath=[IO.File]::ReadAllText($classpathFile).Trim()
$binaryInputs=@(Get-ChildItem -LiteralPath $runtime -Filter '*.dll' -File|ForEach-Object FullName)
$binaryInputs+=Get-ChildItem -LiteralPath (Join-Path $runtime 'ShaderPrecompiled/Rtxpt/dxil/ProcessingPasses') -Filter '*.bin' -File|ForEach-Object FullName
$classInputs=@();foreach($p in $classpath.Split([IO.Path]::PathSeparator)){
    if(Test-Path -LiteralPath $p -PathType Container){$classInputs+=Get-ChildItem -LiteralPath $p -Recurse -Filter '*.class' -File|ForEach-Object FullName}
    elseif(Test-Path -LiteralPath $p -PathType Leaf){$classInputs+=$p}
}
$identity=(Digest ($binaryInputs+$classInputs) @()).Substring(0,16).ToLowerInvariant()
$common=@('-Xmx2g','-XX:-UseJVMCICompiler','--enable-native-access=ALL-UNNAMED','-Dfile.encoding=UTF-8',"-Dtop.ceroxe.rt.rtxpt.runtime=$runtime","-DresourceMatrix.output=$output","-Dtop.ceroxe.rt.rtxpt.testOutput=$output/native-matrix","-DnativeGiReconstruction.output=$output/native-gi-reconstruction","-DrtxptRetained.output=$output/retained-rr",'-cp',$classpath)
if($Phase -in @('All','Correctness')){
    $fixture=Join-Path $output 'diagnostics-snapshot.bin'
    if(-not $CorrectnessCases -or 'native-diagnostics' -in $CorrectnessCases){
    Run 'native-diagnostics' (Join-Path $runtime 'rtxpt_diagnostics_selftest.exe') @($fixture) @((Join-Path $runtime 'rtxpt_diagnostics_selftest.exe'))
    }
    if(-not $CorrectnessCases -or 'native-fault-and-retirement' -in $CorrectnessCases){
    Run 'native-fault-and-retirement' (Join-Path $runtime 'rtxpt_resource_protocol_selftest.exe') @() @((Join-Path $runtime 'rtxpt_resource_protocol_selftest.exe'))
    }
    if(-not $CorrectnessCases -or 'java-diagnostics' -in $CorrectnessCases){
    Run 'java-diagnostics' $java ($common+@('top.ceroxe.rt.renderer.rtxpt.RtxptDiagnosticsSelfTest',$fixture)) ($classInputs+@($fixture))
    }
    foreach($case in @('RtxptHighFramerateResourcesSelfTest','RtxptLightingInstrumentationNativeSelfTest','RtxptSparseQueryOrderingNativeSelfTest','RtxptNativeLightingReconstructionNativeSelfTest','RtxptHostAugmentationNativeSelfTest','RtxptHighFramerateCompositionNativeSelfTest','RtxptResourceLifecycleNativeSelfTest','RtxptNativeLightingGlSelfTest','RtxptResourceRemovalNativeSelfTest','RtxptAttributeTopologyNativeSelfTest','RtxptTopologyPreparationNativeSelfTest')){
        if($CorrectnessCases -and $case -notin $CorrectnessCases){continue}
        Run $case $java ($common+@("top.ceroxe.rt.renderer.rtxpt.$case")) ($binaryInputs+$classInputs)
    }
    if(-not $CorrectnessCases -or 'rr-instrumentation' -in $CorrectnessCases){
        Run 'rr-instrumentation' $java ($common+@('top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingReconstructionNativeSelfTest','instrumentation')) ($binaryInputs+$classInputs)
    }
    foreach($rrMode in @('lifecycle','temporal')){
        if("rr-$rrMode" -in $CorrectnessCases){
            Run "rr-$rrMode" $java ($common+@('top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingReconstructionNativeSelfTest',$rrMode)) ($binaryInputs+$classInputs)
        }
    }
    if('retained-rr' -in $CorrectnessCases){
        Run 'retained-rr' $java ($common+@('top.ceroxe.rt.renderer.rtxpt.RtxptRetainedNativeSelfTest','rr')) ($binaryInputs+$classInputs)
    }
    if('topology-coverage' -in $CorrectnessCases){
        Run 'topology-coverage' $java ($common+@('top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingMatrixNativeSelfTest','topology-coverage')) ($binaryInputs+$classInputs)
    }
    foreach($count in $MeshCounts){if(-not $CorrectnessCases -or "matrix-$count" -in $CorrectnessCases){Run "matrix-$count" $java ($common+@("-DresourceMatrix.meshes=$count",'top.ceroxe.rt.renderer.rtxpt.RtxptResourceLatencyMatrixNativeSelfTest')) ($binaryInputs+$classInputs)}}
}
if($Phase -in @('All','Performance')){
    $gpuProbeUnavailable=$false
    foreach($count in $MeshCounts){foreach($scenario in $Scenarios){foreach($level in $Levels){
        if($level -notin @('OFF','SUMMARY','DEEP')){throw 'Unknown diagnostic level'}
        if($scenario -notin @('stable','animation','local','stream','mixed')){throw 'Unknown scenario'}
        $stem="production-$count-$level-$scenario-$identity"
        $gpu='unavailable: previous GPU telemetry query timed out';$gpuBaselineAvailable=$false
        if(-not $gpuProbeUnavailable){
        $probe=[Diagnostics.ProcessStartInfo]::new('nvidia-smi.exe')
        $probe.ArgumentList.Add('--query-gpu=name,driver_version,utilization.gpu,memory.used,power.draw,clocks.sm,clocks.mem')
        $probe.ArgumentList.Add('--format=csv,noheader');$probe.UseShellExecute=$false;$probe.CreateNoWindow=$true
        $probe.RedirectStandardOutput=$true;$probe.RedirectStandardError=$true
        $process=[Diagnostics.Process]::Start($probe)
        $gpuRead=$process.StandardOutput.ReadToEndAsync();$gpuError=$process.StandardError.ReadToEndAsync()
        if(-not $process.WaitForExit(10000)){
            $process.Kill();$process.Dispose();$gpuProbeUnavailable=$true
            $gpu='unavailable: nvidia-smi query timed out; this does not establish GPU device failure'
            Write-Output 'GPU telemetry query timed out; recording unavailable baseline and continuing the renderer test'
        }else{
            $gpu=$gpuRead.GetAwaiter().GetResult().Trim();$probeCode=$process.ExitCode;$process.Dispose()
            if($probeCode){$gpu='unavailable: '+$gpuError.GetAwaiter().GetResult()}else{$gpuBaselineAvailable=$true}
        }
        }
        $hashes=[ordered]@{};foreach($file in $binaryInputs){$hashes[$file]=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash}
        $arguments=$common+@("-DhighFramerate.meshes=$count","-DhighFramerate.seconds=$Seconds","-DhighFramerate.level=$level","-DhighFramerate.scenario=$scenario","-DhighFramerate.identity=$identity","-DhighFramerate.output=$output",'top.ceroxe.rt.renderer.rtxpt.RtxptHighFramerateBenchmark')
        $key=Digest ($binaryInputs+$classInputs) (@($java)+$arguments)
        if(-not($receipts[$stem] -and $receipts[$stem].identity -eq $key -and $receipts[$stem].passed)){
            [ordered]@{identity=$identity;files=$hashes;gpuBefore=$gpu;gpuBaselineAvailable=$gpuBaselineAvailable;utc=[DateTime]::UtcNow.ToString('o');meshes=$count;scenario=$scenario;level=$level;seconds=$Seconds;reuseDisabled=$true}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $output "$stem-runtime.json") -Encoding utf8
        }
        Run $stem $java $arguments ($binaryInputs+$classInputs)
        Run "$stem-analysis" $python @((Join-Path $PSScriptRoot 'Analyze-ResourceLatency.py'),(Join-Path $output "$stem.json"),'--output',(Join-Path $output "$stem-analysis.json")) @((Join-Path $output "$stem.json"),(Join-Path $output "$stem.csv"),(Join-Path $output "$stem-full-gpu.csv"),(Join-Path $PSScriptRoot 'Analyze-ResourceLatency.py'))
    }}}
}
Write-Output "Resource latency evidence: $output; identity=$identity"
