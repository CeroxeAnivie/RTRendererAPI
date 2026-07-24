package top.ceroxe.mcvulkanrt.renderer.backend.vulkan;

import top.ceroxe.mcvulkanrt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.mcvulkanrt.renderer.api.GpuFrameLease;
import top.ceroxe.mcvulkanrt.renderer.api.RayTracingRenderer;
import top.ceroxe.mcvulkanrt.renderer.api.RayTracingRendererConfig;
import top.ceroxe.mcvulkanrt.renderer.api.RendererBootstrap;
import top.ceroxe.mcvulkanrt.renderer.api.RenderFrameRequest;
import top.ceroxe.mcvulkanrt.renderer.api.RendererDiagnostics;
import top.ceroxe.mcvulkanrt.renderer.api.SubmissionRejectedException;

/** Sustained no-readback throughput gate for the production public GPUScene frame path. */
public final class VulkanGpuSceneThroughputNativeSelfTest {
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int WARMUP_FRAMES = 32;
    private static final int MEASURED_FRAMES = 256;
    private static final int LOW_WINDOW_FRAMES = 16;
    private static final double REQUIRED_AVERAGE_FPS = 1_000.0;
    private static final double REQUIRED_LOW_WINDOW_FPS = 500.0;
    private static final long TIMEOUT_NANOS = 30_000_000_000L;

    private VulkanGpuSceneThroughputNativeSelfTest() {
    }

    public static void main(String[] arguments) throws Exception {
        VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
        require(capability.hardwareRayTracingReady(),
                "GPUScene throughput gate requires hardware RT: " + capability.summary());
        RayTracingRendererConfig configuration = new RayTracingRendererConfig(8, false, true);
        try (RayTracingRenderer renderer = RendererBootstrap.open("vulkan-rt", configuration)) {
            renderer.apply(VulkanGpuSceneRenderingSessionNativeSelfTest.complexScene());
            runRange(renderer, 0L, WARMUP_FRAMES, false);
            Measurement measurement = runRange(renderer, WARMUP_FRAMES, MEASURED_FRAMES, true);
            RendererDiagnostics diagnostics = renderer.diagnostics();
            try (GpuFrameLease lease = renderer.acquireLatestFrame()) {
                require(lease != null, "throughput gate produced no public GPU frame lease");
            }
            require(measurement.averageFps() >= REQUIRED_AVERAGE_FPS,
                    "GPUScene average throughput is below target: " + measurement);
            require(measurement.lowWindowFps() >= REQUIRED_LOW_WINDOW_FPS,
                    "GPUScene low-window throughput is below target: " + measurement);
            require(measurement.maxProgressGapMillis() < 50.0,
                    "GPUScene completion stream contains a visible stall: " + measurement);
            require(diagnostics.frameGpuTiming().enabled()
                            && diagnostics.frameGpuTiming().completedSamples() > 0L,
                    "GPUScene throughput gate has no hardware timestamp evidence: "
                            + diagnostics.frameGpuTiming());
            System.out.println("VulkanGpuSceneThroughputNativeSelfTest passed: device="
                    + capability.preferredDevice().name()
                    + ", extent=" + WIDTH + "x" + HEIGHT
                    + ", measurement=" + measurement
                    + ", gpuTiming=" + diagnostics.frameGpuTiming());
        }
    }

    private static Measurement runRange(
            RayTracingRenderer renderer,
            long firstSequence,
            int frameCount,
            boolean measure
    ) throws InterruptedException {
        long finalSequence = firstSequence + frameCount - 1L;
        long nextSequence = firstSequence;
        long latestCompleted = firstSequence - 1L;
        long deadline = System.nanoTime() + TIMEOUT_NANOS;
        long start = System.nanoTime();
        long lastProgressTime = start;
        long windowTime = start;
        long windowSequence = latestCompleted;
        double lowWindowFps = Double.POSITIVE_INFINITY;
        long maxGapNanos = 0L;

        while (latestCompleted < finalSequence) {
            boolean admitted = false;
            while (nextSequence <= finalSequence) {
                RenderFrameRequest frame = new RenderFrameRequest(
                        nextSequence,
                        0L,
                        WIDTH,
                        HEIGHT,
                        VulkanGpuSceneRenderingSessionNativeSelfTest.camera(),
                        VulkanGpuSceneRenderingSessionNativeSelfTest.environment()
                );
                try {
                    renderer.submit(frame);
                    nextSequence++;
                    admitted = true;
                } catch (SubmissionRejectedException backpressure) {
                    break;
                }
            }

            RendererDiagnostics diagnostics = renderer.diagnostics();
            long observed = diagnostics.latestCompletedFrameSequence();
            long now = System.nanoTime();
            if (observed > latestCompleted) {
                maxGapNanos = Math.max(maxGapNanos, now - lastProgressTime);
                lastProgressTime = now;
                latestCompleted = observed;
                long windowFrames = latestCompleted - windowSequence;
                if (measure && windowFrames >= LOW_WINDOW_FRAMES) {
                    lowWindowFps = Math.min(
                            lowWindowFps,
                            windowFrames * 1_000_000_000.0 / Math.max(1L, now - windowTime)
                    );
                    windowSequence = latestCompleted;
                    windowTime = now;
                }
            } else if (!admitted) {
                Thread.onSpinWait();
            }
            if (now >= deadline) {
                throw new AssertionError(
                        "GPUScene throughput run timed out: submitted=" + (nextSequence - 1L)
                                + ", completed=" + latestCompleted + ", final=" + finalSequence
                );
            }
        }
        long elapsed = Math.max(1L, System.nanoTime() - start);
        double averageFps = frameCount * 1_000_000_000.0 / elapsed;
        if (!Double.isFinite(lowWindowFps)) lowWindowFps = averageFps;
        return new Measurement(averageFps, lowWindowFps, maxGapNanos / 1_000_000.0, elapsed / 1_000_000.0);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Measurement(
            double averageFps,
            double lowWindowFps,
            double maxProgressGapMillis,
            double elapsedMillis
    ) {
    }
}
