package top.ceroxe.rt.renderer.backend.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import top.ceroxe.rt.renderer.api.BindingKey;
import top.ceroxe.rt.renderer.api.BindingLayout;
import top.ceroxe.rt.renderer.api.BindingLayoutEntry;
import top.ceroxe.rt.renderer.api.BindingSet;
import top.ceroxe.rt.renderer.api.BindingType;
import top.ceroxe.rt.renderer.api.BufferResource;
import top.ceroxe.rt.renderer.api.ShaderStage;
import top.ceroxe.rt.renderer.api.TextureUsage;
import top.ceroxe.rt.renderer.rt.device.VulkanFailures;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Owns one immutable binding layout's native descriptor sets.
 *
 * <p>Groups become Vulkan descriptor-set numbers; no group is collapsed or guessed. Descriptor
 * contents are written only from exact versioned values, and all native handles are destroyed
 * before the parent generic image/buffer registry closes.</p>
 */
final class VulkanGenericDescriptorSetBank implements AutoCloseable {
    private final VkDevice device;
    private final BindingLayout layout;
    private final Map<Integer, Long> layoutsByGroup;
    private final Map<Integer, Long> setsByGroup;
    private final long pool;
    private boolean closed;

    private VulkanGenericDescriptorSetBank(
            VkDevice device,
            BindingLayout layout,
            Map<Integer, Long> layoutsByGroup,
            Map<Integer, Long> setsByGroup,
            long pool
    ) {
        this.device = device;
        this.layout = layout;
        this.layoutsByGroup = Collections.unmodifiableMap(new LinkedHashMap<>(layoutsByGroup));
        this.setsByGroup = Collections.unmodifiableMap(new LinkedHashMap<>(setsByGroup));
        this.pool = pool;
    }

    static VulkanGenericDescriptorSetBank create(
            MemoryStack stack,
            VkDevice device,
            BindingLayout layout,
            int maximumBoundDescriptorSets
    ) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(device, "device");
        if (maximumBoundDescriptorSets <= 0) {
            throw new IllegalArgumentException("maximumBoundDescriptorSets must be positive");
        }
        BindingLayout checkedLayout = Objects.requireNonNull(layout, "layout");
        if (checkedLayout.entries().isEmpty()) {
            throw new IllegalArgumentException("an empty binding layout does not require a descriptor bank");
        }
        int maximumGroup = checkedLayout.entries().stream().mapToInt(entry -> entry.key().group()).max().orElseThrow();
        if (maximumGroup >= maximumBoundDescriptorSets) {
            throw new UnsupportedOperationException("binding group " + maximumGroup
                    + " exceeds device maxBoundDescriptorSets=" + maximumBoundDescriptorSets);
        }
        Map<Integer, List<BindingLayoutEntry>> groups = groups(checkedLayout);
        Map<Integer, Long> createdLayouts = new LinkedHashMap<>();
        long pool = VK10.VK_NULL_HANDLE;
        try {
            for (Map.Entry<Integer, List<BindingLayoutEntry>> group : groups.entrySet()) {
                createdLayouts.put(group.getKey(), createLayout(stack, device, group.getValue()));
            }
            pool = createPool(stack, device, checkedLayout);
            LongBuffer nativeLayouts = stack.mallocLong(createdLayouts.size());
            for (long value : createdLayouts.values()) nativeLayouts.put(value);
            nativeLayouts.flip();
            VkDescriptorSetAllocateInfo allocateInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default().descriptorPool(pool).pSetLayouts(nativeLayouts);
            LongBuffer nativeSets = stack.mallocLong(createdLayouts.size());
            VulkanFailures.check(VK10.vkAllocateDescriptorSets(device, allocateInfo, nativeSets),
                    "vkAllocateDescriptorSets.generic");
            Map<Integer, Long> sets = new LinkedHashMap<>();
            int index = 0;
            for (int group : createdLayouts.keySet()) sets.put(group, nativeSets.get(index++));
            VulkanGenericDescriptorSetBank result = new VulkanGenericDescriptorSetBank(
                    device, checkedLayout, createdLayouts, sets, pool
            );
            createdLayouts = null;
            pool = VK10.VK_NULL_HANDLE;
            return result;
        } finally {
            if (pool != VK10.VK_NULL_HANDLE) VK10.vkDestroyDescriptorPool(device, pool, null);
            if (createdLayouts != null) {
                for (long value : createdLayouts.values()) VK10.vkDestroyDescriptorSetLayout(device, value, null);
            }
        }
    }

    void update(BindingSet bindings, VulkanGenericResourceRegistry resources, VulkanGenericSamplerCache samplers) {
        requireOpen();
        BindingSet checked = Objects.requireNonNull(bindings, "bindings");
        if (!checked.layout().entries().equals(layout.entries())) {
            throw new IllegalArgumentException("binding set layout does not match descriptor bank");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(layout.entries().size(), stack);
            int writeIndex = 0;
            for (BindingLayoutEntry entry : layout.entries()) {
                List<BindingSet.Value> values = checked.values().get(entry.key());
                VkWriteDescriptorSet write = writes.get(writeIndex++)
                        .sType$Default().dstSet(set(entry.key().group())).dstBinding(entry.key().binding())
                        .dstArrayElement(0).descriptorType(descriptorType(entry));
                write.descriptorCount(values.size());
                if (isBuffer(entry.type())) {
                    VkDescriptorBufferInfo.Buffer infos = VkDescriptorBufferInfo.calloc(values.size(), stack);
                    for (int index = 0; index < values.size(); index++) {
                        BindingSet.BufferValue value = (BindingSet.BufferValue) values.get(index);
                        VulkanGenericResourceRegistry.BufferRecord record = resources.requireBuffer(value.buffer());
                        resources.requireReadable(record);
                        infos.get(index).buffer(record.buffer().buffer())
                                .offset(value.range().offsetBytes()).range(value.range().lengthBytes());
                    }
                    write.pBufferInfo(infos);
                } else if (isTexture(entry.type())) {
                    VkDescriptorImageInfo.Buffer infos = VkDescriptorImageInfo.calloc(values.size(), stack);
                    for (int index = 0; index < values.size(); index++) {
                        BindingSet.TextureValue value = (BindingSet.TextureValue) values.get(index);
                        long view = resources.requireTextureView(value.view());
                        int imageLayout = entry.type() == BindingType.SAMPLED_TEXTURE
                                ? VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : VK10.VK_IMAGE_LAYOUT_GENERAL;
                        infos.get(index).sampler(VK10.VK_NULL_HANDLE).imageView(view).imageLayout(imageLayout);
                    }
                    write.pImageInfo(infos);
                } else {
                    VkDescriptorImageInfo.Buffer infos = VkDescriptorImageInfo.calloc(values.size(), stack);
                    for (int index = 0; index < values.size(); index++) {
                        BindingSet.SamplerValue value = (BindingSet.SamplerValue) values.get(index);
                        infos.get(index).sampler(samplers.require(value.sampler()))
                                .imageView(VK10.VK_NULL_HANDLE).imageLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
                    }
                    write.pImageInfo(infos);
                }
            }
            VK10.vkUpdateDescriptorSets(device, writes, null);
        }
    }

    long layout(int group) {
        requireOpen();
        Long value = layoutsByGroup.get(group);
        if (value == null) throw new IllegalArgumentException("binding group is not declared: " + group);
        return value;
    }

    long set(int group) {
        requireOpen();
        Long value = setsByGroup.get(group);
        if (value == null) throw new IllegalArgumentException("binding group is not declared: " + group);
        return value;
    }

    List<Integer> groups() { return List.copyOf(setsByGroup.keySet()); }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        VK10.vkDestroyDescriptorPool(device, pool, null);
        for (long value : layoutsByGroup.values()) VK10.vkDestroyDescriptorSetLayout(device, value, null);
    }

    private static Map<Integer, List<BindingLayoutEntry>> groups(BindingLayout layout) {
        Map<Integer, List<BindingLayoutEntry>> result = new LinkedHashMap<>();
        int maximumGroup = layout.entries().stream().mapToInt(entry -> entry.key().group()).max().orElse(-1);
        for (int group = 0; group <= maximumGroup; group++) result.put(group, new ArrayList<>());
        for (BindingLayoutEntry entry : layout.entries()) {
            result.get(entry.key().group()).add(entry);
        }
        return result;
    }

    private static long createLayout(MemoryStack stack, VkDevice device, List<BindingLayoutEntry> entries) {
        VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(entries.size(), stack);
        for (int index = 0; index < entries.size(); index++) {
            BindingLayoutEntry entry = entries.get(index);
            bindings.get(index).binding(entry.key().binding()).descriptorType(descriptorType(entry))
                    .descriptorCount(entry.arrayCount()).stageFlags(stageFlags(entry.visibleStages()));
        }
        VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType$Default().pBindings(bindings);
        LongBuffer output = stack.longs(VK10.VK_NULL_HANDLE);
        VulkanFailures.check(VK10.vkCreateDescriptorSetLayout(device, info, null, output),
                "vkCreateDescriptorSetLayout.generic");
        return output.get(0);
    }

    private static long createPool(MemoryStack stack, VkDevice device, BindingLayout layout) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (BindingLayoutEntry entry : layout.entries()) {
            counts.merge(descriptorType(entry), entry.arrayCount(), Math::addExact);
        }
        VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(counts.size(), stack);
        int index = 0;
        for (Map.Entry<Integer, Integer> count : counts.entrySet()) {
            sizes.get(index++).type(count.getKey()).descriptorCount(count.getValue());
        }
        VkDescriptorPoolCreateInfo info = VkDescriptorPoolCreateInfo.calloc(stack)
                .sType$Default().maxSets(groups(layout).size()).pPoolSizes(sizes);
        LongBuffer output = stack.longs(VK10.VK_NULL_HANDLE);
        VulkanFailures.check(VK10.vkCreateDescriptorPool(device, info, null, output), "vkCreateDescriptorPool.generic");
        return output.get(0);
    }

    private static int descriptorType(BindingLayoutEntry entry) {
        return switch (entry.type()) {
            case UNIFORM_BUFFER -> entry.dynamicOffset() ? VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC : VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
            case READ_ONLY_STORAGE_BUFFER -> entry.dynamicOffset() ? VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC : VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            case READ_WRITE_STORAGE_BUFFER -> entry.dynamicOffset() ? VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC : VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            case SAMPLED_TEXTURE -> VK10.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
            case READ_ONLY_STORAGE_TEXTURE, READ_WRITE_STORAGE_TEXTURE -> VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            case SAMPLER, COMPARISON_SAMPLER -> VK10.VK_DESCRIPTOR_TYPE_SAMPLER;
        };
    }

    private static boolean isBuffer(BindingType type) {
        return type == BindingType.UNIFORM_BUFFER || type == BindingType.READ_ONLY_STORAGE_BUFFER
                || type == BindingType.READ_WRITE_STORAGE_BUFFER;
    }

    private static boolean isTexture(BindingType type) {
        return type == BindingType.SAMPLED_TEXTURE || type == BindingType.READ_ONLY_STORAGE_TEXTURE
                || type == BindingType.READ_WRITE_STORAGE_TEXTURE;
    }

    private static int stageFlags(java.util.Set<ShaderStage> stages) {
        int result = 0;
        for (ShaderStage stage : stages) {
            result |= switch (stage) {
                case VERTEX -> VK10.VK_SHADER_STAGE_VERTEX_BIT;
                case TESSELLATION_CONTROL -> VK10.VK_SHADER_STAGE_TESSELLATION_CONTROL_BIT;
                case TESSELLATION_EVALUATION -> VK10.VK_SHADER_STAGE_TESSELLATION_EVALUATION_BIT;
                case GEOMETRY -> VK10.VK_SHADER_STAGE_GEOMETRY_BIT;
                case FRAGMENT -> VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
                case COMPUTE -> VK10.VK_SHADER_STAGE_COMPUTE_BIT;
                case RAY_GENERATION -> org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR;
                case RAY_MISS -> org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR;
                case RAY_CLOSEST_HIT -> org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR;
                case RAY_ANY_HIT -> org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR;
                case RAY_INTERSECTION -> org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_STAGE_INTERSECTION_BIT_KHR;
                case CALLABLE -> org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_STAGE_CALLABLE_BIT_KHR;
            };
        }
        return result;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("generic descriptor set bank is closed");
    }
}
