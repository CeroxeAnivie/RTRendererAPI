package top.ceroxe.rt.renderer.rtxpt;

import java.nio.file.*;
import java.util.*;
import java.lang.management.ManagementFactory;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Cold and warm session creation, retained output and shutdown are measured separately. */
public final class RtxptResourceLifecycleNativeSelfTest {
    public static void main(String[] args)throws Exception{
        var output=Path.of(System.getProperty("resourceMatrix.output","build/reports/resource-latency"));Files.createDirectories(output);
        if(args.length==1&&args[0].equals("cancellation")){cancellation(output);return;}
        if(args.length==1&&args[0].equals("measurement")){measurement(output);return;}
        var rows=new ArrayList<Object>();var cpu=ManagementFactory.getThreadMXBean();
        for(int session=0;session<2;session++){
            long start=System.nanoTime(),cpuStart=cpu.getCurrentThreadCpuTime();
            var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true);
            try{
                r.nativeLightingPlan(NativeLightingPlan.defaults(49,33));r.diagnosticLevel(RtxptDiagnostics.Level.SUMMARY);
                var monitor=r.diagnosticMonitor();r.apply(RtxptNativeLightingNativeSelfTest.scene());
                RtxptResourceLatencyMatrixNativeSelfTest.render(r,0,1);r.awaitIdle();
                var snapshot=monitor.snapshot();
                rows.add(RtxptJson.object("case",session==0?"process-cold":"warm-reopen","wallNanos",System.nanoTime()-start,
                    "threadCpuNanos",cpuStart<0?-1:cpu.getCurrentThreadCpuTime()-cpuStart,
                    "longestStages",snapshot.events().stream().filter(RtxptDiagnostics.CpuEvent::completed)
                        .sorted(Comparator.comparingLong(RtxptDiagnostics.CpuEvent::inclusiveNanos).reversed()).limit(8).map(Object::toString).toList(),
                    "heapUsed",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),
                    "directBytes",ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class).stream().filter(p->p.getName().equals("direct")).mapToLong(java.lang.management.BufferPoolMXBean::getMemoryUsed).sum()));
                // Leave one newly published packet unimported. Shutdown must own
                // its lifetime without claiming a completed external consumer.
                r.submit(RtxptNativeLightingNativeSelfTest.frame(1,1));
            }finally{
                long close=System.nanoTime();r.close();rows.add(RtxptJson.object("case","close","session",session,"wallNanos",System.nanoTime()-close));
            }
        }
        require(rows.size()==4,"Separate startup and shutdown observations");
        Files.writeString(output.resolve("lifecycle.json"),RtxptJson.encode(rows));
        System.out.println("RESOURCE_LIFECYCLE PASS processCold=true warmReopen=true unimportedOutput=true");
    }
    static void cancellation(Path output)throws Exception{
        var rows=new ArrayList<Object>();
        for(boolean observe:new boolean[]{false,true}){
            var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true);
            r.nativeLightingPlan(NativeLightingPlan.defaults(49,33));
            r.diagnosticLevel(RtxptDiagnostics.Level.SUMMARY);
            r.apply(RtxptNativeLightingNativeSelfTest.scene());
            if(observe)r.diagnosticMonitor().snapshot();
            r.close();r.close();
            require(r.status()==Renderer.Status.CLOSED,"Cancelled preparation closes idempotently");
            boolean rejected=false;
            try{r.trySubmit(RtxptNativeLightingNativeSelfTest.frame(0,1));}
            catch(IllegalStateException expected){rejected=true;}
            require(rejected,"Cancelled preparation cannot accept a frame");
            rows.add(RtxptJson.object("case","cancel-before-first-submit","observerPrepared",observe,"passed",true));
        }
        try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
            r.nativeLightingPlan(NativeLightingPlan.defaults(49,33));
            r.diagnosticLevel(RtxptDiagnostics.Level.SUMMARY);r.diagnosticMonitor();
            r.apply(RtxptNativeLightingNativeSelfTest.scene());
            RtxptResourceLatencyMatrixNativeSelfTest.render(r,0,1);r.awaitIdle();
            rows.add(RtxptJson.object("case","reopen-after-cancel-fresh-output","passed",true));
        }
        Files.writeString(output.resolve("lifecycle-cancellation.json"),RtxptJson.encode(rows));
        System.out.println("RESOURCE_LIFECYCLE_CANCELLATION PASS noAcceptedCancelledFrame=true reopenFreshOutput=true");
    }
    static Map<String,Object> observation(String name,long start,long cpuStart,
            RtxptDiagnostics.Monitor monitor,long firstOrdinal){
        long end=System.nanoTime(),cpuEnd=ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
        var row=RtxptJson.object("case",name,"wallNanos",end-start,"threadCpuNanos",cpuStart<0?-1:cpuEnd-cpuStart,
            "heapUsed",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),
            "directBytes",ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class).stream()
                .filter(p->p.getName().equals("direct")).mapToLong(java.lang.management.BufferPoolMXBean::getMemoryUsed).sum());
        if(monitor!=null){
            var snapshot=monitor.snapshot();
            row.put("cpuStages",snapshot.events().stream().filter(e->e.completed()&&e.ordinal()>=firstOrdinal)
                .map(e->RtxptJson.object("stage",e.stage().name(),"inclusiveNanos",e.inclusiveNanos(),"selfNanos",e.selfNanos(),
                    "threadCpuNanos",e.threadCpuNanos(),"thread",e.thread(),"items",e.items(),"bytes",e.bytes(),"flags",e.flags())).toList());
            row.put("missedSinceRegistration",snapshot.missedSinceRegistration());
            row.put("overwrittenEvents",snapshot.overwrittenEvents());
        }
        return row;
    }
    /** Repeated by separate processes; this does not change or time the steady-state benchmark. */
    static void measurement(Path output)throws Exception{
        var rows=new ArrayList<Object>();var cpu=ManagementFactory.getThreadMXBean();
        require("1".equals(System.getenv("RTR_RTXPT_REQUIRE_PRECOMPILED")),"Lifecycle acceptance requires packaged permutations");
        for(int session=0;session<2;session++){
            long start=System.nanoTime(),cpuStart=cpu.getCurrentThreadCpuTime();
            var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4).copyBuilder().resources(RendererResourceOptions.high()).build(),true);
            try{
                r.nativeLightingPlan(NativeLightingPlan.defaults(1280,800));
                r.lightingReconstruction(NativeLightingReconstruction.DLSS_RR);r.diagnosticLevel(RtxptDiagnostics.Level.SUMMARY);
                var monitor=r.diagnosticMonitor();long configured=System.nanoTime();
                r.apply(RtxptNativeLightingNativeSelfTest.scene());long applied=System.nanoTime();
                var base=RtxptDynamicResourcesNativeSelfTest.request(0,1,0);
                var first=RenderFrameRequest.builder(0,2560,1600,base.camera()).minimumSceneRevision(1).environment(base.environment()).build();
                RtxptResourceLatencyMatrixNativeSelfTest.admit(r,first);RtxptHighFramerateBenchmark.release(r,0,1);r.awaitIdle();
                require(r.lightingReconstruction().actual()==NativeLightingReconstruction.DLSS_RR,"Real RR after startup");
                var row=observation(session==0?"process-cold":"warm-reopen",start,cpuStart,monitor,0);
                row.put("constructAndConfigurationNanos",configured-start);row.put("sceneApplyNanos",applied-configured);
                row.put("firstRequestThroughOutputNanos",((Number)row.get("wallNanos")).longValue()-(applied-start));rows.add(row);
                long sequence=1;
                for(int[] extent:new int[][]{{960,540,1920,1080},{1280,800,2560,1600},{1279,799,2560,1600}}){
                    long ordinal=monitor.snapshot().nextEventOrdinal();start=System.nanoTime();cpuStart=cpu.getCurrentThreadCpuTime();
                    r.nativeLightingPlan(NativeLightingPlan.defaults(extent[0],extent[1]));
                    var resized=RtxptDynamicResourcesNativeSelfTest.request(sequence,1,0);
                    var request=RenderFrameRequest.builder(sequence,extent[2],extent[3],resized.camera()).minimumSceneRevision(1).environment(resized.environment()).build();
                    RtxptResourceLatencyMatrixNativeSelfTest.admit(r,request);RtxptHighFramerateBenchmark.release(r,sequence++,1);r.awaitIdle();
                    row=observation("resize-"+extent[0]+"x"+extent[1],start,cpuStart,monitor,ordinal);
                    row.put("hostWidth",extent[2]);row.put("hostHeight",extent[3]);rows.add(row);
                }
            }finally{
                long close=System.nanoTime(),closeCpu=cpu.getCurrentThreadCpuTime();r.close();
                rows.add(observation("close",close,closeCpu,null,0));
            }
        }
        for(boolean observe:new boolean[]{false,true}){
            long start=System.nanoTime(),cpuStart=cpu.getCurrentThreadCpuTime();
            var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true);
            r.nativeLightingPlan(NativeLightingPlan.defaults(1280,800));r.apply(RtxptNativeLightingNativeSelfTest.scene());
            if(observe)r.diagnosticMonitor();r.close();r.close();
            require(r.status()==Renderer.Status.CLOSED,"Cancelled preparation is closed");
            rows.add(observation(observe?"cancel-after-observer":"cancel-before-first-submit",start,cpuStart,null,0));
        }
        Files.writeString(output.resolve("lifecycle-measurement.json"),RtxptJson.encode(RtxptJson.object("passed",true,
            "scene",RtxptJson.object("meshes",1,"instances",1,"triangles",2,"materials",1,"textures",0,"sparseSources",0,"initialHostWidth",2560,"initialHostHeight",1600,"initialSignalWidth",1280,"initialSignalHeight",800),
            "strictPrecompiled",true,"realRr",true,"cacheBoundary","Cold means first renderer in a new JVM; disk and driver caches are not cleared. Warm means reopen in the same JVM. Internal PSO/RR cache hits are not independently exposed.",
            "timingBoundary","Construction/configuration, apply, first request through validated output; resize includes all retries and output; close/cancel include the complete operation. Event intervals may overlap and are not additive.","cases",rows)));
        System.out.println("RESOURCE_LIFECYCLE_MEASUREMENT PASS cold=true warm=true resize=true close=true cancel=true realRr=true strictPrecompiled=true");
    }
}
