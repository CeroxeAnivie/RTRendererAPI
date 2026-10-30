// Geometry and raw light connections only; density transmittance is evaluated in CUDA.
float gsHeterogeneousPhase(float cosine,float g) {
    float c=clamp(cosine,-1.0,1.0),edge=1.0-abs(g),d=edge*edge+2.0*abs(g)*(1.0-sign(g)*c);
    return ((1.0-g)*(1.0+g))/(4.0*GS_PI*d*sqrt(d));
}
float gsHeterogeneousLightWeight(uint slot,vec3 origin,vec3 direction,float distance) {
    uint base=slot*GPU_SCENE_LIGHT_RECORD_WORDS,flags=gsLights.words[base+GPU_SCENE_LIGHT_FLAGS_WORD];
    if((flags&GPU_SCENE_FLAG_ACTIVE)==0u)return 0.0;
    if(((flags>>1u)&7u)!=GPU_SCENE_LIGHT_DIRECTIONAL) {
        vec3 point=vec3(gsFiniteDoubleAsFloat(gsLights.words[base+GPU_SCENE_LIGHT_POSITION_X_WORD],gsLights.words[base+GPU_SCENE_LIGHT_POSITION_X_WORD+1u]),
            gsFiniteDoubleAsFloat(gsLights.words[base+GPU_SCENE_LIGHT_POSITION_Y_WORD],gsLights.words[base+GPU_SCENE_LIGHT_POSITION_Y_WORD+1u]),
            gsFiniteDoubleAsFloat(gsLights.words[base+GPU_SCENE_LIGHT_POSITION_Z_WORD],gsLights.words[base+GPU_SCENE_LIGHT_POSITION_Z_WORD+1u]));
        if(length(point-origin-direction*clamp(dot(point-origin,direction),0.0,distance))>=uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_RANGE_WORD]))return 0.0;
    }
    vec3 color=vec3(uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_COLOR_WORD]),uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_COLOR_WORD+1u]),
        uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_COLOR_WORD+2u]));
    return gsLuminance(color)*uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_INTENSITY_WORD]);
}
void gsHeterogeneousConnection(uint outLane,uint slot,bool sun,vec3 position,vec3 rayDirection,float g,float factor) {
    vec3 direction,color;float distance=1e20,intensity,attenuation=1.0;bool shadow;
    if(sun) {
        direction=-normalize(gsFrameVec3(GPU_SCENE_FRAME_SUN_DIRECTION_WORD));
        color=gsFrameVec3(GPU_SCENE_FRAME_SUN_COLOR_WORD);intensity=uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_SUN_INTENSITY_WORD]);shadow=true;
    }else {
        uint base=slot*GPU_SCENE_LIGHT_RECORD_WORDS,flags=gsLights.words[base+GPU_SCENE_LIGHT_FLAGS_WORD],type=(flags>>1u)&7u;
        color=vec3(uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_COLOR_WORD]),uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_COLOR_WORD+1u]),
            uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_COLOR_WORD+2u]));
        intensity=uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_INTENSITY_WORD]);shadow=(flags&GPU_SCENE_FLAG_CASTS_SHADOW)!=0u;
        if(type==GPU_SCENE_LIGHT_DIRECTIONAL)direction=-normalize(vec3(uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD]),
            uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD+1u]),uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD+2u])));
        else {
            vec3 point=vec3(gsFiniteDoubleAsFloat(gsLights.words[base+GPU_SCENE_LIGHT_POSITION_X_WORD],gsLights.words[base+GPU_SCENE_LIGHT_POSITION_X_WORD+1u]),
                gsFiniteDoubleAsFloat(gsLights.words[base+GPU_SCENE_LIGHT_POSITION_Y_WORD],gsLights.words[base+GPU_SCENE_LIGHT_POSITION_Y_WORD+1u]),
                gsFiniteDoubleAsFloat(gsLights.words[base+GPU_SCENE_LIGHT_POSITION_Z_WORD],gsLights.words[base+GPU_SCENE_LIGHT_POSITION_Z_WORD+1u]));
            vec3 delta=point-position;distance=length(delta);if(distance<=1e-8)return;direction=delta/distance;
            float range=uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_RANGE_WORD]),fade=max(1.0-distance/range,0.0);
            attenuation=fade*fade/max(distance*distance,1.0);
            if(type==GPU_SCENE_LIGHT_SPOT) {
                vec3 axis=normalize(vec3(uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD]),
                    uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD+1u]),uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD+2u])));
                float inner=uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_INNER_CONE_WORD]),outer=uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_OUTER_CONE_WORD]);
                float cone=dot(axis,-direction);attenuation*=inner==outer?step(outer,cone):smoothstep(outer,inner,cone);
            }
        }
    }
    if(attenuation<=0.0||intensity<=0.0)return;
    float range=max(distance-2.0*gsRayEpsilon(position),gsRayEpsilon(position));
    vec3 visibility=shadow?gsShadowTransmittance(position,direction,direction,range):vec3(1);
    vec3 source=color*(intensity*attenuation*factor*gsHeterogeneousPhase(dot(rayDirection,direction),g))*visibility;
    dispatch.storage.lanes[outLane]=floatBitsToUint(vec4(source,shadow?range:-1.0));
    dispatch.storage.lanes[outLane+1u]=floatBitsToUint(vec4(direction,0));
}

void gsHeterogeneousMultipleMain() {
    if(gl_LaunchIDEXT.x>=dispatch.pixelCount)return;
    uint pixelIndex=dispatch.firstPixel+gl_LaunchIDEXT.x;
    ivec2 extent=ivec2(gsFrame.words[GPU_SCENE_FRAME_EXTENT_WORD],gsFrame.words[GPU_SCENE_FRAME_EXTENT_WORD+1u]);
    ivec2 pixel=ivec2(pixelIndex%uint(extent.x),pixelIndex/uint(extent.x));
    uint samples=dispatch.storage.lanes[1u].z,steps=dispatch.storage.lanes[0u].w;
    uint stateOffset=dispatch.storage.lanes[10u].y/16u,connectionOffset=dispatch.storage.lanes[10u].z/16u;
    uint connections=dispatch.storage.lanes[10u].w,upper=dispatch.storage.lanes[11u].x;
    uint lightSamples=dispatch.storage.lanes[11u].y,first=dispatch.storage.lanes[11u].z;
    float maximum=uintBitsToFloat(dispatch.storage.lanes[3u]).w,g=uintBitsToFloat(dispatch.storage.lanes[4u]).w;
    vec2 jitter=vec2(uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_CURRENT_JITTER_WORD]),
        uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_CURRENT_JITTER_WORD+1u]));
    if(dispatch.mode==0ul) {
        // CUDA initializes each interval state. Only one camera ray per spatial sample
        // crosses this boundary; avoid clearing/recomputing it scatteringSteps times.
        for(uint sampleIndex=0u;sampleIndex<samples;++sampleIndex) {
            uint base=stateOffset+(gl_LaunchIDEXT.x*samples+sampleIndex)*steps*9u;
            vec2 location=vec2(pixel)+gsScatteringSampleOffset(sampleIndex,samples);
            if(!gsExactProjectionActive())location+=jitter;
            dispatch.storage.lanes[base]=floatBitsToUint(vec4(gsFrameCameraPosition(),maximum));
            dispatch.storage.lanes[base+1u]=floatBitsToUint(vec4(gsRayDirectionAtSample(location,extent),0));
        }
        return;
    }
    for(uint sampleIndex=0u;sampleIndex<samples;++sampleIndex)for(uint j=0u;j<steps;++j) {
        uint item=gl_LaunchIDEXT.x*samples+sampleIndex,secondary=item*steps+j;
        uint base=stateOffset+secondary*9u;
        uvec4 control=dispatch.storage.lanes[base+7u];if(control.x==0u)continue;
        vec4 origin=uintBitsToFloat(dispatch.storage.lanes[base]),direction=uintBitsToFloat(dispatch.storage.lanes[base+1u]);
        bool initial=dispatch.mode==32ul,query=true;vec3 position=origin.xyz;
        payload.launchInfo=uvec4(pixel,extent);
        if(!initial) {
            if(first==0u) {
                payload.worldPositionAndDistance=vec4(0,0,0,-1);payload.state=uvec4(0,GS_PAYLOAD_RADIANCE_QUERY,0,0);
                payload.baseColorAndOpacity=vec4(0,0,0,1);payload.emissiveAndMetallic=vec4(0);payload.mediumBoundary=uvec4(0);
                traceRayEXT(sceneTlas,gl_RayFlagsNoneEXT,255,0,0,0,origin.xyz,gsRayEpsilon(origin.xyz),direction.xyz,1e20,0);
                float distance=payload.state.x==0u?-1.0:payload.worldPositionAndDistance.w;
                vec3 terminal=payload.state.x==0u?gsFrameVec3(GPU_SCENE_FRAME_SKY_COLOR_WORD)
                    *max(uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_AMBIENT_INTENSITY_WORD]),.05)
                    *(.35+.65*clamp(direction.y*.5+.5,0.0,1.0))
                    :gsMultipleSurfaceRadiance(payload,origin.xyz,uintBitsToFloat(dispatch.storage.lanes[3u]).xyz,
                        uintBitsToFloat(dispatch.storage.lanes[3u]).w);
                dispatch.storage.lanes[base+5u]=floatBitsToUint(vec4(terminal,distance));
            }
            float distance=uintBitsToFloat(dispatch.storage.lanes[base+5u]).w;
            float end=distance<0.0?origin.w:min(max(distance,0.0),origin.w);
            query=direction.w<end;position+=direction.xyz*direction.w;
        }
        uint selected[8],small[8],candidates=0u;float total=0.0;
        uint seed=dispatch.storage.lanes[base+8u].x^control.z*0xc2b2ae35u;
        if(query&&lightSamples>0u)for(uint slot=0u;slot<upper;++slot) {
            float weight=gsHeterogeneousLightWeight(slot,position,direction.xyz,0.0);if(weight<=0.0)continue;
            if(candidates<8u)small[candidates]=slot;++candidates;total+=weight;
            for(uint k=0u;k<lightSamples;++k)
                if(gsTransportRandom(seed^slot*0x85ebca6bu^k*0xc2b2ae35u)<weight/total)selected[k]=slot;
        }
        for(uint k=0u;k<connections;++k) {
            uint outLane=connectionOffset+(secondary*connections+k)*2u;
            dispatch.storage.lanes[outLane]=uvec4(0u);dispatch.storage.lanes[outLane+1u]=uvec4(0u,0u,0u,control.z);
            if(!query)continue;uint ordinal=first+k;
            if(ordinal==0u)gsHeterogeneousConnection(outLane,0u,true,position,direction.xyz,g,1.0);
            else {
                uint choice=ordinal-1u,slot;float factor=1.0;
                if(lightSamples==0u) {
                    if(choice>=upper)continue;slot=choice;if(gsHeterogeneousLightWeight(slot,position,direction.xyz,0.0)<=0.0)continue;
                }else {
                    bool exhaustive=candidates<=lightSamples;uint count=exhaustive?candidates:lightSamples;
                    if(choice>=count)continue;slot=exhaustive?small[choice]:selected[choice];
                    if(!exhaustive)factor=total/(float(lightSamples)*gsHeterogeneousLightWeight(slot,position,direction.xyz,0.0));
                }
                gsHeterogeneousConnection(outLane,slot,false,position,direction.xyz,g,factor);
            }
            dispatch.storage.lanes[outLane+1u].w=control.z;
        }
    }
}

