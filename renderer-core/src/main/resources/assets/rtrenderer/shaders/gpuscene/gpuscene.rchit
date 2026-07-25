#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require

#include "gpuscene_common.glsl"

layout(location = 0) rayPayloadInEXT GpuScenePayload payload;
hitAttributeEXT vec2 hitAttributes;

void main()
{
    if (payload.state.y == GS_PAYLOAD_SHADOW_QUERY) {
        payload.state.x = 1u;
        return;
    }

    uint meshSlot = gsInstanceMeshSlot(gl_InstanceCustomIndexEXT);
    uint meshBase = gsMeshBase(meshSlot);
    uvec3 indices = gsTriangleIndices(meshBase, gl_PrimitiveID);
    vec3 barycentrics = gsBarycentrics(hitAttributes);

    vec3 p0 = gsPosition(meshBase, indices.x);
    vec3 p1 = gsPosition(meshBase, indices.y);
    vec3 p2 = gsPosition(meshBase, indices.z);
    vec3 wp0 = gl_ObjectToWorldEXT * vec4(p0, 1.0);
    vec3 wp1 = gl_ObjectToWorldEXT * vec4(p1, 1.0);
    vec3 wp2 = gl_ObjectToWorldEXT * vec4(p2, 1.0);
    vec3 camera = gsFrameCameraPosition();
    vec3 cameraRelative0 = wp0 - camera;
    vec3 cameraRelative1 = wp1 - camera;
    vec3 cameraRelative2 = wp2 - camera;
    float sphericalDistance = length(cameraRelative0) * barycentrics.x
        + length(cameraRelative1) * barycentrics.y
        + length(cameraRelative2) * barycentrics.z;
    float cylindricalDistance = max(length(cameraRelative0.xz), abs(cameraRelative0.y)) * barycentrics.x
        + max(length(cameraRelative1.xz), abs(cameraRelative1.y)) * barycentrics.y
        + max(length(cameraRelative2.xz), abs(cameraRelative2.y)) * barycentrics.z;
    vec3 geometricNormal = normalize(cross(wp1 - wp0, wp2 - wp0));
    vec3 worldNormal = geometricNormal;
    mat3 objectToWorld = mat3(gl_ObjectToWorldEXT);
    if (gsOptionalOffsetPresent(meshBase, GPU_SCENE_MESH_NORMAL_OFFSET_WORD)) {
        vec3 objectNormal = normalize(
            gsNormal(meshBase, indices.x) * barycentrics.x
            + gsNormal(meshBase, indices.y) * barycentrics.y
            + gsNormal(meshBase, indices.z) * barycentrics.z
        );
        worldNormal = normalize(transpose(inverse(objectToWorld)) * objectNormal);
    }
    bool backFace = dot(geometricNormal, gl_WorldRayDirectionEXT) > 0.0;
    if (dot(worldNormal, gl_WorldRayDirectionEXT) > 0.0) worldNormal = -worldNormal;

    uint materialSlot = gsTriangleMaterialSlot(meshBase, gl_PrimitiveID);
    uint materialBase = materialSlot * GPU_SCENE_MATERIAL_RECORD_WORDS;
    uint materialFlags = gsMaterials.words[materialBase + GPU_SCENE_MATERIAL_FLAGS_WORD];
    uint shadingModel = (materialFlags >> GPU_SCENE_SHADING_MODEL_SHIFT)
        & GPU_SCENE_SHADING_MODEL_MASK;
    vec2 uv = gsTriangleTexcoord(meshBase, indices, barycentrics);
    vec4 uvFootprint = gsTriangleTextureFootprint(
        meshBase, indices, barycentrics, wp0, wp1, wp2, gl_WorldRayOriginEXT,
        payload.launchInfo
    );
    vec4 vertexColor = shadingModel == GPU_SCENE_SHADING_LIGHTMAP_MODULATED
        ? gsTriangleLightmapModulatedColor(meshBase, indices, barycentrics)
        : gsTriangleColor(meshBase, indices, barycentrics);
    vec4 baseColor = gsUnpackRgba8(gsMaterials.words[
        materialBase + GPU_SCENE_MATERIAL_BASE_COLOR_WORD
    ]) * vertexColor;
    uint baseTexture = gsMaterials.words[materialBase + GPU_SCENE_MATERIAL_BASE_COLOR_TEXTURE_WORD];
    if (baseTexture != GS_INVALID_SLOT) {
        baseColor *= gsSampleTextureFootprint(baseTexture, uv, uvFootprint.xy, uvFootprint.zw);
    }
    if (shadingModel == GPU_SCENE_SHADING_LIGHTMAP_MODULATED) {
        baseColor = gsApplySurfaceVisibility(baseColor, gl_InstanceCustomIndexEXT);
    }

    float roughness = uintBitsToFloat(gsMaterials.words[
        materialBase + GPU_SCENE_MATERIAL_ROUGHNESS_WORD
    ]);
    float metallic = uintBitsToFloat(gsMaterials.words[
        materialBase + GPU_SCENE_MATERIAL_METALLIC_WORD
    ]);
    uint metallicRoughnessTexture = gsMaterials.words[
        materialBase + GPU_SCENE_MATERIAL_METALLIC_ROUGHNESS_TEXTURE_WORD
    ];
    if (metallicRoughnessTexture != GS_INVALID_SLOT) {
        vec4 sampled = gsSampleTextureFootprint(
            metallicRoughnessTexture, uv, uvFootprint.xy, uvFootprint.zw
        );
        roughness *= sampled.g;
        metallic *= sampled.b;
    }

    uint normalTexture = gsMaterials.words[materialBase + GPU_SCENE_MATERIAL_NORMAL_TEXTURE_WORD];
    if (normalTexture != GS_INVALID_SLOT
            && gsOptionalOffsetPresent(meshBase, GPU_SCENE_MESH_TANGENT_OFFSET_WORD)) {
        vec4 objectTangent = gsTangent(meshBase, indices.x) * barycentrics.x
            + gsTangent(meshBase, indices.y) * barycentrics.y
            + gsTangent(meshBase, indices.z) * barycentrics.z;
        vec3 tangent = normalize(objectToWorld * objectTangent.xyz);
        tangent = normalize(tangent - worldNormal * dot(tangent, worldNormal));
        vec3 bitangent = normalize(cross(worldNormal, tangent)) * sign(objectTangent.w);
        vec3 mappedNormal = gsSampleTextureFootprint(
            normalTexture, uv, uvFootprint.xy, uvFootprint.zw
        ).xyz * 2.0 - 1.0;
        worldNormal = normalize(mat3(tangent, bitangent, worldNormal) * mappedNormal);
        if (dot(worldNormal, gl_WorldRayDirectionEXT) > 0.0) worldNormal = -worldNormal;
    }

    vec3 emissive = gsUnpackRgba8(gsMaterials.words[
        materialBase + GPU_SCENE_MATERIAL_EMISSIVE_COLOR_WORD
    ]).rgb * uintBitsToFloat(gsMaterials.words[
        materialBase + GPU_SCENE_MATERIAL_EMISSIVE_STRENGTH_WORD
    ]);
    uint emissiveTexture = gsMaterials.words[materialBase + GPU_SCENE_MATERIAL_EMISSIVE_TEXTURE_WORD];
    if (emissiveTexture != GS_INVALID_SLOT) {
        emissive *= gsSampleTextureFootprint(
            emissiveTexture, uv, uvFootprint.xy, uvFootprint.zw
        ).rgb;
    }

    float transmission = uintBitsToFloat(gsMaterials.words[
        materialBase + GPU_SCENE_MATERIAL_TRANSMISSION_WORD
    ]);
    float ior = uintBitsToFloat(gsMaterials.words[materialBase + GPU_SCENE_MATERIAL_IOR_WORD]);
    payload.worldPositionAndDistance = vec4(
        gl_WorldRayOriginEXT + gl_WorldRayDirectionEXT * gl_HitTEXT,
        gl_HitTEXT
    );
    payload.worldNormalAndRoughness = vec4(worldNormal, roughness);
    payload.baseColorAndOpacity = vec4(baseColor.rgb, baseColor.a);
    payload.emissiveAndMetallic = vec4(emissive, metallic);
    payload.transmissionIor = vec4(transmission, ior, sphericalDistance, cylindricalDistance);
    payload.state = uvec4(
        1u,
        backFace ? GS_PAYLOAD_BACK_FACE : GS_PAYLOAD_RADIANCE_QUERY,
        materialFlags,
        gsInstanceFlags(gl_InstanceCustomIndexEXT)
    );
}
