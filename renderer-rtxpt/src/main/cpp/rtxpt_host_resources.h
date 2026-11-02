#pragma once
#include <atomic>
namespace RtrHostResources {
inline std::atomic<unsigned> workerThreads{4},shaderCompilerThreads{1};
}
