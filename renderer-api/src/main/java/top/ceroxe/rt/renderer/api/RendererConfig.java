package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Immutable configuration for the renderer's implemented native controls. */
public final class RendererConfig {
    private final FrameReconstructionOptions frameReconstruction;
    private final FrameGenerationOptions frameGeneration;
    private final LowLatencyOptions lowLatency;
    private final DenoisingOptions denoising;
    private final RayTracingOptimizationOptions rayTracingOptimizations;
    private final PathTracingOptions pathTracing;
    private final RendererResourceOptions resources;

    private RendererConfig(Builder builder) {
        frameReconstruction = Objects.requireNonNull(builder.frameReconstruction, "frameReconstruction");
        frameGeneration = Objects.requireNonNull(builder.frameGeneration, "frameGeneration");
        lowLatency = Objects.requireNonNull(builder.lowLatency, "lowLatency");
        denoising = Objects.requireNonNull(builder.denoising, "denoising");
        rayTracingOptimizations = Objects.requireNonNull(builder.rayTracingOptimizations, "rayTracingOptimizations");
        pathTracing = Objects.requireNonNull(builder.pathTracing, "pathTracing");
        resources = Objects.requireNonNull(builder.resources, "resources");
    }

    /** Recommended native GPU presentation; optional features remain capability-gated. */
    public static RendererConfig recommended() {
        return recommended(RendererPerformanceTier.MEDIUM);
    }

    /** Recommended native presentation with optional generated frames and pacing. */
    public static RendererConfig presentation() {
        return presentation(RendererPerformanceTier.MEDIUM);
    }

    /** Recommended path-traced native presentation with frame generation disabled. */
    public static RendererConfig pathTracedPresentation() {
        return pathTracedPresentation(RendererPerformanceTier.MEDIUM);
    }

    /** Recommended native controls and resource admission limits for the selected tier. */
    public static RendererConfig recommended(RendererPerformanceTier tier) {
        Objects.requireNonNull(tier, "tier");
        return builder().resources(tier.resources()).pathTracing(tier.pathTracing(false))
                .frameReconstruction(FrameReconstructionOptions.recommended().toBuilder()
                        .quality(tier.reconstructionQuality()).build())
                .rayTracingOptimizations(RayTracingOptimizationOptions.recommended()).build();
    }

    /** Recommended native presentation with optional generated frames and pacing. */
    public static RendererConfig presentation(RendererPerformanceTier tier) {
        return recommended(tier).copyBuilder().frameGeneration(FrameGenerationOptions.recommended())
                .lowLatency(LowLatencyOptions.recommended()).build();
    }

    /** Recommended path-traced native presentation with frame generation disabled. */
    public static RendererConfig pathTracedPresentation(RendererPerformanceTier tier) {
        return recommended(tier).copyBuilder().denoising(DenoisingOptions.recommended())
                .lowLatency(LowLatencyOptions.recommended()).pathTracing(tier.pathTracing(true)).build();
    }

    /** Creates a builder with optional vendor features disabled. */
    public static Builder builder() { return new Builder(); }

    /** Starts an independent builder initialized from every current property. */
    public Builder copyBuilder() { return new Builder(this); }

    /** Returns optional native frame reconstruction controls. */
    public FrameReconstructionOptions frameReconstruction() { return frameReconstruction; }
    /** Returns optional native generated-frame controls. */
    public FrameGenerationOptions frameGeneration() { return frameGeneration; }
    /** Returns optional native pacing and frame-marker controls. */
    public LowLatencyOptions lowLatency() { return lowLatency; }
    /** Returns optional native denoising controls. */
    public DenoisingOptions denoising() { return denoising; }
    /** Returns optional native ray-tracing optimization controls. */
    public RayTracingOptimizationOptions rayTracingOptimizations() { return rayTracingOptimizations; }
    /** Returns native path-tracing controls. */
    public PathTracingOptions pathTracing() { return pathTracing; }
    /** Returns the retained scene's asset admission limits. */
    public RendererResourceOptions resources() { return resources; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RendererConfig config)) return false;
        return frameReconstruction.equals(config.frameReconstruction)
                && frameGeneration.equals(config.frameGeneration)
                && lowLatency.equals(config.lowLatency)
                && denoising.equals(config.denoising)
                && rayTracingOptimizations.equals(config.rayTracingOptimizations)
                && pathTracing.equals(config.pathTracing) && resources.equals(config.resources);
    }

    @Override
    public int hashCode() {
        return Objects.hash(frameReconstruction, frameGeneration, lowLatency, denoising,
                rayTracingOptimizations, pathTracing, resources);
    }

    @Override
    public String toString() {
        return "RendererConfig[frameReconstruction=" + frameReconstruction
                + ", frameGeneration=" + frameGeneration + ", lowLatency=" + lowLatency
                + ", denoising=" + denoising + ", rayTracingOptimizations=" + rayTracingOptimizations
                + ", pathTracing=" + pathTracing + ", resources=" + resources + ']';
    }

    /** Single-thread-confined semantic builder for implemented renderer controls. */
    public static final class Builder {
        private FrameReconstructionOptions frameReconstruction = FrameReconstructionOptions.disabled();
        private FrameGenerationOptions frameGeneration = FrameGenerationOptions.disabled();
        private LowLatencyOptions lowLatency = LowLatencyOptions.disabled();
        private DenoisingOptions denoising = DenoisingOptions.disabled();
        private RayTracingOptimizationOptions rayTracingOptimizations = RayTracingOptimizationOptions.disabled();
        private PathTracingOptions pathTracing = PathTracingOptions.defaults();
        private RendererResourceOptions resources = RendererResourceOptions.defaults();

        private Builder() { }
        private Builder(RendererConfig source) {
            frameReconstruction = source.frameReconstruction;
            frameGeneration = source.frameGeneration;
            lowLatency = source.lowLatency;
            denoising = source.denoising;
            rayTracingOptimizations = source.rayTracingOptimizations;
            pathTracing = source.pathTracing;
            resources = source.resources;
        }

        /** Selects optional native frame reconstruction. */
        public Builder frameReconstruction(FrameReconstructionOptions value) {
            frameReconstruction = Objects.requireNonNull(value, "frameReconstruction");
            return this;
        }

        /** Selects optional native presentation-time frame generation. */
        public Builder frameGeneration(FrameGenerationOptions value) {
            frameGeneration = Objects.requireNonNull(value, "frameGeneration");
            return this;
        }

        /** Selects optional native pacing independently from generated frames. */
        public Builder lowLatency(LowLatencyOptions value) {
            lowLatency = Objects.requireNonNull(value, "lowLatency");
            return this;
        }

        /** Selects optional native denoising. */
        public Builder denoising(DenoisingOptions value) {
            denoising = Objects.requireNonNull(value, "denoising");
            return this;
        }

        /** Selects optional native ray-tracing optimizations. */
        public Builder rayTracingOptimizations(RayTracingOptimizationOptions value) {
            rayTracingOptimizations = Objects.requireNonNull(value, "rayTracingOptimizations");
            return this;
        }

        /** Selects native path-tracing controls. */
        public Builder pathTracing(PathTracingOptions value) {
            pathTracing = Objects.requireNonNull(value, "pathTracing");
            return this;
        }

        /** Selects the retained scene's asset admission limits. */
        public Builder resources(RendererResourceOptions value) {
            resources = Objects.requireNonNull(value, "resources");
            return this;
        }

        /** Returns an independent immutable configuration. */
        public RendererConfig build() { return new RendererConfig(this); }
    }
}
