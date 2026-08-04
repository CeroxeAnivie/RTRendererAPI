package top.ceroxe.rt.renderer.nvidia;

import top.ceroxe.rt.renderer.feature.VulkanFeatureFrameContext;
import top.ceroxe.rt.renderer.feature.VulkanFrameGenerationResourceContract;
import top.ceroxe.rt.renderer.feature.VulkanFrameReconstructionResourceContract;
import top.ceroxe.rt.renderer.feature.VulkanTemporalFrameInput;
import top.ceroxe.rt.renderer.rt.device.RtGpuImage;

import java.util.Objects;

/** Encapsulates DLSS-G/MFG frame tagging, submission markers, proxy presentation, and evidence. */
final class NvidiaStreamlineFrameGenerationRuntime {
    private static final Stats EMPTY_STATS = new Stats(
            0L, 0L, 0L, 0L, 0, 0, 0, 0L, 0, 0, 0, 0L, 0L,
            0L, 0L, 0L, 0L, false
    );

    private NvidiaStreamlineFrameGenerationRuntime() {
    }

    static void record(VulkanFeatureFrameContext context) {
        VulkanFeatureFrameContext checked = Objects.requireNonNull(context, "context");
        VulkanFrameGenerationResourceContract resources = checked.frameGenerationResources().orElse(null);
        VulkanFrameReconstructionResourceContract reconstruction = checked.reconstructionResources().orElse(null);
        if (resources == null && reconstruction == null) {
            throw new IllegalArgumentException("Streamline frame generation requires depth and motion resources");
        }
        VulkanTemporalFrameInput input = checked.temporalInput().orElseThrow(() ->
                new IllegalArgumentException("Streamline frame generation requires temporal frame input")
        );
        StreamlineFrameConstants constants = StreamlineFrameConstants.from(
                input, checked.extents().renderWidth(), checked.extents().renderHeight()
        );
        NvidiaNativeBridge.nativeRecordStreamlineFrameGeneration(
                checked.commandBuffer().address(),
                input.request().sequence(),
                image(checked.publishedOutput()),
                resources != null ? resources.depth() : reconstruction.depth(),
                resources != null ? resources.motionVectors() : reconstruction.motionVectors(),
                constants
        );
    }

    static void beginSubmission(long frameSequence) {
        requireFrameSequence(frameSequence);
        NvidiaNativeBridge.nativeBeginStreamlineFrameSubmission(frameSequence);
    }

    static void beginPreparation(long frameSequence) {
        requireFrameSequence(frameSequence);
        NvidiaNativeBridge.nativeBeginStreamlineFramePreparation(frameSequence);
    }

    static void cancelPreparation(long frameSequence) {
        requireFrameSequence(frameSequence);
        NvidiaNativeBridge.nativeCancelStreamlineFramePreparation(frameSequence);
    }

    static top.ceroxe.rt.renderer.feature.VulkanFeatureSession.InputCompletion awaitInputReuse(long frameSequence) {
        requireFrameSequence(frameSequence);
        long[] completion = NvidiaNativeBridge.nativeAwaitStreamlineFrameInputReuse(frameSequence);
        if (completion == null || completion.length != 2) {
            throw new IllegalStateException("invalid DLSS-G input-completion payload");
        }
        return new top.ceroxe.rt.renderer.feature.VulkanFeatureSession.InputCompletion(
                completion[0], completion[1]
        );
    }

    static void disableFrameGeneration() {
        NvidiaNativeBridge.nativeDisableStreamlineFrameGeneration();
    }

    static void endSubmission(long frameSequence) {
        requireFrameSequence(frameSequence);
        NvidiaNativeBridge.nativeEndStreamlineFrameSubmission(frameSequence);
    }

    static int createSwapchain(long device, long createInfo, long output) {
        return NvidiaNativeBridge.nativeStreamlineCreateSwapchain(device, createInfo, output);
    }

    static void destroySwapchain(long device, long swapchain) {
        NvidiaNativeBridge.nativeStreamlineDestroySwapchain(device, swapchain);
    }

    static int getSwapchainImages(long device, long swapchain, long count, long images) {
        return NvidiaNativeBridge.nativeStreamlineGetSwapchainImages(device, swapchain, count, images);
    }

    static int acquireNextImage(
            long device, long swapchain, long timeout, long semaphore, long fence, long imageIndex
    ) {
        return NvidiaNativeBridge.nativeStreamlineAcquireNextImage(
                device, swapchain, timeout, semaphore, fence, imageIndex
        );
    }

    static int queuePresent(long queue, long presentInfo, int generatedFrames, long frameSequence) {
        requireFrameSequence(frameSequence);
        return NvidiaNativeBridge.nativeStreamlineQueuePresent(
                queue, presentInfo, generatedFrames, frameSequence
        );
    }

    static void retireFrame(long frameSequence) {
        requireFrameSequence(frameSequence);
        NvidiaNativeBridge.nativeRetireStreamlineFrame(frameSequence);
    }

    static Stats stats() {
        long[] values = NvidiaNativeBridge.nativeStreamlineFrameGenerationStats();
        if (values == null || values.length != 18) {
            throw new IllegalStateException("invalid native Streamline frame-generation statistics");
        }
        if (values[17] != 0L && values[17] != 1L) {
            throw new IllegalStateException("invalid native Streamline state-query validity flag");
        }
        return new Stats(
                values[0], values[1], values[2], values[3],
                Math.toIntExact(values[4]), Math.toIntExact(values[5]), Math.toIntExact(values[6]), values[7],
                Math.toIntExact(values[8]), Math.toIntExact(values[9]), Math.toIntExact(values[10]),
                values[11], values[12], values[13], values[14], values[15], values[16],
                values[17] == 1L
        );
    }

    static Stats emptyStats() {
        return EMPTY_STATS;
    }

    private static void requireFrameSequence(long frameSequence) {
        if (frameSequence < 0L) throw new IllegalArgumentException("frameSequence must not be negative");
    }

    private static VulkanFrameReconstructionResourceContract.Image image(RtGpuImage source) {
        RtGpuImage checked = Objects.requireNonNull(source, "source");
        return new VulkanFrameReconstructionResourceContract.Image(
                checked.image(), checked.memory(), checked.imageView(), checked.format(),
                checked.width(), checked.height(), checked.usageFlags()
        );
    }

    record Stats(
            long proxyPresentCalls,
            long stateSamples,
            long framesActuallyPresented,
            long generatedFramesActuallyPresented,
            int lastFramesActuallyPresented,
            int maxFramesToGenerate,
            int status,
            long stateQueryFailures,
            int maxGeneratedFramesInSample,
            int lastRequestedGeneratedFrames,
            int configuredGeneratedFrames,
            long generationRequestMisses,
            long stateQueryCalls,
            long firstProxyPresentSequence,
            long lastProxyPresentSequence,
            long lastGeneratedObservationSequence,
            long resetEpoch,
            boolean latestQuerySucceeded
    ) {
        Stats {
            if (proxyPresentCalls < 0L || stateSamples < 0L || framesActuallyPresented < 0L
                    || generatedFramesActuallyPresented < 0L || lastFramesActuallyPresented < 0
                    || maxFramesToGenerate < 0 || status < 0 || stateQueryFailures < 0L
                    || maxGeneratedFramesInSample < 0 || lastRequestedGeneratedFrames < 0
                    || configuredGeneratedFrames < 0 || generationRequestMisses < 0L
                    || stateQueryCalls < 0L || firstProxyPresentSequence < 0L
                    || lastProxyPresentSequence < 0L || lastGeneratedObservationSequence < 0L
                    || resetEpoch < 0L) {
                throw new IllegalArgumentException("Streamline frame-generation statistics must not be negative");
            }
            if (generatedFramesActuallyPresented > framesActuallyPresented) {
                throw new IllegalArgumentException("generated frame count exceeds total presented frame count");
            }
            if (stateSamples > stateQueryCalls || stateQueryFailures > stateQueryCalls) {
                throw new IllegalArgumentException("DLSS-G state-query counters are inconsistent");
            }
            if (proxyPresentCalls == 0L
                    && (firstProxyPresentSequence != 0L || lastProxyPresentSequence != 0L)) {
                throw new IllegalArgumentException("empty proxy-present evidence cannot publish sequences");
            }
            if (proxyPresentCalls > 0L && firstProxyPresentSequence > lastProxyPresentSequence) {
                throw new IllegalArgumentException("proxy-present sequence range is inverted");
            }
            if (generatedFramesActuallyPresented == 0L && lastGeneratedObservationSequence != 0L) {
                throw new IllegalArgumentException("generated observation sequence requires generated output");
            }
            if (generatedFramesActuallyPresented > 0L
                    && (lastGeneratedObservationSequence < firstProxyPresentSequence
                    || lastGeneratedObservationSequence > lastProxyPresentSequence)) {
                throw new IllegalArgumentException("generated observation sequence is outside proxy-present range");
            }
            if (latestQuerySucceeded && stateQueryCalls == 0L) {
                throw new IllegalArgumentException("successful latest query requires a state-query attempt");
            }
        }

        boolean active() {
            return latestQuerySucceeded && status == 0 && generatedFramesActuallyPresented > 0L;
        }
    }
}
