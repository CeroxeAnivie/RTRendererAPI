package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.device.VulkanFailures;

import java.io.IOException;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;

/** GPU-only unsigned count normalization; scratch allocations live until submission completion.
 *
 * <p>A pass reads each application's counter without modifying it. Each native counted draw
 * receives a distinct unsigned {@code min(counter, command.maximumDrawCount)} word. Native
 * consumption therefore never depends on an unclamped high-bit counter, while draw semantics
 * remain exact for the entire unsigned input range. No host observation or extra submission is
 * involved. The observed native failure at UINT_MAX is a compatibility boundary, not proof of
 * a particular driver implementation defect.</p>
 */
final class VulkanGenericIndirectCounts implements AutoCloseable {
    private static final int PUSH_BYTES = 24;
    private static final int MAXIMUM_CACHED_BUFFERS = 16;
    private final VulkanDeviceRuntime device;
    private final ArrayDeque<RtGpuBuffer> available = new ArrayDeque<>();
    private long pipeline, layout;
    private boolean closed;

    VulkanGenericIndirectCounts(VulkanDeviceRuntime device) { this.device = device; }

    Batch prepare(VulkanGenericCommandPlan plan) {
        if (closed) throw new IllegalStateException("indirect count owner is closed");
        var slots = new IdentityHashMap<VulkanGenericCommandPlan.Indirect, Integer>();
        for (var action : plan.actions()) if (action instanceof VulkanGenericCommandPlan.Indirect indirect
                && indirect.count() != null && indirect.command().maximumDrawCount() != 0) slots.put(indirect, slots.size());
        if (slots.isEmpty()) return null;
        ensurePipeline();
        long required = (long) slots.size() * Integer.BYTES;
        var passes = new IdentityHashMap<VulkanGenericCommandPlan.BeginPass, java.util.List<VulkanGenericCommandPlan.Indirect>>();
        VulkanGenericCommandPlan.BeginPass activePass = null;
        java.util.ArrayList<VulkanGenericCommandPlan.Indirect> passDraws = null;
        for (var action : plan.actions()) {
            if (action instanceof VulkanGenericCommandPlan.BeginPass pass) {
                activePass = pass; passDraws = new java.util.ArrayList<>();
            } else if (action instanceof VulkanGenericCommandPlan.Indirect draw && slots.containsKey(draw)) {
                if (passDraws == null) throw new IllegalStateException("counted draw has no render pass");
                passDraws.add(draw);
            } else if (action instanceof VulkanGenericCommandPlan.EndPass) {
                if (activePass != null && !passDraws.isEmpty()) passes.put(activePass, java.util.List.copyOf(passDraws));
                activePass = null; passDraws = null;
            }
        }
        RtGpuBuffer scratch = null;
        for (var iterator = available.iterator(); iterator.hasNext();) {
            var candidate = iterator.next();
            if (candidate.sizeBytes() >= required) { scratch = candidate; iterator.remove(); break; }
        }
        if (scratch == null) scratch = RtGpuBuffer.createDeviceAddressBuffer(device.device(), device.allocator(), required,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT);
        return new Batch(scratch, slots, passes);
    }

    private void ensurePipeline() {
        if (pipeline != 0) return;
        try (var stack = MemoryStack.stackPush()) {
            long shader = 0;
            try {
                byte[] bytes;
                try (var input = VulkanGenericIndirectCounts.class.getResourceAsStream(
                        "/assets/rtrenderer/shaders/spv/generic_indirect_count.comp.spv")) {
                    if (input == null) throw new IllegalStateException("missing generic indirect count SPIR-V");
                    bytes = input.readAllBytes();
                }
                var code = stack.malloc(bytes.length).order(ByteOrder.nativeOrder()); code.put(bytes).flip();
                var output = stack.longs(0L);
                VulkanFailures.check(VK10.vkCreateShaderModule(device.device(), VkShaderModuleCreateInfo.calloc(stack)
                        .sType$Default().pCode(code), null, output), "vkCreateShaderModule.indirectCount");
                shader = output.get(0);
                var pushes = VkPushConstantRange.calloc(1, stack).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).size(PUSH_BYTES);
                VulkanFailures.check(VK10.vkCreatePipelineLayout(device.device(), VkPipelineLayoutCreateInfo.calloc(stack)
                        .sType$Default().pPushConstantRanges(pushes), null, output), "vkCreatePipelineLayout.indirectCount");
                layout = output.get(0);
                var create = VkComputePipelineCreateInfo.calloc(1, stack);
                create.sType$Default().layout(layout);
                create.stage().sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(shader).pName(stack.UTF8("main"));
                output.put(0, 0L);
                int result = VK10.vkCreateComputePipelines(device.device(), 0, create, null, output);
                if (result != VK10.VK_SUCCESS && output.get(0) != 0) {
                    VK10.vkDestroyPipeline(device.device(), output.get(0), null);
                }
                VulkanFailures.check(result, "vkCreateComputePipelines.indirectCount");
                pipeline = output.get(0);
            } catch (IOException failure) {
                throw new IllegalStateException("cannot read generic indirect count SPIR-V", failure);
            } catch (RuntimeException failure) {
                if (layout != 0) { VK10.vkDestroyPipelineLayout(device.device(), layout, null); layout = 0; }
                throw failure;
            } finally {
                if (shader != 0) VK10.vkDestroyShaderModule(device.device(), shader, null);
            }
        }
    }

    @Override public void close() {
        if (closed) return; closed = true;
        while (!available.isEmpty()) available.removeFirst().close();
        if (pipeline != 0) VK10.vkDestroyPipeline(device.device(), pipeline, null);
        if (layout != 0) VK10.vkDestroyPipelineLayout(device.device(), layout, null);
        pipeline = layout = 0;
    }

    final class Batch implements AutoCloseable {
        private final RtGpuBuffer scratch;
        private final Map<VulkanGenericCommandPlan.Indirect, Integer> slots;
        private final Map<VulkanGenericCommandPlan.BeginPass, java.util.List<VulkanGenericCommandPlan.Indirect>> passes;
        private boolean released;
        private Batch(RtGpuBuffer scratch, Map<VulkanGenericCommandPlan.Indirect, Integer> slots,
                      Map<VulkanGenericCommandPlan.BeginPass, java.util.List<VulkanGenericCommandPlan.Indirect>> passes) {
            this.scratch = scratch; this.slots = slots; this.passes = passes;
        }
        long buffer() { return scratch.buffer(); }
        long offset(VulkanGenericCommandPlan.Indirect action) {
            var slot = slots.get(action);
            if (slot == null) throw new IllegalStateException("count normalization missing for draw");
            return (long) slot * Integer.BYTES;
        }

        void recordPass(VkCommandBuffer commands, MemoryStack stack, VulkanGenericCommandPlan.BeginPass pass) {
            record(commands, stack, passes.getOrDefault(pass, java.util.List.of()));
        }

        void record(VkCommandBuffer commands, MemoryStack stack, java.util.List<VulkanGenericCommandPlan.Indirect> draws) {
            if (draws.isEmpty()) return;
            var before = VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK10.VK_ACCESS_MEMORY_WRITE_BIT).dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT);
            VK10.vkCmdPipelineBarrier(commands, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, before, null, null);
            VK10.vkCmdBindPipeline(commands, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            var data = stack.calloc(PUSH_BYTES).order(ByteOrder.nativeOrder());
            for (var draw : draws) {
                data.clear();
                data.putLong(draw.count().buffer().deviceAddress() + draw.command().count().orElseThrow().range().offsetBytes());
                data.putLong(scratch.deviceAddress() + offset(draw));
                data.putInt(draw.command().maximumDrawCount()).putInt(0).flip();
                VK10.vkCmdPushConstants(commands, layout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, data);
                VK10.vkCmdDispatch(commands, 1, 1, 1);
            }
            var after = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK10.VK_ACCESS_INDIRECT_COMMAND_READ_BIT)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .buffer(scratch.buffer()).offset(0).size(scratch.sizeBytes());
            VK10.vkCmdPipelineBarrier(commands, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK10.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT, 0, null, after, null);
        }

        @Override public void close() {
            if (released) return; released = true;
            if (!closed && available.size() < MAXIMUM_CACHED_BUFFERS) available.addLast(scratch); else scratch.close();
        }
    }
}
