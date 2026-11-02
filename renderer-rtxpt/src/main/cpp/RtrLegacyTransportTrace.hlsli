#ifndef RTR_LEGACY_TRANSPORT_TRACE_HEADER
#define RTR_LEGACY_TRANSPORT_TRACE_HEADER
#ifndef RTR_LEGACY_TRANSPORT_TRACE
#define RTR_LEGACY_TRANSPORT_TRACE 0
#endif
#if RTR_LEGACY_TRANSPORT_TRACE
// Numeric records also work in release DXIL, without debug string constants.
#define RtrLegacyTracePrint(str, ...) do { \
    ShaderDebug::DebugPrinter printer; printer.Init(); \
    printer.StringSize=0; \
    printer.AppendArgs(__VA_ARGS__); printer.Commit(); \
} while(false)
bool RtrLegacyTracePixel(uint2 pixel,uint frame)
{
    return frame>=16 && frame<=17 && (pixel.x&63u)==0 && (pixel.y&63u)==32u;
}
uint4 RtrLegacyTraceKey(uint2 pixel,uint branch,uint vertex,uint frame)
{
    return uint4(pixel,(branch&3u)|(frame<<8u),vertex);
}
#endif
#endif
