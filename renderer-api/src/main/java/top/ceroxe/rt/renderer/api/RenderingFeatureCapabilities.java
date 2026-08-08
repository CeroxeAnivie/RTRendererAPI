package top.ceroxe.rt.renderer.api;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable point-in-time result of optional feature negotiation and execution state.
 *
 * <p>Obtain this value through {@code renderer.extension(RenderingFeatureCapabilities.class)}.
 * Status distinguishes device/provider availability from actual activation; an implementation must
 * never publish {@link Status#ACTIVE} before its execution session owns the required resources.
 * Callers may query the extension again to observe evidence-driven state transitions.</p>
 */
public final class RenderingFeatureCapabilities {
    private final Map<Feature, Entry> features;
    private final Map<Technology, Entry> technologies;

    private RenderingFeatureCapabilities(Builder builder) {
        EnumMap<Feature, Entry> values = new EnumMap<>(Feature.class);
        for (Feature feature : Feature.values()) {
            values.put(feature, builder.features.getOrDefault(feature, Entry.disabled()));
        }
        features = Collections.unmodifiableMap(values);
        EnumMap<Technology, Entry> technologyValues = new EnumMap<>(Technology.class);
        for (Technology technology : Technology.values()) {
            technologyValues.put(
                    technology,
                    builder.technologies.getOrDefault(technology, Entry.disabled())
            );
        }
        technologies = Collections.unmodifiableMap(technologyValues);
    }

    /**
     * Starts an empty builder; omitted features become explicitly disabled.
     *
     * @return new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the negotiation entry for one feature.
     *
     * @param feature non-null feature identity
     * @return non-null immutable entry
     */
    public Entry feature(Feature feature) {
        return features.get(Objects.requireNonNull(feature, "feature"));
    }

    /**
     * Returns all feature entries in stable enum order.
     *
     * @return immutable complete feature map
     */
    public Map<Feature, Entry> features() {
        return features;
    }

    /**
     * Returns the execution entry for one concrete rendering technology.
     *
     * <p>This view is intentionally more precise than {@link #feature(Feature)}. For example,
     * temporal super resolution may be active while spatial upscaling remains available only as
     * a fallback. Applications must not infer these states from GPU names or implementation text.</p>
     *
     * @param technology non-null technology identity
     * @return non-null immutable entry
     */
    public Entry technology(Technology technology) {
        return technologies.get(Objects.requireNonNull(technology, "technology"));
    }

    /**
     * Returns every concrete technology entry in stable enum order.
     *
     * @return immutable complete technology map
     */
    public Map<Technology, Entry> technologies() {
        return technologies;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof RenderingFeatureCapabilities capabilities
                && features.equals(capabilities.features)
                && technologies.equals(capabilities.technologies);
    }

    @Override
    public int hashCode() {
        return Objects.hash(features, technologies);
    }

    @Override
    public String toString() {
        return "RenderingFeatureCapabilities[features=" + features
                + ", technologies=" + technologies + ']';
    }

    /** Stable optional feature identities exposed to applications. */
    public enum Feature {
        /** Temporal reconstruction, super resolution, or native-resolution reconstruction AA. */
        FRAME_RECONSTRUCTION,
        /** Presentation-time optical-flow frame generation or multi-frame generation. */
        FRAME_GENERATION,
        /** Latency pacing and frame-boundary markers independent from frame generation. */
        LOW_LATENCY,
        /** Ray-tracing signal denoising. */
        DENOISING,
        /** Driver-supported shader execution reordering. */
        SHADER_EXECUTION_REORDERING,
        /** Native GPU memory allocation and defragmentation optimization. */
        MEMORY_OPTIMIZATION
    }

    /**
     * Concrete, vendor-neutral technology identities suitable for diagnostics and HUDs.
     * Provider implementation names retain branding such as {@code nvidia.streamline.dlss}.
     */
    public enum Technology {
        /** Temporal reconstruction from a lower internal render extent to the requested output extent. */
        TEMPORAL_SUPER_RESOLUTION,
        /** Native-resolution temporal anti-aliasing without spatial upscaling. */
        NATIVE_TEMPORAL_ANTI_ALIASING,
        /** Non-temporal spatial upscaling, including use as a reconstruction fallback. */
        SPATIAL_UPSCALING,
        /** Presentation of one generated frame between renderer-produced frames. */
        FRAME_GENERATION,
        /** Presentation of two or more generated frames between renderer-produced frames. */
        MULTI_FRAME_GENERATION,
        /** Low-latency pacing and frame-marker integration, usable with native presentation. */
        LOW_LATENCY_MARKERS,
        /** Ray-traced signal denoising executed before final reconstruction and presentation. */
        RAY_TRACING_DENOISING,
        /** Driver-assisted reordering of divergent ray-tracing shader invocations. */
        SHADER_EXECUTION_REORDERING,
        /** Suballocation, compaction, and retirement optimization for acceleration-structure memory. */
        ACCELERATION_STRUCTURE_MEMORY_OPTIMIZATION
    }

    /** Exhaustive negotiation state for one feature. */
    public enum Status {
        /** Feature was not requested and owns no resources. */
        DISABLED,
        /** Current hardware, driver, provider, or rendering path does not support the request. */
        NOT_SUPPORTED,
        /** Provider/device support is present but no execution session is active. */
        AVAILABLE,
        /** A documented lower-tier implementation is selected but has not executed yet. */
        FALLBACK_PENDING,
        /** A documented lower-tier implementation is active. */
        FALLBACK,
        /** Requested implementation owns resources and participates in rendering. */
        ACTIVE,
        /** Support was requested but an initialization or execution error prevents use. */
        BLOCKED
    }

    /** Immutable status and implementation identity for one feature. */
    public static final class Entry {
        private static final Entry DISABLED = new Entry(Status.DISABLED, "none", "not requested");

        private final Status status;
        private final String implementation;
        private final String reason;

        private Entry(Status status, String implementation, String reason) {
            this.status = Objects.requireNonNull(status, "status");
            this.implementation = requireText(implementation, "implementation");
            this.reason = requireText(reason, "reason");
            if (status == Status.DISABLED && !"none".equals(this.implementation)) {
                throw new IllegalArgumentException("disabled feature implementation must be none");
            }
            if (status == Status.NOT_SUPPORTED && !"none".equals(this.implementation)) {
                throw new IllegalArgumentException("unsupported feature implementation must be none");
            }
            if ((status == Status.AVAILABLE || status == Status.FALLBACK_PENDING
                    || status == Status.FALLBACK || status == Status.ACTIVE)
                    && "none".equals(this.implementation)) {
                throw new IllegalArgumentException(
                        status + " feature entry must identify an executable implementation"
                );
            }
        }

        /**
         * Creates a validated feature entry.
         *
         * @param status exhaustive negotiation status
         * @param implementation stable implementation identity
         * @param reason human-readable capability explanation
         * @return immutable entry
         */
        public static Entry of(Status status, String implementation, String reason) {
            return new Entry(status, implementation, reason);
        }

        /**
         * Returns the canonical disabled entry.
         *
         * @return canonical disabled entry
         */
        public static Entry disabled() {
            return DISABLED;
        }

        /**
         * Returns the negotiation status.
         *
         * @return negotiation status
         */
        public Status status() {
            return status;
        }

        /**
         * Returns the stable implementation identity.
         *
         * @return stable implementation identity
         */
        public String implementation() {
            return implementation;
        }

        /**
         * Returns a non-blank human-readable explanation.
         *
         * @return human-readable explanation
         */
        public String reason() {
            return reason;
        }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof Entry entry
                    && status == entry.status
                    && implementation.equals(entry.implementation)
                    && reason.equals(entry.reason);
        }

        @Override
        public int hashCode() {
            return Objects.hash(status, implementation, reason);
        }

        @Override
        public String toString() {
            return "Entry[status=" + status + ", implementation=" + implementation
                    + ", reason=" + reason + ']';
        }
    }

    /** Single-thread-confined builder; omitted features become explicitly disabled. */
    public static final class Builder {
        private final EnumMap<Feature, Entry> features = new EnumMap<>(Feature.class);
        private final EnumMap<Technology, Entry> technologies = new EnumMap<>(Technology.class);

        private Builder() {
        }

        /**
         * Replaces one feature entry.
         *
         * @param feature non-null feature identity
         * @param entry non-null negotiation entry
         * @return this builder
         */
        public Builder feature(Feature feature, Entry entry) {
            features.put(
                    Objects.requireNonNull(feature, "feature"),
                    Objects.requireNonNull(entry, "entry")
            );
            return this;
        }

        /**
         * Replaces one concrete technology entry.
         *
         * @param technology non-null technology identity
         * @param entry non-null execution entry
         * @return this builder
         */
        public Builder technology(Technology technology, Entry entry) {
            technologies.put(
                    Objects.requireNonNull(technology, "technology"),
                    Objects.requireNonNull(entry, "entry")
            );
            return this;
        }

        /**
         * Creates a complete immutable capability result.
         *
         * @return immutable capabilities
         */
        public RenderingFeatureCapabilities build() {
            return new RenderingFeatureCapabilities(this);
        }
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) throw new IllegalArgumentException(label + " must not be blank");
        return checked;
    }
}
