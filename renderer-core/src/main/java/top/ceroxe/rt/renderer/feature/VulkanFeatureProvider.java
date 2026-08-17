package top.ceroxe.rt.renderer.feature;

import top.ceroxe.rt.renderer.api.RendererConfig;

/**
 * Service-provider boundary for optional Vulkan rendering technologies.
 *
 * <p>Implementations belong in optional modules such as {@code renderer-nvidia}. They may load a
 * native bridge only when a requested feature is non-disabled; the core module remains usable when
 * the optional provider or its SDK is absent.</p>
 */
public interface VulkanFeatureProvider {
    /**
     * Returns a stable provider identifier.
     *
     * @return non-blank provider id
     */
    String id();

    /**
     * Returns provider priority; larger values win deterministic feature ownership ties.
     *
     * @return provider priority
     */
    default int priority() {
        return 0;
    }

    /**
     * Declares support and Vulkan extension requirements for the requested configuration.
     *
     * @param configuration immutable renderer configuration
     * @return non-null provider declaration
     */
    VulkanFeatureRequirements requirements(RendererConfig configuration);

    /**
     * Releases provider-owned state created while producing pre-device requirements when the
     * resulting plan will not be opened.
     *
     * <p>Implementations must be idempotent. A provider that keeps no planning state may retain
     * this no-op default; process-scoped SDK integrations use it to balance preflight ownership
     * when device bootstrap fails or another provider wins selection.</p>
     */
    default void discardPlan() {
    }

    /**
     * Opens a device-bound feature session after logical-device creation.
     *
     * @param context borrowed device-bound context
     * @return non-null session
     */
    VulkanFeatureSession open(VulkanFeatureOpenContext context);
}
