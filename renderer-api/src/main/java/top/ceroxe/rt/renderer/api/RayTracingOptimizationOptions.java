package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/** Native shader-execution-reordering preference. AS storage is owned by RTXPT. */
public final class RayTracingOptimizationOptions {
    private static final RayTracingOptimizationOptions DISABLED = new Builder().build();
    private static final RayTracingOptimizationOptions RECOMMENDED = builder().shaderExecutionReordering(RendererFeaturePreference.PREFERRED).build();
    private final RendererFeaturePreference shaderExecutionReordering;

    private RayTracingOptimizationOptions(Builder builder) {
        shaderExecutionReordering = Objects.requireNonNull(builder.shaderExecutionReordering, "shaderExecutionReordering");
    }

    /** Returns the policy with optional processing disabled. */
    public static RayTracingOptimizationOptions disabled() { return DISABLED; }
    /** Returns the recommended executable native policy. */
    public static RayTracingOptimizationOptions recommended() { return RECOMMENDED; }
    /** Starts an explicit builder with processing disabled. */
    public static Builder builder() { return new Builder(); }
    /** Copies every implemented control. */
    public Builder toBuilder() { return new Builder(this); }

    /** Returns or selects the native SER preference. */
    public RendererFeaturePreference shaderExecutionReordering() { return shaderExecutionReordering; }

    @Override public boolean equals(Object other) {
        return this == other || other instanceof RayTracingOptimizationOptions value
                && shaderExecutionReordering == value.shaderExecutionReordering;
    }
    @Override public int hashCode() { return Objects.hash(shaderExecutionReordering); }
    @Override public String toString() { return "RayTracingOptimizationOptions[" + "shaderExecutionReordering=" + shaderExecutionReordering + ']'; }

    /** Thread-confined builder for the implemented native controls. */
    public static final class Builder {
        private RendererFeaturePreference shaderExecutionReordering = RendererFeaturePreference.DISABLED;
        private Builder() { }
        private Builder(RayTracingOptimizationOptions source) {
            shaderExecutionReordering = source.shaderExecutionReordering;
        }
        /** Returns or selects the native SER preference. */
        public Builder shaderExecutionReordering(RendererFeaturePreference value) {
            shaderExecutionReordering = Objects.requireNonNull(value, "shaderExecutionReordering"); return this;
        }
        /** Validates and returns the immutable native policy. */
        public RayTracingOptimizationOptions build() { return new RayTracingOptimizationOptions(this); }
    }
}
