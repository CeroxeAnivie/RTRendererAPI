package top.ceroxe.rt.renderer.api;

import java.util.Objects;
import java.util.Set;

/** Requested independent receiver grid and style signal budget. Grid extent never changes camera projection. */
public record NativeLightingPlan(int width,int height,Set<Signal> signals,int giSamples,float giDistance,
        float giStrength,NativeLightingStyle style,Directional sun,Directional moon,boolean opaqueFastPath,Encoding encoding,DirectAlphaMode directAlphaMode,float sunAngularRadius,float surfaceWetness,SkyOcclusion skyOcclusion,WetFilmRoughnessResponse wetFilmRoughnessResponse) {
    public enum Signal {
        DIRECT_GAIN, INDIRECT_DIFFUSE, NORMAL_DISTANCE, RECEIVER_IDENTITY,
        /** Scene-linear directional highlights and optional wet-surface sky sheen; alpha is receiver opacity. RGBA16_FLOAT. */
        DIRECTIONAL_SPECULAR,
        /** Dominant directional light: R=-1 blocked/+1 visible/0 unclassified; G=half-width in grid texels, zero without reliable evidence.
         * BA is the unit screen-space normal toward light times (signed grid distance + 4*R). RGBA16_FLOAT. */
        SUN_SHADOW_PENUMBRA,
        /** RGBA32_FLOAT: scene-linear radiance in RGB, first dielectric hit distance along the camera ray in A (world units; zero means invalid). */
        DIELECTRIC_RADIANCE
    }
    public enum DirectAlphaMode {
        /** Alpha is receiver opacity, zero for a missing receiver. */
        OPACITY,
        /**
         * Alpha is reconstruction metadata: ordinary=255, integrated coverage=64,
         * unresolved coverage=0, and non-reusable dynamic coverage=128.
         * Every masked texture with multiple mips must publish coverage bounds:
         * every level stores coverage at the material cutoff in R, binary-alpha
         * validity in G, and the layout-valid marker in B; level zero retains
         * original alpha in A. G and B must both be one for integrated coverage.
         * Ordinary color mip chains must not opt into this contract.
         */
        RECONSTRUCTION_COVERAGE_V1
    }
    /** Compact response extends the upper range to 255: neutral is code 128,
     * low codes are 128*gain and high codes are 128+127*log(gain)/log(255).
     * COMPACT_UNORM8 retains its original 15.65 maximum and 32*log2 slope. */
    public enum Encoding { HDR_FLOAT16,COMPACT_UNORM8,COMPACT_RESPONSE_UNORM8 }
    /** Toward-source normalized direction, style weight in [0,1], and linear RGB. */
    public record Directional(float x,float y,float z,float weight,float red,float green,float blue) {
        public Directional {
            double length=(double)x*x+(double)y*y+(double)z*z;
            if(!Double.isFinite(length)||Math.abs(length-1)>.001||!Float.isFinite(weight)||weight<0||weight>1)throw new IllegalArgumentException("Directional style requires normalized direction and weight in [0,1]");
            for(float v:new float[]{red,green,blue})if(!Float.isFinite(v)||v<0)throw new IllegalArgumentException("Invalid directional RGB");
        }
    }
    /** Local obscurance of diffuse sky, not a directional emitter. Strength in [0,1], reach in world units. */
    public record SkyOcclusion(float strength,float reach) {
        public SkyOcclusion {
            if(!Float.isFinite(strength)||strength<0||strength>1||!Float.isFinite(reach)||reach<=0)
                throw new IllegalArgumentException("Invalid sky occlusion strength or reach");
        }
    }
    /** Host-selected multiplier applied to wet-film reflection over a roughness interval. */
    public record WetFilmRoughnessResponse(float start,float end,float scale) {
        public WetFilmRoughnessResponse {
            if(!Float.isFinite(start)||!Float.isFinite(end)||start<0||end>1||start>=end
                    ||!Float.isFinite(scale)||scale<0||scale>65504)
                throw new IllegalArgumentException("Invalid wet-film roughness response");
        }
        public static WetFilmRoughnessResponse neutral(){return new WetFilmRoughnessResponse(0,1,1);}
    }
    public NativeLightingPlan {
        if(width<1||height<1||width>16384||height>16384)throw new IllegalArgumentException("Lighting grid must be in [1,16384] on each axis");
        Objects.requireNonNull(wetFilmRoughnessResponse);
        signals=Set.copyOf(Objects.requireNonNull(signals));Objects.requireNonNull(style);Objects.requireNonNull(encoding);Objects.requireNonNull(directAlphaMode);
        if(style.maximumGain()>65504)throw new IllegalArgumentException("Native gain must fit the declared RGBA16_FLOAT output range");
        if(encoding==Encoding.COMPACT_UNORM8&&style.maximumGain()>15.65f)
            throw new IllegalArgumentException("Compact gain supports a declared maximum of 15.65");
        if(encoding==Encoding.COMPACT_RESPONSE_UNORM8&&style.maximumGain()>255)
            throw new IllegalArgumentException("Compact response gain supports a declared maximum of 255");
        if(signals.isEmpty())throw new IllegalArgumentException("Request at least one lighting signal");
        if(signals.contains(Signal.DIRECTIONAL_SPECULAR)&&!signals.contains(Signal.DIRECT_GAIN))
            throw new IllegalArgumentException("Directional specular reuses the visibility queries of DIRECT_GAIN");
        if(signals.contains(Signal.SUN_SHADOW_PENUMBRA)&&!signals.contains(Signal.DIRECT_GAIN))
            throw new IllegalArgumentException("Directional penumbra reuses the visibility queries of DIRECT_GAIN");
        if(skyOcclusion!=null&&!signals.contains(Signal.DIRECT_GAIN))
            throw new IllegalArgumentException("Sky occlusion applies to DIRECT_GAIN");
        if(!Float.isFinite(sunAngularRadius)||sunAngularRadius<0||sunAngularRadius>=Math.PI/2)
            throw new IllegalArgumentException("Sun angular radius must be finite radians in [0, pi/2)");
        if(!Float.isFinite(surfaceWetness)||surfaceWetness<0||surfaceWetness>1)
            throw new IllegalArgumentException("Surface wetness must be finite and in [0,1]");
        if(giSamples<0||giSamples>8||!Float.isFinite(giDistance)||giDistance<=0||!Float.isFinite(giStrength)||giStrength<0)
            throw new IllegalArgumentException("GI requires 0..8 samples, positive finite distance and nonnegative finite strength");
    }
    public NativeLightingPlan(int width,int height,Set<Signal> signals,int giSamples,float giDistance,float giStrength,
            NativeLightingStyle style,Directional sun,Directional moon,boolean opaqueFastPath,Encoding encoding,
            DirectAlphaMode directAlphaMode,float sunAngularRadius,float surfaceWetness,SkyOcclusion skyOcclusion){
        this(width,height,signals,giSamples,giDistance,giStrength,style,sun,moon,opaqueFastPath,encoding,directAlphaMode,sunAngularRadius,surfaceWetness,skyOcclusion,WetFilmRoughnessResponse.neutral());
    }
    public NativeLightingPlan(int width,int height,Set<Signal> signals,int giSamples,float giDistance,float giStrength,
            NativeLightingStyle style,Directional sun,Directional moon,boolean opaqueFastPath,Encoding encoding,
            DirectAlphaMode directAlphaMode,float sunAngularRadius,float surfaceWetness){
        this(width,height,signals,giSamples,giDistance,giStrength,style,sun,moon,opaqueFastPath,encoding,directAlphaMode,sunAngularRadius,surfaceWetness,null,WetFilmRoughnessResponse.neutral());
    }
    public NativeLightingPlan(int width,int height,Set<Signal> signals,int giSamples,float giDistance,float giStrength,
            NativeLightingStyle style,Directional sun,Directional moon,boolean opaqueFastPath,Encoding encoding,
            DirectAlphaMode directAlphaMode,float sunAngularRadius){
        this(width,height,signals,giSamples,giDistance,giStrength,style,sun,moon,opaqueFastPath,encoding,directAlphaMode,sunAngularRadius,0);
    }
    public NativeLightingPlan(int width,int height,Set<Signal> signals,int giSamples,float giDistance,float giStrength,
            NativeLightingStyle style,Directional sun,Directional moon,boolean opaqueFastPath,Encoding encoding,DirectAlphaMode directAlphaMode){
        this(width,height,signals,giSamples,giDistance,giStrength,style,sun,moon,opaqueFastPath,encoding,directAlphaMode,.00465f);
    }
    public NativeLightingPlan(int width,int height,Set<Signal> signals,int giSamples,float giDistance,float giStrength,
            NativeLightingStyle style,Directional sun,Directional moon,boolean opaqueFastPath,Encoding encoding){
        this(width,height,signals,giSamples,giDistance,giStrength,style,sun,moon,opaqueFastPath,encoding,DirectAlphaMode.OPACITY);
    }
    public NativeLightingPlan(int width,int height,Set<Signal> signals,int giSamples,float giDistance,float giStrength,
            NativeLightingStyle style,Directional sun,Directional moon,boolean opaqueFastPath){
        this(width,height,signals,giSamples,giDistance,giStrength,style,sun,moon,opaqueFastPath,Encoding.HDR_FLOAT16);
    }
    public NativeLightingPlan withEncoding(Encoding value){
        return new NativeLightingPlan(width,height,signals,giSamples,giDistance,giStrength,style,sun,moon,opaqueFastPath,value,directAlphaMode,sunAngularRadius,surfaceWetness,skyOcclusion,wetFilmRoughnessResponse);
    }
    public NativeLightingPlan withDirectAlphaMode(DirectAlphaMode value){
        return new NativeLightingPlan(width,height,signals,giSamples,giDistance,giStrength,style,sun,moon,opaqueFastPath,encoding,value,sunAngularRadius,surfaceWetness,skyOcclusion,wetFilmRoughnessResponse);
    }
    /** Reference angular radius for the cropped, distant sun/moon disk approximation. */
    public NativeLightingPlan withSunAngularRadius(float radians){
        return new NativeLightingPlan(width,height,signals,giSamples,giDistance,giStrength,style,sun,moon,opaqueFastPath,encoding,directAlphaMode,radians,surfaceWetness,skyOcclusion,wetFilmRoughnessResponse);
    }
    /** Amount of a dielectric film on sky-exposed, upward-facing opaque receivers, zero for dry surfaces. */
    public NativeLightingPlan withSurfaceWetness(float amount){
        return new NativeLightingPlan(width,height,signals,giSamples,giDistance,giStrength,style,sun,moon,opaqueFastPath,encoding,directAlphaMode,sunAngularRadius,amount,skyOcclusion,wetFilmRoughnessResponse);
    }
    public NativeLightingPlan withWetFilmRoughnessResponse(float start,float end,float scale){
        return new NativeLightingPlan(width,height,signals,giSamples,giDistance,giStrength,style,sun,moon,opaqueFastPath,encoding,directAlphaMode,sunAngularRadius,surfaceWetness,skyOcclusion,new WetFilmRoughnessResponse(start,end,scale));
    }
    public NativeLightingPlan withSkyOcclusion(float strength,float reach){
        return new NativeLightingPlan(width,height,signals,giSamples,giDistance,giStrength,style,sun,moon,opaqueFastPath,encoding,directAlphaMode,sunAngularRadius,surfaceWetness,new SkyOcclusion(strength,reach),wetFilmRoughnessResponse);
    }
    /** Null sun uses the frame environment (whose style intensity must be in [0,1]); null moon is disabled. */
    public NativeLightingPlan(int width,int height,Set<Signal> signals,int giSamples,float giDistance,float giStrength,NativeLightingStyle style) {
        this(width,height,signals,giSamples,giDistance,giStrength,style,null,null,true);
    }
    public NativeLightingPlan(int width,int height,Set<Signal> signals,int giSamples,float giDistance,float giStrength,NativeLightingStyle style,Directional sun,Directional moon) {
        this(width,height,signals,giSamples,giDistance,giStrength,style,sun,moon,true);
    }
    public static NativeLightingPlan defaults(int width,int height) {
        return new NativeLightingPlan(width,height,Set.of(Signal.DIRECT_GAIN,Signal.INDIRECT_DIFFUSE),2,6,.35f,NativeLightingStyle.defaults());
    }
    public int signalMask(){int mask=0;for(var signal:signals)mask|=1<<signal.ordinal();return mask;}
}
