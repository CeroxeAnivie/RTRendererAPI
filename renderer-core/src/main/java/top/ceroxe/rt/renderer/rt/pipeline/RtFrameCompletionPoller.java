package top.ceroxe.rt.renderer.rt.pipeline;

import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.rt.device.RtGpuImage;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Owns FIFO fence polling, outer-frame coalescing and pending-age diagnostics.
 */
final class RtFrameCompletionPoller {
    private final RtFrameSubmissionQueue submissions = new RtFrameSubmissionQueue();
    private final RendererRtDiagnostics diagnostics;
    private long activeFrameEpoch = -1L;
    private long lastPolledEpoch = Long.MIN_VALUE;
    private long pollsNotReady;
    private long coalescedPolls;
    private long closeWaits;
    private long lastCompletedPolls;
    private long maxPolls;
    private long lastAgeMillis;
    private long maxAgeMillis;

    RtFrameCompletionPoller(RendererRtDiagnostics diagnostics) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    static long ageMillis(RtPendingFrameSubmission pending, long nowNanos) {
        return Math.max(0L, nowNanos - pending.submittedNanos()) / 1_000_000L;
    }

    void beginFrame(long frameStateSequence, boolean frameReadbackEnabled, Consumer<RtPendingFrameSubmission> completion) {
        if (frameStateSequence < 0L) {
            throw new IllegalArgumentException("frameStateSequence must not be negative");
        }
        if (frameStateSequence != activeFrameEpoch) {
            activeFrameEpoch = frameStateSequence;
            lastPolledEpoch = Long.MIN_VALUE;
        }
        pollForActiveFrame(frameReadbackEnabled, completion);
    }

    void pollForActiveFrame(boolean frameReadbackEnabled, Consumer<RtPendingFrameSubmission> completion) {
        if (frameReadbackEnabled || activeFrameEpoch < 0L) {
            poll(false, completion);
            return;
        }
        if (lastPolledEpoch == activeFrameEpoch) {
            coalescedPolls++;
            return;
        }
        lastPolledEpoch = activeFrameEpoch;
        poll(false, completion);
    }

    void poll(boolean wait, Consumer<RtPendingFrameSubmission> completion) {
        RtFrameSubmissionQueue.PollReport report = submissions.drain(
                wait,
                pending -> {
                    lastCompletedPolls = pending.polls();
                    completion.accept(pending);
                },
                () -> closeWaits++
        );
        RtPendingFrameSubmission pending = report.notReady();
        if (pending == null) {
            return;
        }
        pollsNotReady++;
        diagnostics.edges().edgeOnce(
                "frameNotReady:" + pending.dispatchOrdinal(),
                "framePollNotReady",
                "dispatchOrdinal=" + pending.dispatchOrdinal()
                        + ", frameSequence=" + pending.frameStateSequence()
                        + ", pendingFrames=" + submissions.size()
        );
        long ageMillis = ageMillis(pending, System.nanoTime());
        lastAgeMillis = ageMillis;
        maxAgeMillis = Math.max(maxAgeMillis, ageMillis);
        maxPolls = Math.max(maxPolls, pending.polls());
    }

    void enqueue(RtPendingFrameSubmission submission) {
        submissions.enqueue(submission);
    }

    boolean isEmpty() {
        return submissions.isEmpty();
    }

    int size() {
        return submissions.size();
    }

    RtPendingFrameSubmission oldest() {
        return submissions.oldest();
    }

    Iterable<RtPendingFrameSubmission> pendingSubmissions() {
        return submissions.pendingSubmissions();
    }

    long newestFrameStateSequence() {
        long newest = -1L;
        for (RtPendingFrameSubmission pending : submissions.pendingSubmissions()) {
            newest = Math.max(newest, pending.frameStateSequence());
        }
        return newest;
    }

    boolean writesImage(RtGpuImage image) {
        if (image == null) {
            return false;
        }
        for (RtPendingFrameSubmission pending : submissions.pendingSubmissions()) {
            if (pending.outputImage() == image) {
                return true;
            }
        }
        return false;
    }

    long pollsNotReady() {
        return pollsNotReady;
    }

    long coalescedPolls() {
        return coalescedPolls;
    }

    long closeWaits() {
        return closeWaits;
    }

    long lastCompletedPolls() {
        return lastCompletedPolls;
    }

    long maxPolls() {
        return maxPolls;
    }

    long lastAgeMillis() {
        return lastAgeMillis;
    }

    long maxAgeMillis() {
        return maxAgeMillis;
    }
}
