package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.Objects;

/**
 * Fail-fast Vulkan format and Win32 export validation for public native frames.
 */
final class VulkanFrameOutputSupport {
    private static final int USAGE = VK10.VK_IMAGE_USAGE_STORAGE_BIT
            | VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT
            | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;

    private VulkanFrameOutputSupport() {
    }

    static void requireSupported(VkPhysicalDevice physicalDevice, VulkanFrameOutput output) {
        VkPhysicalDevice checkedDevice = Objects.requireNonNull(physicalDevice, "physicalDevice");
        VulkanFrameOutput checkedOutput = Objects.requireNonNull(output, "output");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFormatProperties formatProperties = VkFormatProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceFormatProperties(checkedDevice, checkedOutput.vkFormat(), formatProperties);
            if ((formatProperties.optimalTilingFeatures() & VK10.VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) == 0) {
                throw unsupported(checkedOutput, "optimal-tiled storage images are unavailable");
            }

            int handleType = VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
            VkPhysicalDeviceExternalImageFormatInfo externalInfo =
                    VkPhysicalDeviceExternalImageFormatInfo.calloc(stack)
                            .sType$Default()
                            .handleType(handleType);
            VkPhysicalDeviceImageFormatInfo2 formatInfo = VkPhysicalDeviceImageFormatInfo2.calloc(stack)
                    .sType$Default()
                    .pNext(externalInfo)
                    .format(checkedOutput.vkFormat())
                    .type(VK10.VK_IMAGE_TYPE_2D)
                    .tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
                    .usage(USAGE)
                    .flags(0);
            VkExternalImageFormatProperties externalProperties =
                    VkExternalImageFormatProperties.calloc(stack).sType$Default();
            VkImageFormatProperties2 imageProperties = VkImageFormatProperties2.calloc(stack)
                    .sType$Default()
                    .pNext(externalProperties);
            int result = VK11.vkGetPhysicalDeviceImageFormatProperties2(
                    checkedDevice, formatInfo, imageProperties
            );
            if (result != VK10.VK_SUCCESS) {
                throw unsupported(checkedOutput, "image usage/export query returned VkResult " + result);
            }
            VkExternalMemoryProperties memory = externalProperties.externalMemoryProperties();
            boolean compatible = (memory.compatibleHandleTypes() & handleType) != 0;
            boolean exportable = (memory.externalMemoryFeatures()
                    & VK11.VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT) != 0;
            if (!compatible || !exportable) {
                throw unsupported(checkedOutput, "OPAQUE_WIN32 export is unavailable");
            }
        }
    }

    private static IllegalStateException unsupported(VulkanFrameOutput output, String reason) {
        return new IllegalStateException(
                "selected device does not support " + output.apiFormat() + ": " + reason
        );
    }
}
