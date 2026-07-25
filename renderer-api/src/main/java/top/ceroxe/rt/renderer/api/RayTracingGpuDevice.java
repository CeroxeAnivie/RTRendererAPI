package top.ceroxe.rt.renderer.api;

import java.util.Objects;
import java.util.Set;

/**
 * Immutable, backend-produced description of one physical GPU that satisfies the renderer's
 * hardware ray-tracing admission contract.
 *
 * <p>The pair {@code (backendId, stableId)} is the selection identity. Callers must pass the
 * object returned by {@link RendererBootstrap#availableGpuDevices()} back through
 * {@link RayTracingRendererConfig.Builder#gpuDevice(RayTracingGpuDevice)}; names and enumeration
 * indices are deliberately not selection keys because both can change or collide.</p>
 *
 * <p>Providers construct snapshots through {@link #builder()}. The semantic setters prevent the
 * three adjacent strings and the device/vendor identifiers from being exchanged positionally.</p>
 */
public final class RayTracingGpuDevice {
    private final String backendId;
    private final String stableId;
    private final String name;
    private final int vendorId;
    private final int deviceId;
    private final Type type;
    private final ApiVersion apiVersion;
    private final long deviceLocalMemoryBytes;
    private final Set<Capability> capabilities;
    private final RayTracingLimits rayTracingLimits;

    private RayTracingGpuDevice(Builder builder) {
        backendId = requireText(requireSelected(builder.backendId, "backendId"), "backendId");
        stableId = requireText(requireSelected(builder.stableId, "stableId"), "stableId");
        name = requireText(requireSelected(builder.name, "name"), "name");
        vendorId = builder.vendorId;
        deviceId = builder.deviceId;
        type = requireSelected(builder.type, "type");
        apiVersion = requireSelected(builder.apiVersion, "apiVersion");
        if (builder.deviceLocalMemoryBytes < 0L) {
            throw new IllegalArgumentException("deviceLocalMemoryBytes must not be negative");
        }
        deviceLocalMemoryBytes = builder.deviceLocalMemoryBytes;
        capabilities = Set.copyOf(requireSelected(builder.capabilities, "capabilities"));
        if (!capabilities.contains(Capability.HARDWARE_RAY_TRACING)) {
            throw new IllegalArgumentException("renderer devices must support hardware ray tracing");
        }
        rayTracingLimits = requireSelected(builder.rayTracingLimits, "rayTracingLimits");
    }

    /**
     * Starts an empty semantic builder for one backend-produced device snapshot.
     *
     * @return new single-thread-confined builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private static String requireText(String value, String name) {
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static <T> T requireSelected(T value, String name) {
        if (value == null) {
            throw new IllegalStateException(name + " must be selected before build");
        }
        return value;
    }

    /**
     * Starts an independent builder initialized from this complete snapshot.
     *
     * @return builder containing every current device property
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * Returns the backend provider identity.
     *
     * @return non-blank backend identifier
     */
    public String backendId() {
        return backendId;
    }

    /**
     * Returns the opaque physical-device selection identity.
     *
     * @return non-blank stable identifier
     */
    public String stableId() {
        return stableId;
    }

    /**
     * Returns the driver-supplied display name.
     *
     * @return non-blank device name
     */
    public String name() {
        return name;
    }

    /**
     * Returns the reported PCI vendor identifier.
     *
     * @return backend-reported vendor identifier
     */
    public int vendorId() {
        return vendorId;
    }

    /**
     * Returns the reported PCI device identifier.
     *
     * @return backend-reported device identifier
     */
    public int deviceId() {
        return deviceId;
    }

    /**
     * Returns the physical-device category.
     *
     * @return non-null device type
     */
    public Type type() {
        return type;
    }

    /**
     * Returns the backend API version supported by the device.
     *
     * @return immutable API version
     */
    public ApiVersion apiVersion() {
        return apiVersion;
    }

    /**
     * Returns total device-local heap capacity, or zero when unavailable.
     *
     * @return non-negative byte count
     */
    public long deviceLocalMemoryBytes() {
        return deviceLocalMemoryBytes;
    }

    /**
     * Returns the immutable supported capability set.
     *
     * @return capabilities including hardware ray tracing
     */
    public Set<Capability> capabilities() {
        return capabilities;
    }

    /**
     * Returns immutable hardware ray-tracing limits.
     *
     * @return validated limits
     */
    public RayTracingLimits rayTracingLimits() {
        return rayTracingLimits;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RayTracingGpuDevice device)) return false;
        return vendorId == device.vendorId
                && deviceId == device.deviceId
                && deviceLocalMemoryBytes == device.deviceLocalMemoryBytes
                && backendId.equals(device.backendId)
                && stableId.equals(device.stableId)
                && name.equals(device.name)
                && type == device.type
                && apiVersion.equals(device.apiVersion)
                && capabilities.equals(device.capabilities)
                && rayTracingLimits.equals(device.rayTracingLimits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                backendId, stableId, name, vendorId, deviceId, type, apiVersion,
                deviceLocalMemoryBytes, capabilities, rayTracingLimits
        );
    }

    @Override
    public String toString() {
        return "RayTracingGpuDevice[backendId=" + backendId
                + ", stableId=" + stableId
                + ", name=" + name
                + ", vendorId=" + vendorId
                + ", deviceId=" + deviceId
                + ", type=" + type
                + ", apiVersion=" + apiVersion
                + ", deviceLocalMemoryBytes=" + deviceLocalMemoryBytes
                + ", capabilities=" + capabilities
                + ", rayTracingLimits=" + rayTracingLimits + ']';
    }

    /**
     * Physical-device category reported by the backend.
     */
    public enum Type {
        /**
         * Dedicated discrete graphics processor.
         */
        DISCRETE,
        /**
         * Processor sharing system memory.
         */
        INTEGRATED,
        /**
         * Virtualized graphics processor.
         */
        VIRTUAL,
        /**
         * Backend-reported category not represented above.
         */
        OTHER
    }

    /**
     * Backend-neutral device capability exposed for admission and diagnostics.
     */
    public enum Capability {
        /**
         * Hardware-accelerated ray traversal is available.
         */
        HARDWARE_RAY_TRACING,
        /**
         * Native acceleration structures are available.
         */
        ACCELERATION_STRUCTURE,
        /**
         * Programmable ray-tracing pipelines are available.
         */
        RAY_TRACING_PIPELINE,
        /**
         * Shader-visible buffer device addresses are available.
         */
        BUFFER_DEVICE_ADDRESS,
        /**
         * 64-bit integer shader operations are available.
         */
        SHADER_INT64,
        /**
         * Exportable device memory is available.
         */
        EXTERNAL_MEMORY,
        /**
         * Exportable and importable semaphores are available.
         */
        EXTERNAL_SEMAPHORE,
        /**
         * Exportable display-ready SDR RGBA8 native frames are available.
         */
        NATIVE_SDR_RGBA8,
        /**
         * Exportable linear scene-referred HDR RGBA16F native frames are available.
         */
        NATIVE_LINEAR_HDR_RGBA16F,
        /**
         * Runtime device-memory budget reporting is available.
         */
        MEMORY_BUDGET,
        /**
         * GPU timestamp queries are available.
         */
        GPU_TIMESTAMPS
    }

    /**
     * Single-thread-confined builder for one immutable physical-device snapshot.
     */
    public static final class Builder {
        private String backendId;
        private String stableId;
        private String name;
        private int vendorId;
        private int deviceId;
        private Type type;
        private ApiVersion apiVersion;
        private long deviceLocalMemoryBytes;
        private Set<Capability> capabilities;
        private RayTracingLimits rayTracingLimits;

        private Builder() {
        }

        private Builder(RayTracingGpuDevice source) {
            backendId = source.backendId;
            stableId = source.stableId;
            name = source.name;
            vendorId = source.vendorId;
            deviceId = source.deviceId;
            type = source.type;
            apiVersion = source.apiVersion;
            deviceLocalMemoryBytes = source.deviceLocalMemoryBytes;
            capabilities = source.capabilities;
            rayTracingLimits = source.rayTracingLimits;
        }

        /**
         * Selects the backend provider that owns this device.
         *
         * @param value non-blank backend identifier
         * @return this builder
         */
        public Builder backendId(String value) {
            backendId = requireText(Objects.requireNonNull(value, "backendId"), "backendId");
            return this;
        }

        /**
         * Selects the opaque physical-device identity.
         *
         * @param value non-blank stable identifier
         * @return this builder
         */
        public Builder stableId(String value) {
            stableId = requireText(Objects.requireNonNull(value, "stableId"), "stableId");
            return this;
        }

        /**
         * Selects the driver-supplied display name.
         *
         * @param value non-blank device name
         * @return this builder
         */
        public Builder name(String value) {
            name = requireText(Objects.requireNonNull(value, "name"), "name");
            return this;
        }

        /**
         * Selects the reported PCI vendor identifier.
         *
         * @param value backend-reported vendor identifier
         * @return this builder
         */
        public Builder vendorId(int value) {
            vendorId = value;
            return this;
        }

        /**
         * Selects the reported PCI device identifier.
         *
         * @param value backend-reported device identifier
         * @return this builder
         */
        public Builder deviceId(int value) {
            deviceId = value;
            return this;
        }

        /**
         * Selects the physical-device category.
         *
         * @param value non-null device type
         * @return this builder
         */
        public Builder type(Type value) {
            type = Objects.requireNonNull(value, "type");
            return this;
        }

        /**
         * Selects the backend API version supported by this device.
         *
         * @param value non-null API version
         * @return this builder
         */
        public Builder apiVersion(ApiVersion value) {
            apiVersion = Objects.requireNonNull(value, "apiVersion");
            return this;
        }

        /**
         * Selects total device-local heap capacity, or zero when unavailable.
         *
         * @param value non-negative byte count
         * @return this builder
         */
        public Builder deviceLocalMemoryBytes(long value) {
            if (value < 0L) throw new IllegalArgumentException("deviceLocalMemoryBytes must not be negative");
            deviceLocalMemoryBytes = value;
            return this;
        }

        /**
         * Selects the complete supported capability set.
         *
         * @param values capabilities including hardware ray tracing
         * @return this builder
         */
        public Builder capabilities(Set<Capability> values) {
            capabilities = Set.copyOf(Objects.requireNonNull(values, "capabilities"));
            return this;
        }

        /**
         * Selects validated hardware ray-tracing limits.
         *
         * @param value non-null immutable limits
         * @return this builder
         */
        public Builder rayTracingLimits(RayTracingLimits value) {
            rayTracingLimits = Objects.requireNonNull(value, "rayTracingLimits");
            return this;
        }

        /**
         * Validates and returns an independent immutable device snapshot.
         *
         * @return validated device snapshot
         */
        public RayTracingGpuDevice build() {
            return new RayTracingGpuDevice(this);
        }
    }

    /**
     * Immutable semantic backend API version.
     *
     * @param major non-negative major version
     * @param minor non-negative minor version
     * @param patch non-negative patch version
     */
    public record ApiVersion(int major, int minor, int patch) {
        /**
         * Validates and creates an API version.
         */
        public ApiVersion {
            if (major < 0 || minor < 0 || patch < 0) {
                throw new IllegalArgumentException("API version components must not be negative");
            }
        }
    }

    /**
     * Backend-neutral limits required to size shader tables and validate dispatches.
     */
    public static final class RayTracingLimits {
        private static final int ALL_FIELDS = 0x7f;

        private final int maxRayRecursionDepth;
        private final int shaderGroupHandleSize;
        private final int shaderGroupHandleAlignment;
        private final int shaderGroupBaseAlignment;
        private final int maxShaderGroupStride;
        private final long maxRayDispatchInvocationCount;
        private final int minAccelerationStructureScratchAlignment;

        private RayTracingLimits(Builder builder) {
            if (builder.selectedFields != ALL_FIELDS) {
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
         * Starts an empty semantic hardware-limit builder.
         *
         * @return new single-thread-confined builder
         */
        public static Builder builder() {
            return new Builder();
        }

        private static int requirePositive(int value, String name) {
            if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
            return value;
        }

        private static long requirePositive(long value, String name) {
            if (value <= 0L) throw new IllegalArgumentException(name + " must be positive");
            return value;
        }

        /**
         * Starts an independent builder initialized from this complete value.
         *
         * @return builder containing every current limit
         */
        public Builder toBuilder() {
            return new Builder(this);
        }

        /**
         * Returns the maximum supported ray recursion depth.
         *
         * @return positive recursion depth
         */
        public int maxRayRecursionDepth() {
            return maxRayRecursionDepth;
        }

        /**
         * Returns the shader group handle size.
         *
         * @return positive byte count
         */
        public int shaderGroupHandleSize() {
            return shaderGroupHandleSize;
        }

        /**
         * Returns the shader group handle alignment.
         *
         * @return positive byte alignment
         */
        public int shaderGroupHandleAlignment() {
            return shaderGroupHandleAlignment;
        }

        /**
         * Returns the shader binding table base alignment.
         *
         * @return positive byte alignment
         */
        public int shaderGroupBaseAlignment() {
            return shaderGroupBaseAlignment;
        }

        /**
         * Returns the maximum shader binding table record stride.
         *
         * @return positive byte stride
         */
        public int maxShaderGroupStride() {
            return maxShaderGroupStride;
        }

        /**
         * Returns the maximum invocations in one ray dispatch.
         *
         * @return positive invocation count
         */
        public long maxRayDispatchInvocationCount() {
            return maxRayDispatchInvocationCount;
        }

        /**
         * Returns the required acceleration-structure scratch address alignment.
         *
         * @return positive byte alignment
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

        /**
         * Single-thread-confined builder that requires every hardware limit explicitly.
         */
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

            private Builder(RayTracingLimits source) {
                selectedFields = ALL_FIELDS;
                maxRayRecursionDepth = source.maxRayRecursionDepth;
                shaderGroupHandleSize = source.shaderGroupHandleSize;
                shaderGroupHandleAlignment = source.shaderGroupHandleAlignment;
                shaderGroupBaseAlignment = source.shaderGroupBaseAlignment;
                maxShaderGroupStride = source.maxShaderGroupStride;
                maxRayDispatchInvocationCount = source.maxRayDispatchInvocationCount;
                minAccelerationStructureScratchAlignment = source.minAccelerationStructureScratchAlignment;
            }

            /**
             * Selects the maximum ray recursion depth.
             *
             * @param value positive maximum ray recursion depth
             * @return this builder
             */
            public Builder maxRayRecursionDepth(int value) {
                maxRayRecursionDepth = requirePositive(value, "maxRayRecursionDepth");
                selectedFields |= 1;
                return this;
            }

            /**
             * Selects the shader group handle size.
             *
             * @param value positive shader group handle size in bytes
             * @return this builder
             */
            public Builder shaderGroupHandleSize(int value) {
                shaderGroupHandleSize = requirePositive(value, "shaderGroupHandleSize");
                selectedFields |= 2;
                return this;
            }

            /**
             * Selects the shader group handle alignment.
             *
             * @param value positive shader group handle alignment in bytes
             * @return this builder
             */
            public Builder shaderGroupHandleAlignment(int value) {
                shaderGroupHandleAlignment = requirePositive(value, "shaderGroupHandleAlignment");
                selectedFields |= 4;
                return this;
            }

            /**
             * Selects the shader binding table base alignment.
             *
             * @param value positive shader binding table base alignment in bytes
             * @return this builder
             */
            public Builder shaderGroupBaseAlignment(int value) {
                shaderGroupBaseAlignment = requirePositive(value, "shaderGroupBaseAlignment");
                selectedFields |= 8;
                return this;
            }

            /**
             * Selects the maximum shader binding table record stride.
             *
             * @param value positive maximum shader group stride in bytes
             * @return this builder
             */
            public Builder maxShaderGroupStride(int value) {
                maxShaderGroupStride = requirePositive(value, "maxShaderGroupStride");
                selectedFields |= 16;
                return this;
            }

            /**
             * Selects the maximum invocations in one ray dispatch.
             *
             * @param value positive maximum ray dispatch invocation count
             * @return this builder
             */
            public Builder maxRayDispatchInvocationCount(long value) {
                maxRayDispatchInvocationCount = requirePositive(value, "maxRayDispatchInvocationCount");
                selectedFields |= 32;
                return this;
            }

            /**
             * Selects the acceleration-structure scratch address alignment.
             *
             * @param value positive acceleration-structure scratch alignment in bytes
             * @return this builder
             */
            public Builder minAccelerationStructureScratchAlignment(int value) {
                minAccelerationStructureScratchAlignment = requirePositive(
                        value, "minAccelerationStructureScratchAlignment"
                );
                selectedFields |= 64;
                return this;
            }

            /**
             * Validates and returns immutable hardware ray-tracing limits.
             *
             * @return complete validated limits
             */
            public RayTracingLimits build() {
                return new RayTracingLimits(this);
            }
        }
    }
}
