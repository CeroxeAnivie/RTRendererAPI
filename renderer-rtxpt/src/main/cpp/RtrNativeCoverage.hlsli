// Opt-in coverage hierarchy and reconstruction metadata. Default opacity output
// and the existing physical transmission path do not use this hierarchy.
static bool rtrComplexCoverage=false,rtrUnresolvedCoverage=false,rtrMovingCoverage=false;
static bool rtrCoverageFiltering=false;
static float3 rtrReceiverDx=0,rtrReceiverDy=0;
bool RtrCoverageEnabled(){return g.style.limits.w>=2;}
float3 RtrCameraDirection(float2 pixel){
    float2 ndc=pixel/float2(g.grid.xy)*float2(2,-2)+float2(-1,1)+g.farJitter.yz;
    float4 a=mul(g.inverseClip,float4(ndc,g.cameraNear.w,1)),b=mul(g.inverseClip,float4(ndc,g.farJitter.x,1));
    return RtrNormalize(mul((float3x3)g.cameraToWorld,b.xyz/b.w-a.xyz/a.w));
}
float3 RtrWorldBarycentrics(float3 p,float3 a,float3 b,float3 c){
    float3 u=b-a,v=c-a,w=p-a;float uu=dot(u,u),uv=dot(u,v),vv=dot(v,v),wu=dot(w,u),wv=dot(w,v);
    float d=uu*vv-uv*uv;if(abs(d)<1e-25)return -1e20;
    float y=(vv*wu-uv*wv)/d,z=(uu*wv-uv*wu)/d;return float3(1-y-z,y,z);
}
float3 RtrCoverageNeighbor(RayDesc ray,float3 offset,float lightDistance,float3 worldHit,float3 normal){
    float3 direction=lightDistance>0?RtrNormalize(ray.Direction*lightDistance-offset):ray.Direction;
    float denominator=dot(direction,normal);
    if(abs(denominator)<1e-7)return worldHit+offset*1e5;
    return ray.Origin+offset+direction*(dot(worldHit-ray.Origin-offset,normal)/denominator);
}
float4 RtrCoverageLevel(int textureIndex,float2 uv,uint policy,float lod){
    switch(policy&3){
        case 0:return t_Textures[NonUniformResourceIndex(textureIndex)].SampleLevel(s_TexturePolicy0,uv,lod);
        case 1:return t_Textures[NonUniformResourceIndex(textureIndex)].SampleLevel(s_TexturePolicy1,uv,lod);
        case 2:return t_Textures[NonUniformResourceIndex(textureIndex)].SampleLevel(s_TexturePolicy2,uv,lod);
        default:return t_Textures[NonUniformResourceIndex(textureIndex)].SampleLevel(s_TexturePolicy3,uv,lod);
    }
}
bool RtrCoverageQuad(InstanceData instance,GeometryData geometry,RtrGeometryAttributes mapping,uint primitive,uint3 indices,
        float3 b,float3 dx,float3 dy,ByteAddressBuffer vertices,ByteAddressBuffer indexBuffer,float materialAlpha,float cutoff){
    uint partner=primitive^1;if(partner>=mapping.triangles)return false;
    if(t_SurfaceIndices[mapping.offset/3+partner]!=t_SurfaceIndices[mapping.offset/3+primitive])return false;
    uint3 q=indexBuffer.Load3(geometry.indexOffset+partner*12);int opposite=-1;uint other=0;int unique=0;
    [unroll]for(int i=0;i<3;i++){
        if(all(indices[i]!=q)){if(opposite>=0)return false;opposite=i;}
        if(all(q[i]!=indices)){other=q[i];unique++;}
    }
    if(opposite<0||unique!=1)return false;int s0=(opposite+1)%3,s1=(opposite+2)%3;
    float3 p0=RtrPosition(vertices,geometry,indices[s0]),p1=RtrPosition(vertices,geometry,indices[s1]);
    float3 po=RtrPosition(vertices,geometry,indices[opposite]),pn=RtrPosition(vertices,geometry,other);
    float scale=max(length(p0-po),length(p1-po));
    if(scale<1e-6||length(pn+po-p0-p1)>1e-5*scale)return false;
    if(length(RtrUv(vertices,geometry,other)+RtrUv(vertices,geometry,indices[opposite])
            -RtrUv(vertices,geometry,indices[s0])-RtrUv(vertices,geometry,indices[s1]))>1e-5)return false;
    [unroll]for(int j=0;j<3;j++)if(q[j]==other&&RtrVertexColor(t_Baselines[mapping.offset+partner*3+j].color).a*materialAlpha<cutoff)return false;
    [unroll]for(int corner=0;corner<4;corner++){
        float3 v=b+dx*(corner<2?-.5:.5)+dy*((corner&1)==0?-.5:.5);
        if(all(v>=1e-5))continue;
        float3 adjacent=v;adjacent[opposite]=-v[opposite];adjacent[s0]+=v[opposite];adjacent[s1]+=v[opposite];
        if(any(adjacent<1e-5))return false;
    }
    return true;
}
// -1 keeps the ordinary candidate, [0,1] is proven footprint coverage, and
// 2 requests correlated real-ray fallback. No mean-alpha multiplication.
float RtrCandidateCoverage(RayDesc ray,uint instanceIndex,uint geometryIndex,uint primitive,float2 attributes,
        float hitDistance,float lightDistance,bool filtering,bool shadowRole=true){
    if(!RtrCoverageEnabled())return -1;
    RtrInstance roles=t_InstanceAttributes[instanceIndex];
    if(shadowRole&&(uint(roles.rolesVisibility.x)&2)==0)return -1;
    InstanceData instance=t_Instances[instanceIndex];GeometryData geometry=t_Geometry[instance.firstGeometryIndex+geometryIndex];
    RtrGeometryAttributes mapping=t_GeometryBaselines[instance.firstGeometryIndex+geometryIndex];
    MaterialConstants material=RtrMaterial(geometry,primitive,mapping);
    bool masked=material.domain==MaterialDomain_AlphaTested||material.domain==MaterialDomain_TransmissiveAlphaTested;
    if(!masked)return -1;
    ByteAddressBuffer indices=t_Buffers[NonUniformResourceIndex(geometry.indexBufferIndex)];
    ByteAddressBuffer vertices=t_Buffers[NonUniformResourceIndex(geometry.vertexBufferIndex)];
    uint3 tri=indices.Load3(geometry.indexOffset+primitive*12);float3 bary=float3(1-attributes.x-attributes.y,attributes);
    float3 vertexAlpha=float3(RtrVertexColor(t_Baselines[mapping.offset+primitive*3].color).a,
        RtrVertexColor(t_Baselines[mapping.offset+primitive*3+1].color).a,RtrVertexColor(t_Baselines[mapping.offset+primitive*3+2].color).a);
    if(!filtering||(material.flags&MaterialFlags_UseBaseOrDiffuseTexture)==0)return -1;
    float3 p0=mul(instance.transform,float4(RtrPosition(vertices,geometry,tri.x),1));
    float3 p1=mul(instance.transform,float4(RtrPosition(vertices,geometry,tri.y),1));
    float3 p2=mul(instance.transform,float4(RtrPosition(vertices,geometry,tri.z),1));
    float3 normal=RtrNormalize(cross(p1-p0,p2-p0)),worldHit=ray.Origin+ray.Direction*hitDistance;
    float3 bx=RtrWorldBarycentrics(RtrCoverageNeighbor(ray,rtrReceiverDx,lightDistance,worldHit,normal),p0,p1,p2);
    float3 by=RtrWorldBarycentrics(RtrCoverageNeighbor(ray,rtrReceiverDy,lightDistance,worldHit,normal),p0,p1,p2);
    float2 t0=RtrUv(vertices,geometry,tri.x),t1=RtrUv(vertices,geometry,tri.y),t2=RtrUv(vertices,geometry,tri.z);
    float2 uv=t0*bary.x+t1*bary.y+t2*bary.z;
    float2 du=t0*bx.x+t1*bx.y+t2*bx.z-uv,dv=t0*by.x+t1*by.y+t2*by.z-uv;
    uint width,height,levels;t_Textures[NonUniformResourceIndex(material.baseOrDiffuseTextureIndex)].GetDimensions(0,width,height,levels);
    float span=max(length(du*float2(width,height)),length(dv*float2(width,height)));
    if(span<=1.25)return -1;
    rtrComplexCoverage=true;
    bool interior=all(bary>.5*(abs(bx-bary)+abs(by-bary))+1e-5);
    float minimumAlpha=mapping.maskedOpacity*min(vertexAlpha.x,min(vertexAlpha.y,vertexAlpha.z));
    if(!interior&&minimumAlpha>=material.alphaCutoff)
        interior=RtrCoverageQuad(instance,geometry,mapping,primitive,tri,bary,bx-bary,by-bary,vertices,indices,mapping.maskedOpacity,material.alphaCutoff);
    if(!interior||levels<2||minimumAlpha<material.alphaCutoff||material.domain!=MaterialDomain_AlphaTested
            ||(material.flags&MaterialFlags_UseOpacityTexture)!=0)return 2;
    float lod=clamp(log2(span),0,float(levels-1)),low=floor(lod);
    float4 coverage=RtrCoverageLevel(material.baseOrDiffuseTextureIndex,uv,mapping.basePolicy,low);
    if(lod-low>=.001&&low<float(levels-1))coverage=lerp(coverage,RtrCoverageLevel(material.baseOrDiffuseTextureIndex,uv,mapping.basePolicy,low+1),frac(lod));
    if(coverage.g<.999||coverage.b<.999)return 2;
    return coverage.r<=.001?0:coverage.r>=.999?1:coverage.r;
}
void RtrPrepareCoverage(RtrReceiver receiver,uint2 pixel){
    rtrComplexCoverage=false;rtrUnresolvedCoverage=false;rtrMovingCoverage=false;rtrReceiverDx=0;rtrReceiverDy=0;
    rtrCoverageFiltering=RtrCoverageEnabled();
    if(!rtrCoverageFiltering)return;
    float3 delta=receiver.position-g.cameraNear.xyz;float plane=dot(delta,receiver.geometricNormal);
    float3 dx=RtrCameraDirection(float2(pixel)+float2(1.5,.5)),dy=RtrCameraDirection(float2(pixel)+float2(.5,1.5));
    float nx=dot(dx,receiver.geometricNormal),ny=dot(dy,receiver.geometricNormal);
    rtrReceiverDx=abs(nx)>1e-6?dx*(plane/nx)-delta:0;rtrReceiverDy=abs(ny)>1e-6?dy*(plane/ny)-delta:0;
    uint index=t_Instances[receiver.instance].firstGeometryIndex+receiver.geometry;
    GeometryData geometry=t_Geometry[index];RtrGeometryAttributes mapping=t_GeometryBaselines[index];
    MaterialConstants material=RtrMaterial(geometry,receiver.primitive,mapping);
    // Cutout receivers and frame primitives can animate without moving the
    // receiving plane. They never supply reusable temporal edge geometry.
    rtrMovingCoverage=t_InstanceAttributes[receiver.instance].rolesVisibility.w!=0
        ||material.domain==MaterialDomain_AlphaTested||material.domain==MaterialDomain_TransmissiveAlphaTested;
    RayDesc ray;ray.Origin=g.cameraNear.xyz;ray.Direction=RtrNormalize(delta);ray.TMin=.001;ray.TMax=receiver.distance;
    RtrCandidateCoverage(ray,receiver.instance,receiver.geometry,receiver.primitive,receiver.barycentrics,receiver.distance,0,true,false);
}
float RtrDirectAlpha(bool valid,float opacity){
    if(!RtrCoverageEnabled())return valid?opacity:0;
    return rtrComplexCoverage?(rtrUnresolvedCoverage?0:64.0/255):(rtrMovingCoverage?128.0/255:1);
}
