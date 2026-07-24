package top.ceroxe.mcvulkanrt.renderer.api;

/** Immutable surface material; texture id {@code -1} means no texture. */
public record MaterialAsset(
        long id,
        BlendMode blendMode,
        int baseColorRgba8,
        long baseColorTextureId,
        long normalTextureId,
        long metallicRoughnessTextureId,
        long emissiveTextureId,
        int emissiveColorRgba8,
        float emissiveStrength,
        float alphaCutoff,
        float roughness,
        float metallic,
        float transmission,
        float indexOfRefraction,
        boolean doubleSided,
        ShadingModel shadingModel
) {
    /**
     * Compatibility constructor for renderer clients authored before shading models were explicit.
     * Their existing materials retain the physically based path; source-engine bridges must opt in
     * to a source-compatible model instead of being inferred from texture or blend metadata.
     */
    public MaterialAsset(
            long id,
            BlendMode blendMode,
            int baseColorRgba8,
            long baseColorTextureId,
            long normalTextureId,
            long metallicRoughnessTextureId,
            long emissiveTextureId,
            int emissiveColorRgba8,
            float emissiveStrength,
            float alphaCutoff,
            float roughness,
            float metallic,
            float transmission,
            float indexOfRefraction,
            boolean doubleSided
    ) {
        this(
                id, blendMode, baseColorRgba8, baseColorTextureId, normalTextureId,
                metallicRoughnessTextureId, emissiveTextureId, emissiveColorRgba8,
                emissiveStrength, alphaCutoff, roughness, metallic, transmission,
                indexOfRefraction, doubleSided, ShadingModel.PHYSICALLY_BASED
        );
    }

    public MaterialAsset {
        requireId(id, "id");
        blendMode = java.util.Objects.requireNonNull(blendMode, "blendMode");
        shadingModel = java.util.Objects.requireNonNull(shadingModel, "shadingModel");
        requireOptionalId(baseColorTextureId, "baseColorTextureId");
        requireOptionalId(normalTextureId, "normalTextureId");
        requireOptionalId(metallicRoughnessTextureId, "metallicRoughnessTextureId");
        requireOptionalId(emissiveTextureId, "emissiveTextureId");
        requireRange(emissiveStrength, 0.0F, Float.MAX_VALUE, "emissiveStrength");
        requireRange(alphaCutoff, 0.0F, 1.0F, "alphaCutoff");
        requireRange(roughness, 0.0F, 1.0F, "roughness");
        requireRange(metallic, 0.0F, 1.0F, "metallic");
        requireRange(transmission, 0.0F, 1.0F, "transmission");
        requireRange(indexOfRefraction, 1.0F, 4.0F, "indexOfRefraction");
        if (blendMode == BlendMode.OPAQUE && transmission > 0.0F) {
            throw new IllegalArgumentException("opaque material must not transmit light");
        }
    }

    public enum BlendMode {
        OPAQUE,
        MASKED,
        TRANSLUCENT
    }

    /** Selects a renderer-owned lighting equation without importing source-engine types. */
    public enum ShadingModel {
        PHYSICALLY_BASED,
        /** Texture color multiplied by interpolated per-vertex color and frame lightmap. */
        LIGHTMAP_MODULATED
    }

    static void requireId(long id, String name) {
        if (id < 0L) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    private static void requireOptionalId(long id, String name) {
        if (id < -1L) {
            throw new IllegalArgumentException(name + " must be -1 or a non-negative id");
        }
    }

    private static void requireRange(float value, float minimum, float maximum, String name) {
        if (!Float.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be finite and in [" + minimum + ", " + maximum + "]");
        }
    }
}
