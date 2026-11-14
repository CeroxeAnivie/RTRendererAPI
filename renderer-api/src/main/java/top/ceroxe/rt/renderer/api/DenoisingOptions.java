package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Native NRD / DLSS Ray Reconstruction policy. No independent fallback algorithm is advertised. */
public final class DenoisingOptions {
    private static final DenoisingOptions DISABLED = new Builder().build();
    private static final DenoisingOptions RECOMMENDED = builder().preference(RendererFeaturePreference.PREFERRED).build();
    private final RendererFeaturePreference preference;
    private final Strategy strategy;

    private DenoisingOptions(Builder builder) {
        preference = Objects.requireNonNull(builder.preference, "preference");
        strategy = Objects.requireNonNull(builder.strategy, "strategy");
    }

    /** Returns the policy with optional processing disabled. */
    public static DenoisingOptions disabled() { return DISABLED; }
    /** Returns the recommended executable native policy. */
    public static DenoisingOptions recommended() { return RECOMMENDED; }
    /** Starts an explicit builder with processing disabled. */
    public static Builder builder() { return new Builder(); }
    /** Copies every implemented control. */
    public Builder toBuilder() { return new Builder(this); }

    /** Returns or selects the native denoising preference. */
    public RendererFeaturePreference preference() { return preference; }
    /** Returns or selects the mapped native denoiser strategy. */
    public Strategy strategy() { return strategy; }

    @Override public boolean equals(Object other) {
        return this == other || other instanceof DenoisingOptions value
                && preference == value.preference && strategy == value.strategy;
    }
    @Override public int hashCode() { return Objects.hash(preference, strategy); }
    @Override public String toString() { return "DenoisingOptions[" + "preference=" + preference + ", " + "strategy=" + strategy + ']'; }

    /** BALANCED/STABLE select native RR where applicable; RESPONSIVE selects RELAX. */
    public enum Strategy { BALANCED, STABLE, RESPONSIVE }

    /** Thread-confined builder for the implemented native controls. */
    public static final class Builder {
        private RendererFeaturePreference preference = RendererFeaturePreference.DISABLED;
        private Strategy strategy = Strategy.BALANCED;
        private Builder() { }
        private Builder(DenoisingOptions source) {
            preference = source.preference;
            strategy = source.strategy;
        }
        /** Returns or selects the native denoising preference. */
        public Builder preference(RendererFeaturePreference value) {
            preference = Objects.requireNonNull(value, "preference"); return this;
        }
        /** Returns or selects the mapped native denoiser strategy. */
        public Builder strategy(Strategy value) {
            strategy = Objects.requireNonNull(value, "strategy"); return this;
        }
        /** Validates and returns the immutable native policy. */
        public DenoisingOptions build() { return new DenoisingOptions(this); }
    }
}
