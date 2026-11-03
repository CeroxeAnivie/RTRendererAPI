<# Offline extension artifact checks. No renderer, GPU dispatch or visible window. #>
param([string]$OutputDirectory='build/native-lighting-extension-example',[switch]$KeepScratch)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$utf8=[Text.UTF8Encoding]::new($false)
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$output=[IO.Path]::GetFullPath($OutputDirectory,$repo)
$builder=Join-Path $PSScriptRoot 'Build-NativeLightingExtension.ps1'
$source=Join-Path $repo 'examples/native-lighting-extension/ReceiverTint.hlsli'
$runtime=Join-Path $repo 'build/rtxpt-sdk/bin'
$expectedArguments=@{ExtensionSource=$source;OutputDirectory=$output;Name='receiver-tint-v1';ApplicationSchema='rtr.receiver-tint/v1';MinimumApplicationBytes=16}
function Require([bool]$condition,[string]$message){if(!$condition){throw $message}}
& $builder @expectedArguments
& $builder -OutputDirectory $output -VerifyOnly
$originalManifest=Get-Content -LiteralPath (Join-Path $output 'manifest.json') -Raw -Encoding UTF8
$original=$originalManifest | ConvertFrom-Json
$scratch=Join-Path ([IO.Path]::GetTempPath()) ('rtr-native-extension-'+[Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($scratch)|Out-Null
try{
    $externalTools=Join-Path $scratch 'tools';[IO.Directory]::CreateDirectory($externalTools)|Out-Null
    Copy-Item -LiteralPath $builder -Destination $externalTools
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Invoke-BoundedBuild.ps1') -Destination $externalTools
    Copy-Item -LiteralPath (Join-Path $output 'sdk') -Destination (Join-Path $scratch 'sdk') -Recurse
    Copy-Item -LiteralPath (Join-Path $output 'source') -Destination (Join-Path $scratch 'source') -Recurse
    $externalCompiler=Join-Path $scratch 'runtime/ShaderDynamic/Tools/d3d12/x64'
    [IO.Directory]::CreateDirectory($externalCompiler)|Out-Null
    foreach($file in @('dxc.exe','dxcompiler.dll','dxil.dll')){
        Copy-Item -LiteralPath (Join-Path $runtime "ShaderDynamic/Tools/d3d12/x64/$file") -Destination $externalCompiler
    }
    $externalBuilder=Join-Path $externalTools 'Build-NativeLightingExtension.ps1'
    $externalOutput=Join-Path $scratch 'output'
    & $externalBuilder -ExtensionSource (Join-Path $scratch 'source/ReceiverTint.hlsli') -OutputDirectory $externalOutput `
        -RuntimeDirectory (Join-Path $scratch 'runtime') -SdkDirectory (Join-Path $scratch 'sdk') `
        -Name receiver-tint-v1 -ApplicationSchema rtr.receiver-tint/v1 -MinimumApplicationBytes 16
    & $externalBuilder -OutputDirectory $externalOutput -VerifyOnly
    $external=Get-Content -LiteralPath (Join-Path $externalOutput 'manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    Require ($external.dxil.sha256 -ceq $original.dxil.sha256 -and $external.shaderAbiSha256 -ceq $original.shaderAbiSha256) 'Independent SDK build did not reproduce the same DXIL/ABI hashes'
    Write-Host 'RTXPT_EXTENSION_EXTERNAL_SDK PASS identicalDXIL=true checkoutSources=false'

    $bad=Join-Path $scratch 'source/Bad.hlsli'
    [IO.File]::WriteAllText($bad,"#error INTENTIONAL_EXTENSION_COMPILE_FAILURE`n",$utf8)
    $rejected=$false
    try{& $externalBuilder -ExtensionSource $bad -OutputDirectory $output -RuntimeDirectory (Join-Path $scratch 'runtime') -SdkDirectory (Join-Path $scratch 'sdk')}
    catch{$rejected=$_.Exception.Message.Contains('DXC failed')}
    Require $rejected 'Intentionally invalid source was not rejected by offline compilation'
    Require ((Get-Content -LiteralPath (Join-Path $output 'manifest.json') -Raw -Encoding UTF8) -ceq $originalManifest) 'Failed compile replaced the preceding manifest'
    Write-Host 'RTXPT_EXTENSION_COMPILE_FAILURE PASS priorManifestPreserved=true'

    [IO.File]::WriteAllText($bad,@'
ByteAddressBuffer t_Extra : register(t12);
void RtrEvaluateReceiver(RtrReceiver receiver,out float3 gain,out float3 indirect)
{gain=asfloat(t_Extra.Load3(0));indirect=0;}
'@,$utf8)
    $rejected=$false
    try{& $externalBuilder -ExtensionSource $bad -OutputDirectory $output -RuntimeDirectory (Join-Path $scratch 'runtime') -SdkDirectory (Join-Path $scratch 'sdk')}
    catch{$rejected=$_.Exception.Message.Contains('incompatible resource')}
    Require $rejected 'Unsupported extra resource was not rejected by reflection'
    Require ((Get-Content -LiteralPath (Join-Path $output 'manifest.json') -Raw -Encoding UTF8) -ceq $originalManifest) 'Failed ABI validation replaced the preceding manifest'
    Write-Host 'RTXPT_EXTENSION_BINDING_FAILURE PASS extraT12Rejected=true priorManifestPreserved=true'

    $binary=Join-Path $externalOutput $external.dxil.file;$saved=[IO.File]::ReadAllBytes($binary)
    try{
        $changed=[byte[]]$saved.Clone();$changed[40]=$changed[40] -bxor 1;[IO.File]::WriteAllBytes($binary,$changed)
        $rejected=$false;try{& $externalBuilder -OutputDirectory $externalOutput -VerifyOnly}catch{$rejected=$_.Exception.Message.Contains('mismatch')}
        Require $rejected 'Corrupt DXIL was not rejected by SHA-256 validation'
    }finally{[IO.File]::WriteAllBytes($binary,$saved)}
    & $externalBuilder -OutputDirectory $externalOutput -VerifyOnly
    Write-Host 'RTXPT_EXTENSION_HASH_FAILURE PASS corruptDXILRejected=true recovery=true'
    & $builder -OutputDirectory $output -VerifyOnly
    Write-Host "RTXPT_EXTENSION_OFFLINE_ALL PASS ABI=$($original.shaderAbiSha256) DXIL=$($original.dxil.sha256) bytes=$($original.dxil.bytes) resources=$($original.resources.Count)"
}finally{
    if($KeepScratch){Write-Host "Extension test scratch: $scratch"}
    else{
        $absolute=[IO.Path]::GetFullPath($scratch);$tempRoot=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')+[IO.Path]::DirectorySeparatorChar
        if(!$absolute.StartsWith($tempRoot,[StringComparison]::OrdinalIgnoreCase) -or !(Split-Path -Leaf $absolute).StartsWith('rtr-native-extension-')){throw 'Unexpected extension test scratch path'}
        if(Test-Path -LiteralPath $absolute){Remove-Item -LiteralPath $absolute -Recurse -Force}
    }
}
