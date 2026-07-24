package top.ceroxe.mcvulkanrt.renderer.rt.runtime;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/** Records the synchronous phases of native backend activation without changing its ownership. */
final class RtBackendInitializationFlightRecorder {
    private static final boolean ENABLED = Boolean.getBoolean("mcvulkanrt.takeoverFlightRecorder.enabled");
    private static final EventType EVENT_TYPE = eventType();

    private RtBackendInitializationFlightRecorder() {
    }

    static void record(
            long totalNanos,
            long factoryOpenNanos,
            long foregroundAdmissionNanos,
            long summaryNanos,
            boolean successful
    ) {
        if (!ENABLED || EVENT_TYPE == null || !EVENT_TYPE.isEnabled()) return;
        try {
            BackendInitializationEvent event = new BackendInitializationEvent();
            event.totalMicros = micros(totalNanos);
            event.factoryOpenMicros = micros(factoryOpenNanos);
            event.foregroundAdmissionMicros = micros(foregroundAdmissionNanos);
            event.summaryMicros = micros(summaryNanos);
            event.successful = successful;
            event.commit();
        } catch (RuntimeException | LinkageError ignored) {
            // Backend diagnostics must not become backend initialization failures.
        }
    }

    private static long micros(long nanos) {
        return Math.max(0L, nanos) / 1_000L;
    }

    private static EventType eventType() {
        if (!ENABLED) return null;
        try {
            return EventType.getEventType(BackendInitializationEvent.class);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @Name("top.ceroxe.mcvulkanrt.BackendInitialization")
    @Label("MCVulkanRT Backend Initialization")
    @Category({"MCVulkanRT", "Backend"})
    @StackTrace(false)
    static final class BackendInitializationEvent extends Event {
        @Label("Total us") long totalMicros;
        @Label("Factory open us") long factoryOpenMicros;
        @Label("Initial foreground admission us") long foregroundAdmissionMicros;
        @Label("Initial summary us") long summaryMicros;
        @Label("Successful") boolean successful;
    }
}
