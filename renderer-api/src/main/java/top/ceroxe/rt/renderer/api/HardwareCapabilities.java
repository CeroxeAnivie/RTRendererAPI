package top.ceroxe.rt.renderer.api;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, vendor-neutral hardware facts for one physical rendering device.
 *
 * <p>This value describes physical-device facts only. It does not report whether an optional
 * rendering technology was requested, whether a logical-device feature was enabled, or whether a
 * technology has executed. Those session-scoped states belong to
 * {@link RenderingFeatureCapabilities}.</p>
 *
 * <p>Every capability has an explicit three-state result. In particular, an absent or failed
 * native query is {@link SupportState#UNKNOWN}, never optimistic support. External interoperability
 * is keyed by both the public frame format and native handle contract because extension presence
 * alone does not prove that a specific image or semaphore can be shared.</p>
 */
public final class HardwareCapabilities {
    private final ProbeState probeState;
    private final Map<Feature, Support> features;
    private final long deviceLocalMemoryBytes;
    private final int maxImageDimension2D;
    private final RayTracingLimits rayTracingLimits;
    private final Map<FrameInteropKey, FrameInteropSupport> frameInterop;
    private final String reason;

    private HardwareCapabilities(Builder builder) {
        probeState = Objects.requireNonNull(builder.probeState, "probeState");
        reason = requireText(builder.reason, "reason");
        if (builder.deviceLocalMemoryBytes < 0L) {
            throw new IllegalArgumentException("deviceLocalMemoryBytes must not be negative");
        }
        if (builder.maxImageDimension2D < 0) {
            throw new IllegalArgumentException("maxImageDimension2D must not be negative");
        }
        deviceLocalMemoryBytes = builder.deviceLocalMemoryBytes;
        maxImageDimension2D = builder.maxImageDimension2D;
        rayTracingLimits = builder.rayTracingLimits;

        EnumMap<Feature, Support> completeFeatures = new EnumMap<>(Feature.class);
        for (Feature feature : Feature.values()) {
            completeFeatures.put(feature, builder.features.getOrDefault(feature, Support.unknown("not queried")));
        }
        features = Collections.unmodifiableMap(completeFeatures);
        frameInterop = Collections.unmodifiableMap(new LinkedHashMap<>(builder.frameInterop));
        validateState();
    }

    /**
     * Starts a single-thread-confined builder whose omitted features are unknown.
     *
     * @return a new mutable builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns whether the native probe completed without losing capability evidence.
     *
     * @return probe completeness state
     */
    public ProbeState probeState() {
        return probeState;
    }

    /**
     * Returns a total immutable map containing every generic hardware feature.
     *
     * @return complete feature map
     */
    public Map<Feature, Support> features() {
        return features;
    }

    /**
     * Returns one generic hardware-feature result.
     *
     * @param feature feature identity
     * @return immutable support result
     */
    public Support feature(Feature feature) {
        return features.get(Objects.requireNonNull(feature, "feature"));
    }

    /**
     * Returns true only for positive evidence from a complete probe.
     *
     * @param feature feature identity
     * @return whether the feature is positively supported
     */
    public boolean supports(Feature feature) {
        return probeState == ProbeState.COMPLETE && feature(feature).state() == SupportState.SUPPORTED;
    }

    /**
     * Returns total device-local heap capacity, or zero when the complete probe reported none.
     *
     * @return device-local memory capacity in bytes
     */
    public long deviceLocalMemoryBytes() {
        return deviceLocalMemoryBytes;
    }

    /**
     * Returns the maximum 2D image dimension, or zero when unavailable.
     *
     * @return maximum image dimension
     */
    public int maxImageDimension2D() {
        return maxImageDimension2D;
    }

    /**
     * Returns validated ray-tracing limits when the hardware RT contract is supported.
     *
     * @return optional ray-tracing limits
     */
    public Optional<RayTracingLimits> rayTracingLimits() {
        return Optional.ofNullable(rayTracingLimits);
    }

    /**
     * Returns immutable format-and-handle-specific external-frame evidence.
     *
     * @return interoperation evidence keyed by format and native handle type
     */
    public Map<FrameInteropKey, FrameInteropSupport> frameInterop() {
        return frameInterop;
    }

    /**
     * Returns one interop result, or a fully unknown result when that contract was not queried.
     *
     * @param format public frame output format
     * @param handleType native external-handle type
     * @return exact interoperation evidence for the key
     */
    public FrameInteropSupport frameInterop(FrameOutputFormat format, ExternalHandleType handleType) {
        FrameInteropKey key = new FrameInteropKey(format, handleType);
        return frameInterop.getOrDefault(key, FrameInteropSupport.unknown("not queried"));
    }

    /**
     * Returns the non-blank probe summary or failure explanation.
     *
     * @return probe explanation
     */
    public String reason() {
        return reason;
    }

    private void validateState() {
        if (probeState == ProbeState.FAILED) {
            if (features.values().stream().anyMatch(support -> support.state() == SupportState.SUPPORTED)
                    || frameInterop.values().stream().anyMatch(FrameInteropSupport::hasSupportedOperation)
                    || rayTracingLimits != null) {
                throw new IllegalArgumentException("failed hardware probe cannot publish supported capabilities");
            }
            return;
        }
        if (probeState == ProbeState.COMPLETE
                && features.values().stream().anyMatch(support -> support.state() == SupportState.UNKNOWN)) {
            throw new IllegalArgumentException(
                    "complete hardware probe cannot publish unknown feature facts"
            );
        }
        if (supports(Feature.HARDWARE_RAY_TRACING)) {
            for (Feature prerequisite : new Feature[]{
                    Feature.ACCELERATION_STRUCTURE,
                    Feature.RAY_TRACING_PIPELINE,
                    Feature.BUFFER_DEVICE_ADDRESS,
                    Feature.SHADER_INT64
            }) {
                if (!supports(prerequisite)) {
                    throw new IllegalArgumentException(
                            "hardware ray tracing requires supported " + prerequisite
                    );
                }
            }
            if (rayTracingLimits == null) {
                throw new IllegalArgumentException("hardware ray tracing requires complete ray-tracing limits");
            }
            if (maxImageDimension2D <= 0) {
                throw new IllegalArgumentException("hardware ray tracing requires maxImageDimension2D");
            }
        } else if (rayTracingLimits != null) {
            throw new IllegalArgumentException("ray-tracing limits require hardware ray-tracing support");
        }
        if (supports(Feature.EXTERNAL_MEMORY)
                && frameInterop.values().stream().noneMatch(FrameInteropSupport::hasSupportedMemoryOperation)) {
            throw new IllegalArgumentException(
                    "external-memory support requires a proven import or export contract"
            );
        }
        if (!supports(Feature.EXTERNAL_MEMORY)
                && frameInterop.values().stream().anyMatch(FrameInteropSupport::hasSupportedMemoryOperation)) {
            throw new IllegalArgumentException(
                    "supported frame memory operation requires external-memory support"
            );
        }
        if (supports(Feature.EXTERNAL_SEMAPHORE)
                && frameInterop.values().stream().noneMatch(FrameInteropSupport::bidirectionalSemaphoreSupported)) {
            throw new IllegalArgumentException(
                    "external-semaphore support requires proven export and import for one handle contract"
            );
        }
        if (!supports(Feature.EXTERNAL_SEMAPHORE)
                && frameInterop.values().stream().anyMatch(FrameInteropSupport::hasSupportedSemaphoreOperation)) {
            throw new IllegalArgumentException(
                    "supported frame semaphore operation requires external-semaphore support"
            );
        }
    }

    private static String requireText(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isBlank() || !checked.equals(checked.trim())) {
            throw new IllegalArgumentException(name + " must be non-blank and normalized");
        }
        for (int index = 0; index < checked.length(); index++) {
            if (Character.isISOControl(checked.charAt(index))) {
                throw new IllegalArgumentException(name + " must not contain control characters");
            }
        }
        return checked;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof HardwareCapabilities that)) return false;
        return deviceLocalMemoryBytes == that.deviceLocalMemoryBytes
                && maxImageDimension2D == that.maxImageDimension2D
                && probeState == that.probeState
                && features.equals(that.features)
                && Objects.equals(rayTracingLimits, that.rayTracingLimits)
                && frameInterop.equals(that.frameInterop)
                && reason.equals(that.reason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                probeState, features, deviceLocalMemoryBytes, maxImageDimension2D,
                rayTracingLimits, frameInterop, reason
        );
    }

    @Override
    public String toString() {
        return "HardwareCapabilities[probeState=" + probeState
                + ", features=" + features
                + ", deviceLocalMemoryBytes=" + deviceLocalMemoryBytes
                + ", maxImageDimension2D=" + maxImageDimension2D
                + ", rayTracingLimits=" + rayTracingLimits
                + ", frameInterop=" + frameInterop
                + ", reason=" + reason + ']';
    }

    /** Completeness of the native physical-device probe that produced this value. */
    public enum ProbeState {
        /** Every advertised fact was queried successfully. */
        COMPLETE,
        /** The probe returned a usable partial inventory with facts still unknown. */
        PARTIAL,
        /** The probe failed; all capability results must remain unknown or unsupported. */
        FAILED
    }

    /** Vendor-neutral physical-device capabilities used by renderer admission. */
    public enum Feature {
        /** Complete hardware ray-tracing admission contract. */
        HARDWARE_RAY_TRACING,
        /** Acceleration-structure extension and feature support. */
        ACCELERATION_STRUCTURE,
        /** Ray-tracing pipeline extension and feature support. */
        RAY_TRACING_PIPELINE,
        /** Buffer-device-address support. */
        BUFFER_DEVICE_ADDRESS,
        /** 64-bit shader integer support. */
        SHADER_INT64,
        /** External image-memory interoperability support. */
        EXTERNAL_MEMORY,
        /** External semaphore interoperability support. */
        EXTERNAL_SEMAPHORE,
        /** Device-memory budget telemetry support. */
        MEMORY_BUDGET,
        /** GPU timestamp-query support. */
        GPU_TIMESTAMPS
    }

    /** Exhaustive result of one hardware-capability query. */
    public enum SupportState {
        /** The queried operation is positively supported. */
        SUPPORTED,
        /** The queried operation is positively unsupported. */
        UNSUPPORTED,
        /** The operation could not be queried authoritatively. */
        UNKNOWN
    }

    /**
     * One immutable hardware support result with non-empty evidence text.
     *
     * @param state three-state support result
     * @param reason non-blank evidence or failure explanation
     */
    public record Support(SupportState state, String reason) {
        /** Validates the immutable support snapshot. */
        public Support {
            state = Objects.requireNonNull(state, "state");
            reason = requireText(reason, "reason");
        }

        /**
         * Creates positive support evidence.
         *
         * @param reason non-blank evidence
         * @return supported result
         */
        public static Support supported(String reason) {
            return new Support(SupportState.SUPPORTED, reason);
        }

        /**
         * Creates explicit unsupported evidence.
         *
         * @param reason non-blank evidence
         * @return unsupported result
         */
        public static Support unsupported(String reason) {
            return new Support(SupportState.UNSUPPORTED, reason);
        }

        /**
         * Creates unknown evidence for a failed or omitted query.
         *
         * @param reason non-blank explanation
         * @return unknown result
         */
        public static Support unknown(String reason) {
            return new Support(SupportState.UNKNOWN, reason);
        }
    }

    /** Public native-handle contracts currently implemented by renderer backends. */
    public enum ExternalHandleType {
        /** Windows opaque handle contract. */
        OPAQUE_WIN32
    }

    /**
     * Identity of one format-specific native-frame interoperability contract.
     *
     * @param format public frame output format
     * @param handleType native external-handle type
     */
    public record FrameInteropKey(FrameOutputFormat format, ExternalHandleType handleType) {
        /** Validates the immutable interoperation key. */
        public FrameInteropKey {
            format = Objects.requireNonNull(format, "format");
            handleType = Objects.requireNonNull(handleType, "handleType");
        }
    }

    /** Whether an external image must use a dedicated allocation. */
    public enum DedicatedAllocation {
        /** Dedicated allocation is required by the queried contract. */
        REQUIRED,
        /** Dedicated allocation is not required by the queried contract. */
        NOT_REQUIRED,
        /** Allocation requirement could not be queried. */
        UNKNOWN
    }

    /**
     * Exact sharing evidence for one output format and native handle type.
     *
     * <p>Semaphore support is intentionally split by direction. A producer export cannot be used
     * as consumer-completion synchronization unless import support was independently proven.</p>
     *
     * @param memoryExport image-memory export evidence
     * @param memoryImport image-memory import evidence
     * @param semaphoreExport semaphore export evidence
     * @param semaphoreImport semaphore import evidence
     * @param dedicatedAllocation image allocation requirement
     */
    public record FrameInteropSupport(
            Support memoryExport,
            Support memoryImport,
            Support semaphoreExport,
            Support semaphoreImport,
            DedicatedAllocation dedicatedAllocation
    ) {
        /** Validates directional memory and semaphore evidence. */
        public FrameInteropSupport {
            memoryExport = Objects.requireNonNull(memoryExport, "memoryExport");
            memoryImport = Objects.requireNonNull(memoryImport, "memoryImport");
            semaphoreExport = Objects.requireNonNull(semaphoreExport, "semaphoreExport");
            semaphoreImport = Objects.requireNonNull(semaphoreImport, "semaphoreImport");
            dedicatedAllocation = Objects.requireNonNull(dedicatedAllocation, "dedicatedAllocation");
            if (memoryExport.state() != SupportState.SUPPORTED
                    && memoryImport.state() != SupportState.SUPPORTED
                    && dedicatedAllocation != DedicatedAllocation.UNKNOWN) {
                throw new IllegalArgumentException(
                        "dedicated-allocation evidence requires supported memory export"
                );
            }
        }

        /**
         * Creates an all-unknown interoperation result.
         *
         * @param reason non-blank query explanation
         * @return unknown interoperation evidence
         */
        public static FrameInteropSupport unknown(String reason) {
            Support unknown = Support.unknown(reason);
            return new FrameInteropSupport(
                    unknown, unknown, unknown, unknown, DedicatedAllocation.UNKNOWN
            );
        }

        boolean hasSupportedOperation() {
            return memoryExport.state() == SupportState.SUPPORTED
                    || memoryImport.state() == SupportState.SUPPORTED
                    || semaphoreExport.state() == SupportState.SUPPORTED
                    || semaphoreImport.state() == SupportState.SUPPORTED;
        }

        boolean hasSupportedMemoryOperation() {
            return memoryExport.state() == SupportState.SUPPORTED
                    || memoryImport.state() == SupportState.SUPPORTED;
        }

        boolean hasSupportedSemaphoreOperation() {
            return semaphoreExport.state() == SupportState.SUPPORTED
                    || semaphoreImport.state() == SupportState.SUPPORTED;
        }

        boolean bidirectionalSemaphoreSupported() {
            return semaphoreExport.state() == SupportState.SUPPORTED
                    && semaphoreImport.state() == SupportState.SUPPORTED;
        }
    }

    /** Single-thread-confined semantic builder. */
    public static final class Builder {
        private ProbeState probeState;
        private final EnumMap<Feature, Support> features = new EnumMap<>(Feature.class);
        private long deviceLocalMemoryBytes;
        private int maxImageDimension2D;
        private RayTracingLimits rayTracingLimits;
        private final Map<FrameInteropKey, FrameInteropSupport> frameInterop = new LinkedHashMap<>();
        private String reason;

        private Builder() {
        }

        /**
         * Selects probe completeness.
         *
         * @param value probe state
         * @return this builder
         */
        public Builder probeState(ProbeState value) {
            probeState = Objects.requireNonNull(value, "probeState");
            return this;
        }

        /**
         * Records one generic feature result.
         *
         * @param feature feature identity
         * @param support support evidence
         * @return this builder
         */
        public Builder feature(Feature feature, Support support) {
            features.put(
                    Objects.requireNonNull(feature, "feature"),
                    Objects.requireNonNull(support, "support")
            );
            return this;
        }

        /**
         * Records device-local heap capacity.
         *
         * @param value capacity in bytes
         * @return this builder
         */
        public Builder deviceLocalMemoryBytes(long value) {
            if (value < 0L) throw new IllegalArgumentException("deviceLocalMemoryBytes must not be negative");
            deviceLocalMemoryBytes = value;
            return this;
        }

        /**
         * Records the maximum legal two-dimensional image dimension.
         *
         * @param value maximum dimension
         * @return this builder
         */
        public Builder maxImageDimension2D(int value) {
            if (value < 0) throw new IllegalArgumentException("maxImageDimension2D must not be negative");
            maxImageDimension2D = value;
            return this;
        }

        /**
         * Records complete ray-tracing limits.
         *
         * @param value validated ray-tracing limits
         * @return this builder
         */
        public Builder rayTracingLimits(RayTracingLimits value) {
            rayTracingLimits = Objects.requireNonNull(value, "rayTracingLimits");
            return this;
        }

        /**
         * Records one format-and-handle interoperation result.
         *
         * @param format output format
         * @param handleType native handle type
         * @param support interoperation evidence
         * @return this builder
         */
        public Builder frameInterop(
                FrameOutputFormat format,
                ExternalHandleType handleType,
                FrameInteropSupport support
        ) {
            FrameInteropKey key = new FrameInteropKey(format, handleType);
            if (frameInterop.put(key, Objects.requireNonNull(support, "support")) != null) {
                throw new IllegalStateException("frame interop contract already selected: " + key);
            }
            return this;
        }

        /**
         * Records the probe explanation.
         *
         * @param value non-blank explanation
         * @return this builder
         */
        public Builder reason(String value) {
            reason = requireText(value, "reason");
            return this;
        }

        /**
         * Creates the immutable capability snapshot.
         *
         * @return immutable hardware capabilities
         */
        public HardwareCapabilities build() {
            return new HardwareCapabilities(this);
        }
    }

    /** Immutable limits required to build and dispatch the hardware ray-tracing pipeline. */
    public static final class RayTracingLimits {
        private final int maxRayRecursionDepth;
        private final int shaderGroupHandleSize;
        private final int shaderGroupHandleAlignment;
        private final int shaderGroupBaseAlignment;
        private final int maxShaderGroupStride;
        private final long maxRayDispatchInvocationCount;
        private final int minAccelerationStructureScratchAlignment;

        private RayTracingLimits(Builder builder) {
            if (builder.selectedFields != 0x7f) {
                throw new IllegalStateException("every ray-tracing limit must be selected before build");
            }
            maxRayRecursionDepth = builder.maxRayRecursionDepth;
            shaderGroupHandleSize = builder.shaderGroupHandleSize;
            shaderGroupHandleAlignment = builder.shaderGroupHandleAlignment;
            shaderGroupBaseAlignment = builder.shaderGroupBaseAlignment;
            maxShaderGroupStride = builder.maxShaderGroupStride;
            maxRayDispatchInvocationCount = builder.maxRayDispatchInvocationCount;
            minAccelerationStructureScratchAlignment = builder.minAccelerationStructureScratchAlignment;
        }

        /**
         * Starts a complete ray-tracing limits builder.
         *
         * @return mutable limits builder
         */
        public static Builder builder() {
            return new Builder();
        }

        /**
         * Returns the maximum recursion depth accepted by the ray-tracing pipeline.
         *
         * @return maximum recursion depth
         */
        public int maxRayRecursionDepth() { return maxRayRecursionDepth; }

        /**
         * Returns the size in bytes of one shader-group handle.
         *
         * @return handle size in bytes
         */
        public int shaderGroupHandleSize() { return shaderGroupHandleSize; }

        /**
         * Returns the required shader-group handle alignment in bytes.
         *
         * @return handle alignment in bytes
         */
        public int shaderGroupHandleAlignment() { return shaderGroupHandleAlignment; }

        /**
         * Returns the required shader-binding-table base alignment in bytes.
         *
         * @return base alignment in bytes
         */
        public int shaderGroupBaseAlignment() { return shaderGroupBaseAlignment; }

        /**
         * Returns the largest legal shader-group stride in bytes.
         *
         * @return maximum stride in bytes
         */
        public int maxShaderGroupStride() { return maxShaderGroupStride; }

        /**
         * Returns the maximum number of invocations accepted by one ray dispatch.
         *
         * @return maximum invocation count
         */
        public long maxRayDispatchInvocationCount() { return maxRayDispatchInvocationCount; }

        /**
         * Returns the required acceleration-structure scratch-buffer alignment in bytes.
         *
         * @return scratch alignment in bytes
         */
        public int minAccelerationStructureScratchAlignment() {
            return minAccelerationStructureScratchAlignment;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof RayTracingLimits limits)) return false;
            return maxRayRecursionDepth == limits.maxRayRecursionDepth
                    && shaderGroupHandleSize == limits.shaderGroupHandleSize
                    && shaderGroupHandleAlignment == limits.shaderGroupHandleAlignment
                    && shaderGroupBaseAlignment == limits.shaderGroupBaseAlignment
                    && maxShaderGroupStride == limits.maxShaderGroupStride
                    && maxRayDispatchInvocationCount == limits.maxRayDispatchInvocationCount
                    && minAccelerationStructureScratchAlignment
                    == limits.minAccelerationStructureScratchAlignment;
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    maxRayRecursionDepth, shaderGroupHandleSize, shaderGroupHandleAlignment,
                    shaderGroupBaseAlignment, maxShaderGroupStride, maxRayDispatchInvocationCount,
                    minAccelerationStructureScratchAlignment
            );
        }

        @Override
        public String toString() {
            return "RayTracingLimits[maxRayRecursionDepth=" + maxRayRecursionDepth
                    + ", shaderGroupHandleSize=" + shaderGroupHandleSize
                    + ", shaderGroupHandleAlignment=" + shaderGroupHandleAlignment
                    + ", shaderGroupBaseAlignment=" + shaderGroupBaseAlignment
                    + ", maxShaderGroupStride=" + maxShaderGroupStride
                    + ", maxRayDispatchInvocationCount=" + maxRayDispatchInvocationCount
                    + ", minAccelerationStructureScratchAlignment="
                    + minAccelerationStructureScratchAlignment + ']';
        }

        /** Single-thread-confined builder for complete ray-tracing limits. */
        public static final class Builder {
            private int selectedFields;
            private int maxRayRecursionDepth;
            private int shaderGroupHandleSize;
            private int shaderGroupHandleAlignment;
            private int shaderGroupBaseAlignment;
            private int maxShaderGroupStride;
            private long maxRayDispatchInvocationCount;
            private int minAccelerationStructureScratchAlignment;

            private Builder() {
            }

            private static int positive(int value, String name) {
                if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
                return value;
            }

            private static long positive(long value, String name) {
                if (value <= 0L) throw new IllegalArgumentException(name + " must be positive");
                return value;
            }

            /**
             * Selects maximum ray recursion depth.
             *
             * @param value positive limit
             * @return this builder
             */
            public Builder maxRayRecursionDepth(int value) {
                maxRayRecursionDepth = positive(value, "maxRayRecursionDepth");
                selectedFields |= 1;
                return this;
            }

            /**
             * Selects shader-group handle size.
             *
             * @param value positive size in bytes
             * @return this builder
             */
            public Builder shaderGroupHandleSize(int value) {
                shaderGroupHandleSize = positive(value, "shaderGroupHandleSize");
                selectedFields |= 2;
                return this;
            }

            /**
             * Selects shader-group handle alignment.
             *
             * @param value positive alignment in bytes
             * @return this builder
             */
            public Builder shaderGroupHandleAlignment(int value) {
                shaderGroupHandleAlignment = positive(value, "shaderGroupHandleAlignment");
                selectedFields |= 4;
                return this;
            }

            /**
             * Selects shader-binding-table base alignment.
             *
             * @param value positive alignment in bytes
             * @return this builder
             */
            public Builder shaderGroupBaseAlignment(int value) {
                shaderGroupBaseAlignment = positive(value, "shaderGroupBaseAlignment");
                selectedFields |= 8;
                return this;
            }

            /**
             * Selects maximum shader-group stride.
             *
             * @param value positive stride in bytes
             * @return this builder
             */
            public Builder maxShaderGroupStride(int value) {
                maxShaderGroupStride = positive(value, "maxShaderGroupStride");
                selectedFields |= 16;
                return this;
            }

            /**
             * Selects maximum ray-dispatch invocation count.
             *
             * @param value positive invocation count
             * @return this builder
             */
            public Builder maxRayDispatchInvocationCount(long value) {
                maxRayDispatchInvocationCount = positive(value, "maxRayDispatchInvocationCount");
                selectedFields |= 32;
                return this;
            }

            /**
             * Selects acceleration-structure scratch alignment.
             *
             * @param value positive alignment in bytes
             * @return this builder
             */
            public Builder minAccelerationStructureScratchAlignment(int value) {
                minAccelerationStructureScratchAlignment = positive(
                        value, "minAccelerationStructureScratchAlignment"
                );
                selectedFields |= 64;
                return this;
            }

            /**
             * Creates the immutable complete limits value.
             *
             * @return immutable ray-tracing limits
             */
            public RayTracingLimits build() {
                return new RayTracingLimits(this);
            }
        }
    }
}
