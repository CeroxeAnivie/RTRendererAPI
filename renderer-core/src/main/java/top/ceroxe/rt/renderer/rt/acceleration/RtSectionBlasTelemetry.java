package top.ceroxe.rt.renderer.rt.acceleration;

import top.ceroxe.rt.renderer.RtBuildTelemetrySink;

import java.util.Objects;

/**
 * Owns bounded section-BLAS admission and scheduler stage telemetry.
 */
final class RtSectionBlasTelemetry {
    private final RtBuildTelemetrySink sink;
    private final EnqueueWindow enqueue = new EnqueueWindow();
    private final FrameBudgetWindow budget = new FrameBudgetWindow();

    RtSectionBlasTelemetry(RtBuildTelemetrySink sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    void recordEnqueue(
            long totalNanos,
            long lifecycleNanos,
            long priorityNanos,
            long sourceBookkeepingNanos,
            long materialCompatibilityNanos,
            long queueAdmissionNanos,
            int meshes,
            int removals,
            int materialOnlyUpdates,
            int queuedGeometryUpdates,
            boolean fullResync
    ) {
        enqueue.record(
                sink,
                totalNanos,
                lifecycleNanos,
                priorityNanos,
                sourceBookkeepingNanos,
                materialCompatibilityNanos,
                queueAdmissionNanos,
                meshes,
                removals,
                materialOnlyUpdates,
                queuedGeometryUpdates,
                fullResync
        );
    }

    void recordingPoll(long micros) {
        budget.recordingPollMicros += micros;
    }

    void recordingScan(long micros) {
        budget.recordingScanMicros += micros;
    }

    void recordingComplete(long micros) {
        budget.recordingCompleteMicros += micros;
    }

    void recordingNativeSubmit(long micros) {
        budget.recordingNativeSubmitMicros += micros;
    }

    void gpuPollApply(long micros) {
        budget.gpuPollApplyMicros += micros;
    }

    void gpuPoll(long micros) {
        budget.gpuPollMicros += micros;
    }

    void gpuApply(long micros) {
        budget.gpuApplyMicros += micros;
    }

    void coverageLimits(long micros) {
        budget.coverageLimitsMicros += micros;
    }

    void capacity(long micros) {
        budget.capacityMicros += micros;
    }

    void drain(long micros) {
        budget.drainMicros += micros;
    }

    void recordingEnqueue(long micros) {
        budget.recordingEnqueueMicros += micros;
    }

    void bookkeeping(long micros) {
        budget.bookkeepingMicros += micros;
    }

    void farField(long micros) {
        budget.farFieldMicros += micros;
    }

    void completePass(long totalMicros) {
        budget.totalMicros += totalMicros;
        budget.passes++;
        budget.maybePublish(sink);
    }

    private static final class EnqueueWindow {
        private long windowStartNanos;
        private long calls;
        private long totalNanos;
        private long lifecycleNanos;
        private long priorityNanos;
        private long sourceBookkeepingNanos;
        private long materialCompatibilityNanos;
        private long queueAdmissionNanos;
        private long meshes;
        private long removals;
        private long materialOnlyUpdates;
        private long queuedGeometryUpdates;
        private long fullResyncs;

        private void record(
                RtBuildTelemetrySink sink,
                long totalNanos,
                long lifecycleNanos,
                long priorityNanos,
                long sourceBookkeepingNanos,
                long materialCompatibilityNanos,
                long queueAdmissionNanos,
                int meshes,
                int removals,
                int materialOnlyUpdates,
                int queuedGeometryUpdates,
                boolean fullResync
        ) {
            long now = System.nanoTime();
            if (windowStartNanos == 0L) {
                windowStartNanos = now;
            }
            calls++;
            this.totalNanos += totalNanos;
            this.lifecycleNanos += lifecycleNanos;
            this.priorityNanos += priorityNanos;
            this.sourceBookkeepingNanos += sourceBookkeepingNanos;
            this.materialCompatibilityNanos += materialCompatibilityNanos;
            this.queueAdmissionNanos += queueAdmissionNanos;
            this.meshes += meshes;
            this.removals += removals;
            this.materialOnlyUpdates += materialOnlyUpdates;
            this.queuedGeometryUpdates += queuedGeometryUpdates;
            if (fullResync) {
                fullResyncs++;
            }
            if (now - windowStartNanos < 1_000_000_000L) {
                return;
            }
            sink.aggregate(
                    "sectionBlasEnqueue",
                    "windowMs=" + (now - windowStartNanos) / 1_000_000L
                            + ", calls=" + calls
                            + ", meshes=" + this.meshes
                            + ", removals=" + this.removals
                            + ", materialOnly=" + this.materialOnlyUpdates
                            + ", queuedGeometry=" + this.queuedGeometryUpdates
                            + ", fullResyncs=" + fullResyncs
                            + ", micros={total=" + this.totalNanos / 1_000L
                            + ", lifecycle=" + this.lifecycleNanos / 1_000L
                            + ", priority=" + this.priorityNanos / 1_000L
                            + ", sourceBookkeeping=" + this.sourceBookkeepingNanos / 1_000L
                            + ", materialCompatibility=" + this.materialCompatibilityNanos / 1_000L
                            + ", queueAdmission=" + this.queueAdmissionNanos / 1_000L + "}"
            );
            clear(now);
        }

        private void clear(long now) {
            windowStartNanos = now;
            calls = totalNanos = lifecycleNanos = priorityNanos = sourceBookkeepingNanos = 0L;
            materialCompatibilityNanos = queueAdmissionNanos = meshes = removals = 0L;
            materialOnlyUpdates = queuedGeometryUpdates = fullResyncs = 0L;
        }
    }

    private static final class FrameBudgetWindow {
        private final long[] previous = new long[15];
        private long passes;
        private long totalMicros;
        private long recordingPollMicros;
        private long recordingScanMicros;
        private long recordingCompleteMicros;
        private long recordingNativeSubmitMicros;
        private long gpuPollApplyMicros;
        private long gpuPollMicros;
        private long gpuApplyMicros;
        private long coverageLimitsMicros;
        private long capacityMicros;
        private long drainMicros;
        private long recordingEnqueueMicros;
        private long bookkeepingMicros;
        private long farFieldMicros;
        private long windowStartNanos;

        private void maybePublish(RtBuildTelemetrySink sink) {
            if (!sink.enabled()) {
                return;
            }
            long now = System.nanoTime();
            if (windowStartNanos == 0L) {
                windowStartNanos = now;
                remember();
                return;
            }
            if (now - windowStartNanos < 1_000_000_000L) {
                return;
            }
            long[] current = current();
            sink.aggregate(
                    "sectionBlasBudget",
                    "windowMs=" + (now - windowStartNanos) / 1_000_000L
                            + ", passes=" + delta(current, 0)
                            + ", totalMicros=" + delta(current, 1)
                            + ", stages={recordingPoll=" + delta(current, 2)
                            + ", gpuPollApply=" + delta(current, 6)
                            + ", coverageLimits=" + delta(current, 9)
                            + ", capacity=" + delta(current, 10)
                            + ", drain=" + delta(current, 11)
                            + ", recordingEnqueue=" + delta(current, 12)
                            + ", bookkeeping=" + delta(current, 13)
                            + ", farField=" + delta(current, 14) + "}"
                            + ", recordingDetail={scan=" + delta(current, 3)
                            + ", complete=" + delta(current, 4)
                            + ", nativeSubmit=" + delta(current, 5) + "}"
                            + ", gpuDetail={poll=" + delta(current, 7)
                            + ", apply=" + delta(current, 8) + "}"
            );
            windowStartNanos = now;
            System.arraycopy(current, 0, previous, 0, current.length);
        }

        private long[] current() {
            return new long[]{passes, totalMicros, recordingPollMicros, recordingScanMicros,
                    recordingCompleteMicros, recordingNativeSubmitMicros, gpuPollApplyMicros,
                    gpuPollMicros, gpuApplyMicros, coverageLimitsMicros, capacityMicros, drainMicros,
                    recordingEnqueueMicros, bookkeepingMicros, farFieldMicros};
        }

        private long delta(long[] current, int index) {
            return Math.max(0L, current[index] - previous[index]);
        }

        private void remember() {
            long[] current = current();
            System.arraycopy(current, 0, previous, 0, current.length);
        }
    }
}
