package top.ceroxe.rt.renderer.rtxpt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Bounded native wall-clock evidence. Query snapshots outside measured frame work. */
public final class RtxptDiagnostics {
    private RtxptDiagnostics() { }
    public enum Level { OFF, SUMMARY, DEEP }
    public enum Stage { UNKNOWN, FRAME, INSTANCE_CACHE, SUB_INSTANCES, DISPATCH, CALLER,
        WORKER, TASK_WAIT, MESSAGE_PUMP, SHADER_CACHE, SHADER_COMPILE, SHADER_LIBRARY,
        PIPELINE, SHADER_TABLE, IMPORT, TEXTURES, MATERIALS, ACCELERATION, RETIREMENT,
        SHARED_PUBLISH, SHARED_REUSE_WAIT, RESIZE, POOL_WAIT, LIGHTING, PREPARATION, UPLOAD, SUBMIT }
    /** flags=1 on SHARED_REUSE_WAIT means CPU queue-wait enqueue cost, not a CPU fence wait.
     * batch links caller, workers and completion waits. Self subtracts same-thread children only. */
    public record CpuEvent(long sequence,long revision,long batch,long thread,Stage stage,
            long startNanos,long inclusiveNanos,long selfNanos,long items,long tasks,long parent,long flags) { }
    /** Percentiles describe FRAME events still retained in the rolling buffer, including slow frames. */
    public record Distribution(int samples,long p50Nanos,long p95Nanos,long p99Nanos,long maximumNanos,
            long over50Millis,long over100Millis,long over1Second) { }
    public record Snapshot(long overwrittenEvents,List<CpuEvent> events,Distribution frames,
            long slowFrameTriggers,long overwrittenSlowFrameEvents,List<CpuEvent> lastSlowFrame) {
        public Snapshot { events=List.copyOf(events);lastSlowFrame=List.copyOf(lastSlowFrame); }
    }
    /** A command-list timestamp range; parent IDs are local to nativeFrame and queue. */
    public record GpuScope(long nativeFrame,String queue,int event,int parent,String name,double inclusiveMillis,
            long beginTick,long endTick,long frequency,long sequence,long revision) { }
    public record GpuSnapshot(long droppedQueries,long overwrittenScopes,long pendingQueries,int sampleInterval,
            boolean enabled,List<GpuScope> scopes) {
        public GpuSnapshot { scopes=List.copyOf(scopes); }
    }
    static GpuSnapshot decodeGpu(String[] values){
        var status=values[0].split(",");var scopes=new ArrayList<GpuScope>(values.length-1);
        for(int i=1;i<values.length;i++){
            var v=values[i].strip().split(",");if(v.length!=11)throw new IllegalStateException("Incompatible GPU scope record");
            scopes.add(new GpuScope(Long.parseLong(v[0]),v[1],Integer.parseInt(v[2]),Integer.parseInt(v[3]),v[4],Double.parseDouble(v[5]),Long.parseLong(v[6]),Long.parseLong(v[7]),Long.parseLong(v[8]),Long.parseLong(v[9]),Long.parseLong(v[10])));
        }
        return new GpuSnapshot(Long.parseLong(status[0]),Long.parseLong(status[1]),Long.parseLong(status[2]),Integer.parseInt(status[3]),status[4].equals("1"),scopes);
    }
    static Snapshot decode(long[] values) {
        if(values.length<7||values[0]!=2||values[1]!=12||values[3]<0||values[3]>8192||values[5]<0||values[5]>2048||values.length!=7+12*(values[3]+values[5]))
            throw new IllegalStateException("Incompatible native diagnostic snapshot");
        var events=new ArrayList<CpuEvent>((int)values[3]);var stages=Stage.values();
        var slow=new ArrayList<CpuEvent>((int)values[5]);
        long[] frames=new long[(int)values[3]];int frameCount=0;
        for(int i=7;i<values.length;i+=12){
            int stage=(int)values[i+4];Stage name=stage>=0&&stage<stages.length?stages[stage]:Stage.UNKNOWN;
            var event=new CpuEvent(values[i],values[i+1],values[i+2],values[i+3],name,values[i+5],values[i+6],values[i+7],values[i+8],values[i+9],values[i+10],values[i+11]);
            if(i<7+12*values[3]){events.add(event);if(name==Stage.FRAME)frames[frameCount++]=values[i+6];}else slow.add(event);
        }
        frames=Arrays.copyOf(frames,frameCount);Arrays.sort(frames);
        long fifty=0,hundred=0,second=0;for(long frame:frames){if(frame>50_000_000)fifty++;if(frame>100_000_000)hundred++;if(frame>1_000_000_000)second++;}
        return new Snapshot(values[2],events,new Distribution(frameCount,percentile(frames,.50),percentile(frames,.95),percentile(frames,.99),frameCount==0?0:frames[frameCount-1],fifty,hundred,second),values[4],values[6],slow);
    }
    private static long percentile(long[] values,double percentile){return values.length==0?0:values[Math.max(0,(int)Math.ceil(values.length*percentile)-1)];}
}
