package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Fixed native DLSS generated-frame cadence. Active state and presented counts are queried separately. */
public final class FrameGenerationOptions {
    private static final FrameGenerationOptions DISABLED = new Builder().build();
    private static final FrameGenerationOptions RECOMMENDED = builder().preference(RendererFeaturePreference.PREFERRED).mode(Mode.FRAME_GENERATION).build();
    private final RendererFeaturePreference preference;
    private final Mode mode;
    private final Multiplier multiplier;

    private FrameGenerationOptions(Builder builder) {
        preference = Objects.requireNonNull(builder.preference, "preference");
        mode = Objects.requireNonNull(builder.mode, "mode");
        multiplier = Objects.requireNonNull(builder.multiplier, "multiplier");
        if ((preference == RendererFeaturePreference.DISABLED) != (mode == Mode.DISABLED))
            throw new IllegalArgumentException("Frame generation preference and enabled mode must agree");
        if (mode == Mode.FRAME_GENERATION && multiplier != Multiplier.TWO_X)
            throw new IllegalArgumentException("Ordinary frame generation requires TWO_X");
        if (mode == Mode.MULTI_FRAME_GENERATION && multiplier == Multiplier.TWO_X)
            throw new IllegalArgumentException("Multi-frame generation requires THREE_X or FOUR_X");
    }

    /** Returns the policy with optional processing disabled. */
    public static FrameGenerationOptions disabled() { return DISABLED; }
    /** Returns the recommended executable native policy. */
    public static FrameGenerationOptions recommended() { return RECOMMENDED; }
    /** Starts an explicit builder with processing disabled. */
    public static Builder builder() { return new Builder(); }
    /** Copies every implemented control. */
    public Builder toBuilder() { return new Builder(this); }

    /** Returns or selects the native frame-generation preference. */
    public RendererFeaturePreference preference() { return preference; }
    /** Returns or selects a fixed native generation mode. */
    public Mode mode() { return mode; }
    /** Returns or selects the requested native presentation multiplier. */
    public Multiplier multiplier() { return multiplier; }

    @Override public boolean equals(Object other) {
        return this == other || other instanceof FrameGenerationOptions value
                && preference == value.preference && mode == value.mode && multiplier == value.multiplier;
    }
    @Override public int hashCode() { return Objects.hash(preference, mode, multiplier); }
    @Override public String toString() { return "FrameGenerationOptions[" + "preference=" + preference + ", " + "mode=" + mode + ", " + "multiplier=" + multiplier + ']'; }

    /** Fixed generation families implemented by the native runtime. */
    public enum Mode { DISABLED, FRAME_GENERATION, MULTI_FRAME_GENERATION }
    /** Total requested presentation cadence per renderer-produced frame. */
    public enum Multiplier {
        TWO_X(2), THREE_X(3), FOUR_X(4);
        private final int presentedFramesPerNativeFrame;
        Multiplier(int count) { presentedFramesPerNativeFrame = count; }
        /** Returns the total requested presentation multiplier. */
        public int presentedFramesPerNativeFrame() { return presentedFramesPerNativeFrame; }
    }

    /** Thread-confined builder for the implemented native controls. */
    public static final class Builder {
        private RendererFeaturePreference preference = RendererFeaturePreference.DISABLED;
        private Mode mode = Mode.DISABLED;
        private Multiplier multiplier = Multiplier.TWO_X;
        private Builder() { }
        private Builder(FrameGenerationOptions source) {
            preference = source.preference;
            mode = source.mode;
            multiplier = source.multiplier;
        }
        /** Returns or selects the native frame-generation preference. */
        public Builder preference(RendererFeaturePreference value) {
            preference = Objects.requireNonNull(value, "preference"); return this;
        }
        /** Returns or selects a fixed native generation mode. */
        public Builder mode(Mode value) {
            mode = Objects.requireNonNull(value, "mode"); return this;
        }
        /** Returns or selects the requested native presentation multiplier. */
        public Builder multiplier(Multiplier value) {
            multiplier = Objects.requireNonNull(value, "multiplier"); return this;
        }
        /** Validates and returns the immutable native policy. */
        public FrameGenerationOptions build() { return new FrameGenerationOptions(this); }
    }
}
