package demo;

import java.awt.GraphicsEnvironment;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import javax.swing.JOptionPane;

import top.ceroxe.rt.renderer.api.AntiAliasingState;
import top.ceroxe.rt.renderer.api.CameraState;
import top.ceroxe.rt.renderer.api.CpuFrame;
import top.ceroxe.rt.renderer.api.EnvironmentState;
import top.ceroxe.rt.renderer.api.FramePrimitiveBatch;
import top.ceroxe.rt.renderer.api.Renderer;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.RenderingFeatureCapabilities;
import top.ceroxe.rt.renderer.api.RendererBootstrap;
import top.ceroxe.rt.renderer.api.RendererDiagnostics;
import top.ceroxe.rt.renderer.api.RendererHealth;
import top.ceroxe.rt.renderer.api.SubmissionRejectedException;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFramePresenter;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFramePresenterConfig;

public final class HexBallDemo {
    private static final Duration READER_JOIN_TIMEOUT = Duration.ofSeconds(5);
    private static final double CAMERA_DISTANCE = 15.4;
    /* Must match VulkanFramePresenterConfig.maximumFramesQueuedAhead below. */
    private static final long MAX_PRESENTATION_LEAD = 2L;

    private HexBallDemo() {
    }

    public static void main(String[] arguments) throws Exception {
        try {
            run(arguments);
            // AWT's event queue may remain alive after the last JFrame is disposed. This is a
            // standalone demo entry point, so terminate only after renderer/window ownership has
            // been closed successfully; library callers never execute this path.
            if (!GraphicsEnvironment.isHeadless()) System.exit(0);
        } catch (Exception | Error failure) {
            failure.printStackTrace(System.err);
            if (!automatedRun(arguments)) {
                showFatalError(failure);
            }
            throw failure;
        }
    }

    private static boolean automatedRun(String[] arguments) {
        for (String argument : arguments) {
            if ("--benchmark".equals(argument)
                    || argument.startsWith("--frames=")
                    || argument.startsWith("--duration-seconds=")) return true;
        }
        return false;
    }

    private static void run(String[] arguments) throws Exception {
        DemoConfig config = DemoConfig.parse(arguments);
        AtomicBoolean running = new AtomicBoolean(true);
        RenderStats stats = new RenderStats();
        double presentTotalFps = 0.0;

        if (config.benchmark()) {
            DemoGpuBenchmark.run(config, running, stats);
            return;
        }

        var rendererConfig = DemoRendererProfile.interactive(config);

        if (config.cpuPresentation()) {
            try (RenderWindow window = RenderWindow.open(config, running, stats);
                 Renderer renderer = RendererBootstrap.open(rendererConfig)) {
                runRenderer(config, running, stats, window, renderer);
                presentTotalFps = DemoTechnologyHud.presentTotalFps(renderer, stats);
                requireHealthyRenderer(renderer);
            }
        } else {
            try (Renderer renderer = RendererBootstrap.open(rendererConfig)) {
                try (VulkanFramePresenter presenter = VulkanFramePresenter.open(
                        renderer,
                        VulkanFramePresenterConfig.builder()
                                .title("RTRendererAPI Hex Ball - GPU Direct Presentation")
                                .initialExtent(config.width(), config.height())
                                .windowMode(config.windowed()
                                        ? VulkanFramePresenterConfig.WindowMode.WINDOWED
                                        : VulkanFramePresenterConfig.WindowMode.PRIMARY_MONITOR_FULLSCREEN)
                                .presentMode(config.targetFps() == 0
                                         ? VulkanFramePresenterConfig.PresentMode.UNCAPPED
                                         : VulkanFramePresenterConfig.PresentMode.VSYNC)
                                .maximumFramesQueuedAhead(Math.toIntExact(MAX_PRESENTATION_LEAD))
                                .build()
                )) {
                    runGpuPresented(config, running, stats, renderer, presenter);
                    VulkanFramePresenter.PerformanceSnapshot presentation = presenter.performanceSnapshot();
                    System.out.printf(
                            Locale.ROOT,
                            "Presentation timings: mode=%s, acquire=%.3f ms, managed-copy-gpu=%.3f ms, "
                                    + "present-lock=%.3f ms, present-native=%.3f ms%n",
                            presenter.activePresentMode(),
                            presentation.averageAcquireMillis(),
                            presentation.averageManagedCopyGpuMillis(),
                            presentation.averagePresentQueueLockMillis(),
                            presentation.averagePresentNativeCallMillis()
                    );
                    RendererDiagnostics diagnostics = renderer.diagnostics();
                    System.out.println(
                            DemoTechnologyHud.capture(renderer, config, stats, diagnostics).text()
                    );
                    renderer.extension(RenderingFeatureCapabilities.class).ifPresent(capabilities ->
                            System.out.println(
                                    "Frame-generation capability evidence: feature="
                                            + capabilities.feature(
                                                    RenderingFeatureCapabilities.Feature.FRAME_GENERATION
                                            )
                                            + "; mfg="
                                            + capabilities.technology(
                                                    RenderingFeatureCapabilities.Technology.MULTI_FRAME_GENERATION
                                            )
                            )
                    );
                    DemoAcceptanceReporter.printDiagnostics(diagnostics, System.out);
                    presentTotalFps = DemoTechnologyHud.presentTotalFps(
                            diagnostics.frameGenerationEvidence(), stats.framesPerSecond()
                    );
                }
                // The presenter owns deferred proxy-present retirement. Check renderer health only
                // after presenter.close() has waited those fences and returned every final lease.
                RendererHealth finalHealth = renderer.health();
                DemoAcceptanceReporter.printHealth(finalHealth, System.out);
                requireHealthyRenderer(finalHealth);
            }
        }
        running.set(false);
        System.out.println("HexBallDemo: " + stats.summary(presentTotalFps));
    }

    private static void runGpuPresented(
            DemoConfig config,
            AtomicBoolean running,
            RenderStats stats,
            Renderer renderer,
            VulkanFramePresenter presenter
    ) throws InterruptedException {
        presenter.pollEvents();
        VulkanFramePresenter.WindowState initialWindow = presenter.windowState();
        int initialWidth = initialWindow.drawable()
                ? initialWindow.framebufferWidth()
                : config.width();
        int initialHeight = initialWindow.drawable()
                ? initialWindow.framebufferHeight()
                : config.height();
        System.out.printf(
                Locale.ROOT,
                "Demo startup: requested=%dx%d, framebuffer=%dx%d, spp=%d%n",
                config.width(), config.height(), initialWidth, initialHeight, config.samplesPerPixel()
        );
        AtomicLong renderExtent = new AtomicLong(packExtent(initialWidth, initialHeight));
        AtomicReference<RuntimeException> submissionFailure = new AtomicReference<>();
        Thread submissionThread = Thread.ofPlatform()
                .name("rtrenderer-gpu-submission")
                .daemon(true)
                .start(() -> submitGpuFrames(
                        config, running, stats, renderer, renderExtent, submissionFailure
                ));
        presenter.setOverlayText(DemoTechnologyHud.capture(renderer, config, stats).text());
        long runDeadline = runDeadline(config);
        long nextTitleUpdate = 0L;
        try {
            while (running.get() && !runLimitReached(config, stats, runDeadline)) {
                presenter.pollEvents();
                VulkanFramePresenter.WindowState window = presenter.windowState();
                if (window.closeRequested()) {
                    running.set(false);
                    break;
                }
                if (!window.drawable()) {
                    LockSupport.parkNanos(1_000_000L);
                    continue;
                }
                renderExtent.set(packExtent(window.framebufferWidth(), window.framebufferHeight()));
                boolean presented = presentReadyFrame(presenter, stats);
                long now = System.nanoTime();
                if (now >= nextTitleUpdate) {
                    // Diagnostics acquires the renderer lifecycle lock and pumps session
                    // telemetry. Sample it with the HUD instead of serializing every present
                    // against the independent submission thread.
                    RendererDiagnostics diagnostics = renderer.diagnostics();
                    stats.observeGpuTiming(diagnostics.frameGpuTiming());
                    double presentFps = DemoTechnologyHud.presentTotalFps(
                            diagnostics.frameGenerationEvidence(), stats.framesPerSecond()
                    );
                    double gpuFrameCapacityFps = stats.gpuCapacityFramesPerSecond();
                    presenter.setTitle(String.format(
                            Locale.ROOT,
                "RTRendererAPI Hex Ball | %dx%d | %d spp | %s | Output estimate %.1f FPS | GPU frame capacity %.1f FPS (%.2f ms)",
                            window.framebufferWidth(),
                            window.framebufferHeight(),
                            config.samplesPerPixel(),
                            presenter.activePresentMode(),
                            presentFps,
                            gpuFrameCapacityFps,
                            stats.averageGpuMillis()
                    ));
                    presenter.setOverlayText(
                            DemoTechnologyHud.capture(renderer, config, stats, diagnostics).text()
                    );
                    nextTitleUpdate = now + 250_000_000L;
                }
                RuntimeException producerFailure = submissionFailure.get();
                if (producerFailure != null) throw producerFailure;
                if (!presented) LockSupport.parkNanos(25_000L);
            }
        } finally {
            running.set(false);
            submissionThread.interrupt();
            submissionThread.join(READER_JOIN_TIMEOUT.toMillis());
            if (submissionThread.isAlive()) {
                throw new IllegalStateException("GPU submission thread did not terminate before renderer shutdown");
            }
        }
        RuntimeException producerFailure = submissionFailure.get();
        if (producerFailure != null) throw producerFailure;
        while (presentReadyFrame(presenter, stats)) {
            // Retire every already-completed lease before renderer teardown.
        }
        stats.observeGpuTiming(renderer.diagnostics().frameGpuTiming());
    }

    private static void submitGpuFrames(
            DemoConfig config,
            AtomicBoolean running,
            RenderStats stats,
            Renderer renderer,
            AtomicLong renderExtent,
            AtomicReference<RuntimeException> failure
    ) {
        HexPhysics physics = new HexPhysics();
        DemoScene scene = new DemoScene();
        long acceptedRevision;
        try {
            acceptedRevision = renderer.apply(scene.initialTransaction()).acceptedSceneRevision();
        } catch (RuntimeException initializationFailure) {
            failure.compareAndSet(null, initializationFailure);
            running.set(false);
            return;
        }
        EnvironmentState environment = DemoRendererProfile.environment();
        AntiAliasingState antiAliasing = DemoRendererProfile.antiAliasing(config.samplesPerPixel());

        long nextSequence = 0L;
        FramePrimitiveBatch previousBatch = FramePrimitiveBatch.empty();
        FramePrimitiveBatch pendingBatch = null;
        long framePeriodNanos = config.targetFps() == 0
                ? 0L
                : 1_000_000_000L / config.targetFps();
        long nextFrameDeadline = System.nanoTime();
        try {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                while (running.get() && stats.presentationLead() >= MAX_PRESENTATION_LEAD) {
                    // The presenter owns the only retirement signal. Waiting for it keeps the
                    // application cadence stable for Streamline's frame-generation pacer and
                    // prevents ordinary bounded-ring pressure from becoming rejected-frame spam.
                    LockSupport.parkNanos(200_000L);
                }
                if (!running.get() || Thread.currentThread().isInterrupted()) break;
                long now = System.nanoTime();
                if (framePeriodNanos != 0L && now < nextFrameDeadline) {
                    LockSupport.parkNanos(Math.min(nextFrameDeadline - now, 200_000L));
                    continue;
                }
                if (pendingBatch == null) {
                    physics.advanceTo(now);
                    pendingBatch = scene.primitiveBatch(physics, previousBatch);
                }
                long packedExtent = renderExtent.get();
                int width = unpackWidth(packedExtent);
                int height = unpackHeight(packedExtent);
                CameraState camera = DemoRendererProfile.camera(width, height, CAMERA_DISTANCE);
                RenderFrameRequest request = DemoRendererProfile.frame(
                                nextSequence, width, height, camera
                        )
                        .minimumSceneRevision(acceptedRevision)
                        .primitiveBatch(pendingBatch)
                        .environment(environment)
                        .antiAliasing(antiAliasing)
                        .build();
                Renderer.FrameSubmissionAttempt attempt = renderer.trySubmit(request);
                if (attempt instanceof Renderer.FrameSubmitted) {
                    previousBatch = pendingBatch;
                    pendingBatch = null;
                    nextSequence = Math.addExact(nextSequence, 1L);
                    stats.submittedFrames.incrementAndGet();
                    if (framePeriodNanos != 0L) {
                        nextFrameDeadline = Math.max(System.nanoTime(), nextFrameDeadline)
                                + framePeriodNanos;
                    }
                } else {
                    stats.submissionRejected();
                    LockSupport.parkNanos(25_000L);
                }
            }
        } catch (RuntimeException submissionFailure) {
            failure.compareAndSet(null, submissionFailure);
            running.set(false);
        }
    }

    private static long packExtent(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("render extent must be positive");
        return ((long) width << Integer.SIZE) | Integer.toUnsignedLong(height);
    }

    private static int unpackWidth(long packed) {
        return Math.toIntExact(packed >>> Integer.SIZE);
    }

    private static int unpackHeight(long packed) {
        return Math.toIntExact(packed & 0xffff_ffffL);
    }

    private static boolean presentReadyFrame(
            VulkanFramePresenter presenter,
            RenderStats stats
    ) {
        long presentationStart = System.nanoTime();
        var presented = presenter.presentLatestFrame();
        stats.observePresentationCall(System.nanoTime() - presentationStart);
        if (presented.isEmpty()) return false;
        VulkanFramePresenter.PresentationResult result = presented.orElseThrow();
        if (result.outcome() == VulkanFramePresenter.Outcome.PRESENTED) {
            stats.framePresented();
        }
        return true;
    }

    private static void showFatalError(Throwable failure) {
        if (GraphicsEnvironment.isHeadless()) return;
        String message = failure.getMessage() == null
                ? failure.getClass().getName()
                : failure.getClass().getSimpleName() + ": " + failure.getMessage();
        try {
            JOptionPane.showMessageDialog(
                    null,
                    message,
                    "RTRendererAPI " + DemoBuildInfo.version() + " Demo failed",
                    JOptionPane.ERROR_MESSAGE
            );
        } catch (RuntimeException dialogFailure) {
            failure.addSuppressed(dialogFailure);
        }
    }

    private static void requireHealthyRenderer(Renderer renderer) {
        requireHealthyRenderer(renderer.health());
    }

    private static void requireHealthyRenderer(RendererHealth health) {
        if (health.status() != Renderer.Status.READY
                || health.activeFailure().isPresent()
                || !health.obligations().equals(RendererHealth.ResourceObligations.none())) {
            throw new IllegalStateException("renderer finished with unhealthy state: " + health);
        }
    }

    private static void runRenderer(
            DemoConfig config,
            AtomicBoolean running,
            RenderStats stats,
            RenderWindow window,
            Renderer renderer
    ) throws InterruptedException {
        HexPhysics physics = new HexPhysics();
        DemoScene scene = new DemoScene();
        long acceptedRevision = renderer.apply(scene.initialTransaction()).acceptedSceneRevision();
        AtomicReference<RuntimeException> readerFailure = new AtomicReference<>();
        window.publishOverlay(DemoTechnologyHud.capture(renderer, config, stats));

        Thread frameReader = Thread.ofPlatform()
                .name("rtrenderer-cpu-frame-reader")
                .daemon(true)
                .start(() -> readFrames(renderer, window, config, running, stats, readerFailure));

        try {
            renderUncapped(config, running, stats, renderer, physics, scene, acceptedRevision);
        } finally {
            running.set(false);
            frameReader.interrupt();
            frameReader.join(READER_JOIN_TIMEOUT.toMillis());
            if (frameReader.isAlive()) {
                throw new IllegalStateException("CPU frame reader did not terminate before renderer shutdown");
            }
            if (readerFailure.get() != null) {
                throw new IllegalStateException("CPU frame reader failed", readerFailure.get());
            }
            if (config.frameLimit() > 0L) {
                stats.requireVisibleColorContent();
            }
        }
    }

    private static void renderUncapped(
            DemoConfig config,
            AtomicBoolean running,
            RenderStats stats,
            Renderer renderer,
            HexPhysics physics,
            DemoScene scene,
            long initialRevision
    ) {
        CameraState camera = DemoRendererProfile.camera(config.width(), config.height(), 13.8);
        EnvironmentState environment = DemoRendererProfile.environment();
        AntiAliasingState antiAliasing = DemoRendererProfile.antiAliasing(config.samplesPerPixel());

        long nextSequence = 0L;
        FramePrimitiveBatch previousBatch = FramePrimitiveBatch.empty();
        FramePrimitiveBatch pendingBatch = null;
        long nextFrameDeadline = System.nanoTime();
        long runDeadline = runDeadline(config);
        long framePeriodNanos = config.targetFps() == 0
                ? 0L
                : 1_000_000_000L / config.targetFps();

        while (running.get() && !runLimitReached(config, stats, runDeadline)) {
            long now = System.nanoTime();
            if (framePeriodNanos != 0L && now < nextFrameDeadline) {
                LockSupport.parkNanos(nextFrameDeadline - now);
                continue;
            }
            if (pendingBatch == null) {
                physics.advanceTo(System.nanoTime());
                pendingBatch = scene.primitiveBatch(physics, previousBatch);
            }

            RenderFrameRequest request = DemoRendererProfile.frame(
                            nextSequence,
                            config.width(),
                            config.height(),
                            camera
                    )
                    .minimumSceneRevision(initialRevision)
                    .primitiveBatch(pendingBatch)
                    .environment(environment)
                    .antiAliasing(antiAliasing)
                    .build();
            try {
                renderer.submit(request);
                previousBatch = pendingBatch;
                pendingBatch = null;
                nextSequence = Math.addExact(nextSequence, 1L);
                stats.submittedFrames.incrementAndGet();
                long afterSubmit = System.nanoTime();
                if (framePeriodNanos != 0L) {
                    nextFrameDeadline = Math.max(afterSubmit, nextFrameDeadline) + framePeriodNanos;
                }
            } catch (SubmissionRejectedException backpressure) {
                // A rejected sequence is explicitly reusable because the API retained no state.
                stats.submissionRejected();
                LockSupport.parkNanos(200_000L);
            }
        }
    }

    private static boolean runLimitReached(DemoConfig config, RenderStats stats, long deadlineNanos) {
        return (config.frameLimit() > 0L && stats.presentedFrames.get() >= config.frameLimit())
                || (deadlineNanos != Long.MAX_VALUE && System.nanoTime() >= deadlineNanos);
    }

    private static long runDeadline(DemoConfig config) {
        if (config.duration().isZero()) return Long.MAX_VALUE;
        long now = System.nanoTime();
        try {
            return Math.addExact(now, config.duration().toNanos());
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static void readFrames(
            Renderer renderer,
            RenderWindow window,
            DemoConfig config,
            AtomicBoolean running,
            RenderStats stats,
            AtomicReference<RuntimeException> readerFailure
    ) {
        long nextHudRefresh = 0L;
        try {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                Optional<CpuFrame> frame = renderer.pollLatestCpuFrame();
                if (frame.isPresent()) {
                    window.publish(frame.orElseThrow());
                    RendererDiagnostics.FrameGpuTiming timing = renderer.diagnostics().frameGpuTiming();
                    stats.observeGpuTiming(timing);
                    stats.framePresented();
                    long now = System.nanoTime();
                    if (now >= nextHudRefresh) {
                        window.publishOverlay(DemoTechnologyHud.capture(renderer, config, stats));
                        nextHudRefresh = now + 250_000_000L;
                    }
                } else {
                    // This is not a frame limiter; it only avoids burning a CPU core when no
                    // completed GPU readback exists. Submission remains fully uncapped.
                    LockSupport.parkNanos(100_000L);
                }
            }
        } catch (RuntimeException failure) {
            if (running.get()) {
                readerFailure.compareAndSet(null, failure);
                running.set(false);
            }
        }
    }
}
