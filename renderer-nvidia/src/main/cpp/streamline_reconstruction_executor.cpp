#include "streamline_reconstruction_executor.hpp"

#include <array>
#include <iterator>
#include <stdexcept>

#include <vulkan/vulkan.h>
#include <sl_dlss.h>
#include <sl_nis.h>

#include "streamline_diagnostics.hpp"
#include "streamline_frame_constants_publisher.hpp"
#include "streamline_frame_support.hpp"

namespace rtrenderer::nvidia {

void StreamlineReconstructionExecutor::bind(
        const StreamlineApiBindings& bindings,
        StreamlineFrameConstantsPublisher& frameConstants
) noexcept {
    getNewFrameToken_ = bindings.getNewFrameToken;
    frameConstants_ = &frameConstants;
    evaluateFeature_ = bindings.evaluateFeature;
    freeResources_ = bindings.freeResources;
    dlssOptimalSettings_ = bindings.dlssOptimalSettings;
    dlssSetOptions_ = bindings.dlssSetOptions;
    nisSetOptions_ = bindings.nisSetOptions;
}

void StreamlineReconstructionExecutor::clear() noexcept {
    getNewFrameToken_ = nullptr;
    frameConstants_ = nullptr;
    evaluateFeature_ = nullptr;
    freeResources_ = nullptr;
    dlssOptimalSettings_ = nullptr;
    dlssSetOptions_ = nullptr;
    nisSetOptions_ = nullptr;
    dlssConfiguration_ = {};
    hasDlssResources_ = false;
}

void StreamlineReconstructionExecutor::recordDlss(
        std::uint64_t commandBuffer,
        std::int32_t reconstructionMode,
        std::int32_t quality,
        const StreamlineFrame& frame
) {
    if (dlssSetOptions_ == nullptr || frameConstants_ == nullptr
            || evaluateFeature_ == nullptr || freeResources_ == nullptr
            || commandBuffer == 0) {
        throw std::runtime_error("Streamline DLSS runtime is not device-ready");
    }
    if (!streamline::validSampledImage(frame.inputColor)
            || !streamline::validStorageImage(frame.outputColor)
            || !streamline::validSampledImage(frame.depth)
            || !streamline::validSampledImage(frame.motion)
            || !streamline::validSampledImage(frame.exposure)) {
        throw std::invalid_argument("Streamline received incomplete Vulkan resource metadata");
    }
    if (reconstructionMode < 0 || reconstructionMode > 1 || quality < 0 || quality > 5) {
        throw std::invalid_argument("invalid Streamline reconstruction policy");
    }
    clearStreamlineDiagnostic();

    const DlssConfiguration nextConfiguration{
            dlssMode(reconstructionMode, quality),
            static_cast<std::uint32_t>(frame.outputColor.width),
            static_cast<std::uint32_t>(frame.outputColor.height)
    };
    if (hasDlssResources_ && nextConfiguration != dlssConfiguration_) {
        // Streamline 2.12 defers an internally detected DLSS resize by three intercepted presents.
        // A manual Vulkan integration does not use that hook as its frame clock, so the deferred
        // lambda can survive until its plugin DLL is unloaded. Explicit viewport release is the
        // SDK-supported resize boundary and prevents that stale executable callback from existing.
        streamline::requireOk(
                freeResources_(sl::kFeatureDLSS, viewport_),
                "slFreeResources(DLSS reconfigure)"
        );
        hasDlssResources_ = false;
        dlssConfiguration_ = {};
    }

    sl::FrameToken& token = frameConstants_->publish(viewport_, frame);

    sl::Resource input = streamline::resource(frame.inputColor);
    sl::Resource output = streamline::resource(frame.outputColor);
    sl::Resource depth = streamline::resource(frame.depth);
    sl::Resource motion = streamline::resource(frame.motion);
    sl::Resource exposure = streamline::resource(frame.exposure);
    sl::Extent inputExtent{0, 0, static_cast<uint32_t>(frame.inputColor.width), static_cast<uint32_t>(frame.inputColor.height)};
    sl::Extent outputExtent{0, 0, static_cast<uint32_t>(frame.outputColor.width), static_cast<uint32_t>(frame.outputColor.height)};
    sl::Extent exposureExtent{0, 0, 1, 1};
    // Reconstruction inputs are local to this evaluate call. DLSS-G owns its separate global
    // HUD-less/depth/motion tags and their present-bounded null-tag lifecycle; sharing those tags
    // here would give reconstruction resource references that it has no lifecycle ledger to release.
    std::array<sl::ResourceTag, 5> tags = {
            sl::ResourceTag(&input, sl::kBufferTypeScalingInputColor, sl::ResourceLifecycle::eOnlyValidNow, &inputExtent),
            sl::ResourceTag(&output, sl::kBufferTypeScalingOutputColor, sl::ResourceLifecycle::eOnlyValidNow, &outputExtent),
            sl::ResourceTag(&depth, sl::kBufferTypeDepth, sl::ResourceLifecycle::eValidUntilPresent, &inputExtent),
            sl::ResourceTag(&motion, sl::kBufferTypeMotionVectors, sl::ResourceLifecycle::eOnlyValidNow, &inputExtent),
            sl::ResourceTag(&exposure, sl::kBufferTypeExposure, sl::ResourceLifecycle::eOnlyValidNow, &exposureExtent)
    };
    auto* nativeCommandBuffer = reinterpret_cast<sl::CommandBuffer*>(commandBuffer);
    sl::DLSSOptions options = {};
    options.mode = nextConfiguration.mode;
    options.outputWidth = static_cast<uint32_t>(frame.outputColor.width);
    options.outputHeight = static_cast<uint32_t>(frame.outputColor.height);
    options.colorBuffersHDR = sl::Boolean::eTrue;
    options.useAutoExposure = sl::Boolean::eFalse;
    streamline::requireOk(dlssSetOptions_(viewport_, options), "slDLSSSetOptions");
    const sl::BaseStructure* evaluationInputs[] = {
            &viewport_, &tags[0], &tags[1], &tags[2], &tags[3], &tags[4]
    };
    streamline::requireOk(
            evaluateFeature_(
                    sl::kFeatureDLSS,
                    token,
                    evaluationInputs,
                    static_cast<std::uint32_t>(std::size(evaluationInputs)),
                    nativeCommandBuffer
            ),
            "slEvaluateFeature(DLSS)"
    );
    dlssConfiguration_ = nextConfiguration;
    hasDlssResources_ = true;
}

void StreamlineReconstructionExecutor::recordNis(
        std::uint64_t commandBuffer,
        std::int32_t quality,
        const StreamlineFrame& frame
) {
    if (nisSetOptions_ == nullptr || getNewFrameToken_ == nullptr
            || evaluateFeature_ == nullptr || commandBuffer == 0) {
        throw std::runtime_error("Streamline NIS runtime is not device-ready");
    }
    if (!streamline::validSampledImage(frame.inputColor)
            || !streamline::validStorageImage(frame.outputColor)) {
        throw std::invalid_argument("Streamline NIS received incompatible Vulkan color resources");
    }
    if (quality < 0 || quality > 5) throw std::invalid_argument("invalid NIS quality policy");

    clearStreamlineDiagnostic();
    sl::FrameToken* token = nullptr;
    streamline::requireOk(getNewFrameToken_(token, &frame.frameIndex), "slGetNewFrameToken");
    if (token == nullptr) throw std::runtime_error("slGetNewFrameToken returned a null token");

    sl::Resource input = streamline::resource(frame.inputColor);
    sl::Resource output = streamline::resource(frame.outputColor);
    sl::Extent inputExtent{0, 0, static_cast<uint32_t>(frame.inputColor.width), static_cast<uint32_t>(frame.inputColor.height)};
    sl::Extent outputExtent{0, 0, static_cast<uint32_t>(frame.outputColor.width), static_cast<uint32_t>(frame.outputColor.height)};
    std::array<sl::ResourceTag, 2> tags = {
            sl::ResourceTag(&input, sl::kBufferTypeScalingInputColor, sl::ResourceLifecycle::eOnlyValidNow, &inputExtent),
            sl::ResourceTag(&output, sl::kBufferTypeScalingOutputColor, sl::ResourceLifecycle::eOnlyValidNow, &outputExtent)
    };
    auto* nativeCommandBuffer = reinterpret_cast<sl::CommandBuffer*>(commandBuffer);

    sl::NISOptions options = {};
    options.mode = sl::NISMode::eScaler;
    options.hdrMode = sl::NISHDR::eLinear;
    options.sharpness = nisSharpness(quality);
    streamline::requireOk(nisSetOptions_(viewport_, options), "slNISSetOptions");
    const sl::BaseStructure* evaluationInputs[] = {&viewport_, &tags[0], &tags[1]};
    streamline::requireOk(
            evaluateFeature_(
                    sl::kFeatureNIS,
                    *token,
                    evaluationInputs,
                    static_cast<std::uint32_t>(std::size(evaluationInputs)),
                    nativeCommandBuffer
            ),
            "slEvaluateFeature(NIS)"
    );
}

std::array<std::int32_t, 2> StreamlineReconstructionExecutor::dlssOptimalSettings(
        std::int32_t quality,
        std::int32_t outputWidth,
        std::int32_t outputHeight
) {
    if (dlssOptimalSettings_ == nullptr || outputWidth <= 0 || outputHeight <= 0) {
        throw std::runtime_error("Streamline DLSS optimal-settings runtime is not device-ready");
    }
    sl::DLSSOptions options = {};
    options.mode = dlssMode(0, quality);
    options.outputWidth = static_cast<uint32_t>(outputWidth);
    options.outputHeight = static_cast<uint32_t>(outputHeight);
    options.colorBuffersHDR = sl::Boolean::eTrue;
    sl::DLSSOptimalSettings settings = {};
    streamline::requireOk(dlssOptimalSettings_(options, settings), "slDLSSGetOptimalSettings");
    if (settings.optimalRenderWidth == 0 || settings.optimalRenderHeight == 0
            || settings.optimalRenderWidth > static_cast<uint32_t>(outputWidth)
            || settings.optimalRenderHeight > static_cast<uint32_t>(outputHeight)) {
        throw std::runtime_error("Streamline returned an invalid DLSS render extent");
    }
    return {
            static_cast<std::int32_t>(settings.optimalRenderWidth),
            static_cast<std::int32_t>(settings.optimalRenderHeight)
    };
}

sl::DLSSMode StreamlineReconstructionExecutor::dlssMode(
        std::int32_t reconstructionMode,
        std::int32_t quality
) {
    if (reconstructionMode == 1) return sl::DLSSMode::eDLAA;
    switch (quality) {
        case 1: return sl::DLSSMode::eUltraQuality;
        case 2: return sl::DLSSMode::eMaxQuality;
        case 3: case 0: return sl::DLSSMode::eBalanced;
        case 4: return sl::DLSSMode::eMaxPerformance;
        case 5: return sl::DLSSMode::eUltraPerformance;
        default: throw std::invalid_argument("unsupported DLSS quality");
    }
}

float StreamlineReconstructionExecutor::nisSharpness(std::int32_t quality) {
    switch (quality) {
        case 1: return 0.10f;
        case 2: return 0.15f;
        case 3: case 0: return 0.20f;
        case 4: return 0.25f;
        case 5: return 0.30f;
        default: throw std::invalid_argument("unsupported NIS quality");
    }
}

}
