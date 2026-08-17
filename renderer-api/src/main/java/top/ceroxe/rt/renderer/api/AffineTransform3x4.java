package top.ceroxe.rt.renderer.api;

/** Immutable finite row-major 3x4 affine transform used by a top-level AS instance. */
public record AffineTransform3x4(
        float m00, float m01, float m02, float m03,
        float m10, float m11, float m12, float m13,
        float m20, float m21, float m22, float m23
) {
    /** Rejects non-finite transforms before the native instance record is packed. */
    public AffineTransform3x4 {
        float[] values = {m00, m01, m02, m03, m10, m11, m12, m13, m20, m21, m22, m23};
        for (float value : values) if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("AS instance transform values must be finite");
        }
    }

    /** @return identity transform */
    public static AffineTransform3x4 identity() {
        return new AffineTransform3x4(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0);
    }
}
