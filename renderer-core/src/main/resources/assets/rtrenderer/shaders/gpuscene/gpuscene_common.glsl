#ifndef GS_GPU_SCENE_COMMON_GLSL
#define GS_GPU_SCENE_COMMON_GLSL

#include "gpuscene_abi.glsl"

const uint GS_INVALID_SLOT = 0xffffffffu;
const uint GS_PAYLOAD_RADIANCE_QUERY = 0u;
const uint GS_PAYLOAD_SHADOW_QUERY = 1u;
const uint GS_PAYLOAD_BACK_FACE = 2u;
const float GS_PI = 3.14159265358979323846;

layout(set = 0, binding = GPU_SCENE_BINDING_FRAME_UNIFORMS, std430)
readonly buffer GsFrameUniformWords { uint words[]; } gsFrame;
layout(set = 0, binding = GPU_SCENE_BINDING_TEXTURE_RECORDS, std430)
readonly buffer GsTextureRecordWords { uint words[]; } gsTextures;
layout(set = 0, binding = GPU_SCENE_BINDING_TEXTURE_PIXELS, std430)
readonly buffer GsTexturePixelWords { uint words[]; } gsTexturePixels;
layout(set = 0, binding = GPU_SCENE_BINDING_MATERIAL_RECORDS, std430)
readonly buffer GsMaterialRecordWords { uint words[]; } gsMaterials;
layout(set = 0, binding = GPU_SCENE_BINDING_MESH_RECORDS, std430)
readonly buffer GsMeshRecordWords { uint words[]; } gsMeshes;
layout(set = 0, binding = GPU_SCENE_BINDING_POSITIONS, std430)
readonly buffer GsPositionWords { uint words[]; } gsPositions;
layout(set = 0, binding = GPU_SCENE_BINDING_NORMALS, std430)
readonly buffer GsNormalWords { uint words[]; } gsNormals;
layout(set = 0, binding = GPU_SCENE_BINDING_TANGENTS, std430)
readonly buffer GsTangentWords { uint words[]; } gsTangents;
layout(set = 0, binding = GPU_SCENE_BINDING_TEXCOORDS, std430)
readonly buffer GsTexcoordWords { uint words[]; } gsTexcoords;
layout(set = 0, binding = GPU_SCENE_BINDING_COLORS, std430)
readonly buffer GsColorWords { uint words[]; } gsColors;
layout(set = 0, binding = GPU_SCENE_BINDING_LIGHTMAP_COORDINATES, std430)
readonly buffer GsLightmapCoordinateWords { uint words[]; } gsLightmapCoordinates;
layout(set = 0, binding = GPU_SCENE_BINDING_INDICES, std430)
readonly buffer GsIndexWords { uint words[]; } gsIndices;
layout(set = 0, binding = GPU_SCENE_BINDING_TRIANGLE_MATERIAL_SLOTS, std430)
readonly buffer GsTriangleMaterialWords { uint words[]; } gsTriangleMaterials;
layout(set = 0, binding = GPU_SCENE_BINDING_INSTANCE_RECORDS, std430)
readonly buffer GsInstanceRecordWords { uint words[]; } gsInstances;
layout(set = 0, binding = GPU_SCENE_BINDING_TRANSIENT_INSTANCE_RECORDS, std430)
readonly buffer GsTransientInstanceRecordWords { uint words[]; } gsTransientInstances;
layout(set = 0, binding = GPU_SCENE_BINDING_LIGHT_RECORDS, std430)
readonly buffer GsLightRecordWords { uint words[]; } gsLights;

struct GpuScenePayload {
    vec4 worldPositionAndDistance;
    // Previous-frame object-space transform result. w=1 only for a real surface hit.
    vec4 previousWorldPosition;
    // Scene revision that installed the current/previous transform pair for this instance.
    uvec2 motionRevision;
    vec4 worldNormalAndRoughness;
    vec4 baseColorAndOpacity;
    vec4 emissiveAndMetallic;
    vec4 transmissionIor;
    uvec4 state;
    // Receiver/object masks and outline color are instance state; compositeState carries outline
    // width, overlay depth threshold, and overlay mode without consuming material payload lanes.
    uvec4 surfaceState;
    vec4 compositeState;
    // Launch pixel and extent survive closest/any-hit traversal so the hit shader can reconstruct
    // the rasterizer's screen-space UV footprint without relying on derivative instructions.
    uvec4 launchInfo;
};

uint64_t gsU64(uint lowWord, uint highWord)
{
    return uint64_t(lowWord) | (uint64_t(highWord) << uint64_t(32));
}

uint64_t gsRecordOffset(uint recordBase, uint offsetWord)
{
    return gsU64(gsMeshes.words[recordBase + offsetWord], gsMeshes.words[recordBase + offsetWord + 1u]);
}

uint gsWordIndex(uint64_t byteOffset)
{
    // Descriptor creation validates this narrowing against maxStorageBufferRange.
    return uint(byteOffset >> uint64_t(2));
}

bool gsOptionalOffsetPresent(uint recordBase, uint offsetWord)
{
    return gsMeshes.words[recordBase + offsetWord] != GS_INVALID_SLOT
        || gsMeshes.words[recordBase + offsetWord + 1u] != GS_INVALID_SLOT;
}

vec4 gsUnpackRgba8(uint packed)
{
    return vec4(
        float(packed & 0xffu),
        float((packed >> 8u) & 0xffu),
        float((packed >> 16u) & 0xffu),
        float((packed >> 24u) & 0xffu)
    ) * (1.0 / 255.0);
}

vec3 gsSrgbToLinear(vec3 value)
{
    bvec3 low = lessThanEqual(value, vec3(0.04045));
    vec3 linearLow = value * (1.0 / 12.92);
    vec3 linearHigh = pow((value + vec3(0.055)) * (1.0 / 1.055), vec3(2.4));
    return mix(linearHigh, linearLow, low);
}

float gsFiniteDoubleAsFloat(uint lowWord, uint highWord)
{
    uint exponent = (highWord >> 20u) & 0x7ffu;
    uint highMantissa = highWord & 0xfffffu;
    if (exponent == 0u && highMantissa == 0u && lowWord == 0u) return 0.0;
    float fraction = float(highMantissa) * 9.5367431640625e-7
        + float(lowWord) * 2.220446049250313e-16;
    float magnitude = exponent == 0u
        ? ldexp(fraction, -1022)
        : ldexp(1.0 + fraction, int(exponent) - 1023);
    return (highWord & 0x80000000u) == 0u ? magnitude : -magnitude;
}

vec3 gsFrameVec3(uint word)
{
    return vec3(
        uintBitsToFloat(gsFrame.words[word]),
        uintBitsToFloat(gsFrame.words[word + 1u]),
        uintBitsToFloat(gsFrame.words[word + 2u])
    );
}

vec3 gsFrameCameraPosition()
{
    uint word = GPU_SCENE_FRAME_CAMERA_POSITION_WORD;
    return vec3(
        gsFiniteDoubleAsFloat(gsFrame.words[word], gsFrame.words[word + 1u]),
        gsFiniteDoubleAsFloat(gsFrame.words[word + 2u], gsFrame.words[word + 3u]),
        gsFiniteDoubleAsFloat(gsFrame.words[word + 4u], gsFrame.words[word + 5u])
    );
}

vec3 gsSkyRadiance(vec3 rayDirection)
{
    vec3 sky = gsFrameVec3(GPU_SCENE_FRAME_SKY_COLOR_WORD);
    float ambient = uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_AMBIENT_INTENSITY_WORD]);
    vec3 sunDirection = normalize(gsFrameVec3(GPU_SCENE_FRAME_SUN_DIRECTION_WORD));
    vec3 sunColor = gsFrameVec3(GPU_SCENE_FRAME_SUN_COLOR_WORD);
    float sunIntensity = uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_SUN_INTENSITY_WORD]);
    float horizon = 0.35 + 0.65 * clamp(rayDirection.y * 0.5 + 0.5, 0.0, 1.0);
    float sunDisk = smoothstep(0.9995, 0.9999, dot(normalize(rayDirection), -sunDirection));
    return sky * max(ambient, 0.05) * horizon + sunColor * sunIntensity * sunDisk;
}

bool gsTransientInstance(uint instanceIndex)
{
    return (instanceIndex & GPU_SCENE_TRANSIENT_INSTANCE_BIT) != 0u;
}

uint gsInstanceSlot(uint instanceIndex)
{
    return instanceIndex & ~GPU_SCENE_TRANSIENT_INSTANCE_BIT;
}

uint gsInstanceWord(uint instanceIndex, uint offset)
{
    uint base = gsInstanceSlot(instanceIndex) * GPU_SCENE_INSTANCE_RECORD_WORDS;
    return gsTransientInstance(instanceIndex)
        ? gsTransientInstances.words[base + offset]
        : gsInstances.words[base + offset];
}

uint gsInstanceMeshSlot(uint instanceSlot)
{
    return gsInstanceWord(instanceSlot, GPU_SCENE_INSTANCE_MESH_SLOT_WORD);
}

vec3 gsPreviousInstancePoint(uint instanceSlot, vec3 point)
{
    uint base = gsInstanceSlot(instanceSlot) * GPU_SCENE_INSTANCE_RECORD_WORDS
        + GPU_SCENE_INSTANCE_PREVIOUS_TRANSFORM_WORD;
    vec4 local = vec4(point, 1.0);
    return vec3(
        dot(local, vec4(
            uintBitsToFloat(gsTransientInstance(instanceSlot) ? gsTransientInstances.words[base] : gsInstances.words[base]),
            uintBitsToFloat(gsTransientInstance(instanceSlot) ? gsTransientInstances.words[base + 1u] : gsInstances.words[base + 1u]),
            uintBitsToFloat(gsTransientInstance(instanceSlot) ? gsTransientInstances.words[base + 2u] : gsInstances.words[base + 2u]),
            uintBitsToFloat(gsTransientInstance(instanceSlot) ? gsTransientInstances.words[base + 3u] : gsInstances.words[base + 3u])
        )),
        dot(local, vec4(
            uintBitsToFloat(gsTransientInstance(instanceSlot) ? gsTransientInstances.words[base + 4u] : gsInstances.words[base + 4u]),
            uintBitsToFloat(gsTransientInstance(instanceSlot) ? gsTransientInstances.words[base + 5u] : gsInstances.words[base + 5u]),
            uintBitsToFloat(gsTransientInstance(instanceSlot) ? gsTransientInstances.words[base + 6u] : gsInstances.words[base + 6u]),
            uintBitsToFloat(gsTransientInstance(instanceSlot) ? gsTransientInstances.words[base + 7u] : gsInstances.words[base + 7u])
        )),
        dot(local, vec4(
            uintBitsToFloat(gsTransientInstance(instanceSlot) ? gsTransientInstances.words[base + 8u] : gsInstances.words[base + 8u]),
            uintBitsToFloat(gsTransientInstance(instanceSlot) ? gsTransientInstances.words[base + 9u] : gsInstances.words[base + 9u]),
            uintBitsToFloat(gsTransientInstance(instanceSlot) ? gsTransientInstances.words[base + 10u] : gsInstances.words[base + 10u]),
            uintBitsToFloat(gsTransientInstance(instanceSlot) ? gsTransientInstances.words[base + 11u] : gsInstances.words[base + 11u])
        ))
    );
}

uvec2 gsInstanceMotionRevision(uint instanceIndex)
{
    return uvec2(
        gsInstanceWord(instanceIndex, GPU_SCENE_INSTANCE_MOTION_REVISION_WORD),
        gsInstanceWord(instanceIndex, GPU_SCENE_INSTANCE_MOTION_REVISION_WORD + 1u)
    );
}

uint gsInstanceFlags(uint instanceSlot)
{
    return gsInstanceWord(instanceSlot, GPU_SCENE_INSTANCE_FLAGS_WORD);
}

float gsInstanceSurfaceVisibility(uint instanceSlot)
{
    return clamp(uintBitsToFloat(
        gsInstanceWord(instanceSlot, GPU_SCENE_INSTANCE_SURFACE_VISIBILITY_WORD)
    ), 0.0, 1.0);
}

vec2 gsInstanceLightmapCoordinate(uint instanceSlot)
{
    uint packed = gsInstanceWord(instanceSlot, GPU_SCENE_INSTANCE_PACKED_LIGHT_WORD);
    return vec2(float(packed & 0xffffu), float((packed >> 16u) & 0xffffu)) * (1.0 / 240.0);
}

vec2 gsInstanceUv(uint instanceSlot, vec2 uv)
{
    uint word = GPU_SCENE_INSTANCE_UV_TRANSFORM_WORD;
    vec3 first = vec3(
        uintBitsToFloat(gsInstanceWord(instanceSlot, word)),
        uintBitsToFloat(gsInstanceWord(instanceSlot, word + 1u)),
        uintBitsToFloat(gsInstanceWord(instanceSlot, word + 2u))
    );
    vec3 second = vec3(
        uintBitsToFloat(gsInstanceWord(instanceSlot, word + 3u)),
        uintBitsToFloat(gsInstanceWord(instanceSlot, word + 4u)),
        uintBitsToFloat(gsInstanceWord(instanceSlot, word + 5u))
    );
    return vec2(dot(first, vec3(uv, 1.0)), dot(second, vec3(uv, 1.0)));
}

uint gsInstanceSurfaceMask(uint instanceSlot)
{
    return gsInstanceWord(instanceSlot, GPU_SCENE_INSTANCE_SURFACE_MASK_WORD);
}

uint gsInstanceOverlayReceiverMask(uint instanceSlot)
{
    return gsInstanceWord(instanceSlot, GPU_SCENE_INSTANCE_OVERLAY_RECEIVER_MASK_WORD);
}

uint gsInstanceObjectMask(uint instanceSlot)
{
    return gsInstanceWord(instanceSlot, GPU_SCENE_INSTANCE_OBJECT_MASK_WORD);
}

vec4 gsInstanceOutlineColor(uint instanceSlot)
{
    return gsUnpackRgba8(gsInstanceWord(instanceSlot, GPU_SCENE_INSTANCE_OUTLINE_COLOR_WORD));
}

float gsInstanceOutlineWidth(uint instanceSlot)
{
    return clamp(uintBitsToFloat(
        gsInstanceWord(instanceSlot, GPU_SCENE_INSTANCE_OUTLINE_WIDTH_WORD)
    ), 0.0, 8.0);
}

float gsInstanceCardinalLighting(
    uint instanceSlot,
    vec3 objectGeometricNormal,
    vec3 worldGeometricNormal
) {
    uint flags = gsInstanceFlags(instanceSlot);
    if ((flags & GPU_SCENE_INSTANCE_CARDINAL_LIGHTING_ENABLED) == 0u) return 1.0;

    vec3 normal = (flags & GPU_SCENE_INSTANCE_CARDINAL_LIGHTING_WORLD_SPACE) != 0u
        ? worldGeometricNormal
        : objectGeometricNormal;
    vec3 magnitude = abs(normal);
    uint axis = magnitude.x >= magnitude.y && magnitude.x >= magnitude.z
        ? 0u
        : magnitude.y >= magnitude.z ? 1u : 2u;
    uint direction = normal[axis] >= 0.0 ? 1u : 0u;
    uint word = GPU_SCENE_INSTANCE_CARDINAL_LIGHTING_WORD + axis * 2u + direction;
    return clamp(uintBitsToFloat(gsInstanceWord(instanceSlot, word)), 0.0, 1.0);
}

vec4 gsApplySurfaceVisibility(vec4 color, uint instanceSlot)
{
    vec4 fogColor = vec4(
        gsFrameVec3(GPU_SCENE_FRAME_FOG_COLOR_WORD),
        uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_FOG_COLOR_WORD + 3u])
    );
    return mix(
        fogColor * vec4(1.0, 1.0, 1.0, color.a),
        color,
        gsInstanceSurfaceVisibility(instanceSlot)
    );
}

uint gsMeshBase(uint meshSlot)
{
    return meshSlot * GPU_SCENE_MESH_RECORD_WORDS;
}

uvec3 gsTriangleIndices(uint meshBase, uint primitive)
{
    uint base = gsWordIndex(gsRecordOffset(meshBase, GPU_SCENE_MESH_INDEX_OFFSET_WORD))
        + primitive * 3u;
    return uvec3(gsIndices.words[base], gsIndices.words[base + 1u], gsIndices.words[base + 2u]);
}

uint gsTriangleMaterialSlot(uint meshBase, uint primitive)
{
    uint base = gsWordIndex(gsRecordOffset(meshBase, GPU_SCENE_MESH_TRIANGLE_MATERIAL_OFFSET_WORD));
    return gsTriangleMaterials.words[base + primitive];
}

vec3 gsPosition(uint meshBase, uint vertex)
{
    uint base = gsWordIndex(gsRecordOffset(meshBase, GPU_SCENE_MESH_POSITION_OFFSET_WORD)) + vertex * 3u;
    return vec3(uintBitsToFloat(gsPositions.words[base]),
        uintBitsToFloat(gsPositions.words[base + 1u]), uintBitsToFloat(gsPositions.words[base + 2u]));
}

vec3 gsNormal(uint meshBase, uint vertex)
{
    uint base = gsWordIndex(gsRecordOffset(meshBase, GPU_SCENE_MESH_NORMAL_OFFSET_WORD)) + vertex * 3u;
    return vec3(uintBitsToFloat(gsNormals.words[base]),
        uintBitsToFloat(gsNormals.words[base + 1u]), uintBitsToFloat(gsNormals.words[base + 2u]));
}

vec4 gsTangent(uint meshBase, uint vertex)
{
    uint base = gsWordIndex(gsRecordOffset(meshBase, GPU_SCENE_MESH_TANGENT_OFFSET_WORD)) + vertex * 4u;
    return vec4(uintBitsToFloat(gsTangents.words[base]),
        uintBitsToFloat(gsTangents.words[base + 1u]), uintBitsToFloat(gsTangents.words[base + 2u]),
        uintBitsToFloat(gsTangents.words[base + 3u]));
}

vec2 gsTexcoord(uint meshBase, uint vertex)
{
    uint base = gsWordIndex(gsRecordOffset(meshBase, GPU_SCENE_MESH_TEXCOORD_OFFSET_WORD)) + vertex * 2u;
    return vec2(uintBitsToFloat(gsTexcoords.words[base]),
        uintBitsToFloat(gsTexcoords.words[base + 1u]));
}

vec4 gsVertexColor(uint meshBase, uint vertex)
{
    uint base = gsWordIndex(gsRecordOffset(meshBase, GPU_SCENE_MESH_COLOR_OFFSET_WORD)) + vertex;
    return gsUnpackRgba8(gsColors.words[base]);
}

vec2 gsLightmapCoordinate(uint meshBase, uint vertex)
{
    uint base = gsWordIndex(gsRecordOffset(
        meshBase,
        GPU_SCENE_MESH_LIGHTMAP_COORDINATE_OFFSET_WORD
    )) + vertex * 2u;
    return vec2(
        uintBitsToFloat(gsLightmapCoordinates.words[base]),
        uintBitsToFloat(gsLightmapCoordinates.words[base + 1u])
    );
}

int gsAddressIndex(int index, int extent, uint addressMode)
{
    if (addressMode == GPU_SCENE_ADDRESS_REPEAT) {
        int wrapped = index % extent;
        return wrapped < 0 ? wrapped + extent : wrapped;
    }
    return clamp(index, 0, extent - 1);
}

vec4 gsTextureTexelLevel(uint textureBase, int x, int y, uint level);
vec4 gsSampleTextureLevel(uint textureSlot, vec2 uv, uint requestedLevel);
vec2 gsTriangleTexcoord(uint meshBase, uvec3 indices, vec3 barycentrics);

vec4 gsTextureTexel(uint textureBase, int x, int y)
{
    return gsTextureTexelLevel(textureBase, x, y, 0u);
}

uint64_t gsTextureLevelOffset(uint textureBase, uint level)
{
    uint width = gsTextures.words[textureBase + GPU_SCENE_TEXTURE_WIDTH_WORD];
    uint height = gsTextures.words[textureBase + GPU_SCENE_TEXTURE_HEIGHT_WORD];
    uint64_t offset = uint64_t(0);
    for (uint current = 0u; current < level; current++) {
        uint levelWidth = max(1u, width >> current);
        uint levelHeight = max(1u, height >> current);
        offset += uint64_t(levelWidth) * uint64_t(levelHeight) * uint64_t(4u);
    }
    return offset;
}

vec4 gsTextureTexelLevel(uint textureBase, int x, int y, uint level)
{
    int width = max(1, int(gsTextures.words[textureBase + GPU_SCENE_TEXTURE_WIDTH_WORD] >> level));
    int height = max(1, int(gsTextures.words[textureBase + GPU_SCENE_TEXTURE_HEIGHT_WORD] >> level));
    x = gsAddressIndex(x, width, gsTextures.words[textureBase + GPU_SCENE_TEXTURE_ADDRESS_U_WORD]);
    y = gsAddressIndex(y, height, gsTextures.words[textureBase + GPU_SCENE_TEXTURE_ADDRESS_V_WORD]);
    uint64_t byteOffset = gsU64(
        gsTextures.words[textureBase + GPU_SCENE_TEXTURE_PIXEL_OFFSET_WORD],
        gsTextures.words[textureBase + GPU_SCENE_TEXTURE_PIXEL_OFFSET_WORD + 1u]
    ) + gsTextureLevelOffset(textureBase, level)
        + uint64_t(y) * uint64_t(width * 4)
        + uint64_t(x) * uint64_t(4);
    vec4 value = gsUnpackRgba8(gsTexturePixels.words[gsWordIndex(byteOffset)]);
    if (gsTextures.words[textureBase + GPU_SCENE_TEXTURE_COLOR_SPACE_WORD]
            == GPU_SCENE_COLOR_SPACE_SRGB) {
        value.rgb = gsSrgbToLinear(value.rgb);
    }
    return value;
}

vec4 gsSampleTexture(uint textureSlot, vec2 uv)
{
    return gsSampleTextureLevel(textureSlot, uv, 0u);
}

vec4 gsSampleTextureLevel(uint textureSlot, vec2 uv, uint requestedLevel)
{
    if (textureSlot == GS_INVALID_SLOT) return vec4(1.0);
    uint base = textureSlot * GPU_SCENE_TEXTURE_RECORD_WORDS;
    if ((gsTextures.words[base + GPU_SCENE_TEXTURE_FLAGS_WORD] & GPU_SCENE_FLAG_ACTIVE) == 0u) {
        return vec4(1.0);
    }
    uint level = min(
        requestedLevel,
        gsTextures.words[base + GPU_SCENE_TEXTURE_MIP_LEVEL_COUNT_WORD] - 1u
    );
    vec2 extent = vec2(
        max(1u, gsTextures.words[base + GPU_SCENE_TEXTURE_WIDTH_WORD] >> level),
        max(1u, gsTextures.words[base + GPU_SCENE_TEXTURE_HEIGHT_WORD] >> level)
    );
    if (gsTextures.words[base + GPU_SCENE_TEXTURE_FILTER_WORD] == GPU_SCENE_FILTER_NEAREST) {
        ivec2 coordinate = ivec2(floor(uv * extent));
        return gsTextureTexelLevel(base, coordinate.x, coordinate.y, level);
    }
    vec2 samplePosition = uv * extent - vec2(0.5);
    ivec2 lower = ivec2(floor(samplePosition));
    vec2 fraction = fract(samplePosition);
    vec4 c00 = gsTextureTexelLevel(base, lower.x, lower.y, level);
    vec4 c10 = gsTextureTexelLevel(base, lower.x + 1, lower.y, level);
    vec4 c01 = gsTextureTexelLevel(base, lower.x, lower.y + 1, level);
    vec4 c11 = gsTextureTexelLevel(base, lower.x + 1, lower.y + 1, level);
    return mix(mix(c00, c10, fraction.x), mix(c01, c11, fraction.x), fraction.y);
}

vec4 gsSampleTextureLod(uint textureSlot, vec2 uv, float requestedLod)
{
    if (textureSlot == GS_INVALID_SLOT) return vec4(1.0);
    uint base = textureSlot * GPU_SCENE_TEXTURE_RECORD_WORDS;
    uint lastLevel = gsTextures.words[base + GPU_SCENE_TEXTURE_MIP_LEVEL_COUNT_WORD] - 1u;
    float lod = clamp(requestedLod, 0.0, float(lastLevel));
    uint low = uint(floor(lod));
    uint high = min(low + 1u, lastLevel);
    return mix(
        gsSampleTextureLevel(textureSlot, uv, low),
        gsSampleTextureLevel(textureSlot, uv, high),
        fract(lod)
    );
}

float gsImplicitTextureLod(uint textureBase, vec2 du, vec2 dv)
{
    vec2 extent = vec2(
        gsTextures.words[textureBase + GPU_SCENE_TEXTURE_WIDTH_WORD],
        gsTextures.words[textureBase + GPU_SCENE_TEXTURE_HEIGHT_WORD]
    );
    float footprint = max(length(du * extent), length(dv * extent));
    return max(0.0, log2(max(footprint, 1.0e-8)));
}

vec3 gsPrimaryRayDirection(ivec2 pixel, ivec2 extent)
{
    vec2 ndc = (vec2(pixel) + vec2(0.5)) / vec2(extent) * 2.0 - 1.0;
    vec3 forward = normalize(gsFrameVec3(GPU_SCENE_FRAME_CAMERA_FORWARD_WORD));
    vec3 right = normalize(gsFrameVec3(GPU_SCENE_FRAME_CAMERA_RIGHT_WORD));
    vec3 up = normalize(gsFrameVec3(GPU_SCENE_FRAME_CAMERA_UP_WORD));
    vec2 fov = vec2(
        uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_FOV_WORD]),
        uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_FOV_WORD + 1u])
    );
    return normalize(forward + right * ndc.x * fov.x - up * ndc.y * fov.y);
}

vec3 gsWorldBarycentrics(vec3 point, vec3 p0, vec3 p1, vec3 p2)
{
    vec3 e1 = p1 - p0;
    vec3 e2 = p2 - p0;
    vec3 offset = point - p0;
    float d00 = dot(e1, e1);
    float d01 = dot(e1, e2);
    float d11 = dot(e2, e2);
    float d20 = dot(offset, e1);
    float d21 = dot(offset, e2);
    float denominator = d00 * d11 - d01 * d01;
    if (abs(denominator) < 1.0e-12) return vec3(1.0, 0.0, 0.0);
    float v = (d11 * d20 - d01 * d21) / denominator;
    float w = (d00 * d21 - d01 * d20) / denominator;
    return vec3(1.0 - v - w, v, w);
}

vec4 gsTriangleTextureFootprint(
    uint meshBase,
    uvec3 indices,
    vec3 barycentrics,
    vec3 worldP0,
    vec3 worldP1,
    vec3 worldP2,
    vec3 rayOrigin,
    uvec4 launchInfo,
    uint instanceSlot
)
{
    if (!gsOptionalOffsetPresent(meshBase, GPU_SCENE_MESH_TEXCOORD_OFFSET_WORD)) {
        return vec4(0.0);
    }
    ivec2 pixel = ivec2(launchInfo.x, launchInfo.y);
    ivec2 extent = ivec2(launchInfo.z, launchInfo.w);
    vec3 normal = cross(worldP1 - worldP0, worldP2 - worldP0);
    float normalLength = length(normal);
    if (normalLength < 1.0e-8 || any(lessThanEqual(extent, ivec2(0)))) return vec4(0.0);
    normal /= normalLength;
    vec2 currentUv = gsInstanceUv(
        instanceSlot,
        gsTriangleTexcoord(meshBase, indices, barycentrics)
    );
    vec2 derivatives[2];
    for (int axis = 0; axis < 2; ++axis) {
        ivec2 neighborPixel = pixel;
        neighborPixel[axis] += 1;
        vec3 direction = gsPrimaryRayDirection(neighborPixel, extent);
        float denominator = dot(direction, normal);
        if (abs(denominator) < 1.0e-8) return vec4(0.0);
        float distance = dot(worldP0 - rayOrigin, normal) / denominator;
        vec3 neighborPoint = rayOrigin + direction * distance;
        vec3 neighborBarycentrics = gsWorldBarycentrics(
            neighborPoint, worldP0, worldP1, worldP2
        );
        vec2 neighborUv = gsInstanceUv(
            instanceSlot,
            gsTriangleTexcoord(meshBase, indices, neighborBarycentrics)
        );
        derivatives[axis] = neighborUv - currentUv;
    }
    return vec4(derivatives[0].xy, derivatives[1].xy);
}

vec4 gsSamplePixelStable(uint textureSlot, vec2 uv, vec2 du, vec2 dv)
{
    uint base = textureSlot * GPU_SCENE_TEXTURE_RECORD_WORDS;
    vec2 pixelSize = 1.0 / vec2(
        gsTextures.words[base + GPU_SCENE_TEXTURE_WIDTH_WORD],
        gsTextures.words[base + GPU_SCENE_TEXTURE_HEIGHT_WORD]
    );
    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    vec2 uvTexelCoords = uv / pixelSize;
    vec2 texelCenter = round(uvTexelCoords) - vec2(0.5);
    vec2 texelOffset = uvTexelCoords - texelCenter;
    texelOffset = (texelOffset - vec2(0.5)) * pixelSize
        / max(texelScreenSize, vec2(1.0e-8)) + vec2(0.5);
    vec2 stableUv = (texelCenter + clamp(texelOffset, vec2(0.0), vec2(1.0))) * pixelSize;
    return gsSampleTextureLod(textureSlot, stableUv, gsImplicitTextureLod(base, du, dv));
}

vec4 gsSampleTextureFootprint(uint textureSlot, vec2 uv, vec2 du, vec2 dv)
{
    if (textureSlot == GS_INVALID_SLOT) return vec4(1.0);
    uint base = textureSlot * GPU_SCENE_TEXTURE_RECORD_WORDS;
    if ((gsTextures.words[base + GPU_SCENE_TEXTURE_FLAGS_WORD] & GPU_SCENE_FLAG_ACTIVE) == 0u) {
        return vec4(1.0);
    }
    uint width = gsTextures.words[base + GPU_SCENE_TEXTURE_WIDTH_WORD];
    uint height = gsTextures.words[base + GPU_SCENE_TEXTURE_HEIGHT_WORD];
    vec2 pixelSize = 1.0 / vec2(width, height);
    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    uint mode = gsFrame.words[GPU_SCENE_FRAME_TEXTURE_MINIFICATION_MODE_WORD];
    if (mode == 0u) {
        return gsSamplePixelStable(textureSlot, uv, du, dv);
    }
    if (mode == 1u) {
        float maxTexelSize = max(texelScreenSize.x, texelScreenSize.y);
        float minPixelSize = min(pixelSize.x, pixelSize.y);
        float blendFactor = smoothstep(minPixelSize, minPixelSize * 2.0, maxTexelSize);
        float minDerivative = min(length(du), length(dv));
        float maxDerivative = max(length(du), length(dv));
        float effectiveDerivative = sqrt(minDerivative * maxDerivative);
        float mipLevelExact = max(0.0, log2(max(effectiveDerivative / minPixelSize, 1.0e-8)));
        const vec2 offsets[4] = vec2[](
            vec2(0.125, 0.375), vec2(-0.125, -0.375),
            vec2(0.375, -0.125), vec2(-0.375, 0.125)
        );
        vec4 low = vec4(0.0);
        for (int index = 0; index < 4; ++index) {
            low += gsSampleTextureLod(
                textureSlot,
                uv + offsets[index] * pixelSize,
                mipLevelExact
            );
        }
        vec4 rgss = low * 0.25;
        return mix(
            gsSamplePixelStable(textureSlot, uv, du, dv),
            rgss,
            blendFactor
        );
    }
    // Texture storage is a byte arena, so Vulkan's fixed-function anisotropy cannot be attached to
    // it. Reconstruct the same contract with a bounded gather along the major UV derivative;
    // the minor derivative determines the mip level and the frame ABI caps the sample count.
    float duLength = length(du);
    float dvLength = length(dv);
    vec2 major = duLength >= dvLength ? du : dv;
    vec2 minor = duLength >= dvLength ? dv : du;
    float majorLength = max(length(major), 1.0e-8);
    float minorLength = max(length(minor), 1.0e-8);
    float anisotropy = clamp(majorLength / minorLength, 1.0, 16.0);
    uint sampleCount = uint(clamp(
        float(gsFrame.words[GPU_SCENE_FRAME_MAX_ANISOTROPY_WORD]), 1.0, 16.0
    ));
    sampleCount = min(sampleCount, uint(ceil(anisotropy)));
    float lod = max(0.0, log2(minorLength * max(width, height)));
    vec4 accumulated = vec4(0.0);
    for (uint index = 0u; index < 16u; ++index) {
        if (index >= sampleCount) break;
        float center = (float(index) + 0.5) / float(sampleCount) - 0.5;
        accumulated += gsSampleTextureLod(textureSlot, uv + major * center, lod);
    }
    return accumulated / float(sampleCount);
}

vec3 gsBarycentrics(vec2 hitAttributes)
{
    return vec3(1.0 - hitAttributes.x - hitAttributes.y, hitAttributes.x, hitAttributes.y);
}

vec2 gsTriangleTexcoord(uint meshBase, uvec3 indices, vec3 barycentrics)
{
    if (!gsOptionalOffsetPresent(meshBase, GPU_SCENE_MESH_TEXCOORD_OFFSET_WORD)) return vec2(0.0);
    return gsTexcoord(meshBase, indices.x) * barycentrics.x
        + gsTexcoord(meshBase, indices.y) * barycentrics.y
        + gsTexcoord(meshBase, indices.z) * barycentrics.z;
}

vec4 gsTriangleColor(uint meshBase, uvec3 indices, vec3 barycentrics)
{
    if (!gsOptionalOffsetPresent(meshBase, GPU_SCENE_MESH_COLOR_OFFSET_WORD)) return vec4(1.0);
    return gsVertexColor(meshBase, indices.x) * barycentrics.x
        + gsVertexColor(meshBase, indices.y) * barycentrics.y
        + gsVertexColor(meshBase, indices.z) * barycentrics.z;
}

vec4 gsSampleLightmap(vec2 uv)
{
    vec2 position = clamp(uv, vec2(0.0), vec2(1.0)) * 15.0;
    uvec2 lower = uvec2(floor(position));
    uvec2 upper = min(lower + uvec2(1u), uvec2(15u));
    vec2 fraction = fract(position);
    uint i00 = GPU_SCENE_FRAME_LIGHTMAP_WORD + lower.x * 16u + lower.y;
    uint i10 = GPU_SCENE_FRAME_LIGHTMAP_WORD + upper.x * 16u + lower.y;
    uint i01 = GPU_SCENE_FRAME_LIGHTMAP_WORD + lower.x * 16u + upper.y;
    uint i11 = GPU_SCENE_FRAME_LIGHTMAP_WORD + upper.x * 16u + upper.y;
    vec4 c00 = gsUnpackRgba8(gsFrame.words[i00]);
    vec4 c10 = gsUnpackRgba8(gsFrame.words[i10]);
    vec4 c01 = gsUnpackRgba8(gsFrame.words[i01]);
    vec4 c11 = gsUnpackRgba8(gsFrame.words[i11]);
    return mix(mix(c00, c10, fraction.x), mix(c01, c11, fraction.x), fraction.y);
}

vec4 gsTriangleLightmapModulatedColor(
    uint meshBase,
    uvec3 indices,
    vec3 barycentrics,
    uint instanceSlot
)
{
    if (!gsOptionalOffsetPresent(
            meshBase,
            GPU_SCENE_MESH_LIGHTMAP_COORDINATE_OFFSET_WORD
    )) return gsTriangleColor(meshBase, indices, barycentrics)
        * gsSampleLightmap(gsInstanceLightmapCoordinate(instanceSlot));
    // Match terrain.vsh: each vertex samples UV2 and multiplies Color before the rasterizer
    // interpolates the product. Interpolating UV2 first changes smooth-AO gradients.
    vec4 c0 = gsVertexColor(meshBase, indices.x)
        * gsSampleLightmap(gsLightmapCoordinate(meshBase, indices.x));
    vec4 c1 = gsVertexColor(meshBase, indices.y)
        * gsSampleLightmap(gsLightmapCoordinate(meshBase, indices.y));
    vec4 c2 = gsVertexColor(meshBase, indices.z)
        * gsSampleLightmap(gsLightmapCoordinate(meshBase, indices.z));
    return c0 * barycentrics.x + c1 * barycentrics.y + c2 * barycentrics.z;
}

#endif
