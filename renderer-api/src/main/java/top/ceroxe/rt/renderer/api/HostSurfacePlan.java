package top.ceroxe.rt.renderer.api;

import java.util.List;
import java.util.Objects;

/** Versioned world-space primary-surface inputs, distinct from output lighting planes. */
public record HostSurfacePlan(int width,int height,Composition composition,int preservedHostComponents,
        boolean motionIncludesJitter,long maximumResidentBytes,int outputSignals) {
    public static final int ABI_VERSION=1,SLOTS=3,BYTES_PER_PIXEL=80,BASE_PLANE_COUNT=8,
        AUGMENTATION_PLANE_COUNT=10,AUGMENTATION_BYTES_PER_PIXEL=96;
    public static final long DEFAULT_MAXIMUM_RESIDENT_BYTES=2L*1024*1024*1024;
    /**
     * Ownership of the supplied primary surface's lighting. DIRECT includes
     * analytic/sparse/emissive sources sampled by either NEE or BSDF; AMBIENT
     * includes constant ambient and direct environment illumination. Their
     * secondary multi-bounce contributions remain in transport. EMISSION
     * suppresses only primary self-emission, retaining secondary emitters.
     * Split transport applies neither an extra host AO nor host fog term.
     */
    public static final int HOST_DIRECT=1,HOST_AMBIENT=2,HOST_AO=4,HOST_EMISSION=8,HOST_FOG=16;
    /**
     * Requested outputs. The five radiance AOVs include their BSDF/albedo/path
     * weights exactly once and sum to raw surface radiance. Separate AOVs are
     * raw estimates; reconstruction applies only to COMBINED. RGB is straight
     * scene-linear radiance with coverage in alpha, not premultiplied RGB.
     */
    public static final int COMBINED=1,DIRECT_MODULATION=2,DIFFUSE_DIRECT=4,DIFFUSE_INDIRECT=8,
        SPECULAR=16,TRANSMISSION=32,EMISSION=64,COVERAGE=128,ALL_SIGNALS=255;
    /** Float position.w stores these numeric integer bits, never an asfloat bit cast. */
    public static final int VALID=1,OPAQUE=2,PRIMARY_FALLBACK=4,BACKGROUND=8;
    public enum Composition { HOST_LIT_AUGMENTATION, SPLIT_SURFACE_LIGHTING }
    public enum Format {
        RGBA16_FLOAT(8),RGBA32_FLOAT(16),RG32_FLOAT(8),RGBA32_UINT(16);
        private final int bytes;
        Format(int bytes){this.bytes=bytes;}
        public int bytesPerPixel(){return bytes;}
    }
    /** Fixed ABI order. Color is linear; normals/positions are world-space; motion is top-left pixels. */
    public enum Plane {
        WORLD_POSITION_FLAGS(Format.RGBA32_FLOAT),
        GEOMETRIC_NORMAL_COVERAGE(Format.RGBA16_FLOAT),
        SHADING_NORMAL_ALPHA_CUTOFF(Format.RGBA16_FLOAT),
        BASE_COLOR_OPACITY(Format.RGBA16_FLOAT),
        MATERIAL(Format.RGBA16_FLOAT),
        EMISSION(Format.RGBA16_FLOAT),
        MOTION(Format.RG32_FLOAT),
        IDENTITY(Format.RGBA32_UINT),
        HOST_LIGHTING_BASELINE(Format.RGBA16_FLOAT),
        HOST_LIT_COLOR(Format.RGBA16_FLOAT);
        private final Format format;
        Plane(Format format){this.format=format;}
        public Format format(){return format;}
    }
    public HostSurfacePlan {
        if(width<1||height<1||width>16384||height>16384)throw new IllegalArgumentException("Host-surface extent must be in [1,16384]");
        Objects.requireNonNull(composition);
        if(outputSignals<1||(outputSignals&~ALL_SIGNALS)!=0)throw new IllegalArgumentException("Select known host-surface output signals");
        if(preservedHostComponents<0||(preservedHostComponents&~31)!=0)throw new IllegalArgumentException("Unknown preserved host lighting component");
        if(maximumResidentBytes<65536||maximumResidentBytes>DEFAULT_MAXIMUM_RESIDENT_BYTES)
            throw new IllegalArgumentException("Host-surface texture budget must be in [64 KiB,2 GiB]");
        if((long)width*height*(composition==Composition.HOST_LIT_AUGMENTATION?AUGMENTATION_BYTES_PER_PIXEL:BYTES_PER_PIXEL)*SLOTS>maximumResidentBytes)
            throw new IllegalArgumentException("Three host-surface input slots exceed the requested texture budget");
    }
    public HostSurfacePlan(int width,int height,Composition composition,int preservedHostComponents,
            boolean motionIncludesJitter,long maximumResidentBytes){
        this(width,height,composition,preservedHostComponents,motionIncludesJitter,maximumResidentBytes,COMBINED);
    }
    public HostSurfacePlan(int width,int height){this(width,height,Composition.SPLIT_SURFACE_LIGHTING,0,false,DEFAULT_MAXIMUM_RESIDENT_BYTES);}
    public static HostSurfacePlan augmentLitBase(int width,int height,int alreadyIncludedComponents){
        return new HostSurfacePlan(width,height,Composition.HOST_LIT_AUGMENTATION,alreadyIncludedComponents,false,DEFAULT_MAXIMUM_RESIDENT_BYTES);
    }
    public List<Plane> planes(){return List.of(Plane.values()).subList(0,composition==Composition.HOST_LIT_AUGMENTATION?AUGMENTATION_PLANE_COUNT:BASE_PLANE_COUNT);}
    public HostSurfacePlan withOutputSignals(int signals){
        return new HostSurfacePlan(width,height,composition,preservedHostComponents,motionIncludesJitter,maximumResidentBytes,signals);
    }
    public long logicalBytesPerSlot(){return (long)width*height*(composition==Composition.HOST_LIT_AUGMENTATION?AUGMENTATION_BYTES_PER_PIXEL:BYTES_PER_PIXEL);}
}
