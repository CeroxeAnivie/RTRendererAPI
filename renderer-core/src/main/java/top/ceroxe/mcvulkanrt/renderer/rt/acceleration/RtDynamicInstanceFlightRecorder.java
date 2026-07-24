package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import top.ceroxe.mcvulkanrt.renderer.DynamicMeshAsset;
import top.ceroxe.mcvulkanrt.renderer.DynamicMeshInstance;
import top.ceroxe.mcvulkanrt.renderer.DynamicRenderScene;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded evidence for one captured model instance entering the dynamic TLAS slot table. */
final class RtDynamicInstanceFlightRecorder {
    private static final boolean ENABLED = Boolean.getBoolean("mcvulkanrt.takeoverFlightRecorder.enabled");
    private static final long MAX_EVENTS = positiveLongProperty(
            "mcvulkanrt.takeoverFlightRecorder.dynamicInstanceMaxEvents",
            131_072L
    );
    private static final long OVERFLOW_SAMPLE_MASK = 63L;
    private static final AtomicLong ATTEMPTS = new AtomicLong();
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final AtomicBoolean FAILED_CLOSED = new AtomicBoolean();
    private static final EventType DYNAMIC_TLAS_EVENT_TYPE = EventType.getEventType(DynamicTlasInstanceEvent.class);
    private static final EventType CAPTURE_LOSS_EVENT_TYPE = EventType.getEventType(DynamicTlasCaptureLossEvent.class);

    private RtDynamicInstanceFlightRecorder() {
    }

    static void record(
            String stage,
            long revision,
            long topologyRevision,
            long transformRevision,
            long geometryRevision,
            long materialRevision,
            long sceneRevision,
            int physicalSlot,
            int customIndex,
            int visibilityMask,
            long blasDeviceAddress,
            DynamicRenderScene.DynamicModelInstance instance,
            RtDynamicTransformSlots transforms,
            DynamicMeshAsset residentAsset,
            boolean critical
    ) {
        if (!ENABLED || FAILED_CLOSED.get()
                || (!critical && revision > 4L && (revision & 63L) != 0L)) {
            return;
        }
        try {
            if (!admit(DYNAMIC_TLAS_EVENT_TYPE)) {
                return;
            }
            DynamicTlasInstanceEvent event = new DynamicTlasInstanceEvent();
            event.stage = stage;
            event.revision = revision;
            event.topologyRevision = topologyRevision;
            event.transformRevision = transformRevision;
            event.geometryRevision = geometryRevision;
            event.materialRevision = materialRevision;
            event.sceneRevision = sceneRevision;
            event.physicalSlot = physicalSlot;
            event.customIndex = customIndex;
            event.visibilityMask = visibilityMask;
            event.blasDeviceAddress = blasDeviceAddress;
            event.primitiveId = instance.id();
            event.primitiveKind = instance.kind().name();
            event.debugName = instance.debugName();
            event.assetId = instance.asset().id();
            event.assetRevision = instance.asset().revision();
            event.residentAssetRevision = residentAsset == null ? -1L : residentAsset.revision();
            event.faceCount = instance.asset().faceCount();
            event.renderLane = instance.renderLane().name();
            event.m00 = transforms.value(physicalSlot, 0);
            event.m01 = transforms.value(physicalSlot, 1);
            event.m02 = transforms.value(physicalSlot, 2);
            event.m03 = transforms.value(physicalSlot, 3);
            event.m10 = transforms.value(physicalSlot, 4);
            event.m11 = transforms.value(physicalSlot, 5);
            event.m12 = transforms.value(physicalSlot, 6);
            event.m13 = transforms.value(physicalSlot, 7);
            event.m20 = transforms.value(physicalSlot, 8);
            event.m21 = transforms.value(physicalSlot, 9);
            event.m22 = transforms.value(physicalSlot, 10);
            event.m23 = transforms.value(physicalSlot, 11);
            event.determinant = determinant(transforms, physicalSlot);
            event.droppedBefore = DROPPED.get();
            event.commit();
        } catch (RuntimeException | LinkageError failure) {
            FAILED_CLOSED.set(true);
        }
    }

    private static boolean admit(EventType eventType) {
        if (!ENABLED || FAILED_CLOSED.get() || !eventType.isEnabled()) {
            return false;
        }
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
        DynamicTlasCaptureLossEvent event = new DynamicTlasCaptureLossEvent();
        event.maxEvents = MAX_EVENTS;
        event.attemptedEvents = attemptedEvents;
        event.droppedEventsLowerBound = droppedEvents;
        event.overflowSampleStride = OVERFLOW_SAMPLE_MASK + 1L;
        event.commit();
    }

    private static float determinant(RtDynamicTransformSlots transforms, int slot) {
        float m00 = transforms.value(slot, 0);
        float m01 = transforms.value(slot, 1);
        float m02 = transforms.value(slot, 2);
        float m10 = transforms.value(slot, 4);
        float m11 = transforms.value(slot, 5);
        float m12 = transforms.value(slot, 6);
        float m20 = transforms.value(slot, 8);
        float m21 = transforms.value(slot, 9);
        float m22 = transforms.value(slot, 10);
        return m00 * (m11 * m22 - m12 * m21)
                - m01 * (m10 * m22 - m12 * m20)
                + m02 * (m10 * m21 - m11 * m20);
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

    @Name("top.ceroxe.mcvulkanrt.DynamicTlasInstance")
    @Label("MCVulkanRT Dynamic TLAS Instance")
    @Category({"MCVulkanRT", "Dynamic Acceleration"})
    @StackTrace(false)
    static final class DynamicTlasInstanceEvent extends Event {
        String stage;
        long revision;
        long topologyRevision;
        long transformRevision;
        long geometryRevision;
        long materialRevision;
        long sceneRevision;
        int physicalSlot;
        int customIndex;
        int visibilityMask;
        long blasDeviceAddress;
        long primitiveId;
        String primitiveKind;
        String debugName;
        long assetId;
        long assetRevision;
        long residentAssetRevision;
        int faceCount;
        String renderLane;
        float m00, m01, m02, m03;
        float m10, m11, m12, m13;
        float m20, m21, m22, m23;
        float determinant;
        long droppedBefore;
    }

    @Name("top.ceroxe.mcvulkanrt.DynamicTlasCaptureLoss")
    @Label("MCVulkanRT Dynamic TLAS Capture Loss")
    @Category({"MCVulkanRT", "Dynamic Acceleration"})
    @StackTrace(false)
    static final class DynamicTlasCaptureLossEvent extends Event {
        long maxEvents;
        long attemptedEvents;
        long droppedEventsLowerBound;
        long overflowSampleStride;
    }
}
