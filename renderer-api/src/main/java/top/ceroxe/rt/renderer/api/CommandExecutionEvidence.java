package top.ceroxe.rt.renderer.api;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Immutable, monotonic evidence for one general render-command transaction.
 *
 * <p>Outcome names describe the strongest completed milestone, not caller intent. In particular,
 * {@code ACCEPTED} and {@code RECORDED} never imply GPU completion or visible output.</p>
 */
public final class CommandExecutionEvidence {
    /** Stable machine-readable cause associated with a non-normal outcome. */
    public enum Reason {
        NONE,
        UNSUPPORTED_FEATURE,
        BOUNDED_BACKPRESSURE,
        RESOURCE_VERSION_MISMATCH,
        RESOURCE_NOT_RESIDENT,
        SHADER_VALIDATION_FAILED,
        PIPELINE_COMPILATION_FAILED,
        COMMAND_VALIDATION_FAILED,
        DEVICE_LOST,
        SYNCHRONIZATION_FAILED,
        EXTERNAL_CONSUMER_FAILED,
        FALLBACK_SELECTED
    }

    /** Strongest observed transaction milestone. */
    public enum Outcome {
        REJECTED(false, false, false, false),
        BLOCKED(false, false, false, false),
        ACCEPTED(true, false, false, false),
        RECORDED(true, true, false, false),
        GPU_COMPLETED(true, true, true, false),
        OUTPUT_PRODUCED(true, true, true, true),
        FALLBACK_COMPLETED(true, true, true, true);

        private final boolean accepted;
        private final boolean recorded;
        private final boolean gpuCompleted;
        private final boolean outputProduced;

        Outcome(boolean accepted, boolean recorded, boolean gpuCompleted, boolean outputProduced) {
            this.accepted = accepted;
            this.recorded = recorded;
            this.gpuCompleted = gpuCompleted;
            this.outputProduced = outputProduced;
        }

        /** @return whether the transaction was admitted */
        public boolean accepted() { return accepted; }

        /** @return whether backend commands were recorded */
        public boolean recorded() { return recorded; }

        /** @return whether associated GPU work completed */
        public boolean gpuCompleted() { return gpuCompleted; }

        /** @return whether an output was actually produced */
        public boolean outputProduced() { return outputProduced; }
    }

    private final long transactionSequence;
    private final Outcome outcome;
    private final Reason reason;
    private final OptionalLong frameSequence;
    private final Optional<RenderResourceId> outputResource;
    private final long resetEpoch;
    private final String detail;

    /**
     * Creates a validated evidence snapshot.
     *
     * @param transactionSequence non-negative transaction sequence
     * @param outcome strongest completed milestone
     * @param reason stable machine-readable cause for rejection, blockage, or fallback
     * @param frameSequence frame association, required after recording
     * @param outputResource output identity, present exactly when output was produced
     * @param resetEpoch non-negative diagnostics reset generation
     * @param detail non-blank structured human-readable context
     */
    public CommandExecutionEvidence(
            long transactionSequence,
            Outcome outcome,
            Reason reason,
            OptionalLong frameSequence,
            Optional<RenderResourceId> outputResource,
            long resetEpoch,
            String detail
    ) {
        if (transactionSequence < 0L || resetEpoch < 0L) {
            throw new IllegalArgumentException("transaction sequence and reset epoch must not be negative");
        }
        this.transactionSequence = transactionSequence;
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.frameSequence = Objects.requireNonNull(frameSequence, "frameSequence");
        this.outputResource = Objects.requireNonNull(outputResource, "outputResource");
        this.detail = Objects.requireNonNull(detail, "detail");
        if (detail.isBlank()) throw new IllegalArgumentException("execution-evidence detail must not be blank");
        if (frameSequence.isPresent() && frameSequence.getAsLong() < 0L) {
            throw new IllegalArgumentException("frame sequence must not be negative");
        }
        if (outcome.recorded() != frameSequence.isPresent()) {
            throw new IllegalArgumentException("frame sequence must be present exactly for recorded outcomes");
        }
        if (outcome.outputProduced() != outputResource.isPresent()) {
            throw new IllegalArgumentException("output identity must be present exactly for output-producing outcomes");
        }
        boolean exceptionalOutcome = outcome == Outcome.REJECTED
                || outcome == Outcome.BLOCKED
                || outcome == Outcome.FALLBACK_COMPLETED;
        if (exceptionalOutcome == (reason == Reason.NONE)) {
            throw new IllegalArgumentException(
                    "rejected, blocked, and fallback outcomes require a typed reason; normal milestones require NONE"
            );
        }
        this.resetEpoch = resetEpoch;
    }

    /** @return non-negative transaction sequence */
    public long transactionSequence() { return transactionSequence; }

    /** @return strongest completed milestone */
    public Outcome outcome() { return outcome; }

    /** @return stable machine-readable cause, or {@link Reason#NONE} for normal milestones */
    public Reason reason() { return reason; }

    /** @return associated frame sequence once commands were recorded */
    public OptionalLong frameSequence() { return frameSequence; }

    /** @return exact output resource only after output production */
    public Optional<RenderResourceId> outputResource() { return outputResource; }

    /** @return non-negative diagnostics reset generation */
    public long resetEpoch() { return resetEpoch; }

    /** @return non-blank diagnostic context */
    public String detail() { return detail; }
}
