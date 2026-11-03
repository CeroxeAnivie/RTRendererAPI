<#
.SYNOPSIS
Stage the public native-lighting SDK, offline tools and examples for the runtime archive.
.DESCRIPTION
This copies source files only. No compiler, JVM, network or GPU work is started.
Paths resolve from the repository root; output must stay under build/.
#>
param(
    [string]$OutputDirectory='build/rtxpt-native-lighting-sdk',
    [string]$Version='7.1.0',
    [switch]$VerifyOnly,
    [switch]$CheckPaths
)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$utf8=[Text.UTF8Encoding]::new($false)
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$output=[IO.Path]::GetFullPath($OutputDirectory,$repo)
$build=[IO.Path]::GetFullPath((Join-Path $repo 'build'))+[IO.Path]::DirectorySeparatorChar
if(!$output.StartsWith($build,[StringComparison]::OrdinalIgnoreCase)){throw 'SDK staging output must stay inside this repository build directory'}
function Hash-File([string]$path){(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}
function Hash-Text([string]$value){[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($utf8.GetBytes($value))).ToLowerInvariant()}
function Child-Path([string]$root,[string]$relative){
    if([IO.Path]::IsPathRooted($relative)){throw 'SDK paths must be relative'}
    $path=[IO.Path]::GetFullPath((Join-Path $root $relative))
    if(!$path.StartsWith($root+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'SDK path escapes its root'}
    $path
}
function Verify-Sdk([string]$root){
    $manifest=Get-Content -LiteralPath (Join-Path $root 'native-lighting-sdk.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    if($manifest.format -cne 'rtr-native-lighting-sdk' -or $manifest.formatVersion -ne 1 -or $manifest.version -cne $Version){throw 'Unsupported SDK format or version'}
    $inventory=[Collections.Generic.List[string]]::new()
    foreach($entry in $manifest.files){
        $path=Child-Path $root $entry.path
        if(!(Test-Path -LiteralPath $path -PathType Leaf) -or (Get-Item -LiteralPath $path).Length -ne $entry.bytes -or (Hash-File $path) -cne $entry.sha256){throw "Missing or changed SDK file: $($entry.path)"}
        if($entry.abiPath){$inventory.Add("$($entry.abiPath)=$($entry.sha256)`n")}
    }
    $ordered=$inventory.ToArray();[Array]::Sort($ordered,[StringComparer]::Ordinal)
    if((Hash-Text ([string]::Concat($ordered))) -cne $manifest.shaderAbiSha256){throw 'SDK ABI inventory hash mismatch'}
    Write-Host "Native-lighting release SDK verified: $($manifest.files.Count) files, ABI=$($manifest.shaderAbiSha256)"
}
if($VerifyOnly){Verify-Sdk $output;return}
$files=[Collections.Generic.List[object]]::new()
function Add-Source([string]$source,[string]$destination,[string]$abiPath=''){
    $absolute=[IO.Path]::GetFullPath($source,$repo)
    if(!(Test-Path -LiteralPath $absolute -PathType Leaf)){throw "Missing SDK source: $source"}
    $files.Add([pscustomobject]@{source=$absolute;path=$destination;abiPath=$abiPath})
}
foreach($name in @('RtrNativeLighting.hlsl','RtrNativeLightingStyle.hlsli')){
    Add-Source "renderer-rtxpt/src/main/cpp/$name" "sdk/backend/$name" "backend/$name"
}
foreach($name in @('bindless.h','material_cb.h','packing.hlsli')){
    Add-Source "build/rtxpt-sdk/External/Donut/include/donut/shaders/$name" "sdk/donut/shaders/$name" "donut/shaders/$name"
}
foreach($name in @('Build-NativeLightingExtension.ps1','Invoke-BoundedBuild.ps1')){Add-Source "tools/$name" "tools/$name"}
foreach($directory in @('native-lighting-extension','native-lighting-opengl','host-surface-opengl')){
    foreach($file in Get-ChildItem -LiteralPath (Join-Path $repo "examples/$directory") -File -Recurse){
        $relative=[IO.Path]::GetRelativePath($repo,$file.FullName).Replace('\','/')
        Add-Source $relative $relative
    }
}
foreach($name in @('rtxpt-710-native-lighting-extension.md','rtxpt-710-host-surface-input.md','rtxpt-710-host-augmentation.md','rtxpt-710-diagnostics.md','rtxpt-710-release-sdk.md','rtxpt-710-native-application-and-compact.md','rtxpt-710-native-matrix.md','rtxpt-710-e12-cost.md','rtxpt-710-acceptance.md')){Add-Source "docs/$name" "docs/$name"}
Add-Source 'docs/ordinary-and-expert.md' 'docs/ordinary-and-expert.md'
Add-Source 'docs/7.1.0.md' 'docs/7.1.0.md'
Add-Source 'docs/quality/rtxpt-710-performance.md' 'docs/quality/rtxpt-710-performance.md'
if($CheckPaths){[pscustomobject]@{Repository=$repo;Output=$output;Version=$Version;Files=$files.Count};return}
$stage=$output+'.stage-'+[Guid]::NewGuid().ToString('N')
$backup=$output+'.previous-'+[Guid]::NewGuid().ToString('N')
[IO.Directory]::CreateDirectory($stage)|Out-Null
$moved=$false
try{
    $entries=@(foreach($file in $files | Sort-Object path){
        $destination=Child-Path $stage $file.path
        [IO.Directory]::CreateDirectory((Split-Path -Parent $destination))|Out-Null
        Copy-Item -LiteralPath $file.source -Destination $destination
        [ordered]@{path=$file.path;abiPath=$file.abiPath;bytes=(Get-Item -LiteralPath $destination).Length;sha256=(Hash-File $destination)}
    })
    $abi=[string[]]@($entries | Where-Object abiPath | ForEach-Object {"$($_.abiPath)=$($_.sha256)`n"})
    [Array]::Sort($abi,[StringComparer]::Ordinal)
    $manifest=[ordered]@{format='rtr-native-lighting-sdk';formatVersion=1;version=$Version;shaderAbiSha256=(Hash-Text ([string]::Concat($abi)));compiler='ShaderDynamic/Tools/d3d12/x64/dxc.exe';files=$entries}
    [IO.File]::WriteAllText((Join-Path $stage 'native-lighting-sdk.json'),($manifest|ConvertTo-Json -Depth 8)+"`n",$utf8)
    Verify-Sdk $stage
    if(Test-Path -LiteralPath $output){[IO.Directory]::Move($output,$backup);$moved=$true}
    [IO.Directory]::Move($stage,$output)
    if($moved){Remove-Item -LiteralPath $backup -Recurse -Force}
    Write-Host "Native-lighting release SDK staged: $output"
}catch{
    if($moved -and !(Test-Path -LiteralPath $output)){[IO.Directory]::Move($backup,$output)}
    throw
}finally{
    if(Test-Path -LiteralPath $stage){Remove-Item -LiteralPath $stage -Recurse -Force}
}
