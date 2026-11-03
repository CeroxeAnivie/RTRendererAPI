// Compact output conversion only. The lighting/extension UAV ABI stays RGBA16F.
cbuffer Parameters : register(b0) {uint2 extent;float maximumGi;uint signals;};
Texture2D<float4> directInput : register(t0);
Texture2D<float4> indirectInput : register(t1);
RWTexture2D<float4> directOutput : register(u0);
RWTexture2D<float4> indirectOutput : register(u1);
float GainCode(float value)
{
    float g=isfinite(value)?clamp(value,0,15.65):1;
    return clamp(floor((g<=1?128*g:128+32*log2(g))+.5),0,255)/255;
}
[numthreads(8,8,1)]
void main(uint2 p:SV_DispatchThreadID)
{
    if(any(p>=extent))return;
    if((signals&1)!=0){float4 value=directInput[p];
        directOutput[p]=float4(GainCode(value.x),GainCode(value.y),GainCode(value.z),saturate(value.w));}
    if((signals&2)!=0){float4 value=indirectInput[p];float3 contribution=all(isfinite(value.rgb))?max(value.rgb,0):0;
        float3 code=floor(sqrt(saturate(contribution/maximumGi))*255+.5)/255;
        indirectOutput[p]=float4(code,saturate(value.w));}
}
