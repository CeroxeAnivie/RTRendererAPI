#include "streamline_frame_support.hpp"

#include <cstddef>
#include <stdexcept>
#include <string>

#include <vulkan/vulkan.h>
#include <sl_consts.h>

#include "streamline_diagnostics.hpp"

namespace rtrenderer::nvidia::streamline {
namespace {

bool validImageMetadata(const StreamlineImage& value) noexcept {
    return value.image != 0 && value.memory != 0 && value.view != 0
            && value.width > 0 && value.height > 0;
}

void copyMatrix(sl::float4x4& target, const std::array<float, 16>& source) noexcept {
    for (std::size_t row = 0; row < 4; row++) {
        target.row[row] = {
                source[row * 4], source[row * 4 + 1],
                source[row * 4 + 2], source[row * 4 + 3]
        };
    }
}

}

bool validSampledImage(const StreamlineImage& image) noexcept {
    return validImageMetadata(image) && (image.usage & VK_IMAGE_USAGE_SAMPLED_BIT) != 0;
}

bool validStorageImage(const StreamlineImage& image) noexcept {
    return validImageMetadata(image) && (image.usage & VK_IMAGE_USAGE_STORAGE_BIT) != 0;
}

sl::Resource resource(const StreamlineImage& value) noexcept {
    sl::Resource result(
            sl::ResourceType::eTex2d,
            reinterpret_cast<void*>(value.image),
            reinterpret_cast<void*>(value.memory),
            reinterpret_cast<void*>(value.view),
            VK_IMAGE_LAYOUT_GENERAL
    );
    result.width = static_cast<uint32_t>(value.width);
    result.height = static_cast<uint32_t>(value.height);
    result.nativeFormat = static_cast<uint32_t>(value.format);
    result.mipLevels = 1;
    result.arrayLayers = 1;
    // Streamline 2.12 leaves flags uninitialized in this constructor. Publishing every Vulkan
    // field here prevents SDK behavior from depending on stack contents.
    result.gpuVirtualAddress = 0;
    result.flags = 0;
    result.usage = static_cast<uint32_t>(value.usage);
    result.reserved = 0;
    return result;
}

void setConstantsForFrame(
        PFun_slSetConstants* setConstants,
        const sl::ViewportHandle& viewport,
        sl::FrameToken& token,
        const StreamlineFrame& frame
) {
    if (setConstants == nullptr) {
        throw std::runtime_error("Streamline frame constants function is unavailable");
    }
    sl::Constants constants = {};
    copyMatrix(constants.cameraViewToClip, frame.viewToClip);
    copyMatrix(constants.clipToCameraView, frame.clipToView);
    copyMatrix(constants.clipToPrevClip, frame.clipToPrevious);
    copyMatrix(constants.prevClipToClip, frame.previousToClip);
    // The renderer carries the actual sample displacement (positive means sampling to the right
    // of pixel center). Streamline forwards this field directly to NGX, whose contract expects
    // the inverse offset that de-jitters the input image.
    constants.jitterOffset = {-frame.jitterX, -frame.jitterY};
    constants.mvecScale = {frame.motionScaleX, frame.motionScaleY};
    constants.cameraPos = {frame.position[0], frame.position[1], frame.position[2]};
    constants.cameraUp = {frame.up[0], frame.up[1], frame.up[2]};
    constants.cameraRight = {frame.right[0], frame.right[1], frame.right[2]};
    constants.cameraFwd = {frame.forward[0], frame.forward[1], frame.forward[2]};
    constants.cameraNear = frame.nearPlane;
    constants.cameraFar = frame.farPlane;
    constants.cameraFOV = frame.fovRadians;
    constants.cameraAspectRatio = frame.aspectRatio;
    constants.depthInverted = sl::Boolean::eFalse;
    constants.cameraMotionIncluded = sl::Boolean::eTrue;
    constants.motionVectors3D = sl::Boolean::eFalse;
    // GPUScene projects the same world point through non-jittered current/previous cameras.
    // Jitter remains a separate Streamline constant and must not be applied to the MV twice.
    constants.motionVectorsJittered = sl::Boolean::eFalse;
    constants.reset = frame.reset ? sl::Boolean::eTrue : sl::Boolean::eFalse;
    requireOk(setConstants(constants, token, viewport), "slSetConstants");
}

void requireOk(sl::Result result, const char* operation) {
    if (result != sl::Result::eOk) {
        throw std::runtime_error(
                std::string(operation) + "=" + streamlineResultName(result)
                        + "; Streamline diagnostic: " + currentStreamlineDiagnostic()
        );
    }
}

}
