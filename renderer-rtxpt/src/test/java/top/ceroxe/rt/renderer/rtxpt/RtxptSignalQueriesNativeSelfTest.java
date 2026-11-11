package top.ceroxe.rt.renderer.rtxpt;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import top.ceroxe.rt.renderer.api.*;

/** Concurrent bridge queries must preserve exact publication and GPU lease ownership. */
public final class RtxptSignalQueriesNativeSelfTest {
    public static void main(String[] args) throws Exception {
        var plan=new NativeLightingPlan(64,64,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,
                NativeLightingPlan.Signal.NORMAL_DISTANCE),0,6,0,NativeLightingStyle.defaults());
        try(var renderer=RtxptRenderers.openNativeLighting(RtxptRenderers.nativeLightingConfiguration(),plan)) {
            var lighting=renderer.extension(NativeLightingControl.class).orElseThrow();
            renderer.apply(SceneTransaction.builder(1).resetScene()
                    .upsert(MaterialAsset.builder(1).doubleSided(true).build())
                    .upsert(MeshAsset.builder(1,new float[]{-1,-1,0,1,-1,0,0,1,0},new int[]{0,1,2},new long[]{1}).build())
                    .upsert(SceneInstance.builder(1,1).build()).build());
            submit(renderer,lighting,1);
            var field=RtxptRenderer.class.getDeclaredField("handle");field.setAccessible(true);
            long handle=field.getLong(renderer);
            var stop=new AtomicBoolean();var failure=new AtomicReference<Throwable>();var checks=new AtomicLong();
            var started=new CountDownLatch(1);
            var observer=new Thread(()->{
                try {
                    while(!stop.get()) {
                        RtxptNative.nativeLightingCanPublish(handle);
                        RtxptNative.nativeLightingConsumersRetired(handle);
                        checks.incrementAndGet();started.countDown();LockSupport.parkNanos(100_000);
                    }
                } catch(Throwable error){failure.set(error);started.countDown();}
            },"signal-query-observer");
            var leases=new ArrayList<LightingFrameLease>();
            observer.start();
            try {
                require(started.await(5,TimeUnit.SECONDS),"Concurrent observer did not start");
                leases.add(acquire(lighting,1));
                submit(renderer,lighting,2);leases.add(acquire(lighting,2));
                submit(renderer,lighting,3);leases.add(acquire(lighting,3));
                require(!RtxptNative.nativeLightingCanPublish(handle),"Concurrent query lost ring pressure");
                require(!RtxptNative.nativeLightingConsumersRetired(handle),"Live leases reported retired");
                require(renderer.trySubmit(frame(4)) instanceof Renderer.FrameSubmissionDeferred,"Full ring admitted another frame");
                leases.getFirst().close();
                submit(renderer,lighting,4);var fourth=acquire(lighting,4);
                require(lighting.acquireLightingFrame().isEmpty(),"Packet acquired twice");
                for(int i=1;i<leases.size();i++) {
                    var held=leases.get(i).frame();
                    require(held.sequence()==i+1,"Held frame identity changed");
                    require(held.planes().getFirst().memoryHandle()!=fourth.frame().planes().getFirst().memoryHandle(),
                            "Publishing overwrote a live consumer resource");
                }
                var consumer=new Thread(()->{
                    try{require(fourth.frame().sequence()==4,"Transferred packet is stale");fourth.close();}
                    catch(Throwable error){failure.compareAndSet(null,error);}
                },"signal-lease-consumer");
                fourth.transferTo(consumer);consumer.start();consumer.join(5000);
                require(!consumer.isAlive(),"Transferred lease return blocked");
                for(var lease:leases)lease.close();leases.clear();
                require(RtxptNative.nativeLightingConsumersRetired(handle),"Returned consumers still held");
                if(failure.get()!=null)throw new AssertionError("Concurrent ring query failed",failure.get());
                System.out.println("SIGNAL_QUERIES PASS frames=4 concurrentChecks="+checks.get()+" ringPressure=true exactIdentity=true transfer=true");
            } finally {
                stop.set(true);observer.join(5000);
                for(var lease:leases)lease.close();
            }
        }
    }
    private static RenderFrameRequest frame(long sequence){
        return RenderFrameRequest.builder(sequence,64,64,CameraState.lookAt(0,0,3,0,0,0).build()).minimumSceneRevision(1).build();
    }
    private static void submit(Renderer renderer,NativeLightingControl lighting,long sequence){
        lighting.publishLightingAttributes(new NativeLightingPublication(sequence,
                List.of(NativeLightingPublication.Instance.receiver(1)),List.of(),null,null));
        require(renderer.trySubmit(frame(sequence)) instanceof Renderer.FrameSubmitted,"Available ring refused publication");
    }
    private static LightingFrameLease acquire(NativeLightingControl lighting,long sequence){
        var lease=lighting.acquireLightingFrame().orElseThrow();
        require(lease.frame().sequence()==sequence&&lease.frame().sceneRevision()==1,"Stale signal descriptor");
        return lease;
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
