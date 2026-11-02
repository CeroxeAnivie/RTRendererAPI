// Direct port of gsEvaluatePbrSurface / gsSceneLight / gsDirectContribution.
// Called at every shading receiver, including receivers reached through glass.
#if RTR_SCENE_LIGHT_STATS
RWByteAddressBuffer u_RtrLightStats : register(u96);
void RtrCountLightWork(uint byteOffset) { u_RtrLightStats.InterlockedAdd(byteOffset,1); }
#else
void RtrCountLightWork(uint byteOffset) { }
#endif
float3 RtrSceneShadowVisibility(const RayDesc inputRay,const PathState source,const WorkingContext context)
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
        float fresnel=RtrSceneFresnel(visibilityEta,cosine);
        #if defined(RTR_SCENE_TRANSPORT_TRACE) && RTR_SCENE_TRANSPORT_TRACE
        if(RtrSceneTracePixel(source.GetPixelPos(),context.PtConsts.frameIndex) && (source.getCounter((PackedCounters)3)&4u)!=0)
            RtrSceneTracePrint("shadow",104u,RtrSceneTraceKey(source.GetPixelPos(),RtrSceneTraceBranch(source.getCounter((PackedCounters)3)),source.getVertexIndex(),context.PtConsts.frameIndex),
                float4(event,hit.shadingData.MaterialID(),t_PTMaterialData[hit.shadingData.MaterialID()].TransmissionFactor,transmission),
                float4(hit.shadingData.frontFacing,hit.shadingData.GetEta(),abs(dot(inputRay.Direction,hit.shadingData.N)),fresnel),
                inputRay.Direction,hit.shadingData.posW,throughput,
                float2(visibilityEta,RtrSceneFresnel(eta,cosine)));
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

float RtrLightImportance(RtrSceneLight node,float3 position)
{
    float3 delta=max(max(node.PositionType.xyz-position,position-node.DirectionRange.xyz),0);
    float distanceSquared=dot(delta,delta);
    if(node.ColorIntensity.y==0 && sqrt(distanceSquared)>=node.ColorIntensity.z)return 0;
    return node.ColorIntensity.x/(node.ColorIntensity.y>0?1:max(distanceSquared,1));
}

uint RtrSelectSceneLight(float3 position,float u,uint count,out float probability)
{
    uint index=0;probability=1;
    [loop]for(uint depth=0;depth<32;depth++)
    {
        RtrSceneLight node=t_RtrSceneLights[count+index];
        RtrCountLightWork(8);
        if(node.ColorIntensity.w>=0)return uint(node.ColorIntensity.w);
        uint left=uint(node.PositionType.w),right=uint(node.DirectionRange.w);
        RtrSceneLight a=t_RtrSceneLights[count+left],b=t_RtrSceneLights[count+right];
        float wa=RtrLightImportance(a,position),wb=RtrLightImportance(b,position);
        float uniformProbability=a.ConeShadow.x/node.ConeShadow.x;
        // A uniform mixture preserves support even for a weak or occluded light.
        float p=wa+wb>0?lerp(uniformProbability,wa/(wa+wb),.95):uniformProbability;
        if(u<p){index=left;probability*=p;u=min(u/p,.99999994);}
        else{index=right;probability*=1-p;u=min((u-p)/(1-p),.99999994);}
    }
    return 0; // Balanced CPU construction has depth <= 21 at native capacity.
}

NEEResult RtrSceneDirect(const PathState path,const ShadingData sd,const ActiveBSDF bsdf,const WorkingContext context)
{
    NEEResult result=NEEResult::empty();
    // Every replay prefix is shaded once, before its weighted branches diverge.
    if(RtrSceneReplayPrefixDuplicate(path.getCounter((PackedCounters)3),path.getVertexIndex()))return result;
    float surfaceWeight=1-bsdf.data.SpecularTransmission();
    if(surfaceWeight<=0)return result;
    RtrCountLightWork(0);
    float3 baseColor=RtrSceneBaseColor(sd,bsdf.data);
    float metallic=saturate(float(bsdf.data.Metallic())),roughness=clamp(float(bsdf.data.Roughness()),.025,1);
    float3 diffuse=baseColor*(1-metallic)*context.PtConsts.RtrSceneAmbient.rgb;
    float3 specular=0;
    uint count=context.PtConsts.RtrSceneLightCount;
    bool exhaustive=count<=RTR_SCENE_EXACT_LIGHT_LIMIT+1;
    uint samples=exhaustive?count:max(context.PtConsts.NEEFullSamples,1u);
    for(uint sampleIndex=0;sampleIndex<samples;sampleIndex++)
    {
        float probability=1;uint i=sampleIndex;
        if(!exhaustive)i=RtrSelectSceneLight(sd.posW,RtrSceneOpticalRandom(path.GetPixelPos(),
            context.PtConsts.frameIndex^((sampleIndex+1)*0x967a889bu)^path.getCounter((PackedCounters)3),path.getVertexIndex()),count,probability);
        RtrSceneLight light=t_RtrSceneLights[i];
        RtrCountLightWork(4);
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
        float estimatorWeight=exhaustive?1:rcp(probability*samples);
        float3 radiance=light.ColorIntensity.rgb*light.ColorIntensity.a*attenuation*estimatorWeight;
        float3 inputRadiance=radiance;
        float nl=max(dot(sd.N,direction),0);
        if(nl<=0 || max3(radiance)<=0)continue;
        if(light.ConeShadow.z>0)
        {
            RtrCountLightWork(12);
            LightSample sample=LightSample::make();sample.Direction=direction;sample.Distance=maximumDistance;
            RayDesc ray=ComputeVisibilityRay(sample,sd);
            radiance*=RtrSceneShadowVisibility(ray,path,context);
        }
        #if defined(RTR_SCENE_TRANSPORT_TRACE) && RTR_SCENE_TRANSPORT_TRACE
        if(RtrSceneTracePixel(path.GetPixelPos(),context.PtConsts.frameIndex) && (path.getCounter((PackedCounters)3)&4u)!=0)
            RtrSceneTracePrint("light",103u,RtrSceneTraceKey(path.GetPixelPos(),RtrSceneTraceBranch(path.getCounter((PackedCounters)3)),path.getVertexIndex(),context.PtConsts.frameIndex),i,sd.posW,inputRadiance,radiance);
        #endif
        if(max3(radiance)<=0)continue;
        float3 h=normalize(sd.V+direction);
        float nv=max(dot(sd.N,sd.V),0),nh=max(dot(sd.N,h),0),vh=max(dot(sd.V,h),0);
        float3 f0=float3(bsdf.data.Specular()),f=f0+(1-f0)*pow(1-vh,5);
        float alpha=max(roughness*roughness,.0025),a2=alpha*alpha,denom=nh*nh*(a2-1)+1;
        float distribution=a2/max(K_PI*denom*denom,1e-6);
        float geometry=RtrSceneGeometry(nv,nl,roughness);
        diffuse+=(1-f)*(1-metallic)*baseColor*(nl/K_PI)*radiance;
        specular+=f*distribution*geometry/max(4*nv*nl,1e-5)*nl*radiance;
    }
    float3 weight=path.GetThp()*surfaceWeight;
    #if defined(RTR_SCENE_TRANSPORT_TRACE) && RTR_SCENE_TRANSPORT_TRACE
    if(RtrSceneTracePixel(path.GetPixelPos(),context.PtConsts.frameIndex) && (path.getCounter((PackedCounters)3)&4u)!=0)
        RtrSceneTracePrint("receiver",101u,RtrSceneTraceKey(path.GetPixelPos(),RtrSceneTraceBranch(path.getCounter((PackedCounters)3)),path.getVertexIndex(),context.PtConsts.frameIndex),weight,(diffuse+specular)*weight);
    #endif
    result.AccumulateRadiance((diffuse+specular)*weight,Average(specular*weight));
    return result;
}
