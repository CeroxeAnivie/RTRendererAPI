package top.ceroxe.rt.renderer.api;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * Evidence for provider-owned composition and external-frame publication.
 *
 * <p>This is deliberately separate from {@link FramePresentationEvidence}: a generic command
 * output and a provider-owned external frame have different identities and lifetimes.</p>
 */
public record FrameCompositionEvidence(
        long frameSequence,
        long sceneRevision,
        int width,
        int height,
        FrameOutputFormat format,
        Outcome outcome,
        OptionalLong consumerSequence,
        String detail
) {
    /** Validates that only consumer milestones carry consumer-owned completion evidence. */
    public FrameCompositionEvidence {
        if (width < 0 || height < 0) throw new IllegalArgumentException("composition evidence extent must not be negative");
        format = Objects.requireNonNull(format, "format");
        outcome = Objects.requireNonNull(outcome, "outcome");
        consumerSequence = Objects.requireNonNull(consumerSequence, "consumerSequence");
        detail = Objects.requireNonNull(detail, "detail");
        if (detail.isBlank()) throw new IllegalArgumentException("composition evidence detail must not be blank");
        if (consumerSequence.isPresent() && consumerSequence.getAsLong() < 0L) {
            throw new IllegalArgumentException("consumer sequence must not be negative");
        }
        if (outcome.consumerEvidenceRequired() != consumerSequence.isPresent()) {
            throw new IllegalArgumentException("consumer sequence is required exactly for consumer milestones");
        }
        if (outcome == Outcome.REJECTED) {
            if (frameSequence != -1L || sceneRevision != -1L || width != 0 || height != 0) {
                throw new IllegalArgumentException("rejected composition must not claim output identity or extent");
            }
        } else if (frameSequence < 0L || sceneRevision < 0L || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("non-rejected composition must carry a complete output identity");
        }
    }

    /** Composition milestones never imply display visibility without a separate consumer signal. */
    public enum Outcome {
        REJECTED(false),
        SUBMITTED(false),
        GPU_COMPLETED(false),
        CONSUMER_ACCEPTED(true),
        VISIBLE(true);

        private final boolean consumerEvidenceRequired;

        Outcome(boolean consumerEvidenceRequired) { this.consumerEvidenceRequired = consumerEvidenceRequired; }

        boolean consumerEvidenceRequired() { return consumerEvidenceRequired; }
    }

    /** Creates a typed failure without fabricating a destination frame identity. */
    public static FrameCompositionEvidence rejected(FrameOutputFormat format, String detail) {
        return new FrameCompositionEvidence(-1L, -1L, 0, 0, format, Outcome.REJECTED,
                OptionalLong.empty(), detail);
    }
}
