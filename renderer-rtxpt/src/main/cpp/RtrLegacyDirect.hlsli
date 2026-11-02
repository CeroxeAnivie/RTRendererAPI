// Direct port of gsEvaluatePbrSurface / gsSceneLight / gsDirectContribution.
// Called at every shading receiver, including receivers reached through glass.
float3 RtrLegacyShadowVisibility(const RayDesc inputRay,const PathState source,const WorkingContext context)
{
    // Keep the common unblocked case on the native fast visibility query.
    if(Bridge::traceVisibilityRay(inputRay,source.rayCone,source.getVertexIndex(),source.GetPixelPos(),context.Debug))return 1;
    PathState shadow=source;shadow.SetOrigin(inputRay.Origin);shadow.SetDir(inputRay.Direction);
    float remaining=inputRay.TMax;float3 throughput=1;
    [loop]for(uint event=0;event<Bridge::getMaxBounceLimit();event++)
    {
        RayQuery<RAY_FLAG_SKIP_PROCEDURAL_PRIMITIVES,RAYQUERY_FLAG_ALLOW_OPACITY_MICROMAPS> query;
        Bridge::traceScatterRay(shadow,query,float2(0,remaining),context.Debug);
        if(query.CommittedStatus()!=COMMITTED_TRIANGLE_HIT)return throughput;
        Surface hit=Bridge::loadSurface(query.CommittedInstanceIndex(),query.CommittedGeometryIndex(),query.CommittedPrimitiveIndex(),
            query.CommittedTriangleBarycentrics(),inputRay.Direction,shadow.rayCone,source.getVertexIndex(),source.GetPixelPos(),context.Debug);
        float transmission=hit.bsdf.data.SpecularTransmission();
        // Same straight-through direct-light approximation as gsShadowVisibilityDetailed.
        float fresnel=RtrLegacyFresnel(hit.shadingData.GetEta(),abs(dot(inputRay.Direction,hit.shadingData.N)));
        #if defined(RTR_LEGACY_TRANSPORT_TRACE) && RTR_LEGACY_TRANSPORT_TRACE
        if(RtrLegacyTracePixel(source.GetPixelPos(),context.PtConsts.frameIndex) && (source.getCounter((PackedCounters)3)&4u)!=0)
            RtrLegacyTracePrint("shadow",104u,uint4(source.GetPixelPos(),(source.getCounter((PackedCounters)3)&3u)-1u,source.getVertexIndex()),
                float4(event,hit.shadingData.MaterialID(),t_PTMaterialData[hit.shadingData.MaterialID()].TransmissionFactor,transmission),
                float4(hit.shadingData.frontFacing,hit.shadingData.GetEta(),abs(dot(inputRay.Direction,hit.shadingData.N)),fresnel),
                inputRay.Direction,hit.shadingData.posW,throughput);
        #endif
        if(transmission<=0)return 0;
        throughput*=transmission*(1-fresnel)*hit.bsdf.data.Transmission();
        if(max3(throughput)<=0)return 0;
        float3 position=hit.shadingData.posW;
        float epsilon=max(1e-5,max3(abs(position))*2e-7);
        remaining-=query.CommittedRayT()+epsilon;
        if(remaining<=0)return throughput;
        shadow.SetOrigin(position+inputRay.Direction*epsilon);
    }
    return 0;
}

NEEResult RtrLegacyDirect(const PathState path,const ShadingData sd,const ActiveBSDF bsdf,const WorkingContext context)
{
    NEEResult result=NEEResult::empty();
    // Shared primary receiver is shaded once; only its optical continuations split.
    if(path.getVertexIndex()==1 && (path.getCounter((PackedCounters)3)&3u)==2u)return result;
    float surfaceWeight=1-bsdf.data.SpecularTransmission();
    if(surfaceWeight<=0)return result;
    float3 baseColor=RtrLegacyBaseColor(sd,bsdf.data);
    float metallic=saturate(float(bsdf.data.Metallic())),roughness=clamp(float(bsdf.data.Roughness()),.025,1);
    float3 diffuse=baseColor*(1-metallic)*context.PtConsts.RtrLegacyAmbient.rgb;
    float3 specular=0;
    for(uint i=0;i<context.PtConsts.RtrLegacyLightCount;i++)
    {
        RtrLegacyLight light=t_RtrLegacyLights[i];
        float attenuation=1,maximumDistance=1e20;float3 direction;
        if(light.PositionType.w==0)direction=-normalize(light.DirectionRange.xyz);
        else
        {
            float3 delta=light.PositionType.xyz-sd.posW;maximumDistance=length(delta);
            if(maximumDistance<=1e-4)continue;
            direction=delta/maximumDistance;
            float fade=saturate(1-maximumDistance/max(light.DirectionRange.w,1e-4));
            attenuation=fade*fade/max(maximumDistance*maximumDistance,1);
            if(light.PositionType.w==2)
            {
                float cone=dot(normalize(light.DirectionRange.xyz),-direction);
                attenuation*=light.ConeShadow.x==light.ConeShadow.y?step(light.ConeShadow.y,cone):smoothstep(light.ConeShadow.y,light.ConeShadow.x,cone);
            }
        }
        float3 radiance=light.ColorIntensity.rgb*light.ColorIntensity.a*attenuation;
        float3 inputRadiance=radiance;
        float nl=max(dot(sd.N,direction),0);
        if(nl<=0 || max3(radiance)<=0)continue;
        if(light.ConeShadow.z>0)
        {
            LightSample sample=LightSample::make();sample.Direction=direction;sample.Distance=maximumDistance;
            RayDesc ray=ComputeVisibilityRay(sample,sd);
            radiance*=RtrLegacyShadowVisibility(ray,path,context);
        }
        #if defined(RTR_LEGACY_TRANSPORT_TRACE) && RTR_LEGACY_TRANSPORT_TRACE
        if(RtrLegacyTracePixel(path.GetPixelPos(),context.PtConsts.frameIndex) && (path.getCounter((PackedCounters)3)&4u)!=0)
            RtrLegacyTracePrint("light",103u,uint4(path.GetPixelPos(),(path.getCounter((PackedCounters)3)&3u)-1u,path.getVertexIndex()),i,sd.posW,inputRadiance,radiance);
        #endif
        if(max3(radiance)<=0)continue;
        float3 h=normalize(sd.V+direction);
        float nv=max(dot(sd.N,sd.V),0),nh=max(dot(sd.N,h),0),vh=max(dot(sd.V,h),0);
        float3 f0=lerp(.04.xxx,baseColor,metallic),f=f0+(1-f0)*pow(1-vh,5);
        float alpha=max(roughness*roughness,.0025),a2=alpha*alpha,denom=nh*nh*(a2-1)+1;
        float distribution=a2/max(K_PI*denom*denom,1e-6);
        float geometry=RtrLegacyGeometry(nv,nl,roughness);
        diffuse+=(1-f)*(1-metallic)*baseColor*(nl/K_PI)*radiance;
        specular+=f*distribution*geometry/max(4*nv*nl,1e-5)*nl*radiance;
    }
    float3 weight=path.GetThp()*surfaceWeight;
    #if defined(RTR_LEGACY_TRANSPORT_TRACE) && RTR_LEGACY_TRANSPORT_TRACE
    if(RtrLegacyTracePixel(path.GetPixelPos(),context.PtConsts.frameIndex) && (path.getCounter((PackedCounters)3)&4u)!=0)
        RtrLegacyTracePrint("receiver",101u,uint4(path.GetPixelPos(),(path.getCounter((PackedCounters)3)&3u)-1u,path.getVertexIndex()),weight,(diffuse+specular)*weight);
    #endif
    result.AccumulateRadiance((diffuse+specular)*weight,Average(specular*weight));
    return result;
}
