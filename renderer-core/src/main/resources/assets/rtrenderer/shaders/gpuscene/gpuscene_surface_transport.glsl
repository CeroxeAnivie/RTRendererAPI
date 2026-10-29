#ifndef GS_SURFACE_TRANSPORT_GLSL
#define GS_SURFACE_TRANSPORT_GLSL
#include "gpuscene_common.glsl"
#include "gpuscene_radiance.glsl"
// Surface-only math. Native CUDA owns participating-medium state and integration.
#ifndef RTRENDERER_NO_DIELECTRIC
vec2 gsInterfaceIors(GpuScenePayload hit) {
    float exterior=1.0;
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
#endif
float gsRayEpsilon(vec3 position) {return max(1e-5,max(abs(position.x),max(abs(position.y),abs(position.z)))*2e-7);}
uint gsTransportHash(uint value) {value^=value>>16u;value*=0x7feb352du;value^=value>>15u;value*=0x846ca68bu;return value^(value>>16u);}
float gsTransportRandom(uint seed) {return float(gsTransportHash(seed)>>8u)*(1.0/16777216.0);}
#endif
