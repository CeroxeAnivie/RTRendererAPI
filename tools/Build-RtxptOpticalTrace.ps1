param([switch]$Enable,[uint32]$BranchSeedXor=0,[switch]$RawOpticalRoughness,
    [switch]$DisableReplayPrefixCache,
    [ValidateRange(1,6)][int]$ExactInterfaceCount=6,
    [int]$PixelX=-1,[int]$PixelY=-1,[uint32]$FirstFrame=160,[uint32]$LastFrame=161)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$sdk=Join-Path $repo 'build/rtxpt-sdk'
& (Join-Path $PSScriptRoot 'Build-RtxptBridge.ps1') -ConfigureOnly -OpticalTransportTrace:$Enable -OpticalBranchSeedXor $BranchSeedXor
if($LASTEXITCODE -ne 0){throw 'Shader configuration failed'}
if($DisableReplayPrefixCache) {
    $header=Join-Path $sdk 'Rtxpt/Shaders/PathTracer/RtrLegacyTransportTrace.hlsli'
    [IO.File]::WriteAllText($header,"#define RTR_LEGACY_REPLAY_PREFIX_CACHE 0`n"+[IO.File]::ReadAllText($header),[Text.UTF8Encoding]::new($false))
}
if($ExactInterfaceCount -ne 6) {
    $header=Join-Path $sdk 'Rtxpt/Shaders/PathTracer/RtrLegacyTransportTrace.hlsli'
    $source=[IO.File]::ReadAllText($header).Replace('#define RTR_LEGACY_EXACT_INTERFACE_COUNT 6u',"#define RTR_LEGACY_EXACT_INTERFACE_COUNT $($ExactInterfaceCount)u")
    [IO.File]::WriteAllText($header,$source,[Text.UTF8Encoding]::new($false))
}
if($RawOpticalRoughness) {
    if(!$Enable){throw 'Raw optical roughness is a diagnostic control only'}
    $header=Join-Path $sdk 'Rtxpt/Shaders/PathTracer/RtrLegacyTransportTrace.hlsli'
    [IO.File]::WriteAllText($header,"#define RTR_LEGACY_RAW_OPTICAL_ROUGHNESS 1`n"+[IO.File]::ReadAllText($header),[Text.UTF8Encoding]::new($false))
}
if($PixelX -ge 0 -or $PixelY -ge 0) {
    if(!$Enable -or $PixelX -lt 0 -or $PixelY -lt 0 -or $LastFrame -lt $FirstFrame){throw 'An enabled trace needs both pixel coordinates and an ordered frame range'}
    $header=Join-Path $sdk 'Rtxpt/Shaders/PathTracer/RtrLegacyTransportTrace.hlsli'
    $prefix="#define RTR_LEGACY_TRACE_ORIGIN uint2($($PixelX)u,$($PixelY)u)`n#define RTR_LEGACY_TRACE_FIRST_FRAME $($FirstFrame)u`n#define RTR_LEGACY_TRACE_LAST_FRAME $($LastFrame)u`n"
    [IO.File]::WriteAllText($header,$prefix+[IO.File]::ReadAllText($header),[Text.UTF8Encoding]::new($false))
}
$config=Join-Path $repo 'build/reports/optical-trace-shaders.cfg'
$permutations=Get-Content -LiteralPath (Join-Path $sdk 'Rtxpt/shaders.cfg') | Where-Object {$_ -like 'ProcessingPasses/PostProcess.hlsl *'}
[IO.File]::WriteAllText($config,($permutations -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
Push-Location -LiteralPath (Join-Path $sdk 'Rtxpt')
try {
    & (Join-Path $sdk 'bin/ShaderMake.exe') --jobs 1 --force --ignoreConfigDir --config $config `
        --out (Join-Path $sdk 'bin/ShaderPrecompiled/Rtxpt/dxil') --platform DXIL --binaryBlob --outputExt .bin `
        -I (Join-Path $sdk 'External/Donut/include') -I (Join-Path $sdk 'External/Nrd/Shaders') `
        -I (Join-Path $sdk 'External/Rtxdi/Include') -I (Join-Path $sdk 'External') `
        -D TARGET_D3D12 -D ENABLE_DEBUG_PRINT --compiler (Join-Path $sdk 'External/dxc/build/native/bin/x64/dxc.exe') -m 6_9
    if($LASTEXITCODE -ne 0){throw 'Final-merge trace shader compilation failed'}
} finally {Pop-Location}
