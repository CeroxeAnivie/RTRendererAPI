// Exact finite-support traversal at the actual receiver, also used at secondary hits.
float3 RtrSparseBsdf(const ShadingData sd,const ActiveBSDF bsdf,float3 direction,float3 radiance,out float3 specularColor)
{
    float3 baseColor=RtrSceneBaseColor(sd,bsdf.data);
    float metallic=saturate(float(bsdf.data.Metallic())),roughness=clamp(float(bsdf.data.Roughness()),.025,1);
    float nl=max(dot(sd.N,direction),0),nv=max(dot(sd.N,sd.V),0);
    float3 h=normalize(sd.V+direction);
    float nh=max(dot(sd.N,h),0),vh=max(dot(sd.V,h),0);
    float3 f0=float3(bsdf.data.Specular()),f=f0+(1-f0)*pow(1-vh,5);
    float alpha=max(roughness*roughness,.0025),a2=alpha*alpha,denom=nh*nh*(a2-1)+1;
    float distribution=a2/max(K_PI*denom*denom,1e-6);
    float3 specular=f*distribution*RtrSceneGeometry(nv,nl,roughness)/max(4*nv*nl,1e-5)*nl*radiance;
    specularColor=specular;
    return (1-f)*(1-metallic)*baseColor*(nl/K_PI)*radiance+specular;
}
bool RtrSparseSupports(RtrSceneLight node,float3 position)
{
    float3 delta=max(max(node.PositionType.xyz-position,position-node.DirectionRange.xyz),0);
    float radius=node.ColorIntensity.z;
    if(radius<=0||any(delta>radius))return false;
    float3 relative=delta/radius;
    return dot(relative,relative)<=1;
}
NEEResult RtrSparseDirect(const PathState path,const ShadingData sd,const ActiveBSDF bsdf,const WorkingContext context)
{
    NEEResult result=NEEResult::empty();
    uint count=uint(t_RtrSparseSources[0].PositionShape.x);
    if(count==0)return result;
    // AVL depth is bounded below 32 for the negotiated source capacity. There is
    // no candidate budget, distance truncation, or nearest-N early termination.
    uint stack[33];uint pending=1;stack[0]=uint(t_RtrSparseSources[0].PositionShape.y);
    float3 total=0,totalSpecular=0;
    float3 maxima[32],channelMaxima[32],specularMaxima[32],channelSpecularMaxima[32];uint2 keys[32];uint mask=0,channelMask=0;
    while(pending>0)
    {
        RtrSceneLight node=t_RtrSparseTree[stack[--pending]];
        if(!RtrSparseSupports(node,sd.posW))continue;
        if(node.ColorIntensity.w<0)
        {
            stack[pending++]=uint(node.PositionType.w);stack[pending++]=uint(node.DirectionRange.w);continue;
        }
        uint slot=uint(node.ColorIntensity.w);if(slot>=count)continue;
        RtrSparseSource source=t_RtrSparseSources[slot+1];
        float3 origin=source.PositionShape.xyz;
        if(source.PositionShape.w==1)origin=clamp(sd.posW,origin-source.ExtentFalloff.xyz,origin+source.ExtentFalloff.xyz);
        float3 delta=origin-sd.posW;float distance=length(delta);
        if(distance>=source.DirectionRange.w)continue;
        float3 direction=distance>1e-5?delta/distance:float3(sd.N);
        float fade=saturate(1-distance/source.DirectionRange.w);
        float attenuation=pow(fade,source.ExtentFalloff.w)/max(distance*distance,1);
        if((uint(source.IdentityShadow.w)&2)!=0)attenuation=saturate(1-dot(abs(delta),float3(1,1,1))/source.DirectionRange.w);
        if(source.PositionShape.w==2){float cosine=dot(source.DirectionRange.xyz,-direction);
            attenuation*=source.ConeGroup.x==source.ConeGroup.y?step(source.ConeGroup.y,cosine):smoothstep(source.ConeGroup.y,source.ConeGroup.x,cosine);}
        float3 radiance=source.ColorIntensity.rgb*source.ColorIntensity.w*attenuation;
        if(dot(sd.N,direction)<=0||max3(radiance)<=0)continue;
        if(source.IdentityShadow.x>0&&distance>1e-5)
        {
            LightSample light=LightSample::make();light.Direction=direction;light.Distance=distance;
            radiance*=RtrSceneShadowVisibility(ComputeVisibilityRay(light,sd),path,context);
        }
        float3 specular;float3 contribution=RtrSparseBsdf(sd,bsdf,direction,radiance,specular);
        if(source.ConeGroup.w==0){total+=contribution;totalSpecular+=specular;}
        else if(source.ConeGroup.w==2)
        {
            uint group=uint(source.ConeGroup.z),bit=1u<<group;
            if((channelMask&bit)==0){channelMaxima[group]=contribution;channelSpecularMaxima[group]=specular;channelMask|=bit;}
            else{bool3 take=contribution>channelMaxima[group];channelSpecularMaxima[group]=select(take,specular,channelSpecularMaxima[group]);channelMaxima[group]=max(channelMaxima[group],contribution);}
        }
        else
        {
            uint group=uint(source.ConeGroup.z);uint bit=1u<<group;uint2 key=asuint(source.IdentityShadow.zy);
            float score=Luminance(contribution);
            bool replace=(mask&bit)==0;
            if(!replace){float old=Luminance(maxima[group]);replace=score>old||(score==old&&(key.x<keys[group].x||(key.x==keys[group].x&&key.y<keys[group].y)));}
            if(replace){maxima[group]=contribution;specularMaxima[group]=specular;keys[group]=key;mask|=bit;}
        }
    }
    while(mask!=0){uint group=firstbitlow(mask);mask&=mask-1;total+=maxima[group];totalSpecular+=specularMaxima[group];}
    while(channelMask!=0){uint group=firstbitlow(channelMask);channelMask&=channelMask-1;total+=channelMaxima[group];totalSpecular+=channelSpecularMaxima[group];}
    float3 weight=path.GetThp()*(1-bsdf.data.SpecularTransmission());
    result.AccumulateRadiance(total*weight,Average(totalSpecular*weight));
    RtrHostAovDirect(path,(total-totalSpecular)*weight,totalSpecular*weight,0);return result;
}
