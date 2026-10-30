package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.*;
import top.ceroxe.rt.renderer.rt.device.RtGpuImage;
import top.ceroxe.rt.renderer.rt.device.VulkanFailures;
import top.ceroxe.rt.renderer.rt.pipeline.VulkanFramePublicationPipeline;

/**
 * Bounded HDR-to-SDR staging for the official presenter. The public lease only promises
 * TRANSFER_SRC usage, so conversion never creates a storage view of a borrowed image.
 */
final class VulkanFrameDisplayTransform implements AutoCloseable {
    private final VkDevice device;
    private final long allocator;
    private final boolean ownsAllocator;
    private final VulkanFramePublicationPipeline publication;
    private final RtGpuImage[] hdr;
    private final RtGpuImage[] sdr;
    private boolean closed;

    private VulkanFrameDisplayTransform(
            VkDevice device, long allocator, boolean ownsAllocator,
            VulkanFramePublicationPipeline publication, int slots
    ) {
        this.device = device;
        this.allocator = allocator;
        this.ownsAllocator = ownsAllocator;
        this.publication = publication;
        hdr = new RtGpuImage[slots];
        sdr = new RtGpuImage[slots];
    }

    static VulkanFrameDisplayTransform open(
            VkInstance instance, VkPhysicalDevice physicalDevice, VkDevice device,
            long sharedAllocator, int slots
    ) {
        long allocator = sharedAllocator;
        boolean ownsAllocator = allocator == 0L;
        try {
            if (ownsAllocator) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VmaVulkanFunctions functions = VmaVulkanFunctions.calloc(stack).set(instance, device);
                    VmaAllocatorCreateInfo info = VmaAllocatorCreateInfo.calloc(stack)
                            .instance(instance).physicalDevice(physicalDevice).device(device)
                            .vulkanApiVersion(VK11.VK_API_VERSION_1_1).pVulkanFunctions(functions);
                    PointerBuffer handle = stack.mallocPointer(1);
                    VulkanFailures.check(Vma.vmaCreateAllocator(info, handle), "vmaCreateAllocator.presenterColor");
                    allocator = handle.get(0);
                }
            }
            var pipeline = VulkanFramePublicationPipeline.open(device, slots);
            return new VulkanFrameDisplayTransform(device, allocator, ownsAllocator, pipeline, slots);
        } catch (RuntimeException | Error failure) {
            if (ownsAllocator && allocator != 0L) Vma.vmaDestroyAllocator(allocator);
            throw failure;
        }
    }

    /** Caller has retired this slot and acquired source in TRANSFER_SRC_OPTIMAL. */
    long record(VkCommandBuffer commands, MemoryStack stack, int slot, long source, int width, int height) {
        if (closed) throw new IllegalStateException("display transform is closed");
        ensureExtent(slot, width, height);
        RtGpuImage linear = hdr[slot];
        RtGpuImage display = sdr[slot];
        barrier(commands, stack, linear.image(),
                VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                0, VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
                VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT);
        VkImageCopy.Buffer copy = VkImageCopy.calloc(1, stack);
        copy.srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
        copy.dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
        copy.extent().set(width, height, 1);
        VK10.vkCmdCopyImage(commands, source, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                linear.image(), VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copy);
        barrier(commands, stack, linear.image(),
                VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK10.VK_IMAGE_LAYOUT_GENERAL,
                VK10.VK_ACCESS_TRANSFER_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        barrier(commands, stack, display.image(),
                VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_GENERAL,
                0, VK10.VK_ACCESS_SHADER_WRITE_BIT,
                VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        publication.record(commands, stack, slot, linear, display);
        barrier(commands, stack, display.image(),
                VK10.VK_IMAGE_LAYOUT_GENERAL, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_TRANSFER_READ_BIT,
                VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT);
        return display.image();
    }

    private void ensureExtent(int slot, int width, int height) {
        if (hdr[slot] != null && hdr[slot].width() == width && hdr[slot].height() == height) return;
        RtGpuImage newHdr = RtGpuImage.createStorageImage(
                device, allocator, width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT);
        RtGpuImage newSdr;
        try {
            newSdr = RtGpuImage.createStorageImage(device, allocator, width, height, VK10.VK_FORMAT_R8G8B8A8_UNORM);
        } catch (RuntimeException | Error failure) {
            newHdr.close();
            throw failure;
        }
        if (hdr[slot] != null) hdr[slot].close();
        if (sdr[slot] != null) sdr[slot].close();
        hdr[slot] = newHdr;
        sdr[slot] = newSdr;
    }

    private static void barrier(
            VkCommandBuffer commands, MemoryStack stack, long image, int oldLayout, int newLayout,
            int sourceAccess, int destinationAccess, int sourceStage, int destinationStage
    ) {
        VkImageMemoryBarrier.Buffer barriers = VkImageMemoryBarrier.calloc(1, stack)
                .sType$Default().image(image).oldLayout(oldLayout).newLayout(newLayout)
                .srcAccessMask(sourceAccess).dstAccessMask(destinationAccess)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED);
        barriers.subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        VK10.vkCmdPipelineBarrier(commands, sourceStage, destinationStage, 0, null, null, barriers);
    }

    /** All presenter submissions must have completed before teardown. */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        publication.close();
        for (int slot = 0; slot < hdr.length; slot++) {
            if (hdr[slot] != null) { hdr[slot].close(); hdr[slot] = null; }
            if (sdr[slot] != null) { sdr[slot].close(); sdr[slot] = null; }
        }
        if (ownsAllocator) Vma.vmaDestroyAllocator(allocator);
    }
}
