package top.ceroxe.rt.renderer.rtxpt;

import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Dependency validation and snapshot isolation for persistent retained resource tables. */
public final class RtxptRetainedDependencySelfTest {
    static void reject(RtxptScene scene,SceneTransaction tx){try{new RtxptScene(scene).retain(tx);throw new AssertionError("Dangling reference accepted");}catch(IllegalArgumentException expected){}}
    public static void main(String[] args){
        var original=new RtxptScene();var tx=SceneTransaction.builder(1).resetScene().upsert(TextureAsset.color(1,1,1,new byte[]{-1,-1,-1,-1}));
        for(long id=1;id<=256;id++)tx.upsert(MaterialAsset.builder(id).baseColorTextureId(1).build())
            .upsert(MeshAsset.builder(id,new float[]{0,0,0,1,0,0,0,1,0},new int[]{0,1,2},new long[]{id}).build())
            .upsert(SceneInstance.builder(id,id).build());
        original.retain(tx.build());long before=original.referenceChecks;
        var republished=new RtxptScene(original);var identical=SceneTransaction.builder(2).upsert(original.textures.get(1L));
        for(long id=1;id<=256;id++)identical.upsert(original.materials.get(id).toBuilder().build())
            .upsert(MeshAsset.builder(id,new float[]{0,0,0,1,0,0,0,1,0},new int[]{0,1,2},new long[]{id}).build())
            .upsert(SceneInstance.builder(id,id).build());
        republished.retain(identical.build());
        require(republished.textures.sameSnapshot(original.textures)&&republished.materials.sameSnapshot(original.materials)
            &&republished.meshes.sameSnapshot(original.meshes)&&republished.instances.sameSnapshot(original.instances),
            "Exact immutable republishes retain the original resident snapshots");
        require(republished.referenceChecks==before,"Exact republishes do not delete and revalidate unchanged dependency paths");
        reject(republished,SceneTransaction.builder(3).removeTexture(1).build());
        reject(republished,SceneTransaction.builder(3).removeMaterial(1).build());
        reject(republished,SceneTransaction.builder(3).removeMesh(1).build());
        var light=new RtxptScene(original);light.retain(SceneTransaction.builder(2).upsert(SceneLight.point(1,0,0,1).build()).build());
        require(light.referenceChecks==before,"Light-only transaction checks zero unrelated resource references");
        var orderedLights=light.sortedLights();var sameLight=new RtxptScene(light);
        sameLight.retain(SceneTransaction.builder(3).upsert(light.lights.get(1L)).build());
        require(sameLight.lights.sameSnapshot(light.lights)&&sameLight.sortedLights()==orderedLights,
            "Exact light republication preserves both persistent lights and their ordered view");
        var changed=new RtxptScene(light);changed.retain(SceneTransaction.builder(3).upsert(MaterialAsset.builder(1).baseColorTextureId(1).roughness(.7f).build()).build());
        require(changed.referenceChecks-before==1,"One material validates its one texture dependency");
        require(original.materials.get(1L)!=changed.materials.get(1L)&&original.materials.get(2L)==changed.materials.get(2L),"Immutable snapshots share untouched resources");
        reject(changed,SceneTransaction.builder(4).removeTexture(1).build());
        reject(changed,SceneTransaction.builder(4).removeMaterial(1).build());
        reject(changed,SceneTransaction.builder(4).removeMesh(1).build());
        var removed=new RtxptScene(changed);removed.retain(SceneTransaction.builder(4).removeInstance(1).removeMesh(1).removeMaterial(1).build());
        require(!removed.meshes.containsKey(1L)&&changed.meshes.containsKey(1L),"Joint removal updates dependencies and preserves prior snapshot");
        var restored=new RtxptScene(removed);restored.retain(SceneTransaction.builder(5).upsert(original.materials.get(1L)).upsert(original.meshes.get(1L)).upsert(original.instances.get(1L)).build());
        reject(restored,SceneTransaction.builder(6).removeMaterial(1).build());
        var clear=SceneTransaction.builder(6);for(long id=1;id<=256;id++)clear.removeInstance(id).removeMesh(id).removeMaterial(id);clear.removeTexture(1);
        var empty=new RtxptScene(restored);empty.retain(clear.build());require(empty.meshes.isEmpty()&&empty.textures.isEmpty(),"Joint complete removal validates correctly");
        System.out.println("RETAINED_DEPENDENCIES PASS meshes=256 lightReferenceChecks=0 materialReferenceChecks=1 danglingRejections=7 snapshotIsolation=true removeRestore=true exactRepublishSnapshot=true");
    }
}
