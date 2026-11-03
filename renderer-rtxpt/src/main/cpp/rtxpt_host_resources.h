#pragma once
#include <atomic>
#include <algorithm>
#include <cstdint>
#include <chrono>
#include <thread>
#include "rtxpt_diagnostics.h"
namespace RtrHostResources {
inline std::atomic<unsigned> workerThreads{4},shaderCompilerThreads{1};
// The window belongs to the caller thread. Workers must never dispatch its events.
inline thread_local void (*eventPump)()=nullptr;
inline thread_local uint64_t lastPollNanos=0,maximumPollIntervalNanos=0;
inline void NoteEventPoll(){
    uint64_t now=uint64_t(std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count());
    if(lastPollNanos)maximumPollIntervalNanos=std::max(maximumPollIntervalNanos,now-lastPollNanos);lastPollNanos=now;
}
inline void PumpEvents() { if(eventPump){RtrDiagnostics::Scope diagnostic(RtrDiagnostics::MessagePump,0,0,2);eventPump();} }
}
