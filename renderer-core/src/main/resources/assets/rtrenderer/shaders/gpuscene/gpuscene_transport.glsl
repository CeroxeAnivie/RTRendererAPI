#ifndef GS_TRANSPORT_GLSL
#define GS_TRANSPORT_GLSL
#include "gpuscene_common.glsl"
#include "gpuscene_radiance.glsl"
#ifdef RTRENDERER_COMPACT_MEDIA
const uint GS_MEDIUM_PATH_CAPACITY=8u;
#else
const uint GS_MEDIUM_PATH_CAPACITY=32u;
#endif
// Direct indexing avoids expanding eight vector-bank switches throughout the transport call tree.
struct GsMediumPath {
    uint depth; uint mediumSlot;
#ifndef RTRENDERER_SURFACE_ONLY
    uint ids[GS_MEDIUM_PATH_CAPACITY];
#endif
};
#ifndef RTRENDERER_SURFACE_ONLY
// GLSL value parameters copy the complete array even for a one-slot read/write.
// Expand the access at the caller to keep those copies out of the optimizer IR.
#define gsMediumPathGet(path,index) ((path).ids[index])
#define gsMediumPathSet(path,index,slot) ((path).ids[index]=(slot))
#else
#define gsMediumPathGet(path,index) GS_INVALID_SLOT
#define gsMediumPathSet(path,index,slot)
#endif
uint gsSelectMediumInstance(inout GsMediumPath path) {
    uint best=GS_INVALID_SLOT; int priority=(-2147483647-1);
    [[dont_unroll]] for(uint i=0u;i<path.depth && i<GS_MEDIUM_PATH_CAPACITY;++i) {
        uint candidate=gsMediumPathGet(path,i);
        int value=int(gsInstanceWord(candidate,GPU_SCENE_INSTANCE_MEDIUM_BOUNDARY_PRIORITY_WORD));
        if(best==GS_INVALID_SLOT || value>=priority) {best=candidate;priority=value;}
    }
    return best;
}
GsMediumPath gsEmptyMediumPath() {
    GsMediumPath path;path.depth=0u;path.mediumSlot=GS_INVALID_SLOT;
    // Unused entries are never read; do not initialize 32 irrelevant slots per pixel.
    return path;
}
GsMediumPath gsCameraMediumPath() {
    GsMediumPath path=gsEmptyMediumPath();
#ifndef RTRENDERER_SURFACE_ONLY
    path.depth=min(gsFrame.words[GPU_SCENE_FRAME_CAMERA_MEDIUM_COUNT_WORD],GS_MEDIUM_PATH_CAPACITY);
    [[dont_unroll]] for(uint i=0u;i<path.depth;++i)
        gsMediumPathSet(path,i,gsFrame.words[GPU_SCENE_FRAME_CAMERA_MEDIUM_SLOTS_WORD+i]);
    path.mediumSlot=gsSelectMediumInstance(path);
#endif
    return path;
}
struct GsMediumCoefficients { vec3 extinction;vec3 scattering;vec3 emission;float ior;float g; };
GsMediumCoefficients gsCurrentMedium(uint slot) {
#ifdef RTRENDERER_SURFACE_ONLY
    return GsMediumCoefficients(vec3(0),vec3(0),vec3(0),1.0,0.0);
#else
    if(slot==GS_INVALID_SLOT) {
        float density=uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_MEDIUM_DENSITY_WORD]);
        return GsMediumCoefficients(gsFrameVec3(GPU_SCENE_FRAME_MEDIUM_EXTINCTION_WORD)*density,
            gsFrameVec3(GPU_SCENE_FRAME_MEDIUM_SCATTERING_WORD)*density,
            gsFrameVec3(GPU_SCENE_FRAME_MEDIUM_EMISSION_WORD)*density,
            uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_MEDIUM_IOR_WORD]),
            uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_MEDIUM_PHASE_ANISOTROPY_WORD]));
    }
    float density=uintBitsToFloat(gsInstanceWord(slot,GPU_SCENE_INSTANCE_MEDIUM_DENSITY_WORD));
    return GsMediumCoefficients(
        vec3(uintBitsToFloat(gsInstanceWord(slot,GPU_SCENE_INSTANCE_MEDIUM_EXTINCTION_WORD)),
             uintBitsToFloat(gsInstanceWord(slot,GPU_SCENE_INSTANCE_MEDIUM_EXTINCTION_WORD+1u)),
             uintBitsToFloat(gsInstanceWord(slot,GPU_SCENE_INSTANCE_MEDIUM_EXTINCTION_WORD+2u)))*density,
        vec3(uintBitsToFloat(gsInstanceWord(slot,GPU_SCENE_INSTANCE_MEDIUM_SCATTERING_WORD)),
             uintBitsToFloat(gsInstanceWord(slot,GPU_SCENE_INSTANCE_MEDIUM_SCATTERING_WORD+1u)),
             uintBitsToFloat(gsInstanceWord(slot,GPU_SCENE_INSTANCE_MEDIUM_SCATTERING_WORD+2u)))*density,
        vec3(uintBitsToFloat(gsInstanceWord(slot,GPU_SCENE_INSTANCE_MEDIUM_EMISSION_WORD)),
             uintBitsToFloat(gsInstanceWord(slot,GPU_SCENE_INSTANCE_MEDIUM_EMISSION_WORD+1u)),
             uintBitsToFloat(gsInstanceWord(slot,GPU_SCENE_INSTANCE_MEDIUM_EMISSION_WORD+2u)))*density,
        uintBitsToFloat(gsInstanceWord(slot,GPU_SCENE_INSTANCE_MEDIUM_IOR_WORD)),
        uintBitsToFloat(gsInstanceWord(slot,GPU_SCENE_INSTANCE_MEDIUM_PHASE_ANISOTROPY_WORD)));
#endif
}
void gsCrossBoundary(inout GsMediumPath path,uvec4 boundary,bool backFace) {
#ifndef RTRENDERER_SURFACE_ONLY
    if(boundary.w==0u) return;
    bool entering=(boundary.z!=0u)==backFace;
    uint found=GS_INVALID_SLOT;
    [[dont_unroll]] for(uint i=0u;i<path.depth && i<GS_MEDIUM_PATH_CAPACITY;++i)
        if(gsMediumPathGet(path,i)==boundary.x) {found=i;break;}
    if(entering && found==GS_INVALID_SLOT) {
        if(path.depth>=GS_MEDIUM_PATH_CAPACITY) {path.depth=GS_MEDIUM_PATH_CAPACITY+1u;return;}
        gsMediumPathSet(path,path.depth,boundary.x);
        ++path.depth;
    } else if(!entering && found!=GS_INVALID_SLOT) {
        [[dont_unroll]] for(uint i=found;i+1u<path.depth;++i) gsMediumPathSet(path,i,gsMediumPathGet(path,i+1u));
        --path.depth;
    }
    path.mediumSlot=gsSelectMediumInstance(path);
#endif
}
#define gsCrossMediumBoundary(path,hit) gsCrossBoundary(path,(hit).mediumBoundary,(hit).state.y==GS_PAYLOAD_BACK_FACE)
vec2 gsInterfaceIors(GpuScenePayload hit,uint beforeSlot,uint afterSlot) {
    float exterior=gsCurrentMedium(beforeSlot).ior;
    if(hit.mediumBoundary.w!=0u) return vec2(exterior,gsCurrentMedium(afterSlot).ior);
    return hit.state.y==GS_PAYLOAD_BACK_FACE?vec2(hit.transmissionIor.y,exterior):vec2(exterior,hit.transmissionIor.y);
}
float gsDielectricFresnel(float n1,float n2,float cosine) {
    if(n1==n2) return 0.0;
    float ci=clamp(cosine,0.0,1.0),eta=n1/n2;
    if(ci==0.0) return 1.0;
    // Square after the Snell product: eta*eta can overflow even at normal incidence.
    float st=eta*sqrt(max(0.0,(1.0-ci)*(1.0+ci)));
    if(st>=1.0) return 1.0;
    float ct=sqrt(max(0.0,(1.0-st)*(1.0+st)));
    // Normalize indices before forming sums, so finite large indices cannot overflow.
    float scale=max(n1,n2),a=n1/scale,b=n2/scale;
    float ds=a*ci+b*ct,dp=b*ci+a*ct;
    float rs=ds>0.0?(a*ci-b*ct)/ds:1.0;
    float rp=dp>0.0?(b*ci-a*ct)/dp:1.0;
    return clamp(.5*(rs*rs+rp*rp),0.0,1.0);
}
bool gsRefractDirection(vec3 incident,vec3 normal,float eta,out vec3 transmitted) {
    if(eta==1.0) {transmitted=incident;return true;}
    float cosine=clamp(dot(-incident,normal),0.0,1.0);
    vec3 tangent=incident+cosine*normal;
    float largest=max(abs(tangent.x),max(abs(tangent.y),abs(tangent.z)));
    vec3 scaled=largest>0.0?tangent/largest:vec3(0.0);
    float scaledLength=length(scaled),sine=largest*scaledLength,transmittedSine=eta*sine;
    if(transmittedSine>=1.0) {transmitted=vec3(0.0);return false;}
    float transmittedCosine=sqrt(max(0.0,(1.0-transmittedSine)*(1.0+transmittedSine)));
    transmitted=scaledLength>0.0?scaled/scaledLength*transmittedSine-normal*transmittedCosine:-normal;
    transmitted=normalize(transmitted);
    return true;
}
float gsIntegralChannel(float sigma,float distance) {
    float tau=sigma*distance;
    if(tau<1e-3) return distance*(1.0-tau*(.5-tau*(1.0/6.0-tau/24.0)));
    return (1.0-exp(-tau))/sigma;
}
vec3 gsMediumIntegral(vec3 sigma,float distance) {
    return vec3(gsIntegralChannel(sigma.x,distance),gsIntegralChannel(sigma.y,distance),gsIntegralChannel(sigma.z,distance));
}
float gsRayEpsilon(vec3 position) {return max(1e-5,max(abs(position.x),max(abs(position.y),abs(position.z)))*2e-7);}
uint gsTransportHash(uint value) {value^=value>>16u;value*=0x7feb352du;value^=value>>15u;value*=0x846ca68bu;return value^(value>>16u);}
float gsTransportRandom(uint seed) {return float(gsTransportHash(seed)>>8u)*(1.0/16777216.0);}
#endif
