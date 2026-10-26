package top.ceroxe.rt.renderer.rt.pipeline;

import top.ceroxe.rt.renderer.api.SceneShaderBindings;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.Objects;

/**
 * Owns the immutable GPUScene descriptor layout, pool, and set bank.
 */
final class GpuSceneDescriptorLayout implements AutoCloseable {
    private static final int RAYGEN = KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR;
    private static final int MISS = KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR;
    private static final int CLOSEST_HIT = KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR;
    private static final int ANY_HIT = KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR;
    private static final int ALL_STAGES = RAYGEN | MISS | CLOSEST_HIT | ANY_HIT;
    private static final int SURFACE_STAGES = CLOSEST_HIT | ANY_HIT;

    private final VkDevice device;
    private final long layout;
    private final long pool;
    private final long[] sets;
    private boolean closed;

    private GpuSceneDescriptorLayout(VkDevice device, long layout, long pool, long[] sets) {
        this.device = Objects.requireNonNull(device, "device");
        if (layout == 0L || pool == 0L) throw new IllegalArgumentException("descriptor handles must not be null");
        this.layout = layout;
        this.pool = pool;
        this.sets = Objects.requireNonNull(sets, "sets").clone();
        if (this.sets.length == 0) throw new IllegalArgumentException("descriptor set bank must not be empty");
        for (long set : this.sets) if (set == 0L) throw new IllegalArgumentException("descriptor set is null");
    }

    static GpuSceneDescriptorLayout create(MemoryStack stack, VkDevice device, int setCount, int applicationBindings) {
        Objects.requireNonNull(stack, "stack");
        VkDevice checkedDevice = Objects.requireNonNull(device, "device");
        if (setCount <= 0) throw new IllegalArgumentException("descriptor set count must be positive");
        long layout = 0L;
        long pool = 0L;
        try {
            layout = createSetLayout(stack, checkedDevice, applicationBindings);
            pool = createPool(stack, checkedDevice, setCount, applicationBindings);
            long[] sets = RtRayTracingPipelineFactory.allocateDescriptorSets(
                    stack, checkedDevice, pool, layout, setCount
            );
            GpuSceneDescriptorLayout result = new GpuSceneDescriptorLayout(checkedDevice, layout, pool, sets);
            layout = 0L;
            pool = 0L;
            return result;
        } finally {
            if (pool != 0L) VK10.vkDestroyDescriptorPool(checkedDevice, pool, null);
            if (layout != 0L) VK10.vkDestroyDescriptorSetLayout(checkedDevice, layout, null);
        }
    }

    private static long createSetLayout(MemoryStack stack, VkDevice device, int applicationBindings) {
        VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(
                SceneShaderBindings.COUNT + applicationBindings, stack
        );
        bind(bindings, SceneShaderBindings.TLAS,
                KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, ALL_STAGES);
        bind(bindings, SceneShaderBindings.OUTPUT_IMAGE, VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, RAYGEN);
        bind(bindings, SceneShaderBindings.FRAME_UNIFORMS, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, ALL_STAGES);
        bind(bindings, SceneShaderBindings.TEXTURE_RECORDS, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, ALL_STAGES);
        bind(bindings, SceneShaderBindings.TEXTURE_PIXELS, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, ALL_STAGES);
        bind(bindings, SceneShaderBindings.MATERIAL_RECORDS, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, ALL_STAGES);
        bind(bindings, SceneShaderBindings.MESH_RECORDS, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, ALL_STAGES);
        bind(bindings, SceneShaderBindings.POSITIONS, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, ALL_STAGES);
        bind(bindings, SceneShaderBindings.NORMALS, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, ALL_STAGES);
        bind(bindings, SceneShaderBindings.TANGENTS, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, ALL_STAGES);
        bind(bindings, SceneShaderBindings.TEXTURE_COORDINATES, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, ALL_STAGES);
        bind(bindings, SceneShaderBindings.COLORS, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, ALL_STAGES);
        bind(bindings, SceneShaderBindings.LIGHTMAP_COORDINATES,
                VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, ALL_STAGES);
        bind(bindings, SceneShaderBindings.INDICES, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, ALL_STAGES);
        bind(bindings, SceneShaderBindings.TRIANGLE_MATERIAL_SLOTS,
                VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, ALL_STAGES);
        bind(bindings, SceneShaderBindings.INSTANCE_RECORDS, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, ALL_STAGES);
        bind(bindings, SceneShaderBindings.LIGHT_RECORDS, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, ALL_STAGES);
        bind(bindings, SceneShaderBindings.HISTORY_COLOR_INPUT,
                VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, RAYGEN);
        bind(bindings, SceneShaderBindings.HISTORY_COLOR_OUTPUT,
                VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, RAYGEN);
        bind(bindings, SceneShaderBindings.HISTORY_GEOMETRY_INPUT,
                VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, RAYGEN);
        bind(bindings, SceneShaderBindings.HISTORY_GEOMETRY_OUTPUT,
                VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, RAYGEN);
        bind(bindings, SceneShaderBindings.MOTION_OUTPUT,
                VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, RAYGEN);
        bind(bindings, SceneShaderBindings.TRANSIENT_INSTANCE_RECORDS,
                VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, ALL_STAGES);
        bind(bindings, SceneShaderBindings.DENOISING_NORMAL_ROUGHNESS,
                VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, RAYGEN);
        bind(bindings, SceneShaderBindings.DENOISING_VIEW_Z,
                VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, RAYGEN);
        bind(bindings, SceneShaderBindings.DENOISING_MOTION_VECTORS,
                VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, RAYGEN);
        bind(bindings, SceneShaderBindings.DENOISING_DIFFUSE_RADIANCE_HIT_DISTANCE,
                VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, RAYGEN);
        bind(bindings, SceneShaderBindings.DENOISING_SPECULAR_RADIANCE_HIT_DISTANCE,
                VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, RAYGEN);
        bind(bindings, SceneShaderBindings.DENOISING_DIFFUSE_MATERIAL_FACTOR,
                VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, RAYGEN);
        bind(bindings, SceneShaderBindings.DENOISING_SPECULAR_MATERIAL_FACTOR,
                VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, RAYGEN);
        bind(bindings, SceneShaderBindings.RECONSTRUCTION_DEPTH,
                VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, RAYGEN);
        bind(bindings, SceneShaderBindings.RECONSTRUCTION_MOTION_VECTORS,
                VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, RAYGEN);
        bind(bindings, SceneShaderBindings.RECONSTRUCTION_EXPOSURE,
                VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, RAYGEN);
        for (int i = 0; i < applicationBindings; i++) {
            bind(bindings, SceneShaderBindings.APPLICATION_DATA + i, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, ALL_STAGES);
        }
        VkDescriptorSetLayoutCreateInfo createInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType$Default().pBindings(bindings);
        LongBuffer handle = stack.longs(0L);
        checkVk(VK10.vkCreateDescriptorSetLayout(device, createInfo, null, handle),
                "vkCreateDescriptorSetLayout.gpuScene");
        return handle.get(0);
    }

    private static void bind(
            VkDescriptorSetLayoutBinding.Buffer bindings,
            int binding,
            int descriptorType,
            int stageFlags
    ) {
        bindings.get(binding).binding(binding).descriptorType(descriptorType).descriptorCount(1).stageFlags(stageFlags);
    }

    private static long createPool(MemoryStack stack, VkDevice device, int setCount, int applicationBindings) {
        VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(3, stack);
        sizes.get(0).type(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                .descriptorCount(setCount);
        sizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(
                Math.multiplyExact(setCount, SceneShaderBindings.STORAGE_IMAGE_COUNT)
        );
        sizes.get(2).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(Math.multiplyExact(setCount, SceneShaderBindings.STORAGE_BUFFER_COUNT + applicationBindings));
        VkDescriptorPoolCreateInfo createInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                .sType$Default().maxSets(setCount).pPoolSizes(sizes);
        LongBuffer handle = stack.longs(0L);
        checkVk(VK10.vkCreateDescriptorPool(device, createInfo, null, handle),
                "vkCreateDescriptorPool.gpuScene");
        return handle.get(0);
    }

    private static void checkVk(int result, String operation) {
        top.ceroxe.rt.renderer.rt.device.VulkanFailures.check(result, operation);
    }

    long layout() {
        requireOpen();
        return layout;
    }

    int setCount() {
        requireOpen();
        return sets.length;
    }

    long set(int index) {
        requireOpen();
        if (index < 0 || index >= sets.length) throw new IndexOutOfBoundsException(index);
        return sets[index];
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("GPUScene descriptor layout is closed");
    }

    /**
     * Destroys the descriptor pool and layout; repeated calls are harmless.
     */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        VK10.vkDestroyDescriptorPool(device, pool, null);
        VK10.vkDestroyDescriptorSetLayout(device, layout, null);
    }
}
