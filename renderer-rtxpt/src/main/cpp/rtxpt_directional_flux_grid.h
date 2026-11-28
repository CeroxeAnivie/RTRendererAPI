#pragma once
#include <cstdint>

namespace rtr::optical {
struct DirectionalFluxGrid { uint32_t width,height; };

// Keep reconstruction tied to the emission budget, not to display resolution.
// Pick the smallest common divisor that fits; the shader maps both grids over
// the same NDC extent, including when an axis is not divisible by the divisor.
constexpr DirectionalFluxGrid BoundedDirectionalFluxGrid(uint32_t width,uint32_t height,uint32_t budget){
    if(!width||!height||!budget)return {};
    const auto divide=[](uint32_t size,uint32_t divisor){return 1u+(size-1u)/divisor;};
    uint32_t lo=1,hi=width>height?width:height;
    while(lo<hi){
        const uint32_t mid=lo+(hi-lo)/2;
        if(uint64_t(divide(width,mid))*divide(height,mid)>budget)lo=mid+1;
        else hi=mid;
    }
    return {divide(width,lo),divide(height,lo)};
}

// Directional transport is sparse: many paths miss the current view or end
// behind another surface. Use several launched paths per bin to integrate
// irradiance instead of exposing the emission-cell pattern on the receiver.
constexpr DirectionalFluxGrid DirectionalIrradianceGrid(uint32_t width,uint32_t height,uint32_t sampleBudget){
    constexpr uint32_t LaunchedPathsPerBin=4;
    const uint32_t bins=sampleBudget/LaunchedPathsPerBin;
    return BoundedDirectionalFluxGrid(width,height,bins?bins:1);
}
}
