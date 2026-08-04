package demo;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import top.ceroxe.rt.renderer.api.RendererDiagnostics;

final class RenderStats {
    final AtomicLong submittedFrames = new AtomicLong();
    final AtomicLong rejectedSubmissions = new AtomicLong();
    final AtomicLong presentedFrames = new AtomicLong();

    private final LongSupplier nanoTime;
    private final AtomicLong measurementStartNanos = new AtomicLong();
    private final AtomicLong measurementStartFrame = new AtomicLong();
    private final AtomicLong firstPresentedNanos = new AtomicLong();
    private final AtomicLong lastPresentedNanos = new AtomicLong();
    private final AtomicLong rejectionMeasurementStartNanos = new AtomicLong();
    private final AtomicLong rejectionMeasurementStartCount = new AtomicLong();
    private volatile double framesPerSecond;
    private volatile double rejectedPerSecond;
    private volatile double averageTraceMillis;
    private volatile double averageGpuMillis;
    private final AtomicLong presentationCallNanos = new AtomicLong();
    private final AtomicLong presentationCallSamples = new AtomicLong();
    private volatile int latestLuminanceRange;
    private volatile long latestChromaticPixels;
    private volatile int latestPixelCount;

    RenderStats() {
        this(System::nanoTime);
    }

    RenderStats(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        long now = nanoTime.getAsLong();
        measurementStartNanos.set(now);
        rejectionMeasurementStartNanos.set(now);
    }

    void framePresented() {
        long currentFrame = presentedFrames.incrementAndGet();
        long now = nanoTime.getAsLong();
        if (firstPresentedNanos.compareAndSet(0L, now)) {
            // Presentation measurement must not include renderer/window startup. The first
            // completed present establishes the sole epoch used by rolling and total FPS.
            measurementStartFrame.set(currentFrame);
            measurementStartNanos.set(now);
            lastPresentedNanos.set(now);
            return;
        }
        lastPresentedNanos.set(now);
        long start = measurementStartNanos.get();
        if (now - start < 500_000_000L) {
            return;
        }
        long startFrame = measurementStartFrame.get();
        framesPerSecond = (currentFrame - startFrame) * 1.0E9 / (now - start);
        measurementStartFrame.set(currentFrame);
        measurementStartNanos.set(now);
    }

    double framesPerSecond() {
        double rolling = framesPerSecond;
        return rolling > 0.0 ? rolling : totalPresentationFramesPerSecond();
    }

    double totalPresentationFramesPerSecond() {
        long presented = presentedFrames.get();
        long first = firstPresentedNanos.get();
        long last = lastPresentedNanos.get();
        return presented > 1L && last > first
                ? (presented - 1L) * 1.0E9 / (last - first)
                : 0.0;
    }

    /**
     * Returns the number of application frames that have been accepted but not retired by the
     * presenter.  The GPU producer uses this as the explicit producer-lead contract instead of
     * repeatedly submitting into a full bounded ring and turning normal backpressure into a hot
     * rejection loop.
     */
    long presentationLead() {
        long lead = submittedFrames.get() - presentedFrames.get();
        return Math.max(0L, lead);
    }

    void submissionRejected() {
        long count = rejectedSubmissions.incrementAndGet();
        long now = nanoTime.getAsLong();
        long start = rejectionMeasurementStartNanos.get();
        if (now - start < 500_000_000L) return;
        long startCount = rejectionMeasurementStartCount.get();
        rejectedPerSecond = (count - startCount) * 1.0E9 / (now - start);
        rejectionMeasurementStartCount.set(count);
        rejectionMeasurementStartNanos.set(now);
    }

    double rejectedPerSecond() {
        return rejectedPerSecond;
    }

    String summary() {
        return summary(totalPresentationFramesPerSecond());
    }

    String summary(double presentTotalFps) {
        if (!Double.isFinite(presentTotalFps) || presentTotalFps < 0.0) {
            throw new IllegalArgumentException("presentTotalFps must be finite and non-negative");
        }
        long presented = presentedFrames.get();
        return "submitted=" + submittedFrames.get()
                + ", presented=" + presented
                + ", rejected=" + rejectedSubmissions.get()
                + ", nativeFps=" + String.format(
                        java.util.Locale.ROOT, "%.1f", framesPerSecond()
                )
                + ", outputFps=" + String.format(
                        java.util.Locale.ROOT, "%.1f", presentTotalFps
                )
                + ", gpuFps=" + String.format(java.util.Locale.ROOT, "%.1f", gpuCapacityFramesPerSecond())
                + ", gpuFrameMs=" + String.format(java.util.Locale.ROOT, "%.2f", averageGpuMillis)
                + ", presentCallMs=" + String.format(
                        java.util.Locale.ROOT, "%.2f", averagePresentationCallMillis()
                );
    }

    void observePresentationCall(long elapsedNanos) {
        if (elapsedNanos < 0L) throw new IllegalArgumentException("elapsedNanos must not be negative");
        presentationCallNanos.addAndGet(elapsedNanos);
        presentationCallSamples.incrementAndGet();
    }

    double averagePresentationCallMillis() {
        long samples = presentationCallSamples.get();
        return samples == 0L
                ? 0.0
                : presentationCallNanos.get() / 1_000_000.0 / samples;
    }

    void observeGpuTiming(RendererDiagnostics.FrameGpuTiming timing) {
        if (!timing.enabled() || timing.completedSamples() == 0L) return;
        // GPUSceneFrame timing spans TLAS build, trace, post-trace and publication. Keep the
        // legacy trace field populated for API compatibility, but expose this value to users only
        // as full GPU-frame capacity until true stage checkpoints are added.
        averageTraceMillis = timing.averageTraceNanos() / 1_000_000.0;
        averageGpuMillis = timing.averageTotalNanos() / 1_000_000.0;
    }

    double averageTraceMillis() {
        return averageTraceMillis;
    }

    double averageGpuMillis() {
        return averageGpuMillis;
    }

    double gpuCapacityFramesPerSecond() {
        return averageGpuMillis <= 0.0 ? 0.0 : 1_000.0 / averageGpuMillis;
    }

    double traceCapacityFramesPerSecond() {
        return averageTraceMillis <= 0.0 ? 0.0 : 1_000.0 / averageTraceMillis;
    }

    void observeFrameContent(int luminanceRange, long chromaticPixels, int pixelCount) {
        latestLuminanceRange = luminanceRange;
        latestChromaticPixels = chromaticPixels;
        latestPixelCount = pixelCount;
    }

    void requireVisibleColorContent() {
        if (latestPixelCount <= 0 || latestLuminanceRange < 16) {
            throw new IllegalStateException(
                    "rendered frame lacks visible luminance range: " + latestLuminanceRange
            );
        }
        long minimumChromaticPixels = Math.max(1L, latestPixelCount / 1_000L);
        if (latestChromaticPixels < minimumChromaticPixels) {
            throw new IllegalStateException(
                    "rendered frame lacks colorful lighting: chromaticPixels=" + latestChromaticPixels
            );
        }
    }
}
