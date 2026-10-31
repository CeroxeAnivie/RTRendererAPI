#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require
#include "gpuscene_common.glsl"
layout(location=2) rayPayloadInEXT GsOpticalConnectionPayload payload;
void main(){GS_DETAIL_BEGIN(152u);payload.state.x=0u;payload.mediumBoundary=uvec4(0u);payload.overlayMode=0;GS_DETAIL_END();}
