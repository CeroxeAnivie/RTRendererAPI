package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkRayTracingPipelineCreateInfoKHR;
import org.lwjgl.vulkan.VkRayTracingShaderGroupCreateInfoKHR;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

/** Owns the immutable descriptor and shader-group ABI used to create the Vulkan RT pipeline. */
final class RtRayTracingPipelineFactory {
    static final int RAYGEN_GROUPS = 1;
    static final int MISS_GROUPS = 1;
    static final int HIT_GROUPS = 1;
    static final int CALLABLE_GROUPS = 0;
    static final int GROUP_COUNT = RAYGEN_GROUPS + MISS_GROUPS + HIT_GROUPS + CALLABLE_GROUPS;

    private static final int RAYGEN_STAGE = 0;
    private static final int MISS_STAGE = 1;
    private static final int CLOSEST_HIT_STAGE = 2;
    private static final int ANY_HIT_STAGE = 3;
    private static final int TLAS_BINDING = 0;
    private static final int OUTPUT_BINDING = 1;
    private static final int SECTION_RECORDS_BINDING = 2;
    private static final int FACE_RECORDS_BINDING = 3;
    private static final int FRAME_UNIFORMS_BINDING = 4;
    private static final int TEXTURE_RECORDS_BINDING = 5;
    private static final int TEXTURE_PIXELS_BINDING = 6;
    private static final int DYNAMIC_SCENE_BINDING = 7;
    private static final int DYNAMIC_TLAS_BINDING = 8;
    private static final int DIAGNOSTIC_GBUFFER_BINDING = 9;
    private static final int MATERIAL_LOOKUP_STAGE_FLAGS =
            KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR;
    private static final int DYNAMIC_SCENE_STAGE_FLAGS = MATERIAL_LOOKUP_STAGE_FLAGS
            | KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR;

    private RtRayTracingPipelineFactory() {
    }

    static long createDescriptorSetLayout(
            MemoryStack stack,
            VkDevice device,
            boolean diagnosticGBufferEnabled
    ) {
        VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(
                diagnosticGBufferEnabled ? 10 : 9,
                stack
        );
        bindings.get(0).binding(TLAS_BINDING)
                .descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        bindings.get(1).binding(OUTPUT_BINDING)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        bindings.get(2).binding(SECTION_RECORDS_BINDING)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .stageFlags(MATERIAL_LOOKUP_STAGE_FLAGS);
        bindings.get(3).binding(FACE_RECORDS_BINDING)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .stageFlags(MATERIAL_LOOKUP_STAGE_FLAGS);
        bindings.get(4).binding(FRAME_UNIFORMS_BINDING)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .stageFlags(MATERIAL_LOOKUP_STAGE_FLAGS | KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR);
        bindings.get(5).binding(TEXTURE_RECORDS_BINDING)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .stageFlags(MATERIAL_LOOKUP_STAGE_FLAGS);
        bindings.get(6).binding(TEXTURE_PIXELS_BINDING)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .stageFlags(MATERIAL_LOOKUP_STAGE_FLAGS);
        bindings.get(7).binding(DYNAMIC_SCENE_BINDING)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .stageFlags(DYNAMIC_SCENE_STAGE_FLAGS);
        bindings.get(8).binding(DYNAMIC_TLAS_BINDING)
                .descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                .descriptorCount(1)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        if (diagnosticGBufferEnabled) {
            bindings.get(9).binding(DIAGNOSTIC_GBUFFER_BINDING)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        }
        VkDescriptorSetLayoutCreateInfo createInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .pBindings(bindings);
        LongBuffer handle = stack.longs(0L);
        checkVk(VK10.vkCreateDescriptorSetLayout(device, createInfo, null, handle), "vkCreateDescriptorSetLayout");
        return handle.get(0);
    }

    static long createDescriptorPool(
            MemoryStack stack,
            VkDevice device,
            int descriptorSetCount,
            boolean diagnosticGBufferEnabled
    ) {
        if (descriptorSetCount <= 0) {
            throw new IllegalArgumentException("descriptorSetCount must be positive");
        }
        VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(3, stack);
        poolSizes.get(0)
                .type(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                .descriptorCount(Math.multiplyExact(2, descriptorSetCount));
        poolSizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(descriptorSetCount);
        poolSizes.get(2).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount((diagnosticGBufferEnabled ? 7 : 6) * descriptorSetCount);
        VkDescriptorPoolCreateInfo createInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                .sType$Default()
                .maxSets(descriptorSetCount)
                .pPoolSizes(poolSizes);
        LongBuffer handle = stack.longs(0L);
        checkVk(VK10.vkCreateDescriptorPool(device, createInfo, null, handle), "vkCreateDescriptorPool");
        return handle.get(0);
    }

    static long[] allocateDescriptorSets(
            MemoryStack stack,
            VkDevice device,
            long descriptorPool,
            long descriptorSetLayout,
            int descriptorSetCount
    ) {
        if (descriptorSetCount <= 0) {
            throw new IllegalArgumentException("descriptorSetCount must be positive");
        }
        LongBuffer layouts = stack.mallocLong(descriptorSetCount);
        for (int index = 0; index < descriptorSetCount; index++) {
            layouts.put(index, descriptorSetLayout);
        }
        VkDescriptorSetAllocateInfo allocateInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType$Default()
                .descriptorPool(descriptorPool)
                .pSetLayouts(layouts);
        LongBuffer handles = stack.mallocLong(descriptorSetCount);
        checkVk(VK10.vkAllocateDescriptorSets(device, allocateInfo, handles), "vkAllocateDescriptorSets");
        long[] descriptorSets = new long[descriptorSetCount];
        for (int index = 0; index < descriptorSetCount; index++) {
            descriptorSets[index] = handles.get(index);
        }
        return descriptorSets;
    }

    static long createPipelineLayout(MemoryStack stack, VkDevice device, long descriptorSetLayout) {
        VkPipelineLayoutCreateInfo createInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .pSetLayouts(stack.longs(descriptorSetLayout));
        LongBuffer handle = stack.longs(0L);
        checkVk(VK10.vkCreatePipelineLayout(device, createInfo, null, handle), "vkCreatePipelineLayout");
        return handle.get(0);
    }

    static long createPipeline(
            MemoryStack stack,
            VkDevice device,
            long pipelineLayout,
            long raygenModule,
            long missModule,
            long closestHitModule,
            long anyHitModule
    ) {
        ByteBuffer main = stack.UTF8("main");
        VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(4, stack);
        shaderStage(stages.get(RAYGEN_STAGE), KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR,
                raygenModule, main);
        shaderStage(stages.get(MISS_STAGE), KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR,
                missModule, main);
        shaderStage(stages.get(CLOSEST_HIT_STAGE), KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR,
                closestHitModule, main);
        shaderStage(stages.get(ANY_HIT_STAGE), KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR,
                anyHitModule, main);

        VkRayTracingShaderGroupCreateInfoKHR.Buffer groups =
                VkRayTracingShaderGroupCreateInfoKHR.calloc(GROUP_COUNT, stack);
        generalShaderGroup(groups.get(0), RAYGEN_STAGE);
        generalShaderGroup(groups.get(1), MISS_STAGE);
        trianglesHitGroup(groups.get(2), CLOSEST_HIT_STAGE, ANY_HIT_STAGE);
        VkRayTracingPipelineCreateInfoKHR.Buffer createInfo = VkRayTracingPipelineCreateInfoKHR.calloc(1, stack);
        createInfo.get(0).sType$Default()
                .pStages(stages)
                .pGroups(groups)
                .maxPipelineRayRecursionDepth(1)
                .layout(pipelineLayout)
                .basePipelineHandle(VK10.VK_NULL_HANDLE)
                .basePipelineIndex(-1);
        LongBuffer handle = stack.longs(0L);
        checkVk(KHRRayTracingPipeline.vkCreateRayTracingPipelinesKHR(
                device, VK10.VK_NULL_HANDLE, VK10.VK_NULL_HANDLE, createInfo, null, handle
        ), "vkCreateRayTracingPipelinesKHR");
        return handle.get(0);
    }

    static byte[] queryShaderGroupHandles(
            RtRayTracingPipelineProperties properties,
            VkDevice device,
            long pipeline
    ) {
        int handleBytes = Math.multiplyExact(properties.shaderGroupHandleSize(), GROUP_COUNT);
        ByteBuffer handles = MemoryUtil.memAlloc(handleBytes);
        try {
            checkVk(KHRRayTracingPipeline.vkGetRayTracingShaderGroupHandlesKHR(
                    device, pipeline, 0, GROUP_COUNT, handles
            ), "vkGetRayTracingShaderGroupHandlesKHR");
            byte[] copy = new byte[handleBytes];
            handles.get(0, copy);
            return copy;
        } finally {
            MemoryUtil.memFree(handles);
        }
    }

    private static void shaderStage(
            VkPipelineShaderStageCreateInfo stage,
            int stageFlag,
            long shaderModule,
            ByteBuffer entryPoint
    ) {
        stage.sType$Default().stage(stageFlag).module(shaderModule).pName(entryPoint);
    }

    private static void generalShaderGroup(VkRayTracingShaderGroupCreateInfoKHR group, int stageIndex) {
        group.sType$Default()
                .type(KHRRayTracingPipeline.VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
                .generalShader(stageIndex)
                .closestHitShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR)
                .anyHitShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR)
                .intersectionShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR);
    }

    private static void trianglesHitGroup(
            VkRayTracingShaderGroupCreateInfoKHR group,
            int closestHitStageIndex,
            int anyHitStageIndex
    ) {
        group.sType$Default()
                .type(KHRRayTracingPipeline.VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR)
                .generalShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR)
                .closestHitShader(closestHitStageIndex)
                .anyHitShader(anyHitStageIndex)
                .intersectionShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR);
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
            case VK10.VK_ERROR_DEVICE_LOST -> "VK_ERROR_DEVICE_LOST";
            default -> Integer.toString(result);
        };
    }
}
