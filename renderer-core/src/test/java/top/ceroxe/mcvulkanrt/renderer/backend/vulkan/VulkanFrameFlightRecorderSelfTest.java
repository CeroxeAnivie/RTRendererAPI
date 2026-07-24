package top.ceroxe.mcvulkanrt.renderer.backend.vulkan;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Exact JFR gate for frame causality, fence evidence, and bounded capture loss. */
public final class VulkanFrameFlightRecorderSelfTest {
    private static final String FRAME_EVENT = "top.ceroxe.mcvulkanrt.VulkanFrameLifecycle";
    private static final String LOSS_EVENT = "top.ceroxe.mcvulkanrt.VulkanFrameCaptureLoss";

    private VulkanFrameFlightRecorderSelfTest() {
    }

    public static void main(String[] arguments) throws Exception {
        System.setProperty("mcvulkanrt.renderer.jfr.enabled", "true");
        System.setProperty("mcvulkanrt.renderer.jfr.frameMaxEvents", "1");
        Path recordingPath = Files.createTempFile("mcvulkanrt-frame-lifecycle-", ".jfr");
        try (Recording recording = new Recording()) {
            recording.enable(FRAME_EVENT).withoutThreshold().withoutStackTrace();
            recording.enable(LOSS_EVENT).withoutThreshold().withoutStackTrace();
            recording.start();
            VulkanFrameFlightRecorder.record(
                    VulkanFrameFlightRecorder.PHASE_ADMITTED,
                    2, 101L, 7L, 44L, 1920, 1080, 0L, 0L
            );
            VulkanFrameFlightRecorder.record(
                    VulkanFrameFlightRecorder.PHASE_PRODUCER_COMPLETED,
                    2, 101L, 7L, 44L, 1920, 1080, 800_000L, 3L
            );
            recording.stop();
            recording.dump(recordingPath);
        }
        try {
            List<RecordedEvent> events = RecordingFile.readAllEvents(recordingPath);
            RecordedEvent admitted = events.stream()
                    .filter(event -> FRAME_EVENT.equals(event.getEventType().getName()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("frame lifecycle JFR event was not recorded"));
            require(admitted.getInt("phase") == VulkanFrameFlightRecorder.PHASE_ADMITTED
                            && admitted.getInt("slot") == 2
                            && admitted.getLong("frameSequence") == 101L
                            && admitted.getLong("sceneRevision") == 7L
                            && admitted.getLong("descriptorEpoch") == 44L,
                    "frame JFR event lost causality or slot ownership");
            require(admitted.getInt("width") == 1920 && admitted.getInt("height") == 1080,
                    "frame JFR event lost dispatch extent");
            RecordedEvent loss = events.stream()
                    .filter(event -> LOSS_EVENT.equals(event.getEventType().getName()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("frame JFR capture loss was not recorded"));
            require(loss.getLong("maxEvents") == 1L
                            && loss.getLong("droppedEventsLowerBound") == 1L,
                    "frame JFR loss evidence changed its configured bound");
        } finally {
            Files.deleteIfExists(recordingPath);
        }
        System.out.println("VulkanFrameFlightRecorderSelfTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
