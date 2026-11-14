package top.ceroxe.rt.renderer.rtxpt;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Counts actual list reads and owner-thread allocation; also runs against a published baseline. */
public final class RtxptLocalPreparationSelfTest {
    private static final RtxptMemoryResources.PacketPool PACKETS=new RtxptMemoryResources.PacketPool();
    private static final ThreadMXBean ALLOCATION=(ThreadMXBean)ManagementFactory.getThreadMXBean();
    private static final boolean OBSERVE=Boolean.getBoolean("localPreparation.observe");
    private static final class Reads extends AbstractList<RtxptScene.Item> {
        final List<RtxptScene.Item> source;long reads;
        Reads(List<RtxptScene.Item> source){this.source=source;}
        @Override public int size(){return source.size();}
        @Override public RtxptScene.Item get(int index){reads++;return source.get(index);}
    }
    private static SceneTransaction fixture(int count){
        var tx=SceneTransaction.builder(1).resetScene();
        for(long id=1;id<=count;id++)tx.upsert(TextureAsset.color(id,1,1,new byte[]{-1,-1,-1,-1}))
            .upsert(MaterialAsset.builder(id).baseColorTextureId(id).build())
            .upsert(MeshAsset.builder(id,new float[]{0,0,0,1,0,0,0,1,0},new int[]{0,1,2},new long[]{id}).build())
            .upsert(SceneInstance.builder(id,id).build());
        return tx.build();
    }
    private static MaterialAsset material(float roughness){return MaterialAsset.builder(1).baseColorTextureId(1).roughness(roughness).build();}
    private static long median(long[] values){Arrays.sort(values);return values[values.length/2];}
    private static long allocation(){return ALLOCATION.isThreadAllocatedMemorySupported()&&ALLOCATION.isThreadAllocatedMemoryEnabled()?ALLOCATION.getThreadAllocatedBytes(Thread.currentThread().threadId()):-1;}
    private static Object metric(RtxptDelta.Prepared result,String name){
        try{var work=result.getClass().getDeclaredMethod("work").invoke(result);return work.getClass().getDeclaredMethod(name).invoke(work);}
        catch(ReflectiveOperationException unavailable){return null;}
    }
    private static Map<String,Object> observe(int count,Path output)throws Exception{
        var original=new RtxptScene();original.nativeLighting=true;original.retain(fixture(count));
        var frame=RtxptAdapterSelfTest.frame(0);var items=original.items(frame);
        var stable=new Reads(items);require(RtxptScene.sameStructure(stable,stable),"Same immutable list is structurally equal");
        long stableReads=stable.reads;if(!OBSERVE)require(stableReads==0,"Same-list structure check must not read any item");
        var paths=new HashMap<Long,Path>();for(long id=1;id<=count;id++)paths.put(id,output.resolve("resident-t"+id+".png"));
        var rows=new ArrayList<Object>();
        for(String kind:List.of("material","texture-rgb","texture-alpha","vertex-color","mesh-baseline","pose","light","surface-fade")){
            long[] allocations=new long[5],wall=new long[5],oldReads=new long[5],newReads=new long[5];RtxptDelta.Prepared result=null;
            for(int sample=-2;sample<5;sample++){
                var changed=new RtxptScene(original);var tx=SceneTransaction.builder(2);
                switch(kind){
                    case "material"->tx.upsert(material(.73f));
                    case "texture-rgb"->tx.upsert(TextureAsset.color(1,1,1,new byte[]{32,64,96,-1}));
                    case "texture-alpha"->tx.upsert(TextureAsset.color(1,1,1,new byte[]{-1,-1,-1,64}));
                    case "vertex-color"->tx.upsert(original.meshes.get(1L).toBuilder().vertexColorsRgba8(new int[]{0xff0000ff,0xff0000ff,0xff0000ff}).build());
                    case "mesh-baseline"->tx.upsert(original.meshes.get(1L).toBuilder().lightmapCoordinates(new float[]{.1f,.2f,.3f,.4f,.5f,.6f}).build());
                    case "pose"->tx.upsert(SceneInstance.builder(1,1).transform(new AffineTransform(new float[]{1,0,0,2,0,1,0,0,0,0,1,0})).build());
                    case "light"->tx.upsert(SceneLight.point(1,0,0,2).build());
                    case "surface-fade"->tx.upsert(SceneInstance.builder(1,1).surfaceVisibility(.37f).build());
                    default->throw new AssertionError(kind);
                }
                changed.retain(tx.build());var before=new Reads(items);var after=new Reads(changed.items(frame));
                Path parent=Files.createDirectories(output.resolve(count+"-"+kind+"-"+sample));
                long bytes=allocation(),start=System.nanoTime();
                result=RtxptDelta.prepare(original,changed,before,after,Map.of(),PACKETS);
                long elapsed=System.nanoTime()-start,allocated=allocation()-bytes;
                if(sample>=0){allocations[sample]=allocated;wall[sample]=elapsed;oldReads[sample]=before.reads;newReads[sample]=after.reads;}
                if(!OBSERVE){
                    require(before.reads==0&&after.reads==0,"Independent edit must not scan frame lists: "+kind);
                    if(count>=1024 && metric(result,"inspectedMapNodes") instanceof Number visited)
                        require(visited.longValue()<count/4,"Independent edit scanned resource tables: "+kind);
                    if(Set.of("vertex-color","mesh-baseline","light","surface-fade").contains(kind)){
                        require(!result.hasWork(),"Attribute/light edits must not create an importer delta");
                        try(var files=Files.list(parent)){require(files.findAny().isEmpty(),"No unused export directory or model files");}
                    }
                    if(kind.equals("material"))require(result.materials()==1&&result.meshes()==0&&result.textures()==0,"One independent material delta");
                    require(result.meshes()==0,"No unrelated geometry export: "+kind);
                }
            }
            var row=RtxptJson.object("case",kind,"meshCount",count,"oldItemReads",median(oldReads),"currentItemReads",median(newReads),
                "allocatedBytesP50",median(allocations),"wallNanosP50",median(wall),"exportedMeshes",result.meshes(),"exportedMaterials",result.materials(),
                "exportedTextures",result.textures(),"hasImporterDelta",result.hasWork(),"dependencyVisits",metric(result,"dependencyVisits"),"inspectedMapNodes",metric(result,"inspectedMapNodes"));
            rows.add(row);System.out.println("LOCAL_PREPARATION "+RtxptJson.encode(row));
        }
        if(!OBSERVE){
            var moved=new RtxptScene(original);moved.retain(SceneTransaction.builder(2).upsert(SceneInstance.builder(1,1).transform(new AffineTransform(new float[]{1,0,0,2,0,1,0,0,0,0,1,0})).build()).build());
            require(RtxptScene.sameStructure(items,moved.items(frame)),"Pose keeps the structural identity");
            var restored=new RtxptScene(moved);restored.retain(SceneTransaction.builder(3).removeInstance(1).build());
            require(!RtxptScene.sameStructure(items,restored.items(frame)),"Removal invalidates mapping");
            require(original.instances.size()==count&&original.items(frame)==items,"Prior snapshot remains isolated");
            RtxptRetainedDependencySelfTest.reject(original,SceneTransaction.builder(2).removeMaterial(1).build());
            original.validateAssetBudget(RendererResourceOptions.high());
        }
        return RtxptJson.object("meshes",count,"stableSameListReads",stableReads,"rows",rows);
    }
    private static void persistentDiff(){
        var random=new Random(8291);var actual=new RtxptPersistentMap<Long>();var expected=new TreeMap<Long,Long>();
        for(int run=0;run<500;run++){
            var before=new RtxptPersistentMap<>(actual);var previous=new TreeMap<>(expected);
            for(int edit=0;edit<5;edit++){long id=random.nextInt(200);if(random.nextBoolean()){long value=random.nextLong();actual.put(id,value);expected.put(id,value);}else{actual.remove(id);expected.remove(id);}}
            var reconstructed=new TreeMap<>(previous);actual.forEachChanged(before,(id,value)->{if(value==null)reconstructed.remove(id);else reconstructed.put(id,value);});
            require(reconstructed.equals(expected),"Persistent diff handles rotations, updates and removals");
            require(before.equals(previous),"Persistent diff preserves the old snapshot");
        }
    }
    private static void sharedResources(Path output)throws Exception{
        var scene=new RtxptScene();var tx=SceneTransaction.builder(1).resetScene();
        for(long id=1;id<=2;id++)tx.upsert(MaterialAsset.builder(id).build())
            .upsert(MeshAsset.builder(id,new float[]{0,0,0,1,0,0,0,1,0},new int[]{0,1,2},new long[]{id}).build());
        for(long id=1;id<=256;id++)tx.upsert(SceneInstance.builder(id,1).build());
        tx.upsert(SceneInstance.builder(257,2).build());scene.retain(tx.build());var frame=RtxptAdapterSelfTest.frame(0);
        var shared=new RtxptScene(scene);shared.retain(SceneTransaction.builder(2).upsert(MaterialAsset.builder(1).roughness(.7f).build()).build());
        var delta=RtxptDelta.prepare(scene,shared,scene.items(frame),shared.items(frame),Map.of(),PACKETS);
        require(delta.materials()==1&&delta.meshes()==0&&delta.work().dependencyVisits()>=256,"Shared resource visits all actual users and deduplicates variants");
        var mixed=new RtxptScene(scene);mixed.retain(SceneTransaction.builder(2).upsert(MaterialAsset.builder(2).roughness(.7f).build())
            .upsert(SceneInstance.builder(1,1).transform(new AffineTransform(new float[]{1,0,0,2,0,1,0,0,0,0,1,0})).build()).build());
        var mixedDelta=RtxptDelta.prepare(scene,mixed,scene.items(frame),mixed.items(frame),Map.of(),PACKETS);
        require(mixedDelta.work().dependencyVisits()<16&&mixedDelta.meshes()==0,"Mixed material/pose avoids unrelated users of unchanged pose variant");
        var payload=new byte[mixedDelta.memory().remaining()];mixedDelta.memory().duplicate().get(payload);require(new String(payload,java.nio.charset.StandardCharsets.ISO_8859_1).contains("i1"),"Mixed memory delta publishes changed pose");
        try{scene.validateAssetBudget(new RendererResourceOptions(1,1,1));throw new AssertionError("Tiny asset budget accepted");}
        catch(IllegalArgumentException expected){ }
        System.out.println("LOCAL_PREPARATION_SHARED PASS users=256 mixedDependencies="+mixedDelta.work().dependencyVisits());
    }
    private static void transientDependencies(){
        int count=128;
        var original=new RtxptScene();original.nativeLighting=true;original.retain(fixture(count));
        var primitives=FramePrimitiveBatch.builder();
        for(long id=1;id<=count;id++)primitives.add(PrimitiveInstance.builder(id).build());
        var frame=RtxptAdapterSelfTest.frame(0).toBuilder().primitiveBatch(primitives.build()).build();
        var changed=new RtxptScene(original);var edits=SceneTransaction.builder(2);
        for(long id=1;id<=count;id++)edits.upsert(MeshAsset.builder(id,
                new float[]{0,0,0,2,0,0,0,2,0},new int[]{0,1,2},new long[]{id}).build());
        changed.retain(edits.build());
        var before=new Reads(original.items(frame));var after=new Reads(changed.items(frame));
        var delta=RtxptDelta.prepare(original,changed,before,after,Map.of(),PACKETS);
        require(delta.meshes()==count,"Every changed mesh shared by resident and transient users is prepared");
        require(before.reads+after.reads<=count*8L,"Many changed meshes must not rescan the complete transient tail");
        require(delta.work().itemReads()<=count*8L,"Transient dependency work grows with the tail, not mesh count times tail");
        var bytes=new byte[delta.memory().remaining()];delta.memory().duplicate().get(bytes);
        var payload=new String(bytes,java.nio.charset.StandardCharsets.ISO_8859_1);
        for(int i=0;i<count;i++)require(payload.contains("p"+i),"Changed geometry reattaches every transient user");
        var emptyFrame=frame.toBuilder().primitiveBatch(FramePrimitiveBatch.empty()).build();
        var retired=RtxptDelta.prepare(original,changed,original.items(frame),changed.items(emptyFrame),Map.of(),PACKETS);
        require(retired.meshes()==count,"Retiring transient users preserve all resident geometry updates");
        require(retired.work().itemReads()<=count*8L,"Retired transient names share the same bounded index");
        System.out.println("LOCAL_PREPARATION_TRANSIENT PASS meshes="+count+" reads="+(before.reads+after.reads));
    }
    private static void lightingModeSelection(){
        var beauty=new RtxptScene();beauty.retain(fixture(1));
        beauty.retain(SceneTransaction.builder(2).upsert(SceneInstance.builder(1,1).surfaceVisibility(.75f).build()).build());
        var frame=RtxptAdapterSelfTest.frame(0).toBuilder().primitiveBatch(FramePrimitiveBatch.builder()
                .add(PrimitiveInstance.builder(1).surfaceVisibility(.6f).build()).build()).build();
        var previous=beauty.items(frame);var lighting=new RtxptScene(beauty);
        lighting.selectNativeLighting();var selected=lighting.items(frame);
        require(previous.get(0).visibility()==.75f&&previous.get(1).visibility()==.6f,"Mode selection preserves the imported snapshot");
        require(selected.get(0).visibility()==1&&selected.get(1).visibility()==1,"Mode selection canonicalizes retained and primitive variants");
        require(lighting.instances.get(1L).surfaceVisibility()==.75f,"Physical fade remains authored instance data");
        require(!RtxptScene.sameStructure(previous,selected),"Mode selection publishes the actual geometry identity change");
        var delta=RtxptDelta.prepare(beauty,lighting,previous,selected,Map.of(),PACKETS);
        require(delta.hasWork()&&delta.meshes()==1,"One canonical mesh replaces both faded geometry variants");
        lighting.selectNativeLighting();require(lighting.items(frame)==selected,"Repeated mode selection is a no-op");
        System.out.println("LOCAL_PREPARATION_MODE_SELECTION PASS retained=true primitive=true residentSnapshot=true");
    }
    public static void main(String[] arguments)throws Exception{try{
        Path output=Path.of(System.getProperty("localPreparation.output","build/reports/local-scene-preparation"));Files.createDirectories(output);
        if(!OBSERVE){persistentDiff();sharedResources(output);transientDependencies();lightingModeSelection();}var results=new ArrayList<Object>();for(int count:new int[]{256,4096})results.add(observe(count,output));
        Files.writeString(output.resolve("result.json"),RtxptJson.encode(RtxptJson.object("observationOnly",OBSERVE,"cases",results)));
        System.out.println("LOCAL_PREPARATION_ALL PASS observationOnly="+OBSERVE+" noGpu=true");
    }finally{PACKETS.close();}}
}
