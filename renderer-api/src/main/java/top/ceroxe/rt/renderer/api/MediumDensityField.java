package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Spatial density multiplier for a {@link Medium}. A uniform field is one everywhere and
 * requires no texture. A grid uses the normalized red channel of a {@link TextureAsset}
 * with {@link TextureAsset.ColorSpace#LINEAR LINEAR} color space; other channels are ignored.
 *
 * <p>The base texture level is a vertical slice atlas: width {@code W} and height
 * {@code H * slices} represent a {@code W * H * slices} grid, with successive Z slices
 * stored in increasing atlas Y order. The texture height must be divisible by the slice
 * count. Sampling is texel-centered trilinear interpolation within {@code [0, 1]^3},
 * clamped to edge texels at the domain boundary, and zero outside that domain. Texture
 * address modes, filtering and mip levels do not change density sampling.
 *
 * <p>For a {@link MediumBoundary}, the transform maps object-local coordinates to UVW.
 * For an environment camera medium, it maps world coordinates to UVW. The renderer
 * validates the referenced texture and atlas dimensions against the scene assets.
 *
 * @param textureAssetId non-negative density texture ID, or {@code -1} for a uniform field
 * @param slices positive Z slice count; one for a uniform field
 * @param transform immutable coordinate-to-UVW transform; identity for a uniform field
 */
public record MediumDensityField(long textureAssetId, int slices, AffineTransform transform) {
    private static final MediumDensityField UNIFORM =
            new MediumDensityField(-1, 1, AffineTransform.identity());

    public MediumDensityField {
        Objects.requireNonNull(transform, "transform");
        if (textureAssetId < -1)
            throw new IllegalArgumentException("textureAssetId must be -1 or non-negative");
        if (slices < 1)
            throw new IllegalArgumentException("slices must be positive");
        if (textureAssetId == -1 && (slices != 1 || !transform.equals(AffineTransform.identity())))
            throw new IllegalArgumentException("uniform density requires one slice and the identity transform");
    }

    /** Returns the shared field with density multiplier one everywhere. */
    public static MediumDensityField uniform() { return UNIFORM; }

    /** Creates a density grid referencing a scene texture's base level. */
    public static MediumDensityField grid(long textureAssetId, int slices, AffineTransform transform) {
        MaterialAsset.requireId(textureAssetId, "textureAssetId");
        return new MediumDensityField(textureAssetId, slices, transform);
    }

    /** Returns whether no spatial density lookup is required. */
    public boolean isUniform() { return textureAssetId == -1; }
}
