package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;

import java.util.Objects;

/**
 * Fail-fast storage-format gate for renderer-private temporal images.
 */
final class VulkanTemporalImageSupport {
    static final int HISTORY_FORMAT = VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
    static final int MOTION_FORMAT = VK10.VK_FORMAT_R16G16_SFLOAT;

    private VulkanTemporalImageSupport() {
    }

    static void requireSupported(VkPhysicalDevice physicalDevice) {
        VkPhysicalDevice checked = Objects.requireNonNull(physicalDevice, "physicalDevice");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            requireStorageImage(stack, checked, HISTORY_FORMAT, "RGBA16F temporal history");
            requireStorageImage(stack, checked, MOTION_FORMAT, "RG16F motion vectors");
        }
    }

    private static void requireStorageImage(
            MemoryStack stack,
            VkPhysicalDevice physicalDevice,
            int format,
            String label
    ) {
        VkFormatProperties properties = VkFormatProperties.calloc(stack);
        VK10.vkGetPhysicalDeviceFormatProperties(physicalDevice, format, properties);
        if ((properties.optimalTilingFeatures() & VK10.VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) == 0) {
            throw new IllegalStateException(
                    "selected device does not support optimal-tiled storage images for " + label
            );
        }
    }
}
