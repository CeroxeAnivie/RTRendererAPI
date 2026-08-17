package top.ceroxe.rt.renderer.api;

/**
 * Immutable floating-point viewport using a zero-to-one depth interval.
 *
 * @param x framebuffer x origin
 * @param y framebuffer y origin
 * @param width positive viewport width
 * @param height positive viewport height
 * @param minimumDepth minimum mapped depth in {@code [0, 1]}
 * @param maximumDepth maximum mapped depth in {@code [0, 1]}
 */
public record Viewport(
        float x,
        float y,
        float width,
        float height,
        float minimumDepth,
        float maximumDepth
) {
    /** Rejects non-finite coordinates and invalid extents or depth bounds. */
    public Viewport {
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(width, "width");
        requireFinite(height, "height");
        requireFinite(minimumDepth, "minimumDepth");
        requireFinite(maximumDepth, "maximumDepth");
        if (width <= 0.0f || height <= 0.0f) {
            throw new IllegalArgumentException("viewport width and height must be positive");
        }
        if (!Float.isFinite(x + width) || !Float.isFinite(y + height)) {
            throw new IllegalArgumentException("viewport exclusive end must remain finite");
        }
        if (minimumDepth < 0.0f || maximumDepth > 1.0f || minimumDepth > maximumDepth) {
            throw new IllegalArgumentException("viewport depth bounds must be ordered within [0, 1]");
        }
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("viewport " + name + " must be finite");
    }
}
