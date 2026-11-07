package top.ceroxe.rt.renderer.rtxpt;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import top.ceroxe.rt.renderer.api.*;

/** Final-frame preparation must consume live resources once, without losing poses or pending work. */
public final class RtxptDeferredFrameNativeSelfTest {
    private static final CameraState CAMERA=CameraState.lookAt(0,0,3,0,0,0).build();
    private static NativeLightingPlan plan(int width,int height){
        return new NativeLightingPlan(width,height,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.NORMAL_DISTANCE),0,6,0,NativeLightingStyle.defaults());
    }
    public static void main(String[] args)throws Exception {
        Path output=Path.of(args.length==0?"build/reports/deferred-frame-contract":args[0]).toAbsolutePath();Files.createDirectories(output);
        var rows=new ArrayList<String>();rows.add("sequence,case,graph_refreshes,mesh_buffer_objects,blas_allocations");
        try(var packets=new RtxptMemoryResources.PacketPool();var renderer=RtxptRenderers.openNativeLighting(RtxptRenderers.nativeLightingConfiguration(),plan(64,64))) {
            var access=renderer.extension(RtxptAccess.class).orElseThrow();var lighting=renderer.extension(NativeLightingControl.class).orElseThrow();
            access.diagnosticLevel(RtxptDiagnostics.Level.DEEP);lighting.lightingReconstruction(NativeLightingReconstruction.NONE);
            var field=RtxptRenderer.class.getDeclaredField("handle");field.setAccessible(true);
            var initial=base(1,0,0);renderer.apply(initial);render(renderer,access,lighting,1,64,64);
            long handle=field.getLong(renderer);var monitor=access.nativeDiagnosticMonitor();monitor.poll();
            var mirror=new RtxptScene();mirror.retain(initial);
            double[] receiverBefore=RtxptNative.meshResourceState(handle,"i22");

            // These are distinct native packets in one Frame, not Java changes
            // collapsed before JNI. The temporary front surface must never be uploaded.
            mirror=enqueue(handle,mirror,SceneTransaction.builder(2).upsert(quad(4,0,1,.8f)).upsert(SceneInstance.builder(44,4).build()).build(),packets);
            mirror=enqueue(handle,mirror,SceneTransaction.builder(2).removeInstance(44).removeMesh(4).build(),packets);
            mirror=enqueue(handle,mirror,SceneTransaction.builder(2).upsert(quad(2,0,0,.2f)).build(),packets);
            mirror=enqueue(handle,mirror,SceneTransaction.builder(2).upsert(quad(2,0,0,.6f)).build(),packets);
            var finalEdit=SceneTransaction.builder(2).upsert(quad(2,0,0,.6f)).upsert(instance(.35f)).build();renderer.apply(finalEdit);mirror.retain(finalEdit);
            render(renderer,access,lighting,2,64,64);var snapshot=monitor.poll();
            checkWork(snapshot,2,true);absent(handle,"i44");pose(handle,.35f,0);
            require(receiverBefore[0]==RtxptNative.meshResourceState(handle,"i22")[0],"Final normal-only replacement rebuilt the resident BLAS");
            pixels(handle,output,2,64,64,.6f);row(rows,2,"multiple-packets-final-live-only",snapshot);

            renderer.apply(SceneTransaction.builder(3).build());render(renderer,access,lighting,3,64,64);snapshot=monitor.poll();
            checkWork(snapshot,0,false);pose(handle,.35f,.35f);absent(handle,"i44");pixels(handle,output,3,64,64,.6f);row(rows,3,"previous-pose-converges",snapshot);
            renderer.apply(SceneTransaction.builder(4).build());render(renderer,access,lighting,4,64,64);snapshot=monitor.poll();
            checkWork(snapshot,0,false);pose(handle,.35f,.35f);absent(handle,"i44");pixels(handle,output,4,64,64,.6f);row(rows,4,"no-cross-frame-pending",snapshot);

            mirror=enqueue(handle,mirror,SceneTransaction.builder(5).upsert(quad(5,0,1,.9f)).upsert(SceneInstance.builder(55,5).build()).build(),packets);
            RtxptNative.loadScene(handle,null); // The supported clear path must discard the earlier pending upload.
            var reset=base(5,-.25f,-.4f);renderer.apply(reset);mirror=new RtxptScene();mirror.retain(reset);
            render(renderer,access,lighting,5,64,64);snapshot=monitor.poll();
            checkWork(snapshot,2,false);absent(handle,"i44");absent(handle,"i55");poseCurrent(handle,-.25f);
            pixels(handle,output,5,64,64,-.4f);row(rows,5,"clear-discards-pending",snapshot);

            // Resize is queued between resource packets and the final frame.
            // Actual backbuffer callbacks may use eager preparation; pixels and
            // previous pose remain mandatory regardless of that lifecycle path.
            mirror=enqueue(handle,mirror,SceneTransaction.builder(6).upsert(quad(6,0,1,.9f)).upsert(SceneInstance.builder(66,6).build()).build(),packets);
            mirror=enqueue(handle,mirror,SceneTransaction.builder(6).removeInstance(66).removeMesh(6).upsert(quad(2,0,0,.3f)).build(),packets);
            RtxptNative.resize(handle,96,80);lighting.nativeLightingPlan(plan(96,80));
            var resizeEdit=SceneTransaction.builder(6).upsert(quad(2,0,0,.3f)).upsert(instance(.5f)).build();renderer.apply(resizeEdit);mirror.retain(resizeEdit);
            render(renderer,access,lighting,6,96,80);snapshot=monitor.poll();
            checkWork(snapshot,2,false);absent(handle,"i55");absent(handle,"i66");pose(handle,.5f,-.25f);
            pixels(handle,output,6,96,80,.3f);row(rows,6,"resize-flushes-final-live-data",snapshot);

            renderer.apply(SceneTransaction.builder(7).build());render(renderer,access,lighting,7,96,80);snapshot=monitor.poll();
            checkWork(snapshot,0,false);pose(handle,.5f,.5f);absent(handle,"i55");absent(handle,"i66");pixels(handle,output,7,96,80,.3f);row(rows,7,"resize-next-frame-convergence",snapshot);
            renderer.apply(SceneTransaction.builder(8).build());render(renderer,access,lighting,8,96,80);snapshot=monitor.poll();
            checkWork(snapshot,0,false);pose(handle,.5f,.5f);absent(handle,"i66");pixels(handle,output,8,96,80,.3f);row(rows,8,"resize-no-pending-replay",snapshot);
        }finally {Files.write(output.resolve("deferred-frame-cases.csv"),rows,StandardCharsets.UTF_8);}
        System.out.println("DEFERRED_FRAME_NATIVE PASS freshFrames=8 multipleNativePackets=true finalLiveUpload=true sameIdentityReplacement=true previousPose=true clear=true resize=true noPendingReplay=true freshPixels=true publicModeSwitchSupported=false sameSessionBeautySwitchTested=false");
    }
    private static SceneTransaction base(long sequence,float x,float tilt){
        return SceneTransaction.builder(sequence).resetScene().upsert(MaterialAsset.builder(1).doubleSided(true).build())
                .upsert(quad(1,-3,0,0)).upsert(quad(2,0,0,tilt)).upsert(instance(x)).upsert(SceneInstance.builder(22,2).build()).build();
    }
    private static SceneInstance instance(float x){return SceneInstance.builder(11,1).transform(new AffineTransform(new float[]{1,0,0,x,0,1,0,0,0,0,1,0})).build();}
    private static MeshAsset quad(long id,float x,float z,float tilt){
        float nz=(float)Math.sqrt(1-tilt*tilt);
        return MeshAsset.builder(id,new float[]{x-1,-1,z,x+1,-1,z,x+1,1,z,x-1,1,z},new int[]{0,1,2,0,2,3},new long[]{1,1})
                .normals(new float[]{0,tilt,nz,0,tilt,nz,0,tilt,nz,0,tilt,nz}).build();
    }
    private static RtxptScene enqueue(long handle,RtxptScene previous,SceneTransaction transaction,RtxptMemoryResources.PacketPool packets){
        var next=new RtxptScene(previous);next.retain(transaction);
        var request=RenderFrameRequest.builder(transaction.revision(),64,64,CAMERA).build();
        var delta=RtxptDelta.prepare(previous,next,previous.items(request),next.items(request),Map.of(),packets);
        require(delta.hasWork()&&delta.memory()!=null,"Intermediate native packet unexpectedly had no work");
        try(var borrowed=packets.borrow(delta.memory())){RtxptNative.resourcesMemory(handle,transaction.revision(),borrowed.buffer());}
        return next;
    }
    private static void render(Renderer renderer,RtxptAccess access,NativeLightingControl lighting,long sequence,int width,int height){
        var clock=FrameClock.of(sequence*1_000_000L,sequence==1?0:1_000_000L);
        lighting.publishLightingAttributes(new NativeLightingPublication(sequence,List.of(NativeLightingPublication.Instance.receiver(11),NativeLightingPublication.Instance.receiver(22)),List.of(),null,null));
        var receipt=renderer.submit(RenderFrameRequest.builder(sequence,width,height,CAMERA).minimumSceneRevision(sequence).clock(clock).build());access.awaitIdle();
        require(receipt.frameSequence()==sequence&&receipt.scheduledSceneRevision()==sequence,"Deferred receipt identity changed");
        try(var lease=lighting.acquireLightingFrame().orElseThrow()){
            var frame=lease.frame();require(frame.sequence()==sequence&&frame.sceneRevision()==sequence&&frame.attributeGeneration()==sequence&&frame.clock().equals(clock),"Deferred packet was stale");
            require(frame.width()==width&&frame.height()==height&&frame.planes().size()==2&&frame.planes().getFirst().memoryHandle()!=0&&frame.readyValue()<frame.completionValue(),"Deferred output extent or synchronization changed");
        }
    }
    private static void checkWork(RtxptDiagnostics.NativeSnapshot snapshot,long expectedBufferObjects,boolean exactlyOneGraph){
        require(snapshot.lostEvents()==0,"Deferred diagnostics lost events");
        long buffers=snapshot.events().stream().filter(event->event.stage().equals("MeshBufferUpload")).mapToLong(RtxptDiagnostics.NativeEvent::items).sum();
        require(buffers==expectedBufferObjects,"Transient or superseded mesh reached native upload: "+buffers+" instead of "+expectedBufferObjects);
        if(exactlyOneGraph){require(count(snapshot,"SceneGraphRefresh")==1,"Final topology refreshed the graph more than once");require(count(snapshot,"BlasAllocate")==0,"Transient mesh reached native BLAS allocation");}
    }
    private static void absent(long handle,String name){
        boolean absent=false;try{RtxptNative.meshResourceState(handle,name);}catch(IllegalStateException expected){absent=true;}
        require(absent,"Retired/intermediate resource survived: "+name);
    }
    private static void poseCurrent(long handle,float current){require(Math.abs(RtxptNative.instanceTransformState(handle,"i11")[3]-current)<1e-6,"Current pose was lost");}
    private static void pose(long handle,float current,float previous){
        var value=RtxptNative.instanceTransformState(handle,"i11");
        float[] now={1,0,0,current,0,1,0,0,0,0,1,0},before={1,0,0,previous,0,1,0,0,0,0,1,0};
        for(int component=0;component<12;component++){require(Math.abs(value[component]-now[component])<1e-6,"Current pose changed");require(Math.abs(value[12+component]-before[component])<1e-6,"Previous pose was consumed early or failed to converge");}
    }
    private static void pixels(long handle,Path output,long sequence,int width,int height,float tilt)throws Exception {
        Path path=output.resolve(sequence+"-normal.pfm");RtxptNative.nativeLightingCapture(handle,path.toString(),2);
        byte[] bytes=Files.readAllBytes(path);int offset=0;for(int line=0;line<3;line++)while(offset<bytes.length&&bytes[offset++]!='\n'){}
        require(new String(bytes,0,offset,StandardCharsets.US_ASCII).equals("PF\n"+width+" "+height+"\n-1.0\n")&&bytes.length-offset==width*height*12,"Invalid deferred pixel capture");
        float[] expected={0,tilt,(float)Math.sqrt(1-tilt*tilt)};var data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for(int y=height/2-1;y<=height/2;y++)for(int x=width/2-1;x<=width/2;x++)for(int channel=0;channel<3;channel++){
            float value=data.getFloat(offset+((height-1-y)*width+x)*12+channel*4);require(Float.isFinite(value)&&Math.abs(value-expected[channel])<.025f,"Final live geometry pixels were lost or a transient surface survived");
        }
    }
    private static void row(List<String> rows,long sequence,String name,RtxptDiagnostics.NativeSnapshot snapshot){
        long buffers=snapshot.events().stream().filter(event->event.stage().equals("MeshBufferUpload")).mapToLong(RtxptDiagnostics.NativeEvent::items).sum();
        rows.add(sequence+","+name+","+count(snapshot,"SceneGraphRefresh")+","+buffers+","+count(snapshot,"BlasAllocate"));
        System.out.println("DEFERRED_FRAME sequence="+sequence+" case="+name+" bufferObjects="+buffers+" PASS");
    }
    private static long count(RtxptDiagnostics.NativeSnapshot snapshot,String stage){return snapshot.events().stream().filter(event->event.stage().equals(stage)).count();}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
