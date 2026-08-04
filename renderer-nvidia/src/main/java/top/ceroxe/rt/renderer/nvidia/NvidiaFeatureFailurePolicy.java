package top.ceroxe.rt.renderer.nvidia;

import top.ceroxe.rt.renderer.api.DenoisingOptions;
import top.ceroxe.rt.renderer.api.FrameGenerationOptions;
import top.ceroxe.rt.renderer.api.FrameReconstructionOptions;
import top.ceroxe.rt.renderer.api.RendererFeaturePreference;

import java.util.Objects;

/** Single decision table for every NVIDIA runtime failure boundary. */
final class NvidiaFeatureFailurePolicy {
    enum Action {
        FAIL_REQUIRED,
        SWITCH_TO_NIS,
        FALLBACK_TEMPORAL,
        FALLBACK_NATIVE_RESOLUTION,
        FALLBACK_NATIVE_PRESENTATION,
        DISABLE
    }

    private NvidiaFeatureFailurePolicy() {
    }

    static Action denoising(DenoisingOptions options) {
        DenoisingOptions checked = Objects.requireNonNull(options, "options");
        if (checked.preference() == RendererFeaturePreference.REQUIRED) return Action.FAIL_REQUIRED;
        return checked.builtInTemporalFallback() ? Action.FALLBACK_TEMPORAL : Action.DISABLE;
    }

    static Action reconstruction(
            FrameReconstructionOptions options,
            NvidiaStreamlineRuntime.Feature current,
            boolean nisAvailable
    ) {
        FrameReconstructionOptions checked = Objects.requireNonNull(options, "options");
        NvidiaStreamlineRuntime.Feature active = Objects.requireNonNull(current, "current");
        if (checked.preference() == RendererFeaturePreference.REQUIRED) return Action.FAIL_REQUIRED;
        if (checked.fallback() == FrameReconstructionOptions.Fallback.SPATIAL
                && active != NvidiaStreamlineRuntime.Feature.NIS && nisAvailable) {
            return Action.SWITCH_TO_NIS;
        }
        return checked.fallback() == FrameReconstructionOptions.Fallback.BUILT_IN_TEMPORAL
                ? Action.FALLBACK_TEMPORAL
                : Action.FALLBACK_NATIVE_RESOLUTION;
    }

    static Action frameGeneration(FrameGenerationOptions options) {
        FrameGenerationOptions checked = Objects.requireNonNull(options, "options");
        return checked.preference() == RendererFeaturePreference.REQUIRED
                ? Action.FAIL_REQUIRED
                : Action.FALLBACK_NATIVE_PRESENTATION;
    }
}
