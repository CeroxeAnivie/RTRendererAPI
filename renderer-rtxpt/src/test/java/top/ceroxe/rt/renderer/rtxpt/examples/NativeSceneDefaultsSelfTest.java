package top.ceroxe.rt.renderer.rtxpt.examples;

import top.ceroxe.rt.renderer.api.*;

/** Checks consumer demo defaults without creating a renderer or opening a window. */
public final class NativeSceneDefaultsSelfTest {
    public static void main(String[] args) {
        String disabled = System.getProperty("demo.disable-fg"), multiplier = System.getProperty("demo.fg-multiplier");
        try {
            System.clearProperty("demo.disable-fg"); System.clearProperty("demo.fg-multiplier");
            var balls = NativeSceneDemo.Config.parse(new String[0]);
            var optics = NativeSceneDemo.Config.parse(new String[]{"--scene", "mixed"});
            for (var config : new NativeSceneDemo.Config[]{balls, optics}) {
                var renderer = NativeSceneDemo.rendererConfig(config);
                check(renderer.frameGeneration().mode() == FrameGenerationOptions.Mode.FRAME_GENERATION, "2x FG mode");
                check(renderer.frameGeneration().multiplier() == FrameGenerationOptions.Multiplier.TWO_X, "2x FG multiplier");
                check(renderer.frameGeneration().preference() == RendererFeaturePreference.PREFERRED, "capability gated FG");
                check(renderer.frameReconstruction().quality() == FrameReconstructionOptions.Quality.PERFORMANCE, "SR Performance");
                check(renderer.pathTracing().samplesPerPixel() == 1 && renderer.pathTracing().maxBounces() == 12
                        && renderer.pathTracing().diffuseBounces() == 2, "path budget");
                check(config.frames() == 0 && config.seconds() == 0 && !config.hidden(), "interactive lifetime");
            }
            check(optics.width() == 1920 && optics.height() == 1080, "medium window extent");
            int width = 1920, height = 1080;
            if (!java.awt.GraphicsEnvironment.isHeadless()) {
                var display = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDisplayMode();
                width = Math.min(1920, Math.max(1, display.getWidth() - 160));
                height = Math.min(1080, Math.max(1, display.getHeight() - 160));
            }
            check(balls.width() == width && balls.height() == height, "ball legacy window extent");
            var ballRenderer = NativeSceneDemo.rendererConfig(balls);
            var mediumRenderer = NativeSceneDemo.rendererConfig(optics);
            check(ballRenderer.pathTracing().quality().beautyTransport() == PathTracingQualityOptions.BeautyTransport.ANALYTIC_DIRECT,
                    "ball analytic transport");
            check(ballRenderer.denoising().preference() == RendererFeaturePreference.DISABLED
                    && ballRenderer.pathTracing().radianceCache() == RendererFeaturePreference.DISABLED, "ball denoise/cache off");
            check(mediumRenderer.pathTracing().quality().beautyTransport() == PathTracingQualityOptions.BeautyTransport.NATIVE
                    && mediumRenderer.pathTracing().radianceCache() == RendererFeaturePreference.PREFERRED
                    && mediumRenderer.denoising().preference() == RendererFeaturePreference.PREFERRED, "native medium denoise/cache");
            var overridden = NativeSceneDemo.Config.parse(new String[]{"--width", "1600", "--height", "1000", "--fg", "off"});
            check(overridden.width() == 1600 && overridden.height() == 1000
                    && overridden.generation().preference() == RendererFeaturePreference.DISABLED, "explicit overrides");
            System.setProperty("demo.fg-multiplier", "3");
            check(NativeSceneDemo.Config.parse(new String[0]).generation().multiplier() == FrameGenerationOptions.Multiplier.THREE_X,
                    "legacy multiplier property");
            System.setProperty("demo.disable-fg", "true");
            check(NativeSceneDemo.Config.parse(new String[0]).generation().preference() == RendererFeaturePreference.DISABLED,
                    "legacy FG disable property");
            System.out.println("NATIVE_SCENE_DEFAULTS PASS scenes=2 fg=2x sr=performance legacyDimensions=true overrides=true noWindow=true");
        } finally {
            restore("demo.disable-fg", disabled); restore("demo.fg-multiplier", multiplier);
        }
    }
    private static void restore(String key, String value) { if (value == null) System.clearProperty(key); else System.setProperty(key, value); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
