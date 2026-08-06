package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.api.DenoisingOptions;
import top.ceroxe.rt.renderer.api.FrameGenerationOptions;
import top.ceroxe.rt.renderer.api.FrameOutputFormat;
import top.ceroxe.rt.renderer.api.FrameReconstructionOptions;
import top.ceroxe.rt.renderer.api.RayTracingRendererConfig;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.RendererFeaturePreference;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Feature;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Status;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Technology;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFramePresenter;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFramePresenterConfig;
import top.ceroxe.rt.renderer.nvidia.NvidiaStreamlineDiagnostics;
import top.ceroxe.rt.renderer.nvidia.WindowsChildProcessIsolation;

import java.util.Optional;
import java.util.concurrent.locks.LockSupport;

/** Real swapchain gate for Streamline DLSS Frame Generation and static Multi Frame Generation. */
public final class NvidiaStreamlineFrameGenerationNativeSelfTest {
    private static final int PRESENT_COUNT = 12;
    // DLSS-G requires a supported backbuffer size; the 640x360 extent used by reconstruction
    // gates is below the generator's minimum and must not be used for this acceptance test.
    private static final int FRAME_GENERATION_WIDTH = 1280;
    private static final int FRAME_GENERATION_HEIGHT = 720;
    private static final long TIMEOUT_NANOS = 30_000_000_000L;
    private static final long BASE_FRAME_PERIOD_NANOS = 1_000_000_000L / 60L;

    private NvidiaStreamlineFrameGenerationNativeSelfTest() {
    }

    public static void main(String[] arguments) throws Exception {
        WindowsChildProcessIsolation.preventGradlePipeInheritance();
        boolean multiFrame = arguments.length == 1 && "mfg".equalsIgnoreCase(arguments[0]);
        boolean adaptive = arguments.length == 1 && "adaptive".equalsIgnoreCase(arguments[0]);
        boolean composed = arguments.length == 1 && "composed".equalsIgnoreCase(arguments[0]);
        if (arguments.length > 1 || (arguments.length == 1 && !multiFrame && !adaptive && !composed)) {
            throw new IllegalArgumentException(
                    "usage: NvidiaStreamlineFrameGenerationNativeSelfTest [mfg|adaptive|composed]"
            );
        }
        int requestedGeneratedFrames = multiFrame ? 2 : 1;
        String featureName = composed
                ? "NRD + DLSS SR + adaptive DLSS FG/MFG"
                : adaptive ? "adaptive DLSS FG/MFG" : multiFrame ? "DLSS MFG 3x" : "DLSS FG 2x";
        VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
        NvidiaGpuSceneNativeTestSupport.require(
                capability.hardwareRayTracingReady(),
                featureName + " gate requires Vulkan RT: " + capability.summary()
        );

        FrameGenerationOptions generation = adaptive || composed
                ? FrameGenerationOptions.productionDefault()
                : FrameGenerationOptions.builder()
                .preference(RendererFeaturePreference.REQUIRED)
                .mode(multiFrame
                        ? FrameGenerationOptions.Mode.MULTI_FRAME_GENERATION
                        : FrameGenerationOptions.Mode.FRAME_GENERATION)
                .multiplier(multiFrame
                        ? FrameGenerationOptions.Multiplier.THREE_X
                        : FrameGenerationOptions.Multiplier.TWO_X)
                .fallback(FrameGenerationOptions.Fallback.NONE)
                .build();
        RayTracingRendererConfig configuration = RayTracingRendererConfig.builder()
                .maxFramesInFlight(3)
                .frameOutputFormat(composed
                        ? FrameOutputFormat.LINEAR_HDR_RGBA16F : FrameOutputFormat.SDR_RGBA8)
                .frameReconstruction(composed
                        ? FrameReconstructionOptions.builder()
                        .preference(RendererFeaturePreference.REQUIRED)
                        .mode(FrameReconstructionOptions.Mode.SUPER_RESOLUTION)
                        .quality(FrameReconstructionOptions.Quality.BALANCED)
                        .fallback(FrameReconstructionOptions.Fallback.NONE)
                        .build()
                        : FrameReconstructionOptions.disabled())
                .frameGeneration(generation)
                .denoising(composed
                        ? DenoisingOptions.builder()
                        .preference(RendererFeaturePreference.REQUIRED)
                        .strategy(DenoisingOptions.Strategy.BALANCED)
                        .builtInTemporalFallback(false)
                        .build()
                        : DenoisingOptions.disabled())
                .validationEnabled(Boolean.getBoolean("rtrenderer.validation"))
                .gpuTimingsEnabled(false)
                .build();
        VulkanGpuSceneRenderingSession session = VulkanGpuSceneRenderingSession.open(
                capability, configuration, RendererRtDiagnostics.noop()
        );
        VulkanRendererHost renderer = new VulkanRendererHost(configuration, session);
        try {
            NvidiaGpuSceneNativeTestSupport.require(
                    session.featureCapabilities().feature(Feature.FRAME_GENERATION).status()
                            == Status.AVAILABLE,
                    featureName + " must remain armed before a generated frame is actually presented"
            );
            if (composed) {
                NvidiaGpuSceneNativeTestSupport.require(
                        session.featureCapabilities().feature(Feature.DENOISING).status() == Status.AVAILABLE,
                        "composed gate did not arm NRD before its first dispatch"
                );
                NvidiaGpuSceneNativeTestSupport.require(
                        session.featureCapabilities().feature(Feature.FRAME_RECONSTRUCTION).status()
                                == Status.AVAILABLE,
                        "composed gate did not arm DLSS SR before its first evaluate"
                );
            }
            renderer.apply(NvidiaGpuSceneNativeTestSupport.scene());
            VulkanFramePresenterConfig presenterConfiguration = VulkanFramePresenterConfig.builder()
                    .title("RTRendererAPI " + featureName + " Native Gate")
                    .initialExtent(
                            FRAME_GENERATION_WIDTH,
                            FRAME_GENERATION_HEIGHT
                    )
                    .resizable(false)
                    .presentMode(VulkanFramePresenterConfig.PresentMode.UNCAPPED)
                    .maximumFramesQueuedAhead(2)
                    .build();
            try (VulkanFramePresenter presenter = VulkanFramePresenter.open(
                    renderer, presenterConfiguration
            )) {
                long nextFrameDeadline = System.nanoTime();
                for (long sequence = 0L; sequence < PRESENT_COUNT; sequence++) {
                    if (sequence > 0L) {
                        nextFrameDeadline += BASE_FRAME_PERIOD_NANOS;
                        awaitBaseFrameDeadline(nextFrameDeadline);
                    }
                    RenderFrameRequest frame = NvidiaGpuSceneNativeTestSupport.frame(
                            sequence,
                            FRAME_GENERATION_WIDTH,
                            FRAME_GENERATION_HEIGHT,
                            NvidiaGpuSceneNativeTestSupport.camera(
                                    sequence * 0.025,
                                    (sequence - 4L) * 0.0025,
                                    FRAME_GENERATION_WIDTH,
                                    FRAME_GENERATION_HEIGHT
                            )
                    );
                    NvidiaGpuSceneNativeTestSupport.awaitFrameAdmission(renderer, frame, featureName);
                    VulkanFramePresenter.PresentationResult result = awaitPresentation(
                            renderer, presenter, featureName
                    );
                    NvidiaGpuSceneNativeTestSupport.require(
                            result.frameSequence() == sequence,
                            featureName + " presented an unexpected frame sequence: " + result
                    );
                    NvidiaGpuSceneNativeTestSupport.require(
                            result.outcome() == VulkanFramePresenter.Outcome.PRESENTED,
                            featureName + " did not reach the platform presentation queue: " + result
                    );
                }

                NvidiaStreamlineDiagnostics.FrameGenerationSnapshot stats =
                        NvidiaStreamlineDiagnostics.frameGenerationSnapshot();
                long presenterCalls = presenter.performanceSnapshot().presentSamples();
                NvidiaGpuSceneNativeTestSupport.require(
                        presenterCalls == PRESENT_COUNT,
                        "presenter did not issue exactly one application present per rendered frame: "
                                + presenterCalls
                );
                NvidiaGpuSceneNativeTestSupport.require(
                        stats.proxyPresentCalls() == presenterCalls,
                        "manual-hooking proxy cadence diverged from presenter cadence: " + stats
                );
                NvidiaGpuSceneNativeTestSupport.require(
                        stats.stateQueriesHealthy(),
                        "DLSS-G state queries never succeeded or reported a failure: " + stats
                );
                NvidiaGpuSceneNativeTestSupport.require(
                        stats.maxFramesToGenerate() >= requestedGeneratedFrames,
                        featureName + " exceeds the runtime multiplier limit: " + stats
                );
                if (adaptive || composed) {
                    requestedGeneratedFrames = Math.min(3, stats.maxFramesToGenerate());
                }
                NvidiaGpuSceneNativeTestSupport.require(
                        stats.lastRequestedGeneratedFrames() == requestedGeneratedFrames,
                        featureName + " lost its configured multiplier before JNI present: " + stats
                );
                NvidiaGpuSceneNativeTestSupport.require(
                        stats.configuredGeneratedFrames() == requestedGeneratedFrames,
                        featureName + " did not commit the requested multiplier to Streamline: " + stats
                );
                NvidiaGpuSceneNativeTestSupport.require(
                        stats.active(),
                        featureName + " produced no authoritative generated-frame presentation: " + stats
                );
                NvidiaGpuSceneNativeTestSupport.require(
                        stats.maxGeneratedFramesInSample() >= (multiFrame || composed
                                ? 1 : requestedGeneratedFrames),
                        featureName + " never delivered authoritative generated-frame evidence: " + stats
                );
                NvidiaGpuSceneNativeTestSupport.require(
                        session.featureCapabilities().feature(Feature.FRAME_GENERATION).status()
                                == Status.ACTIVE,
                        featureName + " capability did not activate from actual presentation evidence"
                );
                if (composed) {
                    NvidiaGpuSceneNativeTestSupport.require(
                            session.featureCapabilities().feature(Feature.DENOISING).status() == Status.ACTIVE,
                            "NRD left ACTIVE during composed generated presentation"
                    );
                    NvidiaGpuSceneNativeTestSupport.require(
                            session.featureCapabilities().feature(Feature.FRAME_RECONSTRUCTION).status()
                                    == Status.ACTIVE,
                            "DLSS SR did not activate during composed generated presentation"
                    );
                    Status expectedMultiFrameStatus = stats.maxGeneratedFramesInSample() > 1
                            ? Status.ACTIVE : Status.AVAILABLE;
                    NvidiaGpuSceneNativeTestSupport.require(
                            session.featureCapabilities().technology(Technology.MULTI_FRAME_GENERATION).status()
                                    == expectedMultiFrameStatus,
                            "composed gate published an MFG state without matching presentation evidence"
                    );
                }
                System.out.println("NvidiaStreamlineFrameGenerationNativeSelfTest passed: feature="
                        + featureName + ", device=" + capability.preferredDevice().name()
                        + ", stats=" + stats);
            }
        } finally {
            renderer.close();
        }
    }

    private static VulkanFramePresenter.PresentationResult awaitPresentation(
            VulkanRendererHost renderer,
            VulkanFramePresenter presenter,
            String featureName
    ) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT_NANOS;
        do {
            presenter.pollEvents();
            Optional<VulkanFramePresenter.PresentationResult> result = presenter.presentLatestFrame();
            if (result.isPresent()) return result.orElseThrow();
            renderer.diagnostics();
            Thread.sleep(1L);
        } while (System.nanoTime() < deadline);
        throw new AssertionError(featureName + " frame did not reach the presenter before timeout");
    }

    private static void awaitBaseFrameDeadline(long deadlineNanos) {
        while (true) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0L) return;
            LockSupport.parkNanos(remaining);
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("MFG cadence gate was interrupted");
            }
        }
    }
}
