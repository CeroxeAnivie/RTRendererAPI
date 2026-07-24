package top.ceroxe.mcvulkanrt.renderer;

import java.util.Objects;

/**
 * Explicit renderer-to-native transaction envelope.
 *
 * <p>The previous production path propagated only a {@link RendererFrameUpdate} and stored its
 * trace id in a side-channel {@code ThreadLocal}. That made the synchronous call observable but
 * left static analysis unable to prove which admitted transaction created a native publication.
 * This envelope keeps identity, source, frame state, terrain revisions, and the commit payload in
 * one immutable value that every production backend boundary must accept explicitly.</p>
 */
public record RendererFrameSubmission(
        RendererChainIdentity chainIdentity,
        RendererFrameUpdate update
) {
    public RendererFrameSubmission {
        chainIdentity = Objects.requireNonNull(chainIdentity, "chainIdentity");
        update = Objects.requireNonNull(update, "update");
        if (chainIdentity.frameSequence() != update.frameState().sequence()) {
            throw new IllegalArgumentException("chain identity must match the submitted frame sequence");
        }
    }

    /** Compatibility constructor for tests and diagnostics without architecture anchors. */
    public RendererFrameSubmission(long traceId, Source source, RendererFrameUpdate update) {
        this(
                RendererChainIdentity.unanchored(
                        traceId,
                        source,
                        Objects.requireNonNull(update, "update").frameState().sequence()
                ),
                update
        );
    }

    public static RendererFrameSubmission untraced(RendererFrameUpdate update) {
        return new RendererFrameSubmission(
                RendererChainIdentity.untraced(update.frameState().sequence()),
                update
        );
    }

    public long traceId() {
        return chainIdentity.traceId();
    }

    public Source source() {
        return chainIdentity.source();
    }

    public RendererFrameCommitPlan commitPlan() {
        return update.commitPlan();
    }

    public long frameSequence() {
        return update.frameState().sequence();
    }

    public RendererFrameCausality causality() {
        return new RendererFrameCausality(chainIdentity);
    }

    public enum Source {
        FRAME_END,
        TERRAIN_PREPARATION,
        BOOTSTRAP_PUMP,
        MESH_BACKFILL,
        DIRECT_TEST_OR_DIAGNOSTIC
    }
}
