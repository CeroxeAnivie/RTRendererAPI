package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Immutable effective policy for renderer features that may participate in a runtime transition.
 *
 * <p>The profile deliberately reuses the same vendor-neutral option values used at renderer
 * creation. A controller never guesses omitted feature intent, and a provider may apply only a
 * target whose resources were reserved by the renderer-lifetime configuration.</p>
 *
 * @param frameReconstruction reconstruction policy
 * @param frameGeneration frame-generation policy
 * @param lowLatency low-latency policy
 * @param denoising denoising policy
 * @param rayTracingOptimizations ray-tracing optimization policy
 */
public record RendererFeatureProfile(
        FrameReconstructionOptions frameReconstruction,
        FrameGenerationOptions frameGeneration,
        LowLatencyOptions lowLatency,
        DenoisingOptions denoising,
        RayTracingOptimizationOptions rayTracingOptimizations
) {
    /** Validates and snapshots one complete feature profile. */
    public RendererFeatureProfile {
        frameReconstruction = Objects.requireNonNull(frameReconstruction, "frameReconstruction");
        frameGeneration = Objects.requireNonNull(frameGeneration, "frameGeneration");
        lowLatency = Objects.requireNonNull(lowLatency, "lowLatency");
        denoising = Objects.requireNonNull(denoising, "denoising");
        rayTracingOptimizations = Objects.requireNonNull(
                rayTracingOptimizations, "rayTracingOptimizations"
        );
    }

    /**
     * Returns a profile with every optional feature explicitly disabled.
     *
     * @return disabled feature profile
     */
    public static RendererFeatureProfile disabled() {
        return new RendererFeatureProfile(
                FrameReconstructionOptions.disabled(),
                FrameGenerationOptions.disabled(),
                LowLatencyOptions.disabled(),
                DenoisingOptions.disabled(),
                RayTracingOptimizationOptions.disabled()
        );
    }

    /**
     * Starts an explicit builder with every feature disabled.
     *
     * @return mutable profile builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Starts a builder initialized from this complete profile.
     *
     * @return mutable copy builder
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /** Single-thread-confined builder for one complete runtime profile. */
    public static final class Builder {
        private FrameReconstructionOptions frameReconstruction =
                FrameReconstructionOptions.disabled();
        private FrameGenerationOptions frameGeneration = FrameGenerationOptions.disabled();
        private LowLatencyOptions lowLatency = LowLatencyOptions.disabled();
        private DenoisingOptions denoising = DenoisingOptions.disabled();
        private RayTracingOptimizationOptions rayTracingOptimizations =
                RayTracingOptimizationOptions.disabled();

        private Builder() {
        }

        private Builder(RendererFeatureProfile source) {
            frameReconstruction = source.frameReconstruction;
            frameGeneration = source.frameGeneration;
            lowLatency = source.lowLatency;
            denoising = source.denoising;
            rayTracingOptimizations = source.rayTracingOptimizations;
        }

        /**
         * Selects temporal/spatial reconstruction intent.
         *
         * @param value reconstruction options
         * @return this builder
         */
        public Builder frameReconstruction(FrameReconstructionOptions value) {
            frameReconstruction = Objects.requireNonNull(value, "frameReconstruction");
            return this;
        }

        /**
         * Selects the single generated-frame family and cadence.
         *
         * @param value frame-generation options
         * @return this builder
         */
        public Builder frameGeneration(FrameGenerationOptions value) {
            frameGeneration = Objects.requireNonNull(value, "frameGeneration");
            return this;
        }

        /**
         * Selects independent low-latency pacing and marker intent.
         *
         * @param value low-latency options
         * @return this builder
         */
        public Builder lowLatency(LowLatencyOptions value) {
            lowLatency = Objects.requireNonNull(value, "lowLatency");
            return this;
        }

        /**
         * Selects ray-tracing denoising intent.
         *
         * @param value denoising options
         * @return this builder
         */
        public Builder denoising(DenoisingOptions value) {
            denoising = Objects.requireNonNull(value, "denoising");
            return this;
        }

        /**
         * Selects device/pipeline and acceleration-structure optimization intent.
         *
         * @param value optimization options
         * @return this builder
         */
        public Builder rayTracingOptimizations(RayTracingOptimizationOptions value) {
            rayTracingOptimizations = Objects.requireNonNull(value, "rayTracingOptimizations");
            return this;
        }

        /**
         * Creates the immutable complete profile.
         *
         * @return immutable feature profile
         */
        public RendererFeatureProfile build() {
            return new RendererFeatureProfile(
                    frameReconstruction,
                    frameGeneration,
                    lowLatency,
                    denoising,
                    rayTracingOptimizations
            );
        }
    }
}
