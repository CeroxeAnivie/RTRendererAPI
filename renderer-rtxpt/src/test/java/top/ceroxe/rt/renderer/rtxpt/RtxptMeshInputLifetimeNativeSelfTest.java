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

/** Owned input packets outlive their queue entry, but never their last mesh generation. */
public final class RtxptMeshInputLifetimeNativeSelfTest {
    private static final int SIZE=64;
    private static final CameraState CAMERA=CameraState.lookAt(0,0,3,0,0,0).build();
    private record Queued(RtxptScene scene,int bytes) { }

    public static void main(String[] args)throws Exception {
        Path output=Path.of(args.length==0?"build/reports/mesh-input-lifetime-contract":args[0]).toAbsolutePath();
        Files.createDirectories(output);
        var rows=new ArrayList<String>();
        rows.add("sequence,case,uploaded_buffer_objects,uploaded_bytes,current_retained_bytes,peak_retained_bytes,live_packets");
        var plan=new NativeLightingPlan(SIZE,SIZE,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.NORMAL_DISTANCE),0,6,0,NativeLightingStyle.defaults());
        try(var packets=new RtxptMemoryResources.PacketPool();
            var renderer=RtxptRenderers.openNativeLighting(RtxptRenderers.nativeLightingConfiguration(),plan)) {
            var access=renderer.extension(RtxptAccess.class).orElseThrow();
            var lighting=renderer.extension(NativeLightingControl.class).orElseThrow();
            access.diagnosticLevel(RtxptDiagnostics.Level.DEEP);
            lighting.lightingReconstruction(NativeLightingReconstruction.NONE);
            var survivor=quad(1,0,.4f);
            var neighbour=quad(2,.8f,-.8f);
            var initial=SceneTransaction.builder(1).resetScene()
                    .upsert(MaterialAsset.builder(1).doubleSided(true).build())
                    .upsert(survivor).upsert(SceneInstance.builder(11,1).build()).build();
            renderer.apply(initial);render(renderer,access,lighting,1,11);
            var field=RtxptRenderer.class.getDeclaredField("handle");field.setAccessible(true);
            long handle=field.getLong(renderer);
            var monitor=access.nativeDiagnosticMonitor();
            var mirror=new RtxptScene();mirror.retain(initial);
            var initialRetention=retention(handle);
            require(initialRetention[0]>0&&initialRetention[2]==1,"The live initial mesh did not retain exactly one real owned packet");
            require(initialRetention[1]==initialRetention[0],"The first retained-byte peak does not equal its one real packet");
            // A new one-mesh upload is the native byte-size oracle. This uses the
            // same authored layout and counts actual buffers, not packet capacity.
            var addNeighbour=SceneTransaction.builder(2).upsert(neighbour).upsert(SceneInstance.builder(22,2).build()).build();
            renderer.apply(addNeighbour);mirror.retain(addNeighbour);render(renderer,access,lighting,2,11,22);
            var single=monitor.poll();
            long singleBytes=uploadBytes(single);
            require(singleBytes>0&&uploadObjects(single)==2,"Single-mesh upload oracle must contain one actual index/vertex buffer pair");
            long neighbourPacketBytes=publicPacketBytes(access,2);
            pixels(handle,output,2,-.8f);
            exactRetention(handle,initialRetention[0]+neighbourPacketBytes,2);row(rows,2,"single-mesh-upload-oracle",single,handle);

            // Both mesh generations are encoded in ONE JNI packet. Removing its
            // front neighbour in a later entry must not upload that neighbour's
            // channel ranges, even though the survivor retains their whole owner.
            // Public Java state already knows both identities. The final authored
            // values below equal that state; the public final delta therefore
            // sends only the exact removal, without duplicating the survivor's
            // input owner or bypassing lighting-publication validation.
            var temporary=queue(handle,mirror,SceneTransaction.builder(3)
                    .upsert(quad(1,0,.1f)).upsert(quad(2,.8f,.7f)).build(),packets);
            mirror=temporary.scene();
            var pair=queue(handle,mirror,SceneTransaction.builder(3).upsert(survivor).upsert(neighbour).build(),packets);
            mirror=pair.scene();
            var removeNeighbour=SceneTransaction.builder(3).removeInstance(22).removeMesh(2).build();
            renderer.apply(removeNeighbour);mirror.retain(removeNeighbour);render(renderer,access,lighting,3,11);
            var liveOnly=monitor.poll();
            require(uploadBytes(liveOnly)==singleBytes&&uploadObjects(liveOnly)==2,
                    "A deleted same-packet neighbour reached the actual native upload: "+uploadBytes(liveOnly)+" versus "+singleBytes);
            absent(handle,"i22");pixels(handle,output,3,.4f);
            exactRetention(handle,pair.bytes(),1);row(rows,3,"same-packet-deleted-neighbour",liveOnly,handle);
            double[] identity=RtxptNative.meshResourceState(handle,"i11");

            // Distinct packets A -> B -> A in one frame must use the last raw
            // normal bytes, preserve the registered BLAS and publish fresh pixels.
            var middle=queue(handle,mirror,SceneTransaction.builder(4).upsert(quad(1,0,-.7f)).build(),packets);
            mirror=middle.scene();
            var restored=queue(handle,mirror,SceneTransaction.builder(4).upsert(survivor).build(),packets);
            mirror=restored.scene();advance(renderer,access,lighting,4,11);
            var restoreWork=monitor.poll();
            require(uploadBytes(restoreWork)==singleBytes&&uploadObjects(restoreWork)==2,
                    "Superseded B attributes were uploaded or final A was not uploaded exactly once");
            double[] afterIdentity=RtxptNative.meshResourceState(handle,"i11");
            require(identity[0]!=0&&identity[0]==afterIdentity[0],"Same-frame A/B/A attribute replacement changed the live BLAS identity");
            require(identity[1]!=afterIdentity[1],"The final attribute generation did not receive its native vertex buffer");
            pixels(handle,output,4,.4f);exactRetention(handle,restored.bytes(),1);
            row(rows,4,"same-frame-attributes-a-b-a",restoreWork,handle);

            // Position data is independently exercised: B is a tiny front quad,
            // A restores the full-sized receiver. Check off-center coverage too,
            // so final normals on B's old positions cannot satisfy the assertion.
            var geometryB=queue(handle,mirror,SceneTransaction.builder(5).upsert(quad(1,.8f,-.7f,.1f)).build(),packets);
            mirror=geometryB.scene();
            var geometryA=queue(handle,mirror,SceneTransaction.builder(5).upsert(survivor).build(),packets);
            mirror=geometryA.scene();advance(renderer,access,lighting,5,11);
            var geometryWork=monitor.poll();
            require(uploadBytes(geometryWork)==singleBytes&&uploadObjects(geometryWork)==2,
                    "Same-frame geometry B was uploaded or final A geometry was not uploaded once");
            double[] geometryIdentity=RtxptNative.meshResourceState(handle,"i11");
            require(geometryIdentity[0]!=0&&geometryIdentity[1]!=0,"Final geometry A lost its registered native identity");
            pixels(handle,output,5,.4f);exactRetention(handle,geometryA.bytes(),1);
            row(rows,5,"same-frame-positions-a-b-a",geometryWork,handle);
            advance(renderer,access,lighting,6,11);
            var settledWork=monitor.poll();
            require(uploadObjects(settledWork)==0&&uploadBytes(settledWork)==0,"A/B/A geometry left a replayed upload in the next frame");
            double[] settledIdentity=RtxptNative.meshResourceState(handle,"i11");
            require(geometryIdentity[0]==settledIdentity[0]&&geometryIdentity[1]==settledIdentity[1],"Final geometry identity did not remain stable in the next fresh frame");
            pixels(handle,output,6,.4f);exactRetention(handle,geometryA.bytes(),1);
            row(rows,6,"geometry-identity-settles-without-replay",settledWork,handle);

            // This is the LAST real mesh: removal plus a completed empty frame
            // must release the long-term source lease, not only the JNI queue.
            var deleted=SceneTransaction.builder(7).removeInstance(11).removeMesh(1).build();
            renderer.apply(deleted);mirror.retain(deleted);render(renderer,access,lighting,7);
            var deleteWork=monitor.poll();
            require(uploadObjects(deleteWork)==0&&uploadBytes(deleteWork)==0,"Deleting the last mesh uploaded geometry");
            exactRetention(handle,0,0);absent(handle,"i11");pixels(handle,output,7,Float.NaN);
            long deletedPeak=retention(handle)[1];
            require(deletedPeak>=Math.max(neighbourPacketBytes,Math.max(pair.bytes(),restored.bytes())),"Retention peak omitted an observed real retained packet");
            long totalDecodedBytes=initialRetention[0]+neighbourPacketBytes+temporary.bytes()+pair.bytes()+middle.bytes()+restored.bytes()+geometryB.bytes()+geometryA.bytes();
            require(deletedPeak<=totalDecodedBytes,"Retention peak exceeds every real decoded mesh-owner packet combined");
            row(rows,7,"last-mesh-delete-flush",deleteWork,handle);

            var resetInput=SceneTransaction.builder(8).upsert(quad(5,0,-.3f)).upsert(SceneInstance.builder(55,5).build()).build();
            renderer.apply(resetInput);mirror.retain(resetInput);render(renderer,access,lighting,8,55);
            var resetInputWork=monitor.poll();
            long resetInputBytes=publicPacketBytes(access,8);
            exactRetention(handle,resetInputBytes,1);pixels(handle,output,8,-.3f);
            row(rows,8,"retained-again-before-reset",resetInputWork,handle);
            var reset=SceneTransaction.builder(9).resetScene().build();
            renderer.apply(reset);mirror.retain(reset);render(renderer,access,lighting,9);
            var resetWork=monitor.poll();
            require(uploadObjects(resetWork)==0&&uploadBytes(resetWork)==0,"Reset uploaded retired geometry");
            exactRetention(handle,0,0);absent(handle,"i55");pixels(handle,output,9,Float.NaN);
            require(retention(handle)[1]>=deletedPeak&&retention(handle)[1]>0,"Reset erased or fabricated the lifetime retained-byte peak");
            require(retention(handle)[1]<=totalDecodedBytes+resetInputBytes,"Reset peak includes non-mesh or fabricated retained input bytes");
            row(rows,9,"scene-reset-flush",resetWork,handle);
        } finally {Files.write(output.resolve("mesh-input-lifetime-cases.csv"),rows,StandardCharsets.UTF_8);}
        System.out.println("MESH_INPUT_LIFETIME_NATIVE PASS samePacketLiveOnlyUpload=true wholeOwnerAccounting=true finalAttributesABA=true finalPositionsABA=true blasIdentity=true noPendingReplay=true deleteRelease=true resetRelease=true actualPeak=true freshPixels=true");
    }

    private static Queued queue(long handle,RtxptScene previous,SceneTransaction transaction,RtxptMemoryResources.PacketPool packets) {
        var next=new RtxptScene(previous);next.retain(transaction);
        var request=RenderFrameRequest.builder(transaction.revision(),SIZE,SIZE,CAMERA).build();
        var delta=RtxptDelta.prepare(previous,next,previous.items(request),next.items(request),Map.of(),packets);
        require(delta.hasWork()&&delta.memory()!=null,"A required native lifetime packet was elided");
        int bytes=delta.memory().remaining();
        try(var borrowed=packets.borrow(delta.memory())){RtxptNative.resourcesMemory(handle,transaction.revision(),borrowed.buffer());}
        return new Queued(next,bytes);
    }
    private static void advance(Renderer renderer,RtxptAccess access,NativeLightingControl lighting,long sequence,long... receivers) {
        renderer.apply(SceneTransaction.builder(sequence).build());render(renderer,access,lighting,sequence,receivers);
    }
    private static void render(Renderer renderer,RtxptAccess access,NativeLightingControl lighting,long sequence,long... receivers) {
        var attributes=new ArrayList<NativeLightingPublication.Instance>();
        for(long id:receivers)attributes.add(NativeLightingPublication.Instance.receiver(id));
        lighting.publishLightingAttributes(new NativeLightingPublication(sequence,attributes,List.of(),null,null));
        var receipt=renderer.submit(RenderFrameRequest.builder(sequence,SIZE,SIZE,CAMERA).minimumSceneRevision(sequence).build());
        access.awaitIdle();
        require(receipt.frameSequence()==sequence&&receipt.scheduledSceneRevision()==sequence,"Input lifetime frame receipt changed");
        try(var lease=lighting.acquireLightingFrame().orElseThrow()) {
            var frame=lease.frame();
            require(frame.sequence()==sequence&&frame.sceneRevision()==sequence&&frame.attributeGeneration()==sequence,"Input lifetime output is stale");
            require(frame.width()==SIZE&&frame.height()==SIZE&&frame.planes().size()==2
                    &&frame.planes().getFirst().memoryHandle()!=0&&frame.readyValue()<frame.completionValue(),"Input lifetime output extent or synchronization changed");
        }
    }
    private static MeshAsset quad(long id,float z,float tilt) {return quad(id,z,tilt,1);}
    private static MeshAsset quad(long id,float z,float tilt,float radius) {
        float nz=(float)Math.sqrt(1-tilt*tilt);
        return MeshAsset.builder(id,new float[]{-radius,-radius,z,radius,-radius,z,radius,radius,z,-radius,radius,z},new int[]{0,1,2,0,2,3},new long[]{1,1})
                .normals(new float[]{0,tilt,nz,0,tilt,nz,0,tilt,nz,0,tilt,nz}).build();
    }
    private static long[] retention(long handle) {
        long[] value=RtxptNative.meshInputRetentionState(handle);
        require(value.length==3&&value[0]>=0&&value[1]>=value[0]&&value[2]>=0,"Invalid owned mesh-input retention gauges");
        return value;
    }
    private static void exactRetention(long handle,long bytes,long packets) {
        var actual=retention(handle);
        require(actual[0]==bytes&&actual[2]==packets,"Owned input packets leaked, were double-counted, or were released too early: bytes="+actual[0]+"/"+bytes+" packets="+actual[2]+"/"+packets);
    }
    private static long publicPacketBytes(RtxptAccess access,long sequence) {
        var snapshot=access.diagnosticSnapshot(0);
        require(snapshot.lostEvents()==0,"Public resource-packet diagnostic events were lost");
        long bytes=snapshot.events().stream().filter(event->event.sequence()==sequence
                &&event.stage()==RtxptDiagnostics.Stage.RESOURCES&&!event.failed()).mapToLong(RtxptDiagnostics.Event::bytes).sum();
        require(bytes>0,"The public resource transaction did not submit its real input packet");
        return bytes;
    }
    private static long uploadObjects(RtxptDiagnostics.NativeSnapshot snapshot) {return upload(snapshot,false);}
    private static long uploadBytes(RtxptDiagnostics.NativeSnapshot snapshot) {return upload(snapshot,true);}
    private static long upload(RtxptDiagnostics.NativeSnapshot snapshot,boolean bytes) {
        require(snapshot.lostEvents()==0,"Input lifetime diagnostic events were lost");
        return snapshot.events().stream().filter(event->event.stage().equals("MeshBufferUpload"))
                .mapToLong(event->bytes?event.bytes():event.items()).sum();
    }
    private static void absent(long handle,String name) {
        boolean absent=false;try{RtxptNative.meshResourceState(handle,name);}catch(IllegalStateException expected){absent=true;}
        require(absent,"Deleted mesh instance remains live: "+name);
    }
    private static void pixels(long handle,Path output,long sequence,float tilt)throws Exception {
        Path path=output.resolve(sequence+"-normal.pfm");RtxptNative.nativeLightingCapture(handle,path.toString(),2);
        byte[] bytes=Files.readAllBytes(path);int offset=0;
        for(int line=0;line<3;line++)while(offset<bytes.length&&bytes[offset++]!='\n'){}
        require(new String(bytes,0,offset,StandardCharsets.US_ASCII).equals("PF\n64 64\n-1.0\n")
                &&bytes.length-offset==SIZE*SIZE*12,"Invalid input lifetime normal capture");
        float[] expected=Float.isNaN(tilt)?new float[3]:new float[]{0,tilt,(float)Math.sqrt(1-tilt*tilt)};
        var data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for(int y:new int[]{24,31,32,39})for(int x:new int[]{24,31,32,39})for(int channel=0;channel<3;channel++) {
            float actual=data.getFloat(offset+((SIZE-1-y)*SIZE+x)*12+channel*4);
            require(Float.isFinite(actual)&&Math.abs(actual-expected[channel])<.025f,"Final raw input pixels are wrong or deleted geometry remains visible");
        }
    }
    private static void row(List<String> rows,long sequence,String name,RtxptDiagnostics.NativeSnapshot snapshot,long handle) {
        var state=retention(handle);
        rows.add(sequence+","+name+","+uploadObjects(snapshot)+","+uploadBytes(snapshot)+","+state[0]+","+state[1]+","+state[2]);
        System.out.println("MESH_INPUT_LIFETIME sequence="+sequence+" case="+name+" uploadedBytes="+uploadBytes(snapshot)+" retainedBytes="+state[0]+" peakBytes="+state[1]+" packets="+state[2]+" PASS");
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
