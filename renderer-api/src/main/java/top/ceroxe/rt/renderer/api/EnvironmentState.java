package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Immutable frame-local lighting and camera-medium facts.
 */
public final class EnvironmentState {
    private final float skyRed;
    private final float skyGreen;
    private final float skyBlue;
    private final float ambientIntensity;
    private final float sunDirectionX;
    private final float sunDirectionY;
    private final float sunDirectionZ;
    private final float sunRed;
    private final float sunGreen;
    private final float sunBlue;
    private final float sunIntensity;
    private final Medium cameraMedium;

    private EnvironmentState(
            float skyRed,
            float skyGreen,
            float skyBlue,
            float ambientIntensity,
            float sunDirectionX,
            float sunDirectionY,
            float sunDirectionZ,
            float sunRed,
            float sunGreen,
            float sunBlue,
            float sunIntensity,
            Medium cameraMedium
    ) {
        requireNonNegativeFinite(skyRed, "skyRed");
        requireNonNegativeFinite(skyGreen, "skyGreen");
        requireNonNegativeFinite(skyBlue, "skyBlue");
        requireNonNegativeFinite(ambientIntensity, "ambientIntensity");
        requireDirection(sunDirectionX, sunDirectionY, sunDirectionZ);
        requireNonNegativeFinite(sunRed, "sunRed");
        requireNonNegativeFinite(sunGreen, "sunGreen");
        requireNonNegativeFinite(sunBlue, "sunBlue");
        requireNonNegativeFinite(sunIntensity, "sunIntensity");
        this.skyRed = skyRed;
        this.skyGreen = skyGreen;
        this.skyBlue = skyBlue;
        this.ambientIntensity = ambientIntensity;
        this.sunDirectionX = sunDirectionX;
        this.sunDirectionY = sunDirectionY;
        this.sunDirectionZ = sunDirectionZ;
        this.sunRed = sunRed;
        this.sunGreen = sunGreen;
        this.sunBlue = sunBlue;
        this.sunIntensity = sunIntensity;
        this.cameraMedium = Objects.requireNonNull(cameraMedium, "cameraMedium");
    }

    /**
     * Returns the shared environment with no sky, ambient, or sun contribution.
     *
     * @return immutable neutral vacuum environment
     */
    public static EnvironmentState neutral() {
        return NeutralHolder.INSTANCE;
    }

    /**
     * Starts a semantic builder initialized to {@link #neutral()}.
     *
     * @return new single-thread-confined environment builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private static void requireDirection(float x, float y, float z) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
            throw new IllegalArgumentException("sun direction must be finite");
        }
        float lengthSquared = x * x + y * y + z * z;
        if (Math.abs(lengthSquared - 1.0F) > 2.0E-3F) {
            throw new IllegalArgumentException("sun direction must be normalized");
        }
    }

    private static void requireNonNegativeFinite(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0F) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }

    /**
     * Returns the linear sky-radiance red component.
     *
     * @return finite non-negative red component
     */
    public float skyRed() {
        return skyRed;
    }

    /**
     * Returns the linear sky-radiance green component.
     *
     * @return finite non-negative green component
     */
    public float skyGreen() {
        return skyGreen;
    }

    /**
     * Returns the linear sky-radiance blue component.
     *
     * @return finite non-negative blue component
     */
    public float skyBlue() {
        return skyBlue;
    }

    /**
     * Returns the ambient-light multiplier.
     *
     * @return finite non-negative ambient intensity
     */
    public float ambientIntensity() {
        return ambientIntensity;
    }

    /**
     * Returns the normalized sun-direction x component.
     *
     * @return finite normalized x component
     */
    public float sunDirectionX() {
        return sunDirectionX;
    }

    /**
     * Returns the normalized sun-direction y component.
     *
     * @return finite normalized y component
     */
    public float sunDirectionY() {
        return sunDirectionY;
    }

    /**
     * Returns the normalized sun-direction z component.
     *
     * @return finite normalized z component
     */
    public float sunDirectionZ() {
        return sunDirectionZ;
    }

    /**
     * Returns the linear sun-radiance red component.
     *
     * @return finite non-negative red component
     */
    public float sunRed() {
        return sunRed;
    }

    /**
     * Returns the linear sun-radiance green component.
     *
     * @return finite non-negative green component
     */
    public float sunGreen() {
        return sunGreen;
    }

    /**
     * Returns the linear sun-radiance blue component.
     *
     * @return finite non-negative blue component
     */
    public float sunBlue() {
        return sunBlue;
    }

    /**
     * Returns the sun-light multiplier.
     *
     * @return finite non-negative sun intensity
     */
    public float sunIntensity() {
        return sunIntensity;
    }

    /**
     * Returns the participating medium containing the camera.
     *
     * @return non-null immutable camera medium
     */
    public Medium cameraMedium() {
        return cameraMedium;
    }

    /**
     * Starts an independent builder initialized from this complete environment.
     *
     * @return new builder containing every current environment property
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof EnvironmentState environment)) return false;
        return Float.compare(skyRed, environment.skyRed) == 0
                && Float.compare(skyGreen, environment.skyGreen) == 0
                && Float.compare(skyBlue, environment.skyBlue) == 0
                && Float.compare(ambientIntensity, environment.ambientIntensity) == 0
                && Float.compare(sunDirectionX, environment.sunDirectionX) == 0
                && Float.compare(sunDirectionY, environment.sunDirectionY) == 0
                && Float.compare(sunDirectionZ, environment.sunDirectionZ) == 0
                && Float.compare(sunRed, environment.sunRed) == 0
                && Float.compare(sunGreen, environment.sunGreen) == 0
                && Float.compare(sunBlue, environment.sunBlue) == 0
                && Float.compare(sunIntensity, environment.sunIntensity) == 0
                && cameraMedium.equals(environment.cameraMedium);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                skyRed, skyGreen, skyBlue, ambientIntensity,
                sunDirectionX, sunDirectionY, sunDirectionZ,
                sunRed, sunGreen, sunBlue, sunIntensity, cameraMedium
        );
    }

    @Override
    public String toString() {
        return "EnvironmentState["
                + "skyRed=" + skyRed
                + ", skyGreen=" + skyGreen
                + ", skyBlue=" + skyBlue
                + ", ambientIntensity=" + ambientIntensity
                + ", sunDirectionX=" + sunDirectionX
                + ", sunDirectionY=" + sunDirectionY
                + ", sunDirectionZ=" + sunDirectionZ
                + ", sunRed=" + sunRed
                + ", sunGreen=" + sunGreen
                + ", sunBlue=" + sunBlue
                + ", sunIntensity=" + sunIntensity
                + ", cameraMedium=" + cameraMedium
                + ']';
    }

    /**
     * Single-thread-confined builder for an immutable environment generation.
     */
    public static final class Builder {
        private float skyRed;
        private float skyGreen;
        private float skyBlue;
        private float ambientIntensity;
        private float sunDirectionX;
        private float sunDirectionY = 1.0F;
        private float sunDirectionZ;
        private float sunRed = 1.0F;
        private float sunGreen = 1.0F;
        private float sunBlue = 1.0F;
        private float sunIntensity;
        private Medium cameraMedium = Medium.vacuum();

        private Builder() {
        }

        private Builder(EnvironmentState source) {
            skyRed = source.skyRed;
            skyGreen = source.skyGreen;
            skyBlue = source.skyBlue;
            ambientIntensity = source.ambientIntensity;
            sunDirectionX = source.sunDirectionX;
            sunDirectionY = source.sunDirectionY;
            sunDirectionZ = source.sunDirectionZ;
            sunRed = source.sunRed;
            sunGreen = source.sunGreen;
            sunBlue = source.sunBlue;
            sunIntensity = source.sunIntensity;
            cameraMedium = source.cameraMedium;
        }

        /**
         * Selects linear sky radiance.
         *
         * @param red   finite non-negative red component
         * @param green finite non-negative green component
         * @param blue  finite non-negative blue component
         * @return this builder
         */
        public Builder skyRadiance(float red, float green, float blue) {
            skyRed = red;
            skyGreen = green;
            skyBlue = blue;
            return this;
        }

        /**
         * Selects the ambient-light multiplier.
         *
         * @param value finite non-negative intensity
         * @return this builder
         */
        public Builder ambientIntensity(float value) {
            ambientIntensity = value;
            return this;
        }

        /**
         * Selects an already normalized sun direction.
         *
         * @param x normalized x component
         * @param y normalized y component
         * @param z normalized z component
         * @return this builder
         */
        public Builder sunDirection(float x, float y, float z) {
            sunDirectionX = x;
            sunDirectionY = y;
            sunDirectionZ = z;
            return this;
        }

        /**
         * Selects linear sun radiance.
         *
         * @param red   finite non-negative red component
         * @param green finite non-negative green component
         * @param blue  finite non-negative blue component
         * @return this builder
         */
        public Builder sunRadiance(float red, float green, float blue) {
            sunRed = red;
            sunGreen = green;
            sunBlue = blue;
            return this;
        }

        /**
         * Selects the sun-light multiplier.
         *
         * @param value finite non-negative intensity
         * @return this builder
         */
        public Builder sunIntensity(float value) {
            sunIntensity = value;
            return this;
        }

        /**
         * Selects the participating medium containing the camera.
         *
         * @param value non-null immutable medium
         * @return this builder
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public Builder cameraMedium(Medium value) {
            cameraMedium = Objects.requireNonNull(value, "cameraMedium");
            return this;
        }

        /**
         * Validates and returns an immutable environment.
         *
         * @return immutable validated environment
         * @throws IllegalArgumentException if numeric values or the sun direction are invalid
         */
        public EnvironmentState build() {
            return new EnvironmentState(
                    skyRed, skyGreen, skyBlue, ambientIntensity,
                    sunDirectionX, sunDirectionY, sunDirectionZ,
                    sunRed, sunGreen, sunBlue, sunIntensity, cameraMedium
            );
        }
    }

    /**
     * Immutable homogeneous participating-medium parameters at the camera.
     */
    public static final class Medium {
        private static final Medium VACUUM = new Medium(
                0.0F, 0.0F, 0.0F,
                0.0F, 0.0F, 0.0F,
                0.0F, 1.0F
        );

        private final float extinctionRed;
        private final float extinctionGreen;
        private final float extinctionBlue;
        private final float scatteringRed;
        private final float scatteringGreen;
        private final float scatteringBlue;
        private final float density;
        private final float indexOfRefraction;

        private Medium(
                float extinctionRed,
                float extinctionGreen,
                float extinctionBlue,
                float scatteringRed,
                float scatteringGreen,
                float scatteringBlue,
                float density,
                float indexOfRefraction
        ) {
            requireNonNegativeFinite(extinctionRed, "extinctionRed");
            requireNonNegativeFinite(extinctionGreen, "extinctionGreen");
            requireNonNegativeFinite(extinctionBlue, "extinctionBlue");
            requireNonNegativeFinite(scatteringRed, "scatteringRed");
            requireNonNegativeFinite(scatteringGreen, "scatteringGreen");
            requireNonNegativeFinite(scatteringBlue, "scatteringBlue");
            requireNonNegativeFinite(density, "density");
            if (!Float.isFinite(indexOfRefraction) || indexOfRefraction < 1.0F) {
                throw new IllegalArgumentException("indexOfRefraction must be finite and at least 1");
            }
            this.extinctionRed = extinctionRed;
            this.extinctionGreen = extinctionGreen;
            this.extinctionBlue = extinctionBlue;
            this.scatteringRed = scatteringRed;
            this.scatteringGreen = scatteringGreen;
            this.scatteringBlue = scatteringBlue;
            this.density = density;
            this.indexOfRefraction = indexOfRefraction;
        }

        /**
         * Returns the shared non-participating medium with unit refractive index.
         *
         * @return immutable vacuum medium
         */
        public static Medium vacuum() {
            return VACUUM;
        }

        /**
         * Starts a semantic builder initialized to {@link #vacuum()}.
         *
         * @return new single-thread-confined medium builder
         */
        public static Builder builder() {
            return new Builder();
        }

        /**
         * Returns the red extinction coefficient.
         *
         * @return finite non-negative red extinction
         */
        public float extinctionRed() {
            return extinctionRed;
        }

        /**
         * Returns the green extinction coefficient.
         *
         * @return finite non-negative green extinction
         */
        public float extinctionGreen() {
            return extinctionGreen;
        }

        /**
         * Returns the blue extinction coefficient.
         *
         * @return finite non-negative blue extinction
         */
        public float extinctionBlue() {
            return extinctionBlue;
        }

        /**
         * Returns the red scattering coefficient.
         *
         * @return finite non-negative red scattering
         */
        public float scatteringRed() {
            return scatteringRed;
        }

        /**
         * Returns the green scattering coefficient.
         *
         * @return finite non-negative green scattering
         */
        public float scatteringGreen() {
            return scatteringGreen;
        }

        /**
         * Returns the blue scattering coefficient.
         *
         * @return finite non-negative blue scattering
         */
        public float scatteringBlue() {
            return scatteringBlue;
        }

        /**
         * Returns the density multiplier.
         *
         * @return finite non-negative density
         */
        public float density() {
            return density;
        }

        /**
         * Returns the refractive index.
         *
         * @return finite refractive index greater than or equal to one
         */
        public float indexOfRefraction() {
            return indexOfRefraction;
        }

        /**
         * Starts an independent builder initialized from this complete medium.
         *
         * @return new builder containing every current optical property
         */
        public Builder toBuilder() {
            return new Builder(this);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Medium medium)) return false;
            return Float.compare(extinctionRed, medium.extinctionRed) == 0
                    && Float.compare(extinctionGreen, medium.extinctionGreen) == 0
                    && Float.compare(extinctionBlue, medium.extinctionBlue) == 0
                    && Float.compare(scatteringRed, medium.scatteringRed) == 0
                    && Float.compare(scatteringGreen, medium.scatteringGreen) == 0
                    && Float.compare(scatteringBlue, medium.scatteringBlue) == 0
                    && Float.compare(density, medium.density) == 0
                    && Float.compare(indexOfRefraction, medium.indexOfRefraction) == 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    extinctionRed, extinctionGreen, extinctionBlue,
                    scatteringRed, scatteringGreen, scatteringBlue,
                    density, indexOfRefraction
            );
        }

        @Override
        public String toString() {
            return "Medium["
                    + "extinctionRed=" + extinctionRed
                    + ", extinctionGreen=" + extinctionGreen
                    + ", extinctionBlue=" + extinctionBlue
                    + ", scatteringRed=" + scatteringRed
                    + ", scatteringGreen=" + scatteringGreen
                    + ", scatteringBlue=" + scatteringBlue
                    + ", density=" + density
                    + ", indexOfRefraction=" + indexOfRefraction
                    + ']';
        }

        /**
         * Single-thread-confined builder for immutable homogeneous-medium parameters.
         */
        public static final class Builder {
            private float extinctionRed;
            private float extinctionGreen;
            private float extinctionBlue;
            private float scatteringRed;
            private float scatteringGreen;
            private float scatteringBlue;
            private float density;
            private float indexOfRefraction = 1.0F;

            private Builder() {
            }

            private Builder(Medium source) {
                extinctionRed = source.extinctionRed;
                extinctionGreen = source.extinctionGreen;
                extinctionBlue = source.extinctionBlue;
                scatteringRed = source.scatteringRed;
                scatteringGreen = source.scatteringGreen;
                scatteringBlue = source.scatteringBlue;
                density = source.density;
                indexOfRefraction = source.indexOfRefraction;
            }

            /**
             * Selects spectral extinction coefficients.
             *
             * @param red   finite non-negative red coefficient
             * @param green finite non-negative green coefficient
             * @param blue  finite non-negative blue coefficient
             * @return this builder
             */
            public Builder extinction(float red, float green, float blue) {
                extinctionRed = red;
                extinctionGreen = green;
                extinctionBlue = blue;
                return this;
            }

            /**
             * Selects spectral scattering coefficients.
             *
             * @param red   finite non-negative red coefficient
             * @param green finite non-negative green coefficient
             * @param blue  finite non-negative blue coefficient
             * @return this builder
             */
            public Builder scattering(float red, float green, float blue) {
                scatteringRed = red;
                scatteringGreen = green;
                scatteringBlue = blue;
                return this;
            }

            /**
             * Selects the density multiplier.
             *
             * @param value finite non-negative density
             * @return this builder
             */
            public Builder density(float value) {
                density = value;
                return this;
            }

            /**
             * Selects the refractive index.
             *
             * @param value finite refractive index greater than or equal to one
             * @return this builder
             */
            public Builder indexOfRefraction(float value) {
                indexOfRefraction = value;
                return this;
            }

            /**
             * Validates and returns immutable homogeneous-medium parameters.
             *
             * @return immutable validated medium
             * @throws IllegalArgumentException if an optical property is invalid
             */
            public Medium build() {
                return new Medium(
                        extinctionRed, extinctionGreen, extinctionBlue,
                        scatteringRed, scatteringGreen, scatteringBlue,
                        density, indexOfRefraction
                );
            }
        }
    }

    private static final class NeutralHolder {
        private static final EnvironmentState INSTANCE = new EnvironmentState(
                0.0F, 0.0F, 0.0F, 0.0F,
                0.0F, 1.0F, 0.0F,
                1.0F, 1.0F, 1.0F, 0.0F,
                Medium.vacuum()
        );
    }
}
