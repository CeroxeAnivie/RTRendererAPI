package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Fence-backed retirement milestone attached to the retirement command's execution evidence. */
public record RayTracingPipelineRetirementEvidence(
        RayTracingPipelineHandle handle, long transactionSequence, Outcome outcome) {
    /** Native resources remain owned while pending; RETIRED proves successful release. */
    public enum Outcome { PENDING, RETIRED }

    /** Validates an exact pipeline generation and non-negative transaction sequence. */
    public RayTracingPipelineRetirementEvidence {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(outcome, "outcome");
        if (transactionSequence < 0) throw new IllegalArgumentException("negative retirement sequence");
    }
}
