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
                        require(before[1]!=after[1],"Mixed texture/attribute update omitted new vertex buffer at sequence="+sequence);
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
                        if(sequence!=11&&sequence!=12&&sequence!=13)require(materials==0,
                                "Texture or geometry delta caused full material import at sequence="+sequence);
                        if(!topology&&sequence!=11&&sequence!=12&&sequence!=13)require(count(snapshot,"SceneFullRefresh")==0,
                                "Texture/attribute update caused an extra full scene refresh at sequence="+sequence);
                        if(noOp)require(blas==0&&count(snapshot,"SceneFullRefresh")==0,
                                "No-op texture generation caused global resource work at sequence="+sequence);
                        if(attributeOnly)require(attributes>0,"Mixed update omitted original attribute uploader");
                        sawAttributes|=attributes>0;sawTopology|=blas>0;
                    } else {
                        require(snapshot.events().isEmpty()&&javaSnapshot.events().isEmpty(),"OFF continued collecting diagnostics");
                        require(javaSnapshot.level()==RtxptDiagnostics.Level.OFF,"Diagnostic OFF level was not visible");
                    }
                    checkMetadata(handle,texture,masked,thirdPresent,sequence!=9);
                    if(sequence==11||sequence==12||sequence==35||sequence==36) {
                        Path file=output.resolve(name+".pfm");RtxptNative.nativeLightingCapture(handle,file.toString(),2);
                        checkCenterGeometry(file,sequence==12||sequence==36);
                    }
                    rows.add(sequence+","+name+","+elapsed+","+materials+","+blas+","+attributes+","+snapshot.events().size());
                    System.out.println("BRIDGE_MIGRATION sequence="+sequence+" case="+name+" PASS");
                }
            } finally {if(retained!=null)retained.close();}
            require(sawRealTexture&&sawAttributes&&sawTopology,"Required mixed resource paths were not observed");
            require(lighting.nativeLightingExecution().fullBeautyFrames()==0,"Direct-only contract executed full beauty");
        } finally {Files.write(output.resolve("cases.csv"),rows,StandardCharsets.UTF_8);}
        System.out.println("BRIDGE_MIGRATION PASS frames="+FRAMES+" noOpGenerations=true rectangleParents=true samplerMetadata=true samplerPixels=true mixedAttributes=true mixedTopology=true denseReindex=true maskedScalarPixels=true independentMonitors=true diagnosticOff=true retainedLease=true");
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
        require(center[3]==2&&center[4]==policy,"Dense geometry or sampler policy was stale");
        require(Math.abs(center[8]-((masked.baseColorRgba8()>>>24)&255)/255.0)<1e-6,"Masked scalar metadata was stale");
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
