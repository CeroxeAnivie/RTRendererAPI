package top.ceroxe.rt.renderer.api;

/**
 * Immutable, thread-safe surface material; texture id {@code -1} means no texture.
 *
 */
public final class MaterialAsset {
    private final long id;
    private final BlendMode blendMode;
    private final int baseColorRgba8;
    private final long baseColorTextureId;
    private final long normalTextureId;
    private final long metallicRoughnessTextureId;
    private final long emissiveTextureId;
    private final int emissiveColorRgba8;
    private final float emissiveStrength;
    private final float alphaCutoff;
    private final float roughness;
    private final float metallic;
    private final float transmission;
    private final float indexOfRefraction;
    private final boolean doubleSided;
    private final ShadingModel shadingModel;
    private final SurfaceOverlayState surfaceOverlay;

    /**
     * Validates and creates an immutable material.
     *
     * @param id                         stable non-negative material identifier
     * @param blendMode                  alpha-compositing policy
     * @param baseColorRgba8             packed base color
     * @param baseColorTextureId         base-color texture id, or {@code -1}
     * @param normalTextureId            normal-map texture id, or {@code -1}
     * @param metallicRoughnessTextureId metallic-roughness texture id, or {@code -1}
     * @param emissiveTextureId          emissive texture id, or {@code -1}
     * @param emissiveColorRgba8         packed emissive color
     * @param emissiveStrength           non-negative emissive multiplier
     * @param alphaCutoff                masked-material cutoff in {@code [0, 1]}
     * @param roughness                  perceptual roughness in {@code [0, 1]}
     * @param metallic                   metallic response in {@code [0, 1]}
     * @param transmission               transmission response in {@code [0, 1]}
     * @param indexOfRefraction          refractive index in {@code [1, 4]}
     * @param doubleSided                whether both winding orientations are visible
     * @param shadingModel               renderer-owned lighting equation
     * @throws IllegalArgumentException if an identifier or physical parameter is invalid
     * @throws NullPointerException     if an enum component is {@code null}
     */
    private MaterialAsset(
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
            ShadingModel shadingModel,
            SurfaceOverlayState surfaceOverlay
    ) {
        requireId(id, "id");
        this.blendMode = java.util.Objects.requireNonNull(blendMode, "blendMode");
        this.shadingModel = java.util.Objects.requireNonNull(shadingModel, "shadingModel");
        this.surfaceOverlay = java.util.Objects.requireNonNull(surfaceOverlay, "surfaceOverlay");
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
        if (surfaceOverlay.enabled() && blendMode == BlendMode.OPAQUE
                && ((baseColorRgba8 >>> 24) & 0xff) != 0xff) {
            throw new IllegalArgumentException(
                    "alpha-blended surface overlay must use TRANSLUCENT blend mode"
            );
        }
        this.id = id;
        this.baseColorRgba8 = baseColorRgba8;
        this.baseColorTextureId = baseColorTextureId;
        this.normalTextureId = normalTextureId;
        this.metallicRoughnessTextureId = metallicRoughnessTextureId;
        this.emissiveTextureId = emissiveTextureId;
        this.emissiveColorRgba8 = emissiveColorRgba8;
        this.emissiveStrength = emissiveStrength;
        this.alphaCutoff = alphaCutoff;
        this.roughness = roughness;
        this.metallic = metallic;
        this.transmission = transmission;
        this.indexOfRefraction = indexOfRefraction;
        this.doubleSided = doubleSided;
    }

    /**
     * Starts a physically based opaque material with conservative production defaults.
     *
     * <p>The material starts white, non-emissive, fully rough, non-metallic, non-transmissive,
     * single-sided, and without textures. Callers only select properties they actually need.</p>
     *
     * @param id stable non-negative material identifier
     * @return single-thread-confined material builder
     */
    public static Builder builder(long id) {
        return new Builder(id);
    }

    /**
     * Creates a simple opaque material from a packed base color.
     *
     * @param id             stable non-negative material identifier
     * @param baseColorRgba8 packed RGBA8 base color
     * @return immutable physically based material
     */
    public static MaterialAsset opaque(long id, int baseColorRgba8) {
        return builder(id).baseColorRgba8(baseColorRgba8).build();
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

    /**
     * Returns the stable non-negative material identifier.
     *
     * @return stable non-negative material identifier
     */
    public long id() {
        return id;
    }

    /**
     * Returns the alpha-compositing policy.
     *
     * @return alpha-compositing policy
     */
    public BlendMode blendMode() {
        return blendMode;
    }

    /**
     * Returns the packed linear or texture-modulating base color.
     *
     * @return packed RGBA8 base color
     */
    public int baseColorRgba8() {
        return baseColorRgba8;
    }

    /**
     * Returns the optional base-color texture identifier.
     *
     * @return base-color texture id, or {@code -1}
     */
    public long baseColorTextureId() {
        return baseColorTextureId;
    }

    /**
     * Returns the optional normal-map texture identifier.
     *
     * @return normal-map texture id, or {@code -1}
     */
    public long normalTextureId() {
        return normalTextureId;
    }

    /**
     * Returns the optional metallic-roughness texture identifier.
     *
     * @return metallic-roughness texture id, or {@code -1}
     */
    public long metallicRoughnessTextureId() {
        return metallicRoughnessTextureId;
    }

    /**
     * Returns the optional emissive texture identifier.
     *
     * @return emissive texture id, or {@code -1}
     */
    public long emissiveTextureId() {
        return emissiveTextureId;
    }

    /**
     * Returns the packed emissive color.
     *
     * @return packed RGBA8 emissive color
     */
    public int emissiveColorRgba8() {
        return emissiveColorRgba8;
    }

    /**
     * Returns the non-negative emissive multiplier.
     *
     * @return finite non-negative emissive multiplier
     */
    public float emissiveStrength() {
        return emissiveStrength;
    }

    /**
     * Returns the masked-material alpha cutoff.
     *
     * @return alpha cutoff in {@code [0, 1]}
     */
    public float alphaCutoff() {
        return alphaCutoff;
    }

    /**
     * Returns the perceptual roughness.
     *
     * @return roughness in {@code [0, 1]}
     */
    public float roughness() {
        return roughness;
    }

    /**
     * Returns the metallic response.
     *
     * @return metallic response in {@code [0, 1]}
     */
    public float metallic() {
        return metallic;
    }

    /**
     * Returns the transmission response.
     *
     * @return transmission response in {@code [0, 1]}
     */
    public float transmission() {
        return transmission;
    }

    /**
     * Returns the refractive index.
     *
     * @return refractive index in {@code [1, 4]}
     */
    public float indexOfRefraction() {
        return indexOfRefraction;
    }

    /**
     * Reports whether both winding orientations are visible.
     *
     * @return whether this material is double-sided
     */
    public boolean doubleSided() {
        return doubleSided;
    }

    /**
     * Returns the renderer-owned lighting equation.
     *
     * @return non-null shading model
     */
    public ShadingModel shadingModel() {
        return shadingModel;
    }

    /**
     * Returns receiver-aware surface-overlay policy.
     * @return non-null overlay policy
     */
    public SurfaceOverlayState surfaceOverlay() {
        return surfaceOverlay;
    }

    /**
     * Copies this material into an independent builder.
     * @return initialized material builder
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof MaterialAsset material)) return false;
        return id == material.id
                && baseColorRgba8 == material.baseColorRgba8
                && baseColorTextureId == material.baseColorTextureId
                && normalTextureId == material.normalTextureId
                && metallicRoughnessTextureId == material.metallicRoughnessTextureId
                && emissiveTextureId == material.emissiveTextureId
                && emissiveColorRgba8 == material.emissiveColorRgba8
                && Float.compare(emissiveStrength, material.emissiveStrength) == 0
                && Float.compare(alphaCutoff, material.alphaCutoff) == 0
                && Float.compare(roughness, material.roughness) == 0
                && Float.compare(metallic, material.metallic) == 0
                && Float.compare(transmission, material.transmission) == 0
                && Float.compare(indexOfRefraction, material.indexOfRefraction) == 0
                && doubleSided == material.doubleSided
                && blendMode == material.blendMode
                && shadingModel == material.shadingModel
                && surfaceOverlay.equals(material.surfaceOverlay);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(
                id, blendMode, baseColorRgba8,
                baseColorTextureId, normalTextureId, metallicRoughnessTextureId,
                emissiveTextureId, emissiveColorRgba8, emissiveStrength,
                alphaCutoff, roughness, metallic, transmission, indexOfRefraction,
                doubleSided, shadingModel, surfaceOverlay
        );
    }

    @Override
    public String toString() {
        return "MaterialAsset["
                + "id=" + id
                + ", blendMode=" + blendMode
                + ", baseColorRgba8=" + baseColorRgba8
                + ", baseColorTextureId=" + baseColorTextureId
                + ", normalTextureId=" + normalTextureId
                + ", metallicRoughnessTextureId=" + metallicRoughnessTextureId
                + ", emissiveTextureId=" + emissiveTextureId
                + ", emissiveColorRgba8=" + emissiveColorRgba8
                + ", emissiveStrength=" + emissiveStrength
                + ", alphaCutoff=" + alphaCutoff
                + ", roughness=" + roughness
                + ", metallic=" + metallic
                + ", transmission=" + transmission
                + ", indexOfRefraction=" + indexOfRefraction
                + ", doubleSided=" + doubleSided
                + ", shadingModel=" + shadingModel
                + ", surfaceOverlay=" + surfaceOverlay
                + ']';
    }

    /**
     * Alpha-compositing policy for a surface.
     */
    public enum BlendMode {
        /**
         * Fully opaque surface.
         */
        OPAQUE,
        /**
         * Alpha-tested surface using {@link MaterialAsset#alphaCutoff()}.
         */
        MASKED,
        /**
         * Fractionally blended surface.
         */
        TRANSLUCENT
    }

    /**
     * Selects a renderer-owned lighting equation without importing host-specific types.
     */
    public enum ShadingModel {
        /**
         * Physically based metallic-roughness shading.
         */
        PHYSICALLY_BASED,
        /**
         * Texture color multiplied by interpolated per-vertex color and frame lightmap.
         */
        LIGHTMAP_MODULATED,
        /**
         * Base color and emissive contribution bypass all direct and indirect lighting.
         *
         * <p>UNLIT remains depth-tested and may still be masked, translucent, outlined, or used
         * as a receiver-aware surface overlay.</p>
         */
        UNLIT
    }

    /**
     * Single-thread-confined semantic builder for one material generation.
     */
    public static final class Builder {
        private final long id;
        private BlendMode blendMode = BlendMode.OPAQUE;
        private boolean blendModeExplicit;
        private int baseColorRgba8 = 0xffffffff;
        private long baseColorTextureId = -1L;
        private long normalTextureId = -1L;
        private long metallicRoughnessTextureId = -1L;
        private long emissiveTextureId = -1L;
        private int emissiveColorRgba8;
        private float emissiveStrength;
        private float alphaCutoff = 0.5F;
        private float roughness = 1.0F;
        private float metallic;
        private float transmission;
        private float indexOfRefraction = 1.5F;
        private boolean doubleSided;
        private ShadingModel shadingModel = ShadingModel.PHYSICALLY_BASED;
        private SurfaceOverlayState surfaceOverlay = SurfaceOverlayState.disabled();

        private Builder(long id) {
            requireId(id, "id");
            this.id = id;
        }

        private Builder(MaterialAsset source) {
            id = source.id;
            blendMode = source.blendMode;
            blendModeExplicit = true;
            baseColorRgba8 = source.baseColorRgba8;
            baseColorTextureId = source.baseColorTextureId;
            normalTextureId = source.normalTextureId;
            metallicRoughnessTextureId = source.metallicRoughnessTextureId;
            emissiveTextureId = source.emissiveTextureId;
            emissiveColorRgba8 = source.emissiveColorRgba8;
            emissiveStrength = source.emissiveStrength;
            alphaCutoff = source.alphaCutoff;
            roughness = source.roughness;
            metallic = source.metallic;
            transmission = source.transmission;
            indexOfRefraction = source.indexOfRefraction;
            doubleSided = source.doubleSided;
            shadingModel = source.shadingModel;
            surfaceOverlay = source.surfaceOverlay;
        }

        /**
         * Selects the alpha-compositing policy.
         *
         * @param blendMode non-null blend mode
         * @return this builder
         */
        public Builder blendMode(BlendMode blendMode) {
            this.blendMode = java.util.Objects.requireNonNull(blendMode, "blendMode");
            blendModeExplicit = true;
            return this;
        }

        /**
         * Selects a packed base color.
         *
         * @param baseColorRgba8 packed RGBA8 base color
         * @return this builder
         */
        public Builder baseColorRgba8(int baseColorRgba8) {
            this.baseColorRgba8 = baseColorRgba8;
            return this;
        }

        /**
         * Selects the base-color texture by object reference.
         *
         * @param texture non-null texture asset
         * @return this builder
         */
        public Builder baseColorTexture(TextureAsset texture) {
            baseColorTextureId = java.util.Objects.requireNonNull(texture, "texture").id();
            return this;
        }

        /**
         * Selects the base-color texture by stable asset identifier.
         *
         * @param textureId texture id, or {@code -1} to clear the binding
         * @return this builder
         */
        public Builder baseColorTextureId(long textureId) {
            requireOptionalId(textureId, "baseColorTextureId");
            baseColorTextureId = textureId;
            return this;
        }

        /**
         * Selects the normal texture by object reference.
         *
         * @param texture non-null texture asset
         * @return this builder
         */
        public Builder normalTexture(TextureAsset texture) {
            normalTextureId = java.util.Objects.requireNonNull(texture, "texture").id();
            return this;
        }

        /**
         * Selects the normal-map texture by stable asset identifier.
         *
         * @param textureId texture id, or {@code -1} to clear the binding
         * @return this builder
         */
        public Builder normalTextureId(long textureId) {
            requireOptionalId(textureId, "normalTextureId");
            normalTextureId = textureId;
            return this;
        }

        /**
         * Selects the metallic-roughness texture by object reference.
         *
         * @param texture non-null texture asset
         * @return this builder
         */
        public Builder metallicRoughnessTexture(TextureAsset texture) {
            metallicRoughnessTextureId = java.util.Objects.requireNonNull(texture, "texture").id();
            return this;
        }

        /**
         * Selects the metallic-roughness texture by stable asset identifier.
         *
         * @param textureId texture id, or {@code -1} to clear the binding
         * @return this builder
         */
        public Builder metallicRoughnessTextureId(long textureId) {
            requireOptionalId(textureId, "metallicRoughnessTextureId");
            metallicRoughnessTextureId = textureId;
            return this;
        }

        /**
         * Selects emissive color and strength without an emissive texture.
         *
         * @param emissiveColorRgba8 packed RGBA8 emissive color
         * @param strength           non-negative finite emissive multiplier
         * @return this builder
         */
        public Builder emissive(int emissiveColorRgba8, float strength) {
            requireRange(strength, 0.0F, Float.MAX_VALUE, "emissiveStrength");
            this.emissiveColorRgba8 = emissiveColorRgba8;
            emissiveStrength = strength;
            return this;
        }

        /**
         * Selects an emissive texture, color, and strength.
         *
         * @param texture            non-null emissive texture
         * @param emissiveColorRgba8 packed RGBA8 emissive modulation color
         * @param strength           non-negative finite emissive multiplier
         * @return this builder
         */
        public Builder emissive(TextureAsset texture, int emissiveColorRgba8, float strength) {
            emissiveTextureId = java.util.Objects.requireNonNull(texture, "texture").id();
            return emissive(emissiveColorRgba8, strength);
        }

        /**
         * Selects the emissive texture by stable asset identifier.
         *
         * @param textureId texture id, or {@code -1} to clear the binding
         * @return this builder
         */
        public Builder emissiveTextureId(long textureId) {
            requireOptionalId(textureId, "emissiveTextureId");
            emissiveTextureId = textureId;
            return this;
        }

        /**
         * Selects the masked-material alpha cutoff.
         *
         * @param alphaCutoff cutoff in {@code [0, 1]}
         * @return this builder
         */
        public Builder alphaCutoff(float alphaCutoff) {
            requireRange(alphaCutoff, 0.0F, 1.0F, "alphaCutoff");
            this.alphaCutoff = alphaCutoff;
            return this;
        }

        /**
         * Selects perceptual roughness.
         *
         * @param roughness value in {@code [0, 1]}
         * @return this builder
         */
        public Builder roughness(float roughness) {
            requireRange(roughness, 0.0F, 1.0F, "roughness");
            this.roughness = roughness;
            return this;
        }

        /**
         * Selects metallic response.
         *
         * @param metallic value in {@code [0, 1]}
         * @return this builder
         */
        public Builder metallic(float metallic) {
            requireRange(metallic, 0.0F, 1.0F, "metallic");
            this.metallic = metallic;
            return this;
        }

        /**
         * Selects transmission response.
         *
         * <p>When no blend mode was selected explicitly, a positive value derives
         * {@link BlendMode#TRANSLUCENT} at build time. Deriving the default at the boundary keeps
         * the result independent of builder call order. An explicitly selected
         * {@link BlendMode#OPAQUE} remains authoritative and is rejected by canonical validation
         * when transmission is positive.</p>
         *
         * @param transmission value in {@code [0, 1]}
         * @return this builder
         */
        public Builder transmission(float transmission) {
            requireRange(transmission, 0.0F, 1.0F, "transmission");
            this.transmission = transmission;
            return this;
        }

        /**
         * Selects the refractive index.
         *
         * @param indexOfRefraction value in {@code [1, 4]}
         * @return this builder
         */
        public Builder indexOfRefraction(float indexOfRefraction) {
            requireRange(indexOfRefraction, 1.0F, 4.0F, "indexOfRefraction");
            this.indexOfRefraction = indexOfRefraction;
            return this;
        }

        /**
         * Selects whether both winding orientations are visible.
         *
         * @param doubleSided whether the material is double-sided
         * @return this builder
         */
        public Builder doubleSided(boolean doubleSided) {
            this.doubleSided = doubleSided;
            return this;
        }

        /**
         * Selects the renderer-owned lighting equation.
         *
         * @param shadingModel non-null shading model
         * @return this builder
         */
        public Builder shadingModel(ShadingModel shadingModel) {
            this.shadingModel = java.util.Objects.requireNonNull(shadingModel, "shadingModel");
            return this;
        }

        /**
         * Selects receiver-aware surface compositing for this material.
         *
         * <p>The overlay's receiver mask is instance state because one immutable material may be
         * reused by unrelated semantic layers.</p>
         *
         * @param value non-null overlay policy
         * @return this builder
         */
        public Builder surfaceOverlay(SurfaceOverlayState value) {
            surfaceOverlay = java.util.Objects.requireNonNull(value, "surfaceOverlay");
            return this;
        }

        /**
         * Builds a validated immutable material.
         *
         * @return immutable material generation
         */
        public MaterialAsset build() {
            BlendMode effectiveBlendMode = !blendModeExplicit && transmission > 0.0F
                    ? BlendMode.TRANSLUCENT
                    : blendMode;
            return new MaterialAsset(
                    id, effectiveBlendMode, baseColorRgba8,
                    baseColorTextureId, normalTextureId, metallicRoughnessTextureId,
                    emissiveTextureId, emissiveColorRgba8, emissiveStrength,
                    alphaCutoff, roughness, metallic, transmission, indexOfRefraction,
                    doubleSided, shadingModel, surfaceOverlay
            );
        }
    }
}
