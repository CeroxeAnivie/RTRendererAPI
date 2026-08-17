package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import top.ceroxe.rt.renderer.api.BindingSet;
import top.ceroxe.rt.renderer.api.ComputePipelineState;
import top.ceroxe.rt.renderer.api.ShaderModule;
import top.ceroxe.rt.renderer.api.ShaderStage;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;
import top.ceroxe.rt.renderer.rt.device.VulkanFailures;

import java.nio.LongBuffer;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Compiles and owns exact compute programs; unsupported shader semantics fail during admission. */
final class VulkanGenericComputePipelines implements AutoCloseable {
    private final VulkanDeviceRuntime device;
    private final VulkanGenericResourceRegistry resources;
    private final Map<ComputePipelineState, Compiled> cache = new IdentityHashMap<>();
    private boolean closed;

    VulkanGenericComputePipelines(VulkanDeviceRuntime device, VulkanGenericResourceRegistry resources) {
        this.device = Objects.requireNonNull(device, "device");
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    Compiled require(ComputePipelineState state) {
        requireOpen();
        ComputePipelineState checked = Objects.requireNonNull(state, "state");
        try {
            return cache.computeIfAbsent(checked, this::compile);
        } catch (UnsupportedOperationException unsupported) {
            throw unsupported;
        } catch (VulkanGenericPipelineCompilationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new VulkanGenericPipelineCompilationException(
                    "generic compute pipeline compilation failed for " + checked.program().id(), failure
            );
        }
    }

    void updateBindings(Compiled pipeline, BindingSet bindings) {
        requireOpen();
        if (pipeline.descriptors() == null) {
            if (!bindings.layout().entries().isEmpty()) {
                throw new IllegalArgumentException("compute pipeline has no descriptor layout for non-empty bindings");
            }
            return;
        }
        pipeline.descriptors().update(bindings, resources, resources.samplers());
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
            } finally {
                VK10.vkDestroyPipeline(device.device(), pipeline.pipeline(), null);
                VK10.vkDestroyPipelineLayout(device.device(), pipeline.layout(), null);
            }
        }
        cache.clear();
        if (failure != null) throw failure;
    }

    private Compiled compile(ComputePipelineState state) {
        ShaderModule module = state.program().modules().get(0);
        long shaderModule = VK10.VK_NULL_HANDLE;
        long pipelineLayout = VK10.VK_NULL_HANDLE;
        long pipeline = VK10.VK_NULL_HANDLE;
        VulkanGenericDescriptorSetBank descriptors = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            shaderModule = createShaderModule(stack, module);
            if (!state.program().bindingLayout().entries().isEmpty()) {
                descriptors = VulkanGenericDescriptorSetBank.create(
                        stack, device.device(), state.program().bindingLayout(), device.maxBoundDescriptorSets()
                );
            }
            pipelineLayout = createPipelineLayout(stack, state, descriptors);
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(shaderModule)
                    .pName(stack.UTF8(module.entryPoint()));
            VkComputePipelineCreateInfo.Buffer createInfo = VkComputePipelineCreateInfo.calloc(1, stack);
            createInfo.get(0).sType$Default().stage(stage).layout(pipelineLayout);
            LongBuffer output = stack.longs(VK10.VK_NULL_HANDLE);
            VulkanFailures.check(VK10.vkCreateComputePipelines(device.device(), VK10.VK_NULL_HANDLE,
                    createInfo, null, output), "vkCreateComputePipelines.generic");
            pipeline = output.get(0);
            Compiled result = new Compiled(state, pipeline, pipelineLayout, descriptors);
            pipeline = VK10.VK_NULL_HANDLE;
            pipelineLayout = VK10.VK_NULL_HANDLE;
            descriptors = null;
            return result;
        } finally {
            if (shaderModule != VK10.VK_NULL_HANDLE) VK10.vkDestroyShaderModule(device.device(), shaderModule, null);
            if (pipeline != VK10.VK_NULL_HANDLE) VK10.vkDestroyPipeline(device.device(), pipeline, null);
            if (pipelineLayout != VK10.VK_NULL_HANDLE) VK10.vkDestroyPipelineLayout(device.device(), pipelineLayout, null);
            if (descriptors != null) descriptors.close();
        }
    }

    private long createShaderModule(MemoryStack stack, ShaderModule module) {
        VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
                .sType$Default().pCode(module.spirv());
        LongBuffer output = stack.longs(VK10.VK_NULL_HANDLE);
        VulkanFailures.check(VK10.vkCreateShaderModule(device.device(), info, null, output),
                "vkCreateShaderModule.genericCompute");
        return output.get(0);
    }

    private long createPipelineLayout(
            MemoryStack stack, ComputePipelineState state, VulkanGenericDescriptorSetBank descriptors
    ) {
        VkPipelineLayoutCreateInfo info = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default();
        if (descriptors != null) {
            LongBuffer layouts = stack.mallocLong(descriptors.groups().size());
            for (int group : descriptors.groups()) layouts.put(descriptors.layout(group));
            layouts.flip();
            info.pSetLayouts(layouts);
        }
        int pushConstantSize = state.program().pushConstantByteSize();
        if (pushConstantSize > 0) {
            VkPushConstantRange.Buffer ranges = VkPushConstantRange.calloc(1, stack);
            ranges.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(pushConstantSize);
            info.pPushConstantRanges(ranges);
        }
        LongBuffer output = stack.longs(VK10.VK_NULL_HANDLE);
        VulkanFailures.check(VK10.vkCreatePipelineLayout(device.device(), info, null, output),
                "vkCreatePipelineLayout.genericCompute");
        return output.get(0);
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("generic compute pipeline owner is closed");
    }

    record Compiled(
            ComputePipelineState state,
            long pipeline,
            long layout,
            VulkanGenericDescriptorSetBank descriptors
    ) implements AutoCloseable {
        Compiled {
            Objects.requireNonNull(state, "state");
            if (pipeline == VK10.VK_NULL_HANDLE || layout == VK10.VK_NULL_HANDLE) {
                throw new IllegalArgumentException("compiled compute pipeline handles must be non-null");
            }
        }

        @Override
        public void close() {
            if (descriptors != null) descriptors.close();
        }
    }
}
