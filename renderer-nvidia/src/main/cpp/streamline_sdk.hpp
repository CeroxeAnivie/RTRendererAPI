#pragma once

#include <cstdint>
#include <memory>
#include <string>

#include <vulkan/vulkan.h>
#include <sl.h>
#include <sl_dlss.h>
#include <sl_dlss_g.h>
#include <sl_nis.h>
#include <sl_pcl.h>
#include <sl_reflex.h>

namespace rtrenderer::nvidia {

struct StreamlineVulkanBinding final {
    std::uint64_t instance;
    std::uint64_t physicalDevice;
    std::uint64_t device;
    std::int32_t computeQueueIndex;
    std::int32_t computeQueueFamily;
    std::int32_t graphicsQueueIndex;
    std::int32_t graphicsQueueFamily;
    std::int32_t opticalFlowQueueIndex;
    std::int32_t opticalFlowQueueFamily;
    std::int32_t requiredFeatures;
    bool useNativeOpticalFlowMode;
};

/** Non-owning function table published only after a complete device binding transaction. */
struct StreamlineApiBindings final {
    VkDevice device = VK_NULL_HANDLE;
    PFun_slGetNewFrameToken* getNewFrameToken = nullptr;
    PFun_slSetTagForFrame* setTagForFrame = nullptr;
    PFun_slSetConstants* setConstants = nullptr;
    PFun_slEvaluateFeature* evaluateFeature = nullptr;
    PFun_slFreeResources* freeResources = nullptr;
    PFun_slDLSSGetOptimalSettings* dlssOptimalSettings = nullptr;
    PFun_slDLSSSetOptions* dlssSetOptions = nullptr;
    PFun_slNISSetOptions* nisSetOptions = nullptr;
    PFun_slDLSSGSetOptions* dlssGSetOptions = nullptr;
    PFun_slDLSSGGetState* dlssGGetState = nullptr;
    PFun_slReflexSetOptions* reflexSetOptions = nullptr;
    PFun_slReflexSleep* reflexSleep = nullptr;
    PFun_slPCLSetMarker* pclSetMarker = nullptr;
    PFN_vkCreateSwapchainKHR createSwapchain = nullptr;
    PFN_vkDestroySwapchainKHR destroySwapchain = nullptr;
    PFN_vkGetSwapchainImagesKHR getSwapchainImages = nullptr;
    PFN_vkAcquireNextImageKHR acquireNextImage = nullptr;
    PFN_vkQueuePresentKHR queuePresent = nullptr;
    PFN_vkWaitSemaphores waitSemaphores = nullptr;
    PFN_vkDeviceWaitIdle deviceWaitIdle = nullptr;
};

/** Owns the process-scoped Streamline DLL and the slInit/slShutdown lifecycle. */
class StreamlineSdkSession final {
public:
    StreamlineSdkSession();
    ~StreamlineSdkSession();

    StreamlineSdkSession(const StreamlineSdkSession&) = delete;
    StreamlineSdkSession& operator=(const StreamlineSdkSession&) = delete;

    std::string preflight(std::int32_t requestedFeatures);
    std::int32_t bindVulkan(
            const StreamlineVulkanBinding& binding,
            StreamlineApiBindings& output
    );
    void unloadFrameGeneration();
    void releaseResources(const sl::ViewportHandle& viewport) noexcept;
    void close() noexcept;

    bool initialized() const noexcept;
    std::int32_t requestedFeatures() const noexcept;
    std::int32_t boundFeatures() const noexcept;

private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};

}
