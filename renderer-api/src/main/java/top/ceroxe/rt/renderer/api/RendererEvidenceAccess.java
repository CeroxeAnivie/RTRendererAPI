package top.ceroxe.rt.renderer.api;

/**
 * Optional generic evidence extension obtained through {@link Renderer#extension(Class)}.
 *
 * <p>Accepted commands remain queryable through their first terminal observation. Reading a
 * terminal command via either this extension or RendererCommandAccess permits later eviction.
 * Further readers must acquire leases before that first observation. Leases are bounded by the
 * command capacity; budget exhaustion rejects new work or leases without invalidating old work.
 * Resource retirement returns terminal evidence synchronously; copy that immutable evidence if
 * it is needed beyond the retired-resource window. Reads never acknowledge GPU or consumer
 * completion, release native resources, or reset sequence/replay protection.</p>
 *
 * <p>All identities belong to the current renderer lifetime. Queries after close fail explicitly;
 * recreating a renderer starts a separate session, not a continuation of its old evidence.</p>
 */
public interface RendererEvidenceAccess {
    EvidenceQuery<CommandExecutionEvidence> queryCommandExecutionEvidence(long transactionSequence);

    EvidenceQuery<ResourceResidencyEvidence> queryResourceResidencyEvidence(ResourceGenerationKey generation);

    /**
     * Pins available evidence before readers observe its terminal state.
     * @throws IllegalArgumentException if the sequence is negative, unknown or outside retention
     * @throws IllegalStateException if the lease budget is exhausted or the renderer is closed
     * @throws UnsupportedOperationException if generic execution is unavailable
     */
    EvidenceLease retainCommandEvidence(long transactionSequence);

    /** Returns bounded inventories and lifetime eviction/admission-rejection counters. */
    EvidenceRetentionStatistics evidenceRetentionStatistics();
}
