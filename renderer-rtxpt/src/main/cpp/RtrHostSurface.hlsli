#ifndef RTR_HOST_SURFACE_HLSLI
#define RTR_HOST_SURFACE_HLSLI
uint2 RtrHostPixel(uint2 pixel)
{
    return min(uint2((float2(pixel)+.5)*float2(g_RtrHost.extent.xy)/g_Const.view.viewportSize),g_RtrHost.extent.xy-1);
}
#if defined(RTR_HOST_PRIMARY) && RTR_HOST_PRIMARY
// ABI 1: host-sampled world-space surfaces. Secondary hits keep the resident scene BSDF.
bool RtrHostFindInstance(uint2 id,out RtrHostInstance value)
{
    uint lo=0,hi=g_RtrHost.counts.x;
    while(lo<hi){uint mid=lo+(hi-lo)/2;RtrHostInstance candidate=t_RtrHostInstances[mid];
        bool less=candidate.id.y<id.y||(candidate.id.y==id.y&&candidate.id.x<id.x);
        if(less)lo=mid+1;else hi=mid;}
    value=(RtrHostInstance)0;
    if(lo>=g_RtrHost.counts.x)return false;
    value=t_RtrHostInstances[lo];return all(value.id==id);
}
// 0: controlled primary fallback, 1: supplied surface, 2: explicit background/invalid.
uint RtrHostLoadSurface(uint2 pixel,float3 rayDirection,out PathTracer::Surface surface,out float distance)
{
    surface=(PathTracer::Surface)0;distance=0;
    uint2 p=RtrHostPixel(pixel);float4 position=t_RtrHostPosition[p];
    uint flags=isfinite(position.w)&&position.w>=0&&position.w<=15?uint(position.w):0;
    if((flags&8)!=0)return 2;
    if((flags&1)==0)return (flags&4)!=0?0:2;
    float4 geometric=t_RtrHostGeometric[p],shading=t_RtrHostShading[p];
    float4 base=t_RtrHostBase[p],material=t_RtrHostMaterial[p],emission=t_RtrHostEmission[p];
    uint4 identity=t_RtrHostIdentity[p];RtrHostInstance instance;
    bool valid=all(isfinite(position.xyz))&&all(isfinite(geometric))&&all(isfinite(shading))
        &&all(isfinite(base))&&all(isfinite(material))&&all(isfinite(emission))
        &&dot(geometric.xyz,geometric.xyz)>.01&&dot(shading.xyz,shading.xyz)>.01
        &&geometric.w>0&&geometric.w<=1&&base.w>=0&&base.w<=1&&shading.w>=0&&shading.w<=1&&base.w>=shading.w&&material.x>=0&&material.x<=1
        &&material.y>=0&&material.y<=1&&material.z>=1&&material.z<=4
        &&material.w>=0&&material.w<=1&&identity.w==0&&RtrHostFindInstance(identity.xy,instance);
    if(!valid||identity.z>=instance.triangleCount)return (flags&4)!=0?0:2;
    // Raster alpha blending has no unique opaque continuation. An explicitly
    // permitted fallback traces that region; cutout and BSDF transmission reuse
    // the supplied front surface directly.
    if((flags&2)==0&&base.w<1&&material.w==0)return (flags&4)!=0?0:2;
    uint2 triangleRef=t_RtrHostTriangles[instance.triangleOffset+identity.z];
    uint materialID=t_SubInstanceData[t_InstanceData[instance.instanceIndex].firstGeometryInstanceIndex+triangleRef.x].AlphaTextureIndex_PTMaterialDataIndex&0xffff;
    PTMaterialData source=t_PTMaterialData[materialID];
    ShadingData data=ShadingData::make();data.posW=position.xyz;data.toPrevPosW=0;
    data.V=-rayDirection;float3 normal=normalize(geometric.xyz);
    data.frontFacing=dot(normal,rayDirection)<0;
    data.faceNCorrected=data.frontFacing?normal:-normal;
    data.N=normalize(shading.xyz)*(data.frontFacing?1:-1);
    data.SetGeometryNormalAndShadowTerminatorK(data.faceNCorrected,0);
    data.SetTangentW(float4(1,0,0,1));
    data.AdjustShadingNormalAndComputeTangentSpace(true,true);
    data.SetMaterialIDAndPosErrorScale(materialID,max(1,max(abs(position.x),max(abs(position.y),abs(position.z)))));
    data.SetIoR(1,material.z);data.shadingFlags=ShadingFlags::make();
    data.shadingFlags.setNestedPriority(min(InteriorList::kMaxNestedPriority,1+(uint(source.Flags)>>PTMaterialFlags_NestedPriorityShift)));
    data.shadingFlags.setThinSurface((source.Flags&PTMaterialFlags_ThinSurface)!=0);
    data.shadingFlags.setPSDExclude((source.Flags&PTMaterialFlags_PSDExclude)!=0);
    data.shadingFlags.setPSDDominantDeltaLobeP1((source.Flags&PTMaterialFlags_PSDDominantDeltaLobeP1Mask)>>PTMaterialFlags_PSDDominantDeltaLobeP1Shift);
    data.shadingFlags.setActiveLobes((uint)LobeType::All);
    data.SetEmission(max(emission.rgb,0));
    float f=(material.z-1)/(material.z+1);float3 color=max(base.rgb,0);
    StandardBSDF bsdf=StandardBSDF::make(StandardBSDFData::make(
        color*(1-material.y),lerp((f*f).xxx,color,material.y),material.x,material.y,color,0,material.w*(1-material.y)));
    surface=PathTracer::Surface::make(data,bsdf,0xffffffff,0xffffffff);
    distance=length(position.xyz-g_Const.ptConsts.camera.PosW);
    return isfinite(distance)&&distance>0?1:2;
}
#endif
#endif
