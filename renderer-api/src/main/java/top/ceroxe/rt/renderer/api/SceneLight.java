package top.ceroxe.rt.renderer.api;

/**
 * Immutable persistent analytic light.
 *
 */
public final class SceneLight {
    private final long id;
    private final Type type;
    private final double x;
    private final double y;
    private final double z;
    private final float directionX;
    private final float directionY;
    private final float directionZ;
    private final float red;
    private final float green;
    private final float blue;
    private final float intensity;
    private final float range;
    private final float innerConeCosine;
    private final float outerConeCosine;
    private final boolean castsShadow;

    /**
     * Validates and creates an immutable analytic light.
     *
     * @param id              stable non-negative light identifier
     * @param type            analytic light type
     * @param x               finite world-space origin x coordinate
     * @param y               finite world-space origin y coordinate
     * @param z               finite world-space origin z coordinate
     * @param directionX      normalized direction x component, or zero for point lights
     * @param directionY      normalized direction y component, or zero for point lights
     * @param directionZ      normalized direction z component, or zero for point lights
     * @param red             non-negative linear red radiance component
     * @param green           non-negative linear green radiance component
     * @param blue            non-negative linear blue radiance component
     * @param intensity       non-negative radiance multiplier
     * @param range           positive local-light range, or zero for directional lights
     * @param innerConeCosine spot-light inner cone cosine, or zero otherwise
     * @param outerConeCosine spot-light outer cone cosine, or zero otherwise
     * @param castsShadow     whether the light emits shadow rays
     */
    private SceneLight(
            long id,
            Type type,
            double x,
            double y,
            double z,
            float directionX,
            float directionY,
            float directionZ,
            float red,
            float green,
            float blue,
            float intensity,
            float range,
            float innerConeCosine,
            float outerConeCosine,
            boolean castsShadow
    ) {
        MaterialAsset.requireId(id, "id");
        this.type = java.util.Objects.requireNonNull(type, "type");
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(z, "z");
        requireNonNegative(red, "red");
        requireNonNegative(green, "green");
        requireNonNegative(blue, "blue");
        requireNonNegative(intensity, "intensity");
        requireNonNegative(range, "range");
        requireFinite(directionX, "directionX");
        requireFinite(directionY, "directionY");
        requireFinite(directionZ, "directionZ");
        requireFinite(innerConeCosine, "innerConeCosine");
        requireFinite(outerConeCosine, "outerConeCosine");
        if (type != Type.POINT) {
            requireDirection(directionX, directionY, directionZ);
        } else if (directionX != 0.0F || directionY != 0.0F || directionZ != 0.0F) {
            throw new IllegalArgumentException("point light direction must use the canonical zero vector");
        }
        if (type == Type.SPOT) {
            requireConeCosines(innerConeCosine, outerConeCosine);
        }
        if (type != Type.DIRECTIONAL && range <= 0.0F) {
            throw new IllegalArgumentException("local lights require positive range");
        }
        if (type == Type.DIRECTIONAL && range != 0.0F) {
            throw new IllegalArgumentException("directional light range must use canonical value 0");
        }
        if (type != Type.SPOT && (innerConeCosine != 0.0F || outerConeCosine != 0.0F)) {
            throw new IllegalArgumentException("non-spot cone values must use canonical value 0");
        }
        this.id = id;
        this.x = x;
        this.y = y;
        this.z = z;
        this.directionX = directionX;
        this.directionY = directionY;
        this.directionZ = directionZ;
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.intensity = intensity;
        this.range = range;
        this.innerConeCosine = innerConeCosine;
        this.outerConeCosine = outerConeCosine;
        this.castsShadow = castsShadow;
    }

    /**
     * Starts an omnidirectional point light with white unit radiance and a 10-unit range.
     *
     * @param id stable non-negative light identifier
     * @param x  world-space origin x coordinate
     * @param y  world-space origin y coordinate
     * @param z  world-space origin z coordinate
     * @return semantic light builder
     */
    public static Builder point(long id, double x, double y, double z) {
        return new Builder(id, Type.POINT, x, y, z, 0.0F, 0.0F, 0.0F);
    }

    /**
     * Starts an infinite directional light with white unit radiance.
     *
     * @param id         stable non-negative light identifier
     * @param directionX direction x component; normalization is performed by the builder
     * @param directionY direction y component; normalization is performed by the builder
     * @param directionZ direction z component; normalization is performed by the builder
     * @return semantic light builder
     */
    public static Builder directional(long id, float directionX, float directionY, float directionZ) {
        return new Builder(id, Type.DIRECTIONAL, 0.0D, 0.0D, 0.0D,
                directionX, directionY, directionZ);
    }

    /**
     * Starts a finite spot light with white unit radiance, a 10-unit range, and 20/30-degree
     * inner/outer half-angle cones.
     *
     * @param id         stable non-negative light identifier
     * @param x          world-space origin x coordinate
     * @param y          world-space origin y coordinate
     * @param z          world-space origin z coordinate
     * @param directionX direction x component; normalization is performed by the builder
     * @param directionY direction y component; normalization is performed by the builder
     * @param directionZ direction z component; normalization is performed by the builder
     * @return semantic light builder
     */
    public static Builder spot(
            long id,
            double x,
            double y,
            double z,
            float directionX,
            float directionY,
            float directionZ
    ) {
        return new Builder(id, Type.SPOT, x, y, z, directionX, directionY, directionZ);
    }

    private static void requireDirection(float x, float y, float z) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
            throw new IllegalArgumentException("light direction must be finite");
        }
        float lengthSquared = x * x + y * y + z * z;
        if (Math.abs(lengthSquared - 1.0F) > 2.0E-3F) {
            throw new IllegalArgumentException("light direction must be normalized");
        }
    }

    private static void requireConeCosines(float innerCosine, float outerCosine) {
        if (!Float.isFinite(innerCosine) || !Float.isFinite(outerCosine)
                || innerCosine < -1.0F || innerCosine > 1.0F
                || outerCosine < -1.0F || outerCosine > 1.0F
                || innerCosine < outerCosine) {
            throw new IllegalArgumentException("spot cone cosines must satisfy inner >= outer in [-1, 1]");
        }
    }

    private static void requireNonNegative(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0F) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    /**
     * Returns the stable non-negative light identifier.
     *
     * @return stable non-negative light identifier
     */
    public long id() {
        return id;
    }

    /**
     * Returns the analytic light shape.
     *
     * @return non-null analytic light type
     */
    public Type type() {
        return type;
    }

    /**
     * Returns the world-space origin x coordinate.
     *
     * @return finite origin x coordinate
     */
    public double x() {
        return x;
    }

    /**
     * Returns the world-space origin y coordinate.
     *
     * @return finite origin y coordinate
     */
    public double y() {
        return y;
    }

    /**
     * Returns the world-space origin z coordinate.
     *
     * @return finite origin z coordinate
     */
    public double z() {
        return z;
    }

    /**
     * Returns the normalized direction x component.
     *
     * @return direction x component, or zero for point lights
     */
    public float directionX() {
        return directionX;
    }

    /**
     * Returns the normalized direction y component.
     *
     * @return direction y component, or zero for point lights
     */
    public float directionY() {
        return directionY;
    }

    /**
     * Returns the normalized direction z component.
     *
     * @return direction z component, or zero for point lights
     */
    public float directionZ() {
        return directionZ;
    }

    /**
     * Returns the linear red radiance component.
     *
     * @return non-negative red radiance
     */
    public float red() {
        return red;
    }

    /**
     * Returns the linear green radiance component.
     *
     * @return non-negative green radiance
     */
    public float green() {
        return green;
    }

    /**
     * Returns the linear blue radiance component.
     *
     * @return non-negative blue radiance
     */
    public float blue() {
        return blue;
    }

    /**
     * Returns the radiance multiplier.
     *
     * @return non-negative radiance multiplier
     */
    public float intensity() {
        return intensity;
    }

    /**
     * Returns the finite local-light influence range.
     *
     * @return positive local range, or zero for directional lights
     */
    public float range() {
        return range;
    }

    /**
     * Returns the spot-light inner cone cosine.
     *
     * @return inner cone cosine, or zero for non-spot lights
     */
    public float innerConeCosine() {
        return innerConeCosine;
    }

    /**
     * Returns the spot-light outer cone cosine.
     *
     * @return outer cone cosine, or zero for non-spot lights
     */
    public float outerConeCosine() {
        return outerConeCosine;
    }

    /**
     * Reports whether this light emits shadow rays.
     *
     * @return whether shadow rays are enabled
     */
    public boolean castsShadow() {
        return castsShadow;
    }

    /**
     * Starts an independent builder initialized from this complete light generation.
     *
     * @return builder containing every current light property
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SceneLight light)) return false;
        return id == light.id
                && Double.compare(x, light.x) == 0
                && Double.compare(y, light.y) == 0
                && Double.compare(z, light.z) == 0
                && Float.compare(directionX, light.directionX) == 0
                && Float.compare(directionY, light.directionY) == 0
                && Float.compare(directionZ, light.directionZ) == 0
                && Float.compare(red, light.red) == 0
                && Float.compare(green, light.green) == 0
                && Float.compare(blue, light.blue) == 0
                && Float.compare(intensity, light.intensity) == 0
                && Float.compare(range, light.range) == 0
                && Float.compare(innerConeCosine, light.innerConeCosine) == 0
                && Float.compare(outerConeCosine, light.outerConeCosine) == 0
                && castsShadow == light.castsShadow
                && type == light.type;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(
                id, type, x, y, z,
                directionX, directionY, directionZ,
                red, green, blue, intensity, range,
                innerConeCosine, outerConeCosine, castsShadow
        );
    }

    @Override
    public String toString() {
        return "SceneLight["
                + "id=" + id
                + ", type=" + type
                + ", x=" + x
                + ", y=" + y
                + ", z=" + z
                + ", directionX=" + directionX
                + ", directionY=" + directionY
                + ", directionZ=" + directionZ
                + ", red=" + red
                + ", green=" + green
                + ", blue=" + blue
                + ", intensity=" + intensity
                + ", range=" + range
                + ", innerConeCosine=" + innerConeCosine
                + ", outerConeCosine=" + outerConeCosine
                + ", castsShadow=" + castsShadow
                + ']';
    }

    /**
     * Supported analytic-light shape.
     */
    public enum Type {
        /**
         * Infinite-distance light with parallel rays.
         */
        DIRECTIONAL,
        /**
         * Omnidirectional finite-range point emitter.
         */
        POINT,
        /**
         * Directional finite-range conical emitter.
         */
        SPOT
    }

    /**
     * Single-thread-confined semantic builder for one analytic light.
     */
    public static final class Builder {
        private final long id;
        private final Type type;
        private final double x;
        private final double y;
        private final double z;
        private float directionX;
        private float directionY;
        private float directionZ;
        private float red = 1.0F;
        private float green = 1.0F;
        private float blue = 1.0F;
        private float intensity = 1.0F;
        private float range;
        private float innerConeCosine;
        private float outerConeCosine;
        private boolean castsShadow = true;

        private Builder(
                long id,
                Type type,
                double x,
                double y,
                double z,
                float directionX,
                float directionY,
                float directionZ
        ) {
            MaterialAsset.requireId(id, "id");
            this.id = id;
            this.type = java.util.Objects.requireNonNull(type, "type");
            requireFinite(x, "x");
            requireFinite(y, "y");
            requireFinite(z, "z");
            this.x = x;
            this.y = y;
            this.z = z;
            if (type == Type.POINT) {
                this.directionX = 0.0F;
                this.directionY = 0.0F;
                this.directionZ = 0.0F;
            } else {
                float length = (float) Math.hypot(Math.hypot(directionX, directionY), directionZ);
                if (!Float.isFinite(length) || length <= 1.0E-6F) {
                    throw new IllegalArgumentException("light direction must be finite and non-zero");
                }
                this.directionX = directionX / length;
                this.directionY = directionY / length;
                this.directionZ = directionZ / length;
            }
            range = type == Type.DIRECTIONAL ? 0.0F : 10.0F;
            if (type == Type.SPOT) {
                innerConeCosine = cosineDegrees(20.0F);
                outerConeCosine = cosineDegrees(30.0F);
            }
        }

        private Builder(SceneLight source) {
            id = source.id;
            type = source.type;
            x = source.x;
            y = source.y;
            z = source.z;
            directionX = source.directionX;
            directionY = source.directionY;
            directionZ = source.directionZ;
            red = source.red;
            green = source.green;
            blue = source.blue;
            intensity = source.intensity;
            range = source.range;
            innerConeCosine = source.innerConeCosine;
            outerConeCosine = source.outerConeCosine;
            castsShadow = source.castsShadow;
        }

        private static float cosineDegrees(float degrees) {
            return (float) Math.cos(Math.toRadians(degrees));
        }

        /**
         * Selects linear-light RGB radiance multipliers.
         *
         * @param red   non-negative red component
         * @param green non-negative green component
         * @param blue  non-negative blue component
         * @return this builder
         */
        public Builder color(float red, float green, float blue) {
            requireNonNegative(red, "red");
            requireNonNegative(green, "green");
            requireNonNegative(blue, "blue");
            this.red = red;
            this.green = green;
            this.blue = blue;
            return this;
        }

        /**
         * Selects the radiance multiplier.
         *
         * @param intensity non-negative finite multiplier
         * @return this builder
         */
        public Builder intensity(float intensity) {
            requireNonNegative(intensity, "intensity");
            this.intensity = intensity;
            return this;
        }

        /**
         * Selects an already normalized direction without altering its components.
         *
         * @param x normalized direction x component
         * @param y normalized direction y component
         * @param z normalized direction z component
         * @return this builder
         * @throws IllegalStateException if this builder represents a point light
         */
        public Builder normalizedDirection(float x, float y, float z) {
            if (type == Type.POINT) {
                throw new IllegalStateException("point lights do not have a direction");
            }
            requireDirection(x, y, z);
            directionX = x;
            directionY = y;
            directionZ = z;
            return this;
        }

        /**
         * Selects the finite influence range for a point or spot light.
         *
         * @param range positive finite world-space range
         * @return this builder
         * @throws IllegalStateException if this builder represents a directional light
         */
        public Builder range(float range) {
            if (type == Type.DIRECTIONAL) {
                throw new IllegalStateException("directional lights do not have a finite range");
            }
            if (!Float.isFinite(range) || range <= 0.0F) {
                throw new IllegalArgumentException("local-light range must be finite and positive");
            }
            this.range = range;
            return this;
        }

        /**
         * Selects spot-light inner and outer half-angle cones in degrees.
         *
         * @param innerDegrees inner half-angle in {@code [0, 180]}
         * @param outerDegrees outer half-angle in {@code [inner, 180]}
         * @return this builder
         * @throws IllegalStateException if this builder does not represent a spot light
         */
        public Builder coneDegrees(float innerDegrees, float outerDegrees) {
            if (type != Type.SPOT) throw new IllegalStateException("only spot lights have cone angles");
            if (!Float.isFinite(innerDegrees) || !Float.isFinite(outerDegrees)
                    || innerDegrees < 0.0F || outerDegrees > 180.0F || innerDegrees > outerDegrees) {
                throw new IllegalArgumentException("spot cone degrees must satisfy 0 <= inner <= outer <= 180");
            }
            innerConeCosine = cosineDegrees(innerDegrees);
            outerConeCosine = cosineDegrees(outerDegrees);
            return this;
        }

        /**
         * Selects exact spot-light inner and outer cone cosines.
         *
         * @param innerCosine inner cone cosine in {@code [-1, 1]}
         * @param outerCosine outer cone cosine in {@code [-1, innerCosine]}
         * @return this builder
         * @throws IllegalStateException if this builder does not represent a spot light
         */
        public Builder coneCosines(float innerCosine, float outerCosine) {
            if (type != Type.SPOT) throw new IllegalStateException("only spot lights have cone angles");
            requireConeCosines(innerCosine, outerCosine);
            innerConeCosine = innerCosine;
            outerConeCosine = outerCosine;
            return this;
        }

        /**
         * Selects whether this light emits shadow rays.
         *
         * @param enabled whether shadows are enabled
         * @return this builder
         */
        public Builder castsShadow(boolean enabled) {
            castsShadow = enabled;
            return this;
        }

        /**
         * Builds a validated immutable light.
         *
         * @return immutable analytic light
         */
        public SceneLight build() {
            return new SceneLight(
                    id, type, x, y, z,
                    directionX, directionY, directionZ,
                    red, green, blue, intensity, range,
                    innerConeCosine, outerConeCosine, castsShadow
            );
        }
    }
}
