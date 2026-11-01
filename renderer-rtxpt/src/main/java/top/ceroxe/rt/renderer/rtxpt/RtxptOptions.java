package top.ceroxe.rt.renderer.rtxpt;

import java.util.List;
import java.util.Objects;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.rtxpt.RtxptAccess.Reconstruction;

/** Native RTXPT controls, applied before the first frame. No second transport implementation. */
record RtxptOptions(int samplesPerPixel, int maxBounces, int diffuseBounces,
        Reconstruction reconstruction, DlssQuality dlssQuality, boolean standaloneDenoiser,
        boolean restirDi, boolean restirGi, boolean sharc, boolean ser, boolean reflex) {
    public enum DlssQuality {
        PERFORMANCE(1), BALANCED(2), QUALITY(3), ULTRA_PERFORMANCE(4), ULTRA_QUALITY(5), DLAA(6);
        final int nativeValue;
        DlssQuality(int value) { nativeValue=value; }
    }
    public RtxptOptions {
        Objects.requireNonNull(reconstruction); Objects.requireNonNull(dlssQuality);
        if(samplesPerPixel<1 || samplesPerPixel>64) throw new IllegalArgumentException("samplesPerPixel must be in [1,64]");
        if(maxBounces<1 || maxBounces>64 || diffuseBounces<0 || diffuseBounces>maxBounces)
            throw new IllegalArgumentException("Invalid native path depth");
        if((restirDi || restirGi) && samplesPerPixel!=1)
            throw new IllegalArgumentException("RTXPT ReSTIR uses one sample per pixel");
    }
    /** Official realtime path: 1 SPP, DLSS-RR Balanced, SHaRC, SER and Reflex, FG off. */
    public static RtxptOptions recommended() {
        return new RtxptOptions(1,20,2,Reconstruction.DLSS_RR,DlssQuality.BALANCED,true,false,false,true,true,true);
    }
    public static RtxptOptions nativeOneSample() {
        return new RtxptOptions(1,20,2,Reconstruction.NONE,DlssQuality.DLAA,false,false,false,false,true,false);
    }
    static RendererFeatureProfile profile(RendererConfig c){return new RendererFeatureProfile(c.frameReconstruction(),c.frameGeneration(),c.lowLatency(),c.denoising(),c.rayTracingOptimizations(),c.pathTracing());}
    static RtxptOptions resolve(RendererFeatureProfile p) {
        if(p.frameGeneration().preference()==RendererFeaturePreference.REQUIRED)
            throw new IllegalArgumentException("RTXPT frame generation is not exposed by this host");
        if(p.rayTracingOptimizations().memoryOptimization()==RendererFeaturePreference.REQUIRED)
            throw new IllegalArgumentException("RTXMU memory optimization belongs to the Vulkan backend");
        var r=p.frameReconstruction();
        boolean spatial=r.mode()==FrameReconstructionOptions.Mode.SPATIAL_UPSCALING;
        if(spatial && r.preference()==RendererFeaturePreference.REQUIRED)
            throw new IllegalArgumentException("RTXPT does not expose a spatial reconstruction path");
        boolean reconstruct=r.preference().requested()&&!spatial,denoise=p.denoising().preference().requested();
        var path=p.pathTracing();
        boolean di=path.directLightResampling().requested(),gi=path.indirectLightResampling().requested();
        // Use upstream's supported default combinations. ReSTIR uses standalone NRD + DLSS.
        var aa=reconstruct?(denoise&&!di&&!gi?Reconstruction.DLSS_RR:Reconstruction.DLSS):Reconstruction.NONE;
        var quality=r.mode()==FrameReconstructionOptions.Mode.NATIVE_ANTI_ALIASING?DlssQuality.DLAA:switch(r.quality()) {
            case AUTO,BALANCED->DlssQuality.BALANCED;case QUALITY->DlssQuality.QUALITY;
            case PERFORMANCE->DlssQuality.PERFORMANCE;case ULTRA_PERFORMANCE->DlssQuality.ULTRA_PERFORMANCE;
            case ULTRA_QUALITY->DlssQuality.ULTRA_QUALITY;
        };
        return new RtxptOptions(path.samplesPerPixel(),path.maxBounces(),path.diffuseBounces(),aa,quality,denoise,di,gi,
            path.radianceCache().requested(),p.rayTracingOptimizations().shaderExecutionReordering().requested(),p.lowLatency().preference().requested());
    }
    String[] arguments() {
        var a=new java.util.ArrayList<>(List.of("--realtimeSamplesPerPixel",Integer.toString(samplesPerPixel),
            "--realtimeAA",Integer.toString(reconstruction.ordinal()),"--standaloneDenoiser="+(standaloneDenoiser?1:0),
            "--useReSTIRDI="+(restirDi?1:0),"--useReSTIRGI="+(restirGi?1:0),
            "--overrideRadianceCacheMode",sharc?"1":"0","--overrideDLSSMode",Integer.toString(dlssQuality.nativeValue),
            "--overrideSubSamplingMode","0","--overrideAutoexposureOff"));
        if(!ser) a.add("--noSER");
        return a.toArray(String[]::new);
    }
}
