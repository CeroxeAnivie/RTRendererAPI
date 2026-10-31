#ifndef GS_NATIVE_SCATTERING_COMMON_GLSL
#define GS_NATIVE_SCATTERING_COMMON_GLSL
#ifndef GS_CAMERA_SCATTERING_STAGE_COUNT
#define GS_CAMERA_SCATTERING_STAGE_COUNT 15u
#endif
// Light geometry and surface visibility use the retained production scene. This stage
// does not integrate camera radiance; pinned LuxCore CUDA consumes the source samples.
vec3 gsScatteringLightPosition(uint base) {
    return vec3(uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_FLOAT_POSITION_WORD]),
        uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_FLOAT_POSITION_WORD+1u]),
        uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_FLOAT_POSITION_WORD+2u]));
}
float gsScatteringPhase(float cosine,float g) {
    float c=clamp(cosine,-1.0,1.0),edge=g>=0.0?1.0-g:1.0+g;
    float d=edge*edge+(g>=0.0?2.0*g*(1.0-c):-2.0*g*(1.0+c));
    return ((1.0-g)*(1.0+g))/(4.0*GS_PI*d*sqrt(d));
}
vec3 gsScatteringShadow(vec3 position,vec3 direction,float distance,vec3 sigma,float integrationLimit) {
    vec3 surface=gsShadowTransmittance(position,direction,direction,distance);
    // The strict admission proves a single uniform medium and opaque terminal surfaces.
    // A homogeneous Beer factor is the only medium-shadow compatibility operation here.
    return surface*exp(-sigma*min(distance,integrationLimit));
}
#ifdef RTRENDERER_CAMERA_SCATTERING_PROFILE
void gsScatteringProfileAdd(uint record,uint stage,uint64_t started){
    if(started==0ul)return;uint word=((record/65536u)*GS_CAMERA_SCATTERING_STAGE_COUNT+stage)*2u;uint64_t ended=clockRealtimeEXT();
    GsScatteringDiagnostic target=GsScatteringDiagnostic(dispatch.reserved);
    target.values[word]+=ended-started;target.values[word+1u]+=1ul;
}
void gsScatteringProfileRecord(bool profileEnabled,uint record,uint stage,uint64_t ticks,uint64_t calls){
    if(!profileEnabled||dispatch.reserved==0ul||ticks==0ul)return;
    uint word=((record/65536u)*GS_CAMERA_SCATTERING_STAGE_COUNT+stage)*2u;
    GsScatteringDiagnostic target=GsScatteringDiagnostic(dispatch.reserved);
    target.values[word]+=ticks;target.values[word+1u]+=calls;
}
void gsScatteringProfileRecord(bool profileEnabled,uint record,uint stage,uint64_t ticks){
    gsScatteringProfileRecord(profileEnabled,record,stage,ticks,1ul);
}
uint64_t gsScatteringProfileStart(bool enabled){if(!enabled)return 0ul;return clockRealtimeEXT();}
vec3 gsScatteringShadowProfile(vec3 position,vec3 direction,float distance,vec3 sigma,float integrationLimit,uint record){
    bool profileActive=dispatch.reserved!=0ul&&(record%65536u)==0u;
    uint64_t probeTicks,fallbackTicks,anyHitTicks,closestHitTicks;
    uint anyHitCalls,closestHitCalls;
    vec3 surface=gsShadowTransmittanceDetailed(position,direction,direction,distance,profileActive,
        probeTicks,fallbackTicks,anyHitTicks,anyHitCalls,closestHitTicks,closestHitCalls);
    gsScatteringProfileRecord(profileActive,record,5u,probeTicks);
    gsScatteringProfileRecord(profileActive,record,12u,fallbackTicks);
    gsScatteringProfileRecord(profileActive,record,13u,anyHitTicks,uint64_t(anyHitCalls));
    gsScatteringProfileRecord(profileActive,record,14u,closestHitTicks,uint64_t(closestHitCalls));
    uint64_t started=gsScatteringProfileStart(profileActive);
    vec3 attenuation=exp(-sigma*min(distance,integrationLimit));
    gsScatteringProfileAdd(record,6u,started);
    return surface*attenuation;
}
vec3 gsScatteringLightProfile(uint slot,vec3 position,vec3 rayDirection,float g,vec3 sigma,float integrationLimit,uint record,bool profileActive){
    uint64_t started=gsScatteringProfileStart(profileActive);
    uint base=slot*GPU_SCENE_LIGHT_RECORD_WORDS,flags=gsLights.words[base+GPU_SCENE_LIGHT_FLAGS_WORD],type=(flags>>1u)&7u;
    vec3 color=vec3(uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_COLOR_WORD]),
        uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_COLOR_WORD+1u]),uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_COLOR_WORD+2u]));
    float intensity=uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_INTENSITY_WORD]),distance=1e20,attenuation=1.0;
    vec3 direction;
    if(type==GPU_SCENE_LIGHT_DIRECTIONAL) {
        direction=-normalize(vec3(uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD]),
            uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD+1u]),uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD+2u])));
    }else {
        vec3 point=gsScatteringLightPosition(base);
        vec3 delta=point-position;distance=length(delta);if(distance<=1e-8){gsScatteringProfileAdd(record,7u,started);return vec3(0);}
        direction=delta/distance;float range=uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_RANGE_WORD]),fade=max(1.0-distance/range,0.0);
        attenuation=fade*fade/max(distance*distance,1.0);
        if(type==GPU_SCENE_LIGHT_SPOT) {
            vec3 axis=normalize(vec3(uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD]),
                uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD+1u]),uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD+2u])));
            float inner=uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_INNER_CONE_WORD]),outer=uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_OUTER_CONE_WORD]);
            float cone=dot(axis,-direction);attenuation*=inner==outer?step(outer,cone):smoothstep(outer,inner,cone);
        }
    }
    if(attenuation<=0.0||intensity<=0.0){gsScatteringProfileAdd(record,7u,started);return vec3(0);}
    gsScatteringProfileAdd(record,7u,started);
    vec3 visibility=vec3(1);
    if((flags&GPU_SCENE_FLAG_CASTS_SHADOW)!=0u){
        float shadowDistance=max(distance-2.0*gsRayEpsilon(position),gsRayEpsilon(position));
        uint64_t probeTicks,fallbackTicks,anyHitTicks,closestHitTicks;
        uint anyHitCalls,closestHitCalls;
        visibility=gsShadowTransmittanceDetailed(position,direction,direction,shadowDistance,profileActive,
            probeTicks,fallbackTicks,anyHitTicks,anyHitCalls,closestHitTicks,closestHitCalls);
        gsScatteringProfileRecord(profileActive,record,5u,probeTicks);
        gsScatteringProfileRecord(profileActive,record,12u,fallbackTicks);
        gsScatteringProfileRecord(profileActive,record,13u,anyHitTicks,uint64_t(anyHitCalls));
        gsScatteringProfileRecord(profileActive,record,14u,closestHitTicks,uint64_t(closestHitCalls));
        started=gsScatteringProfileStart(profileActive);
        visibility*=exp(-sigma*min(shadowDistance,integrationLimit));
        gsScatteringProfileAdd(record,6u,started);
    }
    started=gsScatteringProfileStart(profileActive);
    vec3 phase=vec3(gsScatteringPhase(dot(rayDirection,direction),g));
    vec3 result=color*intensity*attenuation*visibility*phase;
    gsScatteringProfileAdd(record,8u,started);
    return result;
}
#endif
float gsScatteringLightWeight(uint slot,vec3 origin,vec3 direction,float distance) {
    uint base=slot*GPU_SCENE_LIGHT_RECORD_WORDS,flags=gsLights.words[base+GPU_SCENE_LIGHT_FLAGS_WORD];
    if((flags&GPU_SCENE_FLAG_ACTIVE)==0u)return 0.0;
    if(((flags>>1u)&7u)!=GPU_SCENE_LIGHT_DIRECTIONAL) {
        vec3 point=vec3(gsFiniteDoubleAsFloat(gsLights.words[base+GPU_SCENE_LIGHT_POSITION_X_WORD],gsLights.words[base+GPU_SCENE_LIGHT_POSITION_X_WORD+1u]),
            gsFiniteDoubleAsFloat(gsLights.words[base+GPU_SCENE_LIGHT_POSITION_Y_WORD],gsLights.words[base+GPU_SCENE_LIGHT_POSITION_Y_WORD+1u]),
            gsFiniteDoubleAsFloat(gsLights.words[base+GPU_SCENE_LIGHT_POSITION_Z_WORD],gsLights.words[base+GPU_SCENE_LIGHT_POSITION_Z_WORD+1u]));
        vec3 nearest=origin+direction*clamp(dot(point-origin,direction),0.0,distance);
        if(length(point-nearest)>=uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_RANGE_WORD]))return 0.0;
    }
    vec3 color=vec3(uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_COLOR_WORD]),
        uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_COLOR_WORD+1u]),uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_COLOR_WORD+2u]));
    return gsLuminance(color)*uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_INTENSITY_WORD]);
}
vec3 gsScatteringLight(uint slot,vec3 position,vec3 rayDirection,float g,vec3 sigma,float integrationLimit) {
    uint base=slot*GPU_SCENE_LIGHT_RECORD_WORDS,flags=gsLights.words[base+GPU_SCENE_LIGHT_FLAGS_WORD],type=(flags>>1u)&7u;
    vec3 color=vec3(uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_COLOR_WORD]),
        uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_COLOR_WORD+1u]),uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_COLOR_WORD+2u]));
    float intensity=uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_INTENSITY_WORD]),distance=1e20,attenuation=1.0;
    vec3 direction;
    if(type==GPU_SCENE_LIGHT_DIRECTIONAL) {
        direction=-normalize(vec3(uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD]),
            uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD+1u]),uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD+2u])));
    }else {
        vec3 point=gsScatteringLightPosition(base);
        vec3 delta=point-position;distance=length(delta);if(distance<=1e-8)return vec3(0);direction=delta/distance;
        float range=uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_RANGE_WORD]),fade=max(1.0-distance/range,0.0);
        attenuation=fade*fade/max(distance*distance,1.0);
        if(type==GPU_SCENE_LIGHT_SPOT) {
            vec3 axis=normalize(vec3(uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD]),
                uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD+1u]),uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD+2u])));
            float inner=uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_INNER_CONE_WORD]),outer=uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_OUTER_CONE_WORD]);
            float cone=dot(axis,-direction);attenuation*=inner==outer?step(outer,cone):smoothstep(outer,inner,cone);
        }
    }
    if(attenuation<=0.0||intensity<=0.0)return vec3(0);
    vec3 visibility=(flags&GPU_SCENE_FLAG_CASTS_SHADOW)!=0u
        ?gsScatteringShadow(position,direction,max(distance-2.0*gsRayEpsilon(position),gsRayEpsilon(position)),sigma,integrationLimit):vec3(1);
    return color*intensity*attenuation*visibility*gsScatteringPhase(dot(rayDirection,direction),g);
}
vec2 gsScatteringSampleOffset(uint sampleIndex,uint samples) {
    if(samples<=1u)return vec2(.5);
    if(samples==2u)return sampleIndex==0u?vec2(.25):vec2(.75);
    if(samples==4u){const vec2 offsets[4]=vec2[](vec2(.375,.125),vec2(.875,.375),vec2(.125,.625),vec2(.625,.875));return offsets[sampleIndex];}
    const vec2 offsets[8]=vec2[](vec2(.5000,.3333),vec2(.2500,.6667),vec2(.7500,.1111),vec2(.1250,.4444),
        vec2(.6250,.7778),vec2(.3750,.2222),vec2(.8750,.5556),vec2(.0625,.8889));return offsets[sampleIndex];
}
#endif
