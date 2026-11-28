#ifndef RTR_FINITE_OPTICAL_EMISSION_HLSLI
#define RTR_FINITE_OPTICAL_EMISSION_HLSLI

#include "RtrOpticalEmission.hlsli"

// The ray carries scene-linear RGB radiant flux, not irradiance at a receiver.
// Apply path transmission to flux, then divide the deposited sum by receiver
// area. Do not apply an additional source cosine, inverse square or ray PDF.
struct RtrFiniteOpticalEmissionSample {
    RayDesc ray;
    float3 flux;
};

static const float RtrFiniteEmissionPi=3.14159265358979323846f;

// The caller validates finite inputs, nonnegative energy, unit normals and
// directions, and 0 <= epsilon < reach before dispatch. UVs are in [0,1)^2.
// Reach is a trace budget, not attenuation. An unreachable sample still counts
// toward the original budget; do not renormalize samples that hit a receiver.
// These functions sample one emitter. If selecting it randomly, the caller
// also weights its flux by the reciprocal selection probability.
RtrFiniteOpticalEmissionSample RtrSampleFiniteDirectionalEmission(RtrDirectionalEmissionTile tile,
    uint2 cell,float3 normalIrradiance,float projectedCellArea,float epsilon,float reach)
{
    // Delta direction. Use the same ray and per-cell projected area as the
    // directional GPU pass. Tile steps divide the aperture into disjoint cells.
    // A uniformly spaced tile uses |dot(cross(stepX,stepY),direction)| per cell.
    RtrFiniteOpticalEmissionSample sample;
    sample.ray=RtrEmitDirectionalFlux(tile,cell,epsilon,reach);
    sample.flux=normalIrradiance*projectedCellArea;
    return sample;
}

RtrFiniteOpticalEmissionSample RtrMakeFiniteEmission(float3 origin,float3 direction,
    float3 flux,float epsilon,float reach)
{
    RtrFiniteOpticalEmissionSample sample;
    sample.ray.Origin=origin;
    sample.ray.Direction=direction;
    sample.ray.TMin=epsilon;
    sample.ray.TMax=reach;
    sample.flux=flux;
    return sample;
}

RtrFiniteOpticalEmissionSample RtrSampleFinitePointEmission(float3 position,float3 totalFlux,
    float inverseSampleCount,float2 directionUv,float epsilon,float reach)
{
    // Isotropic emitter: p(w)=1/(4*pi), totalFlux=4*pi*intensity. Solid-angle
    // spreading accounts for inverse square without a distance term here.
    const float z=1-2*directionUv.x;
    const float radius=2*sqrt(directionUv.x*(1-directionUv.x));
    float s,c;
    sincos(2*RtrFiniteEmissionPi*directionUv.y,s,c);
    return RtrMakeFiniteEmission(position,float3(radius*c,radius*s,z),totalFlux*inverseSampleCount,epsilon,reach);
}

float3 RtrFiniteEmissionDirection(float3 axis,float radius,float z,float azimuthUv)
{
    float s,c;
    sincos(2*RtrFiniteEmissionPi*azimuthUv,s,c);
    // Stable orthonormal basis, including both poles, without normalization.
    const float side=axis.z>=0?1.f:-1.f;
    const float a=-rcp(side+axis.z),b=axis.x*axis.y*a;
    const float3 tangent=float3(1+side*axis.x*axis.x*a,side*b,-side*axis.x);
    const float3 bitangent=float3(b,side+axis.y*axis.y*a,-axis.y);
    return tangent*(radius*c)+bitangent*(radius*s)+axis*z;
}

RtrFiniteOpticalEmissionSample RtrSampleFiniteSpotEmission(float3 position,float3 unitAxis,
    float3 peakIntensity,float innerCosine,float outerCosine,float inverseSampleCount,
    float2 directionUv,float epsilon,float reach)
{
    // The caller supplies -1 <= outerCosine <= innerCosine <= 1. At outer=1,
    // the finite peak intensity has zero integrated power, not a delta beam.
    const float width=1-outerCosine;
    if(width==0)return RtrMakeFiniteEmission(position,unitAxis,peakIntensity*0.f,epsilon,reach);
    const float fromOuter=width*directionUv.x;
    const float fromAxis=width*(1-directionUv.x);
    const float3 direction=RtrFiniteEmissionDirection(unitAxis,sqrt(fromAxis*(2-fromAxis)),1-fromAxis,directionUv.y);
    // Match direct lighting's step at equal cones. Work relative to the rim
    // to preserve the smoothstep even when its two cosine bounds are adjacent.
    float angular=1;
    if(innerCosine>outerCosine){
        const float t=saturate(fromOuter/(innerCosine-outerCosine));
        angular=t*t*(3-2*t);
    }
    // p(w)=1/(2*pi*width), so E[flux*N]=2*pi*I*(1-(inner+outer)/2).
    const float inversePdf=2*RtrFiniteEmissionPi*width;
    return RtrMakeFiniteEmission(position,direction,peakIntensity*(angular*inversePdf*inverseSampleCount),epsilon,reach);
}

RtrFiniteOpticalEmissionSample RtrSampleFiniteLambertianEmission(float3 position,float3 unitNormal,
    float3 emittedRadiance,float areaPerSample,float2 directionUv,float epsilon,float reach)
{
    // position is a uniform surface sample, independent of directionUv;
    // areaPerSample is surface area / sample count (no projected-area factor).
    // For a two-sided emitter, sample each face with its own normal and budget.
    // The cosine in p(w)=cos(theta)/pi cancels the emission cosine.
    const float radius=sqrt(directionUv.x),z=sqrt(1-directionUv.x);
    const float3 direction=RtrFiniteEmissionDirection(unitNormal,radius,z,directionUv.y);
    return RtrMakeFiniteEmission(position,direction,emittedRadiance*(RtrFiniteEmissionPi*areaPerSample),epsilon,reach);
}

RtrFiniteOpticalEmissionSample RtrSampleFiniteTriangleEmission(float3 origin,float3 edgeU,float3 edgeV,
    float3 unitNormal,float3 fluxPerSample,float2 positionUv,float2 directionUv,float epsilon,float reach)
{
    // The square-root transform is uniform over the triangle's world area.
    const float r=sqrt(positionUv.x);
    const float3 position=origin+r*((1-positionUv.y)*edgeU+positionUv.y*edgeV);
    const float radius=sqrt(directionUv.x),z=sqrt(1-directionUv.x);
    const float3 direction=RtrFiniteEmissionDirection(unitNormal,radius,z,directionUv.y);
    return RtrMakeFiniteEmission(position,direction,fluxPerSample,epsilon,reach);
}

// Uniform position on a rectangle or skewed patch. Prepare the unit normal
// and areaPerSample=length(cross(edgeU,edgeV))/sampleCount once per patch.
RtrFiniteOpticalEmissionSample RtrSampleFiniteParallelogramEmission(float3 corner,float3 edgeU,float3 edgeV,
    float3 unitNormal,float3 emittedRadiance,float areaPerSample,float2 positionUv,float2 directionUv,
    float epsilon,float reach)
{
    const float3 position=corner+positionUv.x*edgeU+positionUv.y*edgeV;
    return RtrSampleFiniteLambertianEmission(position,unitNormal,emittedRadiance,areaPerSample,directionUv,epsilon,reach);
}

#endif
