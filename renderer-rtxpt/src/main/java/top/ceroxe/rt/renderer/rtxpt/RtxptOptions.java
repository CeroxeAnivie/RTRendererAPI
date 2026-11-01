package top.ceroxe.rt.renderer.rtxpt;

import java.util.List;
import java.util.Objects;

/** Native RTXPT controls, applied before the first frame. No second transport implementation. */
public record RtxptOptions(int samplesPerPixel, int maxBounces, int diffuseBounces,
        Reconstruction reconstruction, DlssQuality dlssQuality, boolean standaloneDenoiser,
        boolean restirDi, boolean restirGi, boolean sharc, boolean ser, boolean reflex) {
    public enum Reconstruction { NONE, TAA, DLSS, DLSS_RR }
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
    String[] arguments() {
        var a=new java.util.ArrayList<>(List.of("--realtimeSamplesPerPixel",Integer.toString(samplesPerPixel),
            "--realtimeAA",Integer.toString(reconstruction.ordinal()),"--standaloneDenoiser="+standaloneDenoiser,
            "--useReSTIRDI="+restirDi,"--useReSTIRGI="+restirGi,
            "--overrideRadianceCacheMode",sharc?"1":"0","--overrideDLSSMode",Integer.toString(dlssQuality.nativeValue),
            "--overrideSubSamplingMode","0","--overrideAutoexposureOff"));
        if(!ser) a.add("--disableSER");
        return a.toArray(String[]::new);
    }
}
