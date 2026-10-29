#ifndef GS_PRIMARY_QUEUE_GLSL
#define GS_PRIMARY_QUEUE_GLSL
// Explicit 16-byte lanes retain the 208-byte wire format without copying a nested
// ray payload aggregate through a physical-storage pointer. Lane 2 includes the
// original std430 uvec2 alignment gap; lane 12 is stable path identity.
const uint GS_PRIMARY_WORK_LANES=13u;
layout(buffer_reference, std430, buffer_reference_align=16) buffer GsPrimaryQueue { uvec4 lanes[]; };
layout(buffer_reference, std430, buffer_reference_align=16) readonly buffer GsPrimaryTransmittance {
    vec4 values[];
};
layout(push_constant) uniform GsPrimaryDispatch {
    GsPrimaryQueue queue;
    uint firstPixel;
    uint pixelCount;
    uint64_t transmittanceAddress;
    uint64_t emissionAddress;
} gsPrimaryDispatch;

bool gsPrimaryNativeLighting() {
    return (gsPrimaryDispatch.transmittanceAddress&uint64_t(4u))!=uint64_t(0u);
}
vec4 gsPrimaryNativeDiffuse(uint item) {
    return GsPrimaryTransmittance(gsPrimaryDispatch.transmittanceAddress&~uint64_t(6u)).values[item];
}
vec4 gsPrimaryNativeSpecular(uint item) {
    return GsPrimaryTransmittance(gsPrimaryDispatch.emissionAddress).values[item];
}

bool gsPrimaryTerminal(uint item) {
    if((gsPrimaryDispatch.transmittanceAddress&uint64_t(2u))==uint64_t(0u))return false;
    return GsPrimaryTransmittance(gsPrimaryDispatch.emissionAddress).values[item].w==1.0;
}
vec3 gsPrimaryTransmittance(uint item) {
    if(gsPrimaryNativeLighting())return vec3(1.0);
    if(gsPrimaryTerminal(item))return vec3(1.0);
    if (gsPrimaryDispatch.transmittanceAddress == 0ul) return vec3(1.0);
    GsPrimaryTransmittance attenuation = GsPrimaryTransmittance(gsPrimaryDispatch.transmittanceAddress&~uint64_t(6u));
    return attenuation.values[item].rgb;
}

vec3 gsPrimaryEmission(uint item) {
    if(gsPrimaryNativeLighting())return vec3(0.0);
    if(gsPrimaryTerminal(item))return vec3(0.0);
    if (gsPrimaryDispatch.emissionAddress == 0ul) return vec3(0.0);
    GsPrimaryTransmittance emission = GsPrimaryTransmittance(gsPrimaryDispatch.emissionAddress);
    return emission.values[item].rgb;
}

void gsStorePrimary(uint item,GpuScenePayload hit,uvec4 identity) {
    uint base=item*GS_PRIMARY_WORK_LANES;
    gsPrimaryDispatch.queue.lanes[base]=floatBitsToUint(hit.worldPositionAndDistance);
    gsPrimaryDispatch.queue.lanes[base+1u]=floatBitsToUint(hit.previousWorldPosition);
    gsPrimaryDispatch.queue.lanes[base+2u]=uvec4(hit.motionRevision,0u,0u);
    gsPrimaryDispatch.queue.lanes[base+3u]=floatBitsToUint(hit.worldNormalAndRoughness);
    gsPrimaryDispatch.queue.lanes[base+4u]=floatBitsToUint(hit.baseColorAndOpacity);
    gsPrimaryDispatch.queue.lanes[base+5u]=floatBitsToUint(hit.emissiveAndMetallic);
    gsPrimaryDispatch.queue.lanes[base+6u]=floatBitsToUint(hit.transmissionIor);
    gsPrimaryDispatch.queue.lanes[base+7u]=hit.state;
    gsPrimaryDispatch.queue.lanes[base+8u]=hit.surfaceState;
    gsPrimaryDispatch.queue.lanes[base+9u]=floatBitsToUint(hit.compositeState);
    gsPrimaryDispatch.queue.lanes[base+10u]=hit.launchInfo;
    gsPrimaryDispatch.queue.lanes[base+11u]=hit.mediumBoundary;
    gsPrimaryDispatch.queue.lanes[base+12u]=identity;
}
GpuScenePayload gsLoadPrimary(uint item) {
    if(gsPrimaryTerminal(item)) {
        GpuScenePayload direct;
        direct.worldPositionAndDistance=vec4(0,0,0,-1);
        direct.previousWorldPosition=vec4(0);direct.motionRevision=uvec2(0);
        direct.worldNormalAndRoughness=vec4(0,1,0,1);
        direct.baseColorAndOpacity=vec4(GsPrimaryTransmittance(gsPrimaryDispatch.emissionAddress).values[item].rgb,1);
        direct.emissiveAndMetallic=vec4(0);direct.transmissionIor=vec4(0,1,0,0);
        direct.state=uvec4(0);direct.surfaceState=uvec4(0);direct.compositeState=vec4(0);
        direct.launchInfo=uvec4(0);direct.mediumBoundary=uvec4(0);return direct;
    }
    uint base=item*GS_PRIMARY_WORK_LANES;
    GpuScenePayload hit;
    hit.worldPositionAndDistance=uintBitsToFloat(gsPrimaryDispatch.queue.lanes[base]);
    hit.previousWorldPosition=uintBitsToFloat(gsPrimaryDispatch.queue.lanes[base+1u]);
    hit.motionRevision=gsPrimaryDispatch.queue.lanes[base+2u].xy;
    hit.worldNormalAndRoughness=uintBitsToFloat(gsPrimaryDispatch.queue.lanes[base+3u]);
    hit.baseColorAndOpacity=uintBitsToFloat(gsPrimaryDispatch.queue.lanes[base+4u]);
    hit.emissiveAndMetallic=uintBitsToFloat(gsPrimaryDispatch.queue.lanes[base+5u]);
    hit.transmissionIor=uintBitsToFloat(gsPrimaryDispatch.queue.lanes[base+6u]);
    hit.state=gsPrimaryDispatch.queue.lanes[base+7u];
    hit.surfaceState=gsPrimaryDispatch.queue.lanes[base+8u];
    hit.compositeState=uintBitsToFloat(gsPrimaryDispatch.queue.lanes[base+9u]);
    hit.launchInfo=gsPrimaryDispatch.queue.lanes[base+10u];
    hit.mediumBoundary=gsPrimaryDispatch.queue.lanes[base+11u];
    return hit;
}

ivec2 gsPrimaryPixel(ivec2 extent) {
    uint index=gsPrimaryDispatch.firstPixel+gl_LaunchIDEXT.x;
    return ivec2(index%uint(extent.x),index/uint(extent.x));
}
#endif
