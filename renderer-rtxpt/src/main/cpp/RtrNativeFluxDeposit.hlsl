#include "RtrNativeDirectionalFlux.hlsl"

[numthreads(8,8,1)]
void main(uint3 cell:SV_DispatchThreadID)
{
    [branch]if(f.finite.x!=0)RtrDepositTriangleFlux(cell.xy);
    else RtrDepositDirectionalFlux(cell.xy,cell.z);
}
