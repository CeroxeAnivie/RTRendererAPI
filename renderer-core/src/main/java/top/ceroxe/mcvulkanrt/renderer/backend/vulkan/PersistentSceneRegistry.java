package top.ceroxe.mcvulkanrt.renderer.backend.vulkan;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import top.ceroxe.mcvulkanrt.renderer.api.MaterialAsset;
import top.ceroxe.mcvulkanrt.renderer.api.MeshAsset;
import top.ceroxe.mcvulkanrt.renderer.api.SceneInstance;
import top.ceroxe.mcvulkanrt.renderer.api.SceneLight;
import top.ceroxe.mcvulkanrt.renderer.api.SceneRevisionException;
import top.ceroxe.mcvulkanrt.renderer.api.SceneTransaction;
import top.ceroxe.mcvulkanrt.renderer.api.SceneValidationException;
import top.ceroxe.mcvulkanrt.renderer.api.TextureAsset;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
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

    private long revision = -1L;

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
        return new PreparedMutation(this, revision, checked, prospectiveState);
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
    }

    synchronized SceneState commit(PreparedMutation mutation) {
        validate(mutation);
        return commitValidated(mutation);
    }

    /** Publishes a mutation already validated together with the corresponding GPUScene update. */
    synchronized SceneState commitValidated(PreparedMutation mutation) {
        PreparedMutation checked = Objects.requireNonNull(mutation, "mutation");
        SceneTransaction transaction = checked.transaction;
        if (transaction.reset()) {
            clearMaps();
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
            for (MaterialAsset material : materials.values()) {
                if (!pending.materialUpserts.contains(material.id())
                        && !pending.materialRemovals.contains(material.id())) {
                    validateMaterial(material, transaction, pending);
                }
            }
            for (MeshAsset mesh : meshes.values()) {
                if (!pending.meshUpserts.contains(mesh.id()) && !pending.meshRemovals.contains(mesh.id())) {
                    validateMesh(mesh, transaction, pending);
                }
            }
            for (SceneInstance instance : instances.values()) {
                if (!pending.instanceUpserts.contains(instance.id())
                        && !pending.instanceRemovals.contains(instance.id())) {
                    validateInstance(instance, transaction, pending);
                }
            }
        }
        transaction.upserts().materials().forEach(material -> validateMaterial(material, transaction, pending));
        transaction.upserts().meshes().forEach(mesh -> validateMesh(mesh, transaction, pending));
        transaction.upserts().instances().forEach(instance -> validateInstance(instance, transaction, pending));
    }

    private void validateMaterial(MaterialAsset material, SceneTransaction transaction, PendingIds pending) {
        requireOptionalReference(
                material.baseColorTextureId(), textures, pending.textureUpserts, pending.textureRemovals,
                transaction.reset(), "material " + material.id() + " baseColorTexture"
        );
        requireOptionalReference(
                material.normalTextureId(), textures, pending.textureUpserts, pending.textureRemovals,
                transaction.reset(), "material " + material.id() + " normalTexture"
        );
        requireOptionalReference(
                material.metallicRoughnessTextureId(), textures,
                pending.textureUpserts, pending.textureRemovals,
                transaction.reset(), "material " + material.id() + " metallicRoughnessTexture"
        );
        requireOptionalReference(
                material.emissiveTextureId(), textures, pending.textureUpserts, pending.textureRemovals,
                transaction.reset(), "material " + material.id() + " emissiveTexture"
        );
    }

    private void validateMesh(MeshAsset mesh, SceneTransaction transaction, PendingIds pending) {
        LongBuffer materialIds = mesh.triangleMaterialIds();
        while (materialIds.hasRemaining()) {
            requireReference(
                    materialIds.get(), materials, pending.materialUpserts, pending.materialRemovals,
                    transaction.reset(), "mesh " + mesh.id() + " material"
            );
        }
    }

    private void validateInstance(SceneInstance instance, SceneTransaction transaction, PendingIds pending) {
        requireReference(
                instance.meshAssetId(), meshes, pending.meshUpserts, pending.meshRemovals,
                transaction.reset(), "instance " + instance.id() + " mesh"
        );
    }

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

    private static <T> void requireOptionalReference(
            long id,
            Long2ObjectOpenHashMap<T> current,
            LongOpenHashSet upserts,
            LongOpenHashSet removals,
            boolean reset,
            String label
    ) {
        if (id >= 0L) {
            requireReference(id, current, upserts, removals, reset, label);
        }
    }

    private static <T> void requireReference(
            long id,
            Long2ObjectOpenHashMap<T> current,
            LongOpenHashSet upserts,
            LongOpenHashSet removals,
            boolean reset,
            String label
    ) {
        boolean exists = upserts.contains(id) || (!reset && !removals.contains(id) && current.containsKey(id));
        if (!exists) {
            throw new SceneValidationException(label + " references missing id " + id);
        }
    }

    private void clearMaps() {
        instances.clear();
        lights.clear();
        meshes.clear();
        materials.clear();
        textures.clear();
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

    static final class PreparedMutation {
        private final PersistentSceneRegistry owner;
        private final long baseRevision;
        private final SceneTransaction transaction;
        private final SceneState prospectiveState;
        private boolean committed;

        private PreparedMutation(
                PersistentSceneRegistry owner,
                long baseRevision,
                SceneTransaction transaction,
                SceneState prospectiveState
        ) {
            this.owner = owner;
            this.baseRevision = baseRevision;
            this.transaction = transaction;
            this.prospectiveState = prospectiveState;
        }

        SceneTransaction transaction() {
            return transaction;
        }

        SceneState prospectiveState() {
            return prospectiveState;
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
