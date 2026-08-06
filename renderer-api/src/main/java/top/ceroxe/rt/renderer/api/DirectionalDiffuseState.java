package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Immutable per-instance two-direction Lambert modulation for base-color RGB.
 *
 * <p>Each direction points from the shaded surface toward a light and is normalized when the
 * state is built. The renderer evaluates the unperturbed, barycentrically interpolated vertex
 * normal, falling back to the triangle's geometric normal when the mesh has no normal stream.
 * This state changes instance shading data only; it never changes mesh geometry or BLAS
 * ownership.</p>
 */
public final class DirectionalDiffuseState {
    private static final float DEFAULT_FIRST_DIRECTION_X = 0.0F;
    private static final float DEFAULT_FIRST_DIRECTION_Y = 1.0F;
    private static final float DEFAULT_FIRST_DIRECTION_Z = 0.0F;
    private static final float DEFAULT_SECOND_DIRECTION_X = 0.0F;
    private static final float DEFAULT_SECOND_DIRECTION_Y = -1.0F;
    private static final float DEFAULT_SECOND_DIRECTION_Z = 0.0F;
    private static final DirectionalDiffuseState DISABLED = new DirectionalDiffuseState(
            CoordinateSpace.OBJECT,
            DEFAULT_FIRST_DIRECTION_X, DEFAULT_FIRST_DIRECTION_Y, DEFAULT_FIRST_DIRECTION_Z,
            0.0F,
            DEFAULT_SECOND_DIRECTION_X, DEFAULT_SECOND_DIRECTION_Y, DEFAULT_SECOND_DIRECTION_Z,
            0.0F,
            1.0F,
            BackFacePolicy.KEEP_AUTHORED
    );

    private final CoordinateSpace coordinateSpace;
    private final float firstDirectionX;
    private final float firstDirectionY;
    private final float firstDirectionZ;
    private final float firstIntensity;
    private final float secondDirectionX;
    private final float secondDirectionY;
    private final float secondDirectionZ;
    private final float secondIntensity;
    private final float ambient;
    private final BackFacePolicy backFacePolicy;

    private DirectionalDiffuseState(
            CoordinateSpace coordinateSpace,
            float firstDirectionX,
            float firstDirectionY,
            float firstDirectionZ,
            float firstIntensity,
            float secondDirectionX,
            float secondDirectionY,
            float secondDirectionZ,
            float secondIntensity,
            float ambient,
            BackFacePolicy backFacePolicy
    ) {
        this.coordinateSpace = coordinateSpace;
        this.firstDirectionX = firstDirectionX;
        this.firstDirectionY = firstDirectionY;
        this.firstDirectionZ = firstDirectionZ;
        this.firstIntensity = firstIntensity;
        this.secondDirectionX = secondDirectionX;
        this.secondDirectionY = secondDirectionY;
        this.secondDirectionZ = secondDirectionZ;
        this.secondIntensity = secondIntensity;
        this.ambient = ambient;
        this.backFacePolicy = backFacePolicy;
    }

    /**
     * Returns the shared no-op state.
     * @return object-space state whose diffuse multiplier is always {@code 1.0}
     */
    public static DirectionalDiffuseState disabled() {
        return DISABLED;
    }

    /**
     * Starts a thread-confined builder with object-space directions, unit ambient, zero direct
     * intensities, and authored back-face orientation.
     * @return mutable state builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the coordinate space shared by both directions and the evaluated normal.
     * @return non-null coordinate space
     */
    public CoordinateSpace coordinateSpace() {
        return coordinateSpace;
    }

    /**
     * Returns the normalized first direction's X component.
     * @return normalized first direction X component
     */
    public float firstDirectionX() {
        return firstDirectionX;
    }

    /**
     * Returns the normalized first direction's Y component.
     * @return normalized first direction Y component
     */
    public float firstDirectionY() {
        return firstDirectionY;
    }

    /**
     * Returns the normalized first direction's Z component.
     * @return normalized first direction Z component
     */
    public float firstDirectionZ() {
        return firstDirectionZ;
    }

    /**
     * Returns the first Lambert contribution strength.
     * @return first direction intensity in {@code [0, 1]}
     */
    public float firstIntensity() {
        return firstIntensity;
    }

    /**
     * Returns the normalized second direction's X component.
     * @return normalized second direction X component
     */
    public float secondDirectionX() {
        return secondDirectionX;
    }

    /**
     * Returns the normalized second direction's Y component.
     * @return normalized second direction Y component
     */
    public float secondDirectionY() {
        return secondDirectionY;
    }

    /**
     * Returns the normalized second direction's Z component.
     * @return normalized second direction Z component
     */
    public float secondDirectionZ() {
        return secondDirectionZ;
    }

    /**
     * Returns the second Lambert contribution strength.
     * @return second direction intensity in {@code [0, 1]}
     */
    public float secondIntensity() {
        return secondIntensity;
    }

    /**
     * Returns the constant Lambert contribution.
     * @return ambient term in {@code [0, 1]}
     */
    public float ambient() {
        return ambient;
    }

    /**
     * Returns the selected back-face normal orientation policy.
     * @return explicit back-face normal policy
     */
    public BackFacePolicy backFacePolicy() {
        return backFacePolicy;
    }

    /**
     * Reports whether this state can change base-color RGB.
     * @return whether directional diffuse modulation is active
     */
    public boolean enabled() {
        return ambient < 1.0F;
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
        return this == other || other instanceof DirectionalDiffuseState state
                && coordinateSpace == state.coordinateSpace
                && Float.compare(firstDirectionX, state.firstDirectionX) == 0
                && Float.compare(firstDirectionY, state.firstDirectionY) == 0
                && Float.compare(firstDirectionZ, state.firstDirectionZ) == 0
                && Float.compare(firstIntensity, state.firstIntensity) == 0
                && Float.compare(secondDirectionX, state.secondDirectionX) == 0
                && Float.compare(secondDirectionY, state.secondDirectionY) == 0
                && Float.compare(secondDirectionZ, state.secondDirectionZ) == 0
                && Float.compare(secondIntensity, state.secondIntensity) == 0
                && Float.compare(ambient, state.ambient) == 0
                && backFacePolicy == state.backFacePolicy;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                coordinateSpace,
                firstDirectionX, firstDirectionY, firstDirectionZ, firstIntensity,
                secondDirectionX, secondDirectionY, secondDirectionZ, secondIntensity,
                ambient, backFacePolicy
        );
    }

    @Override
    public String toString() {
        return "DirectionalDiffuseState[coordinateSpace=" + coordinateSpace
                + ", firstDirection=(" + firstDirectionX + ", " + firstDirectionY
                + ", " + firstDirectionZ + ')'
                + ", firstIntensity=" + firstIntensity
                + ", secondDirection=(" + secondDirectionX + ", " + secondDirectionY
                + ", " + secondDirectionZ + ')'
                + ", secondIntensity=" + secondIntensity
                + ", ambient=" + ambient
                + ", backFacePolicy=" + backFacePolicy + ']';
    }

    /** Single-thread-confined semantic builder. */
    public static final class Builder {
        private CoordinateSpace coordinateSpace = CoordinateSpace.OBJECT;
        private float firstDirectionX = DEFAULT_FIRST_DIRECTION_X;
        private float firstDirectionY = DEFAULT_FIRST_DIRECTION_Y;
        private float firstDirectionZ = DEFAULT_FIRST_DIRECTION_Z;
        private boolean firstDirectionDefined;
        private float firstIntensity;
        private float secondDirectionX = DEFAULT_SECOND_DIRECTION_X;
        private float secondDirectionY = DEFAULT_SECOND_DIRECTION_Y;
        private float secondDirectionZ = DEFAULT_SECOND_DIRECTION_Z;
        private boolean secondDirectionDefined;
        private float secondIntensity;
        private float ambient = 1.0F;
        private BackFacePolicy backFacePolicy = BackFacePolicy.KEEP_AUTHORED;

        private Builder() {
        }

        private Builder(DirectionalDiffuseState source) {
            coordinateSpace = source.coordinateSpace;
            firstDirectionX = source.firstDirectionX;
            firstDirectionY = source.firstDirectionY;
            firstDirectionZ = source.firstDirectionZ;
            firstDirectionDefined = true;
            firstIntensity = source.firstIntensity;
            secondDirectionX = source.secondDirectionX;
            secondDirectionY = source.secondDirectionY;
            secondDirectionZ = source.secondDirectionZ;
            secondDirectionDefined = true;
            secondIntensity = source.secondIntensity;
            ambient = source.ambient;
            backFacePolicy = source.backFacePolicy;
        }

        /**
         * Selects the coordinate space shared by both directions and the shading normal.
         * @param value non-null coordinate space
         * @return this builder
         */
        public Builder coordinateSpace(CoordinateSpace value) {
            coordinateSpace = Objects.requireNonNull(value, "coordinateSpace");
            return this;
        }

        /**
         * Sets the first surface-to-light direction. The vector is normalized during build.
         * @param x X component
         * @param y Y component
         * @param z Z component
         * @return this builder
         */
        public Builder firstDirection(float x, float y, float z) {
            requireFiniteDirection(x, y, z, "firstDirection");
            firstDirectionX = x;
            firstDirectionY = y;
            firstDirectionZ = z;
            firstDirectionDefined = true;
            return this;
        }

        /**
         * Sets the first Lambert contribution strength.
         * @param value finite intensity in {@code [0, 1]}
         * @return this builder
         */
        public Builder firstIntensity(float value) {
            firstIntensity = requireUnitInterval(value, "firstIntensity");
            return this;
        }

        /**
         * Sets the second surface-to-light direction. The vector is normalized during build.
         * @param x X component
         * @param y Y component
         * @param z Z component
         * @return this builder
         */
        public Builder secondDirection(float x, float y, float z) {
            requireFiniteDirection(x, y, z, "secondDirection");
            secondDirectionX = x;
            secondDirectionY = y;
            secondDirectionZ = z;
            secondDirectionDefined = true;
            return this;
        }

        /**
         * Sets the second Lambert contribution strength.
         * @param value finite intensity in {@code [0, 1]}
         * @return this builder
         */
        public Builder secondIntensity(float value) {
            secondIntensity = requireUnitInterval(value, "secondIntensity");
            return this;
        }

        /**
         * Sets the constant Lambert term.
         * @param value finite ambient contribution in {@code [0, 1]}
         * @return this builder
         */
        public Builder ambient(float value) {
            ambient = requireUnitInterval(value, "ambient");
            return this;
        }

        /**
         * Selects whether back-face hits retain authored normal orientation or flip it once.
         * @param value non-null back-face policy
         * @return this builder
         */
        public Builder backFacePolicy(BackFacePolicy value) {
            backFacePolicy = Objects.requireNonNull(value, "backFacePolicy");
            return this;
        }

        /**
         * Builds validated immutable state. Unit ambient is canonicalized to the shared no-op
         * state because all direct terms are non-negative and the final multiplier is clamped.
         * @return immutable directional diffuse state
         */
        public DirectionalDiffuseState build() {
            if (ambient == 1.0F) {
                return DISABLED;
            }
            if (firstIntensity > 0.0F && !firstDirectionDefined) {
                throw new IllegalStateException("positive firstIntensity requires firstDirection");
            }
            if (secondIntensity > 0.0F && !secondDirectionDefined) {
                throw new IllegalStateException("positive secondIntensity requires secondDirection");
            }
            float[] first = normalized(
                    firstDirectionX, firstDirectionY, firstDirectionZ, "firstDirection"
            );
            float[] second = normalized(
                    secondDirectionX, secondDirectionY, secondDirectionZ, "secondDirection"
            );
            return new DirectionalDiffuseState(
                    coordinateSpace,
                    first[0], first[1], first[2], firstIntensity,
                    second[0], second[1], second[2], secondIntensity,
                    ambient,
                    backFacePolicy
            );
        }
    }

    private static void requireFiniteDirection(float x, float y, float z, String name) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
            throw new IllegalArgumentException(name + " components must be finite");
        }
        if (x == 0.0F && y == 0.0F && z == 0.0F) {
            throw new IllegalArgumentException(name + " must be non-zero");
        }
    }

    private static float[] normalized(float x, float y, float z, String name) {
        requireFiniteDirection(x, y, z, name);
        double length = Math.sqrt((double) x * x + (double) y * y + (double) z * z);
        return new float[]{(float) (x / length), (float) (y / length), (float) (z / length)};
    }

    private static float requireUnitInterval(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0F || value > 1.0F) {
            throw new IllegalArgumentException(name + " must be finite and in [0, 1]");
        }
        return value;
    }

    /** Coordinate space in which directions and the unperturbed shading normal are evaluated. */
    public enum CoordinateSpace {
        /** Mesh-local directions; rotating an instance rotates its diffuse-lighting pattern. */
        OBJECT,
        /** World directions; rotating an instance changes its diffuse response. */
        WORLD
    }

    /** Back-face orientation policy for the unperturbed shading normal. */
    public enum BackFacePolicy {
        /** Preserve the orientation authored by mesh vertex winding and vertex normals. */
        KEEP_AUTHORED,
        /** Negate the authored normal exactly when the ray hits the triangle's back face. */
        FLIP_ON_BACK_FACE
    }
}
