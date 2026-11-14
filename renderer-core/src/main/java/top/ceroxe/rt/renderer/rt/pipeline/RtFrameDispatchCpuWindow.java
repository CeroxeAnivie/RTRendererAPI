package top.ceroxe.rt.renderer.rt.pipeline;

import java.util.Arrays;
import java.util.Objects;

/**
 * Bounded one-second aggregation window for per-stage frame-dispatch CPU time.
 */
final class RtFrameDispatchCpuWindow {
    private static final long WINDOW_NANOS = 1_000_000_000L;

    private final long[] stageNanos = new long[RtFrameDispatchTiming.Stage.values().length];
    private long startedNanos;
    private long samples;

    /**
     * Records one complete stage vector and returns a log fragment only when the window closes.
     * Returning data instead of logging here keeps diagnostics I/O outside the state owner.
     */
    String record(long[] stages, long nowNanos) {
        Objects.requireNonNull(stages, "stages");
        if (stages.length != stageNanos.length) {
            throw new IllegalArgumentException(
                    "dispatch CPU stage vector length mismatch: expected="
                            + stageNanos.length + ", actual=" + stages.length
            );
        }
        if (startedNanos == 0L) {
            startedNanos = nowNanos;
        }
        samples++;
        for (RtFrameDispatchTiming.Stage stage : RtFrameDispatchTiming.Stage.values()) {
            stageNanos[stage.ordinal()] += stages[stage.ordinal()];
        }
        if (nowNanos - startedNanos < WINDOW_NANOS) {
            return null;
        }

        StringBuilder details = new StringBuilder("samples=").append(samples).append(", micros={");
        for (RtFrameDispatchTiming.Stage stage : RtFrameDispatchTiming.Stage.values()) {
            if (stage.ordinal() > 0) {
                details.append(',');
            }
            details.append(stage.logName()).append('=').append(stageNanos[stage.ordinal()] / 1_000L);
        }
        details.append('}');
        startedNanos = nowNanos;
        samples = 0L;
        Arrays.fill(stageNanos, 0L);
        return details.toString();
    }
}
