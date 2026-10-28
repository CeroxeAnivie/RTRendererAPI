#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require
#include "gpuscene_visibility.glsl"
layout(location=1) rayPayloadInEXT GsVisibilityPayload visibility;
void main() { visibility.normalDistance.w=-1.0; }
