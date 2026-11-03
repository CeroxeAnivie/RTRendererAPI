package top.ceroxe.rt.renderer.rtxpt;
import java.nio.LongBuffer;
import java.nio.file.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingMatrixNativeSelfTest.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Same dynamic workload at each recorder level; waits and diagnostic decomposition stay explicit. */
public final class RtxptLightingDiagnosticsCostBenchmark {
    static Map<String,Object> distribution(long[] data,double divisor){
        Arrays.sort(data);return RtxptJson.object("samples",data.length,"p50",data[59]/divisor,"p95",data[113]/divisor,
            "p99",data[118]/divisor,"max",data[119]/divisor);
    }
    public static void main(String[] args)throws Exception{
        var out=Path.of("build/reports/lighting-diagnostics").toAbsolutePath();Files.createDirectories(out);
        var rows=new ArrayList<Object>();var thread=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        thread.setThreadAllocatedMemoryEnabled(true);
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4).copyBuilder().gpuTimingsEnabled(true).build(),true)){
            r.nativeLightingPlan(new NativeLightingPlan(321,201,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,
                NativeLightingPlan.Signal.INDIRECT_DIFFUSE),2,6,.35f,NativeLightingStyle.defaults(),SUN_OFF,null,true));
            r.apply(SceneTransaction.builder(1).resetScene().upsert(material(1,128,128,128)).upsert(material(2,255,255,255))
                .upsert(plane(1,1,8,0,0,0,false)).upsert(plane(2,2,8,1,0,0,false)).upsert(SceneInstance.builder(1,1).build())
                .upsert(SceneInstance.builder(2,2).build()).build(),RtxptLightingPressureNativeSelfTest.sources(0,0,false),LongBuffer.allocate(0));
            publication(r,1,new NativeLightingPublication.Instance(2,4,1,0,1,1,0,0));long sequence=0,revision=1;
            var levels=args.length==0?List.of(RtxptDiagnostics.Level.values()):Arrays.stream(args).map(RtxptDiagnostics.Level::valueOf).toList();
            for(var level:levels){
                r.awaitIdle();r.diagnosticLevel(level);r.lightingWorkStatisticsEnabled(true);
                r.lightingDiagnosticDecompositionEnabled(level==RtxptDiagnostics.Level.DEEP);
                long[] wall=new long[120],allocated=new long[120];var gpu=new LinkedHashMap<String,RtxptDiagnostics.GpuScope>();
                for(int i=-12;i<120;i++){
                    long beforeBytes=thread.getThreadAllocatedBytes(Thread.currentThread().threadId()),start=System.nanoTime();
                    r.apply(SceneTransaction.builder(++revision).build(),RtxptLightingPressureNativeSelfTest.sources(0,(i+12)*.021,false),LongBuffer.allocate(0));
                    var request=RenderFrameRequest.builder(sequence++,640,400,CameraState.lookAt(0,0,3,0,0,0)
                        .verticalFieldOfViewDegrees(52).aspectRatio(1.6).build()).minimumSceneRevision(revision).environment(EnvironmentState.neutral()).build();
                    while(r.trySubmit(request) instanceof Renderer.FrameSubmissionDeferred)Thread.sleep(1);
                    try(var lease=r.acquireLightingFrame().orElseThrow()){require(lease.frame().sequence()==request.sequence(),"Fresh diagnostic workload");}
                    long elapsed=System.nanoTime()-start,bytes=thread.getThreadAllocatedBytes(Thread.currentThread().threadId())-beforeBytes;
                    if(i>=0){wall[i]=elapsed;allocated[i]=bytes;}
                    // Collect outside the measured CPU section; no disk writes in the frame loop.
                    if(level!=RtxptDiagnostics.Level.OFF&&i%16==15)for(var s:r.diagnosticGpuSnapshot().scopes())
                        gpu.put(s.nativeFrame()+":"+s.event(),s);
                }
                r.awaitIdle();var status=r.lightingStatisticsCollection();var snapshot=r.diagnosticGpuSnapshot();
                for(var s:snapshot.scopes())gpu.put(s.nativeFrame()+":"+s.event(),s);
                if(level==RtxptDiagnostics.Level.OFF)require(!status.effective()&&status.pending()==0&&snapshot.pendingQueries()==0,"OFF drains diagnostic work");
                rows.add(RtxptJson.object("level",level.toString(),"cpuWallMilliseconds",distribution(wall,1e6),"ownerJavaAllocatedBytes",distribution(allocated,1),
                    "statistics",status.toString(),"gpuStatus",snapshot.toString(),"gpuScopes",gpu.values().stream().map(Object::toString).toList(),
                    "decomposed",level==RtxptDiagnostics.Level.DEEP));
                System.out.println("LIGHTING_DIAGNOSTIC_COST level="+level+" cpu="+distribution(wall,1e6)+" statistics="+status);
            }
        }
        Files.writeString(out.resolve(args.length==0?"result.json":"supplement.json"),RtxptJson.encode(RtxptJson.object("passed",true,"grid",new int[]{321,201},
            "sources",64,"measuredFramesPerLevel",120,"warmupFramesPerLevel",12,"rows",rows,
            "semantics","CPU wall includes admission; Java owner-thread allocation only; DEEP changes scheduling; GPU timestamps include scheduling/preemption")));
    }
}
