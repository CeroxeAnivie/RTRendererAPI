<# Directed hidden split transport acceptance. Does not rebuild the backend or download dependencies. #>
param(
    [string]$ClasspathFile='build/native-lighting-gl-classpath.txt',
    [ValidateSet('optics','alpha','ownership')][string[]]$Cases=@('optics','alpha','ownership'),
    [string]$RuntimeDirectory='',
    [string]$BridgePath='',
    [string]$OutputDirectory='build/reports/rtxpt-host-transport',
    [switch]$CompileOnly
)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$transportClasspath=[IO.File]::ReadAllText([IO.Path]::GetFullPath($ClasspathFile,$repo)).Trim()
if(!$transportClasspath){throw 'Export the Java test runtime classpath before running host transport acceptance'}
$transportClasses=Join-Path $repo 'build/host-transport-classes'
New-Item -ItemType Directory -Path $transportClasses -Force | Out-Null
$transportSources=@((Join-Path $repo 'examples/host-surface-opengl/GlHostSurfaceWriter.java'),
    (Join-Path $repo 'renderer-rtxpt/src/test/java/top/ceroxe/rt/renderer/rtxpt/RtxptHostSurfaceTransportNativeSelfTest.java'))
$transportSourcePath=(Join-Path $repo 'renderer-api/src/main/java')+';'+(Join-Path $repo 'renderer-rtxpt/src/main/java')
& javac '-J-Dfile.encoding=UTF-8' -encoding UTF-8 -proc:none -cp $transportClasspath -sourcepath $transportSourcePath -d $transportClasses @transportSources
if($LASTEXITCODE -ne 0){throw "Host transport test compilation failed ($LASTEXITCODE)"}
if($CompileOnly){Write-Output 'HOST_TRANSPORT_TEST_COMPILE PASS GPU=notRun';return}
$arguments=@('-Xmx512m','-XX:-UseJVMCICompiler','--enable-native-access=ALL-UNNAMED','-Dfile.encoding=UTF-8',
    ('-Dtop.ceroxe.rt.rtxpt.testOutput='+[IO.Path]::GetFullPath($OutputDirectory,$repo)))
if($RuntimeDirectory){$arguments+=('-Dtop.ceroxe.rt.rtxpt.runtime='+[IO.Path]::GetFullPath($RuntimeDirectory,$repo))}
if($BridgePath){$arguments+=('-Dtop.ceroxe.rt.rtxpt.bridge='+[IO.Path]::GetFullPath($BridgePath,$repo))}
$arguments+=@('-cp',($transportClasses+';'+$transportClasspath),'top.ceroxe.rt.renderer.rtxpt.RtxptHostSurfaceTransportNativeSelfTest')+$Cases
$previousBackground=$env:RTR_RTXPT_BACKGROUND_WINDOW
try{
    $env:RTR_RTXPT_BACKGROUND_WINDOW='1'
    & (Join-Path $PSScriptRoot 'Invoke-BoundedBuild.ps1') -FilePath java -ArgumentList $arguments -WorkingDirectory $repo -MemoryLimitGB 10
    if($LASTEXITCODE -ne 0){throw "Host transport native acceptance failed ($LASTEXITCODE)"}
}finally{$env:RTR_RTXPT_BACKGROUND_WINDOW=$previousBackground}
