package top.ceroxe.rt.renderer.rtxpt;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;

/** Layout clustering and independent edits, with final-frame command-list ownership. */
public final class RtxptMeshInputBatchingNativeSelfTest {
    private static final int SIZE=128,COUNT=64;
    private static final float SPACING=.36f,RADIUS=.14f;
    private static final CameraState CAMERA=CameraState.lookAt(0,0,4,0,0,0).aspectRatio(1).build();
    private static int layout(int index){return switch(index%8){case 1->3;case 3->5;case 5->7;default->1;};}
    private static float tilt(int index){return (index%5-2)*.15f;}
    private static long id(int index){return 1000L+index;}
    private static MeshAsset mesh(int index,float tilt){
        float x=(index%8-3.5f)*SPACING,y=(index/8-3.5f)*SPACING,z=(float)Math.sqrt(1-tilt*tilt);
        int mask=layout(index);
        var result=MeshAsset.builder(id(index),new float[]{x-RADIUS,y-RADIUS,0,x+RADIUS,y-RADIUS,0,
                x+RADIUS,y+RADIUS,0,x-RADIUS,y+RADIUS,0},new int[]{0,1,2,0,2,3},new long[]{(mask&4)==0?1:2,(mask&4)==0?1:2})
                .normals(new float[]{0,tilt,z,0,tilt,z,0,tilt,z,0,tilt,z});
        if((mask&2)!=0)result.tangents(new float[]{1,0,0,1,1,0,0,1,1,0,0,1,1,0,0,1});
        if((mask&4)!=0)result.textureCoordinates(new float[]{.75f,.75f,.75f,.75f,.75f,.75f,.75f,.75f});
        return result.build();
    }
    public static void main(String[] args)throws Exception {
        Path output=Path.of(args.length==0?"build/reports/mesh-input-batching-contract":args[0]).toAbsolutePath();Files.createDirectories(output);
        var plan=new NativeLightingPlan(SIZE,SIZE,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.NORMAL_DISTANCE),
                0,6,0,NativeLightingStyle.defaults());
        var rows=new ArrayList<String>();rows.add("sequence,case,actual_buffer_objects,actual_upload_bytes,retained_bytes,owned_segments");
        try(var renderer=RtxptRenderers.openNativeLighting(RtxptRenderers.nativeLightingConfiguration(),plan)){
            var access=renderer.extension(RtxptAccess.class).orElseThrow();var lighting=renderer.extension(NativeLightingControl.class).orElseThrow();
            access.diagnosticLevel(RtxptDiagnostics.Level.DEEP);lighting.lightingReconstruction(NativeLightingReconstruction.NONE);
            renderer.apply(SceneTransaction.builder(0).resetScene()
                    .upsert(MaterialAsset.builder(1).doubleSided(true).build()).upsert(mesh(0,tilt(0)))
                    .upsert(SceneInstance.builder(id(0),id(0)).build()).build());
            lighting.publishLightingAttributes(new NativeLightingPublication(1,
                    List.of(NativeLightingPublication.Instance.receiver(id(0))),List.of(),null,null));
            renderer.submit(RenderFrameRequest.builder(0,SIZE,SIZE,CAMERA).minimumSceneRevision(0).build());access.awaitIdle();
            try(var bootstrap=lighting.acquireLightingFrame().orElseThrow()){
                    require(bootstrap.frame().sequence()==0&&bootstrap.frame().sceneRevision()==0,"Stale batching bootstrap");
            }
            var monitor=access.nativeDiagnosticMonitor();
            byte[] alpha=new byte[16];for(int pixel=0;pixel<4;pixel++){alpha[pixel*4]=-1;alpha[pixel*4+1]=-1;alpha[pixel*4+2]=-1;}alpha[15]=-1;
            var tx=SceneTransaction.builder(1).resetScene().upsert(MaterialAsset.builder(1).doubleSided(true).build())
                    .upsert(TextureAsset.builder(101,2,2).pixelsRgba8(alpha).colorSpace(TextureAsset.ColorSpace.LINEAR)
                            .filter(TextureAsset.Filter.NEAREST).addressMode(TextureAsset.AddressMode.CLAMP_TO_EDGE).build())
                    .upsert(MaterialAsset.builder(2).doubleSided(true).baseColorTextureId(101).blendMode(MaterialAsset.BlendMode.MASKED).alphaCutoff(.5f).build());
            long ownedBytes=0,gpuBytes=0;
            for(int index=0;index<COUNT;index++){
                var mesh=mesh(index,tilt(index));tx.upsert(mesh).upsert(SceneInstance.builder(id(index),id(index)).build());
                ownedBytes+=4L*(mesh.positions().remaining()+mesh.normals().remaining()+mesh.tangents().remaining()+mesh.textureCoordinates().remaining()+mesh.triangleIndices().remaining());
                gpuBytes+=4L*(12+4+((layout(index)&2)!=0?4:0)+((layout(index)&4)!=0?8:0))+6*4;
            }
            renderer.apply(tx.build());render(renderer,access,lighting,1,false);
            var field=RtxptRenderer.class.getDeclaredField("handle");field.setAccessible(true);long handle=field.getLong(renderer);
            var first=monitor.poll();
            Files.writeString(output.resolve("initial-work.txt"),first.toString(),StandardCharsets.UTF_8);
            require(work(first,"MeshBufferUpload",false)==2,"Five layout batches must occupy distinct slices of one vertex/index page pair");
            require(work(first,"BufferAllocate",false)==0&&work(first,"DescriptorAllocate",false)==0,
                    "Small batches did not append to the existing bootstrap pages/descriptors");
            require(work(first,"MeshBufferUpload",true)==gpuBytes,"Layout clustering changed actual channel/index upload sizes");
            require(work(first,"BlasBuild",false)==COUNT,"Layout clustering dropped actual mesh BLAS builds");
            retention(handle,ownedBytes,COUNT);pixels(handle,output,1,false,false);
            var states=new double[COUNT][];var buffers=new HashMap<Integer,Set<Double>>();
            for(int index=0;index<COUNT;index++){states[index]=RtxptNative.meshResourceState(handle,"i"+id(index));
                require(states[index][0]!=0&&states[index][1]!=0,"Missing actual native mesh resources");
                buffers.computeIfAbsent(layout(index),key->new HashSet<>()).add(states[index][1]);}
            require(buffers.get(1).size()==1&&buffers.get(3).size()==1&&buffers.get(5).size()==1&&buffers.get(7).size()==1,
                    "Small batches did not share their native vertex page");
            var allBuffers=new HashSet<Double>();for(var group:buffers.values())allBuffers.addAll(group);
            require(allBuffers.size()==1,"Different input layouts failed to share disjoint page ranges");
            var ranges=new long[COUNT][];
            for(int index=0;index<COUNT;index++){
                ranges[index]=RtxptNative.meshStorageRanges(handle,"i"+id(index));
                require(ranges[index][1]-ranges[index][0]==48&&ranges[index][3]-ranges[index][2]==24,
                        "Page slice no longer matches its exact authored vertex/index counts");
                for(int other=0;other<index;other++)require(
                        (ranges[index][1]<=ranges[other][0]||ranges[other][1]<=ranges[index][0])
                        &&(ranges[index][3]<=ranges[other][2]||ranges[other][3]<=ranges[index][2]),
                        "Live meshes overlap page position/index ranges");
            }
            row(rows,1,"alternating-layouts-native-five-batches",first,handle);
            long[] commands=RtxptNative.commandWorkState(handle);
            renderer.apply(SceneTransaction.builder(2).build());render(renderer,access,lighting,2,false);
            var idle=monitor.poll();commandDelta(commands,RtxptNative.commandWorkState(handle),0);
            require(work(idle,"MeshBufferUpload",false)==0&&work(idle,"BlasBuild",false)==0,"Idle clustered inputs replayed geometry work");
            tlasReused(idle);pixels(handle,output,2,false,false);row(rows,2,"idle-keeps-buffer-and-tlas",idle,handle);
            commands=RtxptNative.commandWorkState(handle);
            var detach=SceneTransaction.builder(3);for(int index=0;index<COUNT;index+=2)detach.removeInstance(id(index));
            renderer.apply(detach.build());render(renderer,access,lighting,3,true);var removed=monitor.poll();
            commandDelta(commands,RtxptNative.commandWorkState(handle),0);
            require(work(removed,"MeshBufferUpload",false)==0&&work(removed,"BlasBuild",false)==0,"Removing shared-buffer users uploaded/rebuilt resident geometry");
            retention(handle,ownedBytes,COUNT);pixels(handle,output,3,true,false);row(rows,3,"remove-users-retains-input-definitions",removed,handle);
            commands=RtxptNative.commandWorkState(handle);
            renderer.apply(SceneTransaction.builder(4).upsert(mesh(1,.45f)).build());render(renderer,access,lighting,4,true);var edited=monitor.poll();
            commandDelta(commands,RtxptNative.commandWorkState(handle),0);
            require(work(edited,"MeshBufferUpload",false)==1&&work(edited,"MeshBufferUpload",true)==16
                    &&work(edited,"BlasBuild",false)==0,"Shading edit must upload only its16-byte normal range");
            require(work(edited,"BufferAllocate",false)==0&&work(edited,"DescriptorAllocate",false)==0,
                    "Normal-only edit allocated replacement geometry storage or descriptors");
            var editState=RtxptNative.meshResourceState(handle,"i"+id(1));
            require(editState[0]==states[1][0]&&editState[1]==states[1][1],"Shading edit must retain BLAS and native storage position");
            require(Arrays.equals(ranges[1],RtxptNative.meshStorageRanges(handle,"i"+id(1))),
                    "Normal-only edit moved the resident vertex/index ranges");
            for(int index=3;index<COUNT;index+=2)require(Arrays.equals(states[index],RtxptNative.meshResourceState(handle,"i"+id(index))),
                    "A neighbour lost native buffer/BLAS identity after one shared-batch mesh changed");
            retention(handle,ownedBytes,COUNT);pixels(handle,output,4,true,true);tlasReused(edited);row(rows,4,"single-shading-edit-preserves-neighbours",edited,handle);
            commands=RtxptNative.commandWorkState(handle);var release=SceneTransaction.builder(5);
            for(int index=0;index<COUNT;index++){if(index%2!=0)release.removeInstance(id(index));release.removeMesh(id(index));}
            release.removeMaterials(1,2).removeTexture(101);renderer.apply(release.build());render(renderer,access,lighting,5,true);var last=monitor.poll();
            commandDelta(commands,RtxptNative.commandWorkState(handle),1);retention(handle,0,0);pixelsEmpty(handle,output);row(rows,5,"explicit-final-definition-owner-release",last,handle);
        }finally{Files.write(output.resolve("mesh-input-batching.csv"),rows,StandardCharsets.UTF_8);}
        System.out.println("MESH_INPUT_BATCHING_NATIVE PASS meshes=64 layouts=4 nativeBufferObjects=2 exactUploadBytes=true freshPixels=true uvCoverage=true stableGeometryRanges=true dirtyNormalBytes=16 geometryAllocations=0 ownerLifecycle=true lazyPreparationLists=true");
    }
    private static void render(Renderer renderer,RtxptAccess access,NativeLightingControl lighting,long sequence,boolean oddOnly){
        var receivers=new ArrayList<NativeLightingPublication.Instance>();
        if(sequence!=5)for(int index=0;index<COUNT;index++)if(!oddOnly||index%2!=0)receivers.add(NativeLightingPublication.Instance.receiver(id(index)));
        lighting.publishLightingAttributes(new NativeLightingPublication(sequence+1,receivers,List.of(),null,null));
        renderer.submit(RenderFrameRequest.builder(sequence,SIZE,SIZE,CAMERA).minimumSceneRevision(sequence).build());access.awaitIdle();
        try(var lease=lighting.acquireLightingFrame().orElseThrow()){var frame=lease.frame();
            require(frame.sequence()==sequence&&frame.sceneRevision()==sequence&&frame.attributeGeneration()==sequence+1,"Stale batching output");}
    }
    private static void commandDelta(long[] before,long[] after,long preparations){
        // Managed direct preparation records into the final frame's own list.
        // Topology edits share it; the empty-scene lifecycle retains one list.
        require(before.length==10&&after.length==10&&after[0]==before[0]&&after[2]==before[2]
                &&after[1]-before[1]==preparations&&after[3]==before[3],"Geometry/topology packet opened extra resource or structure command lists");
    }
    private static void tlasReused(RtxptDiagnostics.NativeSnapshot snapshot){
        var events=snapshot.events().stream().filter(event->event.stage().equals("TlasBuild")).toList();
        require(events.size()==1&&events.getFirst().items()==0&&(events.getFirst().flags()&4)!=0,"Unchanged BLAS/poses rebuilt native TLAS");
    }
    private static void retention(long handle,long bytes,long owners){var state=RtxptNative.meshInputRetentionState(handle);
        require(state.length==3&&state[0]==bytes&&state[2]==owners,"Canonical input ownership changed with upload layout clustering");}
    private static long work(RtxptDiagnostics.NativeSnapshot snapshot,String stage,boolean bytes){
        require(snapshot.lostEvents()==0,"Batching native diagnostics lost events");
        return snapshot.events().stream().filter(event->event.stage().equals(stage)).mapToLong(event->bytes?event.bytes():event.items()).sum();
    }
    private static ByteBuffer capture(long handle,Path output,String name)throws Exception {
        Path path=output.resolve(name+".pfm");RtxptNative.nativeLightingCapture(handle,path.toString(),2);byte[] bytes=Files.readAllBytes(path);int offset=0;
        for(int line=0;line<3;line++)while(offset<bytes.length&&bytes[offset++]!='\n'){}
        require(new String(bytes,0,offset,StandardCharsets.US_ASCII).equals("PF\n128 128\n-1.0\n")&&bytes.length-offset==SIZE*SIZE*12,"Missing fresh batching normal image");
        return ByteBuffer.wrap(bytes,offset,bytes.length-offset).slice().order(ByteOrder.LITTLE_ENDIAN);
    }
    private static void pixels(long handle,Path output,long sequence,boolean oddOnly,boolean edited)throws Exception {
        var pixels=capture(handle,output,"frame-"+sequence);double half=4*CAMERA.tanHalfFovY();
        for(int index=0;index<COUNT;index++){
            int x=(int)Math.round((.5+(index%8-3.5)*SPACING/(2*half))*SIZE-.5);
            int y=(int)Math.round((.5-(index/8-3.5)*SPACING/(2*half))*SIZE-.5);
            float tilt=edited&&index==1?.45f:tilt(index);boolean live=!oddOnly||index%2!=0;
            float[] expected=live?new float[]{0,tilt,(float)Math.sqrt(1-tilt*tilt)}:new float[3];
            for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++)for(int channel=0;channel<3;channel++){
                float actual=pixels.getFloat(((SIZE-1-y-dy)*SIZE+x+dx)*12+channel*4);
                require(Float.isFinite(actual)&&Math.abs(actual-expected[channel])<.025f,"Wrong layout/index/normal/UV pixel mesh="+index+" frame="+sequence);}
        }
    }
    private static void pixelsEmpty(long handle,Path output)throws Exception {var pixels=capture(handle,output,"frame-5-empty");
        while(pixels.hasRemaining())require(pixels.getFloat()==0,"Explicitly removed batching geometry remains visible");}
    private static void row(List<String> rows,long sequence,String name,RtxptDiagnostics.NativeSnapshot snapshot,long handle){var state=RtxptNative.meshInputRetentionState(handle);
        rows.add(sequence+","+name+","+work(snapshot,"MeshBufferUpload",false)+","+work(snapshot,"MeshBufferUpload",true)+","+state[0]+","+state[2]);}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
