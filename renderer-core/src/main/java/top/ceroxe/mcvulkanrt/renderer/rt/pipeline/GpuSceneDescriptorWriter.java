package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkWriteDescriptorSetAccelerationStructureKHR;

import java.util.Objects;

/** Performs one complete, validated write of the immutable GPUScene descriptor ABI. */
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
        VkDescriptorImageInfo.Buffer image = VkDescriptorImageInfo.calloc(1, stack);
        image.get(0)
                .sampler(VK10.VK_NULL_HANDLE)
                .imageView(checked.outputImageView())
                .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);

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
                .pImageInfo(image);
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
        VK10.vkUpdateDescriptorSets(device, writes, null);
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
