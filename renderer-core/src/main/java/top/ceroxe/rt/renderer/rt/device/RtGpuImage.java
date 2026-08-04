package top.ceroxe.rt.renderer.rt.device;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.*;
import top.ceroxe.rt.renderer.rt.device.interop.VulkanWin32ExternalMemoryProbe;
import top.ceroxe.rt.renderer.rt.device.interop.Win32HandleSupport;

import java.nio.LongBuffer;
import java.util.Objects;

/**
 * VMA backed 2D image plus image view.
 *
 * <p>Image and buffer lifetimes intentionally stay separate. Vulkan images carry
 * layout state and view metadata that buffers do not have, and merging both
 * concepts into one wrapper would make later frame-resource ownership harder to
 * audit.</p>
 */
public final class RtGpuImage implements AutoCloseable {
    private static final int EXTERNAL_WIN32_HANDLE_TYPE = VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
    private static final int STORAGE_IMAGE_USAGE = VK10.VK_IMAGE_USAGE_STORAGE_BIT
            | VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT
            | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    private static final int STORAGE_SAMPLED_IMAGE_USAGE = STORAGE_IMAGE_USAGE
            | VK10.VK_IMAGE_USAGE_SAMPLED_BIT;

    private final VkDevice device;
    private final long allocator;
    private final long image;
    private final long allocation;
    private final long memory;
    private final long imageView;
    private final int width;
    private final int height;
    private final int format;
    private final int usageFlags;
    private final long allocationSize;
    private final int memoryTypeIndex;
    private final boolean exportableWin32Memory;
    private final boolean dedicatedAllocation;
    private long sharedWin32MemoryHandle;
    private long sharedWin32MemoryHandleExports;
    private boolean closed;

    private RtGpuImage(
            VkDevice device,
            long allocator,
            long image,
            long allocation,
            long memory,
            long imageView,
            int width,
            int height,
            int format,
            int usageFlags,
            long allocationSize,
            int memoryTypeIndex,
            boolean exportableWin32Memory,
            boolean dedicatedAllocation
    ) {
        this.device = Objects.requireNonNull(device, "device");
        this.allocator = allocator;
        this.image = image;
        this.allocation = allocation;
        this.memory = memory;
        this.imageView = imageView;
        this.width = width;
        this.height = height;
        this.format = format;
        this.usageFlags = usageFlags;
        this.allocationSize = allocationSize;
        this.memoryTypeIndex = memoryTypeIndex;
        this.exportableWin32Memory = exportableWin32Memory;
        this.dedicatedAllocation = dedicatedAllocation;
    }

    /**
     * Creates a device-local VMA-owned storage image and full-color image view.
     *
     * @param device    logical device that owns the image and view
     * @param allocator live VMA allocator associated with {@code device}
     * @param width     positive image width in pixels
     * @param height    positive image height in pixels
     * @param format    Vulkan image format
     * @return independently owned image that the caller must close after GPU use completes
     */
    public static RtGpuImage createStorageImage(
            VkDevice device,
            long allocator,
            int width,
            int height,
            int format
    ) {
        return createVmaImage(device, allocator, width, height, format, STORAGE_IMAGE_USAGE);
    }

    /**
     * Creates a device-local image that can be written by the renderer and sampled by a
     * reconstruction implementation such as DLSS.
     *
     * <p>This is deliberately separate from {@link #createStorageImage(VkDevice, long, int, int, int)}.
     * Adding sampled usage to every renderer storage image would broaden resource contracts and
     * driver requirements for unrelated render targets.</p>
     *
     * @param device    logical device that owns the image and view
     * @param allocator live VMA allocator associated with {@code device}
     * @param width     positive image width in pixels
     * @param height    positive image height in pixels
     * @param format    Vulkan image format
     * @return independently owned storage-and-sampled image
     */
    public static RtGpuImage createStorageSampledImage(
            VkDevice device,
            long allocator,
            int width,
            int height,
            int format
    ) {
        return createVmaImage(device, allocator, width, height, format, STORAGE_SAMPLED_IMAGE_USAGE);
    }

    private static RtGpuImage createVmaImage(
            VkDevice device,
            long allocator,
            int width,
            int height,
            int format,
            int usageFlags
    ) {
        Objects.requireNonNull(device, "device");
        if (allocator == 0L) {
            throw new IllegalArgumentException("allocator must not be null");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("image dimensions must be positive");
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo imageCreateInfo = imageCreateInfo(stack, width, height, format, usageFlags, 0L);

            VmaAllocationCreateInfo allocationCreateInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE)
                    .flags(Vma.VMA_ALLOCATION_CREATE_WITHIN_BUDGET_BIT);

            LongBuffer imageHandle = stack.longs(0L);
            PointerBuffer allocationHandle = stack.mallocPointer(1);
            checkVk(
                    Vma.vmaCreateImage(allocator, imageCreateInfo, allocationCreateInfo, imageHandle, allocationHandle, null),
                    "vmaCreateImage"
            );

            long image = imageHandle.get(0);
            long allocation = allocationHandle.get(0);
            long allocationSize = 0L;
            try {
                allocationSize = queryMemoryRequirements(stack, device, image).size();
                VmaAllocationInfo allocationInfo = VmaAllocationInfo.calloc(stack);
                Vma.vmaGetAllocationInfo(allocator, allocation, allocationInfo);
                long memory = allocationInfo.deviceMemory();
                if (memory == VK10.VK_NULL_HANDLE) {
                    throw new IllegalStateException("VMA storage image has no VkDeviceMemory backing");
                }
                long imageView = createImageView(stack, device, image, format);
                return new RtGpuImage(
                        device,
                        allocator,
                        image,
                        allocation,
                        memory,
                        imageView,
                        width,
                        height,
                        format,
                        usageFlags,
                        allocationSize,
                        -1,
                        false,
                        false
                );
            } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
                Vma.vmaDestroyImage(allocator, image, allocation);
                throw ex;
            }
        }
    }

    /**
     * Creates dedicated Vulkan-owned storage whose memory may be exported as OPAQUE_WIN32.
     * The returned image owns its memory, view, image, and anchor operating-system handle.
     *
     * @param physicalDevice              physical device used to select a compatible memory type
     * @param device                      logical device that owns the image, memory, and view
     * @param width                       positive image width in pixels
     * @param height                      positive image height in pixels
     * @param format                      Vulkan image format
     * @param dedicatedAllocationRequired whether allocation metadata must dedicate memory to the image
     * @return independently owned exportable image that the caller must close
     */
    public static RtGpuImage createExportableStorageImage(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            int width,
            int height,
            int format,
            boolean dedicatedAllocationRequired
    ) {
        return createExportableImage(
                physicalDevice, device, width, height, format,
                dedicatedAllocationRequired, STORAGE_IMAGE_USAGE
        );
    }

    /**
     * Creates exportable storage that may also be sampled by presentation-time features.
     *
     * @param physicalDevice physical device used to select an export-compatible memory type
     * @param device logical device that owns the image, memory, and view
     * @param width positive image width in pixels
     * @param height positive image height in pixels
     * @param format Vulkan image format
     * @param dedicatedAllocationRequired whether the export contract requires dedicated memory
     * @return independently owned exportable storage-and-sampled image
     */
    public static RtGpuImage createExportableStorageSampledImage(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            int width,
            int height,
            int format,
            boolean dedicatedAllocationRequired
    ) {
        return createExportableImage(
                physicalDevice, device, width, height, format,
                dedicatedAllocationRequired, STORAGE_SAMPLED_IMAGE_USAGE
        );
    }

    private static RtGpuImage createExportableImage(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            int width,
            int height,
            int format,
            boolean dedicatedAllocationRequired,
            int usageFlags
    ) {
        Objects.requireNonNull(physicalDevice, "physicalDevice");
        Objects.requireNonNull(device, "device");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("image dimensions must be positive");
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkExternalMemoryImageCreateInfo externalImageInfo = VkExternalMemoryImageCreateInfo.calloc(stack)
                    .sType$Default()
                    .handleTypes(EXTERNAL_WIN32_HANDLE_TYPE);
            VkImageCreateInfo imageCreateInfo = imageCreateInfo(
                    stack,
                    width,
                    height,
                    format,
                    usageFlags,
                    externalImageInfo.address()
            );

            LongBuffer imageHandle = stack.longs(0L);
            checkVk(VK10.vkCreateImage(device, imageCreateInfo, null, imageHandle), "vkCreateImage.exportableStorage");
            long image = imageHandle.get(0);
            long memory = 0L;
            try {
                VkMemoryRequirements requirements = queryMemoryRequirements(stack, device, image);
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
                checkVk(VK10.vkBindImageMemory(device, image, memory, 0L), "vkBindImageMemory.exportableStorage");
                long imageView = createImageView(stack, device, image, format);
                return new RtGpuImage(
                        device,
                        VK10.VK_NULL_HANDLE,
                        image,
                        VK10.VK_NULL_HANDLE,
                        memory,
                        imageView,
                        width,
                        height,
                        format,
                        usageFlags,
                        allocationSize,
                        memoryTypeIndex,
                        true,
                        dedicatedAllocationRequired
                );
            } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
                if (memory != 0L) {
                    VK10.vkFreeMemory(device, memory, null);
                }
                VK10.vkDestroyImage(device, image, null);
                throw ex;
            }
        }
    }

    private static VkImageCreateInfo imageCreateInfo(
            MemoryStack stack,
            int width,
            int height,
            int format,
            int usageFlags,
            long pNext
    ) {
        VkImageCreateInfo imageCreateInfo = VkImageCreateInfo.calloc(stack)
                .sType$Default()
                .pNext(pNext)
                .imageType(VK10.VK_IMAGE_TYPE_2D)
                .format(format)
                .mipLevels(1)
                .arrayLayers(1)
                .samples(VK10.VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
                .usage(usageFlags)
                .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
        imageCreateInfo.extent()
                .width(width)
                .height(height)
                .depth(1);
        return imageCreateInfo;
    }

    private static VkMemoryRequirements queryMemoryRequirements(MemoryStack stack, VkDevice device, long image) {
        VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
        VK10.vkGetImageMemoryRequirements(device, image, requirements);
        if (requirements.size() <= 0L) {
            throw new IllegalStateException("image memory requirement returned non-positive size");
        }
        return requirements;
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

        int deviceLocal = VulkanWin32ExternalMemoryProbe.selectMemoryTypeIndex(
                memoryTypeBits,
                propertyFlags,
                VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
        );
        if (deviceLocal >= 0) {
            return deviceLocal;
        }

        int fallback = VulkanWin32ExternalMemoryProbe.selectMemoryTypeIndex(memoryTypeBits, propertyFlags, 0);
        if (fallback >= 0) {
            return fallback;
        }
        throw new IllegalStateException("no compatible memory type for storage image: bits=0x"
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
        VkExportMemoryAllocateInfo exportInfo = VkExportMemoryAllocateInfo.calloc(stack)
                .sType$Default()
                .handleTypes(EXTERNAL_WIN32_HANDLE_TYPE);
        VkMemoryAllocateInfo allocateInfo = VkMemoryAllocateInfo.calloc(stack)
                .sType$Default()
                .allocationSize(allocationSize)
                .memoryTypeIndex(memoryTypeIndex);
        if (dedicatedAllocationRequired) {
            VkMemoryDedicatedAllocateInfo dedicatedInfo = VkMemoryDedicatedAllocateInfo.calloc(stack)
                    .sType$Default()
                    .image(image)
                    .buffer(VK10.VK_NULL_HANDLE);
            exportInfo.pNext(dedicatedInfo.address());
        }
        allocateInfo.pNext(exportInfo.address());

        LongBuffer memoryHandle = stack.longs(0L);
        checkVk(VK10.vkAllocateMemory(device, allocateInfo, null, memoryHandle), "vkAllocateMemory.exportableStorage");
        return memoryHandle.get(0);
    }

    private static long createImageView(MemoryStack stack, VkDevice device, long image, int format) {
        VkImageViewCreateInfo createInfo = VkImageViewCreateInfo.calloc(stack)
                .sType$Default()
                .image(image)
                .viewType(VK10.VK_IMAGE_VIEW_TYPE_2D)
                .format(format);
        createInfo.components()
                .r(VK10.VK_COMPONENT_SWIZZLE_IDENTITY)
                .g(VK10.VK_COMPONENT_SWIZZLE_IDENTITY)
                .b(VK10.VK_COMPONENT_SWIZZLE_IDENTITY)
                .a(VK10.VK_COMPONENT_SWIZZLE_IDENTITY);
        createInfo.subresourceRange()
                .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1);

        LongBuffer imageViewHandle = stack.longs(0L);
        checkVk(VK10.vkCreateImageView(device, createInfo, null, imageViewHandle), "vkCreateImageView");
        return imageViewHandle.get(0);
    }

    private static void checkVk(int result, String stage) {
        VulkanFailures.check(result, stage);
    }

    private static String vkResultName(int result) {
        return switch (result) {
            case VK10.VK_SUCCESS -> "VK_SUCCESS";
            case VK10.VK_ERROR_OUT_OF_HOST_MEMORY -> "VK_ERROR_OUT_OF_HOST_MEMORY";
            case VK10.VK_ERROR_OUT_OF_DEVICE_MEMORY -> "VK_ERROR_OUT_OF_DEVICE_MEMORY";
            case VK10.VK_ERROR_INITIALIZATION_FAILED -> "VK_ERROR_INITIALIZATION_FAILED";
            default -> Integer.toString(result);
        };
    }

    /**
     * Returns the native image without transferring ownership.
     *
     * @return owned {@code VkImage}, valid until {@link #close()}
     */
    public long image() {
        return image;
    }

    /**
     * Returns directly allocated device memory without transferring ownership.
     *
     * @return owned {@code VkDeviceMemory}; VMA-backed images expose the allocation's device memory
     */
    public long memory() {
        return memory;
    }

    /**
     * Returns the full-color image view without transferring ownership.
     *
     * @return owned {@code VkImageView}, valid until {@link #close()}
     */
    public long imageView() {
        return imageView;
    }

    /**
     * Returns the image width.
     *
     * @return width in pixels
     */
    public int width() {
        return width;
    }

    /**
     * Returns the image height.
     *
     * @return height in pixels
     */
    public int height() {
        return height;
    }

    /**
     * Returns the image format.
     *
     * @return Vulkan format value
     */
    public int format() {
        return format;
    }

    /**
     * Returns the creation usage flags.
     *
     * @return Vulkan image usage mask
     */
    public int usageFlags() {
        return usageFlags;
    }

    /**
     * Returns the native allocation requirement.
     *
     * @return allocation size in bytes
     */
    public long allocationSize() {
        return allocationSize;
    }

    /**
     * Returns the selected Vulkan memory type.
     *
     * @return memory-type index, or {@code -1} for a VMA-owned allocation
     */
    public int memoryTypeIndex() {
        return memoryTypeIndex;
    }

    /**
     * Reports whether this image supports {@code OPAQUE_WIN32} memory export.
     *
     * @return {@code true} when shared Win32 handles may be requested
     */
    public boolean exportableWin32Memory() {
        return exportableWin32Memory;
    }

    /**
     * Reports whether the image uses dedicated Vulkan memory.
     *
     * @return {@code true} when allocation metadata dedicates the memory to this image
     */
    public boolean dedicatedAllocation() {
        return dedicatedAllocation;
    }

    /**
     * Returns the image-owned anchor handle, exporting it once on demand.
     * Callers must not close this handle; use {@link #exportSharedWin32MemoryHandle()} for transfer.
     *
     * @return valid nonzero Win32 handle owned by this image
     */
    public synchronized long sharedWin32MemoryHandle() {
        if (sharedWin32MemoryHandle != 0L) {
            return sharedWin32MemoryHandle;
        }
        sharedWin32MemoryHandle = exportWin32MemoryHandle();
        sharedWin32MemoryHandleExports++;
        return sharedWin32MemoryHandle;
    }

    /**
     * Duplicates a fresh OS handle for an independently owned public frame lease.
     *
     * <p>The Vulkan export is retained as an image-owned anchor because some drivers return an
     * invalid stale value when the same memory is exported again after its first handle is closed.
     * Each lease instead receives a process-local duplicate that it can close independently.</p>
     *
     * @return independently owned Win32 handle that the caller must close
     */
    public synchronized long exportSharedWin32MemoryHandle() {
        if (closed) throw new IllegalStateException("image is already closed");
        return Win32HandleSupport.duplicate(sharedWin32MemoryHandle());
    }

    private long exportWin32MemoryHandle() {
        if (!exportableWin32Memory || memory == 0L) {
            throw new IllegalStateException("image memory is not exportable as an OPAQUE_WIN32 handle");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryGetWin32HandleInfoKHR handleInfo = VkMemoryGetWin32HandleInfoKHR.calloc(stack)
                    .sType$Default()
                    .memory(memory)
                    .handleType(EXTERNAL_WIN32_HANDLE_TYPE);
            PointerBuffer handle = stack.mallocPointer(1);
            checkVk(
                    KHRExternalMemoryWin32.vkGetMemoryWin32HandleKHR(device, handleInfo, handle),
                    "vkGetMemoryWin32HandleKHR.rtOutput"
            );
            long value = handle.get(0);
            if (value == 0L) {
                throw new IllegalStateException("vkGetMemoryWin32HandleKHR returned a null handle");
            }
            return value;
        }
    }

    /**
     * Returns the logical packed RGBA8 payload size.
     *
     * @return pixel byte count
     */
    public long pixelBytes() {
        return (long) width * height * Integer.BYTES;
    }

    /**
     * Builds a bounded native-resource summary.
     *
     * @param name diagnostic label
     * @return summary containing image metadata but no pixel contents
     */
    public String summary(String name) {
        return name
                + "{image=0x" + Long.toHexString(image)
                + ", view=0x" + Long.toHexString(imageView)
                + ", allocation=0x" + Long.toHexString(allocation)
                + ", memory=0x" + Long.toHexString(memory)
                + ", extent=" + width + "x" + height
                + ", format=" + format
                + ", usage=0x" + Integer.toHexString(usageFlags)
                + ", allocationSize=" + allocationSize
                + ", memoryTypeIndex=" + memoryTypeIndex
                + ", exportableWin32Memory=" + exportableWin32Memory
                + ", dedicatedAllocation=" + dedicatedAllocation
                + ", sharedWin32MemoryHandle=0x" + Long.toHexString(sharedWin32MemoryHandle)
                + ", sharedWin32MemoryHandleExports=" + sharedWin32MemoryHandleExports
                + "}";
    }

    /**
     * Closes the image-owned Win32 anchor, image view, image, and allocation.
     *
     * <p>Independently duplicated lease handles returned by
     * {@link #exportSharedWin32MemoryHandle()} remain caller-owned.</p>
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (sharedWin32MemoryHandle != 0L) {
            Win32HandleSupport.close(sharedWin32MemoryHandle);
            sharedWin32MemoryHandle = 0L;
        }
        VK10.vkDestroyImageView(device, imageView, null);
        if (allocation != 0L) {
            Vma.vmaDestroyImage(allocator, image, allocation);
            return;
        }
        VK10.vkDestroyImage(device, image, null);
        if (memory != 0L) {
            VK10.vkFreeMemory(device, memory, null);
        }
    }
}
