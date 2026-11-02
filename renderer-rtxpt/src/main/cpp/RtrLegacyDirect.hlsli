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
        // This visibility segment stays straight: its direction is in the outside
        // angular domain, including at an exit face. Feeding that angle to the
        // inside-to-outside Fresnel calculation invents total internal reflection.
        // Use the reciprocal outside-domain boundary approximation, as in NVIDIA
        // RTX Remix algorithm/visibility.slangh (outsideFresnel/insideFresnel).
        // Real scatter rays still use their actual eta and retain physical TIR.
        float eta=hit.shadingData.GetEta();
        float visibilityEta=min(eta,rcp(max(eta,1e-7)));
        float cosine=abs(dot(inputRay.Direction,hit.shadingData.N));
        float fresnel=RtrLegacyFresnel(visibilityEta,cosine);
        #if defined(RTR_LEGACY_TRANSPORT_TRACE) && RTR_LEGACY_TRANSPORT_TRACE
        if(RtrLegacyTracePixel(source.GetPixelPos(),context.PtConsts.frameIndex) && (source.getCounter((PackedCounters)3)&4u)!=0)
            RtrLegacyTracePrint("shadow",104u,RtrLegacyTraceKey(source.GetPixelPos(),RtrLegacyTraceBranch(source.getCounter((PackedCounters)3)),source.getVertexIndex(),context.PtConsts.frameIndex),
                float4(event,hit.shadingData.MaterialID(),t_PTMaterialData[hit.shadingData.MaterialID()].TransmissionFactor,transmission),
                float4(hit.shadingData.frontFacing,hit.shadingData.GetEta(),abs(dot(inputRay.Direction,hit.shadingData.N)),fresnel),
                inputRay.Direction,hit.shadingData.posW,throughput,
                float2(visibilityEta,RtrLegacyFresnel(eta,cosine)));
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
    // Second-junction replay shares its prefix lighting with the first replay.
    if(path.getVertexIndex()<=2 && (path.getCounter((PackedCounters)3)&8u)!=0)return result;
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
            RtrLegacyTracePrint("light",103u,RtrLegacyTraceKey(path.GetPixelPos(),RtrLegacyTraceBranch(path.getCounter((PackedCounters)3)),path.getVertexIndex(),context.PtConsts.frameIndex),i,sd.posW,inputRadiance,radiance);
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
        RtrLegacyTracePrint("receiver",101u,RtrLegacyTraceKey(path.GetPixelPos(),RtrLegacyTraceBranch(path.getCounter((PackedCounters)3)),path.getVertexIndex(),context.PtConsts.frameIndex),weight,(diffuse+specular)*weight);
    #endif
    result.AccumulateRadiance((diffuse+specular)*weight,Average(specular*weight));
    return result;
}
