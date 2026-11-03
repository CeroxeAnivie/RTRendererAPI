cbuffer Parameters:register(b0){uint4 sizes;uint4 masks;}
StructuredBuffer<float4> t_Aov:register(t0);
Texture2D<float4> t_Combined:register(t1);
Texture2D<float4> t_Position:register(t2);
Texture2D<float4> t_NormalCoverage:register(t3);
Texture2D<uint4> t_Identity:register(t4);
Texture2D<float4> t_Base:register(t5);
Texture2D<float4> t_Shading:register(t6);
Texture2D<float4> t_Material:register(t7);
Texture2D<float4> t_Emission:register(t8);
struct HostInstance {uint2 id;uint instanceIndex,triangleOffset,triangleCount;uint3 reserved;};
StructuredBuffer<HostInstance> t_Instances:register(t9);
RWTexture2D<float4> u_Combined:register(u0);
RWTexture2D<float4> u_Modulation:register(u1);
RWTexture2D<float4> u_Direct:register(u2);
RWTexture2D<float4> u_Indirect:register(u3);
RWTexture2D<float4> u_Specular:register(u4);
RWTexture2D<float4> u_Transmission:register(u5);
RWTexture2D<float4> u_Emission:register(u6);
RWTexture2D<float4> u_Coverage:register(u7);
uint2 HostPixel(uint2 p){uint w,h;t_Position.GetDimensions(w,h);return min(uint2((float2(p)+.5)*float2(w,h)/float2(sizes.zw)),uint2(w,h)-1);}
uint2 RawHostPixel(uint2 p){uint w,h;t_Position.GetDimensions(w,h);return min(uint2((float2(p)+.5)*float2(w,h)/float2(sizes.xy)),uint2(w,h)-1);}
uint HostFlags(float4 position){return isfinite(position.w)&&position.w>=0&&position.w<=15?uint(position.w):0;}
bool FilterableSurface(uint2 pixel,float4 position,float4 normal,uint4 identity)
{
    uint flags=HostFlags(position);
    float4 base=t_Base[pixel],shading=t_Shading[pixel],material=t_Material[pixel];
    if((flags&1)==0||(flags&8)!=0||!all(isfinite(position.xyz))||!all(isfinite(normal))
        ||dot(normal.xyz,normal.xyz)<=.01||normal.w<=0||normal.w>1||identity.w!=0
        ||!all(isfinite(base))||!all(isfinite(shading))||!all(isfinite(material))||!all(isfinite(t_Emission[pixel]))
        ||dot(shading.xyz,shading.xyz)<=.01||shading.w<0||shading.w>1||base.a<shading.w||base.a<0||base.a>1
        ||any(material.xy<0)||any(material.xy>1)||material.z<1||material.z>4||material.w<0||material.w>1
        ||((flags&2)==0&&base.a<1&&material.w==0))return false;
    uint lo=0,hi=masks.z;
    while(lo<hi){uint mid=lo+(hi-lo)/2;uint2 id=t_Instances[mid].id;
        if(id.y<identity.y||(id.y==identity.y&&id.x<identity.x))lo=mid+1;else hi=mid;}
    if(lo>=masks.z)return false;
    HostInstance instance=t_Instances[lo];
    return all(instance.id==identity.xy)&&identity.z<instance.triangleCount
        &&instance.triangleOffset<masks.w&&identity.z<masks.w-instance.triangleOffset;
}
float3 Reconstruct(uint2 pixel,uint signal)
{
    uint bit=1u<<signal;if((masks.y&bit)==0)return 0;
    uint layer=countbits(masks.y&(bit-1));uint2 hp=HostPixel(pixel);
    uint4 identity=t_Identity[hp];float4 position=t_Position[hp],normal=t_NormalCoverage[hp];
    uint flags=HostFlags(position);
    if((flags&8)!=0||((flags&1)==0&&(flags&4)==0))return 0;
    if(!FilterableSurface(hp,position,normal,identity)){
        // A traced fallback has no supplied surface metadata. Preserve its
        // nearest estimate without blending unrelated fallback surfaces.
        if((flags&4)==0)return 0;
        uint2 nearest=min(uint2((float2(pixel)+.5)*float2(sizes.xy)/float2(sizes.zw)),sizes.xy-1);
        return t_Aov[(layer*sizes.y+nearest.y)*sizes.x+nearest.x].rgb;
    }
    float3 unitNormal=normal.xyz*rsqrt(dot(normal.xyz,normal.xyz));
    float2 location=(float2(pixel)+.5)*float2(sizes.xy)/float2(sizes.zw)-.5;
    int2 first=int2(floor(location));float3 result=0;float total=0;
    [unroll]for(int y=0;y<2;y++)[unroll]for(int x=0;x<2;x++){
        uint2 p=uint2(clamp(first+int2(x,y),int2(0,0),int2(sizes.xy)-1));
        uint2 host=RawHostPixel(p);float4 otherPosition=t_Position[host],other=t_NormalCoverage[host];
        uint4 otherIdentity=t_Identity[host];
        if(!FilterableSurface(host,otherPosition,other,otherIdentity)||any(otherIdentity!=identity))continue;
        float3 otherNormal=other.xyz*rsqrt(dot(other.xyz,other.xyz));
        if(dot(unitNormal,otherNormal)<.9)continue;
        float3 delta=otherPosition.xyz-position.xyz;
        float tolerance=max(.002,length(position.xyz)*2e-5);
        if(abs(dot(delta,unitNormal))>tolerance)continue;
        float2 weight=max(0,1-abs(location-float2(p)));float w=weight.x*weight.y;
        result+=t_Aov[(layer*sizes.y+p.y)*sizes.x+p.x].rgb*w;total+=w;
    }
    // No valid neighbour means no new contribution, never a different surface's light.
    return total>0?result/total:0;
}
[numthreads(8,8,1)]void main(uint2 pixel:SV_DispatchThreadID){
    if(any(pixel>=sizes.zw))return;uint2 hp=HostPixel(pixel);float4 position=t_Position[hp];
    uint flags=HostFlags(position);
    float4 combined=t_Combined[pixel];
    bool supplied=FilterableSurface(hp,position,t_NormalCoverage[hp],t_Identity[hp]);
    float coverage=supplied?t_NormalCoverage[hp].w:((flags&8)==0&&(flags&4)!=0?saturate(combined.a):0);
    if((flags&2)==0&&supplied)coverage*=t_Base[hp].a;
    if((masks.x&1)!=0)u_Combined[pixel]=float4(combined.rgb,coverage);
    if((masks.x&2)!=0)u_Modulation[pixel]=float4(1,1,1,coverage);
    if((masks.x&4)!=0)u_Direct[pixel]=float4(Reconstruct(pixel,2),coverage);
    if((masks.x&8)!=0)u_Indirect[pixel]=float4(Reconstruct(pixel,3),coverage);
    if((masks.x&16)!=0)u_Specular[pixel]=float4(Reconstruct(pixel,4),coverage);
    if((masks.x&32)!=0)u_Transmission[pixel]=float4(Reconstruct(pixel,5),coverage);
    if((masks.x&64)!=0)u_Emission[pixel]=float4(Reconstruct(pixel,6),coverage);
    if((masks.x&128)!=0)u_Coverage[pixel]=float4(coverage,coverage,coverage,coverage);
}
