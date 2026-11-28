package top.ceroxe.rt.renderer.rtxpt;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLongArray;

/** Measurements of this adapter's own calls. RTXPT internals are not expanded. */
public final class RtxptDiagnostics {
    private RtxptDiagnostics() { }
    /** Amount of adapter diagnostic data recorded. */
    public enum Level {
        /** Disable diagnostic event recording. */ OFF,
        /** Record summary events for adapter operations. */ SUMMARY,
        /** Record detailed adapter boundary events. */ DEEP
    }
    /** Adapter operation stage associated with a diagnostic event. */
    public enum Stage {
        /** Input and state validation. */ VALIDATION,
        /** Conversion of consumer data for the renderer. */ TRANSLATION,
        /** Native resource preparation and transfer. */ RESOURCES,
        /** Lighting data preparation and transfer. */ LIGHTING,
        /** Rendering attribute preparation and transfer. */ ATTRIBUTES,
        /** RTXPT frame submission, including native waits. */ RTXPT_FRAME
    }
    /**
     * Measurement of one adapter operation. Frame duration is wall time,
     * including native waits, and is not GPU busy time.
     * @param ordinal monotonically increasing identity within the recorder
     * @param sequence submitted frame or operation sequence
     * @param sceneRevision scene revision associated with the operation
     * @param stage adapter stage measured
     * @param beginNanos start time from the monotonic system clock
     * @param durationNanos elapsed wall time
     * @param items number of items processed, when applicable
     * @param bytes number of bytes processed, when applicable
     * @param failed whether the operation failed
     */
    public record Event(long ordinal, long sequence, long sceneRevision, Stage stage,
            long beginNanos, long durationNanos, long items, long bytes, boolean failed) { }
    /**
     * Immutable result of polling adapter diagnostics.
     * @param level recording level used for these events
     * @param nextOrdinal identity to use as the next cursor boundary
     * @param lostEvents events overwritten or missed since the previous cursor
     * @param events immutable events returned by this poll
     * @param upstreamInternalsExpanded whether upstream internal probes are included
     */
    public record Snapshot(Level level, long nextOrdinal, long lostEvents,
            List<Event> events, boolean upstreamInternalsExpanded) {
        /** Creates an immutable diagnostics snapshot. */
        public Snapshot { events = List.copyOf(events); }
    }

    /** A reader owns an independent cursor and never calls JNI or waits for the native owner. */
    public interface Monitor {
        /**
         * Returns events since this reader's previous poll.
         * @return events since this reader's previous poll
         */
        Snapshot poll();
        /**
         * Reports whether the associated renderer has closed its recorder.
         * @return whether the associated renderer has closed its recorder
         */
        boolean closed();
    }

    /**
     * One event measured at the external native adapter boundary.
     * @param ordinal event identity
     * @param sequence submitted frame or operation sequence
     * @param sceneRevision associated scene revision
     * @param thread native thread identity
     * @param stage native adapter stage name
     * @param beginNanos monotonic start time
     * @param inclusiveNanos elapsed time including child scopes
     * @param selfNanos elapsed time excluding child scopes
     * @param threadCpuNanos thread CPU time including child scopes
     * @param threadCpuSelfNanos thread CPU time excluding child scopes
     * @param scopeId native scope identity
     * @param parentScopeId parent scope identity
     * @param items number of items processed, when applicable
     * @param bytes number of bytes processed, when applicable
     * @param flags native event flags
     * @param tasks number of tasks associated with the event
     */
    public record NativeEvent(long ordinal,long sequence,long sceneRevision,long thread,String stage,
            long beginNanos,long inclusiveNanos,long selfNanos,long threadCpuNanos,long threadCpuSelfNanos,
            long scopeId,long parentScopeId,long items,long bytes,long flags,long tasks) {
        /**
         * Creates an event without a task count; the task count defaults to zero.
         * @param ordinal event identity
         * @param sequence submitted frame or operation sequence
         * @param sceneRevision associated scene revision
         * @param thread native thread identity
         * @param stage native adapter stage name
         * @param beginNanos monotonic start time
         * @param inclusiveNanos elapsed time including child scopes
         * @param selfNanos elapsed time excluding child scopes
         * @param threadCpuNanos thread CPU time including child scopes
         * @param threadCpuSelfNanos thread CPU time excluding child scopes
         * @param scopeId native scope identity
         * @param parentScopeId parent scope identity
         * @param items number of items processed, when applicable
         * @param bytes number of bytes processed, when applicable
         * @param flags native event flags
         */
        public NativeEvent(long ordinal,long sequence,long sceneRevision,long thread,String stage,
                long beginNanos,long inclusiveNanos,long selfNanos,long threadCpuNanos,long threadCpuSelfNanos,
                long scopeId,long parentScopeId,long items,long bytes,long flags){
            this(ordinal,sequence,sceneRevision,thread,stage,beginNanos,inclusiveNanos,selfNanos,threadCpuNanos,
                    threadCpuSelfNanos,scopeId,parentScopeId,items,bytes,flags,0);
        }
    }
    /**
     * Immutable poll result from the native adapter-boundary diagnostics.
     * @param generation native recorder generation
     * @param nextOrdinal identity to use as the next cursor boundary
     * @param lostEvents events overwritten or missed since the previous cursor
     * @param events immutable native events returned by this poll
     */
    public record NativeSnapshot(long generation,long nextOrdinal,long lostEvents,List<NativeEvent> events){
        /** Creates a snapshot with an immutable event list. */
        public NativeSnapshot {events=List.copyOf(events);}
    }
    /** Reader for native adapter-boundary events. */
    public interface NativeMonitor {
        /**
         * Returns native events since this reader's previous poll.
         * @return events since this reader's previous poll
         */
        NativeSnapshot poll();
    }
    private static final String[] NATIVE_STAGES={"UNKNOWN","Frame","InstanceCache","SubInstances","Dispatch","Caller","Worker","TaskWait","MessagePump","ShaderCache","ShaderCompile","ShaderLibrary","Pipeline","ShaderTable","Import","Textures","Materials","Acceleration","Retirement","SharedPublish","SharedReuseWait","Resize","PoolWait","Lighting","Preparation","Upload","Submit","LightValidation","LightObjects","LightGraph","LightTree","LightUpload","LightingPolicy","SubmitValidation","Translation","Resources","SubmitRetirement","AttributePreparation","AttributeValidation","ResourceStaging","ResourceIndex","ResourceRetirement","ResourcePlan","BufferAllocate","DescriptorAllocate","PayloadPack","BufferUpload","BlasAllocate","BlasClone","BlasBuild","BlasRefit","CommandAcquire","CommandSubmit","CompletionPoll","ReclaimEnqueue","DriverRelease","IndexRebuild","TextureAllocate","TextureUpload","RuntimeInitialize","DeviceInitialize","SceneInitialize","LightingInitialize","PoolAcquire","TlasAllocate","HeapAllocate","ResourcePrebuild","AllocationInfo","PlacedBind","PoolEvict","StagingAcquire","StagingAllocate","StagingMap","StagingCopy","StagingBarrier","StagingRecord","SparseRegions","SceneGraphRefresh","SceneFullRefresh","SceneTopologyFallback","SceneMeshPreparation","SceneMaterialRows","SceneGeometryRows","SceneInstanceRows","SceneTableUpload","SparseQueryPreparation","SparseRegionInitialize","SparseHashErase","SparseHashRecover","SparseRegionPublish","SparseUpload","DefaultBufferAllocate","DefaultBufferCommitted","DefaultBufferPostCreate","SceneCpuResize","SceneMaterialPrepare","SceneOmmDebugRefresh","DiagnosticMutexWait","DiagnosticMutexHold","TlasBuild","MeshBufferUpload","ImportDefinitions","ImportMeshObjects","ImportAttributes","ImportReceivers","ImportNormals","ImportOwnership","ImportSurfaces","ImportNodeRemovals","ImportTransforms","ImportFinalRemovals"};
    static NativeSnapshot nativeSnapshot(long handle,long generation,long next){
        long[] p=RtxptNative.boundaryDiagnostics(handle,generation,next);
        if(p.length<21||p[0]!=7||p[1]!=27||p[5]!=0||p[9]!=0||p[15]!=0
            ||p.length!=21+(p[3]+p[7])*27)throw new IllegalStateException("Invalid boundary diagnostic packet");
        var events=new ArrayList<NativeEvent>((int)(p[3]+p[7]));
        for(int i=21;i<p.length;i+=27){
            int stage=(int)p[i+4];
            events.add(new NativeEvent(p[i+24],p[i],p[i+1],p[i+3],stage>=0&&stage<NATIVE_STAGES.length?NATIVE_STAGES[stage]:"UNKNOWN",
                p[i+5],p[i+6],p[i+7],p[i+16],p[i+17],p[i+12],p[i+13],p[i+8],p[i+21],p[i+11],p[i+9]));
        }
        return new NativeSnapshot(p[11],p[14],Math.max(0,p[13]-next),events);
    }
    static NativeMonitor nativeMonitor(long handle){
        var initial=nativeSnapshot(handle,0,1);
        return new NativeMonitor(){
            private final long generation=initial.generation();
            private long next=initial.nextOrdinal();
            @Override public synchronized NativeSnapshot poll(){
                var value=nativeSnapshot(handle,generation,next);next=value.nextOrdinal();return value;
            }
        };
    }

    /** Single-writer bounded primitive storage, with lock-free concurrent readers. */
    static final class Recorder {
        private static final int CAPACITY = 1024, STRIDE = 9;
        private final long[] data = new long[CAPACITY * STRIDE];
        private static final Stage[] STAGES=Stage.values();
        private final AtomicLongArray stamps=new AtomicLongArray(CAPACITY);
        private volatile Level level = Level.SUMMARY;
        private volatile long next;
        private volatile boolean closed;
        Recorder(){for(int i=0;i<CAPACITY;i++)stamps.set(i,Long.MIN_VALUE);}
        void close(){closed=true;}
        Monitor monitor(){
            return new Monitor(){
                private long after=next-1;
                @Override public boolean closed(){return closed;}
                @Override public synchronized Snapshot poll(){
                    var value=snapshot(after);after=value.nextOrdinal()-1;return value;
                }
            };
        }
        void level(Level value) { level = java.util.Objects.requireNonNull(value); }
        Level level() { return level; }
        long clock() { return level == Level.OFF ? 0 : System.nanoTime(); }
        void add(long sequence, long revision, Stage stage, long begin, long items, long bytes, boolean failed) {
            if (begin == 0 || level == Level.OFF || closed) return;
            int slot=(int)(next%CAPACITY),offset=slot*STRIDE;
            // Full volatile store prevents following payload writes from being
            // reordered ahead of slot invalidation on a wrapping writer.
            stamps.set(slot,-next-2);
            data[offset] = next;
            data[offset + 1] = sequence;
            data[offset + 2] = revision;
            data[offset + 3] = stage.ordinal();
            data[offset + 4] = begin;
            data[offset + 5] = Math.max(0, System.nanoTime() - begin);
            data[offset + 6] = items;
            data[offset + 7] = bytes;
            data[offset + 8] = failed ? 1 : 0;
            stamps.set(slot,next);
            next++;
        }
        Snapshot snapshot(long afterOrdinal) {
            if (afterOrdinal < -1 || afterOrdinal >= next && afterOrdinal != next - 1)
                throw new IllegalArgumentException("Invalid diagnostic cursor");
            long end=next,wanted=afterOrdinal+1,oldest=Math.max(0,end-CAPACITY);
            long first=Math.max(wanted,oldest),lost=Math.max(0,oldest-wanted);
            var events=new ArrayList<Event>((int)(end-first));
            for(long ordinal=first;ordinal<end;ordinal++){
                int slot=(int)(ordinal%CAPACITY),offset=slot*STRIDE;
                if(stamps.get(slot)!=ordinal){lost++;continue;}
                long sequence=data[offset+1],revision=data[offset+2],stage=data[offset+3],begin=data[offset+4],duration=data[offset+5],items=data[offset+6],bytes=data[offset+7],failed=data[offset+8];
                java.lang.invoke.VarHandle.acquireFence();
                if(stamps.get(slot)!=ordinal){lost++;continue;}
                events.add(new Event(ordinal,sequence,revision,STAGES[(int)stage],begin,duration,items,bytes,failed!=0));
            }
            return new Snapshot(level,end,lost,events,false);

        }
    }
}
