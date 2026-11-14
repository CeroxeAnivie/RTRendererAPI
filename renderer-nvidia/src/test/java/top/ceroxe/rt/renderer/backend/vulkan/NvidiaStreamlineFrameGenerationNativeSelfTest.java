package top.ceroxe.rt.renderer.backend.vulkan;

import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.api.DenoisingOptions;
import top.ceroxe.rt.renderer.api.FrameGenerationOptions;
import top.ceroxe.rt.renderer.api.FrameOutputFormat;
import top.ceroxe.rt.renderer.api.FrameReconstructionOptions;
import top.ceroxe.rt.renderer.api.RendererConfig;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.RendererFeaturePreference;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Feature;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Status;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities.Technology;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFramePresenter;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFramePresenterConfig;
import top.ceroxe.rt.renderer.nvidia.NvidiaStreamlineDiagnostics;
import top.ceroxe.rt.renderer.nvidia.StreamlineNativeTestDiagnostics;
import top.ceroxe.rt.renderer.nvidia.WindowsChildProcessIsolation;

import java.util.Optional;
import java.util.concurrent.locks.LockSupport;

/** Real swapchain gate for Streamline DLSS Frame Generation and static Multi Frame Generation. */
public final class NvidiaStreamlineFrameGenerationNativeSelfTest {
    private static final int MINIMUM_PRESENT_COUNT = 12;
    // Initialization and display pacing are asynchronous. Measure wall time rather than assuming
    // a fixed number of presents implies a completed cold-start interval. Zero output still fails.
    private static final int BASE_FRAME_RATE = Integer.getInteger("rtrenderer.test.baseFps", 60);
    private static final long OUTPUT_DEADLINE_NANOS = 60_000_000_000L;
    // DLSS-G requires a supported backbuffer size; the 640x360 extent used by reconstruction
    // gates is below the generator's minimum and must not be used for this acceptance test.
    private static final int FRAME_GENERATION_WIDTH = 1280;
    private static final int FRAME_GENERATION_HEIGHT = 720;
    private static final long TIMEOUT_NANOS = 30_000_000_000L;
    private static final long BASE_FRAME_PERIOD_NANOS = 1_000_000_000L / Math.max(1, BASE_FRAME_RATE);

    private NvidiaStreamlineFrameGenerationNativeSelfTest() {
    }

    public static void main(String[] arguments) throws Exception {
        WindowsChildProcessIsolation.preventGradlePipeInheritance();
        if (BASE_FRAME_RATE < 10 || BASE_FRAME_RATE > 120) {
            throw new IllegalArgumentException("test base frame rate must be in [10, 120]");
        }
        System.out.println("Frame-generation acceptance base cadence: " + BASE_FRAME_RATE + " FPS");
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
                ? "NRD + DLSS SR + recommended DLSS FG 2x"
                : adaptive ? "adaptive DLSS FG/MFG" : multiFrame ? "DLSS MFG 3x" : "DLSS FG 2x";
        VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
        NvidiaGpuSceneNativeTestSupport.require(
                capability.hardwareRayTracingReady(),
                featureName + " gate requires Vulkan RT: " + capability.summary()
        );

        FrameGenerationOptions generation = composed
                ? FrameGenerationOptions.recommended()
                : FrameGenerationOptions.builder()
                .preference(RendererFeaturePreference.REQUIRED)
                .mode(adaptive ? FrameGenerationOptions.Mode.ADAPTIVE : multiFrame
                        ? FrameGenerationOptions.Mode.MULTI_FRAME_GENERATION
                        : FrameGenerationOptions.Mode.FRAME_GENERATION)
                .multiplier(adaptive ? FrameGenerationOptions.Multiplier.FOUR_X : multiFrame
                        ? FrameGenerationOptions.Multiplier.THREE_X
                        : FrameGenerationOptions.Multiplier.TWO_X)
                .fallback(FrameGenerationOptions.Fallback.NONE)
                .build();
        RendererConfig configuration = RendererConfig.builder()
                // The acceptance may need a bounded warm-up while the asynchronous pacer
                // publishes generated output. Match the presenter lead to the legal renderer
                // ring maximum so that warm-up measures Streamline latency instead of admission
                // backpressure.
                .maxFramesInFlight(16)
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
                    // This gate validates Streamline generation, not presenter backpressure. A
                    // larger legal lead prevents the test producer from deadlocking while the
                    // previous present fence retires at the supported 720p workload.
                    .maximumFramesQueuedAhead(16)
                    .build();
            try (VulkanFramePresenter presenter = VulkanFramePresenter.open(
                    renderer, presenterConfiguration
            )) {
                long nextFrameDeadline = System.nanoTime();
                long presentedFrames = 0L;
                NvidiaStreamlineDiagnostics.FrameGenerationSnapshot stats =
                        NvidiaStreamlineDiagnostics.frameGenerationSnapshot();
                long observedGeneratedFrames = stats.generatedFramesActuallyPresented();
                int consecutiveGeneratedSamples = 0;
                long firstGeneratedSequence = -1L;
                long presentationStarted = System.nanoTime();
                long nextProgress = presentationStarted + 5_000_000_000L;
                long focusedPresents = 0L;
                long foregroundPresents = 0L;
                long firstGeneratedAt = 0L;
                long foregroundNanos = 0L;
                long previousObservation = presentationStarted;
                boolean previouslyForeground = false;
                boolean sustainedOutputAccepted = false;
                for (long sequence = 0L; System.nanoTime() - presentationStarted < OUTPUT_DEADLINE_NANOS; sequence++) {
                    if (sequence > 0L) {
                        nextFrameDeadline = Math.max(nextFrameDeadline + BASE_FRAME_PERIOD_NANOS,
                                System.nanoTime());
                        awaitBaseFrameDeadline(nextFrameDeadline);
                    }
                    double motionSeconds = (System.nanoTime() - presentationStarted) / 1_000_000_000.0;
                    RenderFrameRequest frame = NvidiaGpuSceneNativeTestSupport.frame(
                            sequence,
                            FRAME_GENERATION_WIDTH,
                            FRAME_GENERATION_HEIGHT,
                            NvidiaGpuSceneNativeTestSupport.camera(
                                    Math.sin(motionSeconds * 0.7) * 0.3,
                                    Math.sin(motionSeconds * 0.5) * 0.06,
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
                    presentedFrames++;
                    boolean focused = ((VulkanGlfwFramePresenter) presenter).focused();
                    boolean foreground = ((VulkanGlfwFramePresenter) presenter).foreground();
                    long foregroundObservedAt = System.nanoTime();
                    if (foreground && previouslyForeground) foregroundNanos += foregroundObservedAt - previousObservation;
                    previousObservation = foregroundObservedAt;
                    previouslyForeground = foreground;
                    if (focused) focusedPresents++;
                    if (foreground) foregroundPresents++;
                    stats = NvidiaStreamlineDiagnostics.frameGenerationSnapshot();
                    if (adaptive) {
                        requestedGeneratedFrames = Math.min(3, stats.maxFramesToGenerate());
                    }
                    long generatedDelta = stats.generatedFramesActuallyPresented()
                            - observedGeneratedFrames;
                    observedGeneratedFrames = stats.generatedFramesActuallyPresented();
                    if (generatedDelta > 0L && firstGeneratedSequence < 0L) {
                        firstGeneratedSequence = sequence;
                        firstGeneratedAt = System.nanoTime();
                        System.out.println(featureName + " first generated output: sequence=" + sequence
                                + ", elapsedMillis=" + (System.nanoTime() - presentationStarted) / 1_000_000L
                                + ", focused=" + focused + ", foreground=" + foreground);
                    }
                    long observedAt = System.nanoTime();
                    if (observedAt >= nextProgress) {
                        System.out.println(featureName + " awaiting sustained output: elapsedMillis="
                                + (observedAt - presentationStarted) / 1_000_000L
                                + ", actualBaseFps=" + presentedFrames * 1_000_000_000.0 / (observedAt - presentationStarted)
                                + ", focusedPresents=" + focusedPresents + "/" + presentedFrames
                                + ", foregroundPresents=" + foregroundPresents + "/" + presentedFrames
                                + ", generated=" + stats.generatedFramesActuallyPresented()
                                + ", status=" + stats.status());
                        nextProgress = observedAt + 5_000_000_000L;
                    }
                    consecutiveGeneratedSamples = foreground && generatedDelta >= requestedGeneratedFrames
                            ? consecutiveGeneratedSamples + 1 : 0;
                    // DLSS-G proxy presentation is asynchronous. Keep supplying real game frames
                    // until the pacer publishes a sustained native-to-generated cadence. A single
                    // late output is not enough to certify FG 2x, and the fixed upper bound still
                    // fails a genuinely inactive or severely degraded implementation.
                    if (presentedFrames >= MINIMUM_PRESENT_COUNT
                            && firstGeneratedAt != 0L && System.nanoTime() - firstGeneratedAt >= 5_000_000_000L
                            && consecutiveGeneratedSamples >= MINIMUM_PRESENT_COUNT) {
                        sustainedOutputAccepted = true;
                        break;
                    }
                }

                long presenterCalls = presenter.performanceSnapshot().presentSamples();
                NvidiaGpuSceneNativeTestSupport.require(
                        presenterCalls == presentedFrames,
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
                        stats.generationRequestMisses() == 0L,
                        "DLSS-G generation requests missed their tagged input: " + stats
                );
                NvidiaGpuSceneNativeTestSupport.require(
                        stats.maxFramesToGenerate() >= requestedGeneratedFrames,
                        featureName + " exceeds the runtime multiplier limit: " + stats
                );
                NvidiaGpuSceneNativeTestSupport.require(
                        stats.lastRequestedGeneratedFrames() == requestedGeneratedFrames,
                        featureName + " lost its configured multiplier before JNI present: " + stats
                );
                NvidiaGpuSceneNativeTestSupport.require(
                        stats.configuredGeneratedFrames() == requestedGeneratedFrames,
                        featureName + " did not commit the requested multiplier to Streamline: " + stats
                );
                if (!sustainedOutputAccepted && foregroundNanos < 5_000_000_000L) {
                    throw new AssertionError(featureName + " ENVIRONMENT_NOT_FOREGROUND: insufficient real foreground observation; "
                            + "acceptance is inconclusive, not unsupported and not a pass; foregroundMillis="
                            + foregroundNanos / 1_000_000L + "; " + stats);
                }
                {
                    // Configuration alone is insufficient for either FG or MFG. Both must
                    // prove sustained authoritative output at their requested cadence.
                    NvidiaGpuSceneNativeTestSupport.require(
                            stats.active(),
                            featureName + " NO_OUTPUT_WITHIN_DEADLINE; this does not establish unsupported hardware; "
                                    + "focusedPresents=" + focusedPresents + "/" + presentedFrames + "; " + stats
                                    + "; foregroundPresents=" + foregroundPresents + "/" + presentedFrames
                                    + "; native diagnostics: " + StreamlineNativeTestDiagnostics.warnings()
                    );
                    NvidiaGpuSceneNativeTestSupport.require(
                            sustainedOutputAccepted && consecutiveGeneratedSamples >= MINIMUM_PRESENT_COUNT,
                            featureName + " did not sustain the requested generated-frame cadence: " + stats
                                    + "; native diagnostics: " + StreamlineNativeTestDiagnostics.warnings()
                    );
                    NvidiaGpuSceneNativeTestSupport.require(
                            stats.maxGeneratedFramesInSample() >= (composed
                                    ? 1 : requestedGeneratedFrames),
                            featureName + " never delivered authoritative generated-frame evidence: " + stats
                    );
                    NvidiaGpuSceneNativeTestSupport.require(
                            session.featureCapabilities().feature(Feature.FRAME_GENERATION).status()
                                    == Status.ACTIVE,
                            featureName + " capability did not activate from actual presentation evidence"
                    );
                }
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
                    Status expectedMultiFrameStatus = Status.DISABLED;
                    NvidiaGpuSceneNativeTestSupport.require(
                            session.featureCapabilities().technology(Technology.MULTI_FRAME_GENERATION).status()
                                    == expectedMultiFrameStatus,
                            "composed gate published an MFG state without matching presentation evidence"
                    );
                }
                System.out.println("NvidiaStreamlineFrameGenerationNativeSelfTest passed: feature="
                        + featureName + ", device=" + capability.preferredDevice().name()
                        + ", presentedFrames=" + presentedFrames
                        + ", firstGeneratedSequence=" + firstGeneratedSequence
                        + ", elapsedMillis=" + (System.nanoTime() - presentationStarted) / 1_000_000L
                        + ", focusedPresents=" + focusedPresents + "/" + presentedFrames
                        + ", foregroundPresents=" + foregroundPresents + "/" + presentedFrames
                        + ", consecutiveGeneratedSamples=" + consecutiveGeneratedSamples
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
