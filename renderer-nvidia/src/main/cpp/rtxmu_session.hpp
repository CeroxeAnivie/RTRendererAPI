#pragma once

#include <cstdint>
#include <memory>
#include <vector>

namespace rtrenderer::nvidia {

/**
 * Owns one RTXMU device integration and every acceleration structure allocated through it.
 *
 * RTXMU v1.4 keeps part of its Vulkan dispatch state process-wide. The implementation therefore
 * acquires an exclusive process lease for the complete lifetime of this object. Keeping that
 * constraint behind this narrow owner prevents JNI and unrelated NVIDIA SDK integrations from
 * depending on RTXMU's global-state limitation.
 */
class RtxmuSession final {
public:
    struct BuildResult final {
        std::uint64_t id;
        std::uint64_t accelerationStructure;
        std::uint64_t deviceAddress;
        std::uint64_t storageBytes;
        std::uint64_t scratchBytes;
    };

    RtxmuSession(std::uint64_t instance, std::uint64_t physicalDevice, std::uint64_t device);
    ~RtxmuSession();

    RtxmuSession(const RtxmuSession&) = delete;
    RtxmuSession& operator=(const RtxmuSession&) = delete;

    BuildResult recordBuild(
            std::uint64_t commandBuffer,
            const std::vector<std::uint64_t>& positionAddresses,
            const std::vector<std::uint64_t>& indexAddresses,
            const std::vector<std::uint32_t>& vertexCounts,
            const std::vector<std::uint32_t>& primitiveCounts,
            const std::vector<bool>& opaque
    );
    BuildResult recordCompaction(std::uint64_t commandBuffer, std::uint64_t id);
    void garbageCollect(std::uint64_t id);
    void remove(std::uint64_t id);
    bool executed() const;

private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};

}
