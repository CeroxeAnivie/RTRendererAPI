#pragma once

#include <array>
#include <cstdint>
#include <mutex>
#include <string>

#include <vulkan/vulkan.h>

#include "streamline_frame_generation_executor.hpp"
#include "streamline_frame_constants_publisher.hpp"
#include "streamline_reconstruction_executor.hpp"
#include "streamline_sdk.hpp"
#include "streamline_types.hpp"

namespace rtrenderer::nvidia {

/** Serializes the process Streamline lease and delegates feature work to narrow executors. */
class StreamlineRuntime final {
public:
    std::string preflight(std::int32_t requestedFeatures);
    void close() noexcept;
    std::string diagnostic();

    std::int32_t setVulkanInfo(const StreamlineVulkanBinding& binding);
    std::int32_t executionFeatureMask();

    VkResult createSwapchain(VkDevice device, const VkSwapchainCreateInfoKHR* createInfo, VkSwapchainKHR* swapchain);
    void destroySwapchain(VkDevice device, VkSwapchainKHR swapchain);
    VkResult getSwapchainImages(VkDevice device, VkSwapchainKHR swapchain, uint32_t* count, VkImage* images);
    VkResult acquireNextImage(
            VkDevice device,
            VkSwapchainKHR swapchain,
            uint64_t timeout,
            VkSemaphore semaphore,
            VkFence fence,
            uint32_t* imageIndex
    );
    VkResult queuePresent(
            VkQueue queue,
            const VkPresentInfoKHR* presentInfo,
            std::int32_t generatedFrames,
            std::uint64_t frameSequence
    );
    void retireFrame(std::uint64_t frameSequence);
    std::array<std::int64_t, 18> frameGenerationStats();
    void disableFrameGeneration();

    void recordDlss(
            std::uint64_t commandBuffer,
            std::int32_t reconstructionMode,
            std::int32_t quality,
            const StreamlineFrame& frame
    );
    void recordNis(std::uint64_t commandBuffer, std::int32_t quality, const StreamlineFrame& frame);
    void recordFrameGeneration(std::uint64_t commandBuffer, const StreamlineFrame& frame);
    std::array<std::uint64_t, 2> awaitFrameInputReuse(std::uint64_t frameSequence);
    void beginFramePreparation(std::uint64_t frameSequence);
    void cancelFramePreparation(std::uint64_t frameSequence);
    void beginFrameSubmission(std::uint64_t frameSequence);
    void endFrameSubmission(std::uint64_t frameSequence);
    std::array<std::int32_t, 2> dlssOptimalSettings(
            std::int32_t quality,
            std::int32_t outputWidth,
            std::int32_t outputHeight
    );

private:
    void closeLocked() noexcept;

    std::mutex mutex_;
    StreamlineSdkSession sdk_;
    StreamlineFrameConstantsPublisher frameConstants_;
    StreamlineReconstructionExecutor reconstruction_;
    StreamlineFrameGenerationExecutor frameGeneration_;
    sl::ViewportHandle viewport_{0};
    std::int32_t preflightFeatures_ = 0;
    std::int32_t executionFeatures_ = 0;
    bool initialized_ = false;
    bool frameGenerationStatsBound_ = false;
    std::string preflightPayload_;
};

StreamlineRuntime& streamlineRuntime();

}
