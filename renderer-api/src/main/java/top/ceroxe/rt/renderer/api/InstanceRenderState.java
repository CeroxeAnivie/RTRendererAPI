package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Immutable per-instance shading and post-processing state shared by persistent and frame-scoped
 * primitives.
 *
 * <p>Masks are raw 32-bit bit sets. A regular surface publishes {@link #surfaceMask()}; an overlay
 * is composited only over a hit sharing at least one bit with {@link #overlayReceiverMask()}.
 * Object mask zero disables outline identity, while non-zero values let adjacent hits be compared
 * without coupling the API to any host application's object model. Instance-owned lighting lets
 * shared mesh geometry and one BLAS retain distinct shading without mutating either resource.</p>
 */
public final class InstanceRenderState {
    private static final InstanceRenderState DEFAULT = new InstanceRenderState(
            UvTransform.identity(), -1, 0, 0, OutlineStyle.disabled(),
            CardinalLightingState.disabled(), DirectionalDiffuseState.disabled()
    );

    private final UvTransform uvTransform;
    private final int surfaceMask;
    private final int overlayReceiverMask;
    private final int objectMask;
    private final OutlineStyle outline;
    private final CardinalLightingState cardinalLighting;
    private final DirectionalDiffuseState directionalDiffuse;

    private InstanceRenderState(Builder builder) {
        uvTransform = Objects.requireNonNull(builder.uvTransform, "uvTransform");
        surfaceMask = builder.surfaceMask;
        overlayReceiverMask = builder.overlayReceiverMask;
        objectMask = builder.objectMask;
        outline = Objects.requireNonNull(builder.outline, "outline");
        cardinalLighting = Objects.requireNonNull(builder.cardinalLighting, "cardinalLighting");
        directionalDiffuse = Objects.requireNonNull(builder.directionalDiffuse, "directionalDiffuse");
        if (outline.enabled() && objectMask == 0) {
            throw new IllegalArgumentException("enabled outline requires a non-zero objectMask");
        }
        if (cardinalLighting.enabled() && directionalDiffuse.enabled()) {
            throw new IllegalArgumentException(
                    "cardinalLighting and directionalDiffuse are mutually exclusive"
            );
        }
    }

    private InstanceRenderState(
            UvTransform uvTransform,
            int surfaceMask,
            int overlayReceiverMask,
            int objectMask,
            OutlineStyle outline,
            CardinalLightingState cardinalLighting,
            DirectionalDiffuseState directionalDiffuse
    ) {
        this.uvTransform = uvTransform;
        this.surfaceMask = surfaceMask;
        this.overlayReceiverMask = overlayReceiverMask;
        this.objectMask = objectMask;
        this.outline = outline;
        this.cardinalLighting = cardinalLighting;
        this.directionalDiffuse = directionalDiffuse;
    }

    /** Returns the shared identity/default state. */
    /**
     * Returns shared default state with identity UVs and no overlay or outline.
     * @return shared default state
     */
    public static InstanceRenderState defaults() {
        return DEFAULT;
    }

    /** Starts a state builder with identity UVs, all receiver bits, and no overlay or outline. */
    /**
     * Starts a thread-confined state builder.
     * @return mutable state builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the UV transform applied by the shader.
     * @return non-null UV transform
     */
    public UvTransform uvTransform() {
        return uvTransform;
    }

    /**
     * Returns receiver bits published by this instance.
     * @return surface receiver bit set
     */
    public int surfaceMask() {
        return surfaceMask;
    }

    /**
     * Returns receiver bits required when this instance material is an overlay.
     * @return overlay receiver bit set
     */
    public int overlayReceiverMask() {
        return overlayReceiverMask;
    }

    /**
     * Returns object identity bits used by outline comparison.
     * @return object mask, or zero when disabled
     */
    public int objectMask() {
        return objectMask;
    }

    /**
     * Returns the screen-space outline policy.
     * @return non-null outline policy
     */
    public OutlineStyle outline() {
        return outline;
    }

    /**
     * Returns instance-local dominant-axis base-color modulation.
     * @return non-null cardinal-lighting state
     */
    public CardinalLightingState cardinalLighting() {
        return cardinalLighting;
    }

    /**
     * Returns instance-local continuous two-direction Lambert modulation.
     * @return non-null directional diffuse state
     */
    public DirectionalDiffuseState directionalDiffuse() {
        return directionalDiffuse;
    }

    /**
     * Copies this state into an independent builder.
     * @return initialized state builder
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof InstanceRenderState state
                && surfaceMask == state.surfaceMask
                && overlayReceiverMask == state.overlayReceiverMask
                && objectMask == state.objectMask
                && uvTransform.equals(state.uvTransform)
                && outline.equals(state.outline)
                && cardinalLighting.equals(state.cardinalLighting)
                && directionalDiffuse.equals(state.directionalDiffuse);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                uvTransform, surfaceMask, overlayReceiverMask, objectMask, outline,
                cardinalLighting, directionalDiffuse
        );
    }

    @Override
    public String toString() {
        return "InstanceRenderState[uvTransform=" + uvTransform
                + ", surfaceMask=" + surfaceMask
                + ", overlayReceiverMask=" + overlayReceiverMask
                + ", objectMask=" + objectMask
                + ", outline=" + outline
                + ", cardinalLighting=" + cardinalLighting
                + ", directionalDiffuse=" + directionalDiffuse + ']';
    }

    /** Single-thread-confined semantic builder. */
    public static final class Builder {
        private UvTransform uvTransform = UvTransform.identity();
        private int surfaceMask = -1;
        private int overlayReceiverMask;
        private int objectMask;
        private OutlineStyle outline = OutlineStyle.disabled();
        private CardinalLightingState cardinalLighting = CardinalLightingState.disabled();
        private DirectionalDiffuseState directionalDiffuse = DirectionalDiffuseState.disabled();

        private Builder() {
        }

        private Builder(InstanceRenderState source) {
            uvTransform = source.uvTransform;
            surfaceMask = source.surfaceMask;
            overlayReceiverMask = source.overlayReceiverMask;
            objectMask = source.objectMask;
            outline = source.outline;
            cardinalLighting = source.cardinalLighting;
            directionalDiffuse = source.directionalDiffuse;
        }

        /**
         * Replaces the UV transform.
         * @param value non-null UV transform
         * @return this builder
         */
        public Builder uvTransform(UvTransform value) {
            uvTransform = Objects.requireNonNull(value, "uvTransform");
            return this;
        }

        /**
         * Replaces surface receiver bits.
         * @param value raw surface receiver bit set
         * @return this builder
         */
        public Builder surfaceMask(int value) {
            surfaceMask = value;
            return this;
        }

        /**
         * Replaces overlay receiver bits.
         * @param value raw overlay receiver bit set
         * @return this builder
         */
        public Builder overlayReceiverMask(int value) {
            overlayReceiverMask = value;
            return this;
        }

        /**
         * Replaces object identity bits.
         * @param value object identity bits, non-zero for enabled outlines
         * @return this builder
         */
        public Builder objectMask(int value) {
            objectMask = value;
            return this;
        }

        /**
         * Replaces the outline policy.
         * @param value non-null outline policy
         * @return this builder
         */
        public Builder outline(OutlineStyle value) {
            outline = Objects.requireNonNull(value, "outline");
            return this;
        }

        /**
         * Replaces dominant-axis base-color modulation.
         * @param value non-null cardinal-lighting state
         * @return this builder
         */
        public Builder cardinalLighting(CardinalLightingState value) {
            cardinalLighting = Objects.requireNonNull(value, "cardinalLighting");
            return this;
        }

        /**
         * Replaces continuous two-direction Lambert base-color modulation.
         * @param value non-null directional diffuse state
         * @return this builder
         */
        public Builder directionalDiffuse(DirectionalDiffuseState value) {
            directionalDiffuse = Objects.requireNonNull(value, "directionalDiffuse");
            return this;
        }

        /**
         * Builds validated immutable state.
         * @return immutable render state
         */
        public InstanceRenderState build() {
            if (uvTransform.equals(UvTransform.identity()) && surfaceMask == -1
                    && overlayReceiverMask == 0 && objectMask == 0
                    && outline.equals(OutlineStyle.disabled())
                    && cardinalLighting.equals(CardinalLightingState.disabled())
                    && directionalDiffuse.equals(DirectionalDiffuseState.disabled())) {
                return DEFAULT;
            }
            return new InstanceRenderState(this);
        }
    }
}
