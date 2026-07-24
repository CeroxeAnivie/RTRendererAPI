package top.ceroxe.mcvulkanrt.renderer.rt.material;

/** Renderer-owned equivalents of host's distinct color-target blend equations. */
public enum RtBlendMode {
    OPAQUE(0, false),
    TRANSLUCENT(1, true),
    PREMULTIPLIED_ALPHA(2, true),
    ADDITIVE(3, true),
    LIGHTNING(4, true),
    OVERLAY(5, true),
    OUTLINE_BLIT(6, true),
    INVERT(7, true),
    MULTIPLY(8, true);

    private final int faceCode;
    private final boolean translucent;

    RtBlendMode(int faceCode, boolean translucent) {
        this.faceCode = faceCode;
        this.translucent = translucent;
    }

    public int faceCode() {
        return faceCode;
    }

    public boolean translucent() {
        return translucent;
    }

    public static RtBlendMode fromFaceCode(int faceCode) {
        for (RtBlendMode mode : values()) {
            if (mode.faceCode == faceCode) {
                return mode;
            }
        }
        throw new IllegalArgumentException("unknown dynamic face blend mode: " + faceCode);
    }
}
