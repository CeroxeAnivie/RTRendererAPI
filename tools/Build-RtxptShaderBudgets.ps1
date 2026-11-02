param(
    [string]$ManifestDirectory='build/rtxpt-shader-manifests',
    [string]$OutputDirectory='build/rtxpt-six-budget-bundle',
    [string]$RuntimeDirectory='build/rtxpt-sdk/bin',
    [switch]$VerifyOnly
)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$utf8=[Text.UTF8Encoding]::new($false)
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
function Full-Path([string]$path){[IO.Path]::GetFullPath((Join-Path $repo $path))}
$runtime=Full-Path $RuntimeDirectory
$output=Full-Path $OutputDirectory
$sourceRoot=Join-Path $runtime 'ShaderDynamic/Source'
function Hash-Text([string]$text){[Convert]::ToHexStringLower([Security.Cryptography.SHA256]::HashData($utf8.GetBytes($text)))}
function Source-Inventory {
    @((Get-ChildItem -LiteralPath $sourceRoot -File -Recurse | Sort-Object FullName) | ForEach-Object {
        [ordered]@{path=[IO.Path]::GetRelativePath($sourceRoot,$_.FullName).Replace('\','/');sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
    })
}
function Verify-Bundle {
    $indexPath=Join-Path $output 'bundle.json'
    if(!(Test-Path -LiteralPath $indexPath)){throw 'Missing six-budget bundle; collect native manifests and run this script first'}
    $index=Get-Content -LiteralPath $indexPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if($index.format -ne 1){throw 'Unsupported RTXPT shader bundle format'}
    $current=Source-Inventory
    if($current.Count -ne $index.sources.Count){throw 'Shader source inventory changed; rebuild the six-budget bundle'}
    for($i=0;$i -lt $current.Count;$i++){
        if($current[$i].path -cne $index.sources[$i].path -or $current[$i].sha256 -cne $index.sources[$i].sha256){throw "Shader source changed: $($current[$i].path)"}
    }
    foreach($entry in $index.shaders){
        $path=[IO.Path]::GetFullPath((Join-Path $output $entry.path))
        if(!$path.StartsWith($output+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Shader bundle path escapes its directory'}
        if(!(Test-Path -LiteralPath $path) -or (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $entry.sha256){throw "Missing or corrupt shader $($entry.path)"}
    }
    foreach($budget in 1..6){if(!($index.shaders | Where-Object {$_.exactInterfaces -eq $budget})){throw "Missing optical shader budget $budget"}}
    Write-Host "RTXPT shader bundle verified: $($index.shaders.Count) upstream permutations, all six optical budgets"
}
if($VerifyOnly){Verify-Bundle;return}
$manifests=Get-ChildItem -LiteralPath (Full-Path $ManifestDirectory) -Filter '*.rtrdxc' -File
if(!$manifests){throw 'No upstream DXC manifests. Run native cases with RTR_RTXPT_SHADER_MANIFEST_DIR set to the manifest directory'}
$compiler=Join-Path $runtime 'ShaderDynamic/Tools/d3d12/x64/dxc.exe'
if(!(Test-Path -LiteralPath $compiler)){throw 'Missing the pinned upstream DXC compiler'}
$sources=Source-Inventory
$sourceStamp=(Get-ChildItem -LiteralPath $sourceRoot -File -Recurse | Measure-Object LastWriteTimeUtc -Maximum).Maximum
$stage=$output+'.stage-'+[Guid]::NewGuid().ToString('N')
$backup=$output+'.previous-'+[Guid]::NewGuid().ToString('N')
New-Item -ItemType Directory -Path $stage -Force | Out-Null
$entries=[Collections.Generic.List[object]]::new()
$seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$oldMoved=$false
try {
    foreach($manifest in $manifests){
        $lines=[IO.File]::ReadAllLines($manifest.FullName)
        if($lines.Count -ne 4 -or $lines[1] -cne (Hash-Text $lines[3])){throw "Invalid upstream manifest: $($manifest.Name)"}
        $stem=$lines[0].Replace('\','/')
        $isOptical=$lines[3] -match '(?:^| )-D RTR_SCENE_LIGHTING=1(?: |$)'
        $budgets=if($isOptical){1..6}else{@(0)}
        foreach($budget in $budgets){
            $command=$lines[2];$canonical=$lines[3]
            if($isOptical){
                if($canonical -notmatch ' -D RTR_SCENE_EXACT_INTERFACE_COUNT=[1-6](?: |$)'){throw 'Optical manifest omits its explicit interface budget'}
                $command=[regex]::Replace($command,'(?<= -D RTR_SCENE_EXACT_INTERFACE_COUNT=)[1-6](?= |$)',[string]$budget)
                $canonical=[regex]::Replace($canonical,'(?<= -D RTR_SCENE_EXACT_INTERFACE_COUNT=)[1-6](?= |$)',[string]$budget)
            }
            $key=Hash-Text $canonical
            if(!$seen.Add($key)){continue}
            $relative=$stem+'_'+$key.Substring(0,24)+'.bin'
            $destination=[IO.Path]::GetFullPath((Join-Path $stage $relative))
            if(!$destination.StartsWith($stage+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Manifest output path escapes the staging directory'}
            New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($destination)) -Force | Out-Null
            $start=[Diagnostics.ProcessStartInfo]::new()
            $start.FileName=$compiler
            $start.Arguments=$command+' -Fo "'+$destination+'"'
            $start.WorkingDirectory=$repo
            $start.UseShellExecute=$false;$start.CreateNoWindow=$true
            $start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
            $process=[Diagnostics.Process]::Start($start)
            $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync()
            $process.WaitForExit();$outText=$stdout.GetAwaiter().GetResult();$errText=$stderr.GetAwaiter().GetResult()
            if($process.ExitCode -ne 0 -or !(Test-Path -LiteralPath $destination)){throw "DXC failed ($($process.ExitCode)) for $relative`n$outText`n$errText"}
            [IO.File]::SetLastWriteTimeUtc($destination,$sourceStamp)
            $entries.Add([ordered]@{path=$relative;exactInterfaces=$budget;commandSha256=$key;sha256=(Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant();bytes=(Get-Item -LiteralPath $destination).Length;canonicalCommand=$canonical})
            Write-Host "RTXPT offline shader $($entries.Count): optical budget=$budget $relative"
        }
    }
    foreach($budget in 1..6){if(!($entries | Where-Object {$_.exactInterfaces -eq $budget})){throw "No compiled optical permutation for budget $budget"}}
    $index=[ordered]@{format=1;contract='Captured upstream permutations; every captured scene-analytic permutation has budgets 1..6. Unseen permutations use the bundled upstream compiler.';sources=$sources;shaders=@($entries.ToArray())}
    [IO.File]::WriteAllText((Join-Path $stage 'bundle.json'),($index | ConvertTo-Json -Depth 8),$utf8)
    if(Test-Path -LiteralPath $output){[IO.Directory]::Move($output,$backup);$oldMoved=$true}
    [IO.Directory]::Move($stage,$output)
    Verify-Bundle
    if($oldMoved){Remove-Item -LiteralPath $backup -Recurse -Force}
} catch {
    if($oldMoved -and !(Test-Path -LiteralPath $output)){[IO.Directory]::Move($backup,$output);$oldMoved=$false}
    throw
} finally {
    if(Test-Path -LiteralPath $stage){Remove-Item -LiteralPath $stage -Recurse -Force}
}
