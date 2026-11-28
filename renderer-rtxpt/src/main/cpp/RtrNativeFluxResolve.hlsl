#include "RtrNativeDirectionalFlux.hlsl"

[numthreads(8,8,1)]
void main(uint2 pixel:SV_DispatchThreadID)
{
    RtrResolveDirectionalFlux(pixel);
}
