package top.ceroxe.rt.renderer.api;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongConsumer;

/** Retains only the immutable dependencies required to classify scene-level optical work. */
final class SceneRoutingState {
    private final Map<Long,MaterialAsset> materials;
    private final Map<Long,MeshAsset> meshes;
    private final Map<Long,SceneInstance> instances;
    private final Map<Long,Boolean> meshMedia;
    private final boolean persistentMedia;
    private final boolean hasTransmissiveMeshes;
    private FramePrimitiveBatch lastPrimitives;
    private boolean lastFrameMedia;

    private SceneRoutingState(Map<Long,MaterialAsset> materials,Map<Long,MeshAsset> meshes,Map<Long,SceneInstance> instances) {
        this.materials=materials;this.meshes=meshes;this.instances=instances;
        meshMedia=new HashMap<>();
        boolean anyTransmission=false;
        for(var mesh:meshes.values()) {
            boolean transmission=false;
            var ids=mesh.triangleMaterialIds();
            while(ids.hasRemaining()) {
                long id=ids.get();var material=materials.get(id);
                if(material==null)throw new IllegalArgumentException("Routing cannot resolve material "+id+" for mesh "+mesh.id());
                transmission|=material.transmission()>0;
            }
            meshMedia.put(mesh.id(),transmission);
            anyTransmission|=transmission;
        }
        hasTransmissiveMeshes=anyTransmission;
        boolean media=false;
        for(var instance:instances.values()) {
            media|=instance.mediumBoundary().isPresent();
            media|=transmissiveMesh(instance.meshAssetId());
        }
        persistentMedia=media;
    }

    static SceneRoutingState initial(SceneTransaction scene) {
        Objects.requireNonNull(scene,"initialScene");
        if(!scene.reset())throw new IllegalArgumentException("Scene routing requires a complete reset transaction, not a delta");
        return new SceneRoutingState(Map.of(),Map.of(),Map.of()).updated(scene);
    }

    SceneRoutingState updated(SceneTransaction scene) {
        if(!scene.reset()&&scene.upserts().materials().isEmpty()&&scene.upserts().meshes().isEmpty()
                &&!scene.removals().materialIds().hasRemaining()&&!scene.removals().meshIds().hasRemaining()
                &&!scene.removals().instanceIds().hasRemaining()) {
            boolean changed=false;
            for(var instance:scene.upserts().instances()) {
                var previous=instances.get(instance.id());
                changed|=previous==null||previous.meshAssetId()!=instance.meshAssetId()
                    ||previous.mediumBoundary().isPresent()!=instance.mediumBoundary().isPresent();
            }
            // Pure transforms, lighting and optical-coefficient edits cannot change provider class.
            if(!changed)return this;
        }
        var nextMaterials=scene.reset()?new HashMap<Long,MaterialAsset>():new HashMap<>(materials);
        var nextMeshes=scene.reset()?new HashMap<Long,MeshAsset>():new HashMap<>(meshes);
        var nextInstances=scene.reset()?new HashMap<Long,SceneInstance>():new HashMap<>(instances);
        remove(scene.removals().materialIds(),nextMaterials::remove);
        remove(scene.removals().meshIds(),nextMeshes::remove);
        remove(scene.removals().instanceIds(),nextInstances::remove);
        for(var material:scene.upserts().materials())nextMaterials.put(material.id(),material);
        for(var mesh:scene.upserts().meshes())nextMeshes.put(mesh.id(),mesh);
        for(var instance:scene.upserts().instances())nextInstances.put(instance.id(),instance);
        return new SceneRoutingState(nextMaterials,nextMeshes,nextInstances);
    }

    private static void remove(java.nio.LongBuffer ids,LongConsumer remove) {
        while(ids.hasRemaining())remove.accept(ids.get());
    }

    boolean hasMedia(FramePrimitiveBatch primitives) {
        if(primitives==lastPrimitives)return lastFrameMedia;
        boolean media=persistentMedia||!primitives.mediumBoundaryIndices().isEmpty();
        if(!media&&hasTransmissiveMeshes) {
            for(var primitive:primitives.primitives()) {
                if(transmissiveMesh(primitive.meshAssetId())) { media=true;break; }
            }
        }
        lastPrimitives=primitives;lastFrameMedia=media;
        return media;
    }

    void validatePrimitiveReferences(FramePrimitiveBatch primitives) {
        for(var primitive:primitives.primitives())if(!meshes.containsKey(primitive.meshAssetId()))
            throw new IllegalArgumentException("Routing cannot resolve mesh "+primitive.meshAssetId()+" in the complete scene");
    }

    private boolean transmissiveMesh(long meshId) {
        var result=meshMedia.get(meshId);
        if(result==null)throw new IllegalArgumentException("Routing cannot resolve mesh "+meshId+" in the complete scene");
        return result;
    }
}
