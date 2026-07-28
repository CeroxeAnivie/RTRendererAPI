package top.ceroxe.rt.renderer.backend.vulkan;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import top.ceroxe.rt.renderer.api.MaterialAsset;
import top.ceroxe.rt.renderer.api.MeshAsset;
import top.ceroxe.rt.renderer.api.SceneInstance;
import top.ceroxe.rt.renderer.api.SceneLight;
import top.ceroxe.rt.renderer.api.SceneRevisionException;
import top.ceroxe.rt.renderer.api.SceneTransaction;
import top.ceroxe.rt.renderer.api.SceneValidationException;
import top.ceroxe.rt.renderer.api.TextureAsset;

import java.nio.LongBuffer;
import java.util.*;
import java.util.function.ToLongFunction;

/**
 * CPU authority for one Vulkan renderer's persistent scene generation.
 *
 * <p>A mutation is first validated and prepared without touching resident state. The Vulkan
 * session can therefore reject admission before {@link #commit(PreparedMutation)} publishes the
 * new generation. This ordering is intentional: host-visible state never advances ahead of the
 * GPUScene submission lane, and retrying the same revision after a rejection remains legal.</p>
 */
final class PersistentSceneRegistry {
    private final Long2ObjectOpenHashMap<TextureAsset> textures = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<MaterialAsset> materials = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<MeshAsset> meshes = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<SceneInstance> instances = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<SceneLight> lights = new Long2ObjectOpenHashMap<>();
    private final ReverseReferences textureMaterials = new ReverseReferences();
    private final ReverseReferences materialMeshes = new ReverseReferences();
    private final ReverseReferences meshInstances = new ReverseReferences();

    private long revision = -1L;

    private static <T> void requireExisting(Long2ObjectOpenHashMap<T> current, LongBuffer ids, String label) {
        while (ids.hasRemaining()) {
            long id = ids.get();
            if (!current.containsKey(id)) {
                throw new SceneValidationException(label + " removal references missing id " + id);
            }
        }
    }

    private static <T> int prospectiveSize(
            Long2ObjectOpenHashMap<T> current,
            LongOpenHashSet upserts,
            LongOpenHashSet removals
    ) {
        int added = 0;
        for (long id : upserts) {
            if (!current.containsKey(id)) {
                added++;
            }
        }
        return current.size() - removals.size() + added;
    }

    private static <T> boolean referenceExists(
            long id,
            Long2ObjectOpenHashMap<T> current,
            LongOpenHashSet upserts,
            LongOpenHashSet removals,
            boolean reset
    ) {
        return upserts.contains(id) || (!reset && !removals.contains(id) && current.containsKey(id));
    }

    private static <T> void removeAll(Long2ObjectOpenHashMap<T> target, LongBuffer ids) {
        while (ids.hasRemaining()) {
            target.remove(ids.get());
        }
    }

    private static <T> void putAll(
            Long2ObjectOpenHashMap<T> target,
            Collection<T> values,
            ToLongFunction<T> idFunction
    ) {
        for (T value : values) {
            target.put(idFunction.applyAsLong(value), value);
        }
    }

    private static <T> List<T> sortedValues(Collection<T> values, ToLongFunction<T> idFunction) {
        ArrayList<T> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.comparingLong(idFunction));
        return List.copyOf(sorted);
    }

    synchronized PreparedMutation prepare(SceneTransaction transaction) {
        SceneTransaction checked = Objects.requireNonNull(transaction, "transaction");
        if (checked.revision() <= revision) {
            throw new SceneRevisionException(
                    "scene transaction revision must advance: current=" + revision
                            + ", submitted=" + checked.revision()
            );
        }
        if (checked.reset() && checked.removals().hasChanges()) {
            throw new SceneValidationException(
                    "reset transaction cannot also remove discarded scene identities"
            );
        }

        PendingIds pending = PendingIds.from(checked);
        if (!checked.reset()) {
            requireExistingRemovals(checked.removals());
        }
        validateProspectiveGeneration(checked, pending);
        ReferenceDelta referenceDelta = prepareReferenceDelta(checked, pending);

        SceneState prospectiveState = checked.reset()
                ? new SceneState(
                checked.revision(),
                checked.upserts().textures().size(),
                checked.upserts().materials().size(),
                checked.upserts().meshes().size(),
                checked.upserts().instances().size(),
                checked.upserts().lights().size()
        )
                : new SceneState(
                checked.revision(),
                prospectiveSize(textures, pending.textureUpserts, pending.textureRemovals),
                prospectiveSize(materials, pending.materialUpserts, pending.materialRemovals),
                prospectiveSize(meshes, pending.meshUpserts, pending.meshRemovals),
                prospectiveSize(instances, pending.instanceUpserts, pending.instanceRemovals),
                prospectiveSize(lights, pending.lightUpserts, pending.lightRemovals)
        );
        return new PreparedMutation(this, revision, checked, prospectiveState, referenceDelta);
    }

    synchronized void validate(PreparedMutation mutation) {
        PreparedMutation checked = Objects.requireNonNull(mutation, "mutation");
        if (checked.owner != this) {
            throw new IllegalArgumentException("prepared scene mutation belongs to another renderer");
        }
        if (checked.committed) {
            throw new IllegalStateException("prepared scene mutation was already committed");
        }
        if (revision != checked.baseRevision) {
            throw new IllegalStateException(
                    "scene authority advanced after preparation: expected=" + checked.baseRevision
                            + ", actual=" + revision
            );
        }
        checked.referenceDelta.validate(textureMaterials, materialMeshes, meshInstances);
    }

    synchronized SceneState commit(PreparedMutation mutation) {
        validate(mutation);
        return commitValidated(mutation);
    }

    /**
     * Publishes a mutation already validated together with the corresponding GPUScene update.
     */
    synchronized SceneState commitValidated(PreparedMutation mutation) {
        PreparedMutation checked = Objects.requireNonNull(mutation, "mutation");
        SceneTransaction transaction = checked.transaction;
        if (transaction.reset()) {
            clearMaps();
            clearReverseReferences();
        }
        removeAll(instances, transaction.removals().instanceIds());
        removeAll(lights, transaction.removals().lightIds());
        removeAll(meshes, transaction.removals().meshIds());
        removeAll(materials, transaction.removals().materialIds());
        removeAll(textures, transaction.removals().textureIds());

        putAll(textures, transaction.upserts().textures(), TextureAsset::id);
        putAll(materials, transaction.upserts().materials(), MaterialAsset::id);
        putAll(meshes, transaction.upserts().meshes(), MeshAsset::id);
        putAll(instances, transaction.upserts().instances(), SceneInstance::id);
        putAll(lights, transaction.upserts().lights(), SceneLight::id);
        checked.referenceDelta.commit(textureMaterials, materialMeshes, meshInstances);
        revision = transaction.revision();
        checked.committed = true;
        return checked.prospectiveState;
    }

    synchronized SceneState state() {
        return new SceneState(
                Math.max(0L, revision),
                textures.size(),
                materials.size(),
                meshes.size(),
                instances.size(),
                lights.size()
        );
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(
                revision >= 0L,
                state(),
                sortedValues(textures.values(), TextureAsset::id),
                sortedValues(materials.values(), MaterialAsset::id),
                sortedValues(meshes.values(), MeshAsset::id),
                sortedValues(instances.values(), SceneInstance::id),
                sortedValues(lights.values(), SceneLight::id)
        );
    }

    private void requireExistingRemovals(SceneTransaction.Removals removals) {
        requireExisting(textures, removals.textureIds(), "texture");
        requireExisting(materials, removals.materialIds(), "material");
        requireExisting(meshes, removals.meshIds(), "mesh");
        requireExisting(instances, removals.instanceIds(), "instance");
        requireExisting(lights, removals.lightIds(), "light");
    }

    private void validateProspectiveGeneration(SceneTransaction transaction, PendingIds pending) {
        if (!transaction.reset()) {
            requireNoRetainedDependents(
                    transaction.removals().textureIds(), textureMaterials,
                    pending.materialUpserts, pending.materialRemovals, "texture", "material"
            );
            requireNoRetainedDependents(
                    transaction.removals().materialIds(), materialMeshes,
                    pending.meshUpserts, pending.meshRemovals, "material", "mesh"
            );
            requireNoRetainedDependents(
                    transaction.removals().meshIds(), meshInstances,
                    pending.instanceUpserts, pending.instanceRemovals, "mesh", "instance"
            );
        }
        transaction.upserts().materials().forEach(material -> validateMaterial(material, transaction, pending));
        transaction.upserts().meshes().forEach(mesh -> validateMesh(mesh, transaction, pending));
        transaction.upserts().instances().forEach(instance -> validateInstance(instance, transaction, pending));
    }

    private static void requireNoRetainedDependents(
            LongBuffer removedTargets,
            ReverseReferences references,
            LongOpenHashSet dependentUpserts,
            LongOpenHashSet dependentRemovals,
            String targetLabel,
            String dependentLabel
    ) {
        while (removedTargets.hasRemaining()) {
            long targetId = removedTargets.get();
            LongOpenHashSet dependents = references.dependents(targetId);
            if (dependents == null) continue;
            for (long dependentId : dependents) {
                if (!dependentUpserts.contains(dependentId) && !dependentRemovals.contains(dependentId)) {
                    // Diagnostics are deliberately constructed only on the rejected cold path.
                    throw new SceneValidationException(
                            targetLabel + " " + targetId + " is still referenced by "
                                    + dependentLabel + " " + dependentId
                    );
                }
            }
        }
    }

    private void validateMaterial(MaterialAsset material, SceneTransaction transaction, PendingIds pending) {
        requireTextureReference(material, material.baseColorTextureId(), "baseColorTexture", transaction, pending);
        requireTextureReference(material, material.normalTextureId(), "normalTexture", transaction, pending);
        requireTextureReference(
                material, material.metallicRoughnessTextureId(), "metallicRoughnessTexture", transaction, pending
        );
        requireTextureReference(material, material.emissiveTextureId(), "emissiveTexture", transaction, pending);
    }

    private void requireTextureReference(
            MaterialAsset material,
            long textureId,
            String role,
            SceneTransaction transaction,
            PendingIds pending
    ) {
        if (textureId >= 0L && !referenceExists(
                textureId, textures, pending.textureUpserts, pending.textureRemovals, transaction.reset()
        )) {
            throw new SceneValidationException(
                    "material " + material.id() + " " + role + " references missing id " + textureId
            );
        }
    }

    private void validateMesh(MeshAsset mesh, SceneTransaction transaction, PendingIds pending) {
        LongBuffer materialIds = mesh.triangleMaterialIds();
        while (materialIds.hasRemaining()) {
            long materialId = materialIds.get();
            if (!referenceExists(
                    materialId, materials, pending.materialUpserts, pending.materialRemovals, transaction.reset()
            )) {
                throw new SceneValidationException(
                        "mesh " + mesh.id() + " material references missing id " + materialId
                );
            }
        }
    }

    private void validateInstance(SceneInstance instance, SceneTransaction transaction, PendingIds pending) {
        if (!referenceExists(
                instance.meshAssetId(), meshes, pending.meshUpserts, pending.meshRemovals, transaction.reset()
        )) {
            throw new SceneValidationException(
                    "instance " + instance.id() + " mesh references missing id " + instance.meshAssetId()
            );
        }
    }

    private ReferenceDelta prepareReferenceDelta(SceneTransaction transaction, PendingIds pending) {
        if (transaction.reset()) {
            return new ReferenceDelta(
                    materialReferenceChanges(transaction.upserts().materials(), true, pending),
                    meshReferenceChanges(transaction.upserts().meshes(), true, pending),
                    instanceReferenceChanges(transaction.upserts().instances(), true, pending)
            );
        }
        return new ReferenceDelta(
                materialReferenceChanges(transaction.upserts().materials(), false, pending),
                meshReferenceChanges(transaction.upserts().meshes(), false, pending),
                instanceReferenceChanges(transaction.upserts().instances(), false, pending)
        );
    }

    private List<ReferenceChange> materialReferenceChanges(
            Collection<MaterialAsset> upserts,
            boolean reset,
            PendingIds pending
    ) {
        ArrayList<ReferenceChange> changes = new ArrayList<>(upserts.size() + pending.materialRemovals.size());
        for (MaterialAsset material : upserts) {
            MaterialAsset previous = reset ? null : materials.get(material.id());
            addReferenceChange(changes, material.id(), textureReferences(previous), textureReferences(material));
        }
        if (!reset) {
            for (long id : pending.materialRemovals) {
                addReferenceChange(changes, id, textureReferences(materials.get(id)), new long[0]);
            }
        }
        return List.copyOf(changes);
    }

    private List<ReferenceChange> meshReferenceChanges(
            Collection<MeshAsset> upserts,
            boolean reset,
            PendingIds pending
    ) {
        ArrayList<ReferenceChange> changes = new ArrayList<>(upserts.size() + pending.meshRemovals.size());
        for (MeshAsset mesh : upserts) {
            MeshAsset previous = reset ? null : meshes.get(mesh.id());
            addReferenceChange(changes, mesh.id(), materialReferences(previous), materialReferences(mesh));
        }
        if (!reset) {
            for (long id : pending.meshRemovals) {
                addReferenceChange(changes, id, materialReferences(meshes.get(id)), new long[0]);
            }
        }
        return List.copyOf(changes);
    }

    private List<ReferenceChange> instanceReferenceChanges(
            Collection<SceneInstance> upserts,
            boolean reset,
            PendingIds pending
    ) {
        ArrayList<ReferenceChange> changes = new ArrayList<>(upserts.size() + pending.instanceRemovals.size());
        for (SceneInstance instance : upserts) {
            SceneInstance previous = reset ? null : instances.get(instance.id());
            addReferenceChange(changes, instance.id(), meshReferences(previous), meshReferences(instance));
        }
        if (!reset) {
            for (long id : pending.instanceRemovals) {
                addReferenceChange(changes, id, meshReferences(instances.get(id)), new long[0]);
            }
        }
        return List.copyOf(changes);
    }

    private static void addReferenceChange(
            List<ReferenceChange> changes,
            long dependentId,
            long[] previousTargets,
            long[] nextTargets
    ) {
        if (!Arrays.equals(previousTargets, nextTargets)) {
            changes.add(new ReferenceChange(dependentId, previousTargets, nextTargets));
        }
    }

    private static long[] textureReferences(MaterialAsset material) {
        if (material == null) return new long[0];
        LongOpenHashSet references = new LongOpenHashSet(4);
        addOptionalReference(references, material.baseColorTextureId());
        addOptionalReference(references, material.normalTextureId());
        addOptionalReference(references, material.metallicRoughnessTextureId());
        addOptionalReference(references, material.emissiveTextureId());
        return sortedReferences(references);
    }

    private static long[] materialReferences(MeshAsset mesh) {
        if (mesh == null) return new long[0];
        LongOpenHashSet references = new LongOpenHashSet();
        LongBuffer materialIds = mesh.triangleMaterialIds();
        while (materialIds.hasRemaining()) references.add(materialIds.get());
        return sortedReferences(references);
    }

    private static long[] meshReferences(SceneInstance instance) {
        return instance == null ? new long[0] : new long[]{instance.meshAssetId()};
    }

    private static void addOptionalReference(LongOpenHashSet references, long identity) {
        if (identity >= 0L) references.add(identity);
    }

    private static long[] sortedReferences(LongOpenHashSet references) {
        long[] identities = references.toLongArray();
        Arrays.sort(identities);
        return identities;
    }

    private void clearMaps() {
        instances.clear();
        lights.clear();
        meshes.clear();
        materials.clear();
        textures.clear();
    }

    private void clearReverseReferences() {
        textureMaterials.clear();
        materialMeshes.clear();
        meshInstances.clear();
    }

    static final class PreparedMutation {
        private final PersistentSceneRegistry owner;
        private final long baseRevision;
        private final SceneTransaction transaction;
        private final SceneState prospectiveState;
        private final ReferenceDelta referenceDelta;
        private boolean committed;

        private PreparedMutation(
                PersistentSceneRegistry owner,
                long baseRevision,
                SceneTransaction transaction,
                SceneState prospectiveState,
                ReferenceDelta referenceDelta
        ) {
            this.owner = owner;
            this.baseRevision = baseRevision;
            this.transaction = transaction;
            this.prospectiveState = prospectiveState;
            this.referenceDelta = Objects.requireNonNull(referenceDelta, "referenceDelta");
        }

        SceneTransaction transaction() {
            return transaction;
        }

        SceneState prospectiveState() {
            return prospectiveState;
        }
    }

    private static final class ReverseReferences {
        private final Long2ObjectOpenHashMap<LongOpenHashSet> dependentsByTarget =
                new Long2ObjectOpenHashMap<>();

        private LongOpenHashSet dependents(long targetId) {
            return dependentsByTarget.get(targetId);
        }

        private void replace(long dependentId, long[] previousTargets, long[] nextTargets) {
            for (long targetId : previousTargets) {
                LongOpenHashSet dependents = dependentsByTarget.get(targetId);
                dependents.remove(dependentId);
                if (dependents.isEmpty()) dependentsByTarget.remove(targetId);
            }
            for (long targetId : nextTargets) {
                dependentsByTarget.computeIfAbsent(targetId, ignored -> new LongOpenHashSet()).add(dependentId);
            }
        }

        private void clear() {
            dependentsByTarget.clear();
        }

        private void validatePrevious(long dependentId, long[] previousTargets) {
            for (long targetId : previousTargets) {
                LongOpenHashSet dependents = dependentsByTarget.get(targetId);
                if (dependents == null || !dependents.contains(dependentId)) {
                    throw new IllegalStateException(
                            "reverse scene reference is missing target " + targetId
                                    + " for dependent " + dependentId
                    );
                }
            }
        }
    }

    private record ReferenceChange(long dependentId, long[] previousTargets, long[] nextTargets) {
        private ReferenceChange {
            if (dependentId < 0L) throw new IllegalArgumentException("dependent identity must not be negative");
            previousTargets = Objects.requireNonNull(previousTargets, "previousTargets").clone();
            nextTargets = Objects.requireNonNull(nextTargets, "nextTargets").clone();
        }

        private void commit(ReverseReferences references) {
            references.replace(dependentId, previousTargets, nextTargets);
        }

        private void validate(ReverseReferences references) {
            references.validatePrevious(dependentId, previousTargets);
        }
    }

    private record ReferenceDelta(
            List<ReferenceChange> materialTextures,
            List<ReferenceChange> meshMaterials,
            List<ReferenceChange> instanceMeshes
    ) {
        private ReferenceDelta {
            materialTextures = List.copyOf(Objects.requireNonNull(materialTextures, "materialTextures"));
            meshMaterials = List.copyOf(Objects.requireNonNull(meshMaterials, "meshMaterials"));
            instanceMeshes = List.copyOf(Objects.requireNonNull(instanceMeshes, "instanceMeshes"));
        }

        private void commit(
                ReverseReferences textureMaterials,
                ReverseReferences materialMeshes,
                ReverseReferences meshInstances
        ) {
            materialTextures.forEach(change -> change.commit(textureMaterials));
            meshMaterials.forEach(change -> change.commit(materialMeshes));
            instanceMeshes.forEach(change -> change.commit(meshInstances));
        }


        private void validate(
                ReverseReferences textureMaterials,
                ReverseReferences materialMeshes,
                ReverseReferences meshInstances
        ) {
            materialTextures.forEach(change -> change.validate(textureMaterials));
            meshMaterials.forEach(change -> change.validate(materialMeshes));
            instanceMeshes.forEach(change -> change.validate(meshInstances));
        }
    }

    record SceneState(
            long revision,
            int textures,
            int materials,
            int meshes,
            int instances,
            int lights
    ) {
        SceneState {
            if (revision < 0L || textures < 0 || materials < 0 || meshes < 0 || instances < 0 || lights < 0) {
                throw new IllegalArgumentException("scene state counters must not be negative");
            }
        }
    }

    record Snapshot(
            boolean initialized,
            SceneState state,
            List<TextureAsset> textures,
            List<MaterialAsset> materials,
            List<MeshAsset> meshes,
            List<SceneInstance> instances,
            List<SceneLight> lights
    ) {
    }

    private record PendingIds(
            LongOpenHashSet textureUpserts,
            LongOpenHashSet materialUpserts,
            LongOpenHashSet meshUpserts,
            LongOpenHashSet instanceUpserts,
            LongOpenHashSet lightUpserts,
            LongOpenHashSet textureRemovals,
            LongOpenHashSet materialRemovals,
            LongOpenHashSet meshRemovals,
            LongOpenHashSet instanceRemovals,
            LongOpenHashSet lightRemovals
    ) {
        static PendingIds from(SceneTransaction transaction) {
            return new PendingIds(
                    ids(transaction.upserts().textures(), TextureAsset::id),
                    ids(transaction.upserts().materials(), MaterialAsset::id),
                    ids(transaction.upserts().meshes(), MeshAsset::id),
                    ids(transaction.upserts().instances(), SceneInstance::id),
                    ids(transaction.upserts().lights(), SceneLight::id),
                    ids(transaction.removals().textureIds()),
                    ids(transaction.removals().materialIds()),
                    ids(transaction.removals().meshIds()),
                    ids(transaction.removals().instanceIds()),
                    ids(transaction.removals().lightIds())
            );
        }

        private static <T> LongOpenHashSet ids(Collection<T> values, ToLongFunction<T> idFunction) {
            LongOpenHashSet ids = new LongOpenHashSet(values.size());
            for (T value : values) {
                ids.add(idFunction.applyAsLong(value));
            }
            return ids;
        }

        private static LongOpenHashSet ids(LongBuffer values) {
            LongOpenHashSet ids = new LongOpenHashSet(values.remaining());
            while (values.hasRemaining()) {
                ids.add(values.get());
            }
            return ids;
        }
    }
}
