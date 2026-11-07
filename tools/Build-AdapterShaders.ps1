<#
.SYNOPSIS
Compile external adapter shaders with the official ShaderMake and DXC tools.
.DESCRIPTION
Sources remain in renderer-rtxpt/src/main/cpp. Only runtime/bin/AdapterShaders
is written; no shader is copied into the SDK and no upstream target is changed.
The resulting blobs are for a separate Donut ShaderFactory rooted at that path.
#>
param(
    [string]$SdkRoot='build/upstream-rtxpt/source',
    [string]$ShaderMakeExecutable='build/upstream-rtxpt/source/bin/ShaderMake.exe',
    [string]$DxcExecutable='build/upstream-rtxpt/source/External/dxc/build/native/bin/x64/dxc.exe',
    [string]$RuntimeDirectory='build/upstream-rtxpt/runtime/rtxpt_v.1.9.0_binaries/bin',
    [string]$ShaderName='',
    [ValidateRange(1,10)][double]$MemoryLimitGB=10,
    [switch]$CheckPaths
)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$identity=& (Join-Path $PSScriptRoot 'Assert-RtxptUpstream.ps1') -SdkRoot $SdkRoot
$sdk=(Resolve-Path -LiteralPath ([IO.Path]::GetFullPath($SdkRoot,$repo))).Path
$compiler=(Resolve-Path -LiteralPath ([IO.Path]::GetFullPath($DxcExecutable,$repo))).Path
$shaderMake=(Resolve-Path -LiteralPath ([IO.Path]::GetFullPath($ShaderMakeExecutable,$repo))).Path
$source=(Resolve-Path -LiteralPath (Join-Path $repo 'renderer-rtxpt/src/main/cpp')).Path
$manifest=(Resolve-Path -LiteralPath (Join-Path $source 'AdapterShaders.cfg')).Path
$runtime=[IO.Path]::GetFullPath($RuntimeDirectory,$repo)
$output=Join-Path $runtime 'AdapterShaders'
$sdkPrefix=$sdk.TrimEnd([IO.Path]::DirectorySeparatorChar,[IO.Path]::AltDirectorySeparatorChar)+[IO.Path]::DirectorySeparatorChar
if($output.Equals($sdk,[StringComparison]::OrdinalIgnoreCase) -or $output.StartsWith($sdkPrefix,[StringComparison]::OrdinalIgnoreCase)){
    throw 'Adapter shader output must be outside the upstream source tree.'
}
# The ProcessingPasses include root resolves existing ../Shaders includes while
# keeping the adapter source files external to the official tree.
$includes=@($source,(Join-Path $sdk 'Rtxpt/Shaders'),(Join-Path $sdk 'Rtxpt/ProcessingPasses'),(Join-Path $sdk 'Rtxpt'),
    (Join-Path $sdk 'External/Donut/include'),(Join-Path $sdk 'External/Nrd/Shaders'),
    (Join-Path $sdk 'External/Rtxdi/Include'),(Join-Path $sdk 'External'))
foreach($include in $includes){
    if(!(Test-Path -LiteralPath $include -PathType Container)){throw ('Missing shader include directory: '+$include)}
}
$compileManifest=$manifest
$temporaryManifest=$null
if($ShaderName){
    if($ShaderName -notmatch '^Rtr[A-Za-z0-9]+$'){throw 'ShaderName must be an adapter shader base name.'}
    $selected=@(Get-Content -LiteralPath $manifest -Encoding UTF8|Where-Object {$_.StartsWith($ShaderName+'.hlsl ')})
    if($selected.Count -eq 0){throw ('Unknown adapter shader: '+$ShaderName)}
    $absoluteShader=(Join-Path $source ($ShaderName+'.hlsl')).Replace('\','/')
    $selected=@($selected|ForEach-Object {$_.Replace($ShaderName+'.hlsl',$absoluteShader)})
    $temporaryManifest=[IO.Path]::GetTempFileName()
    [IO.File]::WriteAllLines($temporaryManifest,$selected,[Text.UTF8Encoding]::new($false))
    $compileManifest=$temporaryManifest
}
$arguments=@('--config',$compileManifest,'--out',$output,'--platform','DXIL',
    '--binaryBlob','--outputExt','.bin','--compiler',$compiler,'--shaderModel','6_9',
    '--hlsl2021','--matrixRowMajor','--serial','--retryCount','0',
    '--project','AdapterShaders','-D','TARGET_D3D12','-D','ENABLE_DEBUG_PRINT')
foreach($include in $includes){$arguments+=@('-I',$include)}
if($CheckPaths){
    [pscustomobject]@{Upstream=$identity;ShaderMake=$shaderMake;Dxc=$compiler;Manifest=$manifest;Output=$output;Arguments=$arguments}
    if($temporaryManifest){Remove-Item -LiteralPath $temporaryManifest -Force}
    return
}
New-Item -ItemType Directory -Force -Path $output | Out-Null
$expectedBlobs=@{}
foreach($line in Get-Content -LiteralPath $manifest -Encoding UTF8){
    if($line -match '^\s*(Rtr\w+)\.hlsl\s'){$expectedBlobs[$Matches[1]+'.bin']=$true}
}
$processing=Join-Path $output 'app/ProcessingPasses'
if(Test-Path -LiteralPath $processing){
    foreach($binary in Get-ChildItem -LiteralPath $processing -Filter 'Rtr*.bin' -File){
        if(-not $expectedBlobs.ContainsKey($binary.Name)){Remove-Item -LiteralPath $binary.FullName}
    }
}
try {
    & (Join-Path $PSScriptRoot 'Invoke-BoundedBuild.ps1') -FilePath $shaderMake -WorkingDirectory $source -ArgumentList $arguments -MemoryLimitGB $MemoryLimitGB
} finally {
    if($temporaryManifest){Remove-Item -LiteralPath $temporaryManifest -Force -ErrorAction SilentlyContinue}
}
if($LASTEXITCODE -ne 0){throw 'External adapter shader compilation failed.'}
foreach($line in Get-Content -LiteralPath $manifest -Encoding UTF8){
    if($line -match '^\s*(Rtr\w+)\.hlsl\s'){
        $binary=Join-Path $output ('app/ProcessingPasses/'+$Matches[1]+'.bin')
        if(!(Test-Path -LiteralPath $binary -PathType Leaf) -or (Get-Item -LiteralPath $binary).Length -eq 0){
            throw ('ShaderMake did not produce adapter blob: '+$binary)
        }
    }
}
& (Join-Path $PSScriptRoot 'Assert-RtxptUpstream.ps1') -SdkRoot $SdkRoot | Out-Null
[pscustomobject]@{AdapterShaders=$output;FactoryBase=$output;UpstreamUnmodified=$true}
