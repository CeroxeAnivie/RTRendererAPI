package demo;

import top.ceroxe.rt.renderer.api.CameraState;
import top.ceroxe.rt.renderer.api.DepthProjectionState;
import top.ceroxe.rt.renderer.api.EnvironmentState;
import top.ceroxe.rt.renderer.api.AntiAliasingState;
import top.ceroxe.rt.renderer.api.FrameGenerationOptions;
import top.ceroxe.rt.renderer.api.DenoisingOptions;
import top.ceroxe.rt.renderer.api.FrameReconstructionOptions;
import top.ceroxe.rt.renderer.api.LowLatencyOptions;
import top.ceroxe.rt.renderer.api.RendererConfig;
import top.ceroxe.rt.renderer.api.RayTracingOptimizationOptions;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.RendererFeaturePreference;
import top.ceroxe.rt.renderer.api.RendererPreset;
import top.ceroxe.rt.renderer.api.TemporalRenderingOptions;

/** Centralizes the demo's production renderer policy and exact camera projection contract. */
final class DemoRendererProfile {
    static final String DISABLE_FRAME_GENERATION_PROPERTY = "demo.disable-fg";
    static final String FRAME_GENERATION_MULTIPLIER_PROPERTY = "demo.fg-multiplier";

    private static final DepthProjectionState DEPTH_PROJECTION =
            DepthProjectionState.vulkanPerspective(0.1F, 1_000.0F);

    private DemoRendererProfile() {
    }

    static RendererConfig interactive(DemoConfig config) {
        return baseConfig(config.cpuPresentation() ? 4 : 3, config.cpuPresentation());
    }

    static RendererConfig benchmark() {
        RendererConfig.Builder builder = RendererPreset.MANAGED_GPU_PRESENTATION.configuration()
                .copyBuilder()
                .maxFramesInFlight(8)
                .gpuTimingsEnabled(true)
                .cpuFrameReadbackEnabled(false)
                .validationEnabled(false);
        return generationOnly(builder)
                .frameGeneration(FrameGenerationOptions.recommended())
                .build();
    }

    static RenderFrameRequest.Builder frame(
            long sequence,
            int width,
            int height,
            CameraState camera
    ) {
        return RenderFrameRequest.builder(sequence, width, height, camera)
                .depthProjection(DEPTH_PROJECTION);
    }

    static DepthProjectionState depthProjection() {
        return DEPTH_PROJECTION;
    }

    static CameraState camera(int width, int height, double distance) {
        return CameraState.lookAt(0.0, 0.25, distance, 0.0, 0.0, 0.45)
                .verticalFieldOfViewDegrees(48.0)
                .aspectRatio(width / (double) height)
                .build();
    }

    static EnvironmentState environment() {
        return EnvironmentState.builder()
                .skyRadiance(0.10F, 0.13F, 0.19F)
                .ambientIntensity(0.70F)
                .sunDirection(-0.250000F, -0.450000F, -0.857321F)
                .sunRadiance(1.0F, 0.92F, 0.80F)
                .sunIntensity(1.65F)
                .build();
    }

    static AntiAliasingState antiAliasing(int samplesPerPixel) {
        return samplesPerPixel == 1
                ? AntiAliasingState.disabled()
                : AntiAliasingState.multisampled(samplesPerPixel);
    }

    private static RendererConfig baseConfig(int maxFramesInFlight, boolean cpuReadback) {
        DemoFeatureProfile profile = DemoFeatureProfile.configured();
        RendererPreset preset = cpuReadback
                ? RendererPreset.CPU_READBACK
                : RendererPreset.MANAGED_GPU_PRESENTATION;
        RendererConfig.Builder builder = preset.configuration().copyBuilder()
                .maxFramesInFlight(maxFramesInFlight)
                .gpuTimingsEnabled(true)
                .cpuFrameReadbackEnabled(cpuReadback)
                .validationEnabled(false);
        return (switch (profile) {
            case RECOMMENDED -> recommended(builder);
            case GENERATION_ONLY -> generationOnly(builder);
            case ALL_EXCEPT_MFG -> allExceptMfg(builder);
        }).build();
    }

    private static RendererConfig.Builder recommended(RendererConfig.Builder builder) {
        // The preset owns the default policy. Explicit demo controls remain opt-in overrides.
        if (System.getProperty(DISABLE_FRAME_GENERATION_PROPERTY) == null
                && System.getProperty(FRAME_GENERATION_MULTIPLIER_PROPERTY) == null) {
            return builder;
        }
        return builder.frameGeneration(configuredGenerationPolicy());
    }

    private static RendererConfig.Builder generationOnly(
            RendererConfig.Builder builder
    ) {
        // A single-variable generation lane keeps every unrelated optional path disabled.
        return builder.temporalRendering(TemporalRenderingOptions.disabled())
                .frameReconstruction(FrameReconstructionOptions.disabled())
                .denoising(DenoisingOptions.disabled())
                .frameGeneration(configuredGenerationPolicy())
                .lowLatency(LowLatencyOptions.disabled())
                .rayTracingOptimizations(RayTracingOptimizationOptions.disabled());
    }

    private static RendererConfig.Builder allExceptMfg(
            RendererConfig.Builder builder
    ) {
        if (Boolean.getBoolean(DISABLE_FRAME_GENERATION_PROPERTY)) {
            throw new IllegalArgumentException(
                    DemoFeatureProfile.PROPERTY + "=all-except-mfg conflicts with "
                            + DISABLE_FRAME_GENERATION_PROPERTY + "=true"
            );
        }
        String multiplier = System.getProperty(FRAME_GENERATION_MULTIPLIER_PROPERTY);
        if (multiplier != null && !"2".equals(multiplier.trim())) {
            throw new IllegalArgumentException(
                    DemoFeatureProfile.PROPERTY + "=all-except-mfg requires "
                            + FRAME_GENERATION_MULTIPLIER_PROPERTY + "=2"
            );
        }
        return builder.temporalRendering(TemporalRenderingOptions.balanced())
                .frameReconstruction(FrameReconstructionOptions.recommended())
                .denoising(DenoisingOptions.recommended())
                .frameGeneration(generationOptions(FrameGenerationOptions.Multiplier.TWO_X))
                .lowLatency(LowLatencyOptions.recommended())
                .rayTracingOptimizations(RayTracingOptimizationOptions.recommended());
    }

    private static FrameGenerationOptions configuredGenerationPolicy() {
        if (Boolean.getBoolean(DISABLE_FRAME_GENERATION_PROPERTY)) {
            return FrameGenerationOptions.disabled();
        }
        String configured = System.getProperty(FRAME_GENERATION_MULTIPLIER_PROPERTY, "2").trim();
        FrameGenerationOptions.Multiplier multiplier = switch (configured) {
            case "2" -> FrameGenerationOptions.Multiplier.TWO_X;
            case "3" -> FrameGenerationOptions.Multiplier.THREE_X;
            case "4" -> FrameGenerationOptions.Multiplier.FOUR_X;
            default -> throw new IllegalArgumentException(
                    FRAME_GENERATION_MULTIPLIER_PROPERTY + " must be 2, 3, or 4: " + configured
            );
        };
        return generationOptions(multiplier);
    }

    private static FrameGenerationOptions generationOptions(
            FrameGenerationOptions.Multiplier multiplier
    ) {
        FrameGenerationOptions.Mode mode = multiplier == FrameGenerationOptions.Multiplier.TWO_X
                ? FrameGenerationOptions.Mode.FRAME_GENERATION
                : FrameGenerationOptions.Mode.MULTI_FRAME_GENERATION;
        return FrameGenerationOptions.builder()
                .preference(RendererFeaturePreference.PREFERRED)
                .mode(mode)
                .multiplier(multiplier)
                .fallback(FrameGenerationOptions.Fallback.PRESENT_NATIVE_FRAMES)
                .build();
    }
}
