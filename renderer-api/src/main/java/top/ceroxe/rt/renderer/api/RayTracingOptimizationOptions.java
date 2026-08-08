package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Independent renderer-lifetime preferences for advanced ray-tracing execution and memory paths.
 *
 * <p>Each preference negotiates independently, so a device with shader execution reordering but
 * no RTXMU memory utility can keep SER active without claiming the missing optimization. A
 * {@link RendererFeaturePreference#REQUIRED REQUIRED} preference is a hard initialization
 * contract and never silently degrades; {@link RendererFeaturePreference#PREFERRED PREFERRED}
 * remains independently fallback-capable.</p>
 */
public final class RayTracingOptimizationOptions {
    private static final RayTracingOptimizationOptions DISABLED = new Builder().build();
    private static final RayTracingOptimizationOptions PRODUCTION_DEFAULT = new Builder()
            .shaderExecutionReordering(RendererFeaturePreference.PREFERRED)
            .memoryOptimization(RendererFeaturePreference.PREFERRED)
            .build();

    private final RendererFeaturePreference shaderExecutionReordering;
    private final RendererFeaturePreference memoryOptimization;

    private RayTracingOptimizationOptions(Builder builder) {
        shaderExecutionReordering = Objects.requireNonNull(
                builder.shaderExecutionReordering, "shaderExecutionReordering"
        );
        memoryOptimization = Objects.requireNonNull(builder.memoryOptimization, "memoryOptimization");
    }

    /**
     * Returns the canonical policy that requests no advanced optimization.
     *
     * @return disabled immutable policy
     */
    public static RayTracingOptimizationOptions disabled() {
        return DISABLED;
    }

    /**
     * Returns the capability-driven production policy for SER and native AS memory reuse.
     *
     * <p>All preferences are deliberately {@link RendererFeaturePreference#PREFERRED}: device
     * negotiation enables only the extensions and providers that are executable on the selected
     * adapter. Unsupported RTX generations therefore retain the core Vulkan paths instead of
     * failing renderer creation.</p>
     *
     * @return preferred adaptive optimization policy
     */
    public static RayTracingOptimizationOptions recommended() {
        return PRODUCTION_DEFAULT;
    }

    /**
     * Starts an explicit builder with all preferences disabled.
     *
     * @return new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Starts an independent builder containing this complete policy.
     *
     * @return copied builder
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * Returns the shader execution reordering preference.
     *
     * @return shader execution reordering preference
     */
    public RendererFeaturePreference shaderExecutionReordering() {
        return shaderExecutionReordering;
    }

    /**
     * Returns the native memory utility preference.
     *
     * @return native memory utility preference
     */
    public RendererFeaturePreference memoryOptimization() {
        return memoryOptimization;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof RayTracingOptimizationOptions options
                && shaderExecutionReordering == options.shaderExecutionReordering
                && memoryOptimization == options.memoryOptimization;
    }

    @Override
    public int hashCode() {
        return Objects.hash(shaderExecutionReordering, memoryOptimization);
    }

    @Override
    public String toString() {
        return "RayTracingOptimizationOptions[shaderExecutionReordering="
                + shaderExecutionReordering
                + ", memoryOptimization=" + memoryOptimization + ']';
    }

    /** Single-thread-confined builder for independent optimization preferences. */
    public static final class Builder {
        private RendererFeaturePreference shaderExecutionReordering = RendererFeaturePreference.DISABLED;
        private RendererFeaturePreference memoryOptimization = RendererFeaturePreference.DISABLED;

        private Builder() {
        }

        private Builder(RayTracingOptimizationOptions source) {
            shaderExecutionReordering = source.shaderExecutionReordering;
            memoryOptimization = source.memoryOptimization;
        }

        /**
         * Selects shader execution reordering policy.
         *
         * @param value non-null preference
         * @return this builder
         */
        public Builder shaderExecutionReordering(RendererFeaturePreference value) {
            shaderExecutionReordering = Objects.requireNonNull(value, "shaderExecutionReordering");
            return this;
        }

        /**
         * Selects native memory optimization policy.
         *
         * @param value non-null preference
         * @return this builder
         */
        public Builder memoryOptimization(RendererFeaturePreference value) {
            memoryOptimization = Objects.requireNonNull(value, "memoryOptimization");
            return this;
        }

        /**
         * Creates the immutable policy.
         *
         * @return immutable policy
         */
        public RayTracingOptimizationOptions build() {
            return new RayTracingOptimizationOptions(this);
        }
    }
}
