// RTRendererAPI adapter. The pinned LuxCore homogeneous segment computes transmittance unchanged.
// The API specifies analytic emission integration, which differs from LuxCore's sampled segment
// emission accumulation. The small compatibility adapter below preserves that public contract.
// Constants: density-scaled sigmaA.rgb, maxDistance, density-scaled emission.rgb, reserved.
// Output: count transmittance float4 values, then count emitted-radiance float4 values.
__device__ void rtrPrimaryEmissionResolve(const float *primary, const float *constants,
        float4 *transmittanceOutput, float4 *emissionOutput) {
    const bool hit = ((const unsigned int *)primary)[28] != 0u;
    const float distance = hit ? fminf(fmaxf(primary[3], 0.f), constants[3]) : constants[3];
    const float3 absorption = make_float3(constants[0], constants[1], constants[2]);
    const float3 zero = make_float3(0.f);
    float3 transmittance, ignoredEmission;
    HomogeneousVolume_SegmentScatter(.5f, false, distance, &absorption, &zero, &zero,
            &transmittance, &ignoredEmission);
    *transmittanceOutput = make_float4(transmittance.x, transmittance.y, transmittance.z, 1.f);
    *emissionOutput = make_float4(
            rtrApiIntegratedEmission(absorption.x, constants[4], distance),
            rtrApiIntegratedEmission(absorption.y, constants[5], distance),
            rtrApiIntegratedEmission(absorption.z, constants[6], distance), 0.f);
}

extern "C" __global__ void primary_medium_emission_resolve(const float *primary, int count,
        const float *constants, float4 *output) {
    const int i = blockIdx.x * blockDim.x + threadIdx.x;
    if (i >= count) return;
    rtrPrimaryEmissionResolve(primary + i * 52, constants, output + i, output + count + i);
}
