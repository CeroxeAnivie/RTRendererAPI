<# Execute the portable lighting fixture on real retained Vulkan and native backends. #>
param(
    [Parameter(Mandatory=$true)][string]$LegacyCoreJar,
    [string]$ClassPath='',
    [string]$CandidateDirectory='build/reports/lighting-backends/native',
    [string]$BaselineDirectory='build/reports/lighting-backends/vulkan',
    [string]$RuntimeDirectory='build/rtxpt-sdk/bin',
    [string]$ComparisonRoundId='',
    [int]$Width=1280,[int]$Height=800,[int]$Frames=120,[int]$Sources=64,
    [string]$Java='java.exe',[string]$JavaCompiler='javac.exe',
    [switch]$Packaged,[switch]$CompileOnly,[switch]$LegacyOnly,[switch]$CompareOnly,[switch]$GeneralGroups
)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$utf8=[Text.UTF8Encoding]::new($false)
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$legacyJar=[IO.Path]::GetFullPath($LegacyCoreJar,$repo)
if(!(Test-Path -LiteralPath $legacyJar -PathType Leaf)){throw "Published legacy core JAR is missing: $legacyJar"}
if(!$ClassPath){$ClassPath=([IO.File]::ReadAllText((Join-Path $repo 'build/renderer-validation-classpath.txt'))).Trim()}
if(!$Packaged){$ClassPath=(Join-Path $repo 'renderer-api/build/classes/java/main')+[IO.Path]::PathSeparator+$ClassPath}
$candidate=[IO.Path]::GetFullPath($CandidateDirectory,$repo)
$baseline=[IO.Path]::GetFullPath($BaselineDirectory,$repo)
if($CompareOnly){& (Join-Path $PSScriptRoot 'Compare-NativeLightingCost.ps1') -CandidateDirectory $candidate -BaselineDirectory $baseline -RequireComparable;return}
if(!$ComparisonRoundId){$ComparisonRoundId=[Guid]::NewGuid().ToString('N')}
$classes=Join-Path $repo 'build/legacy-lighting-classes'
[IO.Directory]::CreateDirectory($classes)|Out-Null
[IO.Directory]::CreateDirectory($baseline)|Out-Null
$runner=Join-Path $PSScriptRoot 'Invoke-BoundedBuild.ps1'
$compileSources=@('renderer-rtxpt/src/test/java/top/ceroxe/rt/renderer/rtxpt/RtxptNativeLightingCostBenchmark.java',
    'renderer-rtxpt/src/test/java/top/ceroxe/rt/renderer/backend/vulkan/VulkanNativeLightingCostBenchmark.java')|ForEach-Object {Join-Path $repo $_}
& $runner -FilePath $JavaCompiler -ArgumentList (@('-encoding','UTF-8','-cp',$ClassPath,'-d',$classes)+$compileSources) -MemoryLimitGB 10
if($LASTEXITCODE){throw 'Lighting backend benchmark compilation failed'}
$legacyEntries=@($ClassPath.Split([IO.Path]::PathSeparator)|Where-Object {
    $_ -notmatch '[/\\]renderer-core[/\\]build[/\\](classes|resources)[/\\]' -and $_ -notmatch '[/\\]renderer-core-[^/\\]+\.jar$'
})
$legacyClassPath=(@($classes,$legacyJar)+$legacyEntries)-join [IO.Path]::PathSeparator
$arguments=@('-Xmx512m','-XX:-UseJVMCICompiler','--enable-native-access=ALL-UNNAMED','-Dfile.encoding=UTF-8','-cp',$legacyClassPath,
    'top.ceroxe.rt.renderer.backend.vulkan.VulkanNativeLightingCostBenchmark',$baseline,(Join-Path $repo 'tools/shaders/native-lighting-reference.comp'),"$Width","$Height","$Frames","$Sources")
if($GeneralGroups){$arguments+='--general-groups'}
if($CompileOnly){$arguments+='--compile-only'}else{
    # Capture independently on each backend. Never copy hardware/idle evidence
    # from the candidate or manufacture a matched comparison condition.
    $samples=@();$hardware=$null;$smi=Get-Command nvidia-smi.exe -ErrorAction SilentlyContinue
    if($smi){for($i=0;$i -lt 3;$i++){
        $rows=@(& $smi.Source '--query-gpu=uuid,name,driver_version,pstate,power.limit,utilization.gpu,clocks.gr,clocks.mem,power.draw' '--format=csv,noheader,nounits' 2>$null)
        if($LASTEXITCODE -eq 0 -and $rows.Count -eq 1){$fields=$rows[0].Split(',').Trim();$usage=0.0
            if($fields.Count -eq 9 -and [double]::TryParse($fields[5],[Globalization.NumberStyles]::Float,[Globalization.CultureInfo]::InvariantCulture,[ref]$usage)){
                $hardware=[ordered]@{uuid=$fields[0];name=$fields[1];driver=$fields[2];pstate=$fields[3];powerLimitWatts=$fields[4];graphicsClockMHz=$fields[6];memoryClockMHz=$fields[7];powerDrawWatts=$fields[8]};$samples+=$usage}}
        if($i -lt 2){[Threading.Thread]::Sleep(200)}
    }}
    $environment=[ordered]@{format='rtr-e12-environment';formatVersion=1;comparisonRoundId=$ComparisonRoundId;timestampUtc=[DateTime]::UtcNow.ToString('o');hardware=$hardware;
        gpuBaselinePercent=$samples;baselineKnown=($samples.Count -eq 3);baselineMaximumPercent=if($samples.Count -eq 3){($samples|Measure-Object -Maximum).Maximum}else{$null};
        effectiveFrameCap='NONE';vsync=$false;hostVisible=$false;jobMemoryLimitGiB=10;scenario='static';minimumDynamicSeconds=0;fullDynamicAcceptance=$false;
        legacyCoreJar=$legacyJar;legacyCoreSha256=(Get-FileHash -LiteralPath $legacyJar -Algorithm SHA256).Hash.ToLowerInvariant();windowsPowerScheme=(& powercfg.exe /getactivescheme 2>$null | Out-String).Trim()}
    [IO.File]::WriteAllText((Join-Path $baseline 'environment.json'),($environment|ConvertTo-Json -Depth 6)+"`n",$utf8)
}
& $runner -FilePath $Java -ArgumentList $arguments -MemoryLimitGB 10
if($LASTEXITCODE){throw 'Legacy Vulkan lighting execution failed'}
if($CompileOnly -or $LegacyOnly){return}
& (Join-Path $PSScriptRoot 'Invoke-NativeLightingCost.ps1') -OutputDirectory $candidate -ClassPath $ClassPath -RuntimeDirectory $RuntimeDirectory -ComparisonRoundId $ComparisonRoundId -Width $Width -Height $Height -Frames $Frames -Sources $Sources -Java $Java -JavaCompiler $JavaCompiler -Packaged:$Packaged
& (Join-Path $PSScriptRoot 'Compare-NativeLightingCost.ps1') -CandidateDirectory $candidate -BaselineDirectory $baseline -RequireComparable
