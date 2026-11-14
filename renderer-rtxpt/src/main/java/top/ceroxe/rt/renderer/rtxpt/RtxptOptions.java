package top.ceroxe.rt.renderer.rtxpt;

import java.util.List;
import java.util.Objects;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rtxpt.RtxptAccess.Reconstruction;

/** Native RTXPT controls, applied before the first frame. No second transport implementation. */
record RtxptOptions(int samplesPerPixel, int maxBounces, int diffuseBounces,
        Reconstruction reconstruction, DlssQuality dlssQuality, boolean standaloneDenoiser, boolean relaxDenoiser,
        boolean restirDi, boolean restirGi, boolean sharc, boolean ser, boolean reflex,
        FrameGenerationOptions frameGeneration,PathTracingQualityOptions quality) {
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
        if(maxBounces<1 || maxBounces>64 || diffuseBounces<0 || diffuseBounces>maxBounces)
            throw new IllegalArgumentException("Invalid native path depth");
        if((restirDi || restirGi) && samplesPerPixel!=1)
            throw new IllegalArgumentException("RTXPT ReSTIR uses one sample per pixel");
        if(reconstruction==Reconstruction.DLSS_RR && dlssQuality==DlssQuality.ULTRA_QUALITY)
            throw new IllegalArgumentException("RTXPT 1.9 cannot safely combine DLSS Ray Reconstruction with Ultra Quality; use Quality or DLAA");
        if(restirDi && restirGi && sharc)
            throw new IllegalArgumentException("The pinned RTXPT 1.9 runtime crashes compiling ReSTIR DI + ReSTIR GI + SHaRC together");
    }
    /** Medium realtime path: 1 SPP, DLSS-RR Performance, SHaRC, SER and Reflex, FG off. */
    public static RtxptOptions recommended() {
        return resolve(profile(RendererConfig.pathTracedPresentation()));
    }
    public static RtxptOptions nativeOneSample() {
        return new RtxptOptions(1,12,2,Reconstruction.NONE,DlssQuality.DLAA,false,false,false,false,false,true,false,
            FrameGenerationOptions.disabled(),PathTracingQualityOptions.defaults());
    }
    static RendererFeatureProfile profile(RendererConfig c){return new RendererFeatureProfile(c.frameReconstruction(),c.frameGeneration(),c.lowLatency(),c.denoising(),c.rayTracingOptimizations(),c.pathTracing());}
    static RtxptOptions resolve(RendererFeatureProfile p) {
        var r=p.frameReconstruction();
        boolean reconstruct=r.preference().requested(),denoise=p.denoising().preference().requested();
        var path=p.pathTracing();
        if(path.quality().beautyTransport()==PathTracingQualityOptions.BeautyTransport.ANALYTIC_DIRECT
                && p.rayTracingOptimizations().shaderExecutionReordering()==RendererFeaturePreference.REQUIRED)
            throw new IllegalArgumentException("Analytic direct uses native inline traversal, without shader execution reordering");
        if(path.quality().beautyTransport()==PathTracingQualityOptions.BeautyTransport.ANALYTIC_DIRECT
                && (denoise||path.directLightResampling().requested()||path.indirectLightResampling().requested()
                    ||path.radianceCache().requested()||path.samplesPerPixel()!=1
                    ||path.quality().subSampling()!=PathTracingQualityOptions.SubSampling.FULL))
            throw new IllegalArgumentException("Analytic direct transport requires 1 SPP, full sampling, no stochastic denoiser, ReSTIR or radiance cache");
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
        return new RtxptOptions(path.samplesPerPixel(),path.maxBounces(),path.diffuseBounces(),aa,quality,denoise,relax,di,gi,
            path.radianceCache().requested(),p.rayTracingOptimizations().shaderExecutionReordering().requested(),p.lowLatency().preference().requested(),frameGeneration,path.quality());
    }
    int frameGenerationMode(){return frameGeneration.preference().requested()?1:0;}
    int generatedFrames(){return frameGeneration.preference().requested()?frameGeneration.multiplier().presentedFramesPerNativeFrame()-1:0;}
    static RendererFeatureProfile normalize(RendererFeatureProfile p) {
        resolve(p);
        return p;
    }
    static boolean hasRequiredFeatures(RendererFeatureProfile p) {
        return p.frameReconstruction().preference()==RendererFeaturePreference.REQUIRED
            ||p.frameGeneration().preference()==RendererFeaturePreference.REQUIRED
            ||p.lowLatency().preference()==RendererFeaturePreference.REQUIRED
            ||p.denoising().preference()==RendererFeaturePreference.REQUIRED
            ||p.rayTracingOptimizations().shaderExecutionReordering()==RendererFeaturePreference.REQUIRED
            ||p.pathTracing().directLightResampling()==RendererFeaturePreference.REQUIRED
            ||p.pathTracing().indirectLightResampling()==RendererFeaturePreference.REQUIRED
            ||p.pathTracing().radianceCache()==RendererFeaturePreference.REQUIRED;
    }
    static void validateRequired(RendererFeatureProfile p,RtxptAccess.RenderingState render,RtxptAccess.PresentationState present) {
        var expected=resolve(p);
        require(p.frameReconstruction().preference(),render.reconstruction()==expected.reconstruction()
            &&render.dlssQuality()==expected.dlssQuality().nativeValue,"DLSS reconstruction");
        require(p.frameGeneration().preference(),present.frameGenerationSupported()&&present.activeFrameGenerationMode()!=0
            &&present.generatedFrames()>=expected.generatedFrames(),"DLSS frame generation");
        require(p.lowLatency().preference(),present.reflexSupported()&&present.activeReflexMode()!=0,"Reflex");
        require(p.denoising().preference(),render.reconstruction()==Reconstruction.DLSS_RR
            ||(render.standaloneDenoiser()&&render.denoiserIntegrations()>0),"native denoising");
        require(p.rayTracingOptimizations().shaderExecutionReordering(),render.dxReorderThreads()||render.nvapiReorderThreads(),"SER");
        require(p.pathTracing().directLightResampling(),render.restirDi(),"ReSTIR DI");
        require(p.pathTracing().indirectLightResampling(),render.restirGi(),"ReSTIR GI");
        require(p.pathTracing().radianceCache(),render.radianceCacheMode()!=0,"radiance cache");
    }
    private static void require(RendererFeaturePreference preference,boolean active,String name) {
        if(preference==RendererFeaturePreference.REQUIRED&&!active)
            throw new IllegalStateException("Required native feature is inactive: "+name);
    }
    String[] arguments() {
        var a=new java.util.ArrayList<>(List.of("--overrideToRealtimeMode=true","--realtimeSamplesPerPixel",Integer.toString(samplesPerPixel),
            "--realtimeAA",Integer.toString(reconstruction.ordinal()),"--standaloneDenoiser="+(standaloneDenoiser?1:0),
            "--useReSTIRDI="+(restirDi?1:0),"--useReSTIRGI="+(restirGi?1:0),
            "--overrideRadianceCacheMode",sharc?"1":"0","--overrideDLSSMode",Integer.toString(dlssQuality.nativeValue),
            "--overrideSubSamplingMode",Integer.toString(quality.subSampling().ordinal()),"--overrideAutoexposureOff=true",
            "--overrideExposureOffset="+Float.toString(quality.postProcessing().exposureOffset())));
        if(!ser) a.add("--noSER");
        if(Boolean.getBoolean("top.ceroxe.rt.rtxpt.validation")) a.add("--debug");
        return a.toArray(String[]::new);
    }
    void applyQuality(long handle){
        var l=quality.lighting();var p=quality.postProcessing();
        RtxptNative.quality(handle,l.sampler().ordinal()-1,l.samples(),l.candidates(),l.adaptiveQuality(),l.localCandidateRatio(),
            quality.denoisingLayers(),quality.denoisingLayerMaxDepth(),quality.nestedDielectricsQuality(),quality.halfPrecision(),quality.textureLodBias(),
            p.bloom(),p.bloomRadius(),p.bloomIntensity(),p.toneMapping(),quality.beautyTransport().ordinal());
    }
}
