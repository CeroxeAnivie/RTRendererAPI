package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;

import java.util.Objects;

/**
 * Capability gate for the renderer-owned images consumed by a radiance denoiser.
 *
 * <p>The check is intentionally kept next to the allocator instead of in the NVIDIA module.
 * The image formats are a renderer/Vulkan contract and must be valid before any optional native
 * provider can receive their handles.</p>
 */
final class VulkanDenoisingImageSupport {
    static final int NORMAL_ROUGHNESS_FORMAT = VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
    static final int VIEW_Z_FORMAT = VK10.VK_FORMAT_R32_SFLOAT;
    static final int MOTION_FORMAT = VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
    static final int RADIANCE_HIT_DISTANCE_FORMAT = VK10.VK_FORMAT_R16G16B16A16_SFLOAT;

    private VulkanDenoisingImageSupport() {
    }

    static void requireSupported(VkPhysicalDevice physicalDevice) {
        VkPhysicalDevice checked = Objects.requireNonNull(physicalDevice, "physicalDevice");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            requireStorageImage(stack, checked, NORMAL_ROUGHNESS_FORMAT, "R16G16B16A16 normal/roughness");
            requireStorageImage(stack, checked, VIEW_Z_FORMAT, "R32 view-Z");
            requireStorageImage(stack, checked, MOTION_FORMAT, "R16G16B16A16 2.5D motion vectors");
            requireStorageImage(
                    stack, checked, RADIANCE_HIT_DISTANCE_FORMAT, "R16G16B16A16 radiance/hit-distance"
            );
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
