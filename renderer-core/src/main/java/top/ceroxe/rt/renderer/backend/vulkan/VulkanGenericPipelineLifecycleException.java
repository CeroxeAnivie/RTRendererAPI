package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.CommandExecutionEvidence;

/** Typed admission failure, before native submission or ownership mutation. */
final class VulkanGenericPipelineLifecycleException extends IllegalArgumentException {
    private final CommandExecutionEvidence.Reason reason;

    VulkanGenericPipelineLifecycleException(CommandExecutionEvidence.Reason reason, String detail) {
        super(detail);
        this.reason = reason;
    }

    CommandExecutionEvidence.Reason reason() { return reason; }
}
