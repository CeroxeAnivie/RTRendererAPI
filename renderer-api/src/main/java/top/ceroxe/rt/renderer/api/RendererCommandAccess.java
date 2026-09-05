package top.ceroxe.rt.renderer.api;

import java.util.Optional;

/** Explicit resource and command transaction boundary for generic rendering. */
public interface RendererCommandAccess {
    default WorkloadExecutionEvidence submitWorkload(RenderWorkload workload) {
        RenderWorkload checked = java.util.Objects.requireNonNull(workload, "workload");
        return switch (checked.mode()) {
            case RAY_TRACING_SCENE -> {
                if (!(this instanceof RendererSceneAccess sceneAccess)) {
                    throw new IllegalStateException("scene workload requires RendererSceneAccess");
                }
                Renderer.FrameSubmissionAttempt attempt =
                        sceneAccess.trySubmit(checked.sceneFrame().orElseThrow());
                if (attempt instanceof Renderer.FrameSubmitted submitted) {
                    yield WorkloadExecutionEvidence.sceneAccepted(submitted.submission());
                }
                Renderer.FrameSubmissionDeferred deferred = (Renderer.FrameSubmissionDeferred) attempt;
                yield WorkloadExecutionEvidence.sceneDeferred(
                        checked.sceneFrame().orElseThrow().sequence(), deferred.detail());
            }
            case GRAPHICS_COMMANDS -> WorkloadExecutionEvidence.graphics(
                    submitCommands(checked.graphicsCommands().orElseThrow()));
            case COMBINED -> WorkloadExecutionEvidence.combinedUnsupported(
                    checked.sceneFrame().orElseThrow().sequence(),
                    "provider has no executable ordered RT/raster composition; submit the lanes separately");
        };
    }

    ResourceTransactionEvidence submitResources(RenderResourceTransaction transaction);

    /**
     * Queries resident or retained retired evidence. Empty does not prove retirement.
     * Use {@link RendererEvidenceAccess} to distinguish unknown, expired and unsupported queries.
     */
    Optional<ResourceResidencyEvidence> resourceResidencyEvidence(ResourceGenerationKey generation);

    RenderingSemanticCapabilities renderingSemanticCapabilities();

    CommandExecutionEvidence submitCommands(RenderCommandTransaction transaction);

    /**
     * Observes command progress. A first terminal observation permits bounded history eviction;
     * use {@link RendererEvidenceAccess} leases for additional delayed readers. Empty does not
     * prove failure or completion; the extension distinguishes missing-history states.
     */
    Optional<CommandExecutionEvidence> commandExecutionEvidence(long transactionSequence);
}
