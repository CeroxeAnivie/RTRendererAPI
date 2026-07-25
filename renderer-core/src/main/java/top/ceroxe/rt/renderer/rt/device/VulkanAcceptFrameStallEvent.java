package top.ceroxe.rt.renderer.rt.device;

import jdk.jfr.*;

/**
 * JFR payload for one renderer-owned accept-frame stall.
 */
@Name("top.ceroxe.rt.AcceptFrameStall")
@Label("RTRenderer Accept Frame Stall")
@Category({"RTRenderer", "Frame", "Stall"})
@StackTrace(false)
final class VulkanAcceptFrameStallEvent extends Event {
    String terminalStage;
    long totalMicros;
    long ingestMicros;
    long preBuildMicros;
    long sectionCompletionPumpMicros;
    long buildBudgetMicros;
    long buildSectionMicros;
    long postBuildMicros;
    long worldTlasMicros;
    long worldTlasSchedulerMicros;
    long worldTlasBindMicros;
    long dispatchMicros;
    long pendingFrameAgeMillis;
    boolean framePending;
    long pendingRtSections;
    long pendingRtTriangles;
    long observedSections;
    long builtSections;
    boolean worldTlasReady;
}
