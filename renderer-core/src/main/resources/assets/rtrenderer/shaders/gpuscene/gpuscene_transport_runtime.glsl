#ifndef GS_TRANSPORT_RUNTIME_GLSL
#define GS_TRANSPORT_RUNTIME_GLSL
#include "gpuscene_visibility.glsl"
#ifndef RTRENDERER_SURFACE_ONLY
layout(location=1) rayPayloadEXT GsVisibilityPayload visibility;
#endif

struct GsShadowVisibility { vec3 approximate; vec3 diffuse; };
GsShadowVisibility gsShadowVisibility(vec3 origin,vec3 normal,vec3 direction,float maximumDistance,bool surfaceReceiver) {
#ifdef RTRENDERER_SURFACE_ONLY
    payload.state=uvec4(0u,GS_PAYLOAD_SHADOW_QUERY,0u,0u);
    traceRayEXT(sceneTlas,gl_RayFlagsTerminateOnFirstHitEXT,255,0,0,0,
        origin+normal*gsRayEpsilon(origin),gsRayEpsilon(origin),direction,maximumDistance,0);
    vec3 value=payload.state.x==0u?vec3(1.0):vec3(0.0);
    return GsShadowVisibility(value,value);
#else
    GsMediumPath path=gsLightingMediumPath;
    GsMediumCoefficients initial=gsCurrentMedium(path.mediumSlot);
    if(gsFrame.words[GPU_SCENE_FRAME_MEDIUM_BOUNDARY_COUNT_WORD]==0u
            && (gsFrame.words[GPU_SCENE_FRAME_FEATURE_FLAGS_WORD]&GPU_SCENE_FEATURE_FLAG_OPAQUE_VISIBILITY)!=0u
            && all(equal(initial.extinction,vec3(0.0)))) {
        payload.state=uvec4(0u,GS_PAYLOAD_SHADOW_QUERY,0u,0u);
        traceRayEXT(sceneTlas,gl_RayFlagsTerminateOnFirstHitEXT,255,0,0,0,
            origin+normal*gsRayEpsilon(origin),gsRayEpsilon(origin),direction,maximumDistance,0);
        vec3 value=payload.state.x==0u?vec3(1.0):vec3(0.0);
    return GsShadowVisibility(value,value);
    }
    float epsilon=gsRayEpsilon(origin);
    vec3 start=origin+normal*epsilon,throughput=vec3(1.0),diffuse=vec3(1.0);
    float remaining=maximumDistance;
    float integrationRemaining=uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_MEDIUM_MAX_DISTANCE_WORD]);
    uint events=gsFrame.words[GPU_SCENE_FRAME_OPTICAL_MAX_EVENTS_WORD];
    [[dont_unroll]] for(uint event=0u;event<events;++event) {
        visibility.normalDistance.w=-1.0;
        traceRayEXT(sceneTlas,gl_RayFlagsNoneEXT,255,1,0,1,start,epsilon,direction,remaining,1);
        GsVisibilityPayload hit=visibility;
        bool missed=hit.normalDistance.w<0.0;
        float distance=missed?remaining:min(hit.normalDistance.w,remaining);
        GsMediumCoefficients medium=gsCurrentMedium(path.mediumSlot);
        float integrationDistance=min(distance,integrationRemaining);
        vec3 segment=gsSegmentTransmittance(path.mediumSlot,start,direction,integrationDistance);
        throughput*=segment;diffuse*=segment;
        integrationRemaining=max(integrationRemaining-integrationDistance,0.0);
        if(missed || hit.normalDistance.w>=remaining) return GsShadowVisibility(throughput,diffuse);
        GsMediumPath next=path;
        gsCrossBoundary(next,hit.boundary,hit.backFace!=0u);
        if(next.depth>GS_MEDIUM_PATH_CAPACITY) return GsShadowVisibility(vec3(0.0),vec3(0.0));
        if((hit.boundary.w&2u)==0u) {
            float n1=medium.ior,n2=gsCurrentMedium(next.mediumSlot).ior;
            if(hit.boundary.w==0u) {
                n2=hit.transmissionIor.y;
                if(hit.backFace!=0u) {n2=n1;n1=hit.transmissionIor.y;}
            }
            float fresnel=gsDielectricFresnel(n1,n2,abs(dot(direction,hit.normalDistance.xyz)));
            float opacity=hit.tintOpacity.a;
            vec3 factor=vec3(1.0-opacity)+opacity*hit.transmissionIor.x*(1.0-fresnel)*hit.tintOpacity.rgb;
            throughput*=factor;
#ifdef RTRENDERER_CAUSTICS
            // Replace diffuse straight-through NEE only where the bounded photon estimator applies.
            // Keep the existing specular and volume NEE approximation explicit and separate.
            if(surfaceReceiver && n1!=n2 && gsCausticRegion(origin) && gsCausticRegion(start+direction*distance))
                factor=vec3(1.0-opacity);
#endif
            diffuse*=factor;
        }
        if(max(throughput.x,max(throughput.y,throughput.z))<=0.0) return GsShadowVisibility(vec3(0.0),vec3(0.0));
        path=next;
        start+=direction*distance;
        epsilon=gsRayEpsilon(start);
        start+=direction*epsilon;
        remaining=max(remaining-distance-epsilon,0.0);
        if(remaining<=epsilon) return GsShadowVisibility(throughput,diffuse);
    }
    // Event exhaustion is conservative visibility, never unoccluded light through unknown geometry.
    return GsShadowVisibility(vec3(0.0),vec3(0.0));
#endif
}

vec3 gsShadowTransmittance(vec3 origin,vec3 normal,vec3 direction,float maximumDistance) {
    return gsShadowVisibility(origin,normal,direction,maximumDistance,false).approximate;
}

#include "gpuscene_lighting.glsl"

float gsPhaseHg(float cosine,float g) {
    float c=clamp(cosine,-1.0,1.0);
    float edge=g>=0.0?1.0-g:1.0+g;
    float d=edge*edge+(g>=0.0?2.0*g*(1.0-c):-2.0*g*(1.0+c));
    return ((1.0-g)*(1.0+g))/(4.0*GS_PI*d*sqrt(d));
}
float gsVolumeLightWeight(uint slot,vec3 origin,vec3 direction,float distance) {
    uint base=slot*GPU_SCENE_LIGHT_RECORD_WORDS;
    uint flags=gsLights.words[base+GPU_SCENE_LIGHT_FLAGS_WORD];
    if((flags&GPU_SCENE_FLAG_ACTIVE)==0u) return 0.0;
    uint type=(flags>>1u)&7u;
    if(type!=GPU_SCENE_LIGHT_DIRECTIONAL) {
        vec3 position=vec3(gsFiniteDoubleAsFloat(gsLights.words[base+GPU_SCENE_LIGHT_POSITION_X_WORD],gsLights.words[base+GPU_SCENE_LIGHT_POSITION_X_WORD+1u]),
            gsFiniteDoubleAsFloat(gsLights.words[base+GPU_SCENE_LIGHT_POSITION_Y_WORD],gsLights.words[base+GPU_SCENE_LIGHT_POSITION_Y_WORD+1u]),
            gsFiniteDoubleAsFloat(gsLights.words[base+GPU_SCENE_LIGHT_POSITION_Z_WORD],gsLights.words[base+GPU_SCENE_LIGHT_POSITION_Z_WORD+1u]));
        float range=uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_RANGE_WORD]);
        vec3 nearest=origin+direction*clamp(dot(position-origin,direction),0.0,distance);
        if(length(position-nearest)>=range) return 0.0;
    }
    vec3 color=vec3(uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_COLOR_WORD]),
        uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_COLOR_WORD+1u]),
        uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_COLOR_WORD+2u]));
    return gsLuminance(color)*uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_INTENSITY_WORD]);
}
vec3 gsVolumeLight(uint slot,vec3 position,vec3 rayDirection,float anisotropy) {
    uint base=slot*GPU_SCENE_LIGHT_RECORD_WORDS;
    uint flags=gsLights.words[base+GPU_SCENE_LIGHT_FLAGS_WORD],type=(flags>>1u)&7u;
    vec3 color=vec3(uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_COLOR_WORD]),
        uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_COLOR_WORD+1u]),
        uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_COLOR_WORD+2u]));
    float intensity=uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_INTENSITY_WORD]);
    vec3 direction;float maximumDistance=1e20,attenuation=1.0;
    if(type==GPU_SCENE_LIGHT_DIRECTIONAL) {
        direction=-normalize(vec3(uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD]),
            uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD+1u]),
            uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD+2u])));
    } else {
        vec3 point=vec3(gsFiniteDoubleAsFloat(gsLights.words[base+GPU_SCENE_LIGHT_POSITION_X_WORD],gsLights.words[base+GPU_SCENE_LIGHT_POSITION_X_WORD+1u]),
            gsFiniteDoubleAsFloat(gsLights.words[base+GPU_SCENE_LIGHT_POSITION_Y_WORD],gsLights.words[base+GPU_SCENE_LIGHT_POSITION_Y_WORD+1u]),
            gsFiniteDoubleAsFloat(gsLights.words[base+GPU_SCENE_LIGHT_POSITION_Z_WORD],gsLights.words[base+GPU_SCENE_LIGHT_POSITION_Z_WORD+1u]));
        vec3 delta=point-position;maximumDistance=length(delta);
        if(maximumDistance<=1e-8) return vec3(0.0);
        direction=delta/maximumDistance;
        float range=uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_RANGE_WORD]);
        float fade=max(1.0-maximumDistance/range,0.0);
        attenuation=fade*fade/max(maximumDistance*maximumDistance,1.0);
        if(type==GPU_SCENE_LIGHT_SPOT) {
            vec3 coneDirection=normalize(vec3(uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD]),
                uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD+1u]),
                uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD+2u])));
            float inner=uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_INNER_CONE_WORD]);
            float outer=uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_OUTER_CONE_WORD]);
            float cone=dot(coneDirection,-direction);
            attenuation*=inner==outer?step(outer,cone):smoothstep(outer,inner,cone);
        }
    }
    if(attenuation<=0.0 || intensity<=0.0) return vec3(0.0);
    vec3 transmittance=(flags&GPU_SCENE_FLAG_CASTS_SHADOW)!=0u
        ?gsShadowTransmittance(position,direction,direction,max(maximumDistance-2.0*gsRayEpsilon(position),gsRayEpsilon(position)))
        :vec3(1.0);
    return color*intensity*attenuation*transmittance*gsPhaseHg(dot(rayDirection,direction),anisotropy);
}
#include "gpuscene_multiscatter.glsl"
vec3 gsIntegrateMedium(vec3 color,inout GsMediumPath path,float distance,vec3 origin,vec3 direction,
        uvec4 launchInfo,float transportRemaining) {
#ifdef RTRENDERER_SURFACE_ONLY
    return color;
#else
    if(!(distance>0.0)) return color;
    GsMediumCoefficients medium=gsCurrentMedium(path.mediumSlot);
    if(all(equal(medium.extinction,vec3(0.0))) && all(equal(medium.emission,vec3(0.0)))) return color;
    distance=min(distance,uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_MEDIUM_MAX_DISTANCE_WORD]));
    float densityMass=gsDensityMass(path.mediumSlot,origin,direction,distance);
    vec3 integral=gsMediumIntegral(medium.extinction,densityMass);
    vec3 radiance=color*exp(-medium.extinction*densityMass)+medium.emission*integral;
    if(all(equal(medium.scattering,vec3(0.0)))) return radiance;
    GsMediumPath saved=gsLightingMediumPath;gsLightingMediumPath=path;
    vec3 ambient=gsFrameVec3(GPU_SCENE_FRAME_SKY_COLOR_WORD)*uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_AMBIENT_INTENSITY_WORD]);
    uint additionalBounces=0u;
#ifdef RTRENDERER_VOLUME_TRANSPORT
    additionalBounces=gsFrame.words[GPU_SCENE_FRAME_SCATTERING_BOUNCES_WORD];
#endif
    if(additionalBounces==0u) radiance+=medium.scattering*ambient*integral;
    uint upper=gsFrame.words[GPU_SCENE_FRAME_LIGHT_SLOT_UPPER_BOUND_WORD];
    uint sampleCount=gsFrame.words[GPU_SCENE_FRAME_SCATTERING_LIGHT_SAMPLES_WORD];
    uint selected[8]; uint firstCandidates[8]; uint candidates=0u;
    float weightSum=0.0;
    uint seed=gsTransportHash(gl_LaunchIDEXT.x+gl_LaunchIDEXT.y*gl_LaunchSizeEXT.x
        ^gsFrame.words[GPU_SCENE_FRAME_SEQUENCE_WORD]*0x9e3779b9u);
    if(sampleCount>0u) {
        // Build one segment-wide importance reservoir. Selection is reused by every interval.
        [[dont_unroll]] for(uint slot=0u;slot<upper;++slot) {
            float weight=gsVolumeLightWeight(slot,origin,direction,distance);
            if(weight<=0.0) continue;
            if(candidates<8u) firstCandidates[candidates]=slot;
            ++candidates;weightSum+=weight;
            [[dont_unroll]] for(uint j=0u;j<sampleCount;++j)
                if(gsTransportRandom(seed^slot*0x85ebca6bu^j*0xc2b2ae35u)<weight/weightSum) selected[j]=slot;
        }
    }
    bool enumerateSmall=sampleCount>0u && candidates<=sampleCount;
    vec3 sunDirection=-normalize(gsFrameVec3(GPU_SCENE_FRAME_SUN_DIRECTION_WORD));
    vec3 sun=gsFrameVec3(GPU_SCENE_FRAME_SUN_COLOR_WORD)*uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_SUN_INTENSITY_WORD]);
    float phase=gsPhaseHg(dot(direction,sunDirection),medium.g);
    uint steps=gsFrame.words[GPU_SCENE_FRAME_SCATTERING_STEPS_WORD];
    vec2 densitySpan;
    if(!gsDensityInterval(path.mediumSlot,origin,direction,distance,densitySpan)) {
        gsLightingMediumPath=saved;return radiance;
    }
    float interval=(densitySpan.y-densitySpan.x)/float(steps),prefixMass=0.0;
    float sigmaReference=min(medium.extinction.x,min(medium.extinction.y,medium.extinction.z));
    float cutoff=uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_TRANSMITTANCE_CUTOFF_WORD]);
    [[dont_unroll]] for(uint stepIndex=0u;stepIndex<steps;++stepIndex) {
        float start=densitySpan.x+float(stepIndex)*interval;
        float density=gsDensityAt(path.mediumSlot,origin+direction*(start+.5*interval));
        if(density<=0.0) continue;
        vec3 prefix=exp(-medium.extinction*prefixMass);
        if(max(prefix.x,max(prefix.y,prefix.z))<cutoff) break;
        float u=gsTransportRandom(seed^(stepIndex+1u)*0x27d4eb2du);
        float effectiveReference=sigmaReference*density,tau=effectiveReference*interval;
        float local=tau<1e-3?u*interval:-log(max(1.0-u*(1.0-exp(-tau)),1e-30))/effectiveReference;
        float pdf=tau<1e-3?1.0/interval:exp(-effectiveReference*local)/gsIntegralChannel(effectiveReference,interval);
        vec3 position=origin+direction*(start+local),source=vec3(0.0);
        if(max(sun.x,max(sun.y,sun.z))>0.0)
            source+=sun*phase*gsShadowTransmittance(position,sunDirection,sunDirection,1e20);
        if(sampleCount==0u) {
            [[dont_unroll]] for(uint slot=0u;slot<upper;++slot)
                if(gsVolumeLightWeight(slot,origin,direction,distance)>0.0)
                    source+=gsVolumeLight(slot,position,direction,medium.g);
        } else {
            uint count=enumerateSmall?candidates:sampleCount;
            [[dont_unroll]] for(uint j=0u;j<count;++j) {
                uint slot=enumerateSmall?firstCandidates[j]:selected[j];
                float factor=enumerateSmall?1.0:weightSum/(float(sampleCount)*gsVolumeLightWeight(slot,origin,direction,distance));
                source+=gsVolumeLight(slot,position,direction,medium.g)*factor;
            }
        }
#ifdef RTRENDERER_VOLUME_TRANSPORT
        if(additionalBounces>0u) {
            uint sampleSeed=seed^(stepIndex+1u)*0x165667b1u;
            vec3 outgoing=gsSamplePhaseHg(direction,medium.g,vec2(gsTransportRandom(sampleSeed),
                gsTransportRandom(sampleSeed^0xd3a2646cu)));
            source+=gsMultipleScatterIncoming(position,outgoing,path,launchInfo,sampleSeed,
                max(transportRemaining-start-local,0.0),additionalBounces);
            gsLightingMediumPath=path;
        }
#endif
        radiance+=exp(-medium.extinction*(prefixMass+density*local))*medium.scattering*density*source/pdf;
        prefixMass+=density*interval;
    }
    gsLightingMediumPath=saved;
    return radiance;
#endif
}
#endif
