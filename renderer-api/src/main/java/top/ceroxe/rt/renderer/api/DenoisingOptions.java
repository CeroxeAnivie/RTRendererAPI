package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Immutable intent for an optional ray-tracing denoiser.
 *
 * <p>Backends translate the semantic strategy to their implementation, for example an NRD REBLUR
 * or RELAX pipeline. The API intentionally does not expose SDK resource handles or tuning
 * constants; those remain owned by the backend feature session.</p>
 */
public final class DenoisingOptions {
    private static final DenoisingOptions DISABLED = new Builder().build();
    private static final DenoisingOptions PRODUCTION_DEFAULT = new Builder()
            .preference(RendererFeaturePreference.PREFERRED)
            .builtInTemporalFallback(true)
            .build();

    private final RendererFeaturePreference preference;
    private final Strategy strategy;
    private final boolean builtInTemporalFallback;

    private DenoisingOptions(Builder builder) {
        preference = Objects.requireNonNull(builder.preference, "preference");
        strategy = Objects.requireNonNull(builder.strategy, "strategy");
        builtInTemporalFallback = builder.builtInTemporalFallback;
        if (preference == RendererFeaturePreference.DISABLED && builtInTemporalFallback) {
            throw new IllegalArgumentException("disabled denoising cannot request a fallback");
        }
        if (preference == RendererFeaturePreference.REQUIRED && builtInTemporalFallback) {
            throw new IllegalArgumentException("required denoising cannot request a fallback");
        }
    }

    /**
     * Returns the canonical policy that allocates no denoiser resources.
     *
     * @return disabled immutable policy
     */
    public static DenoisingOptions disabled() {
        return DISABLED;
    }

    /**
     * Returns the production policy: use an executable native denoiser when negotiation succeeds
     * and otherwise retain the renderer's temporal path without allocating denoiser resources.
     *
     * @return preferred denoising policy with a deterministic built-in fallback
     */
    public static DenoisingOptions recommended() {
        return PRODUCTION_DEFAULT;
    }

    /**
     * Starts a builder with disabled defaults.
     *
     * @return new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Starts an independent builder containing this policy.
     *
     * @return copied builder
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
     * Returns the denoiser quality/stability strategy.
     *
     * @return denoiser quality/stability strategy
     */
    public Strategy strategy() {
        return strategy;
    }

    /**
     * Reports whether the built-in temporal path may replace a preferred denoiser.
     * The enclosing renderer configuration must keep temporal rendering enabled when this is
     * {@code true}.
     *
     * @return whether built-in temporal fallback is permitted
     */
    public boolean builtInTemporalFallback() {
        return builtInTemporalFallback;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof DenoisingOptions options
                && preference == options.preference
                && strategy == options.strategy
                && builtInTemporalFallback == options.builtInTemporalFallback;
    }

    @Override
    public int hashCode() {
        return Objects.hash(preference, strategy, builtInTemporalFallback);
    }

    @Override
    public String toString() {
        return "DenoisingOptions[preference=" + preference + ", strategy=" + strategy
                + ", builtInTemporalFallback=" + builtInTemporalFallback + ']';
    }

    /** Quality intent that a backend maps to a supported denoiser pipeline. */
    public enum Strategy {
        /** Balance temporal stability, responsiveness, and cost. */
        BALANCED,
        /** Prefer stable low-frequency reconstruction. */
        STABLE,
        /** Prefer rapid response to lighting and geometry changes. */
        RESPONSIVE
    }

    /** Single-thread-confined builder for one immutable denoising policy. */
    public static final class Builder {
        private RendererFeaturePreference preference = RendererFeaturePreference.DISABLED;
        private Strategy strategy = Strategy.BALANCED;
        private boolean builtInTemporalFallback;

        private Builder() {
        }

        private Builder(DenoisingOptions source) {
            preference = source.preference;
            strategy = source.strategy;
            builtInTemporalFallback = source.builtInTemporalFallback;
        }

        /**
         * Selects whether denoising is disabled, preferred, or required.
         *
         * @param value non-null preference
         * @return this builder
         */
        public Builder preference(RendererFeaturePreference value) {
            preference = Objects.requireNonNull(value, "preference");
            return this;
        }

        /**
         * Selects the backend-independent denoising strategy.
         *
         * @param value non-null strategy
         * @return this builder
         */
        public Builder strategy(Strategy value) {
            strategy = Objects.requireNonNull(value, "strategy");
            return this;
        }

        /**
         * Permits the built-in temporal renderer when a preferred denoiser is unavailable. The
         * enclosing renderer configuration must keep temporal rendering enabled when selected.
         *
         * @param value whether fallback is permitted
         * @return this builder
         */
        public Builder builtInTemporalFallback(boolean value) {
            builtInTemporalFallback = value;
            return this;
        }

        /**
         * Validates and creates the immutable policy.
         *
         * @return validated policy
         */
        public DenoisingOptions build() {
            return new DenoisingOptions(this);
        }
    }
}
