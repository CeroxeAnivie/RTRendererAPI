#ifndef GS_OPTICS_GLSL
#define GS_OPTICS_GLSL
vec2 gsDielectricSamplePoint(uvec4 launchInfo,uint sampleIndex,uint sampleCount,uint seed) {
    uint sequence=gsFrame.words[GPU_SCENE_FRAME_SEQUENCE_WORD];
    float x=gsOpticalRandom(launchInfo,seed^sequence^0x1b56c4e9u,0u);
    float y=gsOpticalRandom(launchInfo,seed^sequence^0x91e10da5u,0u);
    return vec2((float(sampleIndex)+x)/float(sampleCount),fract(gsRadicalInverseVdc(sampleIndex)+y));
}
// Isotropic GGX visible-normal sampling, Heitz 2018. The view points away from the surface.
vec3 gsSampleVisibleGgx(vec3 normal,vec3 view,float alpha,vec2 samplePoint) {
    vec3 tangent=normalize(abs(normal.z)<.999?cross(vec3(0,0,1),normal):cross(vec3(0,1,0),normal));
    vec3 bitangent=cross(normal,tangent);
    vec3 localView=vec3(dot(view,tangent),dot(view,bitangent),max(dot(view,normal),0.0));
    vec3 stretched=normalize(vec3(alpha*localView.xy,localView.z));
    float planar=dot(stretched.xy,stretched.xy);
    vec3 first=planar>0.0?vec3(-stretched.y,stretched.x,0.0)/sqrt(planar):vec3(1,0,0);
    vec3 second=cross(stretched,first);
    float radius=sqrt(samplePoint.x),angle=2.0*GS_PI*samplePoint.y;
    float x=radius*cos(angle),y=radius*sin(angle),blend=.5*(1.0+stretched.z);
    y=mix(sqrt(max(0.0,1.0-x*x)),y,blend);
    vec3 visible=x*first+y*second+sqrt(max(0.0,1.0-x*x-y*y))*stretched;
    vec3 halfVector=normalize(vec3(alpha*visible.xy,max(visible.z,0.0)));
    return normalize(tangent*halfVector.x+bitangent*halfVector.y+normal*halfVector.z);
}
float gsDielectricMaskWeight(float normalView,float normalOutgoing,float alpha) {
    float v=clamp(normalView,0.0,1.0),l=clamp(normalOutgoing,0.0,1.0),a2=alpha*alpha;
    float sv=sqrt(a2+(1.0-a2)*v*v),sl=sqrt(a2+(1.0-a2)*l*l);
    float denominator=l*sv+v*sl;
    // Height-correlated Smith G2 / G1(view), written without tan(theta) or grazing divisions.
    return denominator>0.0?clamp(l*(v+sv)/denominator,0.0,1.0):0.0;
}
vec3 gsDielectricHalfVector(vec3 normal,vec3 incident,float roughness,vec2 samplePoint) {
    return roughness<=.001?normal:gsSampleVisibleGgx(normal,-incident,roughness*roughness,samplePoint);
}
float gsDielectricBranchWeight(vec3 normal,vec3 incident,vec3 outgoing,float roughness) {
    return roughness<=.001?1.0:gsDielectricMaskWeight(dot(normal,-incident),abs(dot(normal,outgoing)),roughness*roughness);
}
vec3 gsOpticalContinuation(vec3 origin,vec3 direction,uvec4 launchInfo,GsMediumPath path,uint seed,
        float integrationRemaining,out float firstHitDistance) {
    vec3 radiance=vec3(0.0),throughput=vec3(1.0);
    firstHitDistance=0.0;
    // Every caller has already consumed the primary surface/boundary event.
    uint limit=gsFrame.words[GPU_SCENE_FRAME_OPTICAL_MAX_EVENTS_WORD]-1u;
    float cutoff=uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_TRANSMITTANCE_CUTOFF_WORD]);
    integrationRemaining=max(integrationRemaining,0.0);
    [[dont_unroll]] for(uint event=0u;event<limit;++event) {
        GpuScenePayload hit=gsTraceRadiance(origin,direction,launchInfo,path);
        if(event==0u) firstHitDistance=hit.state.x==0u?65504.0:max(hit.worldPositionAndDistance.w,0.0);
        float distance=hit.state.x==0u?integrationRemaining:min(hit.worldPositionAndDistance.w,integrationRemaining);
        radiance+=throughput*gsIntegrateMedium(vec3(0.0),path,distance,origin,direction,launchInfo,integrationRemaining);
        throughput*=gsSegmentTransmittance(path.mediumSlot,origin,direction,distance);
        integrationRemaining=max(integrationRemaining-distance,0.0);
        if(hit.state.x==0u) {radiance+=throughput*gsSkyRadiance(direction);return radiance;}
        bool volume=(hit.mediumBoundary.w&2u)!=0u;
        float opacity=hit.mediumBoundary.w!=0u?1.0:clamp(hit.baseColorAndOpacity.a,0.0,1.0);
        float transmission=volume?1.0:clamp(hit.transmissionIor.x,0.0,1.0);
        if(!volume && opacity*(1.0-transmission)>0.0) {
            gsLightingMediumPath=path;
            if(gsSurfaceOverlay(hit)) {radiance+=throughput*gsResolveSurfaceOverlay(hit,origin,direction,launchInfo,path);return radiance;}
            radiance+=throughput*opacity*(1.0-transmission)*gsOpticalSurface(hit,origin);
        }
        float hole=1.0-opacity,dielectric=opacity*transmission,total=hole+dielectric;
        if(total<=0.0) return radiance;
        throughput*=total;
        // Coverage holes are straight visibility, not a tinted dielectric interface.
        bool uncovered=hole>0.0 && (dielectric==0.0
            || gsOpticalRandom(launchInfo,seed^0x68bc21ebu,event)<hole/total);
        if(!uncovered) {
            GsMediumPath next=path;
            gsCrossMediumBoundary(next,hit);
            if(next.depth>GS_MEDIUM_PATH_CAPACITY) return radiance;
            if(volume) path=next;
            else {
                vec2 ior=gsInterfaceIors(hit,path.mediumSlot,next.mediumSlot);
                vec3 normal=normalize(hit.worldNormalAndRoughness.xyz);
                float roughness=clamp(hit.worldNormalAndRoughness.w,0.0,1.0);
                // Boundary normals are geometric; retaining their hemisphere preserves the medium set.
                if(dot(normal,-direction)<=0.0) return radiance;
                vec2 samplePoint=gsDielectricSamplePoint(launchInfo,0u,1u,seed^(event+1u)*0x02e5be93u);
                vec3 halfVector=ior.x==ior.y?normal:gsDielectricHalfVector(normal,direction,roughness,samplePoint);
                vec3 refracted;
                bool transmitted=gsRefractDirection(direction,halfVector,ior.x/ior.y,refracted);
                float fresnel=transmitted?gsDielectricFresnel(ior.x,ior.y,max(dot(-direction,halfVector),0.0)):1.0;
                bool reflection=!transmitted || gsOpticalRandom(launchInfo,seed^0xb5297a4du,event)<fresnel;
                vec3 outgoing=reflection?normalize(reflect(direction,halfVector)):refracted;
                float cosine=dot(normal,outgoing);
                if(reflection?cosine<=0.0:cosine>=0.0) return radiance;
                // VNDF reflection/refraction PDFs cancel D and the matching refraction Jacobian.
                float weight=ior.x==ior.y?1.0:gsDielectricBranchWeight(normal,direction,outgoing,roughness);
                throughput*=weight;
                if(!reflection) {
                    float eta=ior.x/ior.y;
                    throughput*=clamp(hit.baseColorAndOpacity.rgb,vec3(0.0),vec3(1.0))*(eta*eta);
                    path=next;
                }
                direction=outgoing;
            }
        }
        if(max(throughput.x,max(throughput.y,throughput.z))<=cutoff) return radiance;
        origin=hit.worldPositionAndDistance.xyz+direction*gsRayEpsilon(hit.worldPositionAndDistance.xyz);
    }
    return radiance;
}
vec3 gsEvaluateSecondaryTransport(GpuScenePayload hit,vec3 incident,uvec4 launchInfo,
        inout GsMediumPath incidentPath,uint primaryIndex,uint primaryCount,float integrationRemaining,out float reflectionHitDistance) {
    reflectionHitDistance=0.0;
#ifdef RTRENDERER_SURFACE_ONLY
    if(gsFrame.words[GPU_SCENE_FRAME_OPTICAL_MAX_EVENTS_WORD]<=1u) return vec3(0.0);
    vec3 outgoing;
    vec3 weight=gsOpaqueMetallicReflectionWeight(hit,incident,launchInfo,primaryIndex,primaryCount,outgoing);
    if(max(weight.x,max(weight.y,weight.z))<=0.0) return vec3(0.0);
    vec3 origin=hit.worldPositionAndDistance.xyz+outgoing*gsRayEpsilon(hit.worldPositionAndDistance.xyz);
    GpuScenePayload receiver=gsTraceRadiance(origin,outgoing,launchInfo,incidentPath);
    reflectionHitDistance=receiver.state.x==0u?65504.0:max(receiver.worldPositionAndDistance.w,0.0);
    return weight*(receiver.state.x==0u?gsSkyRadiance(outgoing):gsOpticalSurface(receiver,origin));
#else
    bool volume=(hit.mediumBoundary.w&2u)!=0u;
    bool optical=volume || hit.transmissionIor.x>0.0;
    if(!optical && hit.emissiveAndMetallic.a<=0.0) return vec3(0.0);
    vec3 opaqueDirection=incident,opaqueWeight=vec3(1.0);
    if(!optical) {
        opaqueWeight=gsOpaqueMetallicReflectionWeight(hit,incident,launchInfo,primaryIndex,primaryCount,opaqueDirection);
        if(max(opaqueWeight.x,max(opaqueWeight.y,opaqueWeight.z))<=0.0) return vec3(0.0);
    }
    GsMediumPath transmittedPath=incidentPath;
    if(optical) {
        gsCrossMediumBoundary(transmittedPath,hit);
        if(transmittedPath.depth>GS_MEDIUM_PATH_CAPACITY) return vec3(0.0);
    }
    bool split=optical && !volume;
    vec2 ior=split?gsInterfaceIors(hit,incidentPath.mediumSlot,transmittedPath.mediumSlot):vec2(1.0);
    uint samples=split?gsFrame.words[GPU_SCENE_FRAME_OPTICAL_SAMPLES_WORD]:1u;
    vec3 total=vec3(0.0);float distanceSum=0.0,distanceWeight=0.0;
    [[dont_unroll]] for(uint sampleIndex=0u;sampleIndex<samples;++sampleIndex) {
        vec3 normal=vec3(0.0),halfVector=vec3(0.0),transmitted=incident;
        float roughness=0.0,fresnel=0.0;
        if(split) {
            normal=normalize(hit.worldNormalAndRoughness.xyz);
            roughness=clamp(hit.worldNormalAndRoughness.w,0.0,1.0);
            if(dot(-incident,normal)<=0.0) continue;
            halfVector=ior.x==ior.y?normal:gsDielectricHalfVector(normal,incident,roughness,
                gsDielectricSamplePoint(launchInfo,sampleIndex,samples,primaryIndex*0x967a889bu));
            bool canTransmit=gsRefractDirection(incident,halfVector,ior.x/ior.y,transmitted);
            fresnel=canTransmit?gsDielectricFresnel(ior.x,ior.y,max(dot(-incident,halfVector),0.0)):1.0;
        }
        // Exact first-interface split avoids noisy disappearing reflections at one SPP.
        uint branches=split?2u:1u;
        [[dont_unroll]] for(uint branch=0u;branch<branches;++branch) {
            vec3 direction=opaqueDirection,branchWeight=opaqueWeight;
            GsMediumPath continuationPath=incidentPath;
            uint continuationSeed=primaryIndex+1u;
            if(volume) continuationPath=transmittedPath;
            if(split) {
                bool reflection=branch==0u;
                float weight=reflection?fresnel:1.0-fresnel;
                if(weight<=0.0) continue;
                direction=reflection?normalize(reflect(incident,halfVector)):transmitted;
                float cosine=dot(normal,direction);
                if(reflection?cosine<=0.0:cosine>=0.0) continue;
                // G2/G1 is f*cos/pdf under visible-normal sampling for both dielectric branches.
                weight*=ior.x==ior.y?1.0:gsDielectricBranchWeight(normal,incident,direction,roughness);
                if(!reflection) {float eta=ior.x/ior.y;weight*=eta*eta;continuationPath=transmittedPath;}
                branchWeight=weight*(reflection?vec3(1.0):clamp(hit.baseColorAndOpacity.rgb,vec3(0.0),vec3(1.0)));
                continuationSeed=sampleIndex*2u+branch+1u;
            }
            // One continuation call site avoids cloning the entire volume/light call tree
            // separately for metallic, invisible-volume and dielectric primary events.
            float firstDistance;
            vec3 contribution=gsOpticalContinuation(hit.worldPositionAndDistance.xyz+direction*gsRayEpsilon(hit.worldPositionAndDistance.xyz),
                direction,launchInfo,continuationPath,continuationSeed,integrationRemaining,firstDistance);
            if(!split) {reflectionHitDistance=firstDistance;return branchWeight*contribution;}
            total+=branchWeight*contribution;
            float luminance=gsLuminance(branchWeight*contribution);
            distanceSum+=firstDistance*luminance;distanceWeight+=luminance;
        }
    }
    reflectionHitDistance=distanceWeight>0.0?distanceSum/distanceWeight:0.0;
    return total/float(samples);
#endif
}
#endif
