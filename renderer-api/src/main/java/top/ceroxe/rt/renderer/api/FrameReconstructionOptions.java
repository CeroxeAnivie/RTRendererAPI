package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Immutable intent for renderer-lifetime frame reconstruction and native-resolution AA.
 *
 * <p>The contract is vendor-neutral: a backend may satisfy it with a temporal implementation such
 * as DLSS Super Resolution or DLAA, while {@link Fallback#SPATIAL} permits a spatial scaler such as
 * NIS. Requested output dimensions remain those supplied by {@link RenderFrameRequest}; the
 * backend owns any internal render extent and must report the negotiated implementation through
 * {@link RenderingFeatureCapabilities}.</p>
 */
public final class FrameReconstructionOptions {
    private static final FrameReconstructionOptions DISABLED = new Builder().build();
    private static final FrameReconstructionOptions PRODUCTION_DEFAULT = builder()
            .preference(RendererFeaturePreference.PREFERRED)
            .mode(Mode.SUPER_RESOLUTION)
            .quality(Quality.AUTO)
            .fallback(Fallback.SPATIAL)
            .build();

    private final RendererFeaturePreference preference;
    private final Mode mode;
    private final Quality quality;
    private final Fallback fallback;

    private FrameReconstructionOptions(Builder builder) {
        preference = Objects.requireNonNull(builder.preference, "preference");
        mode = Objects.requireNonNull(builder.mode, "mode");
        quality = Objects.requireNonNull(builder.quality, "quality");
        fallback = Objects.requireNonNull(builder.fallback, "fallback");
        if (preference == RendererFeaturePreference.DISABLED && fallback != Fallback.NONE) {
            throw new IllegalArgumentException("disabled frame reconstruction cannot request a fallback");
        }
        if (preference == RendererFeaturePreference.REQUIRED && fallback != Fallback.NONE) {
            throw new IllegalArgumentException("required frame reconstruction cannot request a fallback");
        }
        if (mode == Mode.NATIVE_ANTI_ALIASING && fallback == Fallback.SPATIAL) {
            throw new IllegalArgumentException("native anti-aliasing cannot use a spatial upscale fallback");
        }
        if (mode == Mode.SPATIAL_UPSCALING && fallback == Fallback.SPATIAL) {
            throw new IllegalArgumentException("spatial upscaling cannot fall back to itself");
        }
    }

    /**
     * Returns the canonical policy that performs no reconstruction-specific work.
     *
     * @return disabled immutable policy
     */
    public static FrameReconstructionOptions disabled() {
        return DISABLED;
    }

    /**
     * Returns the production policy that prefers hardware temporal reconstruction and permits a
     * capability-driven spatial fallback such as NIS. Device negotiation still decides whether
     * DLSS, DLAA, NIS, or the built-in path is executable.
     *
     * @return preferred adaptive reconstruction policy
     */
    public static FrameReconstructionOptions recommended() {
        return PRODUCTION_DEFAULT;
    }

    /**
     * Starts an independent configuration builder with disabled defaults.
     *
     * @return new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Starts an independent builder containing this complete policy.
     *
     * @return new builder containing every policy value
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * Returns the feature availability preference.
     *
     * @return feature availability preference
     */
    public RendererFeaturePreference preference() {
        return preference;
    }

    /**
     * Returns the reconstruction mode.
     *
     * @return reconstruction mode
     */
    public Mode mode() {
        return mode;
    }

    /**
     * Returns the quality/performance intent.
     *
     * @return quality/performance intent
     */
    public Quality quality() {
        return quality;
    }

    /**
     * Returns the explicit fallback policy.
     *
     * @return explicit fallback policy
     */
    public Fallback fallback() {
        return fallback;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof FrameReconstructionOptions options
                && preference == options.preference
                && mode == options.mode
                && quality == options.quality
                && fallback == options.fallback;
    }

    @Override
    public int hashCode() {
        return Objects.hash(preference, mode, quality, fallback);
    }

    @Override
    public String toString() {
        return "FrameReconstructionOptions[preference=" + preference
                + ", mode=" + mode + ", quality=" + quality + ", fallback=" + fallback + ']';
    }

    /** Stable reconstruction intent independent of a vendor algorithm. */
    public enum Mode {
        /** Render internally at an implementation-selected extent and reconstruct the requested output. */
        SUPER_RESOLUTION,
        /** Preserve the requested native extent and use reconstruction only for anti-aliasing. */
        NATIVE_ANTI_ALIASING,
        /** Render below the requested extent and use a non-temporal spatial scaler such as NIS. */
        SPATIAL_UPSCALING
    }

    /** Backend-independent quality/performance intent. */
    public enum Quality {
        /** Let the backend select a stable production quality. */
        AUTO,
        /** Prefer the highest supported reconstruction input ratio. */
        ULTRA_QUALITY,
        /** Prefer image quality over throughput. */
        QUALITY,
        /** Balance image quality and throughput. */
        BALANCED,
        /** Prefer throughput over image quality. */
        PERFORMANCE,
        /** Prefer the lowest supported reconstruction input ratio. */
        ULTRA_PERFORMANCE
    }

    /** Explicit behavior when the preferred temporal implementation is unavailable. */
    public enum Fallback {
        /** Do not substitute another reconstruction family. */
        NONE,
        /** Permit a spatial scaler when temporal reconstruction is unavailable. */
        SPATIAL,
        /**
         * Permit the renderer's built-in temporal accumulation path.
         *
         * <p>The enclosing {@link RendererConfig} must keep
         * {@link TemporalRenderingOptions} enabled so this fallback is executable.</p>
         */
        BUILT_IN_TEMPORAL
    }

    /** Single-thread-confined builder for one immutable reconstruction policy. */
    public static final class Builder {
        private RendererFeaturePreference preference = RendererFeaturePreference.DISABLED;
        private Mode mode = Mode.SUPER_RESOLUTION;
        private Quality quality = Quality.AUTO;
        private Fallback fallback = Fallback.NONE;

        private Builder() {
        }

        private Builder(FrameReconstructionOptions source) {
            preference = source.preference;
            mode = source.mode;
            quality = source.quality;
            fallback = source.fallback;
        }

        /**
         * Selects whether reconstruction is disabled, preferred, or required.
         *
         * @param value non-null preference
         * @return this builder
         */
        public Builder preference(RendererFeaturePreference value) {
            preference = Objects.requireNonNull(value, "preference");
            return this;
        }

        /**
         * Selects temporal super resolution, native-resolution AA, or explicit spatial upscaling.
         *
         * @param value non-null mode
         * @return this builder
         */
        public Builder mode(Mode value) {
            mode = Objects.requireNonNull(value, "mode");
            return this;
        }

        /**
         * Selects a backend-independent quality/performance intent.
         *
         * @param value non-null quality
         * @return this builder
         */
        public Builder quality(Quality value) {
            quality = Objects.requireNonNull(value, "quality");
            return this;
        }

        /**
         * Selects the permitted fallback when preferred reconstruction is unavailable.
         * {@link Fallback#BUILT_IN_TEMPORAL} requires enabled temporal rendering in the enclosing
         * renderer configuration.
         *
         * @param value non-null fallback
         * @return this builder
         */
        public Builder fallback(Fallback value) {
            fallback = Objects.requireNonNull(value, "fallback");
            return this;
        }

        /**
         * Validates and creates the immutable policy.
         *
         * @return validated policy
         */
        public FrameReconstructionOptions build() {
            return new FrameReconstructionOptions(this);
        }
    }
}
