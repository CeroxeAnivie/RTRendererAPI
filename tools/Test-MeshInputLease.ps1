<# CPU-only canonical input ownership check; no native device or network. #>
param([string]$Compiler='cl.exe')
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$out=Join-Path $repo 'build/mesh-input-lease-selftest'
[IO.Directory]::CreateDirectory($out)|Out-Null
$runner=Join-Path $PSScriptRoot 'Invoke-BoundedBuild.ps1'
$source=Join-Path $repo 'renderer-rtxpt/src/main/cpp/rtxpt_mesh_input_lease_selftest.cpp'
$exe=Join-Path $out 'mesh-input-lease.exe'
& $runner -FilePath $Compiler -ArgumentList @('/nologo','/std:c++20','/EHsc','/O2','/utf-8',$source,"/Fe:$exe","/Fo:$(Join-Path $out 'mesh-input-lease.obj')") -MemoryLimitGB 10
if($LASTEXITCODE){throw "Mesh-input lease compile failed: $LASTEXITCODE"}
& $runner -FilePath $exe -MemoryLimitGB 10
if($LASTEXITCODE){throw "Mesh-input lease selftest failed: $LASTEXITCODE"}
