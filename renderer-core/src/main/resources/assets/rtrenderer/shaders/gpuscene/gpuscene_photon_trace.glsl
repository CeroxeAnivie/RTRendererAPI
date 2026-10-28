// Surface light tracing. Each emitted path owns one record; publication finishes before gathering.
void gsStorePhoton(uint index,GpuScenePayload hit,vec3 incoming,vec3 flux) {
    uint base=gsCaustics.words[2]+index*16u;
    vec3 position=hit.worldPositionAndDistance.xyz,normal=gsDecodeGeometricNormal(hit.compositeState.w);
    gsCaustics.words[base]=floatBitsToUint(position.x);
    gsCaustics.words[base+1u]=floatBitsToUint(position.y);
    gsCaustics.words[base+2u]=floatBitsToUint(position.z);
    gsCaustics.words[base+4u]=floatBitsToUint(flux.x);
    gsCaustics.words[base+5u]=floatBitsToUint(flux.y);
    gsCaustics.words[base+6u]=floatBitsToUint(flux.z);
    gsCaustics.words[base+7u]=hit.mediumBoundary.x;
    gsCaustics.words[base+8u]=floatBitsToUint(incoming.x);
    gsCaustics.words[base+9u]=floatBitsToUint(incoming.y);
    gsCaustics.words[base+10u]=floatBitsToUint(incoming.z);
    gsCaustics.words[base+12u]=floatBitsToUint(normal.x);
    gsCaustics.words[base+13u]=floatBitsToUint(normal.y);
    gsCaustics.words[base+14u]=floatBitsToUint(normal.z);
    uint head=gsCaustics.words[1]+(gsCausticHash(gsCausticCell(position))&gsCaustics.words[3]);
    gsCaustics.words[base+3u]=atomicExchange(gsCaustics.words[head],index);
}
vec3 gsPhotonCone(vec3 axis,float cosine,vec2 samplePoint) {
    vec3 tangent=normalize(abs(axis.z)<.999?cross(vec3(0,0,1),axis):cross(vec3(0,1,0),axis));
    vec3 bitangent=cross(axis,tangent);
    float c=1.0-samplePoint.x*(1.0-cosine),s=sqrt(max(0.0,1.0-c*c)),angle=2.0*GS_PI*samplePoint.y;
    return normalize(axis*c+s*(tangent*cos(angle)+bitangent*sin(angle)));
}
GpuScenePayload gsTracePhoton(vec3 origin,vec3 direction,uvec4 launchInfo) {
    payload.state=uvec4(0u,GS_PAYLOAD_PHOTON_QUERY,0u,0u);payload.launchInfo=launchInfo;
    payload.mediumBoundary=uvec4(0u);
    traceRayEXT(sceneTlas,gl_RayFlagsNoneEXT,255,0,0,0,origin,gsRayEpsilon(origin),direction,1.0e20,0);
    return payload;
}
void main() {
    uint index=gl_LaunchIDEXT.x+gl_LaunchIDEXT.y*gl_LaunchSizeEXT.x;
    uint count=gsFrame.words[GPU_SCENE_FRAME_CAUSTIC_PHOTONS_WORD];
    if(index>=count) return;
    uint seed=gsTransportHash(index^gsFrame.words[GPU_SCENE_FRAME_SEQUENCE_WORD]*0x9e3779b9u);
    uint emitters=gsCaustics.words[0];
    uint emitter=min(uint(gsTransportRandom(seed)*float(emitters)),emitters-1u);
    uint row=16u+emitter*36u,slot=gsCaustics.words[row];
    vec3 center=gsFrameVec3(GPU_SCENE_FRAME_CAUSTIC_CENTER_WORD);
    float radius=uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_CAUSTIC_REGION_RADIUS_WORD]);
    float scale=float(emitters)/float(count),range=0.0;
    vec2 samplePoint=vec2(gsTransportRandom(seed^0x68bc21ebu),gsTransportRandom(seed^0x02e5be93u));
    vec3 origin,direction,flux;
    GsMediumPath path=gsEmptyMediumPath();
    path.depth=gsCaustics.words[row+1u];
    [[dont_unroll]] for(uint i=0u;i<path.depth;++i) gsMediumPathSet(path,i,gsCaustics.words[row+2u+i]);
    path.mediumSlot=gsSelectMediumInstance(path);
    uint type=GPU_SCENE_LIGHT_DIRECTIONAL;
    vec3 lightDirection=normalize(gsFrameVec3(GPU_SCENE_FRAME_SUN_DIRECTION_WORD));
    flux=gsFrameVec3(GPU_SCENE_FRAME_SUN_COLOR_WORD)*uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_SUN_INTENSITY_WORD]);
    if(slot!=GS_INVALID_SLOT) {
        uint base=slot*GPU_SCENE_LIGHT_RECORD_WORDS;
        type=(gsLights.words[base+GPU_SCENE_LIGHT_FLAGS_WORD]>>1u)&7u;
        flux=vec3(uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_COLOR_WORD]),
            uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_COLOR_WORD+1u]),
            uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_COLOR_WORD+2u]))
            *uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_INTENSITY_WORD]);
        lightDirection=vec3(uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD]),
            uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD+1u]),
            uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD+2u]));
        if(type!=GPU_SCENE_LIGHT_DIRECTIONAL) {
            origin=vec3(gsFiniteDoubleAsFloat(gsLights.words[base+GPU_SCENE_LIGHT_POSITION_X_WORD],gsLights.words[base+GPU_SCENE_LIGHT_POSITION_X_WORD+1u]),
                gsFiniteDoubleAsFloat(gsLights.words[base+GPU_SCENE_LIGHT_POSITION_Y_WORD],gsLights.words[base+GPU_SCENE_LIGHT_POSITION_Y_WORD+1u]),
                gsFiniteDoubleAsFloat(gsLights.words[base+GPU_SCENE_LIGHT_POSITION_Z_WORD],gsLights.words[base+GPU_SCENE_LIGHT_POSITION_Z_WORD+1u]));
            vec3 toCenter=center-origin;float distance=length(toCenter);
            float cosine=distance>radius?sqrt(max(0.0,1.0-radius*radius/(distance*distance))):-1.0;
            direction=gsPhotonCone(distance>0.0?toCenter/distance:vec3(0,0,1),cosine,samplePoint);
            flux*=scale*2.0*GS_PI*(1.0-cosine);
            range=uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_RANGE_WORD]);
            if(type==GPU_SCENE_LIGHT_SPOT) {
                float inner=uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_INNER_CONE_WORD]);
                float outer=uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_OUTER_CONE_WORD]);
                float cone=dot(normalize(lightDirection),direction);
                flux*=inner==outer?step(outer,cone):smoothstep(outer,inner,cone);
            }
        }
    }
    if(type==GPU_SCENE_LIGHT_DIRECTIONAL) {
        direction=normalize(lightDirection);
        vec3 tangent=normalize(abs(direction.z)<.999?cross(vec3(0,0,1),direction):cross(vec3(0,1,0),direction));
        float r=radius*sqrt(samplePoint.x),angle=2.0*GS_PI*samplePoint.y;
        origin=center-direction*(radius+2.0*gsRayEpsilon(center))
            +r*(tangent*cos(angle)+cross(direction,tangent)*sin(angle));
        flux*=scale*GS_PI*radius*radius;
    }
    if(max(flux.x,max(flux.y,flux.z))<=0.0) return;
    bool specular=false;
    float traveled=0.0,remaining=uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_MEDIUM_MAX_DISTANCE_WORD]);
    uint maxEvents=gsFrame.words[GPU_SCENE_FRAME_CAUSTIC_MAX_EVENTS_WORD];
    uvec4 launchInfo=uvec4(gl_LaunchIDEXT.xy,gl_LaunchSizeEXT.xy);
    [[dont_unroll]] for(uint event=0u;event<maxEvents;++event) {
        GpuScenePayload hit=gsTracePhoton(origin,direction,launchInfo);
        if(hit.state.x==0u) return;
        float distance=hit.worldPositionAndDistance.w;
        traveled+=distance;
        if(distance>remaining || (range>0.0 && traveled>=range)) return;
        flux*=gsSegmentTransmittance(path.mediumSlot,origin,direction,distance);
        remaining-=distance;
        if(any(isnan(flux)) || any(isinf(flux)) || max(flux.x,max(flux.y,flux.z))<=0.0) return;
        bool volume=(hit.mediumBoundary.w&2u)!=0u;
        GsMediumPath next=path;
        gsCrossMediumBoundary(next,hit);
        if(next.depth>GS_MEDIUM_PATH_CAPACITY) return;
        float opacity=hit.mediumBoundary.w!=0u?1.0:clamp(hit.baseColorAndOpacity.a,0.0,1.0);
        float transmission=clamp(hit.transmissionIor.x,0.0,1.0),metallic=clamp(hit.emissiveAndMetallic.a,0.0,1.0);
        float choice=gsTransportRandom(seed^(event+1u)*0x27d4eb2du);
        if(volume) path=next;
        else if(choice>=opacity) { /* coverage hole: straight, no medium transition */ }
        else if(choice<opacity*(1.0-transmission)*(1.0-metallic)) {
            if(specular && gsCausticRegion(hit.worldPositionAndDistance.xyz) && !gsUnlit(hit) && !gsLightmapModulated(hit)) {
                float localPolicy=range>0.0?pow(max(1.0-traveled/range,0.0),2.0)*min(traveled*traveled,1.0):1.0;
                // Receiver BRDF includes this lobe's weight; cancel its discrete selection probability.
                gsStorePhoton(index,hit,-direction,flux*(localPolicy/(opacity*(1.0-transmission)*(1.0-metallic))));
            }
            return;
        } else {
            bool specularEvent=true;
            vec3 normal=normalize(hit.worldNormalAndRoughness.xyz);
            float roughness=clamp(hit.worldNormalAndRoughness.w,0.0,1.0);
            vec2 point=vec2(gsTransportRandom(seed^(event+1u)*0x165667b1u),gsTransportRandom(seed^(event+1u)*0xd3a2646cu));
            vec3 halfVector=gsDielectricHalfVector(normal,direction,roughness,point),outgoing;
            if(choice<opacity*(1.0-transmission)) {
                outgoing=normalize(reflect(direction,halfVector));
                flux*=gsFresnelSchlick(max(dot(-direction,halfVector),0.0),clamp(hit.baseColorAndOpacity.rgb,vec3(0),vec3(1)))
                    *gsDielectricBranchWeight(normal,direction,outgoing,roughness);
                if(dot(normal,outgoing)<=0.0) return;
            } else {
                vec2 ior=gsInterfaceIors(hit,path.mediumSlot,next.mediumSlot);
                specularEvent=ior.x!=ior.y;
                if(ior.x==ior.y) halfVector=normal;
                vec3 refracted;
                bool canTransmit=gsRefractDirection(direction,halfVector,ior.x/ior.y,refracted);
                float fresnel=canTransmit?gsDielectricFresnel(ior.x,ior.y,max(dot(-direction,halfVector),0.0)):1.0;
                bool reflection=!canTransmit || gsTransportRandom(seed^(event+1u)*0x85ebca6bu)<fresnel;
                outgoing=reflection?normalize(reflect(direction,halfVector)):refracted;
                if(reflection?dot(normal,outgoing)<=0.0:dot(normal,outgoing)>=0.0) return;
                flux*=ior.x==ior.y?1.0:gsDielectricBranchWeight(normal,direction,outgoing,roughness);
                if(!reflection) { flux*=clamp(hit.baseColorAndOpacity.rgb,vec3(0),vec3(1));path=next; }
                // Flux/importance transport has no radiance eta^2 factor.
            }
            direction=outgoing;specular=specular || specularEvent;
        }
        origin=hit.worldPositionAndDistance.xyz+direction*gsRayEpsilon(hit.worldPositionAndDistance.xyz);
    }
}
