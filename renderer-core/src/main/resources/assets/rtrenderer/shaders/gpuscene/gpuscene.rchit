#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require
#ifdef RTRENDERER_CAMERA_SCATTERING_PROFILE
#extension GL_EXT_shader_realtime_clock : require
#endif

#include "gpuscene_common.glsl"
#include "gpuscene_boundary_intersection.glsl"

layout(location = 0) rayPayloadInEXT GpuScenePayload payload;
hitAttributeEXT vec2 hitAttributes;

// Reconstruct on the primitive, rather than origin + t * direction. At a shared
// edge the latter can round outside an adjacent face and skip its volume exit.
// NVIDIA's robust spawn construction adds the base vertex and translation last.
vec3 gsBoundaryHitPoint(vec3 a,vec3 b,vec3 c,vec2 bary) {
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
    world.x=gl_ObjectToWorldEXT[3].x+fma(gl_ObjectToWorldEXT[0].x,local.x,
        fma(gl_ObjectToWorldEXT[1].x,local.y,gl_ObjectToWorldEXT[2].x*local.z));
    world.y=gl_ObjectToWorldEXT[3].y+fma(gl_ObjectToWorldEXT[0].y,local.x,
        fma(gl_ObjectToWorldEXT[1].y,local.y,gl_ObjectToWorldEXT[2].y*local.z));
    world.z=gl_ObjectToWorldEXT[3].z+fma(gl_ObjectToWorldEXT[0].z,local.x,
        fma(gl_ObjectToWorldEXT[1].z,local.y,gl_ObjectToWorldEXT[2].z*local.z));
    return world;
}

void main()
{
#ifdef RTRENDERER_CAMERA_SCATTERING_PROFILE
    bool profileShadow=payload.state.y==GS_PAYLOAD_SHADOW_QUERY&&payload.state.z!=0u;
    uint64_t shadowStarted=profileShadow?clockRealtimeEXT():0ul;
#endif
    GS_DETAIL_BEGIN(24u);
    if (payload.state.y == GS_PAYLOAD_SHADOW_QUERY) {
        payload.state.x = 1u;
#ifdef RTRENDERER_CAMERA_SCATTERING_PROFILE
        if(profileShadow) {
            uint64_t elapsed=clockRealtimeEXT()-shadowStarted;
            payload.state.z=uint(elapsed);
            payload.state.w=uint(elapsed>>32u);
        }
#endif
        GS_DETAIL_END();return;
    }

    uint boundaryFlags=gsInstanceWord(gl_InstanceCustomIndexEXT,GPU_SCENE_INSTANCE_MEDIUM_BOUNDARY_FLAGS_WORD);
    uint hitPrimitive=gl_PrimitiveID;vec2 hitBary=hitAttributes;float hitDistance=gl_HitTEXT;
    bool connection=payload.state.y==GS_PAYLOAD_OPTICAL_CONNECTION_QUERY;
    if(connection&&boundaryFlags!=0u){
        bool cached=payload.compositeState.w==1.0&&payload.surfaceState.w==GS_OPTICAL_BOUNDARY_CACHE_TAG
                &&payload.surfaceState.x==gl_InstanceCustomIndexEXT&&payload.surfaceState.y==gl_PrimitiveID
                &&payload.surfaceState.z==floatBitsToUint(gl_HitTEXT)&&payload.motionRevision.y==uint(gl_InstanceID)
                &&all(equal(floatBitsToUint(payload.compositeState.xy),floatBitsToUint(hitAttributes)));
        if(cached){hitPrimitive=payload.motionRevision.x;hitBary=payload.previousWorldPosition.xy;hitDistance=payload.previousWorldPosition.z;}
        else {uint primitive;vec2 bary;float distance;
            if(gsBoundaryConnectionHit(gsMeshBase(gsInstanceMeshSlot(gl_InstanceCustomIndexEXT)),hitPrimitive,hitBary,
                    gl_RayTminEXT,primitive,bary,distance)){hitPrimitive=primitive;hitBary=bary;hitDistance=distance;}}
    }
    if((boundaryFlags&2u)!=0u) {
        uint mesh=gsMeshBase(gsInstanceMeshSlot(gl_InstanceCustomIndexEXT));
        uvec3 indices=gsTriangleIndices(mesh,hitPrimitive);
        vec3 a=gsPosition(mesh,indices.x),b=gsPosition(mesh,indices.y),c=gsPosition(mesh,indices.z);
        vec3 p0=gl_ObjectToWorldEXT*vec4(a,1),p1=gl_ObjectToWorldEXT*vec4(b,1),p2=gl_ObjectToWorldEXT*vec4(c,1);
        vec3 normal=normalize(cross(p1-p0,p2-p0));
        bool back=dot(normal,gl_WorldRayDirectionEXT)>0.0;
        bool boundaryBack=back!=(determinant(mat3(gl_ObjectToWorldEXT))<0.0);
        payload.worldPositionAndDistance=vec4(gsBoundaryHitPoint(a,b,c,hitBary),hitDistance);
        vec3 bary=gsBarycentrics(hitBary);
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
    uvec3 indices = gsTriangleIndices(meshBase, hitPrimitive);
    vec3 barycentrics = gsBarycentrics(hitBary);

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
    uint materialSlot = gsTriangleMaterialSlot(meshBase, hitPrimitive);
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
        boundaryFlags!=0u?gsBoundaryHitPoint(p0,p1,p2,hitBary)
            :gl_WorldRayOriginEXT + gl_WorldRayDirectionEXT * hitDistance,
        hitDistance
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
