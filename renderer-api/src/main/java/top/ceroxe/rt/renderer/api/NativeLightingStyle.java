package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Version 1 of the explicit, display-referred lighting correction contract.
 * This is a style model, not a radiometric BSDF. B and S are interpolated host
 * baseline coordinates in [0,1]. L is the visible per-channel maximum of static
 * and dynamic local sources; D contains only dynamic sources. V and F are host
 * visibility and fog weights. The API applies I=V*(1-F) once to correction and
 * GI; the consumer must not apply those weights again.
 *
 * <p>Floating outputs contain gain directly, and GI as linear RGB outgoing
 * contribution with receiver albedo already applied once. Compact outputs use
 * a piecewise gain code (128*gain below 1, 128+32*log2(gain) above 1), with gain
 * clamped to [0,15.65], and sqrt(clamp(GI/maximumGi,0,1)), rounded to UNORM8.
 * Neutral gain has exact byte code 128. GI is in the declared host working domain;
 * sRGB decoding a tone-mapped host image does not turn it into scene-linear HDR.</p>
 *
 * <p>HOST_RESPONSE_RATIO_V1 instead evaluates a published
 * {@link NativeLightingHostResponse}. Covered static sources replace the baked
 * local level; without static support the original level is preserved and raised
 * only by dynamic sources. The final response ratio receives visibility/fog
 * once. The legacy correction coefficients are not applied in that mode.</p>
 */
public record NativeLightingStyle(AdditionMode additionMode,float localDimming,float localBoost,
        float daylightDimming,float moonlightDimming,float moonRed,float moonGreen,float moonBlue,
        float minimumGain,float maximumGain,float maximumGi) {
    public static final int ABI_VERSION=1;
    public enum AdditionMode { DYNAMIC_ONLY_V1, CLASSIC_ALL_LOCAL_V1, HOST_RESPONSE_RATIO_V1 }
    public NativeLightingStyle {
        Objects.requireNonNull(additionMode,"additionMode");
        for(float v:new float[]{localDimming,localBoost,daylightDimming,moonlightDimming,moonRed,moonGreen,moonBlue,
                minimumGain,maximumGain,maximumGi})
            if(!Float.isFinite(v)||v<0)throw new IllegalArgumentException("Lighting style parameters must be finite and nonnegative");
        if(minimumGain>1||maximumGain<1||minimumGain>maximumGain||maximumGi<=0)
            throw new IllegalArgumentException("Gain range must include neutral 1; maximum GI must be positive");
    }
    public static NativeLightingStyle defaults() {
        return new NativeLightingStyle(AdditionMode.DYNAMIC_ONLY_V1,.75f,12,.55f,.18f,.8f,.9f,1,0,15.65f,1);
    }
    public NativeLightingStyle withAdditionMode(AdditionMode mode) {
        return new NativeLightingStyle(mode,localDimming,localBoost,daylightDimming,moonlightDimming,
            moonRed,moonGreen,moonBlue,minimumGain,maximumGain,maximumGi);
    }
    /** CPU reference, one RGB channel. All values are linear numerical values in the declared host domain. */
    public float directGain(int channel,float baselineLocal,float baselineSky,float visibility,float fog,
            float local,float dynamic,float daylight,float sunVisibility,float moonlight,float moonVisibility) {
        if(additionMode==AdditionMode.HOST_RESPONSE_RATIO_V1)
            throw new IllegalStateException("HOST_RESPONSE_RATIO_V1 requires NativeLightingHostResponse.directGain");
        if(channel<0||channel>2)throw new IllegalArgumentException("RGB channel must be 0, 1 or 2");
        for(float v:new float[]{baselineLocal,baselineSky,visibility,fog,daylight,sunVisibility,moonlight,moonVisibility})
            if(!Float.isFinite(v)||v<0||v>1)throw new IllegalArgumentException("Baseline, visibility, fog and directional weights must be in [0,1]");
        if(!Float.isFinite(local)||local<0||!Float.isFinite(dynamic)||dynamic<0)
            throw new IllegalArgumentException("Local contributions must be finite and nonnegative");
        float influence=visibility*(1-fog),remaining=1-baselineLocal;
        float increase=additionMode==AdditionMode.DYNAMIC_ONLY_V1?dynamic:local;
        float moonColor=channel==0?moonRed:channel==1?moonGreen:moonBlue;
        float gain=1-localDimming*(baselineLocal-Math.min(local,baselineLocal))*influence
            +localBoost*Math.max(increase-baselineLocal,0)*remaining*influence
            -daylightDimming*daylight*baselineSky*remaining*influence*(1-sunVisibility)
            -moonlightDimming*moonlight*baselineSky*remaining*influence*moonColor*(1-moonVisibility);
        return Math.clamp(gain,minimumGain,maximumGain);
    }
    public int encodeGain8(float gain) {
        float g=Math.clamp(finiteNonnegative(gain),0,15.65f);
        return Math.clamp(Math.round(g<=1?128*g:128+32*(float)(Math.log(g)/Math.log(2))),0,255);
    }
    /** Encoding-specific compact reference. The legacy format remains unchanged. */
    public int encodeGain8(float gain,NativeLightingPlan.Encoding encoding) {
        Objects.requireNonNull(encoding,"encoding");
        if(encoding==NativeLightingPlan.Encoding.HDR_FLOAT16)throw new IllegalArgumentException("HDR gain is not UNORM8 encoded");
        if(encoding==NativeLightingPlan.Encoding.COMPACT_UNORM8)return encodeGain8(gain);
        float g=Math.clamp(finiteNonnegative(gain),0,255);
        return Math.clamp(Math.round(g<=1?128*g:128+127*(float)(Math.log(g)/Math.log(255))),0,255);
    }
    public float decodeGain8(int code,NativeLightingPlan.Encoding encoding) {
        Objects.requireNonNull(encoding,"encoding");
        if(encoding==NativeLightingPlan.Encoding.HDR_FLOAT16)throw new IllegalArgumentException("HDR gain is not UNORM8 encoded");
        if(encoding==NativeLightingPlan.Encoding.COMPACT_UNORM8)return decodeGain8(code);
        return checkedCode(code)<=128?code/128f:(float)Math.pow(255,(code-128)/127f);
    }
    public float decodeGain8(int code) {return checkedCode(code)<=128?code/128f:(float)Math.pow(2,(code-128)/32f);}
    public int encodeGi8(float contribution) {return unorm8((float)Math.sqrt(Math.clamp(finiteNonnegative(contribution)/maximumGi,0,1)));}
    public float decodeGi8(int code) {float v=checkedCode(code)/255f;return v*v*maximumGi;}
    private static int unorm8(float v) {return Math.round(Math.clamp(v,0,1)*255);}
    private static int checkedCode(int code) {if(code<0||code>255)throw new IllegalArgumentException("UNORM8 code out of range");return code;}
    private static float finiteNonnegative(float v) {if(!Float.isFinite(v)||v<0)throw new IllegalArgumentException("Signal must be finite and nonnegative");return v;}
}
