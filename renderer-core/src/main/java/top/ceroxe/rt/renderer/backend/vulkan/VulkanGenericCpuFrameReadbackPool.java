package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bounded pool of host-visible buffers used by generic output readback submissions. */
final class VulkanGenericCpuFrameReadbackPool implements AutoCloseable {
    private final VulkanDeviceRuntime device;
    private final int capacity;
    private final Map<Long, ArrayDeque<RtGpuBuffer>> available = new HashMap<>();
    private final Set<RtGpuBuffer> availableBuffers =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final ArrayList<RtGpuBuffer> allocated = new ArrayList<>();
    private boolean closed;

    VulkanGenericCpuFrameReadbackPool(VulkanDeviceRuntime device, int capacity) {
        this.device = Objects.requireNonNull(device, "device");
        if (capacity <= 0) throw new IllegalArgumentException("readback pool capacity must be positive");
        this.capacity = capacity;
    }

    RtGpuBuffer acquire(VulkanGenericResourceRegistry.TextureRecord output) {
        requireOpen();
        long bytes = VulkanGenericCpuFrameReadback.byteCount(output);
        ArrayDeque<RtGpuBuffer> idle = available.get(bytes);
        RtGpuBuffer result = idle == null ? null : idle.pollFirst();
        if (result != null) {
            availableBuffers.remove(result);
            return result;
        }
        if (allocated.size() >= capacity) {
            if (!evictIdleBuffer()) {
                throw new IllegalStateException("generic CPU readback pool exhausted: capacity=" + capacity);
            }
        }
        result = VulkanGenericCpuFrameReadback.createBuffer(device, output);
        allocated.add(result);
        return result;
    }

    private boolean evictIdleBuffer() {
        var iterator = available.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            RtGpuBuffer buffer = entry.getValue().pollFirst();
            if (entry.getValue().isEmpty()) iterator.remove();
            if (buffer == null) continue;
            availableBuffers.remove(buffer);
            allocated.remove(buffer);
            buffer.close();
            return true;
        }
        return false;
    }

    void release(RtGpuBuffer buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (closed) return;
        if (!allocated.contains(buffer)) {
            throw new IllegalArgumentException("readback buffer does not belong to this pool");
        }
        if (!availableBuffers.add(buffer)) {
            throw new IllegalStateException("readback buffer was released more than once");
        }
        available.computeIfAbsent(buffer.sizeBytes(), ignored -> new ArrayDeque<>()).addLast(buffer);
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("generic CPU readback pool is closed");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        for (RtGpuBuffer buffer : allocated) {
            try {
                buffer.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        available.clear();
        availableBuffers.clear();
        allocated.clear();
        if (failure != null) throw failure;
    }
}
