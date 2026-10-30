#extension GL_EXT_ray_tracing : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require
#extension GL_EXT_buffer_reference : require
#include "gpuscene_common.glsl"

layout(set=0,binding=GPU_SCENE_BINDING_TLAS) uniform accelerationStructureEXT sceneTlas;
layout(location=0) rayPayloadEXT GpuScenePayload payload;
layout(buffer_reference,std430,buffer_reference_align=16) buffer GsNativeBoundaryStorage { uvec4 lanes[]; };
layout(push_constant) uniform GsNativeBoundaryDispatch {
    GsNativeBoundaryStorage storage;
    uint firstPixel;
    uint pixelCount;
    uint64_t payloadAddress;
    uint64_t offsets;
} dispatch;

// This is the same deterministic sample pattern used by the ordinary production raygen.
vec2 gsNativeSubpixelOffset(uint sampleIndex,uint samples) {
    if(samples<=1u) return vec2(.5);
    if(samples==2u) return sampleIndex==0u?vec2(.25,.25):vec2(.75,.75);
    if(samples==4u) { const vec2 offsets[4]=vec2[](vec2(.375,.125),vec2(.875,.375),vec2(.125,.625),vec2(.625,.875));
        return offsets[sampleIndex]; }
    const vec2 offsets[8]=vec2[](vec2(.5000,.3333),vec2(.2500,.6667),vec2(.7500,.1111),vec2(.1250,.4444),
        vec2(.6250,.7778),vec2(.3750,.2222),vec2(.8750,.5556),vec2(.0625,.8889));
    return offsets[sampleIndex];
}

void gsNativeStorePayload(uint item,uvec4 identity) {
    uint stateBase=uint(dispatch.offsets&uint64_t(0xffffffffu))/16u+item*(2u+((dispatch.storage.lanes[6u].w&255u)+1u)/2u);
    dispatch.storage.lanes[stateBase+1u+((dispatch.storage.lanes[6u].w&255u)+1u)/2u]=uvec4(floatBitsToUint(payload.worldPositionAndDistance.w),
            floatBitsToUint(gsFrameCameraPosition()));
    // Direct native terminal radiance is admitted per sample. Retain full payload for features/outlines.
    bool terminal=(dispatch.storage.lanes[6u].w&512u)!=0u
        &&(gsFrame.words[GPU_SCENE_FRAME_TEMPORAL_FLAGS_WORD]&GPU_SCENE_TEMPORAL_FLAG_ENABLED)==0u
        &&(gsFrame.words[GPU_SCENE_FRAME_FEATURE_FLAGS_WORD]
            &(GPU_SCENE_FEATURE_FLAG_DENOISING_ACTIVE|GPU_SCENE_FEATURE_FLAG_RECONSTRUCTION_ACTIVE))==0u
        &&(payload.surfaceState.z==0u||payload.compositeState.x<=0.0)
        &&(payload.state.x==0u||(((payload.state.z>>GPU_SCENE_SHADING_MODEL_SHIFT)&GPU_SCENE_SHADING_MODEL_MASK)==GPU_SCENE_SHADING_UNLIT
            &&((payload.state.z>>GPU_SCENE_OVERLAY_DEPTH_MODE_SHIFT)&GPU_SCENE_OVERLAY_DEPTH_MODE_MASK)==0u));
    vec3 terminalColor=payload.state.x==0u?payload.baseColorAndOpacity.rgb:
        max(payload.baseColorAndOpacity.rgb,vec3(0.0))+max(payload.emissiveAndMetallic.rgb,vec3(0.0));
    uint emissionLane=dispatch.storage.lanes[3u].x/16u+item;
    dispatch.storage.lanes[emissionLane]=floatBitsToUint(vec4(terminalColor,terminal?1.0:0.0));
    if(terminal)return;
    // The final consumer reads the complete primary payload from the Vulkan-owned buffer.
    GsNativeBoundaryStorage destination=GsNativeBoundaryStorage(dispatch.payloadAddress);
    uint globalItem=(dispatch.firstPixel+gl_LaunchIDEXT.x)*clamp(gsFrame.words[GPU_SCENE_FRAME_SAMPLE_COUNT_WORD],1u,8u)
            +identity.y;
    uint destinationBase=globalItem*13u;
    destination.lanes[destinationBase]=floatBitsToUint(payload.worldPositionAndDistance);
    destination.lanes[destinationBase+1u]=floatBitsToUint(payload.previousWorldPosition);
    destination.lanes[destinationBase+2u]=uvec4(payload.motionRevision,0u,0u);
    destination.lanes[destinationBase+3u]=floatBitsToUint(payload.worldNormalAndRoughness);
    destination.lanes[destinationBase+4u]=floatBitsToUint(payload.baseColorAndOpacity);
    destination.lanes[destinationBase+5u]=floatBitsToUint(payload.emissiveAndMetallic);
    destination.lanes[destinationBase+6u]=floatBitsToUint(payload.transmissionIor);
    destination.lanes[destinationBase+7u]=payload.state;
    destination.lanes[destinationBase+8u]=payload.surfaceState;
    destination.lanes[destinationBase+9u]=floatBitsToUint(payload.compositeState);
    destination.lanes[destinationBase+10u]=payload.launchInfo;
    destination.lanes[destinationBase+11u]=payload.mediumBoundary;
    destination.lanes[destinationBase+12u]=identity;
}

void main() {
    if(gl_LaunchIDEXT.x>=dispatch.pixelCount) return;
    ivec2 extent=ivec2(gsFrame.words[GPU_SCENE_FRAME_EXTENT_WORD],gsFrame.words[GPU_SCENE_FRAME_EXTENT_WORD+1u]);
    uint pixelIndex=dispatch.firstPixel+gl_LaunchIDEXT.x;
    ivec2 pixel=ivec2(pixelIndex%uint(extent.x),pixelIndex/uint(extent.x));
    uvec4 launch=uvec4(pixel,extent);
    uint samples=clamp(gsFrame.words[GPU_SCENE_FRAME_SAMPLE_COUNT_WORD],1u,8u);
    uint stateBase=uint(dispatch.offsets&uint64_t(0xffffffffu))/16u;
    vec2 jitter=vec2(uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_CURRENT_JITTER_WORD]),uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_CURRENT_JITTER_WORD+1u]));
    for(uint sampleIndex=0u;sampleIndex<samples;++sampleIndex) {
        uint item=gl_LaunchIDEXT.x*samples+sampleIndex,base=stateBase+item*(2u+((dispatch.storage.lanes[6u].w&255u)+1u)/2u);
        vec2 samplePosition=vec2(pixel)+gsNativeSubpixelOffset(sampleIndex,samples);
        if(!gsExactProjectionActive()) samplePosition+=jitter;
        vec3 origin=gsFrameCameraPosition(),direction=gsRayDirectionAtSample(samplePosition,extent);
        dispatch.storage.lanes[base]=uvec4(floatBitsToUint(direction),0u);
#ifdef RTRENDERER_PBR_PRIMARY_CACHED
        // The scattering producer already shaded this exact frame/sample.
        // Only the camera packet is needed here; its complete primary payload
        // remains in the same Vulkan-owned destination for all later consumers.
        GsNativeBoundaryStorage cached=GsNativeBoundaryStorage(dispatch.payloadAddress);
        uint source=(pixelIndex*samples+sampleIndex)*13u;
        uint terminal=base+1u+((dispatch.storage.lanes[6u].w&255u)+1u)/2u;
        dispatch.storage.lanes[terminal]=uvec4(cached.lanes[source].w,floatBitsToUint(origin));
#else
        float epsilon=max(1e-5,max(abs(origin.x),max(abs(origin.y),abs(origin.z)))*2e-7);
        payload.worldPositionAndDistance=vec4(0,0,0,-1);
        payload.previousWorldPosition=vec4(0);payload.motionRevision=uvec2(0);
        payload.worldNormalAndRoughness=vec4(0,1,0,1);payload.baseColorAndOpacity=vec4(0,0,0,1);
        payload.emissiveAndMetallic=vec4(0);payload.transmissionIor=vec4(0,1,0,0);
        payload.state=uvec4(0u,GS_PAYLOAD_RADIANCE_QUERY,0u,0u);payload.surfaceState=uvec4(0);
        payload.compositeState=vec4(0);payload.launchInfo=launch;payload.mediumBoundary=uvec4(item,0,0,0);
        // The stronger shadow-blocker proof also admits every radiance hit:
        // no interfaces, alpha, sidedness, visibility or overlay rejection.
        // Keep closest-hit payload shading, but avoid redundant any-hit dispatch.
        // Without that proof, collect every volume interface as before.
        uint rayFlags=(gsFrame.words[GPU_SCENE_FRAME_FEATURE_FLAGS_WORD]&GPU_SCENE_FEATURE_FLAG_OPAQUE_SHADOW_RAY)!=0u
            ?gl_RayFlagsOpaqueEXT:gl_RayFlagsNoOpaqueEXT;
        traceRayEXT(sceneTlas,rayFlags,0xff,0,0,0,origin,epsilon,direction,1e20,0);
        gsNativeStorePayload(item,uvec4(pixelIndex,sampleIndex,1u,1u));
#endif
    }
}
