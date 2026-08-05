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
    private static final SurfaceOverlayState DISABLED = new SurfaceOverlayState(
            DepthMode.DISABLED, 0.0F, CompositionMode.ALPHA_OVER
    );

    private final DepthMode depthMode;
    private final float depthThreshold;
    private final CompositionMode compositionMode;

    private SurfaceOverlayState(
            DepthMode depthMode,
            float depthThreshold,
            CompositionMode compositionMode
    ) {
        this.depthMode = Objects.requireNonNull(depthMode, "depthMode");
        this.compositionMode = Objects.requireNonNull(compositionMode, "compositionMode");
        if (!Float.isFinite(depthThreshold) || depthThreshold < 0.0F) {
            throw new IllegalArgumentException("depthThreshold must be finite and non-negative");
        }
        if (depthMode == DepthMode.DISABLED && depthThreshold != 0.0F) {
            throw new IllegalArgumentException("disabled overlay must have zero depthThreshold");
        }
        if (depthMode == DepthMode.DISABLED && compositionMode != CompositionMode.ALPHA_OVER) {
            throw new IllegalArgumentException("disabled overlay must use ALPHA_OVER composition");
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
        return depthEqual(tolerance, CompositionMode.ALPHA_OVER);
    }

    /**
     * Requires an equal-depth receiver and applies the selected composition operation.
     * @param tolerance finite non-negative receiver distance bound
     * @param compositionMode non-null receiver composition operation
     * @return immutable equal-depth overlay policy
     */
    public static SurfaceOverlayState depthEqual(
            float tolerance,
            CompositionMode compositionMode
    ) {
        return new SurfaceOverlayState(DepthMode.EQUAL, tolerance, compositionMode);
    }

    /**
     * Allows an explicitly biased overlay to resolve over a receiver within {@code maximumBias}.
     * @param maximumBias finite non-negative receiver distance bound
     * @return immutable biased overlay policy
     */
    public static SurfaceOverlayState depthBias(float maximumBias) {
        return depthBias(maximumBias, CompositionMode.ALPHA_OVER);
    }

    /**
     * Allows a biased receiver and applies the selected composition operation.
     * @param maximumBias finite non-negative receiver distance bound
     * @param compositionMode non-null receiver composition operation
     * @return immutable biased overlay policy
     */
    public static SurfaceOverlayState depthBias(
            float maximumBias,
            CompositionMode compositionMode
    ) {
        return new SurfaceOverlayState(DepthMode.BIAS, maximumBias, compositionMode);
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
     * Returns the operation used to combine overlay and receiver radiance.
     * @return non-null composition mode
     */
    public CompositionMode compositionMode() {
        return compositionMode;
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
                && Float.compare(depthThreshold, state.depthThreshold) == 0
                && compositionMode == state.compositionMode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(depthMode, depthThreshold, compositionMode);
    }

    @Override
    public String toString() {
        return "SurfaceOverlayState[depthMode=" + depthMode
                + ", depthThreshold=" + depthThreshold
                + ", compositionMode=" + compositionMode + ']';
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

    /** Receiver-aware overlay composition operation. */
    public enum CompositionMode {
        /** Interpolates from receiver radiance to overlay radiance using overlay alpha. */
        ALPHA_OVER,
        /** Multiplies receiver radiance by the alpha-weighted overlay RGB modulation. */
        MULTIPLY
    }
}
