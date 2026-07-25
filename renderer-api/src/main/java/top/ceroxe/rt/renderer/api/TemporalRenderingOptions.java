package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Immutable renderer-lifetime temporal reconstruction policy.
 *
 * <p>The presets deliberately expose intent instead of backend thresholds. This keeps applications
 * independent of Vulkan resource layouts while allowing the renderer to improve rejection,
 * clamping, and accumulation algorithms without changing the public contract.</p>
 */
public final class TemporalRenderingOptions {
    /**
     * Default bounded history length used by the balanced production preset.
     */
    public static final int BALANCED_HISTORY_FRAMES = 8;
    /**
     * Minimum history length accepted by the explicit accumulating preset.
     */
    public static final int MIN_HISTORY_FRAMES = 2;
    /**
     * Maximum history length accepted by the explicit accumulating preset.
     */
    public static final int MAX_HISTORY_FRAMES = 64;

    private static final TemporalRenderingOptions DISABLED =
            new TemporalRenderingOptions(Mode.DISABLED, 0);
    private static final TemporalRenderingOptions BALANCED =
            new TemporalRenderingOptions(Mode.BALANCED, BALANCED_HISTORY_FRAMES);

    private final Mode mode;
    private final int maxHistoryFrames;

    private TemporalRenderingOptions(Mode mode, int maxHistoryFrames) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.maxHistoryFrames = maxHistoryFrames;
    }

    /**
     * Returns a policy that performs no temporal reconstruction or full-resolution history allocation.
     * Backends may retain constant-size descriptor sentinels that are independent of frame extent.
     *
     * @return canonical disabled policy
     */
    public static TemporalRenderingOptions disabled() {
        return DISABLED;
    }

    /**
     * Returns the production default balancing stability, latency, and bounded memory use.
     *
     * @return canonical balanced policy
     */
    public static TemporalRenderingOptions balanced() {
        return BALANCED;
    }

    /**
     * Returns an explicit bounded accumulation policy.
     *
     * @param maxHistoryFrames maximum contributing frames in {@code [2, 64]}
     * @return immutable accumulation policy
     * @throws IllegalArgumentException when the requested history length is outside the contract
     */
    public static TemporalRenderingOptions accumulating(int maxHistoryFrames) {
        if (maxHistoryFrames < MIN_HISTORY_FRAMES || maxHistoryFrames > MAX_HISTORY_FRAMES) {
            throw new IllegalArgumentException("maxHistoryFrames must be in [2, 64]");
        }
        return new TemporalRenderingOptions(Mode.ACCUMULATING, maxHistoryFrames);
    }

    /**
     * Returns the semantic temporal mode.
     *
     * @return non-null temporal mode
     */
    public Mode mode() {
        return mode;
    }

    /**
     * Returns the maximum contributing history length, or zero when temporal rendering is disabled.
     *
     * @return bounded history length or zero
     */
    public int maxHistoryFrames() {
        return maxHistoryFrames;
    }

    /**
     * Returns whether this policy requires full-resolution temporal GPU resources.
     *
     * @return {@code true} unless temporal rendering is disabled
     */
    public boolean enabled() {
        return mode != Mode.DISABLED;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof TemporalRenderingOptions options
                && mode == options.mode
                && maxHistoryFrames == options.maxHistoryFrames;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, maxHistoryFrames);
    }

    @Override
    public String toString() {
        return "TemporalRenderingOptions[mode=" + mode
                + ", maxHistoryFrames=" + maxHistoryFrames + ']';
    }

    /**
     * Stable application-facing temporal intent.
     */
    public enum Mode {
        /**
         * Do not allocate or consume temporal history.
         */
        DISABLED,
        /**
         * Use the renderer's production balance of stability and responsiveness.
         */
        BALANCED,
        /**
         * Accumulate up to the explicitly requested history length.
         */
        ACCUMULATING
    }
}
