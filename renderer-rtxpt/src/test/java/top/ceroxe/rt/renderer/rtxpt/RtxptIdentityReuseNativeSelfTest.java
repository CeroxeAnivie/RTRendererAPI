package top.ceroxe.rt.renderer.rtxpt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import top.ceroxe.rt.renderer.api.*;

/** Identity is not geometry: identical container replacements keep native resources. */
public final class RtxptIdentityReuseNativeSelfTest {
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args.length==0?"build/reports/identity-reuse-contract":args[0]).toAbsolutePath();
        Files.createDirectories(output);
        var rows=new ArrayList<String>();
        rows.add("sequence,case,mesh_upload_events,blas_build_events,tlas_items,metadata_instance,metadata_geometry");
        var camera=CameraState.lookAt(0,0,3,0,0,0).build();
        var plan=new NativeLightingPlan(64,64,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.NORMAL_DISTANCE),
                0,6,0,NativeLightingStyle.defaults());
        try(var renderer=RtxptRenderers.openNativeLighting(RtxptRenderers.nativeLightingConfiguration(),plan)){
            var access=renderer.extension(RtxptAccess.class).orElseThrow();
            var lighting=renderer.extension(NativeLightingControl.class).orElseThrow();
            access.diagnosticLevel(RtxptDiagnostics.Level.DEEP);lighting.lightingReconstruction(NativeLightingReconstruction.NONE);
            renderer.apply(SceneTransaction.builder(1).resetScene().upsert(MaterialAsset.builder(1).doubleSided(true).build())
                    .upsert(MaterialAsset.builder(2).doubleSided(true).build())
                    .upsert(mesh(1,0,0,false,false)).upsert(SceneInstance.builder(1,1).build()).build());
            var field=RtxptRenderer.class.getDeclaredField("handle");field.setAccessible(true);
            long handle=0,packedCorners=-1;byte[] expectedPixels=null;double[] resources=null;long[] retainedInput=null;
            RtxptDiagnostics.NativeMonitor monitor=null;
            for(int sequence=1;sequence<=15;sequence++){
                long id=sequence;boolean attribute=sequence==6||sequence==8;
                boolean geometry=sequence==10||sequence==12,groups=sequence==14;
                float normal=sequence<6?0:sequence<8?.2f:.4f;
                if(sequence>1)renderer.apply(SceneTransaction.builder(sequence).removeInstance(id-1).removeMesh(id-1)
                        .upsert(mesh(id,normal,sequence>=10?.25f:0,sequence>=12,sequence>=14))
                        .upsert(SceneInstance.builder(id,id).build()).build());
                lighting.publishLightingAttributes(new NativeLightingPublication(sequence,List.of(NativeLightingPublication.Instance.receiver(id)),
                        List.of(),null,null));
                renderer.submit(RenderFrameRequest.builder(sequence,64,64,camera).minimumSceneRevision(sequence).build());
                access.awaitIdle();
                try(var lease=lighting.acquireLightingFrame().orElseThrow()){
                    require(lease.frame().sequence()==sequence&&lease.frame().sceneRevision()==sequence
                                    &&lease.frame().attributeGeneration()==sequence,
                            "Stale output after identity replacement");
                }
                if(sequence==1){handle=field.getLong(renderer);monitor=access.nativeDiagnosticMonitor();}
                var nextInput=RtxptNative.meshInputRetentionState(handle);
                if(sequence>1&&!attribute&&!geometry)require(nextInput[0]<=retainedInput[0]&&nextInput[2]<=retainedInput[2],
                        "Equal geometry retained another input packet after identity replacement at sequence="+sequence);
                retainedInput=nextInput;
                long nextPackedCorners=RtxptNative.lightingMetadataWork(handle)[5];
                if(sequence>1)require(nextPackedCorners==packedCorners,"Unchanged corner attributes were repacked after identity replacement at sequence="+sequence);
                packedCorners=nextPackedCorners;
                double[] current=RtxptNative.meshResourceState(handle,"i"+id);
                double[] metadata=RtxptNative.lightingMetadataState(handle,"i"+id,"mesh"+id+"_v3f800000:0");
                require(metadata.length==17&&metadata[0]==0&&metadata[1]==0&&metadata[9]==23,
                        "Replacement identity was not mapped to its existing native metadata slots");
                var snapshot=monitor.poll();require(snapshot.lostEvents()==0,
                        "Identity reuse diagnostics were lost");
                require(snapshot.events().stream().noneMatch(e->(e.flags()&(2L|512L|1024L))!=0),
                        "Identity reuse native diagnostic scope failed or was truncated");
                long uploads=count(snapshot,"MeshBufferUpload"),builds=count(snapshot,"BlasBuild");
                long tlas=snapshot.events().stream().filter(e->e.stage().equals("TlasBuild")).mapToLong(e->e.items()).sum();
                if(sequence>1)require(count(snapshot,"TlasBuild")==1,"Missing explicit TLAS work event");
                rows.add(sequence+","+(sequence==1?"bootstrap":attribute?"normal-change-keeps-blas"
                        :groups?"material-group-change-keeps-geometry":geometry?"geometry-change-builds":"identity-only-zero-geometry-work")
                        +","+uploads+","+builds+","+tlas+","+(int)metadata[0]+","+(int)metadata[1]);
                Path image=output.resolve("identity-"+sequence+".pfm");
                RtxptNative.nativeLightingCapture(handle,image.toString(),2);byte[] pixels=Files.readAllBytes(image);
                if(geometry){
                    require(builds==1&&tlas==1&&uploads==1,"Real position/index change was skipped at sequence="+sequence);
                    if(sequence==10)require(!Arrays.equals(pixels,expectedPixels),"Real position edit did not reach GPU output");
                }else if(sequence>1){
                    require(current[0]==resources[0],"Identity/shading-only replacement rebuilt or replaced its BLAS");
                    require(builds==0&&tlas==0,"Identity/shading-only replacement did unnecessary AS work at sequence="
                            +sequence+" builds="+builds+" tlas="+tlas);
                    if(attribute)require(uploads==1&&current[1]!=resources[1],"Real normal change was lost");
                    else{require(uploads==0&&Arrays.equals(current,resources),"Identical replacement reuploaded immutable geometry");
                        require(Arrays.equals(pixels,expectedPixels),"Identical replacement changed GPU lighting output");}
                }
                resources=current;expectedPixels=pixels;
            }
            // Same public mesh identity, changed surface grouping/order/domain:
            // material edits must neither reorder triangles nor rebuild native AS.
            byte[] opaquePixels=expectedPixels;var monitorSurface=access.nativeDiagnosticMonitor();monitorSurface.poll();
            for(int sequence=16;sequence<=21;sequence++){
                var tx=SceneTransaction.builder(sequence);
                if(sequence==16||sequence==18||sequence==19||sequence==21){
                    long[] materials=sequence==19?new long[]{2,2}:sequence==21?new long[]{1,1}:new long[]{2,1};
                    tx.upsert(mesh(15,.4f,.25f,true,materials));
                }
                if(sequence==17||sequence==20)tx.upsert(MaterialAsset.builder(2).doubleSided(true)
                        .blendMode(MaterialAsset.BlendMode.MASKED).baseColorRgba8(0x00ffffff).build());
                if(sequence==18||sequence==21)tx.upsert(MaterialAsset.builder(2).doubleSided(true).build());
                renderer.apply(tx.build());
                lighting.publishLightingAttributes(new NativeLightingPublication(sequence,List.of(NativeLightingPublication.Instance.receiver(15)),List.of(),null,null));
                renderer.submit(RenderFrameRequest.builder(sequence,64,64,camera).minimumSceneRevision(sequence).build());access.awaitIdle();
                try(var lease=lighting.acquireLightingFrame().orElseThrow()){
                    require(lease.frame().sequence()==sequence&&lease.frame().sceneRevision()==sequence,"Stale material-only frame");
                }
                var snapshot=monitorSurface.poll();
                require(snapshot.lostEvents()==0&&snapshot.events().stream().noneMatch(e->(e.flags()&(2L|512L|1024L))!=0),"Material diagnostics incomplete");
                for(var event:snapshot.events())if(event.stage().equals("SceneMaterialRows")&&event.bytes()>0){
                    require(event.items()>0&&event.tasks()==event.items()&&event.bytes()>event.items(),
                            "Surface diagnostics confused material row count with uploaded bytes");
                }
                require(count(snapshot,"MeshBufferUpload")==0&&count(snapshot,"BlasBuild")==0
                                &&snapshot.events().stream().filter(e->e.stage().equals("TlasBuild")).mapToLong(e->e.items()).sum()==0,
                        "Material grouping/order/domain edit did geometry work at sequence="+sequence);
                require(Arrays.equals(resources,RtxptNative.meshResourceState(handle,"i15")),"Material edit replaced immutable geometry");
                require(RtxptNative.lightingMetadataWork(handle)[5]==packedCorners,"Surface edit reuploaded unchanged corner attributes");
                Path image=output.resolve("surface-"+sequence+".pfm");RtxptNative.nativeLightingCapture(handle,image.toString(),2);
                byte[] pixels=Files.readAllBytes(image);
                if(sequence==17||sequence==20)require(!Arrays.equals(pixels,opaquePixels),"Transparent material edit did not reach GPU candidates");
                else require(Arrays.equals(pixels,opaquePixels),"Opaque surface regrouping changed geometry pixels");
                rows.add(sequence+",surface-only-zero-geometry-work,0,0,0,0,0");
            }
            byte[] translatedPixels=null;double[] translatedPose=null;
            long resident=15;
            for(int sequence=22;sequence<=25;sequence++){
                long id=sequence;float x=sequence==22?.35f:sequence==23?-.4f:.6f;
                var pose=new AffineTransform(new float[]{1,0,0,x,0,1,0,0,0,0,1,0});
                renderer.apply(SceneTransaction.builder(sequence).removeInstance(resident).removeMesh(resident)
                        .upsert(mesh(id,.4f,.25f,true,new long[]{1,1}))
                        .upsert(SceneInstance.builder(id,id).transform(pose).build()).build());
                lighting.publishLightingAttributes(new NativeLightingPublication(sequence,List.of(NativeLightingPublication.Instance.receiver(id)),List.of(),null,null));
                renderer.submit(RenderFrameRequest.builder(sequence,64,64,camera).minimumSceneRevision(sequence).build());access.awaitIdle();
                try(var lease=lighting.acquireLightingFrame().orElseThrow()){
                    require(lease.frame().sequence()==sequence&&lease.frame().sceneRevision()==sequence,"Stale cross-position frame");
                }
                var snapshot=monitorSurface.poll();
                require(snapshot.lostEvents()==0&&snapshot.events().stream().noneMatch(e->(e.flags()&(2L|512L|1024L))!=0),"Cross-position diagnostics incomplete");
                require(count(snapshot,"MeshBufferUpload")==0&&count(snapshot,"BlasBuild")==0,
                        "Position-only new identity reuploaded or rebuilt local geometry at sequence="+sequence);
                require(Arrays.equals(resources,RtxptNative.meshResourceState(handle,"i"+id)),"Cross-position replacement discarded resident mesh/BLAS");
                translatedPose=RtxptNative.instanceTransformState(handle,"i"+id);
                require(translatedPose[3]==x,"New position was not applied");
                Path image=output.resolve("translated-"+sequence+".pfm");RtxptNative.nativeLightingCapture(handle,image.toString(),2);
                translatedPixels=Files.readAllBytes(image);
                require(!Arrays.equals(translatedPixels,opaquePixels),"Position edit did not reach native pixels");
                rows.add(sequence+",cross-position-retains-local-geometry,0,0,1,0,0");resident=id;
            }
            // A freshly built instance is the motion-history oracle. In
            // particular, a retired moving node must not leak its previous pose.
            renderer.apply(SceneTransaction.builder(26).resetScene().upsert(MaterialAsset.builder(1).doubleSided(true).build())
                    .upsert(mesh(26,.4f,.25f,true,new long[]{1,1}))
                    .upsert(SceneInstance.builder(26,26).transform(new AffineTransform(new float[]{1,0,0,.6f,0,1,0,0,0,0,1,0})).build()).build());
            lighting.publishLightingAttributes(new NativeLightingPublication(26,List.of(NativeLightingPublication.Instance.receiver(26)),List.of(),null,null));
            renderer.submit(RenderFrameRequest.builder(26,64,64,camera).minimumSceneRevision(26).build());access.awaitIdle();
            try(var lease=lighting.acquireLightingFrame().orElseThrow()){require(lease.frame().sequence()==26,"Stale fresh-node reference");}
            require(Arrays.equals(translatedPose,RtxptNative.instanceTransformState(handle,"i26")),"Cross-position replacement inherited previous motion");
            Path reference=output.resolve("translated-fresh-reference.pfm");RtxptNative.nativeLightingCapture(handle,reference.toString(),2);
            require(Arrays.equals(translatedPixels,Files.readAllBytes(reference)),"Retained geometry differs from fresh native construction");
            rows.add("26,fresh-node-motion-and-pixel-reference,1,1,1,0,0");
            var invalid=java.nio.ByteBuffer.allocateDirect(128).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            invalid.putInt(0x52445259).putInt(0).putInt(0).putInt(1);
            putString(invalid,"forged-geometry");invalid.putInt(1);putString(invalid,"missing-resident-input");
            invalid.flip();RtxptNative.resourcesMemory(handle,27,invalid);
            renderer.apply(SceneTransaction.builder(27).build());
            lighting.publishLightingAttributes(new NativeLightingPublication(27,List.of(NativeLightingPublication.Instance.receiver(26)),List.of(),null,null));
            boolean rejected=false;
            try{renderer.submit(RenderFrameRequest.builder(27,64,64,camera).minimumSceneRevision(27).build());access.awaitIdle();}
            catch(IllegalStateException expected){rejected=String.valueOf(expected.getMessage()).contains("Inherited geometry input is not resident");}
            require(rejected,"Invalid resident geometry reference was accepted or its error was hidden");
        }finally{Files.write(output.resolve("cases.csv"),rows);}
        System.out.println("IDENTITY_REUSE PASS frames=26 identityOnlyZeroUploads=true shadingKeepsBlas=true newMetadata=true exactUnchangedPixels=true positionAndIndexBuild=true materialGroupingOrderAndDomainKeepGeometry=true transparentPixels=true inputLeaseDoesNotGrow=true crossPositionKeepsGeometry=true freshNodeMotionAndPixels=true invalidGeometryReferenceRejected=true");
    }
    private static void putString(java.nio.ByteBuffer buffer,String text){
        var bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);buffer.putInt(bytes.length).put(bytes);
    }
    private static long count(RtxptDiagnostics.NativeSnapshot snapshot,String stage){
        return snapshot.events().stream().filter(e->e.stage().equals(stage)).count();
    }
    private static MeshAsset mesh(long id,float normal,float x,boolean reordered,boolean groups){
        return mesh(id,normal,x,reordered,groups?new long[]{1,2}:new long[]{1,1});
    }
    private static MeshAsset mesh(long id,float normal,float x,boolean reordered,long[] materials){
        return MeshAsset.builder(id,new float[]{-1+x,-1,0,1+x,-1,0,1+x,1,0,-1+x,1,0},
                        reordered?new int[]{0,2,3,0,1,2}:new int[]{0,1,2,0,2,3},materials)
                .normals(new float[]{normal,0,1,normal,0,1,normal,0,1,normal,0,1})
                .textureCoordinates(new float[]{0,0,1,0,1,1,0,1}).build();
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
