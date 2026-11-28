#define RTR_NATIVE_LIGHTING_LIBRARY
#define RTR_WORK_STATISTICS 0
#include "RtrNativeLighting.hlsl"
#include "RtrNativeTransmittedLight.hlsli"
#include "RtrFiniteOpticalEmission.hlsli"
#include "RtrOpticalFluxBins.hlsli"

struct RtrDirectionalFluxJob {
    float4 originReach,stepXArea,stepY,direction;
    float4 fluxPerArea;
    uint4 extentSeed;
};
struct RtrDirectionalFluxParameters {
    row_major float4x4 worldToClip;
    float4 scale,inverseScale;
    RtrDirectionalFluxJob jobs[2];
    uint4 finite;
};
struct RtrOpticalTriangleEmitter {
    float4 originReach,edgeU,edgeV,normal,fluxPerSample;
    uint4 volumeSlots;
};
ConstantBuffer<RtrDirectionalFluxParameters> f : register(b1);
RWTexture2DArray<uint> u_Flux : register(u16);
StructuredBuffer<RtrOpticalTriangleEmitter> t_TriangleEmitters : register(t16);

float3 RtrFluxCameraRay(float2 pixel)
{
    float2 ndc=pixel/float2(g.grid.xy)*float2(2,-2)+float2(-1,1)+g.farJitter.yz;
    float4 nearView=mul(g.inverseClip,float4(ndc,g.cameraNear.w,1));
    float4 farView=mul(g.inverseClip,float4(ndc,g.farJitter.x,1));
    return RtrNormalize(mul((float3x3)g.cameraToWorld,farView.xyz/farView.w-nearView.xyz/nearView.w));
}

bool RtrFluxProject(float3 world,out uint2 pixel)
{
    float4 clip=mul(f.worldToClip,float4(world,1));
    float2 grid=((clip.xy/clip.w-g.farJitter.yz)*float2(.5,-.5)+.5)*float2(g.grid.xy);
    pixel=0;
    if(!(clip.w>0)||!all(isfinite(grid))||!all(grid>=0)||!all(grid<float2(g.grid.xy)))return false;
    pixel=uint2(grid);return true;
}

void RtrDepositOpticalFlux(RayDesc ray,float3 flux,float projectedArea,RtrDielectricStack stack,bool finite)
{
    RtrOpticalHit receiver;
    float3 hitPosition,travel,power;
    const uint terminalRoles=RtrReceiverRole|(finite?RtrOpticalBlockerRole:0);
    if(!RtrTraceTransmittedLight(ray,stack,terminalRoles,receiver,hitPosition,travel,power))return;
    uint2 pixel;
    if(!RtrFluxProject(hitPosition,pixel))return;
    // The emission plane is just upstream of the medium bounds, not at the
    // boundary of the scene. Account for blockers on its light-facing side.
    [branch]if(!finite){
        RayDesc upstream=ray;upstream.Direction=-ray.Direction;
        power*=RtrShadowRay(upstream);
    }
    if(!any(power>0))return;
    RayDesc camera;
    camera.Origin=g.cameraNear.xyz;camera.Direction=RtrFluxCameraRay(float2(pixel)+.5);
    camera.TMin=g.gi.z;camera.TMax=1e20;
    RtrOpticalHit visible;
    if(!RtrFindOpticalHit(camera,RtrReceiverRole,visible)||visible.instance!=receiver.instance
        ||visible.geometry!=receiver.geometry||visible.primitive!=receiver.primitive)return;
    float3 normal=RtrOpticalOutward(receiver.instance,receiver.geometry,receiver.primitive);
    if(dot(normal,camera.Direction)>0)normal=-normal;
    if(dot(normal,travel)>=0)return;
    const float3 center=camera.Origin+visible.distance*camera.Direction;
    const float planeTolerance=max(g.gi.z,visible.distance*1.9073486e-6f);
    if(abs(dot(hitPosition-center,normal))>planeTolerance)return;
    RtrOpticalFluxDeposit(u_Flux,pixel,flux*(projectedArea*power),f.scale.xyz);
}

void RtrDepositDirectionalFlux(uint2 cell,uint source)
{
    RtrDirectionalFluxJob job=f.jobs[source];
    if(any(cell>=job.extentSeed.xy))return;
    RtrDirectionalEmissionTile tile;
    tile.origin=job.originReach.xyz;tile.stepX=job.stepXArea.xyz;tile.stepY=job.stepY.xyz;
    tile.direction=job.direction.xyz;tile.extent=job.extentSeed.xy;tile.seed=job.extentSeed.z;
    RayDesc ray=RtrEmitDirectionalFlux(tile,cell,g.gi.z,job.originReach.w);
    RtrDepositOpticalFlux(ray,job.fluxPerArea.xyz,job.stepXArea.w,RtrDielectricEmptyStack(),false);
}

float2 RtrFluxSamplePair(uint seed)
{
    const uint bits=RtrOpticalEmissionHash(seed);
    return (float2(bits&0xffffu,bits>>16u)+.5f)*(1.f/65536.f);
}

void RtrDepositTriangleFlux(uint2 cell)
{
    const uint lane=cell.x+f.finite.y*cell.y;
    const RtrOpticalTriangleEmitter emitter=t_TriangleEmitters[lane%f.finite.x];
    if(emitter.originReach.w<=g.gi.z||!any(emitter.fluxPerSample.xyz>0))return;
    RtrDielectricStack stack=RtrDielectricEmptyStack();
    [unroll]for(uint i=0;i<RTR_DIELECTRIC_CAPACITY;++i){
        const uint instance=emitter.volumeSlots[i];
        if(instance==0xffffffffu)continue;
        const uint surface=RtrOpticalVolumeSurface(instance);
        if(surface==0||t_SurfaceMaterials[surface-1].mediumPriorityPlusOne==0||
            !RtrOpticalApply(stack,uint2(surface,instance),true))return;
    }
    const RtrFiniteOpticalEmissionSample sample=RtrSampleFiniteTriangleEmission(emitter.originReach.xyz,
        emitter.edgeU.xyz,emitter.edgeV.xyz,emitter.normal.xyz,emitter.fluxPerSample.xyz,
        RtrFluxSamplePair(2u*lane),RtrFluxSamplePair(2u*lane+1u),g.gi.z,emitter.originReach.w);
    RtrDepositOpticalFlux(sample.ray,sample.flux,1,stack,true);
}

float RtrFluxPixelArea(uint2 pixel,RtrReceiver receiver)
{
    float3 s[4];
    float d=dot(receiver.geometricNormal,receiver.position-g.cameraNear.xyz);
    [unroll]for(uint i=0;i<4;++i){
        float3 ray=RtrFluxCameraRay(float2(pixel+uint2(i&1u,i>>1u)));
        float t=d/dot(receiver.geometricNormal,ray);
        if(!(t>0&&isfinite(t)))return 0;
        s[i]=ray*t;
    }
    return .5*(length(cross(s[1]-s[0],s[2]-s[0]))+length(cross(s[1]-s[3],s[2]-s[3])));
}

void RtrResolveDirectionalFlux(uint2 pixel)
{
    if(any(pixel>=g.grid.xy)||(u_Flux[uint3(pixel,0)]|u_Flux[uint3(pixel,1)]|u_Flux[uint3(pixel,2)])==0)return;
    RtrReceiver receiver;
    if(!RtrTrace(g.cameraNear.xyz,RtrFluxCameraRay(float2(pixel)+.5),1e20,RtrReceiverRole,receiver))return;
    float3 flux=RtrOpticalFluxResolve(u_Flux,pixel,f.inverseScale.xyz,RtrFluxPixelArea(pixel,receiver));
    const float inversePi=0.3183098861837907f;
    float3 radiance=flux*(receiver.albedo*(inversePi*receiver.diffuseWeight))*receiver.visibility*(1-receiver.fog);
    if(!all(isfinite(radiance)))return;
    float4 value=u_Indirect[pixel];
    u_Indirect[pixel]=float4(min(value.rgb+max(radiance,0),65504),receiver.opacity);
}
