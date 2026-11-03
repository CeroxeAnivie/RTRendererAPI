package top.ceroxe.rt.renderer.rtxpt;

import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptJson.object;

/** E12 fixed-workload evidence. No legacy speed claim is made by this producer. */
public final class RtxptNativeLightingCostBenchmark {
    private static final float[][] POSITIONS={
        {-4,0,4, 4,0,4, 4,0,-4, -4,0,-4},
        {-4,0,-4, 4,0,-4, 4,4,-4, -4,4,-4},
        {-4,0,4, -4,0,-4, -4,4,-4, -4,4,4},
        {-.5f,0,0, .5f,0,0, .5f,2,0, -.5f,2,0}
    };
    private static final int[] INDICES={0,1,2,0,2,3};
    private static final int[] COLORS={0xffa0a0a0,0xff80b0e0,0xff80d090,0xffc08060};
    private static final float[] BASELINE={.25f,.75f,.25f,.75f,.25f,.75f,.25f,.75f};
    private static final float[] UV={0,0,1,0,1,1,0,1};
    private static final int VIEW_WIDTH=2560,VIEW_HEIGHT=1600;
    private static final NativeLightingPlan.Directional SUN=new NativeLightingPlan.Directional(0,1,0,.8f,1,1,1);
    private static final Map<String,RtxptDiagnostics.GpuScope> GPU=new LinkedHashMap<>();
    private static long adapterLuid;
    private record Sample(long sequence,long submitNanos,long admissionNanos,long releaseNanos,int deferrals) { }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static List<SparseLightSource> sources(int count){
        var result=new ArrayList<SparseLightSource>();
        for(int i=0;i<count;i++)result.add(SparseLightSource.point(i+1,(i%8-3.5)*.7,1+(i%3)*.5,(i/8%8-3.5)*.7,
            .25f+(i%3)*.25f,.7f,.3f,1,6).withLayer(SparseLightSource.Layer.DYNAMIC)
            .withComposition(SparseLightSource.Composition.VISIBLE_GROUP_CHANNEL_MAX,0)
            .withAttenuation(SparseLightSource.Attenuation.MANHATTAN_LINEAR));
        return result;
    }
    public static SceneTransaction scene(){
        var tx=SceneTransaction.builder(1).resetScene();
        for(int i=0;i<POSITIONS.length;i++)tx
            .upsert(MaterialAsset.builder(i+1).baseColorRgba8(COLORS[i]).roughness(.8f).build())
            .upsert(MeshAsset.builder(i+1,POSITIONS[i],INDICES,new long[]{i+1,i+1}).textureCoordinates(UV).lightmapCoordinates(BASELINE).build())
            .upsert(SceneInstance.builder(i+1,i+1).build());
        return tx.build();
    }
    public static RenderFrameRequest frame(long sequence){
        return RenderFrameRequest.builder(sequence,VIEW_WIDTH,VIEW_HEIGHT,
            CameraState.lookAt(0,2,6,0,1,0).verticalFieldOfViewDegrees(52).aspectRatio((double)VIEW_WIDTH/VIEW_HEIGHT).build())
            .minimumSceneRevision(1).clock(FrameClock.of(0,0)).environment(EnvironmentState.neutral()).build();
    }
    private static Map<String,Object> fixture(int width,int height,int sourceCount){
        var meshes=new ArrayList<Object>();
        for(int i=0;i<POSITIONS.length;i++)meshes.add(object("id",i+1,"positions",POSITIONS[i],"indices",INDICES,
            "materialId",i+1,"baseColorRgba8",Integer.toUnsignedLong(COLORS[i]),"roughness",.8f,"uv",UV,
            "baseline",BASELINE,"transform","identity","roles",23,"visibility",1,"fog",0));
        var lights=new ArrayList<Object>();
        for(var s:sources(sourceCount))lights.add(object("id",s.id(),"xyz",new double[]{s.x(),s.y(),s.z()},
            "rgb",new float[]{s.red(),s.green(),s.blue()},"intensity",s.intensity(),"range",s.range(),"shadow",true,
            "shape",s.shape().name(),"composition",s.composition().name(),"group",s.group(),"layer",s.layer().name(),"attenuation",s.attenuation().name()));
        return object("format","rtr-e12-fixture","formatVersion",1,"algorithm","rtr-style-dynamic-only-v1",
            "grid",new int[]{width,height},"viewport",new int[]{VIEW_WIDTH,VIEW_HEIGHT},"signals",List.of("DIRECT_GAIN","INDIRECT_DIFFUSE"),
            "formatEncoding","RGBA16_FLOAT; gain direct; GI linear contribution with receiver albedo once",
            "giSamples",2,"giDistance",6,"giStrength",.35f,"samplePattern","r=sqrt((i+.5)/N); angle=(i+.5)*2.39996323; cosine hemisphere; tangent=cross(abs(n.y)<.999?Y:X,n)",
            "style",NativeLightingStyle.defaults().toString(),"sun",SUN.toString(),"moon",null,"opaqueFastPath",true,"rayEpsilon",.003f,
            "camera",object("eye",new int[]{0,2,6},"target",new int[]{0,1,0},"verticalFovDegrees",52,"jitter",0,"worldOrigin",List.of(0,0,0)),
            "clock",List.of(0,0),"meshes",meshes,"sources",lights,"scenario","static-live-dispatch-no-result-reuse",
            "reconstruction","NONE","consumer","unimported-packet-abandonment; no GL consumer measured");
    }
    /** Shared portable workload contract for the independently executed Vulkan adapter. */
    public static String fixtureJson(int width,int height,int sourceCount){return RtxptJson.encode(fixture(width,height,sourceCount));}
    /** Exact float parameter publication used by both backend shader implementations. */
    public static float[] parameters(int width,int height){return RtxptNativeLightingParameters.pack(new NativeLightingPlan(width,height,
        Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.INDIRECT_DIFFUSE),2,6,.35f,NativeLightingStyle.defaults(),SUN,null,true),frame(0));}
    public static String json(Object value){return RtxptJson.encode(value);}
    private static Sample submit(RtxptRenderer r,long sequence)throws Exception {
        var request=frame(sequence);long start=System.nanoTime(),submitNanos=0;int deferred=0;
        long deadline=start+30_000_000_000L;
        while(true){
            long before=System.nanoTime();var result=r.trySubmit(request);submitNanos+=System.nanoTime()-before;
            if(result instanceof Renderer.FrameSubmitted)break;
            if(System.nanoTime()>deadline)throw new AssertionError("Lighting packet admission timed out");
            deferred++;Thread.sleep(1);
        }
        long admissionNanos=System.nanoTime()-start-submitNanos,releaseStart=System.nanoTime();
        try(var lease=r.acquireLightingFrame().orElseThrow()){
            var packet=lease.frame();adapterLuid=packet.adapterLuid();
            check(packet.sequence()==sequence&&packet.sceneRevision()==1,"Fresh packet identity");
            check(packet.planes().size()==2,"Only the fixed direct/GI output set is allocated");
        }
        return new Sample(sequence,submitNanos,admissionNanos,System.nanoTime()-releaseStart,deferred);
    }
    private static void collect(RtxptRenderer r){
        for(var s:r.diagnosticGpuSnapshot().scopes())GPU.put(s.nativeFrame()+":"+s.queue()+":"+s.event(),s);
    }
    private static Map<String,Object> distribution(double[] values){
        Arrays.sort(values);return object("samples",values.length,"p50Ms",percentile(values,.5),"p95Ms",percentile(values,.95),
            "p99Ms",percentile(values,.99),"maxMs",values.length==0?null:values[values.length-1]);
    }
    private static Double percentile(double[] values,double p){return values.length==0?null:values[Math.max(0,(int)Math.ceil(values.length*p)-1)];}
    public static void main(String[] args)throws Exception {
        Path out=Path.of(args.length>0?args[0]:"build/reports/rtxpt-e12").toAbsolutePath();
        int width=args.length>1?Integer.parseInt(args[1]):1280,height=args.length>2?Integer.parseInt(args[2]):800;
        int measured=args.length>3?Integer.parseInt(args[3]):120,sourceCount=args.length>4?Integer.parseInt(args[4]):64;
        check(measured>=16&&sourceCount>=0&&sourceCount<=100000,"Use >=16 frames and 0..100000 sources");Files.createDirectories(out);
        String fixture=RtxptJson.encode(fixture(width,height,sourceCount));Files.writeString(out.resolve("fixture.json"),fixture,StandardCharsets.UTF_8);
        String workload=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fixture.getBytes(StandardCharsets.UTF_8)));
        if(args.length>5&&args[5].equals("--fixture-only")){System.out.println("RTXPT_E12_FIXTURE PASS workload="+workload);return;}
        var config=RtxptRenderers.nativeLightingConfiguration().copyBuilder().gpuTimingsEnabled(true)
            .frameGeneration(FrameGenerationOptions.disabled()).lowLatency(LowLatencyOptions.disabled()).build();
        var samples=new ArrayList<Sample>();var stages=new ArrayList<Object>();long sequence=0,firstMeasured,lastMeasured;
        NativeLightingControl.WorkStatistics work;NativeLightingControl.Execution execution;
        RtxptDiagnostics.GpuSnapshot gpuStatus;long preparationsNanos;
        try(var r=new RtxptRenderer(config,true)){
            r.nativeLightingPlan(new NativeLightingPlan(width,height,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.INDIRECT_DIFFUSE),
                2,6,.35f,NativeLightingStyle.defaults(),SUN,null,true));
            boolean decomposed=args.length>5&&args[5].equals("--decompose");
            if(decomposed)r.lightingDiagnosticDecompositionEnabled(true);
            r.diagnosticLevel(RtxptDiagnostics.Level.DEEP);long prepareStart=System.nanoTime();
            r.apply(scene(),sources(sourceCount),LongBuffer.allocate(0));submit(r,sequence++);r.awaitIdle();
            preparationsNanos=System.nanoTime()-prepareStart;
            for(int i=0;i<24;i++)submit(r,sequence++);r.awaitIdle();collect(r);
            // Counter evidence is a separate untimed dispatch; timed passes contain no per-ray atomics.
            r.lightingWorkStatisticsEnabled(true);submit(r,sequence++);r.awaitIdle();submit(r,sequence++);r.awaitIdle();
            work=r.lightingWorkStatistics();r.lightingWorkStatisticsEnabled(false);
            check(work.available()&&work.primaryRays()==(long)width*height&&work.receivers()>0&&work.bounceRays()==work.receivers()*2,"Real fixed-sample ray workload: "+work);
            firstMeasured=sequence;
            for(int i=0;i<measured;i++){samples.add(submit(r,sequence++));if(i%16==15)collect(r);}
            lastMeasured=sequence-1;r.awaitIdle();collect(r);
            execution=r.nativeLightingExecution();check(execution.fullBeautyFrames()==0,"Native plan does not execute beauty");
            check(execution.lightingFrames()>=sequence,"New live dispatches are not cached lighting frames");
            r.diagnosticLevel(RtxptDiagnostics.Level.OFF);
            for(int i=0;i<3;i++){submit(r,sequence++);r.awaitIdle();collect(r);}
            gpuStatus=r.diagnosticGpuSnapshot();
            r.captureNativeLighting(out.resolve("direct.pfm"),0);r.captureNativeLighting(out.resolve("indirect.pfm"),1);
        }
        var gpuCsv=new StringBuilder("sequence,revision,native_frame,queue,event,parent,stage,inclusive_ms,begin_tick,end_tick,frequency\n");
        var gpuGroups=new TreeMap<String,List<Double>>();
        for(var s:GPU.values())if(s.sequence()>=firstMeasured&&s.sequence()<=lastMeasured){
            check(Double.isFinite(s.inclusiveMillis())&&s.inclusiveMillis()>=0,"Finite GPU queue interval");
            gpuCsv.append(String.format(Locale.ROOT,"%d,%d,%d,%s,%d,%d,%s,%.9f,%d,%d,%d%n",s.sequence(),s.revision(),s.nativeFrame(),s.queue(),s.event(),s.parent(),s.name(),s.inclusiveMillis(),s.beginTick(),s.endTick(),s.frequency()));
            gpuGroups.computeIfAbsent(s.name(),k->new ArrayList<>()).add(s.inclusiveMillis());
        }
        Files.writeString(out.resolve("gpu.csv"),gpuCsv,StandardCharsets.UTF_8);
        var cpuCsv=new StringBuilder("sequence,submit_recording_ms,admission_wait_ms,unimported_release_ms,deferrals\n");
        for(var s:samples)cpuCsv.append(String.format(Locale.ROOT,"%d,%.9f,%.9f,%.9f,%d%n",s.sequence(),s.submitNanos()/1e6,s.admissionNanos()/1e6,s.releaseNanos()/1e6,s.deferrals()));
        Files.writeString(out.resolve("cpu.csv"),cpuCsv,StandardCharsets.UTF_8);
        gpuGroups.forEach((name,values)->stages.add(object("name",name,"distribution",distribution(values.stream().mapToDouble(Double::doubleValue).toArray()))));
        String dispatchName=args.length>5&&args[5].equals("--decompose")?"DiagnosticDecomposedLighting":"NativeLightingDispatch";
        boolean timingValid=gpuGroups.containsKey(dispatchName)&&gpuGroups.get(dispatchName).size()==measured&&gpuStatus.droppedQueries()==0;
        Files.writeString(out.resolve("run.json"),RtxptJson.encode(object("format","rtr-e12-cost-run","formatVersion",1,"evidenceKind","REAL_GPU_RUN","backend","rtxpt-native",
            "workloadSha256",workload,"adapterLuid",Long.toUnsignedString(adapterLuid),"timestampUtc",java.time.Instant.now().toString(),
            "measuredFrames",measured,"warmupFrames",24,"firstMeasuredSequence",firstMeasured,"lastMeasuredSequence",lastMeasured,
            "timingValid",timingValid,"gpuQueryDropped",gpuStatus.droppedQueries(),"gpuQueryPending",gpuStatus.pendingQueries(),"gpuScopeOverwritten",gpuStatus.overwrittenScopes(),
            "gpuTimerSemantics","queue_timestamp_inclusive_ms_including_scheduling_preemption","gpuStages",stages,
            "initialPreparationMs",preparationsNanos/1e6,"cpuSubmit",distribution(samples.stream().mapToDouble(s->s.submitNanos()/1e6).toArray()),
            "workCountersUntimed",work.toString(),"execution",execution.toString(),"diagnosticMode","DEEP; per-ray counters OFF during timing",
            "unmeasured",List.of("receiver/source-query/shadow/GI are fused; no separate GPU interval","GL import/consumer and host reconstruction","legacy backend not run"),
            "baselineStatus","NOT_RUN","artifact",RtxptRenderer.class.getProtectionDomain().getCodeSource().getLocation().toString())),StandardCharsets.UTF_8);
        System.out.println("RTXPT_E12_NATIVE "+(timingValid?"TIMING_COMPLETE":"TIMING_INCOMPLETE")+" workload="+workload+" out="+out+" legacy=NOT_RUN speedup=UNDETERMINED");
    }
}
