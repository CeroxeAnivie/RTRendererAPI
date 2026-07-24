package top.ceroxe.mcvulkanrt.renderer.rt.device.interop;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRExternalSemaphoreWin32;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkExportSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkExternalSemaphoreProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceExternalSemaphoreInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreGetWin32HandleInfoKHR;

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
        if (result != VK10.VK_SUCCESS) {
            throw new IllegalStateException(stage + " failed: " + vkResultName(result));
        }
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

    public static final class ExportedSemaphore implements AutoCloseable {
        private final VkDevice device;
        private long semaphore;
        private long win32Handle;
        private final int handleType;
        private boolean handleClosed;
        private boolean closed;

        private ExportedSemaphore(VkDevice device, long semaphore, long win32Handle, int handleType) {
            this.device = Objects.requireNonNull(device, "device");
            this.semaphore = semaphore;
            this.win32Handle = win32Handle;
            this.handleType = handleType;
        }

        public long win32Handle() {
            return win32Handle;
        }

        public long semaphore() {
            if (semaphore == 0L) {
                throw new IllegalStateException("exported Vulkan semaphore has already been destroyed");
            }
            return semaphore;
        }

        public int handleType() {
            return handleType;
        }

        public long detachWin32Handle() {
            long detached = win32Handle;
            win32Handle = 0L;
            handleClosed = true;
            return detached;
        }

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

        public boolean handleClosed() {
            return handleClosed;
        }

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
        public Result {
            reason = reason == null ? "" : reason;
        }

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
