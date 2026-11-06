// Select one current receiver for every RR texel. Color and every guide come
// from the exact same source pixel; no blended depth, normals or identities.
Texture2D<float4> t_Raw : register(t0);
Texture2D<float4> t_Albedo : register(t1);
Texture2D<float4> t_Normal : register(t2);
Texture2D<float2> t_Motion : register(t3);
Texture2D<float> t_Depth : register(t4);
RWTexture2D<float4> u_Raw : register(u0);
RWTexture2D<float4> u_Albedo : register(u1);
RWTexture2D<float4> u_Normal : register(u2);
RWTexture2D<float2> u_Motion : register(u3);
RWTexture2D<float> u_Depth : register(u4);
[numthreads(8,8,1)]
void main(uint2 pixel:SV_DispatchThreadID) {
    uint fw,fh,w,h;t_Raw.GetDimensions(fw,fh);u_Raw.GetDimensions(w,h);
    if(any(pixel>=uint2(w,h)))return;
    uint2 source=min(uint2((float2(pixel)+.5)*float2(fw,fh)/float2(w,h)),uint2(fw,fh)-1);
    u_Raw[pixel]=t_Raw[source];u_Albedo[pixel]=t_Albedo[source];u_Normal[pixel]=t_Normal[source];
    // Motion is normalized screen displacement, independent of pixel extent.
    u_Motion[pixel]=t_Motion[source];u_Depth[pixel]=t_Depth[source];
}
