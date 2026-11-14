package top.ceroxe.rt.renderer.rt.pipeline;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.rt.device.RtGpuImage;
import top.ceroxe.rt.renderer.rt.material.RtSceneMaterialTable;

import java.util.Objects;

/**
 * Writes the fixed RT descriptor ABI for a frame slot.
 *
 * <p>Descriptor writes are resource publication, not frame scheduling. Keeping them here makes
 * the binding contract auditable and prevents the pipeline from accidentally mixing output-image
 * replacement with TLAS/material generation updates.</p>
 */
final class RtFrameDescriptorWriter {
    static final int TLAS_BINDING = 0;
    static final int OUTPUT_BINDING = 1;
    static final int SECTION_RECORDS_BINDING = 2;
    static final int FACE_RECORDS_BINDING = 3;
    static final int FRAME_UNIFORMS_BINDING = 4;
    static final int TEXTURE_RECORDS_BINDING = 5;
    static final int TEXTURE_PIXELS_BINDING = 6;
    static final int DYNAMIC_SCENE_BINDING = 7;
    static final int DYNAMIC_TLAS_BINDING = 8;
    static final int DIAGNOSTIC_GBUFFER_BINDING = 9;

    private RtFrameDescriptorWriter() {
    }

    static void updateDescriptors(
            MemoryStack stack,
            VkDevice device,
            long descriptorSet,
            long topLevelAccelerationStructure,
            long dynamicTopLevelAccelerationStructure,
            RtGpuImage outputImage,
            RtSceneMaterialTable sceneMaterialTable,
            RtGpuBuffer frameUniformBuffer,
            RtGpuBuffer dynamicSceneBuffer,
            RtGpuBuffer diagnosticGBuffer,
            long frameUniformBytes,
            long dynamicSceneBufferBytes
    ) {
        Objects.requireNonNull(outputImage, "outputImage");
        Objects.requireNonNull(dynamicSceneBuffer, "dynamicSceneBuffer");
        VkWriteDescriptorSetAccelerationStructureKHR accelerationStructureInfo =
                accelerationStructureDescriptorInfo(stack, topLevelAccelerationStructure);
        VkWriteDescriptorSetAccelerationStructureKHR dynamicAccelerationStructureInfo =
                accelerationStructureDescriptorInfo(stack, dynamicTopLevelAccelerationStructure);

        VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack);
        imageInfo.get(0).sampler(VK10.VK_NULL_HANDLE).imageView(outputImage.imageView())
                .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);

        VkDescriptorBufferInfo.Buffer bufferInfos = VkDescriptorBufferInfo.calloc(diagnosticGBuffer == null ? 6 : 7, stack);
        bufferInfos.get(0).buffer(sceneMaterialTable.sectionRecordBuffer()).offset(0L)
                .range(sceneMaterialTable.sectionRecordBufferBytes());
        bufferInfos.get(1).buffer(sceneMaterialTable.faceRecordBuffer()).offset(0L)
                .range(sceneMaterialTable.faceRecordBufferBytes());
        bufferInfos.get(2).buffer(frameUniformBuffer.buffer()).offset(0L).range(frameUniformBytes);
        bufferInfos.get(3).buffer(sceneMaterialTable.textureRecordBuffer()).offset(0L)
                .range(sceneMaterialTable.textureRecordBufferBytes());
        bufferInfos.get(4).buffer(sceneMaterialTable.texturePixelBuffer()).offset(0L)
                .range(sceneMaterialTable.texturePixelBufferBytes());
        bufferInfos.get(5).buffer(dynamicSceneBuffer.buffer()).offset(0L).range(dynamicSceneBufferBytes);
        if (diagnosticGBuffer != null) {
            bufferInfos.get(6).buffer(diagnosticGBuffer.buffer()).offset(0L).range(diagnosticGBuffer.sizeBytes());
        }

        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(diagnosticGBuffer == null ? 9 : 10, stack);
        writeTopLevelAccelerationStructureDescriptor(writes.get(0), descriptorSet, accelerationStructureInfo, TLAS_BINDING);
        writes.get(1).sType$Default().dstSet(descriptorSet).dstBinding(OUTPUT_BINDING).dstArrayElement(0)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).pImageInfo(imageInfo);
        writeStorageBufferDescriptor(writes.get(2), descriptorSet, SECTION_RECORDS_BINDING, bufferInfos.get(0));
        writeStorageBufferDescriptor(writes.get(3), descriptorSet, FACE_RECORDS_BINDING, bufferInfos.get(1));
        writeStorageBufferDescriptor(writes.get(4), descriptorSet, FRAME_UNIFORMS_BINDING, bufferInfos.get(2));
        writeStorageBufferDescriptor(writes.get(5), descriptorSet, TEXTURE_RECORDS_BINDING, bufferInfos.get(3));
        writeStorageBufferDescriptor(writes.get(6), descriptorSet, TEXTURE_PIXELS_BINDING, bufferInfos.get(4));
        writeStorageBufferDescriptor(writes.get(7), descriptorSet, DYNAMIC_SCENE_BINDING, bufferInfos.get(5));
        writeTopLevelAccelerationStructureDescriptor(writes.get(8), descriptorSet, dynamicAccelerationStructureInfo, DYNAMIC_TLAS_BINDING);
        if (diagnosticGBuffer != null) {
            writeStorageBufferDescriptor(writes.get(9), descriptorSet, DIAGNOSTIC_GBUFFER_BINDING, bufferInfos.get(6));
        }
        VK10.vkUpdateDescriptorSets(device, writes, null);
    }

    static void updateOutputImageDescriptor(MemoryStack stack, VkDevice device, long descriptorSet, RtGpuImage outputImage) {
        VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack);
        imageInfo.get(0).sampler(VK10.VK_NULL_HANDLE).imageView(outputImage.imageView())
                .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(1, stack);
        writes.get(0).sType$Default().dstSet(descriptorSet).dstBinding(OUTPUT_BINDING).dstArrayElement(0)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).pImageInfo(imageInfo);
        VK10.vkUpdateDescriptorSets(device, writes, null);
    }

    static void updateOutputImageDescriptors(MemoryStack stack, VkDevice device, long[] descriptorSets, RtGpuImage outputImage) {
        for (long descriptorSet : descriptorSets) {
            updateOutputImageDescriptor(stack, device, descriptorSet, outputImage);
        }
    }

    static void updateSceneDescriptors(
            MemoryStack stack,
            VkDevice device,
            long descriptorSet,
            long topLevelAccelerationStructure,
            long dynamicTopLevelAccelerationStructure,
            RtSceneMaterialTable sceneMaterialTable,
            RtGpuBuffer diagnosticGBuffer
    ) {
        VkWriteDescriptorSetAccelerationStructureKHR accelerationStructureInfo =
                accelerationStructureDescriptorInfo(stack, topLevelAccelerationStructure);
        VkWriteDescriptorSetAccelerationStructureKHR dynamicAccelerationStructureInfo =
                accelerationStructureDescriptorInfo(stack, dynamicTopLevelAccelerationStructure);
        VkDescriptorBufferInfo.Buffer bufferInfos = VkDescriptorBufferInfo.calloc(4, stack);
        bufferInfos.get(0).buffer(sceneMaterialTable.sectionRecordBuffer()).offset(0L)
                .range(sceneMaterialTable.sectionRecordBufferBytes());
        bufferInfos.get(1).buffer(sceneMaterialTable.faceRecordBuffer()).offset(0L)
                .range(sceneMaterialTable.faceRecordBufferBytes());
        bufferInfos.get(2).buffer(sceneMaterialTable.textureRecordBuffer()).offset(0L)
                .range(sceneMaterialTable.textureRecordBufferBytes());
        bufferInfos.get(3).buffer(sceneMaterialTable.texturePixelBuffer()).offset(0L)
                .range(sceneMaterialTable.texturePixelBufferBytes());

        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(diagnosticGBuffer == null ? 6 : 7, stack);
        writeTopLevelAccelerationStructureDescriptor(writes.get(0), descriptorSet, accelerationStructureInfo, TLAS_BINDING);
        writeStorageBufferDescriptor(writes.get(1), descriptorSet, SECTION_RECORDS_BINDING, bufferInfos.get(0));
        writeStorageBufferDescriptor(writes.get(2), descriptorSet, FACE_RECORDS_BINDING, bufferInfos.get(1));
        writeStorageBufferDescriptor(writes.get(3), descriptorSet, TEXTURE_RECORDS_BINDING, bufferInfos.get(2));
        writeStorageBufferDescriptor(writes.get(4), descriptorSet, TEXTURE_PIXELS_BINDING, bufferInfos.get(3));
        writeTopLevelAccelerationStructureDescriptor(writes.get(5), descriptorSet, dynamicAccelerationStructureInfo, DYNAMIC_TLAS_BINDING);
        if (diagnosticGBuffer != null) {
            VkDescriptorBufferInfo diagnosticInfo = VkDescriptorBufferInfo.calloc(1, stack).get(0)
                    .buffer(diagnosticGBuffer.buffer()).offset(0L).range(diagnosticGBuffer.sizeBytes());
            writeStorageBufferDescriptor(writes.get(6), descriptorSet, DIAGNOSTIC_GBUFFER_BINDING, diagnosticInfo);
        }
        VK10.vkUpdateDescriptorSets(device, writes, null);
    }

    private static VkWriteDescriptorSetAccelerationStructureKHR accelerationStructureDescriptorInfo(
            MemoryStack stack, long topLevelAccelerationStructure
    ) {
        if (topLevelAccelerationStructure == 0L) {
            throw new IllegalArgumentException("topLevelAccelerationStructure must not be null");
        }
        return VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack).sType$Default()
                .accelerationStructureCount(1).pAccelerationStructures(stack.longs(topLevelAccelerationStructure));
    }

    private static void writeTopLevelAccelerationStructureDescriptor(
            VkWriteDescriptorSet write,
            long descriptorSet,
            VkWriteDescriptorSetAccelerationStructureKHR info,
            int binding
    ) {
        write.sType$Default().pNext(info.address()).dstSet(descriptorSet).dstBinding(binding).dstArrayElement(0)
                .descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                .descriptorCount(1);
    }

    private static void writeStorageBufferDescriptor(
            VkWriteDescriptorSet write, long descriptorSet, int binding, VkDescriptorBufferInfo bufferInfo
    ) {
        write.sType$Default().dstSet(descriptorSet).dstBinding(binding).dstArrayElement(0)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1)
                .pBufferInfo(VkDescriptorBufferInfo.create(bufferInfo.address(), 1));
    }
}
