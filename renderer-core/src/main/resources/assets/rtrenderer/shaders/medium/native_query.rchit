#version 460
#extension GL_EXT_ray_tracing : require
struct HitPayload { vec4 hit; uvec4 ids; };
layout(location=0) rayPayloadInEXT HitPayload payload;
hitAttributeEXT vec2 bary;
void main() {
    payload.hit=vec4(gl_HitTEXT,bary,gl_HitKindEXT==gl_HitKindFrontFacingTriangleEXT?1.0:0.0);
    payload.ids=uvec4(gl_PrimitiveID,gl_InstanceCustomIndexEXT,gl_GeometryIndexEXT,0u);
}
