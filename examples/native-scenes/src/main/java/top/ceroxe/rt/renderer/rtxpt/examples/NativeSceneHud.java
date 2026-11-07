package top.ceroxe.rt.renderer.rtxpt.examples;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import top.ceroxe.rt.renderer.api.Renderer;
import top.ceroxe.rt.renderer.rtxpt.RtxptAccess;

/** The consumer's original text HUD and Scene selector; unknown observations stay explicit. */
final class NativeSceneHud {
    // StreamlineInterface::DLSSMode::eDLAA in the locked Donut source; not inferred from dimensions.
    private static final int DLSS_DLAA_MODE = 6;
    private NativeSceneHud() { }

    static String title(boolean balls) {
        return balls ? "RTRendererAPI - RTXPT Hex Ball" : "RTRendererAPI - Medium Demo | RTXPT Only";
    }

    static List<String> choices(boolean balls) {
        return balls ? Arrays.stream(BouncingBallScene.BallMaterial.values()).map(value -> value.label).toList()
                : OpticalMediumScene.LABELS;
    }

    static int selection(boolean balls, BouncingBallScene.BallMaterial material, String preset) {
        return balls ? material.ordinal() : OpticalMediumScene.PRESETS.indexOf(preset);
    }

    static String command(boolean balls, int selection) {
        var commands = balls ? List.of("glass", "opaque", "metal") : OpticalMediumScene.PRESETS;
        return selection >= 0 && selection < commands.size() ? commands.get(selection) : null;
    }

    static String text(boolean balls, BouncingBallScene.BallMaterial material, String preset,
            Double nativeSubmitFps, RtxptAccess.WindowState window, RtxptAccess.PresentationState presentation,
            RtxptAccess.ExposureState exposure, RtxptAccess.Denoiser denoiser, RtxptAccess.RenderingState rendering,
            String upstreamFrameStatistics) {
        String version = Renderer.class.getPackage().getImplementationVersion();
        var text = new StringBuilder("RTRendererAPI");
        if (version != null && !version.isBlank()) text.append(' ').append(version);
        text.append('\n').append(window == null ? "OUTPUT NOT MEASURED"
                : String.format(Locale.ROOT, "%dx%d", window.displayWidth(), window.displayHeight()));
        text.append("\nRTXPT: ").append(upstreamFrameStatistics == null || upstreamFrameStatistics.isBlank()
                        ? "WARMING UP" : upstreamFrameStatistics)
                .append("\nAPI COMPLETED SUBMIT FPS: ").append(rate(nativeSubmitFps))
                .append("\nGPU TIME: native Tracy capture; not a HUD counter")
                .append("\nDLSS SR: ").append(reconstruction(rendering, false))
                .append("\nDLAA: ").append(reconstruction(rendering, true))
                .append("\nNIS: NOT SUPPORTED")
                .append("\nDLSS FG: ").append(generation(presentation, false))
                .append("\nDLSS MFG: ").append(generation(presentation, true))
                .append("\nREFLEX/PCL: ").append(presentation == null ? "N/A"
                        : !presentation.reflexSupported() ? "NOT SUPPORTED"
                        : presentation.activeReflexMode() != 0 ? "ACTIVE" : "DISABLED")
                .append("\nNRD: ").append(rendering == null ? denoiser == null ? "N/A" : denoiser.name() + " selected"
                        : rendering.standaloneDenoiser() ? rendering.denoiser().name()
                                + " | initialized " + rendering.denoiserIntegrations() : "DISABLED")
                .append("\nSER: ").append(rendering == null ? "N/A"
                        : enabled(rendering.dxHitObjectPath() && rendering.dxReorderThreads()
                                || rendering.nvapiHitObjectPath() && rendering.nvapiReorderThreads()))
                .append("\nRTXMU: NOT SUPPORTED")
                .append("\nBACKEND RTXPT 1.9.0 | D3D12 | ").append(window == null ? "N/A"
                        : window.fullscreen() ? "FULLSCREEN" : window.maximized() ? "MAXIMIZED" : "WINDOWED");
        if (window == null) text.append("\nINPUT / OUTPUT N/A");
        else text.append(String.format(Locale.ROOT,
                "%nINPUT %dx%d -> OUTPUT %dx%d",
                window.renderWidth(), window.renderHeight(), window.displayWidth(), window.displayHeight()));
        text.append(rendering == null ? "\nSHaRC N/A | ReSTIR DI/GI N/A"
                : "\nSHaRC " + (rendering.radianceCacheMode() == 0 ? "DISABLED" : "mode " + rendering.radianceCacheMode())
                        + " | ReSTIR DI/GI " + enabled(rendering.restirDi()) + "/" + enabled(rendering.restirGi()));
        if (rendering != null) text.append("\nNATIVE SPP ").append(rendering.samplesPerPixel())
                .append(" | SUBSAMPLING ").append(rendering.subSamplingMode())
                .append(rendering.externalLighting() ? " | EXTERNAL LIGHTING"
                    : rendering.analyticBeauty() ? " | ANALYTIC / 4 INTERFACES" : " | NATIVE BEAUTY");
        text.append("\nEXPOSURE ").append(exposure == null ? "N/A"
                : String.format(Locale.ROOT, "%s | offset %.2f EV",
                        exposure.automatic() ? "AUTO" : "MANUAL", exposure.compensation()));
        if (balls) text.append("\nBALL MATERIAL: ").append(material.label)
                .append("\nWindow selector: change ball material");
        else text.append("\nSCENE ").append(preset.toUpperCase(Locale.ROOT)).append(" | ")
                .append(OpticalMediumScene.description(preset))
                .append("\n[1] Mixed [2] Glass [3] Nested [4] Absorption [5] Rough [6] IOR [Esc] Quit");
        return text.append("\nRTXPT/title share stats; '*' = FG estimate, not measured presents").toString();
    }

    private static String generation(RtxptAccess.PresentationState state, boolean multiple) {
        if (state == null) return "N/A";
        if (!state.frameGenerationSupported()) return "NOT SUPPORTED";
        if (state.activeFrameGenerationMode() == 0 || multiple && state.generatedFrames() <= 1) return "DISABLED";
        return "ACTIVE mode | requested " + (state.generatedFrames()+1) + "x";
    }

    private static String reconstruction(RtxptAccess.RenderingState state, boolean dlaa) {
        if (state == null) return "N/A";
        boolean dlss = state.realtime() && (state.reconstruction() == RtxptAccess.Reconstruction.DLSS
                || state.reconstruction() == RtxptAccess.Reconstruction.DLSS_RR) && state.dlssQuality() > 0;
        boolean matches = dlss && ((state.dlssQuality() == DLSS_DLAA_MODE) == dlaa);
        return enabled(matches) + (matches ? " | " + state.reconstruction() + " quality " + state.dlssQuality() : "");
    }

    private static String enabled(boolean value) { return value ? "ENABLED" : "DISABLED"; }
    private static String rate(Double value) {
        return value == null || !Double.isFinite(value) || value <= 0 ? "N/A"
                : String.format(Locale.ROOT, "%.1f", value);
    }
}
