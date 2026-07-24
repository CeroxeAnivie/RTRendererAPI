package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** State-machine checks for bounded first-front BLAS lifecycle diagnostics. */
public final class RtFirstFrontBlasFlightRecorderSelfTest {
    private RtFirstFrontBlasFlightRecorderSelfTest() {
    }

    public static void main(String[] args) {
        disabledRecorderIsAllocationAndClockSilent();
        boundedTraceFiltersSectionsAndReportsOverwrite();
        resetStartsAnIndependentTrace();
        invalidConstructionAndPublicationInputsFailEarly();
        System.out.println("RtFirstFrontBlasFlightRecorderSelfTest passed");
    }

    private static void disabledRecorderIsAllocationAndClockSilent() {
        AtomicLong clockReads = new AtomicLong();
        RtFirstFrontBlasFlightRecorder recorder = new RtFirstFrontBlasFlightRecorder(
                false,
                2,
                () -> {
                    clockReads.incrementAndGet();
                    return 1L;
                }
        );
        recorder.record("queued", new SectionKey(0, 0, 0), 1L, 2L, 1L, 3, true, false, false);
        recorder.recordProgress(4L, 1, 1, 1, 0, 0, 0, 0);

        require(recorder.dumpOnce(null) == null, "disabled recorder must not publish a dump");
        require(clockReads.get() == 0L, "disabled recorder must not evaluate its scheduler clock");
    }

    private static void boundedTraceFiltersSectionsAndReportsOverwrite() {
        AtomicLong elapsedMillis = new AtomicLong(10L);
        RtFirstFrontBlasFlightRecorder recorder = new RtFirstFrontBlasFlightRecorder(
                true,
                2,
                elapsedMillis::getAndIncrement
        );
        SectionKey discarded = new SectionKey(1, 2, 3);
        SectionKey foreground = new SectionKey(4, 5, 6);
        recorder.record("queued", discarded, 7L, 8L, 6L, 0x11, false, false, false);
        recorder.record("gpuSubmitted", foreground, 9L, 10L, 8L, 0x2a, true, true, false);
        recorder.recordProgress(12L, 4, 4, 3, 2, 1, 1, 0);

        RtFirstFrontBlasFlightRecorder.Dump dump = recorder.dumpOnce(Set.of(foreground));
        require(dump != null, "enabled recorder must publish its terminal dump");
        require(dump.events() == 3L, "dump must report every accepted event");
        require(dump.overwritten() == 1L, "ring wrap must report one overwritten event");
        require(dump.retained() == 2, "foreground section and progress event must remain");
        require(dump.trace().contains("2@11ms:gpuSubmitted"), "trace must retain the foreground edge");
        require(dump.trace().contains("key=" + foreground), "trace must identify the foreground section");
        require(dump.trace().contains("3@12ms:progress"), "trace must retain aggregate progress");
        require(!dump.trace().contains("key=" + discarded), "trace must filter non-foreground sections");
        require(recorder.dumpOnce(Set.of(foreground)) == null, "terminal dump must be single-write");

        recorder.record("applied", foreground, 13L, 14L, 14L, 0, true, true, false);
        require(elapsedMillis.get() == 13L, "events after terminal publication must be ignored");
    }

    private static void resetStartsAnIndependentTrace() {
        AtomicLong elapsedMillis = new AtomicLong(21L);
        RtFirstFrontBlasFlightRecorder recorder = new RtFirstFrontBlasFlightRecorder(
                true,
                2,
                elapsedMillis::getAndIncrement
        );
        SectionKey key = new SectionKey(-1, 7, 9);
        recorder.record("queued", key, 1L, 1L, 0L, 0, false, false, false);
        require(recorder.dumpOnce(Set.of(key)) != null, "first generation must publish");

        recorder.reset();
        recorder.record("active", key, 2L, 2L, 2L, 0, true, true, false);
        RtFirstFrontBlasFlightRecorder.Dump resetDump = recorder.dumpOnce(Set.of(key));
        require(resetDump != null && resetDump.events() == 1L, "reset must restart event sequencing");
        require(resetDump.overwritten() == 0L, "reset must clear overwrite accounting");
        require(resetDump.trace().startsWith("{1@22ms:active"), "reset trace must not retain prior entries");
    }

    private static void invalidConstructionAndPublicationInputsFailEarly() {
        require(expectFailure(() -> new RtFirstFrontBlasFlightRecorder(true, 0, () -> 0L))
                        instanceof IllegalArgumentException,
                "zero ring capacity must be rejected");
        require(expectFailure(() -> new RtFirstFrontBlasFlightRecorder(true, 1, null))
                        instanceof NullPointerException,
                "missing elapsed-time source must be rejected");
        RtFirstFrontBlasFlightRecorder recorder = new RtFirstFrontBlasFlightRecorder(true, 1, () -> 0L);
        require(expectFailure(() -> recorder.dumpOnce(null)) instanceof NullPointerException,
                "enabled publication must reject a missing foreground set");
    }

    private static RuntimeException expectFailure(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            return failure;
        }
        throw new AssertionError("expected failure");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
