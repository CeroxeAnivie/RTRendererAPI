package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import top.ceroxe.rt.renderer.rt.device.RtGpuImage;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.pipeline.GpuSceneDescriptorResources;

import java.util.Objects;

/**
 * Session-owned, format-correct descriptor sentinels for inactive optional image features.
 *
 * <p>Vulkan requires every descriptor declared by a pipeline layout to be initialized even when
 * the runtime feature branch will not touch it. These three 1x1 images deliberately do not form
 * part of either the nine-image NRD contract or the reconstruction contract. They
 * prevent type-incompatible aliasing with the published output while supplying the inactive side
 * when either NRD or reconstruction owns the other descriptor family.</p>
 */
final class VulkanDenoisingDescriptorPlaceholders implements AutoCloseable {
    private RtGpuImage rgba16f;
    private RtGpuImage r32f;
    private RtGpuImage rg16f;
    private boolean closed;

    private VulkanDenoisingDescriptorPlaceholders(
            RtGpuImage rgba16f,
            RtGpuImage r32f,
            RtGpuImage rg16f
    ) {
        this.rgba16f = Objects.requireNonNull(rgba16f, "rgba16f");
        this.r32f = Objects.requireNonNull(r32f, "r32f");
        this.rg16f = Objects.requireNonNull(rg16f, "rg16f");
    }

    static VulkanDenoisingDescriptorPlaceholders create(VulkanDeviceRuntime device) {
        VulkanDeviceRuntime checked = Objects.requireNonNull(device, "device");
        VulkanDenoisingImageSupport.requireSupported(checked.physicalDevice());
        RtGpuImage rgba16f = null;
        RtGpuImage r32f = null;
        RtGpuImage rg16f = null;
        try {
            rgba16f = create(checked, VulkanDenoisingImageSupport.NORMAL_ROUGHNESS_FORMAT);
            r32f = create(checked, VulkanDenoisingImageSupport.VIEW_Z_FORMAT);
            rg16f = create(checked, VK10.VK_FORMAT_R16G16_SFLOAT);
            initializeLayouts(checked, rgba16f, r32f, rg16f);
            return new VulkanDenoisingDescriptorPlaceholders(rgba16f, r32f, rg16f);
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            closeSuppressing(rg16f, failure);
            closeSuppressing(r32f, failure);
            closeSuppressing(rgba16f, failure);
            throw failure;
        }
    }

    GpuSceneDescriptorResources.DenoisingImageViews views() {
        requireOpen();
        return new GpuSceneDescriptorResources.DenoisingImageViews(
                rgba16f.imageView(),
                r32f.imageView(),
                rgba16f.imageView(),
                rgba16f.imageView(),
                rgba16f.imageView(),
                rgba16f.imageView(),
                rgba16f.imageView(),
                rgba16f.imageView(),
                rgba16f.imageView()
        );
    }

    GpuSceneDescriptorResources.ReconstructionImageViews reconstructionViews() {
        requireOpen();
        return new GpuSceneDescriptorResources.ReconstructionImageViews(
                r32f.imageView(), rg16f.imageView(), r32f.imageView()
        );
    }

    private static RtGpuImage create(VulkanDeviceRuntime device, int format) {
        return RtGpuImage.createStorageImage(device.device(), device.allocator(), 1, 1, format);
    }

    private static void initializeLayouts(VulkanDeviceRuntime device, RtGpuImage... images) {
        device.frameCommands().submitOneTime((commandBuffer, stack) ->
                recordInitialLayoutTransitions(commandBuffer, stack, images));
    }

    private static void recordInitialLayoutTransitions(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            RtGpuImage[] images
    ) {
        VkImageMemoryBarrier.Buffer barriers = VkImageMemoryBarrier.calloc(images.length, stack);
        for (int index = 0; index < images.length; index++) {
            VkImageMemoryBarrier barrier = barriers.get(index)
                    .sType$Default()
                    .srcAccessMask(0)
                    .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED)
                    .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .image(images[index].image());
            barrier.subresourceRange()
                    .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1);
        }
        VK10.vkCmdPipelineBarrier(
                commandBuffer,
                VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                0,
                null,
                null,
                barriers
        );
    }

    private static void closeSuppressing(AutoCloseable resource, Throwable failure) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("denoising descriptor placeholders are closed");
    }

    @Override
    public void close() {
        if (closed) return;
        RuntimeException failure = null;
        failure = close(failure, rg16f);
        rg16f = null;
        failure = close(failure, r32f);
        r32f = null;
        failure = close(failure, rgba16f);
        rgba16f = null;
        closed = true;
        if (failure != null) throw failure;
    }

    private static RuntimeException close(RuntimeException accumulated, RtGpuImage image) {
        if (image == null) return accumulated;
        try {
            image.close();
            return accumulated;
        } catch (RuntimeException closeFailure) {
            if (accumulated == null) return closeFailure;
            accumulated.addSuppressed(closeFailure);
            return accumulated;
        }
    }
}
