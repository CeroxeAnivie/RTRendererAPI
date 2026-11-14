package top.ceroxe.rt.renderer;

import java.util.Objects;

/**
 * Explicit renderer-to-native transaction envelope.
 *
 * <p>The previous production path propagated only a {@link RendererFrameUpdate} and stored its
 * trace id in a side-channel {@code ThreadLocal}. That made the synchronous call observable but
 * left static analysis unable to prove which admitted transaction created a native publication.
 * This envelope keeps identity, source, frame state, terrain revisions, and the commit payload in
 * one immutable value that every production backend boundary must accept explicitly.</p>
 *
 * @param chainIdentity immutable admitted transaction identity
 * @param update        immutable renderer frame update
 */
public record RendererFrameSubmission(
        RendererChainIdentity chainIdentity,
        RendererFrameUpdate update
) {
    /**
     * Validates identity/update consistency and requires both immutable payloads.
     */
    public RendererFrameSubmission {
        chainIdentity = Objects.requireNonNull(chainIdentity, "chainIdentity");
        update = Objects.requireNonNull(update, "update");
        if (chainIdentity.frameSequence() != update.frameState().sequence()) {
            throw new IllegalArgumentException("chain identity must match the submitted frame sequence");
        }
    }

    /**
     * Creates a compatibility submission without architecture anchors.
     *
     * @param traceId positive production trace id, or the untraced sentinel for diagnostics
     * @param source  submission source
     * @param update  immutable renderer update
     */
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

    /**
     * Creates an untraced direct diagnostic submission.
     *
     * @param update immutable renderer update
     * @return untraced submission envelope
     */
    public static RendererFrameSubmission untraced(RendererFrameUpdate update) {
        return new RendererFrameSubmission(
                RendererChainIdentity.untraced(update.frameState().sequence()),
                update
        );
    }

    /**
     * Returns the admitted trace identifier.
     *
     * @return trace identifier carried by the chain identity
     */
    public long traceId() {
        return chainIdentity.traceId();
    }

    /**
     * Returns the transaction source.
     *
     * @return submission source carried by the chain identity
     */
    public Source source() {
        return chainIdentity.source();
    }

    /**
     * Returns the commit plan carried by the update.
     *
     * @return immutable commit plan derived from the update
     */
    public RendererFrameCommitPlan commitPlan() {
        return update.commitPlan();
    }

    /**
     * Returns the submitted frame sequence.
     *
     * @return nonnegative submitted frame sequence
     */
    public long frameSequence() {
        return update.frameState().sequence();
    }

    /**
     * Returns the causality projection for downstream ownership.
     *
     * @return immutable causality projection of the chain identity
     */
    public RendererFrameCausality causality() {
        return new RendererFrameCausality(chainIdentity);
    }

    /**
     * Origin of an admitted renderer transaction.
     */
    public enum Source {
        /**
         * Normal end-of-frame submission.
         */
        FRAME_END,
        /**
         * Terrain preparation submission.
         */
        TERRAIN_PREPARATION,
        /**
         * Bootstrap progress submission.
         */
        BOOTSTRAP_PUMP,
        /**
         * Missing terrain mesh recovery submission.
         */
        MESH_BACKFILL,
        /**
         * Compatibility-only test or diagnostic submission.
         */
        DIRECT_TEST_OR_DIAGNOSTIC
    }
}
