// One material/normal producer for closest-hit and collected connection events.
// Keep expression ordering identical across the two execution paths.
#ifndef GS_HIT_EVALUATION_GLSL
#define GS_HIT_EVALUATION_GLSL
// Reconstruct on the primitive, rather than origin + t * direction. At a shared
// edge the latter can round outside an adjacent face and skip its volume exit.
// NVIDIA's robust spawn construction adds the base vertex and translation last.
vec3 gsBoundaryHitPoint(mat4x3 hitObjectToWorld,vec3 a,vec3 b,vec3 c,vec2 bary) {
    // A hit on a shared edge must belong to one face. Normal-only spawning at
    // the exact edge can put a reflected ray outside the neighbouring face
    // without tracing its exit. Resolve that float-precision ambiguity inside
    // the reported triangle; retain the original face normal for Fresnel/Snell.
    // Only the uncertainty band at an edge moves, by at most a few float ULPs
    // of the triangle extent. Interior hits keep their original barycentrics.
    const float edgeBand=8.0*1.192092896e-7;
    vec3 weights=vec3(1.0-bary.x-bary.y,bary);
    if(min(weights.x,min(weights.y,weights.z))<edgeBand) {
        weights=max(weights,vec3(edgeBand));
        weights/=weights.x+weights.y+weights.z;
        bary=weights.yz;
    }
    precise vec3 edge1=b-a,edge2=c-a;
    precise vec3 local=a+fma(vec3(bary.x),edge1,bary.y*edge2);
    precise vec3 world;
    world.x=hitObjectToWorld[3].x+fma(hitObjectToWorld[0].x,local.x,
        fma(hitObjectToWorld[1].x,local.y,hitObjectToWorld[2].x*local.z));
    world.y=hitObjectToWorld[3].y+fma(hitObjectToWorld[0].y,local.x,
        fma(hitObjectToWorld[1].y,local.y,hitObjectToWorld[2].y*local.z));
    world.z=hitObjectToWorld[3].z+fma(hitObjectToWorld[0].z,local.x,
        fma(hitObjectToWorld[1].z,local.y,hitObjectToWorld[2].z*local.z));
    return world;
}

// The collected path only evaluates invisible medium interfaces in its callable.
// Ordinary surfaces retain the original closest-hit producer, including interpolation.
void gsEvaluateInvisibleBoundaryHit(inout GpuScenePayload payload,uint instanceIndex,mat4x3 hitObjectToWorld,
        vec3 rayDirection,uint boundaryFlags,uint hitPrimitive,vec2 hitBary,float hitDistance) {
        uint mesh=gsMeshBase(gsInstanceMeshSlot(instanceIndex));
        uvec3 indices=gsTriangleIndices(mesh,hitPrimitive);
        vec3 a=gsPosition(mesh,indices.x),b=gsPosition(mesh,indices.y),c=gsPosition(mesh,indices.z);
        vec3 p0=hitObjectToWorld*vec4(a,1),p1=hitObjectToWorld*vec4(b,1),p2=hitObjectToWorld*vec4(c,1);
        vec3 normal=normalize(cross(p1-p0,p2-p0));
        bool back=dot(normal,rayDirection)>0.0;
        bool boundaryBack=back!=(determinant(mat3(hitObjectToWorld))<0.0);
        payload.worldPositionAndDistance=vec4(gsBoundaryHitPoint(hitObjectToWorld,a,b,c,hitBary),hitDistance);
        vec3 bary=gsBarycentrics(hitBary);
        payload.previousWorldPosition=vec4(gsPreviousInstancePoint(instanceIndex,a*bary.x+b*bary.y+c*bary.z),1);
        payload.motionRevision=gsInstanceMotionRevision(instanceIndex);
        payload.worldNormalAndRoughness=vec4(back?-normal:normal,0);
        payload.baseColorAndOpacity=vec4(1);payload.emissiveAndMetallic=vec4(0);
        payload.transmissionIor=vec4(1,1,0,0);
        payload.state=uvec4(1u,boundaryBack?GS_PAYLOAD_BACK_FACE:GS_PAYLOAD_RADIANCE_QUERY,0u,gsInstanceFlags(instanceIndex));
        payload.surfaceState=uvec4(0);payload.compositeState=vec4(0);
        payload.mediumBoundary=uvec4(instanceIndex,
            gsInstanceWord(instanceIndex,GPU_SCENE_INSTANCE_MEDIUM_BOUNDARY_PRIORITY_WORD),
            gsInstanceWord(instanceIndex,GPU_SCENE_INSTANCE_MEDIUM_BOUNDARY_ORIENTATION_WORD),boundaryFlags);
}
void gsEvaluateTriangleHit(inout GpuScenePayload payload,uint instanceIndex,mat4x3 hitObjectToWorld,
        vec3 rayOrigin,vec3 rayDirection,uint boundaryFlags,uint hitPrimitive,vec2 hitBary,float hitDistance) {
    GS_DETAIL_BEGIN(24u);
    if((boundaryFlags&2u)!=0u) {
        gsEvaluateInvisibleBoundaryHit(payload,instanceIndex,hitObjectToWorld,rayDirection,boundaryFlags,
                hitPrimitive,hitBary,hitDistance);
        GS_DETAIL_END();return;
    }
    GS_DETAIL_NEXT(25u);
    uint meshSlot = gsInstanceMeshSlot(instanceIndex);
    uint meshBase = gsMeshBase(meshSlot);
    uvec3 indices = gsTriangleIndices(meshBase, hitPrimitive);
    vec3 barycentrics = gsBarycentrics(hitBary);

    GS_DETAIL_NEXT(26u);
    vec3 p0 = gsPosition(meshBase, indices.x);
    vec3 p1 = gsPosition(meshBase, indices.y);
    vec3 p2 = gsPosition(meshBase, indices.z);
    vec3 objectGeometricNormal = normalize(cross(p1 - p0, p2 - p0));
    vec3 localPosition = p0 * barycentrics.x + p1 * barycentrics.y + p2 * barycentrics.z;
    GS_DETAIL_NEXT(27u);
    vec3 wp0 = hitObjectToWorld * vec4(p0, 1.0);
    vec3 wp1 = hitObjectToWorld * vec4(p1, 1.0);
    vec3 wp2 = hitObjectToWorld * vec4(p2, 1.0);
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
    mat3 objectToWorld = mat3(hitObjectToWorld);
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
    bool backFace = dot(geometricNormal, rayDirection) > 0.0;
    vec3 worldNormal = worldShadingNormal;
    if (dot(worldNormal, rayDirection) > 0.0) worldNormal = -worldNormal;

    GS_DETAIL_NEXT(33u);
    uint materialSlot = gsTriangleMaterialSlot(meshBase, hitPrimitive);
    uint materialBase = materialSlot * GPU_SCENE_MATERIAL_RECORD_WORDS;
    uint materialFlags = gsMaterials.words[materialBase + GPU_SCENE_MATERIAL_FLAGS_WORD];
    uint shadingModel = (materialFlags >> GPU_SCENE_SHADING_MODEL_SHIFT)
        & GPU_SCENE_SHADING_MODEL_MASK;
    GS_DETAIL_NEXT(34u);
    vec2 uv = gsInstanceUv(
        instanceIndex,
        gsTriangleTexcoord(meshBase, indices, barycentrics)
    );
    GS_DETAIL_NEXT(35u);
    vec4 uvFootprint = payload.state.y == GS_PAYLOAD_PHOTON_QUERY ? vec4(0.0) : gsTriangleTextureFootprint(
        meshBase, indices, barycentrics, wp0, wp1, wp2, rayOrigin,
        payload.launchInfo,
        instanceIndex
    );
    GS_DETAIL_NEXT(36u);
    vec4 vertexColor = shadingModel == GPU_SCENE_SHADING_LIGHTMAP_MODULATED
        ? gsTriangleLightmapModulatedColor(
            meshBase, indices, barycentrics, instanceIndex
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
        instanceIndex, objectGeometricNormal, geometricNormal
    );
    baseColor.rgb *= gsInstanceDirectionalDiffuse(
        instanceIndex, objectShadingNormal, worldShadingNormal, backFace
    );
    if (shadingModel == GPU_SCENE_SHADING_LIGHTMAP_MODULATED) {
        baseColor = gsApplySurfaceVisibility(baseColor, instanceIndex);
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
        if (dot(worldNormal, rayDirection) > 0.0) worldNormal = -worldNormal;
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
        boundaryFlags!=0u?gsBoundaryHitPoint(hitObjectToWorld,p0,p1,p2,hitBary)
            :rayOrigin + rayDirection * hitDistance,
        hitDistance
    );
    // Reproject the same geometric point through the last successfully rendered object transform.
    // Using a prior scene transaction here would advance temporal state ahead of the GPU frame.
    payload.previousWorldPosition = vec4(
        gsPreviousInstancePoint(instanceIndex, localPosition),
        1.0
    );
    payload.motionRevision = gsInstanceMotionRevision(instanceIndex);
    payload.worldNormalAndRoughness = vec4(worldNormal, roughness);
    payload.baseColorAndOpacity = vec4(baseColor.rgb, baseColor.a);
    payload.emissiveAndMetallic = vec4(emissive, metallic);
    payload.transmissionIor = vec4(transmission, ior, sphericalDistance, cylindricalDistance);
    payload.state = uvec4(
        1u,
        backFace ? GS_PAYLOAD_BACK_FACE : GS_PAYLOAD_RADIANCE_QUERY,
        materialFlags,
        gsInstanceFlags(instanceIndex)
    );
    GS_DETAIL_NEXT(46u);
    payload.surfaceState = uvec4(
        gsInstanceSurfaceMask(instanceIndex),
        gsInstanceOverlayReceiverMask(instanceIndex),
        gsInstanceObjectMask(instanceIndex),
        gsInstanceWord(instanceIndex, GPU_SCENE_INSTANCE_OUTLINE_COLOR_WORD)
    );
    payload.compositeState = vec4(
        gsInstanceOutlineWidth(instanceIndex),
        uintBitsToFloat(gsMaterials.words[
            materialBase + GPU_SCENE_MATERIAL_OVERLAY_DEPTH_THRESHOLD_WORD
        ]),
        float((materialFlags >> GPU_SCENE_OVERLAY_DEPTH_MODE_SHIFT)
            & GPU_SCENE_OVERLAY_DEPTH_MODE_MASK),
        0.0
    );
    payload.mediumBoundary = uvec4(
        instanceIndex,
        gsInstanceWord(instanceIndex, GPU_SCENE_INSTANCE_MEDIUM_BOUNDARY_PRIORITY_WORD),
        gsInstanceWord(instanceIndex, GPU_SCENE_INSTANCE_MEDIUM_BOUNDARY_ORIENTATION_WORD),
        gsInstanceWord(instanceIndex, GPU_SCENE_INSTANCE_MEDIUM_BOUNDARY_FLAGS_WORD)
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
#endif
