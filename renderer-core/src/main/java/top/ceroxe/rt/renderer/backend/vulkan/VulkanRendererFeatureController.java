package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.renderer.api.RendererFeatureApplyResult;
import top.ceroxe.rt.renderer.api.RendererFeatureControlDiagnostics;
import top.ceroxe.rt.renderer.api.RendererFeatureController;
import top.ceroxe.rt.renderer.api.RendererFeaturePlan;
import top.ceroxe.rt.renderer.api.RendererFeatureProfile;

import java.util.Objects;
import java.util.Optional;

/** Generation and observability owner for the public feature-control extension. */
final class VulkanRendererFeatureController implements RendererFeatureController {
    interface Backend {
        VulkanRenderingSession.FeatureReconfigurationAssessment assess(
                RendererFeatureProfile source,
                RendererFeatureProfile target
        );

        VulkanRenderingSession.FeatureReconfigurationResult apply(RendererFeatureProfile target);
    }

    private final Backend backend;
    private RendererFeatureProfile effectiveProfile;
    private long generation;
    private long nextPlanId = 1L;
    private long plannedTransitions;
    private long appliedTransitions;
    private long rejectedTransitions;
    private RendererFeaturePlan latestPlan;
    private RendererFeatureApplyResult latestResult;

    VulkanRendererFeatureController(RendererFeatureProfile initialProfile, Backend backend) {
        effectiveProfile = Objects.requireNonNull(initialProfile, "initialProfile");
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    @Override
    public synchronized RendererFeatureProfile effectiveProfile() {
        return effectiveProfile;
    }

    @Override
    public synchronized RendererFeaturePlan plan(RendererFeatureProfile target) {
        RendererFeatureProfile checkedTarget = Objects.requireNonNull(target, "target");
        VulkanRenderingSession.FeatureReconfigurationAssessment assessment =
                effectiveProfile.equals(checkedTarget)
                        ? VulkanRenderingSession.FeatureReconfigurationAssessment.unchanged()
                        : Objects.requireNonNull(
                                backend.assess(effectiveProfile, checkedTarget),
                                "feature reconfiguration assessment"
                        );
        long id = nextPlanId;
        nextPlanId = Math.incrementExact(nextPlanId);
        plannedTransitions = Math.incrementExact(plannedTransitions);
        latestPlan = new RendererFeaturePlan(
                id,
                generation,
                effectiveProfile,
                checkedTarget,
                assessment.disposition(),
                assessment.boundary(),
                assessment.reason()
        );
        return latestPlan;
    }

    @Override
    public synchronized RendererFeatureApplyResult apply(RendererFeaturePlan plan) {
        RendererFeaturePlan checked = Objects.requireNonNull(plan, "plan");
        if (latestPlan != checked
                || checked.expectedGeneration() != generation
                || !checked.source().equals(effectiveProfile)
                || latestResult != null && latestResult.planId() == checked.id()
                && latestResult.outcome() != RendererFeatureApplyResult.Outcome.RETRY_AFTER_FRAME_DRAIN) {
            return new RendererFeatureApplyResult(
                    checked.id(), generation, RendererFeatureApplyResult.Outcome.STALE_PLAN,
                    effectiveProfile, "plan is stale, superseded, foreign, or already consumed"
            );
        }
        if (checked.disposition() == RendererFeaturePlan.Disposition.UNCHANGED) {
            latestResult = new RendererFeatureApplyResult(
                    checked.id(), generation, RendererFeatureApplyResult.Outcome.UNCHANGED,
                    effectiveProfile, "target profile already effective"
            );
            return latestResult;
        }
        if (checked.disposition() != RendererFeaturePlan.Disposition.APPLICABLE) {
            return reject(
                    checked.id(), rebuildOutcome(checked.disposition()), checked.reason()
            );
        }
        VulkanRenderingSession.FeatureReconfigurationResult result = Objects.requireNonNull(
                backend.apply(checked.target()), "feature reconfiguration result"
        );
        if (!result.applied()) {
            return reject(
                    checked.id(), RendererFeatureApplyResult.Outcome.RETRY_AFTER_FRAME_DRAIN,
                    result.reason()
            );
        }
        generation = Math.incrementExact(generation);
        appliedTransitions = Math.incrementExact(appliedTransitions);
        effectiveProfile = checked.target();
        latestResult = new RendererFeatureApplyResult(
                checked.id(), generation, RendererFeatureApplyResult.Outcome.APPLIED,
                effectiveProfile, result.reason()
        );
        return latestResult;
    }

    @Override
    public synchronized RendererFeatureControlDiagnostics featureControlDiagnostics() {
        return new RendererFeatureControlDiagnostics(
                generation,
                effectiveProfile,
                plannedTransitions,
                appliedTransitions,
                rejectedTransitions,
                Optional.ofNullable(latestPlan),
                Optional.ofNullable(latestResult)
        );
    }

    private RendererFeatureApplyResult reject(
            long planId,
            RendererFeatureApplyResult.Outcome outcome,
            String reason
    ) {
        // A drain retry is an in-flight plan outcome, not a terminal rejection. Foreign plans are
        // also excluded from this controller's counters so diagnostics remain a conservation law:
        // terminal outcomes never exceed plans issued by this controller.
        if (outcome != RendererFeatureApplyResult.Outcome.RETRY_AFTER_FRAME_DRAIN
                && latestPlan != null && latestPlan.id() == planId) {
            rejectedTransitions = Math.incrementExact(rejectedTransitions);
        }
        latestResult = new RendererFeatureApplyResult(
                planId, generation, outcome, effectiveProfile, reason
        );
        return latestResult;
    }

    private static RendererFeatureApplyResult.Outcome rebuildOutcome(
            RendererFeaturePlan.Disposition disposition
    ) {
        return switch (disposition) {
            case REQUIRES_SWAPCHAIN_REBUILD ->
                    RendererFeatureApplyResult.Outcome.REQUIRES_SWAPCHAIN_REBUILD;
            case REQUIRES_PIPELINE_REBUILD ->
                    RendererFeatureApplyResult.Outcome.REQUIRES_PIPELINE_REBUILD;
            case REQUIRES_SCENE_REBUILD ->
                    RendererFeatureApplyResult.Outcome.REQUIRES_SCENE_REBUILD;
            case REQUIRES_RENDERER_REBUILD ->
                    RendererFeatureApplyResult.Outcome.REQUIRES_RENDERER_REBUILD;
            case APPLICABLE, UNCHANGED -> throw new IllegalArgumentException(
                    "disposition does not require a rebuild: " + disposition
            );
        };
    }
}
