#ifndef RTR_NATIVE_OPTICAL_QUERIES_HLSLI
#define RTR_NATIVE_OPTICAL_QUERIES_HLSLI

// Geometry and coincident-boundary traversal shared by optical transport passes.
#define RTR_DIELECTRIC_NATIVE_FRESNEL_INCLUDED
#include "RtrDielectricTransport.hlsli"

static const uint RtrMediumRole = 32u;
static const uint RtrReceiverRole = 1u;
static const uint RtrOpticalBlockerRole = 2u;

struct RtrOpticalHit {
    uint instance, geometry, primitive, surface;
    float distance;
    float2 barycentrics;
    bool entering,blocked;
    float3x4 worldToObject;
};

bool RtrOpticalAcceptTerminal(uint instance,uint geometry,uint primitive,float2 barycentrics,bool frontFace,uint roles)
{
    if((roles&RtrReceiverRole)!=0&&RtrAcceptCandidate(instance,geometry,primitive,barycentrics,frontFace,RtrReceiverRole))
        return true;
    if((roles&RtrOpticalBlockerRole)==0)return false;
    RtrInstance attributes=t_InstanceAttributes[instance];
    uint flags=uint(attributes.rolesVisibility.x);
    if((flags&RtrOpticalBlockerRole)==0||((flags&16u)==0&&!frontFace))return false;
    uint index=t_Instances[instance].firstGeometryIndex+geometry;
    GeometryData geom=t_Geometry[index];
    RtrGeometryAttributes mapping=t_GeometryBaselines[index];
    MaterialConstants material=RtrMaterial(geom,primitive,mapping);
    float3 transmission;bool opaque;
    if(!RtrAcceptCandidateSurface(attributes,primitive,barycentrics,RtrOpticalBlockerRole,
        geom,mapping,material,1,0,0,transmission,opaque))return false;
    // A transmissive shadow proxy can share the boundary of a closed volume.
    // It must not terminate the ray or apply that boundary's transmission twice.
    return opaque||!any(transmission>0);
}

bool RtrFindOpticalHit(RayDesc ray,uint roles,out RtrOpticalHit hit)
{
    RayQuery<RAY_FLAG_FORCE_NON_OPAQUE | RAY_FLAG_SKIP_PROCEDURAL_PRIMITIVES> q;
    q.TraceRayInline(t_Scene,RAY_FLAG_NONE,RtrQueryMask(roles),ray);
    while(q.Proceed()){
        uint instance=q.CandidateInstanceIndex();
        uint instanceRoles=uint(t_InstanceAttributes[instance].rolesVisibility.x);
        if((instanceRoles&roles&RtrMediumRole)!=0 ||
            RtrOpticalAcceptTerminal(instance,q.CandidateGeometryIndex(),q.CandidatePrimitiveIndex(),
                q.CandidateTriangleBarycentrics(),q.CandidateTriangleFrontFace(),roles))
            q.CommitNonOpaqueTriangleHit();
    }
    hit=(RtrOpticalHit)0;
    if(q.CommittedStatus()!=COMMITTED_TRIANGLE_HIT)return false;
    hit.instance=q.CommittedInstanceIndex();hit.geometry=q.CommittedGeometryIndex();hit.primitive=q.CommittedPrimitiveIndex();
    hit.distance=q.CommittedRayT();hit.barycentrics=q.CommittedTriangleBarycentrics();hit.worldToObject=q.CommittedWorldToObject3x4();
    if((uint(t_InstanceAttributes[hit.instance].rolesVisibility.x)&RtrMediumRole)!=0){
        uint geometry=t_Instances[hit.instance].firstGeometryIndex+hit.geometry;
        hit.surface=RtrSurfaceIndex(t_GeometryBaselines[geometry],hit.primitive);
    }else if((roles&RtrOpticalBlockerRole)!=0)
        hit.blocked=!RtrAcceptCandidate(hit.instance,hit.geometry,hit.primitive,hit.barycentrics,q.CommittedTriangleFrontFace(),RtrReceiverRole);
    return true;
}

float3 RtrOpticalOutward(uint instance,uint geometry,uint primitive)
{
    InstanceData data=t_Instances[instance];
    GeometryData geom=t_Geometry[data.firstGeometryIndex+geometry];
    ByteAddressBuffer indices=t_Buffers[NonUniformResourceIndex(geom.indexBufferIndex)];
    ByteAddressBuffer vertices=t_Buffers[NonUniformResourceIndex(geom.vertexBufferIndex)];
    uint3 tri=indices.Load3(geom.indexOffset+primitive*12);
    float3 a=RtrPosition(vertices,geom,tri.x);
    return normalize(cross(mul((float3x3)data.transform,RtrPosition(vertices,geom,tri.y)-a),
                           mul((float3x3)data.transform,RtrPosition(vertices,geom,tri.z)-a)));
}

uint RtrOpticalVolumeSurface(uint instance)
{
    return RtrSurfaceIndex(t_GeometryBaselines[t_Instances[instance].firstGeometryIndex],0);
}

RtrDielectricBoundary RtrOpticalBoundary(uint surface,uint instance)
{
    RtrSurfaceMaterial material=t_SurfaceMaterials[surface-1];
    RtrDielectricBoundary boundary;
    boundary.medium.absorption=material.extinction;boundary.medium.ior=material.indexOfRefraction;
    boundary.priority=material.mediumPriorityPlusOne-1;
    // One homogeneous volume may have several surface materials. Use its
    // canonical row for tie-breaking and for matching entry, exit and camera seed.
    boundary.materialId=RtrOpticalVolumeSurface(instance);boundary.volumeId=instance;
    return boundary;
}

struct RtrOpticalEvent {
    uint2 entries[RTR_DIELECTRIC_CAPACITY],exits[RTR_DIELECTRIC_CAPACITY];
    uint entryCount,exitCount;
};

bool RtrOpticalAppend(inout uint2 sides[RTR_DIELECTRIC_CAPACITY],inout uint count,uint2 side)
{
    [loop]for(uint i=0;i<count;++i)if(all(sides[i]==side))return true;
    if(count==RTR_DIELECTRIC_CAPACITY)return false;
    sides[count++]=side;
    return true;
}

float RtrOpticalContactTolerance(float distance)
{
    return max(2e-5,distance*1.9073486e-6);
}

// Every coincident cap belongs to the same interface. A receiver in that
// contact takes precedence: transport ends in the incident medium rather than
// crossing a topology-only cap into a zero-thickness gap behind the receiver.
bool RtrOpticalGather(RayDesc ray,RtrOpticalHit hit,uint terminalRoles,
    out RtrOpticalEvent event,out RtrOpticalHit contact)
{
    event=(RtrOpticalEvent)0;
    contact=(RtrOpticalHit)0;
    if(hit.entering)event.entries[event.entryCount++]=uint2(hit.surface,hit.instance);
    else event.exits[event.exitCount++]=uint2(hit.surface,hit.instance);
    // Keep the contact probe close to its origin. A narrow [TMin,TMax]
    // around a distant hit can lose its opposing cap after instance transforms.
    float tolerance=RtrOpticalContactTolerance(hit.distance);
    float start=max(ray.TMin,hit.distance-tolerance);
    ray.Origin+=start*ray.Direction;
    ray.TMin=0;ray.TMax=min(ray.TMax,hit.distance+tolerance)-start;
    RayQuery<RAY_FLAG_FORCE_NON_OPAQUE | RAY_FLAG_SKIP_PROCEDURAL_PRIMITIVES> q;
    q.TraceRayInline(t_Scene,RAY_FLAG_NONE,RtrQueryMask(RtrMediumRole|terminalRoles),ray);
    bool valid=true;
    while(q.Proceed()){
        uint instance=q.CandidateInstanceIndex();
        uint roles=uint(t_InstanceAttributes[instance].rolesVisibility.x);
        if((roles&RtrMediumRole)==0){
            if(terminalRoles!=0&&RtrOpticalAcceptTerminal(instance,q.CandidateGeometryIndex(),
                q.CandidatePrimitiveIndex(),q.CandidateTriangleBarycentrics(),q.CandidateTriangleFrontFace(),terminalRoles))
                q.CommitNonOpaqueTriangleHit();
            continue;
        }
        if(instance==hit.instance||!valid)continue;
        uint geometry=q.CandidateGeometryIndex(),primitive=q.CandidatePrimitiveIndex();
        uint surface=RtrSurfaceIndex(t_GeometryBaselines[t_Instances[instance].firstGeometryIndex+geometry],primitive);
        if(surface==0||t_SurfaceMaterials[surface-1].mediumPriorityPlusOne==0)continue;
        if(dot(RtrOpticalOutward(instance,geometry,primitive),ray.Direction)<0){
            valid=RtrOpticalAppend(event.entries,event.entryCount,uint2(surface,instance));
        }else valid=RtrOpticalAppend(event.exits,event.exitCount,uint2(surface,instance));
    }
    if(q.CommittedStatus()!=COMMITTED_TRIANGLE_HIT)return valid;
    contact.instance=q.CommittedInstanceIndex();contact.geometry=q.CommittedGeometryIndex();contact.primitive=q.CommittedPrimitiveIndex();
    contact.distance=start+q.CommittedRayT();contact.barycentrics=q.CommittedTriangleBarycentrics();
    contact.worldToObject=q.CommittedWorldToObject3x4();
    if((terminalRoles&RtrOpticalBlockerRole)!=0)
        contact.blocked=!RtrAcceptCandidate(contact.instance,contact.geometry,contact.primitive,contact.barycentrics,q.CommittedTriangleFrontFace(),RtrReceiverRole);
    return true;
}

bool RtrOpticalApply(inout RtrDielectricStack stack,uint2 side,bool entering)
{
    RtrDielectricBoundary boundary=RtrOpticalBoundary(side.x,side.y);
    RtrDielectricTransition change=RtrDielectricPrepareTransition(stack,boundary,entering);
    if(change.status!=RTR_DIELECTRIC_READY)return false;
    RtrDielectricCommitCrossing(stack,boundary,change);
    return true;
}

bool RtrOpticalCross(RtrDielectricStack incident,RtrOpticalEvent event,out RtrDielectricStack transmitted)
{
    transmitted=incident;
    [loop]for(uint i=0;i<event.exitCount;++i)
        if(!RtrOpticalApply(transmitted,event.exits[i],false))return false;
    [loop]for(uint i=0;i<event.entryCount;++i)
        if(!RtrOpticalApply(transmitted,event.entries[i],true))return false;
    return true;
}

#endif
