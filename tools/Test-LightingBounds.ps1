<# Capture real raw/RR input images; optionally compare with a preserved native runtime. Run without concurrent renderer/build processes. #>
param(
    [string]$BaselineArchive='',
    [string]$RuntimeDirectory='build/rtxpt-sdk/bin',
    [string]$OutputDirectory='build/reports/lighting-bound-correctness',
    [string]$ClassPath='',
    [string]$Java='java.exe',[string]$JavaCompiler='javac.exe'
)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$utf8=[Text.UTF8Encoding]::new($false)
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runtime=[IO.Path]::GetFullPath($RuntimeDirectory,$repo)
$out=[IO.Path]::GetFullPath($OutputDirectory,$repo)
$archive=if($BaselineArchive){[IO.Path]::GetFullPath($BaselineArchive,$repo)}else{''}
if(!$ClassPath){$ClassPath=(Join-Path $repo 'renderer-api/build/classes/java/main')+';'+([IO.File]::ReadAllText((Join-Path $repo 'build/renderer-validation-classpath.txt'))).Trim()}
$classes=Join-Path $repo 'build/lighting-bound-test-classes'
[IO.Directory]::CreateDirectory($classes)|Out-Null
[IO.Directory]::CreateDirectory($out)|Out-Null
$runner=Join-Path $PSScriptRoot 'Invoke-BoundedBuild.ps1'
$source=Join-Path $repo 'renderer-rtxpt/src/test/java/top/ceroxe/rt/renderer/rtxpt/RtxptLightingBoundNativeSelfTest.java'
& $runner -FilePath $JavaCompiler -ArgumentList @('-encoding','UTF-8','-cp',$ClassPath,'-d',$classes,$source) -MemoryLimitGB 10
if($LASTEXITCODE){throw 'Lighting bounds test compilation failed'}
$files=@('rtrenderer_rtxpt.dll','ShaderPrecompiled/Rtxpt/dxil/ProcessingPasses/RtrNativeLighting.bin',
    'ShaderPrecompiled/Rtxpt/dxil/ProcessingPasses/RtrNativeLightingDeep.bin',
    'ShaderPrecompiled/Rtxpt/dxil/ProcessingPasses/RtrNativeLightingReconstruct.bin')
$backup=Join-Path $repo ('build/lighting-bound-runtime-'+[Guid]::NewGuid().ToString('N'))
if($archive){
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip=[IO.Compression.ZipFile]::OpenRead($archive)
    try{
        foreach($file in $files){
            $saved=Join-Path $backup ('candidate/'+$file);$old=Join-Path $backup ('baseline/'+$file)
            [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($saved))|Out-Null
            [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($old))|Out-Null
            Copy-Item -LiteralPath (Join-Path $runtime $file) -Destination $saved
            $entry=$zip.GetEntry($file);if(!$entry){throw "Missing baseline runtime entry: $file"}
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$old,$true)
        }
    }finally{$zip.Dispose()}
}
$variables=@('RTR_RTXPT_BACKGROUND_WINDOW','RTR_RTXPT_REQUIRE_PRECOMPILED');$savedEnvironment=@{}
foreach($name in $variables){$savedEnvironment[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try{
    foreach($name in $variables){[Environment]::SetEnvironmentVariable($name,'1','Process')}
    $phases=if($archive){@('baseline','candidate')}else{@('candidate')}
    foreach($phase in $phases){
        if($archive){foreach($file in $files){Copy-Item -LiteralPath (Join-Path $backup ($phase+'/'+$file)) -Destination (Join-Path $runtime $file)}}
        $directory=Join-Path $out $phase
        & $runner -FilePath $Java -ArgumentList @('-Xmx768m','-XX:-UseJVMCICompiler','--enable-native-access=ALL-UNNAMED',
            '-Dfile.encoding=UTF-8',('-Dtop.ceroxe.rt.rtxpt.runtime='+$runtime),'-cp',($classes+';'+$ClassPath),
            'top.ceroxe.rt.renderer.rtxpt.RtxptLightingBoundNativeSelfTest',$directory) -MemoryLimitGB 10
        if($LASTEXITCODE){throw "Lighting bounds $phase failed"}
    }
}finally{
    if($archive){foreach($file in $files){Copy-Item -LiteralPath (Join-Path $backup ('candidate/'+$file)) -Destination (Join-Path $runtime $file)}}
    foreach($name in $variables){[Environment]::SetEnvironmentVariable($name,$savedEnvironment[$name],'Process')}
}
if($archive){
    $differences=@();$images=@(Get-ChildItem -LiteralPath (Join-Path $out 'baseline') -Filter '*.pfm')
    if($images.Count -ne 432 -or @(Get-ChildItem -LiteralPath (Join-Path $out 'candidate') -Filter '*.pfm').Count -ne $images.Count){throw 'Incomplete bounds image corpus'}
    foreach($file in $images){
        if((Get-FileHash -LiteralPath $file.FullName).Hash -cne (Get-FileHash -LiteralPath (Join-Path $out ('candidate/'+$file.Name))).Hash){$differences+=$file.Name}
    }
    $result=[ordered]@{passed=($differences.Count -eq 0);images=$images.Count;bitExact=($differences.Count -eq 0);differences=$differences}
    [IO.File]::WriteAllText((Join-Path $out 'comparison.json'),($result|ConvertTo-Json -Depth 4)+"`n",$utf8)
    if($differences.Count){throw "Cross-runtime lighting images changed: $($differences -join ', ')"}
    $result|ConvertTo-Json -Depth 4
}
