package top.ceroxe.rt.renderer;

import java.util.Objects;

/**
 * Typed scalar payload for one frame-causality join event.
 *
 * @param relatedRevision related lifecycle revision, or {@code -1} when unavailable
 * @param eventValue      event-specific scalar value
 * @param reason          typed reason associated with the event
 */
public record RendererCausalityEvidence(
        long relatedRevision,
        long eventValue,
        RtCausalitySink.Reason reason
) {
    /**
     * Validates scalar bounds and requires a typed reason.
     */
    public RendererCausalityEvidence {
        if (relatedRevision < -1L) {
            throw new IllegalArgumentException("relatedRevision must be -1 or greater");
        }
        reason = Objects.requireNonNull(reason, "reason");
    }
}
