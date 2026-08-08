package top.ceroxe.rt.renderer.spi;

import top.ceroxe.rt.renderer.api.RayTracingGpuDevice;
import top.ceroxe.rt.renderer.api.RayTracingRenderer;
import top.ceroxe.rt.renderer.api.RayTracingRendererConfig;

import java.util.List;
import java.util.Objects;

/**
 * Service-provider boundary implemented by a renderer backend module.
 */
public interface RayTracingBackendProvider {
    /**
     * Major SPI contract version required by {@code renderer-api}.
     */
    int API_MAJOR = 1;

    /** Highest minor SPI contract this host can consume without a provider upgrade. */
    int API_MINOR = 0;

    /**
     * Returns immutable provider identity and compatibility metadata.
     *
     * @return non-null provider descriptor
     */
    Descriptor descriptor();

    /**
     * Returns current hardware-RT-capable physical devices owned by this provider.
     *
     * @return immutable device list; empty when discovery is unsupported or no device qualifies
     */
    List<RayTracingGpuDevice> availableGpuDevices();

    /**
     * Performs a read-only configuration-aware probe without retaining native resources.
     *
     * @param configuration immutable requested renderer policy
     * @return non-null compatibility result with a diagnostic reason
     */
    ProbeResult probe(RayTracingRendererConfig configuration);

    /**
     * Opens one independently owned renderer instance.
     *
     * @param configuration immutable renderer configuration accepted by {@link #probe}
     * @return newly owned renderer instance
     */
    RayTracingRenderer open(RayTracingRendererConfig configuration);

    /**
     * Backend compatibility classification used during deterministic provider selection.
     */
    enum Compatibility {
        /**
         * The provider can open a renderer with the requested configuration.
         */
        COMPATIBLE,
        /**
         * The environment lacks a required runtime capability.
         */
        UNSUPPORTED,
        /**
         * The provider or configuration violates a required compatibility contract.
         */
        INCOMPATIBLE
    }

    /**
     * Immutable provider identity and SPI compatibility descriptor.
     *
     * <p>Backends use {@link #builder(String)} rather than a positional constructor so priority
     * and independently-versioned SPI metadata cannot be silently exchanged.</p>
     */
    final class Descriptor {
        private final String id;
        private final int priority;
        private final int apiMajor;
        private final int apiMinor;

        private Descriptor(Builder builder) {
            id = requireId(builder.id);
            priority = builder.priority;
            apiMajor = builder.apiMajor;
            apiMinor = builder.apiMinor;
            if (apiMajor <= 0 || apiMinor < 0) {
                throw new IllegalArgumentException("backend API major must be positive and minor must be non-negative");
            }
        }

        /**
         * Starts a provider descriptor for one stable backend identity.
         *
         * @param providerId stable non-blank provider identifier
         * @return new single-thread-confined builder using the current SPI version by default
         */
        public static Builder builder(String providerId) {
            return new Builder(providerId);
        }

        private static String requireId(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("backend provider id must not be blank");
            }
            return value;
        }

        /**
         * Starts an independent builder initialized from this descriptor.
         *
         * @return builder containing every current descriptor property
         */
        public Builder toBuilder() {
            return new Builder(this);
        }

        /**
         * Returns the stable provider identifier.
         *
         * @return non-blank identifier
         */
        public String id() {
            return id;
        }

        /**
         * Returns provider selection priority.
         *
         * @return higher values are attempted first
         */
        public int priority() {
            return priority;
        }

        /**
         * Returns implemented SPI major version.
         *
         * @return positive major version
         */
        public int apiMajor() {
            return apiMajor;
        }

        /**
         * Returns implemented SPI minor version.
         *
         * @return non-negative minor version
         */
        public int apiMinor() {
            return apiMinor;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Descriptor descriptor)) return false;
            return priority == descriptor.priority && apiMajor == descriptor.apiMajor
                    && apiMinor == descriptor.apiMinor && id.equals(descriptor.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, priority, apiMajor, apiMinor);
        }

        @Override
        public String toString() {
            return "Descriptor[id=" + id + ", priority=" + priority + ", apiMajor=" + apiMajor
                    + ", apiMinor=" + apiMinor + ']';
        }

        /**
         * Single-thread-confined semantic builder for one SPI provider descriptor.
         */
        public static final class Builder {
            private String id;
            private int priority;
            private int apiMajor = API_MAJOR;
            private int apiMinor;

            private Builder(String providerId) {
                id = requireId(providerId);
            }

            private Builder(Descriptor source) {
                id = source.id;
                priority = source.priority;
                apiMajor = source.apiMajor;
                apiMinor = source.apiMinor;
            }

            /**
             * Selects provider selection priority.
             *
             * @param value higher values are attempted first
             * @return this builder
             */
            public Builder priority(int value) {
                priority = value;
                return this;
            }

            /**
             * Selects implemented SPI major version.
             *
             * @param value positive major version
             * @return this builder
             */
            public Builder apiMajor(int value) {
                apiMajor = value;
                return this;
            }

            /**
             * Selects implemented SPI minor version.
             *
             * @param value non-negative minor version
             * @return this builder
             */
            public Builder apiMinor(int value) {
                apiMinor = value;
                return this;
            }

            /**
             * Validates and returns an immutable provider descriptor.
             *
             * @return validated descriptor
             */
            public Descriptor build() {
                return new Descriptor(this);
            }
        }
    }

    /**
     * Immutable result of a non-owning backend compatibility probe.
     *
     * @param compatibility compatibility classification
     * @param reason        non-blank human-readable diagnostic reason
     */
    record ProbeResult(Compatibility compatibility, String reason) {
        /**
         * Validates and creates a probe result.
         *
         * @param compatibility compatibility classification
         * @param reason        non-blank human-readable diagnostic reason
         * @throws IllegalArgumentException if {@code reason} is blank
         * @throws NullPointerException     if either component is {@code null}
         */
        public ProbeResult {
            compatibility = Objects.requireNonNull(compatibility, "compatibility");
            reason = Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("backend probe reason must not be blank");
            }
        }

        /**
         * Creates a compatible result.
         *
         * @param reason non-blank compatibility explanation
         * @return compatible probe result
         */
        public static ProbeResult compatible(String reason) {
            return new ProbeResult(Compatibility.COMPATIBLE, reason);
        }

        /**
         * Creates an unsupported result for a missing optional capability or environment.
         *
         * @param reason non-blank unsupported explanation
         * @return unsupported probe result
         */
        public static ProbeResult unsupported(String reason) {
            return new ProbeResult(Compatibility.UNSUPPORTED, reason);
        }

        /**
         * Creates an incompatible result for a contract mismatch.
         *
         * @param reason non-blank incompatibility explanation
         * @return incompatible probe result
         */
        public static ProbeResult incompatible(String reason) {
            return new ProbeResult(Compatibility.INCOMPATIBLE, reason);
        }
    }
}
