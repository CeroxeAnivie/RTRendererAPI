package top.ceroxe.rt.renderer.rtxpt;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import top.ceroxe.rt.renderer.api.*;

/** Generic consumer contract: new direct materials never require beauty registration. */
public final class RtxptDirectMaterialLifecycleNativeSelfTest {
    private static final int SIZE=64;
    private static final CameraState CAMERA=CameraState.lookAt(0,0,3,0,0,0).build();
    private static final NativeLightingPlan PLAN=new NativeLightingPlan(SIZE,SIZE,
            Set.of(NativeLightingPlan.Signal.DIRECT_GAIN,NativeLightingPlan.Signal.NORMAL_DISTANCE),
            0,6,0,new NativeLightingStyle(NativeLightingStyle.AdditionMode.DYNAMIC_ONLY_V1,
                    0,0,1,0,1,1,1,0,1,1),
            new NativeLightingPlan.Directional(0,0,1,1,1,1,1),null,false);
    private record Step(String name,SceneTransaction transaction,boolean caster,float[] expected) { }
    public static void main(String[] args)throws Exception {
        boolean beautyOnly=Arrays.asList(args).contains("--beauty-entry-only");
        boolean beautyDormantOnly=Arrays.asList(args).contains("--beauty-dormant-only");
        Path output=Path.of(Arrays.stream(args).filter(value->!value.startsWith("--")).findFirst()
                .orElse("build/reports/direct-material-lifecycle")).toAbsolutePath();
        Files.createDirectories(output);
        if(beautyDormantOnly){beautyDormant(output);return;}
        if(beautyOnly){beautyEntry(output);return;}
        direct(output);
    }
    private static void direct(Path output)throws Exception {
        var rows=new ArrayList<String>();
        rows.add("sequence,case,red,green,blue,materials_events,scene_full_refresh_events,apply_to_fresh_ms");
        try(var renderer=RtxptRenderers.openNativeLighting(PLAN)){
            var access=renderer.extension(RtxptAccess.class).orElseThrow();
            var lighting=renderer.extension(NativeLightingControl.class).orElseThrow();
            access.diagnosticLevel(RtxptDiagnostics.Level.DEEP);
            var javaMonitor=access.diagnosticMonitor();
            lighting.lightingReconstruction(NativeLightingReconstruction.NONE);
            renderer.apply(SceneTransaction.builder(1).resetScene()
                    .upsert(MaterialAsset.builder(1).doubleSided(true).build())
                    .upsert(quad(1,0,1)).upsert(SceneInstance.builder(11,1).build()).build());
            submit(renderer,access,lighting,1,false);checkColor(capture(renderer,output,"initial-clear"),new float[]{1,1,1},"initial-clear");
            assertNoFailures(javaMonitor.poll(),"initial-clear");
            var monitor=access.nativeDiagnosticMonitor();monitor.poll();
            var white=texture(8,255);var transparent=texture(9,0);
            var initial=transmissive(0xff0000ff,.25f);
            var masked=MaterialAsset.builder(3).blendMode(MaterialAsset.BlendMode.MASKED)
                    .baseColorTextureId(9).doubleSided(true).alphaCutoff(.5f).build();
            var mesh=quad(2,1,3);var instance=SceneInstance.builder(22,2).build();
            List<Step> steps=List.of(
                    new Step("new-material-red",SceneTransaction.builder(2).upsert(white).upsert(initial)
                            .upsert(mesh).upsert(instance).build(),true,new float[]{.25f,0,0}),
                    new Step("new-material-idle",SceneTransaction.builder(3).build(),true,new float[]{.25f,0,0}),
                    new Step("true-alpha-open",SceneTransaction.builder(4).upsert(transparent).upsert(masked).build(),true,new float[]{1,1,1}),
                    new Step("true-alpha-closed",SceneTransaction.builder(5).upsert(texture(9,255)).build(),true,new float[]{0,0,0}),
                    new Step("alpha-idle",SceneTransaction.builder(6).build(),true,new float[]{0,0,0}),
                    new Step("transmission-red-quarter",SceneTransaction.builder(7).upsert(initial).build(),true,new float[]{.25f,0,0}),
                    new Step("transmission-red-half",SceneTransaction.builder(8).upsert(transmissive(0xff0000ff,.5f)).build(),true,new float[]{.5f,0,0}),
                    new Step("transmission-idle",SceneTransaction.builder(9).build(),true,new float[]{.5f,0,0}),
                    new Step("material-inactive",SceneTransaction.builder(10).removeInstance(22).build(),false,new float[]{1,1,1}),
                    new Step("inactive-material-green",SceneTransaction.builder(11).upsert(transmissive(0xff00ff00,.5f)).build(),false,new float[]{1,1,1}),
                    new Step("retained-material-return",SceneTransaction.builder(12).upsert(instance).build(),true,new float[]{0,.5f,0}),
                    new Step("explicit-material-remove",SceneTransaction.builder(13).removeInstance(22).removeMesh(2)
                            .removeMaterial(3).removeTextures(8,9).build(),false,new float[]{1,1,1}),
                    new Step("explicit-material-redeclare",SceneTransaction.builder(14).upsert(texture(8,255))
                            .upsert(transmissive(0xff0000ff,.75f)).upsert(mesh).upsert(instance).build(),true,new float[]{.75f,0,0}),
                    new Step("redeclared-material-idle",SceneTransaction.builder(15).build(),true,new float[]{.75f,0,0}));
            long sequence=1;
            for(var step:steps){
                ++sequence;long begin=System.nanoTime();renderer.apply(step.transaction());
                submit(renderer,access,lighting,sequence,step.caster());
                double elapsed=(System.nanoTime()-begin)/1_000_000.0;
                assertNoFailures(javaMonitor.poll(),step.name());
                var snapshot=monitor.poll();require(snapshot.lostEvents()==0,"Lost native events at "+step.name());
                long materials=count(snapshot,"Materials");
                require(materials==0,"Direct material invoked full beauty registration at "+step.name()+": "+materials);
                var tlas=snapshot.events().stream().filter(event->event.stage().equals("TlasBuild")).toList();
                require(tlas.size()==1,"Missing actual TLAS build/reuse observation at "+step.name());
                if(step.name().equals("alpha-idle")||step.name().equals("transmission-idle"))
                    require(tlas.getFirst().items()==0&&(tlas.getFirst().flags()&4)!=0,
                            "Unchanged direct scene rebuilt its TLAS at "+step.name());
                float[] actual=capture(renderer,output,step.name());checkColor(actual,step.expected(),step.name());
                rows.add(sequence+","+step.name()+","+actual[0]+","+actual[1]+","+actual[2]
                        +","+materials+","+count(snapshot,"SceneFullRefresh")+","+elapsed);
                System.out.println("DIRECT_MATERIAL_LIFECYCLE sequence="+sequence+" case="+step.name()+" PASS materials=0 rgb="+Arrays.toString(actual));
            }
            require(lighting.nativeLightingExecution().fullBeautyFrames()==0,"Direct contract executed beauty shading");
        }
        Files.write(output.resolve("direct-material-lifecycle.csv"),rows,StandardCharsets.UTF_8);
        System.out.println("DIRECT_MATERIAL_LIFECYCLE_NATIVE PASS freshFrames=15 newMaterial=true trueAlpha=true transmission=true inactiveReturn=true explicitRedeclare=true idleMaterials=0 directMaterials=0 propagatedErrors=0 publicModeSwitchSupported=false sameSessionBeautySwitchTested=false");
    }
    /** Separate, already supported public beauty entry; no synthetic mode switch. */
    private static void beautyEntry(Path output)throws Exception {
        final int beautySize=320; // Win32 enforces a minimum top-level window width.
        try(var renderer=RtxptRenderers.openHiddenWindow(RtxptRenderers.nativeLightingConfiguration())){
            var access=renderer.extension(RtxptAccess.class).orElseThrow();access.diagnosticLevel(RtxptDiagnostics.Level.DEEP);
            var javaMonitor=access.diagnosticMonitor();
            renderer.apply(SceneTransaction.builder(1).resetScene().upsert(emissive(1,new Rgb(0,4,0)))
                    .upsert(quad(1,0,1)).upsert(SceneInstance.builder(11,1).build()).build());
            renderer.submit(RenderFrameRequest.builder(1,beautySize,beautySize,CAMERA).minimumSceneRevision(1).build());access.awaitIdle();
            assertNoFailures(javaMonitor.poll(),"beauty-green");checkBeauty(access,output,"beauty-green",1,beautySize);
            var monitor=access.nativeDiagnosticMonitor();monitor.poll();
            renderer.apply(SceneTransaction.builder(2).upsert(emissive(2,new Rgb(4,0,0))).upsert(quad(1,0,2)).build());
            renderer.submit(RenderFrameRequest.builder(2,beautySize,beautySize,CAMERA).minimumSceneRevision(2).build());access.awaitIdle();
            assertNoFailures(javaMonitor.poll(),"beauty-new-red");
            var snapshot=monitor.poll();require(snapshot.lostEvents()==0&&count(snapshot,"Materials")>0,
                    "A genuinely new beauty material did not use original registration");
            require(!access.renderingState().externalLighting(),"Separate beauty entry selected independent lighting");
            checkBeauty(access,output,"beauty-new-red",0,beautySize);
        }
        System.out.println("BEAUTY_MATERIAL_ENTRY_NATIVE PASS freshFrames=2 originalRegistration=true emissivePixels=true propagatedErrors=0 separateSession=true sameSessionBeautySwitchTested=false");
    }
    private static MaterialAsset emissive(long id,Rgb color){
        return MaterialAsset.builder(id).baseColorRgba8(0xff000000).emissive(color).doubleSided(true).build();
    }
    /** Real beauty lifecycle, including a domain edit while the definition has no users. */
    private static void beautyDormant(Path output)throws Exception {
        final int beautySize=320;
        try(var renderer=RtxptRenderers.openHiddenWindow(RtxptRenderers.nativeLightingConfiguration())){
            var access=renderer.extension(RtxptAccess.class).orElseThrow();
            access.diagnosticLevel(RtxptDiagnostics.Level.DEEP);
            var javaMonitor=access.diagnosticMonitor();
            var instance=SceneInstance.builder(11,1).build();
            renderer.apply(SceneTransaction.builder(1).resetScene().upsert(emissive(1,new Rgb(0,4,0)))
                    .upsert(quad(1,0,1)).upsert(instance).build());
            submitBeauty(renderer,access,1,beautySize);
            assertNoFailures(javaMonitor.poll(),"beauty-dormant-initial");
            checkBeauty(access,output,"beauty-dormant-initial",1,beautySize);
            var monitor=access.nativeDiagnosticMonitor();monitor.poll();
            renderer.apply(SceneTransaction.builder(2).removeInstance(11).build());
            submitBeauty(renderer,access,2,beautySize);
            assertNoFailures(javaMonitor.poll(),"beauty-dormant-empty");
            checkBeautyAbsent(access,output,"beauty-dormant-empty",beautySize);
            renderer.apply(SceneTransaction.builder(3).upsert(instance).build());
            submitBeauty(renderer,access,3,beautySize);
            assertNoFailures(javaMonitor.poll(),"beauty-dormant-return");
            checkBeauty(access,output,"beauty-dormant-return",1,beautySize);
            var masked=MaterialAsset.builder(1).baseColorRgba8(0xff000000).emissive(new Rgb(4,0,0))
                    .blendMode(MaterialAsset.BlendMode.MASKED).baseColorTextureId(8)
                    .alphaCutoff(.5f).doubleSided(true).build();
            var beautyTexture=TextureAsset.builder(8,1,1).colorSpace(TextureAsset.ColorSpace.LINEAR)
                    .pixelsRgba8(new byte[]{-1,-1,-1,-1}).build();
            renderer.apply(SceneTransaction.builder(4).removeInstance(11).upsert(beautyTexture).upsert(masked).build());
            submitBeauty(renderer,access,4,beautySize);
            assertNoFailures(javaMonitor.poll(),"beauty-dormant-domain-empty");
            checkBeautyAbsent(access,output,"beauty-dormant-domain-empty",beautySize);
            renderer.apply(SceneTransaction.builder(5).upsert(instance).build());
            submitBeauty(renderer,access,5,beautySize);
            assertNoFailures(javaMonitor.poll(),"beauty-dormant-domain-return");
            checkBeauty(access,output,"beauty-dormant-domain-return",0,beautySize);
            require(monitor.poll().lostEvents()==0,"Lost native beauty dormant events");
            require(!access.renderingState().externalLighting(),"Beauty dormant fixture selected independent lighting");
        }
        System.out.println("BEAUTY_DORMANT_NATIVE PASS freshFrames=5 inactiveReturn=true inactiveDomainEdit=true emissivePixels=true propagatedErrors=0 completedAsyncOmmBakeTested=false");
    }
    private static void submitBeauty(Renderer renderer,RtxptAccess access,long sequence,int extent){
        renderer.submit(RenderFrameRequest.builder(sequence,extent,extent,CAMERA).minimumSceneRevision(sequence).build());
        access.awaitIdle();
    }
    private static void checkBeautyAbsent(RtxptAccess access,Path output,String name,int extent)throws Exception {
        Path path=output.resolve(name+".png");access.capture(path);var image=ImageIO.read(path.toFile());
        require(image!=null&&image.getWidth()==extent&&image.getHeight()==extent,"Missing fresh dormant beauty image");
        int rgb=image.getRGB(extent/2,extent/2);int red=(rgb>>>16)&255,green=(rgb>>>8)&255,blue=rgb&255;
        require(!(red>32&&red>green+20&&red>blue+20)&&!(green>32&&green>red+20&&green>blue+20),
                "Dormant emissive geometry remained in beauty output: "+name);
    }
    private static void checkBeauty(RtxptAccess access,Path output,String name,int expectedChannel,int extent)throws Exception {
        Path path=output.resolve(name+".png");access.capture(path);var image=ImageIO.read(path.toFile());
        require(image!=null&&image.getWidth()==extent&&image.getHeight()==extent,"Missing fresh beauty image");
        int rgb=image.getRGB(extent/2,extent/2);int[] values={(rgb>>>16)&255,(rgb>>>8)&255,rgb&255};
        require(values[expectedChannel]>32,"Emissive material disappeared from beauty output");
        for(int channel=0;channel<3;channel++)if(channel!=expectedChannel)
            require(values[expectedChannel]>values[channel]+20,"New emissive material did not reach beauty output: "+Arrays.toString(values));
    }
    private static MaterialAsset transmissive(int rgba,float transmission){
        return MaterialAsset.builder(3).blendMode(MaterialAsset.BlendMode.TRANSLUCENT).baseColorRgba8(rgba)
                .baseColorTextureId(8).transmission(transmission).roughness(.4f).doubleSided(true).build();
    }
    private static TextureAsset texture(long id,int alpha){
        return TextureAsset.builder(id,1,1).colorSpace(TextureAsset.ColorSpace.LINEAR)
                .addressModes(TextureAsset.AddressMode.CLAMP_TO_EDGE,TextureAsset.AddressMode.CLAMP_TO_EDGE)
                .filter(TextureAsset.Filter.NEAREST).pixelsRgba8(new byte[]{-1,-1,-1,(byte)alpha}).build();
    }
    private static MeshAsset quad(long id,float z,long material){
        return MeshAsset.builder(id,new float[]{-2,-2,z,2,-2,z,2,2,z,-2,2,z},new int[]{0,1,2,0,2,3},new long[]{material,material})
                .normals(new float[]{0,0,1,0,0,1,0,0,1,0,0,1}).textureCoordinates(new float[]{0,0,1,0,1,1,0,1}).build();
    }
    private static void submit(Renderer renderer,RtxptAccess access,NativeLightingControl lighting,long sequence,boolean caster){
        float[] lookup=new float[512];for(int index=1;index<lookup.length;index+=2)lookup[index]=1;
        var instances=new ArrayList<NativeLightingPublication.Instance>();
        instances.add(new NativeLightingPublication.Instance(11,1,1,0,0,0,0,1));
        if(caster)instances.add(NativeLightingPublication.Instance.shadowOnly(22));
        lighting.publishLightingAttributes(new NativeLightingPublication(sequence,instances,List.of(),lookup,null));
        renderer.submit(RenderFrameRequest.builder(sequence,SIZE,SIZE,CAMERA).minimumSceneRevision(sequence).build());access.awaitIdle();
        try(var lease=lighting.acquireLightingFrame().orElseThrow()){
            var frame=lease.frame();require(frame.sequence()==sequence&&frame.sceneRevision()==sequence&&frame.attributeGeneration()==sequence,
                    "Stale direct material frame at "+sequence);
            require(frame.width()==SIZE&&frame.height()==SIZE&&frame.planes().size()==2&&frame.readyValue()<frame.completionValue()
                    &&frame.planes().getFirst().memoryHandle()!=0,"Missing real direct output synchronization");
        }
    }
    private static float[] capture(Renderer renderer,Path output,String name)throws Exception {
        Path path=output.resolve(name+".pfm");((RtxptRenderer)renderer).captureNativeLighting(path,0);
        byte[] bytes=Files.readAllBytes(path);int start=0;
        for(int line=0;line<3;line++)while(start<bytes.length&&bytes[start++]!='\n'){}
        require(new String(bytes,0,start,StandardCharsets.US_ASCII).equals("PF\n64 64\n-1.0\n")
                &&bytes.length-start==SIZE*SIZE*12,"Invalid fresh direct material image");
        var values=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);float[] result=new float[3];
        for(int y=30;y<=33;y++)for(int x=30;x<=33;x++)for(int channel=0;channel<3;channel++){
            float value=values.getFloat(start+((SIZE-1-y)*SIZE+x)*12+channel*4);
            require(Float.isFinite(value),"Nonfinite direct material output");result[channel]+=value/16;
        }
        return result;
    }
    private static void checkColor(float[] actual,float[] expected,String name){
        for(int channel=0;channel<3;channel++)require(Math.abs(actual[channel]-expected[channel])<.02f,
                "Wrong fresh pixel at "+name+" channel="+channel+" actual="+actual[channel]+" expected="+expected[channel]);
    }
    private static void assertNoFailures(RtxptDiagnostics.Snapshot snapshot,String name){
        require(snapshot.lostEvents()==0&&snapshot.events().stream().noneMatch(RtxptDiagnostics.Event::failed),"Failed API stage at "+name);
    }
    private static long count(RtxptDiagnostics.NativeSnapshot snapshot,String name){return snapshot.events().stream().filter(event->event.stage().equals(name)).count();}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
