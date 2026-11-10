package top.ceroxe.rt.renderer.rtxpt;

import java.util.*;
import top.ceroxe.rt.renderer.api.*;
import static top.ceroxe.rt.renderer.rtxpt.RtxptAdapterSelfTest.require;

/** Imported definitions and visible instance membership have different lifetimes. */
public final class RtxptMeshDefinitionLifetimeSelfTest {
    private static final RenderFrameRequest REQUEST=RenderFrameRequest.builder(0,64,64,
            CameraState.lookAt(0,0,4,0,0,0).build()).build();
    private final Map<Long,Map<String,RtxptScene.Item>> meshes=new HashMap<>();
    private final Map<Long,Map<String,RtxptScene.Variant>> materials=new HashMap<>();
    private final RtxptMemoryResources.PacketPool packets=new RtxptMemoryResources.PacketPool();
    private RtxptScene scene=new RtxptScene();
    private RtxptDelta.Prepared apply(SceneTransaction tx){
        var next=new RtxptScene(scene);next.retain(tx);
        var delta=RtxptDelta.prepare(scene,next,scene.items(REQUEST),next.items(REQUEST),Map.of(),materials,meshes,packets);
        if(delta.hasWork()){
            require(delta.memory().getInt(0)==RtxptMemoryResources.PROTOCOL,"Explicit mesh lifetime requires the versioned protocol");
            RtxptDelta.commitMeshVariants(meshes,delta);
            materials.keySet().removeIf(id->!next.materials.containsKey(id));
            for(var variant:delta.materialVariants())materials.computeIfAbsent(variant.material(),id->new LinkedHashMap<>()).put(variant.name(),variant);
        }
        scene=next;return delta;
    }
    private static MeshAsset mesh(float z){
        return MeshAsset.builder(11,new float[]{0,0,z,1,0,z,0,1,z},new int[]{0,1,2},new long[]{1})
                .normals(new float[]{0,0,1,0,0,1,0,0,1}).build();
    }
    public static void main(String[] args){
        var test=new RtxptMeshDefinitionLifetimeSelfTest();
        try(test.packets){
            var first=SceneInstance.builder(21,11).build();
            var initial=test.apply(SceneTransaction.builder(1).upsert(MaterialAsset.builder(1).build()).upsert(mesh(0)).upsert(first).build());
            require(initial.meshes()==1&&test.meshes.get(11L).size()==1,"Initial use imports one mesh definition");
            var detached=test.apply(SceneTransaction.builder(2).removeInstance(21).build());
            require(detached.meshes()==0&&detached.removedMeshVariants().isEmpty()&&test.meshes.get(11L).size()==1,
                    "Removing the last instance must preserve its declared imported definition");
            var returned=test.apply(SceneTransaction.builder(3).upsert(first).build());
            require(returned.meshes()==0&&returned.changedVariants().isEmpty()&&returned.materials()==0,
                    "Returning to a declared mesh must not re-export canonical geometry or materials");
            test.apply(SceneTransaction.builder(4).removeInstance(21).build());
            var changed=test.apply(SceneTransaction.builder(5).upsert(mesh(.375f)).build());
            require(changed.meshes()==1&&changed.changedVariants().size()==1&&test.scene.instances.isEmpty(),
                    "Dormant geometry edits update the definition without reviving old nodes");
            var current=test.apply(SceneTransaction.builder(6).upsert(first).build());
            require(current.meshes()==0&&test.scene.meshes.get(11L).positions().get(2)==.375f,
                    "The next activation consumes the latest dormant definition without retransmission");
            // Every active variant survives; only one obsolete flavor may stay
            // dormant. Visibility history must not become an unbounded cache.
            for(int step=0;step<32;step++){
                test.apply(SceneTransaction.builder(7+step).upsert(first.toBuilder().surfaceVisibility((step+1)/40f).build()).build());
                require(test.meshes.get(11L).size()<=2,"Historical visibility variants accumulated without bound");
            }
            var second=SceneInstance.builder(22,11).surfaceVisibility(.9f).build();
            test.apply(SceneTransaction.builder(39).upsert(second).build());
            require(test.meshes.get(11L).size()<=3,"Active variants plus one dormant flavor exceed their bound");
            var keep=test.apply(SceneTransaction.builder(40).removeInstances(21,22).build());
            require(test.meshes.get(11L).size()==1&&keep.removedMeshVariants().size()>=1,"Inactivity must retain one definition and release surplus historical flavors");
            var scalar=test.apply(SceneTransaction.builder(41).upsert(MaterialAsset.builder(1).roughness(.25f).build()).build());
            require(scalar.meshes()==0&&scalar.materials()>0&&test.meshes.get(11L).size()==1,
                    "Dormant material changes stay current without resurrecting geometry users");
            var removed=test.apply(SceneTransaction.builder(42).removeMesh(11).removeMaterial(1).build());
            require(removed.removedMeshVariants().size()==1&&test.meshes.isEmpty()&&!removed.removedMaterials().isEmpty(),
                    "Explicit mesh removal releases the final imported definition and material links");
            require(!test.apply(SceneTransaction.builder(43).build()).hasWork(),"Empty state has no phantom native releases");
            System.out.println("MESH_DEFINITION_LIFETIME_CPU PASS detachRetains=true returnMeshBytesZero=true dormantEditsCurrent=true noNodeResurrection=true activePlusOneBound=true explicitRelease=true");
        }
    }
}
