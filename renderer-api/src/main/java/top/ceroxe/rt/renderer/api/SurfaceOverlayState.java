package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Immutable material policy for receiver-aware surface compositing.
 *
 * <p>An enabled overlay still references ordinary persistent mesh geometry and therefore reuses
 * its BLAS. The renderer excludes it from shadow rays and resolves it against the next underlying
 * receiver hit, avoiding duplicate geometry uploads while retaining exact ray-visible coverage.</p>
 */
public final class SurfaceOverlayState {
    private static final SurfaceOverlayState DISABLED = new SurfaceOverlayState(DepthMode.DISABLED, 0.0F);

    private final DepthMode depthMode;
    private final float depthThreshold;

    private SurfaceOverlayState(DepthMode depthMode, float depthThreshold) {
        this.depthMode = Objects.requireNonNull(depthMode, "depthMode");
        if (!Float.isFinite(depthThreshold) || depthThreshold < 0.0F) {
            throw new IllegalArgumentException("depthThreshold must be finite and non-negative");
        }
        if (depthMode == DepthMode.DISABLED && depthThreshold != 0.0F) {
            throw new IllegalArgumentException("disabled overlay must have zero depthThreshold");
        }
        this.depthThreshold = depthThreshold;
    }

    /**
     * Returns the shared disabled overlay policy.
     * @return disabled overlay policy
     */
    public static SurfaceOverlayState disabled() {
        return DISABLED;
    }

    /**
     * Requires the receiver to be no farther than {@code tolerance} world units behind the overlay.
     * @param tolerance finite non-negative receiver distance bound
     * @return immutable equal-depth overlay policy
     */
    public static SurfaceOverlayState depthEqual(float tolerance) {
        return new SurfaceOverlayState(DepthMode.EQUAL, tolerance);
    }

    /**
     * Allows an explicitly biased overlay to resolve over a receiver within {@code maximumBias}.
     * @param maximumBias finite non-negative receiver distance bound
     * @return immutable biased overlay policy
     */
    public static SurfaceOverlayState depthBias(float maximumBias) {
        return new SurfaceOverlayState(DepthMode.BIAS, maximumBias);
    }

    /**
     * Returns overlay depth mode.
     * @return non-null depth mode
     */
    public DepthMode depthMode() {
        return depthMode;
    }

    /**
     * Returns receiver depth threshold.
     * @return finite non-negative threshold
     */
    public float depthThreshold() {
        return depthThreshold;
    }

    /**
     * Reports whether receiver-aware overlay composition is enabled.
     * @return whether overlay composition is enabled
     */
    public boolean enabled() {
        return depthMode != DepthMode.DISABLED;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof SurfaceOverlayState state
                && depthMode == state.depthMode
                && Float.compare(depthThreshold, state.depthThreshold) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(depthMode, depthThreshold);
    }

    @Override
    public String toString() {
        return "SurfaceOverlayState[depthMode=" + depthMode
                + ", depthThreshold=" + depthThreshold + ']';
    }

    /** Overlay/receiver depth relationship evaluated by the ray-generation compositor. */
    public enum DepthMode {
        /** Disabled; the material renders as an ordinary surface. */
        DISABLED,
        /** Receiver must satisfy the equal-depth threshold. */
        EQUAL,
        /** Receiver may satisfy the explicit depth-bias threshold. */
        BIAS
    }
}
