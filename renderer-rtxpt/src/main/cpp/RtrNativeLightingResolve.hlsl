Texture2D<float4> t_Reconstructed : register(t0);
Texture2D<float4> t_Raw : register(t1);
RWTexture2D<float4> u_Result : register(u0);
[numthreads(8,8,1)]
void main(uint2 pixel:SV_DispatchThreadID) {
    uint width,height;t_Raw.GetDimensions(width,height);if(any(pixel>=uint2(width,height)))return;
    float coverage=t_Raw[pixel].a;float3 value=t_Reconstructed[pixel].rgb;
    value=float3(isfinite(value.x)?clamp(value.x,0,65504):0,isfinite(value.y)?clamp(value.y,0,65504):0,isfinite(value.z)?clamp(value.z,0,65504):0);
    u_Result[pixel]=float4(coverage>0?value:0,coverage);
}
