#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require
#include "gpuscene_medium.glsl"
layout(set=0,binding=GPU_SCENE_BINDING_TLAS) uniform accelerationStructureEXT sceneTlas;
layout(location=0) rayPayloadEXT GpuScenePayload payload;
layout(location=1) rayPayloadInEXT GsShadowQuery gsShadowQuery;
GsMediumPath gsLightingMediumPath;
float gsShadowRemaining;

bool gsShadowVisible(vec3 origin, vec3 normal, vec3 direction, float maximumDistance)
{
    payload.state = uvec4(0u, GS_PAYLOAD_SHADOW_QUERY, 0u, 0u);
    traceRayEXT(
        sceneTlas,
        gl_RayFlagsTerminateOnFirstHitEXT,
        0xff,
        0,
        0,
        0,
        origin + normal * 1.0e-3,
        1.0e-3,
        direction,
        maximumDistance,
        0
    );
    return payload.state.x == 0u;
}

GpuScenePayload gsTraceRadiance(vec3 origin,vec3 direction,uvec4 launchInfo,GsMediumPath mediumPath) {
    payload.state=uvec4(0u,GS_PAYLOAD_RADIANCE_QUERY,0u,0u);
    payload.launchInfo=launchInfo;payload.mediumBoundary=uvec4(0u);
    traceRayEXT(sceneTlas,gl_RayFlagsNoneEXT,0xff,0,0,0,origin,1.0e-3,direction,gsShadowRemaining,0);
    return payload;
}

vec3 gsShadowTransmittance(vec3 origin, vec3 normal, vec3 direction, float maximumDistance) {
    GsMediumPath saved=gsLightingMediumPath;
    GsMediumCoefficients initialMedium=gsCurrentMedium(saved);
    if(gsFrame.words[GPU_SCENE_FRAME_MEDIUM_BOUNDARY_COUNT_WORD]==0u
            && gsFrame.words[GPU_SCENE_FRAME_TRANSMISSIVE_SURFACE_COUNT_WORD]==0u
            && all(lessThanEqual(initialMedium.extinction*initialMedium.density,vec3(0.0))))
        return gsShadowVisible(origin,normal,direction,maximumDistance)?vec3(1.0):vec3(0.0);
    GsMediumPath path=saved;
    vec3 throughput=vec3(1.0);
    vec3 start=origin+normal*1.0e-3;
    // A transport integration budget never shortens the real surface-to-light visibility ray.
    float remaining=maximumDistance;
    float integrationRemaining=uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_MEDIUM_MAX_DISTANCE_WORD]);
    uint limit=clamp(gsFrame.words[GPU_SCENE_FRAME_OPTICAL_MAX_EVENTS_WORD],1u,32u);
    bool completed=false;
    [[dont_unroll]] for(uint event=0u;event<limit;++event) {
        gsShadowRemaining=remaining;
        GpuScenePayload hit=gsTraceRadiance(start,direction,uvec4(0u,0u,1u,1u),path);
        float distance=hit.state.x==0u?remaining:min(hit.worldPositionAndDistance.w,remaining);
        GsMediumCoefficients medium=gsCurrentMedium(path);
        float integrationDistance=min(max(distance,0.0),integrationRemaining);
        throughput*=exp(-max(medium.extinction*medium.density,vec3(0.0))*integrationDistance);
        integrationRemaining=max(integrationRemaining-integrationDistance,0.0);
        if(hit.state.x==0u || hit.worldPositionAndDistance.w>=remaining) { completed=true;break; }
        GsMediumPath next=gsCrossMediumBoundary(path,hit);
        if(next.depth>GS_MEDIUM_PATH_CAPACITY) {throughput=vec3(0.0);break;}
        bool volumeOnly=(hit.mediumBoundary.w&2u)!=0u;
        bool castsShadow=(hit.state.w&GPU_SCENE_FLAG_CASTS_SHADOW)!=0u;
        if(!volumeOnly && castsShadow && ((hit.state.z >> GPU_SCENE_OVERLAY_DEPTH_MODE_SHIFT) & GPU_SCENE_OVERLAY_DEPTH_MODE_MASK)==0u) {
            float opacity=clamp(hit.baseColorAndOpacity.a,0.0,1.0);
            float transmission=clamp(hit.transmissionIor.x,0.0,1.0);
            vec2 iors=gsInterfaceIors(hit,path,next);
            float incidentIor=iors.x,transmittedIor=iors.y;
            float fresnel=gsDielectricFresnel(incidentIor,transmittedIor,
                    abs(dot(direction,hit.worldNormalAndRoughness.xyz)));
            throughput*=vec3(1.0-opacity)+opacity*transmission*(1.0-fresnel)
                    *clamp(hit.baseColorAndOpacity.rgb,vec3(0.0),vec3(1.0));
        }
        if(max(throughput.r,max(throughput.g,throughput.b))<1.0e-6) break;
        path=next;remaining-=distance;
        start=hit.worldPositionAndDistance.xyz+direction*1.0e-3;
    }
    gsLightingMediumPath=saved;
    return completed?throughput:vec3(0.0);
}

float gsHenyeyGreenstein(float cosine, float g) {
    float g2 = g * g;
    float denominator = max(1.0 + g2 - 2.0 * g * clamp(cosine, -1.0, 1.0), 1.0e-12);
    return (1.0 - g2) / (4.0 * GS_PI * denominator * sqrt(denominator));
}

vec3 gsVolumeLight(uint slot,vec3 position,vec3 rayDirection,float anisotropy) {
    uint base=slot*GPU_SCENE_LIGHT_RECORD_WORDS,flags=gsLights.words[base+GPU_SCENE_LIGHT_FLAGS_WORD];
    if((flags&GPU_SCENE_FLAG_ACTIVE)==0u) return vec3(0.0);
    uint type=(flags>>1u)&0x7u;
    vec3 color=vec3(uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_COLOR_WORD]),
        uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_COLOR_WORD+1u]),
        uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_COLOR_WORD+2u]));
    float intensity=uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_INTENSITY_WORD]);
    vec3 direction;float distance=1.0e20,attenuation=1.0;
    if(type==GPU_SCENE_LIGHT_DIRECTIONAL) {
        direction=-normalize(vec3(uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD]),
            uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD+1u]),
            uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD+2u])));
    } else {
        vec3 lightPosition=vec3(gsFiniteDoubleAsFloat(gsLights.words[base+GPU_SCENE_LIGHT_POSITION_X_WORD],gsLights.words[base+GPU_SCENE_LIGHT_POSITION_X_WORD+1u]),
            gsFiniteDoubleAsFloat(gsLights.words[base+GPU_SCENE_LIGHT_POSITION_Y_WORD],gsLights.words[base+GPU_SCENE_LIGHT_POSITION_Y_WORD+1u]),
            gsFiniteDoubleAsFloat(gsLights.words[base+GPU_SCENE_LIGHT_POSITION_Z_WORD],gsLights.words[base+GPU_SCENE_LIGHT_POSITION_Z_WORD+1u]));
        vec3 delta=lightPosition-position;distance=length(delta);
        if(distance<=1.0e-4) return vec3(0.0);
        direction=delta/distance;
        float range=uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_RANGE_WORD]);
        float fade=clamp(1.0-distance/max(range,1.0e-4),0.0,1.0);
        attenuation=fade*fade/max(distance*distance,1.0);
        if(type==GPU_SCENE_LIGHT_SPOT) {
            vec3 spotDirection=normalize(vec3(uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD]),
                uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD+1u]),
                uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_DIRECTION_WORD+2u])));
            float inner=uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_INNER_CONE_WORD]);
            float outer=uintBitsToFloat(gsLights.words[base+GPU_SCENE_LIGHT_OUTER_CONE_WORD]);
            float cone=dot(spotDirection,-direction);
            attenuation*=inner==outer?step(outer,cone):smoothstep(outer,inner,cone);
        }
    }
    if(intensity*attenuation<1.0e-7) return vec3(0.0);
    vec3 visibility=(flags&GPU_SCENE_FLAG_CASTS_SHADOW)!=0u
        ?gsShadowTransmittance(position,direction,direction,max(distance-.002,.001)):vec3(1.0);
    return color*intensity*attenuation*visibility*gsHenyeyGreenstein(dot(rayDirection,direction),anisotropy);
}

vec3 gsIntegrateMedium(vec3 color, GsMediumPath path, float distance, vec3 origin, vec3 direction) {
    if (!(distance > 0.0) || isnan(distance) || isinf(distance)) return color;
    GsMediumCoefficients medium=gsCurrentMedium(path);
    vec3 extinction=max(medium.extinction*medium.density,vec3(0.0));
    vec3 scattering=min(max(medium.scattering*medium.density,vec3(0.0)),extinction);
    vec3 emission=max(medium.emission*medium.density,vec3(0.0));
    if(all(equal(extinction,vec3(0.0))) && all(equal(emission,vec3(0.0)))) return color;
    distance=min(distance,uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_MEDIUM_MAX_DISTANCE_WORD]));
    vec3 transmittance=exp(-extinction*distance);
    vec3 integral=vec3(extinction.x>1.0e-7?(1.0-transmittance.x)/extinction.x:distance,
        extinction.y>1.0e-7?(1.0-transmittance.y)/extinction.y:distance,
        extinction.z>1.0e-7?(1.0-transmittance.z)/extinction.z:distance);
    vec3 radiance=color*transmittance+emission*integral;
    if(all(lessThanEqual(scattering,vec3(1.0e-7)))) return radiance;
    GsMediumPath saved=gsLightingMediumPath;gsLightingMediumPath=path;
    uint steps=clamp(gsFrame.words[GPU_SCENE_FRAME_SCATTERING_STEPS_WORD],1u,64u);
    float stepLength=distance/float(steps);
    vec3 intervalIntegral=vec3(extinction.x>1.0e-7?(1.0-exp(-extinction.x*stepLength))/extinction.x:stepLength,
        extinction.y>1.0e-7?(1.0-exp(-extinction.y*stepLength))/extinction.y:stepLength,
        extinction.z>1.0e-7?(1.0-exp(-extinction.z*stepLength))/extinction.z:stepLength);
    vec3 ambient=gsFrameVec3(GPU_SCENE_FRAME_SKY_COLOR_WORD)*uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_AMBIENT_INTENSITY_WORD]);
    vec3 sunDirection=-normalize(gsFrameVec3(GPU_SCENE_FRAME_SUN_DIRECTION_WORD));
    float sunStrength=uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_SUN_INTENSITY_WORD]);
    [[dont_unroll]] for(uint stepIndex=0u;stepIndex<steps;++stepIndex) {
        float t=(float(stepIndex)+.5)*stepLength;
        vec3 position=origin+direction*t,source=ambient;
        if(sunStrength>1.0e-5) source+=gsFrameVec3(GPU_SCENE_FRAME_SUN_COLOR_WORD)*sunStrength
            *gsHenyeyGreenstein(dot(direction,sunDirection),medium.g)
            *gsShadowTransmittance(position,sunDirection,sunDirection,1.0e20);
        [[dont_unroll]] for(uint slot=0u;slot<gsFrame.words[GPU_SCENE_FRAME_LIGHT_SLOT_UPPER_BOUND_WORD];++slot)
            source+=gsVolumeLight(slot,position,direction,medium.g);
        radiance+=exp(-extinction*(float(stepIndex)*stepLength))*scattering*source*intervalIntegral;
    }
    gsLightingMediumPath=saved;return radiance;
}

#include "gpuscene_lighting.glsl"

void main() {
    gsLightingMediumPath=gsShadowQuery.path;
    if(gsShadowQuery.operation==2u) {
        gsShadowQuery.surfaceRadiance=gsEvaluatePbrSurface(gsShadowQuery.surfaceSample,gsShadowQuery.origin);
    } else if(gsShadowQuery.operation==1u) {
        gsShadowQuery.transmittance=gsIntegrateMedium(gsShadowQuery.incidentRadiance,gsShadowQuery.path,
            gsShadowQuery.maximumDistance,gsShadowQuery.origin,gsShadowQuery.direction);
    } else {
        gsShadowQuery.transmittance=gsShadowTransmittance(gsShadowQuery.origin,gsShadowQuery.normal,
            gsShadowQuery.direction,gsShadowQuery.maximumDistance);
    }
}
