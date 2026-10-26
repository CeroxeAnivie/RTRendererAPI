package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Immutable application timeline sampled once for a submitted frame.
 *
 * <p>Nanoseconds have an application-defined origin, including negative origins. Differences use
 * signed subtraction, as with System.nanoTime(); intervals must be shorter than 2^63 nanoseconds.
 * The same request retains the same time across admission retries. Pause, seek and deterministic
 * playback are explicit application policy; this clock never reads wall-clock time.</p>
 */
public record FrameClock(long timeNanos, long deltaNanos) {
    public FrameClock {
        if (deltaNanos < 0) throw new IllegalArgumentException("clock delta must not be negative");
    }

    /** Samples the JVM monotonic clock. Delta is zero until measured against a previous sample. */
    public static FrameClock system() { return new FrameClock(System.nanoTime(), 0); }

    /** Creates an explicit timeline, suitable for paused, deterministic or simulated frames. */
    public static FrameClock of(long timeNanos, long deltaNanos) { return new FrameClock(timeNanos, deltaNanos); }

    /** Measures elapsed time from a preceding sample on the same timeline; rejects a rewind. */
    public FrameClock fromPrevious(FrameClock previous) {
        long elapsed = timeNanos - Objects.requireNonNull(previous, "previous").timeNanos;
        if (elapsed < 0) throw new IllegalArgumentException("clock moved backwards or exceeded its difference range");
        return new FrameClock(timeNanos, elapsed);
    }

    /** Returns elapsed seconds for policies that do not require nanosecond precision. */
    public double deltaSeconds() { return deltaNanos / 1_000_000_000.0; }
}
