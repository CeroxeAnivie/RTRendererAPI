package top.ceroxe.rt.renderer.api;

/**
 * Immutable integer framebuffer scissor rectangle.
 *
 * @param x non-negative x origin
 * @param y non-negative y origin
 * @param width positive width
 * @param height positive height
 */
public record ScissorRectangle(int x, int y, int width, int height) {
    /** Validates bounds without permitting exclusive-end overflow. */
    public ScissorRectangle {
        if (x < 0 || y < 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("scissor origin must be non-negative and extent must be positive");
        }
        try {
            Math.addExact(x, width);
            Math.addExact(y, height);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("scissor rectangle end overflows int", overflow);
        }
    }

    public int endXExclusive() { return Math.addExact(x, width); }

    public int endYExclusive() { return Math.addExact(y, height); }
}
