#ifndef GS_CAUSTIC_MAP_GLSL
#define GS_CAUSTIC_MAP_GLSL
#ifdef RTRENDERER_CAUSTICS
layout(set=0,binding=GPU_SCENE_BINDING_CAUSTIC_PHOTONS,std430)
#ifndef RTRENDERER_CAUSTIC_PASS
readonly
#endif
buffer GsCausticWords { uint words[]; } gsCaustics;

bool gsCausticRegion(vec3 point) {
    vec3 delta=point-gsFrameVec3(GPU_SCENE_FRAME_CAUSTIC_CENTER_WORD);
    float radius=uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_CAUSTIC_REGION_RADIUS_WORD]);
    return gsFrame.words[GPU_SCENE_FRAME_CAUSTIC_PHOTONS_WORD]>0u && dot(delta,delta)<radius*radius;
}
ivec3 gsCausticCell(vec3 position) {
    return ivec3(floor((position-gsFrameVec3(GPU_SCENE_FRAME_CAUSTIC_CENTER_WORD))
        /uintBitsToFloat(gsFrame.words[GPU_SCENE_FRAME_CAUSTIC_GATHER_RADIUS_WORD])));
}
uint gsCausticHash(ivec3 cell) {
    return gsTransportHash(uint(cell.x)*0x8da6b343u ^ uint(cell.y)*0xd8163841u ^ uint(cell.z)*0xcb1ab31fu);
}
vec3 gsCausticRecordVec3(uint base) {
    return vec3(uintBitsToFloat(gsCaustics.words[base]),uintBitsToFloat(gsCaustics.words[base+1u]),
        uintBitsToFloat(gsCaustics.words[base+2u]));
}
vec3 gsGatherCaustics(GpuScenePayload receiver,vec3 view);
#endif
#endif
