<# Independent CPU recorder tests; no RTXPT session, visible window, GPU work or network required. #>
param([string]$Compiler='cl.exe',[string]$JavaCompiler='javac.exe',[string]$Java='java.exe',
    [switch]$IncludeMemory,[string]$DirectXHeaders='build/rtxpt-sdk/build-host/_deps/directx_headers-src/include')
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$out=Join-Path $repo 'build/diagnostics-selftest'
[IO.Directory]::CreateDirectory($out)|Out-Null
$runner=Join-Path $PSScriptRoot 'Invoke-BoundedBuild.ps1'
$source=Join-Path $repo 'renderer-rtxpt/src/main/cpp/rtxpt_diagnostics_selftest.cpp'
$exe=Join-Path $out 'diagnostics-selftest.exe'
$obj=Join-Path $out 'diagnostics-selftest.obj'
& $runner -FilePath $Compiler -ArgumentList @('/nologo','/std:c++17','/EHsc','/O2','/utf-8',$source,"/Fe:$exe","/Fo:$obj") -MemoryLimitGB 10
if($LASTEXITCODE){throw "Native diagnostics compile failed: $LASTEXITCODE"}
$fixture=Join-Path $out 'snapshot.bin'
& $runner -FilePath $exe -ArgumentList @($fixture) -MemoryLimitGB 10
if($LASTEXITCODE){throw "Native diagnostics selftest failed: $LASTEXITCODE"}
$classes=Join-Path $out 'classes'
$javaSource=Join-Path $repo 'renderer-rtxpt/src/main/java/top/ceroxe/rt/renderer/rtxpt/RtxptDiagnostics.java'
$testSource=Join-Path $repo 'renderer-rtxpt/src/test/java/top/ceroxe/rt/renderer/rtxpt/RtxptDiagnosticsSelfTest.java'
& $runner -FilePath $JavaCompiler -ArgumentList @('-encoding','UTF-8','-sourcepath',(Join-Path $repo 'renderer-rtxpt/src/main/java'),'-d',$classes,$javaSource,$testSource) -MemoryLimitGB 10
if($LASTEXITCODE){throw "Java diagnostics compile failed: $LASTEXITCODE"}
& $runner -FilePath $Java -ArgumentList @('-Xmx128m','-cp',$classes,'top.ceroxe.rt.renderer.rtxpt.RtxptDiagnosticsSelfTest',$fixture) -MemoryLimitGB 10
if($LASTEXITCODE){throw "Java diagnostics selftest failed: $LASTEXITCODE"}
if($IncludeMemory){
    $headers=[IO.Path]::GetFullPath($DirectXHeaders,$repo)
    if(!(Test-Path -LiteralPath (Join-Path $headers 'directx/d3d12.h'))){throw 'Pass -DirectXHeaders pointing to the pinned DirectX-Headers include directory'}
    $winrt=@(([Environment]::GetEnvironmentVariable('INCLUDE') -split ';') | Where-Object {$_} | ForEach-Object {
        Join-Path (Split-Path -Parent $_.TrimEnd('\','/')) 'winrt'
    } | Where-Object {Test-Path -LiteralPath (Join-Path $_ 'wrl/client.h')} | Select-Object -First 1)
    if(!$winrt){throw 'The configured Windows SDK include paths must contain a sibling winrt/wrl/client.h'}
    $memorySource=Join-Path $repo 'renderer-rtxpt/src/main/cpp/rtxpt_memory_diagnostics_selftest.cpp'
    $memoryExe=Join-Path $out 'memory-diagnostics-selftest.exe'
    $memoryObj=Join-Path $out 'memory-diagnostics-selftest.obj'
    & $runner -FilePath $Compiler -ArgumentList @('/nologo','/std:c++17','/EHsc','/O2','/utf-8',"/I$headers","/I$($winrt[0])",$memorySource,"/Fe:$memoryExe","/Fo:$memoryObj",'/link','dxgi.lib') -MemoryLimitGB 10
    if($LASTEXITCODE){throw "Memory diagnostics compile failed: $LASTEXITCODE"}
    foreach($arguments in @(@(),@('enabled'))){
        & $runner -FilePath $memoryExe -ArgumentList $arguments -MemoryLimitGB 10
        if($LASTEXITCODE){throw "Memory diagnostics selftest failed: $LASTEXITCODE"}
    }
}
