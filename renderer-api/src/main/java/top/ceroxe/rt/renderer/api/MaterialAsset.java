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
    private final Rgb emissiveRadiance;
    private final float alphaCutoff;
    private final float roughness;
    private final float metallic;
    private final float transmission;
    private final float indexOfRefraction;
    private final boolean doubleSided;
    private final PathDecomposition pathDecomposition;
    private final MotionVectorBlocking motionVectorBlocking;



    private MaterialAsset(
            long id, BlendMode blendMode, int baseColorRgba8,
            long baseColorTextureId, long normalTextureId, long metallicRoughnessTextureId,
            long emissiveTextureId, Rgb emissiveRadiance, float alphaCutoff,
            float roughness, float metallic, float transmission, float indexOfRefraction,
            boolean doubleSided, PathDecomposition pathDecomposition,
            MotionVectorBlocking motionVectorBlocking
    ) {
        requireId(id, "id");
        this.blendMode = java.util.Objects.requireNonNull(blendMode, "blendMode");
        this.pathDecomposition = java.util.Objects.requireNonNull(pathDecomposition, "pathDecomposition");
        this.motionVectorBlocking = java.util.Objects.requireNonNull(motionVectorBlocking, "motionVectorBlocking");
        requireOptionalId(baseColorTextureId, "baseColorTextureId");
        requireOptionalId(normalTextureId, "normalTextureId");
        requireOptionalId(metallicRoughnessTextureId, "metallicRoughnessTextureId");
        requireOptionalId(emissiveTextureId, "emissiveTextureId");
        requireRange(alphaCutoff, 0.0F, 1.0F, "alphaCutoff");
        requireRange(roughness, 0.0F, 1.0F, "roughness");
        requireRange(metallic, 0.0F, 1.0F, "metallic");
        requireRange(transmission, 0.0F, 1.0F, "transmission");
        requireRange(indexOfRefraction, 1.0F, 4.0F, "indexOfRefraction");
        if (blendMode == BlendMode.OPAQUE && transmission > 0.0F) {
            throw new IllegalArgumentException("opaque material must not transmit light");
        }

        this.id = id;
        this.baseColorRgba8 = baseColorRgba8;
        this.baseColorTextureId = baseColorTextureId;
        this.normalTextureId = normalTextureId;
        this.metallicRoughnessTextureId = metallicRoughnessTextureId;
        this.emissiveTextureId = emissiveTextureId;
        this.emissiveRadiance = java.util.Objects.requireNonNull(emissiveRadiance, "emissiveRadiance");
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
     * single-sided, excluded from path-space decomposition, and without textures.
     * Callers only select properties they actually need.</p>
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
     * Returns authored linear RGB radiance, before optional emissive texture modulation.
     * No channel is packed, normalized or clamped to one.
     * @return immutable finite non-negative radiance
     */
    public Rgb emissiveRadiance() {
        return emissiveRadiance;
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
     * Returns path-space decomposition participation and dominant delta-lobe selection.
     * @return non-null path decomposition policy
     */
    public PathDecomposition pathDecomposition() {
        return pathDecomposition;
    }

    /**
     * Returns the surface policy for stopping reflected or refracted motion-vector propagation.
     * @return non-null motion-vector blocking policy; OFF by default
     */
    public MotionVectorBlocking motionVectorBlocking() {
        return motionVectorBlocking;
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
                && emissiveRadiance.equals(material.emissiveRadiance)
                && Float.compare(alphaCutoff, material.alphaCutoff) == 0
                && Float.compare(roughness, material.roughness) == 0
                && Float.compare(metallic, material.metallic) == 0
                && Float.compare(transmission, material.transmission) == 0
                && Float.compare(indexOfRefraction, material.indexOfRefraction) == 0
                && doubleSided == material.doubleSided
                && pathDecomposition == material.pathDecomposition
                && motionVectorBlocking == material.motionVectorBlocking
                && blendMode == material.blendMode;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(
                id, blendMode, baseColorRgba8,
                baseColorTextureId, normalTextureId, metallicRoughnessTextureId,
                emissiveTextureId, emissiveRadiance,
                alphaCutoff, roughness, metallic, transmission, indexOfRefraction,
                doubleSided, pathDecomposition, motionVectorBlocking
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
                + ", emissiveRadiance=" + emissiveRadiance
                + ", alphaCutoff=" + alphaCutoff
                + ", roughness=" + roughness
                + ", metallic=" + metallic
                + ", transmission=" + transmission
                + ", indexOfRefraction=" + indexOfRefraction
                + ", doubleSided=" + doubleSided
                + ", pathDecomposition=" + pathDecomposition
                + ", motionVectorBlocking=" + motionVectorBlocking
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

    /** Path-space decomposition policy for specular delta paths. */
    public enum PathDecomposition {
        /** Excludes this material from path decomposition; the default. */
        EXCLUDED,
        /** Participates and lets the renderer select the dominant delta lobe. */
        AUTOMATIC,
        /** Participates with transmission as the dominant delta lobe. */
        TRANSMISSION,
        /** Participates with reflection as the dominant delta lobe. */
        REFLECTION
    }

    /**
     * Per-material motion-vector propagation policy for path decomposition.
     * Curved reflective or refractive surfaces may require stopping propagation
     * at the surface because a rotation-only path transform cannot represent curvature.
     * These choices select the renderer's existing behavior, not a custom motion model.
     */
    public enum MotionVectorBlocking {
        /** Propagates motion vectors normally; the original default. */
        OFF,
        /** Uses the renderer's less sensitive curvature test. */
        AUTO_LOW,
        /** Uses the renderer's more sensitive curvature test. */
        AUTO_HIGH,
        /** Always stops motion-vector propagation at this surface. */
        FULL
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
        private Rgb emissiveRadiance = Rgb.ZERO;
        private float alphaCutoff = 0.5F;
        private float roughness = 1.0F;
        private float metallic;
        private float transmission;
        private float indexOfRefraction = 1.5F;
        private boolean doubleSided;
        private PathDecomposition pathDecomposition = PathDecomposition.EXCLUDED;
        private MotionVectorBlocking motionVectorBlocking = MotionVectorBlocking.OFF;

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
            emissiveRadiance = source.emissiveRadiance;
            alphaCutoff = source.alphaCutoff;
            roughness = source.roughness;
            metallic = source.metallic;
            transmission = source.transmission;
            indexOfRefraction = source.indexOfRefraction;
            doubleSided = source.doubleSided;
            pathDecomposition = source.pathDecomposition;
            motionVectorBlocking = source.motionVectorBlocking;
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
         * <p>RGB bytes use the existing linear numeric convention: red is the low byte,
         * followed by green and blue. Alpha is ignored. Each byte is divided by 255
         * and multiplied by strength once when authored; no sRGB transfer is applied.</p>
         *
         * @param emissiveColorRgba8 packed RGBA8 emissive color
         * @param strength           non-negative finite emissive multiplier
         * @return this builder
         */
        public Builder emissive(int emissiveColorRgba8, float strength) {
            requireRange(strength, 0.0F, Float.MAX_VALUE, "emissiveStrength");
            emissiveRadiance = new Rgb(
                    (emissiveColorRgba8 & 0xff) / 255.0F * strength,
                    ((emissiveColorRgba8 >>> 8) & 0xff) / 255.0F * strength,
                    ((emissiveColorRgba8 >>> 16) & 0xff) / 255.0F * strength);
            return this;
        }

        /**
         * Selects finite non-negative linear RGB radiance without RGBA8 quantization.
         * The optional emissive texture multiplies these channels.
         * @param radiance immutable linear radiance; channels may exceed one
         * @return this builder
         */
        public Builder emissive(Rgb radiance) {
            emissiveRadiance = java.util.Objects.requireNonNull(radiance, "radiance");
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
         * Selects an emissive texture and unquantized linear RGB radiance.
         * @param texture non-null emissive texture
         * @param radiance immutable linear modulation radiance
         * @return this builder
         */
        public Builder emissive(TextureAsset texture, Rgb radiance) {
            emissiveTextureId = java.util.Objects.requireNonNull(texture, "texture").id();
            return emissive(radiance);
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
         * Selects path-space decomposition participation and the dominant delta lobe.
         * This policy does not change transmission, roughness, or alpha blending.
         * @param value non-null path decomposition policy
         * @return this builder
         */
        public Builder pathDecomposition(PathDecomposition value) {
            pathDecomposition = java.util.Objects.requireNonNull(value, "pathDecomposition");
            return this;
        }

        /**
         * Selects the renderer's surface motion-vector blocking behavior.
         * Does not enable path decomposition or change surface transport parameters.
         * @param value non-null motion-vector blocking policy
         * @return this builder
         */
        public Builder motionVectorBlocking(MotionVectorBlocking value) {
            motionVectorBlocking = java.util.Objects.requireNonNull(value, "motionVectorBlocking");
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
                    emissiveTextureId, emissiveRadiance,
                    alphaCutoff, roughness, metallic, transmission, indexOfRefraction,
                    doubleSided, pathDecomposition, motionVectorBlocking
            );
        }
    }
}
