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
}
