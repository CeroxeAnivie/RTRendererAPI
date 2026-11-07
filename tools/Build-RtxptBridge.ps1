<#
.SYNOPSIS
Build the external API adapter against the verified, unmodified RTXPT release.
.DESCRIPTION
8.0 permits the audited noninteractive progress guard and network-only preferences.
No upstream rendering/shader patches, source overlays, or shader injection.
Legacy overlay build arguments are intentionally unsupported.
#>
param(
    [string]$SdkRoot='build/upstream-rtxpt/source',
    [string]$BuildRoot='build/upstream-rtxpt/native',
    [string]$CMakeExecutable='build/native-build-tools/cmake-4.1.2-windows-x86_64/bin/cmake.exe',
    [ValidateRange(1,10)][double]$MemoryLimitGB=10,
    [switch]$ConfigureOnly,
    [switch]$CheckPaths
)
$ErrorActionPreference='Stop'
$buildArguments=@{
    SdkRoot=$SdkRoot;BuildRoot=$BuildRoot;CMakeExecutable=$CMakeExecutable
    MemoryLimitGB=$MemoryLimitGB;Target=@('rtrenderer_rtxpt','rtrenderer_jvm_host')
    WithBridge=$true;ConfigureOnly=$ConfigureOnly;CheckPaths=$CheckPaths
}
& (Join-Path $PSScriptRoot 'Build-RtxptUpstream.ps1') @buildArguments
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
