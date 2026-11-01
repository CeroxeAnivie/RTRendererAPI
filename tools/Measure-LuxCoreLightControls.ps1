param(
    [string]$Sdk = 'build/luxcore-full/LuxCore',
    [string]$SourceScene = 'docs/quality/data/luxcore-official-control-20261101/scene',
    [string]$Output = 'build/reports/luxcore-light-controls',
    [ValidateSet('RTPATHOCL','PATHOCL','TILEPATHOCL')][string]$Engine = 'RTPATHOCL',
    [ValidateSet('minimal','no-camera','full')][string[]]$Cases = @('minimal','no-camera','full'),
    [ValidateRange(5,120)][int]$RenderSeconds = 12
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new()
$nativeConsole = (Resolve-Path (Join-Path $Sdk 'bin/luxcoreconsole.exe')).Path
$source = (Resolve-Path $SourceScene).Path
$null = New-Item -ItemType Directory -Path $Output -Force
$outputDirectory = (Resolve-Path $Output).Path
$originalScene = Get-Content -LiteralPath (Join-Path $source 'scene.scn')
$originalConfig = Get-Content -LiteralPath (Join-Path $source 'render.cfg')
$originalConfig = $originalConfig | ForEach-Object {
    if ($_ -match '^renderengine\.type\s*=') { 'renderengine.type = "'+$Engine+'"' }
    elseif ($_ -match '^sampler\.type\s*=') { 'sampler.type = "'+$(if($Engine -eq 'PATHOCL'){'SOBOL'}else{'TILEPATHSAMPLER'})+'"' }
    else { $_ }
}
$rows = @()
foreach ($case in $Cases) {
    $directory = Join-Path $outputDirectory $case
    $null = New-Item -ItemType Directory -Path $directory -Force
    foreach ($asset in Get-ChildItem -LiteralPath $source -File) {
        if ($asset.Extension -in @('.ply','.exr')) { Copy-Item -LiteralPath $asset.FullName -Destination $directory }
    }
    $scene = switch ($case) {
        'minimal' { @'
scene.camera.type = perspective
scene.camera.lookat.orig = 0 0 7
scene.camera.lookat.target = 0 0 0
scene.camera.up = 0 1 0
scene.camera.fieldofview = 30
scene.lights.sky.type = constantinfinite
scene.lights.sky.color = 0.12 0.16 0.22
scene.materials.solid.type = matte
scene.materials.solid.kd = 0.5 0.6 0.7
scene.objects.cube.ply = mesh-00000.ply
scene.objects.cube.material = solid
scene.objects.cube.transformation = 0.28 0 0 0 0 0.28 0 0 0 0 0.28 0 0 0 0 1
'@ }
        'no-camera' { ($originalScene | Where-Object { $_ -notmatch '^scene\.objects\.o500p0\.' -and $_ -notmatch '^scene\.camera\.volume\s*=' }) -join "`n" }
        'full' { $originalScene -join "`n" }
    }
    [IO.File]::WriteAllText((Join-Path $directory 'scene.scn'), $scene+"`n", [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllLines((Join-Path $directory 'render.cfg'), $originalConfig, [Text.UTF8Encoding]::new($false))
    $stdout = Join-Path $directory 'stdout.log'
    $stderr = Join-Path $directory 'stderr.log'
    $gpuBefore = (& nvidia-smi --query-gpu=timestamp,name,utilization.gpu,power.draw,clocks.sm --format=csv,noheader) -join "`n"
    Write-Host "NativeControl case=$case initialGpu=$gpuBefore"
    $arguments = @('-s','-D','batch.halttime','0','-D','batch.haltspp','0','-o',('"'+(Join-Path $directory 'render.cfg')+'"'))
    $nativeProcess = Start-Process -FilePath $nativeConsole -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $clock = [Diagnostics.Stopwatch]::StartNew()
    $lastElapsed = -1
    $measurements = @()
    try {
        while (-not $nativeProcess.HasExited) {
            Start-Sleep -Milliseconds 500
            $log = (Get-Content -LiteralPath $stdout -Raw -ErrorAction SilentlyContinue)+(Get-Content -LiteralPath $stderr -Raw -ErrorAction SilentlyContinue)
            $matches = [regex]::Matches($log,'\[Elapsed time:\s*(\d+)/\d+sec\]\[Samples\s+(\d+)/\d+\].*?Avg\. samples/sec\s+([\d.]+)M')
            if ($matches.Count -gt 0) {
                $measurements = @($matches | ForEach-Object { [ordered]@{
                    elapsedSeconds=[int]$_.Groups[1].Value
                    passes=[int]$_.Groups[2].Value
                    samplesPerSecond=[double]$_.Groups[3].Value*1000000
                    fps=[double]$_.Groups[3].Value*1000000/2073600
                } })
                $latest = $measurements[-1]
                if ($latest.elapsedSeconds -ne $lastElapsed) {
                    $lastElapsed = $latest.elapsedSeconds
                    Write-Host ('NativeControl case={0} elapsed={1}s fps={2:F3}' -f $case,$lastElapsed,$latest.fps)
                }
                if ($lastElapsed -ge $RenderSeconds) { break }
            }
            if ($clock.Elapsed.TotalSeconds -gt 420) { throw "Native $case failed to produce the requested sample window within 420 seconds; inspect $directory" }
        }
        if ($measurements.Count -eq 0) { throw "Native $case produced no throughput data; inspect $directory" }
        $latest = $measurements[-1]
        $row = [ordered]@{case=$case;initialGpu=$gpuBefore;engine=$Engine;width=1920;height=1080;nativeConsole=$nativeConsole;fps=$latest.fps;msPerOneSppFrame=1000/$latest.fps;progress=$measurements}
        $rows += $row
        $row | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $directory 'result.json') -Encoding utf8
    } finally {
        if (-not $nativeProcess.HasExited) { Stop-Process -Id $nativeProcess.Id; $nativeProcess.WaitForExit() }
        $nativeProcess.Dispose()
    }
}
$rows | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $outputDirectory 'result.json') -Encoding utf8
$rows | ForEach-Object { [pscustomobject]@{case=$_.case;fps=$_.fps;ms=$_.msPerOneSppFrame;initialGpu=$_.initialGpu} } | Format-Table -AutoSize
