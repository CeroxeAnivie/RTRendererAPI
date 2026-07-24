package top.ceroxe.mcvulkanrt.renderer;

import top.ceroxe.mcvulkanrt.renderer.diagnostics.RtTakeoverTimeline;

/** Shared read-only performance-mode policy; owns no renderer state. */
final class RendererPerformanceMode {
    static final String HIGH_PERFORMANCE_DIAGNOSTICS_PROPERTY =
            "mcvulkanrt.renderer.highPerformanceDiagnostics";

    private RendererPerformanceMode() {
    }

    static boolean highPerformanceDiagnosticsEnabled() {
        return Boolean.getBoolean(HIGH_PERFORMANCE_DIAGNOSTICS_PROPERTY)
                || RtTakeoverTimeline.edgeLoggingEnabled();
    }
}
