#version 460
#extension GL_EXT_ray_tracing : require
struct HitPayload { vec4 hit; uvec4 ids; };
layout(location=0) rayPayloadInEXT HitPayload payload;
void main() { payload.hit=vec4(-1.0,0.0,0.0,0.0); payload.ids=uvec4(0xffffffffu); }
