package top.ceroxe.rt.renderer.api;

/**
 * Persistent scene instance referencing an immutable mesh asset generation.
 *
 */
public final class SceneInstance {
    private final long id;
    private final long meshAssetId;
    private final AffineTransform transform;
    private final Mobility mobility;
    private final int visibilityMask;
    private final boolean castsShadow;
    private final float surfaceVisibility;

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
     */
    private SceneInstance(
            long id,
            long meshAssetId,
            AffineTransform transform,
            Mobility mobility,
            int visibilityMask,
            boolean castsShadow,
            float surfaceVisibility
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
                && transform.equals(instance.transform)
                && mobility == instance.mobility;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(
                id, meshAssetId, transform, mobility,
                visibilityMask, castsShadow, surfaceVisibility
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
         * Validates and returns an immutable instance generation.
         *
         * @return immutable validated instance
         */
        public SceneInstance build() {
            return new SceneInstance(
                    id, meshAssetId, transform, mobility,
                    visibilityMask, castsShadow, surfaceVisibility
            );
        }
    }
}
