package top.ceroxe.rt.renderer;

/**
 * Renderer lifecycle state exposed to diagnostics and integrations.
 */
public enum RendererState {
    /**
     * Constructed but not initialized.
     */
    NEW,
    /**
     * Discovering runtime capabilities.
     */
    DISCOVERY,
    /**
     * Discovery completed and ready for capability audit.
     */
    READY_FOR_AUDIT,
    /**
     * Vulkan ray-tracing capability probing is active.
     */
    VULKAN_CAPABILITY_PROBE,
    /**
     * Required Vulkan ray-tracing capability is available.
     */
    VULKAN_RT_CAPABLE,
    /**
     * Disabled because required hardware capability is unavailable.
     */
    DISABLED_UNSUPPORTED,
    /**
     * Disabled after backend initialization or runtime failure.
     */
    DISABLED_BACKEND_FAILURE,
    /**
     * Actively accepting frames.
     */
    ACTIVE,
    /**
     * Renderer and owned resources are closed.
     */
    CLOSED
}
