package top.ceroxe.rt.renderer;

import java.util.Objects;

/**
 * Immutable diagnostic dependency bundle injected once at the renderer composition root.
 *
 * @param edges     lifecycle edge sink
 * @param causality frame-causality sink
 * @param builds    acceleration-structure build telemetry sink
 * @param materials material telemetry sink
 * @param stalls    stall telemetry sink
 */
public record RendererRtDiagnostics(
        RtEdgeSink edges,
        RtCausalitySink causality,
        RtBuildTelemetrySink builds,
        RtMaterialTelemetrySink materials,
        RtStallTelemetrySink stalls
) {
    private static final RendererRtDiagnostics NOOP = new RendererRtDiagnostics(
            RtEdgeSink.NOOP,
            RtCausalitySink.NOOP,
            RtBuildTelemetrySink.NOOP,
            RtMaterialTelemetrySink.NOOP,
            RtStallTelemetrySink.NOOP
    );

    /**
     * Requires all diagnostic channels to be explicit non-null sinks.
     */
    public RendererRtDiagnostics {
        Objects.requireNonNull(edges, "edges");
        Objects.requireNonNull(causality, "causality");
        Objects.requireNonNull(builds, "builds");
        Objects.requireNonNull(materials, "materials");
        Objects.requireNonNull(stalls, "stalls");
    }

    /**
     * Returns the shared diagnostics bundle that discards every event.
     *
     * @return immutable no-op diagnostics bundle
     */
    public static RendererRtDiagnostics noop() {
        return NOOP;
    }
}
