package top.ceroxe.rt.renderer.rt.acceleration;

import jdk.jfr.EventType;

/**
 * Bounded state-transition recorder for the gap between CPU-ready meshes and BLAS submission.
 * Repeating an identical blocked state faster than 100 ms adds no causal evidence and would turn
 * a high-FPS smoke test into an allocation test, so only state changes and periodic heartbeats
 * are retained.
 */
final class RtSectionBlasSchedulerFlightRecorder {
    private static final boolean ENABLED = Boolean.getBoolean("top.ceroxe.rt.takeoverFlightRecorder.enabled");
    private static final long REPEAT_INTERVAL_NANOS = 100_000_000L;

    private final EventType eventType;
    private long passSequence;
    private long lastSignature = Long.MIN_VALUE;
    private long lastEmissionNanos;
    private boolean failedClosed;

    RtSectionBlasSchedulerFlightRecorder() {
        EventType type = null;
        if (ENABLED) {
            try {
                type = EventType.getEventType(RtSectionBlasSchedulerEvent.class);
            } catch (RuntimeException | LinkageError ignored) {
                failedClosed = true;
            }
        }
        eventType = type;
    }

    private static long signature(
            String outcome,
            int pendingBuilds,
            int recordingBatches,
            int gpuBatches,
            int incompleteGpuBatches,
            boolean foregroundPending,
            boolean foregroundCoverageIncomplete,
            boolean firstWorldFrontPending
    ) {
        long value = outcome.hashCode();
        value = 31L * value + pendingBuilds;
        value = 31L * value + recordingBatches;
        value = 31L * value + gpuBatches;
        value = 31L * value + incompleteGpuBatches;
        value = 31L * value + (foregroundPending ? 1L : 0L);
        value = 31L * value + (foregroundCoverageIncomplete ? 1L : 0L);
        return 31L * value + (firstWorldFrontPending ? 1L : 0L);
    }

    void record(
            String outcome,
            int pendingBuilds,
            RtSectionAsyncBuildInventory inventory,
            boolean foregroundPending,
            boolean foregroundCoverageIncomplete,
            boolean firstWorldFrontPending,
            long frameBudgetNanos,
            long passStartNanos
    ) {
        if (!ENABLED || failedClosed || eventType == null || !eventType.isEnabled()) {
            return;
        }
        try {
            long now = System.nanoTime();
            int recordingBatches = inventory.recordingBatchCount();
            int gpuBatches = inventory.gpuBatchCount();
            int incompleteGpuBatches = inventory.incompleteGpuBatchCount();
            long signature = signature(
                    outcome,
                    pendingBuilds,
                    recordingBatches,
                    gpuBatches,
                    incompleteGpuBatches,
                    foregroundPending,
                    foregroundCoverageIncomplete,
                    firstWorldFrontPending
            );
            if (signature == lastSignature && now - lastEmissionNanos < REPEAT_INTERVAL_NANOS) {
                return;
            }
            RtSectionBlasSchedulerEvent event = new RtSectionBlasSchedulerEvent();
            event.outcome = outcome;
            event.passSequence = passSequence;
            event.pendingBuilds = pendingBuilds;
            event.recordingBatches = recordingBatches;
            event.gpuBatches = gpuBatches;
            event.incompleteGpuBatches = incompleteGpuBatches;
            event.foregroundPending = foregroundPending;
            event.foregroundCoverageIncomplete = foregroundCoverageIncomplete;
            event.firstWorldFrontPending = firstWorldFrontPending;
            event.frameBudgetNanos = frameBudgetNanos;
            event.elapsedNanos = Math.max(0L, now - passStartNanos);
            event.commit();
            lastSignature = signature;
            lastEmissionNanos = now;
        } catch (RuntimeException | LinkageError ignored) {
            failedClosed = true;
        }
    }

    void nextPass() {
        passSequence = Math.incrementExact(passSequence);
    }
}
