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

const uint MAX_CELESTIAL_BODIES = 8u;
const uint DYNAMIC_SCENE_FLAG_ACTIVE = 1u;
const uint RAY_QUERY_DIRECTIONAL_VISIBILITY = 1u;
const uint ENVIRONMENT_FLAG_FOG_KNOWN = 1u;
const uint ENVIRONMENT_FLAG_CLOUD_KNOWN = 2u;
const uint ENVIRONMENT_FLAG_SKY_VISIBLE = 4u;
const vec3 DEFAULT_MISS_COLOR = vec3(92.0 / 255.0, 148.0 / 255.0, 224.0 / 255.0);

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

float hash21(vec2 p)
{
    p = fract(p * vec2(123.34, 345.45));
    p += dot(p, p + 34.345);
    return fract(p.x * p.y);
}

float valueNoise(vec2 p)
{
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float cloudCoverage(vec2 cloudPos)
{
    vec2 p = cloudPos / 32.0 + vec2(frame.environmentTime.x * 0.0025, 0.0);
    float n = valueNoise(p) * 0.58
        + valueNoise(p * 2.03 + vec2(17.0, 3.0)) * 0.28
        + valueNoise(p * 4.07 + vec2(5.0, 29.0)) * 0.14;
    return smoothstep(0.50, 0.74, n);
}

vec3 dynamicSkyColor(vec3 rayDirection)
{
    if ((dynamicScene.info.w & DYNAMIC_SCENE_FLAG_ACTIVE) == 0u) {
        return DEFAULT_MISS_COLOR;
    }
    if ((dynamicScene.environmentInfo.z & ENVIRONMENT_FLAG_SKY_VISIBLE) == 0u) {
        return (dynamicScene.environmentInfo.z & ENVIRONMENT_FLAG_FOG_KNOWN) != 0u
            ? dynamicScene.fogColorFlags.rgb
            : DEFAULT_MISS_COLOR;
    }

    float up = clamp(rayDirection.y * 0.5 + 0.5, 0.0, 1.0);
    vec3 color = mix(dynamicScene.skyHorizon.rgb, dynamicScene.skyZenith.rgb, up);
    uint celestialCount = min(dynamicScene.info.z, MAX_CELESTIAL_BODIES);
    for (uint index = 0u; index < celestialCount; index++) {
        vec4 directionRadius = dynamicScene.celestialDirectionRadius[index];
        vec3 bodyDirection = normalize(directionRadius.xyz);
        float angularRadius = max(directionRadius.w, 0.0001);
        float alignment = dot(rayDirection, bodyDirection);
        float inner = cos(angularRadius);
        float outer = cos(angularRadius * 1.35);
        float disc = smoothstep(outer, inner, alignment);
        uvec4 colorKind = dynamicScene.celestialColorKind[index];
        vec3 bodyColor = unpackRgba8(colorKind.x) * max(uintBitsToFloat(colorKind.w), 0.0);
        float alpha = unpackAlpha8(colorKind.x);
        color = mix(color, bodyColor, clamp(disc * alpha, 0.0, 1.0));
    }
    return clamp(color, vec3(0.0), vec3(1.0));
}

vec3 applyCloudLayer(vec3 color, vec3 rayDirection)
{
    if ((dynamicScene.environmentInfo.z & ENVIRONMENT_FLAG_CLOUD_KNOWN) == 0u
            || dynamicScene.environmentInfo.y == 0u) {
        return color;
    }
    if (abs(rayDirection.y) <= 0.002) {
        return color;
    }
    float cloudHeight = dynamicScene.fogDistances1.z;
    float hitT = (cloudHeight - frame.cameraPosition.y) / rayDirection.y;
    if (hitT <= 0.0) {
        return color;
    }
    vec2 cloudDelta = rayDirection.xz * hitT;
    float horizontalDistance = length(cloudDelta);
    float range = max(dynamicScene.fogDistances1.w * 16.0, 64.0);
    float rangeFade = 1.0 - smoothstep(range * 0.72, range, horizontalDistance);
    if (rangeFade <= 0.0) {
        return color;
    }

    vec2 cloudPos = frame.cameraPosition.xz + cloudDelta;
    float coverage = cloudCoverage(cloudPos);
    float fancyLift = dynamicScene.environmentInfo.y >= 2u ? 1.0 : 0.82;
    float alpha = unpackAlpha8(dynamicScene.environmentInfo.x) * coverage * rangeFade * fancyLift;
    return mix(color, unpackRgba8(dynamicScene.environmentInfo.x), clamp(alpha, 0.0, 0.88));
}

vec3 applySkyFog(vec3 color, vec3 rayDirection)
{
    if ((dynamicScene.environmentInfo.z & ENVIRONMENT_FLAG_FOG_KNOWN) == 0u) {
        return color;
    }
    float skyEnd = max(dynamicScene.fogDistances1.x, 0.0);
    if (skyEnd <= 0.001) {
        return color;
    }
    float horizonFog = pow(clamp(1.0 - abs(rayDirection.y), 0.0, 1.0), 1.45);
    float alpha = frame.environmentTime.z > 0.0 ? frame.environmentTime.z : 1.0;
    return mix(color, dynamicScene.fogColorFlags.rgb, clamp(horizonFog * alpha, 0.0, 1.0));
}

void main()
{
    /*
     * A visibility miss is already the complete answer. Running sky, cloud,
     * and fog shading here would turn every unoccluded shadow ray into an
     * accidental secondary shading invocation.
     */
    if (payload.reserved == RAY_QUERY_DIRECTIONAL_VISIBILITY) {
        payload.hit = 0u;
        return;
    }
    vec3 rayDirection = normalize(gl_WorldRayDirectionEXT);
    vec3 color = dynamicSkyColor(rayDirection);
    color = applyCloudLayer(color, rayDirection);
    color = applySkyFog(color, rayDirection);
    payload.colorAlpha = vec4(color, 1.0);
    payload.hitT = -1.0;
    payload.blendMode = 0u;
    payload.hit = 0u;
#ifdef MCVULKANRT_GBUFFER
    payload.materialId = 0u;
    payload.normalOct16 = 0u;
    payload.albedoRgba8 = 0u;
    payload.emissiveRgba8 = 0u;
#endif
}
