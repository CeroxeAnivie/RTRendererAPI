package top.ceroxe.rt.renderer.rtxpt;

import jdk.jfr.*;

/** Scoped native packet storage; distinct from the JVM DirectBufferStatistics pool. */
@Name("top.ceroxe.rt.renderer.rtxpt.ResourcePacketMemory")
@Label("RTXPT resource packet memory")
@Category({"RTRendererAPI","Resources"})
@StackTrace(false)
final class RtxptResourcePacketEvent extends Event {
    String action;
    @Unsigned long pool;
    @DataAmount(DataAmount.BYTES) long usedBytes;
    @DataAmount(DataAmount.BYTES) long capacityBytes;
    @DataAmount(DataAmount.BYTES) long releasedBytes;
}
