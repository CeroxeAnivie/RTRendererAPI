package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Native DLSS Super Resolution or DLAA controls. Requested output dimensions remain unchanged. */
public final class FrameReconstructionOptions {
    private static final FrameReconstructionOptions DISABLED = new Builder().build();
    private static final FrameReconstructionOptions RECOMMENDED = builder().preference(RendererFeaturePreference.PREFERRED).build();
    private final RendererFeaturePreference preference;
    private final Mode mode;
    private final Quality quality;

    private FrameReconstructionOptions(Builder builder) {
        preference = Objects.requireNonNull(builder.preference, "preference");
        mode = Objects.requireNonNull(builder.mode, "mode");
        quality = Objects.requireNonNull(builder.quality, "quality");
    }

    /** Returns the policy with optional processing disabled. */
    public static FrameReconstructionOptions disabled() { return DISABLED; }
    /** Returns the recommended executable native policy. */
    public static FrameReconstructionOptions recommended() { return RECOMMENDED; }
    /** Starts an explicit builder with processing disabled. */
    public static Builder builder() { return new Builder(); }
    /** Copies every implemented control. */
    public Builder toBuilder() { return new Builder(this); }

    /** Returns or selects the reconstruction preference. */
    public RendererFeaturePreference preference() { return preference; }
    /** Returns or selects the native reconstruction family. */
    public Mode mode() { return mode; }
    /** Returns or selects the native DLSS input ratio. */
    public Quality quality() { return quality; }

    @Override public boolean equals(Object other) {
        return this == other || other instanceof FrameReconstructionOptions value
                && preference == value.preference && mode == value.mode && quality == value.quality;
    }
    @Override public int hashCode() { return Objects.hash(preference, mode, quality); }
    @Override public String toString() { return "FrameReconstructionOptions[" + "preference=" + preference + ", " + "mode=" + mode + ", " + "quality=" + quality + ']'; }

    /** Implemented temporal reconstruction families. */
    public enum Mode { SUPER_RESOLUTION, NATIVE_ANTI_ALIASING }
    /** Native DLSS quality selections. AUTO maps to BALANCED. */
    public enum Quality { AUTO, ULTRA_QUALITY, QUALITY, BALANCED, PERFORMANCE, ULTRA_PERFORMANCE }

    /** Thread-confined builder for the implemented native controls. */
    public static final class Builder {
        private RendererFeaturePreference preference = RendererFeaturePreference.DISABLED;
        private Mode mode = Mode.SUPER_RESOLUTION;
        private Quality quality = Quality.AUTO;
        private Builder() { }
        private Builder(FrameReconstructionOptions source) {
            preference = source.preference;
            mode = source.mode;
            quality = source.quality;
        }
        /** Returns or selects the reconstruction preference. */
        public Builder preference(RendererFeaturePreference value) {
            preference = Objects.requireNonNull(value, "preference"); return this;
        }
        /** Returns or selects the native reconstruction family. */
        public Builder mode(Mode value) {
            mode = Objects.requireNonNull(value, "mode"); return this;
        }
        /** Returns or selects the native DLSS input ratio. */
        public Builder quality(Quality value) {
            quality = Objects.requireNonNull(value, "quality"); return this;
        }
        /** Validates and returns the immutable native policy. */
        public FrameReconstructionOptions build() { return new FrameReconstructionOptions(this); }
    }
}
