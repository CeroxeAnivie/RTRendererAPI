#ifndef GS_SURFACE_LIGHTING_RUNTIME_GLSL
#define GS_SURFACE_LIGHTING_RUNTIME_GLSL
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
    if((gsFrame.words[GPU_SCENE_FRAME_FEATURE_FLAGS_WORD]&GPU_SCENE_FEATURE_FLAG_OPAQUE_VISIBILITY)!=0u) {
        payload.state=uvec4(0u,GS_PAYLOAD_SHADOW_QUERY,0u,0u);
        traceRayEXT(sceneTlas,gl_RayFlagsTerminateOnFirstHitEXT,255,0,0,0,
            origin+normal*gsRayEpsilon(origin),gsRayEpsilon(origin),direction,maximumDistance,0);
        vec3 value=payload.state.x==0u?vec3(1.0):vec3(0.0);
    return GsShadowVisibility(value,value);
    }
    float epsilon=gsRayEpsilon(origin);
    vec3 start=origin+normal*epsilon,throughput=vec3(1.0),diffuse=vec3(1.0);
    float remaining=maximumDistance;
    uint events=gsFrame.words[GPU_SCENE_FRAME_OPTICAL_MAX_EVENTS_WORD];
    [[dont_unroll]] for(uint event=0u;event<events;++event) {
        visibility.normalDistance.w=-1.0;
        visibility.launchInfo=payload.launchInfo;
        traceRayEXT(sceneTlas,gl_RayFlagsNoneEXT,255,1,0,1,start,epsilon,direction,remaining,1);
        GsVisibilityPayload hit=visibility;
        bool missed=hit.normalDistance.w<0.0;
        float distance=missed?remaining:min(hit.normalDistance.w,remaining);
        if(missed || hit.normalDistance.w>=remaining) return GsShadowVisibility(throughput,diffuse);
        {
#ifdef RTRENDERER_NO_DIELECTRIC
            vec3 factor=vec3(1.0-hit.tintOpacity.a);
#else
            float n1=1.0,n2=hit.transmissionIor.y;
            if(hit.backFace!=0u) {n2=n1;n1=hit.transmissionIor.y;}
            float fresnel=gsDielectricFresnel(n1,n2,abs(dot(direction,hit.normalDistance.xyz)));
            float opacity=hit.tintOpacity.a;
            vec3 factor=vec3(1.0-opacity)+opacity*hit.transmissionIor.x*(1.0-fresnel)*hit.tintOpacity.rgb;
#endif
            throughput*=factor;
#if defined(RTRENDERER_CAUSTICS) && !defined(RTRENDERER_NO_DIELECTRIC)
            // Replace diffuse straight-through NEE only where the bounded photon estimator applies.
            // Keep the existing specular and volume NEE approximation explicit and separate.
            if(surfaceReceiver && n1!=n2 && gsCausticRegion(origin) && gsCausticRegion(start+direction*distance))
                factor=vec3(1.0-opacity);
#endif
            diffuse*=factor;
        }
        if(max(throughput.x,max(throughput.y,throughput.z))<=0.0) return GsShadowVisibility(vec3(0.0),vec3(0.0));
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

#endif
