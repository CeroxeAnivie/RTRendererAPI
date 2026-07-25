package top.ceroxe.rt.renderer.rt.device.interop;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.Objects;

/**
 * Proves the synchronization half of the future zero-copy presentation path.
 *
 * <p>External memory alone only lets GL see a Vulkan allocation. It does not
 * tell GL when ray tracing writes have finished. This probe verifies the exact
 * Win32 semaphore export/import handle type that will later guard each shared
 * output image handoff.</p>
 */
public final class VulkanWin32ExternalSemaphoreProbe {
    private static final int HANDLE_TYPE = VK11.VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;

    private VulkanWin32ExternalSemaphoreProbe() {
    }

    /**
     * Runs capability query, export, close, and destruction validation for a binary semaphore.
     *
     * <p>The returned result owns no native resource. Expected probe failures are captured in the
     * result instead of escaping to the caller.</p>
     *
     * @param physicalDevice physical device whose external-semaphore capabilities are queried
     * @param device         logical device used for the temporary semaphore and export operation
     * @return immutable evidence describing every attempted lifecycle stage
     * @throws NullPointerException if either device argument is {@code null}
     */
    public static Result run(VkPhysicalDevice physicalDevice, VkDevice device) {
        Objects.requireNonNull(physicalDevice, "physicalDevice");
        Objects.requireNonNull(device, "device");
        if (!Win32HandleSupport.available()) {
            return Result.failed("CloseHandleUnavailable", HANDLE_TYPE, 0, 0, false, false, false, false);
        }
        try {
            return runUnchecked(physicalDevice, device);
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            return Result.failed(
                    ex.getClass().getSimpleName() + ": " + nullToBlank(ex.getMessage()),
                    HANDLE_TYPE,
                    0,
                    0,
                    false,
                    false,
                    false,
                    false
            );
        }
    }

    /**
     * Creates a Vulkan semaphore and exports an {@code OPAQUE_WIN32} handle for it.
     *
     * @param device logical device that creates and owns the semaphore
     * @return owner of both the Vulkan semaphore and the exported Win32 handle; the caller must
     * close it or explicitly detach the Win32 handle
     * @throws NullPointerException                                       if {@code device} is {@code null}
     * @throws IllegalStateException                                      if {@code CloseHandle} is unavailable or Vulkan exports a
     *                                                                    null Win32 handle
     * @throws top.ceroxe.rt.renderer.api.RendererDeviceException if Vulkan cannot create
     *                                                                    the semaphore or export its handle
     */
    public static ExportedSemaphore exportSemaphore(VkDevice device) {
        Objects.requireNonNull(device, "device");
        if (!Win32HandleSupport.available()) {
            throw new IllegalStateException("CloseHandle is unavailable");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long semaphore = 0L;
            long win32Handle = 0L;
            try {
                semaphore = createExportableSemaphore(stack, device);
                win32Handle = exportWin32Handle(stack, device, semaphore);
                ExportedSemaphore exported = new ExportedSemaphore(device, semaphore, win32Handle, HANDLE_TYPE);
                semaphore = 0L;
                win32Handle = 0L;
                return exported;
            } finally {
                if (win32Handle != 0L) {
                    Win32HandleSupport.close(win32Handle);
                }
                if (semaphore != 0L) {
                    VK10.vkDestroySemaphore(device, semaphore, null);
                }
            }
        }
    }

    private static Result runUnchecked(VkPhysicalDevice physicalDevice, VkDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceExternalSemaphoreInfo semaphoreInfo =
                    VkPhysicalDeviceExternalSemaphoreInfo.calloc(stack)
                            .sType$Default()
                            .handleType(HANDLE_TYPE);
            VkExternalSemaphoreProperties properties =
                    VkExternalSemaphoreProperties.calloc(stack).sType$Default();
            VK11.vkGetPhysicalDeviceExternalSemaphoreProperties(physicalDevice, semaphoreInfo, properties);

            int compatibleHandleTypes = properties.compatibleHandleTypes();
            int features = properties.externalSemaphoreFeatures();
            boolean compatibleOpaqueWin32 = (compatibleHandleTypes & HANDLE_TYPE) != 0;
            boolean exportable = (features & VK11.VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT) != 0;
            boolean importable = (features & VK11.VK_EXTERNAL_SEMAPHORE_FEATURE_IMPORTABLE_BIT) != 0;
            if (!compatibleOpaqueWin32 || !exportable) {
                return Result.failed(
                        compatibleOpaqueWin32 ? "opaqueWin32SemaphoreNotExportable" : "opaqueWin32SemaphoreIncompatible",
                        HANDLE_TYPE,
                        features,
                        compatibleHandleTypes,
                        false,
                        false,
                        false,
                        false
                );
            }

            long semaphore = 0L;
            long win32Handle = 0L;
            try {
                semaphore = createExportableSemaphore(stack, device);
                win32Handle = exportWin32Handle(stack, device, semaphore);
                boolean handleClosed = Win32HandleSupport.close(win32Handle);
                win32Handle = 0L;
                if (!handleClosed) {
                    return Result.failed(
                            "CloseHandleFailed:lastError=" + Win32HandleSupport.lastError(),
                            HANDLE_TYPE,
                            features,
                            compatibleHandleTypes,
                            true,
                            true,
                            true,
                            false
                    );
                }
                return Result.success(HANDLE_TYPE, features, compatibleHandleTypes, importable);
            } finally {
                if (win32Handle != 0L) {
                    Win32HandleSupport.close(win32Handle);
                }
                if (semaphore != 0L) {
                    VK10.vkDestroySemaphore(device, semaphore, null);
                }
            }
        }
    }

    private static long createExportableSemaphore(MemoryStack stack, VkDevice device) {
        VkExportSemaphoreCreateInfo exportInfo = VkExportSemaphoreCreateInfo.calloc(stack)
                .sType$Default()
                .handleTypes(HANDLE_TYPE);
        VkSemaphoreCreateInfo createInfo = VkSemaphoreCreateInfo.calloc(stack)
                .sType$Default()
                .pNext(exportInfo.address())
                .flags(0);
        LongBuffer semaphoreHandle = stack.longs(0L);
        checkVk(VK10.vkCreateSemaphore(device, createInfo, null, semaphoreHandle),
                "vkCreateSemaphore.externalInteropProbe");
        return semaphoreHandle.get(0);
    }

    private static long exportWin32Handle(MemoryStack stack, VkDevice device, long semaphore) {
        VkSemaphoreGetWin32HandleInfoKHR handleInfo = VkSemaphoreGetWin32HandleInfoKHR.calloc(stack)
                .sType$Default()
                .semaphore(semaphore)
                .handleType(HANDLE_TYPE);
        PointerBuffer handle = stack.mallocPointer(1);
        checkVk(
                KHRExternalSemaphoreWin32.vkGetSemaphoreWin32HandleKHR(device, handleInfo, handle),
                "vkGetSemaphoreWin32HandleKHR.externalInteropProbe"
        );
        long value = handle.get(0);
        if (value == 0L) {
            throw new IllegalStateException("vkGetSemaphoreWin32HandleKHR returned a null handle");
        }
        return value;
    }

    private static void checkVk(int result, String stage) {
        top.ceroxe.rt.renderer.rt.device.VulkanFailures.check(result, stage);
    }

    private static String vkResultName(int result) {
        return switch (result) {
            case VK10.VK_SUCCESS -> "VK_SUCCESS";
            case VK10.VK_ERROR_OUT_OF_HOST_MEMORY -> "VK_ERROR_OUT_OF_HOST_MEMORY";
            case VK10.VK_ERROR_OUT_OF_DEVICE_MEMORY -> "VK_ERROR_OUT_OF_DEVICE_MEMORY";
            case VK10.VK_ERROR_INITIALIZATION_FAILED -> "VK_ERROR_INITIALIZATION_FAILED";
            case VK10.VK_ERROR_DEVICE_LOST -> "VK_ERROR_DEVICE_LOST";
            case VK10.VK_ERROR_TOO_MANY_OBJECTS -> "VK_ERROR_TOO_MANY_OBJECTS";
            case VK11.VK_ERROR_INVALID_EXTERNAL_HANDLE -> "VK_ERROR_INVALID_EXTERNAL_HANDLE";
            default -> Integer.toString(result);
        };
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    /**
     * Owns a Vulkan binary semaphore and, until detached, its exported Win32 handle.
     *
     * <p>Closing the owner attempts to close the Win32 handle before destroying the Vulkan
     * semaphore. Callers that detach the Win32 handle assume its close responsibility.</p>
     */
    public static final class ExportedSemaphore implements AutoCloseable {
        private final VkDevice device;
        private final int handleType;
        private long semaphore;
        private long win32Handle;
        private boolean handleClosed;
        private boolean closed;

        private ExportedSemaphore(VkDevice device, long semaphore, long win32Handle, int handleType) {
            this.device = Objects.requireNonNull(device, "device");
            this.semaphore = semaphore;
            this.win32Handle = win32Handle;
            this.handleType = handleType;
        }

        /**
         * Returns the exported handle without transferring ownership.
         *
         * @return owned Win32 handle, or zero after close or detach
         */
        public long win32Handle() {
            return win32Handle;
        }

        /**
         * Returns the Vulkan semaphore without transferring ownership.
         *
         * @return owned nonzero {@code VkSemaphore}, valid until {@link #close()}
         * @throws IllegalStateException if this owner has already destroyed the semaphore
         */
        public long semaphore() {
            if (semaphore == 0L) {
                throw new IllegalStateException("exported Vulkan semaphore has already been destroyed");
            }
            return semaphore;
        }

        /**
         * Returns the external handle type used for the export.
         *
         * @return Vulkan external semaphore handle-type bit
         */
        public int handleType() {
            return handleType;
        }

        /**
         * Detaches the Win32 handle from this owner.
         *
         * @return handle whose close responsibility is transferred to the caller, or zero if no
         * handle remains
         */
        public long detachWin32Handle() {
            long detached = win32Handle;
            win32Handle = 0L;
            handleClosed = true;
            return detached;
        }

        /**
         * Attempts to close this owner's Win32 handle without destroying the Vulkan semaphore.
         *
         * @return {@code true} if the handle was closed or no handle remains; {@code false} if
         * {@code CloseHandle} failed, in which case this object retains ownership
         */
        public boolean closeWin32Handle() {
            if (win32Handle == 0L) {
                handleClosed = true;
                return true;
            }
            handleClosed = Win32HandleSupport.close(win32Handle);
            if (handleClosed) {
                win32Handle = 0L;
            }
            return handleClosed;
        }

        /**
         * Reports whether this owner has discharged its Win32-handle responsibility.
         *
         * @return {@code true} after successful closure or detachment
         */
        public boolean handleClosed() {
            return handleClosed;
        }

        /**
         * Closes the owned Win32 handle and destroys the Vulkan semaphore.
         *
         * <p>This operation is idempotent. If Win32 handle closure fails, the method cannot report
         * the failure directly; callers requiring confirmation should invoke
         * {@link #closeWin32Handle()} first.</p>
         */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            closeWin32Handle();
            if (semaphore != 0L) {
                VK10.vkDestroySemaphore(device, semaphore, null);
                semaphore = 0L;
            }
        }
    }

    /**
     * Immutable evidence from the external-semaphore capability probe.
     *
     * @param attempted                 whether native capability or lifecycle work was attempted
     * @param successful                whether every required probe stage succeeded
     * @param handleType                Vulkan external semaphore handle type requested by the probe
     * @param externalSemaphoreFeatures Vulkan external-semaphore feature bit mask
     * @param compatibleHandleTypes     Vulkan compatible external handle-type bit mask
     * @param importable                whether the queried handle type supports import
     * @param semaphoreCreated          whether the temporary Vulkan semaphore was created
     * @param handleExported            whether Vulkan returned a nonzero Win32 handle
     * @param handleClosed              whether the exported handle was closed successfully
     * @param reason                    bounded diagnostic explanation; never {@code null}
     */
    public record Result(
            boolean attempted,
            boolean successful,
            int handleType,
            int externalSemaphoreFeatures,
            int compatibleHandleTypes,
            boolean importable,
            boolean semaphoreCreated,
            boolean handleExported,
            boolean handleClosed,
            String reason
    ) {
        /**
         * Normalizes an absent diagnostic reason to an empty string.
         */
        public Result {
            reason = reason == null ? "" : reason;
        }

        /**
         * Creates a result for a probe deliberately skipped before native work began.
         *
         * @param reason explanation for skipping; {@code null} is normalized to an empty string
         * @return non-attempted, unsuccessful probe result
         */
        public static Result skipped(String reason) {
            return new Result(false, false, HANDLE_TYPE, 0, 0, false, false, false, false, reason);
        }

        static Result success(int handleType, int features, int compatibleHandleTypes, boolean importable) {
            return new Result(
                    true,
                    true,
                    handleType,
                    features,
                    compatibleHandleTypes,
                    importable,
                    true,
                    true,
                    true,
                    "ready"
            );
        }

        static Result failed(
                String reason,
                int handleType,
                int features,
                int compatibleHandleTypes,
                boolean importable,
                boolean semaphoreCreated,
                boolean handleExported,
                boolean handleClosed
        ) {
            return new Result(
                    true,
                    false,
                    handleType,
                    features,
                    compatibleHandleTypes,
                    importable,
                    semaphoreCreated,
                    handleExported,
                    handleClosed,
                    reason
            );
        }

        /**
         * Formats a bounded single-line diagnostic summary.
         *
         * @param name diagnostic label prepended to the summary
         * @return summary containing every result field
         */
        public String summary(String name) {
            return name
                    + "{attempted=" + attempted
                    + ", successful=" + successful
                    + ", handleType=0x" + Integer.toHexString(handleType)
                    + ", externalSemaphoreFeatures=0x" + Integer.toHexString(externalSemaphoreFeatures)
                    + ", compatibleHandleTypes=0x" + Integer.toHexString(compatibleHandleTypes)
                    + ", importable=" + importable
                    + ", semaphoreCreated=" + semaphoreCreated
                    + ", handleExported=" + handleExported
                    + ", handleClosed=" + handleClosed
                    + ", reason=" + reason
                    + "}";
        }
    }
}
