#pragma once

#include <array>
#include <cstdint>
#include <vector>

#include <vulkan/vulkan.h>
#include <sl.h>

#include "streamline_frame_generation_tracker.hpp"
#include "streamline_input_completion_gate.hpp"
#include "streamline_sdk.hpp"
#include "streamline_types.hpp"

namespace rtrenderer::nvidia {

class StreamlineFrameConstantsPublisher;

/** Owns Streamline presentation and frame-marker state, but never the process SDK lifetime. */
class StreamlineFrameGenerationExecutor final {
public:
    void bind(
            const StreamlineApiBindings& bindings,
            StreamlineFrameConstantsPublisher& frameConstants,
            bool generationEnabled,
            bool lowLatencyEnabled
    ) noexcept;
    void close() noexcept;
    void discard() noexcept;
    void disableGeneration();

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
    std::array<std::int64_t, 18> stats() const noexcept;
    void record(std::uint64_t commandBuffer, const StreamlineFrame& frame);
    std::array<std::uint64_t, 2> awaitInputReuse();
    void beginFramePreparation(std::uint64_t frameSequence);
    void cancelFramePreparation(std::uint64_t frameSequence);
    void beginSubmission(std::uint64_t frameSequence);
    void endSubmission(std::uint64_t frameSequence);

private:
    struct TaggedFrame final {
        std::uint32_t sequence;
        std::uint32_t hudlessWidth;
        std::uint32_t hudlessHeight;
        std::uint32_t renderWidth;
        std::uint32_t renderHeight;
        std::uint32_t hudlessFormat;
        std::uint32_t depthFormat;
        std::uint32_t motionFormat;
    };

    sl::DLSSGState queryState(const char* operation, std::uint64_t observationSequence);
    void configure(std::int32_t generatedFrames, const TaggedFrame& frame);
    void configureOff();
    void disable();
    void releaseFrameTags(std::uint32_t frameIndex);
    void retireTaggedFramesThrough(std::uint32_t frameIndex);
    void releaseAllFrameTags();
    void closeOpenFrameMarkers() noexcept;
    void clearBindings() noexcept;
    void resetState() noexcept;

    PFun_slGetNewFrameToken* getNewFrameToken_ = nullptr;
    PFun_slSetTagForFrame* setTagForFrame_ = nullptr;
    StreamlineFrameConstantsPublisher* frameConstants_ = nullptr;
    PFun_slDLSSGSetOptions* dlssGSetOptions_ = nullptr;
    PFun_slDLSSGGetState* dlssGGetState_ = nullptr;
    PFun_slReflexSleep* reflexSleep_ = nullptr;
    PFun_slPCLSetMarker* pclSetMarker_ = nullptr;
    PFN_vkCreateSwapchainKHR proxyCreateSwapchain_ = nullptr;
    PFN_vkDestroySwapchainKHR proxyDestroySwapchain_ = nullptr;
    PFN_vkGetSwapchainImagesKHR proxyGetSwapchainImages_ = nullptr;
    PFN_vkAcquireNextImageKHR proxyAcquireNextImage_ = nullptr;
    PFN_vkQueuePresentKHR proxyQueuePresent_ = nullptr;
    PFN_vkDeviceWaitIdle deviceWaitIdle_ = nullptr;
    sl::ViewportHandle viewport_{0};
    StreamlineFrameGenerationTracker tracker_;
    StreamlineInputCompletionGate inputCompletion_;
    // Frame-token tags outlive command recording until their matching proxy present. The renderer
    // owns only a bounded frame ring, so this collection remains bounded by submitted frame slots.
    std::vector<TaggedFrame> pendingTaggedFrames_;
    std::uint32_t configuredGeneratedFrames_ = 0;
    std::int32_t configuredCadenceRequest_ = 0;
    std::uint32_t lastRequestedGeneratedFrames_ = 0;
    std::uint64_t lastPresentSequence_ = 0;
    std::uint32_t configuredBackBufferWidth_ = 0;
    std::uint32_t configuredBackBufferHeight_ = 0;
    std::uint32_t configuredRenderWidth_ = 0;
    std::uint32_t configuredRenderHeight_ = 0;
    std::uint32_t configuredBackBufferFormat_ = 0;
    std::uint32_t configuredHudlessFormat_ = 0;
    std::uint32_t configuredDepthFormat_ = 0;
    std::uint32_t configuredMotionFormat_ = 0;
    std::uint32_t backBufferWidth_ = 0;
    std::uint32_t backBufferHeight_ = 0;
    std::uint32_t backBufferFormat_ = 0;
    std::uint32_t backBufferCount_ = 0;
    std::uint64_t preparationMarkerSequence_ = 0;
    std::uint64_t submissionMarkerSequence_ = 0;
    bool generationEnabled_ = false;
    bool lowLatencyEnabled_ = false;
    bool hasLastPresentSequence_ = false;
    bool preparationMarkerOpen_ = false;
    bool submissionMarkerOpen_ = false;
};

}
