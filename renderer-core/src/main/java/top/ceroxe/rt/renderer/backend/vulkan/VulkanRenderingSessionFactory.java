package top.ceroxe.rt.renderer.backend.vulkan;

/**
 * Opens a fresh, independently owned Vulkan session for initial startup or device-loss recovery.
 */
@FunctionalInterface
interface VulkanRenderingSessionFactory {
    VulkanRenderingSession open();
}
