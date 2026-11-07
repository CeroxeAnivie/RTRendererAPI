package top.ceroxe.rt.renderer.api;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable equirectangular HDR environment radiance in row-major linear RGB order.
 * Rows run from +Y at the top to -Y at the bottom. Channels are finite and
 * non-negative and may exceed one. No sRGB conversion or ambient multiplier is applied.
 * An environment map replaces constant sky radiance and its ambient multiplier;
 * the separate sun remains independent.
 */
public final class EnvironmentMap {
    private final int width;
    private final int height;
    private final float[] linearRgb;
    private final int cachedHash;

    /**
     * Copies and validates final linear environment radiance.
     * @param width image width in {@code [2, 8192]}
     * @param height image height in {@code [1, 4096]}
     * @param linearRgb exactly {@code width * height * 3} finite non-negative RGB channels
     * @throws IllegalArgumentException if dimensions, channel count or radiance are invalid
     * @throws NullPointerException if the channel array is null
     */
    public EnvironmentMap(int width, int height, float[] linearRgb) {
        if (width < 2 || width > 8192 || height < 1 || height > 4096) {
            throw new IllegalArgumentException("environment map dimensions must be within 2x1 and 8192x4096");
        }
        int channels = Math.multiplyExact(Math.multiplyExact(width, height), 3);
        Objects.requireNonNull(linearRgb, "linearRgb");
        if (linearRgb.length != channels) {
            throw new IllegalArgumentException("environment map requires width * height * 3 channels");
        }
        this.linearRgb = linearRgb.clone();
        for (float channel : this.linearRgb) {
            if (!Float.isFinite(channel) || channel < 0.0F) {
                throw new IllegalArgumentException("environment radiance must be finite and non-negative");
            }
        }
        this.width = width;
        this.height = height;
        cachedHash = 31 * (31 * width + height) + Arrays.hashCode(this.linearRgb);
    }

    /** @return image width in pixels */
    public int width() { return width; }

    /** @return image height in pixels */
    public int height() { return height; }

    /** @return an independent copy of row-major linear RGB radiance */
    public float[] linearRgb() { return linearRgb.clone(); }

    /**
     * Copies radiance into caller-owned storage without an intermediate array.
     * @param target destination array
     * @param offset destination index of the first RGB channel
     * @throws IndexOutOfBoundsException if the destination range does not fit
     * @throws NullPointerException if the destination is null
     */
    public void copyLinearRgbTo(float[] target, int offset) {
        System.arraycopy(linearRgb, 0, target, offset, linearRgb.length);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        return other instanceof EnvironmentMap map
                && width == map.width && height == map.height && cachedHash == map.cachedHash
                && Arrays.equals(linearRgb, map.linearRgb);
    }

    @Override
    public int hashCode() { return cachedHash; }

    @Override
    public String toString() {
        return "EnvironmentMap[width=" + width + ", height=" + height + ", channels=" + linearRgb.length + ']';
    }
}
