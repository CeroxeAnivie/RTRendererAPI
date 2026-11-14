package top.ceroxe.rt.renderer.rtxpt;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import top.ceroxe.rt.renderer.api.*;

/** Consolidated streaming contracts. Timings are observations, not a host FPS benchmark. */
public final class RtxptBridgeMigrationNativeSelfTest {
    private static final int SIZE=64,FRAMES=40;
    private static final long TEXTURE=7;
    private static final NativeLightingPublication.Instance CENTER=
            new NativeLightingPublication.Instance(22,23,.8f,0,.3f,.7f,.1f,.2f);

    public static void main(String[] args) throws Exception {
        Path output=Path.of(args.length==0?"build/reports/bridge-migration-contract":args[0]).toAbsolutePath();
        Files.createDirectories(output);
        if(args.length>1&&args[1].equals("--deformation-only")) {checkDeformationContract(output);return;}
        if(args.length>1&&args[1].equals("--packed-light-only")) {checkPackedLightContract(output);return;}
        if(args.length>1&&args[1].equals("--primitive-history-only")) {checkPrimitiveHistoryContract(output);return;}
        if(args.length>1&&args[1].equals("--tlas-capacity-only")) {checkTlasCapacityContract(output);return;}
        var rows=new ArrayList<String>();
        rows.add("sequence,case,apply_to_fresh_ms,materials_events,blas_events,attribute_events,native_events");
        var plan=new NativeLightingPlan(SIZE,SIZE,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.NORMAL_DISTANCE),
                0,6,0,NativeLightingStyle.defaults());
        try(var renderer=RtxptRenderers.openNativeLighting(RtxptRenderers.nativeLightingConfiguration(),plan)) {
            var access=renderer.extension(RtxptAccess.class).orElseThrow();
            var lighting=renderer.extension(NativeLightingControl.class).orElseThrow();
            access.diagnosticLevel(RtxptDiagnostics.Level.DEEP);
            lighting.lightingReconstruction(NativeLightingReconstruction.NONE);
            var handleField=RtxptRenderer.class.getDeclaredField("handle");handleField.setAccessible(true);
            var camera=CameraState.lookAt(0,0,3,0,0,0).build();
            TextureAsset texture=texture(opaquePixels(),false);MaterialAsset masked=masked(255);
            renderer.apply(SceneTransaction.builder(1).resetScene().upsert(texture)
                    .upsert(MaterialAsset.builder(1).doubleSided(true).build()).upsert(masked)
                    .upsert(mesh(1,-3,0,0)).upsert(mesh(2,0,0,0)).upsert(mesh(3,3,0,0))
                    .upsert(SceneInstance.builder(11,1).build()).upsert(SceneInstance.builder(22,2).build())
                    .upsert(SceneInstance.builder(33,3).build()).build());
            publish(lighting,1);
            renderer.submit(RenderFrameRequest.builder(1,SIZE,SIZE,camera).minimumSceneRevision(1).build());
            access.awaitIdle();
            long handle=handleField.getLong(renderer);
            try(var initial=lighting.acquireLightingFrame().orElseThrow()) {checkFrame(initial.frame(),1);}
            long[] initialWork=RtxptNative.commandWorkState(handle);
            require(initialWork.length==10&&initialWork[7]>0&&initialWork[8]==0,
                    "Independent lighting bootstrap entered complete beauty initialization");
            System.out.println("BRIDGE_MIGRATION bootstrap PASS lean="+initialWork[7]+" complete="+initialWork[8]);
            var monitor=access.nativeDiagnosticMonitor();var independent=access.nativeDiagnosticMonitor();
            var javaMonitor=access.diagnosticMonitor();var javaIndependent=access.diagnosticMonitor();
            boolean thirdPresent=true,sawRealTexture=false,sawAttributes=false,sawTopology=false;
            LightingFrameLease retained=null;
            try {
                for(int sequence=2;sequence<=FRAMES;sequence++) {
                    String name;boolean noOp=false,attributeOnly=false,topology=false;
                    var tx=SceneTransaction.builder(sequence);
                    TextureAsset previousTexture=texture;
                    double[] before=RtxptNative.meshResourceState(handle,"i22");
                    long[] workBefore=RtxptNative.resourceWorkState(handle);
                    long geometryChecksBefore=sequence==9?RtxptNative.lightingMetadataWork(handle)[2]:0;
                    if(sequence==2) {
                        name="full-generation-no-op";texture=copy(texture);noOp=true;
                    } else if(sequence==3) {
                        name="rectangle-no-op-new-parent";texture=patchCorner(texture,false);noOp=true;
                    } else if(sequence==4) {
                        name="rectangle-change";texture=patchCorner(texture,true);sawRealTexture=true;
                    } else if(sequence==5) {
                        name="sampler-only";texture=texture(pixels(texture),true);
                    } else if(sequence==6) {
                        name="texture-and-attributes";texture=patchCorner(texture,false);
                        tx.upsert(mesh(2,0,0,.2f));attributeOnly=true;
                    } else if(sequence==7) {
                        name="texture-no-op-and-topology";texture=copy(texture);
                        tx.upsert(mesh(2,0,.05f,.2f));topology=true;
                    } else if(sequence==8) {
                        name="texture-and-topology";texture=patchCorner(texture,true);
                        tx.upsert(mesh(2,0,.1f,.2f));topology=true;
                    } else if(sequence==9) {
                        name="remove-and-dense-reindex";texture=copy(texture);
                        tx.removeInstance(11).removeMesh(1);topology=true;
                    } else if(sequence==10) {
                        name="replace-identity-after-reindex";texture=patchCorner(texture,false);
                        tx.upsert(mesh(1,-3,0,0)).upsert(SceneInstance.builder(11,1).build());topology=true;
                    } else if(sequence==11) {
                        name="masked-scalar-below-cutoff";texture=texture(opaquePixels(),true);
                        masked=masked(64);tx.upsert(masked);
                    } else if(sequence==12) {
                        name="masked-scalar-above-cutoff";texture=copy(texture);
                        masked=masked(192);tx.upsert(masked);
                    } else if(sequence==13) {
                        name="restore-scalar-and-sampler";texture=texture(opaquePixels(),false);
                        masked=masked(255);tx.upsert(masked);
                    } else if(sequence==14) {
                        name="attribute-only-unchanged-texture";texture=copy(texture);
                        tx.upsert(mesh(2,0,.1f,.3f));attributeOnly=true;
                    } else if(sequence>=15&&sequence<=34) {
                        int phase=(sequence-15)%4;
                        if(phase==0) {
                            name="stream-patch-and-attributes";texture=patchCorner(texture,(sequence&4)==0);
                            tx.upsert(mesh(2,0,.1f,((sequence-15)/4)%2==0?.2f:.3f));attributeOnly=true;
                        } else if(phase==1) {
                            name="stream-sampler";texture=texture(pixels(texture),texture.filter()==TextureAsset.Filter.NEAREST);
                        } else if(phase==2) {
                            name="stream-remove-reinsert";texture=copy(texture);topology=true;
                            if(thirdPresent)tx.removeInstance(33).removeMesh(3);
                            else tx.upsert(mesh(3,3,0,0)).upsert(SceneInstance.builder(33,3).build());
                            thirdPresent=!thirdPresent;
                        } else {name="stream-full-no-op";texture=copy(texture);noOp=true;}
                    } else if(sequence==35) {
                        name="sampler-repeat-nearest-pixels";byte[] texels=opaquePixels();texels[3]=0;
                        texture=texture(texels,false);tx.upsert(samplerMesh());attributeOnly=true;
                    } else if(sequence==36) {
                        name="sampler-clamp-linear-pixels";texture=texture(pixels(texture),true);
                    } else {name="diagnostic-off-no-op";texture=copy(texture);noOp=true;}
                    tx.upsert(texture);
                    if(sequence==37) {
                        monitor.poll();independent.poll();javaMonitor.poll();javaIndependent.poll();
                        access.diagnosticLevel(RtxptDiagnostics.Level.OFF);
                        monitor.poll();independent.poll();javaMonitor.poll();javaIndependent.poll();
                    }
                    long begin=System.nanoTime();renderer.apply(tx.build());publish(lighting,sequence);
                    renderer.submit(RenderFrameRequest.builder(sequence,SIZE,SIZE,camera).minimumSceneRevision(sequence).build());
                    access.awaitIdle();
                    var lease=lighting.acquireLightingFrame().orElseThrow();checkFrame(lease.frame(),sequence);
                    double elapsed=(System.nanoTime()-begin)/1_000_000.0;
                    if(sequence==20)retained=lease;
                    else {
                        if(retained!=null)require(retained.frame().planes().getFirst().memoryHandle()!=lease.frame().planes().getFirst().memoryHandle(),
                                "A live output lease was overwritten at sequence="+sequence);
                        lease.close();
                    }
                    if(sequence==23&&retained!=null) {checkFrame(retained.frame(),20);retained.close();retained=null;}
                    double[] after=RtxptNative.meshResourceState(handle,"i22");
                    long[] workAfter=RtxptNative.resourceWorkState(handle);
                    require(workBefore.length==9&&workAfter.length==9,"Unexpected resource work counters");
                    require(workAfter[7]==workBefore[7],"Texture/scalar update redundantly revalidated resident mesh tangents at sequence="+sequence);
                    require(workAfter[8]>workBefore[8],"Expected resident mesh validation skip was not recorded at sequence="+sequence);
                    boolean samePixels=Arrays.equals(pixels(previousTexture),pixels(texture));
                    boolean samplerOnly=samePixels&&(texture.filter()!=previousTexture.filter()
                            ||texture.addressU()!=previousTexture.addressU()||texture.addressV()!=previousTexture.addressV());
                    if(noOp||samplerOnly) {
                        require(workAfter[0]==workBefore[0]&&workAfter[1]==workBefore[1]
                                &&workAfter[2]==workBefore[2]&&workAfter[3]==workBefore[3]&&workAfter[4]==workBefore[4],
                                "No-op/sampler-only publication uploaded texture bytes at sequence="+sequence);
                        require(workAfter[samplerOnly?6:5]>workBefore[samplerOnly?6:5],
                                "Expected generation/sampler no-upload path was not recorded at sequence="+sequence);
                    }
                    if(texture.sourcePatch().isPresent()&&!samePixels) {
                        var patch=texture.sourcePatch().orElseThrow();
                        long bytes=Math.multiplyExact(Math.multiplyExact((long)patch.width(),patch.height()),4);
                        require(workAfter[0]==workBefore[0]&&workAfter[1]==workBefore[1]
                                &&workAfter[2]-workBefore[2]==1&&workAfter[3]-workBefore[3]==bytes,
                                "Rectangle publication was not a bounded rectangle upload at sequence="+sequence);
                    }
                    if(attributeOnly) {
                        require(before[0]==after[0],"Attribute/texture update rebuilt BLAS at sequence="+sequence);
                        require(before[1]==after[1],"Compatible mixed texture/attribute update discarded stable vertex storage at sequence="+sequence);
                    } else if(!topology&&sequence!=11&&sequence!=12&&sequence!=13)
                        require(Arrays.equals(before,after),"Texture-only publication rebuilt geometry at sequence="+sequence+" before="+Arrays.toString(before)+" after="+Arrays.toString(after));
                    var snapshot=monitor.poll();var second=independent.poll();
                    require(snapshot.lostEvents()==0&&second.lostEvents()==0,"Native diagnostics lost events");
                    require(snapshot.events().equals(second.events()),"Independent native cursors diverged");
                    var javaSnapshot=javaMonitor.poll();var javaSecond=javaIndependent.poll();
                    require(javaSnapshot.lostEvents()==0&&javaSecond.lostEvents()==0,"Java diagnostics lost events");
                    require(javaSnapshot.events().equals(javaSecond.events()),"Independent Java cursors diverged");
                    long materials=count(snapshot,"Materials"),blas=count(snapshot,"BlasBuild"),attributes=count(snapshot,"AttributePreparation");
                    if(sequence<37) {
                        require(materials==0,
                                "Texture or geometry delta caused full material import at sequence="+sequence);
                        if(!topology&&sequence!=11&&sequence!=12&&sequence!=13)require(count(snapshot,"SceneFullRefresh")==0,
                                "Texture/attribute update caused an extra full scene refresh at sequence="+sequence);
                        if(noOp)require(blas==0&&count(snapshot,"SceneFullRefresh")==0,
                                "No-op texture generation caused global resource work at sequence="+sequence);
                        if(attributeOnly)require(attributes>0,"Mixed update omitted original attribute uploader");
                        if(sequence==6||sequence==14){
                            var tlasEvents=snapshot.events().stream().filter(event->event.stage().equals("TlasBuild")).toList();
                            require(tlasEvents.size()==1&&tlasEvents.getFirst().items()==0,
                                    "Attribute-only publication dirtied unchanged instance poses/TLAS or omitted its work event at sequence="+sequence);
                        }
                        sawAttributes|=attributes>0;sawTopology|=blas>0;
                    } else {
                        require(snapshot.events().isEmpty()&&javaSnapshot.events().isEmpty(),"OFF continued collecting diagnostics");
                        require(javaSnapshot.level()==RtxptDiagnostics.Level.OFF,"Diagnostic OFF level was not visible");
                    }
                    checkMetadata(handle,texture,masked,thirdPresent,sequence!=9);
                    if(sequence==9)require(RtxptNative.lightingMetadataWork(handle)[2]==geometryChecksBefore,
                            "Removing a neighbor rechecked surviving receiver material and triangle metadata");
                    if(sequence==11||sequence==12||sequence==35||sequence==36) {
                        Path file=output.resolve(name+".pfm");RtxptNative.nativeLightingCapture(handle,file.toString(),2);
                        checkCenterGeometry(file,sequence==12||sequence==36);
                    }
                    rows.add(sequence+","+name+","+elapsed+","+materials+","+blas+","+attributes+","+snapshot.events().size());
                    System.out.println("BRIDGE_MIGRATION sequence="+sequence+" case="+name+" PASS");
                }
            } finally {if(retained!=null)retained.close();}
            checkPoseFastPath(renderer,access,lighting,camera,handle,rows);
            checkMaterialRegistry(renderer,access,lighting,camera,handle,output,rows);
            require(sawRealTexture&&sawAttributes&&sawTopology,"Required mixed resource paths were not observed");
            require(lighting.nativeLightingExecution().fullBeautyFrames()==0,"Direct-only contract executed full beauty");
        } finally {Files.write(output.resolve("cases.csv"),rows,StandardCharsets.UTF_8);}
        System.out.println("BRIDGE_MIGRATION PASS frames="+FRAMES+" noOpGenerations=true rectangleParents=true samplerMetadata=true samplerPixels=true mixedAttributes=true mixedTopology=true denseReindex=true maskedScalarPixels=true independentMonitors=true diagnosticOff=true retainedLease=true poseFastPathFrames=6 settledInstanceReads=0 cameraInstanceReads=0 materialRegistryFrames=3");
    }

    /** Eleven fresh frames: unrelated publications cannot turn geometry into topology. */
    private static void checkDeformationContract(Path output) throws Exception {
        var rows=new ArrayList<String>();
        rows.add("sequence,case,apply_to_fresh_ms,materials_events,blas_events,attribute_events,native_events");
        var plan=new NativeLightingPlan(SIZE,SIZE,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.NORMAL_DISTANCE),
                0,6,0,NativeLightingStyle.defaults());
        try(var renderer=RtxptRenderers.openNativeLighting(RtxptRenderers.nativeLightingConfiguration(),plan)) {
            var access=renderer.extension(RtxptAccess.class).orElseThrow();
            var lighting=renderer.extension(NativeLightingControl.class).orElseThrow();
            access.diagnosticLevel(RtxptDiagnostics.Level.DEEP);
            var handleField=RtxptRenderer.class.getDeclaredField("handle");handleField.setAccessible(true);
            var camera=CameraState.lookAt(0,0,3,0,0,0).build();
            renderer.apply(SceneTransaction.builder(1).resetScene()
                    .upsert(texture(opaquePixels(),false))
                    .upsert(MaterialAsset.builder(2).baseColorTextureId(TEXTURE).doubleSided(true).build())
                    .upsert(deformationMesh(0,false)).upsert(SceneInstance.builder(22,2).build()).build());
            publish(lighting,1);
            renderer.submit(RenderFrameRequest.builder(1,SIZE,SIZE,camera).minimumSceneRevision(1).build());
            access.awaitIdle();
            long handle=handleField.getLong(renderer);
            try(var lease=lighting.acquireLightingFrame().orElseThrow()){checkFrame(lease.frame(),1);}
            Path initial=output.resolve("deformation-bootstrap.pfm");
            RtxptNative.nativeLightingCapture(handle,initial.toString(),2);checkCenterGeometry(initial,true);
            var monitor=access.nativeDiagnosticMonitor();monitor.poll();
            double[] previous=RtxptNative.meshResourceState(handle,"i22");
            long[] previousRanges=RtxptNative.meshStorageRanges(handle,"i22");
            double[] stableMetadata=RtxptNative.lightingMetadataState(handle,"i22",meshName(2)+":0");
            double previousPolicy=RtxptNative.lightingSurfaceState(handle,meshName(2)+":0",0)[1];
            for(int sequence=2;sequence<=11;sequence++) {
                boolean reshaped=sequence==4||sequence==5||sequence==7;
                boolean mixedSampler=sequence==8||sequence==9;
                boolean subdivided=sequence==5||sequence==6,away=sequence==2||sequence==5||sequence==8||sequence==10;
                long[] commandsBefore=RtxptNative.commandWorkState(handle);
                long[] metadataBefore=RtxptNative.lightingMetadataWork(handle);
                long begin=System.nanoTime();
                var update=SceneTransaction.builder(sequence).upsert(subdivided
                        ?subdividedMesh(away?3:0):deformationMesh(away?3:0,sequence==4));
                if(mixedSampler)update.upsert(texture(opaquePixels(),sequence==8));
                if(sequence>=10)update.upsert(MaterialAsset.builder(999).roughness(sequence==10?.3f:.7f).build());
                renderer.apply(update.build());
                publish(lighting,sequence);
                renderer.submit(RenderFrameRequest.builder(sequence,SIZE,SIZE,camera).minimumSceneRevision(sequence).build());
                access.awaitIdle();
                try(var lease=lighting.acquireLightingFrame().orElseThrow()){checkFrame(lease.frame(),sequence);}
                double elapsed=(System.nanoTime()-begin)/1_000_000.0;
                double[] current=RtxptNative.meshResourceState(handle,"i22");
                long[] currentRanges=RtxptNative.meshStorageRanges(handle,"i22");
                if(sequence==5||sequence==7)require(previous[1]!=current[1]
                        ||currentRanges[1]<=previousRanges[0]||previousRanges[1]<=currentRanges[0],
                        "Vertex-count change reused the previous occupied vertex slice");
                else require(Arrays.equals(previousRanges,currentRanges),"Compatible edit moved its vertex/index ranges");
                previousRanges=currentRanges;
                var snapshot=monitor.poll();require(snapshot.lostEvents()==0,"Deformation diagnostics lost events");
                long full=count(snapshot,"SceneFullRefresh"),tlas=count(snapshot,"TlasAllocate");
                long blas=count(snapshot,"BlasBuild"),attributes=count(snapshot,"AttributePreparation"),materials=count(snapshot,"Materials");
                require(materials==0&&blas>0,"Geometry edit lost its native BLAS build or reimported materials");
                require(full==0&&tlas==0&&attributes>0,"Stable graph edit rebuilt the whole native scene/TLAS");
                require(current[1]!=0&&(sequence==5||sequence==7||previous[1]==current[1]),
                        "Compatible geometry edit changed its native storage page at sequence="+sequence);
                if(sequence==3||sequence==6||mixedSampler||sequence>=10)
                    require(previous[0]==current[0],"Same-layout deformation failed to reuse the ordinary native BLAS");
                // DXR can report the same storage requirement for two and four
                // triangles. The grow case checks the real build, new metadata
                // and pixels; reuse is required for reorder and shrink.
                if(reshaped&&sequence!=5)require(previous[0]==current[0],
                        "Compatible full rebuild replaced its ordinary native capacity");
                double[] metadata=RtxptNative.lightingMetadataState(handle,"i22",meshName(2)+":0");
                require(metadata[0]==stableMetadata[0]&&metadata[1]==stableMetadata[1],
                        "Stable mesh/instance changed its original dense graph indices");
                require(metadata[3]==(subdivided?4:2),"Geometry resize left stale triangle-count metadata");
                long[] metadataAfter=RtxptNative.lightingMetadataWork(handle);
                require(metadataAfter.length==11&&metadataAfter[0]==metadataBefore[0],
                        "Stable geometry edit rebuilt its cached attribute identity keys");
                if(sequence==5||sequence==7)require(metadataAfter[2]>metadataBefore[2],
                        "Triangle count change did not reconcile receiver geometry");
                else if(!mixedSampler)require(metadataAfter[2]==metadataBefore[2],
                        "An edit with unchanged receiver metadata still checked geometry at sequence="+sequence);
                if(mixedSampler) {
                    require(RtxptNative.lightingSurfaceState(handle,meshName(2)+":0",0)[1]!=previousPolicy,
                            "Mixed sampler/deformation lost its real sampling-policy update");
                } else require(metadataAfter[1]==metadataBefore[1],
                            "Geometry-only edit needlessly re-read unchanged material sampling policies");
                previousPolicy=RtxptNative.lightingSurfaceState(handle,meshName(2)+":0",0)[1];
                long[] after=RtxptNative.commandWorkState(handle);
                require(after[7]-commandsBefore[7]==1&&after[8]==commandsBefore[8],"Geometry edit did not execute exactly one lean frame");
                String name=sequence==4?"geometry-index-reorder":sequence==5?"geometry-grow-away":
                        sequence==6?"geometry-grown-back":sequence==7?"geometry-shrink-back":
                        sequence==8?"sampler-deformation-away":sequence==9?"sampler-deformation-back":
                        sequence==10?"unused-material-deformation-away":sequence==11?"unused-material-deformation-back":away?"deformation-away":"deformation-back";
                Path image=output.resolve(name+".pfm");
                RtxptNative.nativeLightingCapture(handle,image.toString(),2);checkCenterGeometry(image,!away);
                rows.add(sequence+","+name+","+elapsed+","+materials+","+blas+","+attributes+","+snapshot.events().size());
                previous=current;
                System.out.println("BRIDGE_DEFORMATION sequence="+sequence+" case="+name+" fullRefresh="+full+" tlasAllocate="+tlas+" PASS");
            }
        }finally {Files.write(output.resolve("deformation-cases.csv"),rows,StandardCharsets.UTF_8);}
        System.out.println("BRIDGE_DEFORMATION PASS frames=11 freshPixels=true stableIdentity=true blasReuse=true geometryResize=true indexReorder=true mixedSampler=true unusedMaterial=true");
    }
    private static MeshAsset subdividedMesh(float x) {
        return MeshAsset.builder(2,new float[]{x-1,-1,0,x+1,-1,0,x+1,1,0,x-1,1,0,x,0,0},
                new int[]{0,1,4,1,2,4,2,3,4,3,0,4},new long[]{2,2,2,2})
                .normals(new float[]{0,0,1,0,0,1,0,0,1,0,0,1,0,0,1}).build();
    }
    private static MeshAsset deformationMesh(float x,boolean changedIndices) {
        int[] indices=changedIndices?new int[]{1,2,0,2,3,0}:new int[]{0,1,2,0,2,3};
        return MeshAsset.builder(2,new float[]{x-1,-1,0,x+1,-1,0,x+1,1,0,x-1,1,0},indices,new long[]{2,2})
                .normals(new float[]{0,0,1,0,0,1,0,0,1,0,0,1}).build();
    }

    private static void checkPoseFastPath(Renderer renderer,RtxptAccess access,NativeLightingControl lighting,
            CameraState camera,long handle,List<String> rows) {
        access.diagnosticLevel(RtxptDiagnostics.Level.DEEP);
        var monitor=access.nativeDiagnosticMonitor();
        double[] previous=RtxptNative.instanceTransformState(handle,"i11");
        for(int sequence=FRAMES+1;sequence<=FRAMES+6;sequence++) {
            var diagnosticBefore=monitor.poll();
            long[] workBefore=RtxptNative.commandWorkState(handle);
            long[] resourceBefore=RtxptNative.resourceWorkState(handle);
            require(workBefore.length==10,"Unexpected command work counters");
            var transaction=SceneTransaction.builder(sequence);
            boolean empty=sequence==FRAMES+3;
            boolean settled=sequence>=FRAMES+4;
            float x=sequence==FRAMES+1?1.25f:-2.5f;
            if(empty) {
                // Empty native transport is legal even though Java normally elides it.
                var packet=ByteBuffer.allocateDirect(36).order(ByteOrder.LITTLE_ENDIAN);
                packet.putInt(0x52445259);for(int index=0;index<8;index++)packet.putInt(0);
                RtxptNative.resourcesMemory(handle,sequence,packet.flip());
            }else if(!settled)transaction.upsert(SceneInstance.builder(11,1)
                    .transform(new AffineTransform(new float[]{1,0,0,x,0,1,0,0,0,0,1,0})).build());
            long begin=System.nanoTime();renderer.apply(transaction.build());publish(lighting,sequence);
            var view=sequence>=FRAMES+5?CameraState.lookAt(.1f*(sequence-FRAMES-4),0,3,0,0,0).build():camera;
            var request=RenderFrameRequest.builder(sequence,SIZE,SIZE,view).minimumSceneRevision(sequence).build();
            if(settled)require(renderer.trySubmit(request) instanceof Renderer.FrameSubmitted,"Available fresh output was deferred");
            else renderer.submit(request);
            access.awaitIdle();
            try(var lease=lighting.acquireLightingFrame().orElseThrow()){checkFrame(lease.frame(),sequence);}
            long[] workAfter=RtxptNative.commandWorkState(handle);
            require(workAfter.length==10,"Command work packet width changed");
            require(workAfter[7]-workBefore[7]==1&&workAfter[8]==workBefore[8]&&workAfter[9]==workBefore[9],
                    "Resident pose/empty packet did not execute exactly one lean native-lighting frame");
            for(int index=0;index<4;index++)require(workAfter[index]==workBefore[index],
                    "Pose/empty packet opened a native resource/structure command list at sequence="+sequence+" counter="+index);
            require(workAfter[4]-workBefore[4]==(empty||settled?0:1)&&workAfter[5]-workBefore[5]==(empty||settled?0:1)
                    &&workAfter[6]-workBefore[6]==(empty?1:0),"Pose/empty fast-path evidence was missing at sequence="+sequence);
            require(Arrays.equals(resourceBefore,RtxptNative.resourceWorkState(handle)),"Pose/empty packet did unrelated texture/reference work");
            double[] current=RtxptNative.instanceTransformState(handle,"i11");
            float[] expected={1,0,0,x,0,1,0,0,0,0,1,0};
            for(int index=0;index<12;index++) {
                require(Math.abs(current[index]-expected[index])<1e-5,"Pose fast path lost the authored transform");
                require(Math.abs(current[12+index]-previous[index])<1e-5,"Pose fast path consumed previous transform early");
            }
            previous=current;
            var snapshot=monitor.poll();
            require(snapshot.lostEvents()==0,"Pose diagnostic events were lost");
            var materialEvents=snapshot.events().stream().filter(event->event.stage().equals("SceneMaterialRows")).toList();
            require(materialEvents.stream().allMatch(event->event.items()==0),"Pose/camera frame rewrote unchanged material rows");
            final int frameSequence=sequence;
            var instanceEvents=snapshot.events().stream().filter(event->event.sequence()==frameSequence&&event.stage().equals("SceneInstanceRows")).toList();
            require(instanceEvents.size()==1,"Fresh pose frame omitted its explicit instance-work event");
            var instanceEvent=instanceEvents.getFirst();
            long[] raw=RtxptNative.boundaryDiagnostics(handle,snapshot.generation(),diagnosticBefore.nextOrdinal());
            require(raw.length>=21&&raw[1]==27&&raw.length==21+(raw[3]+raw[7])*27,"Invalid instance-work diagnostic packet");
            long examined=-1;
            long instanceUploadRows=0,instanceUploadWrites=0,instanceUploadBytes=0;
            var tableEvents=snapshot.events().stream().filter(event->event.sequence()==frameSequence&&event.stage().equals("SceneTableUpload")).toList();
            for(int index=21;index<raw.length;index+=27){
                if(raw[index+24]==instanceEvent.ordinal())examined=raw[index+9];
                for(var tableEvent:tableEvents)if(raw[index+24]==tableEvent.ordinal()){
                    instanceUploadRows+=raw[index+8];instanceUploadWrites+=raw[index+9];instanceUploadBytes+=raw[index+21];
                }
                for(var materialEvent:materialEvents)if(raw[index+24]==materialEvent.ordinal())
                    require(raw[index+9]==0,"Pose/camera frame scanned unchanged material registry");
            }
            require(settled?examined==0&&instanceEvent.items()==0:examined==1&&instanceEvent.items()==1,
                    "Native transform/previous-pose gate performed incorrect instance work at sequence="+sequence+" examined="+examined+" written="+instanceEvent.items());
            require(instanceEvent.tasks()==examined,"Public diagnostic check count differs from native event");
            require(settled?instanceUploadRows==0&&instanceUploadWrites==0&&instanceUploadBytes==0
                    :instanceUploadRows==1&&instanceUploadWrites==1&&instanceUploadBytes==112,
                    "A single current/previous-pose row uploaded unrelated native instance capacity at sequence="+sequence
                            +" rows="+instanceUploadRows+" writes="+instanceUploadWrites+" bytes="+instanceUploadBytes);
            var tlasEvents=snapshot.events().stream().filter(event->event.stage().equals("TlasBuild")).toList();
            require(tlasEvents.size()==1&&tlasEvents.getFirst().items()==(sequence<=FRAMES+2?1:0),
                    "Previous-pose/camera-only frame updated unchanged current TLAS inputs");
            if(settled)require(count(snapshot,"SceneTableUpload")==0,"Settled camera/idle frame uploaded unchanged native tables");
            String name=settled?(sequence==FRAMES+4?"settled-idle-zero-instance-scan":"camera-only-zero-instance-scan")
                    :empty?"previous-pose-converges-without-command-list":"resident-pose-without-command-list";
            rows.add(sequence+","+name+","+(System.nanoTime()-begin)/1_000_000.0+",0,0,0,0");
            System.out.println("BRIDGE_MIGRATION sequence="+sequence+" case="+name+" instanceExamined="+examined+" instanceWritten="+instanceEvent.items()+" instanceUploadBytes="+instanceUploadBytes+" PASS");
        }
    }

    private static void checkMaterialRegistry(Renderer renderer,RtxptAccess access,NativeLightingControl lighting,
            CameraState camera,long handle,Path output,List<String> rows) throws Exception {
        final long id=99,instance=99;
        Path before=output.resolve("material-registry-before.pfm");
        RtxptNative.nativeLightingCapture(handle,before.toString(),2);
        float[] original=centerNormal(before);
        access.diagnosticLevel(RtxptDiagnostics.Level.DEEP);
        var monitor=access.nativeDiagnosticMonitor();monitor.poll();
        for(int sequence=FRAMES+7;sequence<=FRAMES+9;sequence++) {
            boolean remove=sequence==FRAMES+8;
            var tx=SceneTransaction.builder(sequence);
            if(remove)tx.removeInstance(instance).removeMesh(id).removeMaterial(id).removeTexture(id);
            else {
                int alpha=sequence==FRAMES+7?0:255;
                var texture=TextureAsset.builder(id,1,1).colorSpace(TextureAsset.ColorSpace.SRGB)
                        .addressModes(TextureAsset.AddressMode.REPEAT,TextureAsset.AddressMode.REPEAT)
                        .filter(TextureAsset.Filter.NEAREST).pixelsRgba8(new byte[]{-1,-1,-1,(byte)alpha}).build();
                var material=MaterialAsset.builder(id).blendMode(MaterialAsset.BlendMode.MASKED)
                        .baseColorRgba8(0xc0ffffff).baseColorTextureId(id).doubleSided(true).build();
                var mesh=MeshAsset.builder(id,new float[]{-1,-1,.8f,1,-1,.8f,1,1,.8f,-1,1,.8f},
                        new int[]{0,1,2,0,2,3},new long[]{id,id})
                        .normals(new float[]{0,.6f,.8f,0,.6f,.8f,0,.6f,.8f,0,.6f,.8f})
                        .textureCoordinates(new float[]{0,0,1,0,1,1,0,1}).build();
                tx.upsert(texture).upsert(material).upsert(mesh).upsert(SceneInstance.builder(instance,id).build());
            }
            long begin=System.nanoTime();renderer.apply(tx.build());
            lighting.publishLightingAttributes(new NativeLightingPublication(sequence,
                    remove?List.of(CENTER):List.of(CENTER,NativeLightingPublication.Instance.receiver(instance)),
                    List.of(),null,null));
            renderer.submit(RenderFrameRequest.builder(sequence,SIZE,SIZE,camera).minimumSceneRevision(sequence).build());
            access.awaitIdle();
            try(var lease=lighting.acquireLightingFrame().orElseThrow()){checkFrame(lease.frame(),sequence);}
            double elapsed=(System.nanoTime()-begin)/1_000_000.0;
            var snapshot=monitor.poll();require(snapshot.lostEvents()==0,"Material registry diagnostics lost events");
            long materials=count(snapshot,"Materials"),blas=count(snapshot,"BlasBuild"),attributes=count(snapshot,"AttributePreparation");
            require(materials==0,
                    "Independent material registration entered the unused beauty baker at sequence="+sequence);
            if(remove) {
                boolean retired=false;
                try{RtxptNative.meshResourceState(handle,"i99");}catch(IllegalStateException expected){retired=true;}
                require(retired,"Removed unique instance still owns a native mesh identity");
            }else {
                double[] metadata=RtxptNative.lightingMetadataState(handle,"i99",meshName(99)+":0");
                double[] surface=RtxptNative.lightingSurfaceState(handle,meshName(99)+":0",0);
                require(metadata.length==17&&metadata[3]==2&&surface.length==9&&surface[1]==4
                        &&Math.abs(surface[5]-192/255.0)<1e-6&&metadata[9]==23&&metadata[10]==1,
                        "New/re-added unique material lost triangle, sampler, opacity or receiver semantics");
            }
            String name=remove?"remove-unique-material-without-reimport"
                    :sequence==FRAMES+7?"register-transparent-unique-material":"reregister-opaque-unique-material";
            Path image=output.resolve(name+".pfm");RtxptNative.nativeLightingCapture(handle,image.toString(),2);
            float[] actual=centerNormal(image);
            if(sequence==FRAMES+9) {
                require(Math.abs(actual[0])<.025&&Math.abs(actual[1]-.6)<.025&&Math.abs(actual[2]-.8)<.025,
                        "Re-added opaque texture/material did not produce its new receiver pixels");
            }else for(int component=0;component<3;component++)require(Math.abs(actual[component]-original[component])<.005,
                    "Transparent/removed unique material changed the surviving receiver pixels");
            rows.add(sequence+","+name+","+elapsed+","+materials+","+blas+","+attributes+","+snapshot.events().size());
            System.out.println("BRIDGE_MIGRATION sequence="+sequence+" case="+name+" PASS");
        }
    }

    private static void checkTlasCapacityContract(Path output) throws Exception {
        var plan=new NativeLightingPlan(SIZE,SIZE,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.NORMAL_DISTANCE),
            0,6,0,NativeLightingStyle.defaults());
        var camera=CameraState.lookAt(0,0,3,0,0,0).build();
        // Start exactly at a native capacity boundary, including original bootstrap.
        int[] counts={1024,1025,2048,2049,7,2049};
        try(var renderer=RtxptRenderers.openNativeLighting(RtxptRenderers.nativeLightingConfiguration(),plan)) {
            var access=renderer.extension(RtxptAccess.class).orElseThrow();
            var lighting=renderer.extension(NativeLightingControl.class).orElseThrow();
            access.diagnosticLevel(RtxptDiagnostics.Level.DEEP);
            RtxptDiagnostics.NativeMonitor monitor=null;
            var handleField=RtxptRenderer.class.getDeclaredField("handle");handleField.setAccessible(true);
            int previousCount=0;
            for(int index=0;index<counts.length;index++) {
                int sequence=index+1,count=counts[index];
                var tx=SceneTransaction.builder(sequence);
                if(index==0)tx.resetScene().upsert(MaterialAsset.builder(1).doubleSided(true).build()).upsert(mesh(1,0,0,0));
                for(int id=previousCount+1;id<=count;id++)
                    tx.upsert(SceneInstance.builder(id,1).transform(translated(id==1?0:10)).build());
                for(int id=count+1;id<=previousCount;id++)tx.removeInstance(id);
                renderer.apply(tx.build());
                renderer.submit(RenderFrameRequest.builder(sequence,SIZE,SIZE,camera).minimumSceneRevision(sequence).build());
                access.awaitIdle();
                try(var lease=lighting.acquireLightingFrame().orElseThrow()) {
                    require(lease.frame().sequence()==sequence&&lease.frame().sceneRevision()==sequence,
                        "TLAS capacity change published a stale frame");
                }
                if(monitor==null)monitor=access.nativeDiagnosticMonitor();
                var snapshot=monitor.poll();require(snapshot.lostEvents()==0,"TLAS capacity diagnostics lost events");
                if(index>0) {
                    boolean growth=index==1||index==3;
                    require(count(snapshot,"TlasAllocate")== (growth?1:0),
                        "TLAS/sub-instance capacity changed unnecessarily or failed to grow at count="+count);
                    require(count(snapshot,"BlasBuild")==0,"Instance-only capacity change rebuilt shared geometry");
                    var builds=snapshot.events().stream().filter(event->event.stage().equals("TlasBuild")).toList();
                    require(builds.size()==1&&builds.getFirst().items()==1,"Current instance set did not rebuild its TLAS");
                }
                Path image=output.resolve("tlas-capacity-"+count+"-"+sequence+".pfm");
                RtxptNative.nativeLightingCapture(handleField.getLong(renderer),image.toString(),2);
                var normal=centerNormal(image);
                require(Math.abs(normal[0])<.001&&Math.abs(normal[1])<.001&&Math.abs(normal[2]-1)<.001,
                    "TLAS growth/shrink changed the surviving receiver: "+Arrays.toString(normal));
                previousCount=count;
            }
        }
        System.out.println("TLAS_CAPACITY_NATIVE PASS frames=6 growAcrossBoundary=true shrinkRegrow=true sharedBlas=true freshPixels=true");
    }
    private static void checkPrimitiveHistoryContract(Path output) throws Exception {
        var plan=new NativeLightingPlan(SIZE,SIZE,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.INDIRECT_DIFFUSE),1,6,.35f,
            NativeLightingStyle.defaults(),new NativeLightingPlan.Directional(0,1,0,0,1,1,1),null);
        var camera=CameraState.lookAt(0,0,3,0,0,0).aspectRatio(1).build();
        float[] current={0,.1f,.1f,.1f,0,0,.2f,.2f};
        float[] previous={0,-.2f,.1f,.4f,-.1f,0,.2f,.1f};
        try(var renderer=RtxptRenderers.openNativeLighting(RtxptRenderers.nativeLightingConfiguration(),plan)) {
            var access=renderer.extension(RtxptAccess.class).orElseThrow();var lighting=renderer.extension(NativeLightingControl.class).orElseThrow();
            lighting.lightingReconstruction(NativeLightingReconstruction.DLSS_RR);
            var handleField=RtxptRenderer.class.getDeclaredField("handle");handleField.setAccessible(true);
            renderer.apply(SceneTransaction.builder(1).resetScene().upsert(MaterialAsset.builder(1).doubleSided(true).build())
                .upsert(MaterialAsset.builder(2).doubleSided(true).build()).upsert(mesh(1,0,0,0)).upsert(mesh(2,0,0,0))
                // Keep real receiver guides available while the temporary primitive is removed in frame 6.
                .upsert(mesh(3,0,-.5f,0)).upsert(SceneInstance.builder(3,3).build()).build());
            double[] retained=null;
            for(int sequence=1;sequence<=current.length;sequence++) {
                var frame=RenderFrameRequest.builder(sequence,SIZE,SIZE,camera).minimumSceneRevision(1);
                if(sequence!=6) {
                    int i=sequence-1;frame.primitiveBatch(FramePrimitiveBatch.of(List.of(PrimitiveInstance.builder(sequence<5?1:2)
                        .transform(translated(current[i])).previousTransform(translated(previous[i])).build())));
                }
                renderer.submit(frame.build());access.awaitIdle();long handle=handleField.getLong(renderer);
                try(var lease=lighting.acquireLightingFrame().orElseThrow()) {
                    require(lease.frame().sequence()==sequence&&lease.frame().sceneRevision()==1,"Primitive history output used stale input identity");
                }
                var rr=lighting.lightingReconstruction();
                require(rr.actual()==NativeLightingReconstruction.DLSS_RR&&rr.guidesValid()&&rr.evaluatedFrames()==sequence,
                    "Explicit previous-pose contract requires real current-frame RR guides");
                Path motion=output.resolve("primitive-history-"+sequence+".pfm");RtxptNative.nativeLightingCapture(handle,motion.toString(),6);
                var values=centerNormal(motion);float expected=sequence==6?0:(previous[sequence-1]-current[sequence-1])/(6*camera.tanHalfFovX());
                require(Math.abs(values[0]-expected)<.0001&&Math.abs(values[1])<.00001,
                    "Explicit previous-pose motion mismatch at sequence="+sequence+": "+Arrays.toString(values)+" expected="+expected);
                if(sequence!=6) {
                    var resources=RtxptNative.meshResourceState(handle,"p0");
                    if(sequence==3||sequence==4||sequence==8)require(resources[0]==retained[0]&&resources[1]==retained[1],
                        "A previous-pose-only edit replaced geometry or BLAS");
                    retained=resources;
                }
            }
        }
        System.out.println("PRIMITIVE_HISTORY_NATIVE PASS frames=8 explicitPose=true previousOnly=true defaultStill=true identityReplace=true removeReadd=true realRR=true");
    }
    private static AffineTransform translated(float x) {
        return new AffineTransform(new float[]{1,0,0,x,0,1,0,0,0,0,1,0});
    }
    private static void checkPackedLightContract(Path output) throws Exception {
        var plan=new NativeLightingPlan(SIZE,SIZE,Set.of(NativeLightingPlan.Signal.DIRECT_GAIN),0,6,0,
            NativeLightingStyle.defaults(),new NativeLightingPlan.Directional(0,1,0,0,1,1,1),null);
        var camera=CameraState.lookAt(0,0,3,0,0,0).build();
        var base=mesh(1,0,0,0);float[] expected={.625f,1,.8125f,1,.925f,.8125f,.25f,1,.625f};
        try(var renderer=RtxptRenderers.openNativeLighting(RtxptRenderers.nativeLightingConfiguration(),plan)) {
            var access=renderer.extension(RtxptAccess.class).orElseThrow();var lighting=renderer.extension(NativeLightingControl.class).orElseThrow();
            var handleField=RtxptRenderer.class.getDeclaredField("handle");handleField.setAccessible(true);
            long handle=0;double[] retained=null;
            for(int sequence=1;sequence<=expected.length;sequence++) {
                var tx=SceneTransaction.builder(sequence);
                if(sequence==1)tx.resetScene().upsert(MaterialAsset.builder(1).doubleSided(true).build()).upsert(base);
                if(sequence<=2)tx.upsert(SceneInstance.builder(11,1).packedLight(sequence==1?120|(60<<16):0).build());
                if(sequence==3){float[] uv=new float[8];for(int i=0;i<8;i+=2){uv[i]=.25f;uv[i+1]=.75f;}tx.upsert(base.toBuilder().lightmapCoordinates(uv).build());}
                if(sequence==4)tx.upsert(base);
                if(sequence==6)tx.removeInstance(11);
                if(sequence==8)tx.upsert(base.toBuilder().lightmapCoordinates(new float[8]).build());
                renderer.apply(tx.build());
                List<NativeLightingPublication.Instance> instances=sequence==5
                    ?List.of(new NativeLightingPublication.Instance(11,23,1,0,1,1,.1f,.2f)):List.of();
                List<NativeLightingPublication.MeshBaseline> baselines=sequence==9
                    ?List.of(new NativeLightingPublication.MeshBaseline(1,new float[]{.5f,.25f,.5f,.25f,.5f,.25f,.5f,.25f})):List.of();
                lighting.publishLightingAttributes(new NativeLightingPublication(sequence,instances,baselines,null,null));
                var frame=RenderFrameRequest.builder(sequence,SIZE,SIZE,camera).minimumSceneRevision(sequence);
                if(sequence>=6)frame.primitiveBatch(FramePrimitiveBatch.of(List.of(PrimitiveInstance.builder(1)
                    .packedLight(sequence==6?60|(180<<16):SceneInstance.FULL_BRIGHT_PACKED_LIGHT).build())));
                renderer.submit(frame.build());access.awaitIdle();handle=handleField.getLong(renderer);
                try(var lease=lighting.acquireLightingFrame().orElseThrow()) {
                    require(lease.frame().sequence()==sequence&&lease.frame().sceneRevision()==sequence&&lease.frame().attributeGeneration()==sequence,
                        "Packed-light output used stale frame or attribute identity");
                }
                String identity=sequence<6?"i11":"p0";
                var metadata=RtxptNative.lightingMetadataState(handle,identity,meshName(1)+":0");
                double[] transform=sequence==1?new double[]{0,0,.5,.25}:sequence==2||sequence==4?new double[]{0,0,0,0}
                    :sequence==5?new double[]{1,1,.1,.2}:sequence==6?new double[]{0,0,.25,.75}
                    :sequence==7?new double[]{0,0,1,1}:new double[]{1,1,0,0};
                for(int i=0;i<4;i++)require(Math.abs(metadata[13+i]-transform[i])<1e-6,
                    "Packed-light/mesh/explicit publication precedence failed at sequence="+sequence+" component="+i);
                var resources=RtxptNative.meshResourceState(handle,identity);
                if(sequence==2||sequence==7)require(resources[0]==retained[0]&&resources[1]==retained[1],
                    "A packed-light-only edit replaced geometry or BLAS");
                retained=resources;
                Path image=output.resolve("packed-light-"+sequence+".pfm");RtxptNative.nativeLightingCapture(handle,image.toString(),0);
                for(float gain:centerNormal(image))require(Math.abs(gain-expected[sequence-1])<.001,
                    "Packed-light GPU gain mismatch at sequence="+sequence+": "+gain+" expected="+expected[sequence-1]);
            }
        }
        System.out.println("PACKED_LIGHT_NATIVE PASS frames=9 persistent=true framePrimitive=true vertexPrecedence=true explicitOverride=true noGeometryReplacement=true");
    }
    private static float[] centerNormal(Path file) throws Exception {
        byte[] bytes=Files.readAllBytes(file);int start=0;
        for(int line=0;line<3;line++){while(start<bytes.length&&bytes[start++]!='\n'){}}
        require(new String(bytes,0,start,StandardCharsets.US_ASCII).equals("PF\n64 64\n-1.0\n")
                &&bytes.length-start==SIZE*SIZE*12,"Invalid registry geometry PFM");
        var values=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);float[] result=new float[3];
        for(int y=30;y<=33;y++)for(int x=30;x<=33;x++)for(int component=0;component<3;component++) {
            float value=values.getFloat(start+((SIZE-1-y)*SIZE+x)*12+component*4);
            require(Float.isFinite(value),"Nonfinite material-registry receiver output");result[component]+=value/16;
        }
        return result;
    }

    private static void publish(NativeLightingControl lighting,long generation) {
        lighting.publishLightingAttributes(new NativeLightingPublication(generation,List.of(CENTER),List.of(),null,null));
    }
    private static void checkFrame(LightingFrameLease.Frame frame,long sequence) {
        require(frame.sequence()==sequence&&frame.sceneRevision()==sequence&&frame.attributeGeneration()==sequence,
                "Fresh frame/attribute identity mismatch at sequence="+sequence);
        require(frame.width()==SIZE&&frame.height()==SIZE&&frame.planes().size()==2,"Output dimensions changed");
        require(frame.readyValue()<frame.completionValue()&&frame.planes().getFirst().memoryHandle()!=0,"Invalid output synchronization");
    }
    private static void checkMetadata(long handle,TextureAsset texture,MaterialAsset masked,boolean thirdPresent,boolean firstPresent) {
        double[] center=RtxptNative.lightingMetadataState(handle,"i22",meshName(2)+":0");
        require(center.length==17,"Unexpected adapter metadata state");
        int policy=(texture.addressU()==TextureAsset.AddressMode.CLAMP_TO_EDGE?1:0)
                |(texture.addressV()==TextureAsset.AddressMode.CLAMP_TO_EDGE?2:0)
                |(texture.filter()==TextureAsset.Filter.NEAREST?4:0);
        require(center[3]==2,"Dense geometry triangle layout was stale");
        for(int primitive=0;primitive<2;primitive++){
            var surface=RtxptNative.lightingSurfaceState(handle,meshName(2)+":0",primitive);
            require(surface.length==9&&surface[1]==policy,"Triangle surface sampler policy was stale");
            require(Math.abs(surface[5]-((masked.baseColorRgba8()>>>24)&255)/255.0)<1e-6,"Triangle surface masked scalar was stale");
        }
        double[] expected={23,.8,0,0,.3,.7,.1,.2};
        for(int index=0;index<8;index++)require(Math.abs(center[9+index]-expected[index])<1e-6,
                "Surviving attributes changed during dense reindex at component="+index);
        var slots=new HashSet<Integer>();var geometries=new HashSet<Integer>();
        slots.add((int)center[0]);geometries.add((int)center[1]);
        for(int id:new int[]{firstPresent?1:0,thirdPresent?3:0})if(id!=0) {
            long instance=id==1?11:33;
            var state=RtxptNative.lightingMetadataState(handle,"i"+instance,meshName(id)+":0");
            require(slots.add((int)state[0])&&geometries.add((int)state[1]),"Distinct identities alias metadata slots");
        }
        for(int index=0;index<slots.size();index++)require(slots.contains(index)&&geometries.contains(index),"Dense metadata indices contain a hole");
    }
    private static long count(RtxptDiagnostics.NativeSnapshot snapshot,String stage) {
        return snapshot.events().stream().filter(event->event.stage().equals(stage)).count();
    }
    private static String meshName(int id) {return "mesh"+id+"_v3f800000";}
    private static MaterialAsset masked(int alpha) {
        return MaterialAsset.builder(2).blendMode(MaterialAsset.BlendMode.MASKED)
                .baseColorRgba8((alpha<<24)|0x00ffffff).baseColorTextureId(TEXTURE).doubleSided(true).build();
    }
    private static MeshAsset mesh(long id,float x,float z,float tilt) {
        return MeshAsset.builder(id,new float[]{x-1,-1,z,x+1,-1,z,x+1,1,z,x-1,1,z},
                new int[]{0,1,2,0,2,3},new long[]{id==2?2:1,id==2?2:1})
                .normals(new float[]{tilt,0,1,tilt,0,1,tilt,0,1,tilt,0,1})
                .textureCoordinates(new float[]{0,0,1,0,1,1,0,1}).build();
    }
    private static MeshAsset samplerMesh() {
        // Outside-U receiver coordinates distinguish repeat from clamp on the
        // GPU. This is a public UV-only update, retaining positions and BLAS.
        return MeshAsset.builder(2,new float[]{-1,-1,.1f,1,-1,.1f,1,1,.1f,-1,1,.1f},
                new int[]{0,1,2,0,2,3},new long[]{2,2})
                .normals(new float[]{.2f,0,1,.2f,0,1,.2f,0,1,.2f,0,1})
                .textureCoordinates(new float[]{1.125f,.125f,1.125f,.125f,1.125f,.125f,1.125f,.125f}).build();
    }
    private static TextureAsset texture(byte[] pixels,boolean alternate) {
        return TextureAsset.builder(TEXTURE,4,4).colorSpace(TextureAsset.ColorSpace.SRGB)
                .addressModes(alternate?TextureAsset.AddressMode.CLAMP_TO_EDGE:TextureAsset.AddressMode.REPEAT,TextureAsset.AddressMode.REPEAT)
                .filter(alternate?TextureAsset.Filter.LINEAR:TextureAsset.Filter.NEAREST).pixelsRgba8(pixels).build();
    }
    private static TextureAsset copy(TextureAsset source) {
        return TextureAsset.builder(TEXTURE,source.width(),source.height()).colorSpace(source.colorSpace())
                .addressModes(source.addressU(),source.addressV()).filter(source.filter()).pixelsRgba8(pixels(source)).build();
    }
    private static TextureAsset patchCorner(TextureAsset source,boolean changed) {
        byte[] value={(byte)(changed?96:255),-1,-1,-1};
        return source.patched(new TexturePatch(TEXTURE,0,0,0,1,1,4,ByteBuffer.wrap(value)));
    }
    private static byte[] pixels(TextureAsset texture) {
        byte[] result=new byte[texture.rgba8().remaining()];texture.rgba8().get(result);return result;
    }
    private static byte[] opaquePixels() {byte[] result=new byte[64];Arrays.fill(result,(byte)-1);return result;}
    private static void checkCenterGeometry(Path file,boolean hit) throws Exception {
        byte[] bytes=Files.readAllBytes(file);int start=0;
        for(int line=0;line<3;line++) {while(start<bytes.length&&bytes[start++]!='\n') { }}
        require(new String(bytes,0,start,StandardCharsets.US_ASCII).equals("PF\n64 64\n-1.0\n")
                &&bytes.length-start==SIZE*SIZE*12,"Invalid geometry PFM");
        var values=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for(int y=30;y<=33;y++)for(int x=30;x<=33;x++) {
            float z=values.getFloat(start+((SIZE-1-y)*SIZE+x)*12+8);
            require(Float.isFinite(z)&&(hit?z>.9f:Math.abs(z)<1e-6f),"Masked scalar did not affect receiver pixel at "+x+","+y+": "+z);
        }
    }
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
}
