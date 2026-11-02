// Lighting-input parity with gpuscene_lighting.glsl, gpuscene_common.glsl and
// gpuscene_optics.glsl. The traversal, material loading and branch queue are RTXPT's.
#ifndef RTR_SCENE_LIGHTING_INPUTS
#define RTR_SCENE_LIGHTING_INPUTS
#include "RtrSceneTransportTrace.hlsli"

float RtrSceneRadicalInverse(uint bits)
{
    bits = (bits << 16u) | (bits >> 16u);
    bits = ((bits & 0x55555555u) << 1u) | ((bits & 0xAAAAAAAAu) >> 1u);
    bits = ((bits & 0x33333333u) << 2u) | ((bits & 0xCCCCCCCCu) >> 2u);
    bits = ((bits & 0x0F0F0F0Fu) << 4u) | ((bits & 0xF0F0F0F0u) >> 4u);
    bits = ((bits & 0x00FF00FFu) << 8u) | ((bits & 0xFF00FF00u) >> 8u);
    return float(bits) * 2.3283064365386963e-10;
}

float RtrSceneOpticalRandom(uint2 pixel, uint seed, uint event)
{
    uint v = pixel.x*0x9e3779b9u ^ pixel.y*0x85ebca6bu ^ seed*0xc2b2ae35u ^ event*0x27d4eb2du;
    v ^= v >> 16u; v *= 0x7feb352du; v ^= v >> 15u;
    return float(v >> 8u) * (1.0/16777216.0);
}

float3 RtrSceneVisibleNormal(float3 normal, float3 view, float roughness, float2 samplePoint)
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

float RtrSceneMaskWeight(float nv,float nl,float roughness)
{
    if (roughness<=.001) return 1;
    float a=roughness*roughness,a2=a*a;
    float v=saturate(nv),l=saturate(nl);
    float sv=sqrt(a2+(1-a2)*v*v),sl=sqrt(a2+(1-a2)*l*l);
    float denominator=l*sv+v*sl;
    return denominator>0 ? saturate(l*(v+sv)/denominator) : 0;
}

float RtrSceneFresnel(float eta,float cosine)
{
    float sin2=eta*eta*max(0,1-cosine*cosine);
    if(sin2>=1) return 1;
    float ct=sqrt(1-sin2);
    float rs=(eta*cosine-ct)/max(eta*cosine+ct,1e-7);
    float rp=(cosine-eta*ct)/max(cosine+eta*ct,1e-7);
    return .5*(rs*rs+rp*rp);
}

float RtrSceneOpticalRoughness(const ShadingData sd,const StandardBSDFData data)
{
    float roughness=data.Roughness();
    #if !RTR_SCENE_RAW_OPTICAL_ROUGHNESS
    // Match FalcorBSDF::__init / StandardBSDF::getProperties. Guides and actual
    // transport must agree on whether this is a smooth (delta) interface.
    #if EnableDeltaBSDF
    if(roughness*roughness<kMinGGXAlpha)roughness=0;
    #else
    roughness=max(roughness,kMinRoughness);
    #endif
    if(sd.GetEta()==1)roughness=0;
    #endif
    return roughness;
}

float3 RtrSceneDielectricNormal(const ShadingData sd, const StandardBSDFData data, uint2 pixel, uint sequence, uint seed,
    uint sampleIndex=0,uint sampleCount=1)
{
    float2 u=float2(RtrSceneOpticalRandom(pixel,seed^sequence^0x1b56c4e9u,0),
        RtrSceneOpticalRandom(pixel,seed^sequence^0x91e10da5u,0));
    u=float2((sampleIndex+u.x)/max(sampleCount,1u),frac(RtrSceneRadicalInverse(sampleIndex)+u.y));
    return RtrSceneVisibleNormal(sd.N,sd.V,RtrSceneOpticalRoughness(sd,data),u);
}

float3 RtrSceneBaseColor(const ShadingData sd,const StandardBSDFData data)
{
    float metallic=data.Metallic();
    if(data.SpecularTransmission()>0) return data.Transmission();
    if(metallic<.999) return max(float3(data.Diffuse())/max(1-metallic,1e-6),0);
    float ior=sd.GetIoR().y, f=(ior-1)/(ior+1);
    return max((float3(data.Specular())-(f*f)*(1-metallic))/max(metallic,1e-6),0);
}

float RtrSceneGeometry(float nv,float nl,float roughness)
{
    float r=roughness+1,k=r*r*.125;
    return nv/max(nv*(1-k)+k,1e-6)*nl/max(nl*(1-k)+k,1e-6);
}

// gsOpaqueMetallicReflectionWeight: fixed per-pixel sequence, no frame salt.
bool RtrSceneMetalSample(const ShadingData sd,const StandardBSDFData data,uint2 pixel,uint sampleIndex,uint sampleCount,out BSDFSample result)
{
    result=(BSDFSample)0;
    float metallic=data.Metallic(),nv=max(dot(sd.N,sd.V),0);
    if(metallic<=1e-4 || nv<=1e-5) return false;
    uint hash=pixel.x*0x9E3779B9u ^ pixel.y*0x85EBCA6Bu ^ 0xC2B2AE35u;
    float2 u=float2((sampleIndex+.5)/max(sampleCount,1u),frac(RtrSceneRadicalInverse(sampleIndex)+RtrSceneRadicalInverse(hash)));
    float roughness=clamp(float(data.Roughness()),.025,1),alpha=max(roughness*roughness,.0025),a2=alpha*alpha;
    float phi=2*K_PI*u.x,ct=sqrt(max((1-u.y)/max(1+(a2-1)*u.y,1e-7),0)),st=sqrt(max(1-ct*ct,0));
    float3 tangent=normalize(abs(sd.N.z)<.999 ? cross(float3(0,0,1),sd.N) : cross(float3(0,1,0),sd.N));
    float3 h=normalize(tangent*(cos(phi)*st)+cross(sd.N,tangent)*(sin(phi)*st)+sd.N*ct);
    float vh=dot(sd.V,h);
    if(vh<=1e-5) return false;
    result.wo=normalize(reflect(-sd.V,h));
    float nl=dot(sd.N,result.wo);
    if(nl<=1e-5) return false;
    float3 f0=float3(data.Specular());
    float3 f=f0+(1-f0)*pow(1-vh,5);
    float nh=max(dot(sd.N,h),1e-5);
    result.weight=metallic*f*RtrSceneGeometry(nv,nl,roughness)*vh/max(nv*nh,1e-5);
    float denom=nh*nh*(a2-1)+1;
    result.pdf=(a2/max(K_PI*denom*denom,1e-6))*nh/(4*vh);
    result.lobe=(uint)LobeType::SpecularReflection;result.lobeP=1;
    return true;
}

// Both actual fill paths share a micro-normal, matching gsEvaluateSecondaryTransport.
void RtrSceneFirstInterface(const ShadingData sd,const StandardBSDFData data,uint2 pixel,uint sequence,uint sampleIndex,uint sampleCount,
    out DeltaLobe lobes[cMaxDeltaLobes],out uint count,out float nonDeltaPart)
{
    for(uint i=0;i<cMaxDeltaLobes;i++)lobes[i]=DeltaLobe::make();
    count=2;float transmission=data.SpecularTransmission();nonDeltaPart=1-transmission;
    float3 h=RtrSceneDielectricNormal(sd,data,pixel,sequence,sampleIndex*0x967a889bu,sampleIndex,sampleCount);
    float eta=sd.GetEta(),f=RtrSceneFresnel(eta,max(dot(sd.V,h),0));
    float3 reflected=normalize(reflect(-sd.V,h)),transmitted=refract(-sd.V,h,eta);
    lobes[0].transmission=1;lobes[1].transmission=0;
    lobes[0].dir=transmitted;lobes[1].dir=reflected;
    if(dot(sd.N,transmitted)<0 && dot(transmitted,transmitted)>0)
        lobes[0].thp=transmission*(1-f)*RtrSceneMaskWeight(dot(sd.N,sd.V),abs(dot(sd.N,transmitted)),RtrSceneOpticalRoughness(sd,data))*eta*eta*data.Transmission();
    if(dot(sd.N,reflected)>0)
        lobes[1].thp=transmission*f*RtrSceneMaskWeight(dot(sd.N,sd.V),dot(sd.N,reflected),RtrSceneOpticalRoughness(sd,data));
    lobes[0].probability=1-f;lobes[1].probability=f;
    #if RTR_SCENE_TRANSPORT_TRACE
    if(RtrSceneTracePixel(pixel,sequence))
        RtrSceneTracePrint("interface",106u,RtrSceneTraceKey(pixel,sampleIndex,1,sequence),
            float4(data.Roughness(),RtrSceneOpticalRoughness(sd,data),eta,f),sd.N,h,reflected,transmitted,
            float4(lobes[1].thp.x,lobes[0].thp.x,sampleIndex,sampleCount));
    #endif
}

bool RtrSceneOpticalSample(const ShadingData sd,const StandardBSDFData data,uint2 pixel,uint sequence,uint vertex,uint branchControl,out BSDFSample result)
{
    result=(BSDFSample)0;
    uint seed=max(1u,branchControl&3u);
    uint event=vertex>=2?vertex-2:0;
    float3 h=RtrSceneDielectricNormal(sd,data,pixel,sequence,seed^((event+1u)*0x02e5be93u));
    float eta=sd.GetEta(),f=RtrSceneFresnel(eta,max(dot(sd.V,h),0));
    float branchRandom=RtrSceneOpticalRandom(pixel,seed^0xb5297a4du^RTR_SCENE_OPTICAL_BRANCH_SEED_XOR,event);
    bool reflection=branchRandom<f;
    bool exactBranch=false;
    #if PATH_TRACER_MODE==PT_DENOISING_LAYERS_FILL
    // Conditional expectation at the high-contribution junctions, extending
    // the first-interface split (PBRT Whitted: SpecularReflect + SpecularTransmit).
    // All later interfaces retain the existing transport estimator.
    exactBranch=vertex<=RTR_SCENE_EXACT_INTERFACE_COUNT && (branchControl&4u)!=0;
    if(exactBranch)reflection=((branchControl>>(3u+RTR_SCENE_EXACT_INTERFACE_COUNT-vertex))&1u)==0;
    #endif
    float branchWeight=exactBranch?(reflection?f:1-f):1;
    result.wo=reflection?normalize(reflect(-sd.V,h)):refract(-sd.V,h,eta);
    float nl=dot(sd.N,result.wo);
    bool valid=branchWeight>0 && dot(result.wo,result.wo)>0 && (reflection?nl>0:nl<0);
    result.weight=branchWeight*data.SpecularTransmission()*RtrSceneMaskWeight(dot(sd.N,sd.V),abs(nl),RtrSceneOpticalRoughness(sd,data));
    if(!reflection)result.weight*=eta*eta*data.Transmission();
    result.pdf=0;result.lobeP=reflection?f:1-f;
    result.lobe=(uint)(reflection?LobeType::DeltaReflection:LobeType::DeltaTransmission);
    #if RTR_SCENE_TRANSPORT_TRACE
    if(RtrSceneTracePixel(pixel,sequence))
        RtrSceneTracePrint("choice",105u,RtrSceneTraceKey(pixel,RtrSceneTraceBranch(branchControl),vertex,sequence),
            float4(f,branchRandom,reflection,data.Roughness()),float4(eta,seed,event,result.lobe),sd.N,h,result.wo,
            float4(exactBranch,valid,branchWeight,nl));
    #endif
    return valid;
}

float3 RtrSceneSky(float3 direction,const PathTracerConstants constants)
{
    float horizon=.35+.65*saturate(direction.y*.5+.5);
    RtrSceneLight sun=t_RtrSceneLights[0];
    float disk=smoothstep(.9995,.9999,dot(normalize(direction),-normalize(sun.DirectionRange.xyz)));
    return constants.RtrSceneSky.rgb*horizon+sun.ColorIntensity.rgb*sun.ColorIntensity.a*disk;
}
#endif
