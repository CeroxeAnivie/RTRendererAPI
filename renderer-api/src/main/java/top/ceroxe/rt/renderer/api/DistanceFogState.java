package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Immutable frame-local two-distance linear fog.
 */
public final class DistanceFogState {
    private static final DistanceFogState DISABLED = new DistanceFogState(
            0.0F, 0.0F, 0.0F, 0.0F,
            0.0F, 1.0F, 0.0F, 1.0F
    );

    private final float red;
    private final float green;
    private final float blue;
    private final float opacity;
    private final float sphericalStart;
    private final float sphericalEnd;
    private final float cylindricalStart;
    private final float cylindricalEnd;

    private DistanceFogState(
            float red,
            float green,
            float blue,
            float opacity,
            float sphericalStart,
            float sphericalEnd,
            float cylindricalStart,
            float cylindricalEnd
    ) {
        requireUnit(red, "red");
        requireUnit(green, "green");
        requireUnit(blue, "blue");
        requireUnit(opacity, "opacity");
        requireFinite(sphericalStart, "sphericalStart");
        requireFinite(sphericalEnd, "sphericalEnd");
        requireFinite(cylindricalStart, "cylindricalStart");
        requireFinite(cylindricalEnd, "cylindricalEnd");
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.opacity = opacity;
        this.sphericalStart = sphericalStart;
        this.sphericalEnd = sphericalEnd;
        this.cylindricalStart = cylindricalStart;
        this.cylindricalEnd = cylindricalEnd;
    }

    /**
     * Returns the shared canonical state that contributes no fog.
     *
     * @return immutable disabled fog state
     */
    public static DistanceFogState disabled() {
        return DISABLED;
    }

    /**
     * Starts a semantic builder initialized to {@link #disabled()}.
     *
     * @return new single-thread-confined fog builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private static void requireUnit(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0F || value > 1.0F) {
            throw new IllegalArgumentException(name + " must be finite and in [0, 1]");
        }
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    /**
     * Returns the linear fog-color red component.
     *
     * @return finite red component in {@code [0, 1]}
     */
    public float red() {
        return red;
    }

    /**
     * Returns the linear fog-color green component.
     *
     * @return finite green component in {@code [0, 1]}
     */
    public float green() {
        return green;
    }

    /**
     * Returns the linear fog-color blue component.
     *
     * @return finite blue component in {@code [0, 1]}
     */
    public float blue() {
        return blue;
    }

    /**
     * Returns the fog opacity.
     *
     * @return finite opacity in {@code [0, 1]}
     */
    public float opacity() {
        return opacity;
    }

    /**
     * Returns the spherical-distance fade start.
     *
     * @return finite start distance in world units
     */
    public float sphericalStart() {
        return sphericalStart;
    }

    /**
     * Returns the spherical-distance fade end.
     *
     * @return finite end distance in world units
     */
    public float sphericalEnd() {
        return sphericalEnd;
    }

    /**
     * Returns the cylindrical-distance fade start.
     *
     * @return finite start distance in world units
     */
    public float cylindricalStart() {
        return cylindricalStart;
    }

    /**
     * Returns the cylindrical-distance fade end.
     *
     * @return finite end distance in world units
     */
    public float cylindricalEnd() {
        return cylindricalEnd;
    }

    /**
     * Starts an independent builder initialized from this complete fog state.
     *
     * @return new builder containing every current fog property
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof DistanceFogState fog)) return false;
        return Float.compare(red, fog.red) == 0
                && Float.compare(green, fog.green) == 0
                && Float.compare(blue, fog.blue) == 0
                && Float.compare(opacity, fog.opacity) == 0
                && Float.compare(sphericalStart, fog.sphericalStart) == 0
                && Float.compare(sphericalEnd, fog.sphericalEnd) == 0
                && Float.compare(cylindricalStart, fog.cylindricalStart) == 0
                && Float.compare(cylindricalEnd, fog.cylindricalEnd) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                red, green, blue, opacity,
                sphericalStart, sphericalEnd, cylindricalStart, cylindricalEnd
        );
    }

    @Override
    public String toString() {
        return "DistanceFogState["
                + "red=" + red
                + ", green=" + green
                + ", blue=" + blue
                + ", opacity=" + opacity
                + ", sphericalStart=" + sphericalStart
                + ", sphericalEnd=" + sphericalEnd
                + ", cylindricalStart=" + cylindricalStart
                + ", cylindricalEnd=" + cylindricalEnd
                + ']';
    }

    /**
     * Single-thread-confined builder for immutable distance-fog parameters.
     */
    public static final class Builder {
        private float red;
        private float green;
        private float blue;
        private float opacity;
        private float sphericalStart;
        private float sphericalEnd = 1.0F;
        private float cylindricalStart;
        private float cylindricalEnd = 1.0F;

        private Builder() {
        }

        private Builder(DistanceFogState source) {
            red = source.red;
            green = source.green;
            blue = source.blue;
            opacity = source.opacity;
            sphericalStart = source.sphericalStart;
            sphericalEnd = source.sphericalEnd;
            cylindricalStart = source.cylindricalStart;
            cylindricalEnd = source.cylindricalEnd;
        }

        /**
         * Selects the linear fog color.
         *
         * @param redValue   finite red component in {@code [0, 1]}
         * @param greenValue finite green component in {@code [0, 1]}
         * @param blueValue  finite blue component in {@code [0, 1]}
         * @return this builder
         */
        public Builder color(float redValue, float greenValue, float blueValue) {
            red = redValue;
            green = greenValue;
            blue = blueValue;
            return this;
        }

        /**
         * Selects fog opacity.
         *
         * @param value finite opacity in {@code [0, 1]}
         * @return this builder
         */
        public Builder opacity(float value) {
            opacity = value;
            return this;
        }

        /**
         * Selects the spherical-distance fade interval.
         *
         * @param start finite start distance in world units
         * @param end   finite end distance in world units
         * @return this builder
         */
        public Builder sphericalRange(float start, float end) {
            sphericalStart = start;
            sphericalEnd = end;
            return this;
        }

        /**
         * Selects the cylindrical-distance fade interval.
         *
         * @param start finite start distance in world units
         * @param end   finite end distance in world units
         * @return this builder
         */
        public Builder cylindricalRange(float start, float end) {
            cylindricalStart = start;
            cylindricalEnd = end;
            return this;
        }

        /**
         * Validates and returns immutable distance-fog parameters.
         *
         * @return immutable validated fog state
         * @throws IllegalArgumentException if any component violates its documented range
         */
        public DistanceFogState build() {
            return new DistanceFogState(
                    red, green, blue, opacity,
                    sphericalStart, sphericalEnd, cylindricalStart, cylindricalEnd
            );
        }
    }
}
