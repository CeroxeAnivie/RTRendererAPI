package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Immutable renderer-lifetime intent for latency pacing and frame markers.
 *
 * <p>This policy is deliberately independent from {@link FrameGenerationOptions}. Low-latency
 * pacing is useful for native presentation and reconstructed frames as well as generated frames;
 * a frame-generation implementation may still require the same provider internally even when
 * this policy is disabled explicitly.</p>
 */
public final class LowLatencyOptions {
    private static final LowLatencyOptions DISABLED = new Builder().build();
    private static final LowLatencyOptions PRODUCTION_DEFAULT = builder()
            .preference(RendererFeaturePreference.PREFERRED)
            .build();

    private final RendererFeaturePreference preference;

    private LowLatencyOptions(Builder builder) {
        preference = Objects.requireNonNull(builder.preference, "preference");
    }

    /**
     * Returns the canonical policy that performs no latency-provider work.
     *
     * @return disabled immutable policy
     */
    public static LowLatencyOptions disabled() {
        return DISABLED;
    }

    /**
     * Returns the non-terminal production policy: use a supported low-latency provider and keep
     * native presentation unchanged when none is executable.
     *
     * @return preferred immutable production policy
     */
    public static LowLatencyOptions recommended() {
        return PRODUCTION_DEFAULT;
    }

    /**
     * Starts an independent builder with disabled defaults.
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
     * Returns how strictly the renderer must satisfy the low-latency request.
     *
     * @return feature availability preference
     */
    public RendererFeaturePreference preference() {
        return preference;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof LowLatencyOptions options
                && preference == options.preference;
    }

    @Override
    public int hashCode() {
        return preference.hashCode();
    }

    @Override
    public String toString() {
        return "LowLatencyOptions[preference=" + preference + ']';
    }

    /** Single-thread-confined builder for one immutable latency policy. */
    public static final class Builder {
        private RendererFeaturePreference preference = RendererFeaturePreference.DISABLED;

        private Builder() {
        }

        private Builder(LowLatencyOptions source) {
            preference = source.preference;
        }

        /**
         * Selects whether low-latency pacing is disabled, preferred, or required.
         *
         * @param value non-null preference
         * @return this builder
         */
        public Builder preference(RendererFeaturePreference value) {
            preference = Objects.requireNonNull(value, "preference");
            return this;
        }

        /**
         * Validates and creates the immutable policy.
         *
         * @return validated immutable policy
         */
        public LowLatencyOptions build() {
            return new LowLatencyOptions(this);
        }
    }
}
