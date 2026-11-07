<#
.SYNOPSIS
Verify the pinned, unmodified RTXPT source tree and its recursive submodules.
#>
param(
    [string]$SdkRoot='build/upstream-rtxpt/source',
    [string]$SourceManifest=''
)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$sdk=(Resolve-Path -LiteralPath ([IO.Path]::GetFullPath($SdkRoot,$repo))).Path
$expected='303b408ab81790a5051abd7573830b7a4112eb8e'
$manifestPath=if($SourceManifest){[IO.Path]::GetFullPath($SourceManifest,$repo)}else{Join-Path (Split-Path -Parent $sdk) 'source-manifest.json'}
if(!(Test-Path -LiteralPath (Join-Path $sdk '.git')) -and (Test-Path -LiteralPath $manifestPath)){
    $manifest=Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8|ConvertFrom-Json
    if($manifest.format -ne 'rtr-upstream-git-export-v1' -or $manifest.commit -ne $expected){
        throw 'The source export manifest does not identify the pinned official release.'
    }
    if([IO.Path]::GetFullPath($manifest.sourceRoot) -ne $sdk){throw 'Source export manifest root mismatch.'}
    if($manifest.files.Count -eq 0){throw 'The source export manifest is empty.'}
    $prefix=$sdk.TrimEnd([IO.Path]::DirectorySeparatorChar)+[IO.Path]::DirectorySeparatorChar
    $expectedPaths=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach($entry in $manifest.files){
        [void]$expectedPaths.Add($entry.path.Replace('\','/'))
        $path=[IO.Path]::GetFullPath($entry.path,$sdk)
        if(!$path.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase)){throw 'Source manifest contains an out-of-tree path.'}
        $file=Get-Item -LiteralPath $path -ErrorAction Stop
        if($file.Length -ne $entry.bytes -or (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.sha256){
            throw ('Official source file differs from its Git export: '+$path)
        }
    }
    # These directories are generated/downloaded by the unchanged official CMake.
    # API source additions have no allowed location inside the exported core.
    $generatedRoots=@('bin/','bin_arm64/','External/dxc/','External/AgilitySDK/',
        'External/Streamline/','External/Streamline-arm64/')
    # NRD/CMakeLists.txt generates NRDConfig; External/CMakeLists.txt generates Omm.cfg.
    $generatedFiles=@('Support/denoiser_OptiX/Denoiser.exe','External/Omm.cfg',
        'External/Nrd/Shaders/NRDConfig.hlsli')
    $unexpected=[Collections.Generic.List[string]]::new()
    foreach($file in Get-ChildItem -LiteralPath $sdk -File -Recurse){
        $relative=[IO.Path]::GetRelativePath($sdk,$file.FullName).Replace('\','/')
        if($expectedPaths.Contains($relative)){continue}
        if($generatedFiles -contains $relative){continue}
        $generated=$false
        foreach($root in $generatedRoots){
            if($relative.StartsWith($root,[StringComparison]::OrdinalIgnoreCase)){$generated=$true;break}
        }
        if(!$generated){$unexpected.Add($relative)}
    }
    if($unexpected.Count -gt 0){
        throw ('Unexpected files inside official source export: '+(($unexpected|Select-Object -First 10)-join ', '))
    }
    [pscustomobject]@{
        SdkRoot=$sdk
        Release=$manifest.release
        Commit=$manifest.commit
        Repositories=@($manifest.repositories|Where-Object {$_.exported -ne $false}).Count
        VerifiedFiles=$manifest.files.Count
        ManifestSha256=(Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
        OriginalFilesUnmodified=$true
        UnexpectedSourceFiles=0
        SceneAssets='Supplied separately by the verified official release runtime; not core compilation inputs.'
    }
    return
}
$actual=(& git -C $sdk rev-parse HEAD | Out-String).Trim()
if($LASTEXITCODE -ne 0 -or $actual -ne $expected){
    throw 'RTXPT must use the pinned official v.1.9.0 commit.'
}
$scopes=[Collections.Generic.List[object]]::new()
$scopes.Add([pscustomobject]@{Path=$sdk;Commit=$actual})
$submodules=@(& git -C $sdk submodule status --recursive)
if($LASTEXITCODE -ne 0){throw 'Could not inspect the official recursive submodules.'}
foreach($line in $submodules){
    $relative=if($line.Length -ge 43){$line.Substring(42).Split(' ')[0]}else{''}
    if($relative -eq 'Assets' -and $line[0] -eq '-') {continue}
    if($line.Length -lt 43 -or $line[0] -ne ' '){
        throw ('Missing or mismatched upstream submodule: '+$line)
    }
    $scopes.Add([pscustomobject]@{
        Path=[IO.Path]::GetFullPath($relative,$sdk)
        Commit=$line.Substring(1,40)
    })
}
foreach($scope in $scopes){
    # Inspect each repository separately. Generated, untracked dependencies are
    # allowed; no tracked source, shader, CMake file or submodule commit may differ.
    & git -C $scope.Path diff --quiet --ignore-submodules=all HEAD -- .
    if($LASTEXITCODE -ne 0){
        throw ('Upstream tracked files are modified; refusing an overlaid core: '+$scope.Path)
    }
}
[pscustomobject]@{
    SdkRoot=$sdk
    Release='v.1.9.0'
    Commit=$actual
    Repositories=$scopes.Count
    TrackedSourcesUnmodified=$true
}
