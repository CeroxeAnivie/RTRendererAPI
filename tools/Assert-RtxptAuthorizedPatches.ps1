<#
.SYNOPSIS
Audit the two explicitly authorized build-side variants without changing upstream.
#>
param(
    [string]$SdkRoot='build/upstream-rtxpt/source',
    [string]$BuildRoot='build/upstream-rtxpt/native',
    [switch]$RequireGenerated
)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$sdk=(Resolve-Path -LiteralPath ([IO.Path]::GetFullPath($SdkRoot,$repo))).Path
$build=[IO.Path]::GetFullPath($BuildRoot,$repo)
$utf8=[Text.UTF8Encoding]::new($false)
$crlf=[string][char]13+[char]10
function Get-TextSha256([string]$Text){
    [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($utf8.GetBytes($Text))).ToLowerInvariant()
}
function Assert-SamePath([string]$Actual,[string]$Expected){
    if(-not [string]::Equals([IO.Path]::GetFullPath($Actual),[IO.Path]::GetFullPath($Expected),[StringComparison]::OrdinalIgnoreCase)){
        throw ('Authorized patch path mismatch: '+$Actual)
    }
}
$contracts=@(
    @{
        Name='progress-noninteractive';Source='Rtxpt/SampleCommon/SampleCommon.cpp'
        OriginalSha256='fe2d1d29021ca6506707a3ea8749ffa37c6d33491fc321e045a16a84bc3742e0'
        Patch='progress-noninteractive.patch'
        PatchNormalizedSha256='5b106cc260223f7fd8b2addc21bd25d757e9b546fca1bc2054552ce86a417684'
        Anchor=('int ProgressBarStart(const char * windowText)'+$crlf+'{'+$crlf)
        Insertion=('    if (HelpersIsNonInteractive())'+$crlf+'        return -1;'+$crlf+$crlf)
        File='SampleCommon.cpp';Target='RtxptCore';Scope='interactiveBodyByteIdentical';Hunks=1
    },
    @{
        Name='streamline-offline-preferences';Source='External/Donut/src/app/streamline/StreamlineIntegration.cpp'
        OriginalSha256='60b6756523e5e16a4d08e5b9842a99050048af1340008fbdf338c92377299929'
        Patch='streamline-offline-preferences.patch'
        PatchNormalizedSha256='4f245f8fb11e7f0d1b28601040d64cc28fbcf06a749321fad1cf5b3993c9163c'
        Contract='streamline-offline-preferences.json'
        ContractNormalizedSha256='f9df44b2ca8ebf126c1ed24d1888b793f27c4d0315ad9a48f866939986a5e876'
        File='StreamlineIntegration.cpp';Target='donut_app';Scope='networkLifecycleOnly';Hunks=10
    },
    @{
        Name='streamline-offline-device';DirectoryName='streamline-offline-preferences';ManifestFile='device-manifest.json'
        Source='External/Donut/src/app/dx12/DeviceManager_DX12.cpp'
        OriginalSha256='039d933d45ae5fe83a1e67e77fdf826198528f6061a8fcd3258a50359d395193'
        Patch='streamline-offline-device.patch'
        PatchNormalizedSha256='5a15dc7b67e1b5df0842029575bc1821913352c35c4010542476638068227682'
        Contract='streamline-offline-device.json'
        ContractNormalizedSha256='2a1747f032c2f1f1b920557ab84e51d7f1d8300a5c91fe113c908c4c6fdbe658'
        File='DeviceManager_DX12.cpp';Target='donut_app';Scope='networkLifecycleOnly';Hunks=1
    }
)
$results=[Collections.Generic.List[object]]::new()
foreach($contract in $contracts){
    $original=Join-Path $sdk $contract.Source
    $originalHash=(Get-FileHash -LiteralPath $original -Algorithm SHA256).Hash.ToLowerInvariant()
    if($originalHash -ne $contract.OriginalSha256){throw ('Authorized patch official source identity differs: '+$original)}
    $patch=Join-Path $PSScriptRoot ('patches/'+$contract.Patch)
    $patchText=[IO.File]::ReadAllText($patch,$utf8).Replace($crlf,[string][char]10)
    if((Get-TextSha256 $patchText) -ne $contract.PatchNormalizedSha256){throw ('Authorized patch text differs: '+$patch)}
    if([regex]::Matches($patchText,'(?m)^@@ ').Count -ne $contract.Hunks){throw 'Authorized variant patch hunk count differs.'}
    $content=[IO.File]::ReadAllText($original,$utf8)
    $operations=@(@{Anchor=$contract.Anchor;Insertion=$contract.Insertion})
    if($contract.Contract){
        $contractText=[IO.File]::ReadAllText((Join-Path $PSScriptRoot ('patches/'+$contract.Contract)),$utf8).Replace($crlf,[string][char]10)
        if((Get-TextSha256 $contractText) -ne $contract.ContractNormalizedSha256){throw 'Authorized network insertion contract differs.'}
        $networkContract=$contractText|ConvertFrom-Json
        if($networkContract.format -cne 'rtr-authorized-network-variant-v1' -or $networkContract.source -cne $contract.Source -or $networkContract.officialSha256 -cne $originalHash){throw 'Authorized network contract source differs.'}
        $operations=@($networkContract.operations|ForEach-Object {@{Anchor=$_.anchor.Replace([string][char]10,$crlf);Insertion=$_.insertion.Replace([string][char]10,$crlf)}})
        if($operations.Count -ne $contract.Hunks){throw 'Authorized network insertion count differs.'}
    }
    $expectedContent=$content
    foreach($operation in $operations){
        if([regex]::Matches($expectedContent,[regex]::Escape($operation.Anchor)).Count -ne 1){throw ('Authorized insertion anchor is not unique: '+$original)}
        $before=$expectedContent
        $expectedContent=$before.Replace($operation.Anchor,$operation.Anchor+$operation.Insertion)
        if($expectedContent.Replace($operation.Anchor+$operation.Insertion,$operation.Anchor) -cne $before){throw 'Authorized variant is not reversible.'}
    }
    $variantSha=Get-TextSha256 $expectedContent
    $directoryName=if($contract.DirectoryName){$contract.DirectoryName}else{$contract.Name}
    $directory=Join-Path $build ('rtrenderer-patches/'+$directoryName)
    $variant=Join-Path $directory $contract.File
    if($RequireGenerated){
        $manifestFile=if($contract.ManifestFile){$contract.ManifestFile}else{'manifest.json'}
        $manifest=Get-Content -LiteralPath (Join-Path $directory $manifestFile) -Raw -Encoding UTF8|ConvertFrom-Json
        Assert-SamePath $manifest.officialSource $original
        Assert-SamePath $manifest.variantSource $variant
        Assert-SamePath $manifest.patch $patch
        if($manifest.officialSha256 -ne $originalHash -or $manifest.variantSha256 -ne $variantSha -or
            $manifest.patchSha256 -ne (Get-FileHash -LiteralPath $patch -Algorithm SHA256).Hash.ToLowerInvariant()){
            throw ('Authorized variant manifest hash differs: '+$contract.Name)
        }
        if($manifest.target -cne $contract.Target -or $manifest.officialFileVariants -ne 1 -or
            $manifest.replacedTargetSources -ne 1 -or $manifest.hunks -ne $contract.Hunks -or
            $manifest.officialExportUnmodified -ne $true -or $manifest.($contract.Scope) -ne $true){
            throw ('Authorized variant scope differs: '+$contract.Name)
        }
        if($contract.Contract -and
            ($manifest.localFeatureSelectionUnmodified -ne $true -or $manifest.guaranteesNoNetwork -ne $false -or
             $manifest.disabledSessionBypassesInitialization -ne $true -or $manifest.contractNormalizedSha256 -ne $contract.ContractNormalizedSha256)){
            throw 'Offline-preferences audit must retain local features and disclose mandatory-OTA boundary.'
        }
        if((Get-FileHash -LiteralPath $variant -Algorithm SHA256).Hash.ToLowerInvariant() -ne $variantSha -or
            [IO.File]::ReadAllText($variant,$utf8) -cne $expectedContent){
            throw ('Generated variant contains unauthorized changes: '+$variant)
        }
    }
    $results.Add([pscustomobject]@{Name=$contract.Name;Target=$contract.Target;OriginalSha256=$originalHash;VariantSha256=$variantSha;GeneratedVerified=[bool]$RequireGenerated})
}
if($RequireGenerated){
    $manifests=@(Get-ChildItem -LiteralPath (Join-Path $build 'rtrenderer-patches') -Filter '*manifest.json' -File -Recurse)
    if($manifests.Count -ne $contracts.Count){throw 'Exactly three source manifests for the two authorized exceptions must exist.'}
}
[pscustomobject]@{AuthorizedExceptions=2;AuthorizedVariants=$results.Count;SourceContractsVerified=$true;GeneratedVariantsVerified=[bool]$RequireGenerated;Variants=$results.ToArray();NetworkGuarantee='Disabled sessions bypass Streamline initialization and native entry imports; enabled sessions can retain mandatory Streamline updates.'}
