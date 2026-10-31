// Intel Embree 82c67a5318c535be94f017b9ec745498b5eb5606, Apache-2.0:
// tutorials/next_hit/next_hit_device.cpp, gather_next_hits / multi_pass.
// Retain the nearest N events of one traversal, consume in order, then refill.
// Unlike the tutorial, never truncate transport: ambiguous events use the original
// trace path, and a full list continues after its last consumed event.
#include "gpuscene_hit_evaluation.glsl"

mat4x3 gsCollectedObjectToWorld;
vec3 gsCollectedRayOrigin,gsCollectedRayDirection;
float gsCollectedHitT;
#define GS_BOUNDARY_OBJECT_TO_WORLD gsCollectedObjectToWorld
#define GS_BOUNDARY_RAY_ORIGIN gsCollectedRayOrigin
#define GS_BOUNDARY_RAY_DIRECTION gsCollectedRayDirection
#define GS_BOUNDARY_HIT_T gsCollectedHitT
#include "gpuscene_boundary_intersection.glsl"

const uint GS_CONNECTION_BATCH_CAPACITY=8u;
struct GsCollectedEvent {uvec4 identity;vec2 distance;};
GsCollectedEvent gsCollectedEvents[GS_CONNECTION_BATCH_CAPACITY];
uint gsCollectedCount,gsCollectedCursor;
bool gsCollectedComplete,gsCollectedFallback;

void gsCollectedReset() {
    gsCollectedCount=gsCollectedCursor=0u;
    gsCollectedComplete=gsCollectedFallback=false;
}
mat4x3 gsCollectedTransform(uint instance) {
    uint w=GPU_SCENE_INSTANCE_TRANSFORM_WORD;
    return mat4x3(
        uintBitsToFloat(uvec3(gsInstanceWord(instance,w),gsInstanceWord(instance,w+4u),gsInstanceWord(instance,w+8u))),
        uintBitsToFloat(uvec3(gsInstanceWord(instance,w+1u),gsInstanceWord(instance,w+5u),gsInstanceWord(instance,w+9u))),
        uintBitsToFloat(uvec3(gsInstanceWord(instance,w+2u),gsInstanceWord(instance,w+6u),gsInstanceWord(instance,w+10u))),
        uintBitsToFloat(uvec3(gsInstanceWord(instance,w+3u),gsInstanceWord(instance,w+7u),gsInstanceWord(instance,w+11u))));
}
float gsCollectedTolerance(float t) {return 8.0*1.192092896e-7*max(1.0,abs(t));}

// Counters occupy only the existing sparse diagnostic records. No production
// atomics, timestamps, global scratch buffer or additional CUDA readback.
void gsCollectedCountEvent(uint stage) {
#ifdef RTRENDERER_OPTICAL_PROFILE
    if(payload.diagnosticAddress!=0ul&&gsDetailActive(stage)) {
        uint word=(payload.diagnosticRecord*184u+stage)*2u;
        GsOpticalDiagnostic target=GsOpticalDiagnostic(payload.diagnosticAddress);
        target.values[word+1u]+=1ul;
    }
#endif
}
bool gsCollectedFill(vec3 origin,vec3 direction,float minimum,float maximum) {
    GS_DETAIL_BEGIN(176u);
    gsCollectedCount=gsCollectedCursor=0u;gsCollectedComplete=true;
    float firstDiscarded=1.0e30;
    gsCollectedRayOrigin=origin;gsCollectedRayDirection=direction;
    rayQueryEXT query;
    rayQueryInitializeEXT(query,sceneTlas,gl_RayFlagsNoOpaqueEXT,0xff,origin,minimum,direction,maximum);
    while(rayQueryProceedEXT(query)) {
        if(rayQueryGetIntersectionTypeEXT(query,false)!=gl_RayQueryCandidateIntersectionTriangleEXT) {
            gsCollectedFallback=true;break;
        }
        gsCollectedCountEvent(179u);
        uint instance=rayQueryGetIntersectionInstanceCustomIndexEXT(query,false);
        uint primitive=rayQueryGetIntersectionPrimitiveIndexEXT(query,false);
        uint boundary=gsInstanceWord(instance,GPU_SCENE_INSTANCE_MEDIUM_BOUNDARY_FLAGS_WORD);
        uint mesh=gsMeshBase(gsInstanceMeshSlot(instance));
        uint material=gsTriangleMaterialSlot(mesh,primitive)*GPU_SCENE_MATERIAL_RECORD_WORDS;
        uint flags=gsMaterials.words[material+GPU_SCENE_MATERIAL_FLAGS_WORD];
        if((boundary&2u)==0u) {
            if((flags&GPU_SCENE_FLAG_ACTIVE)==0u)continue;
            if(boundary==0u&&(flags&GPU_SCENE_FLAG_DOUBLE_SIDED)==0u
                    &&!rayQueryGetIntersectionFrontFaceEXT(query,false))continue;
        }
        vec2 bary=rayQueryGetIntersectionBarycentricsEXT(query,false);
        float rawT=rayQueryGetIntersectionTEXT(query,false),distance=rawT;
        // Edge repair can substitute another face or split a sub-ULP interval.
        // Keep the original ordered trace for the entire connection in that case.
        if(min(1.0-bary.x-bary.y,min(bary.x,bary.y))<8.0*1.192092896e-7) {
            gsCollectedCountEvent(183u);gsCollectedFallback=true;break;
        }
        if(boundary!=0u) {
            gsCollectedObjectToWorld=rayQueryGetIntersectionObjectToWorldEXT(query,false);
            gsCollectedHitT=rawT;
            if(!gsBoundaryTriangleHit(mesh,primitive,bary,distance)||distance<minimum)continue;
        }
        if(isnan(distance)||isinf(distance)||abs(distance-rawT)>gsCollectedTolerance(rawT)) {
            gsCollectedFallback=true;break;
        }
        bool duplicate=false;
        for(uint j=0u;j<gsCollectedCount;++j)
            if(all(equal(gsCollectedEvents[j].identity.xy,uvec2(instance,primitive)))
                    &&gsCollectedEvents[j].distance.x==rawT){duplicate=true;break;}
        if(duplicate)continue;
        GsCollectedEvent event=GsCollectedEvent(uvec4(instance,primitive,floatBitsToUint(bary)),vec2(rawT,distance));
        uint position=0u;
        while(position<gsCollectedCount&&gsCollectedEvents[position].distance.x<rawT)++position;
        if(gsCollectedCount==GS_CONNECTION_BATCH_CAPACITY) {
            gsCollectedComplete=false;gsCollectedCountEvent(182u);
            firstDiscarded=min(firstDiscarded,position<GS_CONNECTION_BATCH_CAPACITY
                ?gsCollectedEvents[GS_CONNECTION_BATCH_CAPACITY-1u].distance.x:rawT);
        }
        if(position<GS_CONNECTION_BATCH_CAPACITY) {
            uint last=min(gsCollectedCount,GS_CONNECTION_BATCH_CAPACITY-1u);
            while(last>position){gsCollectedEvents[last]=gsCollectedEvents[last-1u];--last;}
            gsCollectedEvents[position]=event;
            gsCollectedCount=min(gsCollectedCount+1u,GS_CONNECTION_BATCH_CAPACITY);
        }
    }
    // Hardware nearest-hit tie selection is unspecified. Do not invent an order
    // for coincident faces, neighboring float bins, or the overflow boundary.
    for(uint j=1u;j<gsCollectedCount;++j)
        if(gsCollectedEvents[j].distance.x-gsCollectedEvents[j-1u].distance.x
                    <=gsCollectedTolerance(gsCollectedEvents[j].distance.x)
                ||gsCollectedEvents[j].distance.y<=gsCollectedEvents[j-1u].distance.y)
            gsCollectedFallback=true;
    if(!gsCollectedComplete&&gsCollectedCount!=0u
            &&firstDiscarded-gsCollectedEvents[gsCollectedCount-1u].distance.x
                <=gsCollectedTolerance(firstDiscarded))gsCollectedFallback=true;
    GS_DETAIL_END();
    return !gsCollectedFallback;
}

bool gsCollectedConnectionHit(vec3 origin,vec3 direction,uvec4 launch,float minimum,float maximum,
        out GpuScenePayload hit) {
    if(gsCollectedFallback)return false;
    for(;;) {
        if(gsCollectedCursor==gsCollectedCount) {
            if(gsCollectedComplete) {
                gsCollectedCountEvent(180u);
                hit.state=uvec4(0);hit.worldPositionAndDistance=vec4(0,0,0,-1);
                hit.mediumBoundary=uvec4(0);hit.previousWorldPosition=vec4(0);hit.motionRevision=uvec2(0);
                hit.worldNormalAndRoughness=vec4(0,1,0,0);hit.baseColorAndOpacity=vec4(0);
                hit.emissiveAndMetallic=hit.transmissionIor=hit.compositeState=vec4(0);
                hit.surfaceState=uvec4(0);hit.launchInfo=launch;return true;
            }
            if(gsCollectedCount!=0u)gsCollectedCountEvent(181u);
            if(!gsCollectedFill(origin,direction,minimum,maximum))return false;
            if(gsCollectedCount==0u)continue;
        }
        GsCollectedEvent event=gsCollectedEvents[gsCollectedCursor++];
        if(event.distance.y<minimum)continue;
        GS_DETAIL_BEGIN(177u);
        uint instance=event.identity.x,primitive=event.identity.y;
        uint boundary=gsInstanceWord(instance,GPU_SCENE_INSTANCE_MEDIUM_BOUNDARY_FLAGS_WORD);
        hit.state=uvec4(0u,GS_PAYLOAD_OPTICAL_CONNECTION_QUERY,0u,0u);
        hit.launchInfo=launch;
#ifdef RTRENDERER_OPTICAL_PROFILE
        hit.diagnosticAddress=payload.diagnosticAddress;hit.diagnosticRecord=payload.diagnosticRecord;
#endif
        gsEvaluateTriangleHit(hit,instance,gsCollectedTransform(instance),origin,direction,
            boundary,primitive,uintBitsToFloat(event.identity.zw),event.distance.y);
        GS_DETAIL_END();
        uint blend=(hit.state.z>>1u)&0x7u;
        if(boundary==0u&&blend==GPU_SCENE_BLEND_MASKED) {
            uint material=gsTriangleMaterialSlot(gsMeshBase(gsInstanceMeshSlot(instance)),primitive)*GPU_SCENE_MATERIAL_RECORD_WORDS;
            if(hit.baseColorAndOpacity.a<uintBitsToFloat(gsMaterials.words[material+GPU_SCENE_MATERIAL_ALPHA_CUTOFF_WORD])) {
                // Even a batch containing only rejected coverage must make progress.
                // Coincident candidates were excluded before any event was consumed.
                minimum=uintBitsToFloat(floatBitsToUint(event.distance.y)+1u);continue;
            }
        }
        if(boundary==0u&&((hit.state.w&GPU_SCENE_FLAG_CASTS_SHADOW)==0u||gsSurfaceOverlay(hit))) {
            minimum=uintBitsToFloat(floatBitsToUint(hit.worldPositionAndDistance.w)+1u);
            if(minimum<=maximum)continue;
        }
        return true;
    }
}
