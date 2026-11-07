<# Runs the existing generic fixture serially. Never builds code or shaders. #>
param(
    [string]$RuntimeDirectory='build/upstream-rtxpt/runtime/rtxpt_v.1.9.0_binaries/bin',
    [string]$BaselineRuntimeDirectory='',
    [string]$ClasspathFile='build/renderer-validation-classpath.txt',
    [string]$OutputDirectory='build/reports/streaming-scene-performance',
    [ValidateSet('medium','fast')][string[]]$Profiles=@('medium','fast'),
    [ValidateSet('OFF','DEEP')][string[]]$Diagnostics=@('OFF','DEEP'),
    [ValidateRange(2,65536)][int]$Meshes=3662,
    [ValidateRange(1,65536)][int]$AdmissionBatches=1,
    [ValidateRange(1,262144)][int]$Sources=45000,
    [ValidateRange(1,3600)][int]$Ticks=60,
    [ValidateRange(1,32)][int]$FramesPerTick=9,
    [ValidateRange(0,3600)][int]$WarmupFrames=30,
    [ValidateRange(1,3600)][int]$StationaryFrames=90,
    [ValidateRange(16,4096)][int]$TextureSize=1024,
    [bool]$DeformGeometry=$true,
    [ValidateRange(1,86400)][int]$TimeoutSeconds=900,
    [switch]$RequireIdle,
    [switch]$CheckPaths
)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$utf8=[Text.UTF8Encoding]::new($false)
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
function Full([string]$Path){[IO.Path]::GetFullPath($Path,$repo)}
function Save-Json([string]$Path,$Value){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 32)+[Environment]::NewLine,$utf8)}
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
$classpathPath=Full $ClasspathFile
$output=Full $OutputDirectory
$hostRunner=Join-Path $PSScriptRoot 'Invoke-RtxptHost.ps1'
$runtimes=[ordered]@{}
if($BaselineRuntimeDirectory){$runtimes.baseline=Full $BaselineRuntimeDirectory}
$runtimes.current=Full $RuntimeDirectory
if($CheckPaths){
    [pscustomobject]@{Runtimes=$runtimes;Classpath=$classpathPath;Output=$output;Host=$hostRunner;Profiles=$Profiles;Diagnostics=$Diagnostics;Meshes=$Meshes;Sources=$Sources;Ticks=$Ticks;FramesPerTick=$FramesPerTick;MovementFrames=$Ticks*$FramesPerTick;StationaryFrames=$StationaryFrames;JobLimitGiB=10;Runs=$runtimes.Count*$Profiles.Count*$Diagnostics.Count}
    return
}
if(!(Test-Path -LiteralPath $classpathPath -PathType Leaf)){throw 'Compile the fixture and export its fixed runtime classpath before running this tool'}
if(Test-Path -LiteralPath (Join-Path $output 'environment.json')){throw 'Choose a fresh output directory; previous measurements are never overwritten'}
[IO.Directory]::CreateDirectory($output)|Out-Null
$classPath=[IO.File]::ReadAllText($classpathPath).Trim()
if($classPath.Contains('*')){throw 'Classpath must name fixed entries, without wildcards'}
$entries=@($classPath.Split([IO.Path]::PathSeparator)|ForEach-Object{Full $_})
$fixtureClasses=@()
foreach($entry in $entries){
    $package=Join-Path $entry 'top/ceroxe/rt/renderer/rtxpt'
    if(Test-Path -LiteralPath $package -PathType Container){
        $fixtureClasses+=@(Get-ChildItem -LiteralPath $package -Filter 'RtxptStreamingWorkload*.class' -File)
    }
}
if($fixtureClasses.Count -eq 0){throw 'The already-compiled RtxptStreamingWorkload classes are missing'}
$classIdentities=@($fixtureClasses|Sort-Object FullName|ForEach-Object{[ordered]@{path=$_.FullName;sha256=(Hash $_.FullName);bytes=$_.Length}})
$runtimeIdentities=[ordered]@{}
foreach($identity in $runtimes.Keys){
    $bin=$runtimes[$identity]
    $files=@('rtrenderer_rtxpt.dll','rtrenderer_jvm_host.exe','PrecompiledShaders/index.tsv','PrecompiledShaders/globals.txt','PrecompiledShaders/sources.tsv')
    foreach($relative in $files){if(!(Test-Path -LiteralPath (Join-Path $bin $relative) -PathType Leaf)){throw "Missing runtime input: $identity/$relative"}}
    $runtimeIdentities[$identity]=@($files|ForEach-Object{$path=Join-Path $bin $_;[ordered]@{name=$_;sha256=(Hash $path);bytes=(Get-Item -LiteralPath $path).Length}})
}
Save-Json (Join-Path $output 'environment.json') ([ordered]@{
    schema='streaming-scene-performance-v1';timestampUtc=[DateTime]::UtcNow.ToString('o');testClasses=$classIdentities;runtime=$runtimeIdentities
    profiles=$Profiles;diagnostics=$Diagnostics;meshes=$Meshes;admissionBatches=$AdmissionBatches;sources=$Sources;ticks=$Ticks;sceneTickHz=20;framesPerTick=$FramesPerTick
    movementFrames=$Ticks*$FramesPerTick;warmupFrames=$WarmupFrames;stationaryFrames=$StationaryFrames;textureSize=$TextureSize
    outputExtent=@(2560,1600);lightingExtent=@(1280,800);jobMemoryLimitGiB=10;javaMaximumHeapMiB=512;serialRuns=$true
    backgroundWindow=$true;runtimeCompilationProhibited=$true;baselineAndCurrentUseIdenticalClassBytes=$true
    measurement='public apply through fresh GPU-only output lease; wall time includes waits; not GPU busy time or application FPS'
})
function Read-GpuBaseline {
    $samples=@();$hardware=@();$command=Get-Command nvidia-smi.exe -ErrorAction SilentlyContinue
    if($command){for($index=0;$index -lt 3;$index++){
        $rows=@(& $command.Source '--query-gpu=uuid,name,driver_version,pstate,utilization.gpu,clocks.gr,clocks.mem,power.draw' '--format=csv,noheader,nounits' 2>$null)
        if($LASTEXITCODE -eq 0 -and $rows.Count -eq 1){
            $fields=$rows[0].Split(',').Trim();$number=0.0
            if($fields.Count -eq 8 -and [double]::TryParse($fields[4],[Globalization.NumberStyles]::Float,[Globalization.CultureInfo]::InvariantCulture,[ref]$number)){
                $samples+=$number;$hardware+=[ordered]@{uuid=$fields[0];name=$fields[1];driver=$fields[2];pstate=$fields[3];graphicsClockMHz=$fields[5];memoryClockMHz=$fields[6];powerWatts=$fields[7]}
            }
        }
        if($index -lt 2){[Threading.Thread]::Sleep(200)}
    }}
    $known=$samples.Count -eq 3
    [ordered]@{known=$known;percent=$samples;hardware=$hardware;absoluteComparisonAllowed=($known -and ($samples|Measure-Object -Maximum).Maximum -le 10)}
}
$runs=[Collections.Generic.List[object]]::new()
$latencies=[Collections.Generic.List[object]]::new()
$performance=[Collections.Generic.List[object]]::new()
$resourceWork=[Collections.Generic.List[object]]::new()
$saved=@{}
$variables=@{RTR_RTXPT_BACKGROUND_WINDOW='1';RTR_RTXPT_REQUIRE_PRECOMPILED='1'}
try {
    foreach($name in $variables.Keys){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process');[Environment]::SetEnvironmentVariable($name,$variables[$name],'Process')}
    foreach($profile in $Profiles){foreach($level in $Diagnostics){foreach($identity in $runtimes.Keys){
        foreach($file in $classIdentities){if((Hash $file.path) -ne $file.sha256){throw 'Fixture class bytes changed between runs'}}
        foreach($file in $runtimeIdentities[$identity]){if((Hash (Join-Path $runtimes[$identity] $file.name)) -ne $file.sha256){throw 'Native runtime bytes changed between runs'}}
        $tag="$identity-$profile-$level";$case=Join-Path $output $tag
        [IO.Directory]::CreateDirectory($case)|Out-Null
        $gpu=Read-GpuBaseline
        Save-Json (Join-Path $case 'environment.json') ([ordered]@{identity=$identity;profile=$profile;diagnostics=$level;initialGpu=$gpu;runtime=$runtimeIdentities[$identity];classes=$classIdentities;timestampUtc=[DateTime]::UtcNow.ToString('o')})
        if($RequireIdle -and !$gpu.absoluteComparisonAllowed){throw "Initial GPU is busy or unknown; $tag was not started"}
        Write-Host "Streaming scene: $tag meshes=$Meshes sources=$Sources logicalTicks=$Ticks renderFrames=$($Ticks*$FramesPerTick)"
        $arguments=@('--meshes',[string]$Meshes,'--admission-batches',[string]$AdmissionBatches,'--sources',[string]$Sources,'--ticks',[string]$Ticks,'--frames-per-tick',[string]$FramesPerTick,
            '--frames',[string]($Ticks*$FramesPerTick),'--warmup-frames',[string]$WarmupFrames,'--stationary-frames',[string]$StationaryFrames,
            '--motion-profile',$profile,'--texture-updates','true','--texture-size',[string]$TextureSize,
            '--deform-geometry',$DeformGeometry.ToString().ToLowerInvariant(),'--diagnostics',$level,'--output',$case)
        Save-Json (Join-Path $case 'host-arguments.json') ([ordered]@{hostRunner=$hostRunner;mainClass='top.ceroxe.rt.renderer.rtxpt.RtxptStreamingWorkload';applicationArguments=$arguments;runtimeDirectory=$runtimes[$identity];classpathFile=$classpathPath;timeoutSeconds=$TimeoutSeconds})
        # Keep paths and application arguments as data. This constant launcher
        # invokes the existing bounded host; native processes cannot escape its Job.
        $launcher=Join-Path $case 'launch.ps1'
        $launcherSource=@'
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$configuration=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'host-arguments.json') -Raw|ConvertFrom-Json
$applicationArguments=[string[]]@($configuration.applicationArguments)
& $configuration.hostRunner -MainClass $configuration.mainClass -ApplicationArguments $applicationArguments -RuntimeDirectory $configuration.runtimeDirectory -ClasspathFile $configuration.classpathFile -TimeoutSeconds $configuration.timeoutSeconds
exit $LASTEXITCODE
'@
        [IO.File]::WriteAllText($launcher,$launcherSource,$utf8)
        $stdout=Join-Path $case 'stdout.log';$stderr=Join-Path $case 'stderr.log'
        $watch=[Diagnostics.Stopwatch]::StartNew()
        # OS-level handles are required: the bounded CreateProcess child inherits
        # these handles and does not write native output through PowerShell pipes.
        $shell=(Get-Command pwsh.exe -CommandType Application).Source
        $process=Start-Process -FilePath $shell -ArgumentList @('-NoProfile','-NonInteractive','-File',('"'+$launcher+'"')) -WorkingDirectory $repo -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -Wait -PassThru
        $process.Refresh();$exitCode=$process.ExitCode;$watch.Stop()
        # Preserve both original streams. The combined file groups stdout then
        # stderr; it does not claim chronological ordering between streams.
        $combined=[IO.File]::Create((Join-Path $case 'run.log'))
        try {foreach($path in @($stdout,$stderr)){$stream=[IO.File]::OpenRead($path);try{$stream.CopyTo($combined)}finally{$stream.Dispose()}}}finally{$combined.Dispose()}
        $resultPath=Join-Path $case 'result.json'
        $result=if(Test-Path -LiteralPath $resultPath){Get-Content -LiteralPath $resultPath -Raw|ConvertFrom-Json}else{$null}
        $guard=[bool](Select-String -LiteralPath @($stdout,$stderr) -SimpleMatch 'runtime compiler processes prohibited' -Quiet|Where-Object{$_}|Select-Object -First 1)
        $run=[ordered]@{identity=$identity;profile=$profile;diagnostics=$level;exitCode=$exitCode;wallSeconds=$watch.Elapsed.TotalSeconds;absoluteComparisonAllowed=$gpu.absoluteComparisonAllowed;compilerGuardObserved=$guard;success=($exitCode -eq 0 -and $null -ne $result -and $result.success -and $guard);result=$result}
        $runs.Add($run)
        Save-Json (Join-Path $output 'runs.json') @($runs.ToArray())
        if(!$run.success){throw "Streaming scene failed: $tag; inspect its run.log and result.json"}
        foreach($phaseName in @('initial','warmup','stationary','movement')){
            $phase=$result.phases.$phaseName;if($null -eq $phase){continue}
            $componentSum=$null;$componentRate=$null
            if($phase.preparation_outside_timing.samples -eq $phase.apply_to_fresh_wall.samples -and $phase.diagnostic_poll_outside_timing.samples -eq $phase.apply_to_fresh_wall.samples -and $phase.apply_to_fresh_wall.samples -gt 0){
                $componentSum=$phase.preparation_outside_timing.mean_ms+$phase.apply_to_fresh_wall.mean_ms+$phase.diagnostic_poll_outside_timing.mean_ms
                if($componentSum -gt 0){$componentRate=1000/$componentSum}
            }
            if($null -ne $phase.pipeline_frames_per_second){$performance.Add([pscustomobject]@{identity=$identity;profile=$profile;diagnostics=$level;phase=$phaseName;pipelineFramesPerSecond=$phase.pipeline_frames_per_second;simulationFramesPerSecond=$phase.simulation_frames_per_second;componentSumDerivedPartialFramesPerSecond=$componentRate;frames=$phase.verified_fresh_frames;absoluteComparisonAllowed=$gpu.absoluteComparisonAllowed})}
            $metrics=[ordered]@{}
            foreach($metric in @('preparation_outside_timing','apply_wall','attributes_wall','submit_wall','apply_to_fresh_wall','diagnostic_poll_outside_timing','simulation_frame_wall')){$metrics[$metric]=$phase.$metric}
            foreach($stage in $phase.boundary_wall.PSObject.Properties){$metrics['boundary.'+$stage.Name]=$stage.Value}
            foreach($group in @('native_boundary_inclusive_wall','native_boundary_self_wall','native_boundary_thread_cpu','native_boundary_self_cpu','native_frame_inclusive_wall','native_frame_self_wall','native_frame_thread_cpu','native_frame_self_cpu')){
                foreach($stage in $phase.$group.PSObject.Properties){$metrics[$group+'.'+$stage.Name]=$stage.Value}
            }
            foreach($metric in $metrics.Keys){foreach($statistic in @('mean_ms','p95_ms','p99_ms','max_ms')){
                $value=$metrics[$metric].$statistic
                if($null -ne $value){$latencies.Add([pscustomobject]@{identity=$identity;profile=$profile;diagnostics=$level;phase=$phaseName;metric=$metric;statistic=$statistic;valueMilliseconds=$value;samples=$metrics[$metric].samples;absoluteComparisonAllowed=$gpu.absoluteComparisonAllowed})}
            }}
            if($phase.resource_work.available){
                foreach($counter in $phase.resource_work.counters.PSObject.Properties){$resourceWork.Add([pscustomobject]@{identity=$identity;profile=$profile;diagnostics=$level;phase=$phaseName;available=($counter.Value.available -ne $false);counter=$counter.Name;begin=$counter.Value.begin;end=$counter.Value.end;delta=$counter.Value.delta;reason=$counter.Value.reason})}
            } else {$resourceWork.Add([pscustomobject]@{identity=$identity;profile=$profile;diagnostics=$level;phase=$phaseName;available=$false;counter=$null;begin=$null;end=$null;delta=$null;reason=$phase.resource_work.reason})}
        }
        $performance.ToArray()|Export-Csv -LiteralPath (Join-Path $output 'throughput.csv') -NoTypeInformation -Encoding utf8
        $latencies.ToArray()|Export-Csv -LiteralPath (Join-Path $output 'latency.csv') -NoTypeInformation -Encoding utf8
        $resourceWork.ToArray()|Export-Csv -LiteralPath (Join-Path $output 'resource-work.csv') -NoTypeInformation -Encoding utf8
    }}}
} finally {
    foreach($name in $saved.Keys){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
}
$comparisons=[Collections.Generic.List[object]]::new()
function Add-Comparison($Old,$New,[string]$Metric,[string]$Statistic,[double]$OldValue,[double]$NewValue){
    $eligible=$Old.absoluteComparisonAllowed -and $New.absoluteComparisonAllowed
    $comparisons.Add([pscustomobject]@{profile=$New.profile;diagnostics=$New.diagnostics;phase=$New.phase;metric=$Metric;statistic=$Statistic;baseline=$OldValue;current=$NewValue;absoluteDifference=if($eligible){$NewValue-$OldValue}else{$null};percentDifference=if($eligible -and $OldValue -ne 0){($NewValue-$OldValue)/$OldValue*100}else{$null};comparison=if($eligible){'single-sequential-pair'}else{'observations-only-initial-GPU-busy-or-unknown'}})
}
foreach($current in @($performance.ToArray()|Where-Object identity -eq 'current')){
    $baseline=$performance.ToArray()|Where-Object{$_.identity -eq 'baseline' -and $_.profile -eq $current.profile -and $_.diagnostics -eq $current.diagnostics -and $_.phase -eq $current.phase}|Select-Object -First 1
    if($baseline){Add-Comparison $baseline $current 'pipeline_frames_per_second' 'rate' $baseline.pipelineFramesPerSecond $current.pipelineFramesPerSecond}
    if($baseline -and $null -ne $baseline.simulationFramesPerSecond -and $null -ne $current.simulationFramesPerSecond){Add-Comparison $baseline $current 'complete_simulation_frames_per_second' 'rate' $baseline.simulationFramesPerSecond $current.simulationFramesPerSecond}
}
foreach($current in @($latencies.ToArray()|Where-Object identity -eq 'current')){
    $baseline=$latencies.ToArray()|Where-Object{$_.identity -eq 'baseline' -and $_.profile -eq $current.profile -and $_.diagnostics -eq $current.diagnostics -and $_.phase -eq $current.phase -and $_.metric -eq $current.metric -and $_.statistic -eq $current.statistic}|Select-Object -First 1
    if($baseline){Add-Comparison $baseline $current $current.metric $current.statistic $baseline.valueMilliseconds $current.valueMilliseconds}
}
$comparisons.ToArray()|Export-Csv -LiteralPath (Join-Path $output 'comparison.csv') -NoTypeInformation -Encoding utf8
$lines=[Collections.Generic.List[string]]::new()
$lines.Add('# Generic streaming scene measurements');$lines.Add('')
$lines.Add('Pipeline throughput is API apply-to-fresh-output wall throughput. Complete simulation throughput additionally includes preparation, diagnostics, phase counter queries and all loop work. Neither is application FPS or GPU busy time. Cold initialization and warmup remain separate.');$lines.Add('')
$lines.Add('| Runtime | Profile | Diagnostics | Phase | Pipeline frames/s | Complete simulation frames/s | Fresh frames | Initial GPU permits comparison |')
$lines.Add('| --- | --- | --- | --- | ---: | ---: | ---: | --- |')
foreach($row in @($performance.ToArray()|Where-Object{$_.phase -in @('stationary','movement')})){
    $simulation=if($null -ne $row.simulationFramesPerSecond){[Math]::Round($row.simulationFramesPerSecond,3)}else{'unavailable'}
    $lines.Add("| $($row.identity) | $($row.profile) | $($row.diagnostics) | $($row.phase) | $([Math]::Round($row.pipelineFramesPerSecond,3)) | $simulation | $($row.frames) | $($row.absoluteComparisonAllowed) |")
}
$lines.Add('');$lines.Add('latency.csv reports mean/P95/P99/max separately for every measured phase and public boundary stage. comparison.csv contains baseline/current values, absolute and percentage differences; busy or unknown initial GPU suppresses gain claims. OFF has no boundary-stage samples, not zero-cost stages.');$lines.Add('')
$lines.Add('runs.json retains each full result, logical motion speed, path length, tick and frame counts, mesh entry/removal, pose changes, light deltas, and texture generation counts. Class bytes are fixed across runtimes. A sequential pair is not repeated ABBA evidence.')
$lines.Add('Native boundary groups retain per-event distributions. Native frame groups sum same-stage events by native sequence/revision before computing frame distributions, including measured zero-work frames. Inclusive and self timings stay separate; stages are never added together. componentSumDerivedPartialFramesPerSecond is a legacy-compatible derived sum of preparation/fresh/poll means, excludes other bookkeeping and cannot replace measured complete simulation throughput or supply a frame P95.')
[IO.File]::WriteAllLines((Join-Path $output 'summary.md'),$lines,$utf8)
Write-Host "Streaming scene measurements complete: $output"
