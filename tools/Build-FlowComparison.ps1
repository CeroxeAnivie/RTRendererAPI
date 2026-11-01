param([string]$FlowRoot='build/flow-sdk/flow',[double]$MemoryLimitGB=10.5)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$repo=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$flow=(Resolve-Path (Join-Path $repo $FlowRoot)).Path
$vswhere=Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
$vs=(& $vswhere -latest -property installationPath).Trim()
$toolVersion=(Get-Content (Join-Path $vs 'VC/Auxiliary/Build/Microsoft.VCToolsVersion.default.txt') -Raw).Trim()
$env:MSVS_TOOLSET=Join-Path $vs "VC/Tools/MSVC/$toolVersion"
$env:MSBUILD_DIR=Join-Path $vs 'MSBuild/Current/Bin'
$env:CL=($env:CL+' /wd4756').Trim() # Upstream intentionally uses infinity constants.
# Only upstream's build utility is changed: cap shader compiler parallelism.
# No simulation, rendering, shader or sampling implementation is modified.
$processFile=Join-Path $flow 'source/nvflowshadertool/NvFlowProcess.cpp'
$processText=[IO.File]::ReadAllText($processFile)
$serialText=$processText.Replace('static const int numProcesses = 16u;','static const int numProcesses = 1u;').Replace('static const int numProcesses = 2u;','static const int numProcesses = 1u;')
if($serialText -ne $processText){[IO.File]::WriteAllText($processFile,$serialText,[Text.UTF8Encoding]::new($false))}
# Restore the upstream compiler backend after the temporary 3 GB experiment.
$slangFile=Join-Path $flow 'source/nvflowshadercompiler/SlangCompiler.cpp'
$slangText=[IO.File]::ReadAllText($slangFile)
if($slangText.Contains('additionalArgs.push_back("-emit-spirv-via-glsl")')){
    $slangText=$slangText.Replace("`n        if (useVulkan) additionalArgs.push_back(`"-emit-spirv-via-glsl`");",'')
    [IO.File]::WriteAllText($slangFile,$slangText,[Text.UTF8Encoding]::new($false))
}
# The upstream shader tool compares .hlsl input text, not output-header existence.
# Invalidate only incomplete outputs left by an interrupted compiler invocation.
$generated=Join-Path $flow '_generated/windows-x86_64/release'
if(Test-Path -LiteralPath $generated){
    Get-ChildItem -LiteralPath $generated -Recurse -File -Filter '*_vulkan.hlsl.h' | ForEach-Object {
        if($_.Length -lt 128 -or ([IO.File]::ReadAllText($_.FullName)) -match '\[\s*0[uUlL]*\s*\]') {
            $inputPath=$_.FullName.Replace('_vulkan.hlsl.h','.hlsl')
            if(Test-Path -LiteralPath $inputPath){Remove-Item -LiteralPath $inputPath}
        }
    }
    Get-ChildItem -LiteralPath $generated -Recurse -File -Filter '*.hlsl' | ForEach-Object {
        if(!(Test-Path -LiteralPath ($_.FullName+'.h'))){Remove-Item -LiteralPath $_.FullName}
    }
}
$benchmark=(Join-Path $PSScriptRoot 'flow_native_benchmark.cpp').Replace('\','/')
$lua=@"
include("premake5.lua")
project "flow_native_benchmark"
    kind "ConsoleApp"
    dependson { "nvflowext" }
    location("_compiler/" .. (_ACTION or "build") .. "/%{prj.name}")
    language "C++"
    cppdialect "C++17"
    exceptionhandling "On"
    includedirs { "include/nvflowext" }
    files { "$benchmark" }
    defines { "NOMINMAX", "_CRT_SECURE_NO_WARNINGS" }
    disablewarnings { "4127", "4267", "4244", "4996" }
"@
[IO.File]::WriteAllText((Join-Path $flow '_flow_benchmark.lua'),$lua,[Text.UTF8Encoding]::new($false))
Push-Location $flow
try {
    & ./external/premake/windows-x86_64/premake5.exe --file=_flow_benchmark.lua vs2022
    if($LASTEXITCODE -ne 0){throw 'Flow project generation failed'}
} finally {Pop-Location}
& (Join-Path $PSScriptRoot 'Invoke-BoundedBuild.ps1') -FilePath (Join-Path $env:MSBUILD_DIR 'MSBuild.exe') -ArgumentList @(
    (Join-Path $flow '_compiler/vs2022/nvflow.sln'),'/t:flow_native_benchmark',
    '/p:Configuration=release','/p:Platform=x64','/p:BuildInParallel=false','/m:1','/nologo','/v:minimal'
) -WorkingDirectory $flow -MemoryLimitGB $MemoryLimitGB
