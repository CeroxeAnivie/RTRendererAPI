package top.ceroxe.mcvulkanrt.renderer;

public enum RendererState {
    NEW,
    DISCOVERY,
    READY_FOR_AUDIT,
    VULKAN_CAPABILITY_PROBE,
    VULKAN_RT_CAPABLE,
    DISABLED_UNSUPPORTED,
    DISABLED_BACKEND_FAILURE,
    ACTIVE,
    CLOSED
}
