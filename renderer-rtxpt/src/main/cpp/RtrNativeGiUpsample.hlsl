Texture2D<float4> t_Reconstructed : register(t0);
Texture2D<float4> t_Raw : register(t1);
Texture2D<float4> t_Albedo : register(t2);
Texture2D<float4> t_Normal : register(t3);
Texture2D<uint4> t_Surface : register(t4);
RWTexture2D<float4> u_Result : register(u0);
[numthreads(8,8,1)]
void main(uint2 pixel:SV_DispatchThreadID) {
    uint fw,fh,w,h;t_Raw.GetDimensions(fw,fh);t_Reconstructed.GetDimensions(w,h);
    if(any(pixel>=uint2(fw,fh)))return;
    float4 raw=t_Raw[pixel];uint4 surface=t_Surface[pixel];
    float3 value=raw.rgb;float weight=0;float3 sum=0;
    if(raw.a>0&&surface.w!=0){
        float2 at=(float2(pixel)+.5)*float2(w,h)/float2(fw,fh)-.5;
        int2 lower=int2(floor(at));float2 fraction=frac(at);
        float4 normal=t_Normal[pixel];float3 albedo=t_Albedo[pixel].rgb;
        [unroll] for(int y=0;y<2;y++)[unroll] for(int x=0;x<2;x++){
            int2 q=clamp(lower+int2(x,y),int2(0,0),int2(w,h)-1);
            uint2 source=min(uint2((float2(q)+.5)*float2(fw,fh)/float2(w,h)),uint2(fw,fh)-1);
            uint4 other=t_Surface[source];float4 n=t_Normal[source];float3 a=t_Albedo[source].rgb;
            bool compatible=other.w!=0&&all(surface.xy==other.xy)
                &&abs(asfloat(surface.z)-asfloat(other.z))<=max(.015,asfloat(surface.z)*.02)
                &&dot(normal.xyz,n.xyz)>=.98&&abs(normal.w-n.w)<=.1
                &&all(abs(albedo-a)<=.08)&&t_Raw[source].a>0;
            float sampleWeight=(x?fraction.x:1-fraction.x)*(y?fraction.y:1-fraction.y);
            if(compatible&&sampleWeight>0){float3 radiance=t_Reconstructed[q].rgb;
                if(all(isfinite(radiance))){sum+=radiance*sampleWeight;weight+=sampleWeight;}}
        }
        if(weight>1e-6)value=sum/weight;
    }
    value=float3(isfinite(value.x)?clamp(value.x,0,65504):0,isfinite(value.y)?clamp(value.y,0,65504):0,isfinite(value.z)?clamp(value.z,0,65504):0);
    u_Result[pixel]=float4(raw.a>0?value:0,raw.a);
}
