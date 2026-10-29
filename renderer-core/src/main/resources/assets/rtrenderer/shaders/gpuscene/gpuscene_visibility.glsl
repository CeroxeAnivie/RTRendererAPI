#ifndef GS_VISIBILITY_GLSL
#define GS_VISIBILITY_GLSL
#include "gpuscene_common.glsl"
// No material lighting, temporal state, medium stack, or source radiance crosses a query trace.
struct GsVisibilityPayload {
    vec4 normalDistance;
    vec4 tintOpacity;
    vec2 transmissionIor;
    uvec4 boundary;
    uint backFace;
    // Original pixel/full output extent, independent of a tiled or compacted dispatch.
    uvec4 launchInfo;
};
#endif
