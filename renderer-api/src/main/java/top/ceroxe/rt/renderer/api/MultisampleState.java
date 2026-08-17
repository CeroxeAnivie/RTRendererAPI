package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Immutable coverage and sample-shading state for a graphics pipeline. */
public final class MultisampleState {
    private final int sampleCount;
    private final long sampleMask;
    private final boolean sampleShadingEnabled;
    private final double minimumSampleShading;
    private final boolean alphaToCoverageEnabled;
    private final boolean alphaToOneEnabled;

    /** Creates multisample state with an explicit unsigned 64-bit coverage mask. */
    public MultisampleState(
            int sampleCount,
            long sampleMask,
            boolean sampleShadingEnabled,
            double minimumSampleShading,
            boolean alphaToCoverageEnabled,
            boolean alphaToOneEnabled
    ) {
        if (sampleCount <= 0 || sampleCount > Long.SIZE || (sampleCount & (sampleCount - 1)) != 0) {
            throw new IllegalArgumentException("sample count must be a power of two in [1, 64]");
        }
        long legalBits = sampleCount == Long.SIZE ? -1L : (1L << sampleCount) - 1L;
        if ((sampleMask & ~legalBits) != 0L) {
            throw new IllegalArgumentException("sample mask contains bits beyond the declared sample count");
        }
        if (!Double.isFinite(minimumSampleShading) || minimumSampleShading < 0.0
                || minimumSampleShading > 1.0) {
            throw new IllegalArgumentException("minimum sample shading must be finite and contained in [0, 1]");
        }
        this.sampleCount = sampleCount;
        this.sampleMask = sampleMask;
        this.sampleShadingEnabled = sampleShadingEnabled;
        this.minimumSampleShading = minimumSampleShading;
        this.alphaToCoverageEnabled = alphaToCoverageEnabled;
        this.alphaToOneEnabled = alphaToOneEnabled;
    }

    /** Returns state in which every declared sample is covered and per-sample shading is disabled. */
    public static MultisampleState allSamples(int sampleCount) {
        if (sampleCount <= 0 || sampleCount > Long.SIZE || (sampleCount & (sampleCount - 1)) != 0) {
            throw new IllegalArgumentException("sample count must be a power of two in [1, 64]");
        }
        long mask = sampleCount == Long.SIZE ? -1L : (1L << sampleCount) - 1L;
        return new MultisampleState(sampleCount, mask, false, 0.0, false, false);
    }

    /** @return conventional single-sample state */
    public static MultisampleState singleSample() { return allSamples(1); }

    public int sampleCount() { return sampleCount; }
    public long sampleMask() { return sampleMask; }
    public boolean sampleShadingEnabled() { return sampleShadingEnabled; }
    public double minimumSampleShading() { return minimumSampleShading; }
    public boolean alphaToCoverageEnabled() { return alphaToCoverageEnabled; }
    public boolean alphaToOneEnabled() { return alphaToOneEnabled; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof MultisampleState that)) return false;
        return sampleCount == that.sampleCount && sampleMask == that.sampleMask
                && sampleShadingEnabled == that.sampleShadingEnabled
                && Double.compare(minimumSampleShading, that.minimumSampleShading) == 0
                && alphaToCoverageEnabled == that.alphaToCoverageEnabled
                && alphaToOneEnabled == that.alphaToOneEnabled;
    }

    @Override public int hashCode() {
        return Objects.hash(sampleCount, sampleMask, sampleShadingEnabled, minimumSampleShading,
                alphaToCoverageEnabled, alphaToOneEnabled);
    }
}
