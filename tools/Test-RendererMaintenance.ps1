param(
    [string]$JavaHome='C:/Program Files/Java/graalvm-jdk-25.0.5.0.2+1.1',
    [string]$Output='build/reports/renderer-maintenance',
    [string]$SkipCases='',
    [string]$Bridge='',
    [string]$RendererJar='',
    [string[]]$Cases=@('RtxptApplicationDataSelfTest','RtxptSceneAdmissionSelfTest','RtxptAdapterSelfTest','RtxptDynamicResourcesSelfTest','RtxptBootstrapNativeSelfTest','RtxptNativeLightingApplicationPatchNativeSelfTest','RtxptBlasReuseNativeSelfTest')
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
    if($RendererJar -and $entry.Contains('renderer-rtxpt\build\classes\java\main')){$entry=$RendererJar}
    foreach($module in @('renderer-api','renderer-core','renderer-nvidia')) {
        if($entry.Contains($module+'\build\libs\')) {$entry=Join-Path $repo ($module+'/build/classes/java/main');break}
    }
    $entry
})
$classpath=$entries -join ';'
if(!$Bridge){$Bridge=$repo+'/build/rtxpt-sdk/bin/rtrenderer_rtxpt.dll'}
$env:RTR_RTXPT_BACKGROUND_WINDOW='1'
$env:RTR_RTXPT_MEMORY_DIAGNOSTICS='0'
$env:RTR_RTXPT_MEMORY_RESOURCE_CENSUS='0'
$gpu=Get-Command nvidia-smi -ErrorAction SilentlyContinue
if($gpu){& $gpu.Source --query-gpu=name,utilization.gpu --format=csv,noheader | Tee-Object -FilePath (Join-Path $out 'initial-gpu.log')}
$results=@()
foreach($case in $Cases){
    if($case -in $SkipCases.Split(',')){continue}
    $arguments=@('-Xmx512m','-XX:-UseJVMCICompiler','-XX:CICompilerCount=2','--enable-native-access=ALL-UNNAMED','-Dfile.encoding=UTF-8',
        ('-Dtop.ceroxe.rt.rtxpt.runtime='+$repo+'/build/rtxpt-sdk/bin'),
        ('-Dtop.ceroxe.rt.rtxpt.bridge='+$Bridge),
        ('-Dtop.ceroxe.rt.rtxpt.testOutput='+$out),'-cp',$classpath,('top.ceroxe.rt.renderer.rtxpt.'+$case))
    $writer=[IO.StreamWriter]::new((Join-Path $out ($case+'.log')),$false,[Text.UTF8Encoding]::new($false))
    $timer=[Diagnostics.Stopwatch]::StartNew()
    try{
        $ErrorActionPreference='Continue'
        & ($JavaHome+'/bin/java.exe') @arguments 2>&1 | ForEach-Object {$line=[string]$_;$writer.WriteLine($line);if($line -match 'PASS|FAILED|Exception|AssertionError'){Write-Output $line}}
        $code=$LASTEXITCODE
    }finally{$writer.Dispose();$timer.Stop();$ErrorActionPreference='Stop'}
    $results+=[ordered]@{name=$case;passed=($code -eq 0);exit=$code;wallSeconds=$timer.Elapsed.TotalSeconds;stdout=($case+'.log')}
    $hasher=[Security.Cryptography.SHA256]::Create()
    try{$nativeHash=([BitConverter]::ToString($hasher.ComputeHash([IO.File]::ReadAllBytes($Bridge)))).Replace('-','').ToLowerInvariant()}finally{$hasher.Dispose()}
    $receipt=[ordered]@{memoryDiagnostics=0;resourceCensus=0;backgroundWindow=1;ciCompilerCount=2;rendererJar=$RendererJar;nativeSha256=$nativeHash;cases=$results}
    [IO.File]::WriteAllText((Join-Path $out 'receipt.json'),($receipt | ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
    if($code -ne 0){throw ($case+' failed: '+$code)}
}
