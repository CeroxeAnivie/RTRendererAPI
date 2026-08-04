package top.ceroxe.rt.renderer.nvidia;

import top.ceroxe.rt.renderer.feature.VulkanFeatureOpenContext;
import top.ceroxe.rt.renderer.feature.VulkanFrameReconstructionResourceContract;
import top.ceroxe.rt.renderer.feature.VulkanStreamlineFrameResourceContract;

import java.util.Objects;

/**
 * Stable raw JNI boundary for NVIDIA SDK integrations.
 *
 * <p>Feature-specific validation and resource translation deliberately live in narrow runtime
 * collaborators. Keeping JNI declarations here preserves native symbol names while preventing
 * rendering policy, SDK lifecycle, and wire-format parsing from accumulating in one coordinator.</p>
 */
final class NvidiaNativeBridge {
    static final int ABI_VERSION = 4;
    static final int DLSS = 1;
    static final int DLAA = 1 << 1;
    static final int NIS = 1 << 2;
    static final int NRD = 1 << 3;
    static final int RTX_MEMORY_UTILITY = 1 << 4;

    private NvidiaNativeBridge() {
    }

    static Probe probe() {
        return Loader.PROBE;
    }

    static long openNrd(VulkanFeatureOpenContext context) {
        Objects.requireNonNull(context, "context");
        Probe probe = probe();
        if (!probe.loaded() || !probe.supports(NRD)) {
            throw new IllegalStateException("NVIDIA native bridge cannot open NRD: " + probe.reason());
        }
        long handle = nativeOpenNrd(
                context.device().instance().address(),
                context.device().physicalDevice().address(),
                context.device().device().address(),
                context.device().queueFamilyIndex()
        );
        if (handle == 0L) throw new IllegalStateException("NRD native session returned a null handle");
        return handle;
    }

    static long openRtxmu(VulkanFeatureOpenContext context) {
        Objects.requireNonNull(context, "context");
        Probe probe = probe();
        if (!probe.loaded() || !probe.supports(RTX_MEMORY_UTILITY)) {
            throw new IllegalStateException("NVIDIA native bridge cannot open RTXMU: " + probe.reason());
        }
        long handle = nativeOpenRtxmu(
                context.device().instance().address(),
                context.device().physicalDevice().address(),
                context.device().device().address()
        );
        if (handle == 0L) throw new IllegalStateException("RTXMU native session returned a null handle");
        return handle;
    }

    static void closeNrd(long handle) {
        if (handle != 0L) nativeCloseNrd(handle);
    }

    static void closeRtxmu(long handle) {
        if (handle != 0L) nativeCloseRtxmu(handle);
    }

    static native int nativeAbiVersion();

    static native int nativeCapabilityMask();

    static native String nativeDiagnostic();

    static native String nativeStreamlinePreflight(int requestedFeatures);

    static native void nativeCloseStreamlinePreflight();

    static native String nativeStreamlineDiagnostic();

    static native int nativeStreamlineSetVulkanInfo(
            long instance,
            long physicalDevice,
            long device,
            int computeQueueIndex,
            int computeQueueFamily,
            int graphicsQueueIndex,
            int graphicsQueueFamily,
            int opticalFlowQueueIndex,
            int opticalFlowQueueFamily,
            int requiredFeatures,
            boolean useNativeOpticalFlowMode
    );

    static native int nativeStreamlineExecutionFeatureMask();

    static native int nativeStreamlineCreateSwapchain(long device, long createInfo, long output);

    static native void nativeStreamlineDestroySwapchain(long device, long swapchain);

    static native int nativeStreamlineGetSwapchainImages(
            long device, long swapchain, long count, long images
    );

    static native int nativeStreamlineAcquireNextImage(
            long device, long swapchain, long timeout, long semaphore, long fence, long imageIndex
    );

    static native int nativeStreamlineQueuePresent(
            long queue, long presentInfo, int generatedFrames, long frameSequence
    );

    static native void nativeRetireStreamlineFrame(long frameSequence);

    static native long[] nativeStreamlineFrameGenerationStats();

    static native long nativeOpenNrd(
            long instance,
            long physicalDevice,
            long device,
            int queueFamilyIndex
    );

    static native long nativeOpenRtxmu(long instance, long physicalDevice, long device);

    static native long[] nativeRtxmuRecordBuild(
            long session,
            long commandBuffer,
            long[] positionAddresses,
            long[] indexAddresses,
            int[] vertexCounts,
            int[] primitiveCounts,
            boolean[] opaque
    );

    static native long[] nativeRtxmuRecordCompaction(
            long session, long commandBuffer, long accelerationStructureId
    );

    static native void nativeRtxmuGarbageCollect(
            long session, long accelerationStructureId
    );

    static native void nativeRtxmuRemove(long session, long accelerationStructureId);

    static native void nativeRecordPostTrace(
            long session,
            long commandBuffer,
            long traceOutputImage,
            long publishedOutputImage,
            long normalDepthImage,
            long motionImage,
            long normalRoughnessImage,
            long viewZImage,
            long motionVectorsImage,
            long diffuseRadianceHitDistanceImage,
            long specularRadianceHitDistanceImage,
            long denoisedDiffuseRadianceHitDistanceImage,
            long denoisedSpecularRadianceHitDistanceImage,
            boolean denoisingActive,
            int width,
            int height,
            NrdFrameConstants constants
    );

    static native void nativeRecordStreamlineFrame(
            long commandBuffer,
            int streamlineFeature,
            int reconstructionMode,
            int quality,
            long sequence,
            VulkanStreamlineFrameResourceContract resources,
            StreamlineFrameConstants constants
    );

    static native void nativeRecordStreamlineFrameGeneration(
            long commandBuffer,
            long sequence,
            VulkanFrameReconstructionResourceContract.Image hudlessColor,
            VulkanFrameReconstructionResourceContract.Image depth,
            VulkanFrameReconstructionResourceContract.Image motionVectors,
            StreamlineFrameConstants constants
    );

    static native long[] nativeAwaitStreamlineFrameInputReuse(long frameSequence);

    static native void nativeDisableStreamlineFrameGeneration();

    static native void nativeBeginStreamlineFramePreparation(long frameSequence);

    static native void nativeCancelStreamlineFramePreparation(long frameSequence);

    static native void nativeBeginStreamlineFrameSubmission(long frameSequence);

    static native void nativeEndStreamlineFrameSubmission(long frameSequence);

    static native int[] nativeStreamlineDlssOptimalSettings(int quality, int outputWidth, int outputHeight);

    static native void nativeCloseNrd(long session);

    static native void nativeCloseRtxmu(long session);

    record Probe(boolean loaded, int capabilityMask, String reason) {
        Probe {
            reason = Objects.requireNonNull(reason, "reason").trim();
            if (reason.isEmpty()) throw new IllegalArgumentException("native probe reason must not be blank");
            if (!loaded && capabilityMask != 0) {
                throw new IllegalArgumentException("unloaded native bridge cannot advertise capabilities");
            }
        }

        boolean supports(int mask) {
            return loaded && (capabilityMask & mask) == mask;
        }
    }

    private static final class Loader {
        private static final Probe PROBE = load();

        private Loader() {
        }

        private static Probe load() {
            try {
                NvidiaNativeLibraryLoader.load();
                int actualAbi = nativeAbiVersion();
                if (actualAbi != ABI_VERSION) {
                    return new Probe(
                            false,
                            0,
                            "NVIDIA bridge ABI mismatch: expected=" + ABI_VERSION + ", actual=" + actualAbi
                    );
                }
                int capabilities = nativeCapabilityMask();
                String diagnostic = Objects.requireNonNullElse(
                        nativeDiagnostic(), "NVIDIA bridge returned no diagnostic"
                );
                return new Probe(true, capabilities, diagnostic);
            } catch (LinkageError | RuntimeException failure) {
                return new Probe(false, 0, failure.getClass().getSimpleName() + ": " + failure.getMessage());
            }
        }
    }
}
