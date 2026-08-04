package top.ceroxe.rt.renderer.feature;

/**
 * Additional logical-device queues requested by an optional Vulkan feature.
 *
 * <p>The counts intentionally preserve Streamline's graphics, compute, and dedicated optical-flow
 * roles. They are additional to renderer-owned queues and compose additively across independently
 * enabled features, exactly as required by Streamline's manual Vulkan integration contract.</p>
 *
 * @param additionalGraphicsQueues non-negative provider-owned graphics queue count
 * @param additionalComputeQueues non-negative provider-owned compute queue count
 * @param additionalOpticalFlowQueues non-negative provider-owned optical-flow queue count
 */
public record VulkanQueueRequirements(
        int additionalGraphicsQueues,
        int additionalComputeQueues,
        int additionalOpticalFlowQueues
) {
    /** No provider-owned queues. */
    public static final VulkanQueueRequirements NONE = new VulkanQueueRequirements(0, 0, 0);

    /** Validates non-negative queue counts for every role. */
    public VulkanQueueRequirements {
        if (additionalGraphicsQueues < 0 || additionalComputeQueues < 0 || additionalOpticalFlowQueues < 0) {
            throw new IllegalArgumentException("additional Vulkan queue counts must not be negative");
        }
    }

    /**
     * Adds independently owned provider requirements without losing queue-role information.
     *
     * @param other non-null requirements to add
     * @return exact role-preserving sum
     */
    public VulkanQueueRequirements plus(VulkanQueueRequirements other) {
        if (other == null) throw new NullPointerException("other");
        return new VulkanQueueRequirements(
                Math.addExact(additionalGraphicsQueues, other.additionalGraphicsQueues),
                Math.addExact(additionalComputeQueues, other.additionalComputeQueues),
                Math.addExact(additionalOpticalFlowQueues, other.additionalOpticalFlowQueues)
        );
    }

    /**
     * Returns queues that can share the renderer's graphics/compute family.
     *
     * @return exact sum of additional graphics and compute queues
     */
    public int additionalGraphicsComputeQueues() {
        return Math.addExact(additionalGraphicsQueues, additionalComputeQueues);
    }

    /**
     * Returns whether no provider-owned queue must be created.
     *
     * @return {@code true} when all role counts are zero
     */
    public boolean isEmpty() {
        return additionalGraphicsQueues == 0 && additionalComputeQueues == 0 && additionalOpticalFlowQueues == 0;
    }
}
