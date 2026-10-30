#include "gpuscene_photon_segment.glsl"
// Light paths reserve bounded per-path slots; publication finishes before gathering.
void gsStorePhoton(uint path,uint ordinal,vec3 position,uint slot,vec3 incoming,vec3 flux,vec3 normal,uint kind) {
    uint record=path*gsCaustics.words[5]+ordinal;
    uint base=gsCaustics.words[2]+record*16u;
    gsCaustics.words[base]=floatBitsToUint(position.x);
    gsCaustics.words[base+1u]=floatBitsToUint(position.y);
    gsCaustics.words[base+2u]=floatBitsToUint(position.z);
    gsCaustics.words[base+4u]=floatBitsToUint(flux.x);
    gsCaustics.words[base+5u]=floatBitsToUint(flux.y);
    gsCaustics.words[base+6u]=floatBitsToUint(flux.z);
    gsCaustics.words[base+7u]=slot;
    gsCaustics.words[base+8u]=floatBitsToUint(incoming.x);
    gsCaustics.words[base+9u]=floatBitsToUint(incoming.y);
    gsCaustics.words[base+10u]=floatBitsToUint(incoming.z);
    gsCaustics.words[base+12u]=floatBitsToUint(normal.x);
    gsCaustics.words[base+13u]=floatBitsToUint(normal.y);
    gsCaustics.words[base+14u]=floatBitsToUint(normal.z);
    gsCaustics.words[base+15u]=kind;
    uint head=gsCaustics.words[1]+(gsCausticHash(gsCausticCell(position))&gsCaustics.words[3]);
    gsCaustics.words[base+3u]=atomicExchange(gsCaustics.words[head],record);
}
#include "gpuscene_photon_emission.glsl"
vec3 gsPhotonHgSample(vec3 incident,float anisotropy,vec2 samplePoint) {
    float g=clamp(anisotropy,-.95,.95),t=2.0*samplePoint.x-1.0;
    float cosine;
    if(abs(g)<1.0e-4) cosine=t;
    else {
        float denominator=1.0+g*t,quotient=(1.0-g*g)/denominator;
        cosine=clamp((1.0+g*g-quotient*quotient)/(2.0*g),-1.0,1.0);
    }
    vec3 axis=normalize(incident),up=abs(axis.z)<.999?vec3(0,0,1):vec3(0,1,0);
    vec3 tangent=normalize(cross(up,axis)),bitangent=cross(axis,tangent);
    float sine=sqrt(max(0.0,1.0-cosine*cosine)),phi=2.0*GS_PI*samplePoint.y;
    return normalize(axis*cosine+sine*(tangent*cos(phi)+bitangent*sin(phi)));
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
    vec3 origin,direction,flux;float range;uint emitterRow;
    gsEmitPhoton(index,count,origin,direction,flux,range,emitterRow);
    if(max(flux.x,max(flux.y,flux.z))<=0.0) return;
    bool specular=false;
    float traveled=0.0,remaining=uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_MEDIUM_MAX_DISTANCE_WORD]);
    uint maxEvents=gsFrame.words[GPU_SCENE_FRAME_CAUSTIC_MAX_EVENTS_WORD];
    float density=uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_MEDIUM_DENSITY_WORD]);
    vec3 extinction=gsFrameVec3(GPU_SCENE_FRAME_MEDIUM_EXTINCTION_WORD)*density;
    vec3 scattering=gsFrameVec3(GPU_SCENE_FRAME_MEDIUM_SCATTERING_WORD)*density;
    uint scatterCount=0u,scatterLimit=gsFrame.words[GPU_SCENE_FRAME_SCATTERING_BOUNCES_WORD]+1u,deposits=0u;
    float anisotropy=uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_MEDIUM_PHASE_ANISOTROPY_WORD]);
    uvec4 launchInfo=uvec4(gl_LaunchIDEXT.xy,gl_LaunchSizeEXT.xy);
    [[dont_unroll]] for(uint event=0u;event<maxEvents;++event) {
        GpuScenePayload hit=gsTracePhoton(origin,direction,launchInfo);
        float distance=hit.state.x!=0u?hit.worldPositionAndDistance.w:remaining;
        float maximum=range>0.0?min(remaining,max(range-traveled,0.0)):remaining;
        if(maximum<=0.0)return;
        GsPhotonSegment segment=gsPhotonSegment(extinction,scattering,min(distance,maximum),
            gsTransportRandom(seed^(event+1u)*0x68bc21ebu),
            gsTransportRandom(seed^(event+1u)*0xd3a2646cu),scatterCount<scatterLimit);
        traveled+=segment.distance;remaining-=segment.distance;
        if(segment.collision) {
            origin+=direction*segment.distance;
            float localPolicy=range>0.0?pow(max(1.0-traveled/range,0.0),2.0)*min(traveled*traveled,1.0):1.0;
            if(specular&&gsCausticRegion(origin)&&deposits<gsCaustics.words[5])
                gsStorePhoton(index,deposits++,origin,GS_INVALID_SLOT,-direction,
                    flux*segment.incidentWeight*localPolicy,vec3(0),1u);
            flux*=segment.transportWeight;
            if(any(isnan(flux))||any(isinf(flux))||max(flux.x,max(flux.y,flux.z))<=0.0)return;
            direction=gsPhotonHgSample(direction,anisotropy,vec2(
                gsTransportRandom(seed^(event+1u)*0x165667b1u),
                gsTransportRandom(seed^(event+1u)*0x85ebca6bu)));
            ++scatterCount;
            continue;
        }
        flux*=segment.transportWeight;
        if(hit.state.x==0u || distance>=maximum)return;
        if(any(isnan(flux)) || any(isinf(flux)) || max(flux.x,max(flux.y,flux.z))<=0.0) return;
        float opacity=clamp(hit.baseColorAndOpacity.a,0.0,1.0);
        float transmission=clamp(hit.transmissionIor.x,0.0,1.0),metallic=clamp(hit.emissiveAndMetallic.a,0.0,1.0);
        vec3 normal=normalize(hit.worldNormalAndRoughness.xyz);
        // Opaque dielectrics also carry a specular lobe. Sampling only the metallic
        // branch made an otherwise valid nonmetal PBR scene emit no reflected photons.
        float dielectricProbability=gsFresnelSchlick(max(dot(-direction,normal),0.0),vec3(.04)).x;
        float diffuseProbability=opacity*(1.0-transmission)*(1.0-metallic)*(1.0-dielectricProbability);
        float choice=gsTransportRandom(seed^(event+1u)*0x27d4eb2du);
        if(choice>=opacity) { /* surface coverage hole */ }
        else if(choice<diffuseProbability) {
            if(specular && deposits<gsCaustics.words[5] && gsCausticRegion(hit.worldPositionAndDistance.xyz)
                    && !gsUnlit(hit) && !gsLightmapModulated(hit)) {
                float localPolicy=range>0.0?pow(max(1.0-traveled/range,0.0),2.0)*min(traveled*traveled,1.0):1.0;
                // Receiver BRDF includes this lobe's weight; cancel its discrete selection probability.
                gsStorePhoton(index,deposits,hit.worldPositionAndDistance.xyz,hit.mediumBoundary.x,-direction,
                    flux*(localPolicy/diffuseProbability),gsDecodeGeometricNormal(hit.compositeState.w),0u);
            }
            return;
        } else {
            bool specularEvent=true;
            float roughness=clamp(hit.worldNormalAndRoughness.w,0.0,1.0);
            vec2 point=vec2(gsTransportRandom(seed^(event+1u)*0x165667b1u),gsTransportRandom(seed^(event+1u)*0xd3a2646cu));
            vec3 halfVector=gsDielectricHalfVector(normal,direction,roughness,point),outgoing;
            if(choice<opacity*(1.0-transmission)) {
                outgoing=normalize(reflect(direction,halfVector));
                float probability=metallic+(1.0-metallic)*dielectricProbability;
                vec3 reflectance=mix(vec3(.04),clamp(hit.baseColorAndOpacity.rgb,vec3(0),vec3(1)),metallic);
                flux*=gsFresnelSchlick(max(dot(-direction,halfVector),0.0),reflectance)
                    *(gsDielectricBranchWeight(normal,direction,outgoing,roughness)/max(probability,1e-8));
                if(dot(normal,outgoing)<=0.0) return;
            } else {
                vec2 ior=gsInterfaceIors(hit);
                specularEvent=ior.x!=ior.y;
                if(ior.x==ior.y) halfVector=normal;
                vec3 refracted;
                bool canTransmit=gsRefractDirection(direction,halfVector,ior.x/ior.y,refracted);
                float fresnel=canTransmit?gsDielectricFresnel(ior.x,ior.y,max(dot(-direction,halfVector),0.0)):1.0;
                bool reflection=!canTransmit || gsTransportRandom(seed^(event+1u)*0x85ebca6bu)<fresnel;
                outgoing=reflection?normalize(reflect(direction,halfVector)):refracted;
                if(reflection?dot(normal,outgoing)<=0.0:dot(normal,outgoing)>=0.0) return;
                flux*=ior.x==ior.y?1.0:gsDielectricBranchWeight(normal,direction,outgoing,roughness);
                if(!reflection) { flux*=clamp(hit.baseColorAndOpacity.rgb,vec3(0),vec3(1)); }
                // Flux/importance transport has no radiance eta^2 factor.
            }
            direction=outgoing;specular=specular || specularEvent;
        }
        origin=hit.worldPositionAndDistance.xyz+direction*gsRayEpsilon(hit.worldPositionAndDistance.xyz);
    }
}
