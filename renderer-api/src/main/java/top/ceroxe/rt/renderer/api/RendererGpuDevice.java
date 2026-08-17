package top.ceroxe.rt.renderer.api;

import java.util.Objects;

/**
 * Immutable, backend-produced description of one physical GPU that satisfies the renderer's
 * hardware ray-tracing admission contract.
 *
 * <p>The pair {@code (backendId, stableId)} is the selection identity. Callers must pass the
 * object returned by {@link RendererBootstrap#availableGpuDevices()} back through
 * {@link RendererConfig.Builder#gpuDevice(RendererGpuDevice)}; names and enumeration
 * indices are deliberately not selection keys because both can change or collide.</p>
 *
 * <p>Providers construct snapshots through {@link #builder()}. The semantic setters prevent the
 * three adjacent strings and the device/vendor identifiers from being exchanged positionally.</p>
 */
public final class RendererGpuDevice {
    private final String backendId;
    private final String stableId;
    private final String name;
    private final int vendorId;
    private final int deviceId;
    private final Type type;
    private final ApiVersion apiVersion;
    private final HardwareCapabilities hardwareCapabilities;

    private RendererGpuDevice(Builder builder) {
        backendId = requireText(requireSelected(builder.backendId, "backendId"), "backendId");
        stableId = requireText(requireSelected(builder.stableId, "stableId"), "stableId");
        name = requireText(requireSelected(builder.name, "name"), "name");
        if (!builder.vendorIdSelected || !builder.deviceIdSelected) {
            throw new IllegalStateException("vendorId and deviceId must be selected before build");
        }
        vendorId = builder.vendorId;
        deviceId = builder.deviceId;
        type = requireSelected(builder.type, "type");
        apiVersion = requireSelected(builder.apiVersion, "apiVersion");
        hardwareCapabilities = requireSelected(builder.hardwareCapabilities, "hardwareCapabilities");
        if (hardwareCapabilities.probeState() != HardwareCapabilities.ProbeState.COMPLETE
                || !hardwareCapabilities.supports(HardwareCapabilities.Feature.HARDWARE_RAY_TRACING)) {
            throw new IllegalArgumentException("renderer devices must support hardware ray tracing");
        }
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
        if (value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(name + " must be non-blank and normalized");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException(name + " must not contain control characters");
            }
        }
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
     * Returns the complete vendor-neutral physical-device capability snapshot.
     *
     * @return immutable, successfully probed hardware facts
     */
    public HardwareCapabilities hardwareCapabilities() {
        return hardwareCapabilities;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RendererGpuDevice device)) return false;
        return vendorId == device.vendorId
                && deviceId == device.deviceId
                && backendId.equals(device.backendId)
                && stableId.equals(device.stableId)
                && name.equals(device.name)
                && type == device.type
                && apiVersion.equals(device.apiVersion)
                && hardwareCapabilities.equals(device.hardwareCapabilities);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                backendId, stableId, name, vendorId, deviceId, type, apiVersion,
                hardwareCapabilities
        );
    }

    @Override
    public String toString() {
        return "RendererGpuDevice[backendId=" + backendId
                + ", stableId=" + stableId
                + ", name=" + name
                + ", vendorId=" + vendorId
                + ", deviceId=" + deviceId
                + ", type=" + type
                + ", apiVersion=" + apiVersion
                + ", hardwareCapabilities=" + hardwareCapabilities + ']';
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
     * Single-thread-confined builder for one immutable physical-device snapshot.
     */
    public static final class Builder {
        private String backendId;
        private String stableId;
        private String name;
        private int vendorId;
        private int deviceId;
        private boolean vendorIdSelected;
        private boolean deviceIdSelected;
        private Type type;
        private ApiVersion apiVersion;
        private HardwareCapabilities hardwareCapabilities;

        private Builder() {
        }

        private Builder(RendererGpuDevice source) {
            backendId = source.backendId;
            stableId = source.stableId;
            name = source.name;
            vendorId = source.vendorId;
            deviceId = source.deviceId;
            vendorIdSelected = true;
            deviceIdSelected = true;
            type = source.type;
            apiVersion = source.apiVersion;
            hardwareCapabilities = source.hardwareCapabilities;
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
            vendorIdSelected = true;
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
            deviceIdSelected = true;
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
         * Selects the complete vendor-neutral hardware capability snapshot.
         *
         * @param value immutable hardware capabilities
         * @return this builder
         */
        public Builder hardwareCapabilities(HardwareCapabilities value) {
            hardwareCapabilities = Objects.requireNonNull(value, "hardwareCapabilities");
            return this;
        }

        /**
         * Validates and returns an independent immutable device snapshot.
         *
         * @return validated device snapshot
         */
        public RendererGpuDevice build() {
            return new RendererGpuDevice(this);
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

}
