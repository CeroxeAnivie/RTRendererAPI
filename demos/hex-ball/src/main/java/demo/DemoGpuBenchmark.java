package demo;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import top.ceroxe.rt.renderer.api.AntiAliasingState;
import top.ceroxe.rt.renderer.api.CameraState;
import top.ceroxe.rt.renderer.api.EnvironmentState;
import top.ceroxe.rt.renderer.api.FramePrimitiveBatch;
import top.ceroxe.rt.renderer.api.Renderer;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.RendererBootstrap;
import top.ceroxe.rt.renderer.api.RendererHealth;
import top.ceroxe.rt.renderer.api.SubmissionRejectedException;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFrameInterop;

/** Owns the finite, non-presenting GPU benchmark lifecycle. */
final class DemoGpuBenchmark {
    private static final Duration COMPLETION_TIMEOUT = Duration.ofSeconds(30);

    private DemoGpuBenchmark() {
    }

    static void run(DemoConfig config, AtomicBoolean running, RenderStats stats)
            throws InterruptedException {
        try (Renderer renderer = RendererBootstrap.open(DemoRendererProfile.benchmark())) {
            VulkanFrameInterop interop = renderer.extension(VulkanFrameInterop.class)
                    .orElseThrow(() -> new IllegalStateException(
                            "Vulkan frame interop is unavailable for the GPU benchmark"));
            HexPhysics physics = new HexPhysics();
            DemoScene scene = new DemoScene();
            long acceptedRevision = renderer.apply(scene.initialTransaction()).acceptedSceneRevision();
            CameraState camera = DemoRendererProfile.camera(config.width(), config.height(), 15.4);
            EnvironmentState environment = DemoRendererProfile.environment();
            AntiAliasingState antiAliasing = DemoRendererProfile.antiAliasing(config.samplesPerPixel());
            long nextSequence = 0L;
            FramePrimitiveBatch previousBatch = FramePrimitiveBatch.empty();
            FramePrimitiveBatch pendingBatch = null;

            long deadlineNanos = runDeadline(config);
            while (running.get()
                    && (config.frameLimit() == 0L || stats.submittedFrames.get() < config.frameLimit())
                    && System.nanoTime() < deadlineNanos) {
                discardReadyFrames(interop, renderer, stats);
                if (pendingBatch == null) {
                    physics.advanceTo(System.nanoTime());
                    pendingBatch = scene.primitiveBatch(physics, previousBatch);
                }
                RenderFrameRequest request = DemoRendererProfile.frame(
                                nextSequence, config.width(), config.height(), camera
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
                    stats.observeGpuTiming(renderer.diagnostics().frameGpuTiming());
                } else {
                    stats.submissionRejected();
                    LockSupport.parkNanos(200_000L);
                }
            }
            if (nextSequence > 0L) {
                awaitCompletion(interop, renderer, stats, nextSequence - 1L);
            }
            stats.observeGpuTiming(renderer.diagnostics().frameGpuTiming());
            System.out.println(DemoTechnologyHud.capture(renderer, config, stats).text());
            requireHealthy(renderer);
        } finally {
            running.set(false);
            System.out.println("HexBallDemo GPU benchmark: " + stats.summary());
        }
    }

    private static void awaitCompletion(
            VulkanFrameInterop interop,
            Renderer renderer,
            RenderStats stats,
            long finalFrameSequence
    ) throws InterruptedException {
        long deadline = Math.addExact(System.nanoTime(), COMPLETION_TIMEOUT.toNanos());
        while (renderer.diagnostics().latestCompletedFrameSequence() < finalFrameSequence) {
            if (Thread.interrupted()) throw new InterruptedException("benchmark completion interrupted");
            discardReadyFrames(interop, renderer, stats);
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException(
                        "GPU benchmark timed out waiting for frame " + finalFrameSequence);
            }
            LockSupport.parkNanos(50_000L);
        }
        discardReadyFrames(interop, renderer, stats);
    }

    private static void discardReadyFrames(
            VulkanFrameInterop interop,
            Renderer renderer,
            RenderStats stats
    ) {
        while (true) {
            VulkanFrameInterop.FramePollResult polled = interop.pollLatestFrame();
            if (!(polled instanceof VulkanFrameInterop.FrameAvailable available)) return;
            available.lease().close();
            stats.observeGpuTiming(renderer.diagnostics().frameGpuTiming());
        }
    }

    private static void requireHealthy(Renderer renderer) {
        RendererHealth health = renderer.health();
        if (health.status() != Renderer.Status.READY
                || health.activeFailure().isPresent()
                || !health.obligations().equals(RendererHealth.ResourceObligations.none())) {
            throw new IllegalStateException("renderer finished with unhealthy state: " + health);
        }
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
}
