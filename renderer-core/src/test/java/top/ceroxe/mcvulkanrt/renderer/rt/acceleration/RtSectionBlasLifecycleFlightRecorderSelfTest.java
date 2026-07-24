package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameCausality;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameSubmission;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Verifies BLAS lifecycle join fields, retirement fences, and bounded loss reporting. */
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
        RendererFrameCausality causality = new RendererFrameCausality(
                0x44L, RendererFrameSubmission.Source.TERRAIN_PREPARATION, 19L
        );
        Object resource = new Object();
        Path path = Files.createTempFile("mcvulkanrt-section-blas-lifecycle-", ".jfr");
        try (Recording recording = new Recording()) {
            recording.enable(RtSectionBlasLifecycleFlightRecorder.EVENT_NAME).withoutThreshold();
            recording.enable(RtSectionBlasLifecycleFlightRecorder.CAPTURE_LOSS_EVENT_NAME).withoutThreshold();
            recording.start();
            recorder.record("BLAS_RECORD_SUBMIT", key, 12L, 91L, -1L, -1L, "submitted", causality);
            recorder.record("GPU_COMPLETE", key, 12L, 91L, -1L, -1L, "completed", causality);
            recorder.applied(resource, key, 12L, 91L, 8L, causality, "installed");
            recorder.retireQueued(
                    resource,
                    key,
                    11L,
                    8L,
                    12L,
                    91L,
                    causality,
                    RtSectionBlasRetirementLifecycle.Reason.BUDGET_EVICTION
            );
            recorder.releaseThrough(10L, true);
            recorder.releaseThrough(11L, true);
            recording.stop();
            recording.dump(path);
        }
        try {
            List<RecordedEvent> events = RecordingFile.readAllEvents(path).stream()
                    .filter(event -> event.getEventType().getName()
                            .equals(RtSectionBlasLifecycleFlightRecorder.EVENT_NAME)).toList();
            require(events.size() == 5, "all five lifecycle edges must be captured");
            RecordedEvent release = events.get(4);
            require(release.getString("stage").equals("RELEASED"), "release stage was not preserved");
            require(release.getLong("safeAfterRevision") == 11L, "retirement fence was not preserved");
            require(release.getLong("traceId") == 0x44L, "causality trace id was not preserved");
            require(release.getLong("buildSequence") == 91L, "build sequence was not joined");
            require(release.getString("retirementReason").equals("BUDGET_EVICTION"),
                    "retirement reason was not preserved through deferred release");
            RtSectionBlasLifecycleFlightRecorder.Snapshot snapshot = recorder.snapshot();
            require(snapshot.dropped() == 0L && snapshot.trackedRetirements() == 0,
                    "successful release must clear the diagnostic retirement mirror");
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static void disabledRecorderIsFailClosed() {
        RtSectionBlasLifecycleFlightRecorder recorder = new RtSectionBlasLifecycleFlightRecorder(false, 1L);
        recorder.record("BLAS_RECORD_SUBMIT", new SectionKey(0, 0, 0), 1L, 1L, 1L, 1L,
                "ignored", RendererFrameCausality.untraced(1L));
        require(!recorder.snapshot().enabled() && recorder.snapshot().attempts() == 0L,
                "disabled recorder must not enter lifecycle accounting");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
