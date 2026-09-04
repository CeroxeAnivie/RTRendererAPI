package top.ceroxe.rt.renderer.api;

/**
 * Composed host boundary for one independently owned renderer instance.
 *
 * <p>Scene and frame submission are single-writer operations. Observation may occur from another
 * thread, but {@link #close()} must be serialized with all submissions. Implementations may
 * process work asynchronously; accepted input ownership never implies immediate GPU completion.
 * Retained scene and explicit command transactions are separate, explicit workload lanes; neither
 * lane is inferred from missing fields or translated into the other. Focused consumers may depend
 * on {@link RendererLifecycle}, {@link RendererFrameAccess}, {@link RendererSceneAccess}, or
 * {@link RendererCommandAccess} instead of this aggregate facade.</p>
 */
public interface Renderer extends RendererLifecycle, RendererFrameAccess, RendererSceneAccess,
        RendererCommandAccess {

    /**
     * Discovers an optional, explicitly named expert extension without adding its native concepts
     * to the ordinary renderer lifecycle.
     *
     * <p>The default recognizes interfaces implemented directly by the renderer. Providers may
     * override this method for delegated extension objects. Stateful service extensions must keep
     * stable identity for the renderer lifetime; explicitly documented immutable snapshot value
     * types may return a newer value on each query. Neither form may fabricate support.</p>
     *
     * @param extensionType non-null extension interface
     * @param <T>           extension interface type
     * @return supported extension instance, or empty when unavailable
     */
    default <T> java.util.Optional<T> extension(Class<T> extensionType) {
        java.util.Objects.requireNonNull(extensionType, "extensionType");
        return extensionType.isInstance(this)
                ? java.util.Optional.of(extensionType.cast(this))
                : java.util.Optional.empty();
    }


    /**
     * Lifecycle state of one renderer instance.
     */
    enum Status {
        /**
         * The renderer accepts scene and frame submissions.
         */
        READY,
        /**
         * Device recreation is waiting for outstanding external frame ownership to retire.
         */
        RECOVERING,
        /**
         * A terminal backend failure prevents further submissions.
         */
        FAILED,
        /**
         * The renderer was closed and owns no reusable public instance state.
         */
        CLOSED
    }

    /**
     * Logical scene publication result; GPU work coalescing is intentionally not observable.
     *
     * @param acceptedSceneRevision exact revision atomically accepted by the renderer
     */
    record SceneUpdateResult(long acceptedSceneRevision) {
        /**
         * Validates and creates a scene publication result.
         *
         * @param acceptedSceneRevision exact non-negative revision accepted by the renderer
         * @throws IllegalArgumentException if the revision is negative
         */
        public SceneUpdateResult {
            if (acceptedSceneRevision < 0L) {
                throw new IllegalArgumentException("acceptedSceneRevision must not be negative");
            }
        }
    }

    /**
     * Evidence that a frame entered the backend dispatch lane against one exact temporal state.
     */
    final class FrameSubmissionResult {
        private final long frameSequence;
        private final long scheduledSceneRevision;
        private final java.util.Set<HistoryInvalidationReason> historyInvalidations;

        private FrameSubmissionResult(
                long frameSequence,
                long scheduledSceneRevision,
                java.util.Set<HistoryInvalidationReason> historyInvalidations
        ) {
            if (frameSequence < 0L || scheduledSceneRevision < 0L) {
                throw new IllegalArgumentException("frame submission revisions must not be negative");
            }
            this.frameSequence = frameSequence;
            this.scheduledSceneRevision = scheduledSceneRevision;
            this.historyInvalidations = java.util.Set.copyOf(java.util.Objects.requireNonNull(
                    historyInvalidations, "historyInvalidations"
            ));
        }

        /**
         * Creates validated admission evidence for a renderer provider.
         *
         * @param frameSequence          exact non-negative admitted frame sequence
         * @param scheduledSceneRevision exact non-negative scene revision selected for rendering
         * @param historyInvalidations   immutable effective temporal invalidation reasons
         * @return validated immutable admission evidence
         */
        public static FrameSubmissionResult accepted(
                long frameSequence,
                long scheduledSceneRevision,
                java.util.Set<HistoryInvalidationReason> historyInvalidations
        ) {
            return new FrameSubmissionResult(
                    frameSequence, scheduledSceneRevision, historyInvalidations
            );
        }

        /**
         * Returns the exact admitted frame sequence.
         *
         * @return non-negative frame sequence
         */
        public long frameSequence() {
            return frameSequence;
        }

        /**
         * Returns the scene revision selected for the admitted frame.
         *
         * @return non-negative scheduled scene revision
         */
        public long scheduledSceneRevision() {
            return scheduledSceneRevision;
        }

        /**
         * Returns immutable effective reasons why this frame started a fresh temporal generation.
         *
         * @return immutable, possibly empty invalidation set
         */
        public java.util.Set<HistoryInvalidationReason> historyInvalidations() {
            return historyInvalidations;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof FrameSubmissionResult result)) return false;
            return frameSequence == result.frameSequence
                    && scheduledSceneRevision == result.scheduledSceneRevision
                    && historyInvalidations.equals(result.historyInvalidations);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(
                    frameSequence, scheduledSceneRevision, historyInvalidations
            );
        }

        @Override
        public String toString() {
            return "FrameSubmissionResult[frameSequence=" + frameSequence
                    + ", scheduledSceneRevision=" + scheduledSceneRevision
                    + ", historyInvalidations=" + historyInvalidations + ']';
        }
    }

    /** Exhaustive, non-null result of a non-throwing capacity-aware frame attempt. */
    sealed interface FrameSubmissionAttempt permits FrameSubmitted, FrameSubmissionDeferred {
    }

    /**
     * Successful frame admission.
     *
     * @param submission exact immutable backend admission evidence
     */
    record FrameSubmitted(FrameSubmissionResult submission) implements FrameSubmissionAttempt {
        /** Validates the successful attempt. */
        public FrameSubmitted {
            submission = java.util.Objects.requireNonNull(submission, "submission");
        }
    }

    /**
     * Recoverable capacity refusal that retained no logical or native submission state.
     *
     * @param deferralReason typed capacity or lifecycle reason
     * @param detail non-blank diagnostic detail suitable for telemetry
     */
    record FrameSubmissionDeferred(
            SubmissionDeferralReason deferralReason,
            String detail
    ) implements FrameSubmissionAttempt {
        /** Validates the deferred attempt. */
        public FrameSubmissionDeferred {
            deferralReason = java.util.Objects.requireNonNull(deferralReason, "deferralReason");
            detail = java.util.Objects.requireNonNull(detail, "detail");
            if (detail.isBlank()) throw new IllegalArgumentException("detail must not be blank");
        }
    }

}
