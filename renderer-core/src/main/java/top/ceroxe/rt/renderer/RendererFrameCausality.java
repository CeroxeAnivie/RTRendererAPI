package top.ceroxe.rt.renderer;

/**
 * Immutable identity of the renderer transaction which admitted native work.
 *
 * <p>This value deliberately contains no scene payload or native resource. It can therefore be
 * retained by asynchronous BLAS/TLAS and descriptor transactions without extending the lifetime
 * of a frame update. A production identity is always traceable; compatibility-only test and
 * diagnostic calls are explicitly marked untraced instead of impersonating a real transaction.</p>
 *
 * @param chainIdentity immutable transaction-chain identity
 */
public record RendererFrameCausality(RendererChainIdentity chainIdentity) {
    public static final long UNTRACED_TRACE_ID = -1L;

    /**
     * Requires a non-null immutable chain identity.
     */
    public RendererFrameCausality {
        chainIdentity = java.util.Objects.requireNonNull(chainIdentity, "chainIdentity");
    }

    /**
     * Creates compatibility causality without architecture anchors.
     *
     * @param traceId       positive production trace id, or the untraced sentinel for diagnostics
     * @param source        submission source
     * @param frameSequence nonnegative frame sequence
     */
    public RendererFrameCausality(
            long traceId,
            RendererFrameSubmission.Source source,
            long frameSequence
    ) {
        this(RendererChainIdentity.unanchored(traceId, source, frameSequence));
    }

    /**
     * Creates untraced diagnostic causality.
     *
     * @param frameSequence nonnegative frame sequence
     * @return untraced causality identity
     */
    public static RendererFrameCausality untraced(long frameSequence) {
        return new RendererFrameCausality(RendererChainIdentity.untraced(frameSequence));
    }

    /**
     * Returns the trace identifier.
     *
     * @return positive production trace id, or the untraced sentinel
     */
    public long traceId() {
        return chainIdentity.traceId();
    }

    /**
     * Returns the submission source.
     *
     * @return submission source
     */
    public RendererFrameSubmission.Source source() {
        return chainIdentity.source();
    }

    /**
     * Returns the admitted frame sequence.
     *
     * @return nonnegative frame sequence
     */
    public long frameSequence() {
        return chainIdentity.frameSequence();
    }

    /**
     * Tests whether this causality value belongs to a production trace.
     *
     * @return {@code true} when traceable
     */
    public boolean traced() {
        return chainIdentity.traced();
    }
}
