package top.ceroxe.rt.renderer.rtxpt;

import java.util.List;
import java.util.Objects;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rtxpt.RtxptAccess.Reconstruction;

/** Native RTXPT controls, applied before the first frame. No second transport implementation. */
record RtxptOptions(int samplesPerPixel, int maxBounces, int diffuseBounces,
        Reconstruction reconstruction, DlssQuality dlssQuality, boolean standaloneDenoiser, boolean relaxDenoiser,
        boolean restirDi, boolean restirGi, boolean sharc, boolean ser, boolean reflex,
        FrameGenerationOptions frameGeneration,int exactOpticalInterfaces,PathTracingQualityOptions quality) {
    public enum DlssQuality {
        PERFORMANCE(1), BALANCED(2), QUALITY(3), ULTRA_PERFORMANCE(4), ULTRA_QUALITY(5), DLAA(6);
        final int nativeValue;
        DlssQuality(int value) { nativeValue=value; }
    }
    public RtxptOptions {
        Objects.requireNonNull(reconstruction); Objects.requireNonNull(dlssQuality);
        Objects.requireNonNull(frameGeneration);
        Objects.requireNonNull(quality);
        if(samplesPerPixel<1 || samplesPerPixel>64) throw new IllegalArgumentException("samplesPerPixel must be in [1,64]");
        if(exactOpticalInterfaces<1 || exactOpticalInterfaces>6) throw new IllegalArgumentException("exactOpticalInterfaces must be in [1,6]");
        if(maxBounces<1 || maxBounces>64 || diffuseBounces<0 || diffuseBounces>maxBounces)
            throw new IllegalArgumentException("Invalid native path depth");
        if((restirDi || restirGi) && samplesPerPixel!=1)
            throw new IllegalArgumentException("RTXPT ReSTIR uses one sample per pixel");
        if(reconstruction==Reconstruction.DLSS_RR && dlssQuality==DlssQuality.ULTRA_QUALITY)
            throw new IllegalArgumentException("RTXPT 1.9 cannot safely combine DLSS Ray Reconstruction with Ultra Quality; use Quality or DLAA");
        if(restirDi && restirGi && sharc)
            throw new IllegalArgumentException("The pinned RTXPT 1.9 runtime crashes compiling ReSTIR DI + ReSTIR GI + SHaRC together");
        if(quality.lighting().input()==PathTracingQualityOptions.LightingInput.SCENE_ANALYTIC&&(restirDi||restirGi||sharc))
            throw new IllegalArgumentException("Scene analytic lighting does not combine with ReSTIR or a radiance cache; choose backend lighting for those features");
        if(quality.lighting().directionalStrata()&&(restirDi||restirGi||sharc||reconstruction!=Reconstruction.DLSS||standaloneDenoiser))
            throw new IllegalArgumentException("Directional strata require standalone-denoiser-free DLSS SR without ReSTIR/cache reuse");
    }
    /** Medium realtime path: 1 SPP, DLSS-RR Performance, SHaRC, SER and Reflex, FG off. */
    public static RtxptOptions recommended() {
        return resolve(profile(RendererConfig.pathTracedPresentation()));
    }
    public static RtxptOptions nativeOneSample() {
        return new RtxptOptions(1,12,2,Reconstruction.NONE,DlssQuality.DLAA,false,false,false,false,false,true,false,
            FrameGenerationOptions.disabled(),4,PathTracingQualityOptions.defaults());
    }
    static RendererFeatureProfile profile(RendererConfig c){return new RendererFeatureProfile(c.frameReconstruction(),c.frameGeneration(),c.lowLatency(),c.denoising(),c.rayTracingOptimizations(),c.pathTracing());}
    static RtxptOptions resolve(RendererFeatureProfile p) {
        if(p.rayTracingOptimizations().memoryOptimization()==RendererFeaturePreference.REQUIRED)
            throw new IllegalArgumentException("RTXMU memory optimization belongs to the Vulkan backend");
        var r=p.frameReconstruction();
        boolean spatial=r.mode()==FrameReconstructionOptions.Mode.SPATIAL_UPSCALING;
        if(spatial && r.preference()==RendererFeaturePreference.REQUIRED)
            throw new IllegalArgumentException("RTXPT does not expose a spatial reconstruction path");
        boolean reconstruct=r.preference().requested()&&!spatial,denoise=p.denoising().preference().requested();
        var path=p.pathTracing();
        boolean di=path.directLightResampling().requested(),gi=path.indirectLightResampling().requested();
        // RESPONSIVE uses upstream RELAX before DLSS SR/DLAA, without enabling unrelated ReSTIR work.
        // Balanced/stable retain the upstream RR default; ReSTIR also needs standalone NRD.
        boolean relax=denoise&&p.denoising().strategy()==DenoisingOptions.Strategy.RESPONSIVE;
        var aa=reconstruct?(denoise&&!di&&!gi&&!relax?Reconstruction.DLSS_RR:Reconstruction.DLSS):Reconstruction.NONE;
        var quality=r.mode()==FrameReconstructionOptions.Mode.NATIVE_ANTI_ALIASING?DlssQuality.DLAA:switch(r.quality()) {
            case AUTO,BALANCED->DlssQuality.BALANCED;case QUALITY->DlssQuality.QUALITY;
            case PERFORMANCE->DlssQuality.PERFORMANCE;case ULTRA_PERFORMANCE->DlssQuality.ULTRA_PERFORMANCE;
            case ULTRA_QUALITY->DlssQuality.ULTRA_QUALITY;
        };
        var frameGeneration=p.frameGeneration();
        if(frameGeneration.mode()==FrameGenerationOptions.Mode.ADAPTIVE) {
            if(frameGeneration.preference()==RendererFeaturePreference.REQUIRED)
                throw new IllegalArgumentException("RTXPT exposes fixed DLSS Frame Generation multipliers, not the API's capped adaptive MFG mode");
            frameGeneration=FrameGenerationOptions.disabled();
        }
        return new RtxptOptions(path.samplesPerPixel(),path.maxBounces(),path.diffuseBounces(),aa,quality,denoise,relax,di,gi,
            path.radianceCache().requested(),p.rayTracingOptimizations().shaderExecutionReordering().requested(),p.lowLatency().preference().requested(),frameGeneration,path.exactOpticalInterfaces(),path.quality());
    }
    int frameGenerationMode(){return frameGeneration.preference().requested()?1:0;}
    int generatedFrames(){return frameGeneration.preference().requested()?frameGeneration.multiplier().presentedFramesPerNativeFrame()-1:1;}
    static RendererFeatureProfile normalize(RendererFeatureProfile p) {
        var resolved=resolve(p);
        var optimizations=p.rayTracingOptimizations().toBuilder().memoryOptimization(RendererFeaturePreference.DISABLED).build();
        var reconstruction=p.frameReconstruction().mode()==FrameReconstructionOptions.Mode.SPATIAL_UPSCALING
            ?FrameReconstructionOptions.disabled():p.frameReconstruction();
        return p.toBuilder().frameGeneration(resolved.frameGeneration()).rayTracingOptimizations(optimizations)
            .frameReconstruction(reconstruction).build();
    }
    String[] arguments() {
        var a=new java.util.ArrayList<>(List.of("--realtimeSamplesPerPixel",Integer.toString(samplesPerPixel),
            "--realtimeAA",Integer.toString(reconstruction.ordinal()),"--standaloneDenoiser="+(standaloneDenoiser?1:0),
            "--useReSTIRDI="+(restirDi?1:0),"--useReSTIRGI="+(restirGi?1:0),
            "--overrideRadianceCacheMode",sharc?"1":"0","--overrideDLSSMode",Integer.toString(dlssQuality.nativeValue),
            "--overrideSubSamplingMode",Integer.toString(quality.subSampling().ordinal()),"--overrideAutoexposureOff"));
        if(!ser) a.add("--noSER");
        return a.toArray(String[]::new);
    }
    int[] qualityInts(){var l=quality.lighting();var c=quality.cache();return new int[]{
        l.input().ordinal(),l.sampler().ordinal(),l.samples(),l.candidates(),l.adaptiveQuality(),l.exactPointLightLimit(),l.directionalStrata()?1:0,
        c.entries(),c.trainingDownscale(),c.maxBounces(),c.diffuseBounces(),c.directLightSamples(),quality.denoisingLayers(),
        quality.denoisingLayerMaxDepth(),quality.nestedDielectricsQuality(),quality.subSampling().ordinal(),quality.halfPrecision()?1:0,c.accumulationFrames(),c.staleFrames(),quality.postProcessing().bloom()?1:0,quality.postProcessing().toneMapping()?1:0};}
    float[] qualityFloats(){var c=quality.cache();var p=quality.postProcessing();return new float[]{quality.lighting().localCandidateRatio(),c.terminationThreshold(),quality.textureLodBias(),c.textureLodBias(),c.sceneScale(),c.minRoughness(),p.bloomRadius(),p.bloomIntensity()};}
}
