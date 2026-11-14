package top.ceroxe.rt.renderer.rt.pipeline;

import java.util.Objects;

/**
 * Single mutable owner for frame-dispatch admission, submission, completion, and latency counters.
 *
 * <p>Counters that describe one transition are updated atomically through one method. This avoids
 * impossible telemetry such as a no-slot rejection without a corresponding pending-async skip, or
 * a submitted ordinal advancing without the submission count.</p>
 */
final class RtFrameDispatchStatistics {
    private long observedFrameStates;
    private long skippedPresentationGateFrames;
    private long skippedUnavailableFrameStates;
    private long skippedIntervalFrames;
    private long skippedPendingAsyncFrames;
    private long skippedPendingBacklogFrames;
    private long freshnessCatchUpFrameDispatches;
    private long frameDispatches;
    private long skippedReadbackIntervalFrames;
    private long skippedNoFrameSlots;
    private long frameSlotDescriptorRefreshes;
    private long asyncFrameSubmissions;
    private long asyncFrameCompletions;
    private long lastFrameDispatchMillis;
    private long maxFrameDispatchMillis;
    private long totalFrameDispatchMillis;

    long observeFrameState() {
        observedFrameStates = Math.incrementExact(observedFrameStates);
        return observedFrameStates;
    }

    void recordAdmissionRejection(RtFrameDispatchAdmission.Decision decision) {
        RtFrameDispatchAdmission.Decision rejected = Objects.requireNonNull(decision, "decision");
        if (rejected.accepted()) {
            throw new IllegalArgumentException("accepted dispatch is not a rejection");
        }
        if (rejected.incrementsPresentationGateSkips()) {
            skippedPresentationGateFrames++;
        }
        if (rejected.incrementsUnavailableStateSkips()) {
            skippedUnavailableFrameStates++;
        }
        if (rejected.incrementsIntervalSkips()) {
            skippedIntervalFrames++;
        }
        if (rejected.incrementsPendingAsyncSkips()) {
            skippedPendingAsyncFrames++;
        }
        if (rejected.incrementsPendingBacklogSkips()) {
            skippedPendingBacklogFrames++;
        }
    }

    void recordNoFrameSlot() {
        skippedNoFrameSlots++;
        skippedPendingAsyncFrames++;
    }

    long nextDispatchOrdinal() {
        return Math.incrementExact(frameDispatches);
    }

    void recordSubmission(long dispatchOrdinal) {
        long expectedOrdinal = Math.incrementExact(frameDispatches);
        if (dispatchOrdinal != expectedOrdinal) {
            throw new IllegalStateException(
                    "non-contiguous frame dispatch ordinal: expected="
                            + expectedOrdinal
                            + ", actual=" + dispatchOrdinal
            );
        }
        frameDispatches = dispatchOrdinal;
        asyncFrameSubmissions++;
    }

    void recordCompletion() {
        asyncFrameCompletions++;
    }

    void recordReadbackIntervalSkip() {
        skippedReadbackIntervalFrames++;
    }

    void recordFrameSlotDescriptorRefresh() {
        frameSlotDescriptorRefreshes++;
    }

    void recordFreshnessCatchUpDispatch() {
        freshnessCatchUpFrameDispatches++;
    }

    void recordDispatchDuration(long elapsedNanos) {
        if (elapsedNanos < 0L) {
            throw new IllegalArgumentException("dispatch duration must not be negative");
        }
        long elapsedMillis = elapsedNanos / 1_000_000L;
        lastFrameDispatchMillis = elapsedMillis;
        totalFrameDispatchMillis += elapsedMillis;
        maxFrameDispatchMillis = Math.max(maxFrameDispatchMillis, elapsedMillis);
    }

    long observedFrameStates() {
        return observedFrameStates;
    }

    long skippedPresentationGateFrames() {
        return skippedPresentationGateFrames;
    }

    long skippedUnavailableFrameStates() {
        return skippedUnavailableFrameStates;
    }

    long skippedIntervalFrames() {
        return skippedIntervalFrames;
    }

    long skippedPendingAsyncFrames() {
        return skippedPendingAsyncFrames;
    }

    long skippedPendingBacklogFrames() {
        return skippedPendingBacklogFrames;
    }

    long freshnessCatchUpFrameDispatches() {
        return freshnessCatchUpFrameDispatches;
    }

    long frameDispatches() {
        return frameDispatches;
    }

    long skippedReadbackIntervalFrames() {
        return skippedReadbackIntervalFrames;
    }

    long skippedNoFrameSlots() {
        return skippedNoFrameSlots;
    }

    long frameSlotDescriptorRefreshes() {
        return frameSlotDescriptorRefreshes;
    }

    long asyncFrameSubmissions() {
        return asyncFrameSubmissions;
    }

    long asyncFrameCompletions() {
        return asyncFrameCompletions;
    }

    long lastFrameDispatchMillis() {
        return lastFrameDispatchMillis;
    }

    long maxFrameDispatchMillis() {
        return maxFrameDispatchMillis;
    }

    long totalFrameDispatchMillis() {
        return totalFrameDispatchMillis;
    }
}
