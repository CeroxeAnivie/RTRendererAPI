package top.ceroxe.rt.renderer.rt.pipeline;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkDevice;
import top.ceroxe.rt.renderer.RtEdgeSink;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

import java.nio.LongBuffer;
import java.util.Objects;

/** Owns the small compute stage that publishes NRD radiance into one frame's final output image. */
public final class VulkanNrdComposePipeline implements AutoCloseable {
    private static final int IMAGE_BINDING_COUNT = 9;
    private static final int WORKGROUP_SIZE = 8;
    private static final String SHADER = "assets/rtrenderer/shaders/gpuscene/gpuscene_nrd_compose.comp";

    private final VkDevice device;
    private final long descriptorPool;
    private final long descriptorLayout;
    private final long pipelineLayout;
    private final long pipeline;
    private final long[] descriptorSets;
    private boolean closed;

    private VulkanNrdComposePipeline(
            VkDevice device, long descriptorPool, long descriptorLayout, long pipelineLayout,
            long pipeline, long[] descriptorSets
    ) {
        this.device = Objects.requireNonNull(device, "device");
        this.descriptorPool = descriptorPool;
        this.descriptorLayout = descriptorLayout;
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
        this.descriptorSets = descriptorSets;
    }

    /**
     * Creates the bounded descriptor sets and compute pipeline used by all frame slots.
     *
     * @param runtime borrowed open Vulkan device runtime
     * @param frameCount positive bounded frame-slot count
     * @param linearHdrOutput whether the published output uses linear HDR encoding
     * @return independently closeable compose pipeline
     */
    public static VulkanNrdComposePipeline open(
            VulkanDeviceRuntime runtime, int frameCount, boolean linearHdrOutput
    ) {
        Objects.requireNonNull(runtime, "runtime");
        if (frameCount <= 0) throw new IllegalArgumentException("frameCount must be positive");
        VkDevice device = runtime.device();
        long pool = 0L;
        long layout = 0L;
        long pipelineLayout = 0L;
        long pipeline = 0L;
        long shaderModule = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            layout = createDescriptorLayout(stack, device);
            pool = createDescriptorPool(stack, device, frameCount);
            long[] sets = allocateSets(stack, device, pool, layout, frameCount);
            pipelineLayout = createPipelineLayout(stack, device, layout);
            shaderModule = RtShaderModuleCompiler.createModule(stack, device,
                    RtShaderModuleCompiler.loadProduction(
                            SHADER, Shaderc.shaderc_compute_shader, linearHdrOutput, RtEdgeSink.NOOP));
            pipeline = createPipeline(stack, device, pipelineLayout, shaderModule);
            return new VulkanNrdComposePipeline(device, pool, layout, pipelineLayout, pipeline, sets);
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            if (pipeline != 0L) VK10.vkDestroyPipeline(device, pipeline, null);
            if (pipelineLayout != 0L) VK10.vkDestroyPipelineLayout(device, pipelineLayout, null);
            if (pool != 0L) VK10.vkDestroyDescriptorPool(device, pool, null);
            if (layout != 0L) VK10.vkDestroyDescriptorSetLayout(device, layout, null);
            throw failure;
        } finally {
            RtShaderModuleCompiler.destroyModule(device, shaderModule);
        }
    }

    /**
     * Records one descriptor update and compute dispatch that combines noisy and denoised signals.
     *
     * @param commands command buffer currently recording the frame
     * @param stack caller-owned temporary native allocation stack
     * @param slot bounded descriptor slot index
     * @param width positive output width
     * @param height positive output height
     * @param traceView original ray-traced color view
     * @param noisyDiffuseView noisy diffuse radiance view
     * @param noisySpecularView noisy specular radiance view
     * @param denoisingViewZ linear view-space depth guide; values at or above the miss sentinel
     *                       bypass NRD history and preserve the trace
     * @param denoisedDiffuseView NRD denoised diffuse radiance view
     * @param denoisedSpecularView NRD denoised specular radiance view
     * @param diffuseMaterialFactorView diffuse remodulation factor view
     * @param specularMaterialFactorView specular remodulation factor view
     * @param outputView final published output view
     */
    public synchronized void record(
            VkCommandBuffer commands, MemoryStack stack, int slot, int width, int height,
            long traceView, long noisyDiffuseView, long noisySpecularView, long denoisingViewZ,
            long denoisedDiffuseView,
            long denoisedSpecularView, long diffuseMaterialFactorView,
            long specularMaterialFactorView, long outputView
    ) {
        requireOpen();
        Objects.requireNonNull(commands, "commands");
        Objects.requireNonNull(stack, "stack");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("compose extent must be positive");
        if (slot < 0 || slot >= descriptorSets.length) throw new IndexOutOfBoundsException(slot);
        updateDescriptors(stack, descriptorSets[slot], traceView, noisyDiffuseView, noisySpecularView,
                denoisingViewZ, denoisedDiffuseView, denoisedSpecularView, diffuseMaterialFactorView,
                specularMaterialFactorView, outputView);
        recordMemoryBarrier(stack, commands, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK10.VK_ACCESS_MEMORY_WRITE_BIT,
                VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
        VK10.vkCmdBindPipeline(commands, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        VK10.vkCmdBindDescriptorSets(commands, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0,
                stack.longs(descriptorSets[slot]), null);
        VK10.vkCmdDispatch(commands, divideUp(width), divideUp(height), 1);
    }

    /**
     * Makes the compose output visible to a following Streamline reconstruction dispatch.
     *
     * @param commands command buffer receiving the barrier
     * @param stack temporary stack used for Vulkan structures
     */
    public static void recordCompletionBarrier(
            VkCommandBuffer commands, MemoryStack stack
    ) {
        Objects.requireNonNull(commands, "commands");
        Objects.requireNonNull(stack, "stack");
        recordMemoryBarrier(stack, commands,
                VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK10.VK_ACCESS_SHADER_WRITE_BIT,
                VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT);
    }

    private static void recordMemoryBarrier(
            MemoryStack stack, VkCommandBuffer commands,
            int sourceStage, int destinationStage,
            int sourceAccess, int destinationAccess
    ) {
        VkMemoryBarrier.Buffer barriers = VkMemoryBarrier.calloc(1, stack)
                .sType$Default()
                .srcAccessMask(sourceAccess)
                .dstAccessMask(destinationAccess);
        VK10.vkCmdPipelineBarrier(commands, sourceStage, destinationStage, 0,
                barriers, null, null);
    }

    private static int divideUp(int value) {
        return Math.addExact(value, WORKGROUP_SIZE - 1) / WORKGROUP_SIZE;
    }

    private void updateDescriptors(MemoryStack stack, long set, long... views) {
        VkDescriptorImageInfo.Buffer infos = VkDescriptorImageInfo.calloc(IMAGE_BINDING_COUNT, stack);
        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(IMAGE_BINDING_COUNT, stack);
        for (int binding = 0; binding < IMAGE_BINDING_COUNT; binding++) {
            if (views[binding] == 0L) throw new IllegalArgumentException("compose image view must not be null");
            infos.get(binding).imageView(views[binding]).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            writes.get(binding).sType$Default().dstSet(set).dstBinding(binding)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1)
                    .pImageInfo(VkDescriptorImageInfo.create(infos.get(binding).address(), 1));
        }
        VK10.vkUpdateDescriptorSets(device, writes, null);
    }

    private static long createDescriptorLayout(MemoryStack stack, VkDevice device) {
        VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(IMAGE_BINDING_COUNT, stack);
        for (int binding = 0; binding < IMAGE_BINDING_COUNT; binding++) {
            bindings.get(binding).binding(binding).descriptorCount(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
        }
        VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType$Default().pBindings(bindings);
        LongBuffer handle = stack.longs(0L);
        check(VK10.vkCreateDescriptorSetLayout(device, info, null, handle), "vkCreateDescriptorSetLayout.nrdCompose");
        return handle.get(0);
    }

    private static long createDescriptorPool(MemoryStack stack, VkDevice device, int count) {
        VkDescriptorPoolSize.Buffer size = VkDescriptorPoolSize.calloc(1, stack);
        size.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(Math.multiplyExact(count, IMAGE_BINDING_COUNT));
        VkDescriptorPoolCreateInfo info = VkDescriptorPoolCreateInfo.calloc(stack)
                .sType$Default().maxSets(count).pPoolSizes(size);
        LongBuffer handle = stack.longs(0L);
        check(VK10.vkCreateDescriptorPool(device, info, null, handle), "vkCreateDescriptorPool.nrdCompose");
        return handle.get(0);
    }

    private static long[] allocateSets(MemoryStack stack, VkDevice device, long pool, long layout, int count) {
        LongBuffer layouts = stack.mallocLong(count);
        for (int index = 0; index < count; index++) layouts.put(index, layout);
        LongBuffer sets = stack.mallocLong(count);
        VkDescriptorSetAllocateInfo info = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType$Default().descriptorPool(pool).pSetLayouts(layouts);
        check(VK10.vkAllocateDescriptorSets(device, info, sets), "vkAllocateDescriptorSets.nrdCompose");
        long[] result = new long[count];
        sets.get(0, result);
        return result;
    }

    private static long createPipelineLayout(MemoryStack stack, VkDevice device, long layout) {
        VkPipelineLayoutCreateInfo info = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType$Default().pSetLayouts(stack.longs(layout));
        LongBuffer handle = stack.longs(0L);
        check(VK10.vkCreatePipelineLayout(device, info, null, handle), "vkCreatePipelineLayout.nrdCompose");
        return handle.get(0);
    }

    private static long createPipeline(MemoryStack stack, VkDevice device, long layout, long module) {
        VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                .sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
        VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack);
        info.get(0).sType$Default().stage(stage).layout(layout);
        LongBuffer handle = stack.longs(0L);
        check(VK10.vkCreateComputePipelines(device, VK10.VK_NULL_HANDLE, info, null, handle), "vkCreateComputePipelines.nrdCompose");
        return handle.get(0);
    }

    private static void check(int result, String operation) {
        top.ceroxe.rt.renderer.rt.device.VulkanFailures.check(result, operation);
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("NRD compose pipeline is closed");
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        VK10.vkDestroyPipeline(device, pipeline, null);
        VK10.vkDestroyPipelineLayout(device, pipelineLayout, null);
        VK10.vkDestroyDescriptorPool(device, descriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(device, descriptorLayout, null);
    }
}
