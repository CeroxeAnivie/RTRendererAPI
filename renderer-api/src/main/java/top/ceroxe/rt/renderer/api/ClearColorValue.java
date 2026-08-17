package top.ceroxe.rt.renderer.api;

/** Immutable finite RGBA clear value in linear component space. */
public record ClearColorValue(float red, float green, float blue, float alpha) {
    /** Rejects NaN and infinity before a backend can serialize the native clear value. */
    public ClearColorValue {
        if (!Float.isFinite(red) || !Float.isFinite(green) || !Float.isFinite(blue) || !Float.isFinite(alpha)) {
            throw new IllegalArgumentException("clear color components must be finite");
        }
    }
}
