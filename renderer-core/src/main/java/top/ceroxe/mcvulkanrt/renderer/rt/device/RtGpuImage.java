package top.ceroxe.mcvulkanrt.renderer.rt.device;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.KHRExternalMemoryWin32;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkExportMemoryAllocateInfo;
import org.lwjgl.vulkan.VkExternalMemoryImageCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryDedicatedAllocateInfo;
import org.lwjgl.vulkan.VkMemoryGetWin32HandleInfoKHR;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import top.ceroxe.mcvulkanrt.renderer.rt.device.interop.VulkanWin32ExternalMemoryProbe;
import top.ceroxe.mcvulkanrt.renderer.rt.device.interop.Win32HandleSupport;

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

    public static RtGpuImage createStorageImage(
            VkDevice device,
            long allocator,
            int width,
            int height,
            int format
    ) {
        Objects.requireNonNull(device, "device");
        if (allocator == 0L) {
            throw new IllegalArgumentException("allocator must not be null");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("image dimensions must be positive");
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo imageCreateInfo = storageImageCreateInfo(stack, width, height, format, 0L);

            VmaAllocationCreateInfo allocationCreateInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);

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
                long imageView = createImageView(stack, device, image, format);
                return new RtGpuImage(
                        device,
                        allocator,
                        image,
                        allocation,
                        VK10.VK_NULL_HANDLE,
                        imageView,
                        width,
                        height,
                        format,
                        STORAGE_IMAGE_USAGE,
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

    public static RtGpuImage createExportableStorageImage(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            int width,
            int height,
            int format,
            boolean dedicatedAllocationRequired
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
            VkImageCreateInfo imageCreateInfo = storageImageCreateInfo(
                    stack,
                    width,
                    height,
                    format,
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
                        STORAGE_IMAGE_USAGE,
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

    public long image() {
        return image;
    }

    public long memory() {
        return memory;
    }

    public long imageView() {
        return imageView;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int format() {
        return format;
    }

    public int usageFlags() {
        return usageFlags;
    }

    public long allocationSize() {
        return allocationSize;
    }

    public int memoryTypeIndex() {
        return memoryTypeIndex;
    }

    public boolean exportableWin32Memory() {
        return exportableWin32Memory;
    }

    public boolean dedicatedAllocation() {
        return dedicatedAllocation;
    }

    public synchronized long sharedWin32MemoryHandle() {
        if (sharedWin32MemoryHandle != 0L) {
            return sharedWin32MemoryHandle;
        }
        sharedWin32MemoryHandle = exportWin32MemoryHandle();
        sharedWin32MemoryHandleExports++;
        return sharedWin32MemoryHandle;
    }

    /**
     * Exports a fresh OS handle for an independently owned public frame lease.
     *
     * <p>Unlike the legacy cached presentation handle, ownership of this handle transfers to the
     * caller immediately. The caller must close it, or record a successful import according to
     * the Vulkan handle-type ownership rule.</p>
     */
    public synchronized long exportSharedWin32MemoryHandle() {
        if (closed) throw new IllegalStateException("image is already closed");
        return exportWin32MemoryHandle();
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

    public long pixelBytes() {
        return (long) width * height * Integer.BYTES;
    }

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

    private static VkImageCreateInfo storageImageCreateInfo(
            MemoryStack stack,
            int width,
            int height,
            int format,
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
                .usage(STORAGE_IMAGE_USAGE)
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
            default -> Integer.toString(result);
        };
    }
}
