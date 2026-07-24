#version 460
#extension GL_EXT_ray_tracing : require

struct RtPayload
{
    vec4 colorAlpha;
    float hitT;
    uint blendMode;
    uint hit;
    uint reserved;
#ifdef MCVULKANRT_GBUFFER
    uint materialId;
    uint normalOct16;
    uint albedoRgba8;
    uint emissiveRgba8;
#endif
};

layout(location = 0) rayPayloadInEXT RtPayload payload;
hitAttributeEXT vec2 hitAttributes;

layout(set = 0, binding = 2, std430) readonly buffer SectionRecords
{
    uvec4 sections[];
} sectionRecords;

layout(set = 0, binding = 3, std430) readonly buffer FaceRecords
{
    uvec4 faces[];
} faceRecords;

layout(set = 0, binding = 4, std430) readonly buffer FrameUniformBuffer
{
    vec4 cameraPosition;
    vec4 cameraForward;
    vec4 cameraRight;
    vec4 cameraUp;
    uvec4 frameInfo;
    uvec2 frameSequence;
    uvec2 reserved;
    vec4 environmentTime;
} frame;

layout(set = 0, binding = 5, std430) readonly buffer TextureRecords
{
    uvec4 textures[];
} textureRecords;

layout(set = 0, binding = 6, std430) readonly buffer TexturePixels
{
    uint pixels[];
} texturePixels;

layout(set = 0, binding = 7, std430) readonly buffer DynamicSceneBuffer
{
    uvec4 info;
    uvec4 counts;
    vec4 skyZenith;
    vec4 skyHorizon;
    vec4 primaryLightDirectionIntensity;
    vec4 primaryLightColorFlags;
    uvec4 lightmapPayloadRgba[64];
    vec4 celestialDirectionRadius[8];
    uvec4 celestialColorKind[8];
    vec4 primitivePositionRadius[64];
    uvec4 primitiveColorKind[64];
    vec4 particlePositionSize[256];
    uvec4 particleColorKind[256];
    vec4 particleRotation[256];
    vec4 particleUv[256];
    vec4 particleLifecycle[256];
    vec4 beamStartRadius[32];
    vec4 beamEndFlags[32];
    uvec4 beamColorKind[32];
    vec4 localLightPositionRadius[64];
    uvec4 localLightColorKindIntensity[64];
    vec4 fogColorFlags;
    vec4 fogDistances0;
    vec4 fogDistances1;
    uvec4 environmentInfo;
    vec4 environmentTime;
    vec4 weatherColumnBounds[256];
    vec4 weatherColumnData[256];
    uvec4 weatherColumnMeta[256];
    uvec4 blockDecalInfo;
    ivec4 blockDecalBoundsMin;
    ivec4 blockDecalBoundsMax;
    ivec4 blockDecals[128];
    vec4 blockDecalOffsets[128];
} dynamicScene;

const uint BOOTSTRAP_SENTINEL_DIRECTION = 255u;
const uint FLAG_LIQUID = 4u;
const uint FLAG_VERTEX_LIGHT_KNOWN = 32u;
const uint FLAG_VERTEX_AO_TRANSLUCENT = 64u;
const uint DYNAMIC_OUTLINE_ENABLED_BIT = 2u;
const uint DYNAMIC_OUTLINE_ONLY_BIT = 4u;
const uint DYNAMIC_DECAL_ENABLED_BIT = 128u;
const uint DYNAMIC_FACE_MARKER = 0x02000000u;
const uint DYNAMIC_FACE_MARKER_MASK = 0x0f000000u;
const uint DYNAMIC_BLEND_MODE_SHIFT = 28u;
const uint DYNAMIC_FOIL_MODE_MASK = 24u;
const uint DYNAMIC_FOIL_MODE_SHIFT = 3u;
const uint DYNAMIC_NO_OVERLAY = 0xa0u;
const uint MAX_DYNAMIC_LIGHTS = 64u;
const uint BLOCK_DECAL_TABLE_SLOTS = 128u;
const uint TEXTURE_INFO_TEXTURE_ID_MASK = 0x3fffffffu;
const uint TEXTURE_INFO_ALPHA_CUTOUT_BIT = 0x40000000u;
const uint TEXTURE_INFO_TINT_BIT = 0x80000000u;
const uint DYNAMIC_MATERIAL_INDEX_BIT = 0x00800000u;
const uint DYNAMIC_MATERIAL_LOCAL_INDEX_MASK = 0x007fffffu;
const uint RAY_QUERY_DIRECTIONAL_VISIBILITY = 1u;

uint resolveMaterialIndex(uint customIndex)
{
    if ((customIndex & DYNAMIC_MATERIAL_INDEX_BIT) == 0u) {
        return customIndex;
    }
    uint terrainMaterialCount = uint(max(frame.environmentTime.w, 0.0));
    return terrainMaterialCount + (customIndex & DYNAMIC_MATERIAL_LOCAL_INDEX_MASK);
}

vec3 fallbackBlockColor(uint voxelTypeId, uint materialFlags)
{
    uint value = voxelTypeId * 747796405u + 2891336453u;
    value = ((value >> ((value >> 28u) + 4u)) ^ value) * 277803737u;
    value = (value >> 22u) ^ value;
    /*
     * MapColor.NONE is common for special blocks that do not expose a stable
     * terrain color. A wide RGB hash makes those faces look like random broken
     * textures, so keep the fallback in a narrow natural range until the real
     * atlas/material system replaces this bootstrap model.
     */
    float variation = float(value & 0xffu) / 255.0;
    vec3 coolStone = vec3(0.42, 0.43, 0.42);
    vec3 warmEarth = vec3(0.58, 0.52, 0.42);
    return mix(coolStone, warmEarth, variation);
}

float faceShade(uint direction)
{
    if (direction == 2u) {
        return 0.50;
    }
    if (direction == 3u) {
        return 1.00;
    }
    if (direction == 0u || direction == 1u) {
        return 0.60;
    }
    if (direction == 4u || direction == 5u) {
        return 0.80;
    }
    return 0.70;
}

vec3 faceNormal(uint direction)
{
    if (direction == 0u) {
        return vec3(-1.0, 0.0, 0.0);
    }
    if (direction == 1u) {
        return vec3(1.0, 0.0, 0.0);
    }
    if (direction == 2u) {
        return vec3(0.0, -1.0, 0.0);
    }
    if (direction == 3u) {
        return vec3(0.0, 1.0, 0.0);
    }
    if (direction == 4u) {
        return vec3(0.0, 0.0, -1.0);
    }
    if (direction == 5u) {
        return vec3(0.0, 0.0, 1.0);
    }
    return vec3(0.0, 1.0, 0.0);
}

vec3 worldFaceNormal(uint direction)
{
    return normalize(gl_ObjectToWorldEXT * vec4(faceNormal(direction), 0.0));
}

#ifdef MCVULKANRT_GBUFFER
uint packRgba8(vec3 color, float alpha)
{
    uvec3 rgb = uvec3(round(clamp(color, vec3(0.0), vec3(1.0)) * 255.0));
    uint a = uint(round(clamp(alpha, 0.0, 1.0) * 255.0));
    return rgb.x | (rgb.y << 8u) | (rgb.z << 16u) | (a << 24u);
}

uint packNormalOct16(vec3 normal)
{
    vec3 unit = normalize(normal);
    unit /= max(abs(unit.x) + abs(unit.y) + abs(unit.z), 0.00001);
    vec2 oct = unit.xy;
    if (unit.z < 0.0) {
        oct = (vec2(1.0) - abs(oct.yx)) * sign(oct.xy);
    }
    ivec2 packed = ivec2(round(clamp(oct, vec2(-1.0), vec2(1.0)) * 32767.0));
    return (uint(packed.x) & 0xffffu) | ((uint(packed.y) & 0xffffu) << 16u);
}
#endif

vec3 unpackMapColor(uint packedRgb)
{
    uint rgb = packedRgb & 0x00ffffffu;
    return vec3(
        float((rgb >> 16u) & 0xffu) / 255.0,
        float((rgb >> 8u) & 0xffu) / 255.0,
        float(rgb & 0xffu) / 255.0
    );
}

uint lightmapPayloadRgba(uint firstCoordinate, uint secondCoordinate)
{
    uint tableIndex = min(firstCoordinate, 15u) * 16u + min(secondCoordinate, 15u);
    uvec4 packed = dynamicScene.lightmapPayloadRgba[tableIndex >> 2u];
    uint lane = tableIndex & 3u;
    if (lane == 0u) {
        return packed.x;
    }
    if (lane == 1u) {
        return packed.y;
    }
    if (lane == 2u) {
        return packed.z;
    }
    return packed.w;
}

vec3 unpackLightmapRgba8(uint packedRgba)
{
    return vec3(
        float(packedRgba & 0xffu) / 255.0,
        float((packedRgba >> 8u) & 0xffu) / 255.0,
        float((packedRgba >> 16u) & 0xffu) / 255.0
    );
}

vec3 lightmapPayloadColorSmooth(float firstCoordinate, float secondCoordinate)
{
    float first = clamp(firstCoordinate, 0.0, 15.0);
    float second = clamp(secondCoordinate, 0.0, 15.0);
    uint first0 = uint(floor(first));
    uint second0 = uint(floor(second));
    uint first1 = min(first0 + 1u, 15u);
    uint second1 = min(second0 + 1u, 15u);
    float firstT = first - float(first0);
    float secondT = second - float(second0);
    vec3 c00 = unpackLightmapRgba8(lightmapPayloadRgba(first0, second0));
    vec3 c10 = unpackLightmapRgba8(lightmapPayloadRgba(first1, second0));
    vec3 c01 = unpackLightmapRgba8(lightmapPayloadRgba(first0, second1));
    vec3 c11 = unpackLightmapRgba8(lightmapPayloadRgba(first1, second1));
    return mix(mix(c00, c10, firstT), mix(c01, c11, firstT), secondT);
}

vec3 lightmapPayloadColor(uint packedMapColorAndLight, uint materialFlags, float emissive)
{
    if ((materialFlags & FLAG_VERTEX_LIGHT_KNOWN) == 0u) {
        return vec3(1.0);
    }
    uint packedLight = (packedMapColorAndLight >> 24u) & 0xffu;
    uint firstCoordinate = (packedLight >> 4u) & 0x0fu;
    uint secondCoordinate = packedLight & 0x0fu;
    secondCoordinate = max(secondCoordinate, uint(round(clamp(emissive, 0.0, 1.0) * 15.0)));
    uint packedRgba = lightmapPayloadRgba(firstCoordinate, secondCoordinate);
    return vec3(
        float(packedRgba & 0xffu) / 255.0,
        float((packedRgba >> 8u) & 0xffu) / 255.0,
        float((packedRgba >> 16u) & 0xffu) / 255.0
    );
}

float vertexSmoothBlock(uint packedVertexLighting)
{
    return float(packedVertexLighting & 0xffu);
}

float vertexSmoothSky(uint packedVertexLighting)
{
    return float((packedVertexLighting >> 8u) & 0xffu);
}

float vertexShade(uint packedVertexLighting)
{
    return float((packedVertexLighting >> 16u) & 0xffu) / 255.0;
}

vec3 primitiveBarycentric(uint primitiveId)
{
    return vec3(
        1.0 - hitAttributes.x - hitAttributes.y,
        hitAttributes.x,
        hitAttributes.y
    );
}

vec2 unpackUv16(uint packedUv)
{
    return vec2(
        float(packedUv & 0xffffu) / 65535.0,
        float((packedUv >> 16u) & 0xffffu) / 65535.0
    );
}

vec3 unpackRgba8(uint packedRgba)
{
    return vec3(
        float(packedRgba & 0xffu) / 255.0,
        float((packedRgba >> 8u) & 0xffu) / 255.0,
        float((packedRgba >> 16u) & 0xffu) / 255.0
    );
}

float unpackAlpha8(uint packedRgba)
{
    return float((packedRgba >> 24u) & 0xffu) / 255.0;
}

vec3 applyPackedEntityOverlay(vec3 color, uint packedOverlay)
{
    uint overlayU = packedOverlay & 0x0fu;
    uint overlayV = (packedOverlay >> 4u) & 0x0fu;
    if (overlayV < 8u) {
        return mix(vec3(1.0, 0.0, 0.0), color, 178.0 / 255.0);
    }
    float overlayAlpha = 1.0 - (float(overlayU) / 15.0) * 0.75;
    return mix(vec3(1.0), color, overlayAlpha);
}

vec2 primitiveUv(uint primitiveId, uvec4 faceUv, uint primitivesPerMaterialRecord)
{
    vec3 barycentric = primitiveBarycentric(primitiveId);
    vec2 uv0 = unpackUv16(faceUv.x);
    vec2 uv1 = unpackUv16(faceUv.y);
    vec2 uv2 = unpackUv16(faceUv.z);
    vec2 uv3 = unpackUv16(faceUv.w);
    if (primitivesPerMaterialRecord == 1u) {
        return uv0 * barycentric.x + uv1 * barycentric.y + uv2 * barycentric.z;
    }
    if ((primitiveId & 1u) == 0u) {
        return uv0 * barycentric.x + uv1 * barycentric.y + uv2 * barycentric.z;
    }
    return uv0 * barycentric.x + uv2 * barycentric.y + uv3 * barycentric.z;
}

int signExtend10(uint value)
{
    return int(value << 22u) >> 22;
}

vec4 sampleTextureNearest(uint textureId, vec2 uv);

vec2 dynamicDecalVertexUv(uint packedWord, float scale)
{
    return vec2(
        float(signExtend10(packedWord & 0x3ffu)),
        float(signExtend10((packedWord >> 10u) & 0x3ffu))
    ) * scale;
}

vec2 dynamicDecalUv(uint primitiveId, uvec4 vertexLighting)
{
    uint fractionalBits = vertexLighting.w >> 28u;
    float scale = exp2(-float(fractionalBits));
    vec2 uv0 = dynamicDecalVertexUv(vertexLighting.y, scale);
    vec2 uv1 = dynamicDecalVertexUv(vertexLighting.z, scale);
    vec2 uv2 = dynamicDecalVertexUv(vertexLighting.w, scale);
    vec2 uv3 = uv0 + uv2 - uv1;
    vec3 barycentric = primitiveBarycentric(primitiveId);
    if ((primitiveId & 1u) == 0u) {
        return uv0 * barycentric.x + uv1 * barycentric.y + uv2 * barycentric.z;
    }
    return uv0 * barycentric.x + uv2 * barycentric.y + uv3 * barycentric.z;
}

uint dynamicDecalTextureId(uvec4 vertexLighting)
{
    return ((vertexLighting.y >> 20u) & 0x0fu)
        | (((vertexLighting.z >> 20u) & 0x0fu) << 4u)
        | (((vertexLighting.w >> 20u) & 0x0fu) << 8u)
        | (((vertexLighting.w >> 24u) & 0x0fu) << 12u);
}

vec4 sampleTextureNearestRepeat(uint textureId, vec2 uv)
{
    return sampleTextureNearest(textureId, fract(uv));
}

vec2 sheetedDecalUv(vec3 worldPosition, uint direction)
{
    if (direction == 0u) {
        return vec2(worldPosition.x, -worldPosition.z);
    }
    if (direction == 1u) {
        return vec2(worldPosition.x, worldPosition.z);
    }
    if (direction == 2u) {
        return vec2(-worldPosition.x, -worldPosition.y);
    }
    if (direction == 3u) {
        return vec2(worldPosition.x, -worldPosition.y);
    }
    if (direction == 4u) {
        return vec2(-worldPosition.z, -worldPosition.y);
    }
    return vec2(worldPosition.z, -worldPosition.y);
}

vec3 applyTerrainBlockDecal(vec3 baseColor, vec3 worldPosition, uint direction)
{
    if (dynamicScene.blockDecalInfo.x == 0u) {
        return baseColor;
    }
    ivec3 hitBlockPosition = ivec3(floor(
        worldPosition - worldFaceNormal(direction) * 0.001
    ));
    if (any(lessThan(hitBlockPosition, dynamicScene.blockDecalBoundsMin.xyz))
            || any(greaterThan(hitBlockPosition, dynamicScene.blockDecalBoundsMax.xyz))) {
        return baseColor;
    }
    uint mask = dynamicScene.blockDecalInfo.y;
    for (int offsetY = -1; offsetY <= 1; offsetY++) {
        for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
            for (int offsetX = -1; offsetX <= 1; offsetX++) {
                ivec3 candidate = hitBlockPosition + ivec3(offsetX, offsetY, offsetZ);
                uint hash = uint(candidate.x) * 0x8da6b343u
                    ^ uint(candidate.y) * 0xd8163841u
                    ^ uint(candidate.z) * 0xcb1ab31fu;
                hash ^= hash >> 16u;
                for (uint probe = 0u; probe < BLOCK_DECAL_TABLE_SLOTS; probe++) {
                    uint slot = (hash + probe) & mask;
                    ivec4 decal = dynamicScene.blockDecals[slot];
                    if (decal.w == 0) {
                        break;
                    }
                    if (!all(equal(decal.xyz, candidate))) {
                        continue;
                    }
                    vec3 modelOffset = dynamicScene.blockDecalOffsets[slot].xyz;
                    ivec3 actualBlockPosition = ivec3(floor(
                        worldPosition - worldFaceNormal(direction) * 0.001 - modelOffset
                    ));
                    if (!all(equal(actualBlockPosition, candidate))) {
                        break;
                    }
                    vec3 localPosition = worldPosition - vec3(decal.xyz) - modelOffset;
                    vec4 texel = sampleTextureNearestRepeat(
                        uint(decal.w),
                        sheetedDecalUv(localPosition, direction)
                    );
                    if (texel.a >= 0.1) {
                        return clamp(2.0 * baseColor * texel.rgb, vec3(0.0), vec3(1.0));
                    }
                    return baseColor;
                }
            }
        }
    }
    return baseColor;
}

vec3 shadeWithPackedVertexLighting(
    vec3 baseColor,
    float emissive,
    uint materialFlags,
    uvec4 vertexLighting,
    uint primitivesPerMaterialRecord
)
{
    if ((materialFlags & FLAG_VERTEX_LIGHT_KNOWN) == 0u) {
        return baseColor;
    }
    vec3 barycentric = primitiveBarycentric(gl_PrimitiveID);
    uint v0 = vertexLighting.x;
    bool triangleNative = primitivesPerMaterialRecord == 1u;
    uint v1 = triangleNative || (gl_PrimitiveID & 1u) == 0u ? vertexLighting.y : vertexLighting.z;
    uint v2 = triangleNative || (gl_PrimitiveID & 1u) == 0u ? vertexLighting.z : vertexLighting.w;
    float smoothBlock = vertexSmoothBlock(v0) * barycentric.x
        + vertexSmoothBlock(v1) * barycentric.y
        + vertexSmoothBlock(v2) * barycentric.z;
    float smoothSky = vertexSmoothSky(v0) * barycentric.x
        + vertexSmoothSky(v1) * barycentric.y
        + vertexSmoothSky(v2) * barycentric.z;
    float shade = vertexShade(v0) * barycentric.x
        + vertexShade(v1) * barycentric.y
        + vertexShade(v2) * barycentric.z;
    smoothBlock = max(smoothBlock, round(clamp(emissive, 0.0, 1.0) * 240.0));
    vec3 lightmapColor = lightmapPayloadColorSmooth(smoothSky / 16.0, smoothBlock / 16.0);
    return clamp(baseColor * lightmapColor * shade, vec3(0.0), vec3(1.0));
}

uint sectionFaceIndex(
    uint geometryIndex,
    uint primitiveId,
    uint secondGeometryFaceOffset,
    uint totalFaceCount,
    uint primitivesPerMaterialRecord
)
{
    uint rawFaceIndex = primitiveId / primitivesPerMaterialRecord;
    if (geometryIndex == 0u || secondGeometryFaceOffset == 0u) {
        uint firstGeometryFaceCount = secondGeometryFaceOffset == 0u
            ? totalFaceCount
            : secondGeometryFaceOffset;
        return min(rawFaceIndex, max(firstGeometryFaceCount, 1u) - 1u);
    }

    /*
     * gl_PrimitiveID is local to the hit geometry. After the BLAS is split into
     * opaque and alpha-cutout geometry segments, the second segment must index
     * the reordered material table from secondGeometryFaceOffset + local face.
     * Subtracting the offset here corrupts dense leaf/cutout sections once the
     * masked geometry contains more faces than the opaque prefix.
     */
    uint secondGeometryFaceCount = max(totalFaceCount - secondGeometryFaceOffset, 1u);
    return secondGeometryFaceOffset + min(rawFaceIndex, secondGeometryFaceCount - 1u);
}

vec4 sampleTextureNearest(uint textureId, vec2 uv)
{
    uvec4 textureRecord = textureRecords.textures[textureId];
    uint pixelOffset = textureRecord.x;
    uint width = max(textureRecord.y, 1u);
    uint height = max(textureRecord.z, 1u);
    /*
     * Face records store sprite-local UVs in the same half-open [0, 1) domain
     * The source atlas sampler effectively uses for texel centers. Pulling an
     * exact 1.0 edge back by one ulp keeps full-quad water/leaf UVs from
     * collapsing onto the final texel column while preserving wrapped sprite
     * endpoints authored by the source model baker.
     */
    vec2 clampedUv = clamp(uv, vec2(0.0), vec2(0.99999994));
    uint x = min(uint(floor(clampedUv.x * float(width))), width - 1u);
    uint y = min(uint(floor(clampedUv.y * float(height))), height - 1u);
    uint packed = texturePixels.pixels[pixelOffset + y * width + x];
    return vec4(unpackRgba8(packed), unpackAlpha8(packed));
}

float liquidTextureDetail(vec3 textureColor)
{
    /*
     * Source fluid geometry submits the resolved fluid tint as vertex color and
     * lets still/flowing sprites provide surface detail. Water sprites in modern
     * resource packs are often neutral gray-blue, so multiplying tint by raw RGB
     * can visibly desaturate the biome color. Keep the captured source tint as
     * the material chroma and use texture luminance only as a bounded ripple term.
     */
    float luminance = dot(textureColor, vec3(0.2126, 0.7152, 0.0722));
    return clamp(mix(0.75, 1.25, luminance), 0.65, 1.35);
}

vec3 shadeWithLightmapPayload(
    vec3 baseColor,
    uint direction,
    float emissive,
    uint materialFlags,
    uint packedMapColorAndLight
)
{
    vec3 lightmapColor = lightmapPayloadColor(packedMapColorAndLight, materialFlags, emissive);
    float directionalShade = faceShade(direction);
    return clamp(baseColor * lightmapColor * directionalShade, vec3(0.0), vec3(1.0));
}

void main()
{
    uint materialIndex = resolveMaterialIndex(gl_InstanceCustomIndexEXT);
    uvec4 section = sectionRecords.sections[materialIndex];
    uint totalFaceCount = max(section.y, 1u);
    uint secondGeometryFaceOffset = min(section.z, totalFaceCount);
    uint primitivesPerMaterialRecord = clamp(section.w, 1u, 2u);
    uint faceIndex = sectionFaceIndex(
        gl_GeometryIndexEXT,
        gl_PrimitiveID,
        secondGeometryFaceOffset,
        totalFaceCount,
        primitivesPerMaterialRecord
    );
    uint faceRecordIndex = (section.x + faceIndex) * 3u;
    uvec4 face = faceRecords.faces[faceRecordIndex];
    uvec4 faceUv = faceRecords.faces[faceRecordIndex + 1u];
    uvec4 vertexLighting = faceRecords.faces[faceRecordIndex + 2u];
    uint fluidAmount = face.y & 0xffu;
    uint direction = (face.y >> 8u) & 0xffu;
    uint lightEmission = (face.y >> 16u) & 0xffu;
    uint materialFlags = (face.y >> 24u) & 0xffu;
    bool liquid = (materialFlags & FLAG_LIQUID) != 0u && fluidAmount > 0u;
    bool dynamicFace = (face.x & DYNAMIC_FACE_MARKER_MASK) == DYNAMIC_FACE_MARKER;
    uint foilMode = dynamicFace
        ? (materialFlags & DYNAMIC_FOIL_MODE_MASK) >> DYNAMIC_FOIL_MODE_SHIFT
        : 0u;
    bool outlineEnabled = dynamicFace && (materialFlags & DYNAMIC_OUTLINE_ENABLED_BIT) != 0u;
    bool outlineOnly = dynamicFace && (materialFlags & DYNAMIC_OUTLINE_ONLY_BIT) != 0u;
    bool decalEnabled = dynamicFace && (materialFlags & DYNAMIC_DECAL_ENABLED_BIT) != 0u;

    if (direction == BOOTSTRAP_SENTINEL_DIRECTION) {
        // Empty-TLAS bootstrap geometry preserves a valid descriptor but must
        // never become a physical shadow caster.
        if (payload.reserved == RAY_QUERY_DIRECTIONAL_VISIBILITY) {
            return;
        }
        payload.colorAlpha = vec4(32.0 / 255.0, 128.0 / 255.0, 1.0, 1.0);
        payload.hitT = gl_HitTEXT;
        payload.blendMode = 0u;
        payload.hit = 1u;
#ifdef MCVULKANRT_GBUFFER
        payload.materialId = materialIndex;
        payload.normalOct16 = packNormalOct16(vec3(0.0, 0.0, 1.0));
        payload.albedoRgba8 = packRgba8(payload.colorAlpha.rgb, 1.0);
        payload.emissiveRgba8 = 0u;
#endif
        return;
    }

    if (payload.reserved == RAY_QUERY_DIRECTIONAL_VISIBILITY) {
        payload.hitT = gl_HitTEXT;
        payload.hit = 1u;
        return;
    }

    uint textureInfo = face.w;
    uint textureId = textureInfo & TEXTURE_INFO_TEXTURE_ID_MASK;
    bool tinted = (textureInfo & TEXTURE_INFO_TINT_BIT) != 0u;
    bool alphaCutout = (textureInfo & TEXTURE_INFO_ALPHA_CUTOUT_BIT) != 0u;
    uint mapRgb = face.z & 0x00ffffffu;
    vec3 baseColor;
    float textureAlpha = 1.0;
    if (textureId == 0u && mapRgb != 0u) {
        baseColor = unpackMapColor(face.z);
    } else if (textureId == 0u) {
        baseColor = fallbackBlockColor(face.x, materialFlags);
    } else {
        vec4 texel = sampleTextureNearest(
            textureId,
            primitiveUv(gl_PrimitiveID, faceUv, primitivesPerMaterialRecord)
        );
        textureAlpha = texel.a;
        baseColor = texel.rgb;
        if (tinted) {
            baseColor *= unpackMapColor(face.z);
        }
    }
    if (liquid && textureId != 0u && mapRgb != 0u && lightEmission == 0u) {
        vec3 liquidTint = unpackMapColor(face.z);
        baseColor = liquidTint * liquidTextureDetail(baseColor);
    }
    if (!dynamicFace && dynamicScene.blockDecalInfo.x != 0u) {
        vec3 worldPosition = gl_WorldRayOriginEXT + gl_WorldRayDirectionEXT * gl_HitTEXT;
        baseColor = applyTerrainBlockDecal(baseColor, worldPosition, direction);
    }
    uint packedOverlay = dynamicFace ? ((vertexLighting.x >> 24u) & 0xffu) : DYNAMIC_NO_OVERLAY;
    baseColor = applyPackedEntityOverlay(baseColor, packedOverlay);
    if (decalEnabled) {
        vec4 decalTexel = sampleTextureNearestRepeat(
            dynamicDecalTextureId(vertexLighting),
            dynamicDecalUv(gl_PrimitiveID, vertexLighting)
        );
        if (decalTexel.a >= 0.1) {
            baseColor = clamp(2.0 * baseColor * decalTexel.rgb, vec3(0.0), vec3(1.0));
        }
    }
    float outlineAlpha = outlineEnabled
        ? float((vertexLighting.z >> 24u) & 0xffu) / 255.0
        : 0.0;
    if (outlineEnabled) {
        vec3 outlineColor = unpackMapColor(face.x);
        if (outlineOnly) {
            baseColor = outlineColor;
        } else {
            float facing = abs(dot(worldFaceNormal(direction), normalize(-gl_WorldRayDirectionEXT)));
            float rim = smoothstep(0.32, 0.04, facing) * outlineAlpha;
            baseColor = mix(baseColor, outlineColor, rim);
        }
    }
    if (foilMode != 0u) {
        float facing = clamp(dot(worldFaceNormal(direction), normalize(-gl_WorldRayDirectionEXT)), 0.0, 1.0);
        float sheen = pow(1.0 - facing, 4.0);
        vec3 foilColor = vec3(0.62, 0.30, 0.95);
        /* Continuous RT shading approximates the source sparse glint texture by its mean energy. */
        float foilStrength = (foilMode == 2u ? 0.85 : 1.0) * (0.015 + 0.045 * sheen);
        /*
         * The source renderer emits glint as a depth-equal overlay with GLINT's
         * SRC_COLOR/ONE blend. Preserve the material albedo and approximate
         * that additive source-color-squared contribution in closest-hit;
         * replacing albedo with a purple mix destroys tint identity.
         */
        baseColor = clamp(baseColor + foilColor * foilColor * foilStrength, vec3(0.0), vec3(1.0));
    }
    float emissive = outlineOnly ? 1.0 : clamp(float(lightEmission) / 15.0, 0.0, 1.0);
    float tintAlpha = dynamicFace
        ? float((vertexLighting.y >> 24u) & 0xffu) / 255.0
        : 1.0;
    uint flatDynamicLighting = vertexLighting.x & 0x00ffffffu;
    uvec4 shadingLighting = dynamicFace
        ? uvec4(flatDynamicLighting)
        : vertexLighting;
    payload.colorAlpha = vec4(
        shadeWithPackedVertexLighting(
            baseColor,
            emissive,
            materialFlags,
            shadingLighting,
            primitivesPerMaterialRecord
        ),
        clamp(textureAlpha * (outlineOnly ? outlineAlpha : tintAlpha), 0.0, 1.0)
    );
    payload.hitT = gl_HitTEXT;
    payload.blendMode = dynamicFace
        ? (face.x >> DYNAMIC_BLEND_MODE_SHIFT) & 0x0fu
        : (liquid || ((materialFlags & FLAG_VERTEX_AO_TRANSLUCENT) != 0u && !alphaCutout) ? 1u : 0u);
    payload.hit = 1u;
#ifdef MCVULKANRT_GBUFFER
    payload.materialId = dynamicFace
        ? (0x80000000u | textureId)
        : face.x;
    payload.normalOct16 = packNormalOct16(worldFaceNormal(direction));
    payload.albedoRgba8 = packRgba8(baseColor, textureAlpha);
    payload.emissiveRgba8 = packRgba8(vec3(emissive), 1.0);
#endif
}
