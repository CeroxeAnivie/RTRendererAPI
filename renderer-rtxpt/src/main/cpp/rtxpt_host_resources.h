#pragma once
#include <atomic>
#include <chrono>
#include <thread>
namespace RtrHostResources {
inline std::atomic<unsigned> workerThreads{4},shaderCompilerThreads{1};
// The window belongs to the caller thread. Workers must never dispatch its events.
inline thread_local void (*eventPump)()=nullptr;
inline void PumpEvents() { if(eventPump)eventPump(); }
}
