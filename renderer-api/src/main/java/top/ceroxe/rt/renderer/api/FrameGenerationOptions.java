package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Immutable intent for presentation-time generated frames.
 *
 * <p>Frame generation is deliberately independent from {@link FrameReconstructionOptions}.
 * Reconstruction creates the one rendered output for a scene submission; generation may insert
 * one or more additional presentation frames between those outputs. A backend must therefore not
 * report this feature active until it owns the presentation cadence and every generated-frame
 * resource. When unavailable, {@link Fallback#PRESENT_NATIVE_FRAMES} preserves the existing
 * one-submission/one-presentation behavior.</p>
 */
public final class FrameGenerationOptions {
    private static final FrameGenerationOptions DISABLED = new Builder().build();
    private static final FrameGenerationOptions PRODUCTION_DEFAULT = builder()
            .preference(RendererFeaturePreference.PREFERRED)
            .mode(Mode.FRAME_GENERATION)
            .multiplier(Multiplier.TWO_X)
            .fallback(Fallback.PRESENT_NATIVE_FRAMES)
            .build();

    private final RendererFeaturePreference preference;
    private final Mode mode;
    private final Multiplier multiplier;
    private final Fallback fallback;

    private FrameGenerationOptions(Builder builder) {
        preference = Objects.requireNonNull(builder.preference, "preference");
        mode = Objects.requireNonNull(builder.mode, "mode");
        multiplier = Objects.requireNonNull(builder.multiplier, "multiplier");
        fallback = Objects.requireNonNull(builder.fallback, "fallback");
        if (preference == RendererFeaturePreference.DISABLED) {
            if (mode != Mode.DISABLED || fallback != Fallback.NONE) {
                throw new IllegalArgumentException("disabled frame generation cannot select a mode or fallback");
            }
        } else if (mode == Mode.DISABLED) {
            throw new IllegalArgumentException("requested frame generation requires an enabled mode");
        }
        if (mode == Mode.FRAME_GENERATION && multiplier != Multiplier.TWO_X) {
            throw new IllegalArgumentException("frame generation supports only the two-times presentation cadence");
        }
        if (mode == Mode.MULTI_FRAME_GENERATION && multiplier == Multiplier.TWO_X) {
            throw new IllegalArgumentException("multi-frame generation requires a three-times or four-times cadence");
        }
        if (preference == RendererFeaturePreference.REQUIRED && fallback != Fallback.NONE) {
            throw new IllegalArgumentException("required frame generation cannot request a fallback");
        }
    }

    /**
     * Returns the canonical policy that never evaluates or presents generated frames.
     *
     * @return disabled immutable policy
     */
    public static FrameGenerationOptions disabled() {
        return DISABLED;
    }

    /**
     * Returns the capability-driven production policy for ordinary 2x frame generation.
     *
     * <p>This preset deliberately never selects multi-frame generation. MFG changes cadence and
     * pacing more aggressively and remains an expert opt-in through {@link #builder()} with
     * {@link Mode#MULTI_FRAME_GENERATION} or {@link Mode#ADAPTIVE}. Unsupported hardware safely
     * retains native presentation.</p>
     *
     * @return preferred immutable production policy
     */
    public static FrameGenerationOptions recommended() {
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
     * Returns how strictly the renderer must satisfy this frame-generation request.
     *
     * @return feature availability preference
     */
    public RendererFeaturePreference preference() {
        return preference;
    }

    /**
     * Returns the requested generated-frame family.
     *
     * @return requested generated-frame family
     */
    public Mode mode() {
        return mode;
    }

    /**
     * Returns the requested ratio between renderer-produced and displayed frames.
     *
     * @return requested native-to-presented output multiplier
     */
    public Multiplier multiplier() {
        return multiplier;
    }

    /**
     * Returns the explicit behavior used when the requested generation family is unavailable.
     *
     * @return unavailable-feature behavior
     */
    public Fallback fallback() {
        return fallback;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof FrameGenerationOptions options
                && preference == options.preference
                && mode == options.mode
                && multiplier == options.multiplier
                && fallback == options.fallback;
    }

    @Override
    public int hashCode() {
        return Objects.hash(preference, mode, multiplier, fallback);
    }

    @Override
    public String toString() {
        return "FrameGenerationOptions[preference=" + preference
                + ", mode=" + mode + ", multiplier=" + multiplier + ", fallback=" + fallback + ']';
    }

    /** Stable presentation-time generation families. */
    public enum Mode {
        /** Do not insert generated presentation frames. */
        DISABLED,
        /** Insert one generated frame between consecutive renderer-produced frames. */
        FRAME_GENERATION,
        /** Insert two or three generated frames between renderer-produced frames. */
        MULTI_FRAME_GENERATION,
        /** Select the highest SDK-reported cadence up to {@link #multiplier() the configured limit}. */
        ADAPTIVE
    }

    /** Requested ratio of displayed frames to renderer-produced frames. */
    public enum Multiplier {
        /** One generated frame for each renderer-produced frame interval. */
        TWO_X(2),
        /** Two generated frames for each renderer-produced frame interval. */
        THREE_X(3),
        /** Three generated frames for each renderer-produced frame interval. */
        FOUR_X(4);

        private final int presentedFramesPerNativeFrame;

        Multiplier(int presentedFramesPerNativeFrame) {
            this.presentedFramesPerNativeFrame = presentedFramesPerNativeFrame;
        }

        /**
         * Returns the total displayed-frame target for each renderer-produced frame interval.
         *
         * @return total displayed frames per renderer-produced frame
         */
        public int presentedFramesPerNativeFrame() {
            return presentedFramesPerNativeFrame;
        }
    }

    /** Explicit behavior when generated-frame presentation cannot be activated. */
    public enum Fallback {
        /** Reject preferred activation as unavailable; no substitute behavior is selected. */
        NONE,
        /** Continue presenting only renderer-produced frames without fabricating generated output. */
        PRESENT_NATIVE_FRAMES
    }

    /** Single-thread-confined builder for one immutable generated-frame policy. */
    public static final class Builder {
        private RendererFeaturePreference preference = RendererFeaturePreference.DISABLED;
        private Mode mode = Mode.DISABLED;
        private Multiplier multiplier = Multiplier.TWO_X;
        private Fallback fallback = Fallback.NONE;

        private Builder() {
        }

        private Builder(FrameGenerationOptions source) {
            preference = source.preference;
            mode = source.mode;
            multiplier = source.multiplier;
            fallback = source.fallback;
        }

        /**
         * Sets how strictly the renderer must satisfy the request.
         *
         * @param value non-null preference
         * @return this builder
         */
        public Builder preference(RendererFeaturePreference value) {
            preference = Objects.requireNonNull(value, "preference");
            return this;
        }

        /**
         * Selects the generated-frame family.
         *
         * @param value non-null frame-generation family
         * @return this builder
         */
        public Builder mode(Mode value) {
            mode = Objects.requireNonNull(value, "mode");
            return this;
        }

        /**
         * Selects the ratio between renderer-produced and displayed frames.
         *
         * @param value non-null presentation multiplier
         * @return this builder
         */
        public Builder multiplier(Multiplier value) {
            multiplier = Objects.requireNonNull(value, "multiplier");
            return this;
        }

        /**
         * Selects the behavior used when frame generation cannot be activated.
         *
         * @param value non-null fallback behavior
         * @return this builder
         */
        public Builder fallback(Fallback value) {
            fallback = Objects.requireNonNull(value, "fallback");
            return this;
        }

        /**
         * Validates the complete policy and creates its immutable value.
         *
         * @return validated immutable policy
         */
        public FrameGenerationOptions build() {
            return new FrameGenerationOptions(this);
        }
    }
}
