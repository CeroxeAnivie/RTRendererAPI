package top.ceroxe.mcvulkanrt.renderer.rt.device;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/** JFR payload for one renderer-owned accept-frame stall. */
@Name("top.ceroxe.mcvulkanrt.AcceptFrameStall")
@Label("MCVulkanRT Accept Frame Stall")
@Category({"MCVulkanRT", "Frame", "Stall"})
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
