// Optical queries reuse the renderer's exact trace, texture, sky and terminal surface functions.
#include "gpuscene_native_scattering_common.glsl"
layout(buffer_reference,std430,buffer_reference_align=16) buffer GsOpticalStorage {uvec4 lanes[];};
layout(push_constant) uniform GsOpticalDispatch {
    GsOpticalStorage storage;uint firstPixel;uint pixelCount;uint64_t initialize;uint64_t primaryAddress;
} gsOptical;
GpuScenePayload gsTraceOpticalRadiance(vec3 origin,vec3 direction,uvec4 launch) {
    payload.state=uvec4(0u,GS_PAYLOAD_RADIANCE_QUERY,0u,0u);
    payload.launchInfo=launch;payload.mediumBoundary=uvec4(0u);
    // CPU proof excludes masked coverage, backface rejection and inactive material slots.
    // Opaque is an intersection flag: closest-hit still evaluates glass and volume boundaries.
    uint flags=(gsOptical.storage.lanes[11u].w&64u)!=0u?gl_RayFlagsOpaqueEXT:gl_RayFlagsNoneEXT;
    traceRayEXT(sceneTlas,flags,0xff,0,0,0,origin,gsRayEpsilon(origin),direction,1.0e20,0);
    return payload;
}
void gsOpticalStoreAt(GsOpticalStorage destination,uint byteOffset,uint item,GpuScenePayload hit,uvec4 identity,uint stride) {
    GS_DETAIL_BEGIN(64u);
    uint base=byteOffset/16u+item*(stride==1u?13u:1u);
    destination.lanes[base]=floatBitsToUint(hit.worldPositionAndDistance);
    GS_DETAIL_NEXT(65u);
    destination.lanes[base+1u*stride]=floatBitsToUint(hit.previousWorldPosition);
    GS_DETAIL_NEXT(66u);
    destination.lanes[base+2u*stride]=uvec4(hit.motionRevision,0u,0u);
    GS_DETAIL_NEXT(67u);
    destination.lanes[base+3u*stride]=floatBitsToUint(hit.worldNormalAndRoughness);
    GS_DETAIL_NEXT(68u);
    destination.lanes[base+4u*stride]=floatBitsToUint(hit.baseColorAndOpacity);
    GS_DETAIL_NEXT(69u);
    destination.lanes[base+5u*stride]=floatBitsToUint(hit.emissiveAndMetallic);
    GS_DETAIL_NEXT(70u);
    destination.lanes[base+6u*stride]=floatBitsToUint(hit.transmissionIor);
    GS_DETAIL_NEXT(71u);
    destination.lanes[base+7u*stride]=hit.state;
    GS_DETAIL_NEXT(72u);
    destination.lanes[base+8u*stride]=hit.surfaceState;
    GS_DETAIL_NEXT(73u);
    destination.lanes[base+9u*stride]=floatBitsToUint(hit.compositeState);
    GS_DETAIL_NEXT(74u);
    destination.lanes[base+10u*stride]=hit.launchInfo;
    GS_DETAIL_NEXT(75u);
    destination.lanes[base+11u*stride]=hit.mediumBoundary;
    GS_DETAIL_NEXT(76u);
    destination.lanes[base+12u*stride]=identity;
    GS_DETAIL_END();
}
void gsOpticalStore(uint byteOffset,uint item,GpuScenePayload hit,uvec4 identity) {
    GS_DETAIL_BEGIN(160u);
    if((gsOptical.storage.lanes[11u].w&1u)!=0u) {
        bool transposed=(gsOptical.storage.lanes[11u].w&8u)!=0u;
        uint stride=transposed?gsOptical.storage.lanes[0u].z:1u;
        uint base=byteOffset/16u+item*(transposed?1u:6u);
        GS_DETAIL_NEXT(161u);
        gsOptical.storage.lanes[base]=floatBitsToUint(hit.worldPositionAndDistance);
        GS_DETAIL_NEXT(162u);
        gsOptical.storage.lanes[base+1u*stride]=floatBitsToUint(hit.worldNormalAndRoughness);
        GS_DETAIL_NEXT(163u);
        gsOptical.storage.lanes[base+2u*stride]=floatBitsToUint(hit.baseColorAndOpacity);
        GS_DETAIL_NEXT(164u);
        gsOptical.storage.lanes[base+3u*stride]=uvec4(floatBitsToUint(hit.transmissionIor.xy),hit.state.xy);
        GS_DETAIL_NEXT(165u);
        gsOptical.storage.lanes[base+4u*stride]=hit.mediumBoundary;
        GS_DETAIL_NEXT(166u);
        gsOptical.storage.lanes[base+5u*stride]=identity;
        GS_DETAIL_END();return;
    }
    GS_DETAIL_NEXT(167u);gsOpticalStoreAt(gsOptical.storage,byteOffset,item,hit,identity,1u);GS_DETAIL_END();
}
GpuScenePayload gsOpticalLoadAt(GsOpticalStorage source,uint byteOffset,uint item,uint stride) {
    GS_DETAIL_BEGIN(77u);
    uint base=byteOffset/16u+item*(stride==1u?13u:1u);GpuScenePayload hit;
    GS_DETAIL_NEXT(77u);
    hit.worldPositionAndDistance=uintBitsToFloat(source.lanes[base]);
    GS_DETAIL_NEXT(78u);
    hit.previousWorldPosition=uintBitsToFloat(source.lanes[base+1u*stride]);
    GS_DETAIL_NEXT(79u);
    hit.motionRevision=source.lanes[base+2u*stride].xy;
    GS_DETAIL_NEXT(80u);
    hit.worldNormalAndRoughness=uintBitsToFloat(source.lanes[base+3u*stride]);
    GS_DETAIL_NEXT(81u);
    hit.baseColorAndOpacity=uintBitsToFloat(source.lanes[base+4u*stride]);
    GS_DETAIL_NEXT(82u);
    hit.emissiveAndMetallic=uintBitsToFloat(source.lanes[base+5u*stride]);
    GS_DETAIL_NEXT(83u);
    hit.transmissionIor=uintBitsToFloat(source.lanes[base+6u*stride]);
    GS_DETAIL_NEXT(84u);
    hit.state=source.lanes[base+7u*stride];
    GS_DETAIL_NEXT(85u);
    hit.surfaceState=source.lanes[base+8u*stride];
    GS_DETAIL_NEXT(86u);
    hit.compositeState=uintBitsToFloat(source.lanes[base+9u*stride]);
    GS_DETAIL_NEXT(87u);
    hit.launchInfo=source.lanes[base+10u*stride];
    GS_DETAIL_NEXT(88u);
    hit.mediumBoundary=source.lanes[base+11u*stride];GS_DETAIL_END();return hit;
}
vec3 gsOpticalScatteringLight(vec3 origin,vec3 direction,uint seed,uvec4 launch) {
    vec3 sigma=uintBitsToFloat(gsOptical.storage.lanes[5u]).xyz+uintBitsToFloat(gsOptical.storage.lanes[10u]).xyz;
    float g=uintBitsToFloat(gsOptical.storage.lanes[10u]).w;
    float maximum=uintBitsToFloat(gsOptical.storage.lanes[6u]).w;
    uint upper=gsFrame.words[GPU_SCENE_FRAME_LIGHT_SLOT_UPPER_BOUND_WORD];
    uint count=gsFrame.words[GPU_SCENE_FRAME_SCATTERING_LIGHT_SAMPLES_WORD];
    uint selected[8],small[8],candidates=0u;float total=0.0;vec3 result=vec3(0);
    payload.launchInfo=launch;
    vec3 sunDirection=-normalize(gsFrameVec3(GPU_SCENE_FRAME_SUN_DIRECTION_WORD));
    vec3 sun=gsFrameVec3(GPU_SCENE_FRAME_SUN_COLOR_WORD)*uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_SUN_INTENSITY_WORD]);
    if(max(sun.x,max(sun.y,sun.z))>0.0)result+=sun*gsScatteringPhase(dot(direction,sunDirection),g)
        *gsScatteringShadow(origin,sunDirection,maximum,sigma,maximum);
    if(count==0u) {
        for(uint slot=0u;slot<upper;++slot)if(gsScatteringLightWeight(slot,origin,direction,0.0)>0.0)
            result+=gsScatteringLight(slot,origin,direction,g,sigma,maximum);
    }else{
        for(uint slot=0u;slot<upper;++slot){float weight=gsScatteringLightWeight(slot,origin,direction,0.0);if(weight<=0.0)continue;
            if(candidates<8u)small[candidates]=slot;++candidates;total+=weight;
            for(uint k=0u;k<count;++k)if(gsTransportRandom(seed^slot*0x85ebca6bu^k*0xc2b2ae35u)<weight/total)selected[k]=slot;}
        bool exhaustive=candidates<=count;uint activeCount=exhaustive?candidates:count;
        for(uint k=0u;k<activeCount;++k){uint slot=exhaustive?small[k]:selected[k];
            float factor=exhaustive?1.0:total/(float(count)*gsScatteringLightWeight(slot,origin,direction,0.0));
            result+=gsScatteringLight(slot,origin,direction,g,sigma,maximum)*factor;}
    }
    return result;
}
// State scalar planes use the actual tile path count, including partial final tiles.
uvec4 gsOpticalStateRead(uint item,uint lane) {
    uint state=gsOptical.storage.lanes[2u].y/4u;
    if((gsOptical.storage.lanes[11u].w&16u)!=0u){
        uint paths=gsOptical.storage.lanes[0u].z,word=state+lane*4u*paths+item;uvec4 value;
        for(uint c=0u;c<4u;c++){uint at=word+c*paths;
#ifdef RTRENDERER_OPTICAL_PROFILE
        uint stage=96u+lane*4u+c;uint64_t t=gsDetailStart(payload.diagnosticAddress,stage);
#endif
        value[c]=gsOptical.storage.lanes[at/4u][at%4u];
#ifdef RTRENDERER_OPTICAL_PROFILE
        gsDetailEnd(payload.diagnosticAddress,payload.diagnosticRecord,stage,t);
#endif
        }return value;
    }
    uint lanes=(gsOptical.storage.lanes[11u].w&2u)!=0u?7u:16u;
    return gsOptical.storage.lanes[state/4u+item*lanes+lane];
}
void gsOpticalStateWrite(uint item,uint lane,uvec4 value) {
    uint state=gsOptical.storage.lanes[2u].y/4u;
    if((gsOptical.storage.lanes[11u].w&16u)!=0u){
        uint paths=gsOptical.storage.lanes[0u].z,word=state+lane*4u*paths+item;
        for(uint c=0u;c<4u;c++){uint at=word+c*paths;
#ifdef RTRENDERER_OPTICAL_PROFILE
        uint stage=124u+lane*4u+c;uint64_t t=gsDetailStart(payload.diagnosticAddress,stage);
#endif
        gsOptical.storage.lanes[at/4u][at%4u]=value[c];
#ifdef RTRENDERER_OPTICAL_PROFILE
        gsDetailEnd(payload.diagnosticAddress,payload.diagnosticRecord,stage,t);
#endif
        }return;
    }
    uint lanes=(gsOptical.storage.lanes[11u].w&2u)!=0u?7u:16u;
    gsOptical.storage.lanes[state/4u+item*lanes+lane]=value;
}
void main() {
    if(gl_LaunchIDEXT.x>=gsOptical.pixelCount)return;
    bool fusedInitialization=(gsOptical.storage.lanes[11u].w&128u)!=0u;
    // Only CUDA consumes this marker, after the complete Vulkan dispatch retires.
    if(gl_LaunchIDEXT.x==0u)gsOptical.storage.lanes[4u].y=gsOptical.initialize!=0ul?1u:0u;
    ivec2 extent=ivec2(gsFrame.words[GPU_SCENE_FRAME_EXTENT_WORD],gsFrame.words[GPU_SCENE_FRAME_EXTENT_WORD+1u]);
    uint pixelIndex=gsOptical.firstPixel+gl_LaunchIDEXT.x;ivec2 pixel=ivec2(pixelIndex%uint(extent.x),pixelIndex/uint(extent.x));
    uvec4 launch=uvec4(pixel,extent);uint samples=gsOptical.storage.lanes[0u].w;
    uint stateOffset=gsOptical.storage.lanes[2u].y,hitOffset=gsOptical.storage.lanes[2u].z;
    uint evaluationOffset=gsOptical.storage.lanes[2u].w,primaryOffset=gsOptical.storage.lanes[3u].x;
    bool firstPass=gsOptical.storage.lanes[8u].z==0u&&gsOptical.storage.lanes[8u].w==0u;
    bool directPrimary=gsOptical.storage.lanes[11u].y!=0u;
    uint primaryStride=(gsOptical.storage.lanes[11u].w&32u)!=0u?uint(extent.x)*uint(extent.y)*samples:1u;
    uint stateLanes=(gsOptical.storage.lanes[11u].w&2u)!=0u?7u:16u;
    vec2 jitter=vec2(uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_CURRENT_JITTER_WORD]),
        uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_CURRENT_JITTER_WORD+1u]));
    for(uint sampleIndex=0u;sampleIndex<samples;sampleIndex++) {
        uint item=gl_LaunchIDEXT.x*samples+sampleIndex,globalPath=pixelIndex*samples+sampleIndex;
        uint initialItem=gsOptical.primaryAddress!=0ul?item:globalPath;
#ifdef RTRENDERER_OPTICAL_PROFILE
        payload.diagnosticAddress=(item&8191u)==0u?gsU64(gsOptical.storage.lanes[12u].x,gsOptical.storage.lanes[12u].y):0ul;
        payload.diagnosticRecord=item/8192u;
#endif
        GS_DETAIL_BEGIN(0u);
        GpuScenePayload hit;vec3 origin,direction;
        if(gsOptical.initialize!=0ul) {
            GS_DETAIL_NEXT(1u);
            if(fusedInitialization) {
                // Deterministic state is constructed by its first CUDA consumer.
            } else if((gsOptical.storage.lanes[11u].w&4u)!=0u) {
                // The remaining five lanes are assigned below before any consumer reads them.
                gsOpticalStateWrite(item,4u,uvec4(0u));
                gsOpticalStateWrite(item,5u,uvec4(0u));
                for(uint lane=7u;lane<stateLanes;lane++)gsOpticalStateWrite(item,lane,uvec4(0u));
            } else for(uint lane=0u;lane<stateLanes;lane++)gsOpticalStateWrite(item,lane,uvec4(0u));
            GS_DETAIL_NEXT(2u);
            vec2 samplePosition=vec2(pixel)+gsSubpixelOffset(sampleIndex,samples);if(!gsExactProjectionActive())samplePosition+=jitter;
            GS_DETAIL_NEXT(3u);
            origin=gsFrameCameraPosition();direction=gsRayDirectionAtSample(samplePosition,extent);
            GS_DETAIL_NEXT(4u);
            gsOpticalStateWrite(item,0u,floatBitsToUint(vec4(origin,gsRayEpsilon(origin))));
            GS_DETAIL_NEXT(5u);
            gsOpticalStateWrite(item,1u,floatBitsToUint(vec4(direction,0)));
            GS_DETAIL_NEXT(6u);
            if(!fusedInitialization) {
            gsOpticalStateWrite(item,2u,uvec4(1u,0u,globalPath,1u));
            GS_DETAIL_NEXT(7u);
            gsOpticalStateWrite(item,3u,uvec4(floatBitsToUint(vec3(1)),gsOptical.storage.lanes[6u].w));
            GS_DETAIL_NEXT(8u);
            uint branches=gsOptical.storage.lanes[9u].y==0u?2u:1u;
            float normalizeWeight=1.0/float(gsOptical.storage.lanes[8u].y*branches);
            gsOpticalStateWrite(item,6u,floatBitsToUint(vec4(1,1,1,normalizeWeight)));
            }
            if(firstPass) {
                GS_DETAIL_NEXT(9u);
                hit=gsTraceOpticalRadiance(origin,direction,launch);
                GS_DETAIL_NEXT(10u);
                if(!directPrimary)gsOpticalStoreAt(gsOptical.storage,primaryOffset,initialItem,hit,uvec4(pixelIndex,sampleIndex,0,0),1u);
                GS_DETAIL_NEXT(11u);
                if(gsOptical.primaryAddress!=0ul)
                    gsOpticalStoreAt(GsOpticalStorage(gsOptical.primaryAddress),0u,globalPath,hit,uvec4(pixelIndex,sampleIndex,0,0),primaryStride);
                GS_DETAIL_NEXT(12u);
                gsOptical.storage.lanes[gsOptical.storage.lanes[3u].y/16u+globalPath]=uvec4(0u);
                gsOptical.storage.lanes[gsOptical.storage.lanes[3u].z/16u+globalPath]=uvec4(0u);
            } else {GS_DETAIL_NEXT(13u);hit=directPrimary?gsOpticalLoadAt(GsOpticalStorage(gsOptical.primaryAddress),0u,globalPath,primaryStride)
                :gsOpticalLoadAt(gsOptical.storage,primaryOffset,initialItem,1u);}
        } else {
            GS_DETAIL_NEXT(14u);
            if(gsOpticalStateRead(item,2u).x==0u){GS_DETAIL_END();continue;}
            GS_DETAIL_NEXT(15u);
            origin=uintBitsToFloat(gsOpticalStateRead(item,0u)).xyz;direction=uintBitsToFloat(gsOpticalStateRead(item,1u)).xyz;
            GS_DETAIL_NEXT(16u);
            if(gsOpticalStateRead(item,2u).x==2u){
                uint epoch=gsOpticalStateRead(item,2u).w;
                GS_DETAIL_NEXT(17u);
                vec3 light=gsOpticalScatteringLight(origin,direction,globalPath^epoch*0x9e3779b9u^gsOptical.storage.lanes[9u].x,launch);
                gsOptical.storage.lanes[evaluationOffset/16u+item]=uvec4(floatBitsToUint(light),epoch);GS_DETAIL_END();continue;
            }
            GS_DETAIL_NEXT(18u);
            hit=gsTraceOpticalRadiance(origin,direction,launch);
        }
        GS_DETAIL_NEXT(19u);
        uint epoch=gsOptical.initialize!=0ul?1u:gsOpticalStateRead(item,2u).w;
        gsOpticalStore(hitOffset,item,hit,uvec4(pixelIndex,sampleIndex,epoch,epoch));
        GS_DETAIL_NEXT(20u);
        vec3 value=vec3(0);
        // Admission proves every non-dielectric terminal is UNLIT. Reassert that
        // proof after the trace/cache boundary instead of retaining the generic
        // PBR lighting and secondary-query tree in this optical dispatch.
        if(hit.state.x==0u)value=hit.baseColorAndOpacity.rgb;
        else if((hit.mediumBoundary.w&2u)==0u&&hit.transmissionIor.x<1.0){
            GS_DETAIL_NEXT(21u);
            value=max(hit.baseColorAndOpacity.rgb,vec3(0.0))+max(hit.emissiveAndMetallic.rgb,vec3(0.0));
        }
        GS_DETAIL_NEXT(22u);
        gsOptical.storage.lanes[evaluationOffset/16u+item]=floatBitsToUint(vec4(value,0));
        GS_DETAIL_END();
    }
}
