package top.ceroxe.mcvulkanrt.renderer.backend.vulkan;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded, allocation-stable JFR evidence for the generic frame-slot state machine. */
final class VulkanFrameFlightRecorder {
    static final int PHASE_ADMITTED = 1;
    static final int PHASE_PRODUCER_COMPLETED = 2;
    static final int PHASE_LEASE_ACQUIRED = 3;
    static final int PHASE_CONSUMER_COMPLETED = 4;

    private static final boolean ENABLED = Boolean.getBoolean("mcvulkanrt.renderer.jfr.enabled");
    private static final long MAX_EVENTS = positiveLongProperty(
            "mcvulkanrt.renderer.jfr.frameMaxEvents", 262_144L
    );
    private static final long OVERFLOW_SAMPLE_MASK = 63L;
    private static final AtomicLong ATTEMPTS = new AtomicLong();
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final AtomicBoolean FAILED_CLOSED = new AtomicBoolean();
    private static final EventType EVENT_TYPE = EventType.getEventType(FrameLifecycleEvent.class);
    private static final EventType LOSS_TYPE = EventType.getEventType(FrameCaptureLossEvent.class);
    private static final ThreadLocal<FrameLifecycleEvent> THREAD_EVENT =
            ThreadLocal.withInitial(FrameLifecycleEvent::new);

    private VulkanFrameFlightRecorder() {
    }

    static void record(
            int phase,
            int slot,
            long frameSequence,
            long sceneRevision,
            long descriptorEpoch,
            int width,
            int height,
            long producerFenceNanos,
            long producerNotReadyPolls
    ) {
        if (!ENABLED || FAILED_CLOSED.get() || !EVENT_TYPE.isEnabled()) return;
        try {
            long attempt = ATTEMPTS.incrementAndGet();
            if (attempt > MAX_EVENTS && ((attempt - MAX_EVENTS) & OVERFLOW_SAMPLE_MASK) != 0L) {
                long dropped = DROPPED.incrementAndGet();
                if (dropped == 1L || (dropped & (dropped - 1L)) == 0L) {
                    recordLoss(attempt, dropped);
                }
                return;
            }
            FrameLifecycleEvent event = THREAD_EVENT.get();
            event.phase = phase;
            event.slot = slot;
            event.frameSequence = frameSequence;
            event.sceneRevision = sceneRevision;
            event.descriptorEpoch = descriptorEpoch;
            event.width = width;
            event.height = height;
            event.producerFenceNanos = producerFenceNanos;
            event.producerNotReadyPolls = producerNotReadyPolls;
            event.droppedBefore = DROPPED.get();
            event.commit();
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            FAILED_CLOSED.set(true);
        }
    }

    private static void recordLoss(long attempted, long dropped) {
        if (!LOSS_TYPE.isEnabled()) return;
        FrameCaptureLossEvent event = new FrameCaptureLossEvent();
        event.maxEvents = MAX_EVENTS;
        event.attemptedEvents = attempted;
        event.droppedEventsLowerBound = dropped;
        event.overflowSampleStride = OVERFLOW_SAMPLE_MASK + 1L;
        event.commit();
    }

    private static long positiveLongProperty(String name, long fallback) {
        String configured = System.getProperty(name);
        if (configured == null || configured.isBlank()) return fallback;
        try {
            long parsed = Long.parseLong(configured);
            return parsed > 0L ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    @Name("top.ceroxe.mcvulkanrt.VulkanFrameLifecycle")
    @Label("Vulkan RT Frame Lifecycle")
    @Category({"MCVulkanRT", "Renderer", "Frame"})
    @StackTrace(false)
    static final class FrameLifecycleEvent extends Event {
        int phase;
        int slot;
        long frameSequence;
        long sceneRevision;
        long descriptorEpoch;
        int width;
        int height;
        long producerFenceNanos;
        long producerNotReadyPolls;
        long droppedBefore;
    }

    @Name("top.ceroxe.mcvulkanrt.VulkanFrameCaptureLoss")
    @Label("Vulkan RT Frame Capture Loss")
    @Category({"MCVulkanRT", "Renderer", "Frame"})
    @StackTrace(false)
    static final class FrameCaptureLossEvent extends Event {
        long maxEvents;
        long attemptedEvents;
        long droppedEventsLowerBound;
        long overflowSampleStride;
    }
}
