package top.ceroxe.rt.renderer.rtxpt;

import java.nio.LongBuffer;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptNativeLightingMatrixNativeSelfTest.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Stable checkpoints pair with native resource census and an external process sampler. */
public final class RtxptLightingLifecycleNativeSelfTest {
    public static void main(String[] args)throws Exception{
        var out=Path.of("build/reports/lighting-lifecycle").toAbsolutePath();Files.createDirectories(out);
        var rows=new ArrayList<Object>();int resizes=0;
        for(int session=0;session<10;session++){
            long sequence=0;
            try(var r=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
                var tx=SceneTransaction.builder(1).resetScene().upsert(material(1,128,128,128));
                for(long id=1;id<=256;id++)tx.upsert(plane(id,1,id==1?8:.1f,0,0,0,false))
                    .upsert(SceneInstance.builder(id,id).transform(new AffineTransform(new float[]{1,0,0,id==1?0:100+id,0,1,0,0,0,0,1,0})).build());
                r.apply(tx.build(),RtxptLightingPressureNativeSelfTest.sources(0,0,false),LongBuffer.allocate(0));
                for(int resize=0;resize<5;resize++){
                    int w=321+resize*2,h=201+resize*2;
                    r.nativeLightingPlan(new NativeLightingPlan(w,h,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,
                        NativeLightingPlan.Signal.INDIRECT_DIFFUSE),2,6,.35f,NativeLightingStyle.defaults(),SUN_OFF,null,true));
                    var request=RenderFrameRequest.builder(sequence++,2560,1600,CameraState.lookAt(0,0,3,0,0,0)
                        .verticalFieldOfViewDegrees(52).aspectRatio(1.6).build()).minimumSceneRevision(1).environment(EnvironmentState.neutral()).build();
                    while(r.trySubmit(request) instanceof Renderer.FrameSubmissionDeferred)Thread.sleep(1);
                    try(var lease=r.acquireLightingFrame().orElseThrow()){
                        require(lease.frame().sequence()==request.sequence()&&lease.frame().width()==w&&lease.frame().height()==h,"Fresh resized lease");
                    }
                    r.awaitIdle();resizes++;
                    rows.add(RtxptJson.object("session",session,"resize",resize,"utc",java.time.Instant.now().toString(),
                        "resources",r.retainedUpdateTimings().toString(),"execution",r.nativeLightingExecution().toString()));
                }
                require(r.nativeLightingExecution().fullBeautyFrames()==0,"Lifecycle runs lighting only");
            }
            System.gc();Thread.sleep(300);
            rows.add(RtxptJson.object("session",session,"closed",true,"utc",java.time.Instant.now().toString()));
            Files.writeString(out.resolve("progress.json"),RtxptJson.encode(rows));
            System.out.println("LIGHTING_LIFECYCLE_CHECKPOINT session="+session+" utc="+java.time.Instant.now()+" closed=true");
        }
        Files.writeString(out.resolve("result.json"),RtxptJson.encode(RtxptJson.object("passed",true,"sessions",10,"resizes",resizes,"meshes",256,"checkpoints",rows)));
        System.out.println("LIGHTING_LIFECYCLE PASS sessions=10 resizes="+resizes+" meshes=256");
    }
}
