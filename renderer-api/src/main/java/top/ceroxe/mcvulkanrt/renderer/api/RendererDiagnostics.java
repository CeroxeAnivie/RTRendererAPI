package top.ceroxe.mcvulkanrt.renderer.api;

/** Bounded host diagnostics with no internal cache or native-resource references. */
public record RendererDiagnostics(
        RayTracingRenderer.Status status,
        long latestAcceptedSceneRevision,
        long latestSubmittedFrameSequence,
        long latestCompletedFrameSequence,
        long residentMeshes,
        long residentInstances,
        FrameGpuTiming frameGpuTiming
) {
    public RendererDiagnostics {
        status = java.util.Objects.requireNonNull(status, "status");
        frameGpuTiming = java.util.Objects.requireNonNull(frameGpuTiming, "frameGpuTiming");
        if (latestAcceptedSceneRevision < 0L || latestSubmittedFrameSequence < -1L
                || latestCompletedFrameSequence < -1L || residentMeshes < 0L || residentInstances < 0L) {
            throw new IllegalArgumentException("renderer diagnostics counters are out of range");
        }
        if (latestCompletedFrameSequence > latestSubmittedFrameSequence) {
            throw new IllegalArgumentException("completed frame sequence must not exceed submitted sequence");
        }
    }

    public record FrameGpuTiming(
            boolean enabled,
            long completedSamples,
            long droppedSamples,
            long failedSamples,
            long averageTraceNanos,
            long averagePostTraceNanos,
            long averageTotalNanos,
            long maxTotalNanos
    ) {
        public FrameGpuTiming {
            if (completedSamples < 0L || droppedSamples < 0L || failedSamples < 0L
                    || averageTraceNanos < 0L || averagePostTraceNanos < 0L
                    || averageTotalNanos < 0L || maxTotalNanos < 0L) {
                throw new IllegalArgumentException("GPU timing values must not be negative");
            }
            long stageSum;
            try {
                stageSum = Math.addExact(averageTraceNanos, averagePostTraceNanos);
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("GPU timing stage sum overflowed", overflow);
            }
            if (Math.abs(averageTotalNanos - stageSum) > 2L) {
                throw new IllegalArgumentException("total GPU time must equal trace plus post-trace time");
            }
            if (!enabled && (completedSamples != 0L || droppedSamples != 0L || failedSamples != 0L
                    || averageTraceNanos != 0L || averagePostTraceNanos != 0L
                    || averageTotalNanos != 0L || maxTotalNanos != 0L)) {
                throw new IllegalArgumentException("disabled GPU timing must not publish samples or durations");
            }
            if (completedSamples == 0L
                    && (averageTraceNanos != 0L || averagePostTraceNanos != 0L
                    || averageTotalNanos != 0L || maxTotalNanos != 0L)) {
                throw new IllegalArgumentException("GPU timing without completed samples must have zero durations");
            }
            if (completedSamples > 0L && maxTotalNanos < averageTotalNanos) {
                throw new IllegalArgumentException("maximum GPU duration must not be below the average");
            }
        }

        public static FrameGpuTiming unavailable() {
            return new FrameGpuTiming(false, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        }
    }
}
