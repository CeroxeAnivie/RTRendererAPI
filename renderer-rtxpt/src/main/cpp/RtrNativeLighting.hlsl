// Standalone receiver/direct/one-bounce plan. It shares the resident Donut TLAS
// and assets; no full path-tracer, beauty, guide, bloom or present target is used.
// Donut uploads InstanceData transforms as three consecutive float4 rows.
// Keep their structured-buffer layout identical in native, host and SDK shaders.
#pragma pack_matrix(row_major)
#include <donut/shaders/bindless.h>
#include <donut/shaders/packing.hlsli>
#include "RtrNativeLightingStyle.hlsli"

struct RtrSource {float4 position,direction,color,extent,cone,identity;};
struct RtrNode {float4 lower,left,upper,right;};
struct RtrInstance {float4 rolesVisibility;float4 baseline;row_major float3x4 explicitPrevious;};
struct RtrVertexAttributes {float2 baseline;uint color;uint reserved;};
struct RtrGeometryAttributes {uint offset,triangles,basePolicy,normalPolicy,ormPolicy,emissivePolicy;float maskedOpacity;};
struct RtrSurfaceMaterial {MaterialConstants material;uint4 policies;float maskedOpacity;uint active;};
struct RtrAnalytic {float4 position,direction,color,cone;};
struct RtrNativeConstants
{
    row_major float4x4 inverseClip;
    row_major float4x4 cameraToWorld;
    float4 cameraNear;
    float4 farJitter; // far NDC, jitter NDC xy, reserved
    uint4 grid; // width,height,output mask,GI sample count
    float4 sun; // toward source, daylight weight
    float4 sunColor;
    float4 moon; // toward source, moonlight weight
    float4 gi; // distance,intensity,epsilon,reserved
    RtrLightingStyle style;
    uint4 publication; // app-data bytes,generation low/high,diagnostic counts enabled
    uint4 clock; // time nanos low/high, delta nanos low/high
    float4 fogRanges; // spherical start/end, cylindrical start/end
    float4 fogControl; // opacity, reserved
};
ConstantBuffer<RtrNativeConstants> g : register(b0);
RaytracingAccelerationStructure t_Scene : register(t0);
StructuredBuffer<InstanceData> t_Instances : register(t1);
StructuredBuffer<GeometryData> t_Geometry : register(t2);
StructuredBuffer<MaterialConstants> t_Materials : register(t3);
StructuredBuffer<RtrSource> t_Sources : register(t4);
StructuredBuffer<RtrNode> t_SourceTree : register(t5);
StructuredBuffer<RtrInstance> t_InstanceAttributes : register(t6);
StructuredBuffer<RtrVertexAttributes> t_Baselines : register(t7);
StructuredBuffer<RtrGeometryAttributes> t_GeometryBaselines : register(t8);
ByteAddressBuffer t_Application : register(t9);
StructuredBuffer<float2> t_BaselineLookup : register(t10); // baseline float2[256], packed host-response RGB[256]
StructuredBuffer<RtrAnalytic> t_Analytic : register(t11); // resident lights followed by their exact support tree
StructuredBuffer<RtrSurfaceMaterial> t_SurfaceMaterials : register(t12);
StructuredBuffer<uint> t_SurfaceIndices : register(t13);
ByteAddressBuffer t_Buffers[] : register(t0,space1);
Texture2D<float4> t_Textures[] : register(t0,space2);
SamplerState s_Material : register(s0);
SamplerState s_TexturePolicy0 : register(s1);
SamplerState s_TexturePolicy1 : register(s2);
SamplerState s_TexturePolicy2 : register(s3);
SamplerState s_TexturePolicy3 : register(s4);
SamplerState s_TexturePolicy4 : register(s5);
SamplerState s_TexturePolicy5 : register(s6);
SamplerState s_TexturePolicy6 : register(s7);
SamplerState s_TexturePolicy7 : register(s8);
RWTexture2D<float4> u_Direct : register(u0);
RWTexture2D<float4> u_Indirect : register(u1);
RWTexture2D<float4> u_Geometry : register(u2);
RWTexture2D<uint4> u_Identity : register(u3);
RWByteAddressBuffer u_Work : register(u4);

// Adapter-owned slot metadata: clamp U, clamp V, nearest. Material flags stay upstream-owned.
float4 RtrSampleTexture(int textureIndex,float2 uv,uint policy){
    switch(policy&7){
        case 0:return t_Textures[NonUniformResourceIndex(textureIndex)].SampleLevel(s_TexturePolicy0,uv,0);
        case 1:return t_Textures[NonUniformResourceIndex(textureIndex)].SampleLevel(s_TexturePolicy1,uv,0);
        case 2:return t_Textures[NonUniformResourceIndex(textureIndex)].SampleLevel(s_TexturePolicy2,uv,0);
        case 3:return t_Textures[NonUniformResourceIndex(textureIndex)].SampleLevel(s_TexturePolicy3,uv,0);
        case 4:return t_Textures[NonUniformResourceIndex(textureIndex)].SampleLevel(s_TexturePolicy4,uv,0);
        case 5:return t_Textures[NonUniformResourceIndex(textureIndex)].SampleLevel(s_TexturePolicy5,uv,0);
        case 6:return t_Textures[NonUniformResourceIndex(textureIndex)].SampleLevel(s_TexturePolicy6,uv,0);
        default:return t_Textures[NonUniformResourceIndex(textureIndex)].SampleLevel(s_TexturePolicy7,uv,0);
    }
}

struct RtrReceiver
{
    float3 position,normal,geometricNormal,albedo;
    float2 uv,baseline,barycentrics;
    float opacity,diffuseWeight,distance,visibility,fog,roughness,metalness,transmission;
    uint instance,geometry,primitive;
};
float3 RtrNormalize(float3 v){return v*rsqrt(max(dot(v,v),1e-30));}
float RtrFalloff(float value,float exponent){
    // A select can evaluate pow even for the common linear/quadratic cases.
    [branch]if(exponent==1)return value;
    [branch]if(exponent==2)return value*value;
    return pow(value,exponent);
}
#ifndef RTR_WORK_STATISTICS
#define RTR_WORK_STATISTICS 1
#endif
#if RTR_WORK_STATISTICS
static uint rtrWorkCounts[12]={0,0,0,0,0,0,0,0,0,0,0,0};
#endif
void RtrCount(uint index){
#if RTR_WORK_STATISTICS
    if((g.publication.w&1)!=0)++rtrWorkCounts[index];
#endif
}
// Count the same events locally, then publish once per active wave and counter.
// No wave operation occurs inside divergent ray/source traversal. Uint overflow
// retains the original modulo-2^32 global-counter behavior.
void RtrFlushWork(){
#if RTR_WORK_STATISTICS
    if((g.publication.w&1)!=0){
        [unroll]for(uint index=0;index<12;++index){
            uint total=WaveActiveSum(rtrWorkCounts[index]);
            if(WaveIsFirstLane()&&total!=0){uint unused;u_Work.InterlockedAdd(index*4,total,unused);}
        }
    }
#endif
}
uint RtrQueryFlags(){return g.style.limits.w>=2||(g.publication.w&32)!=0?RAY_FLAG_FORCE_NON_OPAQUE:((g.publication.w&4)!=0?RAY_FLAG_NONE:((g.publication.w&2)!=0||g.style.limits.w!=0?RAY_FLAG_FORCE_NON_OPAQUE:RAY_FLAG_NONE));}
float3 RtrPosition(ByteAddressBuffer vertices,GeometryData geometry,uint i){return asfloat(vertices.Load3(geometry.positionOffset+i*12));}
float2 RtrUv(ByteAddressBuffer vertices,GeometryData geometry,uint i)
{return geometry.texCoord1Offset==0xffffffff?0:asfloat(vertices.Load2(geometry.texCoord1Offset+i*8));}
float4 RtrVertexColor(uint packed){return float4(packed&255,(packed>>8)&255,(packed>>16)&255,packed>>24)/255.0;}
MaterialConstants RtrMaterial(GeometryData geometry,uint primitive,inout RtrGeometryAttributes attributes){
    uint surface=primitive<attributes.triangles?t_SurfaceIndices[attributes.offset/3+primitive]:0;
    if(surface!=0){
        RtrSurfaceMaterial row=t_SurfaceMaterials[surface-1];
        attributes.basePolicy=row.policies.x;attributes.normalPolicy=row.policies.y;
        attributes.ormPolicy=row.policies.z;attributes.emissivePolicy=row.policies.w;attributes.maskedOpacity=row.maskedOpacity;
        return row.material;
    }
    return t_Materials[geometry.materialIndex];
}
RtrReceiver RtrLoad(uint instance,uint geometry,uint primitive,float2 attributes,float3 origin,float3 direction,float distance,float3x4 worldToObject)
{
    RtrReceiver r=(RtrReceiver)0;r.instance=instance;r.geometry=geometry;r.primitive=primitive;r.distance=distance;r.barycentrics=attributes;
    InstanceData inst=t_Instances[instance];uint geometryIndex=inst.firstGeometryIndex+geometry;
    GeometryData geom=t_Geometry[geometryIndex];RtrGeometryAttributes geometryAttributes=t_GeometryBaselines[geometryIndex];
    ByteAddressBuffer indices=t_Buffers[NonUniformResourceIndex(geom.indexBufferIndex)];
    ByteAddressBuffer vertices=t_Buffers[NonUniformResourceIndex(geom.vertexBufferIndex)];
    uint3 tri=indices.Load3(geom.indexOffset+primitive*12);float3 bary=float3(1-attributes.x-attributes.y,attributes);
    float3 a=RtrPosition(vertices,geom,tri.x),b=RtrPosition(vertices,geom,tri.y),c=RtrPosition(vertices,geom,tri.z);
    float3 edge0=mul((float3x3)inst.transform,b-a),edge1=mul((float3x3)inst.transform,c-a);
    r.geometricNormal=RtrNormalize(cross(edge0,edge1));
    if(dot(r.geometricNormal,direction)>0)r.geometricNormal=-r.geometricNormal;
    r.normal=r.geometricNormal;
    if(geom.normalOffset!=0xffffffff)
    {
        float3 n=Unpack_RGB8_SNORM(vertices.Load(geom.normalOffset+tri.x*4))*bary.x
            +Unpack_RGB8_SNORM(vertices.Load(geom.normalOffset+tri.y*4))*bary.y
            +Unpack_RGB8_SNORM(vertices.Load(geom.normalOffset+tri.z*4))*bary.z;
        // Row normal times inverse = inverse transpose times column normal.
        if(dot(n,n)>1e-20)r.normal=RtrNormalize(mul(n,(float3x3)worldToObject));
        if(dot(r.normal,r.geometricNormal)<0)r.normal=-r.normal;
    }
    r.position=origin+direction*distance;
    r.uv=RtrUv(vertices,geom,tri.x)*bary.x+RtrUv(vertices,geom,tri.y)*bary.y+RtrUv(vertices,geom,tri.z)*bary.z;
    MaterialConstants mat=RtrMaterial(geom,primitive,geometryAttributes);
    bool masked=mat.domain==MaterialDomain_AlphaTested||mat.domain==MaterialDomain_TransmissiveAlphaTested;
    float4 base=float4(mat.baseOrDiffuseColor,masked?geometryAttributes.maskedOpacity:mat.opacity);
    if((mat.flags&MaterialFlags_UseBaseOrDiffuseTexture)!=0)
        base*=RtrSampleTexture(mat.baseOrDiffuseTextureIndex,r.uv,geometryAttributes.basePolicy);
    if((mat.flags&MaterialFlags_UseOpacityTexture)!=0)
        base.a*=t_Textures[NonUniformResourceIndex(mat.opacityTextureIndex)].SampleLevel(s_Material,r.uv,0).r;
    if(primitive<geometryAttributes.triangles){uint first=geometryAttributes.offset+primitive*3;
        base*=RtrVertexColor(t_Baselines[first].color)*bary.x+RtrVertexColor(t_Baselines[first+1].color)*bary.y+RtrVertexColor(t_Baselines[first+2].color)*bary.z;}
    r.albedo=max(base.rgb,0);r.opacity=saturate(base.a);
    r.roughness=mat.roughness;r.metalness=mat.metalness;r.transmission=mat.transmissionFactor;
    if((mat.flags&MaterialFlags_UseMetalRoughOrSpecularTexture)!=0&&(mat.flags&MaterialFlags_UseSpecularGlossModel)==0){
        float4 sampled=RtrSampleTexture(mat.metalRoughOrSpecularTextureIndex,r.uv,geometryAttributes.ormPolicy);
        r.roughness*=sampled.g;r.metalness*=(mat.flags&MaterialFlags_MetalnessInRedChannel)!=0?sampled.r:sampled.b;}
    if((mat.flags&MaterialFlags_UseTransmissionTexture)!=0)r.transmission*=t_Textures[NonUniformResourceIndex(mat.transmissionTextureIndex)].SampleLevel(s_Material,r.uv,0).r;
    r.roughness=saturate(r.roughness);r.metalness=saturate(r.metalness);r.transmission=saturate(r.transmission);
    r.diffuseWeight=(1-r.metalness)*(1-r.transmission);
    if((mat.flags&MaterialFlags_UseNormalTexture)!=0&&geom.tangentOffset!=0xffffffff)
    {
        float4 tangent=Unpack_RGBA8_SNORM(vertices.Load(geom.tangentOffset+tri.x*4))*bary.x
            +Unpack_RGBA8_SNORM(vertices.Load(geom.tangentOffset+tri.y*4))*bary.y
            +Unpack_RGBA8_SNORM(vertices.Load(geom.tangentOffset+tri.z*4))*bary.z;
        float3 t=mul((float3x3)inst.transform,tangent.xyz);t=RtrNormalize(t-r.normal*dot(t,r.normal));
        float3 map=RtrSampleTexture(mat.normalTextureIndex,r.uv,geometryAttributes.normalPolicy).xyz*2-1;
        map.xy*=mat.normalTextureScale;
        r.normal=RtrNormalize(t*map.x+cross(r.normal,t)*sign(tangent.w)*map.y+r.normal*map.z);
        if(dot(r.normal,r.geometricNormal)<0)r.normal=-r.normal;
    }
    RtrInstance data=t_InstanceAttributes[instance];r.visibility=data.rolesVisibility.y;r.fog=data.rolesVisibility.z;
    if(g.fogControl.x>0){
        float3 delta=r.position-g.cameraNear.xyz;
        float spherical=length(delta),cylindrical=length(delta.xz);
        float sf=spherical<=g.fogRanges.x?0:spherical>=g.fogRanges.y?1:(spherical-g.fogRanges.x)/(g.fogRanges.y-g.fogRanges.x);
        float cf=cylindrical<=g.fogRanges.z?0:cylindrical>=g.fogRanges.w?1:(cylindrical-g.fogRanges.z)/(g.fogRanges.w-g.fogRanges.z);
        r.fog=max(r.fog,max(sf,cf)*g.fogControl.x);
    }
    float2 coordinates=0;
    if(primitive<geometryAttributes.triangles){uint offset=geometryAttributes.offset+primitive*3;
        coordinates=t_Baselines[offset].baseline*bary.x+t_Baselines[offset+1].baseline*bary.y+t_Baselines[offset+2].baseline*bary.z;}
    coordinates=saturate(coordinates*data.baseline.xy+data.baseline.zw);
    float2 p=coordinates*15;uint2 lo=uint2(p),hi=min(lo+1,15);float2 f=frac(p);
    r.baseline=lerp(lerp(t_BaselineLookup[lo.y*16+lo.x],t_BaselineLookup[lo.y*16+hi.x],f.x),
        lerp(t_BaselineLookup[hi.y*16+lo.x],t_BaselineLookup[hi.y*16+hi.x],f.x),f.y);
    return r;
}
bool RtrAccept(RtrReceiver receiver,bool frontFace,uint role)
{
    RtrInstance attributes=t_InstanceAttributes[receiver.instance];uint flags=uint(attributes.rolesVisibility.x);
    if((flags&role)==0)return false;
    if(role==1&&(flags&8)!=0&&!frontFace)return false;
    if(role==2&&(flags&16)==0&&!frontFace)return false;
    uint geometryIndex=t_Instances[receiver.instance].firstGeometryIndex+receiver.geometry;
    GeometryData geom=t_Geometry[geometryIndex];RtrGeometryAttributes geometryAttributes=t_GeometryBaselines[geometryIndex];
    MaterialConstants mat=RtrMaterial(geom,receiver.primitive,geometryAttributes);
    if(mat.domain==MaterialDomain_AlphaTested||mat.domain==MaterialDomain_TransmissiveAlphaTested)return receiver.opacity>=mat.alphaCutoff;
    return receiver.opacity>0;
}
// Candidate acceptance needs coverage and roles, not a fully shaded receiver.
// In particular, shadow rays must not fetch normals, tangent frames or baseline
// lookups for every candidate they visit.
bool RtrAcceptCandidate(uint instance,uint geometry,uint primitive,float2 attributes,bool frontFace,uint role,
    out float3 transmission,out bool opaque)
{
    transmission=1;opaque=false;
    uint roles=uint(t_InstanceAttributes[instance].rolesVisibility.x);
    if((roles&role)==0||(role==1&&(roles&8)!=0&&!frontFace)||(role==2&&(roles&16)==0&&!frontFace))return false;
    // Per-instance coverage classification is part of the same attribute commit.
    // Role-only opaque candidates need no material, UV or corner color reads.
    InstanceData inst=t_Instances[instance];uint geometryIndex=inst.firstGeometryIndex+geometry;
    GeometryData geom=t_Geometry[geometryIndex];RtrGeometryAttributes geometryAttributes=t_GeometryBaselines[geometryIndex];
    MaterialConstants mat=RtrMaterial(geom,primitive,geometryAttributes);
    bool masked=mat.domain==MaterialDomain_AlphaTested||mat.domain==MaterialDomain_TransmissiveAlphaTested;
    if((g.publication.w&4)!=0&&t_InstanceAttributes[instance].rolesVisibility.w==0&&mat.domain==MaterialDomain_Opaque&&mat.opacity==1)
        {opaque=role==2;return true;}
    float4 base=float4(mat.baseOrDiffuseColor,masked?geometryAttributes.maskedOpacity:mat.opacity);
    float3 bary=float3(1-attributes.x-attributes.y,attributes);float2 uv=0;
    const uint textureMask=MaterialFlags_UseBaseOrDiffuseTexture|MaterialFlags_UseOpacityTexture|MaterialFlags_UseTransmissionTexture;
    if((mat.flags&textureMask)!=0){
        ByteAddressBuffer indices=t_Buffers[NonUniformResourceIndex(geom.indexBufferIndex)];
        ByteAddressBuffer vertices=t_Buffers[NonUniformResourceIndex(geom.vertexBufferIndex)];
        uint3 tri=indices.Load3(geom.indexOffset+primitive*12);
        uv=RtrUv(vertices,geom,tri.x)*bary.x+RtrUv(vertices,geom,tri.y)*bary.y+RtrUv(vertices,geom,tri.z)*bary.z;
        if((mat.flags&MaterialFlags_UseBaseOrDiffuseTexture)!=0)base*=RtrSampleTexture(mat.baseOrDiffuseTextureIndex,uv,geometryAttributes.basePolicy);
        if((mat.flags&MaterialFlags_UseOpacityTexture)!=0)base.a*=t_Textures[NonUniformResourceIndex(mat.opacityTextureIndex)].SampleLevel(s_Material,uv,0).r;
    }
    if(primitive<geometryAttributes.triangles){uint first=geometryAttributes.offset+primitive*3;
        base*=RtrVertexColor(t_Baselines[first].color)*bary.x+RtrVertexColor(t_Baselines[first+1].color)*bary.y+RtrVertexColor(t_Baselines[first+2].color)*bary.z;}
    float opacity=saturate(base.a);
    if(mat.domain==MaterialDomain_AlphaTested||mat.domain==MaterialDomain_TransmissiveAlphaTested){if(opacity<mat.alphaCutoff)return false;}
    else if(opacity<=0)return false;
    if(role==2){
        if(mat.domain>=MaterialDomain_Transmissive){
            float amount=mat.transmissionFactor;
            if((mat.flags&MaterialFlags_UseTransmissionTexture)!=0)amount*=t_Textures[NonUniformResourceIndex(mat.transmissionTextureIndex)].SampleLevel(s_Material,uv,0).r;
            transmission=saturate(amount)*saturate(max(base.rgb,0));
            if(mat.domain==MaterialDomain_TransmissiveAlphaBlended)transmission=lerp(float3(1,1,1),transmission,opacity);
        }else if(mat.domain==MaterialDomain_AlphaBlended)transmission=1-opacity;
        else opaque=true;
    }
    return true;
}
#include "RtrNativeCoverage.hlsli"
bool RtrTrace(float3 origin,float3 direction,float distance,uint role,out RtrReceiver receiver)
{
    RayDesc ray;ray.Origin=origin;ray.Direction=direction;ray.TMin=g.gi.z;ray.TMax=distance;
    RayQuery<RAY_FLAG_NONE> query;query.TraceRayInline(t_Scene,RtrQueryFlags(),1,ray);
    while(query.Proceed())if(query.CandidateType()==CANDIDATE_NON_OPAQUE_TRIANGLE)
    {
        float3 transmission;bool opaque;
        if(RtrAcceptCandidate(query.CandidateInstanceIndex(),query.CandidateGeometryIndex(),query.CandidatePrimitiveIndex(),
            query.CandidateTriangleBarycentrics(),query.CandidateTriangleFrontFace(),role,transmission,opaque))query.CommitNonOpaqueTriangleHit();
    }
    receiver=(RtrReceiver)0;
    if(query.CommittedStatus()!=COMMITTED_TRIANGLE_HIT)return false;
    receiver=RtrLoad(query.CommittedInstanceIndex(),query.CommittedGeometryIndex(),query.CommittedPrimitiveIndex(),
        query.CommittedTriangleBarycentrics(),origin,direction,query.CommittedRayT(),query.CommittedWorldToObject3x4());return true;
}
float3 RtrShadowRaySample(RayDesc ray,float lightDistance,bool filtering,out uint partialCount)
{
    RtrCount(2);
    RayQuery<RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH> query;query.TraceRayInline(t_Scene,RtrQueryFlags(),1,ray);float3 visibility=1;
    partialCount=0;float partialCoverage=0;
    while(query.Proceed())if(query.CandidateType()==CANDIDATE_NON_OPAQUE_TRIANGLE)
    {
        if(filtering){
            uint role=uint(t_InstanceAttributes[query.CandidateInstanceIndex()].rolesVisibility.x);
            if((role&2)==0||((role&16)==0&&!query.CandidateTriangleFrontFace()))continue;
            float coverage=RtrCandidateCoverage(ray,query.CandidateInstanceIndex(),query.CandidateGeometryIndex(),query.CandidatePrimitiveIndex(),
                query.CandidateTriangleBarycentrics(),query.CandidateTriangleRayT(),lightDistance,true);
            if(coverage>=0){
                if(coverage==0)continue;
                if(coverage==1){visibility=0;query.Abort();break;}
                if(coverage>1||++partialCount>=2){partialCount=2;query.Abort();break;}
                partialCoverage=coverage;continue;
            }
        }
        float3 transmission;bool opaque;
        if(!RtrAcceptCandidate(query.CandidateInstanceIndex(),query.CandidateGeometryIndex(),query.CandidatePrimitiveIndex(),
            query.CandidateTriangleBarycentrics(),query.CandidateTriangleFrontFace(),2,transmission,opaque))continue;
        if(rtrCoverageFiltering){
            uint index=t_Instances[query.CandidateInstanceIndex()].firstGeometryIndex+query.CandidateGeometryIndex();
            GeometryData geometry=t_Geometry[index];RtrGeometryAttributes attributes=t_GeometryBaselines[index];
            MaterialConstants material=RtrMaterial(geometry,query.CandidatePrimitiveIndex(),attributes);
            rtrMovingCoverage|=t_InstanceAttributes[query.CandidateInstanceIndex()].rolesVisibility.w!=0
                ||material.domain==MaterialDomain_AlphaTested||material.domain==MaterialDomain_TransmissiveAlphaTested;
        }
        if(opaque){visibility=0;query.Abort();}else visibility*=transmission;
    }
    if(query.CommittedStatus()==COMMITTED_TRIANGLE_HIT)return 0;
    return visibility*(1-partialCoverage);
}
float3 RtrShadowRay(RayDesc ray,float lightDistance=0)
{
    uint partial;float3 center=RtrShadowRaySample(ray,lightDistance,rtrCoverageFiltering,partial);
    if(partial<2)return center;
    rtrUnresolvedCoverage=true;
    const float2 offsets[4]={float2(.125,.375),float2(-.125,-.375),float2(.375,-.125),float2(-.375,.125)};
    float3 sum=0;
    [unroll]for(uint i=0;i<4;i++){
        float3 offset=rtrReceiverDx*offsets[i].x+rtrReceiverDy*offsets[i].y;RayDesc sample=ray;sample.Origin+=offset;
        if(lightDistance>0){float3 delta=ray.Direction*lightDistance-offset;sample.Direction=RtrNormalize(delta);
            sample.TMax=max(.002,length(delta)-(lightDistance-ray.TMax));}
        uint ignored;sum+=RtrShadowRaySample(sample,lightDistance,false,ignored);
    }
    return sum*.25;
}
float3 RtrShadow(RtrReceiver receiver,float3 direction,float distance)
{
    RayDesc ray;ray.Origin=receiver.position+receiver.geometricNormal*g.gi.z;ray.Direction=direction;ray.TMin=g.gi.z;ray.TMax=max(g.gi.z,distance-g.gi.z*2);
    return RtrShadowRay(ray,distance<1e19?distance:0);
}
float3 RtrSupportShadow(RtrReceiver receiver,RtrSource source,float3 direction,float distance,uint visibilityMode)
{
    float end;
    float radius=(uint(source.identity.w)&16)!=0?source.cone.x:source.extent.x;
    if(visibilityMode==2)end=max(.002,distance-radius-.004);
    else {
        float3 a=abs(direction);
        float3 slab=float3(a.x>0?source.extent.x/a.x:1e30,a.y>0?source.extent.y/a.y:1e30,a.z>0?source.extent.z/a.z:1e30);
        end=distance-min(slab.x,min(slab.y,slab.z))-.004;
        if(end<=.005)return 1;
    }
    RayDesc ray;ray.Origin=receiver.position+receiver.normal*.003;ray.Direction=direction;
    ray.TMin=.001;ray.TMax=end;return RtrShadowRay(ray,distance);
}
bool RtrSupport(RtrNode node,float3 position,bool linearL1)
{
    float3 delta=max(max(node.lower.xyz-position,position-node.left.xyz),0);float radius=node.upper.z;
    // The expanded squared bound is conservative, including near-boundary
    // roundoff. Actual source shape/radius/attenuation is still evaluated at leaves.
    // An all-L1 subtree has the tighter taxicab support of its emitting AABB.
    // The header enables this only when every represented source uses L1;
    // mixed and inverse-square fields keep their original Euclidean bound.
    if(linearL1)return radius>0&&dot(delta,float3(1,1,1))<=radius*1.000004;
    return radius>0&&all(delta<=radius)&&dot(delta,delta)<=radius*radius*1.000004;
}
#ifndef RTR_LIGHT_GROUP_CAPACITY
#define RTR_LIGHT_GROUP_CAPACITY 32
#endif
#ifndef RTR_MAX_COMPOSITION
#define RTR_MAX_COMPOSITION 0
#endif
#if RTR_MAX_COMPOSITION == 1 && RTR_LIGHT_GROUP_CAPACITY <= 4 && !RTR_WORK_STATISTICS
#define RTR_SOURCE_HISTORY 1
Texture2DArray<uint> t_SourceWinners : register(t0,space3);
RWTexture2DArray<uint> u_SourceWinners : register(u0,space3);
static uint2 rtrSourcePixel=0;
#else
#define RTR_SOURCE_HISTORY 0
#endif
void RtrSetSourcePixel(uint2 pixel){
#if RTR_SOURCE_HISTORY
    rtrSourcePixel=pixel;
#endif
}
uint RtrSourceGroup(RtrSource source){
#if RTR_LIGHT_GROUP_CAPACITY == 1
    return 0;
#else
    return uint(source.cone.z);
#endif
}
struct RtrSourceGroups
{
#if RTR_MAX_COMPOSITION != 2
    float3 maxima[RTR_LIGHT_GROUP_CAPACITY];float scores[RTR_LIGHT_GROUP_CAPACITY];uint2 keys[RTR_LIGHT_GROUP_CAPACITY];uint mask;
#endif
#if RTR_SOURCE_HISTORY
    uint slots[RTR_LIGHT_GROUP_CAPACITY];
#endif
#if RTR_MAX_COMPOSITION != 1
    float3 channels[RTR_LIGHT_GROUP_CAPACITY];uint channelMask;
#if RTR_MAX_COMPOSITION == 0
    float3 sum;
#endif
#endif
};
bool RtrCanImprove(RtrSourceGroups groups,RtrSource source,float3 upper)
{
    uint composition=uint(source.cone.w),group=RtrSourceGroup(source),bit=1u<<group;
#if RTR_MAX_COMPOSITION != 1
#if RTR_MAX_COMPOSITION == 0
    if(composition==0)return any(upper>0);
    if(composition==2)
#endif
        return (groups.channelMask&bit)==0||any(upper>groups.channels[group]);
#endif
#if RTR_MAX_COMPOSITION != 2
    if((groups.mask&bit)==0)return true;
    float score=dot(upper,float3(.2126,.7152,.0722));uint2 key=asuint(source.identity.zy),old=groups.keys[group];
    return score>groups.scores[group]||(score==groups.scores[group]&&(key.x<old.x||(key.x==old.x&&key.y<old.y)));
#endif
}
void RtrAccumulate(inout RtrSourceGroups groups,RtrSource source,float3 contribution,uint slot)
{
    uint composition=uint(source.cone.w),group=RtrSourceGroup(source),bit=1u<<group;
#if RTR_MAX_COMPOSITION != 1
#if RTR_MAX_COMPOSITION == 0
    if(composition==0){groups.sum+=contribution;return;}
    if(composition==2)
#endif
    {groups.channels[group]=(groups.channelMask&bit)==0?contribution:max(groups.channels[group],contribution);groups.channelMask|=bit;return;}
#endif
#if RTR_MAX_COMPOSITION != 2
    uint2 key=asuint(source.identity.zy);bool replace=(groups.mask&bit)==0;float score=dot(contribution,float3(.2126,.7152,.0722));
    if(!replace){float old=groups.scores[group];
        replace=score>old||(score==old&&(key.x<groups.keys[group].x||(key.x==groups.keys[group].x&&key.y<groups.keys[group].y)));}
    if(replace){groups.maxima[group]=contribution;groups.scores[group]=score;groups.keys[group]=key;groups.mask|=bit;
#if RTR_SOURCE_HISTORY
        groups.slots[group]=slot+1;
#endif
    }
#endif
}
float3 RtrGroupTotal(RtrSourceGroups groups)
{
    float3 result=0;
#if RTR_MAX_COMPOSITION == 0
    result=groups.sum;
#endif
#if RTR_MAX_COMPOSITION != 2
    uint mask=groups.mask;
    while(mask!=0){uint group=firstbitlow(mask);mask&=mask-1;result+=groups.maxima[group];}
#endif
#if RTR_MAX_COMPOSITION != 1
    uint channelMask=groups.channelMask;while(channelMask!=0){uint group=firstbitlow(channelMask);channelMask&=channelMask-1;result+=groups.channels[group];}
#endif
    return result;
}
float3 RtrNodeUpperBound(RtrNode node,float3 position,bool linearL1)
{
    // Max RGB/radius and minimum exponent conservatively bound every child.
    // Negative exponent marks a mixed subtree containing L1: omit inverse square.
    float3 delta=max(max(node.lower.xyz-position,position-node.left.xyz),0);
    float distance=linearL1?dot(delta,float3(1,1,1)):length(delta);
    float attenuation=saturate(1-distance/node.upper.z);
    [branch]if(!linearL1){
        attenuation=RtrFalloff(attenuation,abs(node.right.y));
        if(node.right.y>0)attenuation/=max(distance*distance,1);
    }
    return float3(node.upper.xy,node.right.x)*attenuation*1.00001+1e-12;
}
uint RtrNodeWinnerMask(RtrSourceGroups groups,uint composition)
{
#if RTR_MAX_COMPOSITION == 2
    return groups.channelMask;
#elif RTR_MAX_COMPOSITION == 1
    return groups.mask;
#else
    return composition==2?groups.channelMask:groups.mask;
#endif
}
bool RtrNodeHasUnseenGroup(uint mask,RtrSourceGroups groups,uint composition)
{
#if RTR_LIGHT_GROUP_CAPACITY == 1
    mask=mask!=0?1:0;
#endif
    return (mask&~RtrNodeWinnerMask(groups,composition))!=0;
}
bool RtrNodeCanImprove(uint mask,float3 upper,RtrSourceGroups groups,uint composition)
{
#if RTR_LIGHT_GROUP_CAPACITY == 1
    mask=mask!=0?1:0;
#endif
    if((mask&~RtrNodeWinnerMask(groups,composition))!=0)return true;
    while(mask!=0){uint group=firstbitlow(mask);mask&=mask-1;
#if RTR_MAX_COMPOSITION != 1
#if RTR_MAX_COMPOSITION == 0
        if(composition==2){if(any(upper>groups.channels[group]))return true;}
        else
#else
        if(any(upper>groups.channels[group]))return true;
#endif
#endif
#if RTR_MAX_COMPOSITION != 2
        if(dot(upper,float3(.2126,.7152,.0722))>=groups.scores[group])return true;
#endif
    }
    return false;
}
// Each declared composition/group is independent, including the dynamic-only
// subset used by the default baseline correction. Visibility precedes selection.
void RtrEvaluateSource(RtrReceiver receiver,uint slot,bool separateDynamic,inout RtrSourceGroups allSources,inout RtrSourceGroups dynamicSources,
    inout bool staticCovered,bool needStaticCoverage)
{
    RtrSource source=t_Sources[slot+1];float3 origin=source.position.xyz;
    if(source.position.w==1)origin=clamp(receiver.position,origin-source.extent.xyz,origin+source.extent.xyz);
    float3 delta=origin-receiver.position;
    bool isLinear=(uint(source.identity.w)&2)!=0,isDynamic=separateDynamic&&(uint(source.identity.w)&1)!=0;
    float attenuation=0;
    [branch] if(isLinear){
        attenuation=saturate(1-dot(abs(delta),float3(1,1,1))/source.direction.w);
        float3 upper=source.color.rgb*source.color.w*attenuation;
        if(!needStaticCoverage||staticCovered)
            if(!RtrCanImprove(allSources,source,upper)&&(!isDynamic||!RtrCanImprove(dynamicSources,source,upper)))return;
    }
    float distance=length(delta);if(distance>=source.direction.w)return;
    uint visibilityMode=(uint(source.identity.w)>>2)&3;bool centerSupport=visibilityMode!=0;
    float3 centerDelta=source.position.xyz-receiver.position;
    float visibilityRadius=(uint(source.identity.w)&16)!=0?source.cone.x:source.extent.x;
    bool insideSupport=centerSupport&&(visibilityMode==2?length(centerDelta)<=visibilityRadius+.005:all(abs(centerDelta)<=source.extent.xyz+.005));
    float3 visibilityDelta=centerSupport?source.position.xyz-receiver.position:delta;
    float visibilityDistance=length(visibilityDelta);
    float3 direction=visibilityDistance>1e-5?visibilityDelta/visibilityDistance:receiver.normal;
    bool outsideHemisphere=!insideSupport&&dot(receiver.normal,direction)<=(centerSupport?.0001:0);
    if(!needStaticCoverage&&outsideHemisphere)return;
    [branch] if(!isLinear)attenuation=RtrFalloff(saturate(1-distance/source.direction.w),source.extent.w)/max(distance*distance,1);
    if(source.position.w==2){float cosine=dot(source.direction.xyz,-direction);
        attenuation*=source.cone.x==source.cone.y?step(source.cone.y,cosine):smoothstep(source.cone.y,source.cone.x,cosine);}
    float3 contribution=source.color.rgb*source.color.w*attenuation;
    // Coverage is a support property before visibility. A fully occluded static
    // source must still replace its baked host contribution, while a missing
    // static capture must leave unrelated host illumination intact.
    if(needStaticCoverage&&(uint(source.identity.w)&1)==0&&any(contribution>0))staticCovered=true;
    if(outsideHemisphere)return;
    if(!isLinear||source.position.w==2)
        if(!RtrCanImprove(allSources,source,contribution)&&(!isDynamic||!RtrCanImprove(dynamicSources,source,contribution)))return;
    if(source.identity.x>0&&!insideSupport&&any(contribution!=0)){
        if(centerSupport)contribution*=RtrSupportShadow(receiver,source,direction,visibilityDistance,visibilityMode);
        else if(distance>1e-5)contribution*=RtrShadow(receiver,direction,distance);
    }
    RtrAccumulate(allSources,source,contribution,slot);if(isDynamic)RtrAccumulate(dynamicSources,source,contribution,slot);
}
void RtrTraverseSparse(RtrReceiver receiver,uint root,uint count,bool separateDynamic,uint bounded,bool linearL1,
    inout RtrSourceGroups allSources,inout RtrSourceGroups dynamicSources,inout bool staticCovered,bool needStaticCoverage)
{
#if RTR_MAX_COMPOSITION
    // Only the branch choice and pending bits need per-ray storage. Parent
    // indices live in the retained tree, avoiding a dynamically indexed local
    // 32-entry stack across each shadow RayQuery.
    uint next=root,depth=0,pending=0,deferredLeft=0;
    while(next!=0xffffffff){
        RtrNode node=t_SourceTree[next];RtrCount(4);bool supported=RtrSupport(node,receiver.position,linearL1);
        bool nodeDynamic=separateDynamic&&(asuint(node.right.z)&0x80000000u)!=0;
        if(supported&&bounded&&(!needStaticCoverage||staticCovered)){uint mask=asuint(node.right.w);
            // A missing winner accepts the node regardless of its score. Avoid
            // sqrt/falloff/division until both queried subsets have winners.
            [branch]if(!RtrNodeHasUnseenGroup(mask,allSources,bounded)&&(!nodeDynamic||!RtrNodeHasUnseenGroup(mask,dynamicSources,bounded))){
                float3 upper=RtrNodeUpperBound(node,receiver.position,linearL1);
                supported=RtrNodeCanImprove(mask,upper,allSources,bounded)||(nodeDynamic&&RtrNodeCanImprove(mask,upper,dynamicSources,bounded));}}
        if(supported&&node.upper.w<0){
            uint left=uint(node.lower.w),right=uint(node.left.w);bool leftFirst=false;
            if(bounded){RtrNode a=t_SourceTree[left],b=t_SourceTree[right];
                float3 da=max(max(a.lower.xyz-receiver.position,receiver.position-a.left.xyz),0);
                float3 db=max(max(b.lower.xyz-receiver.position,receiver.position-b.left.xyz),0);
                leftFirst=(linearL1?dot(da,float3(1,1,1)):dot(da,da))<(linearL1?dot(db,float3(1,1,1)):dot(db,db));}
            uint bit=1u<<depth;pending|=bit;deferredLeft=(deferredLeft&~bit)|(leftFirst?0:bit);++depth;
            next=leftFirst?left:right;continue;
        }
        if(supported){uint slot=uint(node.upper.w);if(slot<count){RtrCount(5);RtrEvaluateSource(receiver,slot,separateDynamic,allSources,dynamicSources,staticCovered,needStaticCoverage);}}
        next=0xffffffff;uint parent=asuint(node.right.z)&0x7fffffffu;
        while(depth!=0){
            uint bit=1u<<--depth;RtrNode ancestor=t_SourceTree[parent];
            if((pending&bit)!=0){pending&=~bit;next=(deferredLeft&bit)!=0?uint(ancestor.lower.w):uint(ancestor.left.w);++depth;break;}
            parent=asuint(ancestor.right.z)&0x7fffffffu;
        }
    }
#else
    uint stack[32],pending=0,next=root;
    while(next!=0xffffffff)
    {
        RtrNode node=t_SourceTree[next];RtrCount(4);bool supported=RtrSupport(node,receiver.position,linearL1);
        bool nodeDynamic=separateDynamic&&(asuint(node.right.z)&0x80000000u)!=0;
        if(supported&&bounded&&(!needStaticCoverage||staticCovered)){uint mask=asuint(node.right.w);
            [branch]if(!RtrNodeHasUnseenGroup(mask,allSources,bounded)&&(!nodeDynamic||!RtrNodeHasUnseenGroup(mask,dynamicSources,bounded))){
                float3 upper=RtrNodeUpperBound(node,receiver.position,linearL1);
                supported=RtrNodeCanImprove(mask,upper,allSources,bounded)||(nodeDynamic&&RtrNodeCanImprove(mask,upper,dynamicSources,bounded));}}
        if(supported&&node.upper.w<0){
            uint deferred=uint(node.lower.w),first=uint(node.left.w);
            if(bounded){RtrNode a=t_SourceTree[deferred],b=t_SourceTree[first];
                float3 da=max(max(a.lower.xyz-receiver.position,receiver.position-a.left.xyz),0);
                float3 db=max(max(b.lower.xyz-receiver.position,receiver.position-b.left.xyz),0);
                if((linearL1?dot(da,float3(1,1,1)):dot(da,da))<(linearL1?dot(db,float3(1,1,1)):dot(db,db))){uint swap=deferred;deferred=first;first=swap;}}
            stack[pending++]=deferred;next=first;continue;
        }
        if(pending>0)next=stack[--pending];else next=0xffffffff;
        if(!supported)continue;
        uint slot=uint(node.upper.w);if(slot>=count)continue;RtrCount(5);
        RtrEvaluateSource(receiver,slot,separateDynamic,allSources,dynamicSources,staticCovered,needStaticCoverage);
    }
#endif
}
uint RtrRegionWord(uint base,uint index)
{
    RtrNode n=t_SourceTree[base+index/16];uint part=(index/4)&3;
    return asuint(part==0?n.lower[index&3]:part==1?n.left[index&3]:part==2?n.upper[index&3]:n.right[index&3]);
}
bool RtrRegionLookup(float3 position,out uint start,out uint count)
{
    start=count=0;float3 coordinate=floor(position/t_Sources[0].cone.w);
    if(any(!isfinite(coordinate))||any(coordinate< -1048576)||any(coordinate>=1048576))return false;
    int3 cell=int3(coordinate);uint mask=uint(t_Sources[0].cone.y);
    uint slot=(uint(cell.x)*73856093u)^(uint(cell.y)*19349663u)^(uint(cell.z)*83492791u);slot&=mask;
    [loop]for(uint probe=0;probe<16;probe++,slot=(slot+1)&mask){
        RtrNode bucket=t_SourceTree[uint(t_Sources[0].cone.x)+slot];uint size=asuint(bucket.left.x);
        if(size==0xffffffff)return true;
        if(all(asint(bucket.lower.xyz)==cell)){start=asuint(bucket.lower.w);count=size;return true;}
    }
    return false;
}
void RtrSparseLocalWithCoverage(RtrReceiver receiver,out float3 local,out float3 dynamic,out bool staticCovered,bool needDynamic=true,bool primary=false,bool needStaticCoverage=false)
{
    local=dynamic=0;staticCovered=false;uint count=uint(t_Sources[0].position.x);if(count==0)return;
#if defined(RTR_ALL_DYNAMIC) && RTR_ALL_DYNAMIC
    bool allDynamic=true,separateDynamic=false;
#else
    bool allDynamic=t_Sources[0].direction.x!=0;
    bool separateDynamic=needDynamic&&!allDynamic&&t_Sources[0].direction.y==0;
#endif
    needStaticCoverage=needStaticCoverage&&!allDynamic;
    RtrSourceGroups allSources=(RtrSourceGroups)0,dynamicSources=(RtrSourceGroups)0;
#if RTR_SOURCE_HISTORY
    bool history=primary&&(g.publication.w&8)!=0;
    if(history){[unroll]for(uint group=0;group<RTR_LIGHT_GROUP_CAPACITY;group++){
        uint encoded=t_SourceWinners.Load(int4(rtrSourcePixel,group,0));
        if(encoded>0&&encoded<=count)RtrEvaluateSource(receiver,encoded-1,separateDynamic,allSources,dynamicSources,staticCovered,needStaticCoverage);
    }}
#endif
    uint bounded=uint(t_Sources[0].position.w);bool linearL1=t_Sources[0].extent.x!=0;
    uint start=0,regionalCount=0;bool regional=t_Sources[0].cone.w!=0;
    RtrCount(6);
    // Resolve lookup before evaluating any roots. A fallback therefore cannot
    // accumulate an already visited region a second time.
    bool found=regional&&RtrRegionLookup(receiver.position,start,regionalCount);
    if(found){
        RtrCount(7);if(regionalCount==0)RtrCount(8);
        uint globalCount=uint(t_Sources[0].extent.y),base=uint(t_Sources[0].cone.z);
        for(uint i=0;i<globalCount+regionalCount;i++){
            bool globalRoot=i<globalCount;uint root=RtrRegionWord(base,globalRoot?i:start+i-globalCount);
            RtrCount(10);if(globalRoot)RtrCount(11);
            RtrTraverseSparse(receiver,root,count,separateDynamic,bounded,linearL1,allSources,dynamicSources,staticCovered,needStaticCoverage);
        }
    }else{
        RtrCount(9);RtrTraverseSparse(receiver,uint(t_Sources[0].position.y),count,separateDynamic,bounded,linearL1,allSources,dynamicSources,staticCovered,needStaticCoverage);
    }
#if RTR_SOURCE_HISTORY
    if(history){[unroll]for(uint group=0;group<RTR_LIGHT_GROUP_CAPACITY;group++)u_SourceWinners[uint3(rtrSourcePixel,group)]=allSources.slots[group];}
#endif
    local=RtrGroupTotal(allSources);dynamic=needDynamic&&allDynamic?local:RtrGroupTotal(dynamicSources);
}
void RtrLocalWithCoverage(RtrReceiver receiver,out float3 local,out float3 dynamic,out bool staticCovered,bool needDynamic=true,bool primary=false,bool needStaticCoverage=false)
{
    RtrSparseLocalWithCoverage(receiver,local,dynamic,staticCovered,needDynamic,primary,needStaticCoverage);
#ifndef RTR_ANALYTIC_LIGHTS
#define RTR_ANALYTIC_LIGHTS 1
#endif
#if RTR_ANALYTIC_LIGHTS
    uint count=asuint(g.farJitter.w);if(count<=1)return;
    uint base=asuint(g.gi.w),stack[32],pending=0,next=asuint(g.sunColor.w);
    while(next!=0xffffffff)
    {
        RtrAnalytic node=t_Analytic[base+next];RtrCount(4);
        float3 boundsDelta=max(max(node.position.xyz-receiver.position,receiver.position-node.direction.xyz),0);
        bool supported=node.color.y!=0||length(boundsDelta)<node.color.z;
        if(supported&&node.color.w<0){stack[pending++]=uint(node.position.w);next=uint(node.direction.w);continue;}
        if(pending>0)next=stack[--pending];else next=0xffffffff;
        if(!supported)continue;
        uint index=uint(node.color.w);if(index==0||index>=count)continue;RtrCount(5);RtrAnalytic light=t_Analytic[index];
        float attenuation=1,distance=1e20;float3 direction=-RtrNormalize(light.direction.xyz);
        if(light.position.w!=0){float3 delta=light.position.xyz-receiver.position;distance=length(delta);if(distance<=1e-5||distance>=light.direction.w)continue;
            direction=delta/distance;float fade=saturate(1-distance/light.direction.w);attenuation=fade*fade/max(distance*distance,1);
            if(light.position.w==2){float cosine=dot(light.direction.xyz,-direction);attenuation*=light.cone.x==light.cone.y?step(light.cone.y,cosine):smoothstep(light.cone.y,light.cone.x,cosine);}}
        if(dot(receiver.normal,direction)<=0)continue;
        float3 contribution=light.color.rgb*light.color.w*attenuation;
        if(light.cone.z>0)contribution*=RtrShadow(receiver,direction,distance);
        // SceneLight has no baked-baseline layer; its declared additive sources are dynamic.
        local+=contribution;dynamic+=contribution;
    }
#endif
}
// Preserve extension source compatibility; coverage is needed only by the
// explicit host-response contract, never by legacy correction or GI.
void RtrSparseLocal(RtrReceiver receiver,out float3 local,out float3 dynamic,bool needDynamic=true,bool primary=false)
{
    bool ignored;RtrSparseLocalWithCoverage(receiver,local,dynamic,ignored,needDynamic,primary,false);
}
void RtrLocal(RtrReceiver receiver,out float3 local,out float3 dynamic,bool needDynamic=true,bool primary=false)
{
    bool ignored;RtrLocalWithCoverage(receiver,local,dynamic,ignored,needDynamic,primary,false);
}
float RtrHostResponseValue(uint channel,uint local,uint sky)
{
    uint scalar=(sky*16+local)*3+channel;float2 packed=t_BaselineLookup[256+scalar/2];
    return (scalar&1)!=0?packed.y:packed.x;
}
float RtrHostResponseSample(uint channel,float2 levels)
{
    float2 p=saturate(levels)*15;uint2 lo=uint2(p),hi=min(lo+1,15);float2 f=frac(p);
    return lerp(lerp(RtrHostResponseValue(channel,lo.x,lo.y),RtrHostResponseValue(channel,hi.x,lo.y),f.x),
        lerp(RtrHostResponseValue(channel,lo.x,hi.y),RtrHostResponseValue(channel,hi.x,hi.y),f.x),f.y);
}
float3 RtrHostResponseGain(RtrReceiver receiver,float3 local,float3 dynamic,bool staticCovered,float3 sunVisibility,float3 moonVisibility)
{
    float3 targetLocal=staticCovered?saturate(local):max(receiver.baseline.x,saturate(dynamic));
    float weight=g.sun.w>0?saturate(g.sun.w):saturate(g.moon.w);
    float3 directionalVisibility=g.sun.w>0?sunVisibility:moonVisibility;
    float3 targetSky=receiver.baseline.y*lerp(1,directionalVisibility,weight),gain=1;
    if(g.style.moonAndMode.w==3){
        float3 blocked=g.style.correction.z*g.sun.w*(1-sunVisibility)
            +g.style.correction.w*g.moon.w*g.style.moonAndMode.rgb*(1-moonVisibility);
        targetSky=receiver.baseline.y*(1-saturate((1-receiver.baseline.x)*blocked));
    }
    [unroll]for(uint channel=0;channel<3;channel++){
        float before=RtrHostResponseSample(channel,receiver.baseline);
        float after=RtrHostResponseSample(channel,float2(targetLocal[channel],targetSky[channel]));
        gain[channel]=before>0?after/before:(g.style.moonAndMode.w==3||after==0?1:g.style.limits.y);
    }
    float influence=saturate(receiver.visibility)*(1-saturate(receiver.fog));
    return clamp(lerp(1,gain,influence),g.style.limits.x,g.style.limits.y);
}
float3 RtrIncident(RtrReceiver receiver)
{
    float3 local,dynamic;RtrLocal(receiver,local,dynamic,false);float cosine=max(dot(receiver.normal,g.sun.xyz),0);
    if(g.sun.w*cosine>0)local+=g.sunColor.rgb*g.sun.w*cosine*RtrShadow(receiver,g.sun.xyz,1e20);
    cosine=max(dot(receiver.normal,g.moon.xyz),0);
    if(g.moon.w*cosine>0)local+=g.style.moonAndMode.rgb*g.moon.w*cosine*RtrShadow(receiver,g.moon.xyz,1e20);
    return local;
}
float3 RtrOneBounce(RtrReceiver receiver)
{
    if(g.grid.w==0||receiver.diffuseWeight<=0)return 0;
    float3 axis=abs(receiver.normal.y)<.999?float3(0,1,0):float3(1,0,0);
    float3 tangent=RtrNormalize(cross(axis,receiver.normal)),bitangent=cross(receiver.normal,tangent),sum=0;
    for(uint i=0;i<g.grid.w;i++)
    {
        float radius=sqrt((float(i)+.5)/g.grid.w),angle=(float(i)+.5)*2.39996323;
        float3 direction=tangent*(radius*cos(angle))+bitangent*(radius*sin(angle))+receiver.normal*sqrt(max(0,1-radius*radius));
        RtrCount(3);RtrReceiver bounce;
        if(RtrTrace(receiver.position+receiver.geometricNormal*g.gi.z,direction,g.gi.x,4,bounce))
            sum+=bounce.albedo*bounce.diffuseWeight*RtrIncident(bounce);
    }
    return receiver.albedo*receiver.diffuseWeight*sum*(g.gi.y/g.grid.w)*receiver.visibility*(1-receiver.fog);
}
#ifndef RTR_LIGHTING_EXTENSION
float3 RtrDirectGain(RtrReceiver receiver)
{
    float3 gain=1;
    if((g.grid.z&1)!=0)
    {
        bool response=g.style.moonAndMode.w>=2,staticCovered;
        float3 local,dynamic;RtrLocalWithCoverage(receiver,local,dynamic,staticCovered,true,true,response);float3 sunVisibility=1,moonVisibility=1;
        if(g.sun.w>0&&dot(receiver.normal,g.sun.xyz)>0)sunVisibility=RtrShadow(receiver,g.sun.xyz,1e20);
        if(g.moon.w>0&&dot(receiver.normal,g.moon.xyz)>0)moonVisibility=RtrShadow(receiver,g.moon.xyz,1e20);
        gain=response?RtrHostResponseGain(receiver,local,dynamic,staticCovered,sunVisibility,moonVisibility)
            :RtrStyleGain(g.style,receiver.baseline,receiver.visibility,receiver.fog,local,dynamic,g.sun.w,sunVisibility,g.moon.w,moonVisibility);
    }
    rtrCoverageFiltering=false;
    return gain;
}
void RtrEvaluateReceiver(RtrReceiver receiver,out float3 gain,out float3 indirect)
{
    gain=RtrDirectGain(receiver);indirect=(g.grid.z&2)!=0?RtrOneBounce(receiver):0;
}
#else
#include RTR_LIGHTING_EXTENSION
#endif
#ifndef RTR_NATIVE_LIGHTING_LIBRARY
[numthreads(8,8,1)]
void main(uint2 pixel:SV_DispatchThreadID)
{
    if(any(pixel>=g.grid.xy))return;RtrSetSourcePixel(pixel);RtrCount(0);
    float2 ndc=(float2(pixel)+.5)/float2(g.grid.xy)*float2(2,-2)+float2(-1,1)+g.farJitter.yz;
    float4 nearView=mul(g.inverseClip,float4(ndc,g.cameraNear.w,1)),farView=mul(g.inverseClip,float4(ndc,g.farJitter.x,1));
    float3 direction=RtrNormalize(mul((float3x3)g.cameraToWorld,farView.xyz/farView.w-nearView.xyz/nearView.w));
    RtrReceiver receiver;float3 gain=1,indirect=0;bool valid=RtrTrace(g.cameraNear.xyz,direction,1e20,1,receiver);
    if((g.grid.z&4)!=0)u_Geometry[pixel]=valid?float4(receiver.normal,receiver.distance):0;
    if((g.grid.z&8)!=0)u_Identity[pixel]=valid?uint4(receiver.instance,receiver.geometry,receiver.primitive,1):uint4(0xffffffff,0xffffffff,0xffffffff,0);
    if(valid){RtrCount(1);RtrPrepareCoverage(receiver,pixel);RtrEvaluateReceiver(receiver,gain,indirect);}
    gain=float3(isfinite(gain.x)?clamp(gain.x,0,65504):1,isfinite(gain.y)?clamp(gain.y,0,65504):1,isfinite(gain.z)?clamp(gain.z,0,65504):1);
    indirect=float3(isfinite(indirect.x)?clamp(indirect.x,0,65504):0,isfinite(indirect.y)?clamp(indirect.y,0,65504):0,isfinite(indirect.z)?clamp(indirect.z,0,65504):0);
    if((g.grid.z&1)!=0)u_Direct[pixel]=float4(gain,RtrDirectAlpha(valid,receiver.opacity));
    if((g.grid.z&2)!=0)u_Indirect[pixel]=float4(indirect,valid?receiver.opacity:0);
    RtrFlushWork();
}
#endif // RTR_NATIVE_LIGHTING_LIBRARY
