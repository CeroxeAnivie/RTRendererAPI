package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.vulkan.VK10;
import top.ceroxe.rt.renderer.api.FrameOutputFormat;

import java.util.Objects;

/**
 * Internal Vulkan representation of one public frame-output policy.
 */
record VulkanFrameOutput(FrameOutputFormat apiFormat, int vkFormat, int bytesPerPixel, boolean linearHdr) {
    VulkanFrameOutput {
        Objects.requireNonNull(apiFormat, "apiFormat");
        if (bytesPerPixel <= 0) throw new IllegalArgumentException("bytesPerPixel must be positive");
    }

    static VulkanFrameOutput from(FrameOutputFormat format) {
        return switch (Objects.requireNonNull(format, "format")) {
            case SDR_RGBA8 -> new VulkanFrameOutput(
                    format, VK10.VK_FORMAT_R8G8B8A8_UNORM, 4, false
            );
            case LINEAR_HDR_RGBA16F -> new VulkanFrameOutput(
                    format, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, 8, true
            );
        };
    }

    long byteCount(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("frame extent must be positive");
        return Math.multiplyExact(Math.multiplyExact((long) width, height), bytesPerPixel);
    }
}
