package top.ceroxe.mcvulkanrt.renderer;

import java.util.Objects;

/** Immutable diagnostic dependency bundle injected once at the renderer composition root. */
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

    public RendererRtDiagnostics {
        Objects.requireNonNull(edges, "edges");
        Objects.requireNonNull(causality, "causality");
        Objects.requireNonNull(builds, "builds");
        Objects.requireNonNull(materials, "materials");
        Objects.requireNonNull(stalls, "stalls");
    }

    public static RendererRtDiagnostics noop() {
        return NOOP;
    }
}
