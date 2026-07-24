package top.ceroxe.mcvulkanrt.renderer;

import java.util.Objects;

/** Typed scalar payload for one frame-causality join event. */
public record RendererCausalityEvidence(
        long relatedRevision,
        long eventValue,
        RtCausalitySink.Reason reason
) {
    public RendererCausalityEvidence {
        if (relatedRevision < -1L) {
            throw new IllegalArgumentException("relatedRevision must be -1 or greater");
        }
        reason = Objects.requireNonNull(reason, "reason");
    }
}
