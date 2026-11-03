<# Standalone hidden ten-plane host augmentation acceptance; no native rebuild or dependency downloads. #>
param(
    [string]$ClasspathFile='build/710-opengl-test-classpath.txt',
    [string]$RuntimeDirectory='',
    [string]$BridgePath='',
    [string]$OutputDirectory='build/reports/rtxpt-710-host-augmentation',
    [switch]$CompileOnly
)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$classpathPath=[IO.Path]::GetFullPath($ClasspathFile,$repo)
if(!(Test-Path -LiteralPath $classpathPath -PathType Leaf)){throw 'Export the Java test runtime classpath before running host augmentation acceptance'}
$augmentationClasspath=(Get-Content -LiteralPath $classpathPath -Raw -Encoding UTF8).Trim()
if(!$augmentationClasspath){throw 'The exported test classpath is empty'}
$augmentationClasses=Join-Path $repo 'build/host-augmentation-classes'
New-Item -ItemType Directory -Path $augmentationClasses -Force | Out-Null
$augmentationSources=@(
    (Join-Path $repo 'examples/host-surface-opengl/GlHostSurfaceWriter.java'),
    (Join-Path $repo 'renderer-rtxpt/src/test/java/top/ceroxe/rt/renderer/rtxpt/RtxptHostAugmentationNativeSelfTest.java')
)
$augmentationSourcePath=(Join-Path $repo 'renderer-api/src/main/java')+';'+(Join-Path $repo 'renderer-rtxpt/src/main/java')
& javac '-J-Dfile.encoding=UTF-8' -encoding UTF-8 -proc:none -cp $augmentationClasspath -sourcepath $augmentationSourcePath -d $augmentationClasses @augmentationSources
if($LASTEXITCODE -ne 0){throw "Host augmentation test compilation failed ($LASTEXITCODE)"}
if($CompileOnly){Write-Output 'HOST_AUGMENTATION_TEST_COMPILE PASS GPU=notRun';return}
$arguments=@('-Xmx512m','-XX:-UseJVMCICompiler','--enable-native-access=ALL-UNNAMED','-Dfile.encoding=UTF-8',
    ('-Dtop.ceroxe.rt.rtxpt.testOutput='+[IO.Path]::GetFullPath($OutputDirectory,$repo)))
if($RuntimeDirectory){$arguments+=('-Dtop.ceroxe.rt.rtxpt.runtime='+[IO.Path]::GetFullPath($RuntimeDirectory,$repo))}
if($BridgePath){$arguments+=('-Dtop.ceroxe.rt.rtxpt.bridge='+[IO.Path]::GetFullPath($BridgePath,$repo))}
$arguments+=@('-cp',($augmentationClasses+';'+$augmentationClasspath),'top.ceroxe.rt.renderer.rtxpt.RtxptHostAugmentationNativeSelfTest')
$previousBackground=$env:RTR_RTXPT_BACKGROUND_WINDOW
try{
    $env:RTR_RTXPT_BACKGROUND_WINDOW='1'
    & (Join-Path $PSScriptRoot 'Invoke-BoundedBuild.ps1') -FilePath java -ArgumentList $arguments -WorkingDirectory $repo -MemoryLimitGB 10
    if($LASTEXITCODE -ne 0){throw "Host augmentation native acceptance failed ($LASTEXITCODE)"}
}finally{$env:RTR_RTXPT_BACKGROUND_WINDOW=$previousBackground}
