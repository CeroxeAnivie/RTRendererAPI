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
import java.util.HashMap;
import top.ceroxe.rt.renderer.api.RayTracingPipelineHandle;
import top.ceroxe.rt.renderer.api.RayTracingPipelineStatistics;
import top.ceroxe.rt.renderer.api.CommandExecutionEvidence;
import java.util.UUID;
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
    private final Map<String, Compiled> cache = new LinkedHashMap<>();
    private final Map<String, PendingRetirement> pendingRetirements = new LinkedHashMap<>();
    private final UUID sessionId = UUID.randomUUID();
    private final Map<String, RayTracingPipelineHandle> handles = new HashMap<>();
    private final Map<String, Long> lastUses = new HashMap<>();
    private long nextGeneration;
    private long created;
    private long retired;
    private long failedReleases;
    private long completedSequence = -1;
    private final RtRayTracingPipelineProperties properties;
    private final java.util.function.Consumer<Compiled> release;
    private boolean closed;

    VulkanGenericRayTracingPipelines(VulkanDeviceRuntime device, VulkanGenericResourceRegistry resources) {
        this(device, resources, Compiled::close);
    }

    VulkanGenericRayTracingPipelines(VulkanDeviceRuntime device, VulkanGenericResourceRegistry resources,
                                    java.util.function.Consumer<Compiled> release) {
        this.device = Objects.requireNonNull(device, "device");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.release = Objects.requireNonNull(release, "release");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.properties = RtRayTracingPipelineProperties.query(stack, device.physicalDevice());
        }
    }

    Compiled require(RayTracingPipelineState state) {
        requireOpen();
        RayTracingPipelineState checked = Objects.requireNonNull(state, "state");
        String identity = checked.identityDigest();
        if (pendingRetirements.containsKey(identity)) {
            throw new VulkanGenericPipelineLifecycleException(CommandExecutionEvidence.Reason.PIPELINE_IN_USE,
                    "ray-tracing pipeline is pending retirement: " + identity);
        }
        try {
            Compiled existing = cache.get(identity);
            if (existing != null) return existing;
            long generation = Math.incrementExact(nextGeneration);
            Compiled compiled = compile(checked);
            cache.put(identity, compiled);
            handles.put(identity, new RayTracingPipelineHandle(sessionId, generation, identity));
            nextGeneration = generation;
            created++;
            return compiled;
        } catch (UnsupportedOperationException | VulkanGenericPipelineCompilationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new VulkanGenericPipelineCompilationException(
                    "generic ray-tracing pipeline compilation failed for " + state.program().id(), failure
            );
        }
    }

    RayTracingPipelineHandle handle(Compiled compiled) {
        return Objects.requireNonNull(handles.get(compiled.state().identityDigest()), "resident pipeline handle");
    }

    void validateRetirement(RayTracingPipelineHandle handle) {
        requireOpen();
        if (!handle.equals(handles.get(handle.identityDigest()))) {
            throw new VulkanGenericPipelineLifecycleException(
                    CommandExecutionEvidence.Reason.PIPELINE_GENERATION_MISMATCH,
                    "pipeline generation is foreign, stale, unknown, or already retired: " + handle);
        }
        if (pendingRetirements.containsKey(handle.identityDigest())
                || lastUses.getOrDefault(handle.identityDigest(), -1L) > completedSequence) {
            throw new VulkanGenericPipelineLifecycleException(CommandExecutionEvidence.Reason.PIPELINE_IN_USE,
                    "pipeline generation has an incomplete consumer or retirement: " + handle);
        }
    }

    void noteSubmitted(List<RayTracingPipelineHandle> used, long sequence) {
        for (RayTracingPipelineHandle handle : used) lastUses.put(handle.identityDigest(), sequence);
    }

    void scheduleRetirement(List<RayTracingPipelineHandle> states, long safeAfterSequence) {
        requireOpen();
        for (RayTracingPipelineHandle handle : states) {
            pendingRetirements.put(handle.identityDigest(), new PendingRetirement(handle, safeAfterSequence));
        }
    }

    /** No tombstones: generation tokens never repeat, and unknown tokens have a stable rejection. */
    void retireCompletedThrough(long sequence) {
        requireOpen();
        completedSequence = Math.max(completedSequence, sequence);
        var iterator = pendingRetirements.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().safeAfterSequence() > completedSequence) continue;
            String identity = entry.getKey();
            Compiled compiled = Objects.requireNonNull(cache.get(identity), "pending native pipeline");
            try {
                release.accept(compiled);
            } catch (RuntimeException failure) {
                failedReleases++;
                throw failure;
            }
            cache.remove(identity);
            handles.remove(identity);
            lastUses.remove(identity);
            iterator.remove();
            retired++;
        }
    }

    RayTracingPipelineStatistics statistics() {
        return new RayTracingPipelineStatistics(cache.size(), pendingRetirements.size(),
                (int) lastUses.values().stream().filter(sequence -> sequence > completedSequence).count(),
                cache.values().stream().mapToLong(value -> value.sbt().buffer().sizeBytes()).sum(),
                created, retired, failedReleases, completedSequence);
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
        pendingRetirements.clear();
        handles.clear();
        lastUses.clear();
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
            Map<top.ceroxe.rt.renderer.api.RenderResourceId, Integer> stageIndexes = new HashMap<>();
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(
                    state.program().modules().size(), stack
            );
            for (int index = 0; index < state.program().modules().size(); index++) {
                ShaderModule module = state.program().modules().get(index);
                VulkanSpirvBindingValidator.requireDeclaredInterface(module);
                long nativeModule = createShaderModule(stack, module);
                shaderModules.add(nativeModule);
                stageIndexes.put(module.id(), index);
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
            Map<top.ceroxe.rt.renderer.api.RenderResourceId, Integer> stageIndexes
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

    private static int index(Map<top.ceroxe.rt.renderer.api.RenderResourceId, Integer> values, ShaderModule module) {
        Integer index = values.get(module.id());
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

    static final class Compiled implements AutoCloseable {
        private final RayTracingPipelineState state;
        private final VkDevice device;
        private long pipeline;
        private long layout;
        private final VulkanGenericDescriptorSetBank descriptors;
        private final Sbt sbt;
        private final int shaderStageFlags;

        Compiled(RayTracingPipelineState state, VkDevice device, long pipeline, long layout,
                 VulkanGenericDescriptorSetBank descriptors, Sbt sbt, int shaderStageFlags) {
            this.state = Objects.requireNonNull(state, "state");
            this.device = Objects.requireNonNull(device, "device");
            this.sbt = Objects.requireNonNull(sbt, "sbt");
            this.descriptors = descriptors;
            this.pipeline = pipeline;
            this.layout = layout;
            this.shaderStageFlags = shaderStageFlags;
            if (pipeline == VK10.VK_NULL_HANDLE || layout == VK10.VK_NULL_HANDLE || shaderStageFlags == 0) {
                throw new IllegalArgumentException("generic RT pipeline handles must be non-null");
            }
        }

        RayTracingPipelineState state() { return state; }
        long pipeline() { return pipeline; }
        long layout() { return layout; }
        VulkanGenericDescriptorSetBank descriptors() { return descriptors; }
        Sbt sbt() { return sbt; }
        int shaderStageFlags() { return shaderStageFlags; }

        @Override public void close() {
            // Stop on failure and retain ownership for a subsequent close. Child owners are
            // idempotent; zero native handles only after their destruction actually returns.
            if (descriptors != null) descriptors.close();
            sbt.close();
            if (pipeline != VK10.VK_NULL_HANDLE) {
                VK10.vkDestroyPipeline(device, pipeline, null);
                pipeline = VK10.VK_NULL_HANDLE;
            }
            if (layout != VK10.VK_NULL_HANDLE) {
                VK10.vkDestroyPipelineLayout(device, layout, null);
                layout = VK10.VK_NULL_HANDLE;
            }
        }
    }

    record Region(int offset, int size, int stride) {
        int end() { return Math.addExact(offset, size); }
    }

    record Sbt(RtGpuBuffer buffer, int baseOffset, Region raygen, Region miss, Region hit, Region callable)
            implements AutoCloseable {
        @Override public void close() { buffer.close(); }
    }

    private record PendingRetirement(RayTracingPipelineHandle state, long safeAfterSequence) {
        private PendingRetirement {
            Objects.requireNonNull(state, "state");
            if (safeAfterSequence < 0L) throw new IllegalArgumentException("safeAfterSequence must not be negative");
        }
    }
}
