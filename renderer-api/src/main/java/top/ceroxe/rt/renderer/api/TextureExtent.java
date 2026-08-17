package top.ceroxe.rt.renderer.api;

/** Positive texel extent for one copy or upload region. */
public record TextureExtent(int width, int height, int depth) {
    public TextureExtent {
        if (width <= 0 || height <= 0 || depth <= 0) {
            throw new IllegalArgumentException("texture extent dimensions must be positive");
        }
    }
}
