param(
    [string]$JavaHome = 'C:/Program Files/Java/graalvm-jdk-25.0.5.0.2+1.1',
    [string]$Output = 'build/reports/blas-reuse',
    [string]$Bridge = '',
    [switch]$Baseline
)
$ErrorActionPreference='Stop'
$OutputEncoding=[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$repo=Split-Path $PSScriptRoot -Parent
Set-Location $repo
$out=[IO.Path]::GetFullPath((Join-Path $repo $Output))
New-Item -ItemType Directory -Force $out | Out-Null
$entries=(Get-Content build/resource-latency-classpath.txt -Raw -Encoding UTF8).Trim().Split(';')
$entries=@($entries | ForEach-Object {
    $entry=$_
    foreach($module in @('renderer-api','renderer-core','renderer-nvidia')) {
        if($entry.Contains($module+'\build\libs\')) {$entry=Join-Path $repo ($module+'/build/classes/java/main');break}
    }
    $entry
})
$classpath=$entries -join ';'
$sources=@('RtxptBlasReuseNativeSelfTest','RtxptResourceArenaNativeSelfTest') | ForEach-Object {
    'renderer-rtxpt/src/test/java/top/ceroxe/rt/renderer/rtxpt/'+$_+'.java'
}
& ($JavaHome+'/bin/javac.exe') -encoding UTF-8 -cp $classpath -d renderer-rtxpt/build/classes/java/test @sources
if($LASTEXITCODE -ne 0){throw 'BLAS reuse test compilation failed'}
$env:RTR_RTXPT_BACKGROUND_WINDOW='1'
$env:RTR_RTXPT_MEMORY_DIAGNOSTICS='0'
$env:RTR_RTXPT_MEMORY_RESOURCE_CENSUS='0'
$gpu=Get-Command nvidia-smi -ErrorAction SilentlyContinue
if($gpu){& $gpu.Source --query-gpu=name,utilization.gpu --format=csv,noheader | Tee-Object -FilePath (Join-Path $out 'initial-gpu.log')}
if(!$Bridge){$Bridge=$repo+'/build/rtxpt-sdk/bin/rtrenderer_rtxpt.dll'}
$arguments=@('-Xmx512m','-XX:-UseJVMCICompiler','--enable-native-access=ALL-UNNAMED','-Dfile.encoding=UTF-8',
    ('-Dtop.ceroxe.rt.rtxpt.runtime='+$repo+'/build/rtxpt-sdk/bin'),
    ('-Dtop.ceroxe.rt.rtxpt.bridge='+$Bridge),
    ('-DblasReuse.allowClones='+$Baseline.IsPresent.ToString().ToLowerInvariant()),
    ('-Dtop.ceroxe.rt.rtxpt.testOutput='+$out),
    '-cp',$classpath,'top.ceroxe.rt.renderer.rtxpt.RtxptBlasReuseNativeSelfTest')
$writer=[IO.StreamWriter]::new((Join-Path $out 'blas-reuse.log'),$false,[Text.UTF8Encoding]::new($false))
try{
    $ErrorActionPreference='Continue'
    & ($JavaHome+'/bin/java.exe') @arguments 2>&1 | ForEach-Object {$writer.WriteLine([string]$_);Write-Output $_}
    $code=$LASTEXITCODE
}finally{$writer.Dispose()}
if($code -ne 0){throw ('BLAS reuse native test failed: '+$code)}
