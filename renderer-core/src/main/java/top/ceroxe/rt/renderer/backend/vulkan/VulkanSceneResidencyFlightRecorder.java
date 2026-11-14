package top.ceroxe.rt.renderer.backend.vulkan;

import jdk.jfr.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded JFR publication evidence for the persistent GPUScene mirror.
 *
 * <p>The recorder receives only already-committed scalar statistics. It intentionally retains
 * neither assets nor prepared transactions, so diagnostics cannot prolong CPU or native resource
 * lifetime. Once the configured full-fidelity budget is exhausted it emits sampled loss evidence
 * rather than adding a hot-path allocation or silently hiding overload.</p>
 */
final class VulkanSceneResidencyFlightRecorder {
    private static final boolean ENABLED =
            Boolean.getBoolean("top.ceroxe.rt.takeoverFlightRecorder.enabled");
    private static final long MAX_EVENTS = positiveLongProperty(
            "top.ceroxe.rt.takeoverFlightRecorder.sceneResidencyMaxEvents",
            65_536L
    );
    private static final long OVERFLOW_SAMPLE_MASK = 63L;
    private static final AtomicLong ATTEMPTS = new AtomicLong();
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final AtomicBoolean FAILED_CLOSED = new AtomicBoolean();
    private static final EventType RESIDENCY_EVENT_TYPE = EventType.getEventType(SceneResidencyEvent.class);
    private static final EventType CAPTURE_LOSS_EVENT_TYPE =
            EventType.getEventType(SceneResidencyCaptureLossEvent.class);
    private static final ThreadLocal<SceneResidencyEvent> THREAD_EVENT =
            ThreadLocal.withInitial(SceneResidencyEvent::new);

    private VulkanSceneResidencyFlightRecorder() {
    }

    static void recordCommitted(VulkanSceneResidency.SceneChangeSet changeSet) {
        if (!ENABLED || FAILED_CLOSED.get() || !RESIDENCY_EVENT_TYPE.isEnabled()) {
            return;
        }
        try {
            if (!admit()) {
                return;
            }
            SceneResidencyEvent event = THREAD_EVENT.get();
            event.baseRevision = changeSet.baseRevision();
            event.revision = changeSet.revision();
            event.reset = changeSet.reset();
            event.totalWrites = changeSet.totalWrites();
            event.totalRemovals = changeSet.totalRemovals();
            event.totalClears = changeSet.totalClears();
            writeTextures(event, changeSet.textures().statistics());
            writeMaterials(event, changeSet.materials().statistics());
            writeMeshes(event, changeSet.meshes().statistics());
            writeInstances(event, changeSet.instances().statistics());
            writeLights(event, changeSet.lights().statistics());
            event.droppedBefore = DROPPED.get();
            event.commit();
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            FAILED_CLOSED.set(true);
        }
    }

    private static void writeTextures(SceneResidencyEvent event, VulkanSceneResidency.DomainUpdateStatistics source) {
        event.textureWrites = source.writes();
        event.textureRemovals = source.removals();
        event.textureClears = source.clears();
        event.textureLiveSlots = source.liveSlots();
        event.textureSlotUpperBound = source.slotUpperBound();
    }

    private static void writeMaterials(SceneResidencyEvent event, VulkanSceneResidency.DomainUpdateStatistics source) {
        event.materialWrites = source.writes();
        event.materialRemovals = source.removals();
        event.materialClears = source.clears();
        event.materialLiveSlots = source.liveSlots();
        event.materialSlotUpperBound = source.slotUpperBound();
    }

    private static void writeMeshes(SceneResidencyEvent event, VulkanSceneResidency.DomainUpdateStatistics source) {
        event.meshWrites = source.writes();
        event.meshRemovals = source.removals();
        event.meshClears = source.clears();
        event.meshLiveSlots = source.liveSlots();
        event.meshSlotUpperBound = source.slotUpperBound();
    }

    private static void writeInstances(SceneResidencyEvent event, VulkanSceneResidency.DomainUpdateStatistics source) {
        event.instanceWrites = source.writes();
        event.instanceRemovals = source.removals();
        event.instanceClears = source.clears();
        event.instanceLiveSlots = source.liveSlots();
        event.instanceSlotUpperBound = source.slotUpperBound();
    }

    private static void writeLights(SceneResidencyEvent event, VulkanSceneResidency.DomainUpdateStatistics source) {
        event.lightWrites = source.writes();
        event.lightRemovals = source.removals();
        event.lightClears = source.clears();
        event.lightLiveSlots = source.liveSlots();
        event.lightSlotUpperBound = source.slotUpperBound();
    }

    private static boolean admit() {
        long sequence = ATTEMPTS.incrementAndGet();
        if (sequence <= MAX_EVENTS || ((sequence - MAX_EVENTS) & OVERFLOW_SAMPLE_MASK) == 0L) {
            return true;
        }
        long dropped = DROPPED.incrementAndGet();
        if (dropped == 1L || (dropped & (dropped - 1L)) == 0L) {
            recordCaptureLoss(sequence, dropped);
        }
        return false;
    }

    private static void recordCaptureLoss(long attemptedEvents, long droppedEvents) {
        if (!CAPTURE_LOSS_EVENT_TYPE.isEnabled()) {
            return;
        }
        SceneResidencyCaptureLossEvent event = new SceneResidencyCaptureLossEvent();
        event.maxEvents = MAX_EVENTS;
        event.attemptedEvents = attemptedEvents;
        event.droppedEventsLowerBound = droppedEvents;
        event.overflowSampleStride = OVERFLOW_SAMPLE_MASK + 1L;
        event.commit();
    }

    private static long positiveLongProperty(String property, long defaultValue) {
        String configured = System.getProperty(property);
        if (configured == null || configured.isBlank()) {
            return defaultValue;
        }
        try {
            long value = Long.parseLong(configured);
            return value > 0L ? value : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    @Name("top.ceroxe.rt.VulkanSceneResidency")
    @Label("Vulkan RT Scene Residency")
    @Category({"RTRenderer", "Renderer", "GPUScene"})
    @StackTrace(false)
    static final class SceneResidencyEvent extends Event {
        long baseRevision;
        long revision;
        boolean reset;
        long totalWrites;
        long totalRemovals;
        long totalClears;
        long textureWrites;
        long textureRemovals;
        long textureClears;
        long textureLiveSlots;
        long textureSlotUpperBound;
        long materialWrites;
        long materialRemovals;
        long materialClears;
        long materialLiveSlots;
        long materialSlotUpperBound;
        long meshWrites;
        long meshRemovals;
        long meshClears;
        long meshLiveSlots;
        long meshSlotUpperBound;
        long instanceWrites;
        long instanceRemovals;
        long instanceClears;
        long instanceLiveSlots;
        long instanceSlotUpperBound;
        long lightWrites;
        long lightRemovals;
        long lightClears;
        long lightLiveSlots;
        long lightSlotUpperBound;
        long droppedBefore;
    }

    @Name("top.ceroxe.rt.VulkanSceneResidencyCaptureLoss")
    @Label("Vulkan RT Scene Residency Capture Loss")
    @Category({"RTRenderer", "Renderer", "GPUScene"})
    @StackTrace(false)
    static final class SceneResidencyCaptureLossEvent extends Event {
        long maxEvents;
        long attemptedEvents;
        long droppedEventsLowerBound;
        long overflowSampleStride;
    }
}
