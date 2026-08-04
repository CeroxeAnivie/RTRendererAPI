package top.ceroxe.rt.renderer.rt.pipeline;

import java.nio.LongBuffer;
import java.util.Objects;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import top.ceroxe.rt.renderer.RtEdgeSink;
import top.ceroxe.rt.renderer.rt.device.RtGpuImage;
import top.ceroxe.rt.renderer.rt.device.VulkanDeviceRuntime;

/** Publishes a private linear-HDR reconstruction result into the public SDR output image. */
public final class VulkanFramePublicationPipeline implements AutoCloseable {
    private static final String SHADER =
            "assets/rtrenderer/shaders/gpuscene/gpuscene_reconstruction_publish.comp";
    private static final int BINDING_COUNT = 2;
    private static final int WORKGROUP_SIZE = 8;

    private final VkDevice device;
    private final long descriptorPool;
    private final long descriptorLayout;
    private final long pipelineLayout;
    private final long pipeline;
    private final long[] descriptorSets;
    private boolean closed;

    private VulkanFramePublicationPipeline(
            VkDevice device,
            long descriptorPool,
            long descriptorLayout,
            long pipelineLayout,
            long pipeline,
            long[] descriptorSets
    ) {
        this.device = Objects.requireNonNull(device, "device");
        this.descriptorPool = descriptorPool;
        this.descriptorLayout = descriptorLayout;
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
        this.descriptorSets = descriptorSets;
    }

    /**
     * Creates one bounded descriptor set per frame slot.
     *
     * @param runtime device owner used to create the publication resources
     * @param frameCount maximum number of independently recorded frame slots
     * @return an open publication pipeline
     */
    public static VulkanFramePublicationPipeline open(VulkanDeviceRuntime runtime, int frameCount) {
        Objects.requireNonNull(runtime, "runtime");
        if (frameCount <= 0) throw new IllegalArgumentException("frameCount must be positive");
        VkDevice device = runtime.device();
        long pool = 0L;
        long layout = 0L;
        long pipelineLayout = 0L;
        long pipeline = 0L;
        long shader = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            layout = createDescriptorLayout(stack, device);
            pool = createDescriptorPool(stack, device, frameCount);
            long[] sets = allocateSets(stack, device, pool, layout, frameCount);
            pipelineLayout = createPipelineLayout(stack, device, layout);
            shader = RtShaderModuleCompiler.createModule(
                    stack,
                    device,
                    RtShaderModuleCompiler.loadProduction(
                            SHADER, Shaderc.shaderc_compute_shader, RtEdgeSink.NOOP
                    )
            );
            pipeline = createPipeline(stack, device, pipelineLayout, shader);
            return new VulkanFramePublicationPipeline(
                    device, pool, layout, pipelineLayout, pipeline, sets
            );
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            if (pipeline != 0L) VK10.vkDestroyPipeline(device, pipeline, null);
            if (pipelineLayout != 0L) VK10.vkDestroyPipelineLayout(device, pipelineLayout, null);
            if (pool != 0L) VK10.vkDestroyDescriptorPool(device, pool, null);
            if (layout != 0L) VK10.vkDestroyDescriptorSetLayout(device, layout, null);
            throw failure;
        } finally {
            RtShaderModuleCompiler.destroyModule(device, shader);
        }
    }

    /**
     * Records HDR-to-SDR publication after reconstruction and before frame-generation tagging.
     *
     * @param commands command buffer receiving the conversion and visibility barriers
     * @param stack caller-owned temporary allocation stack
     * @param slot frame-slot descriptor index
     * @param reconstruction private linear-HDR source image
     * @param published public SDR destination image
     */
    public synchronized void record(
            org.lwjgl.vulkan.VkCommandBuffer commands,
            MemoryStack stack,
            int slot,
            RtGpuImage reconstruction,
            RtGpuImage published
    ) {
        requireOpen();
        Objects.requireNonNull(commands, "commands");
        Objects.requireNonNull(stack, "stack");
        RtGpuImage source = Objects.requireNonNull(reconstruction, "reconstruction");
        RtGpuImage destination = Objects.requireNonNull(published, "published");
        if (source.format() != VK10.VK_FORMAT_R16G16B16A16_SFLOAT
                || destination.format() != VK10.VK_FORMAT_R8G8B8A8_UNORM) {
            throw new IllegalArgumentException("publication requires RGBA16F input and RGBA8 output");
        }
        if (source.width() != destination.width() || source.height() != destination.height()) {
            throw new IllegalArgumentException("publication images must share one output extent");
        }
        if (source.image() == destination.image()) {
            throw new IllegalArgumentException("publication source and destination must not alias");
        }
        if (slot < 0 || slot >= descriptorSets.length) throw new IndexOutOfBoundsException(slot);

        updateDescriptors(stack, descriptorSets[slot], source.imageView(), destination.imageView());
        VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack)
                .sType$Default()
                .srcAccessMask(VK10.VK_ACCESS_MEMORY_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
        VK10.vkCmdPipelineBarrier(
                commands,
                VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0,
                barrier,
                null,
                null
        );
        VK10.vkCmdBindPipeline(commands, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        VK10.vkCmdBindDescriptorSets(
                commands,
                VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                pipelineLayout,
                0,
                stack.longs(descriptorSets[slot]),
                null
        );
        VK10.vkCmdDispatch(
                commands,
                divideUp(destination.width()),
                divideUp(destination.height()),
                1
        );
        VkMemoryBarrier.Buffer completion = VkMemoryBarrier.calloc(1, stack)
                .sType$Default()
                .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_MEMORY_READ_BIT);
        VK10.vkCmdPipelineBarrier(
                commands,
                VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                0,
                completion,
                null,
                null
        );
    }

    private void updateDescriptors(MemoryStack stack, long set, long sourceView, long outputView) {
        VkDescriptorImageInfo.Buffer infos = VkDescriptorImageInfo.calloc(BINDING_COUNT, stack);
        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(BINDING_COUNT, stack);
        long[] views = {sourceView, outputView};
        for (int binding = 0; binding < BINDING_COUNT; binding++) {
            if (views[binding] == 0L) throw new IllegalArgumentException("publication view is null");
            infos.get(binding).imageView(views[binding]).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            writes.get(binding)
                    .sType$Default()
                    .dstSet(set)
                    .dstBinding(binding)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .pImageInfo(VkDescriptorImageInfo.create(infos.get(binding).address(), 1));
        }
        VK10.vkUpdateDescriptorSets(device, writes, null);
    }

    private static long createDescriptorLayout(MemoryStack stack, VkDevice device) {
        VkDescriptorSetLayoutBinding.Buffer bindings =
                VkDescriptorSetLayoutBinding.calloc(BINDING_COUNT, stack);
        for (int binding = 0; binding < BINDING_COUNT; binding++) {
            bindings.get(binding)
                    .binding(binding)
                    .descriptorCount(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
        }
        VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .pBindings(bindings);
        LongBuffer handle = stack.longs(0L);
        check(VK10.vkCreateDescriptorSetLayout(device, info, null, handle),
                "vkCreateDescriptorSetLayout.framePublication");
        return handle.get(0);
    }

    private static long createDescriptorPool(MemoryStack stack, VkDevice device, int count) {
        VkDescriptorPoolSize.Buffer size = VkDescriptorPoolSize.calloc(1, stack);
        size.get(0)
                .type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(Math.multiplyExact(count, BINDING_COUNT));
        VkDescriptorPoolCreateInfo info = VkDescriptorPoolCreateInfo.calloc(stack)
                .sType$Default()
                .maxSets(count)
                .pPoolSizes(size);
        LongBuffer handle = stack.longs(0L);
        check(VK10.vkCreateDescriptorPool(device, info, null, handle),
                "vkCreateDescriptorPool.framePublication");
        return handle.get(0);
    }

    private static long[] allocateSets(
            MemoryStack stack, VkDevice device, long pool, long layout, int count
    ) {
        LongBuffer layouts = stack.mallocLong(count);
        for (int index = 0; index < count; index++) layouts.put(index, layout);
        LongBuffer sets = stack.mallocLong(count);
        VkDescriptorSetAllocateInfo info = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType$Default()
                .descriptorPool(pool)
                .pSetLayouts(layouts);
        check(VK10.vkAllocateDescriptorSets(device, info, sets),
                "vkAllocateDescriptorSets.framePublication");
        long[] result = new long[count];
        sets.get(0, result);
        return result;
    }

    private static long createPipelineLayout(MemoryStack stack, VkDevice device, long layout) {
        VkPipelineLayoutCreateInfo info = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .pSetLayouts(stack.longs(layout));
        LongBuffer handle = stack.longs(0L);
        check(VK10.vkCreatePipelineLayout(device, info, null, handle),
                "vkCreatePipelineLayout.framePublication");
        return handle.get(0);
    }

    private static long createPipeline(
            MemoryStack stack, VkDevice device, long layout, long shader
    ) {
        VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                .sType$Default()
                .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                .module(shader)
                .pName(stack.UTF8("main"));
        VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack);
        info.get(0).sType$Default().stage(stage).layout(layout);
        LongBuffer handle = stack.longs(0L);
        check(VK10.vkCreateComputePipelines(
                device, VK10.VK_NULL_HANDLE, info, null, handle
        ), "vkCreateComputePipelines.framePublication");
        return handle.get(0);
    }

    private static int divideUp(int value) {
        return Math.addExact(value, WORKGROUP_SIZE - 1) / WORKGROUP_SIZE;
    }

    private static void check(int result, String operation) {
        top.ceroxe.rt.renderer.rt.device.VulkanFailures.check(result, operation);
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("frame publication pipeline is closed");
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        VK10.vkDestroyPipeline(device, pipeline, null);
        VK10.vkDestroyPipelineLayout(device, pipelineLayout, null);
        VK10.vkDestroyDescriptorPool(device, descriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(device, descriptorLayout, null);
    }
}
