package top.ceroxe.rt.renderer.api;

import java.util.Objects;
import java.util.Optional;

/**
 * Typed admission result for one explicitly discriminated {@link RenderWorkload}.
 *
 * <p>The result keeps the native scene and generic-command evidence in their own domains. A
 * provider cannot claim that a combined workload executed by projecting one lane into the other;
 * unsupported composition is represented as a typed rejection instead.</p>
 */
public final class WorkloadExecutionEvidence {
    /** Strongest admission milestone available without inventing cross-lane GPU evidence. */
    public enum Outcome {
        ACCEPTED,
        DEFERRED,
        REJECTED
    }

    /** Stable reason for a non-normal workload result. */
    public enum Reason {
        NONE,
        UNSUPPORTED_COMBINATION,
        BOUNDED_BACKPRESSURE,
        DEVICE_NOT_READY,
        COMMAND_REJECTED,
        SCENE_REJECTED
    }

    private final long sequence;
    private final RenderWorkload.Mode mode;
    private final Outcome outcome;
    private final Reason reason;
    private final Optional<RayTracingRenderer.FrameSubmissionResult> sceneSubmission;
    private final Optional<CommandExecutionEvidence> graphicsExecution;
    private final String detail;

    private WorkloadExecutionEvidence(
            long sequence,
            RenderWorkload.Mode mode,
            Outcome outcome,
            Reason reason,
            Optional<RayTracingRenderer.FrameSubmissionResult> sceneSubmission,
            Optional<CommandExecutionEvidence> graphicsExecution,
            String detail
    ) {
        if (sequence < 0L) throw new IllegalArgumentException("workload sequence must not be negative");
        this.sequence = sequence;
        this.mode = Objects.requireNonNull(mode, "mode");
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.sceneSubmission = Objects.requireNonNull(sceneSubmission, "sceneSubmission");
        this.graphicsExecution = Objects.requireNonNull(graphicsExecution, "graphicsExecution");
        this.detail = Objects.requireNonNull(detail, "detail");
        if (detail.isBlank()) throw new IllegalArgumentException("workload evidence detail must not be blank");
        if (sceneSubmission.isPresent()
                && mode != RenderWorkload.Mode.RAY_TRACING_SCENE
                && mode != RenderWorkload.Mode.COMBINED) {
            throw new IllegalArgumentException("scene evidence must match workload mode");
        }
        if (graphicsExecution.isPresent()
                && mode != RenderWorkload.Mode.GRAPHICS_COMMANDS
                && mode != RenderWorkload.Mode.COMBINED) {
            throw new IllegalArgumentException("graphics evidence must match workload mode");
        }
        if (mode == RenderWorkload.Mode.COMBINED
                && (sceneSubmission.isPresent() != graphicsExecution.isPresent())) {
            throw new IllegalArgumentException("combined evidence must contain both lanes or neither lane");
        }
        if (outcome == Outcome.REJECTED || outcome == Outcome.DEFERRED) {
            if (reason == Reason.NONE) throw new IllegalArgumentException("non-accepted workload requires a typed reason");
        } else if (reason != Reason.NONE) {
            throw new IllegalArgumentException("accepted workload must use reason NONE");
        }
    }

    /** Creates evidence for successful retained-scene admission. */
    public static WorkloadExecutionEvidence sceneAccepted(
            RayTracingRenderer.FrameSubmissionResult submission
    ) {
        RayTracingRenderer.FrameSubmissionResult checked = Objects.requireNonNull(submission, "submission");
        return new WorkloadExecutionEvidence(checked.frameSequence(), RenderWorkload.Mode.RAY_TRACING_SCENE,
                Outcome.ACCEPTED, Reason.NONE, Optional.of(checked), Optional.empty(),
                "retained ray-tracing scene workload admitted by its dedicated fast path");
    }

    /** Creates evidence for a generic command admission result. */
    public static WorkloadExecutionEvidence graphics(CommandExecutionEvidence execution) {
        CommandExecutionEvidence checked = Objects.requireNonNull(execution, "execution");
        Outcome outcome = switch (checked.outcome()) {
            case BLOCKED -> Outcome.DEFERRED;
            case REJECTED, FALLBACK_COMPLETED -> Outcome.REJECTED;
            default -> Outcome.ACCEPTED;
        };
        Reason reason = switch (checked.reason()) {
            case BOUNDED_BACKPRESSURE -> Reason.BOUNDED_BACKPRESSURE;
            case DEVICE_LOST -> Reason.DEVICE_NOT_READY;
            case NONE -> Reason.NONE;
            default -> Reason.COMMAND_REJECTED;
        };
        if (outcome == Outcome.ACCEPTED) reason = Reason.NONE;
        return new WorkloadExecutionEvidence(checked.transactionSequence(), RenderWorkload.Mode.GRAPHICS_COMMANDS,
                outcome, reason, Optional.empty(), Optional.of(checked), checked.detail());
    }

    /** Creates a fail-closed result for a combined workload unsupported by the backend. */
    public static WorkloadExecutionEvidence combinedUnsupported(long sequence, String detail) {
        return new WorkloadExecutionEvidence(sequence, RenderWorkload.Mode.COMBINED,
                Outcome.REJECTED, Reason.UNSUPPORTED_COMBINATION, Optional.empty(), Optional.empty(), detail);
    }

    /** Creates evidence for a combined workload submitted in the declared RT-then-raster order. */
    public static WorkloadExecutionEvidence combined(
            RayTracingRenderer.FrameSubmissionResult scene,
            CommandExecutionEvidence graphics
    ) {
        RayTracingRenderer.FrameSubmissionResult checkedScene = Objects.requireNonNull(scene, "scene");
        CommandExecutionEvidence checkedGraphics = Objects.requireNonNull(graphics, "graphics");
        if (checkedScene.frameSequence() != checkedGraphics.transactionSequence()) {
            throw new IllegalArgumentException("combined evidence lanes must share one sequence");
        }
        Outcome outcome = switch (checkedGraphics.outcome()) {
            case BLOCKED -> Outcome.DEFERRED;
            case REJECTED, FALLBACK_COMPLETED -> Outcome.REJECTED;
            default -> Outcome.ACCEPTED;
        };
        Reason reason = switch (checkedGraphics.reason()) {
            case BOUNDED_BACKPRESSURE -> Reason.BOUNDED_BACKPRESSURE;
            case DEVICE_LOST -> Reason.DEVICE_NOT_READY;
            case NONE -> Reason.NONE;
            default -> Reason.COMMAND_REJECTED;
        };
        if (outcome == Outcome.ACCEPTED) reason = Reason.NONE;
        return new WorkloadExecutionEvidence(
                checkedScene.frameSequence(), RenderWorkload.Mode.COMBINED, outcome, reason,
                Optional.of(checkedScene), Optional.of(checkedGraphics),
                "retained RT scene submitted before generic raster commands on the shared Vulkan queue"
        );
    }

    /** Creates a deferred combined result before either lane is accepted. */
    public static WorkloadExecutionEvidence combinedDeferred(long sequence, String detail) {
        return new WorkloadExecutionEvidence(sequence, RenderWorkload.Mode.COMBINED,
                Outcome.DEFERRED, Reason.BOUNDED_BACKPRESSURE, Optional.empty(), Optional.empty(), detail);
    }

    /** Creates a deferred scene admission result without fabricating a scene submission. */
    public static WorkloadExecutionEvidence sceneDeferred(
            long sequence, String detail
    ) {
        return new WorkloadExecutionEvidence(sequence, RenderWorkload.Mode.RAY_TRACING_SCENE,
                Outcome.DEFERRED, Reason.BOUNDED_BACKPRESSURE, Optional.empty(), Optional.empty(), detail);
    }

    public long sequence() { return sequence; }
    public RenderWorkload.Mode mode() { return mode; }
    public Outcome outcome() { return outcome; }
    public Reason reason() { return reason; }
    public Optional<RayTracingRenderer.FrameSubmissionResult> sceneSubmission() { return sceneSubmission; }
    public Optional<CommandExecutionEvidence> graphicsExecution() { return graphicsExecution; }
    public String detail() { return detail; }
}
