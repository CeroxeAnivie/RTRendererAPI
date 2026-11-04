<#
.SYNOPSIS
Build or verify the packaged RTXPT shaders for all six optical interface budgets.
.DESCRIPTION
Relative paths resolve from the repository root. -PlanOnly reports missing compiled keys without
compiling; -VerifyOnly checks both checksums and captured configuration coverage. -RequireReleaseCoverage
also requires reference/build/fill branches for host-primary, scene lighting and small-point lighting.
#>
param(
    [string]$ManifestDirectory='build/rtxpt-shader-manifests',
    [string]$OutputDirectory='build/rtxpt-six-budget-bundle',
    [string]$RuntimeDirectory='build/rtxpt-sdk/bin',
    [switch]$CheckPaths,
    [switch]$VerifyOnly,
    [switch]$PlanOnly,
    [switch]$RequireReleaseCoverage
)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$utf8=[Text.UTF8Encoding]::new($false)
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
function Full-Path([string]$path){[IO.Path]::GetFullPath($path,$repo)}
$runtime=Full-Path $RuntimeDirectory
$output=Full-Path $OutputDirectory
if(!$output.StartsWith((Join-Path $repo 'build')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){
    throw 'Shader bundle output must stay inside this repository build directory'
}
$sourceRoot=Join-Path $runtime 'ShaderDynamic/Source'
if($CheckPaths){
    if(!(Test-Path -LiteralPath $sourceRoot -PathType Container)){throw 'Missing RTXPT runtime shader source directory'}
    [pscustomobject]@{Repository=$repo;Runtime=$runtime;Sources=$sourceRoot;Manifests=(Full-Path $ManifestDirectory);Output=$output}
    return
}
function Hash-Text([string]$text){[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($utf8.GetBytes($text))).ToLowerInvariant()}
function Shader-Plan {
    $manifests=@(Get-ChildItem -LiteralPath (Full-Path $ManifestDirectory) -Filter '*.rtrdxc' -File | Sort-Object Name)
    if(!$manifests){throw 'No upstream DXC manifests. Run native cases with RTR_RTXPT_SHADER_MANIFEST_DIR set to the manifest directory'}
    $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach($manifest in $manifests){
        $lines=[IO.File]::ReadAllLines($manifest.FullName)
        if($lines.Count -ne 4 -or $lines[1] -cne (Hash-Text $lines[3])){throw "Invalid upstream manifest: $($manifest.Name)"}
        $stem=$lines[0].Replace('\','/')
        $isOptical=$lines[3] -match '(?:^| )-D RTR_SCENE_LIGHTING=1(?: |$)'
        $budgets=if($isOptical){1..6}else{@(0)}
        foreach($budget in $budgets){
            # Exact/tree selection is runtime state. Older captures must
            # not put the retired threshold back into the runtime command key.
            $command=[regex]::Replace($lines[2],' -D RTR_SCENE_EXACT_LIGHT_LIMIT=[^ ]+','')
            $canonical=[regex]::Replace($lines[3],' -D RTR_SCENE_EXACT_LIGHT_LIMIT=[^ ]+','')
            if($canonical -match ' -D RTXPT_MATERIAL_PERMUTATIONS_ENABLED=1(?: |$)' -and
               $canonical -match ' -D ActiveBSDF=StandardBSDF(?: |$)'){
                $materialKey=$stem+'.hlsl, {RTXPT_MATERIAL_PERMUTATIONS_ENABLED,1},{ActiveBSDF,StandardBSDF}'
                $materialName='M'+(Hash-Text $materialKey).Substring(0,16)
                $pattern='(?<= -D RTXPT_MATERIAL_PERMUTATION_NAME=)([A-Za-z0-9]+)_[^ ]+'
                $replacement='${1}_'+$materialName
                $command=[regex]::Replace($command,$pattern,$replacement)
                $canonical=[regex]::Replace($canonical,$pattern,$replacement)
            }
            if($isOptical){
                if($canonical -notmatch ' -D RTR_SCENE_EXACT_INTERFACE_COUNT=[1-6](?: |$)'){throw 'Optical manifest omits its explicit interface budget'}
                $command=[regex]::Replace($command,'(?<= -D RTR_SCENE_EXACT_INTERFACE_COUNT=)[1-6](?= |$)',[string]$budget)
                $canonical=[regex]::Replace($canonical,'(?<= -D RTR_SCENE_EXACT_INTERFACE_COUNT=)[1-6](?= |$)',[string]$budget)
            }
            $key=Hash-Text $canonical
            if(!$seen.Add($key)){continue}
            [pscustomobject]@{path=$stem+'_'+$key.Substring(0,24)+'.bin';stem=$stem;exactInterfaces=$budget;commandSha256=$key;command=$command;canonicalCommand=$canonical}
        }
    }
}
function Assert-Coverage($entries){
    foreach($budget in 1..6){if(!($entries | Where-Object {$_.exactInterfaces -eq $budget})){throw "Missing optical shader budget $budget"}}
    if(!$RequireReleaseCoverage){return}
    foreach($feature in @('RTR_HOST_PRIMARY','RTR_SCENE_LIGHTING','RTR_EXACT_SMALL_POINTS')){
        foreach($stem in @('PathTracerSample','PathTracerMaterialSpecializations')){
            foreach($mode in @('PT_REFERENCE','PT_DENOISING_LAYERS_BUILD','PT_DENOISING_LAYERS_FILL')){
                if(!($entries | Where-Object {$_.path.StartsWith($stem+'_',[StringComparison]::Ordinal) -and
                    $_.canonicalCommand -match " -D $feature=1(?: |$)" -and
                    $_.canonicalCommand -match " -D PATH_TRACER_MODE=$mode(?: |$)"})){
                    throw "Missing release shader branch: $feature / $stem / $mode. Capture this configuration before packaging."
                }
            }
        }
    }
}
function Source-Inventory {
    @((Get-ChildItem -LiteralPath $sourceRoot -File -Recurse | Sort-Object FullName) | ForEach-Object {
        [ordered]@{path=[IO.Path]::GetRelativePath($sourceRoot,$_.FullName).Replace('\','/');sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
    })
}
function Independent-SourceChanges($previousSources,$currentSources,$shaderPlan) {
    # A standalone entry point that no captured command or source includes
    # cannot change these binaries. Be conservative about macros and additions:
    # they still invalidate the full bundle rather than guessing dependencies.
    if($previousSources.Count -ne $currentSources.Count){return $null}
    $changed=[Collections.Generic.List[string]]::new()
    for($i=0;$i -lt $currentSources.Count;$i++){
        if($currentSources[$i].path -cne $previousSources[$i].path){return $null}
        if($currentSources[$i].sha256 -cne $previousSources[$i].sha256){$changed.Add($currentSources[$i].path)}
    }
    if(!$changed.Count){return ,@()}
    $leaves=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach($path in $changed){
        [void]$leaves.Add([IO.Path]::GetFileName($path))
        foreach($shader in $shaderPlan){if($shader.canonicalCommand.Contains('"@'+$path+'"',[StringComparison]::OrdinalIgnoreCase)){return $null}}
    }
    foreach($source in $currentSources){
        $text=[IO.File]::ReadAllText((Join-Path $sourceRoot $source.path))
        if($text -match '(?m)^\s*#\s*(?:include\b.*)?\\\s*$'){return $null}
        foreach($include in [regex]::Matches($text,'(?m)^\s*#\s*include\s+([^\r\n]+)')){
            $literal=[regex]::Match($include.Groups[1].Value,'^["<]([^">]+)[">]')
            if(!$literal.Success -or $leaves.Contains([IO.Path]::GetFileName($literal.Groups[1].Value))){return $null}
        }
    }
    return ,@($changed.ToArray())
}
function Verify-Bundle([string]$bundleDirectory=$output) {
    $indexPath=Join-Path $bundleDirectory 'bundle.json'
    if(!(Test-Path -LiteralPath $indexPath)){throw 'Missing six-budget bundle; collect native manifests and run this script first'}
    $index=Get-Content -LiteralPath $indexPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if($index.format -ne 1){throw 'Unsupported RTXPT shader bundle format'}
    $compiler=Join-Path $runtime 'ShaderDynamic/Tools/d3d12/x64/dxc.exe'
    if(!(Test-Path -LiteralPath $compiler) -or (Get-FileHash -LiteralPath $compiler -Algorithm SHA256).Hash.ToLowerInvariant() -cne $index.compilerSha256){throw 'Pinned DXC compiler changed; rebuild the shader bundle'}
    $current=@(Source-Inventory)
    if($current.Count -ne $index.sources.Count){throw 'Shader source inventory changed; rebuild the six-budget bundle'}
    for($i=0;$i -lt $current.Count;$i++){
        if($current[$i].path -cne $index.sources[$i].path -or $current[$i].sha256 -cne $index.sources[$i].sha256){throw "Shader source changed: $($current[$i].path)"}
    }
    $available=@{}
    foreach($entry in $index.shaders){
        if((Hash-Text $entry.canonicalCommand) -cne $entry.commandSha256 -or
            !$entry.path.EndsWith('_'+$entry.commandSha256.Substring(0,24)+'.bin',[StringComparison]::Ordinal)){throw "Invalid shader command identity: $($entry.path)"}
        $path=[IO.Path]::GetFullPath((Join-Path $bundleDirectory $entry.path))
        if(!$path.StartsWith($bundleDirectory+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Shader bundle path escapes its directory'}
        if(!(Test-Path -LiteralPath $path) -or (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $entry.sha256){throw "Missing or corrupt shader $($entry.path)"}
        $available[$entry.commandSha256]=$entry.path
    }
    foreach($expected in @(Shader-Plan)){
        if($available[$expected.commandSha256] -cne $expected.path){throw "Captured configuration is absent from bundle: $($expected.path)"}
    }
    Assert-Coverage $index.shaders
    Write-Host "RTXPT shader bundle verified: $($index.shaders.Count) upstream permutations, all captured configurations and six optical budgets"
}
if($VerifyOnly){Verify-Bundle;return}
$plan=@(Shader-Plan)
Assert-Coverage $plan
if($PlanOnly){
    $existing=@{}
    $indexPath=Join-Path $output 'bundle.json'
    if(Test-Path -LiteralPath $indexPath){
        $index=Get-Content -LiteralPath $indexPath -Raw -Encoding UTF8 | ConvertFrom-Json
        foreach($entry in $index.shaders){$existing[$entry.commandSha256]=$entry}
    }
    $missing=@($plan | Where-Object {!$existing.ContainsKey($_.commandSha256) -or !(Test-Path -LiteralPath (Join-Path $output $_.path))})
    [pscustomobject]@{Permutations=$plan.Count;MissingKeys=$missing.Count;HostPrimary=@($plan|Where-Object {$_.canonicalCommand -match ' -D RTR_HOST_PRIMARY=1(?: |$)'}).Count;SmallPoint=@($plan|Where-Object {$_.canonicalCommand -match ' -D RTR_EXACT_SMALL_POINTS=1(?: |$)'}).Count;MissingPaths=@($missing.path)}
    return
}
$compiler=Join-Path $runtime 'ShaderDynamic/Tools/d3d12/x64/dxc.exe'
if(!(Test-Path -LiteralPath $compiler)){throw 'Missing the pinned upstream DXC compiler'}
$sources=@(Source-Inventory)
$compilerHash=(Get-FileHash -LiteralPath $compiler -Algorithm SHA256).Hash.ToLowerInvariant()
$previousShaders=@{}
$independentChanges=$null
$previousIndex=Join-Path $output 'bundle.json'
if(Test-Path -LiteralPath $previousIndex){
    $previous=Get-Content -LiteralPath $previousIndex -Raw -Encoding UTF8 | ConvertFrom-Json
    $independentChanges=Independent-SourceChanges $previous.sources $sources $plan
    $sameSources=$null -ne $independentChanges
    $previousCompiler=$previous.compilerSha256
    $legacyArchive=Join-Path $repo 'renderer-rtxpt/build/native-bundle/rtxpt-runtime.zip'
    if(!$previousCompiler -and (Test-Path -LiteralPath $legacyArchive)){
        $archive=[IO.Compression.ZipFile]::OpenRead($legacyArchive)
        try{
            $entry=$archive.GetEntry('ShaderDynamic/Tools/d3d12/x64/dxc.exe')
            if($entry){$stream=$entry.Open();try{$previousCompiler=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)).ToLowerInvariant()}finally{$stream.Dispose()}}
        }finally{$archive.Dispose()}
    }
    if($sameSources -and $previousCompiler -ceq $compilerHash){
        foreach($shader in $previous.shaders){$previousShaders[$shader.commandSha256]=$shader}
    }
}
$sourceStamp=(Get-ChildItem -LiteralPath $sourceRoot -File -Recurse | Measure-Object LastWriteTimeUtc -Maximum).Maximum
$stage=$output+'.stage-'+[Guid]::NewGuid().ToString('N')
$backup=$output+'.previous-'+[Guid]::NewGuid().ToString('N')
New-Item -ItemType Directory -Path $stage -Force | Out-Null
$entries=[Collections.Generic.List[object]]::new()
$oldMoved=$false
try {
    foreach($item in $plan){
            $command=$item.command;$canonical=$item.canonicalCommand;$key=$item.commandSha256
            $relative=$item.path;$budget=$item.exactInterfaces
            $destination=[IO.Path]::GetFullPath((Join-Path $stage $relative))
            if(!$destination.StartsWith($stage+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Manifest output path escapes the staging directory'}
            New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($destination)) -Force | Out-Null
            $cached=Join-Path (Join-Path $runtime 'ShaderDynamic/Bin/dxil') $relative
            $origin='dxc'
            $previousShader=$previousShaders[$key]
            $previousBinary=if($previousShader){Join-Path $output $previousShader.path}else{$null}
            if($previousBinary -and (Test-Path -LiteralPath $previousBinary) -and
                (Get-FileHash -LiteralPath $previousBinary -Algorithm SHA256).Hash.ToLowerInvariant() -ceq $previousShader.sha256){
                Copy-Item -LiteralPath $previousBinary -Destination $destination
                $origin='content-verified-bundle'
            }elseif((Test-Path -LiteralPath $cached) -and (Get-Item -LiteralPath $cached).LastWriteTimeUtc -ge $sourceStamp){
                Copy-Item -LiteralPath $cached -Destination $destination
                $origin='validated-runtime-cache'
            } else {
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
            }
            [IO.File]::SetLastWriteTimeUtc($destination,$sourceStamp)
            $entries.Add([ordered]@{path=$relative;exactInterfaces=$budget;commandSha256=$key;sha256=(Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant();bytes=(Get-Item -LiteralPath $destination).Length;origin=$origin;canonicalCommand=$canonical})
            Write-Host "RTXPT offline shader $($entries.Count): optical budget=$budget origin=$origin $relative"
    }
    Assert-Coverage $entries
    $index=[ordered]@{format=1;pipelineIdentity='runtime StandardBSDF; captured host-primary, analytic and small-point configurations';compilerSha256=$compilerHash;independentSourceChanges=@($independentChanges);contract='All captured renderer configurations with canonical material identities and scene-analytic budgets 1..6 are required. Exact/tree selection is runtime state. Release coverage additionally checks host-primary, scene-analytic and small-point reference/build/fill branches. Uncaptured renderer configurations may use the bundled upstream compiler; strict package acceptance must disable that fallback.';sources=$sources;shaders=@($entries.ToArray())}
    [IO.File]::WriteAllText((Join-Path $stage 'bundle.json'),($index | ConvertTo-Json -Depth 8),$utf8)
    Verify-Bundle $stage
    if(Test-Path -LiteralPath $output){[IO.Directory]::Move($output,$backup);$oldMoved=$true}
    [IO.Directory]::Move($stage,$output)
    if($oldMoved){Remove-Item -LiteralPath $backup -Recurse -Force}
} catch {
    if($oldMoved -and !(Test-Path -LiteralPath $output)){[IO.Directory]::Move($backup,$output);$oldMoved=$false}
    throw
} finally {
    if(Test-Path -LiteralPath $stage){Remove-Item -LiteralPath $stage -Recurse -Force}
}
