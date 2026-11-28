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
        PatchNormalizedSha256='516ad4e6f66310dcc7ec2bd722fe6ea7fc517ae9c6df9b818f62297fb191d8ad'
        Contract='streamline-offline-device.json'
        ContractNormalizedSha256='9039d3e202e7a3f69fa6bceeb6d7e787dc2da96b53d387809ac41426ebca0653'
        File='DeviceManager_DX12.cpp';Target='donut_app';Scope='networkLifecycleOnly';Hunks=1
    },
    @{
        Name='runtime-root-lookup';DirectoryName='runtime-root-lookup';ManifestFile='runtime-root-manifest.json'
        Source='External/Donut/src/app/ApplicationBase.cpp'
        OriginalSha256='f0c32e67aa3ec7c263a0d0248581a0213e0e47a9be9edcc57bc0721cd55bb9fb'
        Patch='runtime-root-lookup.patch'
        PatchNormalizedSha256='fb550994ff1ea498d5ae899496f2917e07e0fe826c80e6d912456cc1a3578dbb'
        Contract='runtime-root-lookup.json'
        ContractNormalizedSha256='f0b1a8f8da28e8d3e9ac3efb70fde6c7e49f07b5a85a0a4fe2c4197be0a85390'
        File='ApplicationBase.cpp';Target='donut_app';Scope='runtimeDirectoryOnly';Hunks=2
    },
    @{
        Name='embedded-window';Source='External/Donut/src/app/DeviceManager.cpp'
        OriginalSha256='2c80c3a189c077be6a48e429e84702b16ebf09f008f44904aa81869ed88805e6'
        Patch='embedded-window.patch'
        PatchNormalizedSha256='32f82fa9344a2a8b8bfb407cc7443953ec4fc504a292dedcf300d7ba9d03a855'
        Contract='embedded-window.json'
        ContractNormalizedSha256='a202da41dbaa8760e587504b82f3fe0249a7e5bab38042faf57df93e543596d6'
        File='DeviceManager.cpp';Target='donut_app';Scope='windowVisibilityOnly';Hunks=3
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
        if((Get-TextSha256 $contractText) -ne $contract.ContractNormalizedSha256){throw 'Authorized insertion contract differs.'}
        $sourceContract=$contractText|ConvertFrom-Json
        if($sourceContract.format -notin @('rtr-authorized-network-variant-v1','rtr-authorized-embedded-variant-v1') -or
            $sourceContract.source -cne $contract.Source -or $sourceContract.officialSha256 -cne $originalHash){throw 'Authorized insertion contract source differs.'}
        $operations=@($sourceContract.operations|ForEach-Object {@{Anchor=$_.anchor.Replace([string][char]10,$crlf);Insertion=$_.insertion.Replace([string][char]10,$crlf)}})
        if($operations.Count -ne $contract.Hunks){throw 'Authorized insertion count differs.'}
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
        if($contract.Contract -and $sourceContract.format -ceq 'rtr-authorized-network-variant-v1' -and
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
    if($manifests.Count -ne ($contracts.Count+1)){throw 'Unexpected generated upstream integration variant manifests.'}
    $pipelineDirectory=Join-Path $build 'rtrenderer-patches/pipeline-cache'
    $pipelineManifest=Get-Content -LiteralPath (Join-Path $pipelineDirectory 'manifest.json') -Raw -Encoding UTF8|ConvertFrom-Json
    if($pipelineManifest.format -cne 'rtr-authorized-d3d12-pipeline-cache-v1' -or
        $pipelineManifest.target -cne 'nvrhi_d3d12' -or $pipelineManifest.copiedTargetSources -lt 3 -or
        $pipelineManifest.officialSourcesUnmodified -ne $true){throw 'D3D12 pipeline cache variant scope differs.'}
    $pipelineSources=@{
        'd3d12-backend.h'='a382af4bdbf07f43467485c5dd6376f08eabcb67132d3ebd6cd2f37bbea365b8'
        'd3d12-device.cpp'='47c4083ffd390837f3067e0e13b5cc8e609ab26c89c6a58a448954b83772ef86'
        'd3d12-resource-bindings.cpp'='9d2474b6278f118e60548bc94e833228e7da29ab779517016b1c87cfcfa81016'
        'd3d12-compute.cpp'='88c84e99061fd372756342fa692b10c799f99dcd1249973a328914bf182e1f1f'
    }
    $pipelineVariants=@{
        'd3d12-backend.h'='46a370cc2d33c3fe3c58d990306ad586274f6ea13fa831afd8e5b3f2ea5bb826'
        'd3d12-device.cpp'='17d4f1ee0a74e5cc457c676ddf32524b2b8fd14d992a992acce223dc34f3ba3e'
        'd3d12-resource-bindings.cpp'='8397b8364ab2a07aaaf63224d33f304b2dc8c8835575382d82bfdc41d81404cd'
        'd3d12-compute.cpp'='8024f59498fb77fc5baf13e978a0bf889be59f38a00b2a3c045ca2b2cf009f46'
    }
    foreach($name in $pipelineSources.Keys){
        $original=Join-Path $sdk ('External/Donut/nvrhi/src/d3d12/'+$name)
        $variant=Join-Path $pipelineDirectory $name
        $originalHash=(Get-FileHash -LiteralPath $original -Algorithm SHA256).Hash.ToLowerInvariant()
        $variantHash=(Get-FileHash -LiteralPath $variant -Algorithm SHA256).Hash.ToLowerInvariant()
        if($originalHash -cne $pipelineSources[$name] -or
            $pipelineManifest.files.$name.officialSha256 -cne $originalHash -or
            $pipelineManifest.files.$name.variantSha256 -cne $variantHash -or
            $variantHash -cne $pipelineVariants[$name]){
            throw ('D3D12 pipeline cache variant hash differs: '+$name)
        }
    }
    Assert-SamePath $pipelineManifest.files.adapterHeader (Join-Path $repo 'renderer-rtxpt/src/main/cpp/rtxpt_d3d12_pipeline_cache.h')
}
[pscustomobject]@{AuthorizedExceptions=4;AuthorizedVariants=$results.Count+1;SourceContractsVerified=$true;GeneratedVariantsVerified=[bool]$RequireGenerated;Variants=$results.ToArray();NetworkGuarantee='Disabled sessions bypass Streamline initialization and native entry imports; enabled sessions can retain mandatory Streamline updates.'}
