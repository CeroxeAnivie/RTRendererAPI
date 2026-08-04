#include "rtxmu_session.hpp"

#include <mutex>
#include <stdexcept>
#include <string>
#include <utility>

#include <vulkan/vulkan.h>
// RTXMU v1.4's Suballocator.h uses std::to_string without including <string>. Keep the SDK
// prerequisite local to this adapter so consumers do not depend on unrelated include order.
#include <rtxmu/VkAccelStructManager.h>

namespace rtrenderer::nvidia {
namespace {

std::mutex processOwnerMutex;
bool processOwnerActive = false;

std::uint64_t requireVulkanHandle(std::uint64_t handle) {
    if (handle == 0) throw std::invalid_argument("RTXMU session received a null Vulkan device handle");
    return handle;
}

/** Serializes access to RTXMU v1.4's process-static Vulkan dispatch table. */
class ProcessLease final {
public:
    ProcessLease() {
        std::scoped_lock lock(processOwnerMutex);
        if (processOwnerActive) {
            throw std::runtime_error(
                    "RTXMU v1.4 exposes process-static Vulkan allocator state; "
                    "only one RTXMU device session may be active"
            );
        }
        processOwnerActive = true;
        acquired_ = true;
    }

    ~ProcessLease() {
        if (!acquired_) return;
        std::scoped_lock lock(processOwnerMutex);
        processOwnerActive = false;
    }

    ProcessLease(const ProcessLease&) = delete;
    ProcessLease& operator=(const ProcessLease&) = delete;

private:
    bool acquired_ = false;
};

}

class RtxmuSession::Impl final {
public:
    Impl(std::uint64_t instance, std::uint64_t physicalDevice, std::uint64_t device)
        : processLease_(),
          device_(reinterpret_cast<VkDevice>(requireVulkanHandle(device))),
          manager_(
                vk::Instance(reinterpret_cast<VkInstance>(requireVulkanHandle(instance))),
                device_,
                vk::PhysicalDevice(reinterpret_cast<VkPhysicalDevice>(requireVulkanHandle(physicalDevice))),
                rtxmu::Level::DISABLED) {
        // The official v1.4 backend caches one process-wide dispatch table. Rebind it for every
        // sequential device session while the process lease prevents concurrent replacement.
        rtxmu::VkDynamicLoader loader;
        rtxmu::VkBlock::getDispatchLoader().init(
                vk::Instance(reinterpret_cast<VkInstance>(instance)), device_, loader
        );
        // Eight MiB keeps high-frequency small-mesh generations tightly packed without forcing
        // the 256 MiB default block size into every independently opened renderer session.
        manager_.Initialize(8U * 1024U * 1024U);
    }

    BuildResult recordBuild(
            std::uint64_t commandBuffer,
            const std::vector<std::uint64_t>& positionAddresses,
            const std::vector<std::uint64_t>& indexAddresses,
            const std::vector<std::uint32_t>& vertexCounts,
            const std::vector<std::uint32_t>& primitiveCounts,
            const std::vector<bool>& opaque
    ) {
        std::scoped_lock lock(mutex_);
        const std::size_t geometryCount = positionAddresses.size();
        if (commandBuffer == 0 || geometryCount == 0 || indexAddresses.size() != geometryCount
                || vertexCounts.size() != geometryCount || primitiveCounts.size() != geometryCount
                || opaque.size() != geometryCount) {
            throw std::invalid_argument("RTXMU BLAS build geometry arrays are invalid");
        }

        std::vector<vk::AccelerationStructureGeometryKHR> geometries(geometryCount);
        std::vector<vk::AccelerationStructureBuildRangeInfoKHR> ranges(geometryCount);
        for (std::size_t index = 0; index < geometryCount; ++index) {
            if (positionAddresses[index] == 0 || indexAddresses[index] == 0
                    || vertexCounts[index] == 0 || primitiveCounts[index] == 0) {
                throw std::invalid_argument("RTXMU BLAS geometry contains a null address or empty range");
            }
            vk::DeviceOrHostAddressConstKHR vertexData{};
            vertexData.deviceAddress = positionAddresses[index];
            vk::DeviceOrHostAddressConstKHR indexData{};
            indexData.deviceAddress = indexAddresses[index];
            vk::DeviceOrHostAddressConstKHR transformData{};
            transformData.deviceAddress = 0;
            vk::AccelerationStructureGeometryTrianglesDataKHR triangles{};
            triangles.setVertexFormat(vk::Format::eR32G32B32Sfloat)
                    .setVertexData(vertexData)
                    .setVertexStride(3U * sizeof(float))
                    .setMaxVertex(vertexCounts[index] - 1U)
                    .setIndexType(vk::IndexType::eUint32)
                    .setIndexData(indexData)
                    .setTransformData(transformData);
            vk::AccelerationStructureGeometryDataKHR geometryData{};
            geometryData.triangles = triangles;
            geometries[index].setGeometryType(vk::GeometryTypeKHR::eTriangles)
                    .setGeometry(geometryData)
                    .setFlags(opaque[index]
                            ? vk::GeometryFlagBitsKHR::eOpaque
                            : vk::GeometryFlagsKHR{});
            ranges[index].setPrimitiveCount(primitiveCounts[index])
                    .setPrimitiveOffset(0)
                    .setFirstVertex(0)
                    .setTransformOffset(0);
        }

        vk::AccelerationStructureBuildGeometryInfoKHR buildInfo{};
        buildInfo.setType(vk::AccelerationStructureTypeKHR::eBottomLevel)
                .setFlags(vk::BuildAccelerationStructureFlagBitsKHR::ePreferFastTrace
                        | vk::BuildAccelerationStructureFlagBitsKHR::eAllowCompaction)
                .setMode(vk::BuildAccelerationStructureModeKHR::eBuild)
                .setGeometryCount(static_cast<std::uint32_t>(geometryCount))
                .setPGeometries(geometries.data());

        vk::AccelerationStructureBuildSizesInfoKHR sizes{};
        device_.getAccelerationStructureBuildSizesKHR(
                vk::AccelerationStructureBuildTypeKHR::eDevice,
                &buildInfo,
                primitiveCounts.data(),
                &sizes,
                rtxmu::VkBlock::getDispatchLoader()
        );
        if (sizes.accelerationStructureSize == 0 || sizes.buildScratchSize == 0) {
            throw std::runtime_error("RTXMU received invalid Vulkan BLAS build sizes");
        }

        vk::CommandBuffer commands(reinterpret_cast<VkCommandBuffer>(commandBuffer));
        vk::MemoryBarrier inputBarrier(
                vk::AccessFlagBits::eTransferWrite,
                vk::AccessFlagBits::eAccelerationStructureReadKHR
        );
        commands.pipelineBarrier(
                vk::PipelineStageFlagBits::eTransfer,
                vk::PipelineStageFlagBits::eAccelerationStructureBuildKHR,
                vk::DependencyFlags{},
                1,
                &inputBarrier,
                0,
                nullptr,
                0,
                nullptr,
                rtxmu::VkBlock::getDispatchLoader()
        );

        const vk::AccelerationStructureBuildRangeInfoKHR* rangeData = ranges.data();
        const vk::AccelerationStructureBuildRangeInfoKHR* rangePointers[] = {rangeData};
        const std::uint32_t* primitiveCountData = primitiveCounts.data();
        const std::uint32_t* primitiveCountPointers[] = {primitiveCountData};
        std::vector<std::uint64_t> ids;
        try {
            manager_.PopulateBuildCommandList(
                    commands, &buildInfo, rangePointers, primitiveCountPointers, 1, ids
            );
            if (ids.size() != 1 || !manager_.IsValid(ids.front())) {
                throw std::runtime_error("RTXMU did not return one tracked acceleration structure");
            }
            manager_.PopulateUAVBarriersCommandList(commands, ids);
            manager_.PopulateCompactionSizeCopiesCommandList(commands, ids);
            return result(ids.front(), sizes.buildScratchSize, false);
        } catch (...) {
            for (std::uint64_t id : ids) {
                if (manager_.IsValid(id)) {
                    try {
                        manager_.RemoveAccelerationStructures(std::vector<std::uint64_t>{id});
                    } catch (...) {
                        // Preserve the originating failure. Remaining pools are still released by
                        // the session owner if RTXMU itself cannot unwind a partial allocation.
                    }
                }
            }
            throw;
        }
    }

    BuildResult recordCompaction(std::uint64_t commandBuffer, std::uint64_t id) {
        std::scoped_lock lock(mutex_);
        if (commandBuffer == 0) throw std::invalid_argument("RTXMU compaction received a null command buffer");
        requireValid(id);
        std::vector<std::uint64_t> ids{id};
        manager_.PopulateCompactionCommandList(
                vk::CommandBuffer(reinterpret_cast<VkCommandBuffer>(commandBuffer)), ids
        );
        if (!manager_.GetCompactionComplete(id)) {
            throw std::runtime_error("RTXMU compaction did not select a completed destination");
        }
        return result(id, 1, true);
    }

    void garbageCollect(std::uint64_t id) {
        std::scoped_lock lock(mutex_);
        requireValid(id);
        manager_.GarbageCollection(std::vector<std::uint64_t>{id});
        executed_ = true;
    }

    void remove(std::uint64_t id) {
        std::scoped_lock lock(mutex_);
        if (!manager_.IsValid(id)) return;
        manager_.RemoveAccelerationStructures(std::vector<std::uint64_t>{id});
    }

    bool executed() const {
        std::scoped_lock lock(mutex_);
        return executed_;
    }

private:
    BuildResult result(std::uint64_t id, std::uint64_t scratchBytes, bool compacted) {
        requireValid(id);
        const vk::AccelerationStructureKHR handle = compacted
                ? manager_.GetAccelerationStructCompacted(id)
                : manager_.GetAccelerationStruct(id);
        const std::uint64_t storageBytes = compacted
                ? manager_.GetCompactedAccelStructSize(id)
                : manager_.GetInitialAccelStructSize(id);
        const std::uint64_t address = manager_.GetDeviceAddress(id);
        if (!handle || address == 0 || storageBytes == 0) {
            throw std::runtime_error("RTXMU returned invalid acceleration-structure ownership metadata");
        }
        return BuildResult{
                id,
                reinterpret_cast<std::uint64_t>(static_cast<VkAccelerationStructureKHR>(handle)),
                address,
                storageBytes,
                scratchBytes
        };
    }

    void requireValid(std::uint64_t id) {
        if (!manager_.IsValid(id)) throw std::invalid_argument("RTXMU acceleration structure id is not live");
    }

    mutable std::mutex mutex_;
    ProcessLease processLease_;
    vk::Device device_;
    rtxmu::VkAccelStructManager manager_;
    bool executed_ = false;
};

RtxmuSession::RtxmuSession(
        std::uint64_t instance,
        std::uint64_t physicalDevice,
        std::uint64_t device
) : impl_(std::make_unique<Impl>(instance, physicalDevice, device)) {
}

RtxmuSession::~RtxmuSession() = default;

RtxmuSession::BuildResult RtxmuSession::recordBuild(
        std::uint64_t commandBuffer,
        const std::vector<std::uint64_t>& positionAddresses,
        const std::vector<std::uint64_t>& indexAddresses,
        const std::vector<std::uint32_t>& vertexCounts,
        const std::vector<std::uint32_t>& primitiveCounts,
        const std::vector<bool>& opaque
) {
    return impl_->recordBuild(
            commandBuffer, positionAddresses, indexAddresses, vertexCounts, primitiveCounts, opaque
    );
}

RtxmuSession::BuildResult RtxmuSession::recordCompaction(std::uint64_t commandBuffer, std::uint64_t id) {
    return impl_->recordCompaction(commandBuffer, id);
}

void RtxmuSession::garbageCollect(std::uint64_t id) {
    impl_->garbageCollect(id);
}

void RtxmuSession::remove(std::uint64_t id) {
    impl_->remove(id);
}

bool RtxmuSession::executed() const {
    return impl_->executed();
}

}
