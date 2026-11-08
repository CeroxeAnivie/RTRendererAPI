package top.ceroxe.rt.renderer.rtxpt;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Retained definitions and explicit native variant retirement share one lifetime. */
public final class RtxptMaterialLifetimeSelfTest {
    private static final RenderFrameRequest REQUEST=RenderFrameRequest.builder(0,64,64,
            CameraState.lookAt(0,0,4,0,0,0).build()).build();
    private record Tail(List<String> materials,List<Long> texturesRemoved,List<String> materialsRemoved) { }
    private static String string(ByteBuffer data){
        var bytes=new byte[data.getInt()];data.get(bytes);return new String(bytes,StandardCharsets.UTF_8);
    }
    private static void bytes(ByteBuffer data,int stride){int count=data.getInt();data.position(Math.addExact(data.position(),Math.multiplyExact(count,stride)));}
    private static Tail decode(ByteBuffer memory){
        var data=memory.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        require(data.getInt()==0x52445259,"Current resource protocol");
        for(int count=data.getInt();count>0;count--){
            data.getLong();data.position(data.position()+7*4);data.getLong();
            if(data.getInt()!=0){data.getLong();data.position(data.position()+6*4);}
            bytes(data,1);
        }
        var materials=new ArrayList<String>();
        for(int count=data.getInt();count>0;count--){
            materials.add(string(data));data.position(data.position()+92);
            if(data.getInt()!=0)data.position(data.position()+16);
        }
        for(int count=data.getInt();count>0;count--){
            string(data);
            if(data.getInt()==1)string(data);else for(int attribute=0;attribute<5;attribute++)bytes(data,4);
            for(int groups=data.getInt();groups>0;groups--){string(data);bytes(data,4);}
        }
        for(int count=data.getInt();count>0;count--)string(data);
        for(int count=data.getInt();count>0;count--){string(data);string(data);bytes(data,4);}
        for(int count=data.getInt();count>0;count--)string(data);
        var texturesRemoved=new ArrayList<Long>();
        for(int count=data.getInt();count>0;count--)texturesRemoved.add(data.getLong());
        var materialsRemoved=new ArrayList<String>();
        for(int count=data.getInt();count>0;count--)materialsRemoved.add(string(data));
        require(!data.hasRemaining(),"Material removal tail ends the exact packet");
        return new Tail(List.copyOf(materials),List.copyOf(texturesRemoved),List.copyOf(materialsRemoved));
    }
    private static RtxptScene next(RtxptScene before,SceneTransaction transaction){
        var result=new RtxptScene(before);result.retain(transaction);return result;
    }
    private static RtxptDelta.Prepared prepare(RtxptScene before,RtxptScene after,
            Map<Long,TextureAsset> textures,Map<Long,Map<String,RtxptScene.Variant>> imported,
            RtxptMemoryResources.PacketPool packets){
        return RtxptDelta.prepare(before,after,before.items(REQUEST),after.items(REQUEST),textures,imported,packets);
    }
    private static void commit(RtxptScene scene,RtxptDelta.Prepared delta,
            Map<Long,Map<String,RtxptScene.Variant>> imported){
        imported.keySet().removeIf(id->!scene.materials.containsKey(id));
        for(var variant:delta.materialVariants())imported.computeIfAbsent(variant.material(),id->new LinkedHashMap<>()).put(variant.name(),variant);
    }
    public static void main(String[] args){
        try(var packets=new RtxptMemoryResources.PacketPool()){
            var imported=new HashMap<Long,Map<String,RtxptScene.Variant>>();
            var texture=TextureAsset.builder(7,1,1).pixelsRgba8(new byte[]{-1,-1,-1,-1}).build();
            var replacement=TextureAsset.builder(8,1,1).pixelsRgba8(new byte[]{-1,0,0,-1}).build();
            var material=MaterialAsset.builder(3).baseColorTexture(texture).build();
            var mesh=MeshAsset.builder(11,new float[]{0,0,0,1,0,0,0,1,0},new int[]{0,1,2},new long[]{3}).build();
            var first=SceneInstance.builder(13,11).build();
            var second=SceneInstance.builder(14,11).surfaceVisibility(.5f).build();
            var empty=new RtxptScene();
            var active=next(empty,SceneTransaction.builder(1).upsert(texture).upsert(material).upsert(mesh).upsert(first).upsert(second).build());
            var delta=prepare(empty,active,Map.of(),imported,packets);var tail=decode(delta.memory());
            require(delta.materials()==2&&tail.materials().size()==2&&tail.materialsRemoved().isEmpty(),"Both visibility variants are declared once");
            var names=Set.copyOf(tail.materials());commit(active,delta,imported);
            require(imported.get(3L).keySet().equals(names),"Successful publication retains every imported variant identity");

            var inactive=next(active,SceneTransaction.builder(2).removeInstances(13,14).build());
            delta=prepare(active,inactive,Map.of(7L,texture),imported,packets);tail=decode(delta.memory());
            require(inactive.materials.get(3L)==material&&inactive.meshes.get(11L)==mesh,
                    "Removing last users keeps authored material and mesh definitions");
            require(delta.materials()==0&&tail.materialsRemoved().isEmpty(),"Temporary inactivity never retires material definitions");
            commit(inactive,delta,imported);

            var scalarMaterial=material.toBuilder().roughness(.625f).emissive(new Rgb(.2f,.4f,.8f)).build();
            var scalar=next(inactive,SceneTransaction.builder(3).upsert(scalarMaterial).build());
            delta=prepare(inactive,scalar,Map.of(7L,texture),imported,packets);tail=decode(delta.memory());
            require(delta.materials()==2&&delta.meshes()==0&&delta.changedVariants().isEmpty()
                    &&Set.copyOf(tail.materials()).equals(names)&&tail.materialsRemoved().isEmpty(),
                    "Inactive scalar edits update every imported variant without geometry work");
            require(delta.translated().materials.get(3L)==scalarMaterial,"Inactive edits carry the current exact definition");commit(scalar,delta,imported);

            var reboundMaterial=scalarMaterial.toBuilder().baseColorTexture(replacement).build();
            var rebound=next(scalar,SceneTransaction.builder(4).upsert(replacement).upsert(reboundMaterial).removeTexture(7).build());
            delta=prepare(scalar,rebound,Map.of(7L,texture),imported,packets);tail=decode(delta.memory());
            require(delta.materials()==2&&delta.meshes()==0&&Set.copyOf(tail.materials()).equals(names)
                    &&tail.texturesRemoved().equals(List.of(7L))&&tail.materialsRemoved().isEmpty(),
                    "Inactive texture rebinding precedes explicit removal of its old texture");
            require(delta.translated().materials.get(3L).baseColorTextureId()==8,"All inactive variants use the replacement texture");commit(rebound,delta,imported);

            var returned=next(rebound,SceneTransaction.builder(5).upsert(first).upsert(second).build());
            delta=prepare(rebound,returned,Map.of(8L,replacement),imported,packets);tail=decode(delta.memory());
            require(Set.copyOf(tail.materials()).equals(names)&&tail.materialsRemoved().isEmpty(),
                    "Returning geometry uses the same retained material variant names");commit(returned,delta,imported);

            var removed=next(returned,SceneTransaction.builder(6).removeInstances(13,14).removeMesh(11).removeMaterial(3).removeTexture(8).build());
            delta=prepare(returned,removed,Map.of(8L,replacement),imported,packets);tail=decode(delta.memory());
            require(delta.materials()==0&&Set.copyOf(tail.materialsRemoved()).equals(names)
                    &&tail.materialsRemoved().size()==2&&tail.texturesRemoved().equals(List.of(8L)),
                    "Explicit definition removal retires every historical variant and its final texture");commit(removed,delta,imported);
            require(imported.isEmpty(),"Explicit deletion leaves no stale Java native declaration");
            var unchanged=next(removed,SceneTransaction.builder(7).build());
            require(!prepare(removed,unchanged,Map.of(),imported,packets).hasWork(),"Repeated empty transaction performs no native work");

            var declared=next(unchanged,SceneTransaction.builder(8).upsert(MaterialAsset.builder(99).build()).build());
            require(!prepare(unchanged,declared,Map.of(),imported,packets).hasWork(),"Unused never-imported definition needs no native publication");
            var discarded=next(declared,SceneTransaction.builder(9).removeMaterial(99).build());
            require(!prepare(declared,discarded,Map.of(),imported,packets).hasWork(),"Never-imported definition has no phantom native retirement");
            System.out.println("MATERIAL_LIFETIME_CPU PASS retainedDefinitions=true inactiveScalar=true inactiveRebind=true stableVariants=true explicitAllVariants=true removalTail=true noPhantomRetirement=true");
        }
    }
}
