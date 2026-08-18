package top.ceroxe.rt.renderer.rt.pipeline;

import org.lwjgl.system.MemoryStack;
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
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.util.shaderc.Shaderc;
import top.ceroxe.rt.renderer.RtEdgeSink;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

import java.nio.LongBuffer;
import java.util.Objects;

/** Ordered generic image composition into one provider-owned frame image. */
public final class VulkanFrameCompositionPipeline implements AutoCloseable {
    public static final int MAX_LAYERS = 8;
    private static final int BINDING_COUNT = MAX_LAYERS + 1;
    private static final int WORKGROUP_SIZE = 8;
    private static final String SHADER = "assets/rtrenderer/shaders/gpuscene/generic_frame_composition.comp";

    private final VkDevice device;
    private final long descriptorPool;
    private final long descriptorLayout;
    private final long pipelineLayout;
    private final long pipeline;
    private final long[] descriptorSets;
    private boolean closed;

    private VulkanFrameCompositionPipeline(
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

    public static VulkanFrameCompositionPipeline open(VulkanDeviceRuntime runtime, int frameCount, boolean linearHdr) {
        Objects.requireNonNull(runtime, "runtime");
        if (frameCount <= 0) throw new IllegalArgumentException("frameCount must be positive");
        VkDevice device = runtime.device();
        long pool = 0L, layout = 0L, pipelineLayout = 0L, pipeline = 0L, module = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            layout = createDescriptorLayout(stack, device);
            pool = createDescriptorPool(stack, device, frameCount);
            long[] sets = allocateSets(stack, device, pool, layout, frameCount);
            pipelineLayout = createPipelineLayout(stack, device, layout);
            module = RtShaderModuleCompiler.createModule(stack, device,
                    RtShaderModuleCompiler.loadProduction(
                            SHADER, Shaderc.shaderc_compute_shader, linearHdr, RtEdgeSink.NOOP));
            pipeline = createPipeline(stack, device, pipelineLayout, module);
            return new VulkanFrameCompositionPipeline(device, pool, layout, pipelineLayout, pipeline, sets);
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            if (pipeline != 0L) VK10.vkDestroyPipeline(device, pipeline, null);
            if (pipelineLayout != 0L) VK10.vkDestroyPipelineLayout(device, pipelineLayout, null);
            if (pool != 0L) VK10.vkDestroyDescriptorPool(device, pool, null);
            if (layout != 0L) VK10.vkDestroyDescriptorSetLayout(device, layout, null);
            throw failure;
        } finally {
            RtShaderModuleCompiler.destroyModule(device, module);
        }
    }

    /** Records one ordered composition. All image views must be full single-layer 2D views. */
    public synchronized void record(
            VkCommandBuffer commands, MemoryStack stack, int slot, int width, int height,
            long[] sourceViews, long outputView, int[] operations
    ) {
        requireOpen();
        Objects.requireNonNull(commands, "commands");
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(sourceViews, "sourceViews");
        Objects.requireNonNull(operations, "operations");
        if (slot < 0 || slot >= descriptorSets.length) throw new IndexOutOfBoundsException(slot);
        if (sourceViews.length == 0 || sourceViews.length > MAX_LAYERS || operations.length != sourceViews.length) {
            throw new IllegalArgumentException("composition layer count is outside the executable range");
        }
        if (width <= 0 || height <= 0 || outputView == 0L) throw new IllegalArgumentException("invalid composition target");
        for (long view : sourceViews) if (view == 0L) throw new IllegalArgumentException("source view must not be null");
        for (int operation : operations) if (operation < 0 || operation > 2) throw new IllegalArgumentException("invalid composition operation");
        updateDescriptors(stack, descriptorSets[slot], sourceViews, outputView);
        VK10.vkCmdBindPipeline(commands, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        VK10.vkCmdBindDescriptorSets(commands, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0,
                stack.longs(descriptorSets[slot]), null);
        java.nio.IntBuffer constants = stack.mallocInt(1 + MAX_LAYERS);
        constants.put(0, sourceViews.length);
        for (int index = 0; index < MAX_LAYERS; index++) constants.put(1 + index, index < operations.length ? operations[index] : 0);
        VK10.vkCmdPushConstants(commands, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, constants);
        VK10.vkCmdDispatch(commands, divideUp(width), divideUp(height), 1);
    }

    public static void recordCompletionBarrier(VkCommandBuffer commands, MemoryStack stack) {
        VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
        barrier.get(0).sType$Default().srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_MEMORY_READ_BIT | VK10.VK_ACCESS_MEMORY_WRITE_BIT);
        VK10.vkCmdPipelineBarrier(commands, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, barrier, null, null);
    }

    private void updateDescriptors(MemoryStack stack, long set, long[] sources, long output) {
        VkDescriptorImageInfo.Buffer infos = VkDescriptorImageInfo.calloc(BINDING_COUNT, stack);
        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(BINDING_COUNT, stack);
        long fallback = sources[0];
        for (int binding = 0; binding < MAX_LAYERS; binding++) {
            infos.get(binding).imageView(binding < sources.length ? sources[binding] : fallback)
                    .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            writes.get(binding).sType$Default().dstSet(set).dstBinding(binding)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1)
                    .pImageInfo(VkDescriptorImageInfo.create(infos.get(binding).address(), 1));
        }
        infos.get(MAX_LAYERS).imageView(output).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
        writes.get(MAX_LAYERS).sType$Default().dstSet(set).dstBinding(MAX_LAYERS)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1)
                .pImageInfo(VkDescriptorImageInfo.create(infos.get(MAX_LAYERS).address(), 1));
        VK10.vkUpdateDescriptorSets(device, writes, null);
    }

    private static long createDescriptorLayout(MemoryStack stack, VkDevice device) {
        VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(BINDING_COUNT, stack);
        for (int binding = 0; binding < BINDING_COUNT; binding++) bindings.get(binding).binding(binding).descriptorCount(1)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
        VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings);
        LongBuffer handle = stack.longs(0L);
        check(VK10.vkCreateDescriptorSetLayout(device, info, null, handle), "vkCreateDescriptorSetLayout.frameComposition");
        return handle.get(0);
    }

    private static long createDescriptorPool(MemoryStack stack, VkDevice device, int count) {
        VkDescriptorPoolSize.Buffer size = VkDescriptorPoolSize.calloc(1, stack);
        size.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(Math.multiplyExact(count, BINDING_COUNT));
        VkDescriptorPoolCreateInfo info = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(count).pPoolSizes(size);
        LongBuffer handle = stack.longs(0L);
        check(VK10.vkCreateDescriptorPool(device, info, null, handle), "vkCreateDescriptorPool.frameComposition");
        return handle.get(0);
    }

    private static long[] allocateSets(MemoryStack stack, VkDevice device, long pool, long layout, int count) {
        LongBuffer layouts = stack.mallocLong(count);
        for (int index = 0; index < count; index++) layouts.put(index, layout);
        LongBuffer sets = stack.mallocLong(count);
        VkDescriptorSetAllocateInfo info = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                .descriptorPool(pool).pSetLayouts(layouts);
        check(VK10.vkAllocateDescriptorSets(device, info, sets), "vkAllocateDescriptorSets.frameComposition");
        long[] result = new long[count];
        sets.get(result);
        return result;
    }

    private static long createPipelineLayout(MemoryStack stack, VkDevice device, long layout) {
        VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack);
        range.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(36);
        VkPipelineLayoutCreateInfo info = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                .pSetLayouts(stack.longs(layout)).pPushConstantRanges(range);
        LongBuffer handle = stack.longs(0L);
        check(VK10.vkCreatePipelineLayout(device, info, null, handle), "vkCreatePipelineLayout.frameComposition");
        return handle.get(0);
    }

    private static long createPipeline(MemoryStack stack, VkDevice device, long layout, long module) {
        VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
        VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack);
        info.get(0).sType$Default().stage(stage).layout(layout);
        LongBuffer handle = stack.longs(0L);
        check(VK10.vkCreateComputePipelines(device, VK10.VK_NULL_HANDLE, info, null, handle), "vkCreateComputePipelines.frameComposition");
        return handle.get(0);
    }

    private static int divideUp(int value) { return Math.addExact(value, WORKGROUP_SIZE - 1) / WORKGROUP_SIZE; }
    private static void check(int result, String operation) { top.ceroxe.rt.renderer.rt.device.VulkanFailures.check(result, operation); }
    private void requireOpen() { if (closed) throw new IllegalStateException("frame composition pipeline is closed"); }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        VK10.vkDestroyPipeline(device, pipeline, null);
        VK10.vkDestroyPipelineLayout(device, pipelineLayout, null);
        VK10.vkDestroyDescriptorPool(device, descriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(device, descriptorLayout, null);
    }
}
