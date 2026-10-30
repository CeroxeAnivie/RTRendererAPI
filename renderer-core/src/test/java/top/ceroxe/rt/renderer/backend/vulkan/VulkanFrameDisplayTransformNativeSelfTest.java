package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.rt.device.RtGpuImage;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Real GPU oracle: all finite positive half values, one-pixel edges, slot reuse and resize. */
public final class VulkanFrameDisplayTransformNativeSelfTest {
    public static void main(String[] arguments) {
        var capability = VulkanRtCapabilityProbe.capture();
        try (var runtime = VulkanDeviceRuntime.open(capability, RendererRtDiagnostics.noop(), true, true)) {
            for (boolean ownAllocator : new boolean[]{false, true}) {
                try (var transform = VulkanFrameDisplayTransform.open(
                        runtime.instance(), runtime.physicalDevice(), runtime.device(),
                        ownAllocator ? 0L : runtime.allocator(), 3)) {
                    check(runtime, transform, 0, 257, 129);
                    check(runtime, transform, 0, 257, 129);
                    check(runtime, transform, 1, 1, 1);
                    check(runtime, transform, 2, 17, 9);
                    check(runtime, transform, 0, 19, 13);
                    check(runtime, transform, 0, 257, 129);
                }
            }
        }
        System.out.println("VulkanFrameDisplayTransformNativeSelfTest passed: 12 GPU cases, "
                + "all 31744 nonnegative finite half values, exact one-pixel edges, source unchanged");
    }

    private static void check(
            VulkanDeviceRuntime runtime, VulkanFrameDisplayTransform transform, int slot, int width, int height
    ) {
        int count = Math.multiplyExact(width, height);
        byte[] input = new byte[count * 8];
        ByteBuffer values = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN);
        for (int pixel = 0; pixel < count; pixel++) {
            values.putShort((short) (pixel % 31744));
            values.putShort((short) (31743 - pixel % 31744));
            values.putShort((short) ((pixel & 1) == 0 ? 0 : 0x3c00));
            values.putShort((short) 0x3c00);
        }
        byte[] reference = VulkanFramePixelCodec.convertLinearHdrRgba16fToSdrRgba8(input);
        try (var source = RtGpuImage.createStorageImage(
                runtime.device(), runtime.allocator(), width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT);
             var upload = RtGpuBuffer.createHostVisibleBuffer(
                     runtime.device(), runtime.allocator(), input.length, VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
             var readback = RtGpuBuffer.createHostVisibleBuffer(
                     runtime.device(), runtime.allocator(), reference.length, VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
             var sourceReadback = RtGpuBuffer.createHostVisibleBuffer(
                     runtime.device(), runtime.allocator(), input.length, VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT)) {
            upload.writeBytes(input);
            runtime.frameCommands().submitOneTime((commands, stack) -> {
                barrier(commands, stack, source.image(), VK10.VK_IMAGE_LAYOUT_UNDEFINED,
                        VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT);
                var region = region(stack, width, height);
                VK10.vkCmdCopyBufferToImage(commands, upload.buffer(), source.image(),
                        VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
                barrier(commands, stack, source.image(), VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        VK10.VK_ACCESS_TRANSFER_WRITE_BIT, VK10.VK_ACCESS_TRANSFER_READ_BIT,
                        VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT);
                long display = transform.record(commands, stack, slot, source.image(), width, height);
                VK10.vkCmdCopyImageToBuffer(commands, display, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        readback.buffer(), region);
                VK10.vkCmdCopyImageToBuffer(commands, source.image(), VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        sourceReadback.buffer(), region);
                VkMemoryBarrier.Buffer completed = VkMemoryBarrier.calloc(1, stack).sType$Default()
                        .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK10.VK_ACCESS_HOST_READ_BIT);
                VK10.vkCmdPipelineBarrier(commands, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK10.VK_PIPELINE_STAGE_HOST_BIT, 0, completed, null, null);
            });
            byte[] actual = readback.readBytes();
            for (int channel = 0; channel < actual.length; channel++) {
                int delta = Math.abs(Byte.toUnsignedInt(actual[channel]) - Byte.toUnsignedInt(reference[channel]));
                // The established CPU oracle uses double precision; the shader uses float32.
                require(delta <= 1, "display transform differs at channel " + channel + ": delta=" + delta);
                if ((channel & 3) >= 2) require(delta == 0, "one-pixel edge or alpha was filtered");
            }
            require(Arrays.equals(input, sourceReadback.readBytes()), "presentation modified linear HDR source");
        }
    }

    private static VkBufferImageCopy.Buffer region(MemoryStack stack, int width, int height) {
        var result = VkBufferImageCopy.calloc(1, stack);
        result.imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
        result.imageExtent().set(width, height, 1);
        return result;
    }

    private static void barrier(
            VkCommandBuffer commands, MemoryStack stack, long image, int oldLayout, int newLayout,
            int sourceAccess, int destinationAccess, int sourceStage, int destinationStage
    ) {
        var images = VkImageMemoryBarrier.calloc(1, stack).sType$Default().image(image)
                .oldLayout(oldLayout).newLayout(newLayout).srcAccessMask(sourceAccess).dstAccessMask(destinationAccess)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED);
        images.subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        VK10.vkCmdPipelineBarrier(commands, sourceStage, destinationStage, 0, null, null, images);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
