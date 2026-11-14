package top.ceroxe.rt.renderer.rt.device;

import java.util.Objects;

/**
 * Accumulates one named accept-frame stage in nanosecond input and microsecond telemetry units.
 */
final class VulkanAcceptNanoTiming {
    private final String name;
    long samples;
    long lastMicros;
    long maxMicros;
    long totalMicros;
    private long frameMicros;

    VulkanAcceptNanoTiming(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    String name() {
        return name;
    }

    void record(long elapsedNanos) {
        long elapsedMicros = Math.max(0L, elapsedNanos / 1_000L);
        samples++;
        lastMicros = elapsedMicros;
        maxMicros = Math.max(maxMicros, elapsedMicros);
        totalMicros += elapsedMicros;
        frameMicros += elapsedMicros;
    }

    void resetFrameMicros() {
        frameMicros = 0L;
    }

    long frameMicros() {
        return frameMicros;
    }

    String summary() {
        return name + "{samples=" + samples
                + ", lastMicros=" + lastMicros
                + ", maxMicros=" + maxMicros
                + ", avgMicros=" + (samples == 0L ? 0L : totalMicros / samples)
                + "}";
    }
}
