// RTRendererAPI compatibility adapter: analytic homogeneous emission required by the Java API.
// Inputs are density-scaled absorption and emitted radiance per world-distance unit.
__device__ float rtrApiIntegratedEmission(float absorption, float emission, float distance) {
    if (emission == 0.f || distance == 0.f) return 0.f;
    const float depth = absorption * distance;
    // The series is the analytic zero-extinction limit and also handles underflow in sigma*d.
    const float integral = depth <= .001f
            ? distance * (1.f - .5f * depth + (depth * depth) / 6.f)
            : -expm1f(-depth) / absorption;
    // Keep the native float wire finite when the analytic value exceeds its representable range.
    return fminf(emission * integral, 3.402823466e38f);
}
