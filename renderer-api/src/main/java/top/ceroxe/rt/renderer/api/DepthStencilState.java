package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Immutable depth, depth-bounds, and front/back stencil state. */
public final class DepthStencilState {
    private final boolean depthTestEnabled;
    private final boolean depthWriteEnabled;
    private final CompareOperation depthCompare;
    private final boolean depthBoundsTestEnabled;
    private final double minimumDepthBounds;
    private final double maximumDepthBounds;
    private final boolean stencilTestEnabled;
    private final StencilFaceState frontStencil;
    private final StencilFaceState backStencil;

    /**
     * Creates complete depth/stencil state.
     *
     * @param depthTestEnabled whether fragment depth is compared
     * @param depthWriteEnabled whether passing fragments update depth storage
     * @param depthCompare depth comparison, retained even while the test is disabled
     * @param depthBoundsTestEnabled whether normalized depth bounds are tested
     * @param minimumDepthBounds finite normalized lower bound
     * @param maximumDepthBounds finite normalized upper bound
     * @param stencilTestEnabled whether front/back stencil rules execute
     * @param frontStencil front-facing polygon stencil state
     * @param backStencil back-facing polygon stencil state
     */
    public DepthStencilState(
            boolean depthTestEnabled,
            boolean depthWriteEnabled,
            CompareOperation depthCompare,
            boolean depthBoundsTestEnabled,
            double minimumDepthBounds,
            double maximumDepthBounds,
            boolean stencilTestEnabled,
            StencilFaceState frontStencil,
            StencilFaceState backStencil
    ) {
        this.depthCompare = Objects.requireNonNull(depthCompare, "depthCompare");
        if (!Double.isFinite(minimumDepthBounds) || !Double.isFinite(maximumDepthBounds)
                || minimumDepthBounds < 0.0 || maximumDepthBounds > 1.0
                || minimumDepthBounds > maximumDepthBounds) {
            throw new IllegalArgumentException("depth bounds must be finite, ordered, and contained in [0, 1]");
        }
        this.frontStencil = Objects.requireNonNull(frontStencil, "frontStencil");
        this.backStencil = Objects.requireNonNull(backStencil, "backStencil");
        this.depthTestEnabled = depthTestEnabled;
        this.depthWriteEnabled = depthWriteEnabled;
        this.depthBoundsTestEnabled = depthBoundsTestEnabled;
        this.minimumDepthBounds = minimumDepthBounds;
        this.maximumDepthBounds = maximumDepthBounds;
        this.stencilTestEnabled = stencilTestEnabled;
    }

    /** Returns a depth/stencil state with all tests and writes disabled. */
    public static DepthStencilState disabled() {
        StencilFaceState keep = StencilFaceState.keep();
        return new DepthStencilState(false, false, CompareOperation.ALWAYS, false, 0.0, 1.0, false, keep, keep);
    }

    /** Returns conventional writable less-than depth testing without stencil. */
    public static DepthStencilState depthWriteLess() {
        StencilFaceState keep = StencilFaceState.keep();
        return new DepthStencilState(true, true, CompareOperation.LESS, false, 0.0, 1.0, false, keep, keep);
    }

    public boolean depthTestEnabled() { return depthTestEnabled; }
    public boolean depthWriteEnabled() { return depthWriteEnabled; }
    public CompareOperation depthCompare() { return depthCompare; }
    public boolean depthBoundsTestEnabled() { return depthBoundsTestEnabled; }
    public double minimumDepthBounds() { return minimumDepthBounds; }
    public double maximumDepthBounds() { return maximumDepthBounds; }
    public boolean stencilTestEnabled() { return stencilTestEnabled; }
    public StencilFaceState frontStencil() { return frontStencil; }
    public StencilFaceState backStencil() { return backStencil; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof DepthStencilState that)) return false;
        return depthTestEnabled == that.depthTestEnabled && depthWriteEnabled == that.depthWriteEnabled
                && depthBoundsTestEnabled == that.depthBoundsTestEnabled
                && Double.compare(minimumDepthBounds, that.minimumDepthBounds) == 0
                && Double.compare(maximumDepthBounds, that.maximumDepthBounds) == 0
                && stencilTestEnabled == that.stencilTestEnabled && depthCompare == that.depthCompare
                && frontStencil.equals(that.frontStencil) && backStencil.equals(that.backStencil);
    }

    @Override public int hashCode() {
        return Objects.hash(depthTestEnabled, depthWriteEnabled, depthCompare, depthBoundsTestEnabled,
                minimumDepthBounds, maximumDepthBounds, stencilTestEnabled, frontStencil, backStencil);
    }
}
