<# Compare exact work counters and HDR output with the original per-event atomic implementation.
   The reference uses the current native DLL and isolated shader containers, never an older release.
   Run the current RtxptLightingInstrumentationNativeSelfTest first in CandidateOutput. #>
param(
    [string]$RuntimeDirectory='build/rtxpt-sdk/bin',
    [string]$CandidateOutput='build/reports/resource-latency/native-matrix',
    [string]$OutputDirectory='build/reports/counter-reference',
    [switch]$RetryFailedRun
)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$utf8=[Text.UTF8Encoding]::new($false)
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runtime=[IO.Path]::GetFullPath($RuntimeDirectory,$repo)
$candidate=[IO.Path]::GetFullPath($CandidateOutput,$repo)
$output=[IO.Path]::GetFullPath($OutputDirectory,$repo)
if(-not $output.StartsWith((Join-Path $repo 'build')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Reference output must be inside repository build'}
$reference=Join-Path $output 'runtime'
if((Test-Path -LiteralPath $reference) -and !$RetryFailedRun){throw 'Use a fresh reference output directory; preserve earlier evidence'}
if($RetryFailedRun -and !(Test-Path -LiteralPath $reference)){throw 'Missing failed reference runtime'}
$containers=@('RtrNativeLighting.bin','RtrNativeLightingReconstruct.bin','RtrNativeLightingDeep.bin','RtrHostAugmentation.bin')
foreach($directory in (Get-ChildItem -LiteralPath $runtime -Recurse -Directory)){
    [IO.Directory]::CreateDirectory((Join-Path $reference ([IO.Path]::GetRelativePath($runtime,$directory.FullName))))|Out-Null
}
foreach($file in (Get-ChildItem -LiteralPath $runtime -Recurse -File)){
    $relative=[IO.Path]::GetRelativePath($runtime,$file.FullName)
    if($relative.StartsWith('ShaderPrecompiled') -and $file.Name -in $containers){continue}
    $destination=Join-Path $reference $relative
    [IO.Directory]::CreateDirectory((Split-Path -Parent $destination))|Out-Null
    if($RetryFailedRun){
        if((Get-FileHash -LiteralPath $destination).Hash -cne (Get-FileHash -LiteralPath $file.FullName).Hash){throw "Reference runtime changed: $relative"}
    }else{New-Item -ItemType HardLink -Path $destination -Target $file.FullName|Out-Null}
}
$source=Join-Path $output 'source'
$processing=Join-Path $source 'ProcessingPasses'
[IO.Directory]::CreateDirectory($processing)|Out-Null
if(!$RetryFailedRun){Get-ChildItem -LiteralPath (Join-Path $repo 'renderer-rtxpt/src/main/cpp') -File|Where-Object {$_.Name -like 'Rtr*.hlsl*'}|Copy-Item -Destination $processing}
$main=Join-Path $processing 'RtrNativeLighting.hlsl'
$text=[IO.File]::ReadAllText((Join-Path $repo 'renderer-rtxpt/src/main/cpp/RtrNativeLighting.hlsl'))
$begin=$text.IndexOf('#if RTR_WORK_STATISTICS'+[char]13)
if($begin -lt 0){$begin=$text.IndexOf("#if RTR_WORK_STATISTICS`nstatic uint")}
$end=$text.IndexOf('uint RtrQueryFlags()')
if($begin -lt 0 -or $end -le $begin){throw 'Cannot locate exact diagnostic implementation'}
$direct=@'
void RtrCount(uint index){
#if RTR_WORK_STATISTICS
    if((g.publication.w&1)!=0){uint ignored;u_Work.InterlockedAdd(index*4,1,ignored);}
#endif
}
void RtrFlushWork(){}

'@
$referenceText=$text.Substring(0,$begin)+$direct+$text.Substring($end)
if($RetryFailedRun){if([IO.File]::ReadAllText($main) -cne $referenceText){throw 'Reference source changed'}}
else{[IO.File]::WriteAllText($main,$referenceText,$utf8)}
$config=Join-Path $source 'reference.cfg'
$entries=@(
    'ProcessingPasses/RtrNativeLighting.hlsl -T cs -E main -D RTR_LIGHT_GROUP_CAPACITY={1,2,4,32} -D RTR_ANALYTIC_LIGHTS={0,1} -D RTR_WORK_STATISTICS={0,1} -D RTR_MAX_COMPOSITION={0,1}',
    'ProcessingPasses/RtrNativeLightingReconstruct.hlsl -T cs -E main -D RTR_LIGHT_GROUP_CAPACITY={1,2,4,32} -D RTR_ANALYTIC_LIGHTS={0,1} -D RTR_WORK_STATISTICS={0,1} -D RTR_MAX_COMPOSITION={0,1}',
    'ProcessingPasses/RtrNativeLightingDeep.hlsl -T cs -E main',
    'ProcessingPasses/RtrHostAugmentation.hlsl -T cs -E main'
)
[IO.File]::WriteAllLines($config,$entries,$utf8)
$runner=Join-Path $PSScriptRoot 'Invoke-BoundedBuild.ps1'
function Run-Logged([string]$name,[string]$file,[string[]]$arguments){
    $values=@($file)+$arguments
    if($values|Where-Object {$_.Contains('"') -or $_.Contains("`r") -or $_.Contains("`n")}){throw 'Expected literal single-line native arguments'}
    $command=($values|ForEach-Object {'"'+$_.Replace('%','%%')+'"'}) -join ' '
    $log=Join-Path $output ($name+'.log');$batch=Join-Path $output ($name+'.cmd')
    [IO.File]::WriteAllText($batch,"@echo off`r`nchcp 65001 >nul`r`n"+$command+' > "'+$log.Replace('%','%%')+'" 2>&1'+"`r`nexit /b %errorlevel%`r`n",$utf8)
    & $runner -FilePath $env:ComSpec -ArgumentList @('/d','/v:off','/c',$batch) -WorkingDirectory $repo -MemoryLimitGB 8 -TimeoutSeconds 300
    $code=$LASTEXITCODE;Get-Content -LiteralPath $log -Tail 5
    if($code){throw "Native stage failed: $name ($code)"}
}
$shaderOutput=Join-Path $reference 'ShaderPrecompiled/Rtxpt/dxil'
if(!$RetryFailedRun){Run-Logged 'reference-shaders' (Join-Path $runtime 'ShaderMake.exe') @('--jobs','1','--config',$config,'--out',$shaderOutput,'--platform','DXIL','--binaryBlob','--outputExt','.bin','-I',(Join-Path $repo 'build/rtxpt-sdk/External/Donut/include'),'-D','TARGET_D3D12','--compiler',(Join-Path $runtime 'ShaderDynamic/Tools/d3d12/x64/dxc.exe'),'--shaderModel','6_5','-m','6_9','-D','ENABLE_DEBUG_PRINT')}
$env:RTR_RTXPT_BACKGROUND_WINDOW='1';$env:RTR_RTXPT_DISABLE_SIGNAL_REUSE='1'
$env:RTR_RTXPT_REQUIRE_PRECOMPILED='1'
$env:JAVA_TOOL_OPTIONS='-XX:-UseJVMCICompiler -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8'
$suffix=if($RetryFailedRun){'-retry'}else{''}
$testOutput=Join-Path $output ('native-matrix'+$suffix)
$classpath=[IO.File]::ReadAllText((Join-Path $repo 'build/resource-latency-classpath.txt')).Trim()
Run-Logged ('reference-instrumentation'+$suffix) (Get-Command java.exe -CommandType Application).Source @('-Xmx2g','-XX:CICompilerCount=2','--enable-native-access=ALL-UNNAMED',"-Dtop.ceroxe.rt.rtxpt.runtime=$reference","-Dtop.ceroxe.rt.rtxpt.testOutput=$testOutput",'-cp',$classpath,'top.ceroxe.rt.renderer.rtxpt.RtxptLightingInstrumentationNativeSelfTest')
$counts='lighting-instrumentation-counts.json'
if([IO.File]::ReadAllText((Join-Path $candidate $counts)) -cne [IO.File]::ReadAllText((Join-Path $testOutput $counts))){throw 'Six exact integer counters differ from direct atomics'}
$captures=@(Get-ChildItem -LiteralPath $testOutput -Filter '*.pfm' -File)
if(-not $captures){throw 'Missing reference HDR captures'}
foreach($file in $captures){
    if((Get-FileHash -LiteralPath $file.FullName).Hash -cne (Get-FileHash -LiteralPath (Join-Path $candidate $file.Name)).Hash){throw "HDR differs: $($file.Name)"}
}
$result=[ordered]@{passed=$true;javaExit=0;reference='same current native DLL, original per-event global atomics in isolated shaders';dllSha256=(Get-FileHash -LiteralPath (Join-Path $runtime 'rtrenderer_rtxpt.dll')).Hash.ToLowerInvariant();sixCountersExact=$true;cases=(@(Get-Content -LiteralPath (Join-Path $testOutput $counts) -Raw|ConvertFrom-Json)).Count;hdrFilesByteEqual=$captures.Count;candidateCountsSha256=(Get-FileHash -LiteralPath (Join-Path $candidate $counts)).Hash.ToLowerInvariant();referenceCountsSha256=(Get-FileHash -LiteralPath (Join-Path $testOutput $counts)).Hash.ToLowerInvariant();referenceLog=('reference-instrumentation'+$suffix+'.log');retriedAfterMissingEmptyAssetDirectory=[bool]$RetryFailedRun;performanceMeasured=$false}
[IO.File]::WriteAllText((Join-Path $output 'counter-reference-result.json'),($result|ConvertTo-Json -Depth 5),$utf8)
Write-Output "COUNTER_REFERENCE PASS sixCountersExact=true cases=$($result.cases) HDR=$($captures.Count)"
