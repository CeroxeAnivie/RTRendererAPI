<# Fixed logical streaming workload. Compile once, then reuse identical test class bytes for every runtime. #>
param(
    [string]$Profile='tools/data/streaming-scene-load-profile.json',
    [string]$OutputDirectory='build/reports/streaming-scene-load',
    [Parameter(Mandatory=$true)][string]$ClassPath,
    [string]$RuntimeDirectory='',
    [string]$TestClassesDirectory='build/streaming-scene-load-classes',
    [string]$Java='java.exe',[string]$JavaCompiler='javac.exe',
    [string]$ComparisonId='',
    [switch]$CompileOnly,[switch]$FixtureOnly,[switch]$Packaged,[switch]$RequireIdle,[switch]$CheckPaths
)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$utf8=[Text.UTF8Encoding]::new($false)
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$out=[IO.Path]::GetFullPath($OutputDirectory,$repo)
$profilePath=[IO.Path]::GetFullPath($Profile,$repo)
$classes=[IO.Path]::GetFullPath($TestClassesDirectory,$repo)
$source=Join-Path $repo 'renderer-rtxpt/src/test/java/top/ceroxe/rt/renderer/rtxpt/RtxptStreamingSceneLoadTest.java'
$runner=Join-Path $PSScriptRoot 'Invoke-BoundedBuild.ps1'
if($ClassPath.Contains('*')){throw 'Use an explicit fixed artifact classpath, not a wildcard'}
$ClassPath=($ClassPath.Split([IO.Path]::PathSeparator)|ForEach-Object{[IO.Path]::GetFullPath($_,$repo)}) -join [IO.Path]::PathSeparator
if(!$Packaged -and !$CompileOnly -and !$FixtureOnly -and !$RuntimeDirectory){throw 'Specify RuntimeDirectory or Packaged explicitly'}
$runtime=if($RuntimeDirectory){[IO.Path]::GetFullPath($RuntimeDirectory,$repo)}else{''}
if($CheckPaths){[pscustomobject]@{Source=$source;Profile=$profilePath;Classes=$classes;Output=$out;Runtime=$runtime;ClassPath=$ClassPath;CompileOnly=[bool]$CompileOnly};return}
if(!(Test-Path -LiteralPath $profilePath -PathType Leaf)){throw 'The load profile is missing'}
[IO.Directory]::CreateDirectory($out)|Out-Null
if($CompileOnly){
    [IO.Directory]::CreateDirectory($classes)|Out-Null
    & $runner -FilePath $JavaCompiler -ArgumentList @('-encoding','UTF-8','-cp',$ClassPath,'-d',$classes,$source) -MemoryLimitGB 10
    if($LASTEXITCODE){throw "Streaming fixture compile failed: $LASTEXITCODE"}
    Write-Host 'STREAMING_COMPILE PASS noRenderer=true reuseTheseClassBytes=true'
    return
}
$package=Join-Path $classes 'top/ceroxe/rt/renderer/rtxpt'
$classFiles=@(Get-ChildItem -LiteralPath $package -Filter 'RtxptStreamingSceneLoadTest*.class' -File -ErrorAction Stop|Sort-Object Name)
if($classFiles.Count -eq 0 -or !(Test-Path -LiteralPath (Join-Path $package 'RtxptStreamingSceneLoadTest.class'))){throw 'CompileOnly must produce the fixed test classes before a run'}
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
$environment=[ordered]@{schema='streaming-scene-load-environment-v1';comparisonId=$ComparisonId;timestampUtc=[DateTime]::UtcNow.ToString('o');profileSha256=(Get-FileHash -LiteralPath $profilePath -Algorithm SHA256).Hash;testClasses=$classIdentity;classpath=$classpathIdentity;runtime=$runtimeIdentity;gpuBaselinePercent=$baseline;gpuBaselineKnown=($baseline.Count -eq 3);gpuInitialPeakPercent=if($baseline.Count -eq 3){($baseline|Measure-Object -Maximum).Maximum}else{$null};absolutePerformanceComparisonAllowed=$idle;hardware=$hardware;jobMemoryLimitGiB=10;maximumHeapGiB=2;frameCap='NONE';vsync=$false;backgroundWindow=$true;cpuFrameReadback=$false;diagnosticLevel='DEEP';measurement='owner apply through exact fresh lease; GPU timestamps separate';processProxyConfiguration='inherited; no project override';directoryClasspathRequiresExternalArtifactIdentity=$directoryClasspath}
[IO.File]::WriteAllText((Join-Path $out 'environment.json'),($environment|ConvertTo-Json -Depth 12)+[Environment]::NewLine,$utf8)
if($RequireIdle -and !$FixtureOnly -and !$idle){throw 'Initial GPU baseline is unavailable or exceeds 10%; clean comparison was not started'}
if(!$FixtureOnly -and !$idle){Write-Warning 'Initial GPU is busy or unknown; use only relative stage analysis, not absolute frame-rate gains'}
$arguments=@('-Xmx2g','--enable-native-access=ALL-UNNAMED','-Dfile.encoding=UTF-8')
if(!$Packaged -and !$FixtureOnly){$arguments+='-Dtop.ceroxe.rt.rtxpt.runtime='+$runtime}
$arguments+=@('-cp',($classes+[IO.Path]::PathSeparator+$ClassPath),'top.ceroxe.rt.renderer.rtxpt.RtxptStreamingSceneLoadTest',$profilePath,$out)
if($FixtureOnly){$arguments+='--fixture-only'}
$variables=@('RTR_RTXPT_BACKGROUND_WINDOW','RTR_RTXPT_GPU_PROFILE','RTR_RTXPT_GPU_PROFILE_STRIDE','RTR_RTXPT_REQUIRE_PRECOMPILED')
$saved=@{};foreach($name in $variables){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try{
    [Environment]::SetEnvironmentVariable('RTR_RTXPT_BACKGROUND_WINDOW','1','Process')
    [Environment]::SetEnvironmentVariable('RTR_RTXPT_GPU_PROFILE',(Join-Path $out 'gpu-stream.csv'),'Process')
    [Environment]::SetEnvironmentVariable('RTR_RTXPT_GPU_PROFILE_STRIDE','1','Process')
    [Environment]::SetEnvironmentVariable('RTR_RTXPT_REQUIRE_PRECOMPILED',$(if($Packaged){'1'}else{'0'}),'Process')
    & $runner -FilePath $Java -ArgumentList $arguments -MemoryLimitGB 10
    if($LASTEXITCODE){throw "Streaming load failed: $LASTEXITCODE"}
}finally{foreach($name in $variables){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}}
