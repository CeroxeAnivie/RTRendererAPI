package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.rt.device.RtGpuImage;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

import java.util.Objects;

/**
 * Explicit opt-in readback used only by renderer-core visual acceptance gates.
 */
final class VulkanFrameDiagnosticReadback {
    private VulkanFrameDiagnosticReadback() {
    }

    static byte[] capture(VulkanDeviceRuntime device, RtGpuImage image) {
        VulkanDeviceRuntime checkedDevice = Objects.requireNonNull(device, "device");
        RtGpuImage checkedImage = Objects.requireNonNull(image, "image");
        VulkanFrameOutput output = outputForVkFormat(checkedImage.format());
        long bytes = output.byteCount(checkedImage.width(), checkedImage.height());
        try (RtGpuBuffer readback = RtGpuBuffer.createHostVisibleBuffer(
                checkedDevice.device(),
                checkedDevice.allocator(),
                bytes,
                VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                checkedDevice.frameCommands().stallTelemetry()
        )) {
            checkedDevice.frameCommands().submitOneTime(
                    (commandBuffer, stack) -> record(
                            commandBuffer,
                            stack,
                            checkedImage,
                            readback,
                            checkedDevice.queueFamilyIndex()
                    )
            );
            byte[] nativeBytes = readback.readBytes(bytes);
            return output.linearHdr()
                    ? VulkanFramePixelCodec.convertLinearHdrRgba16fToSdrRgba8(nativeBytes)
                    : nativeBytes;
        }
    }

    private static VulkanFrameOutput outputForVkFormat(int format) {
        if (format == VK10.VK_FORMAT_R8G8B8A8_UNORM) {
            return VulkanFrameOutput.from(top.ceroxe.rt.renderer.api.FrameOutputFormat.SDR_RGBA8);
        }
        if (format == VK10.VK_FORMAT_R16G16B16A16_SFLOAT) {
            return VulkanFrameOutput.from(top.ceroxe.rt.renderer.api.FrameOutputFormat.LINEAR_HDR_RGBA16F);
        }
        throw new IllegalArgumentException("unsupported frame readback format: " + format);
    }

    private static void record(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            RtGpuImage image,
            RtGpuBuffer readback,
            int producerQueueFamilyIndex
    ) {
        imageBarrier(
                commandBuffer, stack, image.image(),
                VK10.VK_IMAGE_LAYOUT_GENERAL, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                0, VK10.VK_ACCESS_TRANSFER_READ_BIT,
                VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK11.VK_QUEUE_FAMILY_EXTERNAL,
                producerQueueFamilyIndex
        );
        VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack);
        copy.get(0).bufferOffset(0L).bufferRowLength(0).bufferImageHeight(0);
        copy.get(0).imageSubresource()
                .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0)
                .baseArrayLayer(0)
                .layerCount(1);
        copy.get(0).imageOffset().set(0, 0, 0);
        copy.get(0).imageExtent().set(image.width(), image.height(), 1);
        VK10.vkCmdCopyImageToBuffer(
                commandBuffer,
                image.image(),
                VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                readback.buffer(),
                copy
        );
        imageBarrier(
                commandBuffer, stack, image.image(),
                VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK10.VK_IMAGE_LAYOUT_GENERAL,
                VK10.VK_ACCESS_TRANSFER_READ_BIT, 0,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                producerQueueFamilyIndex,
                VK11.VK_QUEUE_FAMILY_EXTERNAL
        );
        VkMemoryBarrier.Buffer hostRead = VkMemoryBarrier.calloc(1, stack);
        hostRead.get(0).sType$Default()
                .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_HOST_READ_BIT);
        VK10.vkCmdPipelineBarrier(
                commandBuffer,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK10.VK_PIPELINE_STAGE_HOST_BIT,
                0,
                hostRead,
                null,
                null
        );
    }

    private static void imageBarrier(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            long image,
            int oldLayout,
            int newLayout,
            int sourceAccess,
            int destinationAccess,
            int sourceStage,
            int destinationStage,
            int sourceQueueFamilyIndex,
            int destinationQueueFamilyIndex
    ) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
        barrier.get(0)
                .sType$Default()
                .srcAccessMask(sourceAccess)
                .dstAccessMask(destinationAccess)
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(sourceQueueFamilyIndex)
                .dstQueueFamilyIndex(destinationQueueFamilyIndex)
                .image(image);
        barrier.get(0).subresourceRange()
                .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1);
        VK10.vkCmdPipelineBarrier(
                commandBuffer, sourceStage, destinationStage, 0, null, null, barrier
        );
    }
}
