package demo;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import top.ceroxe.rt.renderer.api.FrameGenerationEvidence;
import top.ceroxe.rt.renderer.api.RayTracingRenderer;
import top.ceroxe.rt.renderer.api.RendererDiagnostics;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Entry;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Status;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Technology;

/** Projects public runtime capabilities into one immutable HUD snapshot. */
final class DemoTechnologyHud {
    private static final int MAX_BLOCKED_REASON_LENGTH = 96;
    private static final List<TechnologyLine> TECHNOLOGIES = List.of(
            new TechnologyLine("DLSS SR", Technology.TEMPORAL_SUPER_RESOLUTION),
            new TechnologyLine("DLAA", Technology.NATIVE_TEMPORAL_ANTI_ALIASING),
            new TechnologyLine("NIS", Technology.SPATIAL_UPSCALING),
            new TechnologyLine("DLSS FG", Technology.FRAME_GENERATION),
            new TechnologyLine("DLSS MFG", Technology.MULTI_FRAME_GENERATION),
            new TechnologyLine("REFLEX/PCL", Technology.LOW_LATENCY_MARKERS),
            new TechnologyLine("NRD", Technology.RAY_TRACING_DENOISING),
            new TechnologyLine("SER", Technology.SHADER_EXECUTION_REORDERING),
            new TechnologyLine("RTXMU", Technology.ACCELERATION_STRUCTURE_MEMORY_OPTIMIZATION)
    );

    private DemoTechnologyHud() {
    }

    static Snapshot capture(RayTracingRenderer renderer, DemoConfig config, RenderStats stats) {
        return capture(renderer, config, stats, renderer.diagnostics());
    }

    static Snapshot capture(
            RayTracingRenderer renderer,
            DemoConfig config,
            RenderStats stats,
            RendererDiagnostics diagnostics
    ) {
        Objects.requireNonNull(renderer, "renderer");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(stats, "stats");
        FrameGenerationEvidence generation = Objects.requireNonNull(
                diagnostics, "diagnostics"
        ).frameGenerationEvidence();
        Optional<RenderingFeatureCapabilities> capabilities =
                renderer.extension(RenderingFeatureCapabilities.class);
        return snapshot(
                capabilities,
                performanceLine(config, stats, generation),
                stats.framesPerSecond(),
                generation
        );
    }

    static double presentTotalFps(RayTracingRenderer renderer, RenderStats stats) {
        Objects.requireNonNull(renderer, "renderer");
        Objects.requireNonNull(stats, "stats");
        double nativeFps = stats.framesPerSecond();
        return presentTotalFps(renderer.diagnostics().frameGenerationEvidence(), nativeFps);
    }

    static double presentTotalFps(
            FrameGenerationEvidence generation,
            double nativeFps
    ) {
        Objects.requireNonNull(generation, "generation");
        if (!Double.isFinite(nativeFps) || nativeFps < 0.0) {
            throw new IllegalArgumentException("nativeFps must be finite and non-negative");
        }
        return nativeFps + generatedFps(generation, nativeFps);
    }

    static Snapshot snapshot(
            Optional<RenderingFeatureCapabilities> capabilities,
            String performanceLine
    ) {
        return snapshot(
                capabilities,
                performanceLine,
                Double.NaN,
                FrameGenerationEvidence.unavailable()
        );
    }

    static Snapshot snapshot(
            Optional<RenderingFeatureCapabilities> capabilities,
            String performanceLine,
            FrameGenerationEvidence generation
    ) {
        return snapshot(capabilities, performanceLine, Double.NaN, generation);
    }

    private static Snapshot snapshot(
            Optional<RenderingFeatureCapabilities> capabilities,
            String performanceLine,
            double presentFps,
            FrameGenerationEvidence generation
    ) {
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(generation, "generation");
        StringBuilder text = new StringBuilder(requireText(performanceLine, "performanceLine"));
        for (TechnologyLine technology : TECHNOLOGIES) {
            text.append('\n').append(technology.label()).append(": ");
            if (capabilities.isEmpty()) {
                text.append(Status.NOT_SUPPORTED);
                continue;
            }
            Entry entry = capabilities.orElseThrow().technology(technology.technology());
            text.append(entry.status());
            if (technology.technology() == Technology.FRAME_GENERATION
                    || technology.technology() == Technology.MULTI_FRAME_GENERATION) {
                appendGenerationCadence(text, technology.technology(), generation, presentFps);
            }
            if (entry.status() == Status.BLOCKED) {
                text.append(" (").append(boundReason(entry.reason())).append(')');
            }
        }
        return new Snapshot(text.toString());
    }

    private static void appendGenerationCadence(
            StringBuilder text,
            Technology technology,
            FrameGenerationEvidence generation,
            double presentFps
    ) {
        if (!generation.reported()) return;
        boolean multiFrameRequested = generation.requestedGeneratedFramesPerNativeFrame() > 1;
        if (multiFrameRequested != (technology == Technology.MULTI_FRAME_GENERATION)) return;
        long generated = generation.generatedFramesActuallyPresented();
        long presents = generation.proxyPresentCalls();
        double generatedFps = presents == 0L || !Double.isFinite(presentFps)
                ? Double.NaN : generated * presentFps / presents;
        double effectiveMultiplier = presents == 0L
                ? Double.NaN : generation.effectivePresentationMultiplier();
        text.append(" | ")
                .append(generation.configuredPresentationMultiplier())
                .append("x | generated ");
        if (Double.isFinite(generatedFps)) {
            text.append(String.format(Locale.ROOT, "%.1f", generatedFps));
        } else {
            text.append("N/A");
        }
        text.append(" FPS | effective ");
        if (Double.isFinite(effectiveMultiplier)) {
            text.append(String.format(Locale.ROOT, "%.2f", effectiveMultiplier));
        } else {
            text.append("N/A");
        }
        text.append('x');
    }

    private static String performanceLine(
            DemoConfig config,
            RenderStats stats,
            FrameGenerationEvidence generation
    ) {
        double nativeFps = stats.framesPerSecond();
        double generatedFps = generatedFps(generation, nativeFps);
        return String.format(
                Locale.ROOT,
                "%dx%d | %d SPP | FPS %.1f (native %.1f | gen %.1f)%n"
                        + "GPU FRAME CAPACITY %.1f FPS (%.2f ms)",
                config.width(),
                config.height(),
                config.samplesPerPixel(),
                nativeFps + generatedFps,
                nativeFps,
                generatedFps,
                stats.gpuCapacityFramesPerSecond(),
                stats.averageGpuMillis()
        );
    }

    private static double generatedFps(
            FrameGenerationEvidence generation,
            double nativeFps
    ) {
        if (!generation.active() || !Double.isFinite(nativeFps)) return 0.0;
        long generated = generation.generatedFramesActuallyPresented();
        long presents = generation.proxyPresentCalls();
        return presents == 0L ? 0.0 : generated * nativeFps / presents;
    }

    private static String boundReason(String reason) {
        String normalized = requireText(reason, "reason")
                .replace('\r', ' ')
                .replace('\n', ' ');
        if (normalized.length() <= MAX_BLOCKED_REASON_LENGTH) return normalized;
        return normalized.substring(0, MAX_BLOCKED_REASON_LENGTH - 3) + "...";
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) throw new IllegalArgumentException(label + " must not be blank");
        return checked;
    }

    record Snapshot(String text) {
        Snapshot {
            text = requireText(text, "text");
        }
    }

    private record TechnologyLine(String label, Technology technology) {
    }
}
