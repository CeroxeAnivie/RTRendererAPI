package top.ceroxe.rt.renderer.rtxpt;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Small hidden JVM/JNI acceptance. No FPS measurement or desktop interaction. */
public final class RtxptDiagnosticMonitorNativeSelfTest {
    private static SceneTransaction scene(){return SceneTransaction.builder(1).resetScene()
        .upsert(MaterialAsset.builder(1).roughness(.8f).build())
        .upsert(RtxptFramePreparationNativeSelfTest.card(1))
        .upsert(SceneInstance.builder(1,1).build())
        .upsert(light(0,0)).build();}
    private static SceneLight light(long id,int edit){return SceneLight.point(id,(id%16-8)*.2,1+(id%3)*.1,2)
        .color(edit%2==0?.3f:.7f,.4f,.2f).intensity(.04f).range(6).castsShadow(false).build();}
    private static RenderFrameRequest frame(long sequence,long revision){return RtxptFramePreparationNativeSelfTest.frame(sequence,revision,96,54);}
    private static RtxptDiagnostics.Snapshot anotherThread(RtxptDiagnostics.Monitor monitor)throws Exception {
        var value=new AtomicReference<RtxptDiagnostics.Snapshot>();var error=new AtomicReference<Throwable>();
        var thread=new Thread(()->{try{value.set(monitor.snapshot());}catch(Throwable e){error.set(e);}},"rtxpt-monitor-one-shot");
        thread.start();thread.join(5000);require(!thread.isAlive(),"Diagnostic monitor must not wait for owner");
        if(error.get()!=null)throw new AssertionError("Foreign-thread diagnostic snapshot failed",error.get());
        return value.get();
    }
    public static void main(String[] args)throws Exception {
        var config=RtxptLightTreeNativeSelfTest.config(4).copyBuilder().gpuTimingsEnabled(true).build();
        RtxptDiagnostics.Monitor oldMonitor;long oldGeneration;
        try(var renderer=Renderer.open("rtxpt",config)){
            var r=(RtxptRenderer)renderer;r.diagnosticLevel(RtxptDiagnostics.Level.DEEP);
            r.apply(scene());r.submit(frame(0,1));for(int i=0;i<3;i++)r.renderFrame();
            oldMonitor=r.diagnosticMonitor();oldGeneration=oldMonitor.snapshot().generation();
            require(anotherThread(oldMonitor).generation()==oldGeneration,"Watchdog reads the current generation");
            var stop=new AtomicBoolean();var ready=new CountDownLatch(1);var error=new AtomicReference<Throwable>();
            var snapshots=new AtomicLong();var liveSubmits=new AtomicLong();var liveStages=new AtomicLong();
            var watchdog=new Thread(()->{
                try{
                    oldMonitor.snapshot();ready.countDown();
                    while(!stop.get()){
                        var s=oldMonitor.snapshot();snapshots.incrementAndGet();
                        for(var event:s.activeScopes()){
                            require(event.active()&&event.selfNanos()==0,"Active timing is explicit and provisional");
                            if(event.stage()==RtxptDiagnostics.Stage.SUBMIT)liveSubmits.incrementAndGet();else liveStages.incrementAndGet();
                        }
                        LockSupport.parkNanos(250_000);
                    }
                }catch(Throwable e){error.set(e);ready.countDown();}
            },"rtxpt-submit-watchdog");
            long revision=1,sequence=1;watchdog.start();
            try{
                require(ready.await(5,TimeUnit.SECONDS),"Watchdog starts without owner access");
                var first=SceneTransaction.builder(++revision);
                for(int i=1;i<=1024;i++)first.upsert(light(i,0));
                r.apply(first.build());r.submit(frame(sequence++,revision));
                for(int edit=0;edit<24;edit++){
                    var tx=SceneTransaction.builder(++revision);
                    for(int i=0;i<18;i++)tx.upsert(light((edit*18L+i)%1024,edit));
                    r.apply(tx.build());r.submit(frame(sequence++,revision));
                }
            }finally{stop.set(true);watchdog.join(5000);}
            require(!watchdog.isAlive(),"Watchdog stops promptly");
            if(error.get()!=null)throw new AssertionError("Concurrent native monitor failed",error.get());
            require(snapshots.get()>0&&liveSubmits.get()>0,"Watchdog observes actual in-progress submit");
            require(liveStages.get()>0,"Watchdog observes actual nested native work");
            System.out.println("RTXPT_MONITOR_CONCURRENT PASS snapshots="+snapshots+" liveSubmits="+liveSubmits+" liveStages="+liveStages);

            long failedSequence=sequence;
            try{r.submit(frame(failedSequence,revision+1));throw new AssertionError("Future scene revision accepted");}
            catch(IllegalArgumentException expected){ }
            var failed=oldMonitor.snapshot();
            require(failed.submissionSamples().stream().anyMatch(s->s.sequence()==failedSequence&&s.failed()&&!s.enteredNativeFrame()),
                "Rejected pre-frame submission is retained as failed");
            require(r.status()==Renderer.Status.READY,"Validation rejection leaves the renderer usable");
            r.submit(frame(sequence++,revision));
            System.out.println("RTXPT_MONITOR_REJECT PASS failedBeforeFrame=true recovery=true");

            var outputBeforeOff=r.presentationStatus();
            require(outputBeforeOff.outputWidth()>0&&outputBeforeOff.outputHeight()>0,"Initialized output has a real extent");
            r.diagnosticLevel(RtxptDiagnostics.Level.OFF);var off=anotherThread(oldMonitor);
            require(off.recordedLevel().orElseThrow()==RtxptDiagnostics.Level.OFF&&off.activeScopes().isEmpty(),"OFF public recorder state");
            for(int edit=0;edit<6;edit++){
                r.apply(SceneTransaction.builder(++revision).upsert(light(0,edit)).build());r.submit(frame(sequence++,revision));
            }
            // Explicit test drain verifies retirement; normal frames above use no CPU GPU wait.
            r.awaitIdle();for(int i=0;i<3;i++)r.renderFrame();
            var afterOff=anotherThread(oldMonitor);var gpuOff=r.diagnosticGpuSnapshot();
            require(afterOff.generation()==oldGeneration&&afterOff.events().equals(off.events()),"OFF stops CPU recording without expiring the monitor");
            require(!gpuOff.enabled()&&gpuOff.pendingQueries()==0,"OFF finishes existing GPU query retirement");
            require(r.status()==Renderer.Status.READY&&r.diagnostics().latestAcceptedSceneRevision()==revision
                &&r.diagnostics().latestCompletedFrameSequence()==sequence-1,"OFF retains operational status and real new frames");
            var outputAfterOff=r.presentationStatus();
            require(r.runtimeStatus()!=null&&outputAfterOff.outputWidth()==outputBeforeOff.outputWidth()
                &&outputAfterOff.outputHeight()==outputBeforeOff.outputHeight(),"OFF retains actual runtime/output status: "+outputAfterOff);
            r.diagnosticLevel(RtxptDiagnostics.Level.SUMMARY);r.submit(frame(sequence++,revision));
            var restored=anotherThread(oldMonitor);
            require(restored.generation()==oldGeneration&&restored.recordedLevel().orElseThrow()==RtxptDiagnostics.Level.SUMMARY
                &&restored.submissionSamples().size()>off.submissionSamples().size(),"SUMMARY resumes on the same monitor");
            System.out.println("RTXPT_MONITOR_OFF PASS newFrames=6 sameGeneration=true gpuPending=0 restored=true");
        }
        var closed=anotherThread(oldMonitor);
        require(closed.generation()==oldGeneration&&closed.recordedLevel().orElseThrow()==RtxptDiagnostics.Level.OFF,
            "Closed session exposes its final bounded evidence");
        // One minimal sequential reopen is necessary to verify stale monitor rejection.
        try(var renderer=Renderer.open("rtxpt",config)){
            var r=(RtxptRenderer)renderer;r.apply(scene());r.submit(frame(0,1));
            require(r.diagnosticMonitor().snapshot().generation()!=oldGeneration,"Reopened session gets a new generation");
            try{oldMonitor.snapshot();throw new AssertionError("Old monitor accessed the replacement session");}
            catch(IllegalStateException expected){require(expected.getMessage().contains("generation expired"),"Stale monitor rejection explains its generation");}
        }
        System.out.println("RTXPT_MONITOR_LIFECYCLE PASS closeFrozen=true oldGenerationRejected=true");
        System.out.println("RTXPT_MONITOR_ALL PASS");
    }
}
