#ifndef RTR_OPTICAL_EMISSION_HLSLI
#define RTR_OPTICAL_EMISSION_HLSLI

// A tile covers a disjoint region in the plane perpendicular to the incoming
// beam. The precomputed per-axis steps include the tile extent divided by its
// sample count; projected area is therefore constant within a directional tile.
struct RtrDirectionalEmissionTile {
    float3 origin;
    float3 stepX,stepY;
    float3 direction;
    uint2 extent;
    uint seed;
};

uint RtrOpticalEmissionHash(uint value)
{
    value=value*747796405u+2891336453u;
    uint word=((value>>((value>>28u)+4u))^value)*277803737u;
    return (word>>22u)^word;
}

// A stable, stratified sample avoids clumping without introducing per-frame
// flicker. Each lane carries projected world area, not an arbitrary light gain.
RayDesc RtrEmitDirectionalFlux(RtrDirectionalEmissionTile tile,uint2 cell,float epsilon,float reach)
{
    const uint sample=RtrOpticalEmissionHash(tile.seed+cell.x+tile.extent.x*cell.y);
    const float2 offset=(float2(sample&0xffffu,sample>>16u)+0.5f)*(1.f/65536.f);
    RayDesc ray;
    ray.Origin=tile.origin+(float(cell.x)+offset.x)*tile.stepX+(float(cell.y)+offset.y)*tile.stepY;
    ray.Direction=tile.direction;
    ray.TMin=epsilon;ray.TMax=reach;
    return ray;
}

#endif
