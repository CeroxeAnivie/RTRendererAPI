package top.ceroxe.rt.renderer;

import java.util.Objects;

/**
 * Immutable identity captured when one renderer transaction enters native ownership.
 *
 * <p>The trace tuple identifies the admitted work. The remaining scalar anchors identify the
 * authority, ownership, successor, and already-published resource generations observed at that
 * exact boundary. No scene collection or native resource is retained, so asynchronous BLAS/TLAS
 * work and presentation can carry this value without extending another owner's lifetime.</p>
 *
 * @param traceId                   positive production trace id, or the explicit untraced sentinel
 * @param source                    submission source
 * @param frameSequence             non-negative frame sequence
 * @param authorityRevision         authoritative scene revision, or {@code -1}
 * @param nativeOwnershipGeneration native ownership generation, or {@code -1}
 * @param successorGeneration       successor generation, or {@code -1}
 * @param publicationGeneration     publication generation, or {@code -1}
 * @param descriptorGeneration      descriptor generation, or {@code -1}
 * @param worldTlasRevision         world TLAS revision, or {@code -1}
 * @param materialRevision          material revision, or {@code -1}
 */
public record RendererChainIdentity(
        long traceId,
        RendererFrameSubmission.Source source,
        long frameSequence,
        long authorityRevision,
        long nativeOwnershipGeneration,
        long successorGeneration,
        long publicationGeneration,
        long descriptorGeneration,
        long worldTlasRevision,
        long materialRevision
) {
    public static final long UNAVAILABLE_GENERATION = -1L;

    /**
     * Validates the trace/source relationship and all scalar generation anchors.
     */
    public RendererChainIdentity {
        source = Objects.requireNonNull(source, "source");
        if (frameSequence < 0L) {
            throw new IllegalArgumentException("frameSequence must not be negative");
        }
        if (source == RendererFrameSubmission.Source.DIRECT_TEST_OR_DIAGNOSTIC) {
            if (traceId != RendererFrameCausality.UNTRACED_TRACE_ID) {
                throw new IllegalArgumentException("direct test or diagnostic chain must be untraced");
            }
        } else if (traceId <= 0L) {
            throw new IllegalArgumentException("production chain traceId must be positive");
        }
        requireGeneration(authorityRevision, "authorityRevision");
        requireGeneration(nativeOwnershipGeneration, "nativeOwnershipGeneration");
        requireGeneration(successorGeneration, "successorGeneration");
        requireGeneration(publicationGeneration, "publicationGeneration");
        requireGeneration(descriptorGeneration, "descriptorGeneration");
        requireGeneration(worldTlasRevision, "worldTlasRevision");
        requireGeneration(materialRevision, "materialRevision");
    }

    /**
     * Creates a compatibility identity for callers that do not own architecture anchors.
     *
     * @param traceId       positive production trace id, or the untraced sentinel for diagnostics
     * @param source        submission source
     * @param frameSequence nonnegative frame sequence
     * @return identity with every architecture generation marked unavailable
     */
    public static RendererChainIdentity unanchored(
            long traceId,
            RendererFrameSubmission.Source source,
            long frameSequence
    ) {
        return new RendererChainIdentity(
                traceId,
                source,
                frameSequence,
                UNAVAILABLE_GENERATION,
                UNAVAILABLE_GENERATION,
                UNAVAILABLE_GENERATION,
                UNAVAILABLE_GENERATION,
                UNAVAILABLE_GENERATION,
                UNAVAILABLE_GENERATION,
                UNAVAILABLE_GENERATION
        );
    }

    /**
     * Creates an untraced direct diagnostic identity.
     *
     * @param frameSequence nonnegative frame sequence
     * @return untraced identity without architecture anchors
     */
    public static RendererChainIdentity untraced(long frameSequence) {
        return unanchored(
                RendererFrameCausality.UNTRACED_TRACE_ID,
                RendererFrameSubmission.Source.DIRECT_TEST_OR_DIAGNOSTIC,
                frameSequence
        );
    }

    private static void requireGeneration(long value, String name) {
        if (value < UNAVAILABLE_GENERATION) {
            throw new IllegalArgumentException(name + " must be -1 or greater");
        }
    }

    /**
     * Tests whether the identity belongs to a production trace.
     *
     * @return {@code true} when the trace id is positive
     */
    public boolean traced() {
        return traceId > 0L;
    }

    /**
     * Tests whether any native authority, ownership or successor anchor is available.
     *
     * @return {@code true} when at least one architecture anchor is present
     */
    public boolean architectureAnchored() {
        return authorityRevision >= 0L || nativeOwnershipGeneration >= 0L || successorGeneration >= 0L;
    }

    /**
     * Captures a production identity without exposing renderer owners to the transaction executor.
     */
    @FunctionalInterface
    public interface Factory {
        /**
         * Captures the chain identity at native admission.
         *
         * @param traceId       positive production trace identifier
         * @param source        submission source
         * @param frameSequence nonnegative frame sequence
         * @return captured immutable chain identity
         */
        RendererChainIdentity capture(long traceId, RendererFrameSubmission.Source source, long frameSequence);
    }
}
