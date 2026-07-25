package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.rt.device.RtGpuImage;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

import java.util.Objects;

/**
 * Explicit opt-in readback used only by renderer-core visual acceptance gates.
 */
final class VulkanFrameDiagnosticReadback {
    private VulkanFrameDiagnosticReadback() {
    }

    static byte[] capture(VulkanDeviceRuntime device, RtGpuImage image) {
        VulkanDeviceRuntime checkedDevice = Objects.requireNonNull(device, "device");
        RtGpuImage checkedImage = Objects.requireNonNull(image, "image");
        VulkanFrameOutput output = outputForVkFormat(checkedImage.format());
        long bytes = output.byteCount(checkedImage.width(), checkedImage.height());
        try (RtGpuBuffer readback = RtGpuBuffer.createHostVisibleBuffer(
                checkedDevice.device(),
                checkedDevice.allocator(),
                bytes,
                VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                checkedDevice.frameCommands().stallTelemetry()
        )) {
            checkedDevice.frameCommands().submitOneTime(
                    (commandBuffer, stack) -> record(
                            commandBuffer,
                            stack,
                            checkedImage,
                            readback,
                            checkedDevice.queueFamilyIndex()
                    )
            );
            byte[] nativeBytes = readback.readBytes(bytes);
            return output.linearHdr() ? convertLinearHdrRgba16fToSdrRgba8(nativeBytes) : nativeBytes;
        }
    }

    static byte[] convertLinearHdrRgba16fToSdrRgba8(byte[] rgba16f) {
        byte[] checked = Objects.requireNonNull(rgba16f, "rgba16f");
        if ((checked.length & 7) != 0) {
            throw new IllegalArgumentException("RGBA16F payload must contain complete eight-byte pixels");
        }
        byte[] rgba8 = new byte[checked.length / 2];
        for (int source = 0, destination = 0; source < checked.length; source += 8, destination += 4) {
            rgba8[destination] = encodeToneMapped(halfToFloat(readHalf(checked, source)));
            rgba8[destination + 1] = encodeToneMapped(halfToFloat(readHalf(checked, source + 2)));
            rgba8[destination + 2] = encodeToneMapped(halfToFloat(readHalf(checked, source + 4)));
            rgba8[destination + 3] = encodeUnit(halfToFloat(readHalf(checked, source + 6)));
        }
        return rgba8;
    }

    private static short readHalf(byte[] bytes, int offset) {
        return (short) ((bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8));
    }

    private static float halfToFloat(short half) {
        int bits = half & 0xffff;
        int sign = (bits & 0x8000) << 16;
        int exponent = (bits >>> 10) & 0x1f;
        int mantissa = bits & 0x3ff;
        if (exponent == 0) {
            if (mantissa == 0) return Float.intBitsToFloat(sign);
            int normalizedExponent = -14;
            while ((mantissa & 0x400) == 0) {
                mantissa <<= 1;
                normalizedExponent--;
            }
            mantissa &= 0x3ff;
            return Float.intBitsToFloat(sign | ((normalizedExponent + 127) << 23) | (mantissa << 13));
        }
        if (exponent == 0x1f) {
            return Float.intBitsToFloat(sign | 0x7f800000 | (mantissa << 13));
        }
        return Float.intBitsToFloat(sign | ((exponent - 15 + 127) << 23) | (mantissa << 13));
    }

    private static byte encodeToneMapped(float value) {
        if (Float.isNaN(value) || value <= 0.0F) return 0;
        if (value == Float.POSITIVE_INFINITY) return (byte) 0xff;
        double numerator = value * (2.51 * value + 0.03);
        double denominator = value * (2.43 * value + 0.59) + 0.14;
        double mapped = Math.max(0.0, Math.min(1.0, numerator / denominator));
        return encodeUnit(Math.pow(mapped, 1.0 / 2.2));
    }

    private static byte encodeUnit(double value) {
        if (!Double.isFinite(value)) return value > 0.0 ? (byte) 0xff : 0;
        return (byte) Math.round(Math.max(0.0, Math.min(1.0, value)) * 255.0);
    }

    private static VulkanFrameOutput outputForVkFormat(int format) {
        if (format == VK10.VK_FORMAT_R8G8B8A8_UNORM) {
            return VulkanFrameOutput.from(top.ceroxe.rt.renderer.api.FrameOutputFormat.SDR_RGBA8);
        }
        if (format == VK10.VK_FORMAT_R16G16B16A16_SFLOAT) {
            return VulkanFrameOutput.from(top.ceroxe.rt.renderer.api.FrameOutputFormat.LINEAR_HDR_RGBA16F);
        }
        throw new IllegalArgumentException("unsupported frame readback format: " + format);
    }

    private static void record(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            RtGpuImage image,
            RtGpuBuffer readback,
            int producerQueueFamilyIndex
    ) {
        imageBarrier(
                commandBuffer, stack, image.image(),
                VK10.VK_IMAGE_LAYOUT_GENERAL, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                0, VK10.VK_ACCESS_TRANSFER_READ_BIT,
                VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK11.VK_QUEUE_FAMILY_EXTERNAL,
                producerQueueFamilyIndex
        );
        VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack);
        copy.get(0).bufferOffset(0L).bufferRowLength(0).bufferImageHeight(0);
        copy.get(0).imageSubresource()
                .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0)
                .baseArrayLayer(0)
                .layerCount(1);
        copy.get(0).imageOffset().set(0, 0, 0);
        copy.get(0).imageExtent().set(image.width(), image.height(), 1);
        VK10.vkCmdCopyImageToBuffer(
                commandBuffer,
                image.image(),
                VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                readback.buffer(),
                copy
        );
        imageBarrier(
                commandBuffer, stack, image.image(),
                VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK10.VK_IMAGE_LAYOUT_GENERAL,
                VK10.VK_ACCESS_TRANSFER_READ_BIT, 0,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                producerQueueFamilyIndex,
                VK11.VK_QUEUE_FAMILY_EXTERNAL
        );
        VkMemoryBarrier.Buffer hostRead = VkMemoryBarrier.calloc(1, stack);
        hostRead.get(0).sType$Default()
                .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_HOST_READ_BIT);
        VK10.vkCmdPipelineBarrier(
                commandBuffer,
                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK10.VK_PIPELINE_STAGE_HOST_BIT,
                0,
                hostRead,
                null,
                null
        );
    }

    private static void imageBarrier(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            long image,
            int oldLayout,
            int newLayout,
            int sourceAccess,
            int destinationAccess,
            int sourceStage,
            int destinationStage,
            int sourceQueueFamilyIndex,
            int destinationQueueFamilyIndex
    ) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
        barrier.get(0)
                .sType$Default()
                .srcAccessMask(sourceAccess)
                .dstAccessMask(destinationAccess)
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(sourceQueueFamilyIndex)
                .dstQueueFamilyIndex(destinationQueueFamilyIndex)
                .image(image);
        barrier.get(0).subresourceRange()
                .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1);
        VK10.vkCmdPipelineBarrier(
                commandBuffer, sourceStage, destinationStage, 0, null, null, barrier
        );
    }
}
