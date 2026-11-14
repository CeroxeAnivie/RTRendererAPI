package top.ceroxe.rt.renderer.rt.pipeline;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.rt.device.RtGpuImage;

import java.util.Objects;

/**
 * Native RT command-recording primitives with explicit image layouts and synchronization edges.
 */
final class RtFrameDispatchCommands {
    private RtFrameDispatchCommands() {
    }

    static void recordFrameReadback(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            RtGpuImage outputImage,
            RtGpuBuffer readbackBuffer
    ) {
        recordImageLayoutTransition(
                commandBuffer, stack, outputImage.image(), VK10.VK_IMAGE_LAYOUT_GENERAL,
                VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK10.VK_ACCESS_SHADER_WRITE_BIT,
                VK10.VK_ACCESS_TRANSFER_READ_BIT, KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
        );
        recordImageToBufferCopy(commandBuffer, stack, outputImage, readbackBuffer.buffer(), outputImage.width(), outputImage.height());
        recordMemoryBarrier(
                commandBuffer, stack, VK10.VK_ACCESS_TRANSFER_WRITE_BIT, VK10.VK_ACCESS_HOST_READ_BIT,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_PIPELINE_STAGE_HOST_BIT
        );
    }

    static void recordTraceRays(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            long pipelineLayout,
            long pipeline,
            long descriptorSet,
            RtGpuBuffer shaderBindingTableBuffer,
            int shaderBindingTableBaseOffsetBytes,
            RtRayTracingPipelineProperties.ShaderBindingTableLayout layout,
            int width,
            int height
    ) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("trace dimensions must be positive");
        }
        VK10.vkCmdBindPipeline(commandBuffer, KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipeline);
        VK10.vkCmdBindDescriptorSets(
                commandBuffer, KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,
                pipelineLayout, 0, stack.longs(descriptorSet), null
        );
        VkStridedDeviceAddressRegionKHR raygen = sbtRegion(
                stack, shaderBindingTableBuffer, shaderBindingTableBaseOffsetBytes, layout, layout.raygen());
        VkStridedDeviceAddressRegionKHR miss = sbtRegion(
                stack, shaderBindingTableBuffer, shaderBindingTableBaseOffsetBytes, layout, layout.miss());
        VkStridedDeviceAddressRegionKHR hit = sbtRegion(
                stack, shaderBindingTableBuffer, shaderBindingTableBaseOffsetBytes, layout, layout.hit());
        KHRRayTracingPipeline.vkCmdTraceRaysKHR(
                commandBuffer, raygen, miss, hit, VkStridedDeviceAddressRegionKHR.calloc(stack), width, height, 1
        );
    }

    static void recordImageToBufferCopy(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            RtGpuImage sourceImage,
            long destinationBuffer,
            int copyWidth,
            int copyHeight
    ) {
        if (copyWidth <= 0 || copyHeight <= 0) {
            throw new IllegalArgumentException("copy dimensions must be positive");
        }
        if (copyWidth > sourceImage.width() || copyHeight > sourceImage.height()) {
            throw new IllegalArgumentException("copy dimensions exceed source image");
        }
        VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack);
        copy.get(0).bufferOffset(0L).bufferRowLength(0).bufferImageHeight(0);
        copy.get(0).imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        copy.get(0).imageOffset().x(0).y(0).z(0);
        copy.get(0).imageExtent().width(copyWidth).height(copyHeight).depth(1);
        VK10.vkCmdCopyImageToBuffer(
                commandBuffer, sourceImage.image(), VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, destinationBuffer, copy
        );
    }

    static void recordDiagnosticGBufferReadback(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            RtGpuBuffer diagnosticGBuffer,
            RtGpuBuffer readbackBuffer
    ) {
        if (diagnosticGBuffer.sizeBytes() != readbackBuffer.sizeBytes()) {
            throw new IllegalArgumentException("diagnostic G-buffer and readback buffer sizes must match");
        }
        recordMemoryBarrier(
                commandBuffer, stack, VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_TRANSFER_READ_BIT,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
        );
        VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack);
        copy.get(0).srcOffset(0L).dstOffset(0L).size(diagnosticGBuffer.sizeBytes());
        VK10.vkCmdCopyBuffer(commandBuffer, diagnosticGBuffer.buffer(), readbackBuffer.buffer(), copy);
        recordMemoryBarrier(
                commandBuffer, stack, VK10.VK_ACCESS_TRANSFER_WRITE_BIT, VK10.VK_ACCESS_HOST_READ_BIT,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_PIPELINE_STAGE_HOST_BIT
        );
    }

    static void recordImageBlit(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            RtGpuImage sourceImage,
            RtGpuImage destinationImage
    ) {
        Objects.requireNonNull(sourceImage, "sourceImage");
        Objects.requireNonNull(destinationImage, "destinationImage");
        VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
        blit.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        blit.get(0).srcOffsets(0).x(0).y(0).z(0);
        blit.get(0).srcOffsets(1).x(sourceImage.width()).y(sourceImage.height()).z(1);
        blit.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        blit.get(0).dstOffsets(0).x(0).y(0).z(0);
        blit.get(0).dstOffsets(1).x(destinationImage.width()).y(destinationImage.height()).z(1);
        VK10.vkCmdBlitImage(
                commandBuffer, sourceImage.image(), VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                destinationImage.image(), VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, VK10.VK_FILTER_LINEAR
        );
    }

    static void recordImageLayoutTransition(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            long image,
            int oldLayout,
            int newLayout,
            int sourceAccessMask,
            int destinationAccessMask,
            int sourceStageMask,
            int destinationStageMask
    ) {
        recordImageLayoutTransition(
                commandBuffer,
                stack,
                image,
                oldLayout,
                newLayout,
                sourceAccessMask,
                destinationAccessMask,
                sourceStageMask,
                destinationStageMask,
                VK10.VK_QUEUE_FAMILY_IGNORED,
                VK10.VK_QUEUE_FAMILY_IGNORED
        );
    }

    static void recordImageLayoutTransition(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            long image,
            int oldLayout,
            int newLayout,
            int sourceAccessMask,
            int destinationAccessMask,
            int sourceStageMask,
            int destinationStageMask,
            int sourceQueueFamilyIndex,
            int destinationQueueFamilyIndex
    ) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
        barrier.get(0).sType$Default().srcAccessMask(sourceAccessMask).dstAccessMask(destinationAccessMask)
                .oldLayout(oldLayout).newLayout(newLayout).srcQueueFamilyIndex(sourceQueueFamilyIndex)
                .dstQueueFamilyIndex(destinationQueueFamilyIndex).image(image);
        barrier.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        VK10.vkCmdPipelineBarrier(commandBuffer, sourceStageMask, destinationStageMask, 0, null, null, barrier);
    }

    static void recordMemoryBarrier(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            int sourceAccessMask,
            int destinationAccessMask,
            int sourceStageMask,
            int destinationStageMask
    ) {
        VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
        barrier.get(0).sType$Default().srcAccessMask(sourceAccessMask).dstAccessMask(destinationAccessMask);
        VK10.vkCmdPipelineBarrier(commandBuffer, sourceStageMask, destinationStageMask, 0, barrier, null, null);
    }

    private static VkStridedDeviceAddressRegionKHR sbtRegion(
            MemoryStack stack,
            RtGpuBuffer buffer,
            int baseOffsetBytes,
            RtRayTracingPipelineProperties.ShaderBindingTableLayout layout,
            RtRayTracingPipelineProperties.Region region
    ) {
        long address = checkedAdd(checkedAdd(buffer.deviceAddress(), baseOffsetBytes), region.offsetBytes());
        requireAligned(address, layout.baseAlignmentBytes(), "SBT region deviceAddress");
        return VkStridedDeviceAddressRegionKHR.calloc(stack)
                .deviceAddress(address).stride(region.strideBytes()).size(region.sizeBytes());
    }

    private static long checkedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("device address range overflows", overflow);
        }
    }

    private static void requireAligned(long value, int alignment, String label) {
        if (alignment <= 0 || value < 0L || value % alignment != 0L) {
            throw new IllegalArgumentException(label + " must be non-negative and aligned to " + alignment);
        }
    }
}
