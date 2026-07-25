package top.ceroxe.rt.renderer.rt.pipeline;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.Objects;

/**
 * Performs one complete, validated write of the immutable GPUScene descriptor ABI.
 */
final class GpuSceneDescriptorWriter {
    private GpuSceneDescriptorWriter() {
    }

    static void update(
            MemoryStack stack,
            VkDevice device,
            long descriptorSet,
            long maxStorageBufferRangeBytes,
            GpuSceneDescriptorResources resources
    ) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(device, "device");
        GpuSceneDescriptorResources checked = Objects.requireNonNull(resources, "resources");
        if (descriptorSet == 0L) throw new IllegalArgumentException("descriptorSet must not be null");
        if (maxStorageBufferRangeBytes <= 0L) {
            throw new IllegalArgumentException("maxStorageBufferRangeBytes must be positive");
        }

        VkWriteDescriptorSetAccelerationStructureKHR acceleration =
                VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                        .sType$Default()
                        .accelerationStructureCount(1)
                        .pAccelerationStructures(stack.longs(checked.topLevelAccelerationStructure()));
        VkDescriptorImageInfo.Buffer images = VkDescriptorImageInfo.calloc(
                GpuSceneShaderBindings.STORAGE_IMAGE_COUNT, stack
        );
        setImageInfo(images.get(0), checked.outputImageView());
        setImageInfo(images.get(1), checked.historyColorInputView());
        setImageInfo(images.get(2), checked.historyColorOutputView());
        setImageInfo(images.get(3), checked.historyGeometryInputView());
        setImageInfo(images.get(4), checked.historyGeometryOutputView());
        setImageInfo(images.get(5), checked.motionOutputView());

        VkDescriptorBufferInfo.Buffer bufferInfos = VkDescriptorBufferInfo.calloc(
                GpuSceneShaderBindings.STORAGE_BUFFER_COUNT, stack
        );
        setBufferInfo(bufferInfos.get(0), checked.frameUniforms(), maxStorageBufferRangeBytes);
        for (int binding = GpuSceneShaderBindings.TEXTURE_RECORDS;
             binding <= GpuSceneShaderBindings.LIGHT_RECORDS;
             binding++) {
            setBufferInfo(
                    bufferInfos.get(binding - GpuSceneShaderBindings.FRAME_UNIFORMS),
                    checked.sceneBuffer(binding),
                    maxStorageBufferRangeBytes
            );
        }

        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(GpuSceneShaderBindings.COUNT, stack);
        writes.get(GpuSceneShaderBindings.TLAS)
                .sType$Default()
                .pNext(acceleration.address())
                .dstSet(descriptorSet)
                .dstBinding(GpuSceneShaderBindings.TLAS)
                .dstArrayElement(0)
                .descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                .descriptorCount(1);
        writes.get(GpuSceneShaderBindings.OUTPUT_IMAGE)
                .sType$Default()
                .dstSet(descriptorSet)
                .dstBinding(GpuSceneShaderBindings.OUTPUT_IMAGE)
                .dstArrayElement(0)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1)
                .pImageInfo(VkDescriptorImageInfo.create(images.get(0).address(), 1));
        for (int binding = GpuSceneShaderBindings.FRAME_UNIFORMS;
             binding <= GpuSceneShaderBindings.LIGHT_RECORDS;
             binding++) {
            VkDescriptorBufferInfo info = bufferInfos.get(binding - GpuSceneShaderBindings.FRAME_UNIFORMS);
            writes.get(binding)
                    .sType$Default()
                    .dstSet(descriptorSet)
                    .dstBinding(binding)
                    .dstArrayElement(0)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .pBufferInfo(VkDescriptorBufferInfo.create(info.address(), 1));
        }
        setImageWrite(writes.get(GpuSceneShaderBindings.HISTORY_COLOR_INPUT), descriptorSet,
                GpuSceneShaderBindings.HISTORY_COLOR_INPUT, images.get(1));
        setImageWrite(writes.get(GpuSceneShaderBindings.HISTORY_COLOR_OUTPUT), descriptorSet,
                GpuSceneShaderBindings.HISTORY_COLOR_OUTPUT, images.get(2));
        setImageWrite(writes.get(GpuSceneShaderBindings.HISTORY_GEOMETRY_INPUT), descriptorSet,
                GpuSceneShaderBindings.HISTORY_GEOMETRY_INPUT, images.get(3));
        setImageWrite(writes.get(GpuSceneShaderBindings.HISTORY_GEOMETRY_OUTPUT), descriptorSet,
                GpuSceneShaderBindings.HISTORY_GEOMETRY_OUTPUT, images.get(4));
        setImageWrite(writes.get(GpuSceneShaderBindings.MOTION_OUTPUT), descriptorSet,
                GpuSceneShaderBindings.MOTION_OUTPUT, images.get(5));
        VK10.vkUpdateDescriptorSets(device, writes, null);
    }

    private static void setImageInfo(VkDescriptorImageInfo info, long imageView) {
        info.sampler(VK10.VK_NULL_HANDLE)
                .imageView(imageView)
                .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
    }

    private static void setImageWrite(
            VkWriteDescriptorSet write,
            long descriptorSet,
            int binding,
            VkDescriptorImageInfo image
    ) {
        write.sType$Default()
                .dstSet(descriptorSet)
                .dstBinding(binding)
                .dstArrayElement(0)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1)
                .pImageInfo(VkDescriptorImageInfo.create(image.address(), 1));
    }

    private static void setBufferInfo(
            VkDescriptorBufferInfo info,
            GpuSceneDescriptorResources.BufferRange range,
            long maxStorageBufferRangeBytes
    ) {
        if (range.rangeBytes() > maxStorageBufferRangeBytes) {
            throw new IllegalArgumentException(
                    "descriptor range " + range.rangeBytes()
                            + " exceeds device maxStorageBufferRange " + maxStorageBufferRangeBytes
            );
        }
        info.buffer(range.buffer()).offset(range.offsetBytes()).range(range.rangeBytes());
    }
}
