#ifndef RTR_LEGACY_TRANSPORT_TRACE_HEADER
#define RTR_LEGACY_TRANSPORT_TRACE_HEADER
#ifndef RTR_LEGACY_TRANSPORT_TRACE
#define RTR_LEGACY_TRANSPORT_TRACE 0
#endif
#ifndef RTR_LEGACY_OPTICAL_BRANCH_SEED_XOR
#define RTR_LEGACY_OPTICAL_BRANCH_SEED_XOR 0u
#endif
#ifndef RTR_LEGACY_RAW_OPTICAL_ROUGHNESS
#define RTR_LEGACY_RAW_OPTICAL_ROUGHNESS 0
#endif
#ifndef RTR_LEGACY_REPLAY_PREFIX_CACHE
#define RTR_LEGACY_REPLAY_PREFIX_CACHE 1
#endif
// Six exact junctions suppress the dominant glass branch variance without
// expanding the native payload. Later transport keeps its existing estimator.
#define RTR_LEGACY_EXACT_INTERFACE_COUNT 6u
// Upstream PathFlags occupy bits 10..30; bit 31 is unused in the pinned SDK.
#define RTR_LEGACY_OPTICAL_VERTEX_FLAG 0x80000000u
bool RtrLegacyReplayPrefixDuplicate(uint control,uint vertex)
{
    uint suffix=control>>3u;
    if(vertex==1)return (control&3u)==2u || suffix!=0;
    if(vertex>RTR_LEGACY_EXACT_INTERFACE_COUNT)return false;
    return (suffix&((1u<<(RTR_LEGACY_EXACT_INTERFACE_COUNT-vertex+1u))-1u))!=0;
}
#if RTR_LEGACY_TRANSPORT_TRACE
// Numeric records also work in release DXIL, without debug string constants.
#define RtrLegacyTracePrint(str, ...) do { \
    ShaderDebug::DebugPrinter printer; printer.Init(); \
    printer.StringSize=0; \
    printer.AppendArgs(__VA_ARGS__); printer.Commit(); \
} while(false)
bool RtrLegacyTracePixel(uint2 pixel,uint frame)
{
    #ifdef RTR_LEGACY_TRACE_ORIGIN
    return frame>=RTR_LEGACY_TRACE_FIRST_FRAME && frame<=RTR_LEGACY_TRACE_LAST_FRAME
        && all(pixel>=RTR_LEGACY_TRACE_ORIGIN) && all(pixel<RTR_LEGACY_TRACE_ORIGIN+4u);
    #else
    return frame>=16 && frame<=17 && all((pixel&31u)==0);
    #endif
}
uint RtrLegacyTraceBranch(uint control)
{
    return ((control&3u)-1u)*(1u<<(RTR_LEGACY_EXACT_INTERFACE_COUNT-1u))+(control>>3u);
}
uint4 RtrLegacyTraceKey(uint2 pixel,uint branch,uint vertex,uint frame)
{
    return uint4(pixel,(branch&255u)|(frame<<8u),vertex);
}
#endif
#endif
