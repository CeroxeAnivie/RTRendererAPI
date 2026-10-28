package demo;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.locks.LockSupport;
import top.ceroxe.rt.renderer.api.*;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFrameInterop;

/** Frozen demo geometry. Measures headless finite-batch completion throughput, including CPU
 * submission, backpressure, polling, queue fill and drain. No presentation or generated frames. */
public final class DemoPerformanceBenchmark {
    private static long sequence;

    private DemoPerformanceBenchmark() { }

    public static void main(String[] args) {
        int width = args.length > 0 ? Integer.parseInt(args[0]) : 1920;
        int height = args.length > 1 ? Integer.parseInt(args[1]) : 1080;
        sequence = 0;
        long opened = System.nanoTime();
        try (Renderer renderer = Renderer.open(DemoRendererProfile.benchmark())) {
            double openMs = (System.nanoTime() - opened) / 1e6;
            var interop = renderer.extension(VulkanFrameInterop.class).orElseThrow();
            long assetStarted = System.nanoTime();
            var scene = new DemoScene();
            var transaction = scene.initialTransaction();
            var batch = scene.primitiveBatch(new HexPhysics(), FramePrimitiveBatch.empty());
            double assetBuildMs = (System.nanoTime() - assetStarted) / 1e6;
            long admissionStarted = System.nanoTime();
            long revision = renderer.apply(transaction).acceptedSceneRevision();
            double admissionMs = (System.nanoTime() - admissionStarted) / 1e6;
            var frame = DemoRendererProfile.frame(0, width, height,
                            DemoRendererProfile.camera(width, height, 15.4))
                    .minimumSceneRevision(revision).primitiveBatch(batch)
                    .environment(DemoRendererProfile.environment())
                    .antiAliasing(DemoRendererProfile.antiAliasing(1)).build();
            long firstStarted = System.nanoTime();
            range(renderer, interop, frame, 1);
            System.out.printf(Locale.ROOT, "DemoBenchmark startup openMs=%.2f assetBuildMs=%.2f "
                    + "sceneAdmissionMs=%.2f firstCompletedBatchMs=%.2f startupToFirstCompletedMs=%.2f%n",
                    openMs, assetBuildMs, admissionMs, (System.nanoTime() - firstStarted) / 1e6,
                    (System.nanoTime() - opened) / 1e6);
            System.out.println("DemoBenchmark mode=headless-native-finite-batch slots=8 temporal=false denoising=false "
                    + "reconstruction=false frameGeneration=false lowLatency=false SER=false "
                    + "gpuTimingScope=submission-interval displayedFps=unmeasured");
            range(renderer, interop, frame, 2400);
            long totalElapsed = 0;
            for (int round = 1; round <= 3; round++) {
                var before = renderer.diagnostics().frameGpuTiming();
                long started = System.nanoTime();
                range(renderer, interop, frame, 600);
                long elapsed = System.nanoTime() - started;
                var after = renderer.diagnostics().frameGpuTiming();
                long samples = after.completedSamples() - before.completedSamples();
                double gpuNs = ((double) after.averageTotalNanos() * after.completedSamples()
                        - (double) before.averageTotalNanos() * before.completedSamples()) / samples;
                if (samples != 600 || after.failedSamples() != before.failedSamples()
                        || after.droppedSamples() != before.droppedSamples() || !Double.isFinite(gpuNs) || gpuNs <= 0)
                    throw new AssertionError("incomplete GPU timing: " + after);
                totalElapsed = Math.addExact(totalElapsed, elapsed);
                double roundingBound = (after.completedSamples() + (double) before.completedSamples()) / samples;
                System.out.printf(Locale.ROOT,
                        "DemoBenchmark round=%d extent=%dx%d spp=1 frames=%d gpuSubmissionMsEstimate=%.6f "
                        + "gpuMeanRoundingBoundNs=%.3f wallMs=%.3f completedNativeBatchFps=%.2f%n",
                        round, width, height, samples, gpuNs / 1e6, roundingBound, elapsed / 1e6, 600e9 / elapsed);
            }
            System.out.printf(Locale.ROOT, "DemoBenchmark aggregate frames=1800 measuredWallMs=%.3f "
                    + "completedNativeBatchFps=%.2f%n", totalElapsed / 1e6, 1800e9 / totalElapsed);
            var health = renderer.health();
            if (health.status() != Renderer.Status.READY || health.activeFailure().isPresent()
                    || !health.obligations().equals(RendererHealth.ResourceObligations.none()))
                throw new AssertionError("unhealthy renderer after benchmark: " + health);
        }
    }

    private static void range(Renderer renderer, VulkanFrameInterop interop, RenderFrameRequest template, int count) {
        long end = Math.addExact(sequence, count);
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
            if (sequence < end || renderer.diagnostics().latestCompletedFrameSequence() < end - 1)
                LockSupport.parkNanos(25_000);
        }
        drain(interop);
    }

    private static void drain(VulkanFrameInterop interop) {
        while (interop.pollLatestFrame() instanceof VulkanFrameInterop.FrameAvailable available)
            available.lease().close();
    }
}
