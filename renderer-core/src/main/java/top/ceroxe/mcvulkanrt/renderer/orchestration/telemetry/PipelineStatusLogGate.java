package top.ceroxe.mcvulkanrt.renderer.orchestration.telemetry;

/** Bounds pipeline status logs while forcing new failures through immediately. */
public final class PipelineStatusLogGate {
    private static final long INITIAL_LOGS = 8L;
    private final long frameInterval, slowInterval; private long lastFrame,lastSlow,lastFailures;
    public PipelineStatusLogGate(long frameInterval,long slowInterval) { if(frameInterval<=0||slowInterval<=0) throw new IllegalArgumentException("intervals must be positive"); this.frameInterval=frameInterval; this.slowInterval=slowInterval; }
    public synchronized Decision evaluate(long frames,long slow,long failures) { if(frames<=0||slow<0||failures<0) throw new IllegalArgumentException("invalid counters"); boolean slowMilestone=slow>lastSlow&&(slow<=INITIAL_LOGS||slow%slowInterval==0), failure=failures>lastFailures; if((frames==lastFrame&&!slowMilestone&&!failure)|| (frames>INITIAL_LOGS&&frames%frameInterval!=0&&!slowMilestone&&!failure)) return new Decision(false,false,""); lastFrame=frames;if(slowMilestone)lastSlow=slow;if(failure)lastFailures=failures;return new Decision(true,failure,reason(frames<=INITIAL_LOGS,frames%frameInterval==0,slowMilestone,failure)); }
    private static String reason(boolean a,boolean b,boolean c,boolean d){StringBuilder s=new StringBuilder();add(s,a,"initial");add(s,b,"periodic");add(s,c,"slowFrame");add(s,d,"presentationFailure");return s.toString();} private static void add(StringBuilder s,boolean on,String v){if(on){if(!s.isEmpty())s.append('+');s.append(v);}}
    public record Decision(boolean shouldLog, boolean warn, String reason) { public Decision { reason=reason==null?"":reason; } }
}
