package top.ceroxe.rt.renderer.rtxpt;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import top.ceroxe.rt.renderer.api.*;

/** Existing material mutation must reach fresh GPU output without re-registering the scene. */
public final class RtxptMaterialMutationNativeSelfTest {
    private static final int SIZE=64;
    private static final long MATERIAL=2,WHITE_TEXTURE=7,RED_TEXTURE=8,GREEN_TEXTURE=9;

    public static void main(String[] args) throws Exception {
        Path output=Path.of(args.length==0?"build/reports/material-mutation-contract":args[0]).toAbsolutePath();
        Files.createDirectories(output);
        // The receiver has sky baseline 1 and local baseline 0. With no local
        // correction and unit directional dimming, gain equals shadow RGB.
        // The caster is excluded from primary rays and admits shadow rays, so
        // changing its color/texture has an unambiguous observable GPU result.
        var style=new NativeLightingStyle(NativeLightingStyle.AdditionMode.DYNAMIC_ONLY_V1,
                0,0,1,0,1,1,1,0,1,1);
        var plan=new NativeLightingPlan(SIZE,SIZE,
                Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.NORMAL_DISTANCE),
                0,6,0,style,new NativeLightingPlan.Directional(0,0,1,1,1,1,1),null,false);
        var rows=new ArrayList<String>();
        rows.add("sequence,case,red,green,blue,materials_events,scene_full_refresh_events,keys_built,policy_reads,geometry_checks,policies_reused,apply_to_fresh_ms");
        try(var renderer=RtxptRenderers.openNativeLighting(RtxptRenderers.nativeLightingConfiguration(),plan)) {
            var access=renderer.extension(RtxptAccess.class).orElseThrow();
            var lighting=renderer.extension(NativeLightingControl.class).orElseThrow();
            access.diagnosticLevel(RtxptDiagnostics.Level.DEEP);
            lighting.lightingReconstruction(NativeLightingReconstruction.NONE);
            var handleField=RtxptRenderer.class.getDeclaredField("handle");handleField.setAccessible(true);
            var camera=CameraState.lookAt(0,0,3,0,0,0).build();
            MaterialAsset initial=material(0xffffffff,WHITE_TEXTURE,1);
            renderer.apply(SceneTransaction.builder(1).resetScene()
                    .upsert(texture(WHITE_TEXTURE,255,255,255))
                    .upsert(texture(RED_TEXTURE,255,0,0)).upsert(texture(GREEN_TEXTURE,0,255,0))
                    .upsert(MaterialAsset.builder(1).doubleSided(true).build()).upsert(initial)
                    .upsert(quad(1,0,1)).upsert(quad(2,1,MATERIAL)).upsert(quad(3,1,MATERIAL,20))
                    .upsert(SceneInstance.builder(11,1).build())
                    .upsert(SceneInstance.builder(22,2).build()).upsert(SceneInstance.builder(33,3).build()).build());
            publish(lighting,1);
            renderer.submit(RenderFrameRequest.builder(1,SIZE,SIZE,camera).minimumSceneRevision(1).build());
            access.awaitIdle();
            long handle=handleField.getLong(renderer);
            checkFrame(lighting,1);
            float[] initialColor=capture(handle,output,"initial-white");
            checkColor(initialColor,new float[]{1,1,1},"initial-white");
            var monitor=access.nativeDiagnosticMonitor();monitor.poll();
            double[] originalMesh=RtxptNative.meshResourceState(handle,"i22");
            // RGBA8 colors use the public contract's low-byte red convention.
            MaterialAsset red=material(0xff0000ff,WHITE_TEXTURE,1);
            MaterialAsset rough=initial.toBuilder().roughness(.85f).build();
            List<Mutation> mutations=List.of(
                    change("scalar-red",red,new float[]{1,0,0},0,0),
                    change("scalar-green",material(0xff00ff00,WHITE_TEXTURE,1),new float[]{0,1,0},0,0),
                    change("scalar-white-restored",initial,new float[]{1,1,1},0,0),
                    change("texture-id-red",material(0xffffffff,RED_TEXTURE,1),new float[]{1,0,0},1,2),
                    change("texture-id-green",material(0xffffffff,GREEN_TEXTURE,1),new float[]{0,1,0},1,2),
                    change("texture-id-white-restored",initial,new float[]{1,1,1},1,2),
                    change("transmission-quarter",material(0xffffffff,WHITE_TEXTURE,.25f),new float[]{.25f,.25f,.25f},0,0),
                    change("transmission-restored",initial,new float[]{1,1,1},0,0),
                    change("opacity-red-base",red,new float[]{1,0,0},0,0),
                    change("opacity-half",material(0x800000ff,WHITE_TEXTURE,1),new float[]{1,127/255f,127/255f},0,2),
                    change("opacity-restored",red,new float[]{1,0,0},0,2),
                    change("opacity-white-restored",initial,new float[]{1,1,1},0,0),
                    // Direct shadow does not use roughness or emission. These
                    // cases prove zero metadata work, not their beauty response.
                    change("roughness-without-policy-work",rough,new float[]{1,1,1},0,0),
                    change("emission-without-policy-work",rough.toBuilder().emissive(new Rgb(2,1,.5f)).build(),new float[]{1,1,1},0,0),
                    change("scalar-restore-without-policy-work",initial,new float[]{1,1,1},0,0),
                    textureChange("pixel-content-blue",texture(WHITE_TEXTURE,0,0,255),new float[]{0,0,1},0,0),
                    textureChange("pixel-content-white-restored",texture(WHITE_TEXTURE,255,255,255),new float[]{1,1,1},0,0),
                    textureChange("sampler-linear",texture(WHITE_TEXTURE,255,255,255,TextureAsset.Filter.LINEAR),new float[]{1,1,1},1,2),
                    textureChange("sampler-nearest-restored",texture(WHITE_TEXTURE,255,255,255),new float[]{1,1,1},1,2));
            long sequence=1;
            for(var mutation:mutations) {
                ++sequence;
                long[] metadataBefore=RtxptNative.lightingMetadataWork(handle);
                long begin=System.nanoTime();
                var transaction=SceneTransaction.builder(sequence);
                if(mutation.material()!=null)transaction.upsert(mutation.material());
                if(mutation.texture()!=null)transaction.upsert(mutation.texture());
                renderer.apply(transaction.build());
                publish(lighting,sequence);
                renderer.submit(RenderFrameRequest.builder(sequence,SIZE,SIZE,camera)
                        .minimumSceneRevision(sequence).build());
                access.awaitIdle();
                checkFrame(lighting,sequence);
                double elapsed=(System.nanoTime()-begin)/1_000_000.0;
                var snapshot=monitor.poll();
                require(snapshot.lostEvents()==0,"Material mutation diagnostics lost events");
                long materials=count(snapshot,"Materials"),fullRefresh=count(snapshot,"SceneFullRefresh");
                require(materials==0,"Existing material was globally re-registered for "+mutation.name());
                require(fullRefresh==0,"Existing material mutation refreshed the whole scene for "+mutation.name());
                long[] metadataAfter=RtxptNative.lightingMetadataWork(handle);
                checkMetadataWork(metadataBefore,metadataAfter,mutation);
                double[] currentMesh=RtxptNative.meshResourceState(handle,"i22");
                require(currentMesh[0]==originalMesh[0],"Scalar/texture-ID mutation replaced its unchanged BLAS");
                float[] actual=capture(handle,output,mutation.name());
                checkColor(actual,mutation.expected(),mutation.name());
                rows.add(sequence+","+mutation.name()+","+actual[0]+","+actual[1]+","+actual[2]
                        +","+materials+","+fullRefresh+","+(metadataAfter[0]-metadataBefore[0])
                        +","+(metadataAfter[1]-metadataBefore[1])+","+(metadataAfter[2]-metadataBefore[2])
                        +","+(metadataAfter[3]-metadataBefore[3])+","+elapsed);
                System.out.println("MATERIAL_MUTATION sequence="+sequence+" case="+mutation.name()+" PASS gpu="
                        +actual[0]+","+actual[1]+","+actual[2]);
            }
            require(lighting.nativeLightingExecution().fullBeautyFrames()==0,
                    "Material mutation unexpectedly executed full beauty");
        }
        Files.write(output.resolve("material-mutation.csv"),rows,StandardCharsets.UTF_8);
        System.out.println("MATERIAL_MUTATION_NATIVE PASS freshFrames=20 scalarColor=true textureId=true pixelContent=true"
                +" transmission=true opacity=true globalMaterialRegistration=0 sceneFullRefresh=0 unchangedBlas=true"
                +" materialDependencyScope=true noUnrelatedPolicyReads=true roughnessGpuVerified=false emissionGpuVerified=false");
    }

    private record Mutation(String name,MaterialAsset material,TextureAsset texture,float[] expected,int policyReads,int geometryChecks) { }
    private static Mutation change(String name,MaterialAsset material,float[] expected,int reads,int checks) {
        return new Mutation(name,material,null,expected,reads,checks);
    }
    private static Mutation textureChange(String name,TextureAsset texture,float[] expected,int reads,int checks) {
        return new Mutation(name,null,texture,expected,reads,checks);
    }
    private static void checkMetadataWork(long[] before,long[] after,Mutation mutation) {
        require(before.length==11&&after.length==11,"Unexpected metadata work counter layout");
        require(after[0]==before[0],"Material mutation rebuilt geometry lookup keys for "+mutation.name());
        require(after[1]-before[1]==mutation.policyReads(),
                "Material mutation reread unrelated texture policies for "+mutation.name()+": "+(after[1]-before[1]));
        require(after[2]-before[2]==mutation.geometryChecks(),
                "Material mutation scanned unrelated geometries for "+mutation.name()+": "+(after[2]-before[2]));
        require(after[4]==before[4]&&after[5]==before[5]&&after[8]==before[8],
                "Material mutation rebuilt layouts or repacked unchanged corner attributes for "+mutation.name());
    }

    private static MaterialAsset material(int rgba,long texture,float transmission) {
        // Keep the material domain transmissive-blended across every mutation;
        // true domain changes may legitimately require conservative rebuilding.
        return MaterialAsset.builder(MATERIAL).blendMode(MaterialAsset.BlendMode.TRANSLUCENT)
                .baseColorRgba8(rgba).baseColorTextureId(texture).transmission(transmission)
                .roughness(.4f).doubleSided(true).build();
    }
    private static TextureAsset texture(long id,int red,int green,int blue) {
        return texture(id,red,green,blue,TextureAsset.Filter.NEAREST);
    }
    private static TextureAsset texture(long id,int red,int green,int blue,TextureAsset.Filter filter) {
        return TextureAsset.builder(id,1,1).colorSpace(TextureAsset.ColorSpace.LINEAR)
                .addressModes(TextureAsset.AddressMode.CLAMP_TO_EDGE,TextureAsset.AddressMode.CLAMP_TO_EDGE)
                .filter(filter)
                .pixelsRgba8(new byte[]{(byte)red,(byte)green,(byte)blue,(byte)255}).build();
    }
    private static MeshAsset quad(long id,float z,long material) {
        return quad(id,z,material,0);
    }
    private static MeshAsset quad(long id,float z,long material,float x) {
        return MeshAsset.builder(id,new float[]{x-2,-2,z,x+2,-2,z,x+2,2,z,x-2,2,z},
                new int[]{0,1,2,0,2,3},new long[]{material,material})
                .normals(new float[]{0,0,1,0,0,1,0,0,1,0,0,1})
                .textureCoordinates(new float[]{0,0,1,0,1,1,0,1}).build();
    }
    private static void publish(NativeLightingControl lighting,long generation) {
        float[] lookup=new float[512];
        for(int index=1;index<lookup.length;index+=2)lookup[index]=1;
        lighting.publishLightingAttributes(new NativeLightingPublication(generation,
                List.of(new NativeLightingPublication.Instance(11,1,1,0,0,0,0,1),
                        NativeLightingPublication.Instance.shadowOnly(22),NativeLightingPublication.Instance.shadowOnly(33)),List.of(),lookup,null));
    }
    private static void checkFrame(NativeLightingControl lighting,long sequence) {
        try(var lease=lighting.acquireLightingFrame().orElseThrow()) {
            var frame=lease.frame();
            require(frame.sequence()==sequence&&frame.sceneRevision()==sequence&&frame.attributeGeneration()==sequence,
                    "Stale material output at sequence="+sequence);
            require(frame.width()==SIZE&&frame.height()==SIZE&&frame.planes().size()==2,
                    "Material output extent/planes changed");
            require(frame.readyValue()<frame.completionValue()&&frame.planes().getFirst().memoryHandle()!=0,
                    "Material output did not expose real GPU synchronization");
        }
    }
    private static float[] capture(long handle,Path output,String name) throws Exception {
        Path file=output.resolve(name+".pfm");RtxptNative.nativeLightingCapture(handle,file.toString(),0);
        byte[] bytes=Files.readAllBytes(file);int start=0;
        for(int line=0;line<3;line++)while(start<bytes.length&&bytes[start++]!='\n') { }
        require(new String(bytes,0,start,StandardCharsets.US_ASCII).equals("PF\n64 64\n-1.0\n")
                &&bytes.length-start==SIZE*SIZE*12,"Invalid fresh material PFM");
        var values=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);float[] result=new float[3];
        // Stay clear of triangle/quad silhouettes and average a central 4x4 patch.
        for(int y=30;y<=33;y++)for(int x=30;x<=33;x++)for(int channel=0;channel<3;channel++) {
            float value=values.getFloat(start+((SIZE-1-y)*SIZE+x)*12+channel*4);
            require(Float.isFinite(value),"Nonfinite material mutation output");result[channel]+=value/16;
        }
        return result;
    }
    private static void checkColor(float[] actual,float[] expected,String name) {
        for(int channel=0;channel<3;channel++)require(Math.abs(actual[channel]-expected[channel])<.02f,
                "Material mutation did not reach GPU for "+name+" channel="+channel
                        +" actual="+actual[channel]+" expected="+expected[channel]);
    }
    private static long count(RtxptDiagnostics.NativeSnapshot snapshot,String stage) {
        return snapshot.events().stream().filter(event->event.stage().equals(stage)).count();
    }
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
}
