package top.ceroxe.rt.renderer.api.interop;

import java.util.Objects;
import java.util.Optional;

/** Immutable authoritative snapshot of one external-frame lease lifecycle. */
public record ExternalFrameConsumptionEvidence(
        long frameSequence,
        Outcome outcome,
        Optional<ExternalFrameCompletionEvidence> completion,
        long resetEpoch,
        String detail
) {
    public ExternalFrameConsumptionEvidence {
        if (frameSequence < 0L || resetEpoch < 0L) {
            throw new IllegalArgumentException("frameSequence and resetEpoch must not be negative");
        }
        outcome = Objects.requireNonNull(outcome, "outcome");
        completion = Objects.requireNonNull(completion, "completion");
        detail = Objects.requireNonNull(detail, "detail");
        if (detail.isBlank()) throw new IllegalArgumentException("detail must not be blank");
        if (outcome.completionPublished() != completion.isPresent()) {
            throw new IllegalArgumentException("completion evidence must be present exactly after publication");
        }
        if (completion.isPresent() && completion.orElseThrow().frameSequence() != frameSequence) {
            throw new IllegalArgumentException("completion evidence belongs to a different frame");
        }
    }

    /** Strongest producer-observed lifecycle milestone. */
    public enum Outcome {
        LEASED(false, false, false),
        COMPLETION_PUBLISHED(true, false, false),
        COMPLETION_OBSERVED(true, true, false),
        RETIRED(true, true, true),
        ABANDONED(false, false, true);

        private final boolean completionPublished;
        private final boolean completionObserved;
        private final boolean terminal;

        Outcome(boolean completionPublished, boolean completionObserved, boolean terminal) {
            this.completionPublished = completionPublished;
            this.completionObserved = completionObserved;
            this.terminal = terminal;
        }

        public boolean completionPublished() { return completionPublished; }

        public boolean completionObserved() { return completionObserved; }

        public boolean terminal() { return terminal; }
    }
}
