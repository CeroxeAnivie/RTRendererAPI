#ifndef GS_SURFACE_LIGHTING_RUNTIME_GLSL
#define GS_SURFACE_LIGHTING_RUNTIME_GLSL
#include "gpuscene_visibility.glsl"
#if !defined(RTRENDERER_SURFACE_ONLY) && !defined(RTRENDERER_SCATTERING_OPAQUE_ONLY)
layout(location=1) rayPayloadEXT GsVisibilityPayload visibility;
#endif

struct GsShadowVisibility { vec3 approximate; vec3 diffuse; };
#ifdef RTRENDERER_SCATTERING_QUERY_AUDIT
void gsScatteringQueryAudit(bool matches);
#endif
GsShadowVisibility gsShadowVisibilityDetailed(vec3 origin,vec3 normal,vec3 direction,float maximumDistance,
        bool surfaceReceiver,bool profileAnyHit,out uint64_t probeTicks,out uint64_t fallbackTicks,
        out uint64_t anyHitTicks,out uint anyHitCalls,out uint64_t closestHitTicks,
        out uint closestHitCalls) {
    probeTicks=0ul;fallbackTicks=0ul;anyHitTicks=0ul;anyHitCalls=0u;
    closestHitTicks=0ul;closestHitCalls=0u;
#ifdef RTRENDERER_CAMERA_SCATTERING_PROFILE
    uint64_t probeStarted=clockRealtimeEXT();
#endif
#ifdef RTRENDERER_SURFACE_ONLY
    payload.state=uvec4(0u,GS_PAYLOAD_SHADOW_QUERY,0u,0u);
    traceRayEXT(sceneTlas,gl_RayFlagsTerminateOnFirstHitEXT,255,0,0,0,
        origin+normal*gsRayEpsilon(origin),gsRayEpsilon(origin),direction,maximumDistance,0);
#ifdef RTRENDERER_CAMERA_SCATTERING_PROFILE
    probeTicks=clockRealtimeEXT()-probeStarted;
#endif
    vec3 value=payload.state.x==0u?vec3(1.0):vec3(0.0);
    return GsShadowVisibility(value,value);
#else
#ifdef RTRENDERER_SCATTERING_RAY_QUERY
#ifdef RTRENDERER_SCATTERING_OPAQUE_ONLY
    // The cached-primary program has the same scene proof as this fast path.
    // Resolve it at compile time so no hit-shader payload or fallback survives.
    {
#else
    if((gsFrame.words[GPU_SCENE_FRAME_FEATURE_FLAGS_WORD]&GPU_SCENE_FEATURE_FLAG_OPAQUE_SHADOW_RAY)!=0u) {
#endif
        // NVIDIA ray-query shadow pattern: traverse here without payload or hit/miss shader dispatch.
        float epsilon=gsRayEpsilon(origin);
        rayQueryEXT query;
        rayQueryInitializeEXT(query,sceneTlas,gl_RayFlagsOpaqueEXT|gl_RayFlagsTerminateOnFirstHitEXT,
            255,origin+normal*epsilon,epsilon,direction,maximumDistance);
        // GPUScene TLAS and frame primitives contain triangles only. Opaque
        // triangles commit inside traversal, and first-hit termination completes
        // it in one proceed: there is no candidate for application confirmation.
        rayQueryProceedEXT(query);
        vec3 value=rayQueryGetIntersectionTypeEXT(query,true)==gl_RayQueryCommittedIntersectionNoneEXT
            ?vec3(1.0):vec3(0.0);
#ifdef RTRENDERER_CAMERA_SCATTERING_PROFILE
        probeTicks=clockRealtimeEXT()-probeStarted;
#endif
#ifdef RTRENDERER_SCATTERING_QUERY_AUDIT
        if(profileAnyHit) {
            payload.state=uvec4(0u,GS_PAYLOAD_SHADOW_QUERY,0u,0u);
            traceRayEXT(sceneTlas,gl_RayFlagsTerminateOnFirstHitEXT,255,0,0,0,
                origin+normal*epsilon,epsilon,direction,maximumDistance,0);
            gsScatteringQueryAudit((payload.state.x==0u)==(value.x>0.0));
        }
#endif
        return GsShadowVisibility(value,value);
    }
#endif
#ifndef RTRENDERER_SCATTERING_OPAQUE_ONLY
    if((gsFrame.words[GPU_SCENE_FRAME_FEATURE_FLAGS_WORD]&GPU_SCENE_FEATURE_FLAG_OPAQUE_VISIBILITY)!=0u) {
        payload.state=uvec4(0u,GS_PAYLOAD_SHADOW_QUERY,profileAnyHit?1u:0u,0u);
        traceRayEXT(sceneTlas,gl_RayFlagsTerminateOnFirstHitEXT,255,0,0,0,
            origin+normal*gsRayEpsilon(origin),gsRayEpsilon(origin),direction,maximumDistance,0);
        vec3 value=payload.state.x==0u?vec3(1.0):vec3(0.0);
#ifdef RTRENDERER_CAMERA_SCATTERING_PROFILE
        closestHitTicks=uint64_t(payload.state.z)|(uint64_t(payload.state.w)<<32u);
        closestHitCalls=payload.state.x;
        probeTicks=clockRealtimeEXT()-probeStarted;
#endif
        return GsShadowVisibility(value,value);
    }
    float epsilon=gsRayEpsilon(origin);
    vec3 start=origin+normal*epsilon,throughput=vec3(1.0),diffuse=vec3(1.0);
#ifdef RTRENDERER_CAMERA_SCATTERING_PROFILE
    visibility.normalDistance=vec4(0.0);
#endif
    visibility.boundary=uvec4(GS_VISIBILITY_STATE_OPAQUE_PROBE,profileAnyHit?1u:0u,0u,0u);
    visibility.tintOpacity=vec4(1.0);
    visibility.transmissionIor=vec2(0.0);
    visibility.launchInfo=payload.launchInfo;
    traceRayEXT(sceneTlas,gl_RayFlagsNoOpaqueEXT|gl_RayFlagsSkipClosestHitShaderEXT,
        255,1,0,1,start,epsilon,direction,maximumDistance,1);
#ifdef RTRENDERER_CAMERA_SCATTERING_PROFILE
    anyHitTicks=uint64_t(floatBitsToUint(visibility.normalDistance.x))
        | (uint64_t(floatBitsToUint(visibility.normalDistance.y))<<32u);
    anyHitCalls=visibility.boundary.z;
    probeTicks=clockRealtimeEXT()-probeStarted;
#endif
    if(visibility.tintOpacity.x<=0.0)
        return GsShadowVisibility(vec3(0.0),vec3(0.0));
    if(visibility.boundary.x==GS_VISIBILITY_STATE_OPAQUE_PROBE)
        return GsShadowVisibility(vec3(1.0),vec3(1.0));

    visibility.boundary.y=0u;
    float remaining=maximumDistance;
    uint events=gsFrame.words[GPU_SCENE_FRAME_OPTICAL_MAX_EVENTS_WORD];
#ifdef RTRENDERER_CAMERA_SCATTERING_PROFILE
    uint64_t fallbackStarted=clockRealtimeEXT();
#endif
    [[dont_unroll]] for(uint event=0u;event<events;++event) {
        visibility.normalDistance.w=-1.0;
        visibility.boundary.x=0u;
        visibility.launchInfo=payload.launchInfo;
        traceRayEXT(sceneTlas,gl_RayFlagsNoneEXT,255,1,0,1,start,epsilon,direction,remaining,1);
        GsVisibilityPayload hit=visibility;
        bool missed=hit.normalDistance.w<0.0;
        float distance=missed?remaining:min(hit.normalDistance.w,remaining);
        if(missed || hit.normalDistance.w>=remaining) {
#ifdef RTRENDERER_CAMERA_SCATTERING_PROFILE
            fallbackTicks=clockRealtimeEXT()-fallbackStarted;
#endif
            return GsShadowVisibility(throughput,diffuse);
        }
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
        if(max(throughput.x,max(throughput.y,throughput.z))<=0.0) {
#ifdef RTRENDERER_CAMERA_SCATTERING_PROFILE
            fallbackTicks=clockRealtimeEXT()-fallbackStarted;
#endif
            return GsShadowVisibility(vec3(0.0),vec3(0.0));
        }
        start+=direction*distance;
        epsilon=gsRayEpsilon(start);
        start+=direction*epsilon;
        remaining=max(remaining-distance-epsilon,0.0);
        if(remaining<=epsilon) {
#ifdef RTRENDERER_CAMERA_SCATTERING_PROFILE
            fallbackTicks=clockRealtimeEXT()-fallbackStarted;
#endif
            return GsShadowVisibility(throughput,diffuse);
        }
    }
    // Event exhaustion is conservative visibility, never unoccluded light through unknown geometry.
#ifdef RTRENDERER_CAMERA_SCATTERING_PROFILE
    fallbackTicks=clockRealtimeEXT()-fallbackStarted;
#endif
    return GsShadowVisibility(vec3(0.0),vec3(0.0));
#endif
#endif
}

GsShadowVisibility gsShadowVisibility(vec3 origin,vec3 normal,vec3 direction,float maximumDistance,bool surfaceReceiver) {
    uint64_t ignoredProbeTicks,ignoredFallbackTicks,ignoredAnyHitTicks;
    uint ignoredAnyHitCalls;
    uint64_t ignoredClosestHitTicks;
    uint ignoredClosestHitCalls;
    return gsShadowVisibilityDetailed(origin,normal,direction,maximumDistance,surfaceReceiver,
        false,ignoredProbeTicks,ignoredFallbackTicks,ignoredAnyHitTicks,ignoredAnyHitCalls,
        ignoredClosestHitTicks,ignoredClosestHitCalls);
}

vec3 gsShadowTransmittanceDetailed(vec3 origin,vec3 normal,vec3 direction,float maximumDistance,
        bool profileAnyHit,out uint64_t probeTicks,out uint64_t fallbackTicks,
        out uint64_t anyHitTicks,out uint anyHitCalls,out uint64_t closestHitTicks,
        out uint closestHitCalls) {
    return gsShadowVisibilityDetailed(origin,normal,direction,maximumDistance,false,
        profileAnyHit,probeTicks,fallbackTicks,anyHitTicks,anyHitCalls,
        closestHitTicks,closestHitCalls).approximate;
}

vec3 gsShadowTransmittance(vec3 origin,vec3 normal,vec3 direction,float maximumDistance) {
    return gsShadowVisibility(origin,normal,direction,maximumDistance,false).approximate;
}

#include "gpuscene_lighting.glsl"

#endif
