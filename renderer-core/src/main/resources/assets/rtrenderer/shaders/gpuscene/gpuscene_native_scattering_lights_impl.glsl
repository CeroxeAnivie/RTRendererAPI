#extension GL_EXT_ray_tracing : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require
#extension GL_EXT_buffer_reference : require
#ifdef RTRENDERER_SCATTERING_RAY_QUERY
#extension GL_EXT_ray_query : require
#endif
#ifdef RTRENDERER_CAMERA_SCATTERING_PROFILE
#extension GL_EXT_shader_realtime_clock : require
#endif
#define RTRENDERER_NO_DIELECTRIC 1
#define GS_LIGHTING_NO_DIRECT_SURFACE 1
#define GS_CAMERA_SCATTERING_STAGE_COUNT 16u
#include "gpuscene_surface_transport.glsl"
layout(set=0,binding=GPU_SCENE_BINDING_TLAS) uniform accelerationStructureEXT sceneTlas;
#ifndef RTRENDERER_SCATTERING_OPAQUE_ONLY
layout(location=0) rayPayloadEXT GpuScenePayload payload;
#endif
#include "gpuscene_surface_lighting_runtime.glsl"
#define RTRENDERER_CAUSTICS 1
#include "gpuscene_caustic_map.glsl"
#include "gpuscene_caustic_gather.glsl"
layout(buffer_reference,std430,buffer_reference_align=16) buffer GsScatteringStorage {uvec4 lanes[];};
layout(push_constant) uniform GsScatteringDispatch {
    GsScatteringStorage storage; uint firstPixel; uint pixelCount;
    uint sourceOffset; uint primaryOffset; uint64_t reserved;
} dispatch;

#ifdef RTRENDERER_CAMERA_SCATTERING_PROFILE
layout(buffer_reference,std430,buffer_reference_align=8) buffer GsScatteringDiagnostic {uint64_t values[];};
uint64_t gsScatteringDetailStart(bool enabled){if(!enabled)return 0ul;return clockRealtimeEXT();}
void gsScatteringDetailEnd(uint record,uint stage,uint64_t started){
    if(started==0ul)return;
    uint word=((record/65536u)*GS_CAMERA_SCATTERING_STAGE_COUNT+stage)*2u;uint64_t ended=clockRealtimeEXT();
    GsScatteringDiagnostic target=GsScatteringDiagnostic(dispatch.reserved);
    target.values[word]+=ended-started;target.values[word+1u]+=1ul;
}
#define GS_SCATTER_DETAIL_BEGIN(stage) uint detailStage=stage;uint detailPath=(dispatch.firstPixel+gl_LaunchIDEXT.x)*samples+sampleIndex;uint detailBuckets=(uint(extent.x)*uint(extent.y)*samples-1u)/65536u+1u;uint detailRecord=detailPath+gl_LaunchIDEXT.y*detailBuckets*65536u;uint detailSequence=gsFrame.words[GPU_SCENE_FRAME_SEQUENCE_WORD];uint detailOffset=(detailSequence*7919u)&65535u;bool detailActive=dispatch.reserved!=0ul&&((detailPath/65536u+detailSequence)&7u)==0u&&(detailPath%65536u)==detailOffset;uint64_t detailStarted=gsScatteringDetailStart(detailActive)
#define GS_SCATTER_DETAIL_NEXT(stage) gsScatteringDetailEnd(detailRecord,detailStage,detailStarted);detailStage=stage;detailStarted=gsScatteringDetailStart(detailActive)
#define GS_SCATTER_DETAIL_END() gsScatteringDetailEnd(detailRecord,detailStage,detailStarted)
#define GS_SCATTER_DETAIL_PAUSE() gsScatteringDetailEnd(detailRecord,detailStage,detailStarted);detailStarted=0ul
#define GS_SCATTER_DETAIL_RESUME(stage) detailStage=stage;detailStarted=gsScatteringDetailStart(detailActive)
#else
#define GS_SCATTER_DETAIL_BEGIN(stage)
#define GS_SCATTER_DETAIL_NEXT(stage)
#define GS_SCATTER_DETAIL_END()
#define GS_SCATTER_DETAIL_PAUSE()
#define GS_SCATTER_DETAIL_RESUME(stage)
#endif

#ifdef RTRENDERER_SCATTERING_QUERY_AUDIT
uint gsScatteringAuditRecord;
void gsScatteringQueryAudit(bool matches) {
    uint word=((gsScatteringAuditRecord/65536u)*GS_CAMERA_SCATTERING_STAGE_COUNT+15u)*2u;
    GsScatteringDiagnostic target=GsScatteringDiagnostic(dispatch.reserved);
    target.values[word]+=matches?0ul:1ul;
    target.values[word+1u]+=1ul;
}
#endif

#include "gpuscene_native_scattering_common.glsl"
// A proposal changes only where the fixed number of connections are evaluated.
// Every contribution is divided by the full mixture PDF before CUDA integration.
vec4 gsScatteringAngularProposal(vec3 light,vec3 origin,vec3 direction,float distance) {
    vec3 delta=light-origin;float along=dot(delta,direction);
    float perpendicular=max(length(delta-direction*along),1.0);
    float angle=atan(-along,perpendicular);
    float span=atan(distance*perpendicular,perpendicular*perpendicular-along*(distance-along));
    return vec4(along,perpendicular,angle,span);
}
float gsScatteringAngularPdf(vec4 guide,float distance) {
    float offset=distance-guide.x;
    return guide.y/(guide.w*(guide.y*guide.y+offset*offset));
}
void main() {
    if(gl_LaunchIDEXT.x>=dispatch.pixelCount)return;
    ivec2 extent=ivec2(gsFrame.words[GPU_SCENE_FRAME_EXTENT_WORD],gsFrame.words[GPU_SCENE_FRAME_EXTENT_WORD+1u]);
    uint pixelIndex=dispatch.firstPixel+gl_LaunchIDEXT.x;ivec2 pixel=ivec2(pixelIndex%uint(extent.x),pixelIndex/uint(extent.x));
    uint samples=clamp(gsFrame.words[GPU_SCENE_FRAME_SAMPLE_COUNT_WORD],1u,8u),steps=dispatch.storage.lanes[0u].w;
    vec4 constants=uintBitsToFloat(dispatch.storage.lanes[3u]);vec3 sigma=constants.xyz;float maximum=constants.w;
    float g=uintBitsToFloat(dispatch.storage.lanes[4u]).w,cutoff=uintBitsToFloat(dispatch.storage.lanes[5u]).w;
    vec3 origin=gsFrameCameraPosition();
    vec2 jitter=vec2(uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_CURRENT_JITTER_WORD]),uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_CURRENT_JITTER_WORD+1u]));
    uint seed=gsTransportHash(pixelIndex^gsFrame.words[GPU_SCENE_FRAME_SEQUENCE_WORD]*0x9e3779b9u);
    for(uint sampleIndex=0u;sampleIndex<samples;++sampleIndex) {
        uint item=pixelIndex*samples+sampleIndex;
#ifdef RTRENDERER_SCATTERING_QUERY_AUDIT
        gsScatteringAuditRecord=item;
#endif
        GS_SCATTER_DETAIL_BEGIN(0u);
        bool tilePrimary=dispatch.storage.lanes[0u].x==0x524d5331u
            && dispatch.storage.lanes[2u].x>dispatch.storage.lanes[2u].z;
        uint primaryItem=tilePrimary?gl_LaunchIDEXT.x*samples+sampleIndex:item;
        uint base=dispatch.primaryOffset/16u+primaryItem*13u;
        vec2 location=vec2(pixel)+gsScatteringSampleOffset(sampleIndex,samples);if(!gsExactProjectionActive())location+=jitter;
        vec3 direction=gsRayDirectionAtSample(location,extent);
#ifndef RTRENDERER_SCATTERING_OPAQUE_ONLY
        payload.launchInfo=uvec4(pixel,extent);
#endif
        vec4 hit;bool present;
#ifdef RTRENDERER_SCATTERING_CACHED_PRIMARY
        {
            uint cached=dispatch.primaryOffset/16u+gl_LaunchIDEXT.x*samples+sampleIndex;
            uvec4 record=dispatch.storage.lanes[cached];
            hit=vec4(0,0,0,uintBitsToFloat(record.x));present=record.y!=0u;
        }
#else
        if(dispatch.storage.lanes[0u].x==0x52534331u && dispatch.storage.lanes[1u].w!=0u) {
            uint64_t primarySink=uint64_t(dispatch.storage.lanes[7u].z)|(uint64_t(dispatch.storage.lanes[7u].w)<<32u);
            if(primarySink!=0ul) {
                payload.worldPositionAndDistance=vec4(0,0,0,-1);
                payload.previousWorldPosition=vec4(0);payload.motionRevision=uvec2(0);
                payload.worldNormalAndRoughness=vec4(0,1,0,1);payload.baseColorAndOpacity=vec4(0,0,0,1);
                payload.emissiveAndMetallic=vec4(0);payload.transmissionIor=vec4(0,1,0,0);
                payload.surfaceState=uvec4(0);payload.compositeState=vec4(0);
            }
            payload.state=uvec4(0u,GS_PAYLOAD_RADIANCE_QUERY,0u,0u);payload.mediumBoundary=uvec4(0u);
            if(primarySink!=0ul)payload.mediumBoundary.x=gl_LaunchIDEXT.x*samples+sampleIndex;
            traceRayEXT(sceneTlas,primarySink!=0ul?gl_RayFlagsOpaqueEXT:gl_RayFlagsNoneEXT,
                0xff,0,0,0,origin,gsRayEpsilon(origin),direction,1e20,0);
            hit=payload.worldPositionAndDistance;present=payload.state.x!=0u;
            if(primarySink!=0ul) {
                GsScatteringStorage destination=GsScatteringStorage(primarySink);
                // Field planes keep neighboring invocations' stores contiguous;
                // PBR and the final consumer read this same allocation directly.
                uint outBase=item,outStride=uint(extent.x)*uint(extent.y)*samples;
                destination.lanes[outBase]=floatBitsToUint(payload.worldPositionAndDistance);
                destination.lanes[outBase+3u*outStride]=floatBitsToUint(payload.worldNormalAndRoughness);
                destination.lanes[outBase+4u*outStride]=floatBitsToUint(payload.baseColorAndOpacity);
                destination.lanes[outBase+5u*outStride]=floatBitsToUint(payload.emissiveAndMetallic);
                destination.lanes[outBase+7u*outStride]=payload.state;
                destination.lanes[outBase+8u*outStride]=payload.surfaceState;
                destination.lanes[outBase+9u*outStride]=floatBitsToUint(payload.compositeState);
                // Multi-sample fallback may need the representative geometry of
                // another sample. Retain complete payloads there conservatively.
                bool compactPrimary=samples==1u
                    &&(gsFrame.words[GPU_SCENE_FRAME_TEMPORAL_FLAGS_WORD]&GPU_SCENE_TEMPORAL_FLAG_ENABLED)==0u
                    &&(gsFrame.words[GPU_SCENE_FRAME_FEATURE_FLAGS_WORD]
                        &(GPU_SCENE_FEATURE_FLAG_DENOISING_ACTIVE|GPU_SCENE_FEATURE_FLAG_RECONSTRUCTION_ACTIVE))==0u
                    &&gsFrame.words[GPU_SCENE_FRAME_CAUSTIC_PHOTONS_WORD]==0u
                    &&(payload.surfaceState.z==0u||payload.compositeState.x<=0.0)
                    &&(payload.state.x==0u||((payload.state.z>>GPU_SCENE_SHADING_MODEL_SHIFT)&GPU_SCENE_SHADING_MODEL_MASK)
                        !=GPU_SCENE_SHADING_LIGHTMAP_MODULATED);
                if(!compactPrimary) {
                    destination.lanes[outBase+1u*outStride]=floatBitsToUint(payload.previousWorldPosition);
                    destination.lanes[outBase+2u*outStride]=uvec4(payload.motionRevision,0u,0u);
                    destination.lanes[outBase+6u*outStride]=floatBitsToUint(payload.transmissionIor);
                    destination.lanes[outBase+10u*outStride]=payload.launchInfo;
                    destination.lanes[outBase+11u*outStride]=payload.mediumBoundary;
                    destination.lanes[outBase+12u*outStride]=uvec4(pixelIndex,sampleIndex,1u,1u);
                }
            }
            uint compact=dispatch.primaryOffset/16u+gl_LaunchIDEXT.x*samples+sampleIndex;
            dispatch.storage.lanes[compact]=uvec4(floatBitsToUint(hit.w),present?1u:0u,0u,0u);
        } else {hit=uintBitsToFloat(dispatch.storage.lanes[base]);present=dispatch.storage.lanes[base+7u].x!=0u;}
#endif
#ifdef RTRENDERER_SCATTERING_PRIMARY_PASS
        // This specialization terminates before all reservoir/integration state.
        // It preserves exactly the original primary trace and planar payload.
        GS_SCATTER_DETAIL_END();
        continue;
#endif
        float distance=present?min(max(hit.w,0.0),maximum):maximum;
        GS_SCATTER_DETAIL_NEXT(1u);
        uint upper=gsFrame.words[GPU_SCENE_FRAME_LIGHT_SLOT_UPPER_BOUND_WORD],sampleCount=gsFrame.words[GPU_SCENE_FRAME_SCATTERING_LIGHT_SAMPLES_WORD];
#ifdef RTRENDERER_SCATTERING_EXHAUSTIVE_LIGHTS
        // CPU admission proves 0 <= upper <= sampleCount <= 8. Preserve the
        // original weight/range filter and ascending contribution order with
        // one mask instead of three arrays live across every shadow query.
        uint validLightMask=0u;
        for(uint slot=0u;slot<upper;++slot) {
            GS_SCATTER_DETAIL_NEXT(1u);
            float weight=gsScatteringLightWeight(slot,origin,direction,distance);
            GS_SCATTER_DETAIL_NEXT(2u);
            if(weight>0.0)validLightMask|=1u<<slot;
        }
        bool exhaustive=true;
        uint selectedCount=upper;
#else
        uint selected[8],firstCandidates[8],candidates=0u;float total=0.0;
        if(sampleCount>0u) {
            [[dont_unroll]] for(uint slot=0u;slot<upper;++slot) {
                GS_SCATTER_DETAIL_NEXT(1u);
                float weight=gsScatteringLightWeight(slot,origin,direction,distance);
                GS_SCATTER_DETAIL_NEXT(2u);
                if(weight>0.0) {
                    if(candidates<8u)firstCandidates[candidates]=slot;++candidates;total+=weight;
                    [[dont_unroll]] for(uint j=0u;j<sampleCount;++j)
                        if(gsTransportRandom(seed^slot*0x85ebca6bu^j*0xc2b2ae35u)<weight/total)selected[j]=slot;
                }
            }
        }
        // The proposal uses the whole camera segment, not the integration point.
        // Retain its correction once per selected light for all scattering intervals.
        bool exhaustive=candidates<=sampleCount;
        uint selectedCount=exhaustive?candidates:sampleCount;
        float selectedFactors[8];
        if(sampleCount>0u) {
            [[dont_unroll]] for(uint k=0u;k<selectedCount;++k) {
                float factor=1.0;
                if(!exhaustive) {
                    GS_SCATTER_DETAIL_NEXT(1u);
                    float weight=gsScatteringLightWeight(selected[k],origin,direction,distance);
                    GS_SCATTER_DETAIL_NEXT(2u);
                    factor=total/(float(sampleCount)*weight);
                }
                selectedFactors[k]=factor;
            }
        }
#endif
        GS_SCATTER_DETAIL_NEXT(3u);
        vec3 sunDirection=-normalize(gsFrameVec3(GPU_SCENE_FRAME_SUN_DIRECTION_WORD));
        vec3 sun=gsFrameVec3(GPU_SCENE_FRAME_SUN_COLOR_WORD)*uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_SUN_INTENSITY_WORD]);
        float interval=distance/float(steps),reference=min(sigma.x,min(sigma.y,sigma.z));
        bool directPacket=dispatch.storage.lanes[0u].x==0x52534331u;
        vec4 guide0=vec4(0),guide1=vec4(0);uint guideCount=0u;
        // Keep the original per-interval estimator for smooth/directional-only
        // rays and cutoff-truncated rays. Guide only strongly localized lamps.
        if(directPacket&&exhaustive&&sampleCount>0u&&distance>8.0
                &&exp(-reference*distance)>=cutoff) {
            for(uint k=0u;k<selectedCount&&guideCount<2u;++k) {
#ifdef RTRENDERER_SCATTERING_EXHAUSTIVE_LIGHTS
                if((validLightMask&(1u<<k))==0u)continue;
                uint lightBase=k*GPU_SCENE_LIGHT_RECORD_WORDS;
#else
                uint lightBase=firstCandidates[k]*GPU_SCENE_LIGHT_RECORD_WORDS;
#endif
                uint flags=gsLights.words[lightBase+GPU_SCENE_LIGHT_FLAGS_WORD];
                if(((flags>>1u)&7u)==GPU_SCENE_LIGHT_DIRECTIONAL)continue;
                vec4 guide=gsScatteringAngularProposal(gsScatteringLightPosition(lightBase),origin,direction,distance);
                if(guide.x<=0.0||guide.x>=distance||guide.y>2.0||distance/guide.y<12.0)continue;
                if(guideCount==0u)guide0=guide;else guide1=guide;
                ++guideCount;
            }
        }
        // Each interval has an independent source slot. One-dimensional callers
        // keep the original loop; a joint cached producer exposes intervals in Y.
        for(uint j=gl_LaunchIDEXT.y;j<steps;j+=gl_LaunchSizeEXT.y) {
            float start=float(j)*interval,local=0.0,absolute=0.0,inversePdf=1.0;vec3 source=vec3(0);
            vec3 prefix=exp(-sigma*start);
            if(interval>0.0&&max(prefix.x,max(prefix.y,prefix.z))>=cutoff) {
                float u=gsTransportRandom(seed^(j+1u)*0x27d4eb2du),tau=reference*interval;
                local=tau<1e-3?u*interval:-log(max(1.0-u*(1.0-exp(-tau)),1e-30))/reference;
                // The inverse CDF is supported on this interval. Float exp/log
                // cancellation near u=1 can otherwise step beyond its endpoint.
                local=clamp(local,0.0,interval);
                absolute=start+local;
                float localIntegral=reference*interval<1e-3?interval:(1.0-exp(-tau))/reference;
                inversePdf=tau<1e-3?interval:localIntegral*exp(reference*local);
                if(guideCount>0u) {
                    float choice=(float(j)+u)/float(steps)*float(guideCount+1u);
                    uint component=min(uint(choice),guideCount);float xi=fract(choice);
                    float fullTau=reference*distance;
                    if(component==0u)absolute=fullTau<1e-3?xi*distance
                        :-log(max(1.0-xi*(1.0-exp(-fullTau)),1e-30))/reference;
                    else {
                        vec4 guide=component==1u?guide0:guide1;
                        absolute=guide.x+guide.y*tan(guide.z+xi*guide.w);
                    }
                    absolute=clamp(absolute,0.0,distance);
                    float fullIntegral=fullTau<1e-3?distance:(1.0-exp(-fullTau))/reference;
                    float pdf=(fullTau<1e-3?1.0/distance:exp(-reference*absolute)/fullIntegral)
                        +gsScatteringAngularPdf(guide0,absolute);
                    if(guideCount==2u)pdf+=gsScatteringAngularPdf(guide1,absolute);
                    inversePdf=float(guideCount+1u)/(float(steps)*pdf);
                }
                vec3 position=origin+direction*absolute;
                GS_SCATTER_DETAIL_NEXT(3u);
                if(max(sun.x,max(sun.y,max(sun.z,0.0)))>0.0) {
                    GS_SCATTER_DETAIL_NEXT(4u);
                    float phase=gsScatteringPhase(dot(direction,sunDirection),g);
                    vec3 sunContribution=sun*phase;
#ifdef RTRENDERER_CAMERA_SCATTERING_PROFILE
                    GS_SCATTER_DETAIL_PAUSE();
                    uint64_t probeTicks,fallbackTicks,anyHitTicks,closestHitTicks;
                    uint anyHitCalls,closestHitCalls;
                    vec3 sunVisibility=gsShadowTransmittanceDetailed(position,sunDirection,sunDirection,1e20,
                        detailActive,probeTicks,fallbackTicks,anyHitTicks,anyHitCalls,
                        closestHitTicks,closestHitCalls);
                    gsScatteringProfileRecord(detailActive,detailRecord,5u,probeTicks);
                    gsScatteringProfileRecord(detailActive,detailRecord,12u,fallbackTicks);
                    gsScatteringProfileRecord(detailActive,detailRecord,13u,anyHitTicks,uint64_t(anyHitCalls));
                    gsScatteringProfileRecord(detailActive,detailRecord,14u,closestHitTicks,uint64_t(closestHitCalls));
                    GS_SCATTER_DETAIL_RESUME(6u);
#else
                    GS_SCATTER_DETAIL_NEXT(5u);
                    vec3 sunVisibility=gsShadowTransmittance(position,sunDirection,sunDirection,1e20);
                    GS_SCATTER_DETAIL_NEXT(6u);
#endif
                    sunVisibility*=exp(-sigma*min(maximum,1e20));
                    source+=sunContribution*sunVisibility;
                }
                GS_SCATTER_DETAIL_NEXT(7u);
#ifndef RTRENDERER_SCATTERING_EXHAUSTIVE_LIGHTS
                if(sampleCount==0u) {
                    [[dont_unroll]] for(uint slot=0u;slot<upper;++slot) {
                        GS_SCATTER_DETAIL_NEXT(1u);
                        float weight=gsScatteringLightWeight(slot,origin,direction,distance);
                        GS_SCATTER_DETAIL_NEXT(7u);
                        if(weight>0.0){
                            GS_SCATTER_DETAIL_PAUSE();
#ifdef RTRENDERER_CAMERA_SCATTERING_PROFILE
                            source+=gsScatteringLightProfile(slot,position,direction,g,sigma,maximum,detailRecord,detailActive);
#else
                        source+=gsScatteringLight(slot,position,direction,g,sigma,maximum);
#endif
                            GS_SCATTER_DETAIL_RESUME(7u);
                        }
                    }
                }else
#endif
                {
                    [[dont_unroll]] for(uint k=0u;k<selectedCount;++k) {
#ifdef RTRENDERER_SCATTERING_EXHAUSTIVE_LIGHTS
                        if((validLightMask&(1u<<k))==0u)continue;
                        uint slot=k;
                        float factor=1.0;
#else
                        uint slot=exhaustive?firstCandidates[k]:selected[k];
                        GS_SCATTER_DETAIL_NEXT(2u);
                        float factor=selectedFactors[k];
#endif
                        GS_SCATTER_DETAIL_NEXT(7u);
                        GS_SCATTER_DETAIL_PAUSE();
#ifdef RTRENDERER_CAMERA_SCATTERING_PROFILE
                        source+=gsScatteringLightProfile(slot,position,direction,g,sigma,maximum,detailRecord,detailActive)*factor;
#else
                        source+=gsScatteringLight(slot,position,direction,g,sigma,maximum)*factor;
#endif
                        GS_SCATTER_DETAIL_RESUME(7u);
                    }
                }
                GS_SCATTER_DETAIL_NEXT(9u);
                source+=gsGatherVolumeCaustics(position,-direction,g);
            }
            GS_SCATTER_DETAIL_NEXT(10u);
            uint localPath=gl_LaunchIDEXT.x*samples+sampleIndex;
            // The shared uniform arena stores interval planes. Adjacent Vulkan
            // producers and CUDA consumers access contiguous paths in each plane.
            // Multiple-scattering headers reuse this word for the bounce budget.
            // Layout flags belong exclusively to the direct-scattering ABI.
            bool planarSources=directPacket&&(dispatch.storage.lanes[1u].w&2u)!=0u;
            uint outLane=dispatch.sourceOffset/16u+(planarSources
                ?j*dispatch.storage.lanes[0u].z+localPath:localPath*steps+j);
            GS_SCATTER_DETAIL_NEXT(11u);
            dispatch.storage.lanes[outLane]=floatBitsToUint(directPacket
                ?vec4(source*inversePdf,absolute):vec4(source,local));
            GS_SCATTER_DETAIL_NEXT(3u);
        }
        GS_SCATTER_DETAIL_END();
    }
}
