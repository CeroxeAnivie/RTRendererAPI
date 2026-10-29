package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease;
import org.lwjgl.vulkan.VK11;

/** Per-lease import state over a slot-owned persistent NT handle. Closing never destroys its slot. */
final class VulkanSlotReadyHandle implements GpuFrameLease.ExportedNativeHandle<GpuFrameLease.VulkanSemaphoreHandleType> {
    private final long handle;
    private boolean importedEver;
    private GpuFrameLease.HandleState state = GpuFrameLease.HandleState.EXPORTED;
    VulkanSlotReadyHandle(long handle) { if (handle == 0L) throw new IllegalArgumentException("null ready handle"); this.handle = handle; }
    public synchronized long value() { if (state == GpuFrameLease.HandleState.CLOSED) throw new IllegalStateException("ready handle closed"); return handle; }
    public GpuFrameLease.VulkanSemaphoreHandleType handleType() { return new GpuFrameLease.VulkanSemaphoreHandleType(VK11.VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT); }
    public GpuFrameLease.ImportDisposition importDisposition() { return GpuFrameLease.ImportDisposition.CALLER_RETAINS_HANDLE; }
    public synchronized GpuFrameLease.HandleState state() { return state; }
    public synchronized boolean markImported() { if (state == GpuFrameLease.HandleState.CLOSED) throw new IllegalStateException("ready handle closed"); if (state == GpuFrameLease.HandleState.IMPORTED) return false; state = GpuFrameLease.HandleState.IMPORTED; importedEver = true; return true; }
    synchronized boolean importedEver() { return importedEver; }
    public synchronized void close() { state = GpuFrameLease.HandleState.CLOSED; }
}
