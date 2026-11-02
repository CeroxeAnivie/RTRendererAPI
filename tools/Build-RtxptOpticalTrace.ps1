param([switch]$Enable)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$sdk=Join-Path $repo 'build/rtxpt-sdk'
& (Join-Path $PSScriptRoot 'Build-RtxptBridge.ps1') -ConfigureOnly -OpticalTransportTrace:$Enable
if($LASTEXITCODE -ne 0){throw 'Shader configuration failed'}
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
