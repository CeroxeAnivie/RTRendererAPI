package top.ceroxe.mcvulkanrt.renderer.rt.device.interop;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRExternalMemoryWin32;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkExportMemoryAllocateInfo;
import org.lwjgl.vulkan.VkExternalMemoryImageCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryDedicatedAllocateInfo;
import org.lwjgl.vulkan.VkMemoryGetWin32HandleInfoKHR;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Objects;

/**
 * Proves the Vulkan external-memory export contract for a host presentation adapter.
 *
 * <p>The current renderer still presents by CPU readback and texture upload. The
 * <p>This probe intentionally creates a tiny throw-away image, exports an
 * {@code OPAQUE_WIN32} NT handle, and closes that handle immediately. Keeping it
 * separate from the production output image validates driver and lifetime
 * behavior without risking the visible render path.</p>
 */
public final class VulkanWin32ExternalMemoryProbe {
    public static final int PROBE_WIDTH = 64;
    public static final int PROBE_HEIGHT = 64;
    private static final int HANDLE_TYPE = VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
    private static final int IMAGE_USAGE = VK10.VK_IMAGE_USAGE_STORAGE_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

    private VulkanWin32ExternalMemoryProbe() {
    }

    public static Result run(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            int format,
            boolean dedicatedAllocationRequired
    ) {
        Objects.requireNonNull(physicalDevice, "physicalDevice");
        Objects.requireNonNull(device, "device");
        if (!Win32HandleSupport.available()) {
            return Result.failed(
                    "CloseHandleUnavailable",
                    PROBE_WIDTH,
                    PROBE_HEIGHT,
                    format,
                    HANDLE_TYPE,
                    0L,
                    -1,
                    dedicatedAllocationRequired,
                    false,
                    false,
                    false,
                    false
            );
        }

        try {
            return runUnchecked(physicalDevice, device, format, dedicatedAllocationRequired);
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            return Result.failed(
                    ex.getClass().getSimpleName() + ": " + nullToBlank(ex.getMessage()),
                    PROBE_WIDTH,
                    PROBE_HEIGHT,
                    format,
                    HANDLE_TYPE,
                    0L,
                    -1,
                    dedicatedAllocationRequired,
                    false,
                    false,
                    false,
                    false
            );
        }
    }

    public static ExportedImage exportImage(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            int format,
            boolean dedicatedAllocationRequired
    ) {
        Objects.requireNonNull(physicalDevice, "physicalDevice");
        Objects.requireNonNull(device, "device");
        if (!Win32HandleSupport.available()) {
            throw new IllegalStateException("CloseHandle is unavailable");
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            long image = 0L;
            long memory = 0L;
            long win32Handle = 0L;
            try {
                image = createExportableImage(stack, device, format);

                VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
                VK10.vkGetImageMemoryRequirements(device, image, requirements);
                long allocationSize = requirements.size();
                int memoryTypeIndex = chooseMemoryTypeIndex(stack, physicalDevice, requirements.memoryTypeBits());

                memory = allocateExportableImageMemory(
                        stack,
                        device,
                        image,
                        allocationSize,
                        memoryTypeIndex,
                        dedicatedAllocationRequired
                );
                checkVk(VK10.vkBindImageMemory(device, image, memory, 0L), "vkBindImageMemory.externalInteropExport");
                win32Handle = exportWin32Handle(stack, device, memory);

                ExportedImage exported = new ExportedImage(
                        device,
                        image,
                        memory,
                        win32Handle,
                        PROBE_WIDTH,
                        PROBE_HEIGHT,
                        format,
                        allocationSize,
                        memoryTypeIndex,
                        dedicatedAllocationRequired
                );
                image = 0L;
                memory = 0L;
                win32Handle = 0L;
                return exported;
            } finally {
                if (win32Handle != 0L) {
                    closeWin32Handle(win32Handle);
                }
                if (memory != 0L) {
                    VK10.vkFreeMemory(device, memory, null);
                }
                if (image != 0L) {
                    VK10.vkDestroyImage(device, image, null);
                }
            }
        }
    }

    public static int selectMemoryTypeIndex(int memoryTypeBits, int[] memoryTypePropertyFlags, int requiredFlags) {
        Objects.requireNonNull(memoryTypePropertyFlags, "memoryTypePropertyFlags");
        int typeCount = Math.min(memoryTypePropertyFlags.length, Integer.SIZE);
        for (int index = 0; index < typeCount; index++) {
            int mask = 1 << index;
            if ((memoryTypeBits & mask) != 0 && (memoryTypePropertyFlags[index] & requiredFlags) == requiredFlags) {
                return index;
            }
        }
        return -1;
    }

    private static Result runUnchecked(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            int format,
            boolean dedicatedAllocationRequired
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long image = 0L;
            long memory = 0L;
            long win32Handle = 0L;
            long allocationSize = 0L;
            int memoryTypeIndex = -1;
            boolean handleClosed = false;
            try {
                image = createExportableImage(stack, device, format);

                VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
                VK10.vkGetImageMemoryRequirements(device, image, requirements);
                allocationSize = requirements.size();
                memoryTypeIndex = chooseMemoryTypeIndex(stack, physicalDevice, requirements.memoryTypeBits());

                memory = allocateExportableImageMemory(
                        stack,
                        device,
                        image,
                        allocationSize,
                        memoryTypeIndex,
                        dedicatedAllocationRequired
                );
                checkVk(VK10.vkBindImageMemory(device, image, memory, 0L), "vkBindImageMemory.externalInteropProbe");

                win32Handle = exportWin32Handle(stack, device, memory);
                handleClosed = Win32HandleSupport.close(win32Handle);
                win32Handle = 0L;
                if (!handleClosed) {
                    return Result.failed(
                            "CloseHandleFailed:lastError=" + Win32HandleSupport.lastError(),
                            PROBE_WIDTH,
                            PROBE_HEIGHT,
                            format,
                            HANDLE_TYPE,
                            allocationSize,
                            memoryTypeIndex,
                            dedicatedAllocationRequired,
                            true,
                            true,
                            true,
                            false
                    );
                }

                return Result.success(
                        PROBE_WIDTH,
                        PROBE_HEIGHT,
                        format,
                        HANDLE_TYPE,
                        allocationSize,
                        memoryTypeIndex,
                        dedicatedAllocationRequired
                );
            } finally {
                if (win32Handle != 0L) {
                    Win32HandleSupport.close(win32Handle);
                }
                if (memory != 0L) {
                    VK10.vkFreeMemory(device, memory, null);
                }
                if (image != 0L) {
                    VK10.vkDestroyImage(device, image, null);
                }
            }
        }
    }

    private static long createExportableImage(MemoryStack stack, VkDevice device, int format) {
        VkExternalMemoryImageCreateInfo externalMemoryImageCreateInfo =
                VkExternalMemoryImageCreateInfo.calloc(stack)
                        .sType$Default()
                        .handleTypes(HANDLE_TYPE);
        VkImageCreateInfo imageCreateInfo = VkImageCreateInfo.calloc(stack)
                .sType$Default()
                .pNext(externalMemoryImageCreateInfo.address())
                .imageType(VK10.VK_IMAGE_TYPE_2D)
                .format(format)
                .mipLevels(1)
                .arrayLayers(1)
                .samples(VK10.VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
                .usage(IMAGE_USAGE)
                .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
        imageCreateInfo.extent()
                .width(PROBE_WIDTH)
                .height(PROBE_HEIGHT)
                .depth(1);

        LongBuffer imageHandle = stack.longs(0L);
        checkVk(VK10.vkCreateImage(device, imageCreateInfo, null, imageHandle), "vkCreateImage.externalInteropProbe");
        return imageHandle.get(0);
    }

    private static int chooseMemoryTypeIndex(
            MemoryStack stack,
            VkPhysicalDevice physicalDevice,
            int memoryTypeBits
    ) {
        VkPhysicalDeviceMemoryProperties memoryProperties = VkPhysicalDeviceMemoryProperties.calloc(stack);
        VK10.vkGetPhysicalDeviceMemoryProperties(physicalDevice, memoryProperties);

        int memoryTypeCount = memoryProperties.memoryTypeCount();
        int[] propertyFlags = new int[memoryTypeCount];
        for (int index = 0; index < memoryTypeCount; index++) {
            propertyFlags[index] = memoryProperties.memoryTypes(index).propertyFlags();
        }

        int deviceLocal = selectMemoryTypeIndex(
                memoryTypeBits,
                propertyFlags,
                VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
        );
        if (deviceLocal >= 0) {
            return deviceLocal;
        }

        int fallback = selectMemoryTypeIndex(memoryTypeBits, propertyFlags, 0);
        if (fallback >= 0) {
            return fallback;
        }
        throw new IllegalStateException("no compatible memory type for external image: bits=0x"
                + Integer.toHexString(memoryTypeBits));
    }

    private static long allocateExportableImageMemory(
            MemoryStack stack,
            VkDevice device,
            long image,
            long allocationSize,
            int memoryTypeIndex,
            boolean dedicatedAllocationRequired
    ) {
        if (allocationSize <= 0L) {
            throw new IllegalStateException("external image memory requirement returned non-positive size");
        }
        if (memoryTypeIndex < 0) {
            throw new IllegalStateException("external image memory type was not selected");
        }

        VkExportMemoryAllocateInfo exportMemoryAllocateInfo = VkExportMemoryAllocateInfo.calloc(stack)
                .sType$Default()
                .handleTypes(HANDLE_TYPE);
        VkMemoryAllocateInfo allocateInfo = VkMemoryAllocateInfo.calloc(stack)
                .sType$Default()
                .allocationSize(allocationSize)
                .memoryTypeIndex(memoryTypeIndex);

        if (dedicatedAllocationRequired) {
            VkMemoryDedicatedAllocateInfo dedicatedAllocateInfo = VkMemoryDedicatedAllocateInfo.calloc(stack)
                    .sType$Default()
                    .image(image)
                    .buffer(VK10.VK_NULL_HANDLE);
            exportMemoryAllocateInfo.pNext(dedicatedAllocateInfo.address());
        }
        allocateInfo.pNext(exportMemoryAllocateInfo.address());

        LongBuffer memoryHandle = stack.longs(0L);
        checkVk(VK10.vkAllocateMemory(device, allocateInfo, null, memoryHandle), "vkAllocateMemory.externalInteropProbe");
        return memoryHandle.get(0);
    }

    private static long exportWin32Handle(MemoryStack stack, VkDevice device, long memory) {
        VkMemoryGetWin32HandleInfoKHR handleInfo = VkMemoryGetWin32HandleInfoKHR.calloc(stack)
                .sType$Default()
                .memory(memory)
                .handleType(HANDLE_TYPE);
        PointerBuffer handle = stack.mallocPointer(1);
        checkVk(
                KHRExternalMemoryWin32.vkGetMemoryWin32HandleKHR(device, handleInfo, handle),
                "vkGetMemoryWin32HandleKHR.externalInteropProbe"
        );
        long value = handle.get(0);
        if (value == 0L) {
            throw new IllegalStateException("vkGetMemoryWin32HandleKHR returned a null handle");
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
            case VK10.VK_ERROR_FORMAT_NOT_SUPPORTED -> "VK_ERROR_FORMAT_NOT_SUPPORTED";
            case VK11.VK_ERROR_INVALID_EXTERNAL_HANDLE -> "VK_ERROR_INVALID_EXTERNAL_HANDLE";
            default -> Integer.toString(result);
        };
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    static boolean closeWin32Handle(long handle) {
        return Win32HandleSupport.close(handle);
    }

    public static final class ExportedImage implements AutoCloseable {
        private final VkDevice device;
        private long image;
        private long memory;
        private long win32Handle;
        private final int width;
        private final int height;
        private final int vulkanFormat;
        private final long allocationSize;
        private final int memoryTypeIndex;
        private final boolean dedicatedAllocation;
        private boolean handleClosed;
        private boolean closed;

        private ExportedImage(
                VkDevice device,
                long image,
                long memory,
                long win32Handle,
                int width,
                int height,
                int vulkanFormat,
                long allocationSize,
                int memoryTypeIndex,
                boolean dedicatedAllocation
        ) {
            this.device = Objects.requireNonNull(device, "device");
            this.image = image;
            this.memory = memory;
            this.win32Handle = win32Handle;
            this.width = width;
            this.height = height;
            this.vulkanFormat = vulkanFormat;
            this.allocationSize = allocationSize;
            this.memoryTypeIndex = memoryTypeIndex;
            this.dedicatedAllocation = dedicatedAllocation;
        }

        public long win32Handle() {
            return win32Handle;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public int vulkanFormat() {
            return vulkanFormat;
        }

        public long allocationSize() {
            return allocationSize;
        }

        public int memoryTypeIndex() {
            return memoryTypeIndex;
        }

        public boolean dedicatedAllocation() {
            return dedicatedAllocation;
        }

        public boolean closeWin32Handle() {
            if (win32Handle == 0L) {
                handleClosed = true;
                return true;
            }
            handleClosed = VulkanWin32ExternalMemoryProbe.closeWin32Handle(win32Handle);
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
            if (memory != 0L) {
                VK10.vkFreeMemory(device, memory, null);
                memory = 0L;
            }
            if (image != 0L) {
                VK10.vkDestroyImage(device, image, null);
                image = 0L;
            }
        }
    }

    public record Result(
            boolean attempted,
            boolean successful,
            int width,
            int height,
            int format,
            int handleType,
            long allocationSize,
            int memoryTypeIndex,
            boolean dedicatedAllocation,
            boolean imageCreated,
            boolean memoryAllocated,
            boolean handleExported,
            boolean handleClosed,
            String reason
    ) {
        public Result {
            reason = reason == null ? "" : reason;
        }

        public static Result skipped(String reason) {
            return new Result(
                    false,
                    false,
                    PROBE_WIDTH,
                    PROBE_HEIGHT,
                    VK10.VK_FORMAT_UNDEFINED,
                    HANDLE_TYPE,
                    0L,
                    -1,
                    false,
                    false,
                    false,
                    false,
                    false,
                    reason
            );
        }

        static Result success(
                int width,
                int height,
                int format,
                int handleType,
                long allocationSize,
                int memoryTypeIndex,
                boolean dedicatedAllocation
        ) {
            return new Result(
                    true,
                    true,
                    width,
                    height,
                    format,
                    handleType,
                    allocationSize,
                    memoryTypeIndex,
                    dedicatedAllocation,
                    true,
                    true,
                    true,
                    true,
                    "ready"
            );
        }

        static Result failed(
                String reason,
                int width,
                int height,
                int format,
                int handleType,
                long allocationSize,
                int memoryTypeIndex,
                boolean dedicatedAllocation,
                boolean imageCreated,
                boolean memoryAllocated,
                boolean handleExported,
                boolean handleClosed
        ) {
            return new Result(
                    true,
                    false,
                    width,
                    height,
                    format,
                    handleType,
                    allocationSize,
                    memoryTypeIndex,
                    dedicatedAllocation,
                    imageCreated,
                    memoryAllocated,
                    handleExported,
                    handleClosed,
                    reason
            );
        }

        public String summary(String name) {
            return name
                    + "{attempted=" + attempted
                    + ", successful=" + successful
                    + ", extent=" + width + "x" + height
                    + ", format=" + format
                    + ", handleType=0x" + Integer.toHexString(handleType)
                    + ", allocationSize=" + allocationSize
                    + ", memoryTypeIndex=" + memoryTypeIndex
                    + ", dedicatedAllocation=" + dedicatedAllocation
                    + ", imageCreated=" + imageCreated
                    + ", memoryAllocated=" + memoryAllocated
                    + ", handleExported=" + handleExported
                    + ", handleClosed=" + handleClosed
                    + ", reason=" + reason
                    + "}";
        }
    }
}
