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
inline thread_local uint64_t lastPollNanos=0,maximumPollIntervalNanos=0,pollEpoch=0;
inline void NoteEventPoll(){
    if(!RtrDiagnostics::level.load(std::memory_order_acquire)){lastPollNanos=0;return;}
    const auto epoch=RtrDiagnostics::generation.load(std::memory_order_acquire);
    if(pollEpoch!=epoch){lastPollNanos=0;pollEpoch=epoch;}
    uint64_t now=RtrDiagnostics::Now();
    if(lastPollNanos)maximumPollIntervalNanos=std::max(maximumPollIntervalNanos,now-lastPollNanos);lastPollNanos=now;
}
inline void PumpEvents() { if(eventPump){RtrDiagnostics::Scope diagnostic(RtrDiagnostics::MessagePump,0,0,2);eventPump();} }
}
