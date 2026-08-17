package top.ceroxe.rt.renderer.api;

import java.util.Optional;

/**
 * Project-independent renderer supporting both the retained scene fast path and explicit general
 * render-command transactions.
 *
 * <p>The inherited {@link RayTracingRenderer} methods remain the stable 1.0 scene API. This
 * interface is the explicit discriminator for the 1.1 command path; implementations must never
 * infer a command transaction from absent scene fields or silently translate unsupported commands
 * into the fixed scene pipeline.</p>
 */
public interface Renderer extends RayTracingRenderer {
    /**
     * Submits one explicitly discriminated workload through its native execution lane.
     *
     * <p>Scene workloads use the retained 1.0 fast path, graphics workloads use the generic
     * command path, and combined workloads are rejected unless a provider has a real ordered
     * composition implementation. No mode is inferred from missing fields.</p>
     *
     * @param workload immutable explicit workload
     * @return typed lane-specific admission evidence
     */
    default WorkloadExecutionEvidence submitWorkload(RenderWorkload workload) {
        RenderWorkload checked = java.util.Objects.requireNonNull(workload, "workload");
        return switch (checked.mode()) {
            case RAY_TRACING_SCENE -> {
                RenderFrameRequest frame = checked.sceneFrame().orElseThrow();
                FrameSubmissionAttempt attempt = trySubmit(frame);
                if (attempt instanceof RayTracingRenderer.FrameSubmitted submitted) {
                    yield WorkloadExecutionEvidence.sceneAccepted(submitted.submission());
                }
                RayTracingRenderer.FrameSubmissionDeferred deferred =
                        (RayTracingRenderer.FrameSubmissionDeferred) attempt;
                yield WorkloadExecutionEvidence.sceneDeferred(frame.sequence(), deferred.detail());
            }
            case GRAPHICS_COMMANDS -> WorkloadExecutionEvidence.graphics(
                    submitCommands(checked.graphicsCommands().orElseThrow())
            );
            case COMBINED -> WorkloadExecutionEvidence.combinedUnsupported(
                    checked.sceneFrame().orElseThrow().sequence(),
                    "provider has no executable ordered RT/raster composition; submit the lanes separately"
            );
        };
    }

    /**
     * Atomically publishes explicit versioned resources for the generic command path.
     *
     * <p>This is deliberately distinct from {@link #submitCommands(RenderCommandTransaction)}:
     * command admission may only reference exact generations already accepted here. The default
     * keeps pre-1.1 providers binary-compatible and fails closed rather than inventing resource
     * residency from a descriptor.</p>
     *
     * @param transaction non-null immutable resource publication and retirement batch
     * @return typed admission evidence; acceptance does not imply GPU readiness
     */
    default ResourceTransactionEvidence submitResources(RenderResourceTransaction transaction) {
        RenderResourceTransaction checked = java.util.Objects.requireNonNull(transaction, "transaction");
        java.util.ArrayList<ResourceResidencyEvidence> rejected = new java.util.ArrayList<>();
        for (ResourceGenerationKey generation : checked.upsertGenerationKeys()) {
            rejected.add(ResourceResidencyEvidence.rejected(
                    generation, checked.revision(), "generic resource execution is not implemented by this provider"
            ));
        }
        return new ResourceTransactionEvidence(
                checked.revision(), ResourceTransactionEvidence.Outcome.REJECTED, rejected,
                "generic resource execution is not implemented by this provider"
        );
    }

    /**
     * Returns the latest lifecycle evidence for one exact resource generation.
     *
     * @param generation non-null stable identity plus immutable generation
     * @return latest evidence, or empty when the generation was never admitted
     */
    default Optional<ResourceResidencyEvidence> resourceResidencyEvidence(ResourceGenerationKey generation) {
        java.util.Objects.requireNonNull(generation, "generation");
        return Optional.empty();
    }

    /**
     * Returns a complete immutable snapshot of executable general-rendering features.
     *
     * @return complete capability snapshot; omitted or unimplemented features are unsupported
     */
    RenderingSemanticCapabilities renderingSemanticCapabilities();

    /**
     * Attempts one immutable command transaction without using exceptions for ordinary admission
     * rejection or bounded backpressure.
     *
     * <p>The returned evidence describes only the strongest milestone reached before this method
     * returns. Later GPU completion must be observed through {@link #commandExecutionEvidence}.</p>
     *
     * @param transaction non-null, prevalidated, strictly ordered command transaction
     * @return non-null typed evidence; acceptance never implies recording or completion
     */
    CommandExecutionEvidence submitCommands(RenderCommandTransaction transaction);

    /**
     * Returns the latest retained evidence for one previously submitted transaction.
     *
     * @param transactionSequence non-negative application-owned transaction sequence
     * @return latest monotonic evidence, or empty when this renderer never admitted the sequence
     */
    Optional<CommandExecutionEvidence> commandExecutionEvidence(long transactionSequence);
}
