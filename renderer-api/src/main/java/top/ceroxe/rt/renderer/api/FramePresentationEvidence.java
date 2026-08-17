package top.ceroxe.rt.renderer.api;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * Typed boundary between completed GPU composition and a frame proven visible to a consumer.
 *
 * <p>A submission fence may establish {@link Outcome#GPU_COMPLETED}; it must never be projected
 * as {@link Outcome#VISIBLE}. Visibility requires a consumer-owned presentation sequence supplied
 * by the presentation implementation or an external completion protocol.</p>
 */
public record FramePresentationEvidence(
        ResourceMutationKey output,
        long commandSequence,
        Outcome outcome,
        OptionalLong consumerSequence,
        String detail
) {
    /** Validates sequence ownership and makes visibility evidence non-forgeable by an empty field. */
    public FramePresentationEvidence {
        output = Objects.requireNonNull(output, "output");
        if (commandSequence < 0L || commandSequence != output.commandSequence()) {
            throw new IllegalArgumentException("presentation command sequence must equal its output mutation sequence");
        }
        outcome = Objects.requireNonNull(outcome, "outcome");
        consumerSequence = Objects.requireNonNull(consumerSequence, "consumerSequence");
        detail = Objects.requireNonNull(detail, "detail");
        if (detail.isBlank()) throw new IllegalArgumentException("presentation detail must not be blank");
        if (consumerSequence.isPresent() && consumerSequence.getAsLong() < 0L) {
            throw new IllegalArgumentException("consumer sequence must not be negative");
        }
        if (outcome.consumerEvidenceRequired() != consumerSequence.isPresent()) {
            throw new IllegalArgumentException("consumer sequence is required exactly for consumer-accepted and visible evidence");
        }
    }

    /** Ordered evidence milestones; no ordinal implies a visible display result. */
    public enum Outcome {
        GPU_COMPLETED(false),
        CONSUMER_ACCEPTED(true),
        VISIBLE(true),
        REJECTED(false);

        private final boolean consumerEvidenceRequired;

        Outcome(boolean consumerEvidenceRequired) {
            this.consumerEvidenceRequired = consumerEvidenceRequired;
        }

        boolean consumerEvidenceRequired() { return consumerEvidenceRequired; }
    }
}
