#ifndef RTR_NATIVE_LIGHTING_STYLE_V1
#define RTR_NATIVE_LIGHTING_STYLE_V1
// Shader ABI v1, mirrored by NativeLightingStyle. Parameters remain dynamic.
struct RtrLightingStyle
{
    float4 correction; // local dim, local boost, daylight dim, moonlight dim
    float4 moonAndMode; // moon RGB, 0=dynamic-only-v1 / 1=classic-all-local-v1
    float4 limits; // minimum gain, maximum gain, maximum GI, reserved
};
float3 RtrStyleGain(RtrLightingStyle style,float2 baseline,float visibility,float fog,
    float3 local,float3 dynamic,float daylight,float3 sunVisibility,float moonlight,float3 moonVisibility)
{
    float influence=visibility*(1-fog),remaining=1-baseline.x;
    float3 increase=style.moonAndMode.w==0?dynamic:local;
    float3 gain=1-style.correction.x*(baseline.x-min(local,baseline.x))*influence
        +style.correction.y*max(increase-baseline.x,0)*remaining*influence
        -style.correction.z*daylight*baseline.y*remaining*influence*(1-sunVisibility)
        -style.correction.w*moonlight*baseline.y*remaining*influence*style.moonAndMode.rgb*(1-moonVisibility);
    return clamp(gain,style.limits.x,style.limits.y);
}
float RtrEncodeGainChannel(float gain){float g=clamp(gain,0,15.65);return (g<=1?g*128:128+32*log2(max(g,1)))/255;}
float3 RtrEncodeGain(RtrLightingStyle style,float3 gain){return float3(RtrEncodeGainChannel(gain.x),RtrEncodeGainChannel(gain.y),RtrEncodeGainChannel(gain.z));}
float3 RtrEncodeGi(RtrLightingStyle style,float3 gi){return sqrt(saturate(gi/style.limits.z));}
#endif
