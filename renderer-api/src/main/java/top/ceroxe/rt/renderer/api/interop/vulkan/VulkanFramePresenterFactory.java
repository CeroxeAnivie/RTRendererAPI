package top.ceroxe.rt.renderer.api.interop.vulkan;

/**
 * Renderer-bound expert extension that creates a presenter on the renderer's physical GPU.
 * Applications normally use {@link VulkanFramePresenter#open} rather than requesting this
 * factory directly.
 */
public interface VulkanFramePresenterFactory {
    /**
     * Opens a thread-affine native presenter owned by the caller.
     *
     * @param configuration immutable native window and swapchain policy
     * @return newly owned presenter
     */
    VulkanFramePresenter openPresenter(VulkanFramePresenterConfig configuration);
}
