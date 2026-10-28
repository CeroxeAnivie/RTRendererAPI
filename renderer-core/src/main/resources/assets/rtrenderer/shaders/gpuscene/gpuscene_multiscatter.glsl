#ifndef GS_MULTISCATTER_GLSL
#define GS_MULTISCATTER_GLSL
#ifdef RTRENDERER_VOLUME_TRANSPORT
vec3 gsSamplePhaseHg(vec3 axis,float g,vec2 samplePoint) {
    // Stable inverse CDF, including g=0 and strongly forward/backward scattering.
    float a=abs(g),u=g>=0.0?samplePoint.x:1.0-samplePoint.x,e=1.0-a;
    float ratio=e/(e+2.0*a*u);
    float cosine=clamp(1.0-2.0*ratio*ratio*(1.0-u)*(1.0+a*u),-1.0,1.0);
    if(g<0.0) cosine=-cosine;
    float sine=sqrt(max(0.0,(1.0-cosine)*(1.0+cosine))),angle=2.0*GS_PI*samplePoint.y;
    vec3 tangent=normalize(abs(axis.z)<.999?cross(vec3(0,0,1),axis):cross(vec3(0,1,0),axis));
    return normalize(axis*cosine+sine*(tangent*cos(angle)+cross(axis,tangent)*sin(angle)));
}
vec3 gsVolumeDirectIncoming(vec3 position,vec3 direction,float anisotropy,uint seed) {
    vec3 sunDirection=-normalize(gsFrameVec3(GPU_SCENE_FRAME_SUN_DIRECTION_WORD));
    vec3 sun=gsFrameVec3(GPU_SCENE_FRAME_SUN_COLOR_WORD)*uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_SUN_INTENSITY_WORD]);
    vec3 source=vec3(0.0);
    if(max(sun.x,max(sun.y,sun.z))>0.0)
        source+=sun*gsPhaseHg(dot(direction,sunDirection),anisotropy)
            *gsShadowTransmittance(position,sunDirection,sunDirection,1e20);
    uint upper=gsFrame.words[GPU_SCENE_FRAME_LIGHT_SLOT_UPPER_BOUND_WORD];
    uint count=gsFrame.words[GPU_SCENE_FRAME_SCATTERING_LIGHT_SAMPLES_WORD];
    uint selected[8],first[8],candidates=0u;float total=0.0;
    [[dont_unroll]] for(uint slot=0u;slot<upper;++slot) {
        float weight=gsVolumeLightWeight(slot,position,direction,0.0);
        if(weight<=0.0) continue;
        if(count==0u) {source+=gsVolumeLight(slot,position,direction,anisotropy);continue;}
        if(candidates<8u) first[candidates]=slot;
        ++candidates;total+=weight;
        [[dont_unroll]] for(uint j=0u;j<count;++j)
            if(gsTransportRandom(seed^slot*0x85ebca6bu^j*0xc2b2ae35u)<weight/total) selected[j]=slot;
    }
    if(count>0u && candidates>0u) {
        bool exhaustive=candidates<=count;
        uint samples=exhaustive?candidates:count;
        [[dont_unroll]] for(uint j=0u;j<samples;++j) {
            uint slot=exhaustive?first[j]:selected[j];
            float factor=exhaustive?1.0:total/(float(count)*gsVolumeLightWeight(slot,position,direction,0.0));
            source+=factor*gsVolumeLight(slot,position,direction,anisotropy);
        }
    }
    return source;
}
vec3 gsVolumeEnvironment(vec3 direction) {
    // The directional sun is sampled by NEE. Its visual sky disk must not be counted again.
    float horizon=.35+.65*clamp(direction.y*.5+.5,0.0,1.0);
    return gsFrameVec3(GPU_SCENE_FRAME_SKY_COLOR_WORD)
        *max(uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_AMBIENT_INTENSITY_WORD]),.05)*horizon;
}
vec3 gsMultipleScatterIncoming(vec3 origin,vec3 direction,GsMediumPath path,uvec4 launchInfo,uint seed,
        float remaining,uint bounceLimit) {
    vec3 radiance=vec3(0),throughput=vec3(1);
    uint bounces=0u,geometryEvents=0u;
    uint eventLimit=gsFrame.words[GPU_SCENE_FRAME_OPTICAL_MAX_EVENTS_WORD];
    float cutoff=uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_TRANSMITTANCE_CUTOFF_WORD]);
    [[dont_unroll]] for(uint iteration=0u;iteration<eventLimit+bounceLimit+1u;++iteration) {
        GpuScenePayload hit=gsTraceRadiance(origin,direction,launchInfo,path);
        float distance=hit.state.x==0u?remaining:min(max(hit.worldPositionAndDistance.w,0.0),remaining);
        GsMediumCoefficients medium=gsCurrentMedium(path.mediumSlot);
        bool sampleCollision=bounces<bounceLimit && any(greaterThan(medium.scattering,vec3(0)));
        uint randomSeed=gsTransportHash(seed^(iteration+1u)*0x9e3779b9u);
        uint channel=min(uint(gsTransportRandom(randomSeed)*3.0),2u);
        float sigma=medium.extinction[channel];
        float target=sampleCollision && sigma>0.0
            ?-log(max(1.0-gsTransportRandom(randomSeed^0x68bc21ebu),1e-30))/sigma:1e30;
        vec3 flight=gsDensityFlight(path.mediumSlot,origin,direction,distance,target);
        // The source integral is a separate deterministic term in the transport equation.
        radiance+=throughput*medium.emission*gsMediumIntegral(medium.extinction,flight.x);
        if(sampleCollision && flight.y>=0.0) {
            vec3 transmittance=exp(-medium.extinction*flight.z);
            float pdf=dot(medium.extinction*transmittance,vec3(1.0/3.0));
            if(!(pdf>0.0)) return radiance;
            throughput*=transmittance*medium.scattering/pdf;
            origin+=direction*flight.y;remaining=max(remaining-flight.y,0.0);
            gsLightingMediumPath=path;
            radiance+=throughput*gsVolumeDirectIncoming(origin,direction,medium.g,randomSeed^0xc2b2ae35u);
            direction=gsSamplePhaseHg(direction,medium.g,vec2(gsTransportRandom(randomSeed^0x27d4eb2du),
                gsTransportRandom(randomSeed^0x165667b1u)));
            ++bounces;
        } else {
            vec3 transmittance=exp(-medium.extinction*flight.x);
            if(sampleCollision) {
                float survival=dot(transmittance,vec3(1.0/3.0));
                if(!(survival>0.0)) return radiance;
                throughput*=transmittance/survival;
            } else throughput*=transmittance;
            remaining=max(remaining-distance,0.0);
            if(hit.state.x==0u) return radiance+throughput*gsVolumeEnvironment(direction);
            if(geometryEvents>=eventLimit) return radiance;
            ++geometryEvents;
            bool volume=(hit.mediumBoundary.w&2u)!=0u;
            float opacity=hit.mediumBoundary.w!=0u?1.0:clamp(hit.baseColorAndOpacity.a,0.0,1.0);
            float transmission=volume?1.0:clamp(hit.transmissionIor.x,0.0,1.0);
            gsLightingMediumPath=path;
            if(!volume && opacity*(1.0-transmission)>0.0)
                radiance+=throughput*opacity*(1.0-transmission)*gsOpticalSurface(hit,origin);
            float hole=1.0-opacity,dielectric=opacity*transmission,total=hole+dielectric;
            if(total<=0.0 || geometryEvents>=eventLimit) return radiance;
            throughput*=total;
            bool uncovered=hole>0.0 && (dielectric==0.0 || gsTransportRandom(randomSeed^0xb5297a4du)<hole/total);
            if(!uncovered) {
                GsMediumPath next=path;
                gsCrossMediumBoundary(next,hit);
                if(next.depth>GS_MEDIUM_PATH_CAPACITY) return radiance;
                if(volume) path=next;
                else {
                    vec2 ior=gsInterfaceIors(hit,path.mediumSlot,next.mediumSlot);
                    vec3 normal=normalize(hit.worldNormalAndRoughness.xyz);
                    float roughness=clamp(hit.worldNormalAndRoughness.w,0.0,1.0);
                    if(dot(normal,-direction)<=0.0) return radiance;
                    vec3 halfVector=ior.x==ior.y?normal:gsDielectricHalfVector(normal,direction,roughness,
                        vec2(gsTransportRandom(randomSeed^0x91e10da5u),gsTransportRandom(randomSeed^0x1b56c4e9u)));
                    vec3 refracted;
                    bool transmitted=gsRefractDirection(direction,halfVector,ior.x/ior.y,refracted);
                    float fresnel=transmitted?gsDielectricFresnel(ior.x,ior.y,max(dot(-direction,halfVector),0.0)):1.0;
                    bool reflected=!transmitted || gsTransportRandom(randomSeed^0x02e5be93u)<fresnel;
                    vec3 outgoing=reflected?normalize(reflect(direction,halfVector)):refracted;
                    float cosine=dot(normal,outgoing);
                    if(reflected?cosine<=0.0:cosine>=0.0) return radiance;
                    throughput*=ior.x==ior.y?1.0:gsDielectricBranchWeight(normal,direction,outgoing,roughness);
                    if(!reflected) {
                        float eta=ior.x/ior.y;
                        throughput*=clamp(hit.baseColorAndOpacity.rgb,vec3(0),vec3(1))*(eta*eta);
                        path=next;
                    }
                    direction=outgoing;
                }
            }
            origin=hit.worldPositionAndDistance.xyz+direction*gsRayEpsilon(hit.worldPositionAndDistance.xyz);
        }
        if(max(throughput.x,max(throughput.y,throughput.z))<=cutoff) return radiance;
    }
    return radiance;
}
#endif
#endif
