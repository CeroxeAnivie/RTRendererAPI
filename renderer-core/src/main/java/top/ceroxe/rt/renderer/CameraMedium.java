package top.ceroxe.rt.renderer;

/**
 * Render-thread copy of the camera's current volumetric medium.
 *
 * <p>The renderer only needs stable optical properties: a tint and an extinction density.
 * Keeping this boundary data-oriented allows all participating media to use the same shader
 * path without retaining host objects or adding medium-specific branches.</p>
 *
 * @param active     whether the camera is inside a participating medium
 * @param packedRgba packed RGBA8 medium tint, canonicalized to zero when inactive
 * @param density    positive extinction density when active, canonicalized to zero otherwise
 */
public record CameraMedium(
        boolean active,
        int packedRgba,
        float density
) {
    private static final CameraMedium AIR = new CameraMedium(false, 0, 0.0F);

    /**
     * 规范化非活动状态并校验活动介质的有限正密度。
     */
    public CameraMedium {
        if (!active) {
            packedRgba = 0;
            density = 0.0F;
        } else {
            if (!Float.isFinite(density) || density <= 0.0F) {
                throw new IllegalArgumentException("active camera fluid density must be positive and finite");
            }
            density = clamp(density, 0.001F, 4.0F);
            if (((packedRgba >>> 24) & 0xFF) == 0) {
                packedRgba = (packedRgba & 0x00FF_FFFF) | 0xFF00_0000;
            }
        }
    }

    /**
     * 返回规范化的空气介质状态。
     *
     * @return 共享的空气介质状态
     */
    public static CameraMedium air() {
        return AIR;
    }

    /**
     * 创建活动流体介质状态。
     *
     * @param rgb24   低 24 位表示的 RGB 颜色
     * @param alpha8  透明度，超出 0..255 的值会被截断
     * @param density 有限正消光密度
     * @return 规范化后的活动介质状态
     */
    public static CameraMedium fluid(int rgb24, int alpha8, float density) {
        return new CameraMedium(true, packRgba8(rgb24, alpha8), density);
    }

    /**
     * 将 RGB24 与透明度编码为渲染器使用的 RGBA8 整数布局。
     *
     * @param rgb24  低 24 位表示的 RGB 颜色
     * @param alpha8 透明度，超出 0..255 的值会被截断
     * @return 打包后的 RGBA8 值
     */
    public static int packRgba8(int rgb24, int alpha8) {
        int red = (rgb24 >>> 16) & 0xFF;
        int green = (rgb24 >>> 8) & 0xFF;
        int blue = rgb24 & 0xFF;
        int alpha = clamp(alpha8, 0, 255);
        return red | (green << 8) | (blue << 16) | (alpha << 24);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 将介质状态格式化为稳定的诊断字段。
     *
     * @return 可直接拼接到单行诊断日志的介质摘要
     */
    public String asLogFragment() {
        if (!active) {
            return "cameraMedium=air";
        }
        return "cameraMedium=fluid"
                + ", mediumRgba=0x" + String.format(java.util.Locale.ROOT, "%08X", packedRgba)
                + ", mediumDensity=" + density;
    }
}
