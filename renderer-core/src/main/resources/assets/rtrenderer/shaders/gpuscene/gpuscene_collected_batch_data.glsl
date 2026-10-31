#ifndef GS_COLLECTED_BATCH_DATA
#define GS_COLLECTED_BATCH_DATA
const uint GS_CONNECTION_BATCH_CAPACITY=8u;
struct GsCollectedEvent {uvec4 identity;vec2 distance;};
struct GsCollectedBatchData {
    GsCollectedEvent events[GS_CONNECTION_BATCH_CAPACITY];
    vec4 originAndMinimum,directionAndMaximum;
    float firstDiscarded;
    uint count;
    bool complete,fallback;
#ifdef RTRENDERER_OPTICAL_PROFILE
    uint64_t diagnosticAddress;
    uint diagnosticRecord;
#endif
};
#endif
