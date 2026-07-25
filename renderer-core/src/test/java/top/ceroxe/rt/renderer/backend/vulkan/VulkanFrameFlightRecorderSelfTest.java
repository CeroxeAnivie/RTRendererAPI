package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

public final class VulkanFrameFlightRecorderSelfTest {
   private static final String FRAME_EVENT = "top.ceroxe.rt.VulkanFrameLifecycle";
   private static final String LOSS_EVENT = "top.ceroxe.rt.VulkanFrameCaptureLoss";

   private VulkanFrameFlightRecorderSelfTest() {
   }

   public static void main(String[] arguments) throws Exception {
      System.setProperty("top.ceroxe.rt.renderer.jfr.enabled", "true");
      System.setProperty("top.ceroxe.rt.renderer.jfr.frameMaxEvents", "1");
      Path recordingPath = Files.createTempFile("rtrenderer-frame-lifecycle-", ".jfr");
      Recording recording = new Recording();

      try {
         recording.enable("top.ceroxe.rt.VulkanFrameLifecycle").withoutThreshold().withoutStackTrace();
         recording.enable("top.ceroxe.rt.VulkanFrameCaptureLoss").withoutThreshold().withoutStackTrace();
         recording.start();
         VulkanFrameFlightRecorder.record(1, 2, 101L, 7L, 44L, 1920, 1080, 0L, 0L);
         VulkanFrameFlightRecorder.record(2, 2, 101L, 7L, 44L, 1920, 1080, 800000L, 3L);
         recording.stop();
         recording.dump(recordingPath);
      } catch (Throwable value10) {
         try {
            recording.close();
         } catch (Throwable value9) {
            value10.addSuppressed(value9);
         }

         throw value10;
      }

      recording.close();

      try {
         List<RecordedEvent> events = RecordingFile.readAllEvents(recordingPath);
         RecordedEvent admitted = (RecordedEvent)events.stream().filter((event) -> "top.ceroxe.rt.VulkanFrameLifecycle".equals(event.getEventType().getName())).findFirst().orElseThrow(() -> new AssertionError("frame lifecycle JFR event was not recorded"));
         require(admitted.getInt("phase") == 1 && admitted.getInt("slot") == 2 && admitted.getLong("frameSequence") == 101L && admitted.getLong("sceneRevision") == 7L && admitted.getLong("descriptorEpoch") == 44L, "frame JFR event lost causality or slot ownership");
         require(admitted.getInt("width") == 1920 && admitted.getInt("height") == 1080, "frame JFR event lost dispatch extent");
         RecordedEvent loss = (RecordedEvent)events.stream().filter((event) -> "top.ceroxe.rt.VulkanFrameCaptureLoss".equals(event.getEventType().getName())).findFirst().orElseThrow(() -> new AssertionError("frame JFR capture loss was not recorded"));
         require(loss.getLong("maxEvents") == 1L && loss.getLong("droppedEventsLowerBound") == 1L, "frame JFR loss evidence changed its configured bound");
      } finally {
         Files.deleteIfExists(recordingPath);
      }

      System.out.println("VulkanFrameFlightRecorderSelfTest passed");
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
