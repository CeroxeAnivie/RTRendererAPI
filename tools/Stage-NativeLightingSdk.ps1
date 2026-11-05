<#
.SYNOPSIS
Stage the public native-lighting SDK, offline tools and examples for the runtime archive.
.DESCRIPTION
This copies source files only. No compiler, JVM, network or GPU work is started.
Paths resolve from the repository root; output must stay under build/.
#>
param(
    [string]$OutputDirectory='build/rtxpt-native-lighting-sdk',
    [string]$Version='',
    [switch]$VerifyOnly,
    [switch]$CheckPaths
)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$utf8=[Text.UTF8Encoding]::new($false)
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if(!$Version){$Version=([IO.File]::ReadAllLines((Join-Path $repo 'gradle.properties')) | Where-Object {$_ -match '^project_version='}) -replace '^project_version=','';if(!$Version){throw 'Missing project_version'}}
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
foreach($name in @('rtxpt-native-lighting-extension.md','rtxpt-host-surface-input.md','rtxpt-host-augmentation.md','rtxpt-diagnostics.md','rtxpt-release-sdk.md','rtxpt-native-application-and-compact.md','rtxpt-native-matrix.md','rtxpt-e12-cost.md','rtxpt-acceptance.md')){Add-Source "docs/$name" "docs/$name"}
Add-Source 'docs/ordinary-and-expert.md' 'docs/ordinary-and-expert.md'
Add-Source 'docs/native-lighting-reconstruction.md' 'docs/native-lighting-reconstruction.md'
Add-Source 'docs/consumer-agent-handoff.md' 'docs/consumer-agent-handoff.md'
Add-Source 'docs/release-local-scene-preparation.md' 'docs/release-local-scene-preparation.md'
Add-Source 'docs/release-dynamic-resources.md' 'docs/release-dynamic-resources.md'
Add-Source 'docs/release-high-framerate-resources.md' 'docs/release-high-framerate-resources.md'
Add-Source 'docs/release-resource-architecture.md' 'docs/release-resource-architecture.md'
Add-Source 'docs/quality/7.1.6-implementation.md' 'docs/quality/7.1.6-implementation.md'
Add-Source 'docs/quality/data/resource-architecture-716.json' 'docs/quality/data/resource-architecture-716.json'
foreach($name in @('transaction-buffer-pages.md','independent-blas-batching.md','resource-pipeline-overlap.md',
    'data/independent-blas-batching.json','data/resource-pipeline-overlap.json')){
    Add-Source "docs/quality/$name" "docs/quality/$name"
}
foreach($name in @('release-resource-latency.md','quality/resource-latency-review.md','quality/resource-latency.md','quality/data/resource-latency.json','quality/data/resource-latency-baseline.json','quality/data/resource-latency-retained.json','quality/data/resource-latency-failures.json',
    'quality/correctness-criteria.md','quality/optimization-closeout.md','quality/optimization-experiments.md','quality/data/optimization-closeout.json','quality/data/optimization-experiments.json','quality/data/tail-latency-review.json','quality/resource-latency-historical.md','quality/data/resource-latency-historical.json','quality/data/resource-latency-release-historical.json')){
    Add-Source "docs/$name" "docs/$name"
}
Add-Source 'docs/quality/high-framerate-resources.md' 'docs/quality/high-framerate-resources.md'
Add-Source 'docs/quality/local-scene-preparation.md' 'docs/quality/local-scene-preparation.md'
foreach($file in Get-ChildItem -LiteralPath (Join-Path $repo 'docs/quality/data/local-scene-preparation') -File){
    Add-Source "docs/quality/data/local-scene-preparation/$($file.Name)" "docs/quality/data/local-scene-preparation/$($file.Name)"
}
Add-Source 'docs/release-independent-native-and-host-lighting.md' 'docs/release-independent-native-and-host-lighting.md'
Add-Source 'docs/quality/rtxpt-performance.md' 'docs/quality/rtxpt-performance.md'
foreach($name in @('lighting-repair-validation.md','native-lighting-performance.md','lighting-backend-comparison.md')){Add-Source "docs/quality/$name" "docs/quality/$name"}
foreach($name in @('native-lighting-performance.json','lighting-backend-comparison.json','lighting-lifecycle-curves.json','native-lighting-traversal.json')){Add-Source "docs/quality/data/$name" "docs/quality/data/$name"}
foreach($file in Get-ChildItem -LiteralPath (Join-Path $repo 'docs/quality/data/native-lighting-traversal') -File){
    Add-Source "docs/quality/data/native-lighting-traversal/$($file.Name)" "docs/quality/data/native-lighting-traversal/$($file.Name)"
}
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
