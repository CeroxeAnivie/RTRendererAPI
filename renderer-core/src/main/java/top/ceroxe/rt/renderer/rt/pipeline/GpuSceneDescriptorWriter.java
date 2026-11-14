package top.ceroxe.rt.renderer.rt.pipeline;

import top.ceroxe.rt.renderer.api.SceneShaderBindings;

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
                SceneShaderBindings.STORAGE_IMAGE_COUNT, stack
        );
        setImageInfo(images.get(0), checked.outputImageView());
        setImageInfo(images.get(1), checked.historyColorInputView());
        setImageInfo(images.get(2), checked.historyColorOutputView());
        setImageInfo(images.get(3), checked.historyGeometryInputView());
        setImageInfo(images.get(4), checked.historyGeometryOutputView());
        setImageInfo(images.get(5), checked.motionOutputView());
        GpuSceneDescriptorResources.DenoisingImageViews denoising = checked.denoisingImages();
        setImageInfo(images.get(6), denoising.normalRoughness());
        setImageInfo(images.get(7), denoising.viewZ());
        setImageInfo(images.get(8), denoising.motionVectors());
        setImageInfo(images.get(9), denoising.diffuseRadianceHitDistance());
        setImageInfo(images.get(10), denoising.specularRadianceHitDistance());
        setImageInfo(images.get(11), denoising.diffuseMaterialFactor());
        setImageInfo(images.get(12), denoising.specularMaterialFactor());
        GpuSceneDescriptorResources.ReconstructionImageViews reconstruction = checked.reconstructionImages();
        setImageInfo(images.get(13), reconstruction.depth());
        setImageInfo(images.get(14), reconstruction.motionVectors());
        setImageInfo(images.get(15), reconstruction.exposure());

        VkDescriptorBufferInfo.Buffer bufferInfos = VkDescriptorBufferInfo.calloc(
                SceneShaderBindings.STORAGE_BUFFER_COUNT, stack
        );
        setBufferInfo(bufferInfos.get(0), checked.frameUniforms(), maxStorageBufferRangeBytes);
        for (int binding = SceneShaderBindings.TEXTURE_RECORDS;
             binding <= SceneShaderBindings.LIGHT_RECORDS;
             binding++) {
            setBufferInfo(
                    bufferInfos.get(binding - SceneShaderBindings.FRAME_UNIFORMS),
                    checked.sceneBuffer(binding),
                    maxStorageBufferRangeBytes
            );
        }
        setBufferInfo(
                bufferInfos.get(SceneShaderBindings.STORAGE_BUFFER_COUNT - 1),
                checked.transientInstanceRecords(),
                maxStorageBufferRangeBytes
        );

        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(SceneShaderBindings.COUNT, stack);
        writes.get(SceneShaderBindings.TLAS)
                .sType$Default()
                .pNext(acceleration.address())
                .dstSet(descriptorSet)
                .dstBinding(SceneShaderBindings.TLAS)
                .dstArrayElement(0)
                .descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                .descriptorCount(1);
        writes.get(SceneShaderBindings.OUTPUT_IMAGE)
                .sType$Default()
                .dstSet(descriptorSet)
                .dstBinding(SceneShaderBindings.OUTPUT_IMAGE)
                .dstArrayElement(0)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1)
                .pImageInfo(VkDescriptorImageInfo.create(images.get(0).address(), 1));
        for (int binding = SceneShaderBindings.FRAME_UNIFORMS;
             binding <= SceneShaderBindings.LIGHT_RECORDS;
             binding++) {
            VkDescriptorBufferInfo info = bufferInfos.get(binding - SceneShaderBindings.FRAME_UNIFORMS);
            writes.get(binding)
                    .sType$Default()
                    .dstSet(descriptorSet)
                    .dstBinding(binding)
                    .dstArrayElement(0)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .pBufferInfo(VkDescriptorBufferInfo.create(info.address(), 1));
        }
        VkDescriptorBufferInfo transientInstances = bufferInfos.get(
                SceneShaderBindings.STORAGE_BUFFER_COUNT - 1
        );
        writes.get(SceneShaderBindings.TRANSIENT_INSTANCE_RECORDS)
                .sType$Default()
                .dstSet(descriptorSet)
                .dstBinding(SceneShaderBindings.TRANSIENT_INSTANCE_RECORDS)
                .dstArrayElement(0)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .pBufferInfo(VkDescriptorBufferInfo.create(transientInstances.address(), 1));
        setImageWrite(writes.get(SceneShaderBindings.HISTORY_COLOR_INPUT), descriptorSet,
                SceneShaderBindings.HISTORY_COLOR_INPUT, images.get(1));
        setImageWrite(writes.get(SceneShaderBindings.HISTORY_COLOR_OUTPUT), descriptorSet,
                SceneShaderBindings.HISTORY_COLOR_OUTPUT, images.get(2));
        setImageWrite(writes.get(SceneShaderBindings.HISTORY_GEOMETRY_INPUT), descriptorSet,
                SceneShaderBindings.HISTORY_GEOMETRY_INPUT, images.get(3));
        setImageWrite(writes.get(SceneShaderBindings.HISTORY_GEOMETRY_OUTPUT), descriptorSet,
                SceneShaderBindings.HISTORY_GEOMETRY_OUTPUT, images.get(4));
        setImageWrite(writes.get(SceneShaderBindings.MOTION_OUTPUT), descriptorSet,
                SceneShaderBindings.MOTION_OUTPUT, images.get(5));
        setImageWrite(writes.get(SceneShaderBindings.DENOISING_NORMAL_ROUGHNESS), descriptorSet,
                SceneShaderBindings.DENOISING_NORMAL_ROUGHNESS, images.get(6));
        setImageWrite(writes.get(SceneShaderBindings.DENOISING_VIEW_Z), descriptorSet,
                SceneShaderBindings.DENOISING_VIEW_Z, images.get(7));
        setImageWrite(writes.get(SceneShaderBindings.DENOISING_MOTION_VECTORS), descriptorSet,
                SceneShaderBindings.DENOISING_MOTION_VECTORS, images.get(8));
        setImageWrite(
                writes.get(SceneShaderBindings.DENOISING_DIFFUSE_RADIANCE_HIT_DISTANCE),
                descriptorSet,
                SceneShaderBindings.DENOISING_DIFFUSE_RADIANCE_HIT_DISTANCE,
                images.get(9)
        );
        setImageWrite(
                writes.get(SceneShaderBindings.DENOISING_SPECULAR_RADIANCE_HIT_DISTANCE),
                descriptorSet,
                SceneShaderBindings.DENOISING_SPECULAR_RADIANCE_HIT_DISTANCE,
                images.get(10)
        );
        setImageWrite(writes.get(SceneShaderBindings.DENOISING_DIFFUSE_MATERIAL_FACTOR), descriptorSet,
                SceneShaderBindings.DENOISING_DIFFUSE_MATERIAL_FACTOR, images.get(11));
        setImageWrite(writes.get(SceneShaderBindings.DENOISING_SPECULAR_MATERIAL_FACTOR), descriptorSet,
                SceneShaderBindings.DENOISING_SPECULAR_MATERIAL_FACTOR, images.get(12));
        setImageWrite(writes.get(SceneShaderBindings.RECONSTRUCTION_DEPTH), descriptorSet,
                SceneShaderBindings.RECONSTRUCTION_DEPTH, images.get(13));
        setImageWrite(writes.get(SceneShaderBindings.RECONSTRUCTION_MOTION_VECTORS), descriptorSet,
                SceneShaderBindings.RECONSTRUCTION_MOTION_VECTORS, images.get(14));
        setImageWrite(writes.get(SceneShaderBindings.RECONSTRUCTION_EXPOSURE), descriptorSet,
                SceneShaderBindings.RECONSTRUCTION_EXPOSURE, images.get(15));
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
