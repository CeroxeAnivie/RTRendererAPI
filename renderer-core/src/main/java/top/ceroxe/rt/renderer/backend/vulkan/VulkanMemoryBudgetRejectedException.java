package top.ceroxe.rt.renderer.backend.vulkan;

import java.util.Objects;

/**
 * Internal rollback-safe signal raised before a GPUScene allocation crosses its memory budget.
 */
final class VulkanMemoryBudgetRejectedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    VulkanMemoryBudgetRejectedException(String message) {
        super(Objects.requireNonNull(message, "message"));
    }
}
