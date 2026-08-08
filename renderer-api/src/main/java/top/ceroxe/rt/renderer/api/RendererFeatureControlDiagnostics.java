package top.ceroxe.rt.renderer.api;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable observability snapshot for one renderer's feature-transition controller.
 *
 * @param generation current committed profile generation
 * @param effectiveProfile profile currently owned by the renderer
 * @param plannedTransitions number of plans issued by this controller
 * @param appliedTransitions number of changed profiles committed
 * @param rejectedTransitions number of terminal plans rejected by this controller
 * @param latestPlan most recently issued plan, when one exists
 * @param latestResult result belonging to the latest plan, when one is terminal or retriable
 */
public record RendererFeatureControlDiagnostics(
        long generation,
        RendererFeatureProfile effectiveProfile,
        long plannedTransitions,
        long appliedTransitions,
        long rejectedTransitions,
        Optional<RendererFeaturePlan> latestPlan,
        Optional<RendererFeatureApplyResult> latestResult
) {
    /** Validates monotonic counters and defensive optional ownership. */
    public RendererFeatureControlDiagnostics {
        if (generation < 0L || plannedTransitions < 0L || appliedTransitions < 0L
                || rejectedTransitions < 0L) {
            throw new IllegalArgumentException("feature-control counters must not be negative");
        }
        if (appliedTransitions > plannedTransitions) {
            throw new IllegalArgumentException("applied transitions exceed planned transitions");
        }
        effectiveProfile = Objects.requireNonNull(effectiveProfile, "effectiveProfile");
        latestPlan = Objects.requireNonNull(latestPlan, "latestPlan");
        latestResult = Objects.requireNonNull(latestResult, "latestResult");
        if (latestPlan.isPresent() && latestResult.isPresent()
                && latestPlan.get().id() != latestResult.get().planId()) {
            throw new IllegalArgumentException(
                    "latest feature result must belong to the latest feature plan"
            );
        }
        if (latestResult.isPresent()
                && !effectiveProfile.equals(latestResult.get().effectiveProfile())) {
            throw new IllegalArgumentException(
                    "latest feature result must report the controller effective profile"
            );
        }
    }
}
