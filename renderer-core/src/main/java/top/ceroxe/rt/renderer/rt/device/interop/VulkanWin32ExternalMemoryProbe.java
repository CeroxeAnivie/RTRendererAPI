package top.ceroxe.rt.renderer.rt.device.interop;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

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
    /**
     * Width, in pixels, of every temporary probe image.
     */
    public static final int PROBE_WIDTH = 64;
    /**
     * Height, in pixels, of every temporary probe image.
     */
    public static final int PROBE_HEIGHT = 64;
    private static final int HANDLE_TYPE = VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
    private static final int IMAGE_USAGE = VK10.VK_IMAGE_USAGE_STORAGE_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

    private VulkanWin32ExternalMemoryProbe() {
    }

    /**
     * Runs create, allocate, bind, export, close, and destroy validation for one image format.
     *
     * <p>The returned result owns no native resource. Expected probe failures are captured in the
     * result instead of escaping to the caller.</p>
     *
     * @param physicalDevice              physical device used to select a compatible memory type
     * @param device                      logical device used for temporary image, memory, and export operations
     * @param format                      Vulkan image format to probe
     * @param dedicatedAllocationRequired whether the export allocation must reference only the
     *                                    probe image through dedicated-allocation metadata
     * @return immutable evidence describing every attempted lifecycle stage
     * @throws NullPointerException if either device argument is {@code null}
     */
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

    /**
     * Creates an exportable image whose Vulkan and Win32 resources are owned by the result.
     *
     * @param physicalDevice              physical device used to select a compatible memory type
     * @param device                      logical device that creates and owns the image and allocation
     * @param format                      Vulkan image format to allocate
     * @param dedicatedAllocationRequired whether allocation metadata must dedicate the memory to
     *                                    the image
     * @return owner of the image, device memory, and Win32 handle; the caller must close it
     * @throws NullPointerException                                       if either device argument is {@code null}
     * @throws IllegalStateException                                      if {@code CloseHandle} is unavailable, no compatible memory
     *                                                                    type exists, allocation requirements are invalid, or Vulkan exports a null handle
     * @throws top.ceroxe.rt.renderer.api.RendererDeviceException if a Vulkan create,
     *                                                                    allocation, bind, or export operation fails
     */
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

    /**
     * Selects the first allowed memory type containing every required property flag.
     *
     * <p>Only the lower 32 entries are considered because Vulkan exposes memory-type eligibility
     * as a 32-bit mask.</p>
     *
     * @param memoryTypeBits          eligibility mask supplied by Vulkan memory requirements
     * @param memoryTypePropertyFlags property flags indexed by Vulkan memory type
     * @param requiredFlags           flags that a selected type must contain; zero accepts any eligible type
     * @return the lowest compatible memory-type index, or {@code -1} when none exists
     * @throws NullPointerException if {@code memoryTypePropertyFlags} is {@code null}
     */
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

    /**
     * Owns a probe image, its device-memory allocation, and its exported Win32 handle.
     *
     * <p>Closing the owner attempts to close the Win32 handle before freeing memory and destroying
     * the image. No accessor transfers ownership.</p>
     */
    public static final class ExportedImage implements AutoCloseable {
        private final VkDevice device;
        private final int width;
        private final int height;
        private final int vulkanFormat;
        private final long allocationSize;
        private final int memoryTypeIndex;
        private final boolean dedicatedAllocation;
        private long image;
        private long memory;
        private long win32Handle;
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

        /**
         * Returns the exported handle without transferring ownership.
         *
         * @return owned Win32 handle, or zero after successful handle closure or object closure
         */
        public long win32Handle() {
            return win32Handle;
        }

        /**
         * Returns the exported image width.
         *
         * @return width in pixels
         */
        public int width() {
            return width;
        }

        /**
         * Returns the exported image height.
         *
         * @return height in pixels
         */
        public int height() {
            return height;
        }

        /**
         * Returns the image format used at creation.
         *
         * @return Vulkan format value
         */
        public int vulkanFormat() {
            return vulkanFormat;
        }

        /**
         * Returns the device-memory allocation size.
         *
         * @return allocation size in bytes
         */
        public long allocationSize() {
            return allocationSize;
        }

        /**
         * Returns the memory type selected for the allocation.
         *
         * @return Vulkan memory-type index
         */
        public int memoryTypeIndex() {
            return memoryTypeIndex;
        }

        /**
         * Reports whether dedicated-allocation metadata was chained into the allocation.
         *
         * @return {@code true} if the device memory was explicitly dedicated to this image
         */
        public boolean dedicatedAllocation() {
            return dedicatedAllocation;
        }

        /**
         * Attempts to close this owner's Win32 handle without releasing the Vulkan resources.
         *
         * @return {@code true} if the handle was closed or no handle remains; {@code false} if
         * {@code CloseHandle} failed, in which case this object retains ownership
         */
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

        /**
         * Reports whether this owner has discharged its Win32-handle responsibility.
         *
         * @return {@code true} after successful handle closure or when no handle remained
         */
        public boolean handleClosed() {
            return handleClosed;
        }

        /**
         * Closes the Win32 handle, frees device memory, and destroys the Vulkan image.
         *
         * <p>This operation is idempotent. If Win32 handle closure fails, Vulkan resources are
         * still released; callers requiring closure confirmation should invoke
         * {@link #closeWin32Handle()} first.</p>
         */
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

    /**
     * Immutable evidence from the external-memory capability probe.
     *
     * @param attempted           whether native lifecycle work was attempted
     * @param successful          whether every required probe stage succeeded
     * @param width               probe image width in pixels
     * @param height              probe image height in pixels
     * @param format              Vulkan image format used by the probe
     * @param handleType          Vulkan external-memory handle type requested by the probe
     * @param allocationSize      device-memory allocation size in bytes, or zero before allocation
     * @param memoryTypeIndex     selected Vulkan memory type, or {@code -1} before selection
     * @param dedicatedAllocation whether dedicated-allocation metadata was requested
     * @param imageCreated        whether the temporary Vulkan image was created
     * @param memoryAllocated     whether device memory was allocated
     * @param handleExported      whether Vulkan returned a nonzero Win32 handle
     * @param handleClosed        whether the exported handle was closed successfully
     * @param reason              bounded diagnostic explanation; never {@code null}
     */
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
