package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryBarrier;
import top.ceroxe.rt.renderer.api.TextureAspect;
import top.ceroxe.rt.renderer.api.TextureFormat;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

import java.util.Objects;

/** Bounded synchronous readback for completed generic RGBA8 color outputs. */
final class VulkanGenericCpuFrameReadback {
    private VulkanGenericCpuFrameReadback() { }

    static byte[] capture(VulkanDeviceRuntime device, VulkanGenericResourceRegistry.TextureRecord record) {
        VulkanDeviceRuntime checkedDevice = Objects.requireNonNull(device, "device");
        VulkanGenericResourceRegistry.TextureRecord checked = Objects.requireNonNull(record, "record");
        var descriptor = checked.descriptor();
        if (descriptor.dimension() != top.ceroxe.rt.renderer.api.TextureDimension.TEXTURE_2D
                || descriptor.sampleCount() != 1 || descriptor.mipLevelCount() != 1
                || descriptor.arrayLayerCount() != 1) {
            throw new IllegalArgumentException("generic CPU output requires a single-sample 2D texture");
        }
        if (descriptor.format() != TextureFormat.RGBA8_UNORM
                && descriptor.format() != TextureFormat.RGBA8_SRGB) {
            throw new IllegalArgumentException("generic CPU output requires an RGBA8 texture format");
        }
        long byteCount = Math.multiplyExact(Math.multiplyExact((long) descriptor.width(), descriptor.height()), 4L);
        int oldLayout = checked.layouts().layout(TextureAspect.COLOR, 0, 0);
        try (RtGpuBuffer readback = RtGpuBuffer.createHostVisibleBuffer(
                checkedDevice.device(), checkedDevice.allocator(), byteCount,
                VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, checkedDevice.frameCommands().stallTelemetry())) {
            checkedDevice.frameCommands().submitOneTime((commandBuffer, stack) -> record(
                    commandBuffer, stack, checked, readback, oldLayout
            ));
            return readback.readBytes(byteCount);
        }
    }

    private static void record(
            VkCommandBuffer commandBuffer, MemoryStack stack,
            VulkanGenericResourceRegistry.TextureRecord record, RtGpuBuffer readback, int oldLayout
    ) {
        transition(commandBuffer, stack, record, oldLayout, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                stage(oldLayout), VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                access(oldLayout), VK10.VK_ACCESS_TRANSFER_READ_BIT);
        VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack);
        copy.get(0).bufferOffset(0L).bufferRowLength(0).bufferImageHeight(0);
        copy.get(0).imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0)
                .baseArrayLayer(0).layerCount(1);
        copy.get(0).imageOffset().set(0, 0, 0);
        copy.get(0).imageExtent().set(record.descriptor().width(), record.descriptor().height(), 1);
        VK10.vkCmdCopyImageToBuffer(commandBuffer, record.image().image(),
                VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, readback.buffer(), copy);
        transition(commandBuffer, stack, record, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, oldLayout,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, stage(oldLayout),
                VK10.VK_ACCESS_TRANSFER_READ_BIT, access(oldLayout));
        VkMemoryBarrier.Buffer hostRead = VkMemoryBarrier.calloc(1, stack);
        hostRead.get(0).sType$Default()
                .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_HOST_READ_BIT);
        VK10.vkCmdPipelineBarrier(commandBuffer, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK10.VK_PIPELINE_STAGE_HOST_BIT, 0, hostRead, null, null);
    }

    private static void transition(
            VkCommandBuffer commandBuffer, MemoryStack stack,
            VulkanGenericResourceRegistry.TextureRecord record,
            int oldLayout, int newLayout, int sourceStage, int destinationStage,
            int sourceAccess, int destinationAccess
    ) {
        if (oldLayout == newLayout) return;
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
        barrier.get(0).sType$Default().oldLayout(oldLayout).newLayout(newLayout)
                .srcAccessMask(sourceAccess).dstAccessMask(destinationAccess)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .image(record.image().image());
        barrier.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        VK10.vkCmdPipelineBarrier(commandBuffer, sourceStage, destinationStage, 0,
                null, null, barrier);
    }

    private static int stage(int layout) {
        return switch (layout) {
            case VK10.VK_IMAGE_LAYOUT_UNDEFINED -> VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            case VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL -> VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            case VK10.VK_IMAGE_LAYOUT_GENERAL -> VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
            case VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
            default -> VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
        };
    }

    private static int access(int layout) {
        return switch (layout) {
            case VK10.VK_IMAGE_LAYOUT_UNDEFINED -> 0;
            case VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL ->
                    VK10.VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            case VK10.VK_IMAGE_LAYOUT_GENERAL -> VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT;
            case VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> VK10.VK_ACCESS_TRANSFER_READ_BIT;
            default -> VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT;
        };
    }
}
