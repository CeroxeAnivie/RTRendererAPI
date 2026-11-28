#pragma once
#include <filesystem>

struct ID3D12DeviceFactory;

namespace rtr::upstream {

// Set only on the native owner during synchronous sample initialization.
// An unbound owner (including the original standalone sample) keeps Donut's defaults.
struct EmbeddedStartup {
    const std::filesystem::path& runtimeBin;
    const std::filesystem::path& pipelineCacheRoot;
    ID3D12DeviceFactory* deviceFactory;
    bool externalPresentation;
};
inline thread_local const EmbeddedStartup* embeddedStartup = nullptr;

} // namespace rtr::upstream
