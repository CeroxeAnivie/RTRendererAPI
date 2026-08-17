package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Immutable primitive rasterization state independent of viewport and scissor commands. */
public final class RasterState {
    public enum PolygonMode { FILL, LINE, POINT }
    public enum CullMode { NONE, FRONT, BACK, FRONT_AND_BACK }
    public enum FrontFace { COUNTER_CLOCKWISE, CLOCKWISE }

    private final boolean rasterizerDiscardEnabled;
    private final boolean depthClampEnabled;
    private final PolygonMode polygonMode;
    private final CullMode cullMode;
    private final FrontFace frontFace;
    private final boolean depthBiasEnabled;
    private final double depthBiasConstantFactor;
    private final double depthBiasClamp;
    private final double depthBiasSlopeFactor;
    private final double lineWidth;

    /** Creates a complete rasterization-state value. */
    public RasterState(
            boolean rasterizerDiscardEnabled,
            boolean depthClampEnabled,
            PolygonMode polygonMode,
            CullMode cullMode,
            FrontFace frontFace,
            boolean depthBiasEnabled,
            double depthBiasConstantFactor,
            double depthBiasClamp,
            double depthBiasSlopeFactor,
            double lineWidth
    ) {
        this.polygonMode = Objects.requireNonNull(polygonMode, "polygonMode");
        this.cullMode = Objects.requireNonNull(cullMode, "cullMode");
        this.frontFace = Objects.requireNonNull(frontFace, "frontFace");
        if (!Double.isFinite(depthBiasConstantFactor) || !Double.isFinite(depthBiasClamp)
                || !Double.isFinite(depthBiasSlopeFactor)) {
            throw new IllegalArgumentException("depth-bias values must be finite");
        }
        if (!Double.isFinite(lineWidth) || lineWidth <= 0.0) {
            throw new IllegalArgumentException("line width must be finite and positive");
        }
        this.rasterizerDiscardEnabled = rasterizerDiscardEnabled;
        this.depthClampEnabled = depthClampEnabled;
        this.depthBiasEnabled = depthBiasEnabled;
        this.depthBiasConstantFactor = depthBiasConstantFactor;
        this.depthBiasClamp = depthBiasClamp;
        this.depthBiasSlopeFactor = depthBiasSlopeFactor;
        this.lineWidth = lineWidth;
    }

    /** Returns conventional filled, back-face-culled counter-clockwise rasterization. */
    public static RasterState filled() {
        return new RasterState(false, false, PolygonMode.FILL, CullMode.BACK,
                FrontFace.COUNTER_CLOCKWISE, false, 0.0, 0.0, 0.0, 1.0);
    }

    public boolean rasterizerDiscardEnabled() { return rasterizerDiscardEnabled; }
    public boolean depthClampEnabled() { return depthClampEnabled; }
    public PolygonMode polygonMode() { return polygonMode; }
    public CullMode cullMode() { return cullMode; }
    public FrontFace frontFace() { return frontFace; }
    public boolean depthBiasEnabled() { return depthBiasEnabled; }
    public double depthBiasConstantFactor() { return depthBiasConstantFactor; }
    public double depthBiasClamp() { return depthBiasClamp; }
    public double depthBiasSlopeFactor() { return depthBiasSlopeFactor; }
    public double lineWidth() { return lineWidth; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RasterState that)) return false;
        return rasterizerDiscardEnabled == that.rasterizerDiscardEnabled
                && depthClampEnabled == that.depthClampEnabled && depthBiasEnabled == that.depthBiasEnabled
                && Double.compare(depthBiasConstantFactor, that.depthBiasConstantFactor) == 0
                && Double.compare(depthBiasClamp, that.depthBiasClamp) == 0
                && Double.compare(depthBiasSlopeFactor, that.depthBiasSlopeFactor) == 0
                && Double.compare(lineWidth, that.lineWidth) == 0 && polygonMode == that.polygonMode
                && cullMode == that.cullMode && frontFace == that.frontFace;
    }

    @Override public int hashCode() {
        return Objects.hash(rasterizerDiscardEnabled, depthClampEnabled, polygonMode, cullMode, frontFace,
                depthBiasEnabled, depthBiasConstantFactor, depthBiasClamp, depthBiasSlopeFactor, lineWidth);
    }
}
