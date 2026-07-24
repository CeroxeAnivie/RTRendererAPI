package top.ceroxe.mcvulkanrt.renderer.rt.pipeline;

/**
 * Pure policy for RT frame-resource sizing, dispatch cadence, and diagnostic readback.
 *
 * <p>This class deliberately owns no Vulkan handle and no mutable frame state. Keeping
 * configuration and validation outside {@link RtRayTracingPipeline} makes the native
 * pipeline a resource/command owner instead of a mixture of RHI lifetime and launch
 * policy. The shape follows UE's split between render policy and RHI resources: callers
 * can test every decision without constructing a device.</p>
 */
final class RtFrameDispatchPolicy {
    private static final long MAX_RETAINED_OUTPUT_SEQUENCE_LAG = 12L;
    static final int MIN_FRAME_RESOURCE_RING_SIZE = 2;
    static final int DEFAULT_FRAME_RESOURCE_RING_SIZE = 3;
    /*
     * Three submissions may be writing concurrently. Publication can additionally retain one
     * latest/exported frame and one previously presented frame, and continuous dispatch needs one
     * writable slot. Six is therefore the complete worst-case ownership set. Twelve duplicated
     * full-resolution output and trace images without representing another legal lifecycle state.
     */
    static final int DEFAULT_GPU_SHARED_FRAME_RESOURCE_RING_SIZE = 6;
    static final int MAX_FRAME_RESOURCE_RING_SIZE = 24;
    static final int DESCRIPTOR_SETS_PER_FRAME_SLOT = 2;
    static final int DEFAULT_FRAME_OUTPUT_WIDTH = 320;
    static final int DEFAULT_FRAME_OUTPUT_HEIGHT = 180;
    static final int DEFAULT_VISIBLE_FRAME_OUTPUT_WIDTH = 854;
    static final int DEFAULT_VISIBLE_FRAME_OUTPUT_HEIGHT = 480;
    static final int DEFAULT_FRAME_MAX_PIXELS = Integer.MAX_VALUE;
    static final int DEFAULT_BACKGROUND_PRIMARY_RAY_UPSCALE_FACTOR = 1;
    static final int DEFAULT_VISIBLE_PRIMARY_RAY_UPSCALE_FACTOR = 1;
    static final int MAX_PRIMARY_RAY_UPSCALE_FACTOR = 8;
    static final int DEFAULT_FRAME_DISPATCH_INTERVAL = 8;
    static final int DEFAULT_VISUAL_EXPERIMENT_FRAME_DISPATCH_INTERVAL = 1;
    static final int DEFAULT_GPU_SHARED_PRESENTATION_FRAME_DISPATCH_INTERVAL = 1;
    static final int DEFAULT_GPU_SHARED_PRESENTATION_MAX_PENDING_FRAMES = 3;
    static final int DEFAULT_EXPLICIT_FRAME_READBACK_INTERVAL = 1;
    static final int DEFAULT_PRESENTATION_DIAGNOSTIC_READBACK_INTERVAL = 4;
    static final int DEFAULT_VISUAL_EXPERIMENT_READBACK_INTERVAL = 1;
    static final int DEFAULT_GPU_SHARED_VISUAL_DIAGNOSTIC_READBACK_INTERVAL = 1_000_000;

    private RtFrameDispatchPolicy() {
    }

    static int descriptorSetCountForFrameSlots(int frameSlotCount) {
        if (frameSlotCount <= 0) {
            throw new IllegalArgumentException("frameSlotCount must be positive");
        }
        return Math.multiplyExact(frameSlotCount, DESCRIPTOR_SETS_PER_FRAME_SLOT);
    }

    static long frameBoundResourceRetirementGeneration(
            long activeDescriptorGeneration,
            long oldestPendingDescriptorGeneration
    ) {
        if (activeDescriptorGeneration <= 0L) {
            throw new IllegalArgumentException("activeDescriptorGeneration must be positive");
        }
        if (oldestPendingDescriptorGeneration < -1L || oldestPendingDescriptorGeneration == 0L) {
            throw new IllegalArgumentException("oldestPendingDescriptorGeneration must be -1 or positive");
        }
        if (oldestPendingDescriptorGeneration > activeDescriptorGeneration) {
            throw new IllegalArgumentException("pending descriptor generation cannot exceed the active generation");
        }
        return oldestPendingDescriptorGeneration < 0L
                ? activeDescriptorGeneration
                : oldestPendingDescriptorGeneration - 1L;
    }

    static int stageableDescriptorIndex(long[] descriptorGenerations, int inFlightDescriptorIndex) {
        if (descriptorGenerations == null) {
            throw new NullPointerException("descriptorGenerations");
        }
        if (descriptorGenerations.length < DESCRIPTOR_SETS_PER_FRAME_SLOT) {
            throw new IllegalArgumentException("descriptor generation bank must be double buffered");
        }
        if (inFlightDescriptorIndex < -1 || inFlightDescriptorIndex >= descriptorGenerations.length) {
            throw new IllegalArgumentException("inFlightDescriptorIndex is outside the descriptor generation bank");
        }
        int selectedIndex = -1;
        long oldestGeneration = Long.MAX_VALUE;
        for (int descriptorIndex = 0; descriptorIndex < descriptorGenerations.length; descriptorIndex++) {
            long generation = descriptorGenerations[descriptorIndex];
            if (generation <= 0L) {
                throw new IllegalArgumentException("descriptor generations must be positive");
            }
            if (descriptorIndex != inFlightDescriptorIndex
                    && (selectedIndex < 0 || generation < oldestGeneration)) {
                selectedIndex = descriptorIndex;
                oldestGeneration = generation;
            }
        }
        return selectedIndex;
    }

    static int frameResourceRingSize(
            int configuredRingSize,
            boolean gpuSharedPresentationEnabled,
            boolean renderReplacementEnabled,
            boolean visualOutputExperimentEnabled
    ) {
        int requested = configuredRingSize > 0
                ? configuredRingSize
                : defaultFrameResourceRingSize(
                        gpuSharedPresentationEnabled,
                        renderReplacementEnabled,
                        visualOutputExperimentEnabled
                );
        return Math.max(MIN_FRAME_RESOURCE_RING_SIZE, Math.min(MAX_FRAME_RESOURCE_RING_SIZE, requested));
    }

    private static int defaultFrameResourceRingSize(
            boolean gpuSharedPresentationEnabled,
            boolean renderReplacementEnabled,
            boolean visualOutputExperimentEnabled
    ) {
        return gpuSharedPresentationEnabled && (renderReplacementEnabled || visualOutputExperimentEnabled)
                ? DEFAULT_GPU_SHARED_FRAME_RESOURCE_RING_SIZE
                : DEFAULT_FRAME_RESOURCE_RING_SIZE;
    }

    static int maxPendingFrameSubmissions(
            int configuredMaxPending,
            boolean gpuSharedPresentationEnabled,
            boolean renderReplacementEnabled,
            boolean visualOutputExperimentEnabled,
            int frameResourceRingSize
    ) {
        if (frameResourceRingSize <= 0) {
            throw new IllegalArgumentException("frameResourceRingSize must be positive");
        }
        int requested = configuredMaxPending > 0
                ? configuredMaxPending
                : defaultMaxPendingFrameSubmissions(
                        gpuSharedPresentationEnabled,
                        renderReplacementEnabled,
                        visualOutputExperimentEnabled,
                        frameResourceRingSize
                );
        return Math.max(1, Math.min(frameResourceRingSize, requested));
    }

    private static int defaultMaxPendingFrameSubmissions(
            boolean gpuSharedPresentationEnabled,
            boolean renderReplacementEnabled,
            boolean visualOutputExperimentEnabled,
            int frameResourceRingSize
    ) {
        return gpuSharedPresentationEnabled && (renderReplacementEnabled || visualOutputExperimentEnabled)
                ? Math.min(DEFAULT_GPU_SHARED_PRESENTATION_MAX_PENDING_FRAMES, frameResourceRingSize)
                : frameResourceRingSize;
    }

    static OutputConfig outputConfig(
            int configuredWidth,
            int configuredHeight,
            int maxPixels,
            boolean followsRenderTarget,
            int primaryRayUpscaleFactor
    ) {
        if (configuredWidth < 0 || configuredHeight < 0) {
            throw new IllegalArgumentException("configured output dimensions must not be negative");
        }
        if (maxPixels <= 0) {
            throw new IllegalArgumentException("maxPixels must be positive");
        }
        if (primaryRayUpscaleFactor <= 0 || primaryRayUpscaleFactor > MAX_PRIMARY_RAY_UPSCALE_FACTOR) {
            throw new IllegalArgumentException(
                    "primaryRayUpscaleFactor must be within 1.." + MAX_PRIMARY_RAY_UPSCALE_FACTOR
            );
        }
        if (followsRenderTarget && primaryRayUpscaleFactor != 1) {
            throw new IllegalArgumentException(
                    "visible RT output must trace at native resolution; primaryRayUpscaleFactor must be 1"
            );
        }
        int fixedWidth = configuredWidth;
        int fixedHeight = configuredHeight;
        if (fixedWidth > 0 && fixedHeight == 0) {
            fixedHeight = Math.max(
                    1,
                    Math.round(fixedWidth * (DEFAULT_FRAME_OUTPUT_HEIGHT / (float) DEFAULT_FRAME_OUTPUT_WIDTH))
            );
        } else if (fixedHeight > 0 && fixedWidth == 0) {
            fixedWidth = Math.max(
                    1,
                    Math.round(fixedHeight * (DEFAULT_FRAME_OUTPUT_WIDTH / (float) DEFAULT_FRAME_OUTPUT_HEIGHT))
            );
        }
        boolean fixed = fixedWidth > 0 && fixedHeight > 0;
        int defaultWidth = followsRenderTarget ? DEFAULT_VISIBLE_FRAME_OUTPUT_WIDTH : DEFAULT_FRAME_OUTPUT_WIDTH;
        int defaultHeight = followsRenderTarget ? DEFAULT_VISIBLE_FRAME_OUTPUT_HEIGHT : DEFAULT_FRAME_OUTPUT_HEIGHT;
        return new OutputConfig(
                fixed ? fixedWidth : defaultWidth,
                fixed ? fixedHeight : defaultHeight,
                maxPixels,
                followsRenderTarget && !fixed,
                primaryRayUpscaleFactor
        );
    }

    static boolean shouldFollowRenderTargetForOutput(
            boolean frameReadbackEnabled,
            boolean presentationEnabled,
            boolean gpuSharedPresentationEnabled,
            boolean renderReplacementEnabled,
            boolean visualOutputExperimentEnabled
    ) {
        return frameReadbackEnabled
                || presentationEnabled
                || gpuSharedPresentationEnabled
                || renderReplacementEnabled
                || visualOutputExperimentEnabled;
    }

    static int frameDispatchInterval(
            int configuredInterval,
            boolean frameReadbackEnabled,
            boolean renderReplacementEnabled,
            boolean visualOutputExperimentEnabled,
            boolean gpuSharedPresentationEnabled
    ) {
        if (configuredInterval > 0) {
            return configuredInterval;
        }
        if (gpuSharedPresentationEnabled) {
            return DEFAULT_GPU_SHARED_PRESENTATION_FRAME_DISPATCH_INTERVAL;
        }
        if (frameReadbackEnabled && (renderReplacementEnabled || visualOutputExperimentEnabled)) {
            return DEFAULT_VISUAL_EXPERIMENT_FRAME_DISPATCH_INTERVAL;
        }
        return DEFAULT_FRAME_DISPATCH_INTERVAL;
    }

    static boolean shouldDispatchForPresentationFreshness(
            long frameStateSequence,
            long latestCompletedFrameStateSequence,
            long newestPendingFrameStateSequence,
            int frameDispatchInterval
    ) {
        if (frameStateSequence < 0L) {
            throw new IllegalArgumentException("frameStateSequence must not be negative");
        }
        if (latestCompletedFrameStateSequence < -1L) {
            throw new IllegalArgumentException("latestCompletedFrameStateSequence must be -1 or greater");
        }
        if (newestPendingFrameStateSequence < -1L) {
            throw new IllegalArgumentException("newestPendingFrameStateSequence must be -1 or greater");
        }
        long newestSubmitted = Math.max(latestCompletedFrameStateSequence, newestPendingFrameStateSequence);
        if (newestSubmitted < 0L) {
            return true;
        }
        if (newestSubmitted > frameStateSequence) {
            return false;
        }
        return frameStateSequence - newestSubmitted >= presentationFreshnessDispatchWatermark(frameDispatchInterval);
    }

    static long presentationFreshnessDispatchWatermark(int frameDispatchInterval) {
        if (frameDispatchInterval <= 0) {
            throw new IllegalArgumentException("frameDispatchInterval must be positive");
        }
        long safetyFrames = Math.max(2L, Math.min(6L, frameDispatchInterval * 2L));
        return Math.max(1L, MAX_RETAINED_OUTPUT_SEQUENCE_LAG - safetyFrames);
    }

    static int frameReadbackInterval(
            int configuredInterval,
            boolean explicitReadbackEnabled,
            boolean presentationEnabled,
            boolean gpuSharedPresentationEnabled,
            boolean renderReplacementEnabled,
            boolean visualOutputExperimentEnabled
    ) {
        if (configuredInterval > 0) {
            return configuredInterval;
        }
        if (explicitReadbackEnabled
                && gpuSharedPresentationEnabled
                && (visualOutputExperimentEnabled || renderReplacementEnabled)) {
            return DEFAULT_GPU_SHARED_VISUAL_DIAGNOSTIC_READBACK_INTERVAL;
        }
        if (explicitReadbackEnabled) {
            return DEFAULT_EXPLICIT_FRAME_READBACK_INTERVAL;
        }
        if (visualOutputExperimentEnabled || renderReplacementEnabled) {
            return DEFAULT_VISUAL_EXPERIMENT_READBACK_INTERVAL;
        }
        if (presentationEnabled && !gpuSharedPresentationEnabled) {
            return DEFAULT_PRESENTATION_DIAGNOSTIC_READBACK_INTERVAL;
        }
        return DEFAULT_EXPLICIT_FRAME_READBACK_INTERVAL;
    }

    static boolean shouldCaptureFrameReadback(
            boolean frameReadbackEnabled,
            long completedFrameDispatches,
            int frameReadbackInterval
    ) {
        validateReadbackCadence(completedFrameDispatches, frameReadbackInterval);
        return frameReadbackEnabled
                && (completedFrameDispatches == 0L
                || completedFrameDispatches % frameReadbackInterval == frameReadbackInterval - 1L);
    }

    static boolean shouldCaptureFrameReadback(
            boolean frameReadbackEnabled,
            long completedFrameDispatches,
            int frameReadbackInterval,
            long frameStateSequence,
            long lastReadbackFrameStateSequence
    ) {
        validateReadbackCadence(completedFrameDispatches, frameReadbackInterval);
        if (lastReadbackFrameStateSequence < -1L) {
            throw new IllegalArgumentException("lastReadbackFrameStateSequence must be -1 or greater");
        }
        if (!frameReadbackEnabled) {
            return false;
        }
        if (completedFrameDispatches == 0L || lastReadbackFrameStateSequence < 0L) {
            return true;
        }
        if (frameStateSequence >= 0L
                && frameStateSequence - lastReadbackFrameStateSequence >= frameReadbackInterval) {
            return true;
        }
        return completedFrameDispatches % frameReadbackInterval == frameReadbackInterval - 1L;
    }

    private static void validateReadbackCadence(long completedFrameDispatches, int frameReadbackInterval) {
        if (completedFrameDispatches < 0L) {
            throw new IllegalArgumentException("completedFrameDispatches must not be negative");
        }
        if (frameReadbackInterval <= 0) {
            throw new IllegalArgumentException("frameReadbackInterval must be positive");
        }
    }

    static boolean shouldEnableFrameReadback(
            boolean explicitReadbackEnabled,
            boolean presentationEnabled,
            boolean gpuSharedPresentationEnabled,
            boolean renderReplacementEnabled
    ) {
        return explicitReadbackEnabled
                || (presentationEnabled && !gpuSharedPresentationEnabled)
                || (renderReplacementEnabled && !gpuSharedPresentationEnabled);
    }

    static boolean shouldRequirePresentationEligibleForFrameDispatch(
            boolean explicitReadbackEnabled,
            boolean presentationEnabled,
            boolean gpuSharedPresentationEnabled,
            boolean renderReplacementEnabled
    ) {
        return !explicitReadbackEnabled
                && !renderReplacementEnabled
                && (presentationEnabled || gpuSharedPresentationEnabled);
    }

    static boolean shouldDispatchPresentationGateProbe(long observedFrameStates, int probeInterval) {
        if (observedFrameStates <= 0L) {
            throw new IllegalArgumentException("observedFrameStates must be positive");
        }
        if (probeInterval <= 0) {
            throw new IllegalArgumentException("probeInterval must be positive");
        }
        return observedFrameStates == 1L || observedFrameStates % probeInterval == 0L;
    }

    record OutputConfig(
            int width,
            int height,
            int maxPixels,
            boolean followsRenderTarget,
            int primaryRayUpscaleFactor
    ) {
    }
}
