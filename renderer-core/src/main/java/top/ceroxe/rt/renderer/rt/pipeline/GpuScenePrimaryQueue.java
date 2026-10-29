package top.ceroxe.rt.renderer.rt.pipeline;

import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;

/** Bounded direct-dispatch primary-hit producer/consumer experiment. No path or sample is dropped. */
public final class GpuScenePrimaryQueue {
    // std430 GpuScenePayload is 192 bytes; uvec4 identity is another 16.
    public static final int ITEM_BYTES = 208;
    public static final int MAX_ITEMS = 65_536;
    // Primary BDA, absolute first pixel/count, optional native-transmittance BDA, reserved.
    public static final int PUSH_BYTES = 32;

    private GpuScenePrimaryQueue() { }

    public static Plan plan(int width, int height, int samples) {
        if (width <= 0 || height <= 0 || samples < 1 || samples > 8)
            throw new IllegalArgumentException("positive extent and 1..8 samples required");
        int pixels = Math.toIntExact(Math.multiplyExact((long) width, height));
        int tilePixels = Math.min(pixels, MAX_ITEMS / samples);
        return new Plan(pixels, samples, tilePixels,
                Math.multiplyExact((long) tilePixels * samples, ITEM_BYTES));
    }

    /** Contains no GPU data and is safe to derive before a slot is reserved. */
    public record Plan(int pixels, int samples, int tilePixels, long bytes) {
        public Plan {
            if (pixels < 1 || samples < 1 || samples > 8 || tilePixels != Math.min(pixels, MAX_ITEMS / samples)
                    || bytes != (long) tilePixels * samples * ITEM_BYTES)
                throw new IllegalArgumentException("invalid primary queue plan");
        }
        public int dispatchPairs() { return (pixels - 1) / tilePixels + 1; }
        public long primaryPaths() { return (long) pixels * samples; }
    }

    public static void record(VkCommandBuffer commandBuffer, MemoryStack stack,
            GpuSceneRayTracingPipeline producer, GpuSceneRayTracingPipeline consumer,
            int slot, RtGpuBuffer scratch, Plan plan) {
        Objects.requireNonNull(scratch, "scratch");
        if (scratch.sizeBytes() < plan.bytes() || scratch.deviceAddress() == 0)
            throw new IllegalArgumentException("queue storage is too small or has no device address");
        int rt = KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
        for (int first = 0; first < plan.pixels(); first += Math.min(plan.tilePixels(), plan.pixels() - first)) {
            int count = Math.min(plan.tilePixels(), plan.pixels() - first);
            // Separate stack frame per tile: command data is copied by Vulkan during recording.
            try (MemoryStack tile = MemoryStack.stackPush()) {
                if (first != 0) barrier(commandBuffer, tile, scratch, plan.bytes(),
                        VK10.VK_ACCESS_SHADER_READ_BIT, VK10.VK_ACCESS_SHADER_WRITE_BIT, rt);
                producer.recordPrimaryQueueTrace(commandBuffer, tile, slot, scratch.deviceAddress(), first, count);
                barrier(commandBuffer, tile, scratch, plan.bytes(),
                        VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT, rt);
                consumer.recordPrimaryQueueTrace(commandBuffer, tile, slot, scratch.deviceAddress(), first, count);
            }
        }
    }

    private static void barrier(VkCommandBuffer commandBuffer, MemoryStack stack, RtGpuBuffer scratch,
            long bytes, int source, int destination, int stage) {
        var barrier = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                .srcAccessMask(source).dstAccessMask(destination)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .buffer(scratch.buffer()).offset(0).size(bytes);
        VK10.vkCmdPipelineBarrier(commandBuffer, stage, stage, 0, null, barrier, null);
    }
}
