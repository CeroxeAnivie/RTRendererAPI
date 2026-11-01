param(
    [string]$Destination = (Join-Path $PSScriptRoot '../build/luxcore-sdk'),
    [string]$Proxy = $env:HTTPS_PROXY
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$Destination = [IO.Path]::GetFullPath($Destination)
New-Item -ItemType Directory -Path $Destination -Force | Out-Null
function Get-PinnedArchive([string]$Url, [string]$Name, [string]$Hash) {
    $path = Join-Path $Destination $Name
    if (!(Test-Path -LiteralPath $path)) {
        $download = @{Uri=$Url; OutFile=$path}
        if ($Proxy) { $download.Proxy = $Proxy }
        Invoke-WebRequest @download
    }
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $Hash) {
        throw "SHA-256 mismatch: $path"
    }
    return $path
}
$sdk = Get-PinnedArchive 'https://github.com/LuxCoreRender/LuxCore/releases/download/samples-v2.11.2/LuxCore-Samples-Windows-X64.zip' 'samples-v2.11.2.zip' '507569EC86CB75A9A3301C8CF5EAC0C5ED6DB483B9792FEA183A0B55C9897797'
$source = Get-PinnedArchive 'https://codeload.github.com/LuxCoreRender/LuxCore/zip/b872186d2fe995a762f73708c606847eed55843e' 'source.zip' 'C93F9889669A20C4DA0B28D8ED94C2296457E8BD358AC5F3CC0456A9A708A2C3'
$boost = Get-PinnedArchive 'https://github.com/boostorg/boost/releases/download/boost-1.88.0/boost-1.88.0-b2-nodocs.zip' 'boost-1.88.0.zip' 'E96A17A1B641909CC6BC2337C77D1C74D13EFABF4A87F4E7F2E7D748C7C7E9A0'
if (!(Test-Path (Join-Path $Destination 'LuxCoreSamples-Windows-X64.zip'))) {
    Expand-Archive -LiteralPath $sdk -DestinationPath $Destination
}
if (!(Test-Path (Join-Path $Destination 'LuxCore/bin/luxcore.dll'))) {
    Expand-Archive -LiteralPath (Join-Path $Destination 'LuxCoreSamples-Windows-X64.zip') -DestinationPath $Destination
}
$headers = Join-Path $Destination 'LuxCore-b872186d2fe995a762f73708c606847eed55843e'
if (!(Test-Path (Join-Path $headers 'include/luxcore/luxcore.h'))) {
    Expand-Archive -LiteralPath $source -DestinationPath $Destination
}
# Process-local convenience only; never persist a workstation proxy in project configuration.
$env:LUXCORE_SDK = Join-Path $Destination 'LuxCore'
$env:LUXCORE_SOURCE = $headers
$env:BOOST_INCLUDE = Join-Path $Destination 'boost-headers'
if (!(Test-Path (Join-Path $env:BOOST_INCLUDE 'boost/version.hpp'))) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($boost)
    try {
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName -notmatch '^[^/]+/(boost/[^:]+)$' -or !$entry.Name) { continue }
            $target = [IO.Path]::GetFullPath((Join-Path $env:BOOST_INCLUDE $Matches[1]))
            if (!$target.StartsWith([IO.Path]::GetFullPath($env:BOOST_INCLUDE) + [IO.Path]::DirectorySeparatorChar)) { throw 'Invalid Boost archive path' }
            [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target)) | Out-Null
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
        }
    } finally { $archive.Dispose() }
}
Write-Output "LUXCORE_SDK=$env:LUXCORE_SDK"
Write-Output "LUXCORE_SOURCE=$env:LUXCORE_SOURCE"
Write-Output "BOOST_INCLUDE=$env:BOOST_INCLUDE"
