<# Project-owned source paths use feature names; release identity belongs in metadata. #>
param()
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$paths=@(& git -C $repo ls-files --cached --others --exclude-standard) | Sort-Object -Unique
if($LASTEXITCODE -ne 0){throw 'Cannot inventory repository paths'}
$violations=@($paths | Where-Object {
    (Test-Path -LiteralPath (Join-Path $repo $_) -PathType Leaf) -and
    ([IO.Path]::GetFileName($_) -match '(?i)(?:v?\d+\.\d+\.\d+|(?:^|[-_])(?:70\d|71\d)(?:[-_.]|$)|Rtxpt(?:70\d|71\d))')
})
if($violations.Count){$violations | ForEach-Object {Write-Output $_};throw "Version-coupled file names: $($violations.Count)"}
Write-Output "FILE_NAMING PASS inspected=$($paths.Count) versionCoupled=0"
