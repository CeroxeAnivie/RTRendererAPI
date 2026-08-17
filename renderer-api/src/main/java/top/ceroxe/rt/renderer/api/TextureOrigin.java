package top.ceroxe.rt.renderer.api;

/** Non-negative texel origin within one texture mip and layer/depth region. */
public record TextureOrigin(int x, int y, int z) {
    public TextureOrigin {
        if (x < 0 || y < 0 || z < 0) throw new IllegalArgumentException("texture origin coordinates must not be negative");
    }
}
