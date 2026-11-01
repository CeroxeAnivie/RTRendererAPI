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
Write-Output "LUXCORE_SDK=$env:LUXCORE_SDK"
Write-Output "LUXCORE_SOURCE=$env:LUXCORE_SOURCE"
