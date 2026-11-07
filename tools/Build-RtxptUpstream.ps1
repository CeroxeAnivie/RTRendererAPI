<#
.SYNOPSIS
Build official RTXPT targets without modifying upstream sources.
.DESCRIPTION
This entry point never changes the verified official source export or shaders.
WithBridge selects the sole authorized build-side SampleCommon progress guard;
its exact source, single hunk and hashes are recorded in the native build tree.
Run -CheckPaths for the source identity check without configuring or building.
The JNI adapter is attached separately through CMake's documented project hook.
#>
param(
    [string]$SdkRoot='build/upstream-rtxpt/source',
    [string]$BuildRoot='build/upstream-rtxpt/native',
    [string]$CMakeExecutable='build/native-build-tools/cmake-4.1.2-windows-x86_64/bin/cmake.exe',
    [string[]]$Target=@('RtxptCore'),
    [string]$RuntimeDirectory='build/upstream-rtxpt/runtime/rtxpt_v.1.9.0_binaries/bin',
    [ValidateRange(1,10)][double]$MemoryLimitGB=10,
    [switch]$WithBridge,
    [switch]$ConfigureOnly,
    [switch]$CheckPaths
)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$source=& (Join-Path $PSScriptRoot 'Assert-RtxptUpstream.ps1') -SdkRoot $SdkRoot
$cmake=(Resolve-Path -LiteralPath ([IO.Path]::GetFullPath($CMakeExecutable,$repo))).Path
$build=[IO.Path]::GetFullPath($BuildRoot,$repo)
if($CheckPaths){
    [pscustomobject]@{Source=$source;CMake=$cmake;BuildRoot=$build;Targets=$Target;MemoryLimitGB=$MemoryLimitGB}
    return
}
$configure=@('-S',$source.SdkRoot,'-B',$build,'-G','Visual Studio 17 2022','-A','x64')
$expectedCache=@{
    CMAKE_HOME_DIRECTORY=$source.SdkRoot
    CMAKE_GENERATOR='Visual Studio 17 2022'
    CMAKE_GENERATOR_PLATFORM='x64'
    CMAKE_PROJECT_RTXPathTracing_INCLUDE=''
    RTR_BRIDGE_SOURCE=''
    RTR_RUNTIME_DIRECTORY=''
}
if($WithBridge){
    $hook=Join-Path $PSScriptRoot 'RtxptBridgeProject.cmake'
    $bridge=Join-Path $repo 'renderer-rtxpt/src/main/cpp'
    $runtime=[IO.Path]::GetFullPath($RuntimeDirectory,$repo)
    $configure+=@(('-DCMAKE_PROJECT_RTXPathTracing_INCLUDE='+$hook),
        ('-DRTR_BRIDGE_SOURCE='+$bridge),('-DRTR_RUNTIME_DIRECTORY='+$runtime))
    $expectedCache.CMAKE_PROJECT_RTXPathTracing_INCLUDE=$hook
    $expectedCache.RTR_BRIDGE_SOURCE=$bridge
    $expectedCache.RTR_RUNTIME_DIRECTORY=$runtime
}else{
    # Clear an earlier bridge configuration when this invocation requests core only.
    $configure+=@('-UCMAKE_PROJECT_RTXPathTracing_INCLUDE','-URTR_BRIDGE_SOURCE','-URTR_RUNTIME_DIRECTORY')
}
$bounded=Join-Path $PSScriptRoot 'Invoke-BoundedBuild.ps1'
$cachePath=Join-Path $build 'CMakeCache.txt'
$configurationMatches=Test-Path -LiteralPath $cachePath
if($configurationMatches){
    $cache=@{}
    foreach($line in Get-Content -LiteralPath $cachePath -Encoding UTF8){
        if($line -match '^([^#/:][^:]*):[^=]+=(.*)$'){$cache[$Matches[1]]=$Matches[2]}
    }
    foreach($key in $expectedCache.Keys){
        $actual=if($cache.ContainsKey($key)){[string]$cache[$key]}else{''}
        $expected=[string]$expectedCache[$key]
        if($key -ne 'CMAKE_GENERATOR' -and $key -ne 'CMAKE_GENERATOR_PLATFORM'){
            $actual=$actual.Replace('\','/').TrimEnd('/')
            $expected=$expected.Replace('\','/').TrimEnd('/')
        }
        if(-not [string]::Equals($actual,$expected,[StringComparison]::OrdinalIgnoreCase)){
            $configurationMatches=$false
            break
        }
    }
}
if($ConfigureOnly -or -not $configurationMatches){
    & $bounded -FilePath $cmake -WorkingDirectory $repo -MemoryLimitGB $MemoryLimitGB `
        -ArgumentList $configure
    if($LASTEXITCODE -ne 0){throw 'Official RTXPT configuration failed.'}
}else{
    Write-Host 'Reusing matching upstream CMake configuration; the build system still checks source dependencies.'
}
& (Join-Path $PSScriptRoot 'Assert-RtxptUpstream.ps1') -SdkRoot $SdkRoot | Out-Null
if($ConfigureOnly){return}
$arguments=@('--build',$build,'--config','Release','--parallel','1','--target')+$Target
$oldCompilerOptions=$env:_CL_
try{
    # Toolchain input encoding and concurrency are host build settings, not core changes.
    $env:_CL_=($oldCompilerOptions+' /utf-8 /MP1').Trim()
    & $bounded -FilePath $cmake -WorkingDirectory $repo -MemoryLimitGB $MemoryLimitGB -ArgumentList $arguments
    $buildExit=$LASTEXITCODE
}finally{
    $env:_CL_=$oldCompilerOptions
}
if($buildExit -ne 0){throw 'Official RTXPT target build failed.'}
& (Join-Path $PSScriptRoot 'Assert-RtxptUpstream.ps1') -SdkRoot $SdkRoot
