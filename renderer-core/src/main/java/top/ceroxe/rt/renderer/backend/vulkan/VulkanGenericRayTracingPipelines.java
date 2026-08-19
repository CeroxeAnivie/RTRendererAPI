package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkRayTracingPipelineCreateInfoKHR;
import org.lwjgl.vulkan.VkRayTracingShaderGroupCreateInfoKHR;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import top.ceroxe.rt.renderer.api.BindingSet;
import top.ceroxe.rt.renderer.api.RayTracingPipelineState;
import top.ceroxe.rt.renderer.api.RayTracingShaderGroup;
import top.ceroxe.rt.renderer.api.ShaderModule;
import top.ceroxe.rt.renderer.api.ShaderStage;
import top.ceroxe.rt.renderer.rt.device.RtGpuBuffer;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.device.VulkanFailures;
import top.ceroxe.rt.renderer.rt.pipeline.RtRayTracingPipelineProperties;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compiles project-neutral RT SPIR-V programs and owns their exact pipeline/SBT resources.
 *
 * <p>It intentionally consumes only the generic {@link RayTracingPipelineState}: no retained
 * scene descriptor, material record, camera convention, or presentation policy enters this type.</p>
 */
final class VulkanGenericRayTracingPipelines implements AutoCloseable {
    private final VulkanDeviceRuntime device;
    private final VulkanGenericResourceRegistry resources;
    private final Map<RayTracingPipelineState, Compiled> cache = new IdentityHashMap<>();
    private final RtRayTracingPipelineProperties properties;
    private boolean closed;

    VulkanGenericRayTracingPipelines(VulkanDeviceRuntime device, VulkanGenericResourceRegistry resources) {
        this.device = Objects.requireNonNull(device, "device");
        this.resources = Objects.requireNonNull(resources, "resources");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.properties = RtRayTracingPipelineProperties.query(stack, device.physicalDevice());
        }
    }

    Compiled require(RayTracingPipelineState state) {
        requireOpen();
        try {
            return cache.computeIfAbsent(Objects.requireNonNull(state, "state"), this::compile);
        } catch (UnsupportedOperationException | VulkanGenericPipelineCompilationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new VulkanGenericPipelineCompilationException(
                    "generic ray-tracing pipeline compilation failed for " + state.program().id(), failure
            );
        }
    }

    void updateBindings(
            Compiled pipeline,
            BindingSet bindings,
            VulkanGenericAccelerationStructures.Compilation accelerationStructures
    ) {
        Objects.requireNonNull(pipeline, "pipeline");
        if (pipeline.descriptors() == null) {
            if (!bindings.layout().entries().isEmpty()) {
                throw new IllegalArgumentException("RT pipeline has no descriptor layout for non-empty bindings");
            }
            return;
        }
        pipeline.descriptors().update(bindings, resources, resources.samplers(), accelerationStructures);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        for (Compiled pipeline : cache.values()) {
            try {
                pipeline.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        cache.clear();
        if (failure != null) throw failure;
    }

    private Compiled compile(RayTracingPipelineState state) {
        if (state.maxRecursionDepth() > properties.maxRayRecursionDepth()) {
            throw new UnsupportedOperationException("requested ray recursion depth exceeds Vulkan device limit: "
                    + state.maxRecursionDepth() + " > " + properties.maxRayRecursionDepth());
        }
        List<Long> shaderModules = new ArrayList<>();
        long layout = VK10.VK_NULL_HANDLE;
        long pipeline = VK10.VK_NULL_HANDLE;
        VulkanGenericDescriptorSetBank descriptors = null;
        Sbt sbt = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Map<ShaderModule, Integer> stageIndexes = new IdentityHashMap<>();
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(
                    state.program().modules().size(), stack
            );
            for (int index = 0; index < state.program().modules().size(); index++) {
                ShaderModule module = state.program().modules().get(index);
                VulkanSpirvBindingValidator.requireDeclaredInterface(module);
                long nativeModule = createShaderModule(stack, module);
                shaderModules.add(nativeModule);
                stageIndexes.put(module, index);
                stages.get(index).sType$Default().stage(stageFlag(module.stage())).module(nativeModule)
                        .pName(stack.UTF8(module.entryPoint()));
            }
            if (!state.program().bindingLayout().entries().isEmpty()) {
                descriptors = VulkanGenericDescriptorSetBank.create(
                        stack, device.device(), state.program().bindingLayout(), device.maxBoundDescriptorSets()
                );
            }
            layout = createLayout(stack, state, descriptors);
            VkRayTracingShaderGroupCreateInfoKHR.Buffer groups = VkRayTracingShaderGroupCreateInfoKHR.calloc(
                    state.shaderGroups().size(), stack
            );
            for (int index = 0; index < state.shaderGroups().size(); index++) {
                writeGroup(groups.get(index), state.shaderGroups().get(index), stageIndexes);
            }
            VkRayTracingPipelineCreateInfoKHR.Buffer info = VkRayTracingPipelineCreateInfoKHR.calloc(1, stack);
            info.get(0).sType$Default().pStages(stages).pGroups(groups)
                    .maxPipelineRayRecursionDepth(state.maxRecursionDepth()).layout(layout)
                    .basePipelineHandle(VK10.VK_NULL_HANDLE).basePipelineIndex(-1);
            LongBuffer output = stack.longs(VK10.VK_NULL_HANDLE);
            VulkanFailures.check(KHRRayTracingPipeline.vkCreateRayTracingPipelinesKHR(
                    device.device(), VK10.VK_NULL_HANDLE, VK10.VK_NULL_HANDLE, info, null, output
            ), "vkCreateRayTracingPipelinesKHR.generic");
            pipeline = output.get(0);
            sbt = createSbt(state, pipeline);
            Compiled result = new Compiled(state, device.device(), pipeline, layout, descriptors, sbt,
                    rayStageFlags(state.program().modules()));
            pipeline = VK10.VK_NULL_HANDLE;
            layout = VK10.VK_NULL_HANDLE;
            descriptors = null;
            sbt = null;
            return result;
        } finally {
            for (long shaderModule : shaderModules) VK10.vkDestroyShaderModule(device.device(), shaderModule, null);
            if (pipeline != VK10.VK_NULL_HANDLE) VK10.vkDestroyPipeline(device.device(), pipeline, null);
            if (layout != VK10.VK_NULL_HANDLE) VK10.vkDestroyPipelineLayout(device.device(), layout, null);
            if (descriptors != null) descriptors.close();
            if (sbt != null) sbt.close();
        }
    }

    private Sbt createSbt(RayTracingPipelineState state, long pipeline) {
        List<Integer> raygen = new ArrayList<>();
        List<Integer> miss = new ArrayList<>();
        List<Integer> hit = new ArrayList<>();
        List<Integer> callable = new ArrayList<>();
        for (int index = 0; index < state.shaderGroups().size(); index++) {
            RayTracingShaderGroup group = state.shaderGroups().get(index);
            if (group.kind() != RayTracingShaderGroup.Kind.GENERAL) {
                hit.add(index);
                continue;
            }
            switch (group.general().orElseThrow().stage()) {
                case RAY_GENERATION -> raygen.add(index);
                case RAY_MISS -> miss.add(index);
                case CALLABLE -> callable.add(index);
                default -> throw new IllegalStateException("non-general shader stage in an RT general group");
            }
        }
        if (raygen.size() != 1) throw new IllegalStateException("generic RT pipeline must produce exactly one raygen SBT record");
        int stride = aligned(properties.shaderGroupHandleSize(), properties.shaderGroupHandleAlignment());
        if (stride > properties.maxShaderGroupStride()) {
            throw new UnsupportedOperationException("generic RT SBT stride exceeds device maxShaderGroupStride");
        }
        Region raygenRegion = region(0, raygen.size(), stride);
        Region missRegion = region(raygenRegion.end(), miss.size(), stride);
        Region hitRegion = region(missRegion.end(), hit.size(), stride);
        Region callableRegion = region(hitRegion.end(), callable.size(), stride);
        int totalBytes = aligned(callableRegion.end(), properties.shaderGroupBaseAlignment());
        byte[] handles = queryHandles(pipeline, state.shaderGroups().size());
        byte[] packed = new byte[totalBytes];
        copyHandles(handles, packed, raygen, raygenRegion);
        copyHandles(handles, packed, miss, missRegion);
        copyHandles(handles, packed, hit, hitRegion);
        copyHandles(handles, packed, callable, callableRegion);
        RtGpuBuffer buffer = RtGpuBuffer.createHostVisibleDeviceAddressBuffer(
                device.device(), device.allocator(), Math.addExact(totalBytes, properties.shaderGroupBaseAlignment()),
                KHRRayTracingPipeline.VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR,
                top.ceroxe.rt.renderer.RtStallTelemetrySink.NOOP
        );
        try {
            int baseOffset = alignedOffset(buffer.deviceAddress(), properties.shaderGroupBaseAlignment());
            buffer.writeBytesAt(baseOffset, packed);
            return new Sbt(buffer, baseOffset, raygenRegion, missRegion, hitRegion, callableRegion);
        } catch (RuntimeException failure) {
            buffer.close();
            throw failure;
        }
    }

    private byte[] queryHandles(long pipeline, int groupCount) {
        int bytes = Math.multiplyExact(properties.shaderGroupHandleSize(), groupCount);
        ByteBuffer nativeHandles = MemoryUtil.memAlloc(bytes);
        try {
            VulkanFailures.check(KHRRayTracingPipeline.vkGetRayTracingShaderGroupHandlesKHR(
                    device.device(), pipeline, 0, groupCount, nativeHandles
            ), "vkGetRayTracingShaderGroupHandlesKHR.generic");
            byte[] result = new byte[bytes];
            nativeHandles.get(0, result);
            return result;
        } finally {
            MemoryUtil.memFree(nativeHandles);
        }
    }

    private long createShaderModule(MemoryStack stack, ShaderModule module) {
        VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(module.spirv());
        LongBuffer output = stack.longs(VK10.VK_NULL_HANDLE);
        VulkanFailures.check(VK10.vkCreateShaderModule(device.device(), info, null, output), "vkCreateShaderModule.genericRt");
        return output.get(0);
    }

    private long createLayout(
            MemoryStack stack, RayTracingPipelineState state, VulkanGenericDescriptorSetBank descriptors
    ) {
        VkPipelineLayoutCreateInfo info = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default();
        if (descriptors != null) {
            LongBuffer setLayouts = stack.mallocLong(descriptors.groups().size());
            for (int group : descriptors.groups()) setLayouts.put(descriptors.layout(group));
            setLayouts.flip();
            info.pSetLayouts(setLayouts);
        }
        if (state.program().pushConstantByteSize() > 0) {
            VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack);
            range.get(0).stageFlags(rayStageFlags(state.program().modules())).offset(0)
                    .size(state.program().pushConstantByteSize());
            info.pPushConstantRanges(range);
        }
        LongBuffer output = stack.longs(VK10.VK_NULL_HANDLE);
        VulkanFailures.check(VK10.vkCreatePipelineLayout(device.device(), info, null, output),
                "vkCreatePipelineLayout.genericRt");
        return output.get(0);
    }

    private static void writeGroup(
            VkRayTracingShaderGroupCreateInfoKHR target,
            RayTracingShaderGroup group,
            Map<ShaderModule, Integer> stageIndexes
    ) {
        target.sType$Default().generalShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR)
                .closestHitShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR)
                .anyHitShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR)
                .intersectionShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR);
        switch (group.kind()) {
            case GENERAL -> target.type(KHRRayTracingPipeline.VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
                    .generalShader(index(stageIndexes, group.general().orElseThrow()));
            case TRIANGLES_HIT -> target.type(KHRRayTracingPipeline.VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR)
                    .closestHitShader(group.closestHit().map(module -> index(stageIndexes, module))
                            .orElse(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR))
                    .anyHitShader(group.anyHit().map(module -> index(stageIndexes, module))
                            .orElse(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR));
            case PROCEDURAL_HIT -> target.type(KHRRayTracingPipeline.VK_RAY_TRACING_SHADER_GROUP_TYPE_PROCEDURAL_HIT_GROUP_KHR)
                    .closestHitShader(group.closestHit().map(module -> index(stageIndexes, module))
                            .orElse(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR))
                    .anyHitShader(group.anyHit().map(module -> index(stageIndexes, module))
                            .orElse(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR))
                    .intersectionShader(index(stageIndexes, group.intersection().orElseThrow()));
        }
    }

    private static int index(Map<ShaderModule, Integer> values, ShaderModule module) {
        Integer index = values.get(module);
        if (index == null) throw new IllegalArgumentException("RT group module is absent from the pipeline program");
        return index;
    }

    private static int stageFlag(ShaderStage stage) {
        return switch (stage) {
            case RAY_GENERATION -> KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR;
            case RAY_MISS -> KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR;
            case RAY_CLOSEST_HIT -> KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR;
            case RAY_ANY_HIT -> KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR;
            case RAY_INTERSECTION -> KHRRayTracingPipeline.VK_SHADER_STAGE_INTERSECTION_BIT_KHR;
            case CALLABLE -> KHRRayTracingPipeline.VK_SHADER_STAGE_CALLABLE_BIT_KHR;
            default -> throw new IllegalArgumentException("non-RT shader stage in generic RT pipeline: " + stage);
        };
    }

    private static int rayStageFlags(List<ShaderModule> modules) {
        int result = 0;
        for (ShaderModule module : modules) result |= stageFlag(module.stage());
        return result;
    }

    private Region region(int previousEnd, int groups, int stride) {
        return new Region(aligned(previousEnd, properties.shaderGroupBaseAlignment()),
                Math.multiplyExact(groups, stride), stride);
    }

    private void copyHandles(byte[] all, byte[] target, List<Integer> groups, Region region) {
        for (int local = 0; local < groups.size(); local++) {
            int source = Math.multiplyExact(groups.get(local), properties.shaderGroupHandleSize());
            int destination = Math.addExact(region.offset(), Math.multiplyExact(local, region.stride()));
            System.arraycopy(all, source, target, destination, properties.shaderGroupHandleSize());
        }
    }

    private static int aligned(int value, int alignment) {
        if (value < 0 || alignment <= 0) throw new IllegalArgumentException("invalid SBT alignment input");
        int remainder = value % alignment;
        return remainder == 0 ? value : Math.addExact(value, alignment - remainder);
    }

    private static int alignedOffset(long address, int alignment) {
        long remainder = address % alignment;
        return Math.toIntExact(remainder == 0L ? 0L : alignment - remainder);
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("generic RT pipeline owner is closed");
    }

    record Compiled(
            RayTracingPipelineState state,
            VkDevice device,
            long pipeline,
            long layout,
            VulkanGenericDescriptorSetBank descriptors,
            Sbt sbt,
            int shaderStageFlags
    ) implements AutoCloseable {
        Compiled {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(device, "device");
            Objects.requireNonNull(sbt, "sbt");
            if (pipeline == VK10.VK_NULL_HANDLE || layout == VK10.VK_NULL_HANDLE || shaderStageFlags == 0) {
                throw new IllegalArgumentException("generic RT pipeline handles must be non-null");
            }
        }

        @Override
        public void close() {
            RuntimeException failure = null;
            if (descriptors != null) {
                try {
                    descriptors.close();
                } catch (RuntimeException closeFailure) {
                    failure = closeFailure;
                }
            }
            try {
                sbt.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            } finally {
                /*
                 * These handles are created by compile() and transferred into this record.  They
                 * are not children of the descriptor bank or SBT and therefore need explicit
                 * destruction before the shared device owner can close.
                 */
                VK10.vkDestroyPipeline(device, pipeline, null);
                VK10.vkDestroyPipelineLayout(device, layout, null);
            }
            if (failure != null) throw failure;
        }
    }

    record Region(int offset, int size, int stride) {
        int end() { return Math.addExact(offset, size); }
    }

    record Sbt(RtGpuBuffer buffer, int baseOffset, Region raygen, Region miss, Region hit, Region callable)
            implements AutoCloseable {
        @Override public void close() { buffer.close(); }
    }
}
