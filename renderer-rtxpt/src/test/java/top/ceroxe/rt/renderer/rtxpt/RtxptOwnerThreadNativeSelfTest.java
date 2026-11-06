package top.ceroxe.rt.renderer.rtxpt;

import java.util.concurrent.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Windows hidden native session created, used and destroyed on a dedicated owner. */
public final class RtxptOwnerThreadNativeSelfTest {
    public static void main(String[] args)throws Exception {
        try(var owner=Executors.newSingleThreadExecutor(task->new Thread(task,"rtxpt-dedicated-owner-test"))){
            var renderer=owner.submit(()->{
                var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true);
                r.nativeLightingPlan(NativeLightingPlan.defaults(64,48));
                r.apply(SceneTransaction.builder(1).resetScene()
                    .upsert(MaterialAsset.builder(1).roughness(.8f).build())
                    .upsert(RtxptFramePreparationNativeSelfTest.card(1))
                    .upsert(SceneInstance.builder(1,1).build()).build());
                r.submit(RtxptFramePreparationNativeSelfTest.frame(0,1,64,48).toBuilder().environment(EnvironmentState.neutral()).build());
                return r;
            }).get(60,TimeUnit.SECONDS);
            try{
                try{renderer.diagnostics();throw new AssertionError("Foreign renderer call was accepted");}
                catch(IllegalStateException expected){require(expected.getMessage().contains("creating thread"),"Renderer explains owner limit");}
                var lease=owner.submit(()->renderer.acquireLightingFrame().orElseThrow()).get(10,TimeUnit.SECONDS);
                var frame=owner.submit(lease::frame).get(10,TimeUnit.SECONDS);
                require(frame.sequence()==0&&frame.sceneRevision()==1,"Immutable frame identity survives owner handoff");
                try{lease.frame();throw new AssertionError("Foreign lease operation was accepted");}
                catch(IllegalStateException expected){require(expected.getMessage().contains("owner thread"),"Lease explains owner limit");}
                owner.submit(()->{lease.close();renderer.awaitIdle();return null;}).get(10,TimeUnit.SECONDS);
            }finally{owner.submit(()->{renderer.close();return null;}).get(15,TimeUnit.SECONDS);}
        }
        System.out.println("RTXPT_OWNER_THREAD PASS create=true submit=true immutableFrame=true foreignRejected=true close=true hidden=true");
    }
}