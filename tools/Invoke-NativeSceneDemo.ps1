<#
.SYNOPSIS
Start one API consumer demo under the process-tree memory limit.
.DESCRIPTION
Keeps Java demo defaults, including preferred 2x FG. Never starts the next scene.
CheckPaths only validates prepared inputs and does not launch a process.
#>
param(
    [ValidateSet('balls','mixed','glass','nested','absorption','rough','ior')][string]$Scene='balls',
    [switch]$Hidden,
    [switch]$CheckPaths
)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$native=Join-Path $repo 'build/upstream-rtxpt/runtime/rtxpt_v.1.9.0_binaries/bin'
$hostExe=Join-Path $native 'rtrenderer_jvm_host.exe'
$classpathFile=Join-Path $repo 'build/renderer-validation-classpath.txt'
$java=(Get-Command java.exe -CommandType Application -ErrorAction Stop).Source
$jvm=Join-Path (Split-Path (Split-Path $java)) 'bin/server/jvm.dll'
foreach($inputPath in @($hostExe,(Join-Path $native 'rtrenderer_rtxpt.dll'),$classpathFile,$jvm)){
    if(!(Test-Path -LiteralPath $inputPath -PathType Leaf)){throw ('Missing prepared demo input: '+$inputPath)}
}
$classpath=(Get-Content -LiteralPath $classpathFile -Raw -Encoding UTF8).Trim()
# Java permits absent resource output directories for NO-SOURCE source sets.
# Dependency archives must exist; required demo classes are checked separately.
foreach($entry in $classpath.Split([IO.Path]::PathSeparator)){
    if([IO.Path]::GetExtension($entry) -eq '.jar' -and !(Test-Path -LiteralPath $entry -PathType Leaf)){
        throw ('Missing classpath archive: '+$entry)
    }
}
$main='top.ceroxe.rt.renderer.rtxpt.examples.NativeSceneDemo'
$demoClassRelativePath='top/ceroxe/rt/renderer/rtxpt/examples/NativeSceneDemo.class'
if(!($classpath.Split([IO.Path]::PathSeparator)|Where-Object {Test-Path -LiteralPath (Join-Path $_ $demoClassRelativePath)})){
    throw 'Demo classes are not prepared; run :renderer-rtxpt:rtxptReleaseValidationClasspath first'
}
$application=@('--scene',$Scene,'--hidden',([bool]$Hidden).ToString().ToLowerInvariant())
if($CheckPaths){
    [pscustomobject]@{Scene=$Scene;Executable=$hostExe;Main=$main;Hidden=[bool]$Hidden;FrameGeneration='demo default: preferred 2x';AutomaticNextScene=$false;LaunchPerformed=$false}
    return
}
$variables=@{RTR_JVM_DLL=$jvm;RTR_JVM_CLASSPATH=$classpath;RTR_JVM_MAIN=$main;RTR_JVM_ARG_COUNT=[string]$application.Count}
$options=@('-Xmx512m','-XX:-UseJVMCICompiler','--enable-native-access=ALL-UNNAMED','-Dfile.encoding=UTF-8',('-Dtop.ceroxe.rt.rtxpt.runtime='+$native))
$variables.RTR_JVM_OPTION_COUNT=[string]$options.Count
for($i=0;$i -lt $application.Count;$i++){$variables['RTR_JVM_ARG_'+$i]=$application[$i]}
for($i=0;$i -lt $options.Count;$i++){$variables['RTR_JVM_OPTION_'+$i]=$options[$i]}
$previous=@{}
try{
    foreach($key in $variables.Keys){$previous[$key]=[Environment]::GetEnvironmentVariable($key,'Process');[Environment]::SetEnvironmentVariable($key,$variables[$key],'Process')}
    & (Join-Path $PSScriptRoot 'Invoke-BoundedBuild.ps1') -FilePath $hostExe -WorkingDirectory $repo -MemoryLimitGB 10
    $code=$LASTEXITCODE
}finally{
    foreach($key in $previous.Keys){[Environment]::SetEnvironmentVariable($key,$previous[$key],'Process')}
}
exit $code
