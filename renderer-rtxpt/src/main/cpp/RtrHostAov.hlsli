#ifndef RTR_HOST_AOV_HLSLI
#define RTR_HOST_AOV_HLSLI
// The two unused high vertex-index bits survive the existing 80-byte payload
// and denoising-layer storage. Host mode admits fewer than 256 path vertices.
void RtrHostAovAdd(uint2 pixel,uint signal,float3 radiance)
{
#if defined(RTR_HOST_PRIMARY) && RTR_HOST_PRIMARY
    uint bit=1u<<signal;
    if((g_RtrHostAov.x&bit)==0||!all(isfinite(radiance)))return;
    uint plane=countbits(g_RtrHostAov.y&(bit-1));
    uint index=(plane*g_RtrHostAov.w+pixel.y)*g_RtrHostAov.z+pixel.x;
    u_RtrHostAov[index]+=float4(max(radiance,0),0);
#endif
}
uint RtrHostLobeBits(PathState path)
{
#if defined(RTR_HOST_PRIMARY) && RTR_HOST_PRIMARY
    return path.flagsAndVertexIndex&768u;
#else
    return 0;
#endif
}
bool RtrHostOwnsPrimaryLight(PathState path,bool environment)
{
#if defined(RTR_HOST_PRIMARY) && RTR_HOST_PRIMARY
    return g_RtrHost.extent.z!=0&&path.getVertexIndex()==1&&(g_RtrHost.counts.y&(environment?2u:1u))!=0;
#else
    return false;
#endif
}
// Source kinds: 0 accumulated lighting, 1 emissive surface, 2 environment.
// Filter both NEE and its BSDF-sampled counterpart before radiance accumulation.
bool RtrHostOwnsSource(PathState path,uint sourceKind)
{
#if defined(RTR_HOST_PRIMARY) && RTR_HOST_PRIMARY
    if(g_RtrHost.extent.z==0||sourceKind==0)return false;
    uint vertex=path.getVertexIndex();
    if(vertex==1)return sourceKind==1&&(g_RtrHost.counts.y&8u)!=0;
    if(vertex==2)return (g_RtrHost.counts.y&(sourceKind==2?2u:1u))!=0;
#endif
    return false;
}
void RtrHostAovRadiance(PathState path,float3 radiance,uint sourceKind)
{
#if defined(RTR_HOST_PRIMARY) && RTR_HOST_PRIMARY
    if(g_RtrHost.extent.z==0)return;
    uint vertex=path.getVertexIndex(),lobe=(path.flagsAndVertexIndex>>8)&3;
    if(vertex==0)return;
    uint signal=6;
    if(vertex>1){
        signal=lobe==3?5:lobe==2?4:(sourceKind!=0&&vertex==2?2:3);
    }else if(sourceKind==0)return; // Primary NEE records its actual RGB BSDF lobes.
    RtrHostAovAdd(path.GetPixelPos(),signal,radiance*Bridge::getNoisyRadianceAttenuation());
#endif
}
void RtrHostAovDirect(PathState path,float3 diffuse,float3 specular,float3 transmission)
{
#if defined(RTR_HOST_PRIMARY) && RTR_HOST_PRIMARY && PATH_TRACER_MODE!=PT_DENOISING_LAYERS_BUILD
    if(g_RtrHost.extent.z==0||path.getVertexIndex()!=1)return;
    float scale=Bridge::getNoisyRadianceAttenuation();
    RtrHostAovAdd(path.GetPixelPos(),2,diffuse*scale);
    RtrHostAovAdd(path.GetPixelPos(),4,specular*scale);
    RtrHostAovAdd(path.GetPixelPos(),5,transmission*scale);
#endif
}
#endif
