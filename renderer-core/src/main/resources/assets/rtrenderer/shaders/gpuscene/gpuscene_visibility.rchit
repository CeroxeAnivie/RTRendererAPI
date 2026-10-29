#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require
#include "gpuscene_visibility.glsl"
layout(location=1) rayPayloadInEXT GsVisibilityPayload visibility;
hitAttributeEXT vec2 hitAttributes;
void main() {
    uint instance=gl_InstanceCustomIndexEXT;
    visibility.boundary=uvec4(instance,gsInstanceWord(instance,GPU_SCENE_INSTANCE_MEDIUM_BOUNDARY_PRIORITY_WORD),
        gsInstanceWord(instance,GPU_SCENE_INSTANCE_MEDIUM_BOUNDARY_ORIENTATION_WORD),
        gsInstanceWord(instance,GPU_SCENE_INSTANCE_MEDIUM_BOUNDARY_FLAGS_WORD));
    uint mesh=gsMeshBase(gsInstanceMeshSlot(instance));
    uvec3 indices=gsTriangleIndices(mesh,gl_PrimitiveID);
    vec3 p0=gl_ObjectToWorldEXT*vec4(gsPosition(mesh,indices.x),1);
    vec3 p1=gl_ObjectToWorldEXT*vec4(gsPosition(mesh,indices.y),1);
    vec3 p2=gl_ObjectToWorldEXT*vec4(gsPosition(mesh,indices.z),1);
    vec3 normal=normalize(cross(p1-p0,p2-p0));
    bool back=dot(normal,gl_WorldRayDirectionEXT)>0.0;
    visibility.backFace=(back!=(determinant(mat3(gl_ObjectToWorldEXT))<0.0))?1u:0u;
    visibility.normalDistance=vec4(back?-normal:normal,gl_HitTEXT);
    visibility.tintOpacity=vec4(1.0);visibility.transmissionIor=vec2(1.0,1.0);
    if((visibility.boundary.w&2u)!=0u) return;
    uint material=gsTriangleMaterialSlot(mesh,gl_PrimitiveID)*GPU_SCENE_MATERIAL_RECORD_WORDS;
    uint flags=gsMaterials.words[material+GPU_SCENE_MATERIAL_FLAGS_WORD];
    vec3 bary=gsBarycentrics(hitAttributes);
    uint model=(flags>>GPU_SCENE_SHADING_MODEL_SHIFT)&GPU_SCENE_SHADING_MODEL_MASK;
    vec4 color=gsUnpackRgba8(gsMaterials.words[material+GPU_SCENE_MATERIAL_BASE_COLOR_WORD]);
    vec4 vertex=model==GPU_SCENE_SHADING_LIGHTMAP_MODULATED
        ?gsTriangleLightmapModulatedColor(mesh,indices,bary,instance):gsTriangleColor(mesh,indices,bary);
    color*=vertex;
    uint texture=gsMaterials.words[material+GPU_SCENE_MATERIAL_BASE_COLOR_TEXTURE_WORD];
    if(texture!=GS_INVALID_SLOT) {
        vec2 uv=gsInstanceUv(instance,gsTriangleTexcoord(mesh,indices,bary));
        vec4 footprint=gsTriangleTextureFootprint(mesh,indices,bary,p0,p1,p2,gl_WorldRayOriginEXT,
            visibility.launchInfo,instance);
        color*=gsSampleTextureFootprint(texture,uv,footprint.xy,footprint.zw);
    }
    if(model==GPU_SCENE_SHADING_LIGHTMAP_MODULATED) color=gsApplySurfaceVisibility(color,instance);
    visibility.tintOpacity=clamp(color,vec4(0.0),vec4(1.0));
    if(visibility.boundary.w!=0u) visibility.tintOpacity.a=1.0;
    visibility.transmissionIor=vec2(uintBitsToFloat(gsMaterials.words[material+GPU_SCENE_MATERIAL_TRANSMISSION_WORD]),
        uintBitsToFloat(gsMaterials.words[material+GPU_SCENE_MATERIAL_IOR_WORD]));
    if(visibility.boundary.w==0u) visibility.backFace=back?1u:0u;
}
