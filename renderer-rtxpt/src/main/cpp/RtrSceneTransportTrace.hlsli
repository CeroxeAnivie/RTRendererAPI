#ifndef RTR_SCENE_TRANSPORT_TRACE_HEADER
#define RTR_SCENE_TRANSPORT_TRACE_HEADER
#ifndef RTR_SCENE_TRANSPORT_TRACE
#define RTR_SCENE_TRANSPORT_TRACE 0
#endif
#ifndef RTR_SCENE_OPTICAL_BRANCH_SEED_XOR
#define RTR_SCENE_OPTICAL_BRANCH_SEED_XOR 0u
#endif
#ifndef RTR_SCENE_RAW_OPTICAL_ROUGHNESS
#define RTR_SCENE_RAW_OPTICAL_ROUGHNESS 0
#endif
#ifndef RTR_SCENE_REPLAY_PREFIX_CACHE
#define RTR_SCENE_REPLAY_PREFIX_CACHE 1
#endif
// Configured before pipeline creation. Later transport keeps its existing estimator.
#ifndef RTR_SCENE_EXACT_INTERFACE_COUNT
#define RTR_SCENE_EXACT_INTERFACE_COUNT 4u
#endif
#if RTR_SCENE_EXACT_INTERFACE_COUNT < 1 || RTR_SCENE_EXACT_INTERFACE_COUNT > 6
#error Exact optical interface budget must be in [1,6]
#endif
#ifndef RTR_SCENE_WORK_TRACE
#define RTR_SCENE_WORK_TRACE 0
#endif
#if RTR_SCENE_WORK_TRACE
// Sparse records in two frames, using upstream ShaderDebug's existing lifetime.
// Does not serialize queues or read final SR inputs back to the CPU.
#define RtrSceneWorkPrint(...) do { ShaderDebug::DebugPrinter p; p.Init(); p.StringSize=0; p.AppendArgs(__VA_ARGS__); p.Commit(); } while(false)
bool RtrSceneWorkPixel(uint2 pixel,uint frame)
{
    return (frame==320u || frame==321u) && all((pixel&127u)==0u);
}
#endif
// Upstream PathFlags occupy bits 10..30; bit 31 is unused in the pinned SDK.
#define RTR_SCENE_OPTICAL_VERTEX_FLAG 0x80000000u
bool RtrSceneReplayPrefixDuplicate(uint control,uint vertex)
{
    uint suffix=control>>3u;
    if(vertex==1)return (control&3u)==2u || suffix!=0;
    if(vertex>RTR_SCENE_EXACT_INTERFACE_COUNT)return false;
    return (suffix&((1u<<(RTR_SCENE_EXACT_INTERFACE_COUNT-vertex+1u))-1u))!=0;
}
#if RTR_SCENE_TRANSPORT_TRACE
// Numeric records also work in release DXIL, without debug string constants.
#define RtrSceneTracePrint(str, ...) do { \
    ShaderDebug::DebugPrinter printer; printer.Init(); \
    printer.StringSize=0; \
    printer.AppendArgs(__VA_ARGS__); printer.Commit(); \
} while(false)
bool RtrSceneTracePixel(uint2 pixel,uint frame)
{
    #ifdef RTR_SCENE_TRACE_ORIGIN
    return frame>=RTR_SCENE_TRACE_FIRST_FRAME && frame<=RTR_SCENE_TRACE_LAST_FRAME
        && all(pixel>=RTR_SCENE_TRACE_ORIGIN) && all(pixel<RTR_SCENE_TRACE_ORIGIN+4u);
    #else
    return frame>=16 && frame<=17 && all((pixel&31u)==0);
    #endif
}
uint RtrSceneTraceBranch(uint control)
{
    return ((control&3u)-1u)*(1u<<(RTR_SCENE_EXACT_INTERFACE_COUNT-1u))+(control>>3u);
}
uint4 RtrSceneTraceKey(uint2 pixel,uint branch,uint vertex,uint frame)
{
    return uint4(pixel,(branch&255u)|(frame<<8u),vertex);
}
#endif
#endif
