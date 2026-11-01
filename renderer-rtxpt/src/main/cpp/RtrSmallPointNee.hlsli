// Small delta-light sum; BSDF evaluation, visibility, MIS and accumulation remain RTXPT's.
// Reference: PBRT-v3 UniformSampleAllLights / EstimateDirect (delta light branch).
// Only zero-radius point lights are summed. When the residual environment is
// stratified, RTXPT retains its sampler and uses matching conditional PDFs/MIS.
#if defined(RTR_EXACT_SMALL_POINTS) && RTR_EXACT_SMALL_POINTS
bool RtrSmallPointSet(const LightSampler sampler)
{
    uint count = sampler.ControlBuffer[0].AnalyticLightCount;
    return count > 0 && count <= 4;
}

bool RtrCoveredPoint(const LightSampler sampler, uint index)
{
    uint first = sampler.ControlBuffer[0].EnvmapQuadNodeCount;
    uint count = sampler.ControlBuffer[0].AnalyticLightCount;
    return index >= first && index < first + count
        && PolymorphicLight::DecodeType(sampler.LoadLight(index)) == PolymorphicLightType::kPoint;
}

void RtrSumPointLights(inout NEEResult result, const LightSampler sampler,
    const ShadingData shading, const ActiveBSDF bsdf, const PathState path,
    inout UniformSampleSequenceGenerator generator, const WorkingContext context)
{
    uint first = sampler.ControlBuffer[0].EnvmapQuadNodeCount;
    uint count = sampler.ControlBuffer[0].AnalyticLightCount;
    for (uint i = 0; i < count; ++i)
    {
        uint index = first + i;
        if (!RtrCoveredPoint(sampler, index))
            continue;
        PolymorphicLightSample pointSample = PolymorphicLight::CalcSample(
            sampler.LoadLight(index), float2(0.5, 0.5), shading.posW);
        LightSample light = LightSample::make();
        float3 delta = pointSample.Position - shading.posW;
        light.Distance = length(delta);
        light.Direction = delta / max(light.Distance, 1e-7);
        light.Li = pointSample.Radiance / max(pointSample.SolidAnglePdf, 1e-20);
        light.SolidAnglePdf = pointSample.SolidAnglePdf;
        light.LightIndex = index;
        light.SelectionPdf = 1.0; // Every covered point is selected exactly once.
        light.LightSampleableByBSDF = false; // A point light has delta support.
        light.FromLocalDistribution = false;
        ProcessLightSample(result, light, 1, 1, 0, 1, shading, bsdf, path,
            sampler, generator, context);
    }
}
#endif
