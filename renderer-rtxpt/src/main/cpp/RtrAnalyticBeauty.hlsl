// API-owned analytic transport. NVIDIA files are included unchanged.
// Intersection, material evaluation, delta lobes and dielectric state stay native.
#define PATH_TRACER_MODE PT_DENOISING_LAYERS_BUILD
#define NON_PATH_TRACING_PASS 1
#define IGNORE_RAY_PAYLOAD 1
#define RTXPT_ACTIVE_DENOISING_LAYER_COUNT 3
#define RTXPT_SKIP_NEEAT_FEEDBACK 1
#define RTXPT_USE_APPROXIMATE_MIS 0
#define ActiveBSDF StandardBSDF
#include "PathTracer/Config.h"
#include "PathTracer/PathTracerTypes.hlsli"
#include "Bindings/ShaderResourceBindings.hlsli"
#include "PathTracerBridgeDonut.hlsli"
#include "PathTracer/BSDFs/StandardBSDF.hlsli"
#include "PathTracerSurfaceBridgeDonut.hlsli"
#include "PathTracer/PathTracer.hlsli"
#include "PathTracer/PathTracerHandleHit.hlsli"

struct RtrAnalyticLight {float4 positionType,directionRange,colorIntensity,coneShadow;};
struct RtrAnalyticConstants {float4 ambient;uint4 inputs;};
ConstantBuffer<RtrAnalyticConstants> rtr : register(b0,space3);
StructuredBuffer<RtrAnalyticLight> rtrLights : register(t0,space3);

// Native visibility is binary. RGB boundary transmission is an API extension;
// the scene traversal, material/texture decode and Fresnel function stay native.
float3 RtrColoredVisibility(RayDesc ray,PathState source,PathTracer::WorkingContext context)
{
    if(Bridge::traceVisibilityRay(ray,source.rayCone,source.getVertexIndex(),source.GetPixelPos(),context.Debug))return 1;
    PathState shadow=source;shadow.SetOrigin(ray.Origin);shadow.SetDir(ray.Direction);
    float remaining=ray.TMax;float3 throughput=1;
    [loop]for(uint event=0;event<Bridge::getMaxBounceLimit();++event){
        RayQuery<RAY_FLAG_SKIP_PROCEDURAL_PRIMITIVES,RAYQUERY_FLAG_ALLOW_OPACITY_MICROMAPS> query;
        Bridge::traceScatterRay(shadow,query,float2(0,remaining),context.Debug);
        if(query.CommittedStatus()!=COMMITTED_TRIANGLE_HIT)return throughput;
        PathTracer::Surface hit=Bridge::loadSurface(query.CommittedInstanceIndex(),query.CommittedGeometryIndex(),
            query.CommittedPrimitiveIndex(),query.CommittedTriangleBarycentrics(),ray.Direction,shadow.rayCone,
            source.getVertexIndex(),source.GetPixelPos(),context.Debug);
        float transmission=hit.bsdf.data.SpecularTransmission();
        if(transmission<=0)return 0;
        float eta=hit.shadingData.GetEta();
        // Straight visibility uses the outside angular domain at both boundaries.
        float visibilityEta=min(eta,rcp(max(eta,1e-7)));
        float fresnel=evalFresnelDielectric(visibilityEta,abs(dot(ray.Direction,hit.shadingData.N)));
        throughput*=transmission*(1-fresnel)*hit.bsdf.data.Transmission();
        if(max3(throughput)<=0)return 0;
        float3 position=hit.shadingData.posW;
        float epsilon=max(1e-5,max3(abs(position))*2e-7);
        remaining-=query.CommittedRayT()+epsilon;
        if(remaining<=0)return throughput;
        shadow.SetOrigin(position+ray.Direction*epsilon);
    }
    return 0;
}

float3 RtrAnalyticDirect(PathState path,PathTracer::Surface surface,PathTracer::WorkingContext context)
{
    ShadingData sd=surface.shadingData;
    ActiveBSDF bsdf=surface.bsdf;
    float3 value=(1-bsdf.data.SpecularTransmission())*bsdf.data.Diffuse()*rtr.ambient.rgb;
    [loop]for(uint index=0;index<rtr.inputs.x;++index){
        RtrAnalyticLight light=rtrLights[index];
        float attenuation=1,distance=1e20;float3 direction;
        if(light.positionType.w==0)direction=-normalize(light.directionRange.xyz);
        else {
            float3 delta=light.positionType.xyz-sd.posW;distance=length(delta);
            if(distance<=1e-4)continue;
            direction=delta/distance;
            float fade=saturate(1-distance/max(light.directionRange.w,1e-4));
            attenuation=fade*fade/max(distance*distance,1);
            if(light.positionType.w==2){
                float cone=dot(normalize(light.directionRange.xyz),-direction);
                attenuation*=light.coneShadow.x==light.coneShadow.y?step(light.coneShadow.y,cone)
                    :smoothstep(light.coneShadow.y,light.coneShadow.x,cone);
            }
        }
        if(dot(sd.N,direction)<=0||attenuation<=0||light.colorIntensity.w<=0)continue;
        float3 incident=light.colorIntensity.rgb*light.colorIntensity.w*attenuation;
        if(light.coneShadow.z>0){
            RayDesc ray;ray.Origin=sd.ComputeNewRayOrigin(true);ray.Direction=direction;
            ray.TMin=0;ray.TMax=distance*0.9985;
            incident*=RtrColoredVisibility(ray,path,context);
        }
        // Original StandardBSDF evaluates its own Fresnel, masking and BRDF.
        value+=bsdf.eval(sd,direction).rgb*incident;
    }
    return value;
}

uint RtrStableHash(uint value){value^=value>>16;value*=0x7feb352du;value^=value>>15;value*=0x846ca68bu;return value^(value>>16);}
float RtrStableRandom(uint value){return float(RtrStableHash(value)>>8)*(1.0/16777216.0);}

[numthreads(8,8,1)]
void main(uint3 thread : SV_DispatchThreadID)
{
    uint2 pixel=thread.xy;
    if(any(pixel>=uint2(g_Const.ptConsts.imageWidth,g_Const.ptConsts.imageHeight)))return;
    PathTracer::WorkingContext context=Bridge::GetWorkingContext();
    PathState path=PathTracer::EmptyPathInitialize(pixel,g_Const.ptConsts.camera.PixelConeSpreadAngle);
    PathTracer::SetupPathPrimaryRay(path,Bridge::computeCameraRay(pixel));
    Bridge::ExportSurfaceInit(pixel);
    // Four exact interfaces, depth-first: shared prefixes are shaded only once.
    // Later events retain a stable native BSDF estimator, rather than dropping energy.
    PathState pending[5];uint pendingCount=0;
    float3 radiance=0;bool primary=true;
    [loop]while(true){
        [loop]for(uint event=0;event<Bridge::getMaxBounceLimit()+16u;++event){
            if(!path.isActive()||max3(path.GetThp())<=0)break;
            RayQuery<RAY_FLAG_SKIP_PROCEDURAL_PRIMITIVES,RAYQUERY_FLAG_ALLOW_OPACITY_MICROMAPS> query;
            Bridge::traceScatterRay(path,query,float2(0,kMaxRayTravel),context.Debug);
            if(query.CommittedStatus()!=COMMITTED_TRIANGLE_HIT){
                if(Bridge::HasEnvMap())radiance+=path.GetThp()*Bridge::CreateEnvMap().Eval(path.GetDir());
                if(primary){
                    float3 position=path.GetOrigin()+path.GetDir()*kEnvironmentMapSceneDistance;
                    Bridge::ExportNonSurface(path,position,Bridge::computeMotionVector(position,position));primary=false;
                }
                break;
            }
            float distance=query.CommittedRayT();
            PathTracer::UpdatePathTravelled(path,path.GetOrigin(),path.GetDir(),distance,context);
            PathTracer::Surface surface=Bridge::loadSurface(query.CommittedInstanceIndex(),query.CommittedGeometryIndex(),
                query.CommittedPrimitiveIndex(),query.CommittedTriangleBarycentrics(),path.GetDir(),path.rayCone,
                path.getVertexIndex(),pixel,context.Debug);
            #if RTXPT_NESTED_DIELECTRICS_QUALITY > 0
            if(!path.interiorList.isEmpty())PathTracer::UpdatePathThroughput(path,
                HomogeneousVolumeSampler::evalTransmittance(Bridge::loadHomogeneousVolumeData(path.interiorList.getTopMaterialID()),distance));
            #endif
            if(!PathTracer::HandleNestedDielectrics(surface,path,context))continue;
            if(primary){
                float3 position=surface.shadingData.posW;
                Bridge::ExportSurface(path,surface,path.GetSceneLength(),
                    Bridge::computeMotionVector(position,position+surface.shadingData.toPrevPosW));primary=false;
            }
            radiance+=path.GetThp()*(surface.shadingData.GetEmission()+RtrAnalyticDirect(path,surface,context));
            if(path.getVertexIndex()>=Bridge::getMaxBounceLimit())break;
            DeltaLobe lobes[cMaxDeltaLobes];int count;float nonDelta;
            surface.bsdf.evalDeltaLobes(surface.shadingData,lobes,count,nonDelta);
            bool advanced=false;
            if(path.getVertexIndex()<=4){
                PathState next=path;
                [unroll]for(int index=0;index<2;++index){
                    if(index>=count||max3(lobes[index].thp)<=0)continue;
                    BSDFSample sample=(BSDFSample)0;sample.wo=lobes[index].dir;sample.weight=lobes[index].thp;
                    sample.lobe=(uint)(lobes[index].transmission?LobeType::DeltaTransmission:LobeType::DeltaReflection);
                    sample.lobeP=lobes[index].probability;
                    PathState branch=path;
                    PathTracer::GenerateScatterRay(sample,surface.shadingData,surface.bsdf,branch,context);
                    if(!advanced){next=branch;advanced=true;}else pending[pendingCount++]=branch;
                }
                if(advanced){path=next;continue;}
            }
            // Opaque diffuse transport intentionally stops at its analytic receiver.
            // Metals get their native glossy reflection, including the primary one.
            if(surface.bsdf.data.SpecularTransmission()>0){
                uint seed=RtrStableHash(pixel.x+pixel.y*context.PtConsts.imageWidth)^path.getVertexIndex();
                float4 random=float4(RtrStableRandom(seed),RtrStableRandom(seed+1),RtrStableRandom(seed+2),RtrStableRandom(seed+3));
                BSDFSample sample;
                if(surface.bsdf.sample(surface.shadingData,random,sample,true)){
                    PathTracer::GenerateScatterRay(sample,surface.shadingData,surface.bsdf,path,context);continue;
                }
            }
            else if(surface.bsdf.data.Metallic()>0&&path.getVertexIndex()==1){
                // Conditional native glossy lobe: no diffuse draw and no inverse
                // lobe-selection probability. This is the old primary-metal policy.
                FalcorBSDF native=FalcorBSDF::make(surface.shadingData,surface.bsdf.data);
                uint seed=RtrStableHash(pixel.x+pixel.y*context.PtConsts.imageWidth);
                BSDFSample sample=(BSDFSample)0;float3 localDirection;
                if(native.specularReflection.sample(surface.shadingData.ToLocal(surface.shadingData.V),localDirection,
                    sample.pdf,sample.weight,sample.lobe,sample.lobeP,float3(.5,RtrStableRandom(seed),.5))){
                    sample.wo=surface.shadingData.FromLocal(localDirection);
                    sample.weight*=surface.bsdf.data.Metallic();
                    PathTracer::GenerateScatterRay(sample,surface.shadingData,surface.bsdf,path,context);continue;
                }
            }
            break;
        }
        if(pendingCount==0)break;
        path=pending[--pendingCount];
    }
    // Native no-denoiser merge and SR read exactly the current analytic signal.
    // No stochastic denoising layer or previous-frame signal is substituted.
    [unroll]for(uint layer=0;layer<3;++layer)u_DenoisingLayersHeader[uint3(pixel,layer)]=cDenoisingLayerInvalidBranchID;
    context.DenoisingLayers.StoreStableRadiance(pixel,radiance);
    u_OutputColor[pixel]=float4(radiance,1);
}
