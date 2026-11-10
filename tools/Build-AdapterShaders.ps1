<#
.SYNOPSIS
Compile external adapter shaders with the official ShaderMake and DXC tools.
.DESCRIPTION
Sources remain in renderer-rtxpt/src/main/cpp. Runtime blobs are written to
runtime/bin/AdapterShaders; stable per-shader configs live in build/adapter-shader-configs.
No shader is copied into the SDK and no upstream target is changed.
The resulting blobs are for a separate Donut ShaderFactory rooted at that path.
#>
param(
    [string]$SdkRoot='build/upstream-rtxpt/source',
    [string]$ShaderMakeExecutable='build/upstream-rtxpt/source/bin/ShaderMake.exe',
    [string]$DxcExecutable='build/upstream-rtxpt/source/External/dxc/build/native/bin/x64/dxc.exe',
    [string]$RuntimeDirectory='build/upstream-rtxpt/runtime/rtxpt_v.1.9.0_binaries/bin',
    [string]$ShaderName='',
    [ValidateRange(1,16)][int]$Jobs=4,
    [ValidateRange(1,10)][double]$MemoryLimitGB=10,
    [switch]$CheckPaths
)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$identity=& (Join-Path $PSScriptRoot 'Assert-RtxptUpstream.ps1') -SdkRoot $SdkRoot
$sdk=(Resolve-Path -LiteralPath ([IO.Path]::GetFullPath($SdkRoot,$repo))).Path
$compiler=(Resolve-Path -LiteralPath ([IO.Path]::GetFullPath($DxcExecutable,$repo))).Path
$shaderMake=(Resolve-Path -LiteralPath ([IO.Path]::GetFullPath($ShaderMakeExecutable,$repo))).Path
$source=(Resolve-Path -LiteralPath (Join-Path $repo 'renderer-rtxpt/src/main/cpp')).Path
$manifest=(Resolve-Path -LiteralPath (Join-Path $source 'AdapterShaders.cfg')).Path
$runtime=[IO.Path]::GetFullPath($RuntimeDirectory,$repo)
$output=Join-Path $runtime 'AdapterShaders'
$sdkPrefix=$sdk.TrimEnd([IO.Path]::DirectorySeparatorChar,[IO.Path]::AltDirectorySeparatorChar)+[IO.Path]::DirectorySeparatorChar
if($output.Equals($sdk,[StringComparison]::OrdinalIgnoreCase) -or $output.StartsWith($sdkPrefix,[StringComparison]::OrdinalIgnoreCase)){
    throw 'Adapter shader output must be outside the upstream source tree.'
}
# The ProcessingPasses include root resolves existing ../Shaders includes while
# keeping the adapter source files external to the official tree.
$includes=@($source,(Join-Path $sdk 'Rtxpt/Shaders'),(Join-Path $sdk 'Rtxpt/ProcessingPasses'),(Join-Path $sdk 'Rtxpt'),
    (Join-Path $sdk 'External/Donut/include'),(Join-Path $sdk 'External/Nrd/Shaders'),
    (Join-Path $sdk 'External/Rtxdi/Include'),(Join-Path $sdk 'External'))
foreach($include in $includes){
    if(!(Test-Path -LiteralPath $include -PathType Container)){throw ('Missing shader include directory: '+$include)}
}
if($ShaderName -and $ShaderName -notmatch '^Rtr[A-Za-z0-9]+$'){throw 'ShaderName must be an adapter shader base name.'}
$shaderLines=[ordered]@{}
foreach($line in Get-Content -LiteralPath $manifest -Encoding UTF8){
    if($line -match '^\s*$|^\s*//'){continue}
    if($line -notmatch '^\s*(Rtr[A-Za-z0-9]+)\.hlsl\s+(.+)$'){
        throw ('Unsupported adapter shader config line: '+$line)
    }
    $name=$Matches[1];$options=$Matches[2]
    if(!$shaderLines.Contains($name)){$shaderLines[$name]=[Collections.Generic.List[string]]::new()}
    # Absolute source paths keep the original source/include resolution even
    # though the generated config lives outside the source tree.
    $absoluteShader=(Join-Path $source ($name+'.hlsl')).Replace('\','/')
    $shaderLines[$name].Add('"'+$absoluteShader+'" '+$options)
}
if($shaderLines.Count -eq 0){throw 'The adapter shader config has no shaders.'}
if($ShaderName -and !$shaderLines.Contains($ShaderName)){throw ('Unknown adapter shader: '+$ShaderName)}
$commonArguments=@('--out',$output,'--platform','DXIL',
    '--binaryBlob','--outputExt','.bin','--compiler',$compiler,'--shaderModel','6_9',
    '--hlsl2021','--matrixRowMajor','--jobs',([string]$Jobs),'--retryCount','0',
    '--project','AdapterShaders','-D','TARGET_D3D12','-D','ENABLE_DEBUG_PRINT')
foreach($include in $includes){$commonArguments+=@('-I',$include)}
# ShaderMake keys its signature by config path, invalidates it before compiling,
# and restores it only after success. A shared config therefore recompiles every
# shader after one failure; a random temporary config recompiles on every call.
# Keep one stable config/signature per shader, with ALL its permutations in one
# invocation so binaryBlob aggregation stays authoritative. First migration to
# these config identities needs one compilation; subsequent calls are incremental.
$configDirectory=Join-Path $repo 'build/adapter-shader-configs'
$plans=@(foreach($name in $shaderLines.Keys){
    if($ShaderName -and $name -ne $ShaderName){continue}
    $config=Join-Path $configDirectory ($name+'.cfg')
    [pscustomobject]@{Shader=$name;Manifest=$config;Content=($shaderLines[$name] -join "`n")+"`n";
        Arguments=@('--config',$config)+$commonArguments;Binary=Join-Path $output ('app/ProcessingPasses/'+$name+'.bin')}
})
if($CheckPaths){
    [pscustomobject]@{Upstream=$identity;ShaderMake=$shaderMake;Dxc=$compiler;Manifest=$manifest;Output=$output;Builds=$plans}
    return
}
New-Item -ItemType Directory -Force -Path $output | Out-Null
$expectedBlobs=@{}
foreach($name in $shaderLines.Keys){$expectedBlobs[$name+'.bin']=$true}
$processing=Join-Path $output 'app/ProcessingPasses'
if(Test-Path -LiteralPath $processing){
    foreach($binary in Get-ChildItem -LiteralPath $processing -Filter 'Rtr*.bin' -File){
        if(-not $expectedBlobs.ContainsKey($binary.Name)){Remove-Item -LiteralPath $binary.FullName}
    }
}
New-Item -ItemType Directory -Force -Path $configDirectory | Out-Null
foreach($plan in $plans){
    # Do not touch an unchanged config's timestamp: ShaderMake includes it in
    # dependency freshness alongside shader/includes, compiler and its own EXE.
    if(!(Test-Path -LiteralPath $plan.Manifest -PathType Leaf) -or [IO.File]::ReadAllText($plan.Manifest) -cne $plan.Content){
        [IO.File]::WriteAllText($plan.Manifest,$plan.Content,[Text.UTF8Encoding]::new($false))
    }
    & (Join-Path $PSScriptRoot 'Invoke-BoundedBuild.ps1') -FilePath $shaderMake -WorkingDirectory $source -ArgumentList $plan.Arguments -MemoryLimitGB $MemoryLimitGB
    if($LASTEXITCODE -ne 0){throw ('External adapter shader compilation failed: '+$plan.Shader)}
    if(!(Test-Path -LiteralPath $plan.Binary -PathType Leaf) -or (Get-Item -LiteralPath $plan.Binary).Length -eq 0){
        throw ('ShaderMake did not produce adapter blob: '+$plan.Binary)
    }
}
& (Join-Path $PSScriptRoot 'Assert-RtxptUpstream.ps1') -SdkRoot $SdkRoot | Out-Null
[pscustomobject]@{AdapterShaders=$output;FactoryBase=$output;UpstreamUnmodified=$true}
