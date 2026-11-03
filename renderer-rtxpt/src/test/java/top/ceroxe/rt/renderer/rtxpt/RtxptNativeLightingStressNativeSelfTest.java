package top.ceroxe.rt.renderer.rtxpt;

import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Real-duration dynamic/mixed work, followed by repeated session and generation retirement. */
public final class RtxptNativeLightingStressNativeSelfTest {
    static final Path OUT=Path.of("build/reports/rtxpt-stress").toAbsolutePath();
    static long frames;
    static RenderFrameRequest frame(long sequence,long revision,int width,int height){
        var base=RtxptNativeLightingNativeSelfTest.frame(sequence,revision);
        return RenderFrameRequest.builder(sequence,width,height,base.camera()).minimumSceneRevision(revision)
            .environment(base.environment()).clock(FrameClock.of(sequence*16_666_667,16_666_667)).build();
    }
    static void submit(RtxptRenderer renderer,RenderFrameRequest request)throws Exception{
        long deadline=System.nanoTime()+10_000_000_000L;
        while(renderer.trySubmit(request) instanceof Renderer.FrameSubmissionDeferred){
            require(System.nanoTime()<deadline,"Returned output slots retire within ten seconds");Thread.sleep(1);
        }
        try(var lease=renderer.acquireLightingFrame().orElseThrow()){
            var output=lease.frame();
            require(output.sequence()==request.sequence()&&output.sceneRevision()==request.minimumSceneRevision(),"Fresh pressure-test packet");
            require(output.clock().equals(request.clock()),"Pressure-test clock identity");
            require(output.planes().size()==2,"Only the requested gain and GI planes are published");
        }
        frames++;
    }
    static long pressure(RtxptRenderer renderer,long sequence,long revision,int seconds,boolean mixed)throws Exception{
        long started=System.nanoTime(),end=started+seconds*1_000_000_000L,first=sequence;
        while(System.nanoTime()<end){
            double phase=sequence*.031;
            var transaction=SceneTransaction.builder(++revision);
            if(mixed&&sequence%8==0)transaction.upsert(MaterialAsset.builder(1)
                .baseColorRgba8(sequence%16==0?0xff808080:0xff709080).roughness(.8f).build());
            transaction.upsert(SceneInstance.builder(1,1).transform(new AffineTransform(new float[]{
                1,0,0,(float)(.15*Math.sin(phase)),0,1,0,0,0,0,1,0})).build());
            var source=SparseLightSource.point(1,Math.sin(phase),0,2,1,.5f,.25f,4,8).withLayer(SparseLightSource.Layer.DYNAMIC);
            renderer.apply(transaction.build(),List.of(source),LongBuffer.allocate(0));
            if(mixed&&sequence%12==0){
                renderer.publishLightingAttributes(new NativeLightingPublication(sequence+1,List.of(
                    new NativeLightingPublication.Instance(1,23,sequence%24==0?.5f:1,0,1,1,0,0)),List.of(),null,null));
                renderer.nativeLightingPlan(NativeLightingPlan.defaults(96+(int)(sequence%3)*2,64+(int)(sequence%3)*2));
            }
            submit(renderer,frame(sequence++,revision,2560,1600));
            if(sequence%120==0)System.out.println("RTXPT_710_PRESSURE_PROGRESS mixed="+mixed+" seconds="+(System.nanoTime()-started)/1_000_000_000+" frames="+(sequence-first));
            Thread.sleep(2);
        }
        renderer.awaitIdle();renderer.captureNativeLighting(OUT.resolve(mixed?"mixed-final.pfm":"dynamic-final.pfm"),0);
        require(renderer.nativeLightingExecution().fullBeautyFrames()==0,"Pressure work stays in the independent lighting plan");
        System.out.println("RTXPT_710_PRESSURE PASS mixed="+mixed+" seconds="+seconds+" frames="+(sequence-first));
        return sequence;
    }
    public static void main(String[] args)throws Exception{
        Files.createDirectories(OUT);int resizes=0;
        for(int session=0;session<10;session++){
            try(var renderer=new RtxptRenderer(RtxptLightTreeNativeSelfTest.config(4),true)){
                renderer.nativeLightingPlan(NativeLightingPlan.defaults(49,33));renderer.apply(RtxptNativeLightingNativeSelfTest.scene());
                long sequence=0;submit(renderer,frame(sequence++,1,96,64));
                for(int resize=0;resize<5;resize++){
                    int width=49+resize*2,height=33+resize*2;
                    renderer.nativeLightingPlan(NativeLightingPlan.defaults(width,height));
                    submit(renderer,frame(sequence++,1,resize%2==0?2560:97,resize%2==0?1600:65));resizes++;
                }
                renderer.awaitIdle();
                if(session==0){
                    renderer.nativeLightingPlan(NativeLightingPlan.defaults(96,64));
                    sequence=pressure(renderer,sequence,1,60,false);
                    // Each pressure frame advances revision once; account for the six setup frames.
                    pressure(renderer,sequence,1+(sequence-6),120,true);
                }
                require(renderer.nativeLightingExecution().fullBeautyFrames()==0,"Lifecycle sessions do not execute beauty");
                System.out.println("RTXPT_710_LIFECYCLE_SESSION PASS session="+(session+1));
            }
        }
        Files.writeString(OUT.resolve("result.json"),"{\"passed\":true,\"sessions\":10,\"resizes\":"+resizes+",\"dynamicSeconds\":60,\"mixedSeconds\":120,\"frames\":"+frames+"}\n");
        System.out.println("RTXPT_710_STRESS_ALL PASS sessions=10 resizes="+resizes+" dynamicSeconds=60 mixedSeconds=120 frames="+frames);
    }
}
