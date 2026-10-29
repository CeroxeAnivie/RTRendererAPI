#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require

#include "gpuscene_common.glsl"

layout(location = 0) rayPayloadInEXT GpuScenePayload payload;
hitAttributeEXT vec2 hitAttributes;

void main()
{
    GS_DETAIL_BEGIN(24u);
    if (payload.state.y == GS_PAYLOAD_SHADOW_QUERY) {
        payload.state.x = 1u;
        GS_DETAIL_END();return;
    }

    uint boundaryFlags=gsInstanceWord(gl_InstanceCustomIndexEXT,GPU_SCENE_INSTANCE_MEDIUM_BOUNDARY_FLAGS_WORD);
    if((boundaryFlags&2u)!=0u) {
        uint mesh=gsMeshBase(gsInstanceMeshSlot(gl_InstanceCustomIndexEXT));
        uvec3 indices=gsTriangleIndices(mesh,gl_PrimitiveID);
        vec3 a=gsPosition(mesh,indices.x),b=gsPosition(mesh,indices.y),c=gsPosition(mesh,indices.z);
        vec3 p0=gl_ObjectToWorldEXT*vec4(a,1),p1=gl_ObjectToWorldEXT*vec4(b,1),p2=gl_ObjectToWorldEXT*vec4(c,1);
        vec3 normal=normalize(cross(p1-p0,p2-p0));
        bool back=dot(normal,gl_WorldRayDirectionEXT)>0.0;
        bool boundaryBack=back!=(determinant(mat3(gl_ObjectToWorldEXT))<0.0);
        payload.worldPositionAndDistance=vec4(gl_WorldRayOriginEXT+gl_WorldRayDirectionEXT*gl_HitTEXT,gl_HitTEXT);
        vec3 bary=gsBarycentrics(hitAttributes);
        payload.previousWorldPosition=vec4(gsPreviousInstancePoint(gl_InstanceCustomIndexEXT,a*bary.x+b*bary.y+c*bary.z),1);
        payload.motionRevision=gsInstanceMotionRevision(gl_InstanceCustomIndexEXT);
        payload.worldNormalAndRoughness=vec4(back?-normal:normal,0);
        payload.baseColorAndOpacity=vec4(1);payload.emissiveAndMetallic=vec4(0);
        payload.transmissionIor=vec4(1,1,0,0);
        payload.state=uvec4(1u,boundaryBack?GS_PAYLOAD_BACK_FACE:GS_PAYLOAD_RADIANCE_QUERY,0u,gsInstanceFlags(gl_InstanceCustomIndexEXT));
        payload.surfaceState=uvec4(0);payload.compositeState=vec4(0);
        payload.mediumBoundary=uvec4(gl_InstanceCustomIndexEXT,
            gsInstanceWord(gl_InstanceCustomIndexEXT,GPU_SCENE_INSTANCE_MEDIUM_BOUNDARY_PRIORITY_WORD),
            gsInstanceWord(gl_InstanceCustomIndexEXT,GPU_SCENE_INSTANCE_MEDIUM_BOUNDARY_ORIENTATION_WORD),boundaryFlags);
        GS_DETAIL_END();return;
    }
    GS_DETAIL_NEXT(25u);
    uint meshSlot = gsInstanceMeshSlot(gl_InstanceCustomIndexEXT);
    uint meshBase = gsMeshBase(meshSlot);
    uvec3 indices = gsTriangleIndices(meshBase, gl_PrimitiveID);
    vec3 barycentrics = gsBarycentrics(hitAttributes);

    GS_DETAIL_NEXT(26u);
    vec3 p0 = gsPosition(meshBase, indices.x);
    vec3 p1 = gsPosition(meshBase, indices.y);
    vec3 p2 = gsPosition(meshBase, indices.z);
    vec3 objectGeometricNormal = normalize(cross(p1 - p0, p2 - p0));
    vec3 localPosition = p0 * barycentrics.x + p1 * barycentrics.y + p2 * barycentrics.z;
    GS_DETAIL_NEXT(27u);
    vec3 wp0 = gl_ObjectToWorldEXT * vec4(p0, 1.0);
    vec3 wp1 = gl_ObjectToWorldEXT * vec4(p1, 1.0);
    vec3 wp2 = gl_ObjectToWorldEXT * vec4(p2, 1.0);
    GS_DETAIL_NEXT(28u);
    vec3 camera = gsFrameCameraPosition();
    vec3 cameraRelative0 = wp0 - camera;
    vec3 cameraRelative1 = wp1 - camera;
    vec3 cameraRelative2 = wp2 - camera;
    float sphericalDistance = length(cameraRelative0) * barycentrics.x
        + length(cameraRelative1) * barycentrics.y
        + length(cameraRelative2) * barycentrics.z;
    GS_DETAIL_NEXT(29u);
    float cylindricalDistance = max(length(cameraRelative0.xz), abs(cameraRelative0.y)) * barycentrics.x
        + max(length(cameraRelative1.xz), abs(cameraRelative1.y)) * barycentrics.y
        + max(length(cameraRelative2.xz), abs(cameraRelative2.y)) * barycentrics.z;
    GS_DETAIL_NEXT(30u);
    vec3 geometricNormal = normalize(cross(wp1 - wp0, wp2 - wp0));
    vec3 objectShadingNormal = objectGeometricNormal;
    vec3 worldShadingNormal = geometricNormal;
    mat3 objectToWorld = mat3(gl_ObjectToWorldEXT);
    GS_DETAIL_NEXT(31u);
    if (gsOptionalOffsetPresent(meshBase, GPU_SCENE_MESH_NORMAL_OFFSET_WORD)) {
        objectShadingNormal = normalize(
            gsNormal(meshBase, indices.x) * barycentrics.x
            + gsNormal(meshBase, indices.y) * barycentrics.y
            + gsNormal(meshBase, indices.z) * barycentrics.z
        );
        worldShadingNormal = normalize(transpose(inverse(objectToWorld)) * objectShadingNormal);
    }
    GS_DETAIL_NEXT(32u);
    bool backFace = dot(geometricNormal, gl_WorldRayDirectionEXT) > 0.0;
    vec3 worldNormal = worldShadingNormal;
    if (dot(worldNormal, gl_WorldRayDirectionEXT) > 0.0) worldNormal = -worldNormal;

    GS_DETAIL_NEXT(33u);
    uint materialSlot = gsTriangleMaterialSlot(meshBase, gl_PrimitiveID);
    uint materialBase = materialSlot * GPU_SCENE_MATERIAL_RECORD_WORDS;
    uint materialFlags = gsMaterials.words[materialBase + GPU_SCENE_MATERIAL_FLAGS_WORD];
    uint shadingModel = (materialFlags >> GPU_SCENE_SHADING_MODEL_SHIFT)
        & GPU_SCENE_SHADING_MODEL_MASK;
    GS_DETAIL_NEXT(34u);
    vec2 uv = gsInstanceUv(
        gl_InstanceCustomIndexEXT,
        gsTriangleTexcoord(meshBase, indices, barycentrics)
    );
    GS_DETAIL_NEXT(35u);
    vec4 uvFootprint = payload.state.y == GS_PAYLOAD_PHOTON_QUERY ? vec4(0.0) : gsTriangleTextureFootprint(
        meshBase, indices, barycentrics, wp0, wp1, wp2, gl_WorldRayOriginEXT,
        payload.launchInfo,
        gl_InstanceCustomIndexEXT
    );
    GS_DETAIL_NEXT(36u);
    vec4 vertexColor = shadingModel == GPU_SCENE_SHADING_LIGHTMAP_MODULATED
        ? gsTriangleLightmapModulatedColor(
            meshBase, indices, barycentrics, gl_InstanceCustomIndexEXT
        )
        : gsTriangleColor(meshBase, indices, barycentrics);
    GS_DETAIL_NEXT(37u);
    vec4 baseColor = gsUnpackRgba8(gsMaterials.words[
        materialBase + GPU_SCENE_MATERIAL_BASE_COLOR_WORD
    ]) * vertexColor;
    GS_DETAIL_NEXT(38u);
    uint baseTexture = gsMaterials.words[materialBase + GPU_SCENE_MATERIAL_BASE_COLOR_TEXTURE_WORD];
    if (baseTexture != GS_INVALID_SLOT) {
        baseColor *= gsSampleTextureFootprint(baseTexture, uv, uvFootprint.xy, uvFootprint.zw);
    }
    GS_DETAIL_NEXT(39u);
    baseColor.rgb *= gsInstanceCardinalLighting(
        gl_InstanceCustomIndexEXT, objectGeometricNormal, geometricNormal
    );
    baseColor.rgb *= gsInstanceDirectionalDiffuse(
        gl_InstanceCustomIndexEXT, objectShadingNormal, worldShadingNormal, backFace
    );
    if (shadingModel == GPU_SCENE_SHADING_LIGHTMAP_MODULATED) {
        baseColor = gsApplySurfaceVisibility(baseColor, gl_InstanceCustomIndexEXT);
    }

    GS_DETAIL_NEXT(40u);
    float roughness = uintBitsToFloat(gsMaterials.words[
        materialBase + GPU_SCENE_MATERIAL_ROUGHNESS_WORD
    ]);
    float metallic = uintBitsToFloat(gsMaterials.words[
        materialBase + GPU_SCENE_MATERIAL_METALLIC_WORD
    ]);
    uint metallicRoughnessTexture = gsMaterials.words[materialBase + GPU_SCENE_MATERIAL_METALLIC_ROUGHNESS_TEXTURE_WORD];
    if (metallicRoughnessTexture != GS_INVALID_SLOT) {
        vec4 sampled = gsSampleTextureFootprint(
            metallicRoughnessTexture, uv, uvFootprint.xy, uvFootprint.zw
        );
        roughness *= sampled.g;
        metallic *= sampled.b;
    }

    GS_DETAIL_NEXT(41u);
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

    GS_DETAIL_NEXT(42u);
    vec3 emissive = gsUnpackRgba8(gsMaterials.words[
        materialBase + GPU_SCENE_MATERIAL_EMISSIVE_COLOR_WORD
    ]).rgb * uintBitsToFloat(gsMaterials.words[
        materialBase + GPU_SCENE_MATERIAL_EMISSIVE_STRENGTH_WORD
    ]);
    GS_DETAIL_NEXT(43u);
    uint emissiveTexture = gsMaterials.words[materialBase + GPU_SCENE_MATERIAL_EMISSIVE_TEXTURE_WORD];
    if (emissiveTexture != GS_INVALID_SLOT) {
        emissive *= gsSampleTextureFootprint(
            emissiveTexture, uv, uvFootprint.xy, uvFootprint.zw
        ).rgb;
    }

    GS_DETAIL_NEXT(44u);
    float transmission = uintBitsToFloat(gsMaterials.words[
        materialBase + GPU_SCENE_MATERIAL_TRANSMISSION_WORD
    ]);
    float ior = uintBitsToFloat(gsMaterials.words[materialBase + GPU_SCENE_MATERIAL_IOR_WORD]);
    GS_DETAIL_NEXT(45u);
    payload.worldPositionAndDistance = vec4(
        gl_WorldRayOriginEXT + gl_WorldRayDirectionEXT * gl_HitTEXT,
        gl_HitTEXT
    );
    // Reproject the same geometric point through the last successfully rendered object transform.
    // Using a prior scene transaction here would advance temporal state ahead of the GPU frame.
    payload.previousWorldPosition = vec4(
        gsPreviousInstancePoint(gl_InstanceCustomIndexEXT, localPosition),
        1.0
    );
    payload.motionRevision = gsInstanceMotionRevision(gl_InstanceCustomIndexEXT);
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
    GS_DETAIL_NEXT(46u);
    payload.surfaceState = uvec4(
        gsInstanceSurfaceMask(gl_InstanceCustomIndexEXT),
        gsInstanceOverlayReceiverMask(gl_InstanceCustomIndexEXT),
        gsInstanceObjectMask(gl_InstanceCustomIndexEXT),
        gsInstanceWord(gl_InstanceCustomIndexEXT, GPU_SCENE_INSTANCE_OUTLINE_COLOR_WORD)
    );
    payload.compositeState = vec4(
        gsInstanceOutlineWidth(gl_InstanceCustomIndexEXT),
        uintBitsToFloat(gsMaterials.words[
            materialBase + GPU_SCENE_MATERIAL_OVERLAY_DEPTH_THRESHOLD_WORD
        ]),
        float((materialFlags >> GPU_SCENE_OVERLAY_DEPTH_MODE_SHIFT)
            & GPU_SCENE_OVERLAY_DEPTH_MODE_MASK),
        0.0
    );
    payload.mediumBoundary = uvec4(
        gl_InstanceCustomIndexEXT,
        gsInstanceWord(gl_InstanceCustomIndexEXT, GPU_SCENE_INSTANCE_MEDIUM_BOUNDARY_PRIORITY_WORD),
        gsInstanceWord(gl_InstanceCustomIndexEXT, GPU_SCENE_INSTANCE_MEDIUM_BOUNDARY_ORIENTATION_WORD),
        gsInstanceWord(gl_InstanceCustomIndexEXT, GPU_SCENE_INSTANCE_MEDIUM_BOUNDARY_FLAGS_WORD)
    );
    GS_DETAIL_NEXT(47u);
    // Only caustic frames consume the spare geometric-normal lane.
    if (gsFrame.words[GPU_SCENE_FRAME_CAUSTIC_PHOTONS_WORD] != 0u)
        payload.compositeState.w = gsEncodeGeometricNormal(backFace ? -geometricNormal : geometricNormal);
    if (payload.mediumBoundary.w != 0u) {
        // Volume interfaces must use the geometric boundary normal, never a normal-map perturbation.
        payload.worldNormalAndRoughness.xyz = backFace ? -geometricNormal : geometricNormal;
        bool boundaryBackFace=backFace!=(determinant(objectToWorld)<0.0);
        payload.state.y=boundaryBackFace?GS_PAYLOAD_BACK_FACE:GS_PAYLOAD_RADIANCE_QUERY;
        if ((payload.mediumBoundary.w & 2u) != 0u) {
            payload.transmissionIor.x = 1.0;
            payload.baseColorAndOpacity = vec4(1.0);
            payload.worldNormalAndRoughness.w = 0.0;
        }
    }
    GS_DETAIL_END();
}
