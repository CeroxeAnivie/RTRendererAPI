package top.ceroxe.rt.renderer.rt.pipeline;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.LockSupport;

/**
 * Owns the immutable descriptor and shader-group ABI used to create the Vulkan RT pipeline.
 */
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
        return createPipelineLayout(stack, device, descriptorSetLayout, 0);
    }

    static long createPipelineLayout(MemoryStack stack, VkDevice device, long descriptorSetLayout, int pushBytes) {
        VkPipelineLayoutCreateInfo createInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .pSetLayouts(stack.longs(descriptorSetLayout));
        if (pushBytes > 0) createInfo.pPushConstantRanges(VkPushConstantRange.calloc(1, stack)
                .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                        | KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR).offset(0).size(pushBytes));
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
        return createPipeline(
                stack, device, pipelineLayout,
                raygenModule, missModule, closestHitModule, anyHitModule,
                VK10.VK_NULL_HANDLE
        );
    }

    static long createPipeline(
            MemoryStack stack,
            VkDevice device,
            long pipelineLayout,
            long raygenModule,
            long missModule,
            long closestHitModule,
            long anyHitModule,
            long pipelineCache
    ) {
        return createPipeline(stack, device, pipelineLayout, raygenModule, missModule,
                closestHitModule, anyHitModule, pipelineCache, 0L, 0L, 0L, 1);
    }

    static long createPipeline(MemoryStack stack, VkDevice device, long pipelineLayout,
            long raygenModule, long missModule, long closestHitModule, long anyHitModule,
            long pipelineCache, long visibilityMiss, long visibilityClosest, long visibilityAny, int recursionDepth) {
        return createPipeline(stack,device,pipelineLayout,raygenModule,missModule,closestHitModule,anyHitModule,
                pipelineCache,visibilityMiss,visibilityClosest,visibilityAny,recursionDepth,0L);
    }
    static long createPipeline(MemoryStack stack, VkDevice device, long pipelineLayout,
            long raygenModule, long missModule, long closestHitModule, long anyHitModule,
            long pipelineCache, long visibilityMiss, long visibilityClosest, long visibilityAny, int recursionDepth,
            long connectionClosest) {
        boolean visibility = visibilityMiss != VK10.VK_NULL_HANDLE;
        boolean connection=connectionClosest!=VK10.VK_NULL_HANDLE;
        if(connection&&!visibility)throw new IllegalArgumentException("optical connection hit group requires the visibility group at slot 1");
        int groupCount = GROUP_COUNT + (visibility ? 2 : 0)+(connection?1:0);
        ByteBuffer main = stack.UTF8("main");
        VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc((visibility ? 7 : 4)+(connection?1:0), stack);
        shaderStage(stages.get(RAYGEN_STAGE), KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR,
                raygenModule, main);
        shaderStage(stages.get(MISS_STAGE), KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR,
                missModule, main);
        shaderStage(stages.get(CLOSEST_HIT_STAGE), KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR,
                closestHitModule, main);
        shaderStage(stages.get(ANY_HIT_STAGE), KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR,
                anyHitModule, main);
        if (visibility) {
            shaderStage(stages.get(4), KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR, visibilityMiss, main);
            shaderStage(stages.get(5), KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR, visibilityClosest, main);
            shaderStage(stages.get(6), KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR, visibilityAny, main);
        }
        if(connection)shaderStage(stages.get(7),KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR,connectionClosest,main);

        VkRayTracingShaderGroupCreateInfoKHR.Buffer groups =
                VkRayTracingShaderGroupCreateInfoKHR.calloc(groupCount, stack);
        generalShaderGroup(groups.get(0), RAYGEN_STAGE);
        generalShaderGroup(groups.get(1), MISS_STAGE);
        if (visibility) generalShaderGroup(groups.get(2), 4);
        trianglesHitGroup(groups.get(visibility ? 3 : 2), CLOSEST_HIT_STAGE, ANY_HIT_STAGE);
        if (visibility) trianglesHitGroup(groups.get(4), 5, 6);
        // Packed SBT hit slots: 0=full radiance, 1=visibility, 2=optical connection.
        // Keep the same any-hit program so alpha and repaired boundary acceptance agree.
        if(connection)trianglesHitGroup(groups.get(5),7,ANY_HIT_STAGE);
        VkRayTracingPipelineCreateInfoKHR.Buffer createInfo = VkRayTracingPipelineCreateInfoKHR.calloc(1, stack);
        createInfo.get(0).sType$Default()
                .pStages(stages)
                .pGroups(groups)
                .maxPipelineRayRecursionDepth(recursionDepth)
                .layout(pipelineLayout)
                .basePipelineHandle(VK10.VK_NULL_HANDLE)
                .basePipelineIndex(-1);
        LongBuffer handle = stack.longs(0L);
        LongBuffer operationHandle = stack.longs(0L);
        checkVk(KHRDeferredHostOperations.vkCreateDeferredOperationKHR(device, null, operationHandle),
                "vkCreateDeferredOperationKHR");
        long operation = operationHandle.get(0);
        long started = System.nanoTime();
        try {
            int result = KHRRayTracingPipeline.vkCreateRayTracingPipelinesKHR(
                    device, operation, pipelineCache, createInfo, null, handle);
            if (Boolean.getBoolean("top.ceroxe.rt.pipeline.handleDiagnostics"))
                System.out.printf(java.util.Locale.ROOT,
                        "RT pipeline create result=%d operation=%d handle=0x%x device=0x%x cache=0x%x%n",
                        result, operation, handle.get(0), device.address(), pipelineCache);
            if (result == KHRDeferredHostOperations.VK_OPERATION_DEFERRED_KHR) {
                finishDeferredPipeline(device, operation);
            } else if (result != KHRDeferredHostOperations.VK_OPERATION_NOT_DEFERRED_KHR) {
                checkVk(result, "vkCreateRayTracingPipelinesKHR");
            }
        } catch (RuntimeException | Error failure) {
            if (handle.get(0) != VK10.VK_NULL_HANDLE)
                VK10.vkDestroyPipeline(device, handle.get(0), null);
            throw failure;
        } finally {
            KHRDeferredHostOperations.vkDestroyDeferredOperationKHR(device, operation, null);
            if (Boolean.getBoolean("top.ceroxe.rt.pipeline.compileDiagnostics"))
                System.out.printf(java.util.Locale.ROOT, "RT pipeline creation wallMs=%.3f%n",
                        (System.nanoTime() - started) / 1e6);
        }
        return handle.get(0);
    }

    private static void finishDeferredPipeline(VkDevice device, long operation) {
        long concurrency = Integer.toUnsignedLong(
                KHRDeferredHostOperations.vkGetDeferredOperationMaxConcurrencyKHR(device, operation));
        int workers = (int) Math.max(1L, Math.min(concurrency, Runtime.getRuntime().availableProcessors()));
        // VVL 1.4.350 wraps deferred handles in its completion callback. Concurrent
        // successful joins can pop the registration callback before that handle list
        // is published (DispatchDevice::DeferredOperationJoinKHR). Keep diagnostic
        // instances single-join; production instances retain parallel driver work.
        // https://github.com/KhronosGroup/Vulkan-ValidationLayers/blob/vulkan-sdk-1.4.350.0/layers/chassis/dispatch_object_manual.cpp
        if (device.getPhysicalDevice().getInstance().getCapabilities().VK_EXT_debug_utils) workers = 1;
        if (Boolean.getBoolean("top.ceroxe.rt.pipeline.compileDiagnostics"))
            System.out.println("RT pipeline deferred compile workers=" + workers + ", driverConcurrency=" + concurrency);
        // All create-info, module and result storage remains alive until every join has returned.
        // The caller also retains its pipeline-cache synchronization throughout this method.
        boolean interrupted = false;
        Throwable failure = null;
        try (var executor = Executors.newFixedThreadPool(workers,
                Thread.ofPlatform().daemon(true).name("rt-pipeline-compile-", 0).factory())) {
            var joins = new ArrayList<Future<?>>(workers);
            for (int index = 0; index < workers; index++)
                joins.add(executor.submit(() -> joinDeferredPipeline(device, operation)));
            for (Future<?> join : joins) {
                while (true) {
                    try {
                        join.get();
                        break;
                    } catch (InterruptedException pending) {
                        interrupted = true;
                    } catch (ExecutionException failed) {
                        failure = failed.getCause();
                        break;
                    }
                }
            }
        } finally {
            try {
                // Thread creation/submission can fail. Finish outstanding native work on this
                // caller before the operation, create-info storage or modules can be destroyed.
                while (KHRDeferredHostOperations.vkGetDeferredOperationResultKHR(device, operation) == VK10.VK_NOT_READY) {
                    joinDeferredPipeline(device, operation);
                    LockSupport.parkNanos(100_000L);
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
        int result = KHRDeferredHostOperations.vkGetDeferredOperationResultKHR(device, operation);
        if (Boolean.getBoolean("top.ceroxe.rt.pipeline.handleDiagnostics"))
            System.out.printf(java.util.Locale.ROOT,
                    "RT pipeline deferred result=%d operation=0x%x device=0x%x%n", result, operation, device.address());
        if (failure != null) throw new IllegalStateException("deferred RT pipeline join failed, result=" + result, failure);
        checkVk(result, "vkGetDeferredOperationResultKHR");
    }

    private static void joinDeferredPipeline(VkDevice device, long operation) {
        while (true) {
            int result = KHRDeferredHostOperations.vkDeferredOperationJoinKHR(device, operation);
            if (result == VK10.VK_SUCCESS || result == KHRDeferredHostOperations.VK_THREAD_DONE_KHR) return;
            if (result != KHRDeferredHostOperations.VK_THREAD_IDLE_KHR)
                checkVk(result, "vkDeferredOperationJoinKHR");
            LockSupport.parkNanos(100_000L);
        }
    }

    static byte[] queryShaderGroupHandles(
            RtRayTracingPipelineProperties properties,
            VkDevice device,
            long pipeline
    ) {
        return queryShaderGroupHandles(properties, device, pipeline, GROUP_COUNT);
    }

    static byte[] queryShaderGroupHandles(RtRayTracingPipelineProperties properties,
            VkDevice device, long pipeline, int groupCount) {
        int handleBytes = Math.multiplyExact(properties.shaderGroupHandleSize(), groupCount);
        ByteBuffer handles = MemoryUtil.memAlloc(handleBytes);
        try {
            checkVk(KHRRayTracingPipeline.vkGetRayTracingShaderGroupHandlesKHR(
                    device, pipeline, 0, groupCount, handles
            ), "vkGetRayTracingShaderGroupHandlesKHR");
            byte[] copy = new byte[handleBytes];
            handles.get(0, copy);
            return copy;
        } finally {
            MemoryUtil.nmemFree(MemoryUtil.memAddress0(handles));
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
        top.ceroxe.rt.renderer.rt.device.VulkanFailures.check(result, stage);
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
