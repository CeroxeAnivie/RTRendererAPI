param(
    [string]$SdkRoot='build/rtxpt-sdk',
    [string]$CMakeExecutable='build/native-build-tools/cmake-4.1.2-windows-x86_64/bin/cmake.exe',
    [string]$StreamlineSdk=$env:STREAMLINE_SDK,
    [double]$MemoryLimitGB=10,
    [switch]$ConfigureOnly,
    [switch]$OpticalTransportTrace
)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$repo=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$sdk=(Resolve-Path (Join-Path $repo $SdkRoot)).Path
$pin='303b408ab81790a5051abd7573830b7a4112eb8e'
if((& git -C $sdk rev-parse HEAD).Trim() -ne $pin){throw 'RTXPT source must match the pinned 1.9.0 commit'}
$utf8=[Text.UTF8Encoding]::new($false)
function Patch-Once([string]$Relative,[string]$Anchor,[string]$Replacement,[string]$Marker){
    $path=Join-Path $sdk $Relative
    $text=[IO.File]::ReadAllText($path)
    if($text.Contains($Marker)){return}
    $Anchor=[regex]::Replace($Anchor,"`r?`n","`r`n")
    $Replacement=[regex]::Replace($Replacement,"`r?`n","`r`n")
    if(!$text.Contains($Anchor)){throw "Pinned host integration anchor changed: $Relative :: $Anchor"}
    [IO.File]::WriteAllText($path,$text.Replace($Anchor,$Replacement),$utf8)
}
# Bound nested shader compilers as well as the top-level MSBuild process.
Patch-Once 'Rtxpt/SampleUI.h' '    //float                               DLSSSharpness = 0.f;' @'
    SI::DLSSPreset                      HostDLSSSRPreset = SI::DLSSPreset::eDefault; // RTR explicit upstream SR model
    //float                               DLSSSharpness = 0.f;
'@ 'RTR explicit upstream SR model'
Patch-Once 'Rtxpt/Sample.cpp' '                dlssOptions.preset = StreamlineInterface::DLSSPreset::eDefault;' @'
                dlssOptions.preset = m_ui.HostDLSSSRPreset; // RTR forward upstream SR model without changing resolution
'@ 'RTR forward upstream SR model'
# Isolate the old Vulkan route's documented 1x1 exposure contract without changing
# the SR model, color, tone mapper, sample count, or render resolution.
Patch-Once 'Rtxpt/SampleUI.h' '    //float                               DLSSSharpness = 0.f;' @'
    float                              HostDLSSFixedExposure = 0.f; // RTR isolated SR exposure, zero keeps upstream auto
    //float                               DLSSSharpness = 0.f;
'@ 'RTR isolated SR exposure'
Patch-Once 'Rtxpt/Sample.h' '    void                                    PostProcessPreToneMapping(nvrhi::ICommandList* commandList, const donut::engine::ICompositeView& compositeView);' @'
    nvrhi::TextureHandle                     m_hostDLSSExposure; // RTR optional one-texel exposure
    void                                    PostProcessPreToneMapping(nvrhi::ICommandList* commandList, const donut::engine::ICompositeView& compositeView);
'@ 'RTR optional one-texel exposure'
Patch-Once 'Rtxpt/Sample.cpp' 'dlssOptions.useAutoExposure = true;' 'dlssOptions.useAutoExposure = m_ui.HostDLSSFixedExposure <= 0.f || m_ui.RealtimeAA != 2; /* RTR explicit SR exposure contract */' 'RTR explicit SR exposure contract'
Patch-Once 'External/Donut/include/donut/app/StreamlineInterface.h' '        nvrhi::ITexture* input) = 0;' @'
        nvrhi::ITexture* input,
        nvrhi::ITexture* exposure = nullptr) = 0; // RTR optional DLSS exposure tag
'@ 'RTR optional DLSS exposure tag'
Patch-Once 'External/Donut/src/app/streamline/StreamlineIntegration.h' '        nvrhi::ITexture* input) override;' @'
        nvrhi::ITexture* input,
        nvrhi::ITexture* exposure = nullptr) override; // RTR optional DLSS exposure tag
'@ 'RTR optional DLSS exposure tag'
Patch-Once 'External/Donut/src/app/streamline/StreamlineIntegration.cpp' '    nvrhi::ITexture* Input)' @'
    nvrhi::ITexture* Input,
    nvrhi::ITexture* Exposure) // RTR optional DLSS exposure tag
'@ 'RTR optional DLSS exposure tag'
Patch-Once 'External/Donut/src/app/streamline/StreamlineIntegration.cpp' '    sl::ResourceTag inputs[] = { inputResourceTag, outputResourceTag };' @'
    sl::Resource exposureResource{};
    sl::Extent exposureExtent{0, 0, 1, 1};
    if (Exposure) GetSLResource(commandList, exposureResource, Exposure, view);
    // A null tag removes previous fixed exposure when returning to upstream auto.
    sl::ResourceTag exposureTag{ Exposure ? &exposureResource : nullptr, sl::kBufferTypeExposure,
        sl::ResourceLifecycle::eValidUntilPresent, &exposureExtent };
    sl::ResourceTag inputs[] = { inputResourceTag, outputResourceTag, exposureTag }; // RTR explicit exposure input
'@ 'RTR explicit exposure input'
Patch-Once 'Rtxpt/Sample.cpp' @'
        // TAG STREAMLINE RESOURCES
        GetDeviceManager()->GetStreamline().TagResourcesDLSSNIS(m_commandList,
'@ @'
        if (m_ui.HostDLSSFixedExposure > 0.f && m_ui.RealtimeAA == 2 && !m_hostDLSSExposure)
        {
            nvrhi::TextureDesc desc;
            desc.width = desc.height = 1;
            desc.format = nvrhi::Format::R32_FLOAT;
            desc.isUAV = true;
            desc.initialState = nvrhi::ResourceStates::ShaderResource;
            desc.keepInitialState = true;
            desc.debugName = "HostDLSSExposure";
            m_hostDLSSExposure = GetDevice()->createTexture(desc);
            m_commandList->clearTextureFloat(m_hostDLSSExposure, nvrhi::AllSubresources, nvrhi::Color(m_ui.HostDLSSFixedExposure));
            log::info("RTXPT explicit SR exposure texture=%.3f (1x1 R32F)", m_ui.HostDLSSFixedExposure);
        }
        // TAG STREAMLINE RESOURCES
        GetDeviceManager()->GetStreamline().TagResourcesDLSSNIS(m_commandList,
'@ 'RTXPT explicit SR exposure texture='
Patch-Once 'Rtxpt/Sample.cpp' @'
            m_renderTargets->ProcessedOutputColor,
            m_renderTargets->OutputColor);
'@ @'
            m_renderTargets->ProcessedOutputColor,
            m_renderTargets->OutputColor,
            m_ui.HostDLSSFixedExposure > 0.f && m_ui.RealtimeAA == 2 ? m_hostDLSSExposure.Get() : nullptr); // RTR tag fixed exposure
'@ 'RTR tag fixed exposure'
Patch-Once 'External/Donut/compileshaders.cmake' 'set(compilerCommand ${SHADERMAKE_PATH}' 'set(compilerCommand ${SHADERMAKE_PATH} --jobs 1' '${SHADERMAKE_PATH} --jobs 1'
Patch-Once 'External/Nrd/CMakeLists.txt' 'set(SHADERMAKE_GENERAL_ARGS' "set(SHADERMAKE_GENERAL_ARGS --jobs 1" 'SHADERMAKE_GENERAL_ARGS --jobs 1'
# Only expose host control and runtime file roots. Rendering, scheduling and shaders stay upstream.
# Observe existing upstream GPU scopes without enabling Tracy or disabling async prefix.
Patch-Once 'Rtxpt/SampleCommon/GpuProfiler.cpp' '#include "GpuProfiler.h"' @'
#include "GpuProfiler.h"
#include "rtxpt_gpu_profile.h" // RTR sampled per-queue timestamp export
'@ 'RTR sampled per-queue timestamp export'
Patch-Once 'Rtxpt/SampleCommon/GpuProfiler.cpp' 'bool                    EmitMarker;' @'
bool                    EmitMarker;
        RtrGpuProfile::Token HostToken; // RTR sampled scope token
'@ 'RTR sampled scope token'
Patch-Once 'Rtxpt/SampleCommon/GpuProfiler.cpp' '        t_openZones.push_back(zone);' @'
        int hostParent=-1;
        for(auto it=t_openZones.rbegin();it!=t_openZones.rend();++it)
            if(it->HostToken.queue>=0 && it->CommandList->getDesc().queueType==commandList->getDesc().queueType) {
                hostParent=int(it->HostToken.zone); break;
            }
        zone.HostToken=RtrGpuProfile::Begin(commandList,name,hostParent); // RTR sampled scope begin
        t_openZones.push_back(zone);
'@ 'RTR sampled scope begin'
Patch-Once 'Rtxpt/SampleCommon/GpuProfiler.cpp' '    t_openZones.pop_back();' @'
    t_openZones.pop_back();
    RtrGpuProfile::End(commandList,zone.HostToken); // RTR sampled scope end
'@ 'RTR sampled scope end'
$profilerPath=Join-Path $sdk 'Rtxpt/SampleCommon/GpuProfiler.cpp'
$profilerText=[IO.File]::ReadAllText($profilerPath)
foreach($entry in @(@('Init(nvrhi::IDevice* device)','RtrGpuProfile::Init(device);'),@('Shutdown()','RtrGpuProfile::Shutdown();'),@('AfterPresent()','RtrGpuProfile::AfterPresent();'))){
    $tag='// RTR host '+$entry[0]
    if(!$profilerText.Contains($tag)){
        $pattern='(void GpuProfiler::'+[regex]::Escape($entry[0])+'\s*\{)'
        if(![regex]::IsMatch($profilerText,$pattern)){throw ('GPU profiler lifecycle anchor changed: '+$entry[0])}
        $profilerText=[regex]::Replace($profilerText,$pattern,('$1'+"`n    "+$entry[1]+' '+$tag),1)
    }
}
[IO.File]::WriteAllText($profilerPath,$profilerText,$utf8)
# SR/TAA must consume this frame's merged radiance, not last frame's OutputColor.
# RR already merges its own guides in the render prefix; NRD already merges before AA.
$samplePath=Join-Path $sdk 'Rtxpt/Sample.cpp'
$sampleText=[IO.File]::ReadAllText($samplePath)
if(!$sampleText.Contains('RTR current radiance before temporal reconstruction')){
    $lateMerge='(?s)        else if \( !m_ui\.ActualUseStandaloneDenoiser\(\) \)\s*\{\s*// If all denoisers disabled, this is a pass-through that just merges and outputs noisy data\s*SampleMiniConstants miniConstants = \{ uint4\(0, 0, 0, 0\) \};\s*nvrhi::TextureDesc tdesc = m_renderTargets->OutputColor->getDesc\(\);\s*GpuProfiler::Begin\(m_commandList, "NoDenoiserFinalMerge"\);\s*m_postProcess->Apply\(m_commandList, PostProcess::ComputePassType::NoDenoiserFinalMerge, m_constantBuffer, miniConstants, m_bindingSet, m_bindingLayout, tdesc.width, tdesc.height\);\s*GpuProfiler::End\(m_commandList\);\s*\}'
    $match=[regex]::Match($sampleText,$lateMerge)
    if(!$match.Success){throw 'Pinned late no-denoiser merge anchor changed'}
    $body=$match.Value.Substring($match.Value.IndexOf('{'))
    $sampleText=$sampleText.Remove($match.Index,$match.Length)
    $anchor='(void Sample::PostProcessAA\(nvrhi::IFramebuffer\* framebuffer, bool reset\)\s*\{\s*ZoneScopedN\("PostProcessAA"\);\s*if \(m_ui.RealtimeMode\)\s*\{)'
    if(![regex]::IsMatch($sampleText,$anchor)){throw 'Pinned pre-AA merge anchor changed'}
    $insert='$1'+"`n        // RTR current radiance before temporal reconstruction`n        if (m_ui.RealtimeAA != 3 && !m_ui.ActualUseStandaloneDenoiser())`n        "+$body+"`n"
    $sampleText=[regex]::Replace($sampleText,$anchor,$insert,1)
    [IO.File]::WriteAllText($samplePath,$sampleText,$utf8)
}
# RTXDI writes directly into OutputColor when no denoiser is active. Initialize
# that color from the current path-tracing layers BEFORE RTXDI adds its lighting;
# merging afterwards discards the entire direct/indirect reuse contribution.
Patch-Once 'Rtxpt/AdvancedSample.cpp' '    // this is a performance optimization where final 2 passes from ReSTIR DI and ReSTIR GI are combined to avoid loading GBuffer twice' @'
    // RTR initialize raw radiance before RTXDI accumulation
    if (m_ui.RealtimeMode && m_ui.RealtimeAA != 3
        && !m_ui.ActualUseStandaloneDenoiser() && m_ui.ActualUseRTXDIPasses())
    {
        GpuProfiler::Begin(m_commandList, "NoDenoiserFinalMerge");
        const auto& desc = m_renderTargets->OutputColor->getDesc();
        m_postProcess->Apply(m_commandList, PostProcess::ComputePassType::NoDenoiserFinalMerge,
            m_constantBuffer, miniConstants, m_bindingSet, m_bindingLayout, desc.width, desc.height);
        GpuProfiler::End(m_commandList);
    }

    // this is a performance optimization where final 2 passes from ReSTIR DI and ReSTIR GI are combined to avoid loading GBuffer twice
'@ 'RTR initialize raw radiance before RTXDI accumulation'
Patch-Once 'Rtxpt/Sample.cpp' 'if (m_ui.RealtimeAA != 3 && !m_ui.ActualUseStandaloneDenoiser())' @'
if (m_ui.RealtimeAA != 3 && !m_ui.ActualUseStandaloneDenoiser()
            && !m_ui.ActualUseRTXDIPasses()) // RTR preserve RTXDI accumulated radiance
'@ 'RTR preserve RTXDI accumulated radiance'
# The filtered hit-distance texture is consumed by NRD, RR guides and debug views.
# Scene-selected quality path: reuse RTXPT point sampling/shadow/BSDF and keep
# the residual-light PDFs and candidate budget coherent with exact delta sums.
[IO.File]::WriteAllText((Join-Path $sdk 'Rtxpt/Shaders/PathTracer/Lighting/RtrLightSamplerStrata.hlsli'),
    [IO.File]::ReadAllText((Join-Path $repo 'renderer-rtxpt/src/main/cpp/RtrLightSamplerStrata.hlsli')),$utf8)
Patch-Once 'Rtxpt/Shaders/PathTracer/Lighting/LightSampler.hlsli' '    // returned value is in [0, ControlBuffer[0].TotalLightCount) range used to read from LightsBuffer' @'
    #include "RtrLightSamplerStrata.hlsli" // RTR conditional environment stratum
    // returned value is in [0, ControlBuffer[0].TotalLightCount) range used to read from LightsBuffer
'@ 'RTR conditional environment stratum'
Patch-Once 'Rtxpt/Shaders/PathTracer/Lighting/LightSampler.hlsli' '        uint totalProxyCount = ControlBuffer[0].SamplingProxyCount;' @'
        uint totalProxyCount = ControlBuffer[0].SamplingProxyCount;
        #if defined(RTR_SPLIT_POINT_ENVIRONMENT) && RTR_SPLIT_POINT_ENVIRONMENT
        if (RtrSplitPointEnvironment()) totalProxyCount = RtrEnvironmentProxyCount(); // RTR conditional proxy range
        #endif
'@ 'RTR conditional proxy range'
Patch-Once 'Rtxpt/Shaders/PathTracer/Lighting/LightSampler.hlsli' '        ReadLocal(indexInIndex, lightIndex, proxyCount);' @'
        #if defined(RTR_SPLIT_POINT_ENVIRONMENT) && RTR_SPLIT_POINT_ENVIRONMENT
        if (RtrSplitPointEnvironment())
        {
            if (RtrEnvironmentLocalProxyCount() == 0) { pdf = 0; return NEEAT_INVALID_LIGHT_INDEX; }
            lightIndex = RtrSampleEnvironmentLocal(rnd, pdf); // RTR conditional NEE-AT local stratum
            return lightIndex;
        }
        #endif
        ReadLocal(indexInIndex, lightIndex, proxyCount);
'@ 'RTR conditional NEE-AT local stratum'
Patch-Once 'Rtxpt/Shaders/PathTracer/Lighting/LightSampler.hlsli' '        float pdf = proxyCountPerLight / float(float(ControlBuffer[0].SamplingProxyCount));' @'
        uint totalProxyCount = ControlBuffer[0].SamplingProxyCount;
        #if defined(RTR_SPLIT_POINT_ENVIRONMENT) && RTR_SPLIT_POINT_ENVIRONMENT
        if (RtrSplitPointEnvironment())
        {
            if (lightIndex >= ControlBuffer[0].EnvmapQuadNodeCount) return 0;
            totalProxyCount = RtrEnvironmentProxyCount();
        }
        #endif
        float pdf = totalProxyCount > 0 ? proxyCountPerLight / float(totalProxyCount) : 0; // RTR matching conditional BSDF PDF
'@ 'RTR matching conditional BSDF PDF'
Patch-Once 'Rtxpt/Shaders/PathTracer/Lighting/LightSampler.hlsli' '        float globalPDFModifier = clamp( globalPdf, 1e-6, 1e6 );' @'
        float feedbackPdf = globalPdf;
        #if defined(RTR_SPLIT_POINT_ENVIRONMENT) && RTR_SPLIT_POINT_ENVIRONMENT
        if (RtrSplitPointEnvironment() && lightIndex < ControlBuffer[0].EnvmapQuadNodeCount)
            feedbackPdf = RtrNativeGlobalPdfForFeedback(lightIndex);
        #endif
        float globalPDFModifier = clamp( feedbackPdf, 1e-6, 1e6 ); // RTR preserve native feedback PDF semantics
'@ 'RTR preserve native feedback PDF semantics'
Patch-Once 'Rtxpt/Shaders/PathTracer/Lighting/LightSampler.hlsli' '        uint packedValue = LocalLightBinarySearch( LocalSamplingBuffer, LocalSamplingTilePos, lightIndex, localProxyCount, RTXPT_LIGHTING_LOCAL_PROXY_BINARY_SEARCH_STEPS );' @'
        #if defined(RTR_SPLIT_POINT_ENVIRONMENT) && RTR_SPLIT_POINT_ENVIRONMENT
        if (RtrSplitPointEnvironment())
        {
            if (lightIndex >= ControlBuffer[0].EnvmapQuadNodeCount) return 0;
            const uint environmentProxyCount = RtrEnvironmentLocalProxyCount();
            if (environmentProxyCount == 0) return 0;
            uint environmentPacked = LocalLightBinarySearch(LocalSamplingBuffer, LocalSamplingTilePos,
                lightIndex, RTXPT_LIGHTING_LOCAL_PROXY_COUNT, RTXPT_LIGHTING_LOCAL_PROXY_BINARY_SEARCH_STEPS);
            if (environmentPacked == NEEAT_INVALID_LIGHT_INDEX) return 0;
            uint environmentIndex, environmentLightProxies;
            NEEAT::UnpackMiniListLightAndCount(environmentPacked, environmentIndex, environmentLightProxies);
            return float(environmentLightProxies) / float(environmentProxyCount);
        }
        #endif
        uint packedValue = LocalLightBinarySearch( LocalSamplingBuffer, LocalSamplingTilePos, lightIndex, localProxyCount, RTXPT_LIGHTING_LOCAL_PROXY_BINARY_SEARCH_STEPS ); // RTR conditional local MIS PDF fallback
'@ 'RTR conditional local MIS PDF'
[IO.File]::WriteAllText((Join-Path $sdk 'Rtxpt/Shaders/PathTracer/RtrSmallPointNee.hlsli'),
    [IO.File]::ReadAllText((Join-Path $repo 'renderer-rtxpt/src/main/cpp/RtrSmallPointNee.hlsli')),$utf8)
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '    inline NEEResult HandleNEE_MultipleSamples(' @'
    #include "RtrSmallPointNee.hlsli" // RTR opt-in exact small-point sum
    inline NEEResult HandleNEE_MultipleSamples(
'@ 'RTR opt-in exact small-point sum'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '                                inout UniformSampleSequenceGenerator sampleGenerator, const WorkingContext workingContext)' @'
                                inout UniformSampleSequenceGenerator sampleGenerator, const WorkingContext workingContext, const bool updateNeeAtFeedback) // RTR feedback policy parameter
'@ 'RTR feedback policy parameter'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '            if( lightSample.LightIndex != NEEAT_INVALID_LIGHT_INDEX && lightSampler.IsTemporalFeedbackRequired() )' @'
            if( updateNeeAtFeedback && lightSample.LightIndex != NEEAT_INVALID_LIGHT_INDEX && lightSampler.IsTemporalFeedbackRequired() ) // RTR exact lights do not train NEE-AT
'@ 'RTR exact lights do not train NEE-AT'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '        [unroll] for (uint sampleIndex = 0; sampleIndex < fullSamples; sampleIndex++)' @'
        // RTR deterministic delta contributions; do not add them again below.
        #if defined(RTR_EXACT_SMALL_POINTS) && RTR_EXACT_SMALL_POINTS
        const bool rtrSmallPoints = RtrSmallPointSet(lightSampler);
        if (rtrSmallPoints)
            RtrSumPointLights(result, lightSampler, shadingData, bsdf, preScatterPath, sampleGenerator, workingContext);
        #endif
        [unroll] for (uint sampleIndex = 0; sampleIndex < fullSamples; sampleIndex++)
'@ 'RTR deterministic delta contributions'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '        lightSampler.GetCandidateSampleCounts(candidateSampleCount, localCount, globalCount);' @'
        // RTR conditional NEE sample budget
        lightSampler.GetCandidateSampleCounts(candidateSampleCount, localCount, globalCount);
        #if defined(RTR_SPLIT_POINT_ENVIRONMENT) && RTR_SPLIT_POINT_ENVIRONMENT
        if (lightSampler.RtrSplitPointEnvironment())
        {
            const uint environmentProxyCount = lightSampler.RtrEnvironmentProxyCount();
            const uint localEnvironmentProxyCount = lightSampler.RtrEnvironmentLocalProxyCount();
            if (localEnvironmentProxyCount == 0) { globalCount += localCount; localCount = 0; }
            else if (environmentProxyCount == 0) { localCount += globalCount; globalCount = 0; }
        }
        #endif
'@ 'RTR conditional NEE sample budget'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '            RtrSumPointLights(result, lightSampler, shadingData, bsdf, preScatterPath, sampleGenerator, workingContext);' @'
            RtrSumPointLights(result, lightSampler, shadingData, bsdf, preScatterPath, sampleGenerator, workingContext);
        #if defined(RTR_SPLIT_POINT_ENVIRONMENT) && RTR_SPLIT_POINT_ENVIRONMENT
        if (rtrSmallPoints && lightSampler.RtrSplitPointEnvironment()
            && lightSampler.RtrEnvironmentProxyCount() == 0 && lightSampler.RtrEnvironmentLocalProxyCount() == 0)
            return result; // RTR no stochastic stratum: avoid zero-sized proxy lookup
        #endif
'@ 'RTR no stochastic stratum'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '            // this computes the BSDF throughput and (if throughput>0) then casts shadow ray and handles radiance summing up & weighted averaging for ''sample distance'' used by denoiser' @'
            #if defined(RTR_EXACT_SMALL_POINTS) && RTR_EXACT_SMALL_POINTS
            if (rtrSmallPoints && lightSample.Valid() && RtrCoveredPoint(lightSampler, lightSample.LightIndex))
                continue; // RTR avoid double-counting exact point contributions
            #endif
            // this computes the BSDF throughput and (if throughput>0) then casts shadow ray and handles radiance summing up & weighted averaging for 'sample distance' used by denoiser
'@ 'RTR avoid double-counting exact point contributions'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '            ProcessLightSample(result, lightSample, candidateSampleCount, fullSamples, localCount, globalCount, shadingData, bsdf, preScatterPath, lightSampler, sampleGenerator, workingContext);' @'
            ProcessLightSample(result, lightSample, candidateSampleCount, fullSamples, localCount, globalCount, shadingData, bsdf, preScatterPath, lightSampler, sampleGenerator, workingContext, true); // RTR sampled candidates train NEE-AT
'@ 'RTR sampled candidates train NEE-AT'
# Keep exact delta points out of both NEE-AT strata and use matching conditional PDFs.
Patch-Once 'Rtxpt/Shaders/PathTracer/Lighting/LightSampler.hlsli' '        globalCount = totalCandidateSamples - localCount;' @'
        globalCount = totalCandidateSamples - localCount;
        #if defined(RTR_EXACT_SMALL_POINTS) && RTR_EXACT_SMALL_POINTS && defined(RTR_CONDITIONAL_POINT_SAMPLING) && RTR_CONDITIONAL_POINT_SAMPLING
        if (RtrSmallPointSet())
        {
            const uint residualGlobal = RtrResidualProxyCount();
            uint residualLocal = 0;
            if (localCount > 0) residualLocal = RtrResidualLocalSlotCount();
            if (residualLocal == 0 && residualGlobal == 0)
            {
                localCount = 0;
                globalCount = 0;
            }
            else
            {
                if (localCount > 0 && residualLocal == 0) { globalCount += localCount; localCount = 0; }
                if (globalCount > 0 && residualGlobal == 0) { localCount += globalCount; globalCount = 0; }
            }
        }
        #endif // RTR residual candidate counts
'@ 'RTR residual candidate counts'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' 'const LightSampler lightSampler)' @'
const LightSampler lightSampler, const bool conditionalPointSampling,
const uint residualGlobalProxyCount, const uint residualLocalSlotCount) // RTR residual sample parameters
'@ 'RTR residual sample parameters'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' @'
            if( sampleIsLocal )
                lightIndex = lightSampler.SampleLocal( rnd, selectionPdf );
            else
                lightIndex = lightSampler.SampleGlobal( rnd, selectionPdf );
'@ @'
            if( sampleIsLocal )
            {
                if (conditionalPointSampling && residualLocalSlotCount < RTXPT_LIGHTING_LOCAL_PROXY_COUNT)
                    lightIndex = lightSampler.RtrSampleResidualLocal(rnd, residualLocalSlotCount, selectionPdf);
                else
                    lightIndex = lightSampler.SampleLocal(rnd, selectionPdf);
            }
            else if (conditionalPointSampling && residualGlobalProxyCount < lightSampler.ControlBuffer[0].SamplingProxyCount)
                lightIndex = lightSampler.RtrSampleResidualGlobal(rnd, residualGlobalProxyCount, selectionPdf);
            else
                lightIndex = lightSampler.SampleGlobal(rnd, selectionPdf);
            // RTR conditional residual selection
'@ 'RTR conditional residual selection'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '            lightSampler.ComputeLightSelectionPdfs(lightSample, localCount, globalCount, thisPdf, otherPdf, thisCount, otherCount);' @'
            bool conditionalPointSampling = false;
            #if defined(RTR_EXACT_SMALL_POINTS) && RTR_EXACT_SMALL_POINTS && defined(RTR_CONDITIONAL_POINT_SAMPLING) && RTR_CONDITIONAL_POINT_SAMPLING
            conditionalPointSampling = lightSampler.RtrSmallPointSet();
            #endif
            lightSampler.RtrComputeLightSelectionPdfs(lightSample, localCount, globalCount, conditionalPointSampling,
                thisPdf, otherPdf, thisCount, otherCount); // RTR matching residual NEE MIS PDFs
'@ 'RTR matching residual NEE MIS PDFs'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '        result.BSDFMISInfo.FullSamples          = fullSamples;' @'
        result.BSDFMISInfo.FullSamples          = fullSamples;
        bool rtrSmallPoints = false;
        bool rtrConditionalPointSampling = false;
        uint rtrResidualGlobalProxyCount = 0;
        uint rtrResidualLocalSlotCount = 0; // RTR residual sampler state
'@ 'RTR residual sampler state'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '        const bool rtrSmallPoints = RtrSmallPointSet(lightSampler);' @'
        rtrSmallPoints = RtrSmallPointSet(lightSampler);
        #if defined(RTR_CONDITIONAL_POINT_SAMPLING) && RTR_CONDITIONAL_POINT_SAMPLING
        rtrConditionalPointSampling = rtrSmallPoints;
        if (rtrConditionalPointSampling)
        {
            rtrResidualGlobalProxyCount = lightSampler.RtrResidualProxyCount();
            rtrResidualLocalSlotCount = lightSampler.RtrResidualLocalSlotCount();
        }
        #endif // RTR residual point activation
'@ 'RTR residual point activation'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '            rtrResidualLocalSlotCount = lightSampler.RtrResidualLocalSlotCount();' @'
            // Power sampling has no local table; never read stale NEE-AT slots.
            if (localCount > 0) rtrResidualLocalSlotCount = lightSampler.RtrResidualLocalSlotCount(); // RTR only active local stratum
'@ 'RTR only active local stratum'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '            RtrSumPointLights(result, lightSampler, shadingData, bsdf, preScatterPath, sampleGenerator, workingContext);' @'
            RtrSumPointLights(result, lightSampler, shadingData, bsdf, preScatterPath, sampleGenerator, workingContext);
        if (rtrConditionalPointSampling && rtrResidualGlobalProxyCount == 0 && rtrResidualLocalSlotCount == 0)
            return result; // RTR every stochastic proxy belongs to an exactly summed point
'@ 'RTR every stochastic proxy belongs to an exactly summed point'
# Repair repeated insertions made by the old marker, which was absent from its
# own replacement. Keep the generated shader stable across repeated builds.
$neePath=Join-Path $sdk 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli'
$neeText=[IO.File]::ReadAllText($neePath)
$emptyResidual='        if \(rtrConditionalPointSampling && rtrResidualGlobalProxyCount == 0 && rtrResidualLocalSlotCount == 0\)\r?\n            return result; // RTR every stochastic proxy belongs to an exactly summed point'
$neeText=[regex]::Replace($neeText,'('+ $emptyResidual +')(?:\r?\n'+ $emptyResidual +')+','$1')
[IO.File]::WriteAllText($neePath,$neeText,$utf8)
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' 'GenerateLightSample(workingContext, shadingData, bsdf, candidateSampleCount, localCount, globalCount, sampleGenerator, lightSampler);' @'
GenerateLightSample(workingContext, shadingData, bsdf, candidateSampleCount, localCount, globalCount,
                sampleGenerator, lightSampler, rtrConditionalPointSampling, rtrResidualGlobalProxyCount, rtrResidualLocalSlotCount); // RTR residual sample call
'@ 'RTR residual sample call'
Patch-Once 'Rtxpt/Shaders/PathTracer/Lighting/LightSampler.hlsli' '        float globalPdf = SampleGlobalPDF(lightIndex);' @'
        bool conditionalPointSampling = false;
        #if defined(RTR_EXACT_SMALL_POINTS) && RTR_EXACT_SMALL_POINTS && defined(RTR_CONDITIONAL_POINT_SAMPLING) && RTR_CONDITIONAL_POINT_SAMPLING
        conditionalPointSampling = RtrSmallPointSet();
        #endif
        float globalPdf = RtrSampleGlobalPDF(lightIndex, conditionalPointSampling); // RTR matching residual BSDF global PDF
'@ 'RTR matching residual BSDF global PDF'
Patch-Once 'Rtxpt/Shaders/PathTracer/Lighting/LightSampler.hlsli' '        float localPdf = (localCount>0)?(SampleLocalPDF(lightIndex)):(0);' @'
        float localPdf = (localCount>0)?(RtrSampleLocalPDF(lightIndex, conditionalPointSampling)):(0); // RTR matching residual BSDF local PDF
'@ 'RTR matching residual BSDF local PDF'
Patch-Once 'Rtxpt/Shaders/PathTracer/Lighting/LightSampler.hlsli' '        float feedbackPdf = globalPdf;' @'
        float feedbackPdf = globalPdf;
        #if defined(RTR_EXACT_SMALL_POINTS) && RTR_EXACT_SMALL_POINTS && defined(RTR_CONDITIONAL_POINT_SAMPLING) && RTR_CONDITIONAL_POINT_SAMPLING
        if (RtrSmallPointSet()) feedbackPdf = RtrNativeGlobalPdfForFeedback(lightIndex);
        #endif // RTR restore native NEE-AT feedback weights
'@ 'RTR native residual feedback weights'
# DirectionalLight::CalcSample returns a to-light direction, not a world position.
# Match the pinned upstream IntroRTXDIBridge::RAB_GetLightDirDistance convention.
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '            candidateSample.Direction = surfToLight / max( candidateSample.Distance, 1e-7 );' @'
            candidateSample.Direction = surfToLight / max( candidateSample.Distance, 1e-7 );
            // RTR directional samples carry directions, not world positions.
            #if !defined(RTR_ORIGINAL_DIRECTIONAL_POSITION)
            if (PolymorphicLight::DecodeType(packedLightInfo) == PolymorphicLightType::kDirectional)
            {
                candidateSample.Direction = lightSample.Position;
                candidateSample.Distance = 1e6;
            }
            #endif
'@ 'RTR directional samples carry directions'
# Match the legacy route's summed continuous surface response without deleting
# Full legacy lighting-input parity uses the same scene values at every receiver.
foreach($header in @('RtrLegacyLightingInputs.hlsli','RtrLegacyDirect.hlsli','RtrLegacyTransportTrace.hlsli')) {
    $headerText=[IO.File]::ReadAllText((Join-Path $repo ('renderer-rtxpt/src/main/cpp/'+$header)))
    if($header -eq 'RtrLegacyTransportTrace.hlsli' -and $OpticalTransportTrace) {
        $headerText="#define RTR_LEGACY_TRANSPORT_TRACE 1 // explicitly enabled by configure`r`n"+$headerText
    }
    [IO.File]::WriteAllText((Join-Path $sdk ('Rtxpt/Shaders/PathTracer/'+$header)),$headerText,$utf8)
}
# Legacy lighting performs cross-material queries inside a receiver shader. Do
# not let future material-specific compile constants discard another hit's data.
Patch-Once 'Rtxpt/Shaders/PathTracer/Config.h' '#define __CONFIG_H__' @'
#define __CONFIG_H__
#if defined(RTR_LEGACY_LIGHTING) && RTR_LEGACY_LIGHTING
#undef RTXPT_MATERIAL_HAS_TRANSMISSION
#undef RTXPT_MATERIAL_USE_NORMAL_TEXTURE
#endif // RTR cross-material lighting queries require runtime material data
'@ 'RTR cross-material lighting queries require runtime material data'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerShared.h' '    PathTracerCameraData camera;' @'
    float4  RtrLegacyAmbient;
    float4  RtrLegacySky;
    uint    RtrLegacyLightCount;
    uint3   RtrLegacyPadding; // RTR complete legacy lighting inputs
    PathTracerCameraData camera;
'@ 'RTR complete legacy lighting inputs'
Patch-Once 'Rtxpt/Shaders/Bindings/ShaderResourceBindings.hlsli' '#endif // #ifndef __SHADER_RESOURCE_BINDINGS_HLSLI__' @'
struct RtrLegacyLight { float4 PositionType; float4 DirectionRange; float4 ColorIntensity; float4 ConeShadow; };
StructuredBuffer<RtrLegacyLight> t_RtrLegacyLights : register(t96); // RTR original API light records
#endif // #ifndef __SHADER_RESOURCE_BINDINGS_HLSLI__
'@ 'RTR original API light records'
Patch-Once 'Rtxpt/Sample.cpp' '    globalBindingLayoutDesc.bindings.push_back(nvrhi::BindingLayoutItem::Texture_SRV(95));' @'
    globalBindingLayoutDesc.bindings.push_back(nvrhi::BindingLayoutItem::StructuredBuffer_SRV(96)); // RTR original lighting buffer layout
    globalBindingLayoutDesc.bindings.push_back(nvrhi::BindingLayoutItem::Texture_SRV(95));
'@ 'RTR original lighting buffer layout'
Patch-Once 'Rtxpt/Sample.cpp' '        bindingSetDesc.bindings.push_back(nvrhi::BindingSetItem::Texture_SRV(95, m_CommonPasses->m_BlackTexture));' @'
        bindingSetDesc.bindings.push_back(nvrhi::BindingSetItem::StructuredBuffer_SRV(96, m_lightsBaker->GetLightBuffer())); // RTR original lighting buffer placeholder
        bindingSetDesc.bindings.push_back(nvrhi::BindingSetItem::Texture_SRV(95, m_CommonPasses->m_BlackTexture));
'@ 'RTR original lighting buffer placeholder'
Patch-Once 'Rtxpt/Shaders/PathTracer/BSDFs/StandardBSDF.hlsli' '#endif // __STANDARD_BSDF_HLSLI__' @'
#if defined(RTR_LEGACY_LIGHTING) && RTR_LEGACY_LIGHTING
#include "../RtrLegacyLightingInputs.hlsli" // RTR legacy sampling and sky input
#endif
#endif // __STANDARD_BSDF_HLSLI__
'@ 'RTR legacy sampling and sky input'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracer.hlsli' '#include "PathTracerTypes.hlsli"' @'
#include "PathTracerTypes.hlsli"
#if defined(RTR_LEGACY_LIGHTING) && RTR_LEGACY_LIGHTING
#include "RtrLegacyTransportTrace.hlsli" // RTR shared trace in raygen and hit shaders
#endif
'@ 'RTR shared trace in raygen and hit shaders'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '    inline NEEResult HandleNEE(const PathState preScatterPath,' @'
    #if defined(RTR_LEGACY_LIGHTING) && RTR_LEGACY_LIGHTING
    #include "RtrLegacyDirect.hlsli" // RTR legacy direct shading at every receiver
    #endif
    inline NEEResult HandleNEE(const PathState preScatterPath,
'@ 'RTR legacy direct shading at every receiver'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '        LightSampler lightSampler = Bridge::CreateLightSampler( preScatterPath.GetPixelPos(), preScatterPath.rayCone.getWidth(), preScatterPath.GetSceneLength() );' @'
        #if defined(RTR_LEGACY_LIGHTING) && RTR_LEGACY_LIGHTING
        return RtrLegacyDirect(preScatterPath,shadingData,bsdf,workingContext); // RTR replace complete stochastic light input
        #endif
        LightSampler lightSampler = Bridge::CreateLightSampler( preScatterPath.GetPixelPos(), preScatterPath.rayCone.getWidth(), preScatterPath.GetSceneLength() );
'@ 'RTR replace complete stochastic light input'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerHandleHit.hlsli' '        // only ActiveBSDF::cRandomNumberCountForSampling are actually used;' @'
        #if defined(RTR_LEGACY_LIGHTING) && RTR_LEGACY_LIGHTING
        BSDFSample legacySample=(BSDFSample)0;bool legacyValid=false;
        if(bsdf.data.SpecularTransmission()>0)
        {
            #if PATH_TRACER_MODE==PT_DENOISING_LAYERS_FILL
            if(path.getVertexIndex()==1)
            {
                // gsEvaluateSecondaryTransport traces both first-interface branches.
                // PSD queues alone contain guides, not completed lighting paths.
                path.setCounter((PackedCounters)3,path.getCounter((PackedCounters)3)|4u);
                DeltaLobe lobes[cMaxDeltaLobes];uint count;float nonDeltaPart;
                RtrLegacyFirstInterface(shadingData,bsdf.data,path.GetPixelPos(),workingContext.PtConsts.frameIndex,g_MiniConst.params.x,
                    uint(max(1.0,rcp(workingContext.PtConsts.invSubSampleCount))),lobes,count,nonDeltaPart);
                uint selected=2u-(path.getCounter((PackedCounters)3)&3u);
                legacySample=(BSDFSample)0;legacySample.wo=lobes[selected].dir;legacySample.weight=lobes[selected].thp;
                legacySample.lobe=(uint)(selected==0?LobeType::DeltaTransmission:LobeType::DeltaReflection);
                legacySample.lobeP=lobes[selected].probability;
                legacyValid=any(legacySample.weight>0);
            }
            else
            #endif
                legacyValid=RtrLegacyOpticalSample(shadingData,bsdf.data,path.GetPixelPos(),workingContext.PtConsts.frameIndex,
                    path.getVertexIndex(),max(1u,path.getCounter((PackedCounters)3)&3u),legacySample);
        }
        else if(path.getVertexIndex()==1)
            legacyValid=RtrLegacyMetalSample(shadingData,bsdf.data,path.GetPixelPos(),g_MiniConst.params.x,
                uint(max(1.0,rcp(workingContext.PtConsts.invSubSampleCount))),legacySample);
        return legacyValid && GenerateScatterRay(legacySample,shadingData,bsdf,path,workingContext); // RTR legacy optical continuation
        #endif
        // only ActiveBSDF::cRandomNumberCountForSampling are actually used;
'@ 'RTR legacy optical continuation'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerHandleHit.hlsli' '        return legacyValid && GenerateScatterRay(legacySample,shadingData,bsdf,path,workingContext); // RTR legacy optical continuation' @'
        #if defined(RTR_LEGACY_TRANSPORT_TRACE) && RTR_LEGACY_TRANSPORT_TRACE
        if(RtrLegacyTracePixel(path.GetPixelPos(),workingContext.PtConsts.frameIndex) && ((path.getCounter((PackedCounters)3)&4u)!=0 || bsdf.data.SpecularTransmission()>0))
        {
            RtrLegacyTracePrint("scatter",100u,RtrLegacyTraceKey(path.GetPixelPos(),(path.getCounter((PackedCounters)3)&3u)-1u,path.getVertexIndex(),workingContext.PtConsts.frameIndex),
                float4(shadingData.MaterialID(),shadingData.frontFacing,shadingData.GetEta(),legacyValid),path.GetThp(),shadingData.N,legacySample.wo,legacySample.weight,legacySample.lobe);
        }
        #endif // RTR optional optical transport trace
        return legacyValid && GenerateScatterRay(legacySample,shadingData,bsdf,path,workingContext); // RTR legacy optical continuation
'@ 'RTR optional optical transport trace'
$rtrTracePath=Join-Path $sdk 'Rtxpt/Shaders/PathTracer/PathTracerHandleHit.hlsli'
$rtrTraceText=[IO.File]::ReadAllText($rtrTracePath)
$rtrTraceText=$rtrTraceText.Replace('uint4(path.GetPixelPos(),(path.getCounter((PackedCounters)3)&3u)-1u,path.getVertexIndex())','RtrLegacyTraceKey(path.GetPixelPos(),(path.getCounter((PackedCounters)3)&3u)-1u,path.getVertexIndex(),workingContext.PtConsts.frameIndex)')
$rtrTraceText=$rtrTraceText.Replace('BSDFSample legacySample;bool legacyValid=false;','BSDFSample legacySample=(BSDFSample)0;bool legacyValid=false;')
$rtrTraceText=$rtrTraceText.Replace('(path.getCounter((PackedCounters)3)>0 || bsdf.data.SpecularTransmission()>0)', '((path.getCounter((PackedCounters)3)&4u)!=0 || bsdf.data.SpecularTransmission()>0)')
$rtrTraceText=$rtrTraceText.Replace('DebugPrint("RTR_TRANSPORT','RtrLegacyTracePrint("RTR_TRANSPORT')
$rtrTraceText=[regex]::Replace($rtrTraceText,'(?s)            RtrLegacyTracePrint\("RTR_TRANSPORT scatter.*?legacySample\.lobe\);',@'
            RtrLegacyTracePrint("scatter",100u,RtrLegacyTraceKey(path.GetPixelPos(),(path.getCounter((PackedCounters)3)&3u)-1u,path.getVertexIndex(),workingContext.PtConsts.frameIndex),
                float4(shadingData.MaterialID(),shadingData.frontFacing,shadingData.GetEta(),legacyValid),path.GetThp(),shadingData.N,legacySample.wo,legacySample.weight,legacySample.lobe);
'@)
[IO.File]::WriteAllText($rtrTracePath,$rtrTraceText,$utf8)
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerDenoisingLayersHandleHit.hlsli' '            surface.bsdf.evalDeltaLobes(surface.shadingData, deltaLobes, deltaLobeCount, nonDeltaPart);' @'
            #if defined(RTR_LEGACY_LIGHTING) && RTR_LEGACY_LIGHTING
            if(vertexIndex==1 && surface.bsdf.data.SpecularTransmission()>0)
            {
                deltaLobeCount=0;nonDeltaPart=1; // RTR first interface stays the fill starting point
            }
            else
            #endif
            surface.bsdf.evalDeltaLobes(surface.shadingData, deltaLobes, deltaLobeCount, nonDeltaPart);
'@ 'RTR first interface stays the fill starting point'
# Older integrations used a marker absent from the replacement, nesting this
# conditional once per configure. Keep one copy, including in existing SDKs.
$rtrLayerPath=Join-Path $sdk 'Rtxpt/Shaders/PathTracer/PathTracerDenoisingLayersHandleHit.hlsli'
$rtrLayerText=[IO.File]::ReadAllText($rtrLayerPath)
$rtrLayerBlock=@'
            #if defined(RTR_LEGACY_LIGHTING) && RTR_LEGACY_LIGHTING
            if(vertexIndex==1 && surface.bsdf.data.SpecularTransmission()>0)
            {
                deltaLobeCount=0;nonDeltaPart=1; // RTR first interface stays the fill starting point
            }
            else
            #endif
'@
$rtrLayerBlock=[regex]::Replace($rtrLayerBlock,"`r?`n","`r`n")+"`r`n"
while($rtrLayerText.Contains($rtrLayerBlock+$rtrLayerBlock)) {
    $rtrLayerText=$rtrLayerText.Replace($rtrLayerBlock+$rtrLayerBlock,$rtrLayerBlock)
}
[IO.File]::WriteAllText($rtrLayerPath,$rtrLayerText,$utf8)
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracer.hlsli' '    workingContext.DenoisingLayers.CommitDenoiserRadiance(path);' @'
    #if defined(RTR_LEGACY_TRANSPORT_TRACE) && RTR_LEGACY_TRANSPORT_TRACE
    float3 rtrBeforeCommit=0;
    bool rtrTraceCommit=RtrLegacyTracePixel(path.GetPixelPos(),workingContext.PtConsts.frameIndex) && (path.getCounter((PackedCounters)3)&4u)!=0;
    if(rtrTraceCommit)rtrBeforeCommit=workingContext.DenoisingLayers.GetAllRadiance(path.GetPixelPos());
    #endif
    workingContext.DenoisingLayers.CommitDenoiserRadiance(path);
    #if defined(RTR_LEGACY_TRANSPORT_TRACE) && RTR_LEGACY_TRANSPORT_TRACE
    if(rtrTraceCommit)
        RtrLegacyTracePrint("commit",102u,RtrLegacyTraceKey(path.GetPixelPos(),(path.getCounter((PackedCounters)3)&3u)-1u,path.getVertexIndex(),workingContext.PtConsts.frameIndex),
            path.getDenoisingLayerIndex(),rtrBeforeCommit,workingContext.DenoisingLayers.GetAllRadiance(path.GetPixelPos()));
    #endif // RTR optional final optical radiance trace
'@ 'RTR optional final optical radiance trace'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracer.hlsli' '                path.SetL( L + newL );' @'
                path.SetL( L + newL );
                #if defined(RTR_LEGACY_TRANSPORT_TRACE) && RTR_LEGACY_TRANSPORT_TRACE
                if(RtrLegacyTracePixel(path.GetPixelPos(),workingContext.PtConsts.frameIndex) && (path.getCounter((PackedCounters)3)&4u)!=0)
                    RtrLegacyTracePrint("accumulate",107u,RtrLegacyTraceKey(path.GetPixelPos(),(path.getCounter((PackedCounters)3)&3u)-1u,path.getVertexIndex(),workingContext.PtConsts.frameIndex),
                        float4(radiance,specularRadianceAvg),L,newL,path.GetL(),uint2(path.flagsAndVertexIndex,path.packedCounters));
                #endif // RTR optical contribution before and after accumulation
'@ 'RTR optical contribution before and after accumulation'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracer.hlsli' '        SharcHandleMiss(environmentEmission);' @'
        #if defined(RTR_LEGACY_TRANSPORT_TRACE) && RTR_LEGACY_TRANSPORT_TRACE
        if(RtrLegacyTracePixel(path.GetPixelPos(),workingContext.PtConsts.frameIndex) && (path.getCounter((PackedCounters)3)&4u)!=0)
            RtrLegacyTracePrint("miss",108u,RtrLegacyTraceKey(path.GetPixelPos(),(path.getCounter((PackedCounters)3)&3u)-1u,path.getVertexIndex(),workingContext.PtConsts.frameIndex),
                rayDir,path.GetThp(),float3(environmentEmission),path.GetL(),uint2(path.flagsAndVertexIndex,path.packedCounters));
        #endif // RTR optical miss emission evidence
        SharcHandleMiss(environmentEmission);
'@ 'RTR optical miss emission evidence'
Patch-Once 'Rtxpt/ProcessingPasses/PostProcess.hlsl' '#include "../Shaders/Libraries/ShaderDebug/ShaderDebug.hlsl"' @'
#include "../Shaders/Libraries/ShaderDebug/ShaderDebug.hlsl"
#include "../Shaders/PathTracer/RtrLegacyTransportTrace.hlsli" // RTR final merge trace
'@ 'RTR final merge trace'
Patch-Once 'Rtxpt/ProcessingPasses/PostProcess.hlsl' '    u_OutputColor[pixelPos] = float4(denoisingLayers.GetAllRadiance(pixelPos), 1.0);' @'
    u_OutputColor[pixelPos] = float4(denoisingLayers.GetAllRadiance(pixelPos), 1.0);
    #if defined(RTR_LEGACY_TRANSPORT_TRACE) && RTR_LEGACY_TRANSPORT_TRACE
    if(g_Const.ptConsts.RtrLegacyLightCount>0 && RtrLegacyTracePixel(pixelPos,g_Const.ptConsts.frameIndex))
        RtrLegacyTracePrint("merge",109u,RtrLegacyTraceKey(pixelPos,0,0,g_Const.ptConsts.frameIndex),
            denoisingLayers.GetAllRadiance(pixelPos),u_OutputColor[pixelPos],g_Const.ptConsts.invSubSampleCount);
    #endif // RTR final radiance at SR input
'@ 'RTR final radiance at SR input'
Patch-Once 'Rtxpt/ProcessingPasses/PostProcess.hlsl' '    u_OutputColor[pixelPos] = float4(denoisingLayers.GetAllRadiance(pixelPos), 1.0);' @'
    u_OutputColor[pixelPos] = float4(denoisingLayers.GetAllRadiance(pixelPos), 1.0);
    #if defined(RTR_LEGACY_TRANSPORT_TRACE) && RTR_LEGACY_TRANSPORT_TRACE
    if(all(pixelPos==uint2(0,0)))
        RtrLegacyTracePrint("merge-stage",110u,uint4(g_Const.ptConsts.frameIndex,g_Const.ptConsts.imageWidth,g_Const.ptConsts.imageHeight,g_Const.ptConsts.RtrLegacyLightCount),
            u_OutputColor[pixelPos],g_Const.ptConsts.invSubSampleCount);
    #endif // RTR verify final merge invocation and constants
'@ 'RTR verify final merge invocation and constants'
$rtrCommitPath=Join-Path $sdk 'Rtxpt/Shaders/PathTracer/PathTracer.hlsli'
$rtrCommitText=[IO.File]::ReadAllText($rtrCommitPath)
$rtrCommitText=$rtrCommitText.Replace('#if defined(RTR_LEGACY_TRANSPORT_TRACE) && RTR_LEGACY_TRANSPORT_TRACE', '#if defined(RTR_LEGACY_TRANSPORT_TRACE) && RTR_LEGACY_TRANSPORT_TRACE && PATH_TRACER_MODE!=PT_DENOISING_LAYERS_BUILD')
$rtrCommitText=$rtrCommitText.Replace('uint4(path.GetPixelPos(),(path.getCounter((PackedCounters)3)&3u)-1u,path.getVertexIndex())','RtrLegacyTraceKey(path.GetPixelPos(),(path.getCounter((PackedCounters)3)&3u)-1u,path.getVertexIndex(),workingContext.PtConsts.frameIndex)')
[IO.File]::WriteAllText($rtrCommitPath,$rtrCommitText,$utf8)
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerTypes.hlsli' '        RWTexture2D<float4>     OutputColor;' @'
        #if defined(RTR_LEGACY_LIGHTING) && RTR_LEGACY_LIGHTING
        uint RtrLegacyFirstBranch; // RTR actual first-interface branch selector
        #endif
        RWTexture2D<float4>     OutputColor;
'@ 'RTR actual first-interface branch selector'
Patch-Once 'Rtxpt/Shaders/PathTracerBridgeDonut.hlsli' '    ret.PtConsts = g_Const.ptConsts;' @'
    #if defined(RTR_LEGACY_LIGHTING) && RTR_LEGACY_LIGHTING
    ret.RtrLegacyFirstBranch=0; // RTR default branch context
    #endif
    ret.PtConsts = g_Const.ptConsts;
'@ 'RTR default branch context'
Patch-Once 'Rtxpt/Shaders/PathTracerSample.hlsl' '    //float3 lastOrigin = float3(1e30f,1e30f,1e30f);' @'
    #if defined(RTR_LEGACY_LIGHTING) && RTR_LEGACY_LIGHTING && PATH_TRACER_MODE==PT_DENOISING_LAYERS_FILL
    [loop] for(uint rtrFirstBranch=0;rtrFirstBranch<2;rtrFirstBranch++) // RTR trace both real dielectric branches
    {
    #endif
    //float3 lastOrigin = float3(1e30f,1e30f,1e30f);
'@ 'RTR trace both real dielectric branches'
Patch-Once 'Rtxpt/Shaders/PathTracerSample.hlsl' '    PathTracer::WorkingContext workingContext = Bridge::GetWorkingContext();' @'
    PathTracer::WorkingContext workingContext = Bridge::GetWorkingContext();
    #if defined(RTR_LEGACY_LIGHTING) && RTR_LEGACY_LIGHTING && PATH_TRACER_MODE==PT_DENOISING_LAYERS_FILL
    path.setCounter((PackedCounters)3,rtrFirstBranch+1u); // RTR first-interface selector in ray payload
    #endif
'@ 'RTR first-interface selector'
Patch-Once 'Rtxpt/Shaders/PathTracerSample.hlsl' '    PathTracer::CommitPixel( path, workingContext );' @'
    PathTracer::CommitPixel( path, workingContext );
    #if defined(RTR_LEGACY_LIGHTING) && RTR_LEGACY_LIGHTING && PATH_TRACER_MODE==PT_DENOISING_LAYERS_FILL
    // Counter 3 is unused upstream. Only first-interface dielectrics set it;
    // opaque pixels keep their single path and never repeat direct lighting.
    if((path.getCounter((PackedCounters)3)&4u)==0)break;
    } // RTR first-interface paths committed and summed
    #endif
'@ 'RTR first-interface paths committed and summed'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerHandleHit.hlsli' '        if (!hasSharcCachedRadiance && any(surfaceEmission>0))' @'
        #if defined(RTR_LEGACY_LIGHTING) && RTR_LEGACY_LIGHTING
        if(path.getVertexIndex()==1 && (path.getCounter((PackedCounters)3)&3u)==2u)surfaceEmission=0; // RTR shared primary emission once
        #endif
        if (!hasSharcCachedRadiance && any(surfaceEmission>0))
'@ 'RTR shared primary emission once'
# Migrate already-patched SDKs. WorkingContext is recreated by closest-hit;
# only PathPayload survives SER_INVOKE_HIT/TraceRay shader boundaries.
$rtrPayloadMigrations=@{
    'Rtxpt/Shaders/PathTracer/PathTracerHandleHit.hlsli'=@(
        @('path.setCounter((PackedCounters)3,workingContext.RtrLegacyFirstBranch+1);','path.setCounter((PackedCounters)3,path.getCounter((PackedCounters)3)|4u);'),
        @('uint selected=1-workingContext.RtrLegacyFirstBranch;','uint selected=2u-(path.getCounter((PackedCounters)3)&3u);'),
        @('max(1u,path.getCounter((PackedCounters)3)),legacySample);','max(1u,path.getCounter((PackedCounters)3)&3u),legacySample);'),
        @('workingContext.RtrLegacyFirstBranch>0','(path.getCounter((PackedCounters)3)&3u)==2u'),
        @('uint4(path.GetPixelPos(),workingContext.RtrLegacyFirstBranch,path.getVertexIndex())','RtrLegacyTraceKey(path.GetPixelPos(),(path.getCounter((PackedCounters)3)&3u)-1u,path.getVertexIndex(),workingContext.PtConsts.frameIndex)'))
    'Rtxpt/Shaders/PathTracerSample.hlsl'=@(
        @('workingContext.RtrLegacyFirstBranch=rtrFirstBranch; // RTR first-interface selector','path.setCounter((PackedCounters)3,rtrFirstBranch+1u); // RTR first-interface selector in ray payload'),
        @('if(path.getCounter((PackedCounters)3)==0)break;','if((path.getCounter((PackedCounters)3)&4u)==0)break;'))
}
foreach($relative in $rtrPayloadMigrations.Keys) {
    $path=Join-Path $sdk $relative;$text=[IO.File]::ReadAllText($path)
    foreach($pair in $rtrPayloadMigrations[$relative]){$text=$text.Replace($pair[0],$pair[1])}
    [IO.File]::WriteAllText($path,$text,$utf8)
}
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracer.hlsli' '        const float rrVal = sqrt(Luminance(path.GetThp())); // closer to perceptual' @'
        #if defined(RTR_LEGACY_LIGHTING) && RTR_LEGACY_LIGHTING
        // gsOpticalContinuation uses its event budget, not throughput roulette.
        // Applying roulette to a deterministic split kills dim eta^2 entry
        // branches before the exit interface restores their radiance weight.
        if((path.getCounter((PackedCounters)3)&4u)!=0)return false; // RTR bounded optical transport without roulette
        #endif
        const float rrVal = sqrt(Luminance(path.GetThp())); // closer to perceptual
'@ 'RTR bounded optical transport without roulette'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerHandleHit.hlsli' '            SharcHandlePathLimit(surfaceEmission);' @'
            #if defined(RTR_LEGACY_LIGHTING) && RTR_LEGACY_LIGHTING && PATH_TRACER_MODE!=PT_DENOISING_LAYERS_BUILD
            // gsOpticalContinuation shades the final receiver before ending its budget.
            NEEResult terminal=RtrLegacyDirect(path,shadingData,bsdf,workingContext);
            float4 terminalRadiance=terminal.GetRadianceAndSpecAvg();
            AccumulatePathRadiance(workingContext,path,terminalRadiance.rgb,terminalRadiance.w,false,false); // RTR shade final legacy receiver
            #endif
            SharcHandlePathLimit(surfaceEmission);
'@ 'RTR shade final legacy receiver'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracer.hlsli' '        NEEBSDFMISInfo misInfo = NEEBSDFMISInfo::Unpack16bit(path.GetPackedMISInfo());' @'
        #if defined(RTR_LEGACY_LIGHTING) && RTR_LEGACY_LIGHTING
        float horizon=.35+.65*saturate(rayDir.y*.5+.5);
        RtrLegacyLight sun=t_RtrLegacyLights[0];
        float disk=smoothstep(.9995,.9999,dot(normalize(rayDir),-normalize(sun.DirectionRange.xyz)));
        environmentEmission=ToLpRadiance(workingContext.PtConsts.RtrLegacySky.rgb*horizon+sun.ColorIntensity.rgb*sun.ColorIntensity.a*disk); // RTR old horizon and solar sky
        #else
        NEEBSDFMISInfo misInfo = NEEBSDFMISInfo::Unpack16bit(path.GetPackedMISInfo());
'@ 'RTR old horizon and solar sky'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracer.hlsli' '#if RTXPT_FIREFLY_FILTER && PATH_TRACER_MODE!=PT_DENOISING_LAYERS_BUILD' @'
        #endif // RTR old horizon and solar sky
#if RTXPT_FIREFLY_FILTER && PATH_TRACER_MODE!=PT_DENOISING_LAYERS_BUILD
'@ '#endif // RTR old horizon and solar sky'
# The bounded legacy history runs after current radiance merge, before SR tags.
[IO.File]::WriteAllText((Join-Path $sdk 'Rtxpt/ProcessingPasses/RtrLegacySrHistory.hlsl'),
    [IO.File]::ReadAllText((Join-Path $repo 'renderer-rtxpt/src/main/cpp/RtrLegacySrHistory.hlsl')),$utf8)
Patch-Once 'Rtxpt/Shaders.cfg' 'ProcessingPasses/PostProcess.hlsl -T cs -E main -D NO_DENOISER_FINAL_MERGE=1' @'
ProcessingPasses/PostProcess.hlsl -T cs -E main -D NO_DENOISER_FINAL_MERGE=1
ProcessingPasses/RtrLegacySrHistory.hlsl -T cs -E main
'@ 'ProcessingPasses/RtrLegacySrHistory.hlsl'
Patch-Once 'Rtxpt/Sample.h' '    virtual void UpdateCustomResources() { }' @'
    virtual void UpdateCustomResources() { }
    virtual void FilterReconstructionInput(nvrhi::ICommandList*, bool) { } // RTR pre-SR history hook
'@ 'RTR pre-SR history hook'
Patch-Once 'Rtxpt/Sample.cpp' '        if (m_ui.RealtimeAA == 0)' @'
        FilterReconstructionInput(m_commandList, reset); // RTR current radiance bounded history
        if (m_ui.RealtimeAA == 0)
'@ 'RTR current radiance bounded history'
# Match the legacy route's summed continuous surface response without deleting
# any lobe or bounce. PBRT-v3 BSDF::Sample_f sums f after forming the mixture PDF.
# Ideal delta events retain native weights; their density is not a finite PDF.
Patch-Once 'Rtxpt/Shaders/PathTracer/BSDFs/StandardBSDF.hlsli' @'
        bool valid = bsdf.sample(wiLocal, woLocal, result.pdf, result.weight, result.lobe, result.lobeP, preGeneratedSamples.xyzw);
#endif
        result.wo = shadingData.FromLocal(woLocal);
'@ @'
        bool valid = bsdf.sample(wiLocal, woLocal, result.pdf, result.weight, result.lobe, result.lobeP, preGeneratedSamples.xyzw);
#endif
        #if defined(RTR_COMBINED_BSDF_WEIGHT) && RTR_COMBINED_BSDF_WEIGHT
        if (valid && result.pdf > 0)
        {
            // Only overlapping continuous lobes need another BSDF evaluation.
            uint overlapping = woLocal.z >= 0
                ? uint(bsdf.pDiffuseReflection > 0)
                    + uint(bsdf.pSpecularReflection > 0 && bsdf.specularReflection.alpha > 0)
                    + uint(bsdf.pSpecularReflectionTransmission > 0 && bsdf.specularReflectionTransmission.alpha > 0)
                : uint(bsdf.pDiffuseTransmission > 0)
                    + uint(bsdf.pSpecularReflectionTransmission > 0 && bsdf.specularReflectionTransmission.alpha > 0);
            if (overlapping > 1)
                result.weight = bsdf.eval(wiLocal, woLocal).rgb / result.pdf;
        }
        #endif // RTR summed continuous BSDF over native mixture PDF
        result.wo = shadingData.FromLocal(woLocal);
'@ 'RTR summed continuous BSDF over native mixture PDF'
# Raw SR/TAA/reference output does not read it; retain upstream filtering for every consumer.
# Partition the EXISTING baked environment instead of adding duplicate suns or
# replacing visibility/indirect lighting with a constant ambient approximation.
foreach($header in @('RtrDirectionalStrata.hlsli','RtrDirectionalNee.hlsli')) {
    [IO.File]::WriteAllText((Join-Path $sdk ('Rtxpt/Shaders/PathTracer/'+$header)),
        [IO.File]::ReadAllText((Join-Path $repo ('renderer-rtxpt/src/main/cpp/'+$header))),$utf8)
}
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerShared.h' '    PathTracerCameraData camera;' @'
    float4  RtrDirectionalCaps[16];
    uint    RtrDirectionalCount;
    uint3   RtrDirectionalPadding; // RTR directional sampling domains
    PathTracerCameraData camera;
'@ 'RTR directional sampling domains'
foreach($header in @('PathTracer.hlsli','PathTracerNEE.hlsli')) {
    Patch-Once ('Rtxpt/Shaders/PathTracer/'+$header) '#include "PathTracerTypes.hlsli"' @'
#include "PathTracerTypes.hlsli"
#include "RtrDirectionalStrata.hlsli" // RTR shared environment partition
'@ 'RTR shared environment partition'
}
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '    return envMap.EvalLocal(localDir, Bridge::DiffuseEnvironmentMapMIPOffset());' @'
    #if defined(RTR_DIRECTIONAL_STRATA) && RTR_DIRECTIONAL_STRATA
    return envMap.EvalLocal(localDir, 0); // RTR one environment target for both estimators
    #else
    return envMap.EvalLocal(localDir, Bridge::DiffuseEnvironmentMapMIPOffset());
    #endif
'@ 'RTR one environment target for both estimators'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '            float wrsWeight = EvalSampleWeight( candidateSample, shadingData, bsdf );' @'
            #if defined(RTR_DIRECTIONAL_STRATA) && RTR_DIRECTIONAL_STRATA
            if (workingContext.PtConsts.RtrDirectionalCount > 0
                && PolymorphicLight::DecodeType(packedLightInfo) == PolymorphicLightType::kEnvironmentQuad)
            {
                EnvMap environment = Bridge::CreateEnvMap();
                candidateSample.Li = environment.Eval(candidateSample.Direction, 0) / pdf;
                if (RtrDirectionalOwner(candidateSample.Direction, workingContext.PtConsts) != 0xffffffffu)
                    candidateSample.Li = 0; // RTR residual excludes separately sampled domains
            }
            #endif
            float wrsWeight = EvalSampleWeight( candidateSample, shadingData, bsdf );
'@ 'RTR residual excludes separately sampled domains'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '    #include "RtrSmallPointNee.hlsli"' @'
    #include "RtrDirectionalNee.hlsli" // RTR native direct-light evaluation for distant strata
    #include "RtrSmallPointNee.hlsli"
'@ 'RTR native direct-light evaluation for distant strata'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracerNEE.hlsli' '        // RTR deterministic delta contributions; do not add them again below.' @'
        #if defined(RTR_DIRECTIONAL_STRATA) && RTR_DIRECTIONAL_STRATA
        RtrSumDirectionalStrata(result, lightSampler, shadingData, bsdf, preScatterPath, sampleGenerator, workingContext);
        #endif // RTR sample every distant stratum once
        // RTR deterministic delta contributions; do not add them again below.
'@ 'RTR sample every distant stratum once'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracer.hlsli' '            float3 Le = envMap.EvalLocal(localDir, mipLevel);' @'
            #if defined(RTR_DIRECTIONAL_STRATA) && RTR_DIRECTIONAL_STRATA
            if (workingContext.PtConsts.RtrDirectionalCount > 0)
                mipLevel = 0; // RTR preserve environment target across escape and NEE
            #endif
            float3 Le = envMap.EvalLocal(localDir, mipLevel);
'@ 'RTR preserve environment target across escape and NEE'
Patch-Once 'Rtxpt/Shaders/PathTracer/PathTracer.hlsli' '                misWeight = lightSampler.ComputeBSDFMISForEnvironmentQuad(environmentQuadLightIndex, bsdfScatterPdf, misInfo.CandidateSamples, misInfo.FullSamples);' @'
                misWeight = lightSampler.ComputeBSDFMISForEnvironmentQuad(environmentQuadLightIndex, bsdfScatterPdf, misInfo.CandidateSamples, misInfo.FullSamples);
                #if defined(RTR_DIRECTIONAL_STRATA) && RTR_DIRECTIONAL_STRATA
                const uint owner = RtrDirectionalOwner(rayDir, workingContext.PtConsts);
                if (owner != 0xffffffffu)
                    misWeight = EvalMIS(RTXPT_NEE_MIS_HEURISTIC, 1, bsdfScatterPdf, 1,
                        RtrDirectionalPdf(owner, workingContext.PtConsts)); // RTR reciprocal BSDF MIS for distant stratum
                #endif
'@ 'RTR reciprocal BSDF MIS for distant stratum'
Patch-Once 'Rtxpt/AdvancedSample.cpp' '        m_denoisingGuidesBaker->DenoiseSpecHitT(m_commandList, m_bindingSet);' @'
        // RTR filter hit distance only when a consumer is active
        if (m_ui.ActualUseStandaloneDenoiser()
            || (m_ui.RealtimeMode && m_ui.RealtimeAA == 3)
            || m_ui.DebugView != DebugViewType::Disabled)
            m_denoisingGuidesBaker->DenoiseSpecHitT(m_commandList, m_bindingSet);
'@ 'RTR filter hit distance only when a consumer is active'
Patch-Once 'Rtxpt/SampleCommon/SampleBaseApp.cpp' 'm_CmdLine.InitFromCommandLine(__argc, __argv)' 'm_CmdLine.InitFromCommandLine(argc, argv)' 'm_CmdLine.InitFromCommandLine(argc, argv)'
Patch-Once 'Rtxpt/SampleCommon/SampleCommon.cpp' '    // progress bar currently interfering with fullscreen so disable' @'
    // RTR embedded host: compilation/loading progress belongs in the host log.
    if (HelpersIsNonInteractive()) {
        donut::log::info("%s", windowText);
        return -1;
    }
    // progress bar currently interfering with fullscreen so disable
'@ 'RTR embedded host: compilation/loading progress'
Patch-Once 'External/Donut/src/app/streamline/StreamlineIntegration.cpp' '#include <donut/core/log.h>' @'
#include <donut/core/log.h>
#include <donut/app/ApplicationBase.h>
'@ '#include <donut/app/ApplicationBase.h>'
Patch-Once 'External/Donut/src/app/streamline/StreamlineIntegration.cpp' 'auto basePath = std::filesystem::path(path).parent_path();' 'auto basePath = donut::app::GetDirectoryWithExecutable(); // RTR signed runtime root' 'RTR signed runtime root'
# SDK inline helpers retain addresses after slShutdown unloads the plugins.
# Match sl.h's cached hot path, with lifecycle-only invalidation on embedded close.
Patch-Once 'External/Donut/src/app/streamline/StreamlineIntegration.cpp' '// Streamline Features' @'
#include "rtxpt_streamline_cache.h" // RTR session-bound feature function caches
// Streamline Features
'@ 'RTR session-bound feature function caches'
# RTXPT selects Streamline manual hooking. Upgrade the DXGI factory before any
# swapchain calls so Streamline can observe CreateSwapChainForHwnd and own Present.
$swapchainPath=Join-Path $sdk 'External/Donut/src/app/dx12/DeviceManager_DX12.cpp'
$swapchainText=[IO.File]::ReadAllText($swapchainPath)
if($swapchainText.Contains('RTR manual-hook present path')) {
    $swapchainText=[regex]::Replace($swapchainText,'(?m)^\s*#if DONUT_WITH_STREAMLINE\r?\n\s*if \(!StreamlineIntegration::UpgradeInterface\(pSwapChain1\)\) return false; // RTR manual-hook present path\r?\n\s*#endif\r?\n','')
    [IO.File]::WriteAllText($swapchainPath,$swapchainText,$utf8)
}
Patch-Once 'External/Donut/src/app/dx12/DeviceManager_DX12.cpp' 'HRESULT hres = CreateDXGIFactory2(m_DeviceParams.enableDebugRuntime ? DXGI_CREATE_FACTORY_DEBUG : 0, IID_PPV_ARGS(&m_DxgiFactory2));' @'
HRESULT hres = CreateDXGIFactory2(m_DeviceParams.enableDebugRuntime ? DXGI_CREATE_FACTORY_DEBUG : 0, IID_PPV_ARGS(&m_DxgiFactory2));
#if DONUT_WITH_STREAMLINE
        if (hres == S_OK && !StreamlineIntegration::UpgradeInterface(m_DxgiFactory2)) return false; // RTR manual-hook factory path
#endif
'@ 'RTR manual-hook factory path'
# The public API can request 3x/4x MFG on devices with a lower Streamline limit.
Patch-Once 'Rtxpt/SampleUI.h' 'bool                                ShowUI                                  = true;' @'
std::string                         HostOverlayText; // RTR GPU-native host HUD
    bool                                ShowUI                                  = true;
'@ 'RTR GPU-native host HUD'
Patch-Once 'Rtxpt/SampleUI.cpp' "void SampleUI::buildUI(void)`r`n{" @'
void SampleUI::buildUI(void)
{
    // RTR GPU-native host HUD: same upstream ImGui pass, independent of the settings panel.
    if (!m_ui.HostOverlayText.empty()) {
        ImGui::PushFont(m_defaultFont->GetScaledFont());
        ImGui::SetNextWindowPos(ImVec2(14.f, 14.f), ImGuiCond_Always);
        ImGui::SetNextWindowBgAlpha(0.69f);
        ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding, ImVec2(12.f, 8.f));
        ImGui::Begin("RTRenderer host HUD", nullptr, ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_AlwaysAutoResize
            | ImGuiWindowFlags_NoSavedSettings | ImGuiWindowFlags_NoInputs | ImGuiWindowFlags_NoFocusOnAppearing
            | ImGuiWindowFlags_NoNav);
        ImGui::TextUnformatted(m_ui.HostOverlayText.c_str());
        ImGui::End();
        ImGui::PopStyleVar();
        ImGui::PopFont();
    }
'@ 'RTR GPU-native host HUD:'
Patch-Once 'Rtxpt/SampleUI.h' 'std::string                         HostOverlayText; // RTR GPU-native host HUD' @'
std::string                         HostOverlayText; // RTR GPU-native host HUD
    std::vector<std::string>             HostOverlayChoices; // RTR host choice selector
    int                                 HostOverlayChoice = -1;
'@ 'RTR host choice selector'
$hostUiPath=Join-Path $sdk 'Rtxpt/SampleUI.cpp'
$hostUiText=[IO.File]::ReadAllText($hostUiPath)
$hostUiText=$hostUiText.Replace('ImGuiWindowFlags_NoSavedSettings | ImGuiWindowFlags_NoInputs | ImGuiWindowFlags_NoFocusOnAppearing',
    'ImGuiWindowFlags_NoSavedSettings | (m_ui.HostOverlayChoices.empty() ? ImGuiWindowFlags_NoInputs : 0) | ImGuiWindowFlags_NoFocusOnAppearing')
[IO.File]::WriteAllText($hostUiPath,$hostUiText,$utf8)
Patch-Once 'Rtxpt/SampleUI.cpp' 'ImGui::TextUnformatted(m_ui.HostOverlayText.c_str());' @'
ImGui::TextUnformatted(m_ui.HostOverlayText.c_str());
        // RTR host choice selector: upstream input, drawing and GPU composition.
        if (!m_ui.HostOverlayChoices.empty()) {
            ImGui::SetNextItemWidth(330.f);
            const auto& labels=m_ui.HostOverlayChoices;
            if (ImGui::BeginCombo("Scene", labels.at(m_ui.HostOverlayChoice).c_str())) {
                for (int i=0; i<int(labels.size()); ++i) {
                    ImGui::PushID(i);
                    if (ImGui::Selectable(labels[i].c_str(), i==m_ui.HostOverlayChoice)) m_ui.HostOverlayChoice=i;
                    if (i==m_ui.HostOverlayChoice) ImGui::SetItemDefaultFocus();
                    ImGui::PopID();
                }
                ImGui::EndCombo();
            }
        }
'@ 'RTR host choice selector:'
# The public API can request 3x/4x MFG on devices with a lower Streamline limit.
# Follow RTXPT's UI negotiation: keep native presentation when the requested count is unavailable.
Patch-Once 'Rtxpt/Sample.cpp' 'm_ui.DLSSFGMaxNumFramesToGenerate = state.numFramesToGenerateMax;' @'
m_ui.DLSSFGMaxNumFramesToGenerate = state.numFramesToGenerateMax;
            // RTR bounded DLSS frame-generation cadence
            if (dlssgOptions.mode != StreamlineInterface::DLSSGMode::eOff
                && dlssgOptions.numFramesToGenerate > state.numFramesToGenerateMax) {
                log::warning("Requested DLSS frame generation cadence is unavailable; keeping native presentation.");
                dlssgOptions.mode = StreamlineInterface::DLSSGMode::eOff;
                m_ui.DLSSFGMode = StreamlineInterface::DLSSGMode::eOff;
            }
'@ 'RTR bounded DLSS frame-generation cadence'
# The embedded host shuts SL down while its tagged render targets are still alive.
# The sample's later device teardown calls Shutdown again; it must not call unloaded plugins.
$slPath=Join-Path $sdk 'External/Donut/src/app/streamline/StreamlineIntegration.cpp'
$slText=[IO.File]::ReadAllText($slPath)
if(!$slText.Contains('RTR idempotent embedded teardown')){
    $slText=[regex]::Replace($slText,'void StreamlineIntegration::Shutdown\(\)\s*\{',
        "void StreamlineIntegration::Shutdown()`n{`n    if (!m_slInitialized) return; // RTR idempotent embedded teardown",1)
    if(!$slText.Contains('RTR idempotent embedded teardown')){throw 'Pinned Streamline shutdown anchor changed'}
    [IO.File]::WriteAllText($slPath,$slText,$utf8)
}
Patch-Once 'External/Donut/src/app/streamline/StreamlineIntegration.h' 'void Shutdown();' @'
void Shutdown();
    sl::Result HostShutdownResult() const { return m_hostShutdownResult; }
private:
    sl::Result m_hostShutdownResult = sl::Result::eOk;
public:
'@ 'HostShutdownResult()'
Patch-Once 'External/Donut/src/app/streamline/StreamlineIntegration.h' 'void Shutdown();' @'
void Shutdown();
    struct HostFrameGenerationTelemetry {
        uint64_t queries=0, samples=0, failures=0, presented=0;
        uint32_t lastPresented=0, status=0, maximumGenerated=0;
        bool querySucceeded=false;
    };
    HostFrameGenerationTelemetry hostFrameGeneration;
'@ 'HostFrameGenerationTelemetry'
Patch-Once 'External/Donut/src/app/streamline/StreamlineIntegration.cpp' 'successCheck(slDLSSGGetState(m_viewport, slState, &slOptions), "slDLSSGGetState");' @'
const auto hostQueryResult = slDLSSGGetState(m_viewport, slState, &slOptions);
    successCheck(hostQueryResult, "slDLSSGGetState");
    // RTR observes the existing query: this counter resets on every SDK GetState call.
    auto& host = hostFrameGeneration;
    ++host.queries;
    host.querySucceeded = hostQueryResult == sl::Result::eOk;
    if (host.querySucceeded) {
        host.status = static_cast<uint32_t>(slState.status);
        host.lastPresented = slState.numFramesActuallyPresented;
        host.maximumGenerated = slState.numFramesToGenerateMax;
        if (host.lastPresented) { ++host.samples; host.presented += host.lastPresented; }
    } else { ++host.failures; }
'@ 'RTR observes the existing query'
Patch-Once 'External/Donut/src/app/streamline/StreamlineIntegration.cpp' 'successCheck(slShutdown(), "slShutdown");' @'
m_hostShutdownResult = slShutdown(); // RTR observable embedded shutdown
        successCheck(m_hostShutdownResult, "slShutdown");
        log::info("RTXPT Streamline shutdown result=%d", int(m_hostShutdownResult));
'@ 'RTR observable embedded shutdown'
Patch-Once 'External/Donut/src/app/streamline/StreamlineIntegration.cpp' 'm_hostShutdownResult = slShutdown(); // RTR observable embedded shutdown' @'
m_hostShutdownResult = slShutdown(); // RTR observable embedded shutdown
        rtr::streamline::FunctionCaches::reset(); // RTR invalidate unloaded plugin addresses
'@ 'RTR invalidate unloaded plugin addresses'
Patch-Once 'Rtxpt/Sample.cpp' 'if (available.empty())' 'if (std::filesystem::path(preferred).is_absolute() && std::filesystem::is_regular_file(preferred)) return preferred; // RTR absolute host scene
    if (available.empty())' 'RTR absolute host scene'
Patch-Once 'Rtxpt/SampleCommon/SampleBaseApp.h' 'private:' @'
public:
    donut::app::DeviceManager* HostDeviceManager() { return m_DeviceManager.get(); }
    Sample* HostRenderPass() { return m_MainSceneRender.get(); }
private:
'@ 'HostDeviceManager'
Patch-Once 'External/Donut/include/donut/app/DeviceManager.h' 'void RunMessageLoop();' @'
void RunMessageLoop();
        bool HostFrame();
'@ 'bool HostFrame()'
Patch-Once 'External/Donut/src/app/DeviceManager.cpp' 'void DeviceManager::RunMessageLoop()' @'
bool DeviceManager::HostFrame() {
            if (m_PreviousFrameTimestamp == 0) m_PreviousFrameTimestamp = glfwGetTime();
#if DONUT_WITH_STREAMLINE
            StreamlineIntegration::Get().SimStart(*this);
#endif
            if (m_callbacks.beforeFrame) m_callbacks.beforeFrame(*this, m_FrameIndex);
            glfwPollEvents();
            UpdateWindowSize();
            return AnimateRenderPresent();
}
void DeviceManager::RunMessageLoop()
'@ 'bool DeviceManager::HostFrame()'
Patch-Once 'External/Donut/include/donut/app/DeviceManager.h' 'bool HostFrame();' @'
bool HostFrame();
        bool HostExternalPresentation = false; // RTR external GPU compositor
'@ 'RTR external GPU compositor'
Patch-Once 'External/Donut/src/app/DeviceManager.cpp' "    glfwShowWindow(m_Window);`r`n    glfwFocusWindow(m_Window);" @'
    // RTR compositor keeps the upstream host hidden from its creation onward.
    if (!HostExternalPresentation) { glfwShowWindow(m_Window); glfwFocusWindow(m_Window); }
'@ 'RTR compositor keeps the upstream host hidden'
Patch-Once 'External/Donut/src/app/DeviceManager.cpp' '            return AnimateRenderPresent();' @'
            // RTR external compositor owns the visible window; the hidden host still renders.
            if (HostExternalPresentation) { m_windowVisible=true; m_windowIsInFocus=true; }
            return AnimateRenderPresent();
'@ 'RTR external compositor owns'
Patch-Once 'External/Donut/src/app/dx12/DeviceManager_DX12.cpp' "bool DeviceManager_DX12::BeginFrame()`r`n{" @'
bool DeviceManager_DX12::BeginFrame()
{
    if (HostExternalPresentation) return true; // RTR external timeline owns frame retirement
'@ 'RTR external timeline owns'
Patch-Once 'External/Donut/src/app/dx12/DeviceManager_DX12.cpp' "bool DeviceManager_DX12::Present()`r`n{" @'
bool DeviceManager_DX12::Present()
{
    if (HostExternalPresentation) return true; // RTR external compositor presents, not DXGI
'@ 'RTR external compositor presents'
$function="std::filesystem::path donut::app::GetDirectoryWithExecutable()`r`n{"
$path=Join-Path $sdk 'External/Donut/src/app/ApplicationBase.cpp'
$text=[IO.File]::ReadAllText($path)
if(!$text.Contains('RTR_RTXPT_RUNTIME')){
    $newline=if($text.Contains("`r`n")){"`r`n"}else{"`n"}
    $anchor='std::filesystem::path donut::app::GetDirectoryWithExecutable()'+$newline+'{'
    if(!$text.Contains($anchor)){throw 'Pinned Donut runtime-root anchor changed'}
    $replacement=$anchor+$newline+'    if (const char* runtime = std::getenv("RTR_RTXPT_RUNTIME")) return std::filesystem::path(runtime);'
    [IO.File]::WriteAllText($path,"#include <cstdlib>`n"+$text.Replace($anchor,$replacement),$utf8)
}
$rootCMake=Join-Path $sdk 'CMakeLists.txt'
$cmakeText=[IO.File]::ReadAllText($rootCMake)
# The command-line OptiX screenshot utility is not used by the embedded realtime host.
$cmakeText=$cmakeText.Replace('if (NOT _denoiser_hash STREQUAL RTXPT_OPTIX_DENOISER_SHA256)',
    'if (RTR_RTXPT_FETCH_CAPTURE_TOOL AND NOT _denoiser_hash STREQUAL RTXPT_OPTIX_DENOISER_SHA256)')
$marker='# RTR_RTXPT_HOST_TARGET'
if($cmakeText.Contains($marker)){$cmakeText=$cmakeText.Substring(0,$cmakeText.IndexOf($marker))}
$hostCMake=(Join-Path $repo 'renderer-rtxpt/src/main/cpp/CMakeLists.txt').Replace('\','/')
[IO.File]::WriteAllText($rootCMake,$cmakeText+"`n$marker`ninclude(`"$hostCMake`")`n",$utf8)
$cmake=(Resolve-Path (Join-Path $repo $CMakeExecutable)).Path
$build=Join-Path $sdk 'build-host'
$localDependencies=@()
if($StreamlineSdk){
    $streamline=(Resolve-Path -LiteralPath $StreamlineSdk).Path.Replace('\','/')
    $localDependencies+=('-DFETCHCONTENT_SOURCE_DIR_STREAMLINE='+$streamline)
}
& $cmake -S $sdk -B $build -G 'Visual Studio 17 2022' -A x64 `
    -DDONUT_WITH_VULKAN=OFF -DDONUT_WITH_KTX=OFF -DDONUT_WITH_AFTERMATH=OFF `
    -DDONUT_EMBED_SHADER_PDBS=OFF -DCMAKE_COMPILE_WARNING_AS_ERROR=OFF `
    -DTRACY_ENABLE=OFF -DSTREAMLINE_FEATURE_NIS=OFF `
    '-DCMAKE_CXX_FLAGS=/DWIN32 /D_WINDOWS /EHsc /utf-8' '-DCMAKE_C_FLAGS=/DWIN32 /D_WINDOWS /utf-8' @localDependencies
if($LASTEXITCODE -ne 0){throw 'RTXPT native configure failed'}
if($ConfigureOnly){return}
& (Join-Path $PSScriptRoot 'Invoke-BoundedBuild.ps1') -FilePath $cmake `
    -ArgumentList @('--build',$build,'--config','Release','--target','rtrenderer_rtxpt','rtxpt_native_control','rtxpt_streamline_cache_selftest','--parallel','1','--','/p:MultiProcessorCompilation=false',('/bl:'+(Join-Path $build 'build.binlog')),('/flp:logfile='+(Join-Path $build 'build.log')+';verbosity=normal;encoding=UTF-8')) `
    -WorkingDirectory $sdk -MemoryLimitGB $MemoryLimitGB
if($LASTEXITCODE -ne 0){throw 'RTXPT JNI bridge build failed'}
& (Join-Path $sdk 'bin/rtxpt_streamline_cache_selftest.exe')
if($LASTEXITCODE -ne 0){throw 'Streamline feature function cache lifecycle self-test failed'}
# Runtime redistribution keeps the exact upstream notices alongside the native payload.
$licenses=Join-Path $sdk 'bin/ThirdPartyLicenses'
New-Item -ItemType Directory -Path $licenses -Force | Out-Null
$noticeFiles=@{
    'LICENSE.txt'='RTXPT.txt'; 'External/Donut/LICENSE.txt'='Donut.txt';
    'External/Donut/ThirdPartyLicenses.txt'='Donut-ThirdParty.txt';
    'External/Donut/nvrhi/LICENSE.txt'='NVRHI.txt'; 'External/Donut/nvrhi/ThirdPartyLicenses.txt'='NVRHI-ThirdParty.txt';
    'External/Donut/ShaderMake/LICENSE.txt'='ShaderMake.txt'; 'External/Donut/ShaderMake/ThirdPartyLicenses.txt'='ShaderMake-ThirdParty.txt';
    'External/Nrd/LICENSE.txt'='NRD.txt'; 'External/Rtxdi/LICENSE.txt'='RTXDI.txt';
    'External/SHARC/License.md'='SHARC.md'; 'External/Rtxtf/LICENSE.txt'='RTXTF.txt';
    'External/NVAPI/License.txt'='NVAPI.txt'; 'External/Omm/LICENSE.txt'='OMM.txt';
    'External/cxxopts/LICENSE'='cxxopts.txt'; 'External/dxc/LICENCE-MIT.txt'='DXC-MIT.txt';
    'External/dxc/LICENSE-LLVM.txt'='DXC-LLVM.txt'; 'External/dxc/LICENSE-MS.txt'='DXC-MS.txt';
    'External/AgilitySDK/1.619.6/LICENSE.txt'='D3D12.txt'; 'External/AgilitySDK/1.619.6/LICENSE-CODE.txt'='D3D12-Code.txt'
}
foreach($entry in $noticeFiles.GetEnumerator()){Copy-Item -LiteralPath (Join-Path $sdk $entry.Key) -Destination (Join-Path $licenses $entry.Value) -Force}
$streamlineRoot=if($StreamlineSdk){(Resolve-Path -LiteralPath $StreamlineSdk).Path}else{Join-Path $build '_deps/streamline-src'}
if(!(Test-Path -LiteralPath (Join-Path $streamlineRoot 'license.txt'))) {
    # Reuse CMake's resolved dependency when this invocation omits the optional
    # SDK argument. Never embed a machine-specific SDK path in the project.
    $configured=Select-String -LiteralPath (Join-Path $build 'CMakeCache.txt') -Pattern '^FETCHCONTENT_SOURCE_DIR_STREAMLINE:PATH=(.+)$'
    if($configured){$streamlineRoot=$configured.Matches[0].Groups[1].Value}
}
Copy-Item -LiteralPath (Join-Path $streamlineRoot 'license.txt') -Destination (Join-Path $licenses 'Streamline.txt') -Force
Copy-Item -LiteralPath (Join-Path $streamlineRoot '3rd-party-licenses.md') -Destination (Join-Path $licenses 'Streamline-ThirdParty.md') -Force
