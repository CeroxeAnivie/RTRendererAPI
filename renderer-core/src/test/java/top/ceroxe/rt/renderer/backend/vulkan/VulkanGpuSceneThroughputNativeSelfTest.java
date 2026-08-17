package top.ceroxe.rt.renderer.backend.vulkan;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.api.DenoisingOptions;
import top.ceroxe.rt.renderer.api.FrameGenerationOptions;
import top.ceroxe.rt.renderer.api.FrameReconstructionOptions;
import top.ceroxe.rt.renderer.api.LowLatencyOptions;
import top.ceroxe.rt.renderer.api.Renderer;
import top.ceroxe.rt.renderer.api.RendererConfig;
import top.ceroxe.rt.renderer.api.RayTracingOptimizationOptions;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.RendererBootstrap;
import top.ceroxe.rt.renderer.api.RendererDiagnostics;
import top.ceroxe.rt.renderer.api.SubmissionRejectedException;
import top.ceroxe.rt.renderer.api.TemporalRenderingOptions;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFrameInterop;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease.LeaseState;

public final class VulkanGpuSceneThroughputNativeSelfTest {
   private static final int WIDTH = 1920;
   private static final int HEIGHT = 1080;
   private static final int WARMUP_FRAMES = 32;
   private static final int MEASURED_FRAMES = 256;
   private static final int MEASUREMENT_RUNS = 3;
   private static final int LOW_WINDOW_FRAMES = 16;
   private static final double REQUIRED_AVERAGE_FPS = 100.0;
   private static final double REQUIRED_LOW_WINDOW_FPS = 50.0;
   private static final long TIMEOUT_NANOS = 30000000000L;

   private VulkanGpuSceneThroughputNativeSelfTest() {
   }

   public static void main(String[] arguments) throws Exception {
      VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
      require(capability.hardwareRayTracingReady(), "GPUScene throughput gate requires hardware RT: " + capability.summary());
      /*
       * Keep this historical throughput comparison independent of production feature defaults.
       * An API-version A/B must measure the base GPUScene pipeline, not whatever optional provider
       * policy happened to become the public default in that release.
       */
      RendererConfig configuration = RendererConfig.expertBuilder()
              .temporalRendering(TemporalRenderingOptions.disabled())
              .frameReconstruction(FrameReconstructionOptions.disabled())
              .denoising(DenoisingOptions.disabled())
              .frameGeneration(FrameGenerationOptions.disabled())
              .lowLatency(LowLatencyOptions.disabled())
              .rayTracingOptimizations(RayTracingOptimizationOptions.disabled())
              .maxFramesInFlight(8)
              .validationEnabled(false)
              .gpuTimingsEnabled(true)
              .build();
      long coldStartNanos = System.nanoTime();
      Renderer renderer = RendererBootstrap.open("vulkan-rt", configuration);

      try {
         VulkanFrameInterop interop = (VulkanFrameInterop)renderer.extension(VulkanFrameInterop.class).orElseThrow(() -> new AssertionError("Vulkan backend omitted its interop extension"));
         renderer.apply(VulkanGpuSceneRenderingSessionNativeSelfTest.complexScene());
         runRange(renderer, interop, 0L, 32, false);
         double coldStartMillis = (double)(System.nanoTime() - coldStartNanos) / 1000000.0;
         ArrayList<Measurement> measurements = new ArrayList<>(3);

         for(int run = 0; run < 3; ++run) {
            long firstSequence = 32L + (long)run * 256L;
            Measurement measurement = runRange(renderer, interop, firstSequence, 256, true);
            measurements.add(measurement);
            require(measurement.averageFps() >= 100.0, "GPUScene average throughput is below target in run " + run + ": " + String.valueOf(measurement));
            require(measurement.lowWindowFps() >= 50.0, "GPUScene low-window throughput is below target in run " + run + ": " + String.valueOf(measurement));
            require(measurement.p99ProgressGapMillis() < 50.0, "GPUScene p99 completion gap contains a visible stall in run " + run + ": " + String.valueOf(measurement));
            require(measurement.maxProgressGapMillis() < 50.0, "GPUScene completion stream contains a visible stall in run " + run + ": " + String.valueOf(measurement));
         }

         RendererDiagnostics diagnostics = renderer.diagnostics();
         require(diagnostics.frameGpuTiming().enabled() && diagnostics.frameGpuTiming().completedSamples() > 0L, "GPUScene throughput gate has no hardware timestamp evidence: " + String.valueOf(diagnostics.frameGpuTiming()));
         PrintStream output10000 = System.out;
         String details10001 = capability.preferredDevice().name();
         output10000.println("VulkanGpuSceneThroughputNativeSelfTest passed: device=" + details10001 + ", extent=1920x1080, coldStartMillis=" + coldStartMillis + ", measurements=" + String.valueOf(measurements) + ", gpuTiming=" + String.valueOf(diagnostics.frameGpuTiming()));
      } catch (Throwable value18) {
         if (renderer != null) {
            try {
               renderer.close();
            } catch (Throwable value15) {
               value18.addSuppressed(value15);
            }
         }

         throw value18;
      }

      if (renderer != null) {
         renderer.close();
      }

   }

   private static Measurement runRange(Renderer renderer, VulkanFrameInterop interop, long firstSequence, int frameCount, boolean measure) throws InterruptedException {
      long finalSequence = firstSequence + (long)frameCount - 1L;
      long nextSequence = firstSequence;
      long latestCompleted = firstSequence - 1L;
      long deadline = System.nanoTime() + 30000000000L;
      long start = System.nanoTime();
      long lastProgressTime = start;
      long windowTime = start;
      long windowSequence = latestCompleted;
      double lowWindowFps = 1.0 / 0.0;
      long maxGapNanos = 0L;
      long consumedLeases = 0L;
      ArrayList<Long> progressGaps = new ArrayList<>();

      while(latestCompleted < finalSequence) {
         if (latestCompleted < finalSequence - 1L) {
            VulkanFrameInterop.FramePollResult available = interop.pollLatestFrame();
            if (available instanceof VulkanFrameInterop.FrameAvailable frame) {
               GpuFrameLease lease = frame.lease();
               require(lease.state() == LeaseState.ACTIVE,
                       "throughput gate returned an inactive public GPU frame lease");
               lease.close();
               ++consumedLeases;
            }
         }
         boolean admitted;
         for(admitted = false; nextSequence <= finalSequence; admitted = true) {
            RenderFrameRequest frame = RenderFrameRequest.builder(nextSequence, 1920, 1080, VulkanGpuSceneRenderingSessionNativeSelfTest.camera()).environment(VulkanGpuSceneRenderingSessionNativeSelfTest.environment()).build();

            try {
               renderer.submit(frame);
               ++nextSequence;
            } catch (SubmissionRejectedException value36) {
               break;
            }
         }

         RendererDiagnostics diagnostics = renderer.diagnostics();
         long observed = diagnostics.latestCompletedFrameSequence();
         long now = System.nanoTime();
         if (observed > latestCompleted) {
            long progressGap = now - lastProgressTime;
            maxGapNanos = Math.max(maxGapNanos, progressGap);
            if (measure) {
               progressGaps.add(progressGap);
            }

            lastProgressTime = now;
            latestCompleted = observed;
            long windowFrames = observed - windowSequence;
            if (measure && windowFrames >= 16L) {
               lowWindowFps = Math.min(lowWindowFps, (double)windowFrames * 1.0E9 / (double)Math.max(1L, now - windowTime));
               windowSequence = observed;
               windowTime = now;
            }
         } else if (!admitted) {
            Thread.onSpinWait();
         }

         if (now >= deadline) {
            throw new AssertionError("GPUScene throughput run timed out: submitted=" + (nextSequence - 1L) + ", completed=" + latestCompleted + ", final=" + finalSequence);
         }
      }

      long elapsed = Math.max(1L, System.nanoTime() - start);
      require(consumedLeases > 0L, "throughput gate consumed no completed GPU frame leases");
      double averageFps = (double)frameCount * 1.0E9 / (double)elapsed;
      if (!Double.isFinite(lowWindowFps)) {
         lowWindowFps = averageFps;
      }

      return new Measurement(averageFps, lowWindowFps, percentileMillis(progressGaps, 0.95), percentileMillis(progressGaps, 0.99), (double)maxGapNanos / 1000000.0, (double)elapsed / 1000000.0);
   }

   private static double percentileMillis(List<Long> samples, double percentile) {
      if (samples.isEmpty()) {
         return 0.0;
      } else {
         long[] sorted = new long[samples.size()];

         for(int index = 0; index < samples.size(); ++index) {
            sorted[index] = (Long)samples.get(index);
         }

         Arrays.sort(sorted);
         int rank = Math.max(0, (int)Math.ceil(percentile * (double)sorted.length) - 1);
         return (double)sorted[rank] / 1000000.0;
      }
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }

   private static record Measurement(double averageFps, double lowWindowFps, double p95ProgressGapMillis, double p99ProgressGapMillis, double maxProgressGapMillis, double elapsedMillis) {
   }
}
