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
    uint4 grid; // flux bin extent, receiver-guide phase
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

bool RtrFluxProject(float3 world,out float2 pixel)
{
    float4 clip=mul(f.worldToClip,float4(world,1));
    float2 grid=((clip.xy/clip.w-g.farJitter.yz)*float2(.5,-.5)+.5)*float2(f.grid.xy);
    pixel=0;
    if(!(clip.w>0)||!all(isfinite(grid))||!all(grid>=0)||!all(grid<float2(f.grid.xy)))return false;
    pixel=grid;return true;
}

bool RtrSameFluxReceiver(RtrOpticalHit hit,RtrOpticalHit visible,float3 normal,float3 cameraDirection)
{
    if(hit.instance!=visible.instance)return false;
    if(hit.geometry==visible.geometry&&hit.primitive==visible.primitive)return true;
    const uint first=t_Instances[hit.instance].firstGeometryIndex;
    const RtrGeometryAttributes a=t_GeometryBaselines[first+hit.geometry];
    const RtrGeometryAttributes b=t_GeometryBaselines[first+visible.geometry];
    const uint aSurface=hit.primitive<a.triangles?RtrSurfaceIndex(a,hit.primitive):0;
    const uint bSurface=visible.primitive<b.triangles?RtrSurfaceIndex(b,visible.primitive):0;
    if(aSurface!=bSurface||(!aSurface&&t_Geometry[first+hit.geometry].materialIndex!=t_Geometry[first+visible.geometry].materialIndex))
        return false;
    float3 planeNormal=RtrOpticalOutward(visible.instance,visible.geometry,visible.primitive);
    if(dot(planeNormal,cameraDirection)>0)planeNormal=-planeNormal;
    return dot(normal,planeNormal)>.99999f;
}

float RtrFluxWorldPixelArea(float2 pixel,float2 pixelSpan,float3 relativePosition,float3 normal)
{
    float3 corner[4];
    const float d=dot(normal,relativePosition);
    [unroll]for(uint i=0;i<4;++i){
        const float3 direction=RtrFluxCameraRay(pixel+float2(i&1u,i>>1u)*pixelSpan);
        const float t=d/dot(normal,direction);
        if(!(t>0&&isfinite(t)))return 0;
        corner[i]=direction*t;
    }
    return .5f*(length(cross(corner[1]-corner[0],corner[2]-corner[0]))+
        length(cross(corner[1]-corner[3],corner[2]-corner[3])));
}

// Three RGB flux slices followed by the receiver-plane guide. The guide is
// built once per bin instead of tracing a visibility ray per splat tap.
static const uint RtrFluxGuideArea=3, RtrFluxGuidePlane=4, RtrFluxGuideNormal=5, RtrFluxGuideIdentity=8;

uint3 RtrFluxReceiverKey(uint instance,uint geometry,uint primitive)
{
    const uint index=t_Instances[instance].firstGeometryIndex+geometry;
    const RtrGeometryAttributes mapping=t_GeometryBaselines[index];
    const uint surface=primitive<mapping.triangles?RtrSurfaceIndex(mapping,primitive):0;
    return uint3(instance,surface,surface==0?t_Geometry[index].materialIndex:0);
}

float RtrFluxPlaneTolerance(float3 relativePosition)
{
    return max(g.gi.z,max(abs(relativePosition.x),max(abs(relativePosition.y),abs(relativePosition.z)))*1.9073486e-6f);
}

void RtrPrepareFluxReceiver(uint2 bin)
{
    if(any(bin>=f.grid.xy))return;
    float inverseArea=0;
    const float2 pixelSpan=float2(g.grid.xy)/float2(f.grid.xy);
    RayDesc camera;
    camera.Origin=g.cameraNear.xyz;camera.Direction=RtrFluxCameraRay((float2(bin)+.5f)*pixelSpan);
    camera.TMin=g.gi.z;camera.TMax=1e20;
    RtrOpticalHit hit;
    if(RtrFindOpticalHit(camera,RtrReceiverRole,hit)){
        float3 normal=RtrOpticalOutward(hit.instance,hit.geometry,hit.primitive);
        if(dot(normal,camera.Direction)>0)normal=-normal;
        const float3 relativePosition=hit.distance*camera.Direction;
        const float area=RtrFluxWorldPixelArea(float2(bin)*pixelSpan,pixelSpan,relativePosition,normal);
        if(area>0&&isfinite(area)){
            inverseArea=rcp(area);
            const uint3 key=RtrFluxReceiverKey(hit.instance,hit.geometry,hit.primitive);
            u_Flux[uint3(bin,RtrFluxGuidePlane)]=asuint(dot(normal,relativePosition));
            [unroll]for(uint i=0;i<3;++i){
                u_Flux[uint3(bin,RtrFluxGuideNormal+i)]=asuint(normal[i]);
                u_Flux[uint3(bin,RtrFluxGuideIdentity+i)]=key[i];
            }
        }
    }
    u_Flux[uint3(bin,RtrFluxGuideArea)]=asuint(inverseArea);
}

float RtrMatchFluxGuide(uint2 bin,uint3 key,float3 relativePosition,float3 normal,float tolerance)
{
    float inverseArea=asfloat(u_Flux[uint3(bin,RtrFluxGuideArea)]);
    if(!(inverseArea>0&&isfinite(inverseArea)))return 0;
    float3 binNormal;
    [unroll]for(uint i=0;i<3;++i){
        if(key[i]!=u_Flux[uint3(bin,RtrFluxGuideIdentity+i)])return 0;
        binNormal[i]=asfloat(u_Flux[uint3(bin,RtrFluxGuideNormal+i)]);
    }
    const float plane=asfloat(u_Flux[uint3(bin,RtrFluxGuidePlane)]);
    return RtrFluxMatchesPlane(dot(normal,binNormal),dot(binNormal,relativePosition)-plane,tolerance)?inverseArea:0;
}

void RtrDepositOpticalFlux(RayDesc ray,float3 flux,float projectedArea,RtrDielectricStack stack,bool finite)
{
    RtrOpticalHit receiver;
    float3 hitPosition,travel,power;
    const uint terminalRoles=RtrReceiverRole|(finite?RtrOpticalBlockerRole:0);
    if(!RtrTraceTransmittedLight(ray,stack,terminalRoles,receiver,hitPosition,travel,power))return;
    float2 pixel;
    if(!RtrFluxProject(hitPosition,pixel))return;
    // The emission plane is just upstream of the medium bounds, not at the
    // boundary of the scene. Account for blockers on its light-facing side.
    [branch]if(!finite){
        RayDesc upstream=ray;upstream.Direction=-ray.Direction;
        power*=RtrShadowRay(upstream);
    }
    if(!any(power>0))return;
    float3 normal=RtrOpticalOutward(receiver.instance,receiver.geometry,receiver.primitive);
    if(dot(normal,hitPosition-g.cameraNear.xyz)>0)normal=-normal;
    if(dot(normal,travel)>=0)return;

    // For a stratified directional beam, one sample represents a finite
    // emission cell, not a point impulse. Estimate its receiver footprint
    // from the projected cell area. Finite emitters have independent angular
    // samples, so use only subpixel reconstruction for those paths.
    const bool reconstruct=any(f.grid.xy!=g.grid.xy);
    const float2 pixelSpan=float2(g.grid.xy)/float2(f.grid.xy);
    const float3 relativePosition=hitPosition-g.cameraNear.xyz;
    float halfWidth=.5f;
    if(!finite){
        const float area=RtrFluxWorldPixelArea((pixel-.5f)*pixelSpan,pixelSpan,relativePosition,normal);
        halfWidth=RtrFluxFootprintHalfWidth(projectedArea,area*abs(dot(normal,travel)));
    }
    const float width=2.f*halfWidth;
    const int2 begin=max(0,int2(floor(pixel-halfWidth)));
    const int2 end=min(int2(f.grid.xy),int2(ceil(pixel+halfWidth)));
    const float3 energy=flux*((projectedArea/(width*width))*power);
    uint3 key=0;
    const float planeTolerance=RtrFluxPlaneTolerance(relativePosition);
    if(reconstruct)key=RtrFluxReceiverKey(receiver.instance,receiver.geometry,receiver.primitive);
    RayDesc camera;
    camera.Origin=g.cameraNear.xyz;camera.TMin=g.gi.z;camera.TMax=1e20;
    [loop]for(int y=begin.y;y<end.y;++y){
        const float wy=RtrFluxPixelWeight(y,pixel.y,halfWidth);
        [loop]for(int x=begin.x;x<end.x;++x){
            const int2 tap=int2(x,y);
            const float weight=wy*RtrFluxPixelWeight(x,pixel.x,halfWidth);
            if(weight<=0)continue;
            [branch]if(reconstruct){
                if(RtrMatchFluxGuide(uint2(tap),key,relativePosition,normal,planeTolerance)>0)
                    RtrOpticalFluxDeposit(u_Flux,uint2(tap),weight*energy,f.scale.xyz);
                continue;
            }
            camera.Direction=RtrFluxCameraRay(float2(tap)+.5f);
            // Restrict the visibility ray to the proposed receiving plane.
            // A nearer cutout or a different material must never borrow flux.
            const float t=dot(hitPosition-camera.Origin,normal)/dot(camera.Direction,normal);
            if(!(t>camera.TMin&&isfinite(t)))continue;
            const float tolerance=max(g.gi.z,t*1.9073486e-6f);
            camera.TMax=t+tolerance;
            RtrOpticalHit visible;
            if(!RtrFindOpticalHit(camera,RtrReceiverRole,visible)||abs(visible.distance-t)>tolerance||
                !RtrSameFluxReceiver(receiver,visible,normal,camera.Direction))continue;
            RtrOpticalFluxDeposit(u_Flux,uint2(tap),weight*energy,f.scale.xyz);
        }
    }
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

void RtrResolveDirectionalFlux(uint2 pixel)
{
    [branch]if(f.grid.z!=0){RtrPrepareFluxReceiver(pixel);return;}
    if(any(pixel>=g.grid.xy))return;
    const bool reconstruct=any(f.grid.xy!=g.grid.xy);
    const float2 sample=(float2(pixel)+.5f)*float2(f.grid.xy)/float2(g.grid.xy)-.5f;
    const int2 base=int2(floor(sample));
    const float2 fraction=frac(sample);
    uint2 bins[4];uint3 energy[4];
    uint nonzero=0;
    const uint taps=reconstruct?4:1;
    [loop]for(uint i=0;i<taps;++i){
        bins[i]=reconstruct?uint2(clamp(base+int2(i&1u,i>>1u),0,int2(f.grid.xy)-1)):pixel;
        energy[i]=RtrOpticalFluxLoad(u_Flux,bins[i]);
        nonzero|=energy[i].x|energy[i].y|energy[i].z;
    }
    if(nonzero==0)return;
    RtrReceiver receiver;
    if(!RtrTrace(g.cameraNear.xyz,RtrFluxCameraRay(float2(pixel)+.5),1e20,RtrReceiverRole,receiver))return;
    float3 flux=0;
    [branch]if(reconstruct){
        const uint3 key=RtrFluxReceiverKey(receiver.instance,receiver.geometry,receiver.primitive);
        const float3 relativePosition=receiver.position-g.cameraNear.xyz;
        const float tolerance=RtrFluxPlaneTolerance(relativePosition);
        float weightSum=0;
        [unroll]for(uint i=0;i<4;++i){
            const float weight=((i&1u)!=0?fraction.x:1-fraction.x)*(i>=2?fraction.y:1-fraction.y);
            const float inverseArea=RtrMatchFluxGuide(bins[i],key,relativePosition,receiver.geometricNormal,tolerance);
            if(inverseArea>0){
                // Zero-energy bins still contribute weight; they carry the
                // real shadow signal. Only geometry mismatches are discarded.
                flux+=(float3(energy[i])*f.inverseScale.xyz)*(weight*inverseArea);
                weightSum+=weight;
            }
        }
        if(weightSum>0)flux/=weightSum;
    }else{
        flux=RtrOpticalFluxResolve(energy[0],f.inverseScale.xyz,
            RtrFluxWorldPixelArea(float2(pixel),1,receiver.position-g.cameraNear.xyz,receiver.geometricNormal));
    }
    const float inversePi=0.3183098861837907f;
    float3 radiance=flux*(receiver.albedo*(inversePi*receiver.diffuseWeight))*receiver.visibility*(1-receiver.fog);
    if(!all(isfinite(radiance)))return;
    float4 value=u_Indirect[pixel];
    u_Indirect[pixel]=float4(min(value.rgb+max(radiance,0),65504),receiver.opacity);
}
