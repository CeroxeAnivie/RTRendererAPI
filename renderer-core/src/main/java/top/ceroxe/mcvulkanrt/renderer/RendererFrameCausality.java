package top.ceroxe.mcvulkanrt.renderer;

/**
 * Immutable identity of the renderer transaction which admitted native work.
 *
 * <p>This value deliberately contains no scene payload or native resource. It can therefore be
 * retained by asynchronous BLAS/TLAS and descriptor transactions without extending the lifetime
 * of a frame update. A production identity is always traceable; compatibility-only test and
 * diagnostic calls are explicitly marked untraced instead of impersonating a real transaction.</p>
 */
public record RendererFrameCausality(RendererChainIdentity chainIdentity) {
    public static final long UNTRACED_TRACE_ID = -1L;

    public RendererFrameCausality {
        chainIdentity = java.util.Objects.requireNonNull(chainIdentity, "chainIdentity");
    }

    /** Compatibility constructor for tests and diagnostics without architecture anchors. */
    public RendererFrameCausality(
            long traceId,
            RendererFrameSubmission.Source source,
            long frameSequence
    ) {
        this(RendererChainIdentity.unanchored(traceId, source, frameSequence));
    }

    public static RendererFrameCausality untraced(long frameSequence) {
        return new RendererFrameCausality(RendererChainIdentity.untraced(frameSequence));
    }

    public long traceId() {
        return chainIdentity.traceId();
    }

    public RendererFrameSubmission.Source source() {
        return chainIdentity.source();
    }

    public long frameSequence() {
        return chainIdentity.frameSequence();
    }

    public boolean traced() {
        return chainIdentity.traced();
    }
}
