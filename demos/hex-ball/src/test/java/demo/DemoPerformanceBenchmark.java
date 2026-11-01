package demo;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.locks.LockSupport;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFrameInterop;

/** Identical demo geometry and frozen physics, with startup and warmup excluded from GPU samples.
 * Uses the public API shared with 5.1.1 so the same class can measure both runtime JARs. */
public final class DemoPerformanceBenchmark {
    private static long sequence;

    private DemoPerformanceBenchmark() { }

    public static void main(String[] args) {
        int width = args.length > 0 ? Integer.parseInt(args[0]) : 1920;
        int height = args.length > 1 ? Integer.parseInt(args[1]) : 1080;
        long opened = System.nanoTime();
        try (Renderer renderer = Renderer.open(DemoRendererProfile.benchmark())) {
            double openMs = (System.nanoTime() - opened) / 1e6;
            var interop = renderer.extension(VulkanFrameInterop.class).orElseThrow();
            var scene = new DemoScene();
            long revision = renderer.apply(scene.initialTransaction()).acceptedSceneRevision();
            var batch = scene.primitiveBatch(new HexPhysics(), FramePrimitiveBatch.empty());
            var frame = DemoRendererProfile.frame(0, width, height,
                            DemoRendererProfile.camera(width, height, 15.4))
                    .minimumSceneRevision(revision).primitiveBatch(batch)
                    .environment(DemoRendererProfile.environment())
                    .antiAliasing(DemoRendererProfile.antiAliasing(1)).build();
            range(renderer, interop, frame, 1);
            System.out.printf(Locale.ROOT, "DemoBenchmark startup openMs=%.2f firstFrameMs=%.2f%n",
                    openMs, (System.nanoTime() - opened) / 1e6);
            range(renderer, interop, frame, 2400);
            for (int round = 1; round <= 3; round++) {
                var before = renderer.diagnostics().frameGpuTiming();
                long started = System.nanoTime();
                range(renderer, interop, frame, 600);
                long elapsed = System.nanoTime() - started;
                var after = renderer.diagnostics().frameGpuTiming();
                long samples = after.completedSamples() - before.completedSamples();
                double gpuNs = ((double) after.averageTotalNanos() * after.completedSamples()
                        - (double) before.averageTotalNanos() * before.completedSamples()) / samples;
                if (samples != 600 || after.failedSamples() != 0 || !Double.isFinite(gpuNs) || gpuNs <= 0)
                    throw new AssertionError("incomplete GPU timing: " + after);
                System.out.printf(Locale.ROOT,
                        "DemoBenchmark round=%d extent=%dx%d spp=1 frames=%d gpuMs=%.4f gpuFps=%.2f completedFps=%.2f%n",
                        round, width, height, samples, gpuNs / 1e6, 1e9 / gpuNs, 600e9 / elapsed);
            }
            var health = renderer.health();
            if (health.status() != Renderer.Status.READY || health.activeFailure().isPresent()
                    || !health.obligations().equals(RendererHealth.ResourceObligations.none()))
                throw new AssertionError("unhealthy renderer after benchmark: " + health);
        }
    }

    private static void range(Renderer renderer, VulkanFrameInterop interop, RenderFrameRequest template, int count) {
        long end = sequence + count;
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (renderer.diagnostics().latestCompletedFrameSequence() < end - 1) {
            while (sequence < end) {
                var request = DemoRendererProfile.frame(sequence, template.width(), template.height(), template.camera())
                        .minimumSceneRevision(template.minimumSceneRevision())
                        .primitiveBatch(template.primitiveBatch()).environment(template.environment())
                        .antiAliasing(template.antiAliasing()).build();
                if (!(renderer.trySubmit(request)
                        instanceof Renderer.FrameSubmitted)) break;
                sequence++;
            }
            drain(interop);
            if (System.nanoTime() >= deadline) throw new AssertionError("demo benchmark timed out");
            LockSupport.parkNanos(25_000);
        }
        drain(interop);
    }

    private static void drain(VulkanFrameInterop interop) {
        while (interop.pollLatestFrame() instanceof VulkanFrameInterop.FrameAvailable available)
            available.lease().close();
    }
}
