package top.ceroxe.rt.renderer.api;

/**
 * Finite non-negative linear RGB values. Values above one represent HDR energy.
 *
 * @param red red channel, finite and non-negative
 * @param green green channel, finite and non-negative
 * @param blue blue channel, finite and non-negative
 */
public record Rgb(float red, float green, float blue) {
    public static final Rgb ZERO = new Rgb(0, 0, 0);
    public static final Rgb WHITE = new Rgb(1, 1, 1);

    public Rgb {
        requireChannel(red, "red");
        requireChannel(green, "green");
        requireChannel(blue, "blue");
        red = red == 0 ? 0 : red;
        green = green == 0 ? 0 : green;
        blue = blue == 0 ? 0 : blue;
    }

    private static void requireChannel(float value, String name) {
        if (!Float.isFinite(value) || value < 0)
            throw new IllegalArgumentException(name + " must be finite and non-negative");
    }

    /** Returns the channel-wise sum, rejecting float overflow. */
    public Rgb add(Rgb other) {
        java.util.Objects.requireNonNull(other, "other");
        return new Rgb(red + other.red, green + other.green, blue + other.blue);
    }

    /** Returns the scaled value; the scale must be finite and non-negative. */
    public Rgb multiply(float scale) {
        requireChannel(scale, "scale");
        return new Rgb(red * scale, green * scale, blue * scale);
    }

    public float maxComponent() { return Math.max(red, Math.max(green, blue)); }
    public boolean isZero() { return red == 0 && green == 0 && blue == 0; }
}
