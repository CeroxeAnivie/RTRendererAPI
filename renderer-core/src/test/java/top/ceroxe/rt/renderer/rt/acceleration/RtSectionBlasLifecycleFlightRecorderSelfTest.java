package top.ceroxe.rt.renderer.rt.acceleration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import top.ceroxe.rt.renderer.RendererFrameCausality;
import top.ceroxe.rt.renderer.RendererFrameSubmission.Source;
import top.ceroxe.rt.renderer.rt.acceleration.RtSectionBlasRetirementLifecycle.Reason;
import top.ceroxe.rt.renderer.scene.SectionKey;

public final class RtSectionBlasLifecycleFlightRecorderSelfTest {
   private RtSectionBlasLifecycleFlightRecorderSelfTest() {
   }

   public static void main(String[] arguments) throws Exception {
      lifecycleEdgesCarryJoinFieldsAndReleaseFence();
      disabledRecorderIsFailClosed();
      System.out.println("RtSectionBlasLifecycleFlightRecorderSelfTest passed");
   }

   private static void lifecycleEdgesCarryJoinFieldsAndReleaseFence() throws Exception {
      RtSectionBlasLifecycleFlightRecorder recorder = new RtSectionBlasLifecycleFlightRecorder(true, 5L);
      SectionKey key = new SectionKey(3, -2, 7);
      RendererFrameCausality causality = new RendererFrameCausality(68L, Source.TERRAIN_PREPARATION, 19L);
      Object resource = new Object();
      Path path = Files.createTempFile("rtrenderer-section-blas-lifecycle-", ".jfr");
      Recording recording = new Recording();

      try {
         recording.enable("top.ceroxe.rt.SectionBlasLifecycle").withoutThreshold();
         recording.enable("top.ceroxe.rt.SectionBlasCaptureLoss").withoutThreshold();
         recording.start();
         recorder.record("BLAS_RECORD_SUBMIT", key, 12L, 91L, -1L, -1L, "submitted", causality);
         recorder.record("GPU_COMPLETE", key, 12L, 91L, -1L, -1L, "completed", causality);
         recorder.applied(resource, key, 12L, 91L, 8L, causality, "installed");
         recorder.retireQueued(resource, key, 11L, 8L, 12L, 91L, causality, Reason.BUDGET_EVICTION);
         recorder.releaseThrough(10L, true);
         recorder.releaseThrough(11L, true);
         recording.stop();
         recording.dump(path);
      } catch (Throwable value13) {
         try {
            recording.close();
         } catch (Throwable value12) {
            value13.addSuppressed(value12);
         }

         throw value13;
      }

      recording.close();

      try {
         List<RecordedEvent> events = RecordingFile.readAllEvents(path).stream().filter((event) -> event.getEventType().getName().equals("top.ceroxe.rt.SectionBlasLifecycle")).toList();
         require(events.size() == 5, "all five lifecycle edges must be captured");
         RecordedEvent release = (RecordedEvent)events.get(4);
         require(release.getString("stage").equals("RELEASED"), "release stage was not preserved");
         require(release.getLong("safeAfterRevision") == 11L, "retirement fence was not preserved");
         require(release.getLong("traceId") == 68L, "causality trace id was not preserved");
         require(release.getLong("buildSequence") == 91L, "build sequence was not joined");
         require(release.getString("retirementReason").equals("BUDGET_EVICTION"), "retirement reason was not preserved through deferred release");
         RtSectionBlasLifecycleFlightRecorder.Snapshot snapshot = recorder.snapshot();
         require(snapshot.dropped() == 0L && snapshot.trackedRetirements() == 0, "successful release must clear the diagnostic retirement mirror");
      } finally {
         Files.deleteIfExists(path);
      }

   }

   private static void disabledRecorderIsFailClosed() {
      RtSectionBlasLifecycleFlightRecorder recorder = new RtSectionBlasLifecycleFlightRecorder(false, 1L);
      recorder.record("BLAS_RECORD_SUBMIT", new SectionKey(0, 0, 0), 1L, 1L, 1L, 1L, "ignored", RendererFrameCausality.untraced(1L));
      require(!recorder.snapshot().enabled() && recorder.snapshot().attempts() == 0L, "disabled recorder must not enter lifecycle accounting");
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
