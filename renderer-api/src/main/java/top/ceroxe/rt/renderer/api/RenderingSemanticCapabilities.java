package top.ceroxe.rt.renderer.api;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable execution capabilities of the generic rendering-semantics path.
 *
 * <p>Every feature is reported independently. A compatible device, accepted descriptor, or
 * stored command never upgrades a feature to {@link Status#EXECUTABLE}; only a backend path that
 * can validate, record, submit, and report completion may advertise that status.</p>
 */
public final class RenderingSemanticCapabilities {
    /** Capability families exposed by the general command API. */
    public enum Feature {
        VERSIONED_BUFFERS,
        VERSIONED_TEXTURES,
        TEXTURE_VIEWS,
        SAMPLERS,
        SPIRV_SHADER_MODULES,
        GRAPHICS_PIPELINES,
        COMPUTE_PIPELINES,
        RAY_TRACING_PIPELINES,
        ACCELERATION_STRUCTURE_BUILDS,
        RAY_TRACING_DISPATCH,
        FRAME_COMPOSITION,
        FRAME_PRESENTATION_EVIDENCE,
        RENDER_PASSES,
        DIRECT_DRAW,
        INDEXED_DRAW,
        INSTANCED_DRAW,
        MULTI_DRAW,
        INDIRECT_DRAW,
        BUFFER_UPLOAD,
        TEXTURE_UPLOAD,
        BUFFER_COPY,
        TEXTURE_COPY,
        BUFFER_TO_TEXTURE_COPY,
        TEXTURE_TO_BUFFER_COPY,
        COLOR_CLEAR,
        DEPTH_STENCIL_CLEAR,
        BUFFER_BARRIERS,
        TEXTURE_BARRIERS,
        RESOURCE_COPY,
        EXPLICIT_BARRIERS,
        EXTERNAL_FRAME_CONSUMER,
        COMBINED_WORKLOADS
    }

    /** Whether a semantic feature has a complete executable backend path. */
    public enum Status {
        UNSUPPORTED,
        EXECUTABLE
    }

    /**
     * One independently justified capability decision.
     *
     * @param status explicit executable status
     * @param detail non-blank diagnostic reason or implementation description
     */
    public record Entry(Status status, String detail) {
        /** Validates a capability entry. */
        public Entry {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(detail, "detail");
            if (detail.isBlank()) throw new IllegalArgumentException("capability detail must not be blank");
        }

        /** @return whether a complete backend path is executable */
        public boolean executable() { return status == Status.EXECUTABLE; }
    }

    private final Map<Feature, Entry> entries;

    private RenderingSemanticCapabilities(Map<Feature, Entry> entries) {
        EnumMap<Feature, Entry> complete = new EnumMap<>(Feature.class);
        for (Feature feature : Feature.values()) {
            Entry entry = entries.get(feature);
            complete.put(feature, entry != null ? entry : new Entry(Status.UNSUPPORTED, "not implemented by this backend"));
        }
        this.entries = Collections.unmodifiableMap(complete);
    }

    /** @return a builder whose features all default to explicit unsupported status */
    public static Builder builder() { return new Builder(); }

    /** @return a complete all-unsupported capability snapshot */
    public static RenderingSemanticCapabilities unsupported() { return builder().build(); }

    /** @return immutable complete capability map */
    public Map<Feature, Entry> entries() { return entries; }

    /** @return the non-null entry for one feature */
    public Entry feature(Feature feature) { return entries.get(Objects.requireNonNull(feature, "feature")); }

    /** Single-thread-confined capability builder. */
    public static final class Builder {
        private final EnumMap<Feature, Entry> entries = new EnumMap<>(Feature.class);

        private Builder() { }

        /**
         * Sets one independently justified feature decision.
         *
         * @param feature non-null feature
         * @param entry non-null decision
         * @return this builder
         */
        public Builder feature(Feature feature, Entry entry) {
            entries.put(Objects.requireNonNull(feature, "feature"), Objects.requireNonNull(entry, "entry"));
            return this;
        }

        /** @return immutable complete capability snapshot */
        public RenderingSemanticCapabilities build() { return new RenderingSemanticCapabilities(entries); }
    }
}
