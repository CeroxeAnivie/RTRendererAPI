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

    private final int maxFramesInFlight;
    private final boolean validationEnabled;
    private final boolean gpuTimingsEnabled;
    private final RayTracingGpuDevice gpuDevice;
    private final FrameOutputFormat frameOutputFormat;
    private final TemporalRenderingOptions temporalRendering;

    private RayTracingRendererConfig(Builder builder) {
        if (builder.maxFramesInFlight < 2 || builder.maxFramesInFlight > 16) {
            throw new IllegalArgumentException("maxFramesInFlight must be in [2, 16]");
        }
        maxFramesInFlight = builder.maxFramesInFlight;
        validationEnabled = builder.validationEnabled;
        gpuTimingsEnabled = builder.gpuTimingsEnabled;
        gpuDevice = builder.gpuDevice;
        frameOutputFormat = Objects.requireNonNull(builder.frameOutputFormat, "frameOutputFormat");
        temporalRendering = Objects.requireNonNull(builder.temporalRendering, "temporalRendering");
    }

    /**
     * Starts a configuration builder with production defaults.
     *
     * @return new single-thread-confined configuration builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the production default configuration, including balanced temporal reconstruction.
     *
     * @return immutable production defaults
     */
    public static RayTracingRendererConfig defaults() {
        return builder().build();
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
     * Returns the maximum admitted frames, in {@code [2, 16]}.
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

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RayTracingRendererConfig config)) return false;
        return maxFramesInFlight == config.maxFramesInFlight
                && validationEnabled == config.validationEnabled
                && gpuTimingsEnabled == config.gpuTimingsEnabled
                && Objects.equals(gpuDevice, config.gpuDevice)
                && frameOutputFormat == config.frameOutputFormat
                && temporalRendering.equals(config.temporalRendering);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                maxFramesInFlight, validationEnabled, gpuTimingsEnabled,
                gpuDevice, frameOutputFormat, temporalRendering
        );
    }

    @Override
    public String toString() {
        return "RayTracingRendererConfig[maxFramesInFlight=" + maxFramesInFlight
                + ", validationEnabled=" + validationEnabled
                + ", gpuTimingsEnabled=" + gpuTimingsEnabled
                + ", gpuDevice=" + gpuDevice
                + ", frameOutputFormat=" + frameOutputFormat
                + ", temporalRendering=" + temporalRendering + ']';
    }

    /**
     * Single-thread-confined builder for one immutable renderer configuration.
     */
    public static final class Builder {
        private int maxFramesInFlight = DEFAULT_MAX_FRAMES_IN_FLIGHT;
        private boolean validationEnabled;
        private boolean gpuTimingsEnabled = true;
        private RayTracingGpuDevice gpuDevice;
        private FrameOutputFormat frameOutputFormat = FrameOutputFormat.SDR_RGBA8;
        private TemporalRenderingOptions temporalRendering = TemporalRenderingOptions.balanced();

        private Builder() {
        }

        private Builder(RayTracingRendererConfig source) {
            maxFramesInFlight = source.maxFramesInFlight;
            validationEnabled = source.validationEnabled;
            gpuTimingsEnabled = source.gpuTimingsEnabled;
            gpuDevice = source.gpuDevice;
            frameOutputFormat = source.frameOutputFormat;
            temporalRendering = source.temporalRendering;
        }

        /**
         * Selects bounded frame concurrency in {@code [2, 16]}.
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
         * Validates and returns an independent immutable configuration.
         *
         * @return immutable validated configuration
         */
        public RayTracingRendererConfig build() {
            return new RayTracingRendererConfig(this);
        }
    }
}
