package top.ceroxe.rt.renderer.rt.material;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;

import java.util.List;
import java.util.Objects;

/**
 * Records bounded Vulkan material-copy batches and their RT shader-read barrier.
 */
final class RtMaterialUploadCommandRecorder {
    private static final int MAX_COPY_REGIONS_PER_COMMAND = 256;

    private RtMaterialUploadCommandRecorder() {
    }

    static void recordBufferCopy(
            VkCommandBuffer commandBuffer,
            long sourceBuffer,
            long targetBuffer,
            long sourceOffset,
            long targetOffset,
            long bytes,
            MemoryStack stack
    ) {
        Objects.requireNonNull(commandBuffer, "commandBuffer");
        Objects.requireNonNull(stack, "stack");
        if (sourceBuffer == 0L || targetBuffer == 0L) {
            throw new IllegalArgumentException("material copy buffers must not be null");
        }
        if (sourceOffset < 0L || targetOffset < 0L) {
            throw new IllegalArgumentException("material copy offsets must not be negative");
        }
        if (bytes <= 0L) {
            return;
        }
        VkBufferCopy.Buffer copyRegion = VkBufferCopy.calloc(1, stack);
        copyRegion.get(0).srcOffset(sourceOffset).dstOffset(targetOffset).size(bytes);
        VK10.vkCmdCopyBuffer(commandBuffer, sourceBuffer, targetBuffer, copyRegion);
    }

    static void recordBufferCopies(
            VkCommandBuffer commandBuffer,
            RtGpuBuffer sourceBuffer,
            long targetBuffer,
            List<RtMaterialDirtyUploadPlan.CopyRange> ranges
    ) {
        Objects.requireNonNull(commandBuffer, "commandBuffer");
        Objects.requireNonNull(ranges, "ranges");
        if (targetBuffer == 0L) {
            throw new IllegalArgumentException("material copy target buffer must not be null");
        }
        if (sourceBuffer == null || ranges.isEmpty()) {
            return;
        }
        for (int first = 0; first < ranges.size(); first += MAX_COPY_REGIONS_PER_COMMAND) {
            int count = Math.min(MAX_COPY_REGIONS_PER_COMMAND, ranges.size() - first);
            /* Keep native stack use bounded for thousands of sparse dirty ranges. */
            try (MemoryStack copyStack = MemoryStack.stackPush()) {
                VkBufferCopy.Buffer copyRegions = VkBufferCopy.calloc(count, copyStack);
                for (int offset = 0; offset < count; offset++) {
                    RtMaterialDirtyUploadPlan.CopyRange range = ranges.get(first + offset);
                    copyRegions.get(offset)
                            .srcOffset(range.sourceOffsetBytes())
                            .dstOffset(range.targetOffsetBytes())
                            .size(range.byteCount());
                }
                VK10.vkCmdCopyBuffer(commandBuffer, sourceBuffer.buffer(), targetBuffer, copyRegions);
            }
        }
    }

    static void recordShaderReadBarrier(VkCommandBuffer commandBuffer, MemoryStack stack) {
        Objects.requireNonNull(commandBuffer, "commandBuffer");
        Objects.requireNonNull(stack, "stack");
        VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
        barrier.get(0)
                .sType$Default()
                .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT);
        VK10.vkCmdPipelineBarrier(
                commandBuffer,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                0,
                barrier,
                null,
                null
        );
    }
}
