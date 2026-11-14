#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require

#include "gpuscene_common.glsl"

layout(location = 0) rayPayloadInEXT GpuScenePayload payload;

void main()
{
    payload.state.x = 0u;
    payload.previousWorldPosition = vec4(0.0);
    payload.motionRevision = uvec2(0u);
    payload.surfaceState = uvec4(0u);
    payload.compositeState = vec4(0.0);
    if (payload.state.y == GS_PAYLOAD_RADIANCE_QUERY) {
        payload.baseColorAndOpacity = vec4(gsSkyRadiance(gl_WorldRayDirectionEXT), 1.0);
        payload.emissiveAndMetallic = vec4(0.0);
        payload.transmissionIor = vec4(0.0, 1.0, 0.0, 0.0);
    }
}
