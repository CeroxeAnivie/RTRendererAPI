package top.ceroxe.rt.renderer.rt.pipeline;

import org.lwjgl.vulkan.*;
import top.ceroxe.rt.renderer.api.SceneShaderBindings;
import top.ceroxe.rt.renderer.api.ResourceData;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

import java.util.List;

/** Frame-slot-local storage: uploads never overwrite bytes referenced by an earlier dispatch. */
final class GpuSceneShaderDataBuffers implements AutoCloseable {
    private final VulkanDeviceRuntime runtime;
    private final long maxRange;
    private final RtGpuBuffer[][] buffers;
    private final ResourceData[][] uploaded;
    private final VkDescriptorBufferInfo.Buffer infos;
    private final VkWriteDescriptorSet.Buffer writes;
    private boolean closed;

    GpuSceneShaderDataBuffers(VulkanDeviceRuntime runtime, int slots, int bindings, long maxRange) {
        this.runtime = runtime;
        this.maxRange = maxRange;
        buffers = new RtGpuBuffer[slots][bindings];
        uploaded = new ResourceData[slots][bindings];
        infos = bindings == 0 ? null : VkDescriptorBufferInfo.calloc(bindings);
        try {
            writes = bindings == 0 ? null : VkWriteDescriptorSet.calloc(bindings);
        } catch (RuntimeException | Error failure) {
            if (infos != null) infos.free();
            throw failure;
        }
    }

    private long capacity(RtGpuBuffer previous, int required) {
        return previous == null ? required : Math.min(maxRange, Math.max(required, previous.sizeBytes() * 2));
    }

    long requiredGrowth(int slot, List<ResourceData> values) {
        long growth = 0;
        for (int i = 0; i < values.size(); i++) {
            RtGpuBuffer old = buffers[slot][i];
            if (old == null || old.sizeBytes() < values.get(i).byteSize()) {
                // Account for the temporary overlap while replacing an allocation, too.
                growth = Math.addExact(growth, capacity(old, values.get(i).byteSize()));
            }
        }
        return growth;
    }

    void update(int slot, long descriptorSet, List<ResourceData> values) {
        if (values.isEmpty()) return;
        {
            // Reuse bounded native descriptor scratch; large interfaces must not overflow MemoryStack.
            for (int i = 0; i < values.size(); i++) {
                ResourceData data = values.get(i);
                RtGpuBuffer buffer = buffers[slot][i];
                if (buffer == null || buffer.sizeBytes() < data.byteSize()) {
                    RtGpuBuffer replacement = RtGpuBuffer.createHostVisibleUploadBuffer(
                            runtime.device(), runtime.allocator(), capacity(buffer, data.byteSize()),
                            VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
                    try {
                        if (buffer != null) buffer.close();
                    } catch (RuntimeException | Error failure) {
                        try { replacement.close(); }
                        catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                        throw failure;
                    }
                    buffers[slot][i] = buffer = replacement;
                    uploaded[slot][i] = null;
                }
                if (uploaded[slot][i] != data) {
                    ResourceData previous = uploaded[slot][i];
                    uploaded[slot][i] = null; // A partial failure cannot authorize skipped ranges on retry.
                    RtGpuBuffer target = buffer;
                    data.forEachChangedRange(previous, (offset, bytes) -> target.writeBytesAt(offset, bytes));
                    uploaded[slot][i] = data;
                }
                infos.get(i).buffer(buffer.buffer()).offset(0).range(data.byteSize());
                writes.get(i).sType$Default().dstSet(descriptorSet)
                        .dstBinding(SceneShaderBindings.APPLICATION_DATA + i)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1)
                        .pBufferInfo(VkDescriptorBufferInfo.create(infos.get(i).address(), 1));
            }
            VK10.vkUpdateDescriptorSets(runtime.device(), writes, null);
        }
    }

    @Override public void close() {
        if (closed) return;
        RuntimeException failure = null;
        for (int slot = 0; slot < buffers.length; slot++) {
            for (int i = 0; i < buffers[slot].length; i++) {
                if (buffers[slot][i] == null) continue;
                try { buffers[slot][i].close(); buffers[slot][i] = null; uploaded[slot][i] = null; }
                catch (RuntimeException error) {
                    if (failure == null) failure = error; else failure.addSuppressed(error);
                }
            }
        }
        if (failure != null) throw failure;
        if (infos != null) infos.free();
        if (writes != null) writes.free();
        closed = true;
    }
}
