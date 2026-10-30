package top.ceroxe.rt.renderer.backend.vulkan;

import java.util.ArrayList;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.api.DenoisingOptions;
import top.ceroxe.rt.renderer.api.FrameGenerationOptions;
import top.ceroxe.rt.renderer.api.FrameReconstructionOptions;
import top.ceroxe.rt.renderer.api.LowLatencyOptions;
import top.ceroxe.rt.renderer.api.Renderer;
import top.ceroxe.rt.renderer.api.RendererConfig;
import top.ceroxe.rt.renderer.api.RayTracingOptimizationOptions;
import top.ceroxe.rt.renderer.api.RenderFrameRequest;
import top.ceroxe.rt.renderer.api.RendererDiagnostics;
import top.ceroxe.rt.renderer.api.SubmissionRejectedException;
import top.ceroxe.rt.renderer.api.TemporalRenderingOptions;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease;
import top.ceroxe.rt.renderer.api.interop.vulkan.VulkanFrameInterop;
import top.ceroxe.rt.renderer.api.interop.vulkan.GpuFrameLease.LeaseState;
import top.ceroxe.rt.renderer.rt.RtNativeBenchmarkReport;
import top.ceroxe.rt.renderer.rt.RtNativeBenchmarkReport.CompletionMeasurement;
import top.ceroxe.rt.renderer.rt.RtNativeBenchmarkReport.CompletionRateTracker;

/** Unpaced host-observed submission/completion throughput, with separate Vulkan timestamp evidence. */
public final class VulkanGpuSceneThroughputNativeSelfTest {
   private static final int WIDTH = 1920;
   private static final int HEIGHT = 1080;
   private static final int WARMUP_FRAMES = 32;
   private static final int MEASURED_FRAMES = 256;
   private static final int MEASUREMENT_RUNS = 3;
   private static final int COMPLETION_WINDOW_FRAMES = 16;
   private static final long COMPLETION_STALL_TIMEOUT_NANOS = 30_000_000_000L;
   private static final long RANGE_TIMEOUT_NANOS = 300_000_000_000L;

   private VulkanGpuSceneThroughputNativeSelfTest() { }

   public static void main(String[] arguments) throws Exception {
      VulkanRtCapabilityProbe.Result capability = VulkanRtCapabilityProbe.capture();
      require(capability.hardwareRayTracingReady(), "GPUScene throughput test requires hardware RT: " + capability.summary());
      // Fix the workload policy for version comparisons; provider defaults must not change this test.
      RendererConfig configuration = RendererConfig.builder()
              .temporalRendering(TemporalRenderingOptions.disabled())
              .frameReconstruction(FrameReconstructionOptions.disabled())
              .denoising(DenoisingOptions.disabled())
              .frameGeneration(FrameGenerationOptions.disabled())
              .lowLatency(LowLatencyOptions.disabled())
              .rayTracingOptimizations(RayTracingOptimizationOptions.disabled())
              .maxFramesInFlight(8).cpuFrameReadbackEnabled(false)
              .validationEnabled(false).gpuTimingsEnabled(true).build();
      long openStart = System.nanoTime();
      try (Renderer renderer = Renderer.open("vulkan-rt", configuration)) {
         long rendererOpenNanos = System.nanoTime() - openStart;
         VulkanFrameInterop interop = renderer.extension(VulkanFrameInterop.class)
                 .orElseThrow(() -> new AssertionError("Vulkan backend omitted its interop extension"));
         long setupStart = System.nanoTime();
         renderer.apply(VulkanGpuSceneRenderingSessionNativeSelfTest.complexScene());
         Measurement warmup = runRange(renderer, interop, 0, WARMUP_FRAMES);
         long sceneSetupAndWarmupNanos = System.nanoTime() - setupStart;
         ArrayList<Measurement> measurements = new ArrayList<>(MEASUREMENT_RUNS);
         for (int run = 0; run < MEASUREMENT_RUNS; run++) {
            long firstSequence = WARMUP_FRAMES + (long)run * MEASURED_FRAMES;
            Measurement measurement = runRange(renderer, interop, firstSequence, MEASURED_FRAMES);
            require(measurement.stats().completedFrames() == MEASURED_FRAMES,
                    "measurement range did not complete its exact frame count: " + measurement);
            require(measurement.gpuTimestampSamples() > 0, "measured range resolved no Vulkan timestamps");
            measurements.add(measurement);
         }
         RendererDiagnostics diagnostics = renderer.diagnostics();
         require(diagnostics.frameGpuTiming().enabled() && diagnostics.frameGpuTiming().failedSamples() == 0,
                 "invalid GPU timestamp evidence: " + diagnostics.frameGpuTiming());
         double aggregateFps = RtNativeBenchmarkReport.aggregateHostCompletedFps(
                 measurements.stream().map(Measurement::stats).toList());
         System.out.println("VulkanGpuSceneThroughputNativeSelfTest passed: device=" + capability.preferredDevice().name()
                 + ", extent=" + WIDTH + "x" + HEIGHT + ", measurementSource=hostObservedCompletion"
                 + ", displayFps=unmeasured, onePercentLowFps=unmeasured, performanceThreshold=none"
                 + ", rendererOpenNanos=" + rendererOpenNanos + ", sceneSetupAndWarmupNanos=" + sceneSetupAndWarmupNanos
                 + ", warmupCompletedFrames=" + warmup.stats().completedFrames()
                 + ", aggregateHostCompletedFps=" + aggregateFps + ", measurements=" + measurements
                 + ", completionWindowKind=nonOverlappingHostBatches,minWindowFrames=" + COMPLETION_WINDOW_FRAMES
                 + ", gpuTimingScope=sessionCumulativeIncludingSetupAndWarmup,gpuTiming=" + diagnostics.frameGpuTiming());
      }
   }

   private static Measurement runRange(Renderer renderer, VulkanFrameInterop interop,
           long firstSequence, int frameCount) {
      long finalSequence = Math.addExact(firstSequence, frameCount - 1L);
      long nextSequence = firstSequence;
      RendererDiagnostics baseline = renderer.diagnostics();
      require(baseline.latestCompletedFrameSequence() == firstSequence - 1,
              "measurement must start after the previous range has drained");
      long start = System.nanoTime();
      CompletionRateTracker rates = new CompletionRateTracker(firstSequence - 1, start, COMPLETION_WINDOW_FRAMES);
      long latestCompleted = firstSequence - 1;
      long end = start;
      long lastCompletion = start;
      long consumedLeases = 0;
      RendererDiagnostics diagnostics = baseline;
      while (latestCompleted < finalSequence) {
         if (consumeLatestLease(interop)) consumedLeases++;
         boolean admitted = false;
         while (nextSequence <= finalSequence) {
            RenderFrameRequest frame = RenderFrameRequest.builder(nextSequence, WIDTH, HEIGHT,
                    VulkanGpuSceneRenderingSessionNativeSelfTest.camera())
                    .environment(VulkanGpuSceneRenderingSessionNativeSelfTest.environment()).build();
            try {
               renderer.submit(frame);
               nextSequence++;
               admitted = true;
            } catch (SubmissionRejectedException deferred) {
               break;
            }
         }
         diagnostics = renderer.diagnostics();
         long observed = diagnostics.latestCompletedFrameSequence();
         end = System.nanoTime();
         require(observed <= nextSequence - 1, "completed sequence exceeds this range's submissions");
         rates.observe(observed, end);
         if (observed > latestCompleted) lastCompletion = end;
         if (observed == latestCompleted && !admitted) Thread.onSpinWait();
         latestCompleted = observed;
         // This test reports throughput without an FPS floor. A fixed 30-second
         // range deadline silently imposed one; keep a strict no-progress
         // watchdog and a separate bounded wall-time budget for all 256 frames.
         if (latestCompleted < finalSequence && (end - lastCompletion >= COMPLETION_STALL_TIMEOUT_NANOS
                 || end - start >= RANGE_TIMEOUT_NANOS))
            throw new AssertionError("GPUScene throughput run timed out: submitted=" + (nextSequence - 1)
                    + ", completed=" + latestCompleted + ", final=" + finalSequence
                    + ", elapsedMillis=" + (end-start)/1_000_000L
                    + ", completionStallMillis=" + (end-lastCompletion)/1_000_000L);
      }
      CompletionMeasurement measurement = rates.finish(end);
      if (consumeLatestLease(interop)) consumedLeases++;
      require(consumedLeases > 0, "throughput test consumed no public GPU frame leases");
      return new Measurement(measurement, consumedLeases,
              diagnostics.frameGpuTiming().completedSamples() - baseline.frameGpuTiming().completedSamples());
   }

   private static boolean consumeLatestLease(VulkanFrameInterop interop) {
      if (!(interop.pollLatestFrame() instanceof VulkanFrameInterop.FrameAvailable available)) return false;
      try (GpuFrameLease lease = available.lease()) {
         require(lease.state() == LeaseState.ACTIVE, "throughput test returned an inactive GPU frame lease");
      }
      return true;
   }

   private static void require(boolean condition, String message) {
      if (!condition) throw new AssertionError(message);
   }

   private record Measurement(CompletionMeasurement stats, long consumedLeases, long gpuTimestampSamples) { }
}
