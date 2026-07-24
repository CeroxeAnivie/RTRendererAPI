package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;

/**
 * Phase-level evidence for CPU work performed after GPU BLAS completion and while scheduling a
 * world TLAS. These events deliberately contain completed timings instead of opening nested JFR
 * events: the renderer keeps one authoritative phase clock and the recording path cannot alter
 * cache ownership, acquire another renderer lock, or poll Vulkan a second time.
 */
final class RtSceneWorkFlightRecorder {
    private static final boolean CONFIGURED =
            Boolean.getBoolean("mcvulkanrt.takeoverFlightRecorder.enabled");
    private static final EventType SECTION_APPLY_TYPE = eventType(SectionBlasApplyPhaseEvent.class);
    private static final EventType WORLD_TLAS_TYPE = eventType(WorldTlasSchedulerPhaseEvent.class);
    private static volatile boolean failedClosed;

    private RtSceneWorkFlightRecorder() {
    }

    static boolean sectionApplyEnabled() {
        return enabled(SECTION_APPLY_TYPE);
    }

    static boolean worldTlasEnabled() {
        return enabled(WORLD_TLAS_TYPE);
    }

    static void recordSectionApply(
            SectionKey key,
            long contentRevision,
            long buildSequence,
            int triangles,
            boolean replacement,
            long totalNanos,
            long prepareNanos,
            long residentInstallNanos,
            long activePublicationNanos,
            long materialSlotNanos,
            long bookkeepingNanos,
            long sourceReleaseNanos,
            long sourceTrimNanos,
            long residentEvictionNanos
    ) {
        if (!enabled(SECTION_APPLY_TYPE)) {
            return;
        }
        try {
            SectionBlasApplyPhaseEvent event = new SectionBlasApplyPhaseEvent();
            event.sectionX = key.x();
            event.sectionY = key.y();
            event.sectionZ = key.z();
            event.contentRevision = contentRevision;
            event.buildSequence = buildSequence;
            event.triangles = triangles;
            event.replacement = replacement;
            event.totalNanos = totalNanos;
            event.prepareNanos = prepareNanos;
            event.residentInstallNanos = residentInstallNanos;
            event.activePublicationNanos = activePublicationNanos;
            event.materialSlotNanos = materialSlotNanos;
            event.bookkeepingNanos = bookkeepingNanos;
            event.sourceReleaseNanos = sourceReleaseNanos;
            event.sourceTrimNanos = sourceTrimNanos;
            event.residentEvictionNanos = residentEvictionNanos;
            event.commit();
        } catch (RuntimeException | LinkageError ignored) {
            failedClosed = true;
        }
    }

    static void recordWorldTlasScheduler(
            String outcome,
            long totalNanos,
            long smokeAggregateNanos,
            long statsSnapshotNanos,
            long observationNanos,
            long completionPollNanos,
            long gateNanos,
            long inputSnapshotNanos,
            long submitNanos,
            long readinessPublishNanos,
            int observedInstances,
            int sectionInstances,
            int sectionActiveViewSections,
            int sectionExactInstances,
            int sectionFarFieldInstances,
            int dynamicInstances,
            int pendingSectionBuilds,
            boolean sectionViewAuthoritative,
            boolean sectionForegroundAuthoritative,
            boolean forceCurrentRevision
    ) {
        if (!enabled(WORLD_TLAS_TYPE)) {
            return;
        }
        try {
            WorldTlasSchedulerPhaseEvent event = new WorldTlasSchedulerPhaseEvent();
            event.outcome = outcome;
            event.totalNanos = totalNanos;
            event.smokeAggregateNanos = smokeAggregateNanos;
            event.statsSnapshotNanos = statsSnapshotNanos;
            event.observationNanos = observationNanos;
            event.completionPollNanos = completionPollNanos;
            event.gateNanos = gateNanos;
            event.inputSnapshotNanos = inputSnapshotNanos;
            event.submitNanos = submitNanos;
            event.readinessPublishNanos = readinessPublishNanos;
            event.observedInstances = observedInstances;
            event.sectionInstances = sectionInstances;
            event.sectionActiveViewSections = sectionActiveViewSections;
            event.sectionExactInstances = sectionExactInstances;
            event.sectionFarFieldInstances = sectionFarFieldInstances;
            event.dynamicInstances = dynamicInstances;
            event.pendingSectionBuilds = pendingSectionBuilds;
            event.sectionViewAuthoritative = sectionViewAuthoritative;
            event.sectionForegroundAuthoritative = sectionForegroundAuthoritative;
            event.forceCurrentRevision = forceCurrentRevision;
            event.commit();
        } catch (RuntimeException | LinkageError ignored) {
            failedClosed = true;
        }
    }

    private static boolean enabled(EventType type) {
        return CONFIGURED && !failedClosed && type != null && type.isEnabled();
    }

    private static EventType eventType(Class<? extends Event> eventClass) {
        if (!CONFIGURED) {
            return null;
        }
        try {
            return EventType.getEventType(eventClass);
        } catch (RuntimeException | LinkageError ignored) {
            failedClosed = true;
            return null;
        }
    }

    @Name("top.ceroxe.mcvulkanrt.SectionBlasApplyPhase")
    @Label("MCVulkanRT Section BLAS Apply Phase")
    @Category({"MCVulkanRT", "Acceleration", "BLAS"})
    @StackTrace(false)
    static final class SectionBlasApplyPhaseEvent extends Event {
        int sectionX;
        int sectionY;
        int sectionZ;
        long contentRevision;
        long buildSequence;
        int triangles;
        boolean replacement;
        long totalNanos;
        long prepareNanos;
        long residentInstallNanos;
        long activePublicationNanos;
        long materialSlotNanos;
        long bookkeepingNanos;
        long sourceReleaseNanos;
        long sourceTrimNanos;
        long residentEvictionNanos;
    }

    @Name("top.ceroxe.mcvulkanrt.WorldTlasSchedulerPhase")
    @Label("MCVulkanRT World TLAS Scheduler Phase")
    @Category({"MCVulkanRT", "Acceleration", "TLAS"})
    @StackTrace(false)
    static final class WorldTlasSchedulerPhaseEvent extends Event {
        String outcome;
        long totalNanos;
        long smokeAggregateNanos;
        long statsSnapshotNanos;
        long observationNanos;
        long completionPollNanos;
        long gateNanos;
        long inputSnapshotNanos;
        long submitNanos;
        long readinessPublishNanos;
        int observedInstances;
        int sectionInstances;
        int sectionActiveViewSections;
        int sectionExactInstances;
        int sectionFarFieldInstances;
        int dynamicInstances;
        int pendingSectionBuilds;
        boolean sectionViewAuthoritative;
        boolean sectionForegroundAuthoritative;
        boolean forceCurrentRevision;
    }
}
