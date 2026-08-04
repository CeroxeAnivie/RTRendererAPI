#include "streamline_frame_generation_executor.hpp"

#include <algorithm>
#include <exception>
#include <limits>
#include <stdexcept>
#include <string>

#include <sl_dlss_g.h>
#include <sl_pcl.h>

#include "streamline_diagnostics.hpp"
#include "streamline_frame_constants_publisher.hpp"
#include "streamline_frame_generation_policy.hpp"
#include "streamline_frame_support.hpp"

namespace rtrenderer::nvidia {

void StreamlineFrameGenerationExecutor::bind(
        const StreamlineApiBindings& bindings,
        StreamlineFrameConstantsPublisher& frameConstants,
        bool generationEnabled,
        bool lowLatencyEnabled
) noexcept {
    getNewFrameToken_ = bindings.getNewFrameToken;
    setTagForFrame_ = bindings.setTagForFrame;
    frameConstants_ = &frameConstants;
    dlssGSetOptions_ = bindings.dlssGSetOptions;
    dlssGGetState_ = bindings.dlssGGetState;
    reflexSleep_ = bindings.reflexSleep;
    pclSetMarker_ = bindings.pclSetMarker;
    proxyCreateSwapchain_ = bindings.createSwapchain;
    proxyDestroySwapchain_ = bindings.destroySwapchain;
    proxyGetSwapchainImages_ = bindings.getSwapchainImages;
    proxyAcquireNextImage_ = bindings.acquireNextImage;
    proxyQueuePresent_ = bindings.queuePresent;
    deviceWaitIdle_ = bindings.deviceWaitIdle;
    // Keep the SDK default eBlockPresentingClientQueue mode until the renderer can associate
    // input-completion fences with the exact frame slot being reused. A single global fence gate
    // is not sufficient for eBlockNoClientQueues and would let the pacer observe bursty reuse.
    inputCompletion_.bind(bindings.device, bindings.waitSemaphores, false);
    generationEnabled_ = generationEnabled;
    lowLatencyEnabled_ = lowLatencyEnabled;
}

void StreamlineFrameGenerationExecutor::disableGeneration() {
    disable();
    generationEnabled_ = false;
}

void StreamlineFrameGenerationExecutor::close() noexcept {
    // Optional-feature failures may skip the Java end callback. Balance the marker and turn
    // DLSS-G off while the SDK function table is still valid, then erase every borrowed pointer.
    closeOpenFrameMarkers();
    if (configuredGeneratedFrames_ != 0 || !pendingTaggedFrames_.empty()) {
        try {
            disable();
        } catch (...) {
            // The process owner still executes slFreeResources/slShutdown as authoritative cleanup.
        }
    }
    clearBindings();
    resetState();
}

void StreamlineFrameGenerationExecutor::discard() noexcept {
    // A failed device handoff may invalidate the previous SDK table before returning. Never call
    // borrowed functions from that table; only erase renderer-owned references and evidence.
    clearBindings();
    resetState();
}

VkResult StreamlineFrameGenerationExecutor::createSwapchain(
        VkDevice device,
        const VkSwapchainCreateInfoKHR* createInfo,
        VkSwapchainKHR* swapchain
) {
    if (proxyCreateSwapchain_ == nullptr || device == VK_NULL_HANDLE || createInfo == nullptr
            || swapchain == nullptr) {
        throw std::invalid_argument("invalid Streamline vkCreateSwapchainKHR proxy call");
    }
    const VkResult result = proxyCreateSwapchain_(device, createInfo, nullptr, swapchain);
    if (result == VK_SUCCESS) {
        backBufferWidth_ = createInfo->imageExtent.width;
        backBufferHeight_ = createInfo->imageExtent.height;
        backBufferFormat_ = static_cast<std::uint32_t>(createInfo->imageFormat);
    }
    return result;
}

void StreamlineFrameGenerationExecutor::destroySwapchain(VkDevice device, VkSwapchainKHR swapchain) {
    std::exception_ptr disableFailure;
    if (proxyDestroySwapchain_ == nullptr || deviceWaitIdle_ == nullptr
            || device == VK_NULL_HANDLE || swapchain == VK_NULL_HANDLE) {
        throw std::invalid_argument("invalid Streamline vkDestroySwapchainKHR proxy call");
    }
    // Turning the feature off and releasing frame tags are independent obligations. A failed
    // options call must not strand Vulkan image references, while a failed null-tag must prevent
    // destruction of the images still referenced by Streamline.
    try {
        configureOff();
    } catch (...) {
        disableFailure = std::current_exception();
    }
    releaseAllFrameTags();
    // Streamline requires DLSS-G to be successfully disabled before resize or other swapchain
    // manipulation. Releasing frame tags satisfies a separate resource-reference obligation; it
    // does not make destroying an eOn proxy swapchain valid. Preserve the existing swapchain so
    // the caller can retry the shutdown transaction after the options failure.
    if (disableFailure != nullptr) std::rethrow_exception(disableFailure);
    // The proxy present may continue on Streamline's private pacer queue after the renderer's
    // presenting queue is idle. vkDeviceWaitIdle is the only Vulkan completion proof covering
    // that non-exported queue; this runs only at swapchain destruction/recreation boundaries.
    const VkResult idleResult = deviceWaitIdle_(device);
    // Native swapchain ownership must still be released if disable or device-idle reports failure.
    proxyDestroySwapchain_(device, swapchain, nullptr);
    backBufferWidth_ = 0;
    backBufferHeight_ = 0;
    backBufferFormat_ = 0;
    backBufferCount_ = 0;
    if (idleResult != VK_SUCCESS) {
        throw std::runtime_error(
                "vkDeviceWaitIdle before Streamline swapchain destruction failed: VkResult="
                        + std::to_string(static_cast<std::int32_t>(idleResult))
        );
    }
}

VkResult StreamlineFrameGenerationExecutor::getSwapchainImages(
        VkDevice device,
        VkSwapchainKHR swapchain,
        uint32_t* count,
        VkImage* images
) {
    if (proxyGetSwapchainImages_ == nullptr || device == VK_NULL_HANDLE
            || swapchain == VK_NULL_HANDLE || count == nullptr) {
        throw std::invalid_argument("invalid Streamline vkGetSwapchainImagesKHR proxy call");
    }
    const VkResult result = proxyGetSwapchainImages_(device, swapchain, count, images);
    if ((result == VK_SUCCESS || result == VK_INCOMPLETE) && images != nullptr && *count > 0) {
        backBufferCount_ = *count;
    }
    return result;
}

VkResult StreamlineFrameGenerationExecutor::acquireNextImage(
        VkDevice device,
        VkSwapchainKHR swapchain,
        uint64_t timeout,
        VkSemaphore semaphore,
        VkFence fence,
        uint32_t* imageIndex
) {
    if (proxyAcquireNextImage_ == nullptr || device == VK_NULL_HANDLE
            || swapchain == VK_NULL_HANDLE || imageIndex == nullptr) {
        throw std::invalid_argument("invalid Streamline vkAcquireNextImageKHR proxy call");
    }
    return proxyAcquireNextImage_(device, swapchain, timeout, semaphore, fence, imageIndex);
}

VkResult StreamlineFrameGenerationExecutor::queuePresent(
        VkQueue queue,
        const VkPresentInfoKHR* presentInfo,
        std::int32_t generatedFrames,
        std::uint64_t frameSequence
) {
    sl::FrameToken* token = nullptr;
    if (proxyQueuePresent_ == nullptr || queue == VK_NULL_HANDLE || presentInfo == nullptr
            || presentInfo->swapchainCount != 1 || presentInfo->pSwapchains == nullptr) {
        throw std::invalid_argument("invalid Streamline vkQueuePresentKHR proxy call");
    }
    if (frameSequence > std::numeric_limits<std::uint32_t>::max()) {
        throw std::invalid_argument("Streamline present sequence exceeds the frame-token ABI");
    }
    if (hasLastPresentSequence_ && frameSequence <= lastPresentSequence_) {
        throw std::invalid_argument("Streamline presents must use strictly increasing frame sequences");
    }
    const std::uint32_t frameIndex = static_cast<std::uint32_t>(frameSequence);
    const auto taggedFrame = std::find_if(
            pendingTaggedFrames_.begin(), pendingTaggedFrames_.end(),
            [frameIndex](const TaggedFrame& candidate) { return candidate.sequence == frameIndex; }
    );
    // Frame-based tagging permits several renderer slots to be submitted before the oldest one is
    // presented. Generation is enabled only when this exact present token owns valid inputs; a
    // frame intentionally skipped for an invalid temporal contract remains a native present.
    const bool generationRequested = generatedFrames != 0
            && taggedFrame != pendingTaggedFrames_.end()
            && taggedFrame->hudlessWidth == backBufferWidth_
            && taggedFrame->hudlessHeight == backBufferHeight_;
    const bool generationRequestMiss = generatedFrames != 0 && !generationRequested;
    const bool markersRequested = lowLatencyEnabled_;
    VkResult result = VK_SUCCESS;
    std::exception_ptr presentFailure;
    try {
        if (generationRequested) configure(generatedFrames, *taggedFrame);
        else configureOff();
        if (markersRequested) {
            streamline::requireOk(getNewFrameToken_(token, &frameIndex), "slGetNewFrameToken(present)");
            if (token == nullptr) throw std::runtime_error("present frame token is null");
            streamline::requireOk(
                    pclSetMarker_(sl::PCLMarker::ePresentStart, *token),
                    "slPCLSetMarker(PresentStart)"
            );
        }
        lastPresentSequence_ = frameSequence;
        hasLastPresentSequence_ = true;
        // The official proxy owns its asynchronous pacer queue. The application supplies queue
        // ranges and never redirects or externally locks Streamline's internal queue.
        result = proxyQueuePresent_(queue, presentInfo);
        tracker_.noteProxyPresent(frameSequence);
        if (generationRequestMiss) {
            // Count a fallback only after the corresponding proxy present exists. Configuration
            // or marker failures before this point must not produce impossible miss > present
            // evidence for a frame that Streamline never accepted for presentation.
            tracker_.noteGenerationRequestMiss();
        }
        if (markersRequested) {
            streamline::requireOk(
                    pclSetMarker_(sl::PCLMarker::ePresentEnd, *token),
                    "slPCLSetMarker(PresentEnd)"
            );
        }
        if (generationRequested) {
            const sl::DLSSGState state = queryState(
                    "slDLSSGGetState(completion)", frameSequence
            );
            inputCompletion_.capture(state);
            if (state.status != sl::DLSSGStatus::eOk) {
                configureOff();
                throw std::runtime_error(
                        "DLSS-G runtime status disabled frame generation: status="
                                + std::to_string(static_cast<std::uint32_t>(state.status))
                );
            }
        }
    } catch (...) {
        presentFailure = std::current_exception();
    }
    // The Vulkan proxy present is asynchronous. The presenter retires this token only after its
    // same-queue retirement fence proves that Streamline has consumed the tagged inputs. Clearing
    // the tags here would race the pacer and can turn a successfully configured frame into a
    // native-only present.
    if (presentFailure != nullptr) std::rethrow_exception(presentFailure);
    return result;
}

void StreamlineFrameGenerationExecutor::retireFrame(std::uint64_t frameSequence) {
    if (frameSequence > std::numeric_limits<std::uint32_t>::max()) {
        throw std::invalid_argument("Streamline retired sequence exceeds the frame-token ABI");
    }
    retireTaggedFramesThrough(static_cast<std::uint32_t>(frameSequence));
}

std::array<std::int64_t, 18> StreamlineFrameGenerationExecutor::stats() const noexcept {
    const auto evidence = tracker_.snapshot();
    return {
            static_cast<std::int64_t>(evidence[0]),
            static_cast<std::int64_t>(evidence[1]),
            static_cast<std::int64_t>(evidence[2]),
            static_cast<std::int64_t>(evidence[3]),
            static_cast<std::int64_t>(evidence[4]),
            static_cast<std::int64_t>(evidence[5]),
            static_cast<std::int64_t>(evidence[6]),
            static_cast<std::int64_t>(evidence[7]),
            static_cast<std::int64_t>(evidence[8]),
            static_cast<std::int64_t>(lastRequestedGeneratedFrames_),
            static_cast<std::int64_t>(configuredGeneratedFrames_),
            static_cast<std::int64_t>(evidence[9]),
            static_cast<std::int64_t>(evidence[10]),
            static_cast<std::int64_t>(evidence[11]),
            static_cast<std::int64_t>(evidence[12]),
            static_cast<std::int64_t>(evidence[13]),
            static_cast<std::int64_t>(evidence[14]),
            static_cast<std::int64_t>(evidence[15])
    };
}

void StreamlineFrameGenerationExecutor::record(
        std::uint64_t commandBuffer,
        const StreamlineFrame& frame
) {
    if (!generationEnabled_ || getNewFrameToken_ == nullptr || setTagForFrame_ == nullptr
            || frameConstants_ == nullptr || commandBuffer == 0) {
        throw std::runtime_error("Streamline DLSS-G tagging runtime is not device-ready");
    }
    if (!streamline::validSampledImage(frame.inputColor)
            || !streamline::validSampledImage(frame.depth)
            || !streamline::validSampledImage(frame.motion)) {
        throw std::invalid_argument("Streamline DLSS-G received incompatible Vulkan inputs");
    }
    clearStreamlineDiagnostic();
    sl::FrameToken& token = frameConstants_->publish(viewport_, frame);

    sl::Resource hudless = streamline::resource(frame.inputColor);
    sl::Resource depth = streamline::resource(frame.depth);
    sl::Resource motion = streamline::resource(frame.motion);
    sl::Extent colorExtent{0, 0, static_cast<uint32_t>(frame.inputColor.width), static_cast<uint32_t>(frame.inputColor.height)};
    sl::Extent signalExtent{0, 0, static_cast<uint32_t>(frame.depth.width), static_cast<uint32_t>(frame.depth.height)};
    // The Java frame slot is not writable again until the Streamline input-completion timeline is
    // consumed. Publishing that real lifetime avoids sl.common's volatile clone path and lets the
    // pacer read the original Vulkan images with the state supplied by this command buffer.
    std::array<sl::ResourceTag, 3> tags = {
            sl::ResourceTag(&hudless, sl::kBufferTypeHUDLessColor, streamline::kFrameSlotResourceLifecycle, &colorExtent),
            sl::ResourceTag(&depth, sl::kBufferTypeDepth, streamline::kFrameSlotResourceLifecycle, &signalExtent),
            sl::ResourceTag(&motion, sl::kBufferTypeMotionVectors, streamline::kFrameSlotResourceLifecycle, &signalExtent)
    };
    auto* nativeCommandBuffer = reinterpret_cast<sl::CommandBuffer*>(commandBuffer);
    const TaggedFrame metadata{
            frame.frameIndex,
            static_cast<std::uint32_t>(frame.inputColor.width),
            static_cast<std::uint32_t>(frame.inputColor.height),
            static_cast<std::uint32_t>(frame.depth.width),
            static_cast<std::uint32_t>(frame.depth.height),
            static_cast<std::uint32_t>(frame.inputColor.format),
            static_cast<std::uint32_t>(frame.depth.format),
            static_cast<std::uint32_t>(frame.motion.format)
    };
    const auto existing = std::find_if(
            pendingTaggedFrames_.begin(), pendingTaggedFrames_.end(),
            [&metadata](const TaggedFrame& candidate) { return candidate.sequence == metadata.sequence; }
    );
    const bool alreadyPending = existing != pendingTaggedFrames_.end();
    // Complete every fallible host operation before the vendor tag becomes externally visible.
    // Reserving here makes the post-tag POD insertion non-allocating and therefore non-throwing.
    if (!alreadyPending) {
        pendingTaggedFrames_.reserve(pendingTaggedFrames_.size() + 1);
    }
    streamline::requireOk(
            setTagForFrame_(token, viewport_, tags.data(), static_cast<uint32_t>(tags.size()), nativeCommandBuffer),
            "slSetTagForFrame(DLSS-G)"
    );
    if (!alreadyPending) pendingTaggedFrames_.push_back(metadata);
    else *existing = metadata;
}

std::array<std::uint64_t, 2> StreamlineFrameGenerationExecutor::awaitInputReuse() {
    if (!generationEnabled_) {
        throw std::runtime_error("Streamline frame-generation input gate is not device-ready");
    }
    return inputCompletion_.await();
}

void StreamlineFrameGenerationExecutor::beginFramePreparation(std::uint64_t frameSequence) {
    if (frameSequence > std::numeric_limits<std::uint32_t>::max()) {
        throw std::invalid_argument("Streamline frame-preparation sequence exceeds the frame-token ABI");
    }
    if (!lowLatencyEnabled_ || getNewFrameToken_ == nullptr
            || reflexSleep_ == nullptr || pclSetMarker_ == nullptr) {
        throw std::runtime_error("Streamline frame-preparation timing runtime is not device-ready");
    }
    if (preparationMarkerOpen_ || submissionMarkerOpen_) {
        throw std::runtime_error("a Streamline frame timing interval is already open");
    }
    const uint32_t frameIndex = static_cast<uint32_t>(frameSequence);
    sl::FrameToken* token = nullptr;
    streamline::requireOk(getNewFrameToken_(token, &frameIndex), "slGetNewFrameToken(frame preparation)");
    if (token == nullptr) throw std::runtime_error("frame-preparation token is null");
    streamline::requireOk(reflexSleep_(*token), "slReflexSleep");
    streamline::requireOk(pclSetMarker_(sl::PCLMarker::eSimulationStart, *token), "slPCLSetMarker(SimulationStart)");
    preparationMarkerSequence_ = frameSequence;
    preparationMarkerOpen_ = true;
}

void StreamlineFrameGenerationExecutor::cancelFramePreparation(std::uint64_t frameSequence) {
    if (!preparationMarkerOpen_) return;
    if (frameSequence > std::numeric_limits<std::uint32_t>::max()
            || preparationMarkerSequence_ != frameSequence) {
        throw std::runtime_error("Streamline frame-preparation markers are unbalanced");
    }
    if (!lowLatencyEnabled_ || getNewFrameToken_ == nullptr || pclSetMarker_ == nullptr) {
        throw std::runtime_error("Streamline frame-preparation timing runtime is not device-ready");
    }
    const uint32_t frameIndex = static_cast<uint32_t>(frameSequence);
    // Clear ownership before invoking vendor code. A failed cancellation disables generation;
    // retaining a logically open interval would only poison every later frame and teardown.
    preparationMarkerOpen_ = false;
    preparationMarkerSequence_ = 0;
    sl::FrameToken* token = nullptr;
    streamline::requireOk(getNewFrameToken_(token, &frameIndex), "slGetNewFrameToken(preparation cancel)");
    if (token == nullptr) throw std::runtime_error("preparation-cancel frame token is null");
    streamline::requireOk(pclSetMarker_(sl::PCLMarker::eSimulationEnd, *token), "slPCLSetMarker(SimulationEnd)");
}

void StreamlineFrameGenerationExecutor::beginSubmission(std::uint64_t frameSequence) {
    if (frameSequence > std::numeric_limits<std::uint32_t>::max()) {
        throw std::invalid_argument("Streamline submission sequence exceeds the frame-token ABI");
    }
    if (!lowLatencyEnabled_ || getNewFrameToken_ == nullptr || pclSetMarker_ == nullptr) {
        throw std::runtime_error("Streamline frame-submission timing runtime is not device-ready");
    }
    if (!preparationMarkerOpen_ || preparationMarkerSequence_ != frameSequence
            || submissionMarkerOpen_) {
        throw std::runtime_error("Streamline frame preparation and submission markers are unbalanced");
    }
    const uint32_t frameIndex = static_cast<uint32_t>(frameSequence);
    // Submission takes ownership of the frame interval before calling the vendor. Any marker
    // failure deterministically disables generation instead of leaving an unrecoverable open
    // simulation interval in this state machine.
    preparationMarkerOpen_ = false;
    preparationMarkerSequence_ = 0;
    sl::FrameToken* token = nullptr;
    streamline::requireOk(getNewFrameToken_(token, &frameIndex), "slGetNewFrameToken(submission)");
    if (token == nullptr) throw std::runtime_error("submission frame token is null");
    streamline::requireOk(pclSetMarker_(sl::PCLMarker::eSimulationEnd, *token), "slPCLSetMarker(SimulationEnd)");
    streamline::requireOk(pclSetMarker_(sl::PCLMarker::eRenderSubmitStart, *token), "slPCLSetMarker(RenderSubmitStart)");
    submissionMarkerSequence_ = frameSequence;
    submissionMarkerOpen_ = true;
}

void StreamlineFrameGenerationExecutor::endSubmission(std::uint64_t frameSequence) {
    if (frameSequence > std::numeric_limits<std::uint32_t>::max()) {
        throw std::invalid_argument("Streamline submission sequence exceeds the frame-token ABI");
    }
    if (!lowLatencyEnabled_ || getNewFrameToken_ == nullptr || pclSetMarker_ == nullptr) {
        throw std::runtime_error("Streamline frame-submission timing runtime is not device-ready");
    }
    if (!submissionMarkerOpen_ || submissionMarkerSequence_ != frameSequence) {
        throw std::runtime_error("Streamline frame submission markers are unbalanced");
    }
    const uint32_t frameIndex = static_cast<uint32_t>(frameSequence);
    sl::FrameToken* token = nullptr;
    streamline::requireOk(getNewFrameToken_(token, &frameIndex), "slGetNewFrameToken(submission end)");
    if (token == nullptr) throw std::runtime_error("submission-end frame token is null");
    submissionMarkerOpen_ = false;
    streamline::requireOk(
            pclSetMarker_(sl::PCLMarker::eRenderSubmitEnd, *token),
            "slPCLSetMarker(RenderSubmitEnd)"
    );
}

sl::DLSSGState StreamlineFrameGenerationExecutor::queryState(
        const char* operation,
        std::uint64_t observationSequence
) {
    if (dlssGGetState_ == nullptr || operation == nullptr) {
        throw std::runtime_error("Streamline DLSS-G state query is not device-ready");
    }
    sl::DLSSGState state = {};
    const sl::Result result = dlssGGetState_(viewport_, state, nullptr);
    if (result != sl::Result::eOk) {
        tracker_.recordQueryFailure();
        streamline::requireOk(result, operation);
    }
    // numFramesActuallyPresented is destructive: every successful query must be accounted for,
    // including the capability query required before a changed cadence is configured.
    tracker_.recordState(state, observationSequence);
    return state;
}

void StreamlineFrameGenerationExecutor::configure(
        std::int32_t generatedFrames,
        const TaggedFrame& frame
) {
    if (!generationEnabled_ || dlssGSetOptions_ == nullptr || dlssGGetState_ == nullptr) {
        throw std::runtime_error("Streamline DLSS-G runtime is not device-ready");
    }
    if (generatedFrames == 0 || generatedFrames < -3 || generatedFrames > 3) {
        throw std::invalid_argument("generated frame count must be explicit [1, 3] or adaptive [-3, -1]");
    }
    const bool configurationUnchanged = configuredCadenceRequest_ == generatedFrames
            && configuredBackBufferWidth_ == backBufferWidth_
            && configuredBackBufferHeight_ == backBufferHeight_
            && configuredRenderWidth_ == frame.renderWidth && configuredRenderHeight_ == frame.renderHeight
            && configuredBackBufferFormat_ == backBufferFormat_
            && configuredHudlessFormat_ == frame.hudlessFormat
            && configuredDepthFormat_ == frame.depthFormat
            && configuredMotionFormat_ == frame.motionFormat;
    if (configurationUnchanged) return;
    const sl::DLSSGState state = queryState(
            "slDLSSGGetState(configuration)",
            hasLastPresentSequence_ ? lastPresentSequence_ : frame.sequence
    );
    if (state.status != sl::DLSSGStatus::eOk) {
        throw std::runtime_error(
                "DLSS-G runtime status prevents frame generation: status="
                        + std::to_string(static_cast<std::uint32_t>(state.status))
        );
    }
    const StreamlineCadenceDecision cadence = selectStreamlineCadence(
            generatedFrames, state.numFramesToGenerateMax
    );
    if (cadence.status == StreamlineCadenceStatus::unavailable) {
        throw std::runtime_error("Streamline reported no executable generated-frame cadence");
    }
    if (cadence.status == StreamlineCadenceStatus::exceedsDeviceLimit) {
        throw std::runtime_error(
                "requested DLSS-G multiplier exceeds the device maximum: requested="
                        + std::to_string(generatedFrames) + ", max="
                        + std::to_string(state.numFramesToGenerateMax)
        );
    }
    if (cadence.status != StreamlineCadenceStatus::ready) {
        throw std::logic_error("validated Streamline cadence produced an invalid policy result");
    }
    const std::uint32_t selectedFrames = cadence.generatedFrames;
    lastRequestedGeneratedFrames_ = selectedFrames;
    sl::DLSSGOptions options = {};
    options.mode = sl::DLSSGMode::eOn;
    options.numFramesToGenerate = selectedFrames;
    if (backBufferCount_ == 0 || backBufferWidth_ == 0 || backBufferHeight_ == 0
            || backBufferFormat_ == 0) {
        throw std::runtime_error("DLSS-G options require resolved swapchain back-buffer metadata");
    }
    options.numBackBuffers = backBufferCount_;
    options.colorWidth = backBufferWidth_;
    options.colorHeight = backBufferHeight_;
    options.mvecDepthWidth = frame.renderWidth;
    options.mvecDepthHeight = frame.renderHeight;
    options.colorBufferFormat = backBufferFormat_;
    options.hudLessBufferFormat = frame.hudlessFormat;
    options.depthBufferFormat = frame.depthFormat;
    options.mvecBufferFormat = frame.motionFormat;
    // A fixed reconstruction ratio commonly makes depth/MV smaller than the output. Streamline
    // explicitly forbids inferring dynamic resolution from that size difference; this renderer
    // does not expose a dynamic-resolution contract, so the flag must remain clear.
    streamline::requireOk(dlssGSetOptions_(viewport_, options), "slDLSSGSetOptions");
    configuredGeneratedFrames_ = selectedFrames;
    configuredCadenceRequest_ = generatedFrames;
    configuredBackBufferWidth_ = backBufferWidth_;
    configuredBackBufferHeight_ = backBufferHeight_;
    configuredRenderWidth_ = frame.renderWidth;
    configuredRenderHeight_ = frame.renderHeight;
    configuredBackBufferFormat_ = backBufferFormat_;
    configuredHudlessFormat_ = frame.hudlessFormat;
    configuredDepthFormat_ = frame.depthFormat;
    configuredMotionFormat_ = frame.motionFormat;
}

void StreamlineFrameGenerationExecutor::configureOff() {
    if (configuredGeneratedFrames_ == 0) return;
    if (dlssGSetOptions_ == nullptr) {
        throw std::runtime_error("cannot disable DLSS-G because slDLSSGSetOptions is unavailable");
    }
    sl::DLSSGOptions options = {};
    options.mode = sl::DLSSGMode::eOff;
    streamline::requireOk(dlssGSetOptions_(viewport_, options), "slDLSSGSetOptions(eOff)");
    configuredGeneratedFrames_ = 0;
    configuredCadenceRequest_ = 0;
    lastRequestedGeneratedFrames_ = 0;
}

void StreamlineFrameGenerationExecutor::disable() {
    std::exception_ptr optionsFailure;
    try {
        configureOff();
    } catch (...) {
        optionsFailure = std::current_exception();
    }
    // Null-tag release is a separate resource-lifetime obligation and must run even if the vendor
    // rejects eOff. A release failure wins because callers must not destroy or recycle the images.
    releaseAllFrameTags();
    if (optionsFailure != nullptr) std::rethrow_exception(optionsFailure);
}

void StreamlineFrameGenerationExecutor::releaseFrameTags(std::uint32_t frameIndex) {
    if (getNewFrameToken_ == nullptr || setTagForFrame_ == nullptr) {
        throw std::runtime_error("cannot release DLSS-G frame tags because Streamline is unavailable");
    }
    sl::FrameToken* token = nullptr;
    streamline::requireOk(getNewFrameToken_(token, &frameIndex), "slGetNewFrameToken(tag release)");
    if (token == nullptr) throw std::runtime_error("tag-release frame token is null");
    std::array<sl::ResourceTag, 3> nullTags = {
            sl::ResourceTag(nullptr, sl::kBufferTypeHUDLessColor, sl::ResourceLifecycle::eValidUntilPresent),
            sl::ResourceTag(nullptr, sl::kBufferTypeDepth, sl::ResourceLifecycle::eValidUntilPresent),
            sl::ResourceTag(nullptr, sl::kBufferTypeMotionVectors, sl::ResourceLifecycle::eValidUntilPresent)
    };
    streamline::requireOk(
            setTagForFrame_(
                    *token,
                    viewport_,
                    nullTags.data(),
                    static_cast<std::uint32_t>(nullTags.size()),
                    nullptr
            ),
            "slSetTagForFrame(null DLSS-G inputs)"
    );
}

void StreamlineFrameGenerationExecutor::retireTaggedFramesThrough(std::uint32_t frameIndex) {
    auto frame = pendingTaggedFrames_.begin();
    while (frame != pendingTaggedFrames_.end()) {
        if (frame->sequence > frameIndex) {
            ++frame;
            continue;
        }
        releaseFrameTags(frame->sequence);
        frame = pendingTaggedFrames_.erase(frame);
    }
}

void StreamlineFrameGenerationExecutor::releaseAllFrameTags() {
    while (!pendingTaggedFrames_.empty()) {
        releaseFrameTags(pendingTaggedFrames_.front().sequence);
        pendingTaggedFrames_.erase(pendingTaggedFrames_.begin());
    }
}

void StreamlineFrameGenerationExecutor::closeOpenFrameMarkers() noexcept {
    if ((!submissionMarkerOpen_ && !preparationMarkerOpen_)
            || getNewFrameToken_ == nullptr || pclSetMarker_ == nullptr) {
        preparationMarkerOpen_ = false;
        submissionMarkerOpen_ = false;
        return;
    }
    try {
        if (submissionMarkerOpen_) {
            sl::FrameToken* token = nullptr;
            const uint32_t frameIndex = static_cast<uint32_t>(
                    std::min<std::uint64_t>(submissionMarkerSequence_, std::numeric_limits<uint32_t>::max())
            );
            if (getNewFrameToken_(token, &frameIndex) == sl::Result::eOk && token != nullptr) {
                (void)pclSetMarker_(sl::PCLMarker::eRenderSubmitEnd, *token);
            }
        }
        if (preparationMarkerOpen_) {
            sl::FrameToken* token = nullptr;
            const uint32_t frameIndex = static_cast<uint32_t>(
                    std::min<std::uint64_t>(preparationMarkerSequence_, std::numeric_limits<uint32_t>::max())
            );
            if (getNewFrameToken_(token, &frameIndex) == sl::Result::eOk && token != nullptr) {
                (void)pclSetMarker_(sl::PCLMarker::eSimulationEnd, *token);
            }
        }
    } catch (...) {
        // Never let a vendor callback prevent the remaining process teardown.
    }
    preparationMarkerOpen_ = false;
    preparationMarkerSequence_ = 0;
    submissionMarkerOpen_ = false;
    submissionMarkerSequence_ = 0;
}

void StreamlineFrameGenerationExecutor::clearBindings() noexcept {
    getNewFrameToken_ = nullptr;
    setTagForFrame_ = nullptr;
    frameConstants_ = nullptr;
    dlssGSetOptions_ = nullptr;
    dlssGGetState_ = nullptr;
    reflexSleep_ = nullptr;
    pclSetMarker_ = nullptr;
    proxyCreateSwapchain_ = nullptr;
    proxyDestroySwapchain_ = nullptr;
    proxyGetSwapchainImages_ = nullptr;
    proxyAcquireNextImage_ = nullptr;
    proxyQueuePresent_ = nullptr;
    deviceWaitIdle_ = nullptr;
    inputCompletion_.clear();
}

void StreamlineFrameGenerationExecutor::resetState() noexcept {
    tracker_.reset();
    pendingTaggedFrames_.clear();
    configuredGeneratedFrames_ = 0;
    configuredCadenceRequest_ = 0;
    lastRequestedGeneratedFrames_ = 0;
    lastPresentSequence_ = 0;
    configuredBackBufferWidth_ = 0;
    configuredBackBufferHeight_ = 0;
    configuredRenderWidth_ = 0;
    configuredRenderHeight_ = 0;
    configuredBackBufferFormat_ = 0;
    configuredHudlessFormat_ = 0;
    configuredDepthFormat_ = 0;
    configuredMotionFormat_ = 0;
    backBufferWidth_ = 0;
    backBufferHeight_ = 0;
    backBufferFormat_ = 0;
    backBufferCount_ = 0;
    preparationMarkerSequence_ = 0;
    submissionMarkerSequence_ = 0;
    generationEnabled_ = false;
    lowLatencyEnabled_ = false;
    hasLastPresentSequence_ = false;
    preparationMarkerOpen_ = false;
    submissionMarkerOpen_ = false;
}

}
