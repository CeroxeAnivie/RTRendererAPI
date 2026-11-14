#include "streamline_runtime.hpp"

#include <limits>
#include <stdexcept>

#include <sl.h>

#include "streamline_diagnostics.hpp"
#include "streamline_types.hpp"

namespace rtrenderer::nvidia {

std::string StreamlineRuntime::preflight(std::int32_t requestedFeatures) {
    std::scoped_lock lock(mutex_);
    // Repeating slShutdown/slInit for an identical plan races vendor worker teardown and adds no
    // capability information. Reuse only the exact successful payload.
    if (initialized_ && preflightFeatures_ == requestedFeatures && !preflightPayload_.empty()) {
        return preflightPayload_;
    }
    closeLocked();
    const std::string result = sdk_.preflight(requestedFeatures);
    initialized_ = sdk_.initialized();
    preflightFeatures_ = sdk_.requestedFeatures();
    executionFeatures_ = 0;
    preflightPayload_ = initialized_ ? result : std::string{};
    return result;
}

void StreamlineRuntime::close() noexcept {
    std::scoped_lock lock(mutex_);
    closeLocked();
}

std::string StreamlineRuntime::diagnostic() {
    std::scoped_lock lock(mutex_);
    return currentStreamlineDiagnostic();
}

std::int32_t StreamlineRuntime::setVulkanInfo(const StreamlineVulkanBinding& binding) {
    std::scoped_lock lock(mutex_);
    StreamlineApiBindings bindings{};
    const std::int32_t result = sdk_.bindVulkan(binding, bindings);
    if (result != static_cast<std::int32_t>(sl::Result::eOk)) {
        reconstruction_.clear();
        frameGeneration_.discard();
        frameConstants_.clear();
        frameGenerationStatsBound_ = false;
        initialized_ = false;
        preflightFeatures_ = 0;
        executionFeatures_ = 0;
        return result;
    }
    executionFeatures_ = sdk_.boundFeatures();
    frameConstants_.bind(bindings);
    reconstruction_.bind(bindings, frameConstants_);
    frameGeneration_.bind(
            bindings,
            frameConstants_,
            (executionFeatures_ & kStreamlineFrameGeneration) != 0,
            (executionFeatures_ & (kStreamlineReflex | kStreamlinePcl))
                    == (kStreamlineReflex | kStreamlinePcl)
    );
    frameGenerationStatsBound_ = (executionFeatures_ & kStreamlineFrameGeneration) != 0;
    return result;
}

std::int32_t StreamlineRuntime::executionFeatureMask() {
    std::scoped_lock lock(mutex_);
    return executionFeatures_;
}

VkResult StreamlineRuntime::createSwapchain(
        VkDevice device,
        const VkSwapchainCreateInfoKHR* createInfo,
        VkSwapchainKHR* swapchain
) {
    std::scoped_lock lock(mutex_);
    return frameGeneration_.createSwapchain(device, createInfo, swapchain);
}

void StreamlineRuntime::destroySwapchain(VkDevice device, VkSwapchainKHR swapchain) {
    std::scoped_lock lock(mutex_);
    frameGeneration_.destroySwapchain(device, swapchain);
}

VkResult StreamlineRuntime::getSwapchainImages(
        VkDevice device,
        VkSwapchainKHR swapchain,
        uint32_t* count,
        VkImage* images
) {
    std::scoped_lock lock(mutex_);
    return frameGeneration_.getSwapchainImages(device, swapchain, count, images);
}

VkResult StreamlineRuntime::acquireNextImage(
        VkDevice device,
        VkSwapchainKHR swapchain,
        uint64_t timeout,
        VkSemaphore semaphore,
        VkFence fence,
        uint32_t* imageIndex
) {
    std::scoped_lock lock(mutex_);
    return frameGeneration_.acquireNextImage(device, swapchain, timeout, semaphore, fence, imageIndex);
}

VkResult StreamlineRuntime::queuePresent(
        VkQueue queue,
        const VkPresentInfoKHR* presentInfo,
        std::int32_t generatedFrames,
        std::uint64_t frameSequence
) {
    std::scoped_lock lock(mutex_);
    return frameGeneration_.queuePresent(queue, presentInfo, generatedFrames, frameSequence);
}

void StreamlineRuntime::retireFrame(std::uint64_t frameSequence) {
    std::scoped_lock lock(mutex_);
    frameGeneration_.retireFrame(frameSequence);
}

std::array<std::int64_t, 18> StreamlineRuntime::frameGenerationStats() {
    std::scoped_lock lock(mutex_);
    if (!frameGenerationStatsBound_) {
        throw std::runtime_error("Streamline frame-generation statistics are not bound to a device session");
    }
    return frameGeneration_.stats();
}

void StreamlineRuntime::disableFrameGeneration() {
    std::scoped_lock lock(mutex_);
    // Disable through the still-valid plugin function table before unloading DLSS-G. Reflex/PCL
    // bindings remain borrowed by an independent low-latency request after this transition.
    frameGeneration_.disableGeneration();
    sdk_.unloadFrameGeneration();
    executionFeatures_ &= ~kStreamlineFrameGeneration;
}

void StreamlineRuntime::recordDlss(
        std::uint64_t commandBuffer,
        std::int32_t reconstructionMode,
        std::int32_t quality,
        const StreamlineFrame& frame
) {
    std::scoped_lock lock(mutex_);
    if (!initialized_) throw std::runtime_error("Streamline DLSS runtime is not initialized");
    reconstruction_.recordDlss(commandBuffer, reconstructionMode, quality, frame);
}

void StreamlineRuntime::recordNis(
        std::uint64_t commandBuffer,
        std::int32_t quality,
        const StreamlineFrame& frame
) {
    std::scoped_lock lock(mutex_);
    if (!initialized_) throw std::runtime_error("Streamline NIS runtime is not initialized");
    reconstruction_.recordNis(commandBuffer, quality, frame);
}

void StreamlineRuntime::recordFrameGeneration(
        std::uint64_t commandBuffer,
        const StreamlineFrame& frame
) {
    std::scoped_lock lock(mutex_);
    frameGeneration_.record(commandBuffer, frame);
}

std::array<std::uint64_t, 2> StreamlineRuntime::awaitFrameInputReuse(std::uint64_t frameSequence) {
    std::scoped_lock lock(mutex_);
    if (!initialized_) throw std::runtime_error("Streamline frame-generation runtime is not initialized");
    if (frameSequence > std::numeric_limits<std::uint32_t>::max()) {
        throw std::invalid_argument("Streamline input-reuse sequence exceeds the frame-token ABI");
    }
    return frameGeneration_.awaitInputReuse();
}

void StreamlineRuntime::beginFramePreparation(std::uint64_t frameSequence) {
    std::scoped_lock lock(mutex_);
    frameGeneration_.beginFramePreparation(frameSequence);
}

void StreamlineRuntime::cancelFramePreparation(std::uint64_t frameSequence) {
    std::scoped_lock lock(mutex_);
    frameGeneration_.cancelFramePreparation(frameSequence);
}

void StreamlineRuntime::beginFrameSubmission(std::uint64_t frameSequence) {
    std::scoped_lock lock(mutex_);
    frameGeneration_.beginSubmission(frameSequence);
}

void StreamlineRuntime::endFrameSubmission(std::uint64_t frameSequence) {
    std::scoped_lock lock(mutex_);
    frameGeneration_.endSubmission(frameSequence);
}

std::array<std::int32_t, 2> StreamlineRuntime::dlssOptimalSettings(
        std::int32_t quality,
        std::int32_t outputWidth,
        std::int32_t outputHeight
) {
    std::scoped_lock lock(mutex_);
    if (!initialized_) {
        throw std::runtime_error("Streamline DLSS optimal-settings runtime is not initialized");
    }
    return reconstruction_.dlssOptimalSettings(quality, outputWidth, outputHeight);
}

void StreamlineRuntime::closeLocked() noexcept {
    // Executors return all feature state while the borrowed SDK callbacks are valid. The process
    // owner then frees viewport resources and finally shuts down the vendor runtime.
    frameGeneration_.close();
    reconstruction_.clear();
    frameConstants_.clear();
    sdk_.releaseResources(viewport_);
    sdk_.close();
    initialized_ = false;
    preflightFeatures_ = 0;
    executionFeatures_ = 0;
    frameGenerationStatsBound_ = false;
    preflightPayload_.clear();
}

StreamlineRuntime& streamlineRuntime() {
    static StreamlineRuntime runtime;
    return runtime;
}

}
