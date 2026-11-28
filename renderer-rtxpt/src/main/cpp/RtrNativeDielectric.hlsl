#define RTR_NATIVE_LIGHTING_LIBRARY
#define RTR_WORK_STATISTICS 0

#if RTR_DIELECTRIC_PASS == 1
#define RTR_DIELECTRIC_PROBE 1
RWByteAddressBuffer u_OpticalProbe : register(u16);
static bool rtrProbeSelected;
static uint rtrProbeCount;
static uint2 rtrTerminalProbePath;

void RtrWriteProbe(uint kind,uint3 detail,float4 value){
    if(!rtrProbeSelected)return;
    // Reserve the final record for the signal even if the detailed path overflows.
    if(rtrProbeCount==255u&&kind!=16u){u_OpticalProbe.Store(12,1);return;}
    uint offset=16u+32u*rtrProbeCount++;
    u_OpticalProbe.Store4(offset,uint4(kind,detail));
    u_OpticalProbe.Store4(offset+16,asuint(value));
    u_OpticalProbe.Store(8,rtrProbeCount);
}
#define RTR_PROBE(kind,detail,value) RtrWriteProbe(kind,detail,value)
#define RTR_TERMINAL_PROBE(kind,detail,value) RtrWriteProbe(kind,uint3(rtrTerminalProbePath,detail),value)
#else
#define RTR_PROBE(kind,detail,value)
#endif

#include "RtrNativeLighting.hlsl"
#include "RtrNativeOpticalQueries.hlsli"

RWTexture2D<float4> u_Dielectric : register(u15);
Texture2D<float4> t_DielectricSky : register(t15);

static const uint RtrPathLimit = 24u;
static const uint RtrWorkLimit = 48u;
static const uint RtrDeferredLimit = 2u;
// Don't launch a secondary branch carrying less than 0.2% of incident power.
static const float RtrMinSecondaryPower = 1.f / 512.f;

void RtrWriteOpticalSignal(uint2 pixel,float4 signal){
    RTR_PROBE(16,uint3(pixel,0),signal);
    u_Dielectric[pixel]=signal;
}

float3 RtrOpticalEscapeRadiance(float3 direction)
{
    static const uint DielectricSkyFlag=1u<<7; // Matches RtrNativeLightingPass::DielectricSkyFlag.
    [branch]if((g.publication.w&DielectricSkyFlag)==0)return float3(g.skyOcclusion.zw,g.wetFilmResponse.w);
    static const float invPi=0.3183098861837907f;
    float longitude=any(direction.xz!=0)?atan2(direction.x,-direction.z):0;
    float2 uv=float2(0.5f+longitude*(0.5f*invPi),acos(clamp(direction.y,-1.f,1.f))*invPi);
    // Texture policy 2 is linear, with U wrapping and V clamping.
    return t_DielectricSky.SampleLevel(s_TexturePolicy2,uv,0).rgb;
}

float3 RtrOpticalTerminal(RayDesc ray,RtrOpticalHit hit,uint path,uint depth)
{
    RtrReceiver receiver=RtrLoad(hit.instance,hit.geometry,hit.primitive,hit.barycentrics,
        ray.Origin,ray.Direction,hit.distance,hit.worldToObject);
    uint index=t_Instances[hit.instance].firstGeometryIndex+hit.geometry;
    RtrGeometryAttributes mapping=t_GeometryBaselines[index];
    MaterialConstants material=RtrMaterial(t_Geometry[index],hit.primitive,mapping);
    float3 emission=material.emissiveColor;
    if((material.flags&MaterialFlags_UseEmissiveTexture)!=0)
        emission*=RtrSampleTexture(material.emissiveTextureIndex,receiver.uv,mapping.emissivePolicy).rgb;
#if RTR_DIELECTRIC_PROBE
    rtrTerminalProbePath=uint2(path,depth);
#endif
    float3 incident=RtrReflectedLight(receiver,true);
    float3 lighting=(receiver.albedo*incident)*receiver.diffuseWeight;
    RTR_PROBE(20,uint3(path,depth,hit.primitive),float4(receiver.position,hit.distance));
    RTR_PROBE(21,uint3(path,depth,mapping.basePolicy),float4(receiver.uv,0,0));
    RTR_PROBE(22,uint3(path,depth,index),float4(receiver.albedo,receiver.diffuseWeight));
    RTR_PROBE(23,uint3(path,depth,index),float4(incident,0));
#if RTR_DIELECTRIC_PROBE
    if(rtrProbeSelected&&(material.flags&MaterialFlags_UseBaseOrDiffuseTexture)!=0){
        // Compare both filters at the exact same UV and preserve the address mode.
        uint address=mapping.basePolicy&3u;
        RTR_PROBE(26,uint3(path,depth,address|4u),RtrSampleTexture(material.baseOrDiffuseTextureIndex,receiver.uv,address|4u));
        RTR_PROBE(27,uint3(path,depth,address),RtrSampleTexture(material.baseOrDiffuseTextureIndex,receiver.uv,address));
    }
#endif
    RTR_PROBE(10,uint3(path,depth,index),float4(lighting,receiver.diffuseWeight));
    RTR_PROBE(11,uint3(path,depth,material.flags),float4(emission,0));
    return lighting+emission;
}

float3 RtrOpticalAttenuate(RtrDielectricMedium medium,float distance,uint path,uint depth,float3 power)
{
    float3 transmittance=RtrDielectricBeerLambert(medium,distance);
    RTR_PROBE(24,uint3(path,depth,0),float4(transmittance,distance));
    power*=transmittance;
    RTR_PROBE(25,uint3(path,depth,0),float4(power,medium.ior));
    return power;
}

struct RtrDeferredOpticalRay {
    float3 origin,direction;
    float3 power;
    RtrDielectricStack stack;
    uint depth;
#if RTR_DIELECTRIC_PROBE
    uint path;
#endif
};

float4 RtrTraceDielectric(uint2 pixel,uint2 extent)
{
    RtrSetSourcePixel(pixel);
    float2 ndc=(float2(pixel)+.5)/float2(extent)*float2(2,-2)+float2(-1,1)+g.farJitter.yz;
    float4 nearView=mul(g.inverseClip,float4(ndc,g.cameraNear.w,1)),farView=mul(g.inverseClip,float4(ndc,g.farJitter.x,1));
    RayDesc ray;
    ray.Origin=g.cameraNear.xyz;
    ray.Direction=normalize(mul((float3x3)g.cameraToWorld,farView.xyz/farView.w-nearView.xyz/nearView.w));
    ray.TMin=g.gi.z;ray.TMax=1e20;
    bool immersed=g.cameraMediumSlots.x!=0&&g.cameraMediumSlots.x!=0xffffffffu;
    RTR_PROBE(1,uint3(pixel,immersed),float4(ray.Origin,ray.TMin));
    RTR_PROBE(2,uint3(g.grid.xy,0),float4(ray.Direction,0));
    RtrDielectricStack stack=RtrDielectricEmptyStack();
    [branch]if(immersed){
        [loop]for(uint i=0;i<RTR_DIELECTRIC_CAPACITY;++i){
            uint slot=g.cameraMediumSlots[i];
            if(slot==0xffffffffu)break;
            uint instance=slot-1;
            uint surface=RtrOpticalVolumeSurface(instance);
            if(surface==0||t_SurfaceMaterials[surface-1].mediumPriorityPlusOne==0||
                !RtrOpticalApply(stack,uint2(surface,instance),true)){
                RTR_PROBE(14,uint3(0,0,1),float4(0,0,0,0));
                return 0;
            }
        }
    }
    RtrOpticalHit hit;
    [branch]if(immersed){
        if(!RtrFindOpticalHit(ray,RtrReceiverRole|RtrMediumRole,hit)){
            RTR_PROBE(14,uint3(0,0,2),float4(0,0,0,0));
            return 0;
        }
    }else{
        // Dedicated hardware mask lets ordinary pixels leave without traversing receiver geometry.
        if(!RtrFindOpticalHit(ray,RtrMediumRole,hit)||hit.surface==0||t_SurfaceMaterials[hit.surface-1].mediumPriorityPlusOne==0){
            RTR_PROBE(14,uint3(0,0,3),float4(0,0,0,0));
            return 0;
        }
        RayDesc front=ray;front.TMax=hit.distance+RtrOpticalContactTolerance(hit.distance);
        RtrOpticalHit blocker;
        if(RtrFindOpticalHit(front,RtrReceiverRole,blocker)){
            RTR_PROBE(17,uint3(blocker.instance,blocker.geometry,blocker.primitive),float4(blocker.distance,hit.distance,0,0));
            return 0;
        }
    }

    float firstDistance=immersed?0:hit.distance;
    float straightDistance=0;
    float cameraIor=RtrDielectricActiveMedium(stack).ior;
    float3 power=1,radiance=0;
    bool resolved=false;
    uint work=0,depth=0,deferredCount=0;
    uint path=0;
#if RTR_DIELECTRIC_PROBE
    uint nextPath=1;
#endif
    RtrDeferredOpticalRay deferred[RtrDeferredLimit];
    bool hasHit=true;
    [loop]while(true){
        [loop]while(work<RtrWorkLimit&&depth<RtrPathLimit){
            ++work;
            RtrDielectricMedium incident=RtrDielectricActiveMedium(stack);
            // The eta factors telescope; keep branch power in a common measure for pruning.
            float eta=cameraIor/incident.ior;
            if(!hasHit){
                if(firstDistance>0&&stack.count==0){
                    float3 contribution=power*(eta*eta)*RtrOpticalEscapeRadiance(ray.Direction);
                    RTR_PROBE(13,uint3(path,depth,0),float4(contribution,eta));
                    radiance+=contribution;
                    resolved=true;
                }else{RTR_PROBE(14,uint3(path,depth,4),float4(power,stack.count));}
                break;
            }
            RTR_PROBE(3,uint3(hit.instance,hit.geometry,hit.primitive),float4(hit.distance,hit.barycentrics,hit.surface));
            bool boundary=hit.surface!=0&&t_SurfaceMaterials[hit.surface-1].mediumPriorityPlusOne!=0;
            float3 outward=0;
            RtrOpticalEvent event;
            if(boundary){
                outward=RtrOpticalOutward(hit.instance,hit.geometry,hit.primitive);
                hit.entering=dot(outward,ray.Direction)<0;
                RTR_PROBE(4,uint3(path,depth,hit.entering),float4(outward,hit.distance));
                RtrOpticalHit contact;
                // An outside primary ray already checked the contact in its front-receiver query.
                if(!RtrOpticalGather(ray,hit,(immersed||depth>0)?RtrReceiverRole:0,event,contact)){
                    RTR_PROBE(14,uint3(path,depth,5),float4(power,stack.count));
                    break;
                }
                if(contact.distance>0){
                    RTR_PROBE(19,uint3(contact.instance,contact.geometry,contact.primitive),
                        float4(contact.distance,hit.distance,contact.barycentrics));
                    hit=contact;
                    boundary=false;
                }
            }
            if(!boundary){
                power=RtrOpticalAttenuate(incident,hit.distance,path,depth,power);
                if(firstDistance==0){
                    return float4(power,-(straightDistance+hit.distance));
                }
                float3 contribution=power*(eta*eta)*RtrOpticalTerminal(ray,hit,path,depth);
                RTR_PROBE(12,uint3(path,depth,0),float4(contribution,eta));
                radiance+=contribution;
                resolved=true;
                break;
            }
            // An inferred camera medium is used only when the caller didn't supply containment.
            if(work==1&&g.cameraMediumSlots.x==0){
                bool valid=true;
                [loop]for(uint i=0;i<event.exitCount&&valid;++i)valid=RtrOpticalApply(stack,event.exits[i],true);
                if(!valid){
                    RTR_PROBE(14,uint3(path,depth,6),float4(power,stack.count));
                    break;
                }
                incident=RtrDielectricActiveMedium(stack);
                cameraIor=incident.ior;
            }
            power=RtrOpticalAttenuate(incident,hit.distance,path,depth,power);
            RtrDielectricStack crossed;
            if(!RtrOpticalCross(stack,event,crossed)){
                RTR_PROBE(14,uint3(path,depth,7),float4(power,stack.count));
                break;
            }
            RtrDielectricMedium transmitted=RtrDielectricActiveMedium(crossed);
            RTR_PROBE(5,uint3(path,event.entryCount,event.exitCount),float4(incident.ior,transmitted.ior,stack.count,crossed.count));
            ray.Origin+=hit.distance*ray.Direction;
            ++depth;
            if(firstDistance==0){
                straightDistance+=hit.distance;
                // An identical medium across a section seam doesn't create a visible interface.
                if(incident.ior==transmitted.ior&&all(incident.absorption==transmitted.absorption)){
                    stack=crossed;
                    hasHit=RtrFindOpticalHit(ray,RtrReceiverRole|RtrMediumRole,hit);
                    continue;
                }
                firstDistance=straightDistance;
            }
            if(incident.ior!=transmitted.ior){
                float3 normal=hit.entering?outward:-outward;
                RtrDielectricInterface scatter=RtrDielectricEvaluateInterface(ray.Direction,normal,incident.ior,transmitted.ior);
                float reflectance=scatter.reflectance,transmittance=1.f-reflectance;
                bool reflect=reflectance>=transmittance;
                float3 secondary=power*min(reflectance,transmittance);
                RTR_PROBE(6,uint3(path,depth,scatter.totalInternalReflection),float4(reflectance,transmittance,incident.ior,transmitted.ior));
                RTR_PROBE(7,uint3(path,depth,0),float4(scatter.reflectedDirection,reflectance));
                RTR_PROBE(8,uint3(path,depth,0),float4(scatter.transmittedDirection,transmittance));
#if RTR_DIELECTRIC_PROBE
                if(rtrProbeSelected&&work==1){
                    // Record the previous per-pixel first-event decision for comparison only.
                    uint seed=(pixel.x+g.grid.x*pixel.y)*747796405u+2891336453u;
                    uint word=((seed>>((seed>>28u)+4u))^seed)*277803737u;
                    float draw=float(((word>>22u)^word)>>8u)*(1.f/16777216.f);
                    RTR_PROBE(18,uint3(path,depth,scatter.totalInternalReflection||draw<reflectance),float4(draw,reflectance,0,0));
                }
                uint deferredPath=0xffffffffu;
#endif
                // Follow the stronger lobe first and add the weaker lobe's radiance, not a
                // random choice between them. TIR has no transmission and never pushes a ray.
                if(deferredCount<RtrDeferredLimit&&max(secondary.x,max(secondary.y,secondary.z))>=RtrMinSecondaryPower){
                    RtrDeferredOpticalRay next;
                    next.origin=ray.Origin;
                    next.direction=normalize(reflect?scatter.transmittedDirection:scatter.reflectedDirection);
                    next.power=secondary;
                    if(reflect)next.stack=crossed;
                    else next.stack=stack;
                    next.depth=depth;
#if RTR_DIELECTRIC_PROBE
                    next.path=nextPath++;
                    deferredPath=next.path;
#endif
                    deferred[deferredCount++]=next;
                }
                ray.Direction=normalize(reflect?scatter.reflectedDirection:scatter.transmittedDirection);
                power*=max(reflectance,transmittance);
                RTR_PROBE(9,uint3(path,deferredPath,reflect),float4(power,depth));
                if(!reflect)stack=crossed;
            }else stack=crossed;
            hasHit=RtrFindOpticalHit(ray,RtrReceiverRole|RtrMediumRole,hit);
        }
        if(deferredCount==0||work>=RtrWorkLimit)break;
        RtrDeferredOpticalRay next=deferred[--deferredCount];
        ray.Origin=next.origin;ray.Direction=next.direction;
        power=next.power;stack=next.stack;depth=next.depth;
#if RTR_DIELECTRIC_PROBE
        path=next.path;
        RTR_PROBE(15,uint3(path,depth,stack.count),float4(power,0));
#endif
        hasHit=RtrFindOpticalHit(ray,RtrReceiverRole|RtrMediumRole,hit);
    }
    if(work>=RtrWorkLimit||depth>=RtrPathLimit){
        RTR_PROBE(14,uint3(path,depth,8),float4(power,work));
    }
    // If none of the paths resolved, leave the host's raster color intact.
    return resolved?float4(radiance,firstDistance):0;
}

#if RTR_DIELECTRIC_PASS < 2
[numthreads(8,8,1)]
void main(uint2 pixel:SV_DispatchThreadID)
{
    if(any(pixel>=g.grid.xy))return;
#if RTR_DIELECTRIC_PROBE
    rtrProbeSelected=all(pixel==u_OpticalProbe.Load2(0));
    rtrProbeCount=0;
#endif
    RtrWriteOpticalSignal(pixel,RtrTraceDielectric(pixel,g.grid.xy));
}
#else
RWTexture2D<float4> u_Refinement : register(u17);
uint2 RtrAtlasPosition(uint index){return uint2(index%g.grid.x,index/g.grid.x);}
uint2 RtrRefinementStride(){return (g.opticalViewport.xy+g.grid.xy-1)/g.grid.xy;}
uint2 RtrViewportCellBegin(uint2 cell){return (2*cell*g.opticalViewport.xy+g.grid.xy-1)/(2*g.grid.xy);}
uint RtrMetadataTexels(){return (g.grid.x*g.grid.y+3)/4;}

#if RTR_DIELECTRIC_PASS == 2
Texture2D<float4> t_CoarseDielectric : register(t17);
RWByteAddressBuffer u_RefinementJobs : register(u18);

float3 RtrEdgeMetric(float3 radiance){return sqrt(max(radiance,0)/(1+max(radiance,0)));}
bool RtrNeedsRefinement(uint2 cell,float4 center){
    if(center.a<0)return false; // Straight opaque receivers have a separate resolve.
    float3 reference=RtrEdgeMetric(center.rgb);
    [loop]for(int dy=-1;dy<=1;++dy){
        [unroll]for(int dx=-1;dx<=1;++dx){
            if(dx==0&&dy==0)continue;
            int2 neighbor=int2(cell)+int2(dx,dy);
            if(any(neighbor<0)||any(neighbor>=int2(g.grid.xy)))continue;
            float4 other=t_CoarseDielectric.Load(int3(neighbor,0));
            if((center.a>0)!=(other.a>0))return true;
            if(center.a>0&&any(abs(reference-RtrEdgeMetric(other.rgb))>0.035))return true;
        }
    }
    return false;
}

[numthreads(64,1,1)]
void main(uint2 thread:SV_DispatchThreadID){
    uint header=thread.x+thread.y*65536u;
    if(header>=RtrMetadataTexels())return;
    float4 pointers=0;
    [unroll]for(uint lane=0;lane<4;++lane){
        uint index=header*4+lane;
        if(index>=g.grid.x*g.grid.y)break;
        uint2 cell=RtrAtlasPosition(index);
        if(!RtrNeedsRefinement(cell,t_CoarseDielectric.Load(int3(cell,0))))continue;
        uint job;
        u_RefinementJobs.InterlockedAdd(0,1,job);
        if(job>=g.opticalViewport.z)continue;
        u_RefinementJobs.Store(4*(job+1),index);
        pointers[lane]=float(job+1);
    }
    u_Refinement[RtrAtlasPosition(header)]=pointers;
}
#else
ByteAddressBuffer t_RefinementJobs : register(t16);

[numthreads(64,1,1)]
void main(uint thread:SV_DispatchThreadID){
    uint2 stride=RtrRefinementStride();
    uint samples=stride.x*stride.y;
    uint job=thread/samples;
    if(job>=min(t_RefinementJobs.Load(0),g.opticalViewport.z))return;
    uint sample=thread%samples;
    uint2 cell=RtrAtlasPosition(t_RefinementJobs.Load(4*(job+1)));
    uint2 pixel=RtrViewportCellBegin(cell)+uint2(sample%stride.x,sample/stride.x);
    if(any(pixel>=RtrViewportCellBegin(cell+1)))return;
    uint atlasIndex=RtrMetadataTexels()+thread;
    u_Refinement[RtrAtlasPosition(atlasIndex)]=RtrTraceDielectric(pixel,g.opticalViewport.xy);
}
#endif
#endif
