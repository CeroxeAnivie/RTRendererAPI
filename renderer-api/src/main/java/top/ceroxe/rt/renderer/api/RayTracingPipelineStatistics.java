package top.ceroxe.rt.renderer.api;

/** Bounded-owner accounting snapshot; counts include pending retirement until release succeeds. */
public record RayTracingPipelineStatistics(
        int livePipelines, int pendingRetirements, int inFlightPipelines, long liveSbtBytes,
        long createdPipelines, long retiredPipelines, long failedReleases, long completionSequence) {
    /** Rejects negative counts and inconsistent live-resource accounting. */
    public RayTracingPipelineStatistics {
        if (livePipelines < 0 || pendingRetirements < 0 || inFlightPipelines < 0 || liveSbtBytes < 0
                || createdPipelines < 0 || retiredPipelines < 0 || failedReleases < 0 || completionSequence < -1
                || pendingRetirements > livePipelines || inFlightPipelines > livePipelines
                || createdPipelines - retiredPipelines != livePipelines) {
            throw new IllegalArgumentException("inconsistent ray-tracing pipeline statistics");
        }
    }
}
