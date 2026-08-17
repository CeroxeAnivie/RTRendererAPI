package top.ceroxe.rt.renderer.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable per-target blending, optional logic operation, and blend constants. */
public final class BlendState {
    private final List<ColorTargetBlendState> targets;
    private final LogicOperation logicOperation;
    private final double constantRed;
    private final double constantGreen;
    private final double constantBlue;
    private final double constantAlpha;

    /**
     * Creates color-output merge state.
     *
     * @param targets one entry per color attachment, in attachment order
     * @param logicOperation nullable bitwise operation; when present it takes precedence over blending
     * @param constantRed finite red blend constant
     * @param constantGreen finite green blend constant
     * @param constantBlue finite blue blend constant
     * @param constantAlpha finite alpha blend constant
     */
    public BlendState(
            List<ColorTargetBlendState> targets,
            LogicOperation logicOperation,
            double constantRed,
            double constantGreen,
            double constantBlue,
            double constantAlpha
    ) {
        Objects.requireNonNull(targets, "targets");
        this.targets = targets.stream()
                .map(target -> Objects.requireNonNull(target, "color target blend state"))
                .toList();
        if (!Double.isFinite(constantRed) || !Double.isFinite(constantGreen)
                || !Double.isFinite(constantBlue) || !Double.isFinite(constantAlpha)) {
            throw new IllegalArgumentException("blend constants must be finite");
        }
        this.logicOperation = logicOperation;
        this.constantRed = constantRed;
        this.constantGreen = constantGreen;
        this.constantBlue = constantBlue;
        this.constantAlpha = constantAlpha;
    }

    /** Creates direct replacement state for the requested number of color attachments. */
    public static BlendState replace(int colorTargetCount) {
        if (colorTargetCount < 0) throw new IllegalArgumentException("color target count must be non-negative");
        return new BlendState(java.util.Collections.nCopies(
                colorTargetCount, ColorTargetBlendState.replace(ColorWriteMask.all())), null, 0.0, 0.0, 0.0, 0.0);
    }

    public List<ColorTargetBlendState> targets() { return targets; }
    public Optional<LogicOperation> logicOperation() { return Optional.ofNullable(logicOperation); }
    public double constantRed() { return constantRed; }
    public double constantGreen() { return constantGreen; }
    public double constantBlue() { return constantBlue; }
    public double constantAlpha() { return constantAlpha; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof BlendState that)) return false;
        return Double.compare(constantRed, that.constantRed) == 0
                && Double.compare(constantGreen, that.constantGreen) == 0
                && Double.compare(constantBlue, that.constantBlue) == 0
                && Double.compare(constantAlpha, that.constantAlpha) == 0
                && targets.equals(that.targets) && logicOperation == that.logicOperation;
    }

    @Override public int hashCode() {
        return Objects.hash(targets, logicOperation, constantRed, constantGreen, constantBlue, constantAlpha);
    }
}
