package top.ceroxe.rt.renderer.backend.vulkan;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import top.ceroxe.rt.renderer.api.*;

/** Cached opacity proof for resources actually reachable from submitted instances. */
final class VulkanSurfaceSceneIndex {
    private static final int OPAQUE = 1, TRANSMISSIVE = 2;
    private final Map<Long, MaterialAsset> materials = new HashMap<>();
    private final Map<Long, Boolean> opaqueTextures = new HashMap<>();
    private final Map<Long, MeshFact> meshes = new HashMap<>();
    private final Map<Long, InstanceFact> instances = new HashMap<>();
    private final Map<Long, Integer> meshFlags = new HashMap<>(), instanceFlags = new HashMap<>();
    private final Map<Long, Set<Long>> textureMaterials = new HashMap<>(), materialMeshes = new HashMap<>(), meshInstances = new HashMap<>();
    private int generalInstances, transmissiveInstances;
    private long generation, batchGeneration = -1;
    private FramePrimitiveBatch cachedBatch;
    private int batchGeneral, batchTransmissive;

    void sceneApplied(VulkanSceneResidency.SceneChangeSet changes) {
        if (changes.reset()) {
            materials.clear(); opaqueTextures.clear(); meshes.clear(); instances.clear();
            meshFlags.clear(); instanceFlags.clear(); textureMaterials.clear(); materialMeshes.clear(); meshInstances.clear();
            generalInstances = transmissiveInstances = 0;
        }
        if (!changes.reset() && unchanged(changes.materials()) && unchanged(changes.textures())
                && unchanged(changes.meshes()) && unchanged(changes.instances())) return;
        var affectedMaterials = new HashSet<Long>();
        var affectedMeshes = new HashSet<Long>();
        var affectedInstances = new HashSet<Long>();
        for (long id : changes.textures().removedIdentities()) {
            opaqueTextures.remove(id); affectedMaterials.addAll(textureMaterials.getOrDefault(id, Set.of()));
        }
        for (var write : changes.textures().writes()) {
            opaqueTextures.put(write.id(), opaque(write.value()));
            affectedMaterials.addAll(textureMaterials.getOrDefault(write.id(), Set.of()));
        }
        for (long id : changes.materials().removedIdentities()) {
            MaterialAsset previous = materials.remove(id);
            if (previous != null) remove(textureMaterials, previous.baseColorTextureId(), id);
            affectedMaterials.add(id);
        }
        for (var write : changes.materials().writes()) {
            MaterialAsset previous = materials.put(write.id(), write.value());
            if (previous != null) remove(textureMaterials, previous.baseColorTextureId(), write.id());
            if (write.value().baseColorTextureId() >= 0) add(textureMaterials, write.value().baseColorTextureId(), write.id());
            affectedMaterials.add(write.id());
        }
        for (long id : affectedMaterials) affectedMeshes.addAll(materialMeshes.getOrDefault(id, Set.of()));
        for (long id : changes.meshes().removedIdentities()) {
            MeshFact previous = meshes.remove(id);
            if (previous != null) for (long material : previous.materials) remove(materialMeshes, material, id);
            meshFlags.remove(id); affectedMeshes.add(id);
        }
        for (var write : changes.meshes().writes()) {
            MeshFact previous = meshes.put(write.id(), mesh(write.value()));
            if (previous != null) for (long material : previous.materials) remove(materialMeshes, material, write.id());
            for (long material : meshes.get(write.id()).materials) add(materialMeshes, material, write.id());
            affectedMeshes.add(write.id());
        }
        for (long id : changes.instances().removedIdentities()) {
            InstanceFact previous = instances.remove(id);
            if (previous != null) remove(meshInstances, previous.mesh, id);
            affectedInstances.add(id);
        }
        for (var write : changes.instances().writes()) {
            var source = write.value();
            InstanceFact value = new InstanceFact(source.meshAssetId(), source.surfaceVisibility() == 1,
                    source.mediumBoundary().map(boundary -> !boundary.surfaceInteraction()).orElse(false));
            InstanceFact previous = instances.put(write.id(), value);
            if (previous != null) remove(meshInstances, previous.mesh, write.id());
            add(meshInstances, value.mesh, write.id()); affectedInstances.add(write.id());
        }
        for (long id : affectedMeshes) {
            MeshFact fact = meshes.get(id);
            if (fact != null) meshFlags.put(id, flags(fact));
            affectedInstances.addAll(meshInstances.getOrDefault(id, Set.of()));
        }
        for (long id : affectedInstances) {
            Integer previous = instanceFlags.remove(id);
            if (previous != null) { if ((previous & OPAQUE) == 0) generalInstances--; if ((previous & TRANSMISSIVE) != 0) transmissiveInstances--; }
            InstanceFact fact = instances.get(id);
            if (fact != null) {
                int flags = flags(fact);
                instanceFlags.put(id, flags);
                if ((flags & OPAQUE) == 0) generalInstances++;
                if ((flags & TRANSMISSIVE) != 0) transmissiveInstances++;
            }
        }
        generation++;
    }

    boolean opaque() { return generalInstances == 0; }
    int transmissiveCount() { return transmissiveInstances; }
    boolean opaque(FramePrimitiveBatch batch) { prepare(batch); return generalInstances == 0 && batchGeneral == 0; }
    int transmissiveCount(FramePrimitiveBatch batch) { prepare(batch); return Math.addExact(transmissiveInstances, batchTransmissive); }

    private void prepare(FramePrimitiveBatch batch) {
        if (cachedBatch == batch && batchGeneration == generation) return;
        batchGeneral = batchTransmissive = 0;
        for (PrimitiveInstance primitive : batch.primitives()) {
            int flags = flags(new InstanceFact(primitive.meshAssetId(), primitive.surfaceVisibility() == 1,
                    primitive.mediumBoundary().map(boundary -> !boundary.surfaceInteraction()).orElse(false)));
            if ((flags & OPAQUE) == 0) batchGeneral++;
            if ((flags & TRANSMISSIVE) != 0) batchTransmissive++;
        }
        cachedBatch = batch; batchGeneration = generation;
    }

    private int flags(InstanceFact instance) {
        if (instance.volumeOnly) return OPAQUE;
        int flags = meshFlags.getOrDefault(instance.mesh, 0);
        return instance.fullyVisible ? flags : flags & ~OPAQUE;
    }

    private int flags(MeshFact mesh) {
        boolean opaque = mesh.vertexOpaque;
        boolean transmissive = false;
        for (long id : mesh.materials) {
            MaterialAsset material = materials.get(id);
            if (material == null) { opaque = false; continue; }
            transmissive |= material.transmission() > 0;
            opaque &= material.transmission() == 0 && (material.baseColorRgba8() >>> 24) == 255
                    && !material.surfaceOverlay().enabled()
                    && material.shadingModel() != MaterialAsset.ShadingModel.LIGHTMAP_MODULATED
                    && (material.baseColorTextureId() < 0 || Boolean.TRUE.equals(opaqueTextures.get(material.baseColorTextureId())));
        }
        return (opaque ? OPAQUE : 0) | (transmissive ? TRANSMISSIVE : 0);
    }

    private static MeshFact mesh(MeshAsset mesh) {
        boolean opaque = true;
        var colors = mesh.vertexColorsRgba8();
        while (colors.hasRemaining()) if ((colors.get() >>> 24) != 255) { opaque = false; break; }
        var materials = new HashSet<Long>();
        var ids = mesh.triangleMaterialIds(); while (ids.hasRemaining()) materials.add(ids.get());
        return new MeshFact(opaque, Set.copyOf(materials));
    }

    private static boolean opaque(TextureAsset texture) {
        var pixels = texture.rgba8();
        for (int offset = pixels.position() + 3; offset < pixels.limit(); offset += 4)
            if (pixels.get(offset) != (byte) 255) return false;
        return true;
    }

    private static boolean unchanged(VulkanSceneResidency.DomainChange<?> domain) {
        return domain.statistics().writes() == 0 && domain.statistics().removals() == 0;
    }
    private static void add(Map<Long, Set<Long>> references, long target, long dependent) {
        references.computeIfAbsent(target, ignored -> new HashSet<>()).add(dependent);
    }
    private static void remove(Map<Long, Set<Long>> references, long target, long dependent) {
        Set<Long> values = references.get(target);
        if (values != null && values.remove(dependent) && values.isEmpty()) references.remove(target);
    }
    private record MeshFact(boolean vertexOpaque, Set<Long> materials) {}
    private record InstanceFact(long mesh, boolean fullyVisible, boolean volumeOnly) {}
}
