package top.ceroxe.rt.renderer;

import top.ceroxe.rt.renderer.diagnostics.RtTakeoverTimeline;

/** Shared read-only performance-mode policy; owns no renderer state. */
final class RendererPerformanceMode {
    static final String HIGH_PERFORMANCE_DIAGNOSTICS_PROPERTY =
            "top.ceroxe.rt.renderer.highPerformanceDiagnostics";

    private RendererPerformanceMode() {
    }

    static boolean highPerformanceDiagnosticsEnabled() {
        return Boolean.getBoolean(HIGH_PERFORMANCE_DIAGNOSTICS_PROPERTY)
                || RtTakeoverTimeline.edgeLoggingEnabled();
    }
}
