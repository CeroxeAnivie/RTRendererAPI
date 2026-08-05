package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Immutable per-instance color modulation selected from the dominant geometric-normal axis.
 *
 * <p>The six multipliers darken base-color RGB without changing material identity, mesh geometry,
 * or BLAS ownership. Classification uses the unperturbed geometric normal, so normal maps affect
 * terminal lighting but cannot move a fragment between cardinal faces.</p>
 */
public final class CardinalLightingState {
    private static final CardinalLightingState DISABLED = new CardinalLightingState(
            CoordinateSpace.OBJECT, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F
    );

    private final CoordinateSpace coordinateSpace;
    private final float negativeX;
    private final float positiveX;
    private final float negativeY;
    private final float positiveY;
    private final float negativeZ;
    private final float positiveZ;

    private CardinalLightingState(
            CoordinateSpace coordinateSpace,
            float negativeX,
            float positiveX,
            float negativeY,
            float positiveY,
            float negativeZ,
            float positiveZ
    ) {
        this.coordinateSpace = Objects.requireNonNull(coordinateSpace, "coordinateSpace");
        this.negativeX = requireMultiplier(negativeX, "negativeX");
        this.positiveX = requireMultiplier(positiveX, "positiveX");
        this.negativeY = requireMultiplier(negativeY, "negativeY");
        this.positiveY = requireMultiplier(positiveY, "positiveY");
        this.negativeZ = requireMultiplier(negativeZ, "negativeZ");
        this.positiveZ = requireMultiplier(positiveZ, "positiveZ");
    }

    /**
     * Returns the shared no-op state.
     * @return object-space state whose six multipliers are {@code 1.0}
     */
    public static CardinalLightingState disabled() {
        return DISABLED;
    }

    /**
     * Creates cardinal modulation classified in mesh object space.
     *
     * @param negativeX multiplier for the dominant negative-X face
     * @param positiveX multiplier for the dominant positive-X face
     * @param negativeY multiplier for the dominant negative-Y face
     * @param positiveY multiplier for the dominant positive-Y face
     * @param negativeZ multiplier for the dominant negative-Z face
     * @param positiveZ multiplier for the dominant positive-Z face
     * @return immutable object-space state, or the shared disabled state when every value is one
     */
    public static CardinalLightingState objectSpace(
            float negativeX,
            float positiveX,
            float negativeY,
            float positiveY,
            float negativeZ,
            float positiveZ
    ) {
        return create(
                CoordinateSpace.OBJECT,
                negativeX, positiveX, negativeY, positiveY, negativeZ, positiveZ
        );
    }

    /**
     * Creates cardinal modulation classified after the instance transform in world space.
     *
     * @param negativeX multiplier for the dominant negative-X face
     * @param positiveX multiplier for the dominant positive-X face
     * @param negativeY multiplier for the dominant negative-Y face
     * @param positiveY multiplier for the dominant positive-Y face
     * @param negativeZ multiplier for the dominant negative-Z face
     * @param positiveZ multiplier for the dominant positive-Z face
     * @return immutable world-space state, or the shared disabled state when every value is one
     */
    public static CardinalLightingState worldSpace(
            float negativeX,
            float positiveX,
            float negativeY,
            float positiveY,
            float negativeZ,
            float positiveZ
    ) {
        return create(
                CoordinateSpace.WORLD,
                negativeX, positiveX, negativeY, positiveY, negativeZ, positiveZ
        );
    }

    private static CardinalLightingState create(
            CoordinateSpace coordinateSpace,
            float negativeX,
            float positiveX,
            float negativeY,
            float positiveY,
            float negativeZ,
            float positiveZ
    ) {
        CardinalLightingState state = new CardinalLightingState(
                coordinateSpace,
                negativeX, positiveX, negativeY, positiveY, negativeZ, positiveZ
        );
        return state.enabled() ? state : DISABLED;
    }

    /**
     * Returns the coordinate space used for dominant-axis classification.
     * @return non-null coordinate space
     */
    public CoordinateSpace coordinateSpace() {
        return coordinateSpace;
    }

    /**
     * Returns the negative-X multiplier.
     * @return value in {@code [0, 1]}
     */
    public float negativeX() {
        return negativeX;
    }

    /**
     * Returns the positive-X multiplier.
     * @return value in {@code [0, 1]}
     */
    public float positiveX() {
        return positiveX;
    }

    /**
     * Returns the negative-Y multiplier.
     * @return value in {@code [0, 1]}
     */
    public float negativeY() {
        return negativeY;
    }

    /**
     * Returns the positive-Y multiplier.
     * @return value in {@code [0, 1]}
     */
    public float positiveY() {
        return positiveY;
    }

    /**
     * Returns the negative-Z multiplier.
     * @return value in {@code [0, 1]}
     */
    public float negativeZ() {
        return negativeZ;
    }

    /**
     * Returns the positive-Z multiplier.
     * @return value in {@code [0, 1]}
     */
    public float positiveZ() {
        return positiveZ;
    }

    /**
     * Reports whether at least one face changes base-color RGB.
     * @return whether cardinal modulation is active
     */
    public boolean enabled() {
        return negativeX != 1.0F || positiveX != 1.0F
                || negativeY != 1.0F || positiveY != 1.0F
                || negativeZ != 1.0F || positiveZ != 1.0F;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof CardinalLightingState state
                && coordinateSpace == state.coordinateSpace
                && Float.compare(negativeX, state.negativeX) == 0
                && Float.compare(positiveX, state.positiveX) == 0
                && Float.compare(negativeY, state.negativeY) == 0
                && Float.compare(positiveY, state.positiveY) == 0
                && Float.compare(negativeZ, state.negativeZ) == 0
                && Float.compare(positiveZ, state.positiveZ) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                coordinateSpace,
                negativeX, positiveX, negativeY, positiveY, negativeZ, positiveZ
        );
    }

    @Override
    public String toString() {
        return "CardinalLightingState[coordinateSpace=" + coordinateSpace
                + ", negativeX=" + negativeX
                + ", positiveX=" + positiveX
                + ", negativeY=" + negativeY
                + ", positiveY=" + positiveY
                + ", negativeZ=" + negativeZ
                + ", positiveZ=" + positiveZ + ']';
    }

    private static float requireMultiplier(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0F || value > 1.0F) {
            throw new IllegalArgumentException(name + " must be finite and in [0, 1]");
        }
        return value;
    }

    /** Coordinate space in which the unperturbed geometric normal is classified. */
    public enum CoordinateSpace {
        /** Mesh-local direction; rotating an instance rotates its face-lighting pattern. */
        OBJECT,
        /** World direction; rotating an instance changes which world-facing multiplier applies. */
        WORLD
    }
}
