package top.ceroxe.mcvulkanrt.renderer.backend.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryBarrier;
import top.ceroxe.mcvulkanrt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.mcvulkanrt.renderer.rt.device.RtGpuImage;
import top.ceroxe.mcvulkanrt.renderer.rt.device.VulkanDeviceRuntime;

import java.util.Objects;

/** Explicit opt-in readback used only by renderer-core visual acceptance gates. */
final class VulkanFrameDiagnosticReadback {
    private VulkanFrameDiagnosticReadback() {
    }

    static byte[] capture(VulkanDeviceRuntime device, RtGpuImage image) {
        VulkanDeviceRuntime checkedDevice = Objects.requireNonNull(device, "device");
        RtGpuImage checkedImage = Objects.requireNonNull(image, "image");
        long bytes = Math.multiplyExact(Math.multiplyExact(
                (long) checkedImage.width(), checkedImage.height()), Integer.BYTES);
        try (RtGpuBuffer readback = RtGpuBuffer.createHostVisibleBuffer(
                checkedDevice.device(),
                checkedDevice.allocator(),
                bytes,
                VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                checkedDevice.frameCommands().stallTelemetry()
        )) {
            checkedDevice.frameCommands().submitOneTime(
                    (commandBuffer, stack) -> record(commandBuffer, stack, checkedImage, readback)
            );
            return readback.readBytes(bytes);
        }
    }

    private static void record(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            RtGpuImage image,
            RtGpuBuffer readback
    ) {
        imageBarrier(
                commandBuffer, stack, image.image(),
                VK10.VK_IMAGE_LAYOUT_GENERAL, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_TRANSFER_READ_BIT,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
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
                VK10.VK_ACCESS_TRANSFER_READ_BIT, VK10.VK_ACCESS_SHADER_WRITE_BIT,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
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
            int destinationStage
    ) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
        barrier.get(0)
                .sType$Default()
                .srcAccessMask(sourceAccess)
                .dstAccessMask(destinationAccess)
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
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
