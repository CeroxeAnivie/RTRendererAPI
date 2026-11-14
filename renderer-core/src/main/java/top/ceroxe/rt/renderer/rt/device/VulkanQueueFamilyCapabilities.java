package top.ceroxe.rt.renderer.rt.device;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.nio.IntBuffer;
import java.util.Objects;

/**
 * Canonical queue-family selection and stable capabilities used by probing and device creation.
 *
 * @param index              physical-device queue-family index
 * @param availableQueues    number of queues exposed by the family
 * @param timestampValidBits number of valid timestamp bits
 */
public record VulkanQueueFamilyCapabilities(
        int index,
        int availableQueues,
        int timestampValidBits
) {
    /**
     * Validates queue availability and the Vulkan timestamp-bit range.
     */
    public VulkanQueueFamilyCapabilities {
        if (index < 0 || availableQueues <= 0) {
            throw new IllegalArgumentException("invalid queue family");
        }
        if (timestampValidBits < 0 || timestampValidBits > Long.SIZE) {
            throw new IllegalArgumentException("timestampValidBits must be in [0, 64]");
        }
    }

    /**
     * Selects the queue family used by the renderer.
     *
     * <p>Keeping selection here prevents capability discovery from advertising properties of a
     * different family than the one later used to create command queues.</p>
     *
     * @param stack  bounded native scratch allocator
     * @param device physical device to inspect
     * @return selected queue family and its stable limits
     */
    public static VulkanQueueFamilyCapabilities select(MemoryStack stack, VkPhysicalDevice device) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(device, "device");
        IntBuffer count = stack.ints(0);
        VK10.vkGetPhysicalDeviceQueueFamilyProperties(device, count, null);
        if (count.get(0) == 0) {
            throw new IllegalStateException("selected device reports no queue families");
        }
        VkQueueFamilyProperties.Buffer properties = VkQueueFamilyProperties.malloc(count.get(0), stack);
        VK10.vkGetPhysicalDeviceQueueFamilyProperties(device, count, properties);
        VulkanQueueFamilyCapabilities compute = null;
        VulkanQueueFamilyCapabilities graphics = null;
        for (int index = 0; index < properties.limit(); index++) {
            VkQueueFamilyProperties property = properties.get(index);
            boolean hasGraphics = (property.queueFlags() & VK10.VK_QUEUE_GRAPHICS_BIT) != 0;
            boolean hasCompute = (property.queueFlags() & VK10.VK_QUEUE_COMPUTE_BIT) != 0;
            VulkanQueueFamilyCapabilities candidate = new VulkanQueueFamilyCapabilities(
                    index, property.queueCount(), property.timestampValidBits()
            );
            if (hasGraphics && hasCompute) return candidate;
            if (hasCompute && compute == null) compute = candidate;
            if (hasGraphics && graphics == null) graphics = candidate;
        }
        if (compute != null) return compute;
        if (graphics != null) return graphics;
        throw new IllegalStateException("selected device has no graphics or compute queue family");
    }

    /**
     * Returns whether timestamp queries are valid on the selected renderer queue family.
     *
     * @return {@code true} when the queue exposes at least one valid timestamp bit
     */
    public boolean gpuTimestamps() {
        return timestampValidBits > 0;
    }
}
