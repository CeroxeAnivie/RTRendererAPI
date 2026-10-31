#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require
#ifdef RTRENDERER_CAMERA_SCATTERING_PROFILE
#extension GL_EXT_shader_realtime_clock : require
#endif

#include "gpuscene_common.glsl"
#include "gpuscene_boundary_intersection.glsl"

layout(location = 0) rayPayloadInEXT GpuScenePayload payload;
hitAttributeEXT vec2 hitAttributes;

#include "gpuscene_hit_evaluation.glsl"

void main()
{
#ifdef RTRENDERER_CAMERA_SCATTERING_PROFILE
    bool profileShadow=payload.state.y==GS_PAYLOAD_SHADOW_QUERY&&payload.state.z!=0u;
    uint64_t shadowStarted=profileShadow?clockRealtimeEXT():0ul;
#endif
    GS_DETAIL_BEGIN(24u);
    if (payload.state.y == GS_PAYLOAD_SHADOW_QUERY) {
        payload.state.x = 1u;
#ifdef RTRENDERER_CAMERA_SCATTERING_PROFILE
        if(profileShadow) {
            uint64_t elapsed=clockRealtimeEXT()-shadowStarted;
            payload.state.z=uint(elapsed);
            payload.state.w=uint(elapsed>>32u);
        }
#endif
        GS_DETAIL_END();return;
    }

    uint boundaryFlags=gsInstanceWord(gl_InstanceCustomIndexEXT,GPU_SCENE_INSTANCE_MEDIUM_BOUNDARY_FLAGS_WORD);
    uint hitPrimitive=gl_PrimitiveID;vec2 hitBary=hitAttributes;float hitDistance=gl_HitTEXT;
    bool connection=payload.state.y==GS_PAYLOAD_OPTICAL_CONNECTION_QUERY;
    if(connection&&boundaryFlags!=0u){
        if(payload.state.z==GS_OPTICAL_BOUNDARY_HIT_REUSE
                &&all(equal(payload.motionRevision,uvec2(gl_InstanceCustomIndexEXT,gl_PrimitiveID)))) {
            hitPrimitive=floatBitsToUint(payload.previousWorldPosition.w);
            hitBary=payload.previousWorldPosition.xy;hitDistance=payload.previousWorldPosition.z;
        } else {uint primitive;vec2 bary;float distance;
            // Opaque traversal and a cache replaced by another any-hit candidate
            // retain the exact original geometric classification and error handling.
            if(gsBoundaryConnectionHit(gsMeshBase(gsInstanceMeshSlot(gl_InstanceCustomIndexEXT)),hitPrimitive,hitBary,
                    gl_RayTminEXT,primitive,bary,distance)){hitPrimitive=primitive;hitBary=bary;hitDistance=distance;}
        }
    }
    GS_DETAIL_END();
    gsEvaluateTriangleHit(payload,gl_InstanceCustomIndexEXT,gl_ObjectToWorldEXT,
        gl_WorldRayOriginEXT,gl_WorldRayDirectionEXT,boundaryFlags,hitPrimitive,hitBary,hitDistance);
}
