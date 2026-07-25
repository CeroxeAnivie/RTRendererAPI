package top.ceroxe.rt.renderer.rt.device.interop;

import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease;
import top.ceroxe.rt.renderer.rt.device.VulkanFailures;

import java.nio.LongBuffer;
import java.util.Objects;

/**
 * Owns one Vulkan binary semaphore imported from a caller-retained {@code OPAQUE_WIN32} handle.
 *
 * <p>Closing an instance destroys only its Vulkan semaphore. Vulkan does not consume the imported
 * {@code OPAQUE_WIN32} handle, so the original handle remains the caller's responsibility.</p>
 */
public final class VulkanImportedSemaphore implements AutoCloseable {
    /**
     * Vulkan external handle type accepted by
     * {@link #importBinary(VkDevice, GpuFrameLease.ExternalSemaphoreSignal)}.
     */
    public static final int HANDLE_TYPE = VK11.VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;

    private final VkDevice device;
    private long semaphore;

    private VulkanImportedSemaphore(VkDevice device, long semaphore) {
        this.device = Objects.requireNonNull(device, "device");
        this.semaphore = semaphore;
    }

    /**
     * Imports a caller-retained OPAQUE_WIN32 handle into a newly owned Vulkan semaphore.
     *
     * @param device logical device that owns the new semaphore
     * @param signal binary completion signal; its Win32 handle remains caller-owned
     * @return imported semaphore owned by the caller, which must invoke {@link #close()}
     * @throws NullPointerException                                       if {@code device} or {@code signal} is {@code null}
     * @throws UnsupportedOperationException                              if the signal is not binary or the device lacks
     *                                                                    {@code VK_KHR_external_semaphore_win32}
     * @throws IllegalArgumentException                                   if the signal has an incompatible handle type, transfers
     *                                                                    handle ownership, or contains a zero handle
     * @throws top.ceroxe.rt.renderer.api.RendererDeviceException if Vulkan cannot create
     *                                                                    the semaphore or import the external handle
     */
    public static VulkanImportedSemaphore importBinary(
            VkDevice device,
            GpuFrameLease.ExternalSemaphoreSignal signal
    ) {
        VkDevice checkedDevice = Objects.requireNonNull(device, "device");
        GpuFrameLease.ExternalSemaphoreSignal checkedSignal = Objects.requireNonNull(signal, "signal");
        if (checkedSignal.kind() != GpuFrameLease.SemaphoreKind.BINARY || checkedSignal.timelineValue() != 0L) {
            throw new UnsupportedOperationException("only binary external semaphore completion is enabled");
        }
        if (checkedSignal.handleType().value() != HANDLE_TYPE) {
            throw new IllegalArgumentException("external semaphore must use OPAQUE_WIN32 handle type");
        }
        if (checkedSignal.importDisposition() != GpuFrameLease.ImportDisposition.CALLER_RETAINS_HANDLE) {
            throw new IllegalArgumentException("OPAQUE_WIN32 semaphore import does not consume the caller handle");
        }
        if (checkedSignal.handle() == 0L) {
            throw new IllegalArgumentException("external semaphore handle must not be null");
        }

        long importFunction = checkedDevice.getCapabilities().vkImportSemaphoreWin32HandleKHR;
        if (importFunction == 0L) {
            throw new UnsupportedOperationException("VK_KHR_external_semaphore_win32 is unavailable");
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer handle = stack.longs(0L);
            VulkanFailures.check(
                    VK10.vkCreateSemaphore(
                            checkedDevice, VkSemaphoreCreateInfo.calloc(stack).sType$Default(), null, handle),
                    "vkCreateSemaphore.consumerCompletion"
            );
            long semaphore = handle.get(0);
            try {
                VkImportSemaphoreWin32HandleInfoKHR importInfo =
                        VkImportSemaphoreWin32HandleInfoKHR.calloc(stack)
                                .sType$Default()
                                .semaphore(semaphore)
                                .flags(0)
                                .handleType(HANDLE_TYPE)
                                .handle(checkedSignal.handle());
                VulkanFailures.check(
                        /*
                         * LWJGL 3.4.1 validates both mutually exclusive Win32 import selectors
                         * (handle and name) as mandatory. Vulkan requires exactly the populated
                         * handle here and a null name. Even LWJGL's generated nvk* entry point runs
                         * the same invalid validator, so call the resolved Vulkan function pointer
                         * directly after the explicit contract checks above. The argument shape is
                         * the generated binding's native ABI: VkDevice, import-info pointer, result.
                         */
                        JNI.callPPI(checkedDevice.address(), importInfo.address(), importFunction),
                        "vkImportSemaphoreWin32HandleKHR.consumerCompletion"
                );
                VulkanImportedSemaphore result = new VulkanImportedSemaphore(checkedDevice, semaphore);
                semaphore = 0L;
                return result;
            } finally {
                if (semaphore != 0L) VK10.vkDestroySemaphore(checkedDevice, semaphore, null);
            }
        }
    }

    /**
     * Returns the imported Vulkan semaphore without transferring its ownership.
     *
     * @return nonzero imported {@code VkSemaphore}, valid until {@link #close()}
     * @throws IllegalStateException if this owner has already been closed
     */
    public synchronized long semaphore() {
        if (semaphore == 0L) throw new IllegalStateException("imported semaphore is closed");
        return semaphore;
    }

    /**
     * Destroys the owned Vulkan semaphore; repeated calls are no-ops.
     *
     * <p>The Win32 handle supplied to {@link #importBinary(VkDevice,
     * GpuFrameLease.ExternalSemaphoreSignal)} is not owned and is therefore never closed here.</p>
     */
    @Override
    public synchronized void close() {
        if (semaphore == 0L) return;
        VK10.vkDestroySemaphore(device, semaphore, null);
        semaphore = 0L;
    }
}
