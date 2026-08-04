package top.ceroxe.rt.renderer.feature;

import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;

import java.util.function.Predicate;

/**
 * Exact Vulkan 1.2 feature fields that an optional pre-device provider may request.
 *
 * <p>The names deliberately match the Vulkan structure fields used by Streamline. Keeping the
 * mapping typed prevents a misspelled native feature name from silently becoming a device created
 * without the feature that its provider requires.</p>
 */
public enum Vulkan12Feature {
    /** Maps {@code samplerMirrorClampToEdge}. */
    SAMPLER_MIRROR_CLAMP_TO_EDGE("samplerMirrorClampToEdge", VkPhysicalDeviceVulkan12Features::samplerMirrorClampToEdge, features -> features.samplerMirrorClampToEdge(true)),
    /** Maps {@code drawIndirectCount}. */
    DRAW_INDIRECT_COUNT("drawIndirectCount", VkPhysicalDeviceVulkan12Features::drawIndirectCount, features -> features.drawIndirectCount(true)),
    /** Maps {@code storageBuffer8BitAccess}. */
    STORAGE_BUFFER_8_BIT_ACCESS("storageBuffer8BitAccess", VkPhysicalDeviceVulkan12Features::storageBuffer8BitAccess, features -> features.storageBuffer8BitAccess(true)),
    /** Maps {@code uniformAndStorageBuffer8BitAccess}. */
    UNIFORM_AND_STORAGE_BUFFER_8_BIT_ACCESS("uniformAndStorageBuffer8BitAccess", VkPhysicalDeviceVulkan12Features::uniformAndStorageBuffer8BitAccess, features -> features.uniformAndStorageBuffer8BitAccess(true)),
    /** Maps {@code storagePushConstant8}. */
    STORAGE_PUSH_CONSTANT_8("storagePushConstant8", VkPhysicalDeviceVulkan12Features::storagePushConstant8, features -> features.storagePushConstant8(true)),
    /** Maps {@code shaderBufferInt64Atomics}. */
    SHADER_BUFFER_INT64_ATOMICS("shaderBufferInt64Atomics", VkPhysicalDeviceVulkan12Features::shaderBufferInt64Atomics, features -> features.shaderBufferInt64Atomics(true)),
    /** Maps {@code shaderSharedInt64Atomics}. */
    SHADER_SHARED_INT64_ATOMICS("shaderSharedInt64Atomics", VkPhysicalDeviceVulkan12Features::shaderSharedInt64Atomics, features -> features.shaderSharedInt64Atomics(true)),
    /** Maps {@code shaderFloat16}. */
    SHADER_FLOAT16("shaderFloat16", VkPhysicalDeviceVulkan12Features::shaderFloat16, features -> features.shaderFloat16(true)),
    /** Maps {@code shaderInt8}. */
    SHADER_INT8("shaderInt8", VkPhysicalDeviceVulkan12Features::shaderInt8, features -> features.shaderInt8(true)),
    /** Maps {@code descriptorIndexing}. */
    DESCRIPTOR_INDEXING("descriptorIndexing", VkPhysicalDeviceVulkan12Features::descriptorIndexing, features -> features.descriptorIndexing(true)),
    /** Maps {@code shaderInputAttachmentArrayDynamicIndexing}. */
    SHADER_INPUT_ATTACHMENT_ARRAY_DYNAMIC_INDEXING("shaderInputAttachmentArrayDynamicIndexing", VkPhysicalDeviceVulkan12Features::shaderInputAttachmentArrayDynamicIndexing, features -> features.shaderInputAttachmentArrayDynamicIndexing(true)),
    /** Maps {@code shaderUniformTexelBufferArrayDynamicIndexing}. */
    SHADER_UNIFORM_TEXEL_BUFFER_ARRAY_DYNAMIC_INDEXING("shaderUniformTexelBufferArrayDynamicIndexing", VkPhysicalDeviceVulkan12Features::shaderUniformTexelBufferArrayDynamicIndexing, features -> features.shaderUniformTexelBufferArrayDynamicIndexing(true)),
    /** Maps {@code shaderStorageTexelBufferArrayDynamicIndexing}. */
    SHADER_STORAGE_TEXEL_BUFFER_ARRAY_DYNAMIC_INDEXING("shaderStorageTexelBufferArrayDynamicIndexing", VkPhysicalDeviceVulkan12Features::shaderStorageTexelBufferArrayDynamicIndexing, features -> features.shaderStorageTexelBufferArrayDynamicIndexing(true)),
    /** Maps {@code shaderUniformBufferArrayNonUniformIndexing}. */
    SHADER_UNIFORM_BUFFER_ARRAY_NON_UNIFORM_INDEXING("shaderUniformBufferArrayNonUniformIndexing", VkPhysicalDeviceVulkan12Features::shaderUniformBufferArrayNonUniformIndexing, features -> features.shaderUniformBufferArrayNonUniformIndexing(true)),
    /** Maps {@code shaderSampledImageArrayNonUniformIndexing}. */
    SHADER_SAMPLED_IMAGE_ARRAY_NON_UNIFORM_INDEXING("shaderSampledImageArrayNonUniformIndexing", VkPhysicalDeviceVulkan12Features::shaderSampledImageArrayNonUniformIndexing, features -> features.shaderSampledImageArrayNonUniformIndexing(true)),
    /** Maps {@code shaderStorageBufferArrayNonUniformIndexing}. */
    SHADER_STORAGE_BUFFER_ARRAY_NON_UNIFORM_INDEXING("shaderStorageBufferArrayNonUniformIndexing", VkPhysicalDeviceVulkan12Features::shaderStorageBufferArrayNonUniformIndexing, features -> features.shaderStorageBufferArrayNonUniformIndexing(true)),
    /** Maps {@code shaderStorageImageArrayNonUniformIndexing}. */
    SHADER_STORAGE_IMAGE_ARRAY_NON_UNIFORM_INDEXING("shaderStorageImageArrayNonUniformIndexing", VkPhysicalDeviceVulkan12Features::shaderStorageImageArrayNonUniformIndexing, features -> features.shaderStorageImageArrayNonUniformIndexing(true)),
    /** Maps {@code shaderInputAttachmentArrayNonUniformIndexing}. */
    SHADER_INPUT_ATTACHMENT_ARRAY_NON_UNIFORM_INDEXING("shaderInputAttachmentArrayNonUniformIndexing", VkPhysicalDeviceVulkan12Features::shaderInputAttachmentArrayNonUniformIndexing, features -> features.shaderInputAttachmentArrayNonUniformIndexing(true)),
    /** Maps {@code shaderUniformTexelBufferArrayNonUniformIndexing}. */
    SHADER_UNIFORM_TEXEL_BUFFER_ARRAY_NON_UNIFORM_INDEXING("shaderUniformTexelBufferArrayNonUniformIndexing", VkPhysicalDeviceVulkan12Features::shaderUniformTexelBufferArrayNonUniformIndexing, features -> features.shaderUniformTexelBufferArrayNonUniformIndexing(true)),
    /** Maps {@code shaderStorageTexelBufferArrayNonUniformIndexing}. */
    SHADER_STORAGE_TEXEL_BUFFER_ARRAY_NON_UNIFORM_INDEXING("shaderStorageTexelBufferArrayNonUniformIndexing", VkPhysicalDeviceVulkan12Features::shaderStorageTexelBufferArrayNonUniformIndexing, features -> features.shaderStorageTexelBufferArrayNonUniformIndexing(true)),
    /** Maps {@code descriptorBindingUniformBufferUpdateAfterBind}. */
    DESCRIPTOR_BINDING_UNIFORM_BUFFER_UPDATE_AFTER_BIND("descriptorBindingUniformBufferUpdateAfterBind", VkPhysicalDeviceVulkan12Features::descriptorBindingUniformBufferUpdateAfterBind, features -> features.descriptorBindingUniformBufferUpdateAfterBind(true)),
    /** Maps {@code descriptorBindingSampledImageUpdateAfterBind}. */
    DESCRIPTOR_BINDING_SAMPLED_IMAGE_UPDATE_AFTER_BIND("descriptorBindingSampledImageUpdateAfterBind", VkPhysicalDeviceVulkan12Features::descriptorBindingSampledImageUpdateAfterBind, features -> features.descriptorBindingSampledImageUpdateAfterBind(true)),
    /** Maps {@code descriptorBindingStorageImageUpdateAfterBind}. */
    DESCRIPTOR_BINDING_STORAGE_IMAGE_UPDATE_AFTER_BIND("descriptorBindingStorageImageUpdateAfterBind", VkPhysicalDeviceVulkan12Features::descriptorBindingStorageImageUpdateAfterBind, features -> features.descriptorBindingStorageImageUpdateAfterBind(true)),
    /** Maps {@code descriptorBindingStorageBufferUpdateAfterBind}. */
    DESCRIPTOR_BINDING_STORAGE_BUFFER_UPDATE_AFTER_BIND("descriptorBindingStorageBufferUpdateAfterBind", VkPhysicalDeviceVulkan12Features::descriptorBindingStorageBufferUpdateAfterBind, features -> features.descriptorBindingStorageBufferUpdateAfterBind(true)),
    /** Maps {@code descriptorBindingUniformTexelBufferUpdateAfterBind}. */
    DESCRIPTOR_BINDING_UNIFORM_TEXEL_BUFFER_UPDATE_AFTER_BIND("descriptorBindingUniformTexelBufferUpdateAfterBind", VkPhysicalDeviceVulkan12Features::descriptorBindingUniformTexelBufferUpdateAfterBind, features -> features.descriptorBindingUniformTexelBufferUpdateAfterBind(true)),
    /** Maps {@code descriptorBindingStorageTexelBufferUpdateAfterBind}. */
    DESCRIPTOR_BINDING_STORAGE_TEXEL_BUFFER_UPDATE_AFTER_BIND("descriptorBindingStorageTexelBufferUpdateAfterBind", VkPhysicalDeviceVulkan12Features::descriptorBindingStorageTexelBufferUpdateAfterBind, features -> features.descriptorBindingStorageTexelBufferUpdateAfterBind(true)),
    /** Maps {@code descriptorBindingUpdateUnusedWhilePending}. */
    DESCRIPTOR_BINDING_UPDATE_UNUSED_WHILE_PENDING("descriptorBindingUpdateUnusedWhilePending", VkPhysicalDeviceVulkan12Features::descriptorBindingUpdateUnusedWhilePending, features -> features.descriptorBindingUpdateUnusedWhilePending(true)),
    /** Maps {@code descriptorBindingPartiallyBound}. */
    DESCRIPTOR_BINDING_PARTIALLY_BOUND("descriptorBindingPartiallyBound", VkPhysicalDeviceVulkan12Features::descriptorBindingPartiallyBound, features -> features.descriptorBindingPartiallyBound(true)),
    /** Maps {@code descriptorBindingVariableDescriptorCount}. */
    DESCRIPTOR_BINDING_VARIABLE_DESCRIPTOR_COUNT("descriptorBindingVariableDescriptorCount", VkPhysicalDeviceVulkan12Features::descriptorBindingVariableDescriptorCount, features -> features.descriptorBindingVariableDescriptorCount(true)),
    /** Maps {@code runtimeDescriptorArray}. */
    RUNTIME_DESCRIPTOR_ARRAY("runtimeDescriptorArray", VkPhysicalDeviceVulkan12Features::runtimeDescriptorArray, features -> features.runtimeDescriptorArray(true)),
    /** Maps {@code samplerFilterMinmax}. */
    SAMPLER_FILTER_MINMAX("samplerFilterMinmax", VkPhysicalDeviceVulkan12Features::samplerFilterMinmax, features -> features.samplerFilterMinmax(true)),
    /** Maps {@code scalarBlockLayout}. */
    SCALAR_BLOCK_LAYOUT("scalarBlockLayout", VkPhysicalDeviceVulkan12Features::scalarBlockLayout, features -> features.scalarBlockLayout(true)),
    /** Maps {@code imagelessFramebuffer}. */
    IMAGELESS_FRAMEBUFFER("imagelessFramebuffer", VkPhysicalDeviceVulkan12Features::imagelessFramebuffer, features -> features.imagelessFramebuffer(true)),
    /** Maps {@code uniformBufferStandardLayout}. */
    UNIFORM_BUFFER_STANDARD_LAYOUT("uniformBufferStandardLayout", VkPhysicalDeviceVulkan12Features::uniformBufferStandardLayout, features -> features.uniformBufferStandardLayout(true)),
    /** Maps {@code shaderSubgroupExtendedTypes}. */
    SHADER_SUBGROUP_EXTENDED_TYPES("shaderSubgroupExtendedTypes", VkPhysicalDeviceVulkan12Features::shaderSubgroupExtendedTypes, features -> features.shaderSubgroupExtendedTypes(true)),
    /** Maps {@code separateDepthStencilLayouts}. */
    SEPARATE_DEPTH_STENCIL_LAYOUTS("separateDepthStencilLayouts", VkPhysicalDeviceVulkan12Features::separateDepthStencilLayouts, features -> features.separateDepthStencilLayouts(true)),
    /** Maps {@code hostQueryReset}. */
    HOST_QUERY_RESET("hostQueryReset", VkPhysicalDeviceVulkan12Features::hostQueryReset, features -> features.hostQueryReset(true)),
    /** Maps {@code timelineSemaphore}. */
    TIMELINE_SEMAPHORE("timelineSemaphore", VkPhysicalDeviceVulkan12Features::timelineSemaphore, features -> features.timelineSemaphore(true)),
    /** Maps {@code bufferDeviceAddress}. */
    BUFFER_DEVICE_ADDRESS("bufferDeviceAddress", VkPhysicalDeviceVulkan12Features::bufferDeviceAddress, features -> features.bufferDeviceAddress(true)),
    /** Maps {@code bufferDeviceAddressCaptureReplay}. */
    BUFFER_DEVICE_ADDRESS_CAPTURE_REPLAY("bufferDeviceAddressCaptureReplay", VkPhysicalDeviceVulkan12Features::bufferDeviceAddressCaptureReplay, features -> features.bufferDeviceAddressCaptureReplay(true)),
    /** Maps {@code bufferDeviceAddressMultiDevice}. */
    BUFFER_DEVICE_ADDRESS_MULTI_DEVICE("bufferDeviceAddressMultiDevice", VkPhysicalDeviceVulkan12Features::bufferDeviceAddressMultiDevice, features -> features.bufferDeviceAddressMultiDevice(true)),
    /** Maps {@code vulkanMemoryModel}. */
    VULKAN_MEMORY_MODEL("vulkanMemoryModel", VkPhysicalDeviceVulkan12Features::vulkanMemoryModel, features -> features.vulkanMemoryModel(true)),
    /** Maps {@code vulkanMemoryModelDeviceScope}. */
    VULKAN_MEMORY_MODEL_DEVICE_SCOPE("vulkanMemoryModelDeviceScope", VkPhysicalDeviceVulkan12Features::vulkanMemoryModelDeviceScope, features -> features.vulkanMemoryModelDeviceScope(true)),
    /** Maps {@code vulkanMemoryModelAvailabilityVisibilityChains}. */
    VULKAN_MEMORY_MODEL_AVAILABILITY_VISIBILITY_CHAINS("vulkanMemoryModelAvailabilityVisibilityChains", VkPhysicalDeviceVulkan12Features::vulkanMemoryModelAvailabilityVisibilityChains, features -> features.vulkanMemoryModelAvailabilityVisibilityChains(true)),
    /** Maps {@code shaderOutputViewportIndex}. */
    SHADER_OUTPUT_VIEWPORT_INDEX("shaderOutputViewportIndex", VkPhysicalDeviceVulkan12Features::shaderOutputViewportIndex, features -> features.shaderOutputViewportIndex(true)),
    /** Maps {@code shaderOutputLayer}. */
    SHADER_OUTPUT_LAYER("shaderOutputLayer", VkPhysicalDeviceVulkan12Features::shaderOutputLayer, features -> features.shaderOutputLayer(true)),
    /** Maps {@code subgroupBroadcastDynamicId}. */
    SUBGROUP_BROADCAST_DYNAMIC_ID("subgroupBroadcastDynamicId", VkPhysicalDeviceVulkan12Features::subgroupBroadcastDynamicId, features -> features.subgroupBroadcastDynamicId(true));

    private final String streamlineName;
    private final Predicate<VkPhysicalDeviceVulkan12Features> supported;
    private final java.util.function.Consumer<VkPhysicalDeviceVulkan12Features> enable;

    Vulkan12Feature(
            String streamlineName,
            Predicate<VkPhysicalDeviceVulkan12Features> supported,
            java.util.function.Consumer<VkPhysicalDeviceVulkan12Features> enable
    ) {
        this.streamlineName = streamlineName;
        this.supported = supported;
        this.enable = enable;
    }

    /**
     * Returns the exact Streamline/Vulkan structure field name.
     *
     * @return exact {@code VkPhysicalDeviceVulkan12Features} field name
     */
    public String streamlineName() { return streamlineName; }

    /**
     * Resolves an SDK-provided Vulkan 1.2 field name without accepting unknown fields.
     *
     * @param value exact non-null structure field name
     * @return typed feature mapping
     */
    public static Vulkan12Feature fromStreamlineName(String value) {
        String checked = java.util.Objects.requireNonNull(value, "value");
        for (Vulkan12Feature feature : values()) if (feature.streamlineName.equals(checked)) return feature;
        throw new IllegalArgumentException("unsupported Vulkan 1.2 feature requested by provider: " + checked);
    }

    /**
     * Returns whether the physical device reports this exact field.
     *
     * @param features queried Vulkan 1.2 feature structure
     * @return {@code true} when this field is supported
     */
    public boolean supportedBy(VkPhysicalDeviceVulkan12Features features) { return supported.test(features); }

    /**
     * Enables only this field in a feature structure already chained to {@code VkDeviceCreateInfo}.
     *
     * @param features mutable Vulkan 1.2 feature structure used for device creation
     */
    public void enable(VkPhysicalDeviceVulkan12Features features) { enable.accept(features); }
}
