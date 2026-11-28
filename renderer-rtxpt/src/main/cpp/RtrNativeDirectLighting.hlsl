// Receiver outputs without hemispherical GI. The optional indirect plane is
// initialized here for independent transport passes (e.g. dielectric flux).
#define RTR_DIRECT_ONLY 1
#define RTR_NATIVE_LIGHTING_LIBRARY
#include "RtrNativeLighting.hlsl"

[numthreads(8,8,1)]
void main(uint2 pixel:SV_DispatchThreadID)
{
    if(any(pixel>=g.grid.xy))return;
    RtrSetSourcePixel(pixel);
    float2 ndc=(float2(pixel)+.5)/float2(g.grid.xy)*float2(2,-2)+float2(-1,1)+g.farJitter.yz;
    float4 nearView=mul(g.inverseClip,float4(ndc,g.cameraNear.w,1)),farView=mul(g.inverseClip,float4(ndc,g.farJitter.x,1));
    float3 direction=RtrNormalize(mul((float3x3)g.cameraToWorld,farView.xyz/farView.w-nearView.xyz/nearView.w));
    RtrReceiver receiver;
    bool valid=RtrTrace(g.cameraNear.xyz,direction,1e20,1,receiver);
#ifndef RTR_RECEIVER_SIGNALS_ONLY
    if((g.grid.z&1)!=0){
        float3 gain=1;
        if(valid){RtrPrepareCoverage(receiver,pixel);gain=RtrDirectGain(receiver);}
        gain=float3(isfinite(gain.x)?clamp(gain.x,0,65504):1,isfinite(gain.y)?clamp(gain.y,0,65504):1,isfinite(gain.z)?clamp(gain.z,0,65504):1);
        u_Direct[pixel]=float4(gain,RtrDirectAlpha(valid,receiver.opacity));
    }
    RtrStoreDirectionalSpecular(pixel,valid,receiver.opacity);
    RtrStoreSunEvidence(pixel,valid,receiver);
#endif
    if((g.grid.z&2)!=0)u_Indirect[pixel]=float4(0,0,0,valid?receiver.opacity:0);
    if((g.grid.z&4)!=0)u_Geometry[pixel]=valid?float4(receiver.normal,receiver.distance):0;
    if((g.grid.z&8)!=0)u_Identity[pixel]=valid?uint4(receiver.instance,receiver.geometry,receiver.primitive,1):uint4(0xffffffff,0xffffffff,0xffffffff,0);
}
