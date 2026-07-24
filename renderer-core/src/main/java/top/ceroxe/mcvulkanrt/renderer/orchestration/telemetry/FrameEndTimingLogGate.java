package top.ceroxe.mcvulkanrt.renderer.orchestration.telemetry;

/** Bounds frame-end timing logs without losing first and slow-frame evidence. */
public final class FrameEndTimingLogGate {
    private static final long INITIAL_LOGS = 8L;
    private final long frameInterval, slowFrameInterval; private long slowSamples, lastFrame, lastSlow;
    public FrameEndTimingLogGate(long frameInterval, long slowFrameInterval) { if (frameInterval <= 0 || slowFrameInterval <= 0) throw new IllegalArgumentException("intervals must be positive"); this.frameInterval = frameInterval; this.slowFrameInterval = slowFrameInterval; }
    public synchronized Decision evaluate(long frameEnds, boolean slowFrame) { if (frameEnds <= 0) throw new IllegalArgumentException("frameEnds must be positive"); if (slowFrame) slowSamples++; boolean initial=frameEnds<=INITIAL_LOGS, periodic=frameEnds%frameInterval==0, slow=slowFrame && slowSamples>lastSlow && (slowSamples==1 || slowSamples%slowFrameInterval==0); if ((frameEnds==lastFrame&&!slow)||(!initial&&!periodic&&!slow)) return new Decision(false,"skip",slowSamples); lastFrame=frameEnds; if(slow) lastSlow=slowSamples; return new Decision(true, reason(initial,periodic,slow),slowSamples); }
    private static String reason(boolean initial, boolean periodic, boolean slow) { StringBuilder b=new StringBuilder(); add(b,initial,"initial"); add(b,periodic,"periodic"); add(b,slow,"slowFrame"); return b.toString(); }
    private static void add(StringBuilder b, boolean on, String v) { if(on) { if(!b.isEmpty()) b.append('+'); b.append(v); } }
    public record Decision(boolean shouldLog, String reason, long slowFrameSamples) { public Decision { reason = reason == null ? "" : reason; } }
}
