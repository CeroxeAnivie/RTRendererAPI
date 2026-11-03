// Opt-in DEEP execution: same work/formulas with explicit receiver storage and
// dispatch boundaries. Measures altered scheduling; never called in production mode.
#define RTR_NATIVE_LIGHTING_LIBRARY
#include "RtrNativeLighting.hlsl"
struct RtrDeepReceiver {RtrReceiver receiver;uint valid;};
RWStructuredBuffer<RtrDeepReceiver> u_Receivers : register(u5);
[numthreads(8,8,1)]
void main(uint2 pixel:SV_DispatchThreadID)
{
    if(any(pixel>=g.grid.xy))return;uint index=pixel.y*g.grid.x+pixel.x,phase=g.publication.w>>8;
    if(phase==1){
        RtrCount(0);float2 ndc=(float2(pixel)+.5)/float2(g.grid.xy)*float2(2,-2)+float2(-1,1)+g.farJitter.yz;
        float4 nearView=mul(g.inverseClip,float4(ndc,g.cameraNear.w,1)),farView=mul(g.inverseClip,float4(ndc,g.farJitter.x,1));
        float3 direction=RtrNormalize(mul((float3x3)g.cameraToWorld,farView.xyz/farView.w-nearView.xyz/nearView.w));
        RtrDeepReceiver value=(RtrDeepReceiver)0;value.valid=RtrTrace(g.cameraNear.xyz,direction,1e20,1,value.receiver)?1:0;
        if(value.valid)RtrCount(1);u_Receivers[index]=value;
        if((g.grid.z&4)!=0)u_Geometry[pixel]=value.valid?float4(value.receiver.normal,value.receiver.distance):0;
        if((g.grid.z&8)!=0)u_Identity[pixel]=value.valid?uint4(value.receiver.instance,value.receiver.geometry,value.receiver.primitive,1):uint4(0xffffffff,0xffffffff,0xffffffff,0);
    }else{
        RtrDeepReceiver value=u_Receivers[index];float coverage=value.valid?value.receiver.opacity:0;
        if(phase==2){float3 gain=value.valid?RtrDirectGain(value.receiver):1;
            gain=float3(isfinite(gain.x)?clamp(gain.x,0,65504):1,isfinite(gain.y)?clamp(gain.y,0,65504):1,isfinite(gain.z)?clamp(gain.z,0,65504):1);
            u_Direct[pixel]=float4(gain,coverage);
        }else if(phase==3){float3 gi=value.valid?RtrOneBounce(value.receiver):0;
            gi=float3(isfinite(gi.x)?clamp(gi.x,0,65504):0,isfinite(gi.y)?clamp(gi.y,0,65504):0,isfinite(gi.z)?clamp(gi.z,0,65504):0);
            u_Indirect[pixel]=float4(gi,coverage);
        }
    }
}
