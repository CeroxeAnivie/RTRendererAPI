#ifndef GS_COLLECTED_MATERIAL_DATA
#define GS_COLLECTED_MATERIAL_DATA
// Stage boundary, not a device allocation: one compact event enters, one hit returns.
// No scene copy, shared scratch storage, CPU readback or extra synchronization.
struct GsCollectedMaterialData {
    GpuScenePayload hit;
    uvec4 identity;
    vec4 originAndDistance;
    vec3 direction;
};
#endif
