package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * FIFO completion coordinator for asynchronous RT submissions.
 *
 * <p>Vulkan fences are polled strictly from the queue head. That preserves output image ordering
 * and prevents an already-completed newer slot from bypassing the publication proof held by an
 * older submission. The pipeline supplies completion side effects (publication, JFR, readback),
 * while this class owns queue mutation and poll accounting.</p>
 */
final class RtFrameSubmissionQueue {
    private final Deque<RtPendingFrameSubmission> submissions = new ArrayDeque<>();

    void enqueue(RtPendingFrameSubmission submission) {
        submissions.addLast(Objects.requireNonNull(submission, "submission"));
    }

    boolean isEmpty() {
        return submissions.isEmpty();
    }

    int size() {
        return submissions.size();
    }

    RtPendingFrameSubmission oldest() {
        return submissions.peekFirst();
    }

    Iterable<RtPendingFrameSubmission> pendingSubmissions() {
        return submissions;
    }

    PollReport drain(
            boolean wait,
            Consumer<RtPendingFrameSubmission> completedConsumer,
            Runnable closeWaitObserver
    ) {
        Objects.requireNonNull(completedConsumer, "completedConsumer");
        Objects.requireNonNull(closeWaitObserver, "closeWaitObserver");
        int completed = 0;
        while (!submissions.isEmpty()) {
            RtPendingFrameSubmission pending = submissions.peekFirst();
            if (wait) {
                pending.submission().close();
                closeWaitObserver.run();
            } else if (!pending.submission().pollComplete()) {
                pending.incrementPolls();
                return new PollReport(completed, pending);
            }
            submissions.removeFirst();
            completedConsumer.accept(pending);
            completed++;
        }
        return new PollReport(completed, null);
    }

    record PollReport(int completedCount, RtPendingFrameSubmission notReady) {
        PollReport {
            if (completedCount < 0) {
                throw new IllegalArgumentException("queue completion count must not be negative");
            }
        }
    }
}
