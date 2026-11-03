<#
.SYNOPSIS
Compile and run the independent native-lighting numeric matrix using an exported test classpath.
.DESCRIPTION
This entry point does not rebuild the backend or fetch dependencies. GPU runs use a hidden
host and the repository's 10 GiB process-tree bound. Coordinate with other GPU/build jobs.
The classpath may point to development classes or isolated packaged provider JARs.
#>
param(
    [string]$ClasspathFile='build/native-lighting-gl-classpath.txt',
    [ValidateSet('cpu','gi','roles','pose','projection','time')][string[]]$Cases=@('gi','roles','pose','projection','time'),
    [string]$RuntimeDirectory='',
    [string]$BridgePath='',
    [string]$OutputDirectory='build/reports/rtxpt-native-matrix',
    [switch]$CompileOnly
)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$classpathPath=[IO.Path]::GetFullPath($ClasspathFile,$repo)
if(!(Test-Path -LiteralPath $classpathPath -PathType Leaf)){throw 'Export the Java test runtime classpath before running the matrix'}
$matrixClasspath=(Get-Content -LiteralPath $classpathPath -Raw -Encoding UTF8).Trim()
if(!$matrixClasspath){throw 'The exported test classpath is empty'}
$matrixClasses=Join-Path $repo 'build/native-matrix-classes'
$source=Join-Path $repo 'renderer-rtxpt/src/test/java/top/ceroxe/rt/renderer/rtxpt/RtxptNativeLightingMatrixNativeSelfTest.java'
New-Item -ItemType Directory -Path $matrixClasses -Force | Out-Null
& javac '-J-Dfile.encoding=UTF-8' -encoding UTF-8 -proc:none -cp $matrixClasspath -d $matrixClasses $source
if($LASTEXITCODE -ne 0){throw "Native-lighting matrix compilation failed ($LASTEXITCODE)"}
if($CompileOnly){Write-Output 'Native-lighting matrix compilation PASS';return}
$arguments=@('-Xmx512m','-XX:-UseJVMCICompiler','--enable-native-access=ALL-UNNAMED','-Dfile.encoding=UTF-8',
    ('-Dtop.ceroxe.rt.rtxpt.testOutput='+[IO.Path]::GetFullPath($OutputDirectory,$repo)))
if($RuntimeDirectory){$arguments+=('-Dtop.ceroxe.rt.rtxpt.runtime='+[IO.Path]::GetFullPath($RuntimeDirectory,$repo))}
if($BridgePath){$arguments+=('-Dtop.ceroxe.rt.rtxpt.bridge='+[IO.Path]::GetFullPath($BridgePath,$repo))}
$arguments+=@('-cp',($matrixClasses+';'+$matrixClasspath),'top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingMatrixNativeSelfTest')+$Cases
$previousBackground=$env:RTR_RTXPT_BACKGROUND_WINDOW
try{
    $env:RTR_RTXPT_BACKGROUND_WINDOW='1'
    & (Join-Path $PSScriptRoot 'Invoke-BoundedBuild.ps1') -FilePath java -ArgumentList $arguments -WorkingDirectory $repo -MemoryLimitGB 10
    if($LASTEXITCODE -ne 0){throw "Native-lighting matrix failed ($LASTEXITCODE)"}
}finally{$env:RTR_RTXPT_BACKGROUND_WINDOW=$previousBackground}
