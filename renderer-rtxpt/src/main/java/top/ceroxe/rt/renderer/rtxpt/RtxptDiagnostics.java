package top.ceroxe.rt.renderer.rtxpt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Bounded native wall-clock evidence. Query snapshots outside measured frame work. */
public final class RtxptDiagnostics {
    private RtxptDiagnostics() { }
    public enum Level { OFF, SUMMARY, DEEP }
    public static final long FLAG_FAILED=2, FLAG_ACTIVE=256, FLAG_TRUNCATED=512,
        FLAG_CANCELLED=1024, FLAG_ENTERED_NATIVE_FRAME=2048;
    /** Dominant work for a submission: initial load, resize, resource/light update, wait, or steady. */
    public enum SubmissionKind { INITIAL, UPDATE, STEADY, RESIZE, WAIT }
    /** A borrowed thread-safe view. It never enters the renderer or waits for its owner thread.
     * A monitor stays valid across level changes and expires when a new native session replaces it. */
    @FunctionalInterface public interface Monitor { Snapshot snapshot(); }
    /** Does not enter the renderer, poll a fence or wait for the owner thread. */
    @FunctionalInterface public interface ProtocolMonitor { ProtocolSnapshot snapshot(); }
    /** flags: published=1, leased=2, returned=4, consumer imported=8. Tick time is monotonic milliseconds. */
    public record ProtocolEvent(long ordinal,long kind,long adapterLuid,long queue,long slot,long generation,long sequence,
        long revision,long readyTarget,long doneTarget,long observedCompletion,long flags,long error,long tickMillis,long thread,int diagnosticLevel) {
        public ProtocolEvent(long ordinal,long kind,long adapterLuid,long queue,long slot,long generation,long sequence,long revision,
                long readyTarget,long doneTarget,long observedCompletion,long flags,long error,long tickMillis,long thread){
            this(ordinal,kind,adapterLuid,queue,slot,generation,sequence,revision,readyTarget,doneTarget,observedCompletion,flags,error,tickMillis,thread,-1);
        }
    }
    public record ProtocolSnapshot(long token,long overwritten,List<ProtocolEvent> events,long firstOrdinal,long nextOrdinal,
            long registrationOrdinal,long missedSinceRegistration){
        public ProtocolSnapshot{events=List.copyOf(events);}
        public ProtocolSnapshot(long token,long overwritten,List<ProtocolEvent> events){
            this(token,overwritten,events,events.isEmpty()?overwritten+1:events.getFirst().ordinal(),
                events.isEmpty()?overwritten+1:events.getLast().ordinal()+1,0,0);
        }
    }
    static ProtocolSnapshot decodeProtocol(long[] data){
        if(data.length<4||data[3]!=16||data[2]<0||data[2]>256||data.length!=4+data[2]*16)throw new IllegalStateException("Invalid protocol snapshot");
        var events=new ArrayList<ProtocolEvent>((int)data[2]);for(int i=4;i<data.length;i+=16){
            if(data[i+15]<0||data[i+15]>2)throw new IllegalStateException("Invalid protocol diagnostic level");
            events.add(new ProtocolEvent(data[i],data[i+1],data[i+2],data[i+3],data[i+4],data[i+5],data[i+6],data[i+7],data[i+8],data[i+9],data[i+10],data[i+11],data[i+12],data[i+13],data[i+14],(int)data[i+15]));
        }
        return new ProtocolSnapshot(data[0],data[1],events);
    }
    public enum Stage { UNKNOWN, FRAME, INSTANCE_CACHE, SUB_INSTANCES, DISPATCH, CALLER,
        WORKER, TASK_WAIT, MESSAGE_PUMP, SHADER_CACHE, SHADER_COMPILE, SHADER_LIBRARY,
        PIPELINE, SHADER_TABLE, IMPORT, TEXTURES, MATERIALS, ACCELERATION, RETIREMENT,
        SHARED_PUBLISH, SHARED_REUSE_WAIT, RESIZE, POOL_WAIT, LIGHTING, PREPARATION, UPLOAD, SUBMIT,
        LIGHT_VALIDATION, LIGHT_OBJECTS, LIGHT_GRAPH, LIGHT_TREE, LIGHT_UPLOAD, LIGHTING_POLICY,
        SUBMIT_VALIDATION, TRANSLATION, RESOURCES, SUBMIT_RETIREMENT, ATTRIBUTE_PREPARATION, ATTRIBUTE_VALIDATION, RESOURCE_STAGING, RESOURCE_INDEX, RESOURCE_RETIREMENT,
        RESOURCE_PLAN, BUFFER_ALLOCATE, DESCRIPTOR_ALLOCATE, PAYLOAD_PACK, BUFFER_UPLOAD, BLAS_ALLOCATE, BLAS_CLONE, BLAS_BUILD, BLAS_REFIT,
        COMMAND_ACQUIRE, COMMAND_SUBMIT, COMPLETION_POLL, RECLAIM_ENQUEUE, DRIVER_RELEASE, INDEX_REBUILD, TEXTURE_ALLOCATE, TEXTURE_UPLOAD,
        RUNTIME_INITIALIZE, DEVICE_INITIALIZE, SCENE_INITIALIZE, LIGHTING_INITIALIZE, POOL_ACQUIRE, TLAS_ALLOCATE }
    /** flags=1 on SHARED_REUSE_WAIT means CPU queue-wait enqueue cost, not a CPU fence wait.
     * batch links caller, workers and completion waits. Self subtracts same-thread children only. */
    public record CpuEvent(long sequence,long revision,long batch,long thread,Stage stage,
            long startNanos,long inclusiveNanos,long selfNanos,long items,long tasks,long parent,long flags,
            long scopeId,long parentScopeId,long inputGeneration,long outputGeneration,long threadCpuNanos,long threadCpuSelfNanos,
            long resourceIdentity,long resourceGeneration,long reason,long bytes,long attempt,int diagnosticLevel,long ordinal) {
        public CpuEvent(long sequence,long revision,long batch,long thread,Stage stage,long startNanos,long inclusiveNanos,long selfNanos,long items,long tasks,long parent,long flags,
                long scopeId,long parentScopeId,long inputGeneration,long outputGeneration,long threadCpuNanos,long threadCpuSelfNanos){
            this(sequence,revision,batch,thread,stage,startNanos,inclusiveNanos,selfNanos,items,tasks,parent,flags,scopeId,parentScopeId,
                inputGeneration,outputGeneration,threadCpuNanos,threadCpuSelfNanos,0,0,0,0,0,-1,0);
        }
        public CpuEvent(long sequence,long revision,long batch,long thread,Stage stage,long startNanos,long inclusiveNanos,long selfNanos,long items,long tasks,long parent,long flags,
                long scopeId,long parentScopeId,long inputGeneration,long outputGeneration){
            this(sequence,revision,batch,thread,stage,startNanos,inclusiveNanos,selfNanos,items,tasks,parent,flags,scopeId,parentScopeId,inputGeneration,outputGeneration,-1,-1);
        }
        public CpuEvent(long sequence,long revision,long batch,long thread,Stage stage,long startNanos,
                long inclusiveNanos,long selfNanos,long items,long tasks,long parent,long flags) {
            this(sequence,revision,batch,thread,stage,startNanos,inclusiveNanos,selfNanos,items,tasks,parent,flags,0,0,0,0,-1,-1);
        }
        public boolean active(){return (flags&FLAG_ACTIVE)!=0;}
        public boolean truncated(){return (flags&FLAG_TRUNCATED)!=0;}
        public boolean failed(){return (flags&FLAG_FAILED)!=0;}
        public boolean completed(){return !active()&&!truncated();}
    }
    /** Percentiles describe completed samples in bounded retained windows. Truncated timing is excluded. */
    public record Distribution(int samples,long p50Nanos,long p95Nanos,long p99Nanos,long maximumNanos,
            long over50Millis,long over100Millis,long over1Second) { }
    public record SubmissionSample(long sequence,long revision,long inclusiveNanos,long flags,
            SubmissionKind kind,long scopeId) {
        public boolean truncated(){return (flags&FLAG_TRUNCATED)!=0;}
        public boolean failed(){return (flags&FLAG_FAILED)!=0;}
        public boolean cancelled(){return (flags&FLAG_CANCELLED)!=0;}
        public boolean enteredNativeFrame(){return (flags&FLAG_ENTERED_NATIVE_FRAME)!=0;}
    }
    public record Snapshot(long overwrittenEvents,List<CpuEvent> events,Distribution frames,
            long slowFrameTriggers,long overwrittenSlowFrameEvents,List<CpuEvent> lastSlowFrame,
            List<CpuEvent> activeScopes,long droppedActiveScopes,List<SubmissionSample> submissionSamples,
            long overwrittenSubmissionSamples,long generation,Optional<Level> recordedLevel,
            long firstEventOrdinal,long nextEventOrdinal,long registrationOrdinal,long missedSinceRegistration) {
        public Snapshot {
            events=List.copyOf(events);lastSlowFrame=List.copyOf(lastSlowFrame);activeScopes=List.copyOf(activeScopes);
            submissionSamples=List.copyOf(submissionSamples);recordedLevel=java.util.Objects.requireNonNull(recordedLevel);
        }
        public Snapshot(long overwrittenEvents,List<CpuEvent> events,Distribution frames,long slowFrameTriggers,long overwrittenSlowFrameEvents,List<CpuEvent> lastSlowFrame,
                List<CpuEvent> activeScopes,long droppedActiveScopes,List<SubmissionSample> submissionSamples,long overwrittenSubmissionSamples,long generation,Optional<Level> recordedLevel){
            this(overwrittenEvents,events,frames,slowFrameTriggers,overwrittenSlowFrameEvents,lastSlowFrame,activeScopes,droppedActiveScopes,
                submissionSamples,overwrittenSubmissionSamples,generation,recordedLevel,0,0,0,0);
        }
        Snapshot observed(long registration,long missed){
            return new Snapshot(overwrittenEvents,events,frames,slowFrameTriggers,overwrittenSlowFrameEvents,lastSlowFrame,activeScopes,droppedActiveScopes,
                submissionSamples,overwrittenSubmissionSamples,generation,recordedLevel,firstEventOrdinal,nextEventOrdinal,registration,missed);
        }
        public Snapshot(long overwrittenEvents,List<CpuEvent> events,Distribution frames,
                long slowFrameTriggers,long overwrittenSlowFrameEvents,List<CpuEvent> lastSlowFrame) {
            this(overwrittenEvents,events,frames,slowFrameTriggers,overwrittenSlowFrameEvents,lastSlowFrame,List.of(),0,List.of(),0,0,Optional.empty());
        }
        /** Complete submit wall time, including scene preparation before native FRAME. */
        public Distribution submissions(){
            return distribution(submissionSamples.isEmpty()
                ?events.stream().filter(e->e.stage()==Stage.SUBMIT&&e.completed()).mapToLong(CpuEvent::inclusiveNanos).sorted().toArray()
                :submissionSamples.stream().filter(s->!s.truncated()).mapToLong(SubmissionSample::inclusiveNanos).sorted().toArray());
        }
        /** Independent bounded submit retention avoids losing every submit to detailed worker events. */
        public Distribution submissions(SubmissionKind kind){
            java.util.Objects.requireNonNull(kind);
            return distribution(submissionSamples.stream().filter(s->s.kind()==kind&&!s.truncated()).mapToLong(SubmissionSample::inclusiveNanos).sorted().toArray());
        }
        public Map<SubmissionKind,Distribution> submissionsByKind(){
            var result=new EnumMap<SubmissionKind,Distribution>(SubmissionKind.class);
            for(var kind:SubmissionKind.values())result.put(kind,submissions(kind));
            return Collections.unmodifiableMap(result);
        }
        public long failedSubmissions(){return submissionSamples.stream().filter(SubmissionSample::failed).count();}
        public long cancelledSubmissions(){return submissionSamples.stream().filter(SubmissionSample::cancelled).count();}
        public long truncatedSubmissions(){return submissionSamples.stream().filter(SubmissionSample::truncated).count();}
        public long submissionsWithoutNativeFrame(){return submissionSamples.stream().filter(s->!s.enteredNativeFrame()).count();}
        /** Completed stage wall times in the rolling event window. Concurrent worker durations are not added. */
        public Distribution stage(Stage stage){
            java.util.Objects.requireNonNull(stage);
            return distribution(events.stream().filter(e->e.stage()==stage&&e.completed()).mapToLong(CpuEvent::inclusiveNanos).sorted().toArray());
        }
    }
    public record LightUpdates(long residentLights,long patches,long upserts,long removals,long createdObjects,
            long reusedObjects,long retiredObjects,long visitedTreeNodes,long treeRebuilds,long uploadedLightRecords,
            long uploadedTreeNodes,long uploadedBytes,long capacityGrowths,long fullCommits,long graphRefreshes,long lastPatchNanos){ }
    static LightUpdates decodeLights(long[] n){if(n.length!=16)throw new IllegalStateException("Incompatible light telemetry");return new LightUpdates(n[0],n[1],n[2],n[3],n[4],n[5],n[6],n[7],n[8],n[9],n[10],n[11],n[12],n[13],n[14],n[15]);}
    private static Distribution distribution(long[] values){
        long fifty=0,hundred=0,second=0;for(long n:values){if(n>50_000_000)fifty++;if(n>100_000_000)hundred++;if(n>1_000_000_000)second++;}
        return new Distribution(values.length,percentile(values,.50),percentile(values,.95),percentile(values,.99),values.length==0?0:values[values.length-1],fifty,hundred,second);
    }
    /** A command-list timestamp range; parent IDs are local to nativeFrame and queue.
     * nativeFrame is the profiler's active-frame ordinal (paused while OFF);
     * sequence/revision are the renderer identity. Timestamps include queue scheduling
     * and preemption and do not claim exclusive shader busy time. */
    public record GpuScope(long nativeFrame,String queue,int event,int parent,String name,double inclusiveMillis,
            long beginTick,long endTick,long frequency,long sequence,long revision,int diagnosticLevel,
            long resourceIdentity,long resourceGeneration,long reason) {
        public GpuScope(long nativeFrame,String queue,int event,int parent,String name,double inclusiveMillis,
                long beginTick,long endTick,long frequency,long sequence,long revision){
            this(nativeFrame,queue,event,parent,name,inclusiveMillis,beginTick,endTick,frequency,sequence,revision,-1,0,0,0);
        }
    }
    public record GpuSnapshot(long droppedQueries,long overwrittenScopes,long pendingQueries,int sampleInterval,
            boolean enabled,List<GpuScope> scopes) {
        public GpuSnapshot { scopes=List.copyOf(scopes); }
    }
    static GpuSnapshot decodeGpu(String[] values){
        var status=values[0].split(",");var scopes=new ArrayList<GpuScope>(values.length-1);
        for(int i=1;i<values.length;i++){
            var v=values[i].strip().split(",");if(v.length!=11&&v.length!=15)throw new IllegalStateException("Incompatible GPU scope record");
            int mode=v.length==15?Integer.parseInt(v[11]):-1;
            if(mode< -1||mode>2)throw new IllegalStateException("Invalid GPU diagnostic level");
            scopes.add(new GpuScope(Long.parseLong(v[0]),v[1],Integer.parseInt(v[2]),Integer.parseInt(v[3]),v[4],Double.parseDouble(v[5]),Long.parseLong(v[6]),Long.parseLong(v[7]),Long.parseLong(v[8]),Long.parseLong(v[9]),Long.parseLong(v[10]),
                mode,v.length==15?Long.parseUnsignedLong(v[12]):0,v.length==15?Long.parseLong(v[13]):0,v.length==15?Long.parseLong(v[14]):0));
        }
        return new GpuSnapshot(Long.parseLong(status[0]),Long.parseLong(status[1]),Long.parseLong(status[2]),Integer.parseInt(status[3]),status[4].equals("1"),scopes);
    }
    static Snapshot decode(long[] values) {
        if(values==null||values.length<7||(values[0]!=2&&values[0]!=3&&values[0]!=4&&values[0]!=5))
            throw new IllegalStateException("Incompatible native diagnostic snapshot");
        boolean current=values[0]>=3;int header=values[0]>=5?15:current?13:7,width=values[0]>=5?25:values[0]==4?18:current?16:12;
        if(values.length<header||values[1]!=width||values[2]<0||values[3]<0||values[3]>8192||values[4]<0
                ||values[5]<0||values[5]>2048||values[6]<0)
            throw new IllegalStateException("Incompatible native diagnostic snapshot");
        long activeCount=current?values[7]:0,submitCount=current?values[9]:0;
        if(activeCount<0||activeCount>512||submitCount<0||submitCount>1024
                ||current&&(values[8]<0||values[10]<0||values[11]<=0||values[12]<0||values[12]>=Level.values().length)
                ||values.length!=header+width*(values[3]+values[5]+activeCount)+6*submitCount)
            throw new IllegalStateException("Incompatible native diagnostic snapshot");
        if(values[0]>=5&&(values[13]<1||values[14]<values[13]||values[14]-values[13]!=values[3]))
            throw new IllegalStateException("Invalid diagnostic ordinal window");
        var events=new ArrayList<CpuEvent>((int)values[3]);
        var slow=new ArrayList<CpuEvent>((int)values[5]);var active=new ArrayList<CpuEvent>((int)activeCount);
        int offset=header;
        for(int i=0;i<values[3];i++,offset+=width)events.add(decodeEvent(values,offset,width));
        for(int i=0;i<values[5];i++,offset+=width)slow.add(decodeEvent(values,offset,width));
        for(int i=0;i<activeCount;i++,offset+=width){
            var event=decodeEvent(values,offset,width);
            if(!event.active())throw new IllegalStateException("Active diagnostic scope missing its incomplete flag");
            active.add(event);
        }
        var samples=new ArrayList<SubmissionSample>((int)submitCount);var kinds=SubmissionKind.values();
        for(int i=0;i<submitCount;i++,offset+=6){
            if(values[offset+2]<0||values[offset+4]<0||values[offset+4]>=kinds.length)
                throw new IllegalStateException("Incompatible native submit sample");
            samples.add(new SubmissionSample(values[offset],values[offset+1],values[offset+2],values[offset+3],kinds[(int)values[offset+4]],values[offset+5]));
        }
        var frames=distribution(events.stream().filter(e->e.stage()==Stage.FRAME&&e.completed()).mapToLong(CpuEvent::inclusiveNanos).sorted().toArray());
        return new Snapshot(values[2],events,frames,values[4],values[6],slow,active,current?values[8]:0,samples,
            current?values[10]:0,current?values[11]:0,current?Optional.of(Level.values()[(int)values[12]]):Optional.empty(),
            values[0]>=5?values[13]:0,values[0]>=5?values[14]:0,0,0);
    }
    private static CpuEvent decodeEvent(long[] values,int i,int width){
        long stage=values[i+4];var stages=Stage.values();
        Stage name=stage>=0&&stage<stages.length?stages[(int)stage]:Stage.UNKNOWN;
        if(values[i+6]<0||values[i+7]<0||values[i+7]>values[i+6])throw new IllegalStateException("Invalid diagnostic duration");
        if(width>=25&&(values[i+19]<0||values[i+20]<0||values[i+21]<0||values[i+22]<0||values[i+23]<0||values[i+23]>2||values[i+24]<0))
            throw new IllegalStateException("Invalid diagnostic resource metadata");
        return new CpuEvent(values[i],values[i+1],values[i+2],values[i+3],name,values[i+5],values[i+6],values[i+7],values[i+8],values[i+9],values[i+10],values[i+11],
            width>=16?values[i+12]:0,width>=16?values[i+13]:0,width>=16?values[i+14]:0,width>=16?values[i+15]:0,width>=18?values[i+16]:-1,width>=18?values[i+17]:-1,
            width>=25?values[i+18]:0,width>=25?values[i+19]:0,width>=25?values[i+20]:0,width>=25?values[i+21]:0,width>=25?values[i+22]:0,width>=25?(int)values[i+23]:-1,width>=25?values[i+24]:0);
    }
    static Monitor monitor(long token){
        var initial=decode(RtxptNative.diagnosticMonitorSnapshot(token));
        return new Monitor(){
            private final long registration=initial.nextEventOrdinal();
            private long cursor=registration,missed;
            @Override public synchronized Snapshot snapshot(){
                var value=decode(RtxptNative.diagnosticMonitorSnapshot(token));
                missed+=Math.max(0,value.firstEventOrdinal()-cursor);cursor=value.nextEventOrdinal();
                return value.observed(registration,missed);
            }
        };
    }
    static ProtocolMonitor protocolMonitor(long token){
        var initial=decodeProtocol(RtxptNative.protocolMonitorSnapshot(token));
        return new ProtocolMonitor(){
            private final long registration=initial.nextOrdinal();
            private long cursor=registration,missed;
            @Override public synchronized ProtocolSnapshot snapshot(){
                var value=decodeProtocol(RtxptNative.protocolMonitorSnapshot(token));
                missed+=Math.max(0,value.firstOrdinal()-cursor);cursor=value.nextOrdinal();
                return new ProtocolSnapshot(value.token(),value.overwritten(),value.events(),value.firstOrdinal(),value.nextOrdinal(),registration,missed);
            }
        };
    }
    private static long percentile(long[] values,double percentile){return values.length==0?0:values[Math.max(0,(int)Math.ceil(values.length*percentile)-1)];}
}
