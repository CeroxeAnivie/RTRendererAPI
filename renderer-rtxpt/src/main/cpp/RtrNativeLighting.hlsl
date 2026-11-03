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
struct RtrInstance {float4 rolesVisibility;float4 baseline;};
struct RtrVertexAttributes {float2 baseline;uint color;uint reserved;};
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
StructuredBuffer<uint2> t_GeometryBaselines : register(t8); // offset, triangle count
ByteAddressBuffer t_Application : register(t9);
StructuredBuffer<float2> t_BaselineLookup : register(t10); // 16x16, linear interpolation
StructuredBuffer<RtrAnalytic> t_Analytic : register(t11); // resident lights followed by their exact support tree
ByteAddressBuffer t_Buffers[] : register(t0,space1);
Texture2D<float4> t_Textures[] : register(t0,space2);
SamplerState s_Material : register(s0);
RWTexture2D<float4> u_Direct : register(u0);
RWTexture2D<float4> u_Indirect : register(u1);
RWTexture2D<float4> u_Geometry : register(u2);
RWTexture2D<uint4> u_Identity : register(u3);
RWByteAddressBuffer u_Work : register(u4);

struct RtrReceiver
{
    float3 position,normal,geometricNormal,albedo;
    float2 uv,baseline,barycentrics;
    float opacity,diffuseWeight,distance,visibility,fog,roughness,metalness,transmission;
    uint instance,geometry,primitive;
};
float3 RtrNormalize(float3 v){return v*rsqrt(max(dot(v,v),1e-30));}
#ifndef RTR_WORK_STATISTICS
#define RTR_WORK_STATISTICS 1
#endif
void RtrCount(uint index){
#if RTR_WORK_STATISTICS
    if((g.publication.w&1)!=0){uint unused;u_Work.InterlockedAdd(index*4,1,unused);}
#endif
}
uint RtrQueryFlags(){return (g.publication.w&2)!=0||g.style.limits.w!=0?RAY_FLAG_FORCE_NON_OPAQUE:RAY_FLAG_NONE;}
float3 RtrPosition(ByteAddressBuffer vertices,GeometryData geometry,uint i){return asfloat(vertices.Load3(geometry.positionOffset+i*12));}
float2 RtrUv(ByteAddressBuffer vertices,GeometryData geometry,uint i)
{return geometry.texCoord1Offset==0xffffffff?0:asfloat(vertices.Load2(geometry.texCoord1Offset+i*8));}
float4 RtrVertexColor(uint packed){return float4(packed&255,(packed>>8)&255,(packed>>16)&255,packed>>24)/255.0;}
RtrReceiver RtrLoad(uint instance,uint geometry,uint primitive,float2 attributes,float3 origin,float3 direction,float distance,float3x4 worldToObject)
{
    RtrReceiver r=(RtrReceiver)0;r.instance=instance;r.geometry=geometry;r.primitive=primitive;r.distance=distance;r.barycentrics=attributes;
    InstanceData inst=t_Instances[instance];GeometryData geom=t_Geometry[inst.firstGeometryIndex+geometry];
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
    MaterialConstants mat=t_Materials[geom.materialIndex];float4 base=float4(mat.baseOrDiffuseColor,mat.opacity);
    if((mat.flags&MaterialFlags_UseBaseOrDiffuseTexture)!=0)
        base*=t_Textures[NonUniformResourceIndex(mat.baseOrDiffuseTextureIndex)].SampleLevel(s_Material,r.uv,0);
    if((mat.flags&MaterialFlags_UseOpacityTexture)!=0)
        base.a*=t_Textures[NonUniformResourceIndex(mat.opacityTextureIndex)].SampleLevel(s_Material,r.uv,0).r;
    uint2 vertexRange=t_GeometryBaselines[inst.firstGeometryIndex+geometry];
    if(primitive<vertexRange.y){uint first=vertexRange.x+primitive*3;
        base*=RtrVertexColor(t_Baselines[first].color)*bary.x+RtrVertexColor(t_Baselines[first+1].color)*bary.y+RtrVertexColor(t_Baselines[first+2].color)*bary.z;}
    r.albedo=max(base.rgb,0);r.opacity=saturate(base.a);
    r.roughness=mat.roughness;r.metalness=mat.metalness;r.transmission=mat.transmissionFactor;
    if((mat.flags&MaterialFlags_UseMetalRoughOrSpecularTexture)!=0&&(mat.flags&MaterialFlags_UseSpecularGlossModel)==0){
        float4 sampled=t_Textures[NonUniformResourceIndex(mat.metalRoughOrSpecularTextureIndex)].SampleLevel(s_Material,r.uv,0);
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
        float3 map=t_Textures[NonUniformResourceIndex(mat.normalTextureIndex)].SampleLevel(s_Material,r.uv,0).xyz*2-1;
        map.xy*=mat.normalTextureScale;
        r.normal=RtrNormalize(t*map.x+cross(r.normal,t)*sign(tangent.w)*map.y+r.normal*map.z);
        if(dot(r.normal,r.geometricNormal)<0)r.normal=-r.normal;
    }
    RtrInstance data=t_InstanceAttributes[instance];r.visibility=data.rolesVisibility.y;r.fog=data.rolesVisibility.z;
    uint2 baseline=t_GeometryBaselines[inst.firstGeometryIndex+geometry];float2 coordinates=0;
    if(primitive<baseline.y){uint offset=baseline.x+primitive*3;
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
    GeometryData geom=t_Geometry[t_Instances[receiver.instance].firstGeometryIndex+receiver.geometry];MaterialConstants mat=t_Materials[geom.materialIndex];
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
    InstanceData inst=t_Instances[instance];GeometryData geom=t_Geometry[inst.firstGeometryIndex+geometry];
    MaterialConstants mat=t_Materials[geom.materialIndex];float4 base=float4(mat.baseOrDiffuseColor,mat.opacity);
    float3 bary=float3(1-attributes.x-attributes.y,attributes);float2 uv=0;
    const uint textureMask=MaterialFlags_UseBaseOrDiffuseTexture|MaterialFlags_UseOpacityTexture|MaterialFlags_UseTransmissionTexture;
    if((mat.flags&textureMask)!=0){
        ByteAddressBuffer indices=t_Buffers[NonUniformResourceIndex(geom.indexBufferIndex)];
        ByteAddressBuffer vertices=t_Buffers[NonUniformResourceIndex(geom.vertexBufferIndex)];
        uint3 tri=indices.Load3(geom.indexOffset+primitive*12);
        uv=RtrUv(vertices,geom,tri.x)*bary.x+RtrUv(vertices,geom,tri.y)*bary.y+RtrUv(vertices,geom,tri.z)*bary.z;
        if((mat.flags&MaterialFlags_UseBaseOrDiffuseTexture)!=0)base*=t_Textures[NonUniformResourceIndex(mat.baseOrDiffuseTextureIndex)].SampleLevel(s_Material,uv,0);
        if((mat.flags&MaterialFlags_UseOpacityTexture)!=0)base.a*=t_Textures[NonUniformResourceIndex(mat.opacityTextureIndex)].SampleLevel(s_Material,uv,0).r;
    }
    uint2 range=t_GeometryBaselines[inst.firstGeometryIndex+geometry];
    if(primitive<range.y){uint first=range.x+primitive*3;
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
float3 RtrShadow(RtrReceiver receiver,float3 direction,float distance)
{
    RtrCount(2);RayDesc ray;ray.Origin=receiver.position+receiver.geometricNormal*g.gi.z;ray.Direction=direction;ray.TMin=g.gi.z;ray.TMax=max(g.gi.z,distance-g.gi.z*2);
    RayQuery<RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH> query;query.TraceRayInline(t_Scene,RtrQueryFlags(),1,ray);float3 visibility=1;
    while(query.Proceed())if(query.CandidateType()==CANDIDATE_NON_OPAQUE_TRIANGLE)
    {
        float3 transmission;bool opaque;
        if(!RtrAcceptCandidate(query.CandidateInstanceIndex(),query.CandidateGeometryIndex(),query.CandidatePrimitiveIndex(),
            query.CandidateTriangleBarycentrics(),query.CandidateTriangleFrontFace(),2,transmission,opaque))continue;
        if(opaque){visibility=0;query.Abort();}else visibility*=transmission;
    }
    if(query.CommittedStatus()==COMMITTED_TRIANGLE_HIT)return 0;
    return visibility;
}
bool RtrSupport(RtrNode node,float3 position)
{
    float3 delta=max(max(node.lower.xyz-position,position-node.left.xyz),0);float radius=node.upper.z;
    return radius>0&&all(delta<=radius)&&dot(delta/radius,delta/radius)<=1;
}
#ifndef RTR_LIGHT_GROUP_CAPACITY
#define RTR_LIGHT_GROUP_CAPACITY 32
#endif
uint RtrSourceGroup(RtrSource source){
#if RTR_LIGHT_GROUP_CAPACITY == 1
    return 0;
#else
    return uint(source.cone.z);
#endif
}
struct RtrSourceGroups
{
    float3 sum,maxima[RTR_LIGHT_GROUP_CAPACITY],channels[RTR_LIGHT_GROUP_CAPACITY];uint2 keys[RTR_LIGHT_GROUP_CAPACITY];uint mask,channelMask;
};
bool RtrCanImprove(RtrSourceGroups groups,RtrSource source,float3 upper)
{
    uint composition=uint(source.cone.w),group=RtrSourceGroup(source),bit=1u<<group;
    if(composition==0)return any(upper>0);
    if(composition==2)return (groups.channelMask&bit)==0||any(upper>groups.channels[group]);
    // Visibility is in [0,1], so its luminance cannot exceed this bound.
    // Keep ties for the stable source-ID rule used by RtrAccumulate.
    return (groups.mask&bit)==0||dot(upper,float3(.2126,.7152,.0722))>=dot(groups.maxima[group],float3(.2126,.7152,.0722));
}
void RtrAccumulate(inout RtrSourceGroups groups,RtrSource source,float3 contribution)
{
    uint composition=uint(source.cone.w),group=RtrSourceGroup(source),bit=1u<<group;
    if(composition==0){groups.sum+=contribution;return;}
    if(composition==2){groups.channels[group]=(groups.channelMask&bit)==0?contribution:max(groups.channels[group],contribution);groups.channelMask|=bit;return;}
    uint2 key=asuint(source.identity.zy);bool replace=(groups.mask&bit)==0;
    if(!replace){float score=dot(contribution,float3(.2126,.7152,.0722)),old=dot(groups.maxima[group],float3(.2126,.7152,.0722));
        replace=score>old||(score==old&&(key.x<groups.keys[group].x||(key.x==groups.keys[group].x&&key.y<groups.keys[group].y)));}
    if(replace){groups.maxima[group]=contribution;groups.keys[group]=key;groups.mask|=bit;}
}
float3 RtrGroupTotal(RtrSourceGroups groups)
{
    float3 result=groups.sum;uint mask=groups.mask;
    while(mask!=0){uint group=firstbitlow(mask);mask&=mask-1;result+=groups.maxima[group];}
    mask=groups.channelMask;while(mask!=0){uint group=firstbitlow(mask);mask&=mask-1;result+=groups.channels[group];}return result;
}
// Each declared composition/group is independent, including the dynamic-only
// subset used by the default baseline correction. Visibility precedes selection.
void RtrSparseLocal(RtrReceiver receiver,out float3 local,out float3 dynamic,bool needDynamic=true)
{
    local=dynamic=0;uint count=uint(t_Sources[0].position.x);if(count==0)return;
    RtrSourceGroups allSources=(RtrSourceGroups)0,dynamicSources=(RtrSourceGroups)0;
    // Visit the right child directly and keep only its deferred sibling. This
    // preserves the original right-first order, without pushing/popping both
    // children at every branch of the retained AVL tree.
    uint stack[32],pending=0,next=uint(t_Sources[0].position.y);
    while(next!=0xffffffff)
    {
        RtrNode node=t_SourceTree[next];RtrCount(4);bool supported=RtrSupport(node,receiver.position);
        if(supported&&node.upper.w<0){stack[pending++]=uint(node.lower.w);next=uint(node.left.w);continue;}
        if(pending>0)next=stack[--pending];else next=0xffffffff;
        if(!supported)continue;
        uint slot=uint(node.upper.w);if(slot>=count)continue;RtrCount(5);RtrSource source=t_Sources[slot+1];
        float3 origin=source.position.xyz;
        if(source.position.w==1)origin=clamp(receiver.position,origin-source.extent.xyz,origin+source.extent.xyz);
        float3 delta=origin-receiver.position;
        bool isLinear=(uint(source.identity.w)&2)!=0,isDynamic=needDynamic&&(uint(source.identity.w)&1)!=0;
        float attenuation=0;
        [branch] if(isLinear){
            attenuation=saturate(1-dot(abs(delta),float3(1,1,1))/source.direction.w);
            // This bound does not need a normalized direction or Euclidean
            // distance. Spot cones and visibility can only reduce it. Check
            // both independent subsets before doing that work for a source
            // that cannot change the result (including stable-ID ties).
            float3 upper=source.color.rgb*source.color.w*attenuation;
            if(!RtrCanImprove(allSources,source,upper)&&(!isDynamic||!RtrCanImprove(dynamicSources,source,upper)))continue;
        }
        float distance=length(delta);if(distance>=source.direction.w)continue;
        float3 direction=distance>1e-5?delta/distance:receiver.normal;if(dot(receiver.normal,direction)<=0)continue;
        [branch] if(!isLinear)attenuation=pow(saturate(1-distance/source.direction.w),source.extent.w)/max(distance*distance,1);
        if(source.position.w==2){float cosine=dot(source.direction.xyz,-direction);
            attenuation*=source.cone.x==source.cone.y?step(source.cone.y,cosine):smoothstep(source.cone.y,source.cone.x,cosine);}
        float3 contribution=source.color.rgb*source.color.w*attenuation;
        if(!isLinear||source.position.w==2)
            if(!RtrCanImprove(allSources,source,contribution)&&(!isDynamic||!RtrCanImprove(dynamicSources,source,contribution)))continue;
        if(source.identity.x>0&&distance>1e-5)contribution*=RtrShadow(receiver,direction,distance);
        RtrAccumulate(allSources,source,contribution);if(isDynamic)RtrAccumulate(dynamicSources,source,contribution);
    }
    local=RtrGroupTotal(allSources);dynamic=RtrGroupTotal(dynamicSources);
}
void RtrLocal(RtrReceiver receiver,out float3 local,out float3 dynamic,bool needDynamic=true)
{
    RtrSparseLocal(receiver,local,dynamic,needDynamic);
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
        float3 local,dynamic;RtrLocal(receiver,local,dynamic);float3 sunVisibility=1,moonVisibility=1;
        if(g.sun.w>0&&dot(receiver.normal,g.sun.xyz)>0)sunVisibility=RtrShadow(receiver,g.sun.xyz,1e20);
        if(g.moon.w>0&&dot(receiver.normal,g.moon.xyz)>0)moonVisibility=RtrShadow(receiver,g.moon.xyz,1e20);
        gain=RtrStyleGain(g.style,receiver.baseline,receiver.visibility,receiver.fog,local,dynamic,g.sun.w,sunVisibility,g.moon.w,moonVisibility);
    }
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
    if(any(pixel>=g.grid.xy))return;RtrCount(0);
    float2 ndc=(float2(pixel)+.5)/float2(g.grid.xy)*float2(2,-2)+float2(-1,1)+g.farJitter.yz;
    float4 nearView=mul(g.inverseClip,float4(ndc,g.cameraNear.w,1)),farView=mul(g.inverseClip,float4(ndc,g.farJitter.x,1));
    float3 direction=RtrNormalize(mul((float3x3)g.cameraToWorld,farView.xyz/farView.w-nearView.xyz/nearView.w));
    RtrReceiver receiver;float3 gain=1,indirect=0;bool valid=RtrTrace(g.cameraNear.xyz,direction,1e20,1,receiver);
    if(valid){RtrCount(1);RtrEvaluateReceiver(receiver,gain,indirect);}
    gain=float3(isfinite(gain.x)?clamp(gain.x,0,65504):1,isfinite(gain.y)?clamp(gain.y,0,65504):1,isfinite(gain.z)?clamp(gain.z,0,65504):1);
    indirect=float3(isfinite(indirect.x)?clamp(indirect.x,0,65504):0,isfinite(indirect.y)?clamp(indirect.y,0,65504):0,isfinite(indirect.z)?clamp(indirect.z,0,65504):0);
    if((g.grid.z&1)!=0)u_Direct[pixel]=float4(gain,valid?receiver.opacity:0);
    if((g.grid.z&2)!=0)u_Indirect[pixel]=float4(indirect,valid?receiver.opacity:0);
    if((g.grid.z&4)!=0)u_Geometry[pixel]=valid?float4(receiver.normal,receiver.distance):0;
    if((g.grid.z&8)!=0)u_Identity[pixel]=valid?uint4(receiver.instance,receiver.geometry,receiver.primitive,1):uint4(0xffffffff,0xffffffff,0xffffffff,0);
}
#endif // RTR_NATIVE_LIGHTING_LIBRARY
