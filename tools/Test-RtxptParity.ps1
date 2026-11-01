param(
    [int]$Rounds=3,
    [string]$JavaExecutable='java',
    [string]$OutputDirectory='build/reports/rtxpt-parity',
    [switch]$SkipBuild
)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$repo=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location -LiteralPath $repo
if(!$SkipBuild) {
    & ./gradlew.bat :renderer-rtxpt:testClasses :renderer-rtxpt:jar :renderer-api:jar
    if($LASTEXITCODE -ne 0){throw 'Build the packaged acceptance artifacts first'}
}
$output=[IO.Path]::GetFullPath((Join-Path $repo $OutputDirectory))
New-Item -ItemType Directory -Path $output -Force | Out-Null
$api=Get-ChildItem renderer-api/build/libs/renderer-api-*.jar | Where-Object Name -NotMatch 'sources|javadoc' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
$backend=Get-ChildItem renderer-rtxpt/build/libs/renderer-rtxpt-*.jar | Where-Object Name -NotMatch 'sources|javadoc' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if(!$api -or !$backend){throw 'Missing distribution JARs'}
$classpath=@((Join-Path $repo 'renderer-rtxpt/build/classes/java/test'),$api.FullName,$backend.FullName)-join ';'
$javaArguments=@('-Xmx512m','-XX:-UseJVMCICompiler','--enable-native-access=ALL-UNNAMED','-Dfile.encoding=UTF-8','-cp',$classpath,'top.ceroxe.rt.renderer.rtxpt.RtxptParityBenchmark')
$fixture=Join-Path $output 'fixture'
$native=Join-Path $repo 'build/rtxpt-sdk/bin/rtxpt_native_control.exe'
$consumer=Join-Path ([IO.Path]::GetTempPath()) ('rtxpt-parity-consumer-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $consumer | Out-Null
$rows=[Collections.Generic.List[object]]::new()
try {
    Push-Location -LiteralPath $consumer
    & $JavaExecutable @javaArguments prepare $fixture *> (Join-Path $output 'prepare.log')
    if($LASTEXITCODE -ne 0){throw ('Fixture preparation failed: '+(Join-Path $output 'prepare.log'))}
    $nativeArguments=@(Get-Content -LiteralPath (Join-Path $fixture 'native-arguments.json') -Raw | ConvertFrom-Json)
    for($round=1;$round -le $Rounds;$round++) {
        $order=if($round%2){@('native','jar')}else{@('jar','native')}
        foreach($mode in $order) {
            $gpu=(& nvidia-smi --query-gpu=utilization.gpu --format=csv,noheader,nounits --id=0).Trim()
            if($LASTEXITCODE -ne 0 -or $gpu -notmatch '^\d+$'){throw 'Cannot verify GPU idle state; benchmark was not started'}
            if([int]$gpu -ne 0){
                [pscustomobject]@{status='WAITING_FOR_IDLE_GPU';round=$round;mode=$mode;initialGpuPercent=[int]$gpu;rows=@($rows.ToArray())} |
                    ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $output 'paused.json') -Encoding utf8
                throw "GPU baseline is $gpu%; benchmark paused until GPU baseline is 0%"
            }
            $log=Join-Path $output ("round-$round-$mode.log")
            if($mode -eq 'native'){& $native @nativeArguments *> $log}
            else{& $JavaExecutable @javaArguments benchmark $fixture *> $log}
            if($LASTEXITCODE -ne 0){throw "$mode round $round failed; see $log"}
            $content=Get-Content -LiteralPath $log -Raw
            if($content -match '(?m)^(ERROR:|FATAL ERROR:|Exception in thread)'){throw "Runtime error in $log"}
            $match=[regex]::Match($content,'RTXPT_PARITY (native|jar) frames=(\d+) seconds=([\d.]+) fps=([\d.]+) ms=([\d.]+) input=(\d+)x(\d+) aa=(\d+) sharc=(\d+) fg=(\d+)')
            if(!$match.Success){throw "Missing completed-frame result in $log"}
            $row=[pscustomobject]@{round=$round;mode=$mode;initialGpuPercent=[int]$gpu;frames=[long]$match.Groups[2].Value;
                seconds=[double]::Parse($match.Groups[3].Value,[Globalization.CultureInfo]::InvariantCulture);
                fps=[double]::Parse($match.Groups[4].Value,[Globalization.CultureInfo]::InvariantCulture);
                ms=[double]::Parse($match.Groups[5].Value,[Globalization.CultureInfo]::InvariantCulture);
                inputWidth=[int]$match.Groups[6].Value;inputHeight=[int]$match.Groups[7].Value;
                aa=[int]$match.Groups[8].Value;sharc=[int]$match.Groups[9].Value;fg=[int]$match.Groups[10].Value}
            if($row.aa -ne 3 -or $row.sharc -ne 1 -or $row.fg -ne 0){throw 'Native/JAR settings differ'}
            $rows.Add($row);$row|Format-Table
            Start-Sleep -Seconds 1
        }
    }
} finally {
    Pop-Location
    # Only remove the exact temporary consumer folder allocated above.
    if($consumer.StartsWith([IO.Path]::GetTempPath(),[StringComparison]::OrdinalIgnoreCase)){
        Remove-Item -LiteralPath $consumer -Recurse -Force
    }
}
function Median($values){$sorted=@($values|Sort-Object);if($sorted.Count%2){$sorted[[int][Math]::Floor($sorted.Count/2)]}else{($sorted[$sorted.Count/2-1]+$sorted[$sorted.Count/2])/2}}
$nativeMs=Median @($rows|Where-Object mode -EQ 'native'|ForEach-Object ms)
$jarMs=Median @($rows|Where-Object mode -EQ 'jar'|ForEach-Object ms)
$loss=100*($jarMs/$nativeMs-1)
$comparable=@($rows|Where-Object initialGpuPercent -NE 0).Count -eq 0
$summary=[ordered]@{commit=(& git -C $repo rev-parse HEAD).Trim();rounds=$Rounds;outputWidth=1920;outputHeight=1080;spp=1;fg=$false;
    nativeMedianMs=$nativeMs;jarMedianMs=$jarMs;nativeFps=1000/$nativeMs;jarFps=1000/$jarMs;
    overheadPercent=$loss;absoluteComparable=$comparable;pass=$comparable -and $loss -le 5;rows=@($rows.ToArray());
    backendJarSha256=(Get-FileHash -LiteralPath $backend.FullName -Algorithm SHA256).Hash}
$summary|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $output 'result.json') -Encoding utf8
$rows|Export-Csv -LiteralPath (Join-Path $output 'rounds.csv') -NoTypeInformation -Encoding utf8
Write-Output ("Native {0:F2} FPS / {1:F3} ms; JAR {2:F2} FPS / {3:F3} ms; overhead {4:F2}%; comparable={5}; pass={6}" -f $summary.nativeFps,$nativeMs,$summary.jarFps,$jarMs,$loss,$comparable,$summary.pass)
if(!$summary.pass){throw 'Parity acceptance did not pass; see result.json'}
