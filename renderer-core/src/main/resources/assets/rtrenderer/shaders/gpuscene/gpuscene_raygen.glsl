#extension GL_EXT_ray_tracing : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require
#ifdef RTRENDERER_SER
#extension GL_NV_shader_invocation_reorder : require
#endif

#include "gpuscene_medium.glsl"

layout(set = 0, binding = GPU_SCENE_BINDING_TLAS) uniform accelerationStructureEXT sceneTlas;
#ifdef RTRENDERER_LINEAR_HDR_OUTPUT
layout(set = 0, binding = GPU_SCENE_BINDING_OUTPUT_IMAGE, rgba16f) uniform writeonly image2D outputImage;
#else
layout(set = 0, binding = GPU_SCENE_BINDING_OUTPUT_IMAGE, rgba8) uniform writeonly image2D outputImage;
#endif
layout(set = 0, binding = GPU_SCENE_BINDING_HISTORY_COLOR_INPUT, rgba16f)
uniform readonly image2D historyColorInput;
layout(set = 0, binding = GPU_SCENE_BINDING_HISTORY_COLOR_OUTPUT, rgba16f)
uniform writeonly image2D historyColorOutput;
layout(set = 0, binding = GPU_SCENE_BINDING_HISTORY_GEOMETRY_INPUT, rgba16f)
uniform readonly image2D historyGeometryInput;
layout(set = 0, binding = GPU_SCENE_BINDING_HISTORY_GEOMETRY_OUTPUT, rgba16f)
uniform writeonly image2D historyGeometryOutput;
layout(set = 0, binding = GPU_SCENE_BINDING_MOTION_OUTPUT, rg16f)
uniform writeonly image2D motionOutput;
layout(set = 0, binding = GPU_SCENE_BINDING_DENOISING_NORMAL_ROUGHNESS, rgba16f)
uniform writeonly image2D denoisingNormalRoughness;
layout(set = 0, binding = GPU_SCENE_BINDING_DENOISING_VIEW_Z, r32f)
uniform writeonly image2D denoisingViewZ;
layout(set = 0, binding = GPU_SCENE_BINDING_DENOISING_MOTION_VECTORS, rgba16f)
uniform writeonly image2D denoisingMotionVectors;
layout(set = 0, binding = GPU_SCENE_BINDING_DENOISING_DIFFUSE_RADIANCE_HIT_DISTANCE, rgba16f)
uniform writeonly image2D denoisingDiffuseRadianceHitDistance;
layout(set = 0, binding = GPU_SCENE_BINDING_DENOISING_SPECULAR_RADIANCE_HIT_DISTANCE, rgba16f)
uniform writeonly image2D denoisingSpecularRadianceHitDistance;
layout(set = 0, binding = GPU_SCENE_BINDING_DENOISING_DIFFUSE_MATERIAL_FACTOR, rgba16f)
uniform writeonly image2D denoisingDiffuseMaterialFactor;
layout(set = 0, binding = GPU_SCENE_BINDING_DENOISING_SPECULAR_MATERIAL_FACTOR, rgba16f)
uniform writeonly image2D denoisingSpecularMaterialFactor;
layout(set = 0, binding = GPU_SCENE_BINDING_RECONSTRUCTION_DEPTH, r32f)
uniform writeonly image2D reconstructionDepth;
layout(set = 0, binding = GPU_SCENE_BINDING_RECONSTRUCTION_MOTION_VECTORS, rg16f)
uniform writeonly image2D reconstructionMotionVectors;
layout(set = 0, binding = GPU_SCENE_BINDING_RECONSTRUCTION_EXPOSURE, r32f)
uniform writeonly image2D reconstructionExposure;
layout(location = 0) rayPayloadEXT GpuScenePayload payload;
layout(location = 1) rayPayloadEXT GsShadowQuery gsShadowQuery;

GsMediumPath gsLightingMediumPath;
GpuScenePayload gsTraceRadiance(vec3 origin, vec3 direction, uvec4 launchInfo, GsMediumPath mediumPath);


vec3 gsToneMap(vec3 color)
{
    vec3 mapped = (color * (2.51 * color + 0.03)) / (color * (2.43 * color + 0.59) + 0.14);
    return pow(clamp(mapped, vec3(0.0), vec3(1.0)), vec3(1.0 / 2.2));
}

vec3 gsLinearToSrgb(vec3 color)
{
    vec3 clamped = clamp(color, vec3(0.0), vec3(1.0));
    bvec3 low = lessThanEqual(clamped, vec3(0.0031308));
    vec3 linear = clamped * 12.92;
    vec3 curved = 1.055 * pow(clamped, vec3(1.0 / 2.4)) - 0.055;
    return mix(curved, linear, low);
}

float gsLinearFogValue(float distance, float start, float end)
{
    if (distance <= start) return 0.0;
    if (distance >= end) return 1.0;
    return (distance - start) / (end - start);
}

vec3 gsApplyDistanceFog(vec3 color, float sphericalDistance, float cylindricalDistance)
{
    float spherical = gsLinearFogValue(
        sphericalDistance,
        uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_FOG_SPHERICAL_START_WORD]),
        uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_FOG_SPHERICAL_END_WORD])
    );
    float cylindrical = gsLinearFogValue(
        cylindricalDistance,
        uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_FOG_CYLINDRICAL_START_WORD]),
        uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_FOG_CYLINDRICAL_END_WORD])
    );
    vec4 fog = vec4(
        uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_FOG_COLOR_WORD]),
        uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_FOG_COLOR_WORD + 1u]),
        uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_FOG_COLOR_WORD + 2u]),
        uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_FOG_COLOR_WORD + 3u])
    );
    return mix(color, fog.rgb, max(spherical, cylindrical) * fog.a);
}

bool gsLightmapModulated(GpuScenePayload surfaceSample)
{
    return surfaceSample.state.x != 0u
        && ((surfaceSample.state.z >> GPU_SCENE_SHADING_MODEL_SHIFT) & GPU_SCENE_SHADING_MODEL_MASK)
            == GPU_SCENE_SHADING_LIGHTMAP_MODULATED;
}

bool gsUnlit(GpuScenePayload surfaceSample)
{
    return surfaceSample.state.x != 0u
        && ((surfaceSample.state.z >> GPU_SCENE_SHADING_MODEL_SHIFT) & GPU_SCENE_SHADING_MODEL_MASK)
            == GPU_SCENE_SHADING_UNLIT;
}

bool gsSurfaceOverlay(GpuScenePayload surfaceSample)
{
    return surfaceSample.state.x != 0u
        && ((surfaceSample.state.z >> GPU_SCENE_OVERLAY_DEPTH_MODE_SHIFT)
            & GPU_SCENE_OVERLAY_DEPTH_MODE_MASK) != 0u;
}

// An ordinary visibility ray must not route every surface through an extra TLAS miss.
// Participating/transmissive scenes retain the ordered service and its full medium path.
vec3 gsShadowTransmittance(vec3 origin, vec3 normal, vec3 direction, float maximumDistance) {
#ifndef RTRENDERER_SURFACE_ONLY
    GsMediumCoefficients medium = gsCurrentMedium(gsLightingMediumPath);
    if (gsFrame.words[GPU_SCENE_FRAME_MEDIUM_BOUNDARY_COUNT_WORD] == 0u
            && gsFrame.words[GPU_SCENE_FRAME_TRANSMISSIVE_SURFACE_COUNT_WORD] == 0u
            && all(lessThanEqual(medium.extinction * medium.density, vec3(0.0)))) {
#endif
        payload.state = uvec4(0u, GS_PAYLOAD_SHADOW_QUERY, 0u, 0u);
        traceRayEXT(sceneTlas, gl_RayFlagsTerminateOnFirstHitEXT, 0xff, 0, 0, 0,
            origin + normal * 1.0e-3, 1.0e-3, direction, maximumDistance, 0);
        return payload.state.x == 0u ? vec3(1.0) : vec3(0.0);
#ifndef RTRENDERER_SURFACE_ONLY
    }
    gsShadowQuery.operation=0u; gsShadowQuery.path=gsLightingMediumPath;
    gsShadowQuery.origin=origin; gsShadowQuery.normal=normal;
    gsShadowQuery.direction=direction; gsShadowQuery.maximumDistance=maximumDistance;
    traceRayEXT(sceneTlas,gl_RayFlagsNoneEXT,0,0,0,1,origin,1.0e-3,direction,1.0,1);
    return gsShadowQuery.transmittance;
#endif
}

#ifndef RTRENDERER_SURFACE_ONLY
#define gsEvaluatePbrSurface gsEvaluateDirectPbrSurface
#endif
#include "gpuscene_lighting.glsl"
#ifndef RTRENDERER_SURFACE_ONLY
#undef gsEvaluatePbrSurface

// Secondary paths share one lighting service, avoiding duplication of the light and
// medium loops at every optical continuation/overlay call site in the driver compiler.
GsRadianceSplit gsEvaluatePbrSurface(GpuScenePayload surfaceSample,vec3 rayOrigin) {
    gsShadowQuery.operation=2u;gsShadowQuery.path=gsLightingMediumPath;
    gsShadowQuery.surfaceSample=surfaceSample;gsShadowQuery.origin=rayOrigin;
    traceRayEXT(sceneTlas,gl_RayFlagsNoneEXT,0,0,0,1,rayOrigin,1.0e-3,vec3(0,0,1),1.0,1);
    return gsShadowQuery.surfaceRadiance;
}
#endif

vec3 gsIntegrateMedium(vec3 color,GsMediumPath path,float distance,vec3 origin,vec3 direction) {
#ifdef RTRENDERER_SURFACE_ONLY
    return color;
#else
    if(!(distance>0.0)) return color;
    GsMediumCoefficients medium=gsCurrentMedium(path);
    vec3 extinction=max(medium.extinction*medium.density,vec3(0.0));
    vec3 emission=max(medium.emission*medium.density,vec3(0.0));
    if(all(equal(extinction,vec3(0.0))) && all(equal(emission,vec3(0.0)))) return color;
    // Homogeneous absorption/emission has an exact integral; no service dispatch is needed.
    if(all(lessThanEqual(medium.scattering*medium.density,vec3(1.0e-7)))) {
        distance=min(distance,uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_MEDIUM_MAX_DISTANCE_WORD]));
        vec3 transmittance=exp(-extinction*distance);
        vec3 integral=vec3(extinction.x>1.0e-7?(1.0-transmittance.x)/extinction.x:distance,
            extinction.y>1.0e-7?(1.0-transmittance.y)/extinction.y:distance,
            extinction.z>1.0e-7?(1.0-transmittance.z)/extinction.z:distance);
        return color*transmittance+emission*integral;
    }
    gsShadowQuery.operation=1u;gsShadowQuery.incidentRadiance=color;gsShadowQuery.path=path;
    gsShadowQuery.origin=origin;gsShadowQuery.direction=direction;gsShadowQuery.maximumDistance=distance;
    traceRayEXT(sceneTlas,gl_RayFlagsNoneEXT,0,0,0,1,origin,1.0e-3,direction,1.0,1);
    return gsShadowQuery.transmittance;
#endif
}

GpuScenePayload gsTraceRadiance(vec3 origin, vec3 direction, uvec4 launchInfo, GsMediumPath mediumPath)
{
    payload.state = uvec4(0u, GS_PAYLOAD_RADIANCE_QUERY, 0u, 0u);
    payload.launchInfo = launchInfo;
    gsLightingMediumPath=mediumPath;
    payload.mediumBoundary = uvec4(0u);
    traceRayEXT(sceneTlas, gl_RayFlagsNoneEXT, 0xff, 0, 0, 0,
        origin, 1.0e-3, direction, 1.0e20, 0);
    return payload;
}

vec3 gsEvaluateTerminalRadiance(GpuScenePayload surfaceSample, vec3 rayOrigin, vec3 rayDirection)
{
    if (surfaceSample.state.x == 0u) return surfaceSample.baseColorAndOpacity.rgb;
    if (gsLightmapModulated(surfaceSample)) {
        return gsApplyDistanceFog(
            surfaceSample.baseColorAndOpacity.rgb,
            surfaceSample.transmissionIor.z,
            surfaceSample.transmissionIor.w
        );
    }
    if (gsUnlit(surfaceSample)) {
        return max(surfaceSample.baseColorAndOpacity.rgb, vec3(0.0))
            + max(surfaceSample.emissiveAndMetallic.rgb, vec3(0.0));
    }

    vec3 surface = gsRadiance(gsEvaluatePbrSurface(surfaceSample, rayOrigin));
    float opacity = clamp(surfaceSample.baseColorAndOpacity.a, 0.0, 1.0);
    float transmission = clamp(surfaceSample.transmissionIor.x, 0.0, 1.0);
    return mix(gsSkyRadiance(rayDirection), surface, opacity * (1.0 - transmission));
}

vec3 gsResolveSurfaceOverlay(
    GpuScenePayload overlay,
    vec3 rayOrigin,
    vec3 rayDirection,
    uvec4 launchInfo,
    GsMediumPath mediumPath
) {
    vec3 overlayPosition = overlay.worldPositionAndDistance.xyz;
    vec3 receiverOrigin = overlayPosition + rayDirection * 1.0e-3;
    GpuScenePayload receiver = gsTraceRadiance(receiverOrigin, rayDirection, launchInfo, mediumPath);
    if (receiver.state.x == 0u) {
        return receiver.baseColorAndOpacity.rgb;
    }
    bool receives = (receiver.surfaceState.x & overlay.surfaceState.y) != 0u;
    float maximumGap = max(overlay.compositeState.y, 2.0e-3);
    if (!receives || receiver.worldPositionAndDistance.w > maximumGap) {
        return gsEvaluateTerminalRadiance(receiver, receiverOrigin, rayDirection);
    }
    vec3 receiverColor = gsEvaluateTerminalRadiance(receiver, receiverOrigin, rayDirection);
    vec3 overlayColor = gsEvaluateTerminalRadiance(overlay, rayOrigin, rayDirection);
    float alpha = clamp(overlay.baseColorAndOpacity.a, 0.0, 1.0);
    uint compositionMode = (overlay.state.z >> GPU_SCENE_OVERLAY_COMPOSITION_MODE_SHIFT)
        & GPU_SCENE_OVERLAY_COMPOSITION_MODE_MASK;
    if (compositionMode == GPU_SCENE_OVERLAY_COMPOSITION_MULTIPLY) {
        return receiverColor * mix(vec3(1.0), overlayColor, alpha);
    }
    return mix(receiverColor, overlayColor, alpha);
}


vec3 gsApplyObjectOutline(
    vec3 currentColor,
    GpuScenePayload center,
    vec3 rayOrigin,
    ivec2 pixel,
    ivec2 extent,
    vec3 forward,
    vec3 right,
    vec3 up,
    vec2 fov,
    uvec4 launchInfo,
    GsMediumPath mediumPath,
    out bool applied
) {
    applied = false;
    uint centerMask = center.surfaceState.z;
    float centerWidth = center.compositeState.x;
    // Outlines are deliberately inner-only. A background pixel must not launch four rays merely
    // to discover a neighboring outlined object; ordinary frames therefore pay zero outline rays.
    if (centerMask == 0u || centerWidth <= 0.0) return currentColor;
    float probeRadius = centerWidth;
    const vec2 directions[4] = vec2[](
        vec2(1.0, 0.0), vec2(-1.0, 0.0), vec2(0.0, 1.0), vec2(0.0, -1.0)
    );
    for (int index = 0; index < 4; ++index) {
        vec2 samplePosition = vec2(pixel) + vec2(0.5) + directions[index] * probeRadius;
        if (any(lessThan(samplePosition, vec2(0.0)))
                || any(greaterThanEqual(samplePosition, vec2(extent)))) continue;
        vec3 direction = gsRayDirectionAtSample(samplePosition, extent);
        GpuScenePayload neighbor = gsTraceRadiance(rayOrigin, direction, launchInfo, mediumPath);
        uint neighborMask = neighbor.surfaceState.z;
        if (neighborMask != centerMask) {
            vec4 outline = gsUnpackRgba8(center.surfaceState.w);
            applied = outline.a > 0.0;
            if (applied) return mix(currentColor, outline.rgb, outline.a);
        }
    }
    return currentColor;
}

float gsRadicalInverseVdc(uint bits)
{
    bits = (bits << 16u) | (bits >> 16u);
    bits = ((bits & 0x55555555u) << 1u) | ((bits & 0xAAAAAAAAu) >> 1u);
    bits = ((bits & 0x33333333u) << 2u) | ((bits & 0xCCCCCCCCu) >> 2u);
    bits = ((bits & 0x0F0F0F0Fu) << 4u) | ((bits & 0xF0F0F0F0u) >> 4u);
    bits = ((bits & 0x00FF00FFu) << 8u) | ((bits & 0xFF00FF00u) >> 8u);
    return float(bits) * 2.3283064365386963e-10;
}

vec2 gsReflectionSample(uint sampleIndex, uint sampleCount, uvec4 launchInfo)
{
    // A per-pixel Cranley-Patterson rotation removes coherent GGX bands while preserving
    // deterministic samples, which is required by temporal reconstruction.
    uint pixelHash = launchInfo.x * 0x9E3779B9u
        ^ launchInfo.y * 0x85EBCA6Bu
        ^ 0xC2B2AE35u;
    float rotation = gsRadicalInverseVdc(pixelHash);
    return vec2(
        (float(sampleIndex) + 0.5) / float(max(sampleCount, 1u)),
        fract(gsRadicalInverseVdc(sampleIndex) + rotation)
    );
}

vec3 gsSampleGgxHalfVector(vec3 normal, float roughness, vec2 samplePoint)
{
    float alpha = max(roughness * roughness, 0.0025);
    float alpha2 = alpha * alpha;
    float phi = 2.0 * GS_PI * samplePoint.x;
    float cosineTheta = sqrt(max(
        (1.0 - samplePoint.y) / (1.0 + (alpha2 - 1.0) * samplePoint.y),
        0.0
    ));
    float sineTheta = sqrt(max(1.0 - cosineTheta * cosineTheta, 0.0));
    vec3 tangent = normalize(abs(normal.z) < 0.999
        ? cross(vec3(0.0, 0.0, 1.0), normal)
        : cross(vec3(0.0, 1.0, 0.0), normal));
    vec3 bitangent = cross(normal, tangent);
    return normalize(
        tangent * (cos(phi) * sineTheta)
        + bitangent * (sin(phi) * sineTheta)
        + normal * cosineTheta
    );
}

vec3 gsOpaqueMetallicReflectionWeight(
    GpuScenePayload surfaceSample,
    vec3 incidentDirection,
    uvec4 launchInfo,
    uint sampleIndex,
    uint sampleCount,
    out vec3 reflectedDirection
) {
    reflectedDirection = vec3(0.0);
    float metallic = clamp(surfaceSample.emissiveAndMetallic.a, 0.0, 1.0);
    if (metallic <= 1.0e-4) return vec3(0.0);

    vec3 normal = normalize(surfaceSample.worldNormalAndRoughness.xyz);
    vec3 view = normalize(-incidentDirection);
    float normalView = max(dot(normal, view), 0.0);
    if (normalView <= 1.0e-5) return vec3(0.0);

    float roughness = clamp(surfaceSample.worldNormalAndRoughness.w, 0.025, 1.0);
    vec3 halfVector = gsSampleGgxHalfVector(
        normal,
        roughness,
        gsReflectionSample(sampleIndex, sampleCount, launchInfo)
    );
    float viewHalf = dot(view, halfVector);
    if (viewHalf <= 1.0e-5) return vec3(0.0);

    reflectedDirection = normalize(reflect(incidentDirection, halfVector));
    float normalLight = dot(normal, reflectedDirection);
    if (normalLight <= 1.0e-5) return vec3(0.0);

    vec3 baseColor = max(surfaceSample.baseColorAndOpacity.rgb, vec3(0.0));
    vec3 fresnel = gsFresnelSchlick(viewHalf, mix(vec3(0.04), baseColor, metallic));
    float geometry = gsGeometrySmith(normalView, normalLight, roughness);
    float normalHalf = max(dot(normal, halfVector), 1.0e-5);
    // This is f * N.L / pdf for GGX half-vector sampling. Unlike an arbitrary color add,
    // it preserves Fresnel and masking/shadowing energy terms and remains one-bounce bounded.
    vec3 sampleWeight = metallic * fresnel * geometry * viewHalf
        / max(normalView * normalHalf, 1.0e-5);
    return sampleWeight;
}

float gsOpticalRandom(uvec4 launchInfo,uint seed,uint event) {
    uint value=launchInfo.x*0x9e3779b9u^launchInfo.y*0x85ebca6bu^seed*0xc2b2ae35u^event*0x27d4eb2du;
    value^=value>>16u;value*=0x7feb352du;value^=value>>15u;
    return float(value>>8u)*(1.0/16777216.0);
}

vec3 gsOpticalSurface(GpuScenePayload surfaceSample,vec3 origin) {
    if(gsLightmapModulated(surfaceSample)) return gsApplyDistanceFog(surfaceSample.baseColorAndOpacity.rgb,
        surfaceSample.transmissionIor.z,surfaceSample.transmissionIor.w);
    if(gsUnlit(surfaceSample)) return max(surfaceSample.baseColorAndOpacity.rgb,vec3(0.0))
        +max(surfaceSample.emissiveAndMetallic.rgb,vec3(0.0));
    return gsRadiance(gsEvaluatePbrSurface(surfaceSample,origin));
}

vec3 gsOpticalContinuation(vec3 origin,vec3 direction,uvec4 launchInfo,GsMediumPath path,uint seed,out float firstHitDistance) {
    vec3 radiance=vec3(0.0),throughput=vec3(1.0);
    firstHitDistance=0.0;
    uint events=clamp(gsFrame.words[GPU_SCENE_FRAME_OPTICAL_MAX_EVENTS_WORD],1u,32u);
    float maximumDistance=uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_MEDIUM_MAX_DISTANCE_WORD]);
    [[dont_unroll]] for(uint event=0u;event<events;++event) {
        GpuScenePayload hit=gsTraceRadiance(origin,direction,launchInfo,path);
        if(event==0u) firstHitDistance=hit.state.x==0u?65504.0:max(hit.worldPositionAndDistance.w,0.0);
        float distance=hit.state.x==0u?maximumDistance:min(hit.worldPositionAndDistance.w,maximumDistance);
        radiance+=throughput*gsIntegrateMedium(vec3(0.0),path,distance,origin,direction);
        GsMediumCoefficients medium=gsCurrentMedium(path);
        throughput*=exp(-max(medium.extinction*medium.density,vec3(0.0))*distance);
        if(hit.state.x==0u) {
            radiance+=throughput*gsSkyRadiance(direction);break;
        }
        bool volumeOnly=(hit.mediumBoundary.w&2u)!=0u;
        float opacity=hit.mediumBoundary.w!=0u?1.0:clamp(hit.baseColorAndOpacity.a,0.0,1.0);
        float transmission=volumeOnly?1.0:clamp(hit.transmissionIor.x,0.0,1.0);
        if(!volumeOnly && opacity*(1.0-transmission)>0.0) {
            gsLightingMediumPath=path;
            if(gsSurfaceOverlay(hit)) {radiance+=throughput*gsResolveSurfaceOverlay(hit,origin,direction,launchInfo,path);break;}
            radiance+=throughput*opacity*(1.0-transmission)*gsOpticalSurface(hit,origin);
        }
        float passWeight=1.0-opacity+opacity*transmission;
        throughput*=passWeight;
        if(max(throughput.r,max(throughput.g,throughput.b))<1.0e-6) break;
        GsMediumPath next=gsCrossMediumBoundary(path,hit);
        if(next.depth>GS_MEDIUM_PATH_CAPACITY) break;
        vec3 normal=normalize(hit.worldNormalAndRoughness.xyz);
        if(volumeOnly) path=next;
        else {
            vec2 iors=gsInterfaceIors(hit,path,next);
            float incidentIor=iors.x,transmittedIor=iors.y;
            float eta=incidentIor/transmittedIor;
            vec3 refracted=refract(direction,normal,eta);
            float fresnel=gsDielectricFresnel(incidentIor,transmittedIor,clamp(dot(-direction,normal),0.0,1.0));
            if(dot(refracted,refracted)<=1.0e-8 || gsOpticalRandom(launchInfo,seed,event)<fresnel) direction=normalize(reflect(direction,normal));
            else {direction=normalize(refracted);path=next;throughput*=clamp(hit.baseColorAndOpacity.rgb,vec3(0.0),vec3(1.0));}
        }
        origin=hit.worldPositionAndDistance.xyz+direction*1.0e-3;
    }
    return radiance;
}

// All secondary transport shares one continuation call site. Keeping its ray/medium/light loops
// out of separate reflection/refraction branches prevents driver inlining from multiplying them.
vec3 gsEvaluateSecondaryTransport(GpuScenePayload surfaceSample,vec3 incidentDirection,uvec4 launchInfo,
        GsMediumPath incidentPath,uint primarySampleIndex,uint primarySampleCount,out float reflectionHitDistance) {
    reflectionHitDistance=0.0;
    bool volumeOnly=(surfaceSample.mediumBoundary.w&2u)!=0u;
    bool optical=volumeOnly || surfaceSample.transmissionIor.x>1.0e-4;
    if(!optical && surfaceSample.emissiveAndMetallic.a<=1.0e-4) return vec3(0.0);
    vec3 position=surfaceSample.worldPositionAndDistance.xyz;
    vec3 normal=normalize(surfaceSample.worldNormalAndRoughness.xyz);
    GsMediumPath transmittedPath=optical?gsCrossMediumBoundary(incidentPath,surfaceSample):incidentPath;
    if(transmittedPath.depth>GS_MEDIUM_PATH_CAPACITY) return vec3(0.0);
    vec2 iors=gsInterfaceIors(surfaceSample,incidentPath,transmittedPath);
    float incidentIor=iors.x,transmittedIor=iors.y;
    uint samples=optical && !volumeOnly?clamp(gsFrame.words[GPU_SCENE_FRAME_OPTICAL_SAMPLES_WORD],1u,8u):1u;
    vec3 total=vec3(0.0);
    [[dont_unroll]] for(uint sampleIndex=0u;sampleIndex<samples;++sampleIndex) {
        vec3 interfaceNormal=normal;
        float roughness=clamp(surfaceSample.worldNormalAndRoughness.w,0.0,1.0);
        if(optical && !volumeOnly && roughness>.05 && dot(-incidentDirection,normal)>.001) {
            vec3 candidate=gsSampleGgxHalfVector(normal,roughness,gsReflectionSample(sampleIndex,samples,launchInfo));
            if(dot(-incidentDirection,candidate)>.001) interfaceNormal=candidate;
        }
        float eta=incidentIor/transmittedIor;
        vec3 reflectedDirection=normalize(reflect(incidentDirection,interfaceNormal));
        vec3 refractedDirection=refract(incidentDirection,interfaceNormal,eta);
        float fresnel=gsDielectricFresnel(incidentIor,transmittedIor,clamp(dot(-incidentDirection,interfaceNormal),0.0,1.0));
        bool totalReflection=dot(refractedDirection,refractedDirection)<=1.0e-8;
        if(!totalReflection) refractedDirection=normalize(refractedDirection);
        uint branches=optical && !volumeOnly?2u:1u;
        [[dont_unroll]] for(uint branch=0u;branch<branches;++branch) {
            bool reflection=branch==0u;
            float weight=reflection?(totalReflection?1.0:fresnel):(totalReflection?0.0:1.0-fresnel);
            vec3 outgoing=reflection?reflectedDirection:refractedDirection;
            GsMediumPath next=reflection?incidentPath:transmittedPath;
            vec3 tint=reflection?vec3(1.0):clamp(surfaceSample.baseColorAndOpacity.rgb,vec3(0.0),vec3(1.0));
            if(volumeOnly) {outgoing=incidentDirection;next=transmittedPath;weight=1.0;tint=vec3(1.0);}
            else if(!optical) {
                tint=gsOpaqueMetallicReflectionWeight(surfaceSample,incidentDirection,launchInfo,
                    primarySampleIndex,primarySampleCount,outgoing);
                weight=1.0;
            }
            if(weight<=1.0e-6 || max(tint.r,max(tint.g,tint.b))<=1.0e-6) continue;
            float firstHitDistance;
            vec3 transported=gsOpticalContinuation(position+outgoing*.001,outgoing,launchInfo,next,
                sampleIndex*2u+branch+1u,firstHitDistance);
            if(!optical) reflectionHitDistance=firstHitDistance;
            total+=weight*tint*transported;
        }
    }
    return total/float(samples);
}

vec2 gsSubpixelOffset(uint sampleIndex, uint sampleCount)
{
    if (sampleCount <= 1u) return vec2(0.5);
    if (sampleCount == 2u) {
        const vec2 samples[2] = vec2[](vec2(0.25, 0.25), vec2(0.75, 0.75));
        return samples[sampleIndex];
    }
    if (sampleCount == 4u) {
        const vec2 samples[4] = vec2[](
            vec2(0.375, 0.125), vec2(0.875, 0.375),
            vec2(0.125, 0.625), vec2(0.625, 0.875)
        );
        return samples[sampleIndex];
    }
    const vec2 samples[8] = vec2[](
        vec2(0.5000, 0.3333), vec2(0.2500, 0.6667),
        vec2(0.7500, 0.1111), vec2(0.1250, 0.4444),
        vec2(0.6250, 0.7778), vec2(0.3750, 0.2222),
        vec2(0.8750, 0.5556), vec2(0.0625, 0.8889)
    );
    return samples[sampleIndex];
}

vec3 gsCameraDelta()
{
    uint word = GPU_SCENE_FRAME_CAMERA_DELTA_WORD;
    return vec3(
        gsFiniteDoubleAsFloat(gsFrame.words[word], gsFrame.words[word + 1u]),
        gsFiniteDoubleAsFloat(gsFrame.words[word + 2u], gsFrame.words[word + 3u]),
        gsFiniteDoubleAsFloat(gsFrame.words[word + 4u], gsFrame.words[word + 5u])
    );
}

vec2 gsPreviousJitter()
{
    return vec2(
        uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_PREVIOUS_JITTER_WORD]),
        uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_PREVIOUS_JITTER_WORD + 1u])
    );
}

vec3 gsPreviousCameraPosition()
{
    uint word = GPU_SCENE_FRAME_PREVIOUS_CAMERA_POSITION_WORD;
    return vec3(
        gsFiniteDoubleAsFloat(gsFrame.words[word], gsFrame.words[word + 1u]),
        gsFiniteDoubleAsFloat(gsFrame.words[word + 2u], gsFrame.words[word + 3u]),
        gsFiniteDoubleAsFloat(gsFrame.words[word + 4u], gsFrame.words[word + 5u])
    );
}

bool gsProjectWorldPosition(
    vec3 worldPosition,
    vec3 cameraPosition,
    vec3 cameraForward,
    vec3 cameraRight,
    vec3 cameraUp,
    vec2 cameraFov,
    ivec2 extent,
    out vec2 pixel,
    out float logDepth
) {
    vec3 relative = worldPosition - cameraPosition;
    vec3 previousForward = normalize(cameraForward);
    vec3 previousRight = normalize(cameraRight);
    vec3 previousUp = normalize(cameraUp);
    float forwardDistance = dot(relative, previousForward);
    if (forwardDistance <= 1.0e-4 || any(lessThanEqual(cameraFov, vec2(1.0e-6)))) {
        pixel = vec2(0.0);
        logDepth = 0.0;
        return false;
    }
    vec2 previousNdc = vec2(
        dot(relative, previousRight) / (forwardDistance * cameraFov.x),
        -dot(relative, previousUp) / (forwardDistance * cameraFov.y)
    );
    pixel = (previousNdc * 0.5 + 0.5) * vec2(extent);
    logDepth = log2(1.0 + length(relative));
    return all(greaterThanEqual(pixel, vec2(0.0)))
        && all(lessThan(pixel, vec2(extent)));
}

vec2 gsPreviousCameraFov()
{
    return vec2(
        uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_PREVIOUS_FOV_WORD]),
        uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_PREVIOUS_FOV_WORD + 1u])
    );
}

vec2 gsCurrentCameraFov()
{
    return vec2(
        uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_FOV_WORD]),
        uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_FOV_WORD + 1u])
    );
}

bool gsPreviousPixelPositionWorld(
    vec3 worldPosition,
    ivec2 extent,
    out vec2 previousPixel,
    out float expectedPreviousLogDepth
) {
    vec2 noJitter;
    if (!gsProjectWorldPosition(
            worldPosition,
            gsPreviousCameraPosition(),
            gsFrameVec3(GPU_SCENE_FRAME_PREVIOUS_CAMERA_FORWARD_WORD),
            gsFrameVec3(GPU_SCENE_FRAME_PREVIOUS_CAMERA_RIGHT_WORD),
            gsFrameVec3(GPU_SCENE_FRAME_PREVIOUS_CAMERA_UP_WORD),
            gsPreviousCameraFov(), extent, noJitter, expectedPreviousLogDepth)) {
        previousPixel = vec2(0.0);
        return false;
    }
    previousPixel = noJitter - gsPreviousJitter();
    return all(greaterThanEqual(previousPixel, vec2(0.0)))
        && all(lessThan(previousPixel, vec2(extent)));
}

bool gsPreviousPixelPositionNoJitter(
    vec3 worldPosition,
    ivec2 extent,
    out vec2 previousPixel,
    out float expectedPreviousLogDepth
) {
    return gsProjectWorldPosition(
        worldPosition,
        gsPreviousCameraPosition(),
        gsFrameVec3(GPU_SCENE_FRAME_PREVIOUS_CAMERA_FORWARD_WORD),
        gsFrameVec3(GPU_SCENE_FRAME_PREVIOUS_CAMERA_RIGHT_WORD),
        gsFrameVec3(GPU_SCENE_FRAME_PREVIOUS_CAMERA_UP_WORD),
        gsPreviousCameraFov(), extent, previousPixel, expectedPreviousLogDepth
    );
}

bool gsCurrentPixelPositionNoJitter(
    vec3 worldPosition,
    ivec2 extent,
    out vec2 currentPixel
) {
    float ignoredDepth;
    return gsProjectWorldPosition(
        worldPosition,
        gsFrameCameraPosition(),
        gsFrameVec3(GPU_SCENE_FRAME_CAMERA_FORWARD_WORD),
        gsFrameVec3(GPU_SCENE_FRAME_CAMERA_RIGHT_WORD),
        gsFrameVec3(GPU_SCENE_FRAME_CAMERA_UP_WORD),
        gsCurrentCameraFov(), extent, currentPixel, ignoredDepth
    );
}

vec3 gsTemporalPreviousWorldPosition(GpuScenePayload primary)
{
    // Frame-local records are already tied to the admitted frame slot. Their explicit previous
    // transform is authoritative and must not be filtered through persistent scene revisions.
    if ((primary.state.w & GPU_SCENE_FLAG_FRAME_LOCAL) != 0u) {
        return primary.previousWorldPosition.w > 0.5
            ? primary.previousWorldPosition.xyz
            : primary.worldPositionAndDistance.xyz;
    }
    uint currentLow = gsFrame.words[GPU_SCENE_FRAME_SCENE_REVISION_WORD];
    uint currentHigh = gsFrame.words[GPU_SCENE_FRAME_SCENE_REVISION_WORD + 1u];
    uint previousLow = gsFrame.words[GPU_SCENE_FRAME_PREVIOUS_SCENE_REVISION_WORD];
    uint previousHigh = gsFrame.words[GPU_SCENE_FRAME_PREVIOUS_SCENE_REVISION_WORD + 1u];
    uint64_t currentRevision = gsU64(currentLow, currentHigh);
    uint64_t previousRevision = gsU64(previousLow, previousHigh);
    uint64_t motionRevision = gsU64(primary.motionRevision.x, primary.motionRevision.y);
    // A sparse instance record can outlive unrelated material/light revisions. Consume its prior
    // transform exactly once: only when this instance was updated after the source frame.
    bool instanceMoved = motionRevision > previousRevision && motionRevision <= currentRevision;
    return instanceMoved && primary.previousWorldPosition.w > 0.5
        ? primary.previousWorldPosition.xyz
        : primary.worldPositionAndDistance.xyz;
}

vec4 gsTemporalGeometry(GpuScenePayload primary)
{
    if (primary.state.x == 0u) return vec4(0.0);
    vec3 normal = normalize(primary.worldNormalAndRoughness.xyz);
    float logDepth = log2(1.0 + max(primary.worldPositionAndDistance.w, 0.0));
    return vec4(normal * 0.5 + 0.5, max(logDepth, 1.0e-4));
}

bool gsTemporalGeometryMatches(
    vec4 currentGeometry,
    vec4 previousGeometry,
    float expectedPreviousLogDepth
)
{
    if (currentGeometry.a <= 0.0 || previousGeometry.a <= 0.0) return false;
    vec3 currentNormal = normalize(currentGeometry.rgb * 2.0 - 1.0);
    vec3 previousNormal = normalize(previousGeometry.rgb * 2.0 - 1.0);
    float depthTolerance = max(0.025, expectedPreviousLogDepth * 0.02);
    return dot(currentNormal, previousNormal) >= 0.85
        && abs(expectedPreviousLogDepth - previousGeometry.a) <= depthTolerance;
}

vec3 gsResolveTemporal(
    vec3 currentColor,
    vec4 historyColor,
    vec2 motion,
    ivec2 extent,
    uint maxHistoryFrames,
    out float historyConfidence
) {
    float maximumAge = float(max(maxHistoryFrames, 1u));
    float historyAge = clamp(historyColor.a, 1.0, maximumAge);
    float boundedWeight = min(
        historyAge / (historyAge + 1.0),
        (maximumAge - 1.0) / maximumAge
    );
    float motionPixels = length(motion * vec2(extent));
    historyConfidence = exp(-motionPixels * 0.05);
    float historyWeight = boundedWeight * historyConfidence;
    vec3 clipRadius = max(abs(currentColor) * 0.35, vec3(0.025));
    vec3 clippedHistory = clamp(historyColor.rgb, currentColor - clipRadius, currentColor + clipRadius);
    return mix(currentColor, clippedHistory, clamp(historyWeight, 0.0, boundedWeight));
}

bool gsDenoisingActive()
{
    return (gsFrame.words[GPU_SCENE_FRAME_FEATURE_FLAGS_WORD]
        & GPU_SCENE_FEATURE_FLAG_DENOISING_ACTIVE) != 0u;
}

// NRD treats viewZ values below CommonSettings::denoisingRange as valid surfaces. Keep miss
// pixels outside that range so NRD and the compose pass cannot reuse stale surface history for sky.
const float GS_NRD_MISS_VIEW_Z = 1.0e6;

bool gsReconstructionActive()
{
    return (gsFrame.words[GPU_SCENE_FRAME_FEATURE_FLAGS_WORD]
        & GPU_SCENE_FEATURE_FLAG_RECONSTRUCTION_ACTIVE) != 0u;
}

float gsReconstructionDepth(float viewZ)
{
    float nearPlane = uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_RECONSTRUCTION_NEAR_PLANE_WORD]);
    float farPlane = uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_RECONSTRUCTION_FAR_PLANE_WORD]);
    // The native Streamline constants use the matching unjittered Vulkan [0,1] projection.
    return clamp(farPlane / (farPlane - nearPlane)
        - (farPlane * nearPlane) / ((farPlane - nearPlane) * max(viewZ, nearPlane)), 0.0, 1.0);
}

vec4 gsNrdPackNormalRoughness(vec3 normal, float roughness)
{
    // Matches NRD_NORMAL_ENCODING=2 and NRD_ROUGHNESS_ENCODING=1 in the native NRD build.
    vec3 octahedron = normal / max(abs(normal.x) + abs(normal.y) + abs(normal.z), 1.0e-6);
    vec3 packed;
    packed.y = octahedron.y * 0.5 + 0.5;
    packed.x = octahedron.x * 0.5 + packed.y;
    packed.y -= octahedron.x * 0.5;
    float signedRoughness = octahedron.z < 0.0
        ? -max(roughness, 1.5 / 512.0)
        : max(roughness, 1.5 / 512.0);
    packed.z = signedRoughness * 0.5 + 0.5;
    return vec4(packed, 0.0);
}

vec3 gsNrdEnvironmentTerm(vec3 reflectance, float normalView, float roughness)
{
    float m = clamp(roughness * roughness, 0.0, 1.0);
    vec4 x = vec4(1.0, normalView, normalView * normalView,
        normalView * normalView * normalView);
    vec4 y = vec4(1.0, m, m * m, m * m * m);
    vec2 m1x = vec2(
        0.99044 * x.x - 1.28514 * x.y,
        1.29678 * x.x - 0.755907 * x.y
    );
    vec3 m2x = vec3(
        1.0 * x.x + 2.92338 * x.y + 59.4188 * x.w,
        20.3225 * x.x - 27.0302 * x.y + 222.592 * x.w,
        121.563 * x.x + 626.13 * x.y + 316.627 * x.w
    );
    vec2 m3x = vec2(
        0.0365463 * x.x + 3.32707 * x.y,
        9.0632 * x.x - 9.04756 * x.y
    );
    vec3 m4x = vec3(
        1.0 * x.x + 3.59685 * x.z - 1.36772 * x.w,
        9.04401 * x.x - 16.3174 * x.z + 9.22949 * x.w,
        5.56589 * x.x + 19.7886 * x.z - 20.2123 * x.w
    );
    float bias = dot(m1x, y.xy) / max(dot(m2x, y.xyw), 1.0e-6);
    float scale = dot(m3x, y.xy) / max(dot(m4x, y.xyw), 1.0e-6);
    return clamp(reflectance * scale + bias, vec3(0.0), vec3(1.0));
}

void gsNrdMaterialFactors(
    GpuScenePayload primary,
    vec3 rayOrigin,
    out vec3 diffuseFactor,
    out vec3 specularFactor
) {
    vec3 normal = normalize(primary.worldNormalAndRoughness.xyz);
    vec3 view = normalize(rayOrigin - primary.worldPositionAndDistance.xyz);
    vec3 baseColor = max(primary.baseColorAndOpacity.rgb, vec3(0.0));
    float metallic = clamp(primary.emissiveAndMetallic.a, 0.0, 1.0);
    float roughness = clamp(primary.worldNormalAndRoughness.w, 0.025, 1.0);
    vec3 albedo = baseColor * (1.0 - metallic);
    vec3 reflectance = mix(vec3(0.04), baseColor, metallic);
    vec3 environment = gsNrdEnvironmentTerm(
        reflectance, abs(dot(normal, view)), roughness
    );
    // Exact NRD_MaterialFactors clamps prevent demodulation from amplifying near-zero lobes.
    diffuseFactor = mix(vec3(0.02), vec3(1.0), (vec3(1.0) - environment) * albedo);
    specularFactor = environment * mix(vec3(0.1), vec3(1.0), roughness);
    specularFactor = mix(vec3(0.02), vec3(1.0), specularFactor);
}

float gsNrdNormalizedHitDistance(float hitDistance, float viewZ, float roughness)
{
    // REBLUR defaults: ReblurHitDistanceParameters{A=3, B=0.1, C=20}.
    float spread = 1.0 - exp2(-200.0 * roughness * roughness);
    spread *= sqrt(clamp(roughness, 0.0, 1.0));
    float normalization = (3.0 + abs(viewZ) * 0.1) * mix(20.0, 1.0, spread);
    return clamp(max(hitDistance, 0.0) / max(normalization, 1.0e-6), 0.0, 1.0);
}

vec4 gsNrdPackRadianceHitDistance(
    vec3 radiance,
    float hitDistance,
    float viewZ,
    float roughness
) {
    vec3 sanitized = any(isnan(radiance)) || any(isinf(radiance))
        ? vec3(0.0)
        : clamp(radiance, vec3(0.0), vec3(65504.0));
    vec3 ycocg = vec3(
        dot(sanitized, vec3(0.25, 0.5, 0.25)),
        dot(sanitized, vec3(0.5, 0.0, -0.5)),
        dot(sanitized, vec3(-0.25, 0.5, -0.25))
    );
    return vec4(ycocg, gsNrdNormalizedHitDistance(hitDistance, viewZ, roughness));
}

void main()
{
    ivec2 extent = imageSize(outputImage);
    ivec2 pixel = ivec2(gl_LaunchIDEXT.xy);
    if (any(greaterThanEqual(pixel, extent))) return;

    vec3 origin = gsFrameCameraPosition();
    vec3 forward = normalize(gsFrameVec3(GPU_SCENE_FRAME_CAMERA_FORWARD_WORD));
    vec3 right = normalize(gsFrameVec3(GPU_SCENE_FRAME_CAMERA_RIGHT_WORD));
    vec3 up = normalize(gsFrameVec3(GPU_SCENE_FRAME_CAMERA_UP_WORD));
    vec2 fov = vec2(
        uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_FOV_WORD]),
        uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_FOV_WORD + 1u])
    );
    uint sampleCount = clamp(gsFrame.words[GPU_SCENE_FRAME_SAMPLE_COUNT_WORD], 1u, 8u);
    uvec4 launchInfo = uvec4(uint(pixel.x), uint(pixel.y), uint(extent.x), uint(extent.y));
    bool denoisingActive = gsDenoisingActive();
    bool reconstructionActive = gsReconstructionActive();
    vec3 accumulated = vec3(0.0);
    GsRadianceSplit denoisingSplit = gsRadianceSplit(vec3(0.0), vec3(0.0));
    float denoisingDiffuseHitDistance = 0.0;
    float denoisingSpecularHitDistance = 0.0;
    vec3 denoisingDiffuseFactor = vec3(1.0);
    vec3 denoisingSpecularFactor = vec3(1.0);
    bool denoisingPrimary = false;
    GpuScenePayload representative;
    uint lightmapSampleCount = 0u;
    vec3 representativeRayDirection = vec3(0.0);
    vec2 representativeSamplePosition = vec2(pixel) + vec2(0.5);
    vec2 currentJitter = vec2(
        uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_CURRENT_JITTER_WORD]),
        uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_CURRENT_JITTER_WORD + 1u])
    );
    GsMediumPath cameraMediumPath = gsCameraMediumPath();
    for (uint sampleIndex = 0u; sampleIndex < 8u; ++sampleIndex) {
        if (sampleIndex >= sampleCount) break;
        vec2 samplePosition = vec2(pixel) + gsSubpixelOffset(sampleIndex, sampleCount);
        if (!gsExactProjectionActive()) samplePosition += currentJitter;
        vec3 rayDirection = gsRayDirectionAtSample(samplePosition, extent);
#ifdef RTRENDERER_SER
        // Reorder by direction before the primary trace fans out into material-specific paths.
        vec3 absoluteDirection = abs(rayDirection);
        uint dominantAxis = absoluteDirection.x >= absoluteDirection.y
                && absoluteDirection.x >= absoluteDirection.z ? 0u
                : (absoluteDirection.y >= absoluteDirection.z ? 1u : 2u);
        uint directionOctant = (rayDirection.x < 0.0 ? 1u : 0u)
                | (rayDirection.y < 0.0 ? 2u : 0u)
                | (rayDirection.z < 0.0 ? 4u : 0u);
        reorderThreadNV(directionOctant | (dominantAxis << 3u), 5u);
#endif
        GsRadianceSplit previousDenoisingSplit = denoisingSplit;
        GpuScenePayload primary = gsTraceRadiance(origin, rayDirection, launchInfo, cameraMediumPath);
        GsMediumPath primaryMediumPath = cameraMediumPath;
        if (sampleIndex == 0u) {
            representative = primary;
            representativeRayDirection = rayDirection;
            representativeSamplePosition = samplePosition;
        }

        vec3 radiance;
        bool lightmapModulated = gsLightmapModulated(primary);
        if (lightmapModulated) lightmapSampleCount++;
        if (primary.state.x == 0u) {
            radiance = primary.baseColorAndOpacity.rgb;
        } else if (gsSurfaceOverlay(primary)) {
            radiance = gsResolveSurfaceOverlay(primary, origin, rayDirection, launchInfo, primaryMediumPath);
        } else if (lightmapModulated && primary.transmissionIor.x<=0.0 && primary.mediumBoundary.w==0u) {
            // Host-compatible materials already contain texture, vertex tint, AO and lightmap.
            // Running them through the PBR light loop or ACES would light and curve them twice.
            radiance = gsApplyDistanceFog(
                primary.baseColorAndOpacity.rgb,
                primary.transmissionIor.z,
                primary.transmissionIor.w
            );
        } else if (gsUnlit(primary) && primary.transmissionIor.x<=0.0 && primary.mediumBoundary.w==0u) {
            radiance = gsEvaluateTerminalRadiance(primary, origin, rayDirection);
        } else {
            float distance = primary.worldPositionAndDistance.w;
            float opacity = primary.mediumBoundary.w!=0u?1.0:clamp(primary.baseColorAndOpacity.a, 0.0, 1.0);
            float transmission = (primary.mediumBoundary.w&2u)!=0u?1.0:clamp(primary.transmissionIor.x, 0.0, 1.0);
            GsRadianceSplit surfaceSplit = (primary.mediumBoundary.w&2u)!=0u || transmission>=1.0
                ?gsRadianceSplit(vec3(0.0),vec3(0.0))
                :(gsLightmapModulated(primary)||gsUnlit(primary)
                    ?gsRadianceSplit(gsOpticalSurface(primary,origin),vec3(0.0)):gsEvaluatePbrSurface(primary, origin));
            vec3 surface = gsRadiance(surfaceSplit);
            vec3 background = gsSkyRadiance(rayDirection);
            float opaqueReflectionHitDistance;
            vec3 secondary = gsEvaluateSecondaryTransport(primary,rayDirection,launchInfo,primaryMediumPath,
                sampleIndex,sampleCount,opaqueReflectionHitDistance);
            vec3 opaqueReflection = transmission <= 1.0e-4 && opacity >= 0.999
                ? secondary
                : vec3(0.0);
            if (!(transmission <= 1.0e-4 && opacity >= 0.999)) {
                opaqueReflectionHitDistance = 0.0;
            }
            vec3 optical = transmission > 0.0
                ? secondary
                : background;
            vec3 material = mix(surface + opaqueReflection, optical, transmission);
            radiance = mix(background, material, opacity);
            if (denoisingActive && sampleIndex == 0u) {
                float surfaceWeight = opacity * (1.0 - transmission);
                float opticalWeight = opacity * transmission;
                denoisingSplit.diffuse = surfaceSplit.diffuse * surfaceWeight;
                denoisingSplit.specular = (surfaceSplit.specular + opaqueReflection) * surfaceWeight
                    + optical * opticalWeight;
                denoisingDiffuseHitDistance = surfaceSplit.diffuseHitDistance;
                denoisingSpecularHitDistance = gsLuminance(opaqueReflection) > 1.0e-6
                    ? opaqueReflectionHitDistance
                    : surfaceSplit.specularHitDistance;
                gsNrdMaterialFactors(
                    primary, origin, denoisingDiffuseFactor, denoisingSpecularFactor
                );
                denoisingPrimary = true;
            } else if (denoisingActive && denoisingPrimary) {
                // Average every SPP contribution using the representative (first-hit) material
                // factors. NRD is fed one demodulated pixel signal, not only sample zero.
                float surfaceWeight = opacity * (1.0 - transmission);
                float opticalWeight = opacity * transmission;
                denoisingSplit.diffuse += surfaceSplit.diffuse * surfaceWeight;
                denoisingSplit.specular += (surfaceSplit.specular + opaqueReflection) * surfaceWeight
                    + optical * opticalWeight;
            }
        }
        float segmentDistance = primary.state.x == 0u
                ? uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_MEDIUM_MAX_DISTANCE_WORD])
                : max(primary.worldPositionAndDistance.w, 0.0);
        vec3 unattenuatedRadiance=radiance;
        radiance = gsIntegrateMedium(radiance, primaryMediumPath, segmentDistance, origin, rayDirection);
        if (denoisingActive && denoisingPrimary) {
            GsMediumCoefficients cameraMedium=gsCurrentMedium(primaryMediumPath);
            vec3 mediumTransmittance=exp(-max(cameraMedium.extinction*cameraMedium.density,vec3(0.0))
                *min(segmentDistance,uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_MEDIUM_MAX_DISTANCE_WORD])));
            vec3 mediumSource=max(radiance-unattenuatedRadiance*mediumTransmittance,vec3(0.0));
            denoisingSplit.diffuse=previousDenoisingSplit.diffuse
                +(denoisingSplit.diffuse-previousDenoisingSplit.diffuse)*mediumTransmittance;
            denoisingSplit.specular=previousDenoisingSplit.specular
                +(denoisingSplit.specular-previousDenoisingSplit.specular)*mediumTransmittance+mediumSource;
        }
        accumulated += radiance;
    }
    vec3 currentColor = accumulated / float(sampleCount);
    if (denoisingActive && denoisingPrimary) {
        denoisingSplit.diffuse /= float(sampleCount) * denoisingDiffuseFactor;
        denoisingSplit.specular /= float(sampleCount) * denoisingSpecularFactor;
    } else {
        denoisingSplit = gsRadianceSplit(vec3(0.0), vec3(0.0));
    }
    bool outlineApplied;
    currentColor = gsApplyObjectOutline(
        currentColor,
        representative,
        origin,
        pixel,
        extent,
        forward,
        right,
        up,
        fov,
        launchInfo,
        cameraMediumPath,
        outlineApplied
    );
    if (denoisingActive) {
        vec3 denoisingNormal = denoisingPrimary
            ? normalize(representative.worldNormalAndRoughness.xyz)
            : vec3(0.0, 0.0, 1.0);
        float denoisingRoughness = denoisingPrimary
            ? clamp(representative.worldNormalAndRoughness.w, 0.025, 1.0)
            : 1.0;
        float viewZ = denoisingPrimary
            ? max(dot(representative.worldPositionAndDistance.xyz - origin, forward), 0.0)
            : GS_NRD_MISS_VIEW_Z;
        vec2 previousPixelNoJitter;
        vec2 currentPixelNoJitter;
        float ignoredPreviousDepth;
        vec3 previousWorldPosition = gsTemporalPreviousWorldPosition(representative);
        bool reprojected = denoisingPrimary
            && gsPreviousPixelPositionNoJitter(
                previousWorldPosition, extent, previousPixelNoJitter, ignoredPreviousDepth
            )
            && gsCurrentPixelPositionNoJitter(
                representative.worldPositionAndDistance.xyz, extent, currentPixelNoJitter
            );
        // NRD's contract is MV = previous - current in non-jittered pixel units. Projecting the
        // actual hit point in both camera states removes both temporal jitter and multi-sample
        // subpixel offsets; subtracting sample coordinates directly leaks both into the signal.
        vec2 motionPixels = reprojected
            ? previousPixelNoJitter - currentPixelNoJitter
            : vec2(0.0);
        float previousViewZ = reprojected
            ? max(dot(previousWorldPosition - gsPreviousCameraPosition(),
                normalize(gsFrameVec3(GPU_SCENE_FRAME_PREVIOUS_CAMERA_FORWARD_WORD))), 0.0)
            : viewZ;
        imageStore(denoisingNormalRoughness, pixel,
            gsNrdPackNormalRoughness(denoisingNormal, denoisingRoughness));
        imageStore(denoisingViewZ, pixel, vec4(viewZ, 0.0, 0.0, 0.0));
        imageStore(denoisingMotionVectors, pixel,
            vec4(motionPixels, previousViewZ - viewZ, 0.0));
        imageStore(denoisingDiffuseRadianceHitDistance, pixel,
            gsNrdPackRadianceHitDistance(
                denoisingSplit.diffuse,
                denoisingDiffuseHitDistance,
                viewZ,
                denoisingRoughness
            ));
        imageStore(denoisingSpecularRadianceHitDistance, pixel,
            gsNrdPackRadianceHitDistance(
                denoisingSplit.specular,
                denoisingSpecularHitDistance,
                viewZ,
                denoisingRoughness
            ));
        imageStore(denoisingDiffuseMaterialFactor, pixel, vec4(denoisingDiffuseFactor, 1.0));
        imageStore(denoisingSpecularMaterialFactor, pixel, vec4(denoisingSpecularFactor, 1.0));
    }
    if (reconstructionActive) {
        float reconstructionViewZ = representative.state.x != 0u
            ? max(dot(representative.worldPositionAndDistance.xyz - origin, forward), 0.0)
            : 0.0;
        float ignoredPreviousDepth;
        vec2 previousPixelNoJitter;
        vec2 currentPixelNoJitter;
        bool reprojected = representative.state.x != 0u
            && gsPreviousPixelPositionNoJitter(
                gsTemporalPreviousWorldPosition(representative),
                extent, previousPixelNoJitter, ignoredPreviousDepth
            )
            && gsCurrentPixelPositionNoJitter(
                representative.worldPositionAndDistance.xyz, extent, currentPixelNoJitter
            );
        // Streamline receives pixel-space previous-current vectors; its constants normalize them
        // once. This path deliberately shares the exact no-jitter projection used by NRD.
        vec2 motionPixels = reprojected
            ? previousPixelNoJitter - currentPixelNoJitter
            : vec2(0.0);
        float reconstructionDepthValue = representative.state.x != 0u
            ? gsReconstructionDepth(reconstructionViewZ)
            : 1.0;
        imageStore(reconstructionDepth, pixel, vec4(reconstructionDepthValue, 0.0, 0.0, 0.0));
        imageStore(reconstructionMotionVectors, pixel, vec4(motionPixels, 0.0, 0.0));
        if (all(equal(pixel, ivec2(0)))) {
            imageStore(reconstructionExposure, ivec2(0), vec4(1.0, 0.0, 0.0, 0.0));
        }
    }
    vec3 resolvedColor = currentColor;
    bool temporalEnabled = (gsFrame.words[GPU_SCENE_FRAME_TEMPORAL_FLAGS_WORD]
        & GPU_SCENE_TEMPORAL_FLAG_ENABLED) != 0u;
    if (temporalEnabled) {
        vec4 currentGeometry = gsTemporalGeometry(representative);
        bool dynamicSurface = (representative.state.w & GPU_SCENE_FLAG_DYNAMIC) != 0u
            || gsSurfaceOverlay(representative)
            || representative.mediumBoundary.w!=0u
            || representative.transmissionIor.x>0.0
            || outlineApplied;
        if (dynamicSurface) currentGeometry = vec4(0.0);
        vec2 previousPixelPosition;
        float expectedPreviousLogDepth;
        bool reprojected = representative.state.x != 0u
            && !dynamicSurface
            && gsPreviousPixelPositionWorld(
                gsTemporalPreviousWorldPosition(representative),
                extent,
                previousPixelPosition,
                expectedPreviousLogDepth
            );
        vec2 motion = reprojected
            ? (previousPixelPosition - (vec2(pixel) + vec2(0.5))) / vec2(extent)
            : vec2(0.0);
        bool historyValid = (gsFrame.words[GPU_SCENE_FRAME_TEMPORAL_FLAGS_WORD]
            & GPU_SCENE_TEMPORAL_FLAG_HISTORY_VALID) != 0u;
        bool accepted = false;
        vec4 previousColor = vec4(0.0);
        // NRD owns temporal accumulation for its signals. Keep this history warm for a later
        // non-NRD frame, but never blend it into the trace that NRD will resolve.
        if (!denoisingActive && historyValid && reprojected) {
            ivec2 previousPixel = ivec2(floor(previousPixelPosition));
            vec4 previousGeometry = imageLoad(historyGeometryInput, previousPixel);
            accepted = gsTemporalGeometryMatches(
                currentGeometry, previousGeometry, expectedPreviousLogDepth
            );
            if (accepted) previousColor = imageLoad(historyColorInput, previousPixel);
        }
        float historyConfidence = 0.0;
        if (accepted) {
            resolvedColor = gsResolveTemporal(
                currentColor,
                previousColor,
                motion,
                extent,
                gsFrame.words[GPU_SCENE_FRAME_MAX_HISTORY_FRAMES_WORD],
                historyConfidence
            );
        }
        float nextHistoryAge = accepted
            ? min(1.0 + previousColor.a * historyConfidence,
                float(gsFrame.words[GPU_SCENE_FRAME_MAX_HISTORY_FRAMES_WORD]))
            : 1.0;
        imageStore(historyColorOutput, pixel, vec4(resolvedColor, nextHistoryAge));
        imageStore(historyGeometryOutput, pixel, currentGeometry);
        imageStore(motionOutput, pixel, vec4(motion, 0.0, 0.0));
    }
#ifdef RTRENDERER_LINEAR_HDR_OUTPUT
    vec3 outputColor = resolvedColor;
#else
    float lightmapCoverage = float(lightmapSampleCount) / float(sampleCount);
    vec3 outputColor = mix(
        gsToneMap(resolvedColor),
        gsLinearToSrgb(resolvedColor),
        lightmapCoverage
    );
#endif
    imageStore(outputImage, pixel, vec4(outputColor, 1.0));
}
