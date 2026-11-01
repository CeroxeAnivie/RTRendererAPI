// PBRT SampleAllLights: an exactly integrated delta-light stratum must not
// compete with the remaining stochastic stratum. Keep RTXPT's native power
// proxy distribution inside the environment stratum, including BSDF MIS PDFs.
// Included inside LightSampler; compiled out unless explicitly requested.
#if defined(RTR_SPLIT_POINT_ENVIRONMENT) && RTR_SPLIT_POINT_ENVIRONMENT
bool RtrSplitPointEnvironment()
{
    const uint first = ControlBuffer[0].EnvmapQuadNodeCount;
    const uint count = ControlBuffer[0].AnalyticLightCount;
    if (count == 0 || count > 4 || ControlBuffer[0].TriangleLightCount != 0
        || ControlBuffer[0].LightClusterCount != 0)
        return false;
    for (uint i = 0; i < count; ++i)
        if (PolymorphicLight::DecodeType(LoadLight(first + i)) != PolymorphicLightType::kPoint)
            return false;
    return true;
}

uint RtrEnvironmentProxyCount()
{
    uint count = ControlBuffer[0].SamplingProxyCount;
    const uint first = ControlBuffer[0].EnvmapQuadNodeCount;
    for (uint i = 0; i < ControlBuffer[0].AnalyticLightCount; ++i)
        count -= ProxyCounters[first + i];
    // Native CreateProxyJobs orders the proxy array by light index. Environment
    // quads are its contiguous prefix, followed by all analytic delta lights.
    return count;
}

uint RtrEnvironmentLocalProxyCount()
{
    uint count = 0;
    const uint environmentLights = ControlBuffer[0].EnvmapQuadNodeCount;
    for (uint i = 0; i < RTXPT_LIGHTING_LOCAL_PROXY_COUNT; ++i)
    {
        uint lightIndex, proxyCount;
        ReadLocal(i, lightIndex, proxyCount);
        if (lightIndex < environmentLights)
            count += proxyCount;
    }
    return count;
}

uint RtrSampleEnvironmentLocal(const float rnd, out float pdf)
{
    const uint total = RtrEnvironmentLocalProxyCount();
    if (total == 0)
    {
        pdf = 0;
        return NEEAT_INVALID_LIGHT_INDEX;
    }
    uint remaining = min(uint(rnd * total), total - 1u);
    const uint environmentLights = ControlBuffer[0].EnvmapQuadNodeCount;
    for (uint i = 0; i < RTXPT_LIGHTING_LOCAL_PROXY_COUNT; ++i)
    {
        uint lightIndex, proxyCount;
        ReadLocal(i, lightIndex, proxyCount);
        if (lightIndex >= environmentLights || proxyCount == 0)
            continue;
        if (remaining < proxyCount)
        {
            pdf = float(proxyCount) / float(total);
            return lightIndex;
        }
        remaining -= proxyCount;
    }
    pdf = 0;
    return NEEAT_INVALID_LIGHT_INDEX;
}
#endif
