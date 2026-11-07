package top.ceroxe.rt.renderer.rtxpt;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Memory ownership, rectangle rows, generation rejection and file-free resource planning. */
public final class RtxptDynamicResourcesSelfTest {
    private static final RtxptMemoryResources.PacketPool PACKETS=new RtxptMemoryResources.PacketPool();
    static void reject(Runnable action){try{action.run();}catch(IllegalArgumentException|ArithmeticException expected){return;}throw new AssertionError("Expected invalid input rejection");}
    static void emission(){
        var radiance=new Rgb(.5f,.25f,2f);var changedRadiance=new Rgb(Math.nextUp(.5f),.25f,2f);
        require(Math.round(radiance.red()*255)==Math.round(changedRadiance.red()*255),"Regression inputs share the same RGBA8 red quantization");
        var first=MaterialAsset.builder(7).baseColorRgba8(0x7f123456).blendMode(MaterialAsset.BlendMode.MASKED)
                .roughness(.625f).metallic(.375f).alphaCutoff(.3125f).doubleSided(true).indexOfRefraction(1.625f).emissive(radiance).build();
        var changed=first.toBuilder().emissive(changedRadiance).build();
        require(first.emissiveRadiance().equals(radiance)&&changed.emissiveRadiance().equals(changedRadiance),"Authored linear HDR emission preserves every float channel");
        require(!first.equals(changed)&&new HashSet<>(List.of(first,changed)).size()==2,"Sub-RGBA8 emission changes remain distinct material values");
        require(first.toBuilder().build().equals(first)&&first.toBuilder().build().hashCode()==first.hashCode(),"Copy builder preserves exact emission and hash identity");
        int packed=0xff804020;float strength=2.75f;
        var expected=new Rgb(32/255.0f*strength,64/255.0f*strength,128/255.0f*strength);
        var convenient=MaterialAsset.builder(7).emissive(packed,strength).build();
        require(convenient.emissiveRadiance().equals(expected),"Packed convenience keeps linear low-byte-red division then intensity multiplication");
        require(convenient.equals(convenient.toBuilder().emissive(expected).build()),"Canonical emission identity is independent of input convenience syntax");
        require(convenient.equals(MaterialAsset.builder(7).emissive(packed&0x00ffffff,strength).build()),"Packed convenience ignores alpha");
        var scene=new RtxptScene();scene.retain(SceneTransaction.builder(1).upsert(first)
                .upsert(MeshAsset.builder(11,new float[]{0,0,0,1,0,0,0,1,0},new int[]{0,1,2},new long[]{7}).build())
                .upsert(SceneInstance.builder(13,11).build()).build());
        var updated=new RtxptScene(scene);updated.retain(SceneTransaction.builder(2).upsert(changed).build());
        int[] changes={0};updated.materials.forEachChanged(scene.materials,(id,value)->{
            if(!Objects.equals(value,scene.materials.get(id)))++changes[0];
        });
        require(changes[0]==1&&scene.materials.get(7L).emissiveRadiance().equals(radiance)
                &&updated.materials.get(7L).emissiveRadiance().equals(changedRadiance),"Retained snapshots and delta selection keep sub-RGBA8 emission edits");
        var variant=new RtxptScene.Variant(7,null,1f);
        emissionPacket(RtxptMemoryResources.pack(new RtxptScene(),scene,List.of(),List.of(variant),List.of(),List.of(),List.of(),Map.of(),PACKETS),first,variant.name());
        var request=RenderFrameRequest.builder(0,64,64,CameraState.lookAt(0,0,4,0,0,0).build()).build();
        var delta=RtxptDelta.prepare(scene,updated,scene.items(request),updated.items(request),Map.of(),PACKETS);
        require(delta.hasWork()&&delta.materials()==1&&delta.meshes()==0&&delta.textures()==0
                &&!delta.mappingsChanged()&&delta.changedVariants().isEmpty(),"Emission-only edit updates one stable material slot without geometry retransmission or remapping");
        emissionPacket(delta.memory(),changed,variant.name());
        var convenientScene=new RtxptScene();convenientScene.retain(SceneTransaction.builder(1).upsert(convenient).build());
        emissionPacket(RtxptMemoryResources.pack(new RtxptScene(),convenientScene,List.of(),List.of(variant),List.of(),List.of(),List.of(),Map.of(),PACKETS),convenient,variant.name());
    }
    private static void emissionPacket(ByteBuffer memory,MaterialAsset material,String slot){
        materialPacket(memory,material,slot,1,-1);
    }
    private static void materialPacket(ByteBuffer memory,MaterialAsset material,String slot,int excluded,int dominantLobe){
        var packet=memory.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        require(packet.isDirect()&&packet.getInt()==0x52445257,"Only the float3, path-decomposition and motion-vector blocking resource protocol is emitted");
        require(packet.getInt()==0&&packet.getInt()==1,"Emission packet contains no textures and one material");
        int length=packet.getInt();byte[] name=new byte[length];packet.get(name);
        require(new String(name,StandardCharsets.UTF_8).equals(slot),"Stable material slot does not include emission content");
        require(packet.getInt()==material.baseColorRgba8(),"Base color keeps its packet position");
        float[] preceding={1f,material.roughness(),material.metallic(),material.transmission(),material.alphaCutoff()};
        for(float value:preceding)require(packet.getInt()==Float.floatToRawIntBits(value),"Material scalar bits precede float3 emission");
        require(packet.getInt()==material.blendMode().ordinal()&&packet.getInt()==(material.doubleSided()?1:0),"Material flags precede float3 emission");
        var radiance=material.emissiveRadiance();
        for(float value:new float[]{radiance.red(),radiance.green(),radiance.blue(),material.indexOfRefraction()})
            require(packet.getInt()==Float.floatToRawIntBits(value),"Emission RGB retains exact float bits and is immediately followed by IOR");
        require(packet.getInt()==excluded&&packet.getInt()==dominantLobe,"Explicit path decomposition fields follow IOR and precede texture bindings");
        int blocking=switch(material.motionVectorBlocking()){case OFF->0;case AUTO_LOW->1;case AUTO_HIGH->2;case FULL->3;};
        require(packet.getInt()==blocking,"Motion-vector blocking maps all original values before texture bindings");
        for(long texture:new long[]{material.baseColorTextureId(),material.normalTextureId(),material.metallicRoughnessTextureId(),material.emissiveTextureId()})
            require(packet.getLong()==texture,"Texture bindings remain aligned after float3 emission");
        require(packet.getInt()==0,"No medium boundary in emission packet");
        for(int count=0;count<6;count++)require(packet.getInt()==0,"Emission-only packet has no geometry, removals, nodes, coverage, texture removals or material removals");
        require(!packet.hasRemaining(),"Float3 packet has no legacy strength field or trailing bytes");
    }
    static void pathDecomposition(){
        var base=MaterialAsset.builder(7).transmission(.9f).roughness(.02f).indexOfRefraction(1.52f)
                .emissive(new Rgb(.25f,.5f,2f)).build();
        require(base.pathDecomposition()==MaterialAsset.PathDecomposition.EXCLUDED
                &&MaterialAsset.opaque(3,0xffffffff).pathDecomposition()==MaterialAsset.PathDecomposition.EXCLUDED,"Existing material authoring remains excluded by default, including transmissive materials");
        try{base.toBuilder().pathDecomposition(null);throw new AssertionError("Null path decomposition accepted");}catch(NullPointerException expected){}
        var modes=new MaterialAsset.PathDecomposition[]{MaterialAsset.PathDecomposition.EXCLUDED,MaterialAsset.PathDecomposition.AUTOMATIC,
                MaterialAsset.PathDecomposition.TRANSMISSION,MaterialAsset.PathDecomposition.REFLECTION};
        int[] excluded={1,0,0,0},dominant={-1,-1,0,1};
        var scene=new RtxptScene();scene.retain(SceneTransaction.builder(1)
                .upsert(base.toBuilder().pathDecomposition(MaterialAsset.PathDecomposition.REFLECTION).build())
                .upsert(MeshAsset.builder(11,new float[]{0,0,0,1,0,0,0,1,0},new int[]{0,1,2},new long[]{7}).build())
                .upsert(SceneInstance.builder(13,11).build()).build());
        var request=RenderFrameRequest.builder(0,64,64,CameraState.lookAt(0,0,4,0,0,0).build()).build();
        var values=new HashSet<MaterialAsset>();
        for(int index=0;index<modes.length;index++){
            var material=base.toBuilder().pathDecomposition(modes[index]).build();values.add(material);
            require(material.pathDecomposition()==modes[index]&&material.toBuilder().build().equals(material)
                    &&material.toBuilder().build().hashCode()==material.hashCode()
                    &&material.toString().contains("pathDecomposition="+modes[index]),"Path decomposition survives copy, equality, hashing and description");
            require(material.blendMode()==base.blendMode()&&material.transmission()==base.transmission()
                    &&material.roughness()==base.roughness(),"Path decomposition does not silently change surface properties");
            var updated=new RtxptScene(scene);updated.retain(SceneTransaction.builder(index+2).upsert(material).build());
            var delta=RtxptDelta.prepare(scene,updated,scene.items(request),updated.items(request),Map.of(),PACKETS);
            require(delta.hasWork()&&delta.materials()==1&&delta.meshes()==0&&delta.textures()==0
                    &&!delta.mappingsChanged()&&delta.changedVariants().isEmpty(),"Every path decomposition change updates exactly one stable material without geometry retransmission");
            materialPacket(delta.memory(),material,"m7_v3f800000",excluded[index],dominant[index]);scene=updated;
        }
        require(values.size()==4,"All four path decomposition values remain distinct");
    }
    static void motionVectorBlocking(){
        var base=MaterialAsset.builder(7).transmission(.9f).roughness(.02f).indexOfRefraction(1.52f)
                .pathDecomposition(MaterialAsset.PathDecomposition.TRANSMISSION).build();
        require(base.motionVectorBlocking()==MaterialAsset.MotionVectorBlocking.OFF
                &&MaterialAsset.opaque(3,0xffffffff).motionVectorBlocking()==MaterialAsset.MotionVectorBlocking.OFF,
                "Existing opaque and transmissive authoring preserves the original Off default");
        try{base.toBuilder().motionVectorBlocking(null);throw new AssertionError("Null motion-vector blocking accepted");}catch(NullPointerException expected){}
        var modes=new MaterialAsset.MotionVectorBlocking[]{MaterialAsset.MotionVectorBlocking.OFF,
                MaterialAsset.MotionVectorBlocking.AUTO_LOW,MaterialAsset.MotionVectorBlocking.AUTO_HIGH,
                MaterialAsset.MotionVectorBlocking.FULL};
        var scene=new RtxptScene();scene.retain(SceneTransaction.builder(1)
                .upsert(base.toBuilder().motionVectorBlocking(MaterialAsset.MotionVectorBlocking.FULL).build())
                .upsert(MeshAsset.builder(11,new float[]{0,0,0,1,0,0,0,1,0},new int[]{0,1,2},new long[]{7}).build())
                .upsert(SceneInstance.builder(13,11).build()).build());
        var request=RenderFrameRequest.builder(0,64,64,CameraState.lookAt(0,0,4,0,0,0).build()).build();
        var values=new HashSet<MaterialAsset>();
        for(int index=0;index<modes.length;index++){
            var material=base.toBuilder().motionVectorBlocking(modes[index]).build();values.add(material);
            require(material.motionVectorBlocking()==modes[index]&&material.toBuilder().build().equals(material)
                    &&material.toBuilder().build().hashCode()==material.hashCode()
                    &&material.toString().contains("motionVectorBlocking="+modes[index]),
                    "Motion-vector blocking survives copy, equality, hashing and description");
            require(material.pathDecomposition()==base.pathDecomposition()&&material.blendMode()==base.blendMode()
                    &&material.transmission()==base.transmission()&&material.roughness()==base.roughness()
                    &&material.indexOfRefraction()==base.indexOfRefraction(),
                    "Motion-vector blocking does not silently change decomposition or surface transport");
            var updated=new RtxptScene(scene);updated.retain(SceneTransaction.builder(index+2).upsert(material).build());
            var delta=RtxptDelta.prepare(scene,updated,scene.items(request),updated.items(request),Map.of(),PACKETS);
            require(delta.hasWork()&&delta.materials()==1&&delta.meshes()==0&&delta.textures()==0
                    &&!delta.mappingsChanged()&&delta.changedVariants().isEmpty(),
                    "Each motion-vector blocking change updates one stable material without geometry retransmission");
            materialPacket(delta.memory(),material,"m7_v3f800000",0,0);scene=updated;
        }
        require(values.size()==4,"All four original motion-vector blocking choices remain distinct");
    }
    static void patches(){
        byte[] pixels=new byte[(4*4+2*2+1)*4];Arrays.fill(pixels,(byte)31);
        var base=TextureAsset.builder(7,4,4).mipChainRgba8(3,pixels).colorSpace(TextureAsset.ColorSpace.LINEAR)
                .addressModes(TextureAsset.AddressMode.CLAMP_TO_EDGE,TextureAsset.AddressMode.REPEAT).filter(TextureAsset.Filter.NEAREST).build();
        var input=ByteBuffer.allocate(20);input.position(3);input.put(new byte[]{1,2,3,4,5,6,7,8,99,99,99,99,9,10,11,12});input.limit(19).position(3);
        var patch=new TexturePatch(7,1,0,0,1,2,12,input);input.put(3,(byte)100);
        var next=base.patched(patch);next.validateReplacementOf(base);
        require(base.rgba8().get(64)==31&&next.rgba8().get(64)==1&&next.rgba8().get(72)==9,"Patch copies input and preserves old generation, using mip row stride");
        require(next.rgba8().get(68)==31&&next.rgba8().get(80)==31,"Untouched texels and mips survive");
        require(next.filter()==base.filter()&&next.colorSpace()==base.colorSpace()&&next.addressU()==base.addressU(),"Sampling metadata survives");
        reject(()->next.validateReplacementOf(null));reject(()->next.validateReplacementOf(next));
        reject(()->base.patched(new TexturePatch(7,1,2,0,1,1,4,ByteBuffer.allocate(4))));
        reject(()->new TexturePatch(7,0,0,0,2,1,4,ByteBuffer.allocate(8)));
        reject(()->new TexturePatch(7,0,0,0,2,2,8,ByteBuffer.allocate(15)));
        var scene=new RtxptScene();scene.retain(SceneTransaction.builder(1).upsert(base).build());
        var updated=new RtxptScene(scene);updated.retain(SceneTransaction.builder(2).upsert(next).build());
        reject(()->new RtxptScene(updated).retain(SceneTransaction.builder(3).upsert(next).build()));
        var finalTexture=next.patched(new TexturePatch(7,2,0,0,1,1,4,ByteBuffer.wrap(new byte[]{4,3,2,1})));
        new RtxptScene(updated).retain(SceneTransaction.builder(3).upsert(finalTexture).build());
        require(scene.textures.get(7L)==base&&updated.textures.get(7L)==next,"Rejected updates preserve snapshots");
    }
    private static SceneTransaction initial(){
        var tx=SceneTransaction.builder(1).resetScene()
                .upsert(MaterialAsset.builder(1).build()).upsert(MaterialAsset.builder(2).build());
        for(long id=1;id<=4;id++)tx.upsert(MeshAsset.builder(id,
                new float[]{0,0,0,1,0,0,1,1,0,0,1,0},new int[]{0,1,2,0,2,3},new long[]{1,2})
                .vertexColorsRgba8(new int[]{0xffa0b0c0,0xffa0b0c0,0xffa0b0c0,0xffa0b0c0}).build())
                .upsert(SceneInstance.builder(id,id).build());
        return tx.build();
    }
    static void resources()throws Exception{
        var old=new RtxptScene();var scene=new RtxptScene();scene.retain(initial());
        var items=scene.items(RenderFrameRequest.builder(0,64,64,CameraState.lookAt(0,0,4,0,0,0).build()).build());
        Path path=Files.createTempDirectory("resource-plan-");
        try{
            var result=RtxptDelta.prepare(old,scene,List.of(),items,Map.of(),PACKETS);
            require(result.memory().isDirect()&&result.memory().getInt(0)==0x52445257,"Direct little-endian resource packet");
            require(result.work().exportedModels()==0,"No file importer path");
            try(var files=Files.list(path)){require(files.findAny().isEmpty(),"Resource planning creates no files");}
            var mesh=scene.meshes.get(1L);var groups=RtxptAttributeBatch.groups(mesh);
            require(groups.size()==2&&groups.stream().mapToInt(g->g.corners().length).sum()==mesh.triangleCount()*3,"Multi-material primitive groups preserve every corner");
            boolean[] seen=new boolean[mesh.triangleCount()*3];
            for(var group:groups){var entry=RtxptAttributeBatch.convert("mesh",mesh,group,null,true,true);
                for(int i=0;i<group.corners().length;i++){int corner=group.corners()[i];require(!seen[corner],"Unique corner mapping");seen[corner]=true;
                    require(mesh.triangleMaterialIds().get(corner/3)==group.material()&&entry.colors()[i]==0xffa0b0c0,"Group and authored color preserved");}}
        }finally{Files.delete(path);}
    }
    public static void main(String[] args)throws Exception{
        try{
            if(args.length==1&&args[0].equals("--motion-vector-blocking-only")){
                motionVectorBlocking();
                System.out.println("MATERIAL_MOTION_VECTOR_BLOCKING_CPU PASS defaultOff=true states=4 wireMapping=true copied=true materialOnlyDelta=true stableSlot=true");
                return;
            }
            if(args.length==1&&args[0].equals("--path-decomposition-only")){
                pathDecomposition();
                System.out.println("MATERIAL_PATH_DECOMPOSITION_CPU PASS defaultExcluded=true states=4 wireMapping=true copied=true materialOnlyDelta=true stableSlot=true");
                return;
            }
            emission();
            if(args.length==1&&args[0].equals("--emission-only")){
                System.out.println("MATERIAL_EMISSION_CPU PASS linearRgb=true subRgba8Distinct=true copied=true retained=true packetFloat3=true stableSlot=true materialOnlyDelta=true");
                return;
            }
            pathDecomposition();motionVectorBlocking();patches();resources();
            System.out.println("DYNAMIC_RESOURCES_CPU PASS rows=true mips=true staleRejected=true snapshotIsolation=true noFiles=true primitiveGroups=true motionVectorBlocking=true");
        }finally{PACKETS.close();}
    }
}
