package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.util.Objects;

/** Records bounded {@code vkCmdUpdateBuffer} uploads without retaining native staging memory. */
final class RtCommandBufferUploads {
    static final int MAX_UPDATE_BYTES = 65_536;

    private RtCommandBufferUploads() {
    }

    static void recordBytes(VkCommandBuffer commandBuffer, long buffer, long targetOffsetBytes, byte[] values) {
        Objects.requireNonNull(commandBuffer, "commandBuffer");
        Objects.requireNonNull(values, "values");
        if (buffer == 0L) {
            throw new IllegalArgumentException("buffer must not be null");
        }
        if ((targetOffsetBytes & 3L) != 0L || (values.length & 3) != 0) {
            throw new IllegalArgumentException("vkCmdUpdateBuffer requires 4-byte aligned offset and byte count");
        }
        if (values.length == 0) {
            return;
        }

        ByteBuffer upload = MemoryUtil.memAlloc(values.length);
        try {
            upload.put(values).flip();
            for (int offset = 0; offset < values.length; offset += MAX_UPDATE_BYTES) {
                int count = Math.min(MAX_UPDATE_BYTES, values.length - offset);
                ByteBuffer chunk = upload.duplicate();
                chunk.position(offset);
                chunk.limit(offset + count);
                VK10.vkCmdUpdateBuffer(
                        commandBuffer,
                        buffer,
                        checkedAdd(targetOffsetBytes, offset),
                        chunk.slice()
                );
            }
        } finally {
            MemoryUtil.memFree(upload);
        }
    }

    private static long checkedAdd(long left, long right) {
        long result = left + right;
        if (((left ^ result) & (right ^ result)) < 0L) {
            throw new IllegalArgumentException("buffer offset overflow");
        }
        return result;
    }
}
