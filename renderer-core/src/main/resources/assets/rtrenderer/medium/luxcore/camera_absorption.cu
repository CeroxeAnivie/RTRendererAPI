// RTRendererAPI integration wrapper. The medium calculation calls the pinned upstream function unchanged.
// Constants: origin/maxDistance, forward/uint width, right/uint height, up/uint firstPixel,
// fov/jitter, density-scaled absorption/epsilon. Output is local to this tile.
extern "C" __global__ void camera_medium_init(float *records, int count, const float *c, float4 *output) {
    int i = blockIdx.x * blockDim.x + threadIdx.x; if (i >= count) return;
    const unsigned int *cu = (const unsigned int *)c;
    unsigned int pixel = cu[15] + (unsigned int)i;
    unsigned int width = cu[7], height = cu[11];
    float x = ((float)(pixel % width) + .5f + c[18]) / (float)width * 2.f - 1.f;
    float y = ((float)(pixel / width) + .5f + c[19]) / (float)height * 2.f - 1.f;
    float3 forward = normalize(make_float3(c[4], c[5], c[6]));
    float3 right = normalize(make_float3(c[8], c[9], c[10]));
    float3 up = normalize(make_float3(c[12], c[13], c[14]));
    float3 direction = normalize(forward + right * x * c[16] - up * y * c[17]);
    float *p = records + i * 32; for (int j = 0; j < 32; ++j) p[j] = 0;
    p[0] = c[0]; p[1] = c[1]; p[2] = c[2]; p[3] = c[23];
    p[4] = direction.x; p[5] = direction.y; p[6] = direction.z; p[7] = c[3];
    unsigned int *u = (unsigned int *)p;
    u[8] = 1; u[9] = 0; u[10] = pixel; u[11] = 1;
}
extern "C" __global__ void camera_medium_resolve(float *records, int count, const float *c, float4 *output) {
    int i = blockIdx.x * blockDim.x + threadIdx.x; if (i >= count) return;
    float *p = records + i * 32;
    float distance = p[12] < 0.f ? c[3] : fminf(fmaxf(p[12], 0.f), c[3]);
    float3 a = make_float3(c[20], c[21], c[22]), zero = make_float3(0.f), tr, emission;
    HomogeneousVolume_SegmentScatter(.5f, false, distance, &a, &zero, &zero, &tr, &emission);
    output[i] = make_float4(tr.x, tr.y, tr.z, 1.f);
}
