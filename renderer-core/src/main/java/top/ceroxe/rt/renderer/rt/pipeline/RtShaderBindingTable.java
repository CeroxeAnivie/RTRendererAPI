package top.ceroxe.rt.renderer.rt.pipeline;

import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import top.ceroxe.rt.renderer.rt.device.RtCommandContext;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;

import java.util.Objects;

/**
 * Owns the uploaded SBT buffer together with the only layout and aligned base offset valid for it.
 */
final class RtShaderBindingTable implements AutoCloseable {
    private final RtGpuBuffer buffer;
    private final int baseOffsetBytes;
    private final RtRayTracingPipelineProperties.ShaderBindingTableLayout layout;

    private RtShaderBindingTable(
            RtGpuBuffer buffer,
            int baseOffsetBytes,
            RtRayTracingPipelineProperties.ShaderBindingTableLayout layout
    ) {
        this.buffer = Objects.requireNonNull(buffer, "buffer");
        if (baseOffsetBytes < 0) {
            throw new IllegalArgumentException("baseOffsetBytes must not be negative");
        }
        this.baseOffsetBytes = baseOffsetBytes;
        this.layout = Objects.requireNonNull(layout, "layout");
    }

    static RtShaderBindingTable create(
            VkDevice device,
            long allocator,
            RtCommandContext commandContext,
            byte[] packedTable,
            RtRayTracingPipelineProperties.ShaderBindingTableLayout layout
    ) {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(commandContext, "commandContext");
        Objects.requireNonNull(packedTable, "packedTable");
        Objects.requireNonNull(layout, "layout");
        if (packedTable.length != layout.totalBytes()) {
            throw new IllegalArgumentException("packed SBT length must match layout totalBytes");
        }

        long bufferBytes = checkedAdd(packedTable.length, layout.baseAlignmentBytes());
        RtGpuBuffer buffer = RtGpuBuffer.createDeviceAddressBuffer(
                device,
                allocator,
                bufferBytes,
                VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                        | KHRRayTracingPipeline.VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR,
                commandContext.stallTelemetry()
        );
        boolean transferred = false;
        try {
            int baseOffsetBytes = alignedBaseOffset(buffer.deviceAddress(), layout.baseAlignmentBytes());
            validateRange(baseOffsetBytes, packedTable.length, buffer.sizeBytes());
            commandContext.submitOneTime((commandBuffer, stack) -> {
                RtCommandBufferUploads.recordBytes(commandBuffer, buffer.buffer(), baseOffsetBytes, packedTable);
                RtFrameDispatchCommands.recordMemoryBarrier(
                        commandBuffer,
                        stack,
                        VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK10.VK_ACCESS_SHADER_READ_BIT,
                        VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                );
            });
            RtShaderBindingTable result = new RtShaderBindingTable(buffer, baseOffsetBytes, layout);
            transferred = true;
            return result;
        } finally {
            if (!transferred) {
                buffer.close();
            }
        }
    }

    private static int alignedBaseOffset(long deviceAddress, int alignmentBytes) {
        if (alignmentBytes <= 0) {
            throw new IllegalArgumentException("SBT base alignment must be positive");
        }
        long remainder = Long.remainderUnsigned(deviceAddress, alignmentBytes);
        long padding = remainder == 0L ? 0L : alignmentBytes - remainder;
        if (padding > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("SBT base offset outside int range: " + padding);
        }
        return (int) padding;
    }

    private static void validateRange(long offsetBytes, long sizeBytes, long bufferBytes) {
        if (offsetBytes < 0L || sizeBytes < 0L || bufferBytes < 0L) {
            throw new IllegalArgumentException("SBT upload range values must not be negative");
        }
        if (checkedAdd(offsetBytes, sizeBytes) > bufferBytes) {
            throw new IllegalStateException(
                    "SBT upload range exceeds buffer: offset=" + offsetBytes
                            + ", size=" + sizeBytes + ", buffer=" + bufferBytes
            );
        }
    }

    private static long checkedAdd(long left, long right) {
        long result = left + right;
        if (((left ^ result) & (right ^ result)) < 0L) {
            throw new IllegalArgumentException("SBT size overflow");
        }
        return result;
    }

    RtGpuBuffer buffer() {
        return buffer;
    }

    int baseOffsetBytes() {
        return baseOffsetBytes;
    }

    RtRayTracingPipelineProperties.ShaderBindingTableLayout layout() {
        return layout;
    }

    String layoutSummary() {
        return "sbtStride=" + layout.strideBytes()
                + ", sbtBytes=" + layout.totalBytes()
                + ", sbtBaseOffset=" + baseOffsetBytes
                + ", raygenOffset=" + layout.raygen().offsetBytes()
                + ", missOffset=" + layout.miss().offsetBytes()
                + ", hitOffset=" + layout.hit().offsetBytes();
    }

    String bufferSummary() {
        return buffer.summary("sbtBuffer");
    }

    /**
     * Releases the GPU buffer backing all shader-binding-table regions.
     */
    @Override
    public void close() {
        buffer.close();
    }
}
