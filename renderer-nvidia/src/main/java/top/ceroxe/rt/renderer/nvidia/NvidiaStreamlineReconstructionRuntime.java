package top.ceroxe.rt.renderer.nvidia;

import top.ceroxe.rt.renderer.api.FrameReconstructionOptions;
import top.ceroxe.rt.renderer.feature.VulkanFeatureFrameContext;
import top.ceroxe.rt.renderer.feature.VulkanStreamlineFrameResourceContract;
import top.ceroxe.rt.renderer.feature.VulkanTemporalFrameInput;
import top.ceroxe.rt.renderer.rt.pipeline.VulkanFrameExtents;

import java.util.Objects;

/** Encapsulates Streamline DLSS/DLAA/NIS resource tagging and extent negotiation. */
final class NvidiaStreamlineReconstructionRuntime {
    private NvidiaStreamlineReconstructionRuntime() {
    }

    static void record(
            VulkanFeatureFrameContext context,
            FrameReconstructionOptions options,
            NvidiaStreamlineRuntime.Feature feature
    ) {
        VulkanFeatureFrameContext checked = Objects.requireNonNull(context, "context");
        FrameReconstructionOptions reconstruction = Objects.requireNonNull(options, "options");
        VulkanStreamlineFrameResourceContract resources = VulkanStreamlineFrameResourceContract.from(checked);
        VulkanTemporalFrameInput input = checked.temporalInput().orElseThrow(() ->
                new IllegalArgumentException("Streamline reconstruction requires temporal frame input")
        );
        StreamlineFrameConstants constants = StreamlineFrameConstants.from(
                input, checked.extents().renderWidth(), checked.extents().renderHeight()
        );
        NvidiaNativeBridge.nativeRecordStreamlineFrame(
                checked.commandBuffer().address(),
                Objects.requireNonNull(feature, "feature").bit(),
                reconstruction.mode().ordinal(),
                reconstruction.quality().ordinal(),
                input.request().sequence(),
                resources,
                constants
        );
    }

    static VulkanFrameExtents extents(
            FrameReconstructionOptions options,
            NvidiaStreamlineRuntime.Feature feature,
            int outputWidth,
            int outputHeight
    ) {
        FrameReconstructionOptions checked = Objects.requireNonNull(options, "options");
        NvidiaStreamlineRuntime.Feature selected = Objects.requireNonNull(feature, "feature");
        if (selected == NvidiaStreamlineRuntime.Feature.NIS) {
            return spatialExtents(checked.quality(), outputWidth, outputHeight);
        }
        if (selected != NvidiaStreamlineRuntime.Feature.DLSS) {
            throw new IllegalArgumentException("unsupported reconstruction feature: " + selected);
        }
        if (checked.mode() == FrameReconstructionOptions.Mode.NATIVE_ANTI_ALIASING) {
            return VulkanFrameExtents.identity(outputWidth, outputHeight);
        }
        int[] result = NvidiaNativeBridge.nativeStreamlineDlssOptimalSettings(
                checked.quality().ordinal(), outputWidth, outputHeight
        );
        if (result == null || result.length != 2) {
            throw new IllegalStateException("invalid native DLSS optimal settings result");
        }
        return new VulkanFrameExtents(result[0], result[1], outputWidth, outputHeight);
    }

    private static VulkanFrameExtents spatialExtents(
            FrameReconstructionOptions.Quality quality,
            int outputWidth,
            int outputHeight
    ) {
        if (outputWidth <= 0 || outputHeight <= 0) {
            throw new IllegalArgumentException("NIS output extent must be positive");
        }
        int ratioPercent = switch (Objects.requireNonNull(quality, "quality")) {
            case ULTRA_QUALITY -> 77;
            case QUALITY -> 67;
            case BALANCED, AUTO -> 59;
            case PERFORMANCE -> 50;
            case ULTRA_PERFORMANCE -> 33;
        };
        int renderWidth = Math.max(1, Math.toIntExact(Math.round(outputWidth * (ratioPercent / 100.0))));
        int renderHeight = Math.max(1, Math.toIntExact(Math.round(outputHeight * (ratioPercent / 100.0))));
        return new VulkanFrameExtents(renderWidth, renderHeight, outputWidth, outputHeight);
    }
}
