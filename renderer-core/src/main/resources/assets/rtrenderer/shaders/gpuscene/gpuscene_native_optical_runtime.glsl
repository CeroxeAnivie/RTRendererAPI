// Optical queries reuse the renderer's exact trace, texture, sky and terminal surface functions.
#include "gpuscene_native_scattering_common.glsl"
layout(buffer_reference,std430,buffer_reference_align=16) buffer GsOpticalStorage {uvec4 lanes[];};
layout(push_constant) uniform GsOpticalDispatch {
    GsOpticalStorage storage;uint firstPixel;uint pixelCount;uint64_t initialize;uint64_t primaryAddress;
} gsOptical;
#include "gpuscene_photon_emission.glsl"
vec3 gsOpticalBeer(float distance,vec3 extinction,float limit) {
    return exp(-extinction*min(max(distance,0.0),limit));
}
vec3 gsOpticalPbrSurface(GpuScenePayload receiver,vec3 rayOrigin) {
    vec3 position=receiver.worldPositionAndDistance.xyz,normal=normalize(receiver.worldNormalAndRoughness.xyz);
    vec3 base=max(receiver.baseColorAndOpacity.rgb,vec3(0.0)),emissive=max(receiver.emissiveAndMetallic.rgb,vec3(0.0));
    if(gsLightmapModulated(receiver))return gsApplyDistanceFog(base,receiver.transmissionIor.z,receiver.transmissionIor.w);
    if(gsUnlit(receiver))return base+emissive;
    float roughness=clamp(receiver.worldNormalAndRoughness.w,.025,1.0),metallic=clamp(receiver.emissiveAndMetallic.a,0.0,1.0);
    vec3 view=normalize(rayOrigin-position),sigma=gsFrameVec3(GPU_SCENE_FRAME_MEDIUM_EXTINCTION_WORD)
        *uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_MEDIUM_DENSITY_WORD]);
    float limit=uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_MEDIUM_MAX_DISTANCE_WORD]);
    GsRadianceSplit radiance=gsRadianceSplit(emissive+base*gsFrameVec3(GPU_SCENE_FRAME_SKY_COLOR_WORD)
        *uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_AMBIENT_INTENSITY_WORD])*(1.0-metallic),vec3(0.0));
#ifdef RTRENDERER_CAUSTICS
    radiance.diffuse+=gsGatherCaustics(receiver,view);
#endif
    if(gsOptical.storage.lanes[4u].w!=0u)return gsRadiance(radiance);
    vec3 sunDirection=-normalize(gsFrameVec3(GPU_SCENE_FRAME_SUN_DIRECTION_WORD));
    vec3 sunRadiance=gsFrameVec3(GPU_SCENE_FRAME_SUN_COLOR_WORD)*uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_SUN_INTENSITY_WORD]);
    GsRadianceSplit sun=gsDirectContribution(position,normal,view,sunDirection,sunRadiance,1.0e20,true,base,roughness,metallic);
    sun.diffuse*=gsOpticalBeer(sun.diffuseHitDistance,sigma,limit);
    sun.specular*=gsOpticalBeer(sun.specularHitDistance,sigma,limit);
    gsAccumulateRadianceSplit(radiance,sun);
    for(uint slot=0u;slot<gsFrame.words[GPU_SCENE_FRAME_LIGHT_SLOT_UPPER_BOUND_WORD];++slot) {
        GsRadianceSplit light=gsSceneLight(slot,position,normal,view,base,roughness,metallic);
        light.diffuse*=gsOpticalBeer(light.diffuseHitDistance,sigma,limit);
        light.specular*=gsOpticalBeer(light.specularHitDistance,sigma,limit);
        gsAccumulateRadianceSplit(radiance,light);
    }
    return gsRadiance(radiance);
}
GpuScenePayload gsTraceOpticalRadiance(vec3 origin,vec3 direction,uvec4 launch,float minimum,uint queryKind) {
    payload.state=uvec4(0u,queryKind,0u,0u);
    payload.launchInfo=launch;payload.mediumBoundary=uvec4(0u);
    // CPU proof excludes masked coverage, backface rejection and inactive material slots.
    // Opaque is an intersection flag: closest-hit still evaluates glass and volume boundaries.
    uint flags=(gsOptical.storage.lanes[11u].w&64u)!=0u?gl_RayFlagsOpaqueEXT:gl_RayFlagsNoneEXT;
    traceRayEXT(sceneTlas,flags,0xff,0,0,0,origin,minimum,direction,1.0e20,0);
    return payload;
}
GpuScenePayload gsTraceOpticalRadiance(vec3 origin,vec3 direction,uvec4 launch,float minimum) {
    return gsTraceOpticalRadiance(origin,direction,launch,minimum,
            gsOptical.initialize>=2ul?GS_PAYLOAD_PHOTON_QUERY:GS_PAYLOAD_RADIANCE_QUERY);
}
GpuScenePayload gsTraceOpticalRadiance(vec3 origin,vec3 direction,uvec4 launch) {
    return gsTraceOpticalRadiance(origin,direction,launch,gsRayEpsilon(origin));
}
bool gsOpticalOverlayReceiver(GpuScenePayload overlay,vec3 direction,uvec4 launch){
    vec3 origin=overlay.worldPositionAndDistance.xyz+direction*1.0e-3;
    float minimum=gsRayEpsilon(origin);
    GpuScenePayload receiver=gsTraceOpticalRadiance(origin,direction,launch,minimum);
    // Invisible boundaries change transport on the real continuation below, but
    // are not the surface whose receiver mask decides overlay composition.
    while(receiver.state.x!=0u&&(receiver.mediumBoundary.w&2u)!=0u){
        minimum=uintBitsToFloat(floatBitsToUint(receiver.worldPositionAndDistance.w)+1u);
        receiver=gsTraceOpticalRadiance(origin,direction,launch,minimum);
    }
    return receiver.state.x!=0u&&(receiver.surfaceState.x&overlay.surfaceState.y)!=0u
        &&receiver.worldPositionAndDistance.w<=max(overlay.compositeState.y,2.0e-3);
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
#ifdef RTRENDERER_CAUSTICS
    result+=gsGatherVolumeCaustics(origin,-direction,g);
#endif
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
float gsOpticalNextFloat(float value,bool positive) {
    if(value==0.0)return uintBitsToFloat(positive?1u:0x80000001u);
    uint bits=floatBitsToUint(value);
    return uintBitsToFloat((value>0.0)==positive?bits+1u:bits-1u);
}
vec3 gsOpticalLightOrigin(vec3 point,vec3 normal,float bound) {
    vec3 offset=normal*max(1e-5,bound),result=point+offset;
    for(uint axis=0u;axis<3u;++axis)if(offset[axis]!=0.0)
        result[axis]=gsOpticalNextFloat(result[axis],offset[axis]>0.0);
    return result;
}
// Select light geometry only. CUDA owns phase, density attenuation and the copied medium stack.
void gsOpticalConnectionTrace(uint item,uint globalPath,vec3 position,uvec4 launch,uint epoch,bool initialize,uint hitOffset) {
    vec3 sourcePosition=position;
    for(;;) {
    position=sourcePosition;
    GS_DETAIL_BEGIN(168u);
    uint base=gsOptical.storage.lanes[4u].z/16u+item*22u;
    if(initialize) {
        uvec4 control=gsOptical.storage.lanes[base+5u];
        // PBRT SpawnRayTo: offset the source first, then aim at the finite light endpoint.
        // Moving it after computing direction creates a parallel ray that misses that endpoint.
        if(control.w!=0u) {
            vec4 normal=uintBitsToFloat(gsOptical.storage.lanes[base+16u]);
            position=gsOpticalLightOrigin(position,normal.xyz,normal.w);
        }
        for(;;) {
        uint ordinal=control.x;
#ifdef RTRENDERER_CAUSTICS
        GS_DETAIL_NEXT(169u);
        if(ordinal==0u&&control.w==0u) {
            uint selected=(gsOptical.storage.lanes[11u].w&2u)!=0u?GS_INVALID_SLOT:gsOpticalStateRead(item,7u).x;
            uint mediumSlot=selected==GS_INVALID_SLOT?GS_INVALID_SLOT:gsOptical.storage.lanes[24u+selected*4u].x;
            float g=uintBitsToFloat(selected==GS_INVALID_SLOT?gsOptical.storage.lanes[10u].w
                :gsOptical.storage.lanes[26u+selected*4u].w);
            vec3 caustic=gsGatherVolumeCaustics(position,-uintBitsToFloat(gsOpticalStateRead(item,1u)).xyz,g,mediumSlot);
            gsOptical.storage.lanes[base+4u]=floatBitsToUint(vec4(caustic,0));
        }
#endif
        GS_DETAIL_NEXT(170u);
        uint upper=gsFrame.words[GPU_SCENE_FRAME_LIGHT_SLOT_UPPER_BOUND_WORD];
        uint samples=gsFrame.words[GPU_SCENE_FRAME_SCATTERING_LIGHT_SAMPLES_WORD];
        uint selected[8],small[8],candidates=0u;float total=0.0;
        uint seed=globalPath^gsOpticalStateRead(item,5u).w*0x9e3779b9u^gsOptical.storage.lanes[9u].x
                ^gsOptical.storage.lanes[8u].z*0x967a889bu;
        if(samples>0u)for(uint slot=0u;slot<upper;++slot) {
            float weight=gsScatteringLightWeight(slot,position,vec3(0),0.0);if(weight<=0.0)continue;
            if(candidates<8u)small[candidates]=slot;++candidates;total+=weight;
            for(uint k=0u;k<samples;++k)if(gsTransportRandom(seed^slot*0x85ebca6bu^k*0xc2b2ae35u)<weight/total)selected[k]=slot;
        }
        bool exhaustive=candidates<=samples;uint count=samples==0u?upper:(exhaustive?candidates:samples);
        control.y=count+1u;control.z=0u;gsOptical.storage.lanes[base+5u]=control;
        GS_DETAIL_NEXT(171u);
        vec3 direction=vec3(0,0,1),color=vec3(0);float intensity=0.0,distance=1.0e20,attenuation=1.0;bool shadow=true;
        if(ordinal==0u) {
            color=gsFrameVec3(GPU_SCENE_FRAME_SUN_COLOR_WORD);intensity=uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_SUN_INTENSITY_WORD]);
            if(intensity>0.0)direction=-normalize(gsFrameVec3(GPU_SCENE_FRAME_SUN_DIRECTION_WORD));
        }else if(ordinal<=count) {
            uint choice=ordinal-1u,slot=samples==0u?choice:(exhaustive?small[choice]:selected[choice]);
            uint lightBase=slot*GPU_SCENE_LIGHT_RECORD_WORDS,flags=gsLights.words[lightBase+GPU_SCENE_LIGHT_FLAGS_WORD],type=(flags>>1u)&7u;
            float factor=samples==0u||exhaustive?1.0:total/(float(samples)*gsScatteringLightWeight(slot,position,vec3(0),0.0));
            color=vec3(uintBitsToFloat(gsLights.words[lightBase+GPU_SCENE_LIGHT_COLOR_WORD]),
                uintBitsToFloat(gsLights.words[lightBase+GPU_SCENE_LIGHT_COLOR_WORD+1u]),uintBitsToFloat(gsLights.words[lightBase+GPU_SCENE_LIGHT_COLOR_WORD+2u]));
            intensity=(flags&GPU_SCENE_FLAG_ACTIVE)==0u?0.0:uintBitsToFloat(gsLights.words[lightBase+GPU_SCENE_LIGHT_INTENSITY_WORD])*factor;
            shadow=(flags&GPU_SCENE_FLAG_CASTS_SHADOW)!=0u;
            if(intensity>0.0&&type==GPU_SCENE_LIGHT_DIRECTIONAL)direction=-normalize(vec3(uintBitsToFloat(gsLights.words[lightBase+GPU_SCENE_LIGHT_DIRECTION_WORD]),
                uintBitsToFloat(gsLights.words[lightBase+GPU_SCENE_LIGHT_DIRECTION_WORD+1u]),uintBitsToFloat(gsLights.words[lightBase+GPU_SCENE_LIGHT_DIRECTION_WORD+2u])));
            else if(intensity>0.0) {
                vec3 point=vec3(gsFiniteDoubleAsFloat(gsLights.words[lightBase+GPU_SCENE_LIGHT_POSITION_X_WORD],gsLights.words[lightBase+GPU_SCENE_LIGHT_POSITION_X_WORD+1u]),
                    gsFiniteDoubleAsFloat(gsLights.words[lightBase+GPU_SCENE_LIGHT_POSITION_Y_WORD],gsLights.words[lightBase+GPU_SCENE_LIGHT_POSITION_Y_WORD+1u]),
                    gsFiniteDoubleAsFloat(gsLights.words[lightBase+GPU_SCENE_LIGHT_POSITION_Z_WORD],gsLights.words[lightBase+GPU_SCENE_LIGHT_POSITION_Z_WORD+1u]));
                vec3 delta=point-position;distance=length(delta);direction=distance>1.0e-8?delta/distance:vec3(0,0,1);
                float range=uintBitsToFloat(gsLights.words[lightBase+GPU_SCENE_LIGHT_RANGE_WORD]),fade=range>0.0?max(1.0-distance/range,0.0):0.0;
                attenuation=distance>1.0e-8?fade*fade/max(distance*distance,1.0):0.0;
                if(type==GPU_SCENE_LIGHT_SPOT) {
                    vec3 axis=normalize(vec3(uintBitsToFloat(gsLights.words[lightBase+GPU_SCENE_LIGHT_DIRECTION_WORD]),
                        uintBitsToFloat(gsLights.words[lightBase+GPU_SCENE_LIGHT_DIRECTION_WORD+1u]),uintBitsToFloat(gsLights.words[lightBase+GPU_SCENE_LIGHT_DIRECTION_WORD+2u])));
                    float inner=uintBitsToFloat(gsLights.words[lightBase+GPU_SCENE_LIGHT_INNER_CONE_WORD]),outer=uintBitsToFloat(gsLights.words[lightBase+GPU_SCENE_LIGHT_OUTER_CONE_WORD]);
                    attenuation*=inner==outer?step(outer,dot(axis,-direction)):smoothstep(outer,inner,dot(axis,-direction));
                }
            }
        }
        GS_DETAIL_NEXT(172u);
        vec3 source=color*(intensity*attenuation);
        if(control.w!=0u) {
            vec3 normal=uintBitsToFloat(gsOptical.storage.lanes[base+16u]).xyz;
            vec3 view=uintBitsToFloat(gsOptical.storage.lanes[base+17u]).xyz;
            vec4 material=uintBitsToFloat(gsOptical.storage.lanes[base+18u]);
            if(dot(normal,direction)<=0.0)source=vec3(0);
            else {
                float metallic=uintBitsToFloat(gsOptical.storage.lanes[base+15u]).w;
                GsRadianceSplit brdf=gsEvaluateBrdf(normal,view,direction,material.xyz,material.w,metallic);
                source*=gsRadiance(brdf);
            }
        }
        // PBRT queues shadow work only for nonzero light/BSDF contribution.
        // Empty ordinals need no CUDA segment integration. Advance exactly the
        // epoch that the old CUDA consumer would have advanced, preserving all
        // later random identities. Leave the final ordinal for CUDA to finish
        // lighting/continuation once, including the all-empty-light case.
        if((gsOptical.storage.lanes[11u].w&512u)!=0u&&all(equal(source,vec3(0)))&&ordinal<count) {
            ++control.x;gsOptical.storage.lanes[base+5u]=control;
            uvec4 stateControl=gsOpticalStateRead(item,2u);++stateControl.w;
            gsOpticalStateWrite(item,2u,stateControl);epoch=stateControl.w;
            continue;
        }
        gsOptical.storage.lanes[base]=floatBitsToUint(vec4(position,distance));
        gsOptical.storage.lanes[base+1u]=floatBitsToUint(vec4(direction,0));
        gsOptical.storage.lanes[base+2u]=uvec4(floatBitsToUint(source),max(source.x,max(source.y,source.z))>0.0?(shadow?1u:2u):0u);
        gsOptical.storage.lanes[base+3u]=uvec4(floatBitsToUint(vec3(1)),gsOptical.storage.lanes[6u].w);
        break;
        }
    }
    GS_DETAIL_NEXT(173u);
    vec4 origin=uintBitsToFloat(gsOptical.storage.lanes[base]);
    vec4 ray=uintBitsToFloat(gsOptical.storage.lanes[base+1u]);
    vec3 direction=ray.xyz;
    // Continue on the same straight light ray. The next representable t
    // excludes the processed hit without a normal offset crossing a nearby
    // medium boundary or a scene-scale epsilon skipping a thin volume.
    float minimum=gsOptical.storage.lanes[base+5u].z==0u?0.0:uintBitsToFloat(floatBitsToUint(ray.w)+1u);
    GpuScenePayload hit;
    if(gsOptical.storage.lanes[base+2u].w==1u){
        hit=gsTraceOpticalRadiance(origin.xyz,direction,launch,minimum,GS_PAYLOAD_OPTICAL_CONNECTION_QUERY);
        GS_DETAIL_NEXT(174u);
        // Connections need full material hits for transparent attenuation, while
        // preserving the public shadow exclusions for overlays and non-blockers.
        while(hit.state.x!=0u&&hit.mediumBoundary.w==0u
                &&((hit.state.w&GPU_SCENE_FLAG_CASTS_SHADOW)==0u||gsSurfaceOverlay(hit))){
            minimum=uintBitsToFloat(floatBitsToUint(hit.worldPositionAndDistance.w)+1u);
            hit=gsTraceOpticalRadiance(origin.xyz,direction,launch,minimum,GS_PAYLOAD_OPTICAL_CONNECTION_QUERY);
        }
    }
    else {hit.worldPositionAndDistance=vec4(0,0,0,-1);hit.previousWorldPosition=vec4(0);hit.motionRevision=uvec2(0);
        hit.worldNormalAndRoughness=vec4(0,1,0,0);hit.baseColorAndOpacity=vec4(0);hit.emissiveAndMetallic=vec4(0);
        hit.transmissionIor=vec4(0);hit.state=uvec4(0);hit.surfaceState=uvec4(0);hit.compositeState=vec4(0);
        hit.launchInfo=launch;hit.mediumBoundary=uvec4(0);}
    // PBRT TraceTransmittance rejects an opaque blocker before medium work.
    // Here only a first segment can stay in GLSL: its connection stack still
    // equals the main path stack, so the next light needs no CUDA stack reset.
    // Continuing light segments retain their full CUDA boundary validation.
    if(initialize&&(gsOptical.storage.lanes[11u].w&1024u)!=0u
            &&gsOptical.storage.lanes[base+2u].w==1u&&hit.state.x!=0u&&hit.mediumBoundary.w==0u
            &&hit.worldPositionAndDistance.w>=0.0&&hit.worldPositionAndDistance.w<origin.w
            &&hit.baseColorAndOpacity.w>=1.0&&hit.transmissionIor.x<=0.0) {
        // A strictly zero connection cannot change lighting or consume RNG.
        // Preserve the ordinal/epoch increments of the original CUDA consumer.
        gsOptical.storage.lanes[base+2u]=uvec4(0u);
        uvec4 control=gsOptical.storage.lanes[base+5u];
        if(control.x+1u<control.y) {
            ++control.x;gsOptical.storage.lanes[base+5u]=control;
            uvec4 stateControl=gsOpticalStateRead(item,2u);++stateControl.w;
            gsOpticalStateWrite(item,2u,stateControl);epoch=stateControl.w;
            GS_DETAIL_END();continue;
        }
        // Keep the final CUDA consumer for phase/surface accumulation and
        // continuation. Its source mode skips only the proven-zero segment.
    }
    GS_DETAIL_END();
    gsOpticalStore(hitOffset,item,hit,uvec4(globalPath/gsOptical.storage.lanes[0u].w,globalPath%gsOptical.storage.lanes[0u].w,epoch,epoch));
    return;
    }
}
// Light paths use the camera pipeline's geometric hit ABI, with native medium state.
void gsOpticalPhotonTrace() {
    uint item=gl_LaunchIDEXT.x+gl_LaunchIDEXT.y*gl_LaunchSizeEXT.x;
    if(item>=gsOptical.pixelCount)return;
    GsOpticalStorage photons=GsOpticalStorage(gsOptical.primaryAddress);
    uint state=photons.lanes[1u].x/16u+item*17u;
#ifdef RTRENDERER_OPTICAL_PROFILE
    payload.diagnosticAddress=0ul;payload.diagnosticRecord=0u;
#endif
    if(gsOptical.initialize==2ul) {
        for(uint lane=0u;lane<17u;++lane)photons.lanes[state+lane]=uvec4(0u);
        vec3 origin,direction,flux;float range;uint row;
        gsEmitPhoton(item,gsOptical.pixelCount,origin,direction,flux,range,row);
        float maximum=uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_MEDIUM_MAX_DISTANCE_WORD]);
        if(range>0.0)maximum=min(maximum,range);
        photons.lanes[state]=floatBitsToUint(vec4(origin,0));
        photons.lanes[state+1u]=floatBitsToUint(vec4(direction,0));
        photons.lanes[state+2u]=uvec4(max(flux.x,max(flux.y,flux.z))>0.0?1u:0u,0u,item,1u);
        photons.lanes[state+3u]=floatBitsToUint(vec4(flux,maximum));
        photons.lanes[state+6u]=floatBitsToUint(vec4(1));
        photons.lanes[state+7u].x=GS_INVALID_SLOT;
        uint count=gsCaustics.words[row+1u];
        photons.lanes[state+15u].y=count;
        for(uint j=0u;j<count;++j) {
            uint slot=gsCaustics.words[row+2u+j],low=0u,high=gsOptical.storage.lanes[1u].w;
            while(low<high){uint mid=low+(high-low)/2u;
                if(gsOptical.storage.lanes[24u+mid*4u].x<slot)low=mid+1u;else high=mid;}
            uint word=29u+j;
            photons.lanes[state+word/4u][word%4u]=low;
        }
        photons.lanes[state+16u].w=floatBitsToUint(range);
    }
    if(photons.lanes[state+2u].x==0u)return;
    vec3 origin=uintBitsToFloat(photons.lanes[state]).xyz;
    vec3 direction=uintBitsToFloat(photons.lanes[state+1u]).xyz;
    uvec4 launch=uvec4(gl_LaunchIDEXT.xy,gl_LaunchSizeEXT.xy);
    GpuScenePayload hit=gsTraceOpticalRadiance(origin,direction,launch,0.0);
    while(hit.state.x!=0u&&hit.mediumBoundary.w==0u&&gsSurfaceOverlay(hit)){
        float minimum=uintBitsToFloat(floatBitsToUint(hit.worldPositionAndDistance.w)+1u);
        hit=gsTraceOpticalRadiance(origin,direction,launch,minimum);
    }
    uint epoch=photons.lanes[state+2u].w;
    gsOpticalStoreAt(photons,photons.lanes[1u].y,item,hit,uvec4(item,0u,epoch,epoch),1u);
}
void main() {
    if(gsOptical.initialize>=2ul){gsOpticalPhotonTrace();return;}
    if(gl_LaunchIDEXT.x>=gsOptical.pixelCount)return;
    bool fusedInitialization=(gsOptical.storage.lanes[11u].w&128u)!=0u;
    // Only CUDA consumes this marker, after the complete Vulkan dispatch retires.
    if(gl_LaunchIDEXT.x==0u)gsOptical.storage.lanes[4u].y=gsOptical.initialize!=0ul?1u:0u;
    ivec2 extent=ivec2(gsFrame.words[GPU_SCENE_FRAME_EXTENT_WORD],gsFrame.words[GPU_SCENE_FRAME_EXTENT_WORD+1u]);
    uint samples=gsOptical.storage.lanes[0u].w;
    bool queuedPaths=gsOptical.initialize==0ul&&(gsOptical.storage.lanes[11u].w&256u)!=0u;
    uint stateOffset=gsOptical.storage.lanes[2u].y,hitOffset=gsOptical.storage.lanes[2u].z;
    uint evaluationOffset=gsOptical.storage.lanes[2u].w,primaryOffset=gsOptical.storage.lanes[3u].x;
    bool firstPass=gsOptical.storage.lanes[8u].z==0u&&gsOptical.storage.lanes[8u].w==0u;
    bool directPrimary=gsOptical.storage.lanes[11u].y!=0u;
    uint primaryStride=(gsOptical.storage.lanes[11u].w&32u)!=0u?uint(extent.x)*uint(extent.y)*samples:1u;
    uint stateLanes=(gsOptical.storage.lanes[11u].w&2u)!=0u?7u:16u;
    vec2 jitter=vec2(uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_CURRENT_JITTER_WORD]),
        uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_CURRENT_JITTER_WORD+1u]));
    for(uint dispatchSample=0u;dispatchSample<samples;dispatchSample++) {
        uint item=gl_LaunchIDEXT.x*samples+dispatchSample;
        if(queuedPaths){
            // CUDA publishes only surviving original IDs. Inactive launch slots
            // leave before reading strided path state or constructing payloads.
            if(item>=gsOptical.storage.lanes[13u].y)continue;
            uint queue=gsOptical.storage.lanes[13u].w/16u;
            item=gsOptical.storage.lanes[queue+item/4u][item%4u];
        }
        uint pixelIndex=gsOptical.firstPixel+item/samples,sampleIndex=item%samples;
        ivec2 pixel=ivec2(pixelIndex%uint(extent.x),pixelIndex/uint(extent.x));
        uvec4 launch=uvec4(pixel,extent);uint globalPath=pixelIndex*samples+sampleIndex;
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
            uint mode=gsOpticalStateRead(item,2u).x;
            if((mode==2u||mode==3u)&&gsOptical.storage.lanes[4u].w!=0u){
                gsOpticalConnectionTrace(item,globalPath,origin,launch,gsOpticalStateRead(item,2u).w,mode==2u,hitOffset);
                GS_DETAIL_END();continue;
            }
            if(mode==2u){
                uint epoch=gsOpticalStateRead(item,2u).w;
                GS_DETAIL_NEXT(17u);
                vec3 light=gsOpticalScatteringLight(origin,direction,globalPath^epoch*0x9e3779b9u^gsOptical.storage.lanes[9u].x,launch);
                gsOptical.storage.lanes[evaluationOffset/16u+item]=uvec4(floatBitsToUint(light),epoch);GS_DETAIL_END();continue;
            }
            GS_DETAIL_NEXT(18u);
            // Surface vertices were already offset along the geometric normal;
            // volume vertices have no self surface. A scene-scale tMin would skip
            // an arbitrarily close valid boundary and desynchronize its medium stack.
            hit=gsTraceOpticalRadiance(origin,direction,launch,0.0);
        }
        GS_DETAIL_NEXT(19u);
        uint epoch=gsOptical.initialize!=0ul?1u:gsOpticalStateRead(item,2u).w;
        gsOpticalStore(hitOffset,item,hit,uvec4(pixelIndex,sampleIndex,epoch,epoch));
        GS_DETAIL_NEXT(20u);
        vec3 value=vec3(0);
        // Keep UNLIT terminals cheap; PBR direct light also attenuates the shadow
        // segment through the admitted global participating medium.
        if(hit.state.x==0u)value=hit.baseColorAndOpacity.rgb;
        else if((hit.mediumBoundary.w&2u)==0u&&(hit.transmissionIor.x<1.0||gsSurfaceOverlay(hit))){
            GS_DETAIL_NEXT(21u);
            value=gsOpticalPbrSurface(hit,origin);
        }
        GS_DETAIL_NEXT(22u);
        bool surface=hit.state.x!=0u&&(hit.mediumBoundary.w&2u)==0u;
        bool pbr=surface&&(hit.transmissionIor.x<1.0||gsSurfaceOverlay(hit))&&!gsUnlit(hit)&&!gsLightmapModulated(hit);
        float surfaceKind=surface?(pbr?1.0:2.0):0.0;
        if(surface&&gsSurfaceOverlay(hit)){
            if(!gsOpticalOverlayReceiver(hit,direction,launch))surfaceKind=5.0;
            else{
                uint composition=(hit.state.z>>GPU_SCENE_OVERLAY_COMPOSITION_MODE_SHIFT)&GPU_SCENE_OVERLAY_COMPOSITION_MODE_MASK;
                bool multiply=composition==GPU_SCENE_OVERLAY_COMPOSITION_MULTIPLY;
                surfaceKind=pbr?(multiply?4.0:3.0):(multiply?7.0:6.0);
            }
        }
        if(surface&&gsOptical.storage.lanes[4u].w!=0u) {
            uint connection=gsOptical.storage.lanes[4u].z/16u+item*22u;
            vec2 reflectionSample=gsReflectionSample(sampleIndex,samples,launch);
            gsOptical.storage.lanes[connection+15u]=floatBitsToUint(vec4(hit.worldPositionAndDistance.xyz,
                clamp(hit.emissiveAndMetallic.a,0.0,1.0)));
            gsOptical.storage.lanes[connection+16u]=floatBitsToUint(vec4(normalize(hit.worldNormalAndRoughness.xyz),reflectionSample.x));
            gsOptical.storage.lanes[connection+17u]=floatBitsToUint(vec4(normalize(origin-hit.worldPositionAndDistance.xyz),reflectionSample.y));
            gsOptical.storage.lanes[connection+18u]=floatBitsToUint(vec4(max(hit.baseColorAndOpacity.rgb,vec3(0)),
                clamp(hit.worldNormalAndRoughness.w,.025,1.0)));
            if(gsSurfaceOverlay(hit))gsOptical.storage.lanes[connection+20u]=floatBitsToUint(vec4(gsSkyRadiance(direction),0));
        }
        gsOptical.storage.lanes[evaluationOffset/16u+item]=floatBitsToUint(vec4(value,surfaceKind));
        GS_DETAIL_END();
    }
}
