package top.ceroxe.rt.renderer.api;

import java.util.Objects;
import java.util.Set;

/** Requested independent receiver grid and style signal budget. Grid extent never changes camera projection. */
public record NativeLightingPlan(int width,int height,Set<Signal> signals,int giSamples,float giDistance,
        float giStrength,NativeLightingStyle style,Directional sun,Directional moon,boolean opaqueFastPath,Encoding encoding) {
    public enum Signal { DIRECT_GAIN, INDIRECT_DIFFUSE, NORMAL_DISTANCE, RECEIVER_IDENTITY }
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
    public NativeLightingPlan {
        if(width<1||height<1||width>16384||height>16384)throw new IllegalArgumentException("Lighting grid must be in [1,16384] on each axis");
        signals=Set.copyOf(Objects.requireNonNull(signals));Objects.requireNonNull(style);Objects.requireNonNull(encoding);
        if(style.maximumGain()>65504)throw new IllegalArgumentException("Native gain must fit the declared RGBA16_FLOAT output range");
        if(encoding==Encoding.COMPACT_UNORM8&&style.maximumGain()>15.65f)
            throw new IllegalArgumentException("Compact gain supports a declared maximum of 15.65");
        if(encoding==Encoding.COMPACT_RESPONSE_UNORM8&&style.maximumGain()>255)
            throw new IllegalArgumentException("Compact response gain supports a declared maximum of 255");
        if(signals.isEmpty())throw new IllegalArgumentException("Request at least one lighting signal");
        if(giSamples<0||giSamples>8||!Float.isFinite(giDistance)||giDistance<=0||!Float.isFinite(giStrength)||giStrength<0)
            throw new IllegalArgumentException("GI requires 0..8 samples, positive finite distance and nonnegative finite strength");
    }
    public NativeLightingPlan(int width,int height,Set<Signal> signals,int giSamples,float giDistance,float giStrength,
            NativeLightingStyle style,Directional sun,Directional moon,boolean opaqueFastPath){
        this(width,height,signals,giSamples,giDistance,giStrength,style,sun,moon,opaqueFastPath,Encoding.HDR_FLOAT16);
    }
    public NativeLightingPlan withEncoding(Encoding value){
        return new NativeLightingPlan(width,height,signals,giSamples,giDistance,giStrength,style,sun,moon,opaqueFastPath,value);
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
