package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

/** One persistently mapped, frame-context-owned transfer source for the optional text HUD. */
final class VulkanTextOverlayUpload implements AutoCloseable {
    static final long CAPACITY_BYTES = (long) VulkanTextOverlayRasterizer.MAX_WIDTH
            * VulkanTextOverlayRasterizer.MAX_HEIGHT * 4L;

    private final VkDevice device;
    private long buffer;
    private long memory;
    private long mappedAddress;
    private ByteBuffer mappedBytes;

    private VulkanTextOverlayUpload(
            VkDevice device,
            long buffer,
            long memory,
            long mappedAddress
    ) {
        this.device = device;
        this.buffer = buffer;
        this.memory = memory;
        this.mappedAddress = mappedAddress;
        mappedBytes = MemoryUtil.memByteBuffer(mappedAddress, Math.toIntExact(CAPACITY_BYTES));
    }

    static VulkanTextOverlayUpload create(VkPhysicalDevice physicalDevice, VkDevice device) {
        long buffer = 0L;
        long memory = 0L;
        long mappedAddress = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer bufferHandle = stack.longs(0L);
            checkVk(
                    VK10.vkCreateBuffer(
                            device,
                            VkBufferCreateInfo.calloc(stack)
                                    .sType$Default()
                                    .size(CAPACITY_BYTES)
                                    .usage(VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE),
                            null,
                            bufferHandle
                    ),
                    "vkCreateBuffer.presenterOverlay"
            );
            buffer = bufferHandle.get(0);

            VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
            VK10.vkGetBufferMemoryRequirements(device, buffer, requirements);
            int memoryTypeIndex = selectHostCoherentMemoryType(
                    stack,
                    physicalDevice,
                    requirements.memoryTypeBits()
            );
            LongBuffer memoryHandle = stack.longs(0L);
            checkVk(
                    VK10.vkAllocateMemory(
                            device,
                            VkMemoryAllocateInfo.calloc(stack)
                                    .sType$Default()
                                    .allocationSize(requirements.size())
                                    .memoryTypeIndex(memoryTypeIndex),
                            null,
                            memoryHandle
                    ),
                    "vkAllocateMemory.presenterOverlay"
            );
            memory = memoryHandle.get(0);
            checkVk(
                    VK10.vkBindBufferMemory(device, buffer, memory, 0L),
                    "vkBindBufferMemory.presenterOverlay"
            );
            PointerBuffer mapped = stack.mallocPointer(1);
            checkVk(
                    VK10.vkMapMemory(device, memory, 0L, VK10.VK_WHOLE_SIZE, 0, mapped),
                    "vkMapMemory.presenterOverlay"
            );
            mappedAddress = mapped.get(0);
            return new VulkanTextOverlayUpload(device, buffer, memory, mappedAddress);
        } catch (RuntimeException | Error failure) {
            if (mappedAddress != 0L) VK10.vkUnmapMemory(device, memory);
            if (buffer != 0L) VK10.vkDestroyBuffer(device, buffer, null);
            if (memory != 0L) VK10.vkFreeMemory(device, memory, null);
            throw failure;
        }
    }

    long buffer() {
        if (buffer == 0L) throw new IllegalStateException("text overlay upload is closed");
        return buffer;
    }

    void write(VulkanTextOverlayRasterizer.Raster raster) {
        if (buffer == 0L) throw new IllegalStateException("text overlay upload is closed");
        if (raster.pixels().length > CAPACITY_BYTES) {
            throw new IllegalArgumentException("text overlay exceeds its upload capacity");
        }
        mappedBytes.clear();
        mappedBytes.put(raster.pixels());
    }

    @Override
    public void close() {
        if (mappedAddress != 0L) VK10.vkUnmapMemory(device, memory);
        mappedAddress = 0L;
        mappedBytes = null;
        if (buffer != 0L) VK10.vkDestroyBuffer(device, buffer, null);
        buffer = 0L;
        if (memory != 0L) VK10.vkFreeMemory(device, memory, null);
        memory = 0L;
    }

    private static int selectHostCoherentMemoryType(
            MemoryStack stack,
            VkPhysicalDevice physicalDevice,
            int compatibleTypeBits
    ) {
        VkPhysicalDeviceMemoryProperties properties = VkPhysicalDeviceMemoryProperties.malloc(stack);
        VK10.vkGetPhysicalDeviceMemoryProperties(physicalDevice, properties);
        int required = VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
        for (int index = 0; index < properties.memoryTypeCount(); index++) {
            boolean compatible = (compatibleTypeBits & (1 << index)) != 0;
            int flags = properties.memoryTypes(index).propertyFlags();
            if (compatible && (flags & required) == required) return index;
        }
        throw new UnsupportedOperationException(
                "Vulkan device exposes no host-visible coherent memory for the presenter HUD"
        );
    }

    private static void checkVk(int result, String operation) {
        if (result != VK10.VK_SUCCESS) {
            throw new IllegalStateException(operation + " failed with VkResult " + result);
        }
    }
}
