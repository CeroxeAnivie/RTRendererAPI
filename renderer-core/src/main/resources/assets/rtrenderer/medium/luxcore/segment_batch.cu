// RTRendererAPI batch adapter. The called upstream function is unchanged.
// Input: u, scatterAllowed, distance, sigmaA.rgb, sigmaS.rgb, emission.rgb.
// Output: scatterDistance (-1 for escape), transmittance.rgb, emission.rgb.
extern "C" __global__ void rtr_luxcore_segment(const float *input, float *output, int count) {
    const int index = blockIdx.x * blockDim.x + threadIdx.x;
    if (index >= count) return;
    const float *p = input + index * 12;
    float *q = output + index * 7;
    float3 sigmaA = make_float3(p[3], p[4], p[5]);
    float3 sigmaS = make_float3(p[6], p[7], p[8]);
    float3 emission = make_float3(p[9], p[10], p[11]);
    float3 transmittance, segmentEmission;
    q[0] = HomogeneousVolume_SegmentScatter(p[0], p[1] != 0, p[2],
            &sigmaA, &sigmaS, &emission, &transmittance, &segmentEmission);
    q[1] = transmittance.x; q[2] = transmittance.y; q[3] = transmittance.z;
    q[4] = segmentEmission.x; q[5] = segmentEmission.y; q[6] = segmentEmission.z;
}
