package top.ceroxe.rt.renderer.rtxpt;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import top.ceroxe.rt.renderer.api.*;

/** Native topology changes must keep valid capacity, current poses and live-only output. */
public final class RtxptBridgeTopologyNativeSelfTest {
    private static final int SIZE=64;

    public static void main(String[] args) throws Exception {
        Path output=Path.of(args.length==0?"build/reports/bridge-topology-contract":args[0]).toAbsolutePath();
        Files.createDirectories(output);
        var rows=new ArrayList<String>();
        rows.add("sequence,case,active_instances,geometry_groups,surface_material_groups,tlas_allocations,full_refreshes,graph_refreshes,container_reuses,native_table_fallbacks,material_imports,apply_to_fresh_ms");
        var plan=new NativeLightingPlan(SIZE,SIZE,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.NORMAL_DISTANCE),
                0,6,0,NativeLightingStyle.defaults());
        try(var renderer=RtxptRenderers.openNativeLighting(RtxptRenderers.nativeLightingConfiguration(),plan)) {
            var access=renderer.extension(RtxptAccess.class).orElseThrow();
            var lighting=renderer.extension(NativeLightingControl.class).orElseThrow();
            access.diagnosticLevel(RtxptDiagnostics.Level.DEEP);
            lighting.lightingReconstruction(NativeLightingReconstruction.NONE);
            var field=RtxptRenderer.class.getDeclaredField("handle");field.setAccessible(true);
            var camera=CameraState.lookAt(0,0,3,0,0,0).build();
            var active=new LinkedHashSet<Long>(List.of(1L,2L,3L));
            var groups=new HashMap<Long,Integer>();for(long id:active)groups.put(id,1);
            renderer.apply(SceneTransaction.builder(1).resetScene()
                    .upsert(MaterialAsset.builder(1).doubleSided(true).build())
                    .upsert(MaterialAsset.builder(2).doubleSided(true).build())
                    .upsert(MaterialAsset.builder(3).doubleSided(true).build())
                    .upsert(quad(1,-3,0,false,3,3,0)).upsert(quad(2,0,0,false,2,3,0))
                    .upsert(quad(3,3,0,false,1,1,0)).upsert(SceneInstance.builder(11,1).build())
                    .upsert(SceneInstance.builder(22,2).build()).upsert(SceneInstance.builder(33,3).build()).build());
            render(renderer,access,lighting,camera,active,1);
            long handle=field.getLong(renderer);
            var monitor=access.nativeDiagnosticMonitor();monitor.poll();
            double[] previousPose=RtxptNative.instanceTransformState(handle,"i11");
            for(int sequence=2;sequence<=29;sequence++) {
                var before=new HashMap<Long,double[]>();
                for(long id:active)before.put(id,RtxptNative.meshResourceState(handle,"i"+(id*11)));
                boolean leftWasPresent=active.contains(1L);
                var tx=SceneTransaction.builder(sequence);
                String name;
                switch(sequence) {
                    case 2,14 -> {tx.removeInstance(22);active.remove(2L);name="remove-center-retain-capacity";}
                    case 3 -> {tx.upsert(SceneInstance.builder(22,2).build());active.add(2L);name="restore-within-existing-capacity";}
                    case 4 -> {tx.upsert(quad(4,0,.8f,false,1,3,.6f)).upsert(SceneInstance.builder(44,4).build());active.add(4L);groups.put(4L,1);name="grow-instance-capacity";}
                    case 5,13 -> {tx.removeInstance(44);active.remove(4L);name="remove-front-no-ghost";}
                    case 6,11 -> {tx.upsert(quad(2,0,0,true,2,3,0));groups.put(2L,2);name="grow-surface-groups-without-native-topology";}
                    case 7 -> {tx.upsert(quad(2,0,0,false,2,3,0));groups.put(2L,1);name="shrink-surface-groups";}
                    case 8 -> {tx.removeInstance(11).removeInstance(22).removeInstance(33);active.clear();name="remove-all-no-ghost";}
                    case 9 -> {tx.upsert(SceneInstance.builder(22,2).build());active.add(2L);name="restore-after-empty";}
                    case 10 -> {tx.upsert(SceneInstance.builder(33,3).build());active.add(1L);active.add(3L);name="restore-old-maximum";}
                    case 12 -> {tx.upsert(quad(4,0,.8f,true,1,3,.6f)).upsert(SceneInstance.builder(44,4).build());active.add(4L);groups.put(4L,2);name="restore-instance-with-split-surface";}
                    case 15 -> {tx.upsert(MaterialAsset.builder(5).blendMode(MaterialAsset.BlendMode.MASKED).baseColorRgba8(0x00ffffff).doubleSided(true).build())
                            .upsert(quad(5,0,.8f,false,5,5,.6f)).upsert(SceneInstance.builder(55,5).build());active.add(5L);groups.put(5L,1);name="register-while-old-material-inactive";}
                    case 16 -> {tx.removeInstance(55).upsert(SceneInstance.builder(22,2).build());active.remove(5L);active.add(2L);name="restore-material-after-other-registration";}
                    case 17 -> {tx.removeInstance(22).removeMesh(2).removeMaterial(2);active.remove(2L);name="explicit-material-definition-retirement";}
                    case 18 -> {tx.upsert(MaterialAsset.builder(2).doubleSided(true).build()).upsert(quad(2,0,0,false,2,3,0))
                            .upsert(SceneInstance.builder(22,2).build());active.add(2L);groups.put(2L,1);name="reregister-explicitly-retired-definition";}
                    case 19,24 -> {tx.removeInstance(33).removeMesh(3).upsert(quad(6,3,0,false,1,1,0))
                            .upsert(SceneInstance.builder(66,6).build());active.remove(3L);active.add(6L);groups.put(6L,1);name="atomic-container-reuse-new-identities";}
                    case 20 -> {tx.removeInstance(66).removeMesh(6).upsert(quad(7,3,0,true,1,3,0))
                            .upsert(SceneInstance.builder(77,7).build());active.remove(6L);active.add(7L);groups.put(7L,2);name="group-layout-mismatch-native-lifecycle";}
                    case 21 -> {tx.removeInstance(77).removeMesh(7).upsert(quad(3,3,0,false,1,1,0))
                            .upsert(SceneInstance.builder(33,3).build());active.remove(7L);active.add(3L);groups.put(3L,1);name="group-shrink-native-lifecycle";}
                    case 22 -> {tx.removeInstance(33).removeMesh(3).upsert(quad(6,3,0,false,1,1,0))
                            .upsert(SceneInstance.builder(66,6).transform(new AffineTransform(new float[]{1,0,0,.5f,0,1,0,0,0,0,1,0})).build());
                            active.remove(3L);active.add(6L);groups.put(6L,1);name="new-pose-reuses-mesh-with-fresh-node";}
                    case 23 -> {tx.removeInstance(66).removeMesh(6).upsert(quad(3,3,0,false,1,1,0))
                            .upsert(SceneInstance.builder(33,3).build());active.remove(6L);active.add(3L);groups.put(3L,1);name="old-pose-reuses-mesh-with-fresh-node";}
                    case 25 -> {tx.upsert(quad(2,0,0,false,2,3,.2f)).upsert(quad(6,3,0,false,1,1,.3f));name="batch-two-attribute-generations";}
                    case 26 -> {tx.upsert(quad(2,0,0,false,2,3,.4f));name="edit-first-after-shared-upload";}
                    case 27 -> {tx.upsert(quad(6,3,0,false,1,1,.5f));name="edit-second-after-shared-upload";}
                    case 28 -> {tx.removeInstance(66).removeMesh(6).upsert(quad(8,0,.6f,false,1,1,0))
                            .upsert(SceneInstance.builder(88,8).transform(new AffineTransform(new float[]{1,0,0,.25f,0,1,0,0,0,0,1,0})).build());
                            active.remove(6L);active.add(8L);groups.put(8L,1);name="new-pose-replaces-bootstrap-compacted-blas";}
                    case 29 -> {tx.removeInstance(88).removeMesh(8)
                            .upsert(MeshAsset.builder(9,new float[]{-1,-1,.6f,1,-1,.6f,0,1,.6f},new int[]{0,1,2},new long[]{1})
                                    .normals(new float[]{0,0,1,0,0,1,0,0,1}).build())
                            .upsert(SceneInstance.builder(99,9).build());
                            active.remove(8L);active.add(9L);groups.put(9L,1);name="smaller-full-build-reuses-ordinary-capacity";}
                    default -> throw new AssertionError("Unplanned topology case");
                }
                float translation=sequence/32.f;
                if(active.contains(1L))tx.upsert(SceneInstance.builder(11,1).transform(
                        new AffineTransform(new float[]{1,0,0,translation,0,1,0,0,0,0,1,0})).build());
                long begin=System.nanoTime();renderer.apply(tx.build());render(renderer,access,lighting,camera,active,sequence);
                double elapsed=(System.nanoTime()-begin)/1_000_000.0;
                // Finite lighting keeps one canonical geometry per mesh; changing
                // triangle material groups does not grow native geometry capacity.
                long expectedAllocations=sequence==4?1:0;
                var snapshot=monitor.poll();require(snapshot.lostEvents()==0,"Topology diagnostics lost events");
                long allocations=count(snapshot,"TlasAllocate"),full=count(snapshot,"SceneFullRefresh");
                long prepared=count(snapshot,"SceneGraphRefresh"),materials=count(snapshot,"Materials");
                long reuses=snapshot.events().stream().filter(event->event.stage().equals("SceneMeshPreparation")&&(event.flags()&4)!=0).mapToLong(RtxptDiagnostics.NativeEvent::items).sum();
                long fallbacks=count(snapshot,"SceneTopologyFallback");
                boolean reused=(sequence>=19&&sequence<=24)||sequence>=28;
                require(allocations==expectedAllocations,"Native capacity was not reused at sequence="+sequence+": "+allocations);
                require(full==0,"Topology change within native table capacity repeated full RefreshBuffers at sequence="+sequence);
                require(reuses==(reused?1:0),"Atomic native container reuse did not match its exact contract at sequence="+sequence);
                if(!active.isEmpty())require(prepared==1,"Lean topology must publish the final graph exactly once at sequence="+sequence+": "+prepared);
                require(materials==0,"Independent topology entered the unused beauty baker at sequence="+sequence);
                if(sequence==22||sequence==23){
                    var pose=RtxptNative.instanceTransformState(handle,sequence==22?"i66":"i33");
                    require(Math.abs(pose[3]-(sequence==22?.5:0))<1e-6,"Container reuse inherited the retired instance pose");
                }
                for(long id:new long[]{1,3})if(before.containsKey(id)&&active.contains(id))
                    require(before.get(id)[0]==RtxptNative.meshResourceState(handle,"i"+(id*11))[0],"Topology change rebuilt a surviving unrelated BLAS");
                if(sequence>=25&&sequence<=27) {
                    for(long id:new long[]{2,6})require(before.get(id)[0]==RtxptNative.meshResourceState(handle,"i"+(id*11))[0],"Batched attribute edit rebuilt BLAS");
                    long buffers=snapshot.events().stream().filter(event->event.stage().equals("MeshBufferUpload")).mapToLong(RtxptDiagnostics.NativeEvent::items).sum();
                    require(buffers==1,"Batched edit did not write the exact dirty page: "+buffers);
                }
                if(sequence==28) {
                    // This lineage still owns the original compacted startup
                    // BLAS. It cannot be rebuilt; the first position edit must
                    // allocate ordinary native storage before later reuse.
                    require(before.get(6L)[0]!=RtxptNative.meshResourceState(handle,"i88")[0],"Position edit reused a compacted startup BLAS");
                    require(count(snapshot,"BlasAllocate")==1,"Compacted startup replacement did not allocate ordinary BLAS storage");
                    var pose=RtxptNative.instanceTransformState(handle,"i88");
                    require(Math.abs(pose[3]-.25)<1e-6,"New instance inherited the retired pose");
                }
                if(sequence==29) {
                    require(before.get(8L)[0]==RtxptNative.meshResourceState(handle,"i99")[0],"Smaller full build discarded compatible ordinary BLAS capacity");
                    require(count(snapshot,"BlasAllocate")==0,"Smaller full build allocated new BLAS storage");
                }
                checkActive(handle,active,groups);
                if(active.contains(1L)) {
                    var current=RtxptNative.instanceTransformState(handle,"i11");
                    require(Math.abs(current[3]-translation)<1e-6,"Current transform missed same-frame publication");
                    if(leftWasPresent)for(int component=0;component<12;component++)
                        require(Math.abs(current[12+component]-previousPose[component])<1e-6,"Prepared refresh consumed the previous pose early");
                    previousPose=current;
                }
                Path image=output.resolve(sequence+"-"+name+".pfm");RtxptNative.nativeLightingCapture(handle,image.toString(),2);
                checkCenter(image,active.contains(8L)||active.contains(9L)?new float[]{0,0,1}:active.contains(4L)?new float[]{0,.6f,.8f}:active.contains(2L)?sequence>=25?new float[]{0,sequence==25?.2f:.4f,(float)Math.sqrt(1-(sequence==25?.04f:.16f))}:new float[]{0,0,1}:new float[]{0,0,0});
                int geometryCount=active.size();
                int surfaceGroupCount=active.stream().mapToInt(id->groups.get(id)).sum();
                rows.add(sequence+","+name+","+active.size()+","+geometryCount+","+surfaceGroupCount+","+allocations+","+full+","+prepared+","+reuses+","+fallbacks+","+materials+","+elapsed);
                System.out.println("BRIDGE_TOPOLOGY sequence="+sequence+" case="+name+" active="+active.size()+" groups="+geometryCount+" tlas="+allocations+" PASS");
            }
        } finally {Files.write(output.resolve("topology-cases.csv"),rows,StandardCharsets.UTF_8);}
        RtxptDeferredFrameNativeSelfTest.main(new String[]{output.resolve("deferred-frame").toString()});
        System.out.println("BRIDGE_TOPOLOGY PASS frames=29 freshPixels=true liveOnly=true previousPose=true capacityReuse=true canonicalTriangleSurfaces=true materialLifetime=true containerReuse=true freshNodePose=true batchUpload=true independentBatchEdits=true ordinaryBlasShrinkReuse=true compactedBlasReplacement=true singleGraphPublication=true");
    }

    private static void render(Renderer renderer,RtxptAccess access,NativeLightingControl lighting,CameraState camera,Set<Long> active,long sequence) {
        lighting.publishLightingAttributes(new NativeLightingPublication(sequence,active.stream().map(id->NativeLightingPublication.Instance.receiver(id*11)).toList(),List.of(),null,null));
        renderer.submit(RenderFrameRequest.builder(sequence,SIZE,SIZE,camera).minimumSceneRevision(sequence).build());access.awaitIdle();
        try(var lease=lighting.acquireLightingFrame().orElseThrow()) {
            var frame=lease.frame();require(frame.sequence()==sequence&&frame.sceneRevision()==sequence&&frame.attributeGeneration()==sequence,"Topology output was not current");
            require(frame.width()==SIZE&&frame.height()==SIZE&&frame.planes().size()==2,"Topology output dimensions changed");
        }
    }

    private static void checkActive(long handle,Set<Long> active,Map<Long,Integer> groups) {
        var instances=new HashSet<Integer>();var geometries=new HashSet<Integer>();
        var materialSlots=new HashMap<Long,Integer>();var slotMaterials=new HashMap<Integer,Long>();
        for(long id=1;id<=9;id++) {
            if(!active.contains(id)) {
                boolean retired=false;try{RtxptNative.meshResourceState(handle,"i"+(id*11));}catch(IllegalStateException expected){retired=true;}
                require(retired,"Inactive instance retained a native resource identity");continue;
            }
            String geometry="mesh"+id+"_v3f800000:0";
            var state=RtxptNative.lightingMetadataState(handle,"i"+(id*11),geometry);
            require(instances.add((int)state[0]),"Active instances alias a native dense slot");
            require(geometries.add((int)state[1]),"Active canonical geometries alias a native dense slot");
            int expectedTriangles=id==9?1:2;
            require(state[3]==expectedTriangles,"Canonical triangle count was stale: mesh="+id
                    +" expected="+expectedTriangles+" actual="+state[3]);
            var meshSlots=new HashSet<Integer>();
            for(int primitive=0;primitive<expectedTriangles;primitive++) {
                long material=id==1?3:id==2?2:id==5?5:1;
                if(primitive==1&&groups.get(id)==2)material=3;
                var surface=RtxptNative.lightingSurfaceState(handle,geometry,primitive);
                int slot=(int)surface[0];meshSlots.add(slot);
                var previousSlot=materialSlots.putIfAbsent(material,slot);
                var previousMaterial=slotMaterials.putIfAbsent(slot,material);
                require(previousSlot==null||previousSlot==slot,"Same material acquired inconsistent triangle surface slots");
                require(previousMaterial==null||previousMaterial==material,"Distinct materials alias a triangle surface slot");
            }
            require(meshSlots.size()==groups.get(id),"Canonical triangle material mapping was stale: mesh="+id);
        }
        require(instances.size()==active.size(),"Active instance count differs from native identities");
        for(int index=0;index<instances.size();index++)require(instances.contains(index),"Native live instance indices contain a hole");
        for(int index=0;index<geometries.size();index++)require(geometries.contains(index),"Native live geometry indices contain a hole");
    }

    private static MeshAsset quad(long id,float x,float z,boolean split,long first,long second,float tilt) {
        float nz=(float)Math.sqrt(1-tilt*tilt);
        return MeshAsset.builder(id,new float[]{x-1,-1,z,x+1,-1,z,x+1,1,z,x-1,1,z},new int[]{0,1,2,0,2,3},new long[]{first,split?second:first})
                .normals(new float[]{0,tilt,nz,0,tilt,nz,0,tilt,nz,0,tilt,nz}).textureCoordinates(new float[]{0,0,1,0,1,1,0,1}).build();
    }

    private static void checkCenter(Path path,float[] expected) throws Exception {
        byte[] bytes=Files.readAllBytes(path);int offset=0;
        for(int line=0;line<3;line++)while(offset<bytes.length&&bytes[offset++]!='\n'){}
        require(new String(bytes,0,offset,StandardCharsets.US_ASCII).equals("PF\n64 64\n-1.0\n")&&bytes.length-offset==SIZE*SIZE*12,"Invalid topology normal capture");
        var values=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for(int y=30;y<=33;y++)for(int x=30;x<=33;x++)for(int channel=0;channel<3;channel++) {
            float actual=values.getFloat(offset+((SIZE-1-y)*SIZE+x)*12+channel*4);
            require(Float.isFinite(actual)&&Math.abs(actual-expected[channel])<.025f,"Current topology pixels differ or a retired surface remains visible");
        }
    }

    private static long count(RtxptDiagnostics.NativeSnapshot snapshot,String stage){return snapshot.events().stream().filter(event->event.stage().equals(stage)).count();}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
