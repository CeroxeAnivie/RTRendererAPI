<# Compare identical E12 fixtures and fresh GPU timestamps; never infer speedup from FPS. #>
param(
    [Parameter(Mandatory=$true)][string]$CandidateDirectory,
    [string]$BaselineDirectory='',
    [string]$OutputFile='',
    [ValidateRange(0.000001,1)][double]$MaximumAbsoluteError=.01,
    [ValidateRange(0.000001,1)][double]$MaximumRmse=.002,
    [switch]$RequireComparable,
    [switch]$SyntheticContractTest
)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$utf8=[Text.UTF8Encoding]::new($false)
$candidate=[IO.Path]::GetFullPath($CandidateDirectory)
$report=[ordered]@{format='rtr-e12-cost-comparison';formatVersion=1;classification='INCOMPLETE';speedup=$null;reasons=@();imageErrors=@();stages=@();qualityMatched=$false;imageThresholds=@{maximumAbsolute=$MaximumAbsoluteError;maximumRmse=$MaximumRmse}}
function Load-Run([string]$directory){
    $run=Get-Content -LiteralPath (Join-Path $directory 'run.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    if($run.format -cne 'rtr-e12-cost-run' -or $run.formatVersion -ne 1){throw 'Unsupported E12 run format'}
    if($run.evidenceKind -cne 'REAL_GPU_RUN' -and !($SyntheticContractTest -and $run.evidenceKind -ceq 'SYNTHETIC_TEST_ONLY')){throw 'A real GPU run is required; isolated synthetic contracts need -SyntheticContractTest'}
    if($run.measuredFrames -lt 1 -or $run.lastMeasuredSequence-$run.firstMeasuredSequence+1 -ne $run.measuredFrames){throw 'Invalid measured sequence interval'}
    $hash=(Get-FileHash -LiteralPath (Join-Path $directory 'fixture.json') -Algorithm SHA256).Hash.ToLowerInvariant()
    if($hash -cne $run.workloadSha256){throw 'E12 fixture identity does not match the run'}
    $run
}
function Stage-Name([string]$name){
    switch -CaseSensitive ($name){'NativeLightingDispatch'{'lighting_dispatch'}'NativeLightingUpload'{'lighting_upload'}'NativeLightingPublish'{'producer_handoff'}default{$name}}
}
function Stage-Stats([string]$directory,$run){
    $groups=@{};$sequences=@{}
    foreach($row in Import-Csv -LiteralPath (Join-Path $directory 'gpu.csv')){
        $sequence=[long]$row.sequence
        if($sequence -lt $run.firstMeasuredSequence -or $sequence -gt $run.lastMeasuredSequence){throw 'GPU sample is outside the declared measured frame interval'}
        $frequency=[double]$row.frequency;$begin=[long]$row.begin_tick;$end=[long]$row.end_tick;$ms=[double]::Parse($row.inclusive_ms,[Globalization.CultureInfo]::InvariantCulture)
        # Subtract integer ticks before conversion: absolute GPU clocks can exceed
        # double's exact integer range even when the measured interval is short.
        if($frequency -le 0 -or $end -lt $begin -or ![double]::IsFinite($ms) -or $ms -lt 0 -or [Math]::Abs([double]($end-$begin)*1000/$frequency-$ms) -gt .00001){throw 'GPU elapsed time does not match its timestamp interval'}
        $name=Stage-Name $row.stage
        if(!$groups.ContainsKey($name)){$groups[$name]=[Collections.Generic.List[double]]::new();$sequences[$name]=[Collections.Generic.HashSet[long]]::new()}
        $groups[$name].Add($ms)
        $null=$sequences[$name].Add($sequence)
    }
    $result=@{}
    foreach($name in $groups.Keys){
        $values=$groups[$name].ToArray();[Array]::Sort($values)
        $result[$name]=[ordered]@{samples=$values.Count;uniqueSequences=$sequences[$name].Count;p50Ms=$values[[Math]::Max(0,[Math]::Ceiling($values.Count*.5)-1)];p95Ms=$values[[Math]::Max(0,[Math]::Ceiling($values.Count*.95)-1)];p99Ms=$values[[Math]::Max(0,[Math]::Ceiling($values.Count*.99)-1)];maximumMs=$values[-1]}
    }
    $result
}
function Finish {
    if(!$OutputFile){$script:OutputFile=Join-Path $candidate 'comparison.json'}
    [IO.File]::WriteAllText([IO.Path]::GetFullPath($OutputFile),($report|ConvertTo-Json -Depth 12)+"`n",$utf8)
    $report|ConvertTo-Json -Depth 12
    if($RequireComparable -and $report.classification -cne 'MATCHED_BACKEND_COMPARISON'){throw "E12 comparison is not valid: $($report.reasons -join '; ')"}
}
$current=Load-Run $candidate
$report.evidenceKind=$current.evidenceKind
$currentStats=Stage-Stats $candidate $current
if(!$BaselineDirectory){
    $report.classification='BASELINE_NOT_RUN';$report.reasons=@('A fresh old-backend run of this exact fixture and algorithm is required.','Required: fixture/run/environment JSON, GPU timestamp CSV, direct.pfm and indirect.pfm.','Receiver/source/shadow/GI are fused in the native dispatch; their separate costs remain unmeasured.','GL consumption and host reconstruction are outside this native-only measurement.');Finish;return
}
$baseline=[IO.Path]::GetFullPath($BaselineDirectory);$old=Load-Run $baseline;$oldStats=Stage-Stats $baseline $old
if($old.workloadSha256 -cne $current.workloadSha256){$report.classification='WORKLOAD_MISMATCH';$report.reasons=@('Scene, camera, algorithm, sample pattern, grid, formula or output set differ.');Finish;return}
if($old.backend -ceq $current.backend){$report.classification='SAME_BACKEND';$report.reasons=@('This pair does not establish a backend replacement benefit.');Finish;return}
if($old.evidenceKind -cne $current.evidenceKind){throw 'Real and synthetic evidence cannot be combined'}
if(!$old.timingValid -or !$current.timingValid -or $old.gpuTimerSemantics -cne $current.gpuTimerSemantics){$report.classification='INCOMPLETE_TIMING';$report.reasons=@('Complete matching GPU command timestamp intervals are required.');Finish;return}
$a=Get-Content -LiteralPath (Join-Path $candidate 'environment.json') -Raw -Encoding UTF8|ConvertFrom-Json
$b=Get-Content -LiteralPath (Join-Path $baseline 'environment.json') -Raw -Encoding UTF8|ConvertFrom-Json
$sameConditions=$a.comparisonRoundId -and $a.comparisonRoundId -ceq $b.comparisonRoundId -and $a.hardware.uuid -and $a.hardware.uuid -ceq $b.hardware.uuid -and $a.hardware.driver -ceq $b.hardware.driver -and $a.hardware.powerLimitWatts -ceq $b.hardware.powerLimitWatts -and $a.hardware.pstate -ceq $b.hardware.pstate -and $a.effectiveFrameCap -ceq $b.effectiveFrameCap -and $a.vsync -eq $b.vsync -and $a.hostVisible -eq $b.hostVisible
if(!('RtrE12Pfm' -as [type])){Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Globalization;
using System.Text;
public static class RtrE12Pfm {
    static string Token(byte[] b, ref int p) {
        while(p<b.Length && b[p]<=32)p++;
        if(p>=b.Length)throw new InvalidDataException("Truncated PFM header");
        if(b[p]==35){while(p<b.Length && b[p]!=10)p++;return Token(b,ref p);}
        int start=p;while(p<b.Length && b[p]>32)p++;
        return Encoding.ASCII.GetString(b,start,p-start);
    }
    static float[] Read(string file,out int width,out int height) {
        byte[] b=File.ReadAllBytes(file);int p=0;
        if(Token(b,ref p)!="PF")throw new InvalidDataException("E12 requires RGB PFM");
        width=int.Parse(Token(b,ref p),CultureInfo.InvariantCulture);height=int.Parse(Token(b,ref p),CultureInfo.InvariantCulture);
        if(double.Parse(Token(b,ref p),CultureInfo.InvariantCulture)!=-1.0)throw new InvalidDataException("E12 requires little-endian unit-scale PFM");
        if(p<b.Length && b[p]==13)p++;if(p>=b.Length || b[p++]!=10)throw new InvalidDataException("PFM header lacks newline");
        long count=(long)width*height*3;
        if(width<1 || height<1 || count>int.MaxValue || b.Length-p!=count*4)throw new InvalidDataException("Invalid PFM extent");
        float[] result=new float[(int)count];Buffer.BlockCopy(b,p,result,0,(int)count*4);return result;
    }
    public static double[] Compare(string a,string b) {
        int aw,ah,bw,bh;float[] x=Read(a,out aw,out ah),y=Read(b,out bw,out bh);
        if(aw!=bw || ah!=bh)throw new InvalidDataException("PFM extents differ");
        double maximum=0,sum=0;
        for(int i=0;i<x.Length;i++){
            if(float.IsNaN(x[i]) || float.IsInfinity(x[i]) || float.IsNaN(y[i]) || float.IsInfinity(y[i]))throw new InvalidDataException("Nonfinite PFM output");
            double difference=(double)x[i]-y[i];maximum=Math.Max(maximum,Math.Abs(difference));sum+=difference*difference;
        }
        return new double[]{maximum,Math.Sqrt(sum/x.Length),aw,ah};
    }
}
'@}
foreach($signal in @('direct','indirect')){
    $imageDelta=[RtrE12Pfm]::Compare((Join-Path $candidate "$signal.pfm"),(Join-Path $baseline "$signal.pfm"))
    $fixture=Get-Content -LiteralPath (Join-Path $candidate 'fixture.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    if($imageDelta[2] -ne $fixture.grid[0] -or $imageDelta[3] -ne $fixture.grid[1]){throw 'Captured PFM dimensions do not match the fixed lighting grid'}
    $report.imageErrors+=@{signal=$signal;maximumAbsolute=$imageDelta[0];rmse=$imageDelta[1];width=$imageDelta[2];height=$imageDelta[3]}
    if($imageDelta[0] -gt $MaximumAbsoluteError -or $imageDelta[1] -gt $MaximumRmse){$report.classification='QUALITY_MISMATCH';$report.reasons=@('Fixed predeclared pixel error thresholds failed; no same-algorithm performance claim.');Finish;return}
}
$report.qualityMatched=$true
if(!$sameConditions -or $current.adapterLuid -cne $old.adapterLuid){$report.classification='CONDITIONS_MISMATCH';$report.reasons=@('Use the same comparison round, adapter, driver, power limit, frame cap, VSync and visibility.');Finish;return}
if(!$a.baselineKnown -or !$b.baselineKnown -or $a.baselineMaximumPercent -gt 10 -or $b.baselineMaximumPercent -gt 10){$report.classification='RELATIVE_STAGES_ONLY';$report.reasons=@('Initial GPU utilization was above 10% or unknown. Cross-run speedup and absolute FPS claims are disabled.');Finish;return}
if(!$currentStats.ContainsKey('lighting_dispatch') -or !$oldStats.ContainsKey('lighting_dispatch') -or $currentStats['lighting_dispatch'].samples -ne $current.measuredFrames -or $oldStats['lighting_dispatch'].samples -ne $old.measuredFrames -or $currentStats['lighting_dispatch'].uniqueSequences -ne $current.measuredFrames -or $oldStats['lighting_dispatch'].uniqueSequences -ne $old.measuredFrames){$report.classification='INCOMPLETE_TIMING';$report.reasons=@('One native lighting dispatch GPU interval per measured frame is required.');Finish;return}
foreach($name in @('lighting_dispatch','lighting_upload','producer_handoff','receiver','source_query','shadow','gi','reconstruction','consumer_handoff')){
    $now=$currentStats[$name];$previous=$oldStats[$name];$ratio=$null
    if($now -and $previous -and $now.samples -eq $current.measuredFrames -and $previous.samples -eq $old.measuredFrames -and $now.uniqueSequences -eq $current.measuredFrames -and $previous.uniqueSequences -eq $old.measuredFrames -and $now.p50Ms -gt 0){$ratio=$previous.p50Ms/$now.p50Ms}
    $report.stages+=@{stage=$name;candidate=$now;baseline=$previous;p50Speedup=$ratio;status=if($null -eq $ratio){'NOT_COMPARABLE_OR_UNMEASURED'}else{'MATCHED_GPU_QUEUE_INTERVAL'}}
}
$report.classification=if($SyntheticContractTest){'SYNTHETIC_CONTRACT_TEST'}else{'MATCHED_BACKEND_COMPARISON'};$report.reasons=@('Ratios apply only to the listed matched command scopes, not whole-game FPS or unmeasured fused subphases.','Static fixture evidence does not complete the >=60s dynamic or >=120s mixed-pressure acceptance.');Finish
