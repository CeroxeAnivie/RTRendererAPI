#ifndef GS_OPTICAL_DIAGNOSTICS
#define GS_OPTICAL_DIAGNOSTICS
#ifdef RTRENDERER_OPTICAL_PROFILE
#extension GL_EXT_shader_realtime_clock : require
#extension GL_EXT_buffer_reference : require
layout(buffer_reference,std430,buffer_reference_align=8) buffer GsOpticalDiagnostic {uint64_t values[];};
bool gsDetailActive(uint stage){return (stage<24u?0u:stage<48u?1u:stage<64u?2u:stage<96u?3u:stage<124u?4u:stage<152u?5u:stage<160u?6u:7u)==RTRENDERER_OPTICAL_PROFILE_GROUP;}
uint64_t gsDetailStart(uint64_t address,uint stage){return address!=0ul&&gsDetailActive(stage)?clockRealtimeEXT():0ul;}
void gsDetailEnd(uint64_t address,uint record,uint stage,uint64_t started){
    if(started==0ul)return;uint64_t ended=clockRealtimeEXT();uint word=(record*184u+stage)*2u;
    GsOpticalDiagnostic target=GsOpticalDiagnostic(address);
    target.values[word]+=ended-started;target.values[word+1u]+=1ul;
}
#define GS_DETAIL_BEGIN(stage) uint detailStage=stage;uint64_t detailStarted=gsDetailStart(payload.diagnosticAddress,stage)
#define GS_DETAIL_NEXT(stage) gsDetailEnd(payload.diagnosticAddress,payload.diagnosticRecord,detailStage,detailStarted);detailStage=stage;detailStarted=gsDetailStart(payload.diagnosticAddress,stage)
#define GS_DETAIL_END() gsDetailEnd(payload.diagnosticAddress,payload.diagnosticRecord,detailStage,detailStarted)
#else
#define GS_DETAIL_BEGIN(stage)
#define GS_DETAIL_NEXT(stage)
#define GS_DETAIL_END()
#endif
#endif
