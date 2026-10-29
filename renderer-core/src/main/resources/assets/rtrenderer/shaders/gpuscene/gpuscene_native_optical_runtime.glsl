// Optical queries reuse the renderer's exact trace, texture, sky and terminal surface functions.
#include "gpuscene_native_scattering_common.glsl"
layout(buffer_reference,std430,buffer_reference_align=16) buffer GsOpticalStorage {uvec4 lanes[];};
layout(push_constant) uniform GsOpticalDispatch {
    GsOpticalStorage storage;uint firstPixel;uint pixelCount;uint64_t initialize;uint64_t primaryAddress;
} gsOptical;
void gsOpticalStoreAt(GsOpticalStorage destination,uint byteOffset,uint item,GpuScenePayload hit,uvec4 identity) {
    uint base=byteOffset/16u+item*13u;
    destination.lanes[base]=floatBitsToUint(hit.worldPositionAndDistance);
    destination.lanes[base+1u]=floatBitsToUint(hit.previousWorldPosition);
    destination.lanes[base+2u]=uvec4(hit.motionRevision,0u,0u);
    destination.lanes[base+3u]=floatBitsToUint(hit.worldNormalAndRoughness);
    destination.lanes[base+4u]=floatBitsToUint(hit.baseColorAndOpacity);
    destination.lanes[base+5u]=floatBitsToUint(hit.emissiveAndMetallic);
    destination.lanes[base+6u]=floatBitsToUint(hit.transmissionIor);
    destination.lanes[base+7u]=hit.state;destination.lanes[base+8u]=hit.surfaceState;
    destination.lanes[base+9u]=floatBitsToUint(hit.compositeState);
    destination.lanes[base+10u]=hit.launchInfo;destination.lanes[base+11u]=hit.mediumBoundary;
    destination.lanes[base+12u]=identity;
}
void gsOpticalStore(uint byteOffset,uint item,GpuScenePayload hit,uvec4 identity) {
    gsOpticalStoreAt(gsOptical.storage,byteOffset,item,hit,identity);
}
GpuScenePayload gsOpticalLoadAt(GsOpticalStorage source,uint byteOffset,uint item) {
    uint base=byteOffset/16u+item*13u;GpuScenePayload hit;
    hit.worldPositionAndDistance=uintBitsToFloat(source.lanes[base]);
    hit.previousWorldPosition=uintBitsToFloat(source.lanes[base+1u]);
    hit.motionRevision=source.lanes[base+2u].xy;
    hit.worldNormalAndRoughness=uintBitsToFloat(source.lanes[base+3u]);
    hit.baseColorAndOpacity=uintBitsToFloat(source.lanes[base+4u]);
    hit.emissiveAndMetallic=uintBitsToFloat(source.lanes[base+5u]);
    hit.transmissionIor=uintBitsToFloat(source.lanes[base+6u]);
    hit.state=source.lanes[base+7u];hit.surfaceState=source.lanes[base+8u];
    hit.compositeState=uintBitsToFloat(source.lanes[base+9u]);
    hit.launchInfo=source.lanes[base+10u];hit.mediumBoundary=source.lanes[base+11u];return hit;
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
void main() {
    if(gl_LaunchIDEXT.x>=gsOptical.pixelCount)return;
    ivec2 extent=ivec2(gsFrame.words[GPU_SCENE_FRAME_EXTENT_WORD],gsFrame.words[GPU_SCENE_FRAME_EXTENT_WORD+1u]);
    uint pixelIndex=gsOptical.firstPixel+gl_LaunchIDEXT.x;ivec2 pixel=ivec2(pixelIndex%uint(extent.x),pixelIndex/uint(extent.x));
    uvec4 launch=uvec4(pixel,extent);uint samples=gsOptical.storage.lanes[0u].w;
    uint stateOffset=gsOptical.storage.lanes[2u].y,hitOffset=gsOptical.storage.lanes[2u].z;
    uint evaluationOffset=gsOptical.storage.lanes[2u].w,primaryOffset=gsOptical.storage.lanes[3u].x;
    bool firstPass=gsOptical.storage.lanes[8u].z==0u&&gsOptical.storage.lanes[8u].w==0u;
    bool directPrimary=gsOptical.storage.lanes[11u].y!=0u;
    vec2 jitter=vec2(uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_CURRENT_JITTER_WORD]),
        uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_CURRENT_JITTER_WORD+1u]));
    for(uint sampleIndex=0u;sampleIndex<samples;sampleIndex++) {
        uint item=gl_LaunchIDEXT.x*samples+sampleIndex,globalPath=pixelIndex*samples+sampleIndex;
        uint initialItem=gsOptical.primaryAddress!=0ul?item:globalPath;
        uint base=stateOffset/16u+item*16u;GpuScenePayload hit;vec3 origin,direction;
        if(gsOptical.initialize!=0ul) {
            for(uint lane=0u;lane<16u;lane++)gsOptical.storage.lanes[base+lane]=uvec4(0u);
            vec2 samplePosition=vec2(pixel)+gsSubpixelOffset(sampleIndex,samples);if(!gsExactProjectionActive())samplePosition+=jitter;
            origin=gsFrameCameraPosition();direction=gsRayDirectionAtSample(samplePosition,extent);
            gsOptical.storage.lanes[base]=floatBitsToUint(vec4(origin,gsRayEpsilon(origin)));
            gsOptical.storage.lanes[base+1u]=floatBitsToUint(vec4(direction,0));
            gsOptical.storage.lanes[base+2u]=uvec4(1u,0u,globalPath,1u);
            gsOptical.storage.lanes[base+3u]=uvec4(floatBitsToUint(vec3(1)),gsOptical.storage.lanes[6u].w);
            uint branches=gsOptical.storage.lanes[9u].y==0u?2u:1u;
            float normalizeWeight=1.0/float(gsOptical.storage.lanes[8u].y*branches);
            gsOptical.storage.lanes[base+6u]=floatBitsToUint(vec4(1,1,1,normalizeWeight));
            if(firstPass) {
                hit=gsTraceRadiance(origin,direction,launch);
                if(!directPrimary)gsOpticalStore(primaryOffset,initialItem,hit,uvec4(pixelIndex,sampleIndex,0,0));
                if(gsOptical.primaryAddress!=0ul)
                    gsOpticalStoreAt(GsOpticalStorage(gsOptical.primaryAddress),0u,globalPath,hit,uvec4(pixelIndex,sampleIndex,0,0));
                gsOptical.storage.lanes[gsOptical.storage.lanes[3u].y/16u+globalPath]=uvec4(0u);
                gsOptical.storage.lanes[gsOptical.storage.lanes[3u].z/16u+globalPath]=uvec4(0u);
            } else hit=directPrimary?gsOpticalLoadAt(GsOpticalStorage(gsOptical.primaryAddress),0u,globalPath)
                :gsOpticalLoadAt(gsOptical.storage,primaryOffset,initialItem);
        } else {
            if(gsOptical.storage.lanes[base+2u].x==0u)continue;
            origin=uintBitsToFloat(gsOptical.storage.lanes[base]).xyz;direction=uintBitsToFloat(gsOptical.storage.lanes[base+1u]).xyz;
            if(gsOptical.storage.lanes[base+2u].x==2u){
                uint epoch=gsOptical.storage.lanes[base+2u].w;
                vec3 light=gsOpticalScatteringLight(origin,direction,globalPath^epoch*0x9e3779b9u^gsOptical.storage.lanes[9u].x,launch);
                gsOptical.storage.lanes[evaluationOffset/16u+item]=uvec4(floatBitsToUint(light),epoch);continue;
            }
            hit=gsTraceRadiance(origin,direction,launch);
        }
        uint epoch=gsOptical.storage.lanes[base+2u].w;
        gsOpticalStore(hitOffset,item,hit,uvec4(pixelIndex,sampleIndex,epoch,epoch));
        vec3 value=vec3(0);
        if(hit.state.x==0u)value=gsEvaluateTerminalRadiance(hit,origin,direction);
        else if((hit.mediumBoundary.w&2u)==0u&&hit.transmissionIor.x<1.0)value=gsOpticalSurface(hit,origin);
        gsOptical.storage.lanes[evaluationOffset/16u+item]=floatBitsToUint(vec4(value,0));
    }
}
