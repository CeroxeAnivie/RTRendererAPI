package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Immutable typed view of exactly one versioned texture subresource range.
 *
 * <p>Format reinterpretation is intentionally not exposed in the portable contract. A backend
 * that supports compatible aliases may offer it through a future capability-gated extension; an
 * unchecked reinterpretation would make cross-backend correctness impossible to verify.</p>
 */
public final class TextureView {
    private final TextureResource texture;
    private final TextureViewDimension dimension;
    private final TextureSubresourceRange range;

    /**
     * Creates a typed view.
     *
     * @param texture non-null source resource descriptor
     * @param dimension non-null exposed view shape
     * @param range non-null contained subresource range
     */
    public TextureView(TextureResource texture, TextureViewDimension dimension, TextureSubresourceRange range) {
        this.texture = Objects.requireNonNull(texture, "texture");
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.range = this.texture.requireContained(Objects.requireNonNull(range, "range"));
        validateDimension();
    }

    /** @return non-null versioned source texture */
    public TextureResource texture() { return texture; }

    /** @return non-null exposed view shape */
    public TextureViewDimension dimension() { return dimension; }

    /** @return non-null exact immutable subresource range */
    public TextureSubresourceRange range() { return range; }

    private void validateDimension() {
        switch (dimension) {
            case TEXTURE_1D -> {
                if (texture.dimension() != TextureDimension.TEXTURE_1D || range.arrayLayerCount() != 1) {
                    throw new IllegalArgumentException("one-dimensional views require a one-dimensional texture and one layer");
                }
            }
            case TEXTURE_1D_ARRAY -> {
                if (texture.dimension() != TextureDimension.TEXTURE_1D) {
                    throw new IllegalArgumentException("one-dimensional array views require a one-dimensional texture");
                }
            }
            case TEXTURE_2D -> {
                if (texture.dimension() != TextureDimension.TEXTURE_2D || texture.sampleCount() != 1
                        || range.arrayLayerCount() != 1) {
                    throw new IllegalArgumentException("two-dimensional views require a single-sample two-dimensional texture and one layer");
                }
            }
            case TEXTURE_2D_ARRAY -> {
                if (texture.dimension() != TextureDimension.TEXTURE_2D || texture.sampleCount() != 1) {
                    throw new IllegalArgumentException("two-dimensional array views require a single-sample two-dimensional texture");
                }
            }
            case TEXTURE_2D_MULTISAMPLED -> {
                if (texture.dimension() != TextureDimension.TEXTURE_2D || texture.sampleCount() == 1
                        || range.arrayLayerCount() != 1) {
                    throw new IllegalArgumentException("multisampled two-dimensional views require a multisample texture and one layer");
                }
            }
            case TEXTURE_2D_MULTISAMPLED_ARRAY -> {
                if (texture.dimension() != TextureDimension.TEXTURE_2D || texture.sampleCount() == 1) {
                    throw new IllegalArgumentException("multisampled array views require a multisample two-dimensional texture");
                }
            }
            case TEXTURE_3D -> {
                if (texture.dimension() != TextureDimension.TEXTURE_3D || range.arrayLayerCount() != 1) {
                    throw new IllegalArgumentException("three-dimensional views require a three-dimensional texture and one layer");
                }
            }
            case CUBE -> {
                if (texture.dimension() != TextureDimension.TEXTURE_2D || texture.sampleCount() != 1
                        || texture.width() != texture.height()
                        || range.arrayLayerCount() != 6) {
                    throw new IllegalArgumentException("cube views require six square two-dimensional layers");
                }
            }
            case CUBE_ARRAY -> {
                if (texture.dimension() != TextureDimension.TEXTURE_2D || texture.sampleCount() != 1
                        || texture.width() != texture.height()
                        || range.arrayLayerCount() % 6 != 0) {
                    throw new IllegalArgumentException("cube-array views require a positive multiple of six square two-dimensional layers");
                }
            }
        }
    }
}
