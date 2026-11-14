package top.ceroxe.rt.renderer.backend.vulkan;

import java.util.Objects;
import top.ceroxe.rt.renderer.api.FrameValidationException;
import top.ceroxe.rt.renderer.rt.pipeline.VulkanFrameExtents;

import static top.ceroxe.rt.renderer.api.FrameValidationException.Reason.OUTPUT_EXTENT_EXCEEDS_DEVICE_LIMIT;
import static top.ceroxe.rt.renderer.api.FrameValidationException.Reason.RAY_DISPATCH_EXCEEDS_DEVICE_LIMIT;

/**
 * Immutable physical-device limits applied before allocating or dispatching a frame.
 */
record VulkanFrameAdmissionLimits(
        int maxImageDimension2D,
        long maxRayDispatchInvocationCount
) {
    VulkanFrameAdmissionLimits {
        if (maxImageDimension2D <= 0 || maxRayDispatchInvocationCount <= 0L) {
            throw new IllegalArgumentException("Vulkan frame admission limits must be positive");
        }
    }

    void validate(int width, int height) {
        validate(VulkanFrameExtents.identity(width, height));
    }

    void validate(VulkanFrameExtents extents) {
        VulkanFrameExtents checked = Objects.requireNonNull(extents, "extents");
        int width = checked.outputWidth();
        int height = checked.outputHeight();
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("frame extent must be positive");
        }
        if (width > maxImageDimension2D || height > maxImageDimension2D) {
            throw new FrameValidationException(
                    OUTPUT_EXTENT_EXCEEDS_DEVICE_LIMIT,
                    "frame extent " + width + 'x' + height
                            + " exceeds VkPhysicalDeviceLimits.maxImageDimension2D=" + maxImageDimension2D
            );
        }
        long invocations = checked.renderPixelCount();
        if (invocations > maxRayDispatchInvocationCount) {
            throw new FrameValidationException(
                    RAY_DISPATCH_EXCEEDS_DEVICE_LIMIT,
                    "ray dispatch invocations " + invocations
                            + " exceed maxRayDispatchInvocationCount=" + maxRayDispatchInvocationCount
            );
        }
    }
}
