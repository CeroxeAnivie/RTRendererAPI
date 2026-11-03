<# Tests host-surface descriptors and the allocation-independent fence protocol, with no GPU work. #>
param([string]$Compiler='cl.exe',[string]$JavaCompiler='javac.exe',[string]$Java='java.exe',[switch]$CheckGpuHeader)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$out=Join-Path $repo 'build/host-surface-selftest'
[IO.Directory]::CreateDirectory($out)|Out-Null
$runner=Join-Path $PSScriptRoot 'Invoke-BoundedBuild.ps1'
$source=Join-Path $repo 'renderer-rtxpt/src/main/cpp/rtxpt_surface_inputs_selftest.cpp'
$exe=Join-Path $out 'surface-inputs-selftest.exe'
& $runner -FilePath $Compiler -ArgumentList @('/nologo','/std:c++17','/EHsc','/O2','/utf-8',$source,"/Fe:$exe","/Fo:$(Join-Path $out 'surface-inputs-selftest.obj')") -MemoryLimitGB 10
if($LASTEXITCODE){throw "Host-surface protocol compile failed: $LASTEXITCODE"}
& $runner -FilePath $exe -MemoryLimitGB 10
if($LASTEXITCODE){throw "Host-surface protocol selftest failed: $LASTEXITCODE"}
$api=Join-Path $repo 'renderer-api/src/main/java'
$package=Join-Path $api 'top/ceroxe/rt/renderer/api'
$classes=Join-Path $out 'classes'
$test=Join-Path $repo 'renderer-api/src/test/java/top/ceroxe/rt/renderer/api/HostSurfaceContractSelfTest.java'
$sources=@('FrameClock.java','HostSurfacePlan.java','HostSurfaceInputLease.java')|ForEach-Object {Join-Path $package $_}
& $runner -FilePath $JavaCompiler -ArgumentList (@('-encoding','UTF-8','-d',$classes)+$sources+@($test)) -MemoryLimitGB 10
if($LASTEXITCODE){throw "Host-surface contract compile failed: $LASTEXITCODE"}
& $runner -FilePath $Java -ArgumentList @('-Xmx128m','-cp',$classes,'top.ceroxe.rt.renderer.api.HostSurfaceContractSelfTest') -MemoryLimitGB 10
if($LASTEXITCODE){throw "Host-surface contract selftest failed: $LASTEXITCODE"}
if($CheckGpuHeader){
    $directx=Join-Path $repo 'build/rtxpt-sdk/build-host/_deps/directx_headers-src/include'
    $nvrhi=Join-Path $repo 'build/rtxpt-sdk/External/Donut/nvrhi/include'
    $native=Join-Path $repo 'renderer-rtxpt/src/main/cpp'
    $winrt=@(([Environment]::GetEnvironmentVariable('INCLUDE') -split ';')|Where-Object {$_}|ForEach-Object {
        Join-Path (Split-Path -Parent $_.TrimEnd('\','/')) 'winrt'
    }|Where-Object {Test-Path -LiteralPath (Join-Path $_ 'wrl/client.h')}|Select-Object -First 1)
    if(!$winrt){throw 'The configured Windows SDK include paths must contain a sibling winrt/wrl/client.h'}
    $headerSource=Join-Path $out 'gpu-header.cpp'
    [IO.File]::WriteAllText($headerSource,"#include `"rtxpt_surface_inputs.h`"`n",[Text.UTF8Encoding]::new($false))
    & $runner -FilePath $Compiler -ArgumentList @('/nologo','/std:c++17','/EHsc','/utf-8','/Zs',"/I$directx","/I$nvrhi","/I$native","/I$($winrt[0])",$headerSource) -MemoryLimitGB 10
    if($LASTEXITCODE){throw "Host-surface GPU header compile failed: $LASTEXITCODE"}
    Write-Output 'HOST_SURFACE_GPU_HEADER_COMPILE PASS'
    $hostSource=Join-Path $out 'host-codegen.cpp'
    $hostBody=@'
#include "rtxpt_host_surface.h"
void ForceCodeGeneration(RtrHostSurface& surface,nvrhi::IDevice* device,const std::shared_ptr<donut::engine::Scene>& scene,nvrhi::ICommandList* command,nvrhi::BindingSetDesc& bindings){
    surface.PollWork(device);surface.RecordWork(device);surface.Prepare(device,scene,command);surface.Bind(bindings);
}
'@
    [IO.File]::WriteAllText($hostSource,$hostBody,[Text.UTF8Encoding]::new($false))
    $donut=Join-Path $repo 'build/rtxpt-sdk/External/Donut/include'
    & $runner -FilePath $Compiler -ArgumentList @('/nologo','/std:c++17','/EHsc','/utf-8','/O2','/c','/DNOMINMAX',"/I$nvrhi","/I$native","/I$donut",$hostSource,"/Fo:$(Join-Path $out 'host-codegen.obj')") -MemoryLimitGB 10
    if($LASTEXITCODE){throw "Host-surface optimized codegen failed: $LASTEXITCODE"}
    Write-Output 'HOST_SURFACE_OPTIMIZED_CODEGEN PASS'
}
