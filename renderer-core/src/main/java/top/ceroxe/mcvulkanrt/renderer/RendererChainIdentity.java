package top.ceroxe.mcvulkanrt.renderer;

import java.util.Objects;

/**
 * Immutable identity captured when one renderer transaction enters native ownership.
 *
 * <p>The trace tuple identifies the admitted work. The remaining scalar anchors identify the
 * authority, ownership, successor, and already-published resource generations observed at that
 * exact boundary. No scene collection or native resource is retained, so asynchronous BLAS/TLAS
 * work and presentation can carry this value without extending another owner's lifetime.</p>
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

    /** Compatibility identity for tests and callers which do not own architecture anchors. */
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

    public static RendererChainIdentity untraced(long frameSequence) {
        return unanchored(
                RendererFrameCausality.UNTRACED_TRACE_ID,
                RendererFrameSubmission.Source.DIRECT_TEST_OR_DIAGNOSTIC,
                frameSequence
        );
    }

    public boolean traced() {
        return traceId > 0L;
    }

    public boolean architectureAnchored() {
        return authorityRevision >= 0L || nativeOwnershipGeneration >= 0L || successorGeneration >= 0L;
    }

    private static void requireGeneration(long value, String name) {
        if (value < UNAVAILABLE_GENERATION) {
            throw new IllegalArgumentException(name + " must be -1 or greater");
        }
    }

    /** Captures a production identity without exposing renderer owners to the transaction executor. */
    @FunctionalInterface
    public interface Factory {
        RendererChainIdentity capture(long traceId, RendererFrameSubmission.Source source, long frameSequence);
    }
}
