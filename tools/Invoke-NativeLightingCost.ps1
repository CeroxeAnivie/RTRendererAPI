<# E12 fixed native workload. -CompileOnly and -FixtureOnly never create a renderer. #>
param(
    [string]$OutputDirectory='build/reports/rtxpt-e12',
    [string]$ClassPath='',
    [string]$RuntimeDirectory='build/rtxpt-sdk/bin',
    [string]$ComparisonRoundId='',
    [string]$ExpectedPstate='',
    [int]$Width=1280,[int]$Height=800,[int]$Frames=120,[int]$Sources=64,
    [string]$Java='java.exe',[string]$JavaCompiler='javac.exe',
    [switch]$Packaged,[switch]$CompileOnly,[switch]$FixtureOnly,[switch]$CheckPaths,[switch]$RequireIdle
)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$utf8=[Text.UTF8Encoding]::new($false)
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$out=[IO.Path]::GetFullPath($OutputDirectory,$repo)
$classes=Join-Path $repo 'build/e12-benchmark-classes'
$source=Join-Path $repo 'renderer-rtxpt/src/test/java/top/ceroxe/rt/renderer/rtxpt/RtxptNativeLightingCostBenchmark.java'
if(!$ClassPath){$ClassPath=@('renderer-api/build/classes/java/main','renderer-rtxpt/build/classes/java/main','renderer-rtxpt/build/classes/java/test'|ForEach-Object {Join-Path $repo $_}) -join [IO.Path]::PathSeparator}
if($CheckPaths){[pscustomobject]@{Output=$out;Source=$source;ClassPath=$ClassPath;Classes=$classes;Packaged=[bool]$Packaged};return}
[IO.Directory]::CreateDirectory($out)|Out-Null
[IO.Directory]::CreateDirectory($classes)|Out-Null
$runner=Join-Path $PSScriptRoot 'Invoke-BoundedBuild.ps1'
& $runner -FilePath $JavaCompiler -ArgumentList @('-encoding','UTF-8','-cp',$ClassPath,'-d',$classes,$source) -MemoryLimitGB 10
if($LASTEXITCODE){throw "E12 benchmark compile failed: $LASTEXITCODE"}
if($CompileOnly){Write-Host 'RTXPT_710_E12_COMPILE PASS noGpu=true';return}
if(!$ComparisonRoundId){$ComparisonRoundId=[Guid]::NewGuid().ToString('N')}
if(!$FixtureOnly){
    $deadline=[DateTime]::UtcNow.AddSeconds(30)
    do {
    $baseline=@();$hardware=$null
    $smi=Get-Command nvidia-smi.exe -ErrorAction SilentlyContinue
    if($smi){
        for($i=0;$i -lt 3;$i++){
            $line=@(& $smi.Source '--query-gpu=uuid,name,driver_version,pstate,power.limit,utilization.gpu,clocks.gr,clocks.mem,power.draw' '--format=csv,noheader,nounits' 2>$null)
            if($LASTEXITCODE -eq 0 -and $line.Count -eq 1){
                $fields=$line[0].Split(',').Trim();$utilization=0.0
                if($fields.Count -eq 9 -and [double]::TryParse($fields[5],[Globalization.NumberStyles]::Float,[Globalization.CultureInfo]::InvariantCulture,[ref]$utilization)){
                    $hardware=[ordered]@{uuid=$fields[0];name=$fields[1];driver=$fields[2];pstate=$fields[3];powerLimitWatts=$fields[4];graphicsClockMHz=$fields[6];memoryClockMHz=$fields[7];powerDrawWatts=$fields[8]};$baseline+=$utilization
                }
            }
            if($i -lt 2){[Threading.Thread]::Sleep(200)}
        }
    }
    $idle=$baseline.Count -eq 3 -and ($baseline|Measure-Object -Maximum).Maximum -le 10 -and
        (!$ExpectedPstate -or $hardware.pstate -ceq $ExpectedPstate)
    if($RequireIdle -and !$idle){[Threading.Thread]::Sleep(500)}
    } while($RequireIdle -and !$idle -and [DateTime]::UtcNow -lt $deadline)
    if($RequireIdle -and !$idle){throw 'Native GPU did not settle to the requested idle/power state; no clean comparison was run'}
    $environment=[ordered]@{format='rtr-e12-environment';formatVersion=1;comparisonRoundId=$ComparisonRoundId;timestampUtc=[DateTime]::UtcNow.ToString('o');hardware=$hardware;
        gpuBaselinePercent=$baseline;baselineKnown=($baseline.Count -eq 3);baselineMaximumPercent=if($baseline.Count -eq 3){($baseline|Measure-Object -Maximum).Maximum}else{$null};
        effectiveFrameCap='NONE';vsync=$false;hostVisible=$false;jobMemoryLimitGiB=10;scenario='static';minimumDynamicSeconds=0;fullDynamicAcceptance=$false;windowsPowerScheme=(& powercfg.exe /getactivescheme 2>$null | Out-String).Trim()}
    [IO.File]::WriteAllText((Join-Path $out 'environment.json'),($environment|ConvertTo-Json -Depth 6)+"`n",$utf8)
}
$arguments=@('-Xmx512m','-XX:-UseJVMCICompiler','--enable-native-access=ALL-UNNAMED','-Dfile.encoding=UTF-8')
if(!$Packaged -and !$FixtureOnly){$runtime=[IO.Path]::GetFullPath($RuntimeDirectory,$repo);$arguments+='-Dtop.ceroxe.rt.rtxpt.runtime='+$runtime}
$arguments+=@('-cp',($classes+[IO.Path]::PathSeparator+$ClassPath),'top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingCostBenchmark',$out,"$Width","$Height","$Frames","$Sources")
if($FixtureOnly){$arguments+='--fixture-only'}
$variables=@('RTR_RTXPT_BACKGROUND_WINDOW','RTR_RTXPT_GPU_PROFILE','RTR_RTXPT_GPU_PROFILE_STRIDE','RTR_RTXPT_REQUIRE_PRECOMPILED')
$saved=@{};foreach($name in $variables){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try{
    [Environment]::SetEnvironmentVariable('RTR_RTXPT_BACKGROUND_WINDOW','1','Process')
    [Environment]::SetEnvironmentVariable('RTR_RTXPT_GPU_PROFILE',(Join-Path $out 'gpu-stream.csv'),'Process')
    [Environment]::SetEnvironmentVariable('RTR_RTXPT_GPU_PROFILE_STRIDE','1','Process')
    [Environment]::SetEnvironmentVariable('RTR_RTXPT_REQUIRE_PRECOMPILED',$(if($Packaged){'1'}else{'0'}),'Process')
    & $runner -FilePath $Java -ArgumentList $arguments -MemoryLimitGB 10
    if($LASTEXITCODE){throw "E12 benchmark run failed: $LASTEXITCODE"}
}finally{foreach($name in $variables){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}}
