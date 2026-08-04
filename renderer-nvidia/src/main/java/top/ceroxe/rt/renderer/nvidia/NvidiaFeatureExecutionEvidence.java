package top.ceroxe.rt.renderer.nvidia;

import top.ceroxe.rt.renderer.api.TechnologyExecutionEvidence;

import java.util.EnumMap;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Thread-safe CPU ledger separating recorded, queue-accepted, GPU-completed, and presented work.
 *
 * <p>The render thread records frame-local facts, queue acceptance retains GPU feature work until
 * the matching fence completion, and the presenter thread closes Reflex evidence. This owner
 * performs no JNI and never calls back into the feature session, preventing presenter/session
 * lock inversion.</p>
 */
final class NvidiaFeatureExecutionEvidence {
    private final NavigableSet<Long> renderIntervalsAwaitingPresent = new TreeSet<>();
    private final NavigableMap<Long, SubmissionWork> featureSubmissionsAwaitingCompletion =
            new TreeMap<>();
    private final EnumMap<Lane, MutableActivity> activities = new EnumMap<>(Lane.class);
    private PendingSubmission pending;
    private boolean lowLatencyPresentCommitted;
    private long latestCommittedSubmission = -1L;
    private long latestSuccessfulPresent = -1L;

    synchronized void recordDenoising(long sequence) {
        PendingSubmission submission = pending(sequence);
        if (submission.denoisingRecorded) return;
        submission.denoisingRecorded = true;
        mutableActivity(Lane.DENOISING).record(sequence);
    }

    synchronized void recordReconstruction(long sequence, NvidiaStreamlineRuntime.Feature feature) {
        if (feature != NvidiaStreamlineRuntime.Feature.DLSS
                && feature != NvidiaStreamlineRuntime.Feature.NIS) {
            throw new IllegalArgumentException("invalid reconstruction evidence feature: " + feature);
        }
        PendingSubmission submission = pending(sequence);
        if (submission.reconstructionFeature != null && submission.reconstructionFeature != feature) {
            throw new IllegalStateException("one submission recorded multiple reconstruction implementations");
        }
        if (submission.reconstructionFeature == null) {
            submission.reconstructionFeature = feature;
            mutableActivity(lane(feature)).record(sequence);
        }
    }

    synchronized void recordLowLatencyRenderEnd(long sequence) {
        PendingSubmission submission = pending(sequence);
        if (submission.lowLatencyRenderEndRecorded) return;
        submission.lowLatencyRenderEndRecorded = true;
        mutableActivity(Lane.LOW_LATENCY).record(sequence);
    }

    synchronized void commitSubmission(long sequence) {
        if (sequence < 0L) throw new IllegalArgumentException("sequence must not be negative");
        if (sequence <= latestCommittedSubmission) {
            throw new IllegalStateException("submission evidence sequence did not advance");
        }
        latestCommittedSubmission = sequence;
        if (pending == null) return;
        requireSequence(sequence);
        SubmissionWork committed = new SubmissionWork(
                pending.denoisingRecorded,
                pending.reconstructionFeature,
                pending.lowLatencyRenderEndRecorded
        );
        if (committed.denoisingRecorded() || committed.reconstructionFeature() != null) {
            featureSubmissionsAwaitingCompletion.put(sequence, committed);
        }
        if (committed.denoisingRecorded()) mutableActivity(Lane.DENOISING).accept();
        if (committed.reconstructionFeature() != null) {
            mutableActivity(lane(committed.reconstructionFeature())).accept();
        }
        if (committed.lowLatencyRenderEndRecorded()) mutableActivity(Lane.LOW_LATENCY).accept();
        if (pending.lowLatencyRenderEndRecorded) renderIntervalsAwaitingPresent.add(sequence);
        pending = null;
    }

    synchronized SubmissionWork completeSubmission(long sequence) {
        if (sequence < 0L) throw new IllegalArgumentException("sequence must not be negative");
        if (sequence > latestCommittedSubmission) {
            throw new IllegalStateException("completion precedes submission evidence");
        }
        SubmissionWork completed = featureSubmissionsAwaitingCompletion.remove(sequence);
        if (completed == null) return SubmissionWork.empty();
        if (completed.denoisingRecorded()) mutableActivity(Lane.DENOISING).complete(sequence);
        if (completed.reconstructionFeature() != null) {
            mutableActivity(lane(completed.reconstructionFeature())).complete(sequence);
        }
        return completed;
    }

    synchronized void discardSubmission(long sequence) {
        if (pending == null) return;
        requireSequence(sequence);
        pending = null;
    }

    synchronized void observePresent(long sequence, boolean succeeded) {
        if (sequence < 0L) throw new IllegalArgumentException("sequence must not be negative");
        boolean renderIntervalCommitted = renderIntervalsAwaitingPresent.remove(sequence);
        renderIntervalsAwaitingPresent.headSet(sequence, true).clear();
        if (succeeded && renderIntervalCommitted) {
            lowLatencyPresentCommitted = true;
            mutableActivity(Lane.LOW_LATENCY).complete(sequence);
        }
        if (succeeded) latestSuccessfulPresent = Math.max(latestSuccessfulPresent, sequence);
    }

    synchronized boolean lowLatencyPresentCommitted() {
        return lowLatencyPresentCommitted;
    }

    synchronized long latestCommittedSubmission() {
        return latestCommittedSubmission;
    }

    synchronized long latestSuccessfulPresent() {
        return latestSuccessfulPresent;
    }

    synchronized Activity activity(Lane lane) {
        MutableActivity value = activities.get(lane);
        return value == null ? Activity.empty() : value.snapshot();
    }

    synchronized void completeFallback(Lane lane, long sequence) {
        if (sequence < 0L) throw new IllegalArgumentException("sequence must not be negative");
        MutableActivity fallback = mutableActivity(lane);
        fallback.record(sequence);
        fallback.accept();
        fallback.complete(sequence);
    }

    private MutableActivity mutableActivity(Lane lane) {
        return activities.computeIfAbsent(lane, ignored -> new MutableActivity());
    }

    private static Lane lane(NvidiaStreamlineRuntime.Feature feature) {
        return feature == NvidiaStreamlineRuntime.Feature.NIS ? Lane.NIS : Lane.DLSS;
    }

    private PendingSubmission pending(long sequence) {
        if (sequence < 0L) throw new IllegalArgumentException("sequence must not be negative");
        if (pending == null) pending = new PendingSubmission(sequence);
        else requireSequence(sequence);
        return pending;
    }

    private void requireSequence(long sequence) {
        if (pending.sequence != sequence) {
            throw new IllegalStateException(
                    "evidence frame " + sequence + " does not match pending frame " + pending.sequence
            );
        }
    }

    record SubmissionWork(
            boolean denoisingRecorded,
            NvidiaStreamlineRuntime.Feature reconstructionFeature,
            boolean lowLatencyRenderEndRecorded
    ) {
        static SubmissionWork empty() {
            return new SubmissionWork(false, null, false);
        }
    }

    enum Lane {
        DENOISING,
        DLSS,
        NIS,
        LOW_LATENCY,
        DENOISING_FALLBACK,
        RECONSTRUCTION_FALLBACK,
        FRAME_GENERATION_FALLBACK,
        LOW_LATENCY_FALLBACK
    }

    record Activity(
            long recorded,
            long queueAccepted,
            long gpuCompleted,
            long output,
            long firstSequence,
            long lastSequence,
            long lastOutputSequence,
            TechnologyExecutionEvidence.SequenceDomain sequenceDomain,
            long resetEpoch
    ) {
        static Activity empty() {
            return new Activity(
                    0L, 0L, 0L, 0L, -1L, -1L, -1L,
                    TechnologyExecutionEvidence.SequenceDomain.NONE, 0L
            );
        }
    }

    private static final class MutableActivity {
        private long recorded;
        private long queueAccepted;
        private long gpuCompleted;
        private long output;
        private long firstSequence = -1L;
        private long lastSequence = -1L;
        private long lastOutputSequence = -1L;

        private void record(long sequence) {
            recorded = Math.incrementExact(recorded);
            firstSequence = firstSequence < 0L ? sequence : Math.min(firstSequence, sequence);
            lastSequence = Math.max(lastSequence, sequence);
        }

        private void accept() {
            queueAccepted = Math.incrementExact(queueAccepted);
        }

        private void complete(long sequence) {
            gpuCompleted = Math.incrementExact(gpuCompleted);
            output = Math.incrementExact(output);
            lastOutputSequence = Math.max(lastOutputSequence, sequence);
        }

        private Activity snapshot() {
            return new Activity(
                    recorded,
                    queueAccepted,
                    gpuCompleted,
                    output,
                    firstSequence,
                    lastSequence,
                    lastOutputSequence,
                    TechnologyExecutionEvidence.SequenceDomain.RENDERER_FRAME,
                    0L
            );
        }
    }

    private static final class PendingSubmission {
        private final long sequence;
        private boolean denoisingRecorded;
        private NvidiaStreamlineRuntime.Feature reconstructionFeature;
        private boolean lowLatencyRenderEndRecorded;

        private PendingSubmission(long sequence) {
            this.sequence = sequence;
        }
    }
}
