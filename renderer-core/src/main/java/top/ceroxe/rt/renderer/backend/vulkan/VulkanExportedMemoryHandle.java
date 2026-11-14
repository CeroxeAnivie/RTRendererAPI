package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.vulkan.VK11;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease;
import top.ceroxe.rt.renderer.rt.device.interop.Win32HandleSupport;

import java.util.Objects;
import java.util.function.LongSupplier;

/** Stateful ownership wrapper for one freshly exported OPAQUE_WIN32 memory handle. */
final class VulkanExportedMemoryHandle
        implements GpuFrameLease.ExportedNativeHandle<GpuFrameLease.VulkanMemoryHandleType> {
    private LongSupplier exporter;
    private long value;
    private GpuFrameLease.HandleState state = GpuFrameLease.HandleState.EXPORTED;

    VulkanExportedMemoryHandle(long value) {
        if (value == 0L) throw new IllegalArgumentException("exported memory handle must not be null");
        this.value = value;
    }

    /**
     * Creates a handle owner whose OS handle is exported only when an expert consumer asks for
     * its numeric value. The managed same-device presenter never crosses an OS-handle boundary,
     * so eagerly duplicating a Win32 handle on every frame would be pure overhead.
     */
    VulkanExportedMemoryHandle(LongSupplier exporter) {
        this.exporter = Objects.requireNonNull(exporter, "exporter");
    }

    @Override
    public synchronized long value() {
        if (state == GpuFrameLease.HandleState.CLOSED) {
            throw new IllegalStateException("exported memory handle is closed");
        }
        materialize();
        return value;
    }

    private void materialize() {
        if (value != 0L) return;
        long exported = exporter.getAsLong();
        if (exported == 0L) {
            throw new IllegalStateException("memory handle exporter returned null");
        }
        value = exported;
        exporter = null;
    }

    /** Returns whether the lazy expert handle has crossed the native export boundary. */
    synchronized boolean materialized() {
        return value != 0L;
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
        materialize();
        state = GpuFrameLease.HandleState.IMPORTED;
        return true;
    }

    @Override
    public synchronized void close() {
        if (state == GpuFrameLease.HandleState.CLOSED) return;
        long handle = value;
        exporter = null;
        if (handle != 0L && !Win32HandleSupport.close(handle)) {
            int error = Win32HandleSupport.lastError();
            throw new IllegalStateException(
                    "CloseHandle failed for exported Vulkan memory handle=0x"
                            + Long.toHexString(handle) + ", error=" + error
            );
        }
        state = GpuFrameLease.HandleState.CLOSED;
    }
}
