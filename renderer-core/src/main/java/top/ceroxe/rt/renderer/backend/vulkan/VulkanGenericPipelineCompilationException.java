package top.ceroxe.rt.renderer.backend.vulkan;

/** Structured admission failure for native generic pipeline creation. */
final class VulkanGenericPipelineCompilationException extends RuntimeException {
    VulkanGenericPipelineCompilationException(String message) {
        super(message);
    }

    VulkanGenericPipelineCompilationException(String message, Throwable cause) {
        super(message, cause);
    }
}
