#ifndef GS_GPU_SCENE_MEDIUM_GLSL
#define GS_GPU_SCENE_MEDIUM_GLSL
#include "gpuscene_common.glsl"

struct GsMediumCoefficients { vec3 extinction; vec3 scattering; vec3 emission; float density; float ior; float g; };

GsMediumCoefficients gsFrameMedium() {
    return GsMediumCoefficients(
        gsFrameVec3(GPU_SCENE_FRAME_MEDIUM_EXTINCTION_WORD),
        gsFrameVec3(GPU_SCENE_FRAME_MEDIUM_SCATTERING_WORD),
        gsFrameVec3(GPU_SCENE_FRAME_MEDIUM_EMISSION_WORD),
        uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_MEDIUM_DENSITY_WORD]),
        max(uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_MEDIUM_IOR_WORD]), 1.0),
        uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_MEDIUM_PHASE_ANISOTROPY_WORD])
    );
}

GsMediumCoefficients gsInstanceMedium(uint instanceIndex) {
    uint base = GPU_SCENE_INSTANCE_MEDIUM_EXTINCTION_WORD;
    return GsMediumCoefficients(
        vec3(uintBitsToFloat(gsInstanceWord(instanceIndex, base)),
             uintBitsToFloat(gsInstanceWord(instanceIndex, base + 1u)),
             uintBitsToFloat(gsInstanceWord(instanceIndex, base + 2u))),
        vec3(uintBitsToFloat(gsInstanceWord(instanceIndex, GPU_SCENE_INSTANCE_MEDIUM_SCATTERING_WORD)),
             uintBitsToFloat(gsInstanceWord(instanceIndex, GPU_SCENE_INSTANCE_MEDIUM_SCATTERING_WORD + 1u)),
             uintBitsToFloat(gsInstanceWord(instanceIndex, GPU_SCENE_INSTANCE_MEDIUM_SCATTERING_WORD + 2u))),
        vec3(uintBitsToFloat(gsInstanceWord(instanceIndex, GPU_SCENE_INSTANCE_MEDIUM_EMISSION_WORD)),
             uintBitsToFloat(gsInstanceWord(instanceIndex, GPU_SCENE_INSTANCE_MEDIUM_EMISSION_WORD + 1u)),
             uintBitsToFloat(gsInstanceWord(instanceIndex, GPU_SCENE_INSTANCE_MEDIUM_EMISSION_WORD + 2u))),
        uintBitsToFloat(gsInstanceWord(instanceIndex, GPU_SCENE_INSTANCE_MEDIUM_DENSITY_WORD)),
        max(uintBitsToFloat(gsInstanceWord(instanceIndex, GPU_SCENE_INSTANCE_MEDIUM_IOR_WORD)), 1.0),
        uintBitsToFloat(gsInstanceWord(instanceIndex, GPU_SCENE_INSTANCE_MEDIUM_PHASE_ANISOTROPY_WORD))
    );
}

GsMediumCoefficients gsCurrentMedium(GsMediumPath path) {
#ifdef RTRENDERER_SURFACE_ONLY
    return gsFrameMedium();
#else
    uint instanceIndex = gsCurrentMediumInstance(path);
    return instanceIndex == GS_INVALID_SLOT ? gsFrameMedium() : gsInstanceMedium(instanceIndex);
#endif
}

vec2 gsInterfaceIors(GpuScenePayload surfaceSample,GsMediumPath incidentPath,GsMediumPath transmittedPath) {
    float exterior=gsCurrentMedium(incidentPath).ior;
    if(surfaceSample.mediumBoundary.w!=0u) return vec2(exterior,gsCurrentMedium(transmittedPath).ior);
    float material=max(surfaceSample.transmissionIor.y,1.0);
    // Preserve the existing front/back interface convention for undeclared surface-only dielectrics.
    return surfaceSample.state.y==GS_PAYLOAD_BACK_FACE?vec2(material,exterior):vec2(exterior,material);
}

GsMediumPath gsCrossMediumBoundary(GsMediumPath path, GpuScenePayload surfaceSample) {
#ifndef RTRENDERER_SURFACE_ONLY
    if (surfaceSample.mediumBoundary.w == 0u) return path;
    uint boundary = surfaceSample.mediumBoundary.x;
    bool backFace = surfaceSample.state.y == GS_PAYLOAD_BACK_FACE;
    bool inwardWinding = surfaceSample.mediumBoundary.z != 0u;
    bool entering = inwardWinding == backFace;
    int existing = gsMediumPathFind(path, boundary);
    if (entering) {
        if (existing < 0 && path.depth < GS_MEDIUM_PATH_CAPACITY) {
            gsMediumPathSet(path, path.depth, boundary);
            path.depth++;
        }
        else if (existing < 0) path.depth = GS_MEDIUM_PATH_CAPACITY + 1u;
    } else if (existing >= 0) {
        [[dont_unroll]] for (uint i = uint(existing); i + 1u < path.depth; ++i)
            gsMediumPathSet(path, i, gsMediumPathGet(path, i + 1u));
        path.depth--;
    }
    path.mediumSlot=gsSelectMediumInstance(path);
#endif
    return path;
}

float gsDielectricFresnel(float incidentIor, float transmittedIor, float cosine)
{
    float cosI = clamp(cosine, 0.0, 1.0);
    float sinT2 = (incidentIor / transmittedIor) * (incidentIor / transmittedIor)
        * max(0.0, 1.0 - cosI * cosI);
    if (sinT2 >= 1.0) return 1.0;
    float cosT = sqrt(max(0.0, 1.0 - sinT2));
    float rsNumerator = incidentIor * cosI - transmittedIor * cosT;
    float rsDenominator = incidentIor * cosI + transmittedIor * cosT;
    float rpNumerator = incidentIor * cosT - transmittedIor * cosI;
    float rpDenominator = incidentIor * cosT + transmittedIor * cosI;
    float rs = rsNumerator / max(abs(rsDenominator), 1.0e-6);
    float rp = rpNumerator / max(abs(rpDenominator), 1.0e-6);
    return clamp(0.5 * (rs * rs + rp * rp), 0.0, 1.0);
}

struct GsRadianceSplit {
    vec3 diffuse;
    vec3 specular;
    float diffuseHitDistance;
    float specularHitDistance;
    float diffuseHitWeight;
    float specularHitWeight;
};

GsRadianceSplit gsRadianceSplit(vec3 diffuse, vec3 specular)
{
    GsRadianceSplit result;
    result.diffuse = diffuse;
    result.specular = specular;
    result.diffuseHitDistance = 0.0;
    result.specularHitDistance = 0.0;
    result.diffuseHitWeight = 0.0;
    result.specularHitWeight = 0.0;
    return result;
}

float gsLuminance(vec3 value)
{
    return dot(max(value, vec3(0.0)), vec3(0.2126, 0.7152, 0.0722));
}

void gsAccumulateRadianceSplit(inout GsRadianceSplit total, GsRadianceSplit contribution)
{
    float diffuseWeight = gsLuminance(contribution.diffuse);
    float specularWeight = gsLuminance(contribution.specular);
    total.diffuse += contribution.diffuse;
    total.specular += contribution.specular;
    total.diffuseHitDistance += contribution.diffuseHitDistance * diffuseWeight;
    total.specularHitDistance += contribution.specularHitDistance * specularWeight;
    total.diffuseHitWeight += diffuseWeight;
    total.specularHitWeight += specularWeight;
}

void gsFinalizeRadianceHitDistances(inout GsRadianceSplit split)
{
    split.diffuseHitDistance = split.diffuseHitWeight > 1.0e-6
        ? split.diffuseHitDistance / split.diffuseHitWeight : 0.0;
    split.specularHitDistance = split.specularHitWeight > 1.0e-6
        ? split.specularHitDistance / split.specularHitWeight : 0.0;
}

vec3 gsRadiance(GsRadianceSplit split)
{
    return split.diffuse + split.specular;
}

struct GsShadowQuery {
    GpuScenePayload surfaceSample;
    GsRadianceSplit surfaceRadiance;
    uint operation;
    vec3 incidentRadiance;
    GsMediumPath path;
    vec3 origin;
    vec3 normal;
    vec3 direction;
    float maximumDistance;
    vec3 transmittance;
};

#endif
