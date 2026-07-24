#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require

#include "gpuscene_common.glsl"

layout(location = 0) rayPayloadInEXT GpuScenePayload payload;

void main()
{
    payload.state.x = 0u;
    if (payload.state.y == 0u) {
        payload.baseColorAndOpacity = vec4(gsSkyRadiance(gl_WorldRayDirectionEXT), 1.0);
        payload.emissiveAndMetallic = vec4(0.0);
        payload.transmissionIor = vec4(0.0, 1.0, 0.0, 0.0);
    }
}
