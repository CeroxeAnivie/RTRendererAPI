package top.ceroxe.rt.renderer.nvidia;

import top.ceroxe.rt.renderer.api.DenoisingOptions;
import top.ceroxe.rt.renderer.api.FrameGenerationOptions;
import top.ceroxe.rt.renderer.api.FrameReconstructionOptions;
import top.ceroxe.rt.renderer.api.RendererFeaturePreference;

/** Exhaustive policy matrix for first-frame, steady-state, resize, and present failures. */
public final class NvidiaFeatureFailurePolicySelfTest {
    private NvidiaFeatureFailurePolicySelfTest() {
    }

    public static void main(String[] arguments) {
        require(NvidiaFeatureFailurePolicy.denoising(denoising(RendererFeaturePreference.REQUIRED, false))
                == NvidiaFeatureFailurePolicy.Action.FAIL_REQUIRED);
        require(NvidiaFeatureFailurePolicy.denoising(denoising(RendererFeaturePreference.PREFERRED, true))
                == NvidiaFeatureFailurePolicy.Action.FALLBACK_TEMPORAL);
        require(NvidiaFeatureFailurePolicy.denoising(denoising(RendererFeaturePreference.PREFERRED, false))
                == NvidiaFeatureFailurePolicy.Action.DISABLE);

        FrameReconstructionOptions required = reconstruction(
                RendererFeaturePreference.REQUIRED, FrameReconstructionOptions.Fallback.NONE
        );
        require(reconstruction(required, false) == NvidiaFeatureFailurePolicy.Action.FAIL_REQUIRED);
        require(reconstruction(reconstruction(RendererFeaturePreference.PREFERRED,
                        FrameReconstructionOptions.Fallback.SPATIAL), true)
                == NvidiaFeatureFailurePolicy.Action.SWITCH_TO_NIS);
        require(reconstruction(reconstruction(RendererFeaturePreference.PREFERRED,
                        FrameReconstructionOptions.Fallback.SPATIAL), false)
                == NvidiaFeatureFailurePolicy.Action.FALLBACK_NATIVE_RESOLUTION);
        require(reconstruction(reconstruction(RendererFeaturePreference.PREFERRED,
                        FrameReconstructionOptions.Fallback.BUILT_IN_TEMPORAL), false)
                == NvidiaFeatureFailurePolicy.Action.FALLBACK_TEMPORAL);
        require(reconstruction(reconstruction(RendererFeaturePreference.PREFERRED,
                        FrameReconstructionOptions.Fallback.NONE), false)
                == NvidiaFeatureFailurePolicy.Action.FALLBACK_NATIVE_RESOLUTION);

        require(NvidiaFeatureFailurePolicy.frameGeneration(frameGeneration(RendererFeaturePreference.REQUIRED))
                == NvidiaFeatureFailurePolicy.Action.FAIL_REQUIRED);
        require(NvidiaFeatureFailurePolicy.frameGeneration(FrameGenerationOptions.recommended())
                == NvidiaFeatureFailurePolicy.Action.FALLBACK_NATIVE_PRESENTATION);
        NvidiaProviderFaultIsolationSelfTest.runAll();
        System.out.println("NvidiaFeatureFailurePolicySelfTest passed");
    }

    private static NvidiaFeatureFailurePolicy.Action reconstruction(
            FrameReconstructionOptions options, boolean nisAvailable
    ) {
        return NvidiaFeatureFailurePolicy.reconstruction(
                options, NvidiaStreamlineRuntime.Feature.DLSS, nisAvailable
        );
    }

    private static DenoisingOptions denoising(RendererFeaturePreference preference, boolean fallback) {
        return DenoisingOptions.builder().preference(preference).builtInTemporalFallback(fallback).build();
    }

    private static FrameReconstructionOptions reconstruction(
            RendererFeaturePreference preference,
            FrameReconstructionOptions.Fallback fallback
    ) {
        return FrameReconstructionOptions.builder()
                .preference(preference)
                .mode(FrameReconstructionOptions.Mode.SUPER_RESOLUTION)
                .fallback(fallback)
                .build();
    }

    private static FrameGenerationOptions frameGeneration(RendererFeaturePreference preference) {
        return FrameGenerationOptions.builder()
                .preference(preference)
                .mode(FrameGenerationOptions.Mode.FRAME_GENERATION)
                .multiplier(FrameGenerationOptions.Multiplier.TWO_X)
                .fallback(FrameGenerationOptions.Fallback.NONE)
                .build();
    }

    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("unexpected NVIDIA failure-policy decision");
    }
}
