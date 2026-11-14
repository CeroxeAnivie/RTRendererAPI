package top.ceroxe.rt.renderer.rt.device;

import jdk.jfr.EventType;
import top.ceroxe.rt.renderer.rt.RtSceneReadiness;
import top.ceroxe.rt.renderer.rt.runtime.RtCore;

/**
 * Emits only materially slow renderer transactions. The event observes completed stage state;
 * it neither polls Vulkan nor changes the scheduler's frame budget.
 */
final class VulkanAcceptFrameStallRecorder {
    private static final boolean ENABLED = Boolean.getBoolean("top.ceroxe.rt.takeoverFlightRecorder.enabled");
    private static final long STALL_THRESHOLD_NANOS = 8_000_000L;

    private final EventType eventType;
    private boolean failedClosed;

    VulkanAcceptFrameStallRecorder() {
        EventType type = null;
        if (ENABLED) {
            try {
                type = EventType.getEventType(VulkanAcceptFrameStallEvent.class);
            } catch (RuntimeException | LinkageError ignored) {
                failedClosed = true;
            }
        }
        eventType = type;
    }

    void record(
            long elapsedNanos,
            String terminalStage,
            RtSceneReadiness readiness,
            RtCore.RuntimeActivity activity,
            VulkanAcceptFrameTiming.FrameBreakdown breakdown
    ) {
        if (elapsedNanos < STALL_THRESHOLD_NANOS || !ENABLED || failedClosed
                || eventType == null || !eventType.isEnabled()) {
            return;
        }
        try {
            VulkanAcceptFrameStallEvent event = new VulkanAcceptFrameStallEvent();
            event.terminalStage = terminalStage;
            event.totalMicros = elapsedNanos / 1_000L;
            event.ingestMicros = breakdown.ingestMicros();
            event.preBuildMicros = breakdown.preBuildMicros();
            event.sectionCompletionPumpMicros = breakdown.sectionCompletionPumpMicros();
            event.buildBudgetMicros = breakdown.buildBudgetMicros();
            event.buildSectionMicros = breakdown.buildSectionMicros();
            event.postBuildMicros = breakdown.postBuildMicros();
            event.worldTlasMicros = breakdown.worldTlasMicros();
            event.worldTlasSchedulerMicros = breakdown.worldTlasSchedulerMicros();
            event.worldTlasBindMicros = breakdown.worldTlasBindMicros();
            event.dispatchMicros = breakdown.dispatchMicros();
            event.pendingFrameAgeMillis = activity.pendingFrameAgeMillis();
            event.framePending = activity.pendingFrame();
            event.pendingRtSections = readiness.pendingRtSectionBuilds();
            event.pendingRtTriangles = readiness.pendingRtTriangles();
            event.observedSections = readiness.observedSectionInstances();
            event.builtSections = readiness.builtSectionInstances();
            event.worldTlasReady = readiness.worldTlasReady();
            event.commit();
        } catch (RuntimeException | LinkageError ignored) {
            failedClosed = true;
        }
    }
}
