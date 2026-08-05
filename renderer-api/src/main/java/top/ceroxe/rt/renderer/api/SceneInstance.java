package top.ceroxe.rt.renderer.api;

/**
 * Persistent scene instance referencing an immutable mesh asset generation.
 *
 */
public final class SceneInstance {
    /** Largest supported lightmap coordinate on either unsigned 16-bit axis. */
    public static final int MAX_LIGHT_COORDINATE = 240;

    /** Packed full-bright coordinates used when an instance does not override its lighting. */
    public static final int FULL_BRIGHT_PACKED_LIGHT = 0x00f0_00f0;

    private final long id;
    private final long meshAssetId;
    private final AffineTransform transform;
    private final Mobility mobility;
    private final int visibilityMask;
    private final boolean castsShadow;
    private final float surfaceVisibility;
    private final int packedLight;
    private final InstanceRenderState renderState;

    /**
     * Validates and creates an immutable scene instance.
     *
     * @param id                stable non-negative instance identifier
     * @param meshAssetId       referenced non-negative mesh identifier
     * @param transform         immutable object-to-world transform
     * @param mobility          acceleration-structure update policy
     * @param visibilityMask    non-zero 8-bit ray visibility mask
     * @param castsShadow       whether the instance participates in shadow rays
     * @param surfaceVisibility shader-visible opacity multiplier in {@code [0, 1]}
     * @param packedLight       two validated lightmap coordinates packed into unsigned 16-bit halves
     */
    private SceneInstance(
            long id,
            long meshAssetId,
            AffineTransform transform,
            Mobility mobility,
            int visibilityMask,
            boolean castsShadow,
            float surfaceVisibility,
            int packedLight,
            InstanceRenderState renderState
    ) {
        MaterialAsset.requireId(id, "id");
        MaterialAsset.requireId(meshAssetId, "meshAssetId");
        this.transform = java.util.Objects.requireNonNull(transform, "transform");
        this.mobility = java.util.Objects.requireNonNull(mobility, "mobility");
        if ((visibilityMask & ~0xff) != 0 || visibilityMask == 0) {
            throw new IllegalArgumentException("visibilityMask must contain a non-zero Vulkan 8-bit mask");
        }
        if (!Float.isFinite(surfaceVisibility)
                || surfaceVisibility < 0.0F || surfaceVisibility > 1.0F) {
            throw new IllegalArgumentException("surfaceVisibility must be finite and within [0, 1]");
        }
        this.id = id;
        this.meshAssetId = meshAssetId;
        this.visibilityMask = visibilityMask;
        this.castsShadow = castsShadow;
        this.surfaceVisibility = surfaceVisibility;
        this.packedLight = requireValidPackedLight(packedLight);
        this.renderState = java.util.Objects.requireNonNull(renderState, "renderState");
    }

    /**
     * Starts a semantic instance builder with an identity transform and fully visible defaults.
     *
     * @param id          stable non-negative instance identifier
     * @param meshAssetId referenced non-negative mesh identifier
     * @return new single-thread-confined instance builder
     */
    public static Builder builder(long id, long meshAssetId) {
        return new Builder(id, meshAssetId);
    }

    /**
     * Returns the stable non-negative instance identifier.
     *
     * @return stable non-negative instance identifier
     */
    public long id() {
        return id;
    }

    /**
     * Returns the referenced non-negative mesh identifier.
     *
     * @return referenced non-negative mesh identifier
     */
    public long meshAssetId() {
        return meshAssetId;
    }

    /**
     * Returns the immutable object-to-world transform.
     *
     * @return immutable object-to-world transform
     */
    public AffineTransform transform() {
        return transform;
    }

    /**
     * Returns the acceleration-structure update policy.
     *
     * @return acceleration-structure update policy
     */
    public Mobility mobility() {
        return mobility;
    }

    /**
     * Returns the non-zero 8-bit ray visibility mask.
     *
     * @return non-zero 8-bit ray visibility mask
     */
    public int visibilityMask() {
        return visibilityMask;
    }

    /**
     * Reports whether the instance participates in shadow rays.
     *
     * @return whether the instance participates in shadow rays
     */
    public boolean castsShadow() {
        return castsShadow;
    }

    /**
     * Returns the shader-visible opacity multiplier.
     *
     * @return shader-visible opacity multiplier in {@code [0, 1]}
     */
    public float surfaceVisibility() {
        return surfaceVisibility;
    }

    /**
     * Returns the instance lightmap coordinates packed into two unsigned 16-bit halves.
     *
     * <p>The first coordinate occupies bits {@code [0, 15]} and the second occupies bits
     * {@code [16, 31]}. Both coordinates are in {@code [0, 240]}. This instance attribute
     * deliberately does not live in {@link MeshAsset}: moving an object through a light field
     * must update shading state, not immutable geometry or its acceleration structure.</p>
     *
     * @return two lightmap coordinates packed as {@code second << 16 | first}
     */
    public int packedLight() {
        return packedLight;
    }

    /**
     * Returns per-instance UV, receiver-mask, object-mask, outline, and cardinal-lighting state.
     * @return non-null render state
     */
    public InstanceRenderState renderState() {
        return renderState;
    }

    /**
     * Starts an independent builder initialized from this complete instance generation.
     *
     * @return new builder containing every current instance property
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SceneInstance instance)) return false;
        return id == instance.id
                && meshAssetId == instance.meshAssetId
                && visibilityMask == instance.visibilityMask
                && castsShadow == instance.castsShadow
                && Float.compare(surfaceVisibility, instance.surfaceVisibility) == 0
                && packedLight == instance.packedLight
                && transform.equals(instance.transform)
                && mobility == instance.mobility
                && renderState.equals(instance.renderState);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(
                id, meshAssetId, transform, mobility,
                visibilityMask, castsShadow, surfaceVisibility, packedLight, renderState
        );
    }

    @Override
    public String toString() {
        return "SceneInstance["
                + "id=" + id
                + ", meshAssetId=" + meshAssetId
                + ", transform=" + transform
                + ", mobility=" + mobility
                + ", visibilityMask=" + visibilityMask
                + ", castsShadow=" + castsShadow
                + ", surfaceVisibility=" + surfaceVisibility
                + ", packedLight=" + packedLight
                + ", renderState=" + renderState
                + ']';
    }

    /**
     * Acceleration-structure update policy for an instance.
     */
    public enum Mobility {
        /**
         * Transform and geometry are expected to remain stable.
         */
        STATIC,
        /**
         * Transform or geometry may change between scene revisions.
         */
        DYNAMIC
    }

    /**
     * Single-thread-confined builder for a persistent instance generation.
     */
    public static final class Builder {
        private final long id;
        private final long meshAssetId;
        private AffineTransform transform = AffineTransform.identity();
        private Mobility mobility = Mobility.STATIC;
        private int visibilityMask = 0xff;
        private boolean castsShadow = true;
        private float surfaceVisibility = 1.0F;
        private int packedLight = FULL_BRIGHT_PACKED_LIGHT;
        private InstanceRenderState renderState = InstanceRenderState.defaults();

        private Builder(long id, long meshAssetId) {
            MaterialAsset.requireId(id, "id");
            MaterialAsset.requireId(meshAssetId, "meshAssetId");
            this.id = id;
            this.meshAssetId = meshAssetId;
        }

        private Builder(SceneInstance source) {
            id = source.id;
            meshAssetId = source.meshAssetId;
            transform = source.transform;
            mobility = source.mobility;
            visibilityMask = source.visibilityMask;
            castsShadow = source.castsShadow;
            surfaceVisibility = source.surfaceVisibility;
            packedLight = source.packedLight;
            renderState = source.renderState;
        }

        /**
         * Selects the object-to-world transform.
         *
         * @param value non-null immutable transform
         * @return this builder
         */
        public Builder transform(AffineTransform value) {
            transform = java.util.Objects.requireNonNull(value, "transform");
            return this;
        }

        /**
         * Selects the acceleration-structure update policy.
         *
         * @param value non-null mobility policy
         * @return this builder
         */
        public Builder mobility(Mobility value) {
            mobility = java.util.Objects.requireNonNull(value, "mobility");
            return this;
        }

        /**
         * Selects the non-zero 8-bit ray visibility mask.
         *
         * @param value visibility mask in {@code [1, 255]}
         * @return this builder
         */
        public Builder visibilityMask(int value) {
            visibilityMask = value;
            return this;
        }

        /**
         * Selects whether this instance participates in shadow rays.
         *
         * @param value whether shadows are enabled
         * @return this builder
         */
        public Builder castsShadow(boolean value) {
            castsShadow = value;
            return this;
        }

        /**
         * Selects the shader-visible opacity multiplier.
         *
         * @param value finite surface visibility in {@code [0, 1]}
         * @return this builder
         */
        public Builder surfaceVisibility(float value) {
            surfaceVisibility = value;
            return this;
        }

        /**
         * Selects two packed lightmap coordinates.
         *
         * <p>The low and high unsigned 16-bit halves must each be in {@code [0, 240]}.
         * Prefer {@link #lightmapCoordinates(int, int)} when the caller does not already own a
         * packed host representation.</p>
         *
         * @param value coordinates packed as {@code second << 16 | first}
         * @return this builder
         */
        public Builder packedLight(int value) {
            packedLight = value;
            return this;
        }

        /**
         * Selects the two lightmap coordinates without requiring bit packing at the call site.
         *
         * @param firstCoordinate  coordinate stored in the low unsigned 16-bit half, in {@code [0, 240]}
         * @param secondCoordinate coordinate stored in the high unsigned 16-bit half, in {@code [0, 240]}
         * @return this builder
         */
        public Builder lightmapCoordinates(int firstCoordinate, int secondCoordinate) {
            requireValidLightCoordinate(firstCoordinate, "firstCoordinate");
            requireValidLightCoordinate(secondCoordinate, "secondCoordinate");
            packedLight = firstCoordinate | secondCoordinate << 16;
            return this;
        }

        /** Selects immutable instance-local UV, masking, outline, and cardinal-lighting state. */
        /**
         * Replaces the shared per-instance shading state.
         * @param value non-null render state
         * @return this builder
         */
        public Builder renderState(InstanceRenderState value) {
            renderState = java.util.Objects.requireNonNull(value, "renderState");
            return this;
        }

        /**
         * Validates and returns an immutable instance generation.
         *
         * @return immutable validated instance
         */
        public SceneInstance build() {
            return new SceneInstance(
                    id, meshAssetId, transform, mobility,
                    visibilityMask, castsShadow, surfaceVisibility, packedLight, renderState
            );
        }
    }

    static int requireValidPackedLight(int value) {
        requireValidLightCoordinate(value & 0xffff, "packedLight low coordinate");
        requireValidLightCoordinate(value >>> 16, "packedLight high coordinate");
        return value;
    }

    static void requireValidLightCoordinate(int value, String name) {
        if (value < 0 || value > MAX_LIGHT_COORDINATE) {
            throw new IllegalArgumentException(name + " must be within [0, "
                    + MAX_LIGHT_COORDINATE + "]");
        }
    }
}
