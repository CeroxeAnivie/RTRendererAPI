package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Immutable renderer-lifetime policy assembled through a stable semantic builder.
 *
 * <p>The constructor is intentionally private. Adding an independent renderer policy therefore
 * does not change a public constructor descriptor or force applications to depend on argument
 * ordering.</p>
 */
public final class RayTracingRendererConfig {
    /**
     * Default bounded frame concurrency.
     */
    public static final int DEFAULT_MAX_FRAMES_IN_FLIGHT = 3;
    /** Smallest supported bounded frame ring. */
    public static final int MIN_MAX_FRAMES_IN_FLIGHT = 2;
    /** Largest supported bounded frame ring. */
    public static final int MAX_MAX_FRAMES_IN_FLIGHT = 16;

    private static final RayTracingRendererConfig AUTOMATIC_DEFAULTS = builder()
            .frameReconstruction(FrameReconstructionOptions.productionDefault())
            .denoising(DenoisingOptions.productionDefault())
            .rayTracingOptimizations(RayTracingOptimizationOptions.productionDefault())
            .build();
    private static final RayTracingRendererConfig AUTOMATIC_GPU_PRESENTATION_DEFAULTS =
            AUTOMATIC_DEFAULTS.toBuilder()
                    .cpuFrameReadbackEnabled(false)
                    .frameGeneration(FrameGenerationOptions.productionDefault())
                    .lowLatency(LowLatencyOptions.productionDefault())
                    .build();

    private final int maxFramesInFlight;
    private final boolean validationEnabled;
    private final boolean gpuTimingsEnabled;
    private final boolean cpuFrameReadbackEnabled;
    private final RayTracingGpuDevice gpuDevice;
    private final FrameOutputFormat frameOutputFormat;
    private final TemporalRenderingOptions temporalRendering;
    private final FrameReconstructionOptions frameReconstruction;
    private final FrameGenerationOptions frameGeneration;
    private final LowLatencyOptions lowLatency;
    private final DenoisingOptions denoising;
    private final RayTracingOptimizationOptions rayTracingOptimizations;

    private RayTracingRendererConfig(Builder builder) {
        if (builder.maxFramesInFlight < MIN_MAX_FRAMES_IN_FLIGHT
                || builder.maxFramesInFlight > MAX_MAX_FRAMES_IN_FLIGHT) {
            throw new IllegalArgumentException(
                    "maxFramesInFlight must be in ["
                            + MIN_MAX_FRAMES_IN_FLIGHT + ", " + MAX_MAX_FRAMES_IN_FLIGHT + "]"
            );
        }
        maxFramesInFlight = builder.maxFramesInFlight;
        validationEnabled = builder.validationEnabled;
        gpuTimingsEnabled = builder.gpuTimingsEnabled;
        cpuFrameReadbackEnabled = builder.cpuFrameReadbackEnabled;
        gpuDevice = builder.gpuDevice;
        frameOutputFormat = Objects.requireNonNull(builder.frameOutputFormat, "frameOutputFormat");
        temporalRendering = Objects.requireNonNull(builder.temporalRendering, "temporalRendering");
        frameReconstruction = Objects.requireNonNull(builder.frameReconstruction, "frameReconstruction");
        frameGeneration = Objects.requireNonNull(builder.frameGeneration, "frameGeneration");
        lowLatency = Objects.requireNonNull(builder.lowLatency, "lowLatency");
        denoising = Objects.requireNonNull(builder.denoising, "denoising");
        rayTracingOptimizations = Objects.requireNonNull(
                builder.rayTracingOptimizations, "rayTracingOptimizations"
        );
        if (!temporalRendering.enabled()
                && frameReconstruction.fallback()
                == FrameReconstructionOptions.Fallback.BUILT_IN_TEMPORAL) {
            throw new IllegalArgumentException(
                    "built-in temporal reconstruction fallback requires temporal rendering"
            );
        }
        if (!temporalRendering.enabled() && denoising.builtInTemporalFallback()) {
            throw new IllegalArgumentException(
                    "built-in temporal denoising fallback requires temporal rendering"
            );
        }
    }

    /**
     * Starts an expert configuration builder with explicit, conservative feature defaults.
     *
     * <p>All vendor and advanced optional features begin disabled so setting one expert policy
     * cannot silently enable unrelated owners. Applications wanting the ordinary capability-driven
     * policy should tune {@link #defaults()} through {@link #toBuilder()}.</p>
     *
     * @return new single-thread-confined configuration builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the ordinary capability-driven production configuration.
     *
     * <p>Reconstruction, denoising, SER, and AS-memory optimization are preferred and negotiate
     * independently. Unsupported implementations retain their documented renderer fallbacks.
     * Presentation-time generation and latency pacing stay disabled because this CPU-readable
     * preset does not own a display cadence; use {@link #gpuPresentationDefaults()} when the
     * application will open the managed GPU presenter.</p>
     *
     * @return immutable production defaults
     */
    public static RayTracingRendererConfig defaults() {
        return AUTOMATIC_DEFAULTS;
    }

    /**
     * Returns production defaults for applications that consume frames through the managed GPU
     * presenter.
     *
     * <p>This preset disables the independent CPU-readback ring and prefers ordinary FG 2x with
     * its Reflex/PCL pacing dependency. MFG is never selected automatically. The caller still
     * opens and owns the managed presenter explicitly; unavailable presentation features retain
     * native one-frame-per-submission presentation. Raw {@code VulkanFrameInterop} consumers do
     * not own this swapchain cadence and should instead disable CPU readback on
     * {@link #defaults()} through {@link #toBuilder()}.</p>
     *
     * @return immutable production defaults without CPU frame readback
     */
    public static RayTracingRendererConfig gpuPresentationDefaults() {
        return AUTOMATIC_GPU_PRESENTATION_DEFAULTS;
    }

    /**
     * Starts an independent builder initialized from this complete value.
     *
     * @return new builder containing every current policy
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * Returns the maximum admitted frames, in
     * {@code [MIN_MAX_FRAMES_IN_FLIGHT, MAX_MAX_FRAMES_IN_FLIGHT]}.
     *
     * @return bounded frame concurrency
     */
    public int maxFramesInFlight() {
        return maxFramesInFlight;
    }

    /**
     * Returns whether backend validation diagnostics are requested.
     *
     * @return whether validation is enabled
     */
    public boolean validationEnabled() {
        return validationEnabled;
    }

    /**
     * Returns whether GPU timing collection is requested.
     *
     * @return whether GPU timing collection is enabled
     */
    public boolean gpuTimingsEnabled() {
        return gpuTimingsEnabled;
    }

    /**
     * Returns whether every frame slot owns an asynchronous managed CPU-readback target.
     *
     * <p>Enabled mode keeps {@link RayTracingRenderer#pollLatestCpuFrame()} allocation-stable on
     * the Vulkan side and never waits for the whole queue to become idle. Expert consumers that
     * exclusively import {@code VulkanFrameInterop} images may disable this policy to omit the
     * otherwise unnecessary image-to-buffer copy and host-visible frame ring.</p>
     *
     * @return whether managed CPU frame readback is enabled
     */
    public boolean cpuFrameReadbackEnabled() {
        return cpuFrameReadbackEnabled;
    }

    /**
     * Returns the explicitly selected GPU when device selection is pinned.
     *
     * @return selected GPU snapshot, or empty for backend selection policy
     */
    public java.util.Optional<RayTracingGpuDevice> gpuDevice() {
        return java.util.Optional.ofNullable(gpuDevice);
    }

    /**
     * Returns the renderer-lifetime native frame encoding.
     *
     * @return non-null output format
     */
    public FrameOutputFormat frameOutputFormat() {
        return frameOutputFormat;
    }

    /**
     * Returns the renderer-lifetime temporal reconstruction policy.
     *
     * @return non-null temporal rendering policy
     */
    public TemporalRenderingOptions temporalRendering() {
        return temporalRendering;
    }

    /**
     * Returns the optional renderer-lifetime frame reconstruction policy.
     *
     * @return non-null reconstruction policy
     */
    public FrameReconstructionOptions frameReconstruction() {
        return frameReconstruction;
    }

    /**
     * Returns the optional presentation-time generated-frame policy.
     *
     * @return non-null frame generation policy
     */
    public FrameGenerationOptions frameGeneration() {
        return frameGeneration;
    }

    /**
     * Returns the low-latency pacing and frame-marker policy, independent from frame generation.
     *
     * @return non-null low-latency policy
     */
    public LowLatencyOptions lowLatency() {
        return lowLatency;
    }

    /**
     * Returns the optional renderer-lifetime denoising policy.
     *
     * @return non-null denoising policy
     */
    public DenoisingOptions denoising() {
        return denoising;
    }

    /**
     * Returns independent advanced ray-tracing optimization preferences.
     *
     * @return non-null optimization policy
     */
    public RayTracingOptimizationOptions rayTracingOptimizations() {
        return rayTracingOptimizations;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RayTracingRendererConfig config)) return false;
        return maxFramesInFlight == config.maxFramesInFlight
                && validationEnabled == config.validationEnabled
                && gpuTimingsEnabled == config.gpuTimingsEnabled
                && cpuFrameReadbackEnabled == config.cpuFrameReadbackEnabled
                && Objects.equals(gpuDevice, config.gpuDevice)
                && frameOutputFormat == config.frameOutputFormat
                && temporalRendering.equals(config.temporalRendering)
                && frameReconstruction.equals(config.frameReconstruction)
                && frameGeneration.equals(config.frameGeneration)
                && lowLatency.equals(config.lowLatency)
                && denoising.equals(config.denoising)
                && rayTracingOptimizations.equals(config.rayTracingOptimizations);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                maxFramesInFlight, validationEnabled, gpuTimingsEnabled, cpuFrameReadbackEnabled,
                gpuDevice, frameOutputFormat, temporalRendering, frameReconstruction, denoising,
                frameGeneration, lowLatency, rayTracingOptimizations
        );
    }

    @Override
    public String toString() {
        return "RayTracingRendererConfig[maxFramesInFlight=" + maxFramesInFlight
                + ", validationEnabled=" + validationEnabled
                + ", gpuTimingsEnabled=" + gpuTimingsEnabled
                + ", cpuFrameReadbackEnabled=" + cpuFrameReadbackEnabled
                + ", gpuDevice=" + gpuDevice
                + ", frameOutputFormat=" + frameOutputFormat
                + ", temporalRendering=" + temporalRendering
                + ", frameReconstruction=" + frameReconstruction
                + ", frameGeneration=" + frameGeneration
                + ", lowLatency=" + lowLatency
                + ", denoising=" + denoising
                + ", rayTracingOptimizations=" + rayTracingOptimizations + ']';
    }

    /**
     * Single-thread-confined builder for one immutable renderer configuration.
     */
    public static final class Builder {
        private int maxFramesInFlight = DEFAULT_MAX_FRAMES_IN_FLIGHT;
        private boolean validationEnabled;
        private boolean gpuTimingsEnabled = true;
        private boolean cpuFrameReadbackEnabled = true;
        private RayTracingGpuDevice gpuDevice;
        private FrameOutputFormat frameOutputFormat = FrameOutputFormat.SDR_RGBA8;
        private TemporalRenderingOptions temporalRendering = TemporalRenderingOptions.balanced();
        private FrameReconstructionOptions frameReconstruction = FrameReconstructionOptions.disabled();
        private FrameGenerationOptions frameGeneration = FrameGenerationOptions.disabled();
        private LowLatencyOptions lowLatency = LowLatencyOptions.disabled();
        private DenoisingOptions denoising = DenoisingOptions.disabled();
        private RayTracingOptimizationOptions rayTracingOptimizations =
                RayTracingOptimizationOptions.disabled();

        private Builder() {
        }

        private Builder(RayTracingRendererConfig source) {
            maxFramesInFlight = source.maxFramesInFlight;
            validationEnabled = source.validationEnabled;
            gpuTimingsEnabled = source.gpuTimingsEnabled;
            cpuFrameReadbackEnabled = source.cpuFrameReadbackEnabled;
            gpuDevice = source.gpuDevice;
            frameOutputFormat = source.frameOutputFormat;
            temporalRendering = source.temporalRendering;
            frameReconstruction = source.frameReconstruction;
            frameGeneration = source.frameGeneration;
            lowLatency = source.lowLatency;
            denoising = source.denoising;
            rayTracingOptimizations = source.rayTracingOptimizations;
        }

        /**
         * Selects bounded frame concurrency in
         * {@code [MIN_MAX_FRAMES_IN_FLIGHT, MAX_MAX_FRAMES_IN_FLIGHT]}.
         *
         * @param value maximum admitted frames
         * @return this builder
         */
        public Builder maxFramesInFlight(int value) {
            maxFramesInFlight = value;
            return this;
        }

        /**
         * Enables or disables backend validation diagnostics.
         *
         * @param value whether validation is requested
         * @return this builder
         */
        public Builder validationEnabled(boolean value) {
            validationEnabled = value;
            return this;
        }

        /**
         * Enables or disables GPU timing collection.
         *
         * @param value whether GPU timings are requested
         * @return this builder
         */
        public Builder gpuTimingsEnabled(boolean value) {
            gpuTimingsEnabled = value;
            return this;
        }

        /**
         * Selects whether frame submissions populate a persistent asynchronous CPU-readback ring.
         *
         * <p>Disable this only when the application exclusively consumes
         * {@code VulkanFrameInterop} leases. Calling managed CPU-frame methods on a renderer built
         * with this policy disabled is rejected instead of silently introducing a synchronous
         * fallback.</p>
         *
         * @param value whether managed CPU frame readback is enabled
         * @return this builder
         */
        public Builder cpuFrameReadbackEnabled(boolean value) {
            cpuFrameReadbackEnabled = value;
            return this;
        }

        /**
         * Pins rendering to a non-null device returned by backend enumeration.
         *
         * @param value enumerated RT-capable device snapshot
         * @return this builder
         */
        public Builder gpuDevice(RayTracingGpuDevice value) {
            gpuDevice = Objects.requireNonNull(value, "gpuDevice");
            return this;
        }

        /**
         * Restores backend GPU selection policy.
         *
         * @return this builder
         */
        public Builder automaticGpuSelection() {
            gpuDevice = null;
            return this;
        }

        /**
         * Selects the renderer-lifetime native frame encoding.
         *
         * @param value non-null native frame encoding
         * @return this builder
         */
        public Builder frameOutputFormat(FrameOutputFormat value) {
            frameOutputFormat = Objects.requireNonNull(value, "frameOutputFormat");
            return this;
        }

        /**
         * Selects the renderer-lifetime temporal reconstruction policy.
         *
         * @param value non-null temporal rendering policy
         * @return this builder
         */
        public Builder temporalRendering(TemporalRenderingOptions value) {
            temporalRendering = Objects.requireNonNull(value, "temporalRendering");
            return this;
        }

        /**
         * Selects optional frame reconstruction or native-resolution reconstruction AA.
         *
         * @param value non-null reconstruction policy
         * @return this builder
         */
        public Builder frameReconstruction(FrameReconstructionOptions value) {
            frameReconstruction = Objects.requireNonNull(value, "frameReconstruction");
            return this;
        }

        /**
         * Selects optional presentation-time frame generation independently from reconstruction.
         *
         * @param value non-null generated-frame policy
         * @return this builder
         */
        public Builder frameGeneration(FrameGenerationOptions value) {
            frameGeneration = Objects.requireNonNull(value, "frameGeneration");
            return this;
        }

        /**
         * Selects low-latency pacing independently from generated presentation.
         *
         * @param value non-null low-latency policy
         * @return this builder
         */
        public Builder lowLatency(LowLatencyOptions value) {
            lowLatency = Objects.requireNonNull(value, "lowLatency");
            return this;
        }

        /**
         * Selects the optional ray-tracing denoising policy.
         *
         * @param value non-null denoising policy
         * @return this builder
         */
        public Builder denoising(DenoisingOptions value) {
            denoising = Objects.requireNonNull(value, "denoising");
            return this;
        }

        /**
         * Selects independent advanced ray-tracing optimization preferences.
         *
         * @param value non-null optimization policy
         * @return this builder
         */
        public Builder rayTracingOptimizations(RayTracingOptimizationOptions value) {
            rayTracingOptimizations = Objects.requireNonNull(value, "rayTracingOptimizations");
            return this;
        }

        /**
         * Validates and returns an independent immutable configuration.
         *
         * @return immutable validated configuration
         */
        public RayTracingRendererConfig build() {
            return new RayTracingRendererConfig(this);
        }
    }
}
