package top.ceroxe.rt.renderer.api;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Typed evidence for one exact resource generation's backend residency lifecycle.
 *
 * <p>Each outcome has a fixed set of legal evidence fields. The model never treats a missing
 * descriptor as retired, and it never treats submission or upload recording as GPU readiness.
 * The transaction revision identifies the generation's original resource-publication transaction;
 * later command submissions and retirement transactions advance the lifecycle without rewriting
 * that provenance.</p>
 */
public record ResourceResidencyEvidence(
        ResourceGenerationKey generation,
        Outcome outcome,
        long transactionRevision,
        OptionalLong submissionSequence,
        OptionalLong lastConsumerSequence,
        String detail
) {
    public ResourceResidencyEvidence {
        generation = Objects.requireNonNull(generation, "generation");
        outcome = Objects.requireNonNull(outcome, "outcome");
        if (transactionRevision < 0L) {
            throw new IllegalArgumentException("transactionRevision must not be negative");
        }
        submissionSequence = Objects.requireNonNull(submissionSequence, "submissionSequence");
        lastConsumerSequence = Objects.requireNonNull(lastConsumerSequence, "lastConsumerSequence");
        detail = Objects.requireNonNull(detail, "detail");
        if (detail.isBlank()) throw new IllegalArgumentException("detail must not be blank");
        requireNonNegative(submissionSequence, "submissionSequence");
        requireNonNegative(lastConsumerSequence, "lastConsumerSequence");

        if (outcome.submissionRequired() != submissionSequence.isPresent()) {
            throw new IllegalArgumentException("submission sequence is required exactly after acceptance/upload");
        }
        if (outcome.consumerSequenceRequired() != lastConsumerSequence.isPresent()) {
            throw new IllegalArgumentException("consumer sequence is required exactly for retirement evidence");
        }
        if (outcome.gpuReady() && !outcome.uploadRecorded()) {
            throw new IllegalArgumentException("GPU_READY evidence requires recorded upload evidence");
        }
        if (outcome.retired() && !outcome.gpuReady() && outcome != Outcome.RETIRED_UNUSED) {
            throw new IllegalArgumentException("retired evidence requires GPU_READY evidence");
        }
        if (outcome == Outcome.RETIRED && lastConsumerSequence.getAsLong() < submissionSequence.getAsLong()) {
            throw new IllegalArgumentException("retired evidence requires consumer completion through submission sequence");
        }
    }

    private static void requireNonNegative(OptionalLong value, String name) {
        if (value.isPresent() && value.getAsLong() < 0L) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    /** Creates initial accepted evidence with no fake upload or GPU completion. */
    public static ResourceResidencyEvidence accepted(
            ResourceGenerationKey generation,
            long transactionRevision,
            String detail
    ) {
        return new ResourceResidencyEvidence(
                generation, Outcome.ACCEPTED, transactionRevision,
                OptionalLong.empty(), OptionalLong.empty(), detail);
    }

    /** Creates explicit rejection evidence for a generation that was not admitted. */
    public static ResourceResidencyEvidence rejected(
            ResourceGenerationKey generation,
            long transactionRevision,
            String detail
    ) {
        return new ResourceResidencyEvidence(
                generation, Outcome.REJECTED, transactionRevision,
                OptionalLong.empty(), OptionalLong.empty(), detail);
    }

    /**
     * Returns the exact recorded content snapshot when this evidence has a GPU submission.
     *
     * <p>The token is empty for accepted, rejected, and unused-retired storage because none of
     * those states may fabricate content execution. It remains stable from recorded through
     * completion and retirement, so a consumer can distinguish storage identity from the exact
     * in-flight mutation it is waiting to retire.</p>
     *
     * @return immutable mutation token, or empty before any command recording
     */
    public Optional<ResourceMutationKey> mutationKey() {
        return submissionSequence.isPresent()
                ? Optional.of(new ResourceMutationKey(generation, submissionSequence.getAsLong()))
                : Optional.empty();
    }

    /**
     * Validates a legal one-step lifecycle transition.
     *
     * @param next next immutable evidence snapshot for the same generation
     * @throws IllegalArgumentException if the transition skips or regresses a milestone
     */
    public void requireNext(ResourceResidencyEvidence next) {
        Objects.requireNonNull(next, "next");
        if (!generation.equals(next.generation) || transactionRevision != next.transactionRevision) {
            throw new IllegalArgumentException("residency evidence transition changes generation or transaction revision");
        }
        if (!outcome.mayAdvanceTo(next.outcome)) {
            throw new IllegalArgumentException("illegal residency evidence transition: " + outcome + " -> " + next.outcome);
        }
        if (submissionSequence.isPresent() && !submissionSequence.equals(next.submissionSequence)) {
            boolean nextOrderedMutation = outcome == Outcome.GPU_READY
                    && next.outcome == Outcome.UPLOAD_RECORDED
                    && next.submissionSequence.isPresent()
                    && next.submissionSequence.getAsLong() > submissionSequence.getAsLong();
            if (!nextOrderedMutation) {
                throw new IllegalArgumentException("residency evidence cannot change its submission sequence outside a later mutation");
            }
        }
    }

    /** Lifecycle milestones with their required evidence shape. */
    public enum Outcome {
        ACCEPTED(false, false, false, false, false),
        UPLOAD_RECORDED(true, true, false, false, false),
        GPU_READY(true, true, true, false, false),
        RETIRE_PENDING(true, true, true, true, false),
        RETIRED(true, true, true, true, true),
        RETIRED_UNUSED(false, false, false, false, true),
        REJECTED(false, false, false, false, false);

        private final boolean submissionRequired;
        private final boolean uploadRecorded;
        private final boolean gpuReady;
        private final boolean consumerSequenceRequired;
        private final boolean retired;

        Outcome(
                boolean submissionRequired,
                boolean uploadRecorded,
                boolean gpuReady,
                boolean consumerSequenceRequired,
                boolean retired
        ) {
            this.submissionRequired = submissionRequired;
            this.uploadRecorded = uploadRecorded;
            this.gpuReady = gpuReady;
            this.consumerSequenceRequired = consumerSequenceRequired;
            this.retired = retired;
        }

        public boolean submissionRequired() { return submissionRequired; }

        public boolean uploadRecorded() { return uploadRecorded; }

        public boolean gpuReady() { return gpuReady; }

        public boolean consumerSequenceRequired() { return consumerSequenceRequired; }

        public boolean retired() { return retired; }

        private boolean mayAdvanceTo(Outcome next) {
            return switch (this) {
                case ACCEPTED -> next == UPLOAD_RECORDED || next == RETIRED_UNUSED || next == REJECTED;
                case UPLOAD_RECORDED -> next == GPU_READY || next == REJECTED;
                case GPU_READY -> next == UPLOAD_RECORDED || next == RETIRE_PENDING;
                case RETIRE_PENDING -> next == RETIRED;
                case RETIRED, RETIRED_UNUSED, REJECTED -> false;
            };
        }
    }
}
