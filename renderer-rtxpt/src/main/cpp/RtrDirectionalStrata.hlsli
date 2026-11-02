// Disjoint environment strata around scene directionals. The environment is
// still baked/evaluated by RTXPT; these are sampling domains, not extra lights.
// Stratified direct-light estimators: PBRT EstimateDirect / UniformSampleAllLights.
#ifndef RTR_DIRECTIONAL_STRATA_HLSLI
#define RTR_DIRECTIONAL_STRATA_HLSLI
#if defined(RTR_DIRECTIONAL_STRATA) && RTR_DIRECTIONAL_STRATA
uint RtrDirectionalOwner(float3 direction, const PathTracerConstants constants)
{
    for (uint i = 0; i < constants.RtrDirectionalCount; ++i)
        if (dot(direction, constants.RtrDirectionalCaps[i].xyz) >= constants.RtrDirectionalCaps[i].w)
            return i;
    return 0xffffffffu;
}

float RtrDirectionalPdf(uint index, const PathTracerConstants constants)
{
    return rcp(2 * K_PI * (1 - constants.RtrDirectionalCaps[index].w));
}
#endif
#endif
