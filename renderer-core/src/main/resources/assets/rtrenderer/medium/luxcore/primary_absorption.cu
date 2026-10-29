// RTRendererAPI adapter for the existing 208-byte GPUScene primary-hit payload.
// Constants: density-scaled sigmaA.rgb, maxDistance. Output is local to this tile.
// The pinned upstream segment function supplies the transmittance calculation.
extern "C" __global__ void primary_medium_resolve(const float *primary, int count,
        const float *constants, float4 *output) {
    const int i = blockIdx.x * blockDim.x + threadIdx.x;
    if (i >= count) return;
    const float *p = primary + i * 52;
    const unsigned int *identity = (const unsigned int *)p;
    const bool hit = identity[28] != 0u;
    const float distance = hit ? fminf(fmaxf(p[3], 0.f), constants[3]) : constants[3];
    float3 absorption = make_float3(constants[0], constants[1], constants[2]);
    float3 zero = make_float3(0.f), transmittance, emission;
    HomogeneousVolume_SegmentScatter(.5f, false, distance, &absorption, &zero, &zero,
            &transmittance, &emission);
    output[i] = make_float4(transmittance.x, transmittance.y, transmittance.z, 1.f);
}
