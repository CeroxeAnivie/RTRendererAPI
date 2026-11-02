package top.ceroxe.rt.renderer.backend.vulkan;

/** A valid source texture is still borrowed by a submitted composition consumer. */
final class VulkanCompositionSourceBusyException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    VulkanCompositionSourceBusyException(String message) {
        super(message);
    }
}
