#ifndef RTR_OPTICAL_FLUX_BINS_HLSLI
#define RTR_OPTICAL_FLUX_BINS_HLSLI

// Bind one R32_UINT UAV with three array slices (R, G, B at 0, 1, 2).
// The caller clears all slices and orders the clear, deposits and resolve with
// UAV barriers. This include owns no bindings or scene/receiver resources.
//
// A texel is an already validated receiver in this grid's mip 0. Project the
// photon terminal hit with the receiver grid's current-frame view and jitter:
// require finite coordinates in [0,width) x [0,height), then floor to uint2.
// Require the same frame, instance/geometry/primitive and geometric face side
// as the visible receiver, and abs(dot(hit - receiver, unitGeometricNormal))
// within the caller's world-space plane-distance tolerance. Reject mismatches;
// never clamp coordinates, search neighboring texels, or splat across texels.

static const float RtrOpticalFluxMaxScaled = 2147483648.0f; // 2^31, exact in FP32.

// Per channel, the caller supplies scale=2^k and inverseScale=2^-k as exact
// normal FP32 powers of two. Fix them from clear to resolve. Bound the sum B
// of all nonnegative weightedFlux values that can be deposited in this period
// (including every contributing dispatch, all targeting one bin in the worst
// case). Choose k with B*2^k <= 2^31, not from an average or nominal source
// power. B can be bounded by maximum deposit count times maximum deposit flux.
// Each stored floor(weightedFlux*scale) is no larger than its scaled input;
// the bound makes plain uint atomic addition safe without a CAS or saturation.
void RtrOpticalFluxDeposit(RWTexture2DArray<uint> bins, uint2 texel,
    float3 weightedFlux, float3 scale)
{
    // Flux already includes emission/PDF, projected area and path weighting.
    precise float3 scaledFlux = weightedFlux * scale;
    if (!all(scaledFlux >= 0.0f) || !all(scaledFlux <= RtrOpticalFluxMaxScaled))
        return; // No partial RGB deposit for a malformed sample.

    // Truncation of nonnegative values implements floor; zero needs no atomic.
    uint3 quantized = (uint3)scaledFlux;
    if (quantized.x != 0u) InterlockedAdd(bins[uint3(texel, 0u)], quantized.x);
    if (quantized.y != 0u) InterlockedAdd(bins[uint3(texel, 1u)], quantized.y);
    if (quantized.z != 0u) InterlockedAdd(bins[uint3(texel, 2u)], quantized.z);
}

// Resolve total incident flux per unit of actual receiver world area, not per
// screen area or photon count. An invalid area has no irradiance. The caller
// supplies a valid in-bounds texel and keeps dequantized flux and irradiance
// within FP32 range; the resolve itself is subject to FP32 rounding.
float3 RtrOpticalFluxResolve(RWTexture2DArray<uint> bins, uint2 texel,
    float3 inverseScale, float receiverWorldArea)
{
    if (!(receiverWorldArea > 0.0f && isfinite(receiverWorldArea)))
        return 0.0f;

    uint3 total = uint3(bins[uint3(texel, 0u)], bins[uint3(texel, 1u)], bins[uint3(texel, 2u)]);
    return (float3(total) * inverseScale) / receiverWorldArea;
}

#endif
