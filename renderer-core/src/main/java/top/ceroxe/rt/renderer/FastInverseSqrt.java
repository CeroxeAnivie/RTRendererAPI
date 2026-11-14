package top.ceroxe.rt.renderer;

/**
 * Small, guarded fast inverse square-root helper for CPU-side normalization.
 *
 * <p>The shader path should keep using GLSL {@code normalize()}, because the
 * Vulkan compiler can lower that to the GPU's native reciprocal-square-root
 * instruction. This helper is intentionally limited to Java hot paths where we
 * only need a stable normalization scale and can keep all invalid inputs out at
 * the boundary.</p>
 */
public final class FastInverseSqrt {
    private static final int QUAKE_FLOAT_MAGIC = 0x5f3759df;
    private static final float NEWTON_THREE_HALVES = 1.5F;
    private static final float NEWTON_HALF = 0.5F;

    private FastInverseSqrt() {
    }

    /**
     * 计算有限正浮点数的稳定近似平方根倒数。
     *
     * @param value 有限正输入
     * @return {@code 1 / sqrt(value)} 的两步牛顿迭代近似
     */
    public static float inverseSqrt(float value) {
        if (!Float.isFinite(value) || value <= 0.0F) {
            throw new IllegalArgumentException("value must be positive and finite");
        }
        if (value < Float.MIN_NORMAL) {
            /*
             * The bit-level seed assumes a normalized IEEE-754 float. Very tiny
             * camera vectors are rare and guarded by callers, so preserving
             * correctness here is more important than forcing the fast path.
             */
            return (float) (1.0D / Math.sqrt(value));
        }

        float half = NEWTON_HALF * value;
        int bits = Float.floatToRawIntBits(value);
        float estimate = Float.intBitsToFloat(QUAKE_FLOAT_MAGIC - (bits >> 1));
        estimate *= NEWTON_THREE_HALVES - half * estimate * estimate;
        estimate *= NEWTON_THREE_HALVES - half * estimate * estimate;
        return estimate;
    }
}
