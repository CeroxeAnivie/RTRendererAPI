<# Isolated CPU application-patch/range checks; no JNI, device, network or visible window. #>
param([string]$Compiler='cl.exe',[string]$JavaCompiler='javac.exe',[string]$Java='java.exe')
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$out=Join-Path $repo 'build/native-application-selftest'
[IO.Directory]::CreateDirectory($out)|Out-Null
$runner=Join-Path $PSScriptRoot 'Invoke-BoundedBuild.ps1'
$source=Join-Path $repo 'renderer-rtxpt/src/main/cpp/rtxpt_native_application_ranges_selftest.cpp'
$exe=Join-Path $out 'ranges.exe'
& $runner -FilePath $Compiler -ArgumentList @('/nologo','/std:c++20','/EHsc','/O2','/utf-8',$source,"/Fe:$exe","/Fo:$(Join-Path $out 'ranges.obj')") -MemoryLimitGB 10
if($LASTEXITCODE){throw "Application range compile failed: $LASTEXITCODE"}
& $runner -FilePath $exe -MemoryLimitGB 10
if($LASTEXITCODE){throw "Application range selftest failed: $LASTEXITCODE"}
$record=Join-Path $repo 'renderer-api/src/main/java/top/ceroxe/rt/renderer/api/NativeLightingApplicationPatch.java'
$test=Join-Path $repo 'renderer-api/src/test/java/top/ceroxe/rt/renderer/api/NativeLightingApplicationPatchSelfTest.java'
& $runner -FilePath $JavaCompiler -ArgumentList @('-encoding','UTF-8','-d',$out,$record,$test) -MemoryLimitGB 10
if($LASTEXITCODE){throw "Application patch compile failed: $LASTEXITCODE"}
& $runner -FilePath $Java -ArgumentList @('-Xmx128m','-cp',$out,'top.ceroxe.rt.renderer.api.NativeLightingApplicationPatchSelfTest') -MemoryLimitGB 10
if($LASTEXITCODE){throw "Application patch selftest failed: $LASTEXITCODE"}
