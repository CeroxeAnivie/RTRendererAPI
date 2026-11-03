// Host-supplied receiver evaluation. The only rays below are native shadow/bounce
// helper queries; the native camera-primary main is excluded at compile time.
#define RTR_NATIVE_LIGHTING_LIBRARY 1
#include "RtrNativeLighting.hlsl"

struct RtrAugInstance {uint2 id;uint instanceIndex,triangleOffset,triangleCount;uint3 reserved;};
cbuffer RtrAugmentationConstants:register(b1)
{
    uint4 rtrAugExtents; // input width,height; output width,height
    uint4 rtrAugOptions; // output mask, working domain, host-color present, host work counters
    uint4 rtrAugCounts; // stable instance count, mapping triangle count, host components, reserved
};
Texture2D<float4> t_AugPosition:register(t12);
Texture2D<float4> t_AugGeometric:register(t13);
Texture2D<float4> t_AugShading:register(t14);
Texture2D<float4> t_AugAlbedo:register(t15);
Texture2D<float4> t_AugMaterial:register(t16);
Texture2D<float4> t_AugEmission:register(t17);
Texture2D<float4> t_AugBaseline:register(t18); // resolved B,S,V,F, not coordinates or baked RGB
Texture2D<float4> t_AugHostLit:register(t19); // actual pre-fog host layer, never albedo
Texture2D<uint4> t_AugIdentity:register(t20);
StructuredBuffer<RtrAugInstance> t_AugInstances:register(t21);
StructuredBuffer<uint2> t_AugTriangles:register(t22);
RWTexture2D<float4> u_AugCombined:register(u5);
RWTexture2D<float4> u_AugModulation:register(u6);
RWTexture2D<float4> u_AugIndirect:register(u8);
RWTexture2D<float4> u_AugEmission:register(u11);
RWTexture2D<float4> u_AugCoverage:register(u12);
RWByteAddressBuffer u_AugWork:register(u13);

bool RtrAugFind(uint2 id,out RtrAugInstance result)
{
    uint lo=0,hi=rtrAugCounts.x;result=(RtrAugInstance)0;
    while(lo<hi){uint mid=lo+(hi-lo)/2;RtrAugInstance value=t_AugInstances[mid];
        if(value.id.y<id.y||(value.id.y==id.y&&value.id.x<id.x))lo=mid+1;else hi=mid;}
    if(lo>=rtrAugCounts.x)return false;result=t_AugInstances[lo];return all(result.id==id);
}
bool RtrAugLoad(uint2 p,out RtrReceiver receiver,out float coverage)
{
    receiver=(RtrReceiver)0;coverage=0;
    float4 position=t_AugPosition[p];uint flags=isfinite(position.w)&&position.w>=0&&position.w<=15?uint(position.w):0;
    if((flags&8)!=0||(flags&1)==0)return false; // Missing/fallback-requested surfaces stay neutral; no primary trace.
    float4 geometric=t_AugGeometric[p],shading=t_AugShading[p],base=t_AugAlbedo[p],material=t_AugMaterial[p],baseline=t_AugBaseline[p];
    uint4 identity=t_AugIdentity[p];RtrAugInstance instance;
    if(!all(isfinite(position.xyz))||!all(isfinite(geometric))||!all(isfinite(shading))||!all(isfinite(base))
        ||!all(isfinite(material))||!all(isfinite(baseline))||any(baseline<0)||any(baseline>1)
        ||dot(geometric.xyz,geometric.xyz)<.01||dot(shading.xyz,shading.xyz)<.01
        ||geometric.w<=0||geometric.w>1||base.a<=0||base.a>1||shading.a<0||shading.a>1||base.a<shading.a
        ||material.x<0||material.x>1||material.y<0||material.y>1||material.z<1||material.z>4||material.w<0||material.w>1
        ||identity.w!=0||!RtrAugFind(identity.xy,instance)||identity.z>=instance.triangleCount
        ||instance.triangleOffset>rtrAugCounts.y||identity.z>=rtrAugCounts.y-instance.triangleOffset)return false;
    uint2 mappedPrimitive=t_AugTriangles[instance.triangleOffset+identity.z];
    uint roles=uint(t_InstanceAttributes[instance.instanceIndex].rolesVisibility.x);
    if((roles&1)==0)return false;
    receiver.position=position.xyz;receiver.geometricNormal=RtrNormalize(geometric.xyz);receiver.normal=RtrNormalize(shading.xyz);
    float3 towardCamera=g.cameraNear.xyz-position.xyz;
    if((roles&8)!=0&&dot(receiver.geometricNormal,towardCamera)<=0)return false;
    if(dot(receiver.geometricNormal,towardCamera)<0)receiver.geometricNormal=-receiver.geometricNormal;
    if(dot(receiver.normal,receiver.geometricNormal)<0)receiver.normal=-receiver.normal;
    receiver.albedo=max(base.rgb,0);receiver.opacity=base.a;receiver.roughness=material.x;receiver.metalness=material.y;receiver.transmission=material.w;
    receiver.diffuseWeight=(1-material.y)*(1-material.w);receiver.baseline=baseline.xy;receiver.visibility=baseline.z;receiver.fog=baseline.w;
    receiver.distance=length(towardCamera);receiver.instance=instance.instanceIndex;receiver.geometry=mappedPrimitive.x;receiver.primitive=mappedPrimitive.y;
    coverage=saturate(geometric.w*base.a);return true;
}
float3 RtrAugFinite(float3 value,float fallback)
{
    return float3(isfinite(value.x)?clamp(value.x,0,65504):fallback,isfinite(value.y)?clamp(value.y,0,65504):fallback,isfinite(value.z)?clamp(value.z,0,65504):fallback);
}
[numthreads(8,8,1)]
void main(uint2 pixel:SV_DispatchThreadID)
{
    if(any(pixel>=rtrAugExtents.zw))return;
    uint2 p=min(uint2((float2(pixel)+.5)*float2(rtrAugExtents.xy)/float2(rtrAugExtents.zw)),rtrAugExtents.xy-1);
    RtrReceiver receiver;float coverage;bool valid=RtrAugLoad(p,receiver,coverage);float3 gain=1,indirect=0;
    if(rtrAugOptions.w!=0){uint ignored;u_AugWork.InterlockedAdd(valid?0:8,1,ignored);}
    // RtrCount(0) is deliberately absent: supplied receiver evaluation traces zero camera primaries.
    if(valid){RtrCount(1);RtrEvaluateReceiver(receiver,gain,indirect);}
    gain=RtrAugFinite(gain,1);indirect=RtrAugFinite(indirect,0);
    float3 emission=valid?RtrAugFinite(t_AugEmission[p].rgb,0):0;
    uint outputs=rtrAugOptions.x;
    if((outputs&1)!=0){
        float4 host=t_AugHostLit[p];float3 color=RtrAugFinite(host.rgb,0);
        if(valid){float3 base=(rtrAugCounts.z&8)!=0?max(color-emission,0):color;color=RtrAugFinite(base*gain+indirect+emission,0);}
        u_AugCombined[pixel]=float4(color,isfinite(host.a)?saturate(host.a):0);
    }
    if((outputs&2)!=0)u_AugModulation[pixel]=float4(gain,valid?coverage:0);
    if((outputs&8)!=0)u_AugIndirect[pixel]=float4(indirect,valid?coverage:0);
    if((outputs&64)!=0)u_AugEmission[pixel]=float4(emission,valid?coverage:0);
    if((outputs&128)!=0)u_AugCoverage[pixel]=coverage.xxxx;
}
