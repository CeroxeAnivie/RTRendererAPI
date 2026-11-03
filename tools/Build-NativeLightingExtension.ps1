<#
.SYNOPSIS
Compile a native-lighting receiver extension offline and publish a versioned DXIL bundle.
.DESCRIPTION
Relative paths resolve from the current directory. Use -CheckPaths without compiling, or
-VerifyOnly to verify a published bundle. DXC runs in a bounded 10 GiB process tree.
The output includes a portable shader SDK; -SdkDirectory can select that SDK later.
#>
param(
    [string]$ExtensionSource='',
    [string]$OutputDirectory='build/native-lighting-extension',
    [string]$RuntimeDirectory='',
    [string]$SdkDirectory='',
    [string]$Name='application-lighting',
    [string]$ApplicationSchema='application/v1',
    [ValidateRange(0,16777216)][int]$MinimumApplicationBytes=0,
    [ValidateRange(1,15)][int]$SignalsMask=3,
    [switch]$CheckPaths,
    [switch]$VerifyOnly
)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$utf8=[Text.UTF8Encoding]::new($false)
$toolRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$invocationRoot=(Get-Location).ProviderPath
function Full-Path([string]$value){[IO.Path]::GetFullPath($value,$invocationRoot)}
function Hash-File([string]$value){(Get-FileHash -LiteralPath $value -Algorithm SHA256).Hash.ToLowerInvariant()}
function Hash-Text([string]$value){[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($utf8.GetBytes($value))).ToLowerInvariant()}
function Inside([string]$value,[string]$root){$value.StartsWith($root.TrimEnd('\','/')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)}
function Abi-Hash($entries){
    $ordered=[string[]]@($entries | ForEach-Object {"$($_.path)=$($_.sha256)`n"})
    [Array]::Sort($ordered,[StringComparer]::Ordinal)
    Hash-Text ([string]::Concat($ordered))
}
function Check-Dxil([string]$path){
    $bytes=[IO.File]::ReadAllBytes($path)
    if($bytes.Length -lt 36 -or [Text.Encoding]::ASCII.GetString($bytes,0,4) -cne 'DXBC' -or
        [BitConverter]::ToUInt32($bytes,24) -ne $bytes.Length){throw 'Invalid DXIL container header'}
    $count=[BitConverter]::ToUInt32($bytes,28)
    if($count -lt 1 -or $count -gt 4096 -or 32L+4L*$count -gt $bytes.Length){throw 'Invalid DXIL part table'}
    $found=$false
    for($i=0;$i -lt $count;$i++){
        $offset=[BitConverter]::ToUInt32($bytes,32+4*$i)
        if($offset -lt 32+4*$count -or [long]$offset+8 -gt $bytes.Length){throw 'Invalid DXIL part offset'}
        $size=[BitConverter]::ToUInt32($bytes,[int]$offset+4)
        if([long]$offset+8+$size -gt $bytes.Length){throw 'Invalid DXIL part size'}
        if([Text.Encoding]::ASCII.GetString($bytes,[int]$offset,4) -ceq 'DXIL'){$found=$true}
    }
    if(!$found){throw 'Container has no DXIL shader part'}
}
function Bundle-Path([string]$root,[string]$relative){
    if([IO.Path]::IsPathRooted($relative)){throw 'Manifest paths must be relative'}
    $path=[IO.Path]::GetFullPath((Join-Path $root $relative))
    if(!(Inside $path $root)){throw 'Manifest path escapes its bundle'}
    $path
}
$output=Full-Path $OutputDirectory
if($VerifyOnly){
    $manifest=Get-Content -LiteralPath (Join-Path $output 'manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    if($manifest.format -cne 'rtr-native-lighting-extension' -or $manifest.formatVersion -ne 1 -or
        $manifest.abiVersion -ne 1 -or $manifest.entryPoint -cne 'main' -or $manifest.shaderModel -cne 'cs_6_6' -or
        $manifest.constantsBytes -ne 320 -or ($manifest.threadGroup -join ',') -cne '8,8,1'){
        throw 'Unsupported native-lighting extension manifest ABI'
    }
    $binary=Bundle-Path $output $manifest.dxil.file
    if((Get-Item -LiteralPath $binary).Length -ne $manifest.dxil.bytes -or (Hash-File $binary) -cne $manifest.dxil.sha256){throw 'DXIL length or SHA-256 mismatch'}
    Check-Dxil $binary
    foreach($source in @($manifest.abiSources)+@($manifest.extensionSources)){
        $file=Bundle-Path $output $source.file
        if((Hash-File $file) -cne $source.sha256){throw "Source SHA-256 mismatch: $($source.file)"}
    }
    if((Abi-Hash $manifest.abiSources) -cne $manifest.shaderAbiSha256){throw 'Shader SDK ABI SHA-256 mismatch'}
    Write-Host "Native-lighting extension verified: $($manifest.name) $($manifest.dxil.sha256)"
    return
}
if(!$ExtensionSource){throw 'Supply -ExtensionSource, or use -VerifyOnly'}
if($Name -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$'){throw 'Name requires 1..128 ASCII letters, digits, dots, underscores or hyphens'}
if(!$ApplicationSchema -or $ApplicationSchema.Length -gt 256 -or ($MinimumApplicationBytes%4) -ne 0){throw 'Application schema must be nonempty; minimum bytes must be 4-byte aligned'}
$extension=Full-Path $ExtensionSource
if(!(Test-Path -LiteralPath $extension -PathType Leaf)){throw 'Extension source file does not exist'}
$extensionRoot=Split-Path -Parent $extension
if(!$RuntimeDirectory){
    foreach($candidate in @((Join-Path $toolRoot 'build/rtxpt-sdk/bin'),$toolRoot)){
        if(Test-Path -LiteralPath (Join-Path $candidate 'ShaderDynamic/Tools/d3d12/x64/dxc.exe')){$RuntimeDirectory=$candidate;break}
    }
}
if(!$RuntimeDirectory){throw 'Supply -RuntimeDirectory pointing to the extracted RTXPT runtime'}
$runtime=Full-Path $RuntimeDirectory
$compiler=Join-Path $runtime 'ShaderDynamic/Tools/d3d12/x64/dxc.exe'
$runner=Join-Path $PSScriptRoot 'Invoke-BoundedBuild.ps1'
if(!(Test-Path -LiteralPath $compiler -PathType Leaf)){throw 'Pinned runtime DXC is missing'}
if(!(Test-Path -LiteralPath $runner -PathType Leaf)){throw 'Invoke-BoundedBuild.ps1 is missing beside this tool'}
if($SdkDirectory){
    $sdk=Full-Path $SdkDirectory
    $backend=Join-Path $sdk 'backend';$includeRoot=$sdk
}else{
    $backend=Join-Path $toolRoot 'renderer-rtxpt/src/main/cpp'
    $includeRoot=Join-Path $toolRoot 'build/rtxpt-sdk/External/Donut/include'
    if(!(Test-Path -LiteralPath (Join-Path $backend 'RtrNativeLighting.hlsl'))){
        $backend=Join-Path $runtime 'ShaderDynamic/Source/Rtxpt/ProcessingPasses'
        $includeRoot=Join-Path $runtime 'ShaderDynamic/Source/External'
    }
}
$main=Join-Path $backend 'RtrNativeLighting.hlsl'
if(!(Test-Path -LiteralPath $main) -or !(Test-Path -LiteralPath (Join-Path $includeRoot 'donut/shaders/bindless.h'))){throw 'Public native-lighting shader SDK is missing; supply -SdkDirectory'}
$inventory=[Collections.Generic.Dictionary[string,object]]::new([StringComparer]::OrdinalIgnoreCase)
function Inventory([string]$path,[string]$role){
    $path=[IO.Path]::GetFullPath($path)
    if($inventory.ContainsKey($path)){return}
    if(Inside $path $backend){$logical='backend/'+[IO.Path]::GetRelativePath($backend,$path).Replace('\','/');$role='abi'}
    elseif(Inside $path $includeRoot){$logical=[IO.Path]::GetRelativePath($includeRoot,$path).Replace('\','/');$role='abi'}
    elseif(Inside $path $extensionRoot){$logical=[IO.Path]::GetRelativePath($extensionRoot,$path).Replace('\','/');$role='extension'}
    else{throw "Include is outside the extension source tree and public SDK: $path"}
    $bytes=[IO.File]::ReadAllBytes($path)
    $inventory.Add($path,[pscustomobject]@{path=$logical;role=$role;bytes=$bytes;sha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()})
    $text=$utf8.GetString($bytes)
    foreach($match in [regex]::Matches($text,'(?m)^\s*#\s*include\s*(?:"([^"\r\n]+)"|<([^>\r\n]+)>|([^\s/]+))')){
        if($match.Groups[3].Success){
            if($role -ceq 'abi' -and $match.Groups[3].Value -ceq 'RTR_LIGHTING_EXTENSION'){continue}
            throw "Only literal includes are supported in extensions: $path"
        }
        $name=if($match.Groups[1].Success){$match.Groups[1].Value}else{$match.Groups[2].Value}
        $resolved=$null
        foreach($root in @((Split-Path -Parent $path),$extensionRoot,$includeRoot,$backend)){
            $candidate=[IO.Path]::GetFullPath((Join-Path $root $name))
            if(Test-Path -LiteralPath $candidate -PathType Leaf){$resolved=$candidate;break}
        }
        if(!$resolved){throw "Unresolved include '$name' from $path"}
        Inventory $resolved $role
    }
}
Inventory $main 'abi';Inventory $extension 'extension'
$abi=@($inventory.Values | Where-Object role -CEQ 'abi' | Sort-Object path | ForEach-Object {[ordered]@{path=$_.path;file='sdk/'+$_.path;sha256=$_.sha256}})
$sources=@($inventory.Values | Where-Object role -CEQ 'extension' | Sort-Object path | ForEach-Object {[ordered]@{path=$_.path;file='source/'+$_.path;sha256=$_.sha256}})
$abiHash=Abi-Hash $abi
if($CheckPaths){
    [pscustomobject]@{Extension=$extension;Runtime=$runtime;Backend=$backend;IncludeRoot=$includeRoot;Output=$output;ShaderAbiSha256=$abiHash;AbiFiles=$abi.Count;ExtensionFiles=$sources.Count}
    return
}
[IO.Directory]::CreateDirectory($output)|Out-Null
$staging=Join-Path $output ('.prepare-'+[Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($staging)|Out-Null
try{
    foreach($entry in $inventory.Values){
        $relative=if($entry.role -ceq 'abi'){'sdk/'+$entry.path}else{'extension/'+$entry.path}
        $destination=Join-Path $staging $relative
        [IO.Directory]::CreateDirectory((Split-Path -Parent $destination))|Out-Null
        [IO.File]::WriteAllBytes($destination,$entry.bytes)
    }
    $extensionRelative=[IO.Path]::GetRelativePath($extensionRoot,$extension).Replace('\','/')
    $wrapper="#define RTR_LIGHTING_EXTENSION `"extension/$extensionRelative`"`n#include `"sdk/backend/RtrNativeLighting.hlsl`"`n"
    [IO.File]::WriteAllText((Join-Path $staging 'build.hlsl'),$wrapper,$utf8)
    $arguments=@('-T','cs_6_6','-E','main','-HV','2021','-Zpr','-O3','-Ges','-WX','-Qstrip_debug','-I','.','-I','sdk','-Fo','lighting.dxil','-Fc','reflection.txt','build.hlsl')
    & $runner -FilePath $compiler -ArgumentList $arguments -WorkingDirectory $staging -MemoryLimitGB 10
    if($LASTEXITCODE -ne 0){throw "Offline native-lighting DXC failed: $LASTEXITCODE"}
    $binary=Join-Path $staging 'lighting.dxil';Check-Dxil $binary
    $reflection=[IO.File]::ReadAllText((Join-Path $staging 'reflection.txt'))
    if($reflection -notmatch 'NumThreads=\(8,8,1\)'){throw 'Extension changed the required 8x8x1 entry point'}
    if($reflection -notmatch 'EntryFunctionName:\s+main\s' -or $reflection -notmatch '\}\s+g;[^\r\n]*Size:\s+320\s'){
        throw 'Extension changed the required entry point or 320-byte constant buffer'
    }
    $expected=@{
        'cb0:0'='g|cbuffer|NA|NA';'s0:0'='s_Material|sampler|NA|NA';'t0:0'='t_Scene|texture|i32|ras'
        't1:0'='t_Instances|texture|struct|r/o';'t2:0'='t_Geometry|texture|struct|r/o';'t3:0'='t_Materials|texture|struct|r/o'
        't4:0'='t_Sources|texture|struct|r/o';'t5:0'='t_SourceTree|texture|struct|r/o';'t6:0'='t_InstanceAttributes|texture|struct|r/o'
        't7:0'='t_Baselines|texture|struct|r/o';'t8:0'='t_GeometryBaselines|texture|struct|r/o';'t9:0'='t_Application|texture|byte|r/o'
        't10:0'='t_BaselineLookup|texture|struct|r/o';'t11:0'='t_Analytic|texture|struct|r/o'
        't0:1'='t_Buffers|texture|byte|r/o';'t0:2'='t_Textures|texture|f32|2d'
        'u0:0'='u_Direct|UAV|f32|2d';'u1:0'='u_Indirect|UAV|f32|2d';'u2:0'='u_Geometry|UAV|f32|2d'
        'u3:0'='u_Identity|UAV|u32|2d';'u4:0'='u_Work|UAV|byte|r/w'
    }
    $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $bindings=@()
    foreach($match in [regex]::Matches($reflection,'(?m)^;\s+(\w+)\s+(\S+)\s+(\S+)\s+(\S+)\s+\S+\s+(cb\d+|[tsu]\d+)(?:,space(\d+))?\s*(unbounded|\d+)\s*$')){
        $binding=$match.Groups[5].Value;$space=if($match.Groups[6].Success){[int]$match.Groups[6].Value}else{0};$count=$match.Groups[7].Value
        $key="$($binding):$space";$shape=($match.Groups[1..4].Value -join '|')
        $requiredCount=if($space -eq 0){'1'}else{'unbounded'}
        if(!$expected.ContainsKey($key) -or $shape -cne $expected[$key] -or $count -cne $requiredCount -or !$seen.Add($key)){
            throw "Extension declared an incompatible resource: $shape $binding space$space count=$count"
        }
        $bindings+=[ordered]@{name=$match.Groups[1].Value;type=$match.Groups[2].Value;format=$match.Groups[3].Value;dimension=$match.Groups[4].Value;register=$binding;space=$space;count=$count}
    }
    if($bindings.Count -eq 0){throw 'No DXIL resource reflection found'}
    $binaryHash=Hash-File $binary;$binaryName="lighting.$binaryHash.dxil"
    $manifest=[ordered]@{
        format='rtr-native-lighting-extension';formatVersion=1;abiVersion=1;name=$Name
        entryPoint='main';shaderModel='cs_6_6';threadGroup=@(8,8,1);constantsBytes=320;signalsMask=$SignalsMask
        application=[ordered]@{minimumBytes=$MinimumApplicationBytes;multipleOf=4;maximumBytes=16777216;schema=$ApplicationSchema}
        dxil=[ordered]@{file=$binaryName;bytes=(Get-Item -LiteralPath $binary).Length;sha256=$binaryHash}
        shaderAbiSha256=$abiHash;compilerSha256=(Hash-File $compiler)
        abiSources=$abi;extensionSources=$sources;resources=$bindings
        compile=[ordered]@{arguments=@('-T','cs_6_6','-E','main','-HV','2021','-Zpr','-O3','-Ges','-WX','-Qstrip_debug');matrixPacking='row-major'}
    }
    [IO.File]::WriteAllText((Join-Path $staging 'manifest.json'),($manifest | ConvertTo-Json -Depth 12)+"`n",$utf8)
    Move-Item -LiteralPath $binary -Destination (Join-Path $output $binaryName) -Force
    foreach($entry in $inventory.Values){
        $relative=if($entry.role -ceq 'abi'){'sdk/'+$entry.path}else{'source/'+$entry.path}
        $destination=Join-Path $output $relative;[IO.Directory]::CreateDirectory((Split-Path -Parent $destination))|Out-Null
        [IO.File]::WriteAllBytes($destination,$entry.bytes)
    }
    [IO.File]::WriteAllText((Join-Path $output 'reflection.txt'),$reflection,$utf8)
    Move-Item -LiteralPath (Join-Path $staging 'manifest.json') -Destination (Join-Path $output 'manifest.json') -Force
    Write-Host "Native-lighting extension built: $(Join-Path $output 'manifest.json')"
    Write-Host "ABI=$abiHash DXIL=$binaryHash resources=$($bindings.Count)"
}finally{
    # Remove only the unique staging directory that this invocation created.
    $resolved=[IO.Path]::GetFullPath($staging)
    if(!(Inside $resolved $output) -or !(Split-Path -Leaf $resolved).StartsWith('.prepare-')){throw 'Unexpected extension staging path'}
    if(Test-Path -LiteralPath $resolved){Remove-Item -LiteralPath $resolved -Recurse -Force}
}
