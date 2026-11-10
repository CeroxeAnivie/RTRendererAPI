package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Immutable display-domain host lighting response. The 16x16 RGB samples are
 * indexed as {@code (sky*16+local)*3+channel}; both axes represent normalized
 * host lighting levels. Values are finite in [0,1] and bilinearly interpolated.
 * A host supplies its already-resolved current response, including its own
 * exposure and display controls. This table is independent of application data.
 * A multiplicative gain cannot recover nonzero color from an exactly black
 * host image. A zero-to-positive response uses the declared finite maximum gain;
 * zero-to-zero is neutral. PARTIAL_SKY instead keeps every zero denominator
 * neutral, including zero-to-positive. Hosts requiring exact recovery must retain a nonzero
 * base response or use an additive/full-color output contract.
 */
public record NativeLightingHostResponse(float[] values) {
    public static final int SAMPLES=16*16*3;
    public NativeLightingHostResponse {
        values=Objects.requireNonNull(values,"values").clone();
        if(values.length!=SAMPLES)throw new IllegalArgumentException("Host response requires 16x16 RGB values");
        for(float value:values)unit(value);
    }
    @Override public float[] values(){return values.clone();}
    public float sample(int channel,float local,float sky) {
        if(channel<0||channel>2)throw new IllegalArgumentException("RGB channel must be 0, 1 or 2");
        float x=unit(local)*15,y=unit(sky)*15;
        int x0=(int)x,y0=(int)y,x1=Math.min(x0+1,15),y1=Math.min(y0+1,15);
        float a=values[(y0*16+x0)*3+channel],b=values[(y0*16+x1)*3+channel];
        float c=values[(y1*16+x0)*3+channel],d=values[(y1*16+x1)*3+channel];
        return (a+(b-a)*(x-x0))*(1-(y-y0))+(c+(d-c)*(x-x0))*(y-y0);
    }
    /** CPU reference for HOST_RESPONSE_RATIO_V1, for one RGB channel. */
    public float directGain(NativeLightingStyle style,int channel,float baselineLocal,float baselineSky,
            float visibility,float fog,float local,float dynamic,boolean staticCovered,
            float directionalWeight,float directionalVisibility) {
        Objects.requireNonNull(style,"style");
        unit(baselineLocal);unit(baselineSky);unit(visibility);unit(fog);unit(directionalWeight);unit(directionalVisibility);
        if(!Float.isFinite(local)||local<0||!Float.isFinite(dynamic)||dynamic<0)
            throw new IllegalArgumentException("Local contributions must be finite and nonnegative");
        float targetLocal=staticCovered?Math.clamp(local,0,1):Math.max(baselineLocal,Math.clamp(dynamic,0,1));
        float targetSky=baselineSky*(1-directionalWeight*(1-directionalVisibility));
        float before=sample(channel,baselineLocal,baselineSky),after=sample(channel,targetLocal,targetSky);
        float ratio=before>0?after/before:after==0?1:style.maximumGain();
        return Math.clamp(1+(ratio-1)*visibility*(1-fog),style.minimumGain(),style.maximumGain());
    }
    /** Partial replacement of directional sky response; static-support and dynamic-source rules remain unchanged. */
    public float directGainPartialSky(NativeLightingStyle style,int channel,float baselineLocal,float baselineSky,
            float visibility,float fog,float local,float dynamic,boolean staticCovered,
            float daylight,float sunVisibility,float moonlight,float moonVisibility) {
        Objects.requireNonNull(style,"style");
        unit(baselineLocal);unit(baselineSky);unit(visibility);unit(fog);unit(daylight);unit(sunVisibility);unit(moonlight);unit(moonVisibility);
        if(!Float.isFinite(local)||local<0||!Float.isFinite(dynamic)||dynamic<0)
            throw new IllegalArgumentException("Local contributions must be finite and nonnegative");
        float targetLocal=staticCovered?Math.clamp(local,0,1):Math.max(baselineLocal,Math.clamp(dynamic,0,1));
        float moonColor=channel==0?style.moonRed():channel==1?style.moonGreen():style.moonBlue();
        float blocked=(1-baselineLocal)*(style.daylightDimming()*daylight*(1-sunVisibility)
            +style.moonlightDimming()*moonlight*moonColor*(1-moonVisibility));
        float targetSky=baselineSky*(1-Math.clamp(blocked,0,1));
        float before=sample(channel,baselineLocal,baselineSky),after=sample(channel,targetLocal,targetSky);
        float ratio=before>0?after/before:1;
        return Math.clamp(1+(ratio-1)*visibility*(1-fog),style.minimumGain(),style.maximumGain());
    }
    private static float unit(float value) {
        if(!Float.isFinite(value)||value<0||value>1)throw new IllegalArgumentException("Host response values and coordinates must be in [0,1]");
        return value;
    }
}
