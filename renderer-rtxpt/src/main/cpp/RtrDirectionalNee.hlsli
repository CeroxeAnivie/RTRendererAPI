#if defined(RTR_DIRECTIONAL_STRATA) && RTR_DIRECTIONAL_STRATA
void RtrSumDirectionalStrata(inout NEEResult result, const LightSampler sampler,
    const ShadingData shading, const ActiveBSDF bsdf, const PathState path,
    inout UniformSampleSequenceGenerator generator, const WorkingContext context)
{
    for (uint i = 0; i < context.PtConsts.RtrDirectionalCount; ++i)
    {
        const float4 cap = context.PtConsts.RtrDirectionalCaps[i];
        float3 tangent, bitangent;
        BranchlessONB(cap.xyz, tangent, bitangent);
        const float3 local = sample_cone(sampleNext2D(generator), cap.w);
        const float3 direction = tangent * local.x + bitangent * local.y + cap.xyz * local.z;
        // Earlier caps own overlaps, so no piece of the sky is counted twice.
        if (RtrDirectionalOwner(direction, context.PtConsts) != i) continue;
        const float pdf = RtrDirectionalPdf(i, context.PtConsts);
        EnvMap environment = Bridge::CreateEnvMap();
        LightSample light = LightSample::make();
        light.Direction = direction;
        light.Distance = DISTANT_LIGHT_DISTANCE;
        light.Li = environment.Eval(direction, 0) / pdf;
        light.SolidAnglePdf = pdf;
        light.LightIndex = NEEAT_INVALID_LIGHT_INDEX;
        light.SelectionPdf = 1;
        light.LightSampleableByBSDF = true;
        light.FromLocalDistribution = false;
        ProcessLightSample(result, light, 1, 1, 0, 1, shading, bsdf, path,
            sampler, generator, context, false);
    }
}
#endif
