// Lighting-input parity with gpuscene_lighting.glsl, gpuscene_common.glsl and
// gpuscene_optics.glsl. The traversal, material loading and branch queue are RTXPT's.
#ifndef RTR_LEGACY_LIGHTING_INPUTS
#define RTR_LEGACY_LIGHTING_INPUTS

float RtrLegacyRadicalInverse(uint bits)
{
    bits = (bits << 16u) | (bits >> 16u);
    bits = ((bits & 0x55555555u) << 1u) | ((bits & 0xAAAAAAAAu) >> 1u);
    bits = ((bits & 0x33333333u) << 2u) | ((bits & 0xCCCCCCCCu) >> 2u);
    bits = ((bits & 0x0F0F0F0Fu) << 4u) | ((bits & 0xF0F0F0F0u) >> 4u);
    bits = ((bits & 0x00FF00FFu) << 8u) | ((bits & 0xFF00FF00u) >> 8u);
    return float(bits) * 2.3283064365386963e-10;
}

float RtrLegacyOpticalRandom(uint2 pixel, uint seed, uint event)
{
    uint v = pixel.x*0x9e3779b9u ^ pixel.y*0x85ebca6bu ^ seed*0xc2b2ae35u ^ event*0x27d4eb2du;
    v ^= v >> 16u; v *= 0x7feb352du; v ^= v >> 15u;
    return float(v >> 8u) * (1.0/16777216.0);
}

float3 RtrLegacyVisibleNormal(float3 normal, float3 view, float roughness, float2 samplePoint)
{
    if (roughness <= .001) return normal;
    float alpha = roughness*roughness;
    float3 tangent = normalize(abs(normal.z)<.999 ? cross(float3(0,0,1),normal) : cross(float3(0,1,0),normal));
    float3 bitangent = cross(normal,tangent);
    float3 localView = float3(dot(view,tangent),dot(view,bitangent),max(dot(view,normal),0));
    float3 stretched = normalize(float3(alpha*localView.xy,localView.z));
    float planar = dot(stretched.xy,stretched.xy);
    float3 first = planar>0 ? float3(-stretched.y,stretched.x,0)/sqrt(planar) : float3(1,0,0);
    float3 second = cross(stretched,first);
    float radius=sqrt(samplePoint.x), angle=2*K_PI*samplePoint.y;
    float x=radius*cos(angle), y=radius*sin(angle), blend=.5*(1+stretched.z);
    y=lerp(sqrt(max(0,1-x*x)),y,blend);
    float3 visible=x*first+y*second+sqrt(max(0,1-x*x-y*y))*stretched;
    float3 h=normalize(float3(alpha*visible.xy,max(visible.z,0)));
    return normalize(tangent*h.x+bitangent*h.y+normal*h.z);
}

float RtrLegacyMaskWeight(float nv,float nl,float roughness)
{
    if (roughness<=.001) return 1;
    float a=roughness*roughness,a2=a*a;
    float v=saturate(nv),l=saturate(nl);
    float sv=sqrt(a2+(1-a2)*v*v),sl=sqrt(a2+(1-a2)*l*l);
    float denominator=l*sv+v*sl;
    return denominator>0 ? saturate(l*(v+sv)/denominator) : 0;
}

float RtrLegacyFresnel(float eta,float cosine)
{
    float sin2=eta*eta*max(0,1-cosine*cosine);
    if(sin2>=1) return 1;
    float ct=sqrt(1-sin2);
    float rs=(eta*cosine-ct)/max(eta*cosine+ct,1e-7);
    float rp=(cosine-eta*ct)/max(cosine+eta*ct,1e-7);
    return .5*(rs*rs+rp*rp);
}

float3 RtrLegacyDielectricNormal(const ShadingData sd, const StandardBSDFData data, uint2 pixel, uint sequence, uint seed,
    uint sampleIndex=0,uint sampleCount=1)
{
    float2 u=float2(RtrLegacyOpticalRandom(pixel,seed^sequence^0x1b56c4e9u,0),
        RtrLegacyOpticalRandom(pixel,seed^sequence^0x91e10da5u,0));
    u=float2((sampleIndex+u.x)/max(sampleCount,1u),frac(RtrLegacyRadicalInverse(sampleIndex)+u.y));
    return RtrLegacyVisibleNormal(sd.N,sd.V,data.Roughness(),u);
}

float3 RtrLegacyBaseColor(const ShadingData sd,const StandardBSDFData data)
{
    float metallic=data.Metallic();
    if(data.SpecularTransmission()>0) return data.Transmission();
    if(metallic<.999) return max(float3(data.Diffuse())/max(1-metallic,1e-6),0);
    float ior=sd.GetIoR().y, f=(ior-1)/(ior+1);
    return max((float3(data.Specular())-(f*f)*(1-metallic))/max(metallic,1e-6),0);
}

float RtrLegacyGeometry(float nv,float nl,float roughness)
{
    float r=roughness+1,k=r*r*.125;
    return nv/max(nv*(1-k)+k,1e-6)*nl/max(nl*(1-k)+k,1e-6);
}

// gsOpaqueMetallicReflectionWeight: fixed per-pixel sequence, no frame salt.
bool RtrLegacyMetalSample(const ShadingData sd,const StandardBSDFData data,uint2 pixel,uint sampleIndex,uint sampleCount,out BSDFSample result)
{
    result=(BSDFSample)0;
    float metallic=data.Metallic(),nv=max(dot(sd.N,sd.V),0);
    if(metallic<=1e-4 || nv<=1e-5) return false;
    uint hash=pixel.x*0x9E3779B9u ^ pixel.y*0x85EBCA6Bu ^ 0xC2B2AE35u;
    float2 u=float2((sampleIndex+.5)/max(sampleCount,1u),frac(RtrLegacyRadicalInverse(sampleIndex)+RtrLegacyRadicalInverse(hash)));
    float roughness=clamp(float(data.Roughness()),.025,1),alpha=max(roughness*roughness,.0025),a2=alpha*alpha;
    float phi=2*K_PI*u.x,ct=sqrt(max((1-u.y)/max(1+(a2-1)*u.y,1e-7),0)),st=sqrt(max(1-ct*ct,0));
    float3 tangent=normalize(abs(sd.N.z)<.999 ? cross(float3(0,0,1),sd.N) : cross(float3(0,1,0),sd.N));
    float3 h=normalize(tangent*(cos(phi)*st)+cross(sd.N,tangent)*(sin(phi)*st)+sd.N*ct);
    float vh=dot(sd.V,h);
    if(vh<=1e-5) return false;
    result.wo=normalize(reflect(-sd.V,h));
    float nl=dot(sd.N,result.wo);
    if(nl<=1e-5) return false;
    float3 f0=lerp(.04.xxx,RtrLegacyBaseColor(sd,data),metallic);
    float3 f=f0+(1-f0)*pow(1-vh,5);
    float nh=max(dot(sd.N,h),1e-5);
    result.weight=metallic*f*RtrLegacyGeometry(nv,nl,roughness)*vh/max(nv*nh,1e-5);
    float denom=nh*nh*(a2-1)+1;
    result.pdf=(a2/max(K_PI*denom*denom,1e-6))*nh/(4*vh);
    result.lobe=(uint)LobeType::SpecularReflection;result.lobeP=1;
    return true;
}

// Both actual fill paths share a micro-normal, matching gsEvaluateSecondaryTransport.
void RtrLegacyFirstInterface(const ShadingData sd,const StandardBSDFData data,uint2 pixel,uint sequence,uint sampleIndex,uint sampleCount,
    out DeltaLobe lobes[cMaxDeltaLobes],out uint count,out float nonDeltaPart)
{
    for(uint i=0;i<cMaxDeltaLobes;i++)lobes[i]=DeltaLobe::make();
    count=2;float transmission=data.SpecularTransmission();nonDeltaPart=1-transmission;
    float3 h=RtrLegacyDielectricNormal(sd,data,pixel,sequence,sampleIndex*0x967a889bu,sampleIndex,sampleCount);
    float eta=sd.GetEta(),f=RtrLegacyFresnel(eta,max(dot(sd.V,h),0));
    float3 reflected=normalize(reflect(-sd.V,h)),transmitted=refract(-sd.V,h,eta);
    lobes[0].transmission=1;lobes[1].transmission=0;
    lobes[0].dir=transmitted;lobes[1].dir=reflected;
    if(dot(sd.N,transmitted)<0 && dot(transmitted,transmitted)>0)
        lobes[0].thp=transmission*(1-f)*RtrLegacyMaskWeight(dot(sd.N,sd.V),abs(dot(sd.N,transmitted)),data.Roughness())*eta*eta*data.Transmission();
    if(dot(sd.N,reflected)>0)
        lobes[1].thp=transmission*f*RtrLegacyMaskWeight(dot(sd.N,sd.V),dot(sd.N,reflected),data.Roughness());
    lobes[0].probability=1-f;lobes[1].probability=f;
}

bool RtrLegacyOpticalSample(const ShadingData sd,const StandardBSDFData data,uint2 pixel,uint sequence,uint vertex,uint seed,out BSDFSample result)
{
    result=(BSDFSample)0;
    uint event=vertex>=2?vertex-2:0;
    float3 h=RtrLegacyDielectricNormal(sd,data,pixel,sequence,seed^((event+1u)*0x02e5be93u));
    float eta=sd.GetEta(),f=RtrLegacyFresnel(eta,max(dot(sd.V,h),0));
    bool reflection=RtrLegacyOpticalRandom(pixel,seed^0xb5297a4du,event)<f;
    result.wo=reflection?normalize(reflect(-sd.V,h)):refract(-sd.V,h,eta);
    float nl=dot(sd.N,result.wo);
    if(reflection?nl<=0:nl>=0) return false;
    result.weight=data.SpecularTransmission()*RtrLegacyMaskWeight(dot(sd.N,sd.V),abs(nl),data.Roughness());
    if(!reflection)result.weight*=eta*eta*data.Transmission();
    result.pdf=0;result.lobeP=reflection?f:1-f;
    result.lobe=(uint)(reflection?LobeType::DeltaReflection:LobeType::DeltaTransmission);
    return true;
}

float3 RtrLegacySky(float3 direction,const PathTracerConstants constants)
{
    float horizon=.35+.65*saturate(direction.y*.5+.5);
    RtrLegacyLight sun=t_RtrLegacyLights[0];
    float disk=smoothstep(.9995,.9999,dot(normalize(direction),-normalize(sun.DirectionRange.xyz)));
    return constants.RtrLegacySky.rgb*horizon+sun.ColorIntensity.rgb*sun.ColorIntensity.a*disk;
}
#endif
