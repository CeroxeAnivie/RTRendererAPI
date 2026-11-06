// RR reconstructs only the scene-linear diffuse GI. Gain is never an RR input.
#define RTR_NATIVE_LIGHTING_LIBRARY
#include "RtrNativeLighting.hlsl"
cbuffer RtrReconstruction : register(b1) {
    row_major float4x4 rtrWorldToClip;
    row_major float4x4 rtrPreviousWorldToClip;
    float4 rtrDepth; // NDC-to-hardware scale, bias, optional RR divisor, unused
};
RWTexture2D<float4> u_DiffuseAlbedo : register(u5);
RWTexture2D<float4> u_SpecularAlbedo : register(u6);
RWTexture2D<float4> u_NormalRoughness : register(u7);
RWTexture2D<float2> u_Motion : register(u8);
RWTexture2D<float> u_Depth : register(u9);
RWTexture2D<float> u_SpecularDistance : register(u10);
RWTexture2D<uint4> u_CurrentSurface : register(u11);
float3 RtrPreviousPosition(RtrReceiver r) {
    InstanceData inst=t_Instances[r.instance];GeometryData geom=t_Geometry[inst.firstGeometryIndex+r.geometry];
    ByteAddressBuffer indices=t_Buffers[NonUniformResourceIndex(geom.indexBufferIndex)];
    ByteAddressBuffer vertices=t_Buffers[NonUniformResourceIndex(geom.vertexBufferIndex)];
    uint3 tri=indices.Load3(geom.indexOffset+r.primitive*12);float3 bary=float3(1-r.barycentrics.x-r.barycentrics.y,r.barycentrics);
    uint offset=geom.prevPositionOffset==0xffffffff?geom.positionOffset:geom.prevPositionOffset;
    float3 local=asfloat(vertices.Load3(offset+tri.x*12))*bary.x+asfloat(vertices.Load3(offset+tri.y*12))*bary.y+asfloat(vertices.Load3(offset+tri.z*12))*bary.z;
    return mul(inst.prevTransform,float4(local,1));
}
[numthreads(8,8,1)]
void main(uint2 pixel:SV_DispatchThreadID) {
    if(any(pixel>=g.grid.xy))return;RtrSetSourcePixel(pixel);RtrCount(0);
    float2 ndc=(float2(pixel)+.5)/float2(g.grid.xy)*float2(2,-2)+float2(-1,1)+g.farJitter.yz;
    float4 nearView=mul(g.inverseClip,float4(ndc,g.cameraNear.w,1)),farView=mul(g.inverseClip,float4(ndc,g.farJitter.x,1));
    float3 direction=RtrNormalize(mul((float3x3)g.cameraToWorld,farView.xyz/farView.w-nearView.xyz/nearView.w));
    RtrReceiver r;float3 gain=1,indirect=0;bool valid=RtrTrace(g.cameraNear.xyz,direction,1e20,1,r);
    if(rtrDepth.z>1)u_CurrentSurface[pixel]=valid?uint4(r.instance,r.geometry,asuint(r.distance),1):uint4(0xffffffff,0xffffffff,0,0);
    // Publish trace-only data before the nested lighting/shadow queries. Keeping
    // barycentrics, geometry IDs and material guide fields alive through that
    // traversal increases register pressure without changing either signal.
    if((g.grid.z&4)!=0)u_Geometry[pixel]=valid?float4(r.normal,r.distance):0;
    if((g.grid.z&8)!=0)u_Identity[pixel]=valid?uint4(r.instance,r.geometry,r.primitive,1):uint4(0xffffffff,0xffffffff,0xffffffff,0);
    u_DiffuseAlbedo[pixel]=valid?float4(r.albedo*r.diffuseWeight,1):0;
    // Specular albedo and hit distance stay zero for this diffuse-only layer;
    // their textures are cleared once when allocated by RtrNativeRR.
    u_NormalRoughness[pixel]=valid?float4(r.normal,r.roughness):float4(0,0,1,1);
    float4 clip=mul(rtrWorldToClip,float4(valid?r.position:g.cameraNear.xyz+direction*1e7,1));
    // Retained scene buffers keep their previous deformation until the next
    // resource update. Consume that motion once, then project current positions.
    float3 previousPosition=valid?((g.publication.w&16)!=0?RtrPreviousPosition(r):r.position):g.cameraNear.xyz+direction*1e7;
    float4 previous=mul(rtrPreviousWorldToClip,float4(previousPosition,1));
    float2 motion=(previous.xy/previous.w-clip.xy/clip.w)*float2(.5,-.5);
    u_Motion[pixel]=all(isfinite(motion))?motion:0;
    u_Depth[pixel]=saturate(clip.z/clip.w*rtrDepth.x+rtrDepth.y);
    if(valid){RtrCount(1);RtrEvaluateReceiver(r,gain,indirect);}
    gain=clamp(gain,0,65504);indirect=clamp(indirect,0,65504);
    if((g.grid.z&1)!=0)u_Direct[pixel]=float4(gain,valid?r.opacity:0);
    u_Indirect[pixel]=float4(indirect,valid?r.opacity:0);
    RtrFlushWork();
}
