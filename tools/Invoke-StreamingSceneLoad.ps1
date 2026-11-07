<# Fixed logical streaming workload. Compile once, then reuse identical test class bytes for every runtime.
The optional profile file is provenance only, never a claim of per-frame replay. #>
param(
    [string]$Profile='tools/data/streaming-scene-load-profile.json',
    [string]$OutputDirectory='build/reports/streaming-scene-load',
    [Parameter(Mandatory=$true)][string]$ClassPath,
    [string]$RuntimeDirectory='',
    [string]$TestClassesDirectory='build/streaming-scene-load-classes',
    [string]$Java='java.exe',[string]$JavaCompiler='javac.exe',
    [string]$ComparisonId='',
    [ValidateSet('physical-sum','finite-channel-max')][string]$SourceModel='physical-sum',
    [ValidateRange(-1,262144)][int]$StaticSources=-1,
    [ValidateRange(0,65535)][int]$FlowPerFrame=0,
    [ValidateRange(2,256)][int]$MaterialCount=2,
    [switch]$ExportRaw,[switch]$RequireTarget,
    [switch]$CompileOnly,[switch]$FixtureOnly,[switch]$Packaged,[switch]$RequireIdle,[switch]$CheckPaths
)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$utf8=[Text.UTF8Encoding]::new($false)
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$outRoot=[IO.Path]::GetFullPath($OutputDirectory,$repo)
$profilePath=[IO.Path]::GetFullPath($Profile,$repo)
$classes=[IO.Path]::GetFullPath($TestClassesDirectory,$repo)
$source=Join-Path $repo 'renderer-rtxpt/src/test/java/top/ceroxe/rt/renderer/rtxpt/RtxptStreamingWorkload.java'
$mainClass='top.ceroxe.rt.renderer.rtxpt.RtxptStreamingWorkload'
$sources=45000;$meshes=3662
if($SourceModel -eq 'physical-sum' -and $StaticSources -ne -1){throw 'StaticSources requires finite-channel-max'}
$effectiveStaticSources=if($SourceModel -eq 'finite-channel-max'){if($StaticSources -ge 0){$StaticSources}else{[Math]::Max(0,$sources-150)}}else{0}
if($effectiveStaticSources -gt $sources -or $FlowPerFrame -ge $meshes){throw 'StaticSources must not exceed 45000 and FlowPerFrame must be smaller than 3662'}
$dynamicSources=$sources-$effectiveStaticSources
$workloadKey="$SourceModel/static=$effectiveStaticSources/dynamic=$dynamicSources/flow=$FlowPerFrame/materials=$MaterialCount/raw=$([int][bool]$ExportRaw)"
$caseTag="$SourceModel-s$effectiveStaticSources-d$dynamicSources-flow$FlowPerFrame-mat$MaterialCount-raw$([int][bool]$ExportRaw)"
$out=Join-Path $outRoot $caseTag
$runner=Join-Path $PSScriptRoot 'Invoke-BoundedBuild.ps1'
if($ClassPath.Contains('*')){throw 'Use an explicit fixed artifact classpath, not a wildcard'}
$ClassPath=($ClassPath.Split([IO.Path]::PathSeparator)|ForEach-Object{[IO.Path]::GetFullPath($_,$repo)}) -join [IO.Path]::PathSeparator
if($FixtureOnly){throw 'The removed profile-replay fixture has no FixtureOnly mode; use CheckPaths for input planning or run the current workload'}
if(!$Packaged -and !$CompileOnly -and !$RuntimeDirectory){throw 'Specify RuntimeDirectory or Packaged explicitly'}
$runtime=if($RuntimeDirectory){[IO.Path]::GetFullPath($RuntimeDirectory,$repo)}else{''}
if($CheckPaths){[pscustomobject]@{Source=$source;MainClass=$mainClass;Profile=$profilePath;ProfileUse='identity-only-not-per-frame-replay';Classes=$classes;Output=$out;CaseTag=$caseTag;Runtime=$runtime;ClassPath=$ClassPath;CompileOnly=[bool]$CompileOnly;SourceModel=$SourceModel;StaticSources=$effectiveStaticSources;DynamicSources=$dynamicSources;FlowPerFrame=$FlowPerFrame;MaterialCount=$MaterialCount;ExportRaw=[bool]$ExportRaw;RequireTarget=[bool]$RequireTarget;WorkloadKey=$workloadKey};return}
if(!(Test-Path -LiteralPath $profilePath -PathType Leaf)){throw 'The load profile is missing'}
if($CompileOnly){
    [IO.Directory]::CreateDirectory($classes)|Out-Null
    & $runner -FilePath $JavaCompiler -ArgumentList @('-encoding','UTF-8','-cp',$ClassPath,'-d',$classes,$source) -MemoryLimitGB 10
    if($LASTEXITCODE){throw "Streaming fixture compile failed: $LASTEXITCODE"}
    Write-Host 'STREAMING_COMPILE PASS noRenderer=true reuseTheseClassBytes=true'
    return
}
$package=Join-Path $classes 'top/ceroxe/rt/renderer/rtxpt'
$classFiles=@(Get-ChildItem -LiteralPath $package -Filter 'RtxptStreamingWorkload*.class' -File -ErrorAction Stop|Sort-Object Name)
if($classFiles.Count -eq 0 -or !(Test-Path -LiteralPath (Join-Path $package 'RtxptStreamingWorkload.class'))){throw 'CompileOnly must produce the fixed test classes before a run'}
if(Test-Path -LiteralPath (Join-Path $out 'environment.json')){throw 'Choose a fresh output directory; previous measurements are never overwritten'}
[IO.Directory]::CreateDirectory($out)|Out-Null
$classIdentity=@($classFiles|ForEach-Object{[ordered]@{name=$_.Name;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash;bytes=$_.Length}})
$classpathIdentity=@($ClassPath.Split([IO.Path]::PathSeparator)|ForEach-Object{if(Test-Path -LiteralPath $_ -PathType Leaf){[ordered]@{name=[IO.Path]::GetFileName($_);sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash}}else{[ordered]@{name=[IO.Path]::GetFileName($_);directory=$true;identity='unhashed-directory'}}})
$runtimeIdentity=@();if(!$Packaged -and !$FixtureOnly){
    if(!(Test-Path -LiteralPath $runtime -PathType Container)){throw 'The explicit runtime directory is missing'}
    $runtimeIdentity=@(Get-ChildItem -LiteralPath $runtime -File|Where-Object{$_.Extension -in '.dll','.exe'}|Sort-Object Name|ForEach-Object{[ordered]@{name=$_.Name;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash;bytes=$_.Length}})
}
if(!$ComparisonId){$ComparisonId=[Guid]::NewGuid().ToString('N')}
$baseline=@();$hardware=$null;$idle=$false
if(!$FixtureOnly){
    $deadline=[DateTime]::UtcNow.AddSeconds(30)
    do{
        $baseline=@();$hardware=$null;$smi=Get-Command nvidia-smi.exe -ErrorAction SilentlyContinue
        if($smi){for($i=0;$i -lt 3;$i++){
            $lines=@(& $smi.Source '--query-gpu=uuid,name,driver_version,pstate,utilization.gpu,clocks.gr,clocks.mem,power.draw' '--format=csv,noheader,nounits' 2>$null)
            if($LASTEXITCODE -eq 0 -and $lines.Count -eq 1){$fields=$lines[0].Split(',').Trim();$util=0.0;if($fields.Count -eq 8 -and [double]::TryParse($fields[4],[Globalization.NumberStyles]::Float,[Globalization.CultureInfo]::InvariantCulture,[ref]$util)){$baseline+=$util;$hardware=[ordered]@{uuid=$fields[0];name=$fields[1];driver=$fields[2];pstate=$fields[3];graphicsClockMHz=$fields[5];memoryClockMHz=$fields[6];powerDrawWatts=$fields[7]}}}
            if($i -lt 2){[Threading.Thread]::Sleep(200)}
        }}
        $idle=$baseline.Count -eq 3 -and ($baseline|Measure-Object -Maximum).Maximum -le 10
        if($RequireIdle -and !$idle){[Threading.Thread]::Sleep(500)}
    }while($RequireIdle -and !$idle -and [DateTime]::UtcNow -lt $deadline)
}
$directoryClasspath=@($classpathIdentity|Where-Object{$_.directory}).Count -gt 0
$environment=[ordered]@{schema='streaming-scene-load-environment-v2';comparisonId=$ComparisonId;comparisonKey="$ComparisonId|$workloadKey";workloadKey=$workloadKey;caseTag=$caseTag;mainClass=$mainClass;sourceModel=$SourceModel;staticSources=$effectiveStaticSources;dynamicSources=$dynamicSources;flowPerFrame=$FlowPerFrame;materialCount=$MaterialCount;exportRaw=[bool]$ExportRaw;requireTarget=[bool]$RequireTarget;timestampUtc=[DateTime]::UtcNow.ToString('o');profileSha256=(Get-FileHash -LiteralPath $profilePath -Algorithm SHA256).Hash;profileUse='identity-only-not-per-frame-replay';testClasses=$classIdentity;classpath=$classpathIdentity;runtime=$runtimeIdentity;gpuBaselinePercent=$baseline;gpuBaselineKnown=($baseline.Count -eq 3);gpuInitialPeakPercent=if($baseline.Count -eq 3){($baseline|Measure-Object -Maximum).Maximum}else{$null};absolutePerformanceComparisonAllowed=$idle;hardware=$hardware;jobMemoryLimitGiB=10;maximumHeapGiB=2;frameCap='NONE';vsync=$false;backgroundWindow=$true;cpuFrameReadback=$false;diagnosticLevel='DEEP';measurement='pipeline apply-to-fresh and complete simulation wall are separate; both must reach 180 Hz; neither is application FPS';runtimePrecompiledRequired=$true;processProxyConfiguration='inherited; no project override';directoryClasspathRequiresExternalArtifactIdentity=$directoryClasspath}
[IO.File]::WriteAllText((Join-Path $out 'environment.json'),($environment|ConvertTo-Json -Depth 12)+[Environment]::NewLine,$utf8)
if($RequireIdle -and !$FixtureOnly -and !$idle){throw 'Initial GPU baseline is unavailable or exceeds 10%; clean comparison was not started'}
if(!$FixtureOnly -and !$idle){Write-Warning 'Initial GPU is busy or unknown; use only relative stage analysis, not absolute frame-rate gains'}
$arguments=@('-Xmx2g','--enable-native-access=ALL-UNNAMED','-Dfile.encoding=UTF-8')
if(!$Packaged -and !$FixtureOnly){$arguments+='-Dtop.ceroxe.rt.rtxpt.runtime='+$runtime}
$fixtureArguments=@('--source-model',$SourceModel,'--flow-per-frame',[string]$FlowPerFrame,'--material-count',[string]$MaterialCount,
    '--export-raw',([bool]$ExportRaw).ToString().ToLowerInvariant(),'--require-target',([bool]$RequireTarget).ToString().ToLowerInvariant(),'--output',$out)
if($SourceModel -eq 'finite-channel-max'){$fixtureArguments+=@('--static-sources',[string]$effectiveStaticSources)}
$arguments+=@('-cp',($classes+[IO.Path]::PathSeparator+$ClassPath),$mainClass)+$fixtureArguments
[IO.File]::WriteAllText((Join-Path $out 'host-arguments.json'),([ordered]@{mainClass=$mainClass;applicationArguments=$fixtureArguments;javaArguments=$arguments;workloadKey=$workloadKey}|ConvertTo-Json -Depth 12)+[Environment]::NewLine,$utf8)
$variables=@('RTR_RTXPT_BACKGROUND_WINDOW','RTR_RTXPT_GPU_PROFILE','RTR_RTXPT_GPU_PROFILE_STRIDE','RTR_RTXPT_REQUIRE_PRECOMPILED')
$saved=@{};foreach($name in $variables){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try{
    [Environment]::SetEnvironmentVariable('RTR_RTXPT_BACKGROUND_WINDOW','1','Process')
    [Environment]::SetEnvironmentVariable('RTR_RTXPT_GPU_PROFILE',(Join-Path $out 'gpu-stream.csv'),'Process')
    [Environment]::SetEnvironmentVariable('RTR_RTXPT_GPU_PROFILE_STRIDE','1','Process')
    [Environment]::SetEnvironmentVariable('RTR_RTXPT_REQUIRE_PRECOMPILED','1','Process')
    & $runner -FilePath $Java -ArgumentList $arguments -MemoryLimitGB 10
    $exitCode=$LASTEXITCODE
}finally{foreach($name in $variables){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}}
$resultPath=Join-Path $out 'result.json'
$result=if(Test-Path -LiteralPath $resultPath){Get-Content -LiteralPath $resultPath -Raw|ConvertFrom-Json}else{$null}
$contractMatches=$null -ne $result -and $result.source_model -eq $SourceModel -and $result.configuration.static_sources -eq $effectiveStaticSources -and $result.configuration.flow_meshes_per_frame -eq $FlowPerFrame -and $result.configuration.authored_material_count -eq $MaterialCount -and $result.configuration.export_raw -eq [bool]$ExportRaw -and $result.configuration.require_target -eq [bool]$RequireTarget
$executionSuccess=$exitCode -eq 0 -and $null -ne $result -and $result.success -and $contractMatches
$targetRatesObserved=$null -ne $result -and $result.meets_target_pipeline_rate -eq $true -and $result.meets_target_simulation_rate -eq $true
$targetCertified=$executionSuccess -and $targetRatesObserved -and $idle
$status=[ordered]@{workloadKey=$workloadKey;sourceModel=$SourceModel;staticSources=$effectiveStaticSources;dynamicSources=$dynamicSources;flowPerFrame=$FlowPerFrame;materialCount=$MaterialCount;exitCode=$exitCode;requestedContractMatches=$contractMatches;executionSuccess=$executionSuccess;requireTarget=[bool]$RequireTarget;targetRatesObserved=$targetRatesObserved;targetCertified=$targetCertified;targetQualification=if($idle){'known-idle-initial-GPU'}else{'observation-only-initial-GPU-busy-or-unknown'};success=($executionSuccess -and (!$RequireTarget -or $targetCertified))}
[IO.File]::WriteAllText((Join-Path $out 'run-status.json'),($status|ConvertTo-Json -Depth 12)+[Environment]::NewLine,$utf8)
if(!$status.success){throw "Streaming load failed or target is unqualified: $caseTag; inspect result.json and run-status.json"}
