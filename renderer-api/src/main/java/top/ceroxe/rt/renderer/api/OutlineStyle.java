package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Immutable screen-space outline style associated with one object-mask producer. */
public final class OutlineStyle {
    /** Maximum supported outline radius in output pixels. */
    public static final float MAX_WIDTH_PIXELS = 8.0F;

    private static final OutlineStyle DISABLED = new OutlineStyle(0, 0.0F);

    private final int colorRgba8;
    private final float widthPixels;

    private OutlineStyle(int colorRgba8, float widthPixels) {
        if (!Float.isFinite(widthPixels) || widthPixels < 0.0F || widthPixels > MAX_WIDTH_PIXELS) {
            throw new IllegalArgumentException("widthPixels must be finite and within [0, "
                    + MAX_WIDTH_PIXELS + "]");
        }
        if (widthPixels > 0.0F && ((colorRgba8 >>> 24) & 0xff) == 0) {
            throw new IllegalArgumentException("enabled outline color must have non-zero alpha");
        }
        this.colorRgba8 = colorRgba8;
        this.widthPixels = widthPixels;
    }

    /** Returns the shared disabled style. */
    /**
     * Returns the shared disabled outline style.
     * @return disabled outline style
     */
    public static OutlineStyle disabled() {
        return DISABLED;
    }

    /**
     * Creates an enabled outline.
     * @param colorRgba8 packed RGBA8 color with non-zero alpha
     * @param widthPixels finite output-pixel radius
     * @return immutable outline style
     */
    public static OutlineStyle of(int colorRgba8, float widthPixels) {
        return widthPixels == 0.0F ? DISABLED : new OutlineStyle(colorRgba8, widthPixels);
    }

    /**
     * Returns packed linear RGBA8 outline color.
     * @return packed color
     */
    public int colorRgba8() {
        return colorRgba8;
    }

    /**
     * Returns outline radius in output pixels.
     * @return radius, or zero when disabled
     */
    public float widthPixels() {
        return widthPixels;
    }

    /**
     * Reports whether this style requests outline compositing.
     * @return whether the outline is enabled
     */
    public boolean enabled() {
        return widthPixels > 0.0F;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof OutlineStyle style
                && colorRgba8 == style.colorRgba8
                && Float.compare(widthPixels, style.widthPixels) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(colorRgba8, widthPixels);
    }

    @Override
    public String toString() {
        return "OutlineStyle[colorRgba8=" + colorRgba8 + ", widthPixels=" + widthPixels + ']';
    }
}
