package top.ceroxe.rt.renderer.backend.vulkan;

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

    void validate(int width, int height) throws VulkanRenderingSession.SubmissionRejectedException {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("frame extent must be positive");
        }
        if (width > maxImageDimension2D || height > maxImageDimension2D) {
            throw new VulkanRenderingSession.SubmissionRejectedException(
                    "frame extent " + width + 'x' + height
                            + " exceeds VkPhysicalDeviceLimits.maxImageDimension2D=" + maxImageDimension2D
            );
        }
        long invocations = (long) width * height;
        if (invocations > maxRayDispatchInvocationCount) {
            throw new VulkanRenderingSession.SubmissionRejectedException(
                    "ray dispatch invocations " + invocations
                            + " exceed maxRayDispatchInvocationCount=" + maxRayDispatchInvocationCount
            );
        }
    }
}
