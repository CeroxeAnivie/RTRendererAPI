package top.ceroxe.rt.renderer.feature;

import org.lwjgl.vulkan.VkPhysicalDeviceVulkan13Features;

import java.util.function.Consumer;
import java.util.function.Predicate;

/** Typed Vulkan 1.3 feature fields accepted from optional pre-device providers. */
public enum Vulkan13Feature {
    /** Maps {@code robustImageAccess}. */
    ROBUST_IMAGE_ACCESS("robustImageAccess", VkPhysicalDeviceVulkan13Features::robustImageAccess, features -> features.robustImageAccess(true)),
    /** Maps {@code inlineUniformBlock}. */
    INLINE_UNIFORM_BLOCK("inlineUniformBlock", VkPhysicalDeviceVulkan13Features::inlineUniformBlock, features -> features.inlineUniformBlock(true)),
    /** Maps {@code descriptorBindingInlineUniformBlockUpdateAfterBind}. */
    DESCRIPTOR_BINDING_INLINE_UNIFORM_BLOCK_UPDATE_AFTER_BIND("descriptorBindingInlineUniformBlockUpdateAfterBind", VkPhysicalDeviceVulkan13Features::descriptorBindingInlineUniformBlockUpdateAfterBind, features -> features.descriptorBindingInlineUniformBlockUpdateAfterBind(true)),
    /** Maps {@code pipelineCreationCacheControl}. */
    PIPELINE_CREATION_CACHE_CONTROL("pipelineCreationCacheControl", VkPhysicalDeviceVulkan13Features::pipelineCreationCacheControl, features -> features.pipelineCreationCacheControl(true)),
    /** Maps {@code privateData}. */
    PRIVATE_DATA("privateData", VkPhysicalDeviceVulkan13Features::privateData, features -> features.privateData(true)),
    /** Maps {@code shaderDemoteToHelperInvocation}. */
    SHADER_DEMOTE_TO_HELPER_INVOCATION("shaderDemoteToHelperInvocation", VkPhysicalDeviceVulkan13Features::shaderDemoteToHelperInvocation, features -> features.shaderDemoteToHelperInvocation(true)),
    /** Maps {@code shaderTerminateInvocation}. */
    SHADER_TERMINATE_INVOCATION("shaderTerminateInvocation", VkPhysicalDeviceVulkan13Features::shaderTerminateInvocation, features -> features.shaderTerminateInvocation(true)),
    /** Maps {@code subgroupSizeControl}. */
    SUBGROUP_SIZE_CONTROL("subgroupSizeControl", VkPhysicalDeviceVulkan13Features::subgroupSizeControl, features -> features.subgroupSizeControl(true)),
    /** Maps {@code computeFullSubgroups}. */
    COMPUTE_FULL_SUBGROUPS("computeFullSubgroups", VkPhysicalDeviceVulkan13Features::computeFullSubgroups, features -> features.computeFullSubgroups(true)),
    /** Maps {@code synchronization2}. */
    SYNCHRONIZATION_2("synchronization2", VkPhysicalDeviceVulkan13Features::synchronization2, features -> features.synchronization2(true)),
    /** Maps {@code textureCompressionASTC_HDR}. */
    TEXTURE_COMPRESSION_ASTC_HDR("textureCompressionASTC_HDR", VkPhysicalDeviceVulkan13Features::textureCompressionASTC_HDR, features -> features.textureCompressionASTC_HDR(true)),
    /** Maps {@code shaderZeroInitializeWorkgroupMemory}. */
    SHADER_ZERO_INITIALIZE_WORKGROUP_MEMORY("shaderZeroInitializeWorkgroupMemory", VkPhysicalDeviceVulkan13Features::shaderZeroInitializeWorkgroupMemory, features -> features.shaderZeroInitializeWorkgroupMemory(true)),
    /** Maps {@code dynamicRendering}. */
    DYNAMIC_RENDERING("dynamicRendering", VkPhysicalDeviceVulkan13Features::dynamicRendering, features -> features.dynamicRendering(true)),
    /** Maps {@code shaderIntegerDotProduct}. */
    SHADER_INTEGER_DOT_PRODUCT("shaderIntegerDotProduct", VkPhysicalDeviceVulkan13Features::shaderIntegerDotProduct, features -> features.shaderIntegerDotProduct(true)),
    /** Maps {@code maintenance4}. */
    MAINTENANCE_4("maintenance4", VkPhysicalDeviceVulkan13Features::maintenance4, features -> features.maintenance4(true));

    private final String streamlineName;
    private final Predicate<VkPhysicalDeviceVulkan13Features> supported;
    private final Consumer<VkPhysicalDeviceVulkan13Features> enable;

    Vulkan13Feature(
            String streamlineName,
            Predicate<VkPhysicalDeviceVulkan13Features> supported,
            Consumer<VkPhysicalDeviceVulkan13Features> enable
    ) {
        this.streamlineName = streamlineName;
        this.supported = supported;
        this.enable = enable;
    }

    /**
     * Returns the exact Streamline/Vulkan structure field name.
     *
     * @return exact {@code VkPhysicalDeviceVulkan13Features} field name
     */
    public String streamlineName() { return streamlineName; }

    /**
     * Resolves an SDK-provided Vulkan 1.3 field name without accepting unknown fields.
     *
     * @param value exact non-null structure field name
     * @return typed feature mapping
     */
    public static Vulkan13Feature fromStreamlineName(String value) {
        String checked = java.util.Objects.requireNonNull(value, "value");
        for (Vulkan13Feature feature : values()) if (feature.streamlineName.equals(checked)) return feature;
        throw new IllegalArgumentException("unsupported Vulkan 1.3 feature requested by provider: " + checked);
    }

    /**
     * Returns whether the physical device reports this exact field.
     *
     * @param features queried Vulkan 1.3 feature structure
     * @return {@code true} when this field is supported
     */
    public boolean supportedBy(VkPhysicalDeviceVulkan13Features features) { return supported.test(features); }

    /**
     * Enables only this field in a feature structure already chained to {@code VkDeviceCreateInfo}.
     *
     * @param features mutable Vulkan 1.3 feature structure used for device creation
     */
    public void enable(VkPhysicalDeviceVulkan13Features features) { enable.accept(features); }
}
