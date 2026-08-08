package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Immutable result of attempting one previously prepared feature plan.
 *
 * @param planId identifier of the plan that was attempted
 * @param generation controller generation observed for the result
 * @param outcome exact application outcome
 * @param effectiveProfile profile that is actually effective after the attempt
 * @param reason bounded human-readable outcome explanation
 */
public record RendererFeatureApplyResult(
        long planId,
        long generation,
        Outcome outcome,
        RendererFeatureProfile effectiveProfile,
        String reason
) {
    /** Validates truthful transition evidence. */
    public RendererFeatureApplyResult {
        if (planId <= 0L) throw new IllegalArgumentException("planId must be positive");
        if (generation < 0L) throw new IllegalArgumentException("generation must not be negative");
        outcome = Objects.requireNonNull(outcome, "outcome");
        effectiveProfile = Objects.requireNonNull(effectiveProfile, "effectiveProfile");
        reason = requireText(reason, "reason");
    }

    /** Exhaustive application outcome. Only {@link #APPLIED} proves a changed profile committed. */
    public enum Outcome {
        /** The target profile was committed. */
        APPLIED,
        /** The target already matched the effective profile. */
        UNCHANGED,
        /** The plan no longer matches the controller generation or identity. */
        STALE_PLAN,
        /** The frame ring must drain before retrying the same plan. */
        RETRY_AFTER_FRAME_DRAIN,
        /** A swapchain rebuild is required. */
        REQUIRES_SWAPCHAIN_REBUILD,
        /** A pipeline rebuild is required. */
        REQUIRES_PIPELINE_REBUILD,
        /** A scene rebuild is required. */
        REQUIRES_SCENE_REBUILD,
        /** A renderer rebuild is required. */
        REQUIRES_RENDERER_REBUILD,
        /** The transition was rejected without changing the effective profile. */
        REJECTED
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) throw new IllegalArgumentException(label + " must not be blank");
        return checked;
    }
}
