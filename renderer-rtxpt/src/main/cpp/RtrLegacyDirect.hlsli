// Direct port of gsEvaluatePbrSurface / gsSceneLight / gsDirectContribution.
// Called at every shading receiver, including receivers reached through glass.
NEEResult RtrLegacyDirect(const PathState path,const ShadingData sd,const ActiveBSDF bsdf,const WorkingContext context)
{
    NEEResult result=NEEResult::empty();
    // Shared primary receiver is shaded once; only its optical continuations split.
    if(path.getVertexIndex()==1 && context.RtrLegacyFirstBranch>0)return result;
    float surfaceWeight=1-bsdf.data.SpecularTransmission();
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
        float nl=max(dot(sd.N,direction),0);
        if(nl<=0 || max3(radiance)<=0)continue;
        if(light.ConeShadow.z>0)
        {
            LightSample sample=LightSample::make();sample.Direction=direction;sample.Distance=maximumDistance;
            RayDesc ray=ComputeVisibilityRay(sample,sd);
            if(!Bridge::traceVisibilityRay(ray,path.rayCone,path.getVertexIndex(),path.GetPixelPos(),context.Debug))continue;
        }
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
    result.AccumulateRadiance((diffuse+specular)*weight,Average(specular*weight));
    return result;
}
