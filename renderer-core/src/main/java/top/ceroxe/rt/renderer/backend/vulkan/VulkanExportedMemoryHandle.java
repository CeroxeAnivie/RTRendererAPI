package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.vulkan.VK11;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease;
import top.ceroxe.rt.renderer.rt.device.interop.Win32HandleSupport;

/** Stateful ownership wrapper for one freshly exported OPAQUE_WIN32 memory handle. */
final class VulkanExportedMemoryHandle
        implements GpuFrameLease.ExportedNativeHandle<GpuFrameLease.VulkanMemoryHandleType> {
    private final long value;
    private GpuFrameLease.HandleState state = GpuFrameLease.HandleState.EXPORTED;

    VulkanExportedMemoryHandle(long value) {
        if (value == 0L) throw new IllegalArgumentException("exported memory handle must not be null");
        this.value = value;
    }

    @Override
    public synchronized long value() {
        if (state == GpuFrameLease.HandleState.CLOSED) {
            throw new IllegalStateException("exported memory handle is closed");
        }
        return value;
    }

    @Override
    public GpuFrameLease.VulkanMemoryHandleType handleType() {
        return new GpuFrameLease.VulkanMemoryHandleType(
                VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT
        );
    }

    @Override
    public GpuFrameLease.ImportDisposition importDisposition() {
        // OPAQUE_WIN32 import does not transfer ownership of the NT handle to Vulkan.
        return GpuFrameLease.ImportDisposition.CALLER_RETAINS_HANDLE;
    }

    @Override
    public synchronized GpuFrameLease.HandleState state() {
        return state;
    }

    @Override
    public synchronized boolean markImported() {
        if (state == GpuFrameLease.HandleState.CLOSED) {
            throw new IllegalStateException("cannot import a closed memory handle");
        }
        if (state == GpuFrameLease.HandleState.IMPORTED) return false;
        state = GpuFrameLease.HandleState.IMPORTED;
        return true;
    }

    @Override
    public synchronized void close() {
        if (state == GpuFrameLease.HandleState.CLOSED) return;
        if (!Win32HandleSupport.close(value)) {
            int error = Win32HandleSupport.lastError();
            throw new IllegalStateException(
                    "CloseHandle failed for exported Vulkan memory handle=0x"
                            + Long.toHexString(value) + ", error=" + error
            );
        }
        state = GpuFrameLease.HandleState.CLOSED;
    }
}
