<# CPU-only check of original packed-table slicing; no native device or network. #>
param([string]$Compiler='cl.exe',[string]$SdkRoot='build/upstream-rtxpt/source')
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if(-not [IO.Path]::IsPathRooted($SdkRoot)){$SdkRoot=Join-Path $repo $SdkRoot}
$include=Join-Path $SdkRoot 'External/Donut/nvrhi/include'
if(-not (Test-Path -LiteralPath (Join-Path $include 'nvrhi/nvrhi.h'))){throw 'Verified RTXPT/NVRHI export is required'}
$out=Join-Path $repo 'build/scene-table-upload-selftest'
[IO.Directory]::CreateDirectory($out)|Out-Null
$runner=Join-Path $PSScriptRoot 'Invoke-BoundedBuild.ps1'
$source=Join-Path $repo 'renderer-rtxpt/src/main/cpp/rtxpt_scene_table_upload_selftest.cpp'
$exe=Join-Path $out 'scene-table-upload.exe'
& $runner -FilePath $Compiler -ArgumentList @('/nologo','/std:c++20','/EHsc','/O2','/utf-8',"/I$include",$source,"/Fe:$exe","/Fo:$(Join-Path $out 'scene-table-upload.obj')") -MemoryLimitGB 10
if($LASTEXITCODE){throw "Scene-table upload compile failed: $LASTEXITCODE"}
& $runner -FilePath $exe -MemoryLimitGB 10
if($LASTEXITCODE){throw "Scene-table upload selftest failed: $LASTEXITCODE"}
