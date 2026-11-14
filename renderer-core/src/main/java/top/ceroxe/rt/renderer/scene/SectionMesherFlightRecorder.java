package top.ceroxe.rt.renderer.scene;

import jdk.jfr.*;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Causality-only phase evidence for a single immutable section mesh build.
 *
 * <p>The first RT foreground can only become visible after every frozen
 * section has completed CPU meshing.  A total worker duration cannot tell
 * whether its long tail is input classification or surface emission.  The
 * event also preserves block/fluid source counts and output faces, so an
 * observed surface cost can be joined to the exact material mix without
 * putting a clock inside the voxel loop. This recorder is therefore enabled only by the
 * existing smoke causality switch and emits fixed primitive fields to JFR.
 * Normal rendering does not read a clock in the inner meshing loops.</p>
 */
public final class SectionMesherFlightRecorder {
    private static final long MAX_EVENTS = Long.getLong(
            "top.ceroxe.rt.takeoverFlightRecorder.sectionMesherMaxEvents", 16_384L
    );
    private static final AtomicLong CAPTURED_EVENTS = new AtomicLong();
    private static final AtomicLong DROPPED_EVENTS = new AtomicLong();
    private static final ThreadMXBean THREAD_MX_BEAN = ManagementFactory.getThreadMXBean();

    private SectionMesherFlightRecorder() {
    }

    /**
     * Reports whether section-meshing JFR capture is enabled.
     *
     * @return whether the section-meshing JFR event is enabled
     */
    public static boolean enabled() {
        return new SectionMesherEvent().isEnabled();
    }

    /**
     * Samples supported current-thread CPU time.
     *
     * @return current thread CPU time in nanoseconds, or zero when unsupported
     */
    public static long currentThreadCpuNanos() {
        return THREAD_MX_BEAN.isCurrentThreadCpuTimeSupported()
                && THREAD_MX_BEAN.isThreadCpuTimeEnabled()
                ? Math.max(0L, THREAD_MX_BEAN.getCurrentThreadCpuTime())
                : 0L;
    }

    /**
     * Emits bounded phase timings for one completed section mesh.
     *
     * @param key                     section identity
     * @param faces                   emitted face count
     * @param blockSources            classified block-source count
     * @param fluidSources            classified fluid-source count
     * @param classificationNanos     classification wall time
     * @param classificationCpuNanos  classification CPU time
     * @param surfaceEmissionNanos    emission wall time
     * @param surfaceEmissionCpuNanos emission CPU time
     * @param totalNanos              total wall time
     */
    public static void record(
            SectionKey key,
            int faces,
            int blockSources,
            int fluidSources,
            long classificationNanos,
            long classificationCpuNanos,
            long surfaceEmissionNanos,
            long surfaceEmissionCpuNanos,
            long totalNanos
    ) {
        SectionMesherEvent event = new SectionMesherEvent();
        if (!event.isEnabled()) {
            return;
        }
        long captured = CAPTURED_EVENTS.incrementAndGet();
        if (captured > MAX_EVENTS) {
            recordCaptureLoss(captured);
            return;
        }
        event.sectionX = key.x();
        event.sectionY = key.y();
        event.sectionZ = key.z();
        event.faces = faces;
        event.blockSources = blockSources;
        event.fluidSources = fluidSources;
        event.classificationNanos = classificationNanos;
        event.classificationCpuNanos = classificationCpuNanos;
        event.surfaceEmissionNanos = surfaceEmissionNanos;
        event.surfaceEmissionCpuNanos = surfaceEmissionCpuNanos;
        event.totalNanos = totalNanos;
        event.commit();
    }

    /**
     * Records cap-induced evidence loss without turning a pathological rebuild
     * burst into another high-volume event stream. Power-of-two reports retain
     * a monotonically increasing lower bound even if the run ends abruptly.
     */
    private static void recordCaptureLoss(long attempted) {
        long dropped = DROPPED_EVENTS.incrementAndGet();
        if (dropped != 1L && (dropped & (dropped - 1L)) != 0L) {
            return;
        }
        SectionMesherCaptureLossEvent event = new SectionMesherCaptureLossEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.maxEvents = MAX_EVENTS;
        event.attemptedEvents = attempted;
        event.droppedEventsLowerBound = dropped;
        event.commit();
    }

    @Name("top.ceroxe.rt.SectionMesherPhase")
    @Label("RTRenderer Section Mesher Phases")
    @Category({"RTRenderer", "Scene", "Meshing"})
    @StackTrace(false)
    static final class SectionMesherEvent extends Event {
        int sectionX;
        int sectionY;
        int sectionZ;
        int faces;
        int blockSources;
        int fluidSources;
        long classificationNanos;
        long classificationCpuNanos;
        long surfaceEmissionNanos;
        long surfaceEmissionCpuNanos;
        long totalNanos;
    }

    @Name("top.ceroxe.rt.SectionMesherCaptureLoss")
    @Label("RTRenderer Section Mesher Capture Loss")
    @Category({"RTRenderer", "Scene", "Meshing"})
    @StackTrace(false)
    static final class SectionMesherCaptureLossEvent extends Event {
        long maxEvents;
        long attemptedEvents;
        long droppedEventsLowerBound;
    }
}
