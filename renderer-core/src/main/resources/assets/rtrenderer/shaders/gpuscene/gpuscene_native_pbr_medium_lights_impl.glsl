#extension GL_EXT_ray_tracing : require
#ifdef RTRENDERER_PBR_LIGHT_RAY_QUERY
#extension GL_EXT_ray_query : require
#endif
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require
#extension GL_EXT_buffer_reference : require
#define RTRENDERER_NO_DIELECTRIC 1
#define GS_LIGHTING_NO_DIRECT_SURFACE 1
#include "gpuscene_surface_transport.glsl"
#define RTRENDERER_CAUSTICS 1
#include "gpuscene_caustic_map.glsl"
#include "gpuscene_lighting.glsl"
#include "gpuscene_caustic_gather.glsl"
layout(set=0,binding=GPU_SCENE_BINDING_TLAS) uniform accelerationStructureEXT sceneTlas;
#ifndef RTRENDERER_PBR_LIGHT_OPAQUE_ONLY
layout(location=0) rayPayloadEXT GpuScenePayload payload;
#endif
layout(buffer_reference,std430,buffer_reference_align=16) buffer GsPbrStorage {uvec4 lanes[];};
layout(push_constant) uniform GsPbrDispatch {
    GsPbrStorage storage;uint firstPixel;uint pixelCount;uint64_t primaryAddress;uint64_t offsets;
} dispatch;

#ifdef RTRENDERER_PBR_LIGHT_OPAQUE_ONLY
#define GS_PBR_SKIP_CONNECTION break
#else
#define GS_PBR_SKIP_CONNECTION continue
#endif

void main() {
    if(gl_LaunchIDEXT.x>=dispatch.pixelCount)return;
    uint connections=dispatch.storage.lanes[16].x,samples=dispatch.storage.lanes[0].w;
    uint localPixel=gl_LaunchIDEXT.x/connections,light=gl_LaunchIDEXT.x%connections;
    uint paths=dispatch.storage.lanes[16].z,pixel=dispatch.firstPixel+localPixel;
    bool endpointPacket=(dispatch.storage.lanes[6].w&1024u)!=0u;
#ifdef RTRENDERER_PBR_LIGHT_OPAQUE_ONLY
    // This program is selected only for the shared, uniform cached primary proof.
    // Resolve format once, rather than carry optional packet stores through each query.
    if((dispatch.storage.lanes[16].w&7u)!=7u||!endpointPacket||(dispatch.storage.lanes[6].w&255u)!=0u) {
        atomicAdd(dispatch.storage.lanes[7].y,1u);return;
    }
    const bool colorEndpoints=true;
    const bool compactColors=true;
#else
    bool colorEndpoints=(dispatch.storage.lanes[16].w&2u)!=0u;
    bool compactColors=(dispatch.storage.lanes[16].w&4u)!=0u;
#endif
    uint capacity=dispatch.storage.lanes[6].w&255u,stride=endpointPacket?1u:2u+(capacity+1u)/2u;
    uint packetOffset=dispatch.storage.lanes[2].y/16u,colorOffset=dispatch.storage.lanes[16].y/16u;
    GsPbrStorage primary=GsPbrStorage(dispatch.primaryAddress);
    ivec2 extent=ivec2(gsFrame.words[GPU_SCENE_FRAME_EXTENT_WORD],gsFrame.words[GPU_SCENE_FRAME_EXTENT_WORD+1u]);
#ifdef RTRENDERER_PBR_LIGHT_OPAQUE_ONLY
    uint primaryStride=uint(extent.x)*uint(extent.y)*samples;
#else
    uint primaryStride=(dispatch.storage.lanes[16].w&1u)!=0u?uint(extent.x)*uint(extent.y)*samples:1u;
#endif
    for(uint sampleIndex=0u;sampleIndex<samples;++sampleIndex) {
        uint local=localPixel*samples+sampleIndex,source=(pixel*samples+sampleIndex)*(primaryStride==1u?13u:1u),index=(compactColors?0u:paths)+local*connections+light;
        uint packet=packetOffset+index*stride,outLane=colorOffset+index*2u;
        if(!colorEndpoints) {
            if(!endpointPacket)dispatch.storage.lanes[packet]=uvec4(0);
            dispatch.storage.lanes[packet+stride-1u]=uvec4(0);
        }
#ifdef RTRENDERER_PBR_LIGHT_OPAQUE_ONLY
        vec4 resultDiffuse=vec4(0),resultSpecular=vec4(0);
        // Keep failed/occluded connections zero, but publish every result once.
        do {
#else
        dispatch.storage.lanes[outLane]=uvec4(0);dispatch.storage.lanes[outLane+1u]=uvec4(0);
#endif
        vec4 position=uintBitsToFloat(primary.lanes[source]),normalRoughness=uintBitsToFloat(primary.lanes[source+3u*primaryStride]);
        vec4 base=uintBitsToFloat(primary.lanes[source+4u*primaryStride]),emissive=uintBitsToFloat(primary.lanes[source+5u*primaryStride]);
        uvec4 state=primary.lanes[source+7u*primaryStride];
        uint shading=(state.z>>GPU_SCENE_SHADING_MODEL_SHIFT)&GPU_SCENE_SHADING_MODEL_MASK;
        if(light==0u) {
            vec3 localColor=state.x==0u?base.rgb:(shading==GPU_SCENE_SHADING_UNLIT?max(base.rgb,vec3(0))+max(emissive.rgb,vec3(0)):
                max(emissive.rgb,vec3(0))+max(base.rgb,vec3(0))*gsFrameVec3(GPU_SCENE_FRAME_SKY_COLOR_WORD)
                    *uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_AMBIENT_INTENSITY_WORD])*(1.0-clamp(emissive.a,0.0,1.0)));
            if (state.x!=0u && shading!=GPU_SCENE_SHADING_UNLIT && gsCausticRegion(position.xyz)) {
                GpuScenePayload receiver;
                receiver.worldPositionAndDistance=position;
                receiver.previousWorldPosition=uintBitsToFloat(primary.lanes[source+1u*primaryStride]);
                receiver.motionRevision=primary.lanes[source+2u*primaryStride].xy;
                receiver.worldNormalAndRoughness=normalRoughness;
                receiver.baseColorAndOpacity=base;
                receiver.emissiveAndMetallic=emissive;
                receiver.transmissionIor=uintBitsToFloat(primary.lanes[source+6u*primaryStride]);
                receiver.state=state;
                receiver.surfaceState=primary.lanes[source+8u*primaryStride];
                receiver.compositeState=uintBitsToFloat(primary.lanes[source+9u*primaryStride]);
                receiver.launchInfo=primary.lanes[source+10u*primaryStride];
                receiver.mediumBoundary=primary.lanes[source+11u*primaryStride];
                localColor+=gsGatherCaustics(receiver,normalize(gsFrameCameraPosition()-position.xyz));
            }
            // Uniform integration needs the camera distance, not a second endpoint packet.
            // Preserve the origin validation formerly performed by the CUDA packet walk.
            if(colorEndpoints&&(any(isnan(gsFrameCameraPosition()))||any(isinf(gsFrameCameraPosition()))))
                atomicAdd(dispatch.storage.lanes[7].y,1u);
            if(compactColors) {
                // Each CUDA path consumes its own camera lane before writing final diffuse.
                dispatch.storage.lanes[dispatch.storage.lanes[2].w/16u+local]=floatBitsToUint(vec4(localColor,position.w));
            } else {
                dispatch.storage.lanes[colorOffset+local*2u]=floatBitsToUint(vec4(localColor,colorEndpoints?position.w:0.0));
                dispatch.storage.lanes[colorOffset+local*2u+1u]=uvec4(0);
            }
        }
        if(state.x==0u||shading==GPU_SCENE_SHADING_UNLIT)GS_PBR_SKIP_CONNECTION;
        vec3 direction,color;float distance=1e20,intensity,attenuation=1.0;bool shadow;
        if(light==0u) {
            direction=-normalize(gsFrameVec3(GPU_SCENE_FRAME_SUN_DIRECTION_WORD));
            color=gsFrameVec3(GPU_SCENE_FRAME_SUN_COLOR_WORD);intensity=uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_SUN_INTENSITY_WORD]);shadow=true;
        }else {
            uint lightBase=(light-1u)*GPU_SCENE_LIGHT_RECORD_WORDS,flags=gsLights.words[lightBase+GPU_SCENE_LIGHT_FLAGS_WORD];
            if((flags&GPU_SCENE_FLAG_ACTIVE)==0u)GS_PBR_SKIP_CONNECTION;uint type=(flags>>1u)&7u;
            color=vec3(uintBitsToFloat(gsLights.words[lightBase+GPU_SCENE_LIGHT_COLOR_WORD]),
                uintBitsToFloat(gsLights.words[lightBase+GPU_SCENE_LIGHT_COLOR_WORD+1u]),uintBitsToFloat(gsLights.words[lightBase+GPU_SCENE_LIGHT_COLOR_WORD+2u]));
            intensity=uintBitsToFloat(gsLights.words[lightBase+GPU_SCENE_LIGHT_INTENSITY_WORD]);shadow=(flags&GPU_SCENE_FLAG_CASTS_SHADOW)!=0u;
            if(type==GPU_SCENE_LIGHT_DIRECTIONAL)direction=-normalize(vec3(uintBitsToFloat(gsLights.words[lightBase+GPU_SCENE_LIGHT_DIRECTION_WORD]),
                uintBitsToFloat(gsLights.words[lightBase+GPU_SCENE_LIGHT_DIRECTION_WORD+1u]),uintBitsToFloat(gsLights.words[lightBase+GPU_SCENE_LIGHT_DIRECTION_WORD+2u])));
            else {
                vec3 point=vec3(gsFiniteDoubleAsFloat(gsLights.words[lightBase+GPU_SCENE_LIGHT_POSITION_X_WORD],gsLights.words[lightBase+GPU_SCENE_LIGHT_POSITION_X_WORD+1u]),
                    gsFiniteDoubleAsFloat(gsLights.words[lightBase+GPU_SCENE_LIGHT_POSITION_Y_WORD],gsLights.words[lightBase+GPU_SCENE_LIGHT_POSITION_Y_WORD+1u]),
                    gsFiniteDoubleAsFloat(gsLights.words[lightBase+GPU_SCENE_LIGHT_POSITION_Z_WORD],gsLights.words[lightBase+GPU_SCENE_LIGHT_POSITION_Z_WORD+1u]));
                vec3 delta=point-position.xyz;distance=length(delta);if(distance<=1e-4)GS_PBR_SKIP_CONNECTION;direction=delta/distance;
                float range=uintBitsToFloat(gsLights.words[lightBase+GPU_SCENE_LIGHT_RANGE_WORD]),fade=clamp(1.0-distance/max(range,1e-4),0.0,1.0);
                attenuation=fade*fade/max(distance*distance,1.0);
                if(type==GPU_SCENE_LIGHT_SPOT) {
                    vec3 axis=normalize(vec3(uintBitsToFloat(gsLights.words[lightBase+GPU_SCENE_LIGHT_DIRECTION_WORD]),
                        uintBitsToFloat(gsLights.words[lightBase+GPU_SCENE_LIGHT_DIRECTION_WORD+1u]),uintBitsToFloat(gsLights.words[lightBase+GPU_SCENE_LIGHT_DIRECTION_WORD+2u])));
                    float inner=uintBitsToFloat(gsLights.words[lightBase+GPU_SCENE_LIGHT_INNER_CONE_WORD]),outer=uintBitsToFloat(gsLights.words[lightBase+GPU_SCENE_LIGHT_OUTER_CONE_WORD]);
                    attenuation*=inner==outer?step(outer,dot(axis,-direction)):smoothstep(outer,inner,dot(axis,-direction));
                }
            }
        }
        vec3 normal=normalize(normalRoughness.xyz),radiance=color*(intensity*attenuation);
        if(dot(normal,direction)<=0.0||max(max(radiance.x,radiance.y),radiance.z)<=0.0)GS_PBR_SKIP_CONNECTION;
        GsRadianceSplit brdf=gsEvaluateBrdf(normal,normalize(gsFrameCameraPosition()-position.xyz),direction,
            max(base.rgb,vec3(0)),clamp(normalRoughness.w,.025,1.0),clamp(emissive.a,0.0,1.0));
        float epsilon=gsRayEpsilon(position.xyz);vec3 origin=position.xyz+normal*epsilon;
        if(!colorEndpoints) {
            if(!endpointPacket)dispatch.storage.lanes[packet]=uvec4(floatBitsToUint(direction),0u);
            dispatch.storage.lanes[packet+stride-1u]=uvec4(floatBitsToUint(shadow?distance:-1.0),floatBitsToUint(origin));
        }
        if(shadow) {
#ifdef RTRENDERER_PBR_LIGHT_RAY_QUERY
#ifndef RTRENDERER_PBR_LIGHT_OPAQUE_ONLY
            if((gsFrame.words[GPU_SCENE_FRAME_FEATURE_FLAGS_WORD]&GPU_SCENE_FEATURE_FLAG_OPAQUE_SHADOW_RAY)!=0u) {
#endif
                // The scene proof excludes medium interfaces, alpha decisions,
                // overlays, single-sided and non-shadow-casting instances. Thus
                // every candidate is a blocking triangle and no boundary event
                // or hit/miss shader invocation is needed for this connection.
                rayQueryEXT query;
                rayQueryInitializeEXT(query,sceneTlas,gl_RayFlagsOpaqueEXT|gl_RayFlagsTerminateOnFirstHitEXT,
                    0xff,origin,epsilon,direction,max(distance-2.0*epsilon,epsilon));
                rayQueryProceedEXT(query);
                if(rayQueryGetIntersectionTypeEXT(query,true)!=gl_RayQueryCommittedIntersectionNoneEXT)GS_PBR_SKIP_CONNECTION;
#ifndef RTRENDERER_PBR_LIGHT_OPAQUE_ONLY
            } else
#endif
#endif
#ifndef RTRENDERER_PBR_LIGHT_OPAQUE_ONLY
            {
            payload.state=uvec4(0u,GS_PAYLOAD_SHADOW_QUERY,0u,0u);
            payload.launchInfo=uvec4(pixel%uint(extent.x),pixel/uint(extent.x),extent);
            payload.mediumBoundary=uvec4(index,0,0,0);
            // Geometry remains queryable beyond the medium integration-distance budget.
            traceRayEXT(sceneTlas,gl_RayFlagsNoOpaqueEXT,0xff,0,0,0,origin,epsilon,direction,max(distance-2.0*epsilon,epsilon),0);
            if(payload.state.x!=0u)GS_PBR_SKIP_CONNECTION;
            }
#endif
        }
        if(colorEndpoints&&shadow&&(any(isnan(origin))||any(isinf(origin))))
            atomicAdd(dispatch.storage.lanes[7].y,1u);
#ifdef RTRENDERER_PBR_LIGHT_OPAQUE_ONLY
        resultDiffuse=vec4(brdf.diffuse*radiance,distance);
        resultSpecular=vec4(brdf.specular*radiance,colorEndpoints?(shadow?distance:-1.0):distance);
        } while(false);
        dispatch.storage.lanes[outLane]=floatBitsToUint(resultDiffuse);
        dispatch.storage.lanes[outLane+1u]=floatBitsToUint(resultSpecular);
#else
        dispatch.storage.lanes[outLane]=floatBitsToUint(vec4(brdf.diffuse*radiance,distance));
        dispatch.storage.lanes[outLane+1u]=floatBitsToUint(vec4(brdf.specular*radiance,colorEndpoints?(shadow?distance:-1.0):distance));
#endif
    }
}
