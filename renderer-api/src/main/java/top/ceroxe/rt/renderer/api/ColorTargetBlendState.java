package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Immutable blend and write state for one color attachment index. */
public final class ColorTargetBlendState {
    private final boolean blendEnabled;
    private final BlendFactor sourceColorFactor;
    private final BlendFactor destinationColorFactor;
    private final BlendOperation colorOperation;
    private final BlendFactor sourceAlphaFactor;
    private final BlendFactor destinationAlphaFactor;
    private final BlendOperation alphaOperation;
    private final ColorWriteMask writeMask;

    /** Creates complete color and alpha blend state for one target. */
    public ColorTargetBlendState(
            boolean blendEnabled,
            BlendFactor sourceColorFactor,
            BlendFactor destinationColorFactor,
            BlendOperation colorOperation,
            BlendFactor sourceAlphaFactor,
            BlendFactor destinationAlphaFactor,
            BlendOperation alphaOperation,
            ColorWriteMask writeMask
    ) {
        this.sourceColorFactor = Objects.requireNonNull(sourceColorFactor, "sourceColorFactor");
        this.destinationColorFactor = Objects.requireNonNull(destinationColorFactor, "destinationColorFactor");
        this.colorOperation = Objects.requireNonNull(colorOperation, "colorOperation");
        this.sourceAlphaFactor = Objects.requireNonNull(sourceAlphaFactor, "sourceAlphaFactor");
        this.destinationAlphaFactor = Objects.requireNonNull(destinationAlphaFactor, "destinationAlphaFactor");
        this.alphaOperation = Objects.requireNonNull(alphaOperation, "alphaOperation");
        this.writeMask = Objects.requireNonNull(writeMask, "writeMask");
        this.blendEnabled = blendEnabled;
    }

    /** Returns disabled blending with direct fragment-output replacement. */
    public static ColorTargetBlendState replace(ColorWriteMask writeMask) {
        return new ColorTargetBlendState(false,
                BlendFactor.ONE, BlendFactor.ZERO, BlendOperation.ADD,
                BlendFactor.ONE, BlendFactor.ZERO, BlendOperation.ADD,
                writeMask);
    }

    /** Returns conventional straight-alpha source-over blending. */
    public static ColorTargetBlendState sourceOver(ColorWriteMask writeMask) {
        return new ColorTargetBlendState(true,
                BlendFactor.SOURCE_ALPHA, BlendFactor.ONE_MINUS_SOURCE_ALPHA, BlendOperation.ADD,
                BlendFactor.ONE, BlendFactor.ONE_MINUS_SOURCE_ALPHA, BlendOperation.ADD,
                writeMask);
    }

    public boolean blendEnabled() { return blendEnabled; }
    public BlendFactor sourceColorFactor() { return sourceColorFactor; }
    public BlendFactor destinationColorFactor() { return destinationColorFactor; }
    public BlendOperation colorOperation() { return colorOperation; }
    public BlendFactor sourceAlphaFactor() { return sourceAlphaFactor; }
    public BlendFactor destinationAlphaFactor() { return destinationAlphaFactor; }
    public BlendOperation alphaOperation() { return alphaOperation; }
    public ColorWriteMask writeMask() { return writeMask; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ColorTargetBlendState that)) return false;
        return blendEnabled == that.blendEnabled && sourceColorFactor == that.sourceColorFactor
                && destinationColorFactor == that.destinationColorFactor && colorOperation == that.colorOperation
                && sourceAlphaFactor == that.sourceAlphaFactor
                && destinationAlphaFactor == that.destinationAlphaFactor && alphaOperation == that.alphaOperation
                && writeMask.equals(that.writeMask);
    }

    @Override public int hashCode() {
        return Objects.hash(blendEnabled, sourceColorFactor, destinationColorFactor, colorOperation,
                sourceAlphaFactor, destinationAlphaFactor, alphaOperation, writeMask);
    }
}
