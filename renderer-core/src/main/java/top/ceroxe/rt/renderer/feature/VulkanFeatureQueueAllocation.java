package top.ceroxe.rt.renderer.feature;

/**
 * Concrete logical-device queues reserved for one optional Vulkan provider.
 *
 * <p>Queue requirements alone are insufficient after {@code vkCreateDevice}: manual SDKs such
 * as Streamline need the exact queue indices and families passed to their device handoff. This
 * value keeps that result provider-scoped rather than coupling device bootstrap to a vendor.</p>
 *
 * @param graphicsQueueIndex reserved graphics queue index, or {@code -1} when absent
 * @param graphicsQueueFamily graphics queue family index, or {@code -1} when absent
 * @param computeQueueIndex reserved compute queue index, or {@code -1} when absent
 * @param computeQueueFamily compute queue family index, or {@code -1} when absent
 * @param opticalFlowQueueIndex reserved optical-flow queue index, or {@code -1} when absent
 * @param opticalFlowQueueFamily optical-flow queue family index, or {@code -1} when absent
 */
public record VulkanFeatureQueueAllocation(
        int graphicsQueueIndex,
        int graphicsQueueFamily,
        int computeQueueIndex,
        int computeQueueFamily,
        int opticalFlowQueueIndex,
        int opticalFlowQueueFamily
) {
    /** A provider without queue requirements uses no allocation. */
    public static final VulkanFeatureQueueAllocation NONE = new VulkanFeatureQueueAllocation(-1, -1, -1, -1, -1, -1);

    /** Validates that every queue index/family pair is either assigned or absent together. */
    public VulkanFeatureQueueAllocation {
        validatePair(graphicsQueueIndex, graphicsQueueFamily, "graphics");
        validatePair(computeQueueIndex, computeQueueFamily, "compute");
        validatePair(opticalFlowQueueIndex, opticalFlowQueueFamily, "opticalFlow");
    }

    private static void validatePair(int index, int family, String role) {
        if ((index < 0) != (family < 0)) {
            throw new IllegalArgumentException(role + " queue index and family must be both assigned or both absent");
        }
    }
}
