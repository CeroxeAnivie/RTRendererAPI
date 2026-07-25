package top.ceroxe.rt.renderer.rt.pipeline;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import top.ceroxe.rt.renderer.RendererChainIdentity;
import top.ceroxe.rt.renderer.RendererFrameCausality;
import top.ceroxe.rt.renderer.RendererFrameSubmission.Source;

public final class RtFrameDispatchFlightRecorderSelfTest {
   private static final String EVENT_NAME = "top.ceroxe.rt.FrameDispatch";
   private static final String CAPTURE_LOSS_EVENT_NAME = "top.ceroxe.rt.FrameDispatchCaptureLoss";

   private RtFrameDispatchFlightRecorderSelfTest() {
   }

   public static void main(String[] arguments) throws Exception {
      System.setProperty("top.ceroxe.rt.takeoverFlightRecorder.enabled", "true");
      System.setProperty("top.ceroxe.rt.takeoverFlightRecorder.frameDispatchMaxEvents", "3");
      assertProductionWiring();
      Path recordingPath = Files.createTempFile("rtrenderer-frame-dispatch-", ".jfr");
      Recording recording = new Recording();

      try {
         recording.enable("top.ceroxe.rt.FrameDispatch").withoutThreshold().withoutStackTrace();
         recording.enable("top.ceroxe.rt.FrameDispatchCaptureLoss").withoutThreshold().withoutStackTrace();
         recording.start();
         RendererFrameCausality causality = causality();
         RtFrameDispatchFlightRecorder.CpuTimings cpu = new RtFrameDispatchFlightRecorder.CpuTimings(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L, 14L, 15L, 16L, 17L, 18L);
         RtFrameDispatchFlightRecorder.recordSubmitted(causality, 41L, 7L, 52L, 61L, 53L, 100L, cpu);
         RtFrameDispatchFlightRecorder.recordCompleted(causality, 41L, 7L, 52L, 61L, 53L, 4L, 200L, 120L, 30L, 150L);
         RtFrameDispatchFlightRecorder.recordRejected(causality, 41L, 52L, 61L, 53L, "frameSlotRingBusy");
         RtFrameDispatchFlightRecorder.recordFailed(causality, 41L, 52L, 61L, 53L, 300L, "submissionFailure");
         recording.stop();
         recording.dump(recordingPath);
      } catch (Throwable value14) {
         try {
            recording.close();
         } catch (Throwable value12) {
            value14.addSuppressed(value12);
         }

         throw value14;
      }

      recording.close();

      try {
         List<RecordedEvent> allEvents = RecordingFile.readAllEvents(recordingPath);
         List<RecordedEvent> dispatchEvents = allEvents.stream().filter((event) -> "top.ceroxe.rt.FrameDispatch".equals(event.getEventType().getName())).toList();
         require(dispatchEvents.size() == 3, "expected the configured three full-fidelity events");
         RecordedEvent submitted = (RecordedEvent)dispatchEvents.get(0);
         require(submitted.getInt("outcome") == 1, "submit outcome was not preserved");
         require(submitted.getLong("traceId") == 31L, "causality trace was not preserved");
         require(submitted.getLong("frameStateSequence") == 41L, "frame sequence was not preserved");
         require(submitted.getLong("dispatchOrdinal") == 7L, "dispatch ordinal was not preserved");
         require(submitted.getLong("dynamicSceneRevision") == 52L, "dynamic scene revision was not preserved");
         require(submitted.getLong("descriptorGeneration") == 61L, "descriptor generation was not preserved");
         require(submitted.getLong("worldTlasRevision") == 71L, "world TLAS revision was not preserved");
         require(submitted.getLong("queueLockWaitNanos") == 15L, "queue-lock timing was not preserved");
         require(submitted.getLong("bookkeepingNanos") == 18L, "bookkeeping timing was not preserved");
         require(submitted.getLong("elapsedNanos") == 100L, "submit elapsed time was not preserved");
         RecordedEvent completed = (RecordedEvent)dispatchEvents.get(1);
         require(completed.getInt("outcome") == 2, "completion outcome was not preserved");
         require(completed.getLong("dispatchOrdinal") == 7L, "completion must correlate with the submitted dispatch");
         require(completed.getLong("pollCount") == 4L, "completion poll count was not preserved");
         require(completed.getLong("elapsedNanos") == 200L, "GPU completion elapsed time was not preserved");
         require(completed.getLong("traceGpuNanos") == 120L, "ray-trace GPU time was not preserved");
         require(completed.getLong("postTraceGpuNanos") == 30L, "post-trace GPU time was not preserved");
         require(completed.getLong("totalGpuNanos") == 150L, "total GPU time was not preserved");
         RecordedEvent rejected = (RecordedEvent)dispatchEvents.get(2);
         require(rejected.getInt("outcome") == 3, "rejection outcome was not preserved");
         require(rejected.getLong("dispatchOrdinal") == -1L, "a rejected frame must not impersonate a submitted dispatch");
         require("frameSlotRingBusy".equals(rejected.getString("reason")), "rejection reason was not preserved");
         RecordedEvent captureLoss = (RecordedEvent)allEvents.stream().filter((event) -> "top.ceroxe.rt.FrameDispatchCaptureLoss".equals(event.getEventType().getName())).findFirst().orElseThrow(() -> new AssertionError("capture loss was not observable"));
         require(captureLoss.getLong("maxEvents") == 3L, "capture-loss event did not preserve the configured budget");
         require(captureLoss.getLong("droppedEventsLowerBound") == 1L, "capture-loss event did not count the dropped attempt");
      } finally {
         Files.deleteIfExists(recordingPath);
      }

      System.out.println("RtFrameDispatchFlightRecorderSelfTest passed");
   }

   private static void assertProductionWiring() throws Exception {
      Path sources = Path.of(System.getProperty("user.dir")).resolve("src/main/java/top/ceroxe/rt/renderer/rt/pipeline");
      assertProductionEdge(sources.resolve("RtRayTracingPipeline.java"), "RtFrameDispatchFlightRecorder.recordSubmitted(");
      assertProductionEdge(sources.resolve("RtFrameCompletionPublisher.java"), "RtFrameDispatchFlightRecorder.recordCompleted(");
      assertProductionEdge(sources.resolve("RtRayTracingPipeline.java"), "RtFrameDispatchFlightRecorder.recordRejected(");
   }

   private static void assertProductionEdge(Path owner, String requiredEdge) throws Exception {
      String source = Files.readString(owner, StandardCharsets.UTF_8);
      boolean condition10000 = source.contains(requiredEdge);
      String details10001 = String.valueOf(owner.getFileName());
      require(condition10000, details10001 + " is missing production JFR edge " + requiredEdge);
   }

   private static RendererFrameCausality causality() {
      return new RendererFrameCausality(new RendererChainIdentity(31L, Source.FRAME_END, 41L, 42L, 43L, 44L, 45L, 46L, 71L, 72L));
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
