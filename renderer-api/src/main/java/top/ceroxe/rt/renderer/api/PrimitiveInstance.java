package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Immutable frame-scoped instance of a persistent {@link MeshAsset}.
 *
 * <p>No global identity is required because list position is the complete identity within one
 * {@link FramePrimitiveBatch}. The provider may therefore replace the batch atomically without
 * mutating persistent scene revision state. Callers that move a primitive provide its transform
 * from the last successfully submitted frame; this preserves exact NRD/DLSS object motion while
 * keeping high-churn instances in frame-slot-local buffers and TLAS generations.</p>
 */
public final class PrimitiveInstance {
    private final long meshAssetId;
    private final AffineTransform transform;
    private final AffineTransform previousTransform;
    private final int visibilityMask;
    private final boolean castsShadow;
    private final float surfaceVisibility;
    private final int packedLight;
    private final InstanceRenderState renderState;

    private PrimitiveInstance(Builder builder) {
        MaterialAsset.requireId(builder.meshAssetId, "meshAssetId");
        transform = Objects.requireNonNull(builder.transform, "transform");
        previousTransform = builder.previousTransform == null
                ? transform
                : Objects.requireNonNull(builder.previousTransform, "previousTransform");
        if ((builder.visibilityMask & ~0xff) != 0 || builder.visibilityMask == 0) {
            throw new IllegalArgumentException("visibilityMask must contain a non-zero Vulkan 8-bit mask");
        }
        if (!Float.isFinite(builder.surfaceVisibility)
                || builder.surfaceVisibility < 0.0F || builder.surfaceVisibility > 1.0F) {
            throw new IllegalArgumentException("surfaceVisibility must be finite and within [0, 1]");
        }
        meshAssetId = builder.meshAssetId;
        visibilityMask = builder.visibilityMask;
        castsShadow = builder.castsShadow;
        surfaceVisibility = builder.surfaceVisibility;
        packedLight = SceneInstance.requireValidPackedLight(builder.packedLight);
        renderState = Objects.requireNonNull(builder.renderState, "renderState");
    }

    /**
     * Starts a frame primitive with identity transform and fully visible defaults.
     * @param meshAssetId resident mesh identity
     * @return mutable primitive builder
     */
    public static Builder builder(long meshAssetId) {
        return new Builder(meshAssetId);
    }

    /**
     * Copies a persistent instance's complete render state into a frame-scoped value.
     * @param instance non-null persistent instance
     * @return frame-scoped primitive without a persistent instance identity
     */
    public static PrimitiveInstance from(SceneInstance instance) {
        SceneInstance source = Objects.requireNonNull(instance, "instance");
        return builder(source.meshAssetId())
                .transform(source.transform())
                .previousTransform(source.transform())
                .visibilityMask(source.visibilityMask())
                .castsShadow(source.castsShadow())
                .surfaceVisibility(source.surfaceVisibility())
                .packedLight(source.packedLight())
                .renderState(source.renderState())
                .build();
    }

    /**
     * Returns the resident mesh identity.
     * @return mesh identity
     */
    public long meshAssetId() { return meshAssetId; }
    /**
     * Returns the object-to-world transform.
     * @return non-null transform
     */
    public AffineTransform transform() { return transform; }
    /**
     * Returns the object-to-world transform from the last successfully submitted frame.
     *
     * <p>Static or newly introduced primitives default this value to {@link #transform()} so they
     * produce zero object motion instead of borrowing an unrelated history sample.</p>
     *
     * @return non-null previous object-to-world transform
     */
    public AffineTransform previousTransform() { return previousTransform; }
    /**
     * Returns the Vulkan ray visibility mask.
     * @return non-zero 8-bit mask
     */
    public int visibilityMask() { return visibilityMask; }
    /**
     * Reports whether this primitive participates in shadow rays.
     * @return whether the primitive casts shadows
     */
    public boolean castsShadow() { return castsShadow; }
    /**
     * Returns surface visibility.
     * @return finite visibility in {@code [0, 1]}
     */
    public float surfaceVisibility() { return surfaceVisibility; }
    /**
     * Returns packed lightmap coordinates.
     * @return validated packed light value
     */
    public int packedLight() { return packedLight; }
    /**
     * Returns shared per-instance render state.
     * @return non-null render state
     */
    public InstanceRenderState renderState() { return renderState; }

    /**
     * Copies this primitive into an independent builder.
     * @return initialized primitive builder
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof PrimitiveInstance instance
                && meshAssetId == instance.meshAssetId
                && visibilityMask == instance.visibilityMask
                && castsShadow == instance.castsShadow
                && Float.compare(surfaceVisibility, instance.surfaceVisibility) == 0
                && packedLight == instance.packedLight
                && transform.equals(instance.transform)
                && previousTransform.equals(instance.previousTransform)
                && renderState.equals(instance.renderState);
    }

    @Override
    public int hashCode() {
        return Objects.hash(meshAssetId, transform, previousTransform, visibilityMask, castsShadow,
                surfaceVisibility, packedLight, renderState);
    }

    @Override
    public String toString() {
        return "PrimitiveInstance[meshAssetId=" + meshAssetId
                + ", transform=" + transform
                + ", previousTransform=" + previousTransform
                + ", visibilityMask=" + visibilityMask
                + ", castsShadow=" + castsShadow
                + ", surfaceVisibility=" + surfaceVisibility
                + ", packedLight=" + packedLight
                + ", renderState=" + renderState + ']';
    }

    /** Single-thread-confined semantic builder. */
    public static final class Builder {
        private final long meshAssetId;
        private AffineTransform transform = AffineTransform.identity();
        private AffineTransform previousTransform;
        private int visibilityMask = 0xff;
        private boolean castsShadow = true;
        private float surfaceVisibility = 1.0F;
        private int packedLight = SceneInstance.FULL_BRIGHT_PACKED_LIGHT;
        private InstanceRenderState renderState = InstanceRenderState.defaults();

        private Builder(long meshAssetId) {
            MaterialAsset.requireId(meshAssetId, "meshAssetId");
            this.meshAssetId = meshAssetId;
        }

        private Builder(PrimitiveInstance source) {
            meshAssetId = source.meshAssetId;
            transform = source.transform;
            previousTransform = source.previousTransform;
            visibilityMask = source.visibilityMask;
            castsShadow = source.castsShadow;
            surfaceVisibility = source.surfaceVisibility;
            packedLight = source.packedLight;
            renderState = source.renderState;
        }

        /**
         * Replaces the object-to-world transform.
         * @param value non-null transform
         * @return this builder
         */
        public Builder transform(AffineTransform value) {
            transform = Objects.requireNonNull(value, "transform");
            return this;
        }

        /**
         * Replaces the transform from the last successfully submitted frame.
         *
         * <p>The caller must advance this value only after submission succeeds. Rejected frames
         * therefore retain their original provenance and cannot inject motion from an image that
         * was never rendered.</p>
         *
         * @param value non-null previous object-to-world transform
         * @return this builder
         */
        public Builder previousTransform(AffineTransform value) {
            previousTransform = Objects.requireNonNull(value, "previousTransform");
            return this;
        }

        /**
         * Replaces the ray visibility mask.
         * @param value non-zero Vulkan 8-bit mask
         * @return this builder
         */
        public Builder visibilityMask(int value) {
            visibilityMask = value;
            return this;
        }

        /**
         * Selects shadow participation.
         * @param value whether this primitive casts shadows
         * @return this builder
         */
        public Builder castsShadow(boolean value) {
            castsShadow = value;
            return this;
        }

        /**
         * Replaces surface visibility.
         * @param value finite visibility in {@code [0, 1]}
         * @return this builder
         */
        public Builder surfaceVisibility(float value) {
            surfaceVisibility = value;
            return this;
        }

        /**
         * Replaces packed lightmap coordinates.
         * @param value validated packed light value
         * @return this builder
         */
        public Builder packedLight(int value) {
            packedLight = value;
            return this;
        }

        /**
         * Sets two validated lightmap coordinates.
         * @param firstCoordinate low packed coordinate
         * @param secondCoordinate high packed coordinate
         * @return this builder
         */
        public Builder lightmapCoordinates(int firstCoordinate, int secondCoordinate) {
            SceneInstance.requireValidLightCoordinate(firstCoordinate, "firstCoordinate");
            SceneInstance.requireValidLightCoordinate(secondCoordinate, "secondCoordinate");
            packedLight = firstCoordinate | secondCoordinate << 16;
            return this;
        }

        /**
         * Replaces shared per-instance render state.
         * @param value non-null render state
         * @return this builder
         */
        public Builder renderState(InstanceRenderState value) {
            renderState = Objects.requireNonNull(value, "renderState");
            return this;
        }

        /**
         * Builds validated immutable frame primitive.
         * @return immutable primitive
         */
        public PrimitiveInstance build() {
            return new PrimitiveInstance(this);
        }
    }
}
