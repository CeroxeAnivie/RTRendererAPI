package top.ceroxe.rt.renderer.rt.pipeline;

import top.ceroxe.rt.renderer.RtBuildTelemetrySink;

import java.util.Arrays;
import java.util.Objects;

/**
 * Bounded aggregate telemetry for the CPU particle spatial index.
 */
final class RtParticleTileTelemetry {
    private static final long WINDOW_NANOS = 1_000_000_000L;

    private final RtBuildTelemetrySink telemetry;
    private final long[] fallbackSamples =
            new long[RtRayTracingPipeline.ParticleTileFallback.values().length];
    private long windowStartNanos;
    private long samples;
    private long particles;
    private long references;
    private long occupiedTiles;
    private long candidateSum;
    private int maxCandidates;

    RtParticleTileTelemetry(RtBuildTelemetrySink telemetry) {
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    void record(int particleCount, RtParticleTilePlanner.Metrics index) {
        Objects.requireNonNull(index, "index");
        if (!telemetry.enabled()) {
            return;
        }
        if (particleCount < 0) {
            throw new IllegalArgumentException("particleCount must not be negative");
        }
        long now = System.nanoTime();
        if (windowStartNanos == 0L) {
            windowStartNanos = now;
        }
        samples++;
        particles += particleCount;
        references += index.referenceCount();
        int frameOccupiedTiles = 0;
        int frameCandidateSum = 0;
        int frameMaxCandidates = 0;
        for (int count : index.counts()) {
            if (count > 0) {
                frameOccupiedTiles++;
                frameCandidateSum = Math.addExact(frameCandidateSum, count);
                frameMaxCandidates = Math.max(frameMaxCandidates, count);
            }
        }
        occupiedTiles += frameOccupiedTiles;
        candidateSum += frameCandidateSum;
        maxCandidates = Math.max(maxCandidates, frameMaxCandidates);
        fallbackSamples[index.fallback().ordinal()]++;
        if (now - windowStartNanos < WINDOW_NANOS) {
            return;
        }
        publishWindow();
        windowStartNanos = now;
    }

    private void publishWindow() {
        StringBuilder fallbackSummary = new StringBuilder();
        for (RtRayTracingPipeline.ParticleTileFallback fallback
                : RtRayTracingPipeline.ParticleTileFallback.values()) {
            long count = fallbackSamples[fallback.ordinal()];
            if (fallback != RtRayTracingPipeline.ParticleTileFallback.NONE && count > 0L) {
                if (!fallbackSummary.isEmpty()) {
                    fallbackSummary.append('|');
                }
                fallbackSummary.append(fallback.name()).append(':').append(count);
            }
        }
        telemetry.aggregate(
                "particleTileIndex",
                "samples=" + samples
                        + ", particles=" + particles
                        + ", references=" + references
                        + ", occupiedTiles=" + occupiedTiles
                        + ", maxCandidatesPerTile=" + maxCandidates
                        + ", avgCandidatesPerOccupiedTile="
                        + (occupiedTiles == 0L ? 0.0D : candidateSum / (double) occupiedTiles)
                        + ", fallbacks=" + (fallbackSummary.isEmpty() ? "none" : fallbackSummary)
        );
        samples = 0L;
        particles = 0L;
        references = 0L;
        occupiedTiles = 0L;
        candidateSum = 0L;
        maxCandidates = 0;
        Arrays.fill(fallbackSamples, 0L);
    }
}
