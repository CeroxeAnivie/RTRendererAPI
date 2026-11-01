// Conditional residual sampling leaves exact delta points to the deterministic estimator.
bool RtrCoveredPoint(uint lightIndex)
{
    const uint first = ControlBuffer[0].EnvmapQuadNodeCount;
    const uint count = ControlBuffer[0].AnalyticLightCount;
    return lightIndex >= first && lightIndex < first + count
        && PolymorphicLight::DecodeType(LoadLight(lightIndex)) == PolymorphicLightType::kPoint;
}

bool RtrSmallPointSet()
{
    const uint count = ControlBuffer[0].AnalyticLightCount;
    if (count == 0 || count > 4 || ControlBuffer[0].LightClusterCount != 0)
        return false;

    bool pointFound = false;
    const uint first = ControlBuffer[0].EnvmapQuadNodeCount;
    for (uint i = 0; i < count; ++i)
    {
        const PolymorphicLightType type = PolymorphicLight::DecodeType(LoadLight(first + i));
        if (type == PolymorphicLightType::kPoint)
            pointFound = true;
        else if (type != PolymorphicLightType::kSphere && type != PolymorphicLightType::kDirectional)
            return false;
    }
    return pointFound;
}

uint RtrResidualProxyCount()
{
    uint count = ControlBuffer[0].SamplingProxyCount;
    const uint first = ControlBuffer[0].EnvmapQuadNodeCount;
    for (uint i = 0; i < ControlBuffer[0].AnalyticLightCount; ++i)
    {
        const uint pointIndex = first + i;
        if (RtrCoveredPoint(pointIndex))
            count -= min(count, ProxyCounters[pointIndex]);
    }
    return count;
}

// Each compressed NEE-AT entry is repeated proxyCount times in the fixed-size list.
uint RtrResidualLocalSlotCount()
{
    uint count = 0;
    for (uint i = 0; i < RTXPT_LIGHTING_LOCAL_PROXY_COUNT; ++i)
    {
        uint lightIndex, proxyCount;
        ReadLocal(i, lightIndex, proxyCount);
        if (lightIndex != NEEAT_INVALID_LIGHT_INDEX && proxyCount > 0 && !RtrCoveredPoint(lightIndex))
            ++count;
    }
    return count;
}

uint RtrFirstGlobalProxyIndex(uint lightIndex)
{
    uint left = 0;
    uint right = ControlBuffer[0].SamplingProxyCount;
    while (left < right)
    {
        const uint middle = left + ((right - left) >> 1);
        if (ProxyIndices[middle] < lightIndex)
            left = middle + 1;
        else
            right = middle;
    }
    return left;
}

uint RtrSampleResidualGlobal(const float rnd, const uint residualProxyCount, out float pdf)
{
    if (residualProxyCount == 0)
    {
        pdf = 0;
        return NEEAT_INVALID_LIGHT_INDEX;
    }

    uint proxyIndex = min(uint(rnd * residualProxyCount), residualProxyCount - 1u);
    const uint first = ControlBuffer[0].EnvmapQuadNodeCount;
    for (uint i = 0; i < ControlBuffer[0].AnalyticLightCount; ++i)
    {
        const uint pointIndex = first + i;
        if (!RtrCoveredPoint(pointIndex))
            continue;

        const uint pointProxyCount = ProxyCounters[pointIndex];
        if (pointProxyCount == 0)
            continue;

        const uint firstPointProxy = RtrFirstGlobalProxyIndex(pointIndex);
        if (proxyIndex >= firstPointProxy)
            proxyIndex += pointProxyCount;
    }

    if (proxyIndex >= ControlBuffer[0].SamplingProxyCount)
    {
        pdf = 0;
        return NEEAT_INVALID_LIGHT_INDEX;
    }

    const uint lightIndex = ProxyIndices[proxyIndex];
    pdf = float(ProxyCounters[lightIndex]) / float(residualProxyCount);
    return lightIndex;
}

uint RtrSampleResidualLocal(const float rnd, const uint residualSlotCount, out float pdf)
{
    if (residualSlotCount == 0)
    {
        pdf = 0;
        return NEEAT_INVALID_LIGHT_INDEX;
    }

    uint remaining = min(uint(rnd * residualSlotCount), residualSlotCount - 1u);
    for (uint i = 0; i < RTXPT_LIGHTING_LOCAL_PROXY_COUNT; ++i)
    {
        uint lightIndex, proxyCount;
        ReadLocal(i, lightIndex, proxyCount);
        if (lightIndex == NEEAT_INVALID_LIGHT_INDEX || proxyCount == 0 || RtrCoveredPoint(lightIndex))
            continue;
        if (remaining == 0)
        {
            pdf = float(proxyCount) / float(residualSlotCount);
            return lightIndex;
        }
        --remaining;
    }

    pdf = 0;
    return NEEAT_INVALID_LIGHT_INDEX;
}

float RtrNativeGlobalPdfForFeedback(uint lightIndex)
{
    const uint totalProxyCount = ControlBuffer[0].SamplingProxyCount;
    return totalProxyCount > 0 ? float(ProxyCounters[lightIndex]) / float(totalProxyCount) : 0;
}

float RtrSampleGlobalPDF(uint lightIndex, const bool exactPointsActive)
{
    if (!exactPointsActive)
        return SampleGlobalPDF(lightIndex);
    if (RtrCoveredPoint(lightIndex))
        return 0;
    const uint residualProxyCount = RtrResidualProxyCount();
    return residualProxyCount > 0 ? float(ProxyCounters[lightIndex]) / float(residualProxyCount) : 0;
}

float RtrSampleLocalPDF(uint lightIndex, const bool exactPointsActive)
{
    if (!exactPointsActive)
        return SampleLocalPDF(lightIndex);
    if (RtrCoveredPoint(lightIndex))
        return 0;

    const uint residualSlotCount = RtrResidualLocalSlotCount();
    if (residualSlotCount == 0)
        return 0;

    const uint packed = LocalLightBinarySearch(LocalSamplingBuffer, LocalSamplingTilePos,
        lightIndex, RTXPT_LIGHTING_LOCAL_PROXY_COUNT, RTXPT_LIGHTING_LOCAL_PROXY_BINARY_SEARCH_STEPS);
    if (packed == NEEAT_INVALID_LIGHT_INDEX)
        return 0;

    uint foundIndex, proxyCount;
    NEEAT::UnpackMiniListLightAndCount(packed, foundIndex, proxyCount);
    return foundIndex == lightIndex ? float(proxyCount) / float(residualSlotCount) : 0;
}

void RtrComputeLightSelectionPdfs(const LightSample lightSample, const uint localCandidateCount,
    const uint globalCandidateCount, const bool exactPointsActive, out float thisPdf, out float otherPdf,
    out float thisCount, out float otherCount)
{
    thisPdf = lightSample.SelectionPdf;
    if (lightSample.FromLocalDistribution)
    {
        otherPdf = RtrSampleGlobalPDF(lightSample.LightIndex, exactPointsActive);
        thisCount = localCandidateCount;
        otherCount = globalCandidateCount;
    }
    else
    {
        thisCount = globalCandidateCount;
        if (localCandidateCount != 0)
        {
            otherPdf = RtrSampleLocalPDF(lightSample.LightIndex, exactPointsActive);
            otherCount = localCandidateCount;
        }
        else
        {
            otherPdf = 0;
            otherCount = 0;
        }
    }
}

void RtrComputeLightSelectionPdfs(const LightSample lightSample, const uint localCandidateCount,
    const uint globalCandidateCount, const bool exactPointsActive, out float thisPdf, out float otherPdf)
{
    float thisCount, otherCount;
    RtrComputeLightSelectionPdfs(lightSample, localCandidateCount, globalCandidateCount,
        exactPointsActive, thisPdf, otherPdf, thisCount, otherCount);
}
