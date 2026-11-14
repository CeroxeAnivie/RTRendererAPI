package top.ceroxe.rt.renderer.rt.material;

/**
 * Renderer-owned color-target blend equations.
 */
public enum RtBlendMode {
    /**
     * Fully opaque replacement.
     */
    OPAQUE(0, false),
    /**
     * Conventional source-alpha translucency.
     */
    TRANSLUCENT(1, true),
    /**
     * Premultiplied-alpha compositing.
     */
    PREMULTIPLIED_ALPHA(2, true),
    /**
     * Additive color accumulation.
     */
    ADDITIVE(3, true),
    /**
     * High-energy additive beam compositing.
     */
    LIGHTNING(4, true),
    /**
     * Alpha-weighted overlay compositing.
     */
    OVERLAY(5, true),
    /**
     * Outline-mask blit compositing.
     */
    OUTLINE_BLIT(6, true),
    /**
     * Destination-color inversion.
     */
    INVERT(7, true),
    /**
     * Multiplicative color modulation.
     */
    MULTIPLY(8, true);

    private final int faceCode;
    private final boolean translucent;

    RtBlendMode(int faceCode, boolean translucent) {
        this.faceCode = faceCode;
        this.translucent = translucent;
    }

    /**
     * Resolves a blend mode from its stable GPU face code.
     *
     * @param faceCode encoded blend-mode value
     * @return matching blend mode
     * @throws IllegalArgumentException when the code is unknown
     */
    public static RtBlendMode fromFaceCode(int faceCode) {
        for (RtBlendMode mode : values()) {
            if (mode.faceCode == faceCode) {
                return mode;
            }
        }
        throw new IllegalArgumentException("unknown dynamic face blend mode: " + faceCode);
    }

    /**
     * Returns the stable GPU face code.
     *
     * @return encoded blend-mode value
     */
    public int faceCode() {
        return faceCode;
    }

    /**
     * Tests whether this mode requires translucent ordering or blending.
     *
     * @return {@code true} for non-opaque blend modes
     */
    public boolean translucent() {
        return translucent;
    }
}
