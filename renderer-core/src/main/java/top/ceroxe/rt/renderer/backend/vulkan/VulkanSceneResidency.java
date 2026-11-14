package top.ceroxe.rt.renderer.backend.vulkan;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import top.ceroxe.rt.renderer.api.MaterialAsset;
import top.ceroxe.rt.renderer.api.MeshAsset;
import top.ceroxe.rt.renderer.api.SceneInstance;
import top.ceroxe.rt.renderer.api.SceneLight;
import top.ceroxe.rt.renderer.api.SceneTransaction;
import top.ceroxe.rt.renderer.api.TextureAsset;
import top.ceroxe.rt.renderer.api.AffineTransform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Aggregates independently uploadable GPUScene domains behind one atomic scene generation.
 */
final class VulkanSceneResidency {
    private final StableIdentitySlots<TextureAsset> textures = new StableIdentitySlots<>(TextureAsset::id);
    private final StableIdentitySlots<MaterialAsset> materials = new StableIdentitySlots<>(MaterialAsset::id);
    private final StableIdentitySlots<MeshAsset> meshes = new StableIdentitySlots<>(MeshAsset::id);
    private final StableIdentitySlots<SceneInstance> instances = new StableIdentitySlots<>(SceneInstance::id);
    private final StableIdentitySlots<SceneLight> lights = new StableIdentitySlots<>(SceneLight::id);
    /** Last instance transforms used by a successfully submitted frame, not merely by a scene update. */
    private final Map<Long, AffineTransform> lastRenderedTransforms = new HashMap<>();

    private long revision = -1L;

    private static <T> DomainState domainState(StableIdentitySlots<T> domain) {
        return new DomainState(domain.liveCount(), domain.slotUpperBound());
    }

    private static <T> DomainChange<T> change(StableIdentitySlots.Prepared<T> prepared) {
        return new DomainChange<>(
                prepared.writes(),
                prepared.removedIds(),
                prepared.clearedSlots(),
                new DomainUpdateStatistics(
                        prepared.writes().size(),
                        prepared.removedIds().length,
                        prepared.clearedSlots().length,
                        prepared.liveCount(),
                        prepared.slotUpperBound()
                )
        );
    }

    synchronized PreparedUpdate prepare(SceneTransaction transaction) {
        SceneTransaction checked = Objects.requireNonNull(transaction, "transaction");
        if (checked.revision() <= revision) {
            throw new IllegalArgumentException(
                    "resident scene revision must advance: current=" + revision
                            + ", submitted=" + checked.revision()
            );
        }
        SceneTransaction.Upserts upserts = checked.upserts();
        SceneTransaction.Removals removals = checked.removals();
        StableIdentitySlots.Prepared<TextureAsset> preparedTextures =
                textures.prepare(checked.revision(), checked.reset(), upserts.textures(), removals.textureIds());
        StableIdentitySlots.Prepared<MaterialAsset> preparedMaterials =
                materials.prepare(checked.revision(), checked.reset(), upserts.materials(), removals.materialIds());
        StableIdentitySlots.Prepared<MeshAsset> preparedMeshes =
                meshes.prepare(checked.revision(), checked.reset(), upserts.meshes(), removals.meshIds());
        StableIdentitySlots.Prepared<SceneInstance> preparedInstances =
                instances.prepare(checked.revision(), checked.reset(), upserts.instances(), removals.instanceIds());
        StableIdentitySlots.Prepared<SceneLight> preparedLights =
                lights.prepare(checked.revision(), checked.reset(), upserts.lights(), removals.lightIds());
        MeshUpdates meshUpdates = meshUpdates(preparedMeshes, checked.reset());
        List<InstanceMotionWrite> instanceMotionWrites = instanceMotionWrites(preparedInstances, checked.reset());
        return new PreparedUpdate(
                this,
                revision,
                checked.revision(),
                preparedTextures,
                preparedMaterials,
                preparedMeshes,
                preparedInstances,
                preparedLights,
                new SceneChangeSet(
                        revision,
                        checked.revision(),
                        checked.reset(),
                        change(preparedTextures),
                        change(preparedMaterials),
                        change(preparedMeshes),
                        meshUpdates,
                        change(preparedInstances),
                        instanceMotionWrites,
                        change(preparedLights)
                )
        );
    }

    synchronized void validate(PreparedUpdate update) {
        PreparedUpdate checked = Objects.requireNonNull(update, "update");
        if (checked.owner != this) {
            throw new IllegalArgumentException("prepared residency update belongs to another renderer");
        }
        if (revision != checked.baseRevision) {
            throw new IllegalStateException("resident scene advanced after preparation");
        }
        textures.validate(checked.textures);
        materials.validate(checked.materials);
        meshes.validate(checked.meshes);
        instances.validate(checked.instances);
        lights.validate(checked.lights);
    }

    synchronized SceneResidencyState commit(PreparedUpdate update) {
        validate(update);
        return commitValidated(update);
    }

    /**
     * Publishes a previously validated change set. The host uses this only after every CPU and
     * native admission invariant has passed, so a later domain cannot expose a half generation.
     */
    synchronized SceneResidencyState commitValidated(PreparedUpdate update) {
        PreparedUpdate checked = Objects.requireNonNull(update, "update");
        textures.commitValidated(checked.textures);
        materials.commitValidated(checked.materials);
        meshes.commitValidated(checked.meshes);
        instances.commitValidated(checked.instances);
        lights.commitValidated(checked.lights);
        revision = checked.revision;
        SceneResidencyState committed = state();
        VulkanSceneResidencyFlightRecorder.recordCommitted(checked.changeSet);
        return committed;
    }

    synchronized SceneResidencyState state() {
        return new SceneResidencyState(
                Math.max(0L, revision),
                domainState(textures),
                domainState(materials),
                domainState(meshes),
                domainState(instances),
                domainState(lights)
        );
    }

    synchronized int textureSlot(long id) {
        return textures.slot(id);
    }

    synchronized int materialSlot(long id) {
        return materials.slot(id);
    }

    synchronized int meshSlot(long id) {
        return meshes.slot(id);
    }

    synchronized int instanceSlot(long id) {
        return instances.slot(id);
    }

    synchronized int lightSlot(long id) {
        return lights.slot(id);
    }

    /**
     * Commits the instance transforms actually consumed by a successfully admitted frame. A scene
     * transaction can be prepared more often than frames are rendered, so this is deliberately a
     * frame boundary rather than part of {@link #commitValidated(PreparedUpdate)}.
     */
    synchronized void markFrameRendered(long renderedRevision) {
        // Before the first scene transaction, the public scene authority is the empty revision 0
        // while the internal -1 sentinel means only that no slot transaction has been committed.
        // Empty-scene frames are therefore valid and must use the same normalized revision exposed
        // by state(); no instance transform can be lost because the residency is necessarily empty.
        long effectiveRevision = Math.max(0L, revision);
        if (renderedRevision != effectiveRevision) {
            throw new IllegalArgumentException(
                    "rendered scene revision does not match resident revision: rendered="
                            + renderedRevision + ", resident=" + effectiveRevision
            );
        }
        lastRenderedTransforms.clear();
        for (SceneInstance instance : instances.valuesSnapshot()) {
            lastRenderedTransforms.put(instance.id(), instance.transform());
        }
    }

    private MeshUpdates meshUpdates(StableIdentitySlots.Prepared<MeshAsset> prepared, boolean reset) {
        ArrayList<MeshUpdate> updates = new ArrayList<>(prepared.writes().size());
        for (StableIdentitySlots.SlotWrite<MeshAsset> write : prepared.writes()) {
            MeshAsset previous = reset ? null : meshes.valueAt(meshes.slot(write.id()));
            updates.add(new MeshUpdate(write.id(), write.slot(), MeshDirtyMask.between(previous, write.value())));
        }
        return new MeshUpdates(updates);
    }

    private List<InstanceMotionWrite> instanceMotionWrites(
            StableIdentitySlots.Prepared<SceneInstance> prepared,
            boolean reset
    ) {
        ArrayList<InstanceMotionWrite> writes = new ArrayList<>(prepared.writes().size());
        for (StableIdentitySlots.SlotWrite<SceneInstance> write : prepared.writes()) {
            AffineTransform previous = reset ? null : lastRenderedTransforms.get(write.id());
            writes.add(new InstanceMotionWrite(
                    write.slot(), write.id(), write.value(), previous == null ? write.value().transform() : previous
            ));
        }
        return List.copyOf(writes);
    }

    static final class PreparedUpdate {
        private final VulkanSceneResidency owner;
        private final long baseRevision;
        private final long revision;
        private final StableIdentitySlots.Prepared<TextureAsset> textures;
        private final StableIdentitySlots.Prepared<MaterialAsset> materials;
        private final StableIdentitySlots.Prepared<MeshAsset> meshes;
        private final StableIdentitySlots.Prepared<SceneInstance> instances;
        private final StableIdentitySlots.Prepared<SceneLight> lights;
        private final SceneChangeSet changeSet;

        private PreparedUpdate(
                VulkanSceneResidency owner,
                long baseRevision,
                long revision,
                StableIdentitySlots.Prepared<TextureAsset> textures,
                StableIdentitySlots.Prepared<MaterialAsset> materials,
                StableIdentitySlots.Prepared<MeshAsset> meshes,
                StableIdentitySlots.Prepared<SceneInstance> instances,
                StableIdentitySlots.Prepared<SceneLight> lights,
                SceneChangeSet changeSet
        ) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.baseRevision = baseRevision;
            this.revision = revision;
            this.textures = Objects.requireNonNull(textures, "textures");
            this.materials = Objects.requireNonNull(materials, "materials");
            this.meshes = Objects.requireNonNull(meshes, "meshes");
            this.instances = Objects.requireNonNull(instances, "instances");
            this.lights = Objects.requireNonNull(lights, "lights");
            this.changeSet = Objects.requireNonNull(changeSet, "changeSet");
        }

        long baseRevision() {
            return baseRevision;
        }

        long revision() {
            return revision;
        }

        StableIdentitySlots.Prepared<TextureAsset> textures() {
            return textures;
        }

        StableIdentitySlots.Prepared<MaterialAsset> materials() {
            return materials;
        }

        StableIdentitySlots.Prepared<MeshAsset> meshes() {
            return meshes;
        }

        StableIdentitySlots.Prepared<SceneInstance> instances() {
            return instances;
        }

        StableIdentitySlots.Prepared<SceneLight> lights() {
            return lights;
        }

        SceneChangeSet changeSet() {
            return changeSet;
        }
    }

    /**
     * Immutable sparse GPUScene payload given to native admission; it has no commit capability.
     */
    record SceneChangeSet(
            long baseRevision,
            long revision,
            boolean reset,
            DomainChange<TextureAsset> textures,
            DomainChange<MaterialAsset> materials,
            DomainChange<MeshAsset> meshes,
            MeshUpdates meshUpdates,
            DomainChange<SceneInstance> instances,
            List<InstanceMotionWrite> instanceMotionWrites,
            DomainChange<SceneLight> lights
    ) {
        SceneChangeSet {
            if (baseRevision < -1L || revision < 0L || revision <= baseRevision) {
                throw new IllegalArgumentException("resident scene change-set revisions must advance");
            }
            textures = Objects.requireNonNull(textures, "textures");
            materials = Objects.requireNonNull(materials, "materials");
            meshes = Objects.requireNonNull(meshes, "meshes");
            meshUpdates = Objects.requireNonNull(meshUpdates, "meshUpdates");
            instances = Objects.requireNonNull(instances, "instances");
            instanceMotionWrites = List.copyOf(Objects.requireNonNull(instanceMotionWrites, "instanceMotionWrites"));
            lights = Objects.requireNonNull(lights, "lights");
            meshUpdates.validate(meshes.writes());
            if (instanceMotionWrites.size() != instances.writes().size()) {
                throw new IllegalArgumentException("instance motion writes do not match instance writes");
            }
            for (int index = 0; index < instanceMotionWrites.size(); index++) {
                InstanceMotionWrite motion = instanceMotionWrites.get(index);
                StableIdentitySlots.SlotWrite<SceneInstance> write = instances.writes().get(index);
                if (motion.slot() != write.slot() || motion.id() != write.id()
                        || motion.current() != write.value()) {
                    throw new IllegalArgumentException("instance motion write diverges from instance write");
                }
            }
        }

        SceneChangeSet(
                long baseRevision,
                long revision,
                boolean reset,
                DomainChange<TextureAsset> textures,
                DomainChange<MaterialAsset> materials,
                DomainChange<MeshAsset> meshes,
                DomainChange<SceneInstance> instances,
                DomainChange<SceneLight> lights
        ) {
            this(
                    baseRevision, revision, reset, textures, materials, meshes,
                    MeshUpdates.all(meshes.writes()), instances,
                    defaultInstanceMotionWrites(instances), lights
            );
        }

        private static List<InstanceMotionWrite> defaultInstanceMotionWrites(
                DomainChange<SceneInstance> instances
        ) {
            ArrayList<InstanceMotionWrite> result = new ArrayList<>(instances.writes().size());
            for (StableIdentitySlots.SlotWrite<SceneInstance> write : instances.writes()) {
                result.add(new InstanceMotionWrite(
                        write.slot(), write.id(), write.value(), write.value().transform()
                ));
            }
            return List.copyOf(result);
        }

        int totalWrites() {
            return textures.statistics().writes() + materials.statistics().writes()
                    + meshes.statistics().writes() + instances.statistics().writes()
                    + lights.statistics().writes();
        }

        int totalClears() {
            return textures.statistics().clears() + materials.statistics().clears()
                    + meshes.statistics().clears() + instances.statistics().clears()
                    + lights.statistics().clears();
        }

        int totalRemovals() {
            return textures.statistics().removals() + materials.statistics().removals()
                    + meshes.statistics().removals() + instances.statistics().removals()
                    + lights.statistics().removals();
        }
    }

    static final class MeshDirtyMask {
        static final int POSITIONS = 1;
        static final int NORMALS = 1 << 1;
        static final int TANGENTS = 1 << 2;
        static final int TEXTURE_COORDINATES = 1 << 3;
        static final int COLORS = 1 << 4;
        static final int LIGHTMAP_COORDINATES = 1 << 5;
        static final int INDICES = 1 << 6;
        static final int TRIANGLE_MATERIALS = 1 << 7;
        static final int BLAS = POSITIONS | INDICES;
        static final int ALL = (1 << 8) - 1;

        private MeshDirtyMask() {
        }

        private static int between(MeshAsset previous, MeshAsset next) {
            if (previous == null) return ALL;
            int mask = 0;
            if (!previous.positions().equals(next.positions())) mask |= POSITIONS;
            if (!previous.normals().equals(next.normals())) mask |= NORMALS;
            if (!previous.tangents().equals(next.tangents())) mask |= TANGENTS;
            if (!previous.textureCoordinates().equals(next.textureCoordinates())) mask |= TEXTURE_COORDINATES;
            if (!previous.vertexColorsRgba8().equals(next.vertexColorsRgba8())) mask |= COLORS;
            if (!previous.lightmapCoordinates().equals(next.lightmapCoordinates())) mask |= LIGHTMAP_COORDINATES;
            if (!previous.triangleIndices().equals(next.triangleIndices())) mask |= INDICES;
            if (!previous.triangleMaterialIds().equals(next.triangleMaterialIds())) mask |= TRIANGLE_MATERIALS;
            return mask;
        }
    }

    record MeshUpdate(long identity, int slot, int dirtyMask) {
        MeshUpdate {
            if (identity < 0L || slot < 0 || dirtyMask < 0 || (dirtyMask & ~MeshDirtyMask.ALL) != 0) {
                throw new IllegalArgumentException("resident mesh update classification is invalid");
            }
        }

        boolean dirty(int mask) {
            return (dirtyMask & mask) != 0;
        }
    }

    static final class MeshUpdates {
        private final List<MeshUpdate> updates;
        private final Long2IntOpenHashMap indexByIdentity;

        private MeshUpdates(List<MeshUpdate> updates) {
            this.updates = List.copyOf(Objects.requireNonNull(updates, "updates"));
            indexByIdentity = new Long2IntOpenHashMap(this.updates.size());
            indexByIdentity.defaultReturnValue(-1);
            for (int index = 0; index < this.updates.size(); index++) {
                MeshUpdate update = this.updates.get(index);
                if (indexByIdentity.putIfAbsent(update.identity(), index) >= 0) {
                    throw new IllegalArgumentException("duplicate classified mesh identity " + update.identity());
                }
            }
        }

        private static MeshUpdates all(List<StableIdentitySlots.SlotWrite<MeshAsset>> writes) {
            ArrayList<MeshUpdate> updates = new ArrayList<>(writes.size());
            for (StableIdentitySlots.SlotWrite<MeshAsset> write : writes) {
                updates.add(new MeshUpdate(write.id(), write.slot(), MeshDirtyMask.ALL));
            }
            return new MeshUpdates(updates);
        }

        MeshUpdate get(long identity) {
            int index = indexByIdentity.get(identity);
            if (index < 0) throw new IllegalArgumentException("mesh update identity was not classified: " + identity);
            return updates.get(index);
        }

        List<MeshUpdate> values() {
            return updates;
        }

        int blasDirtyCount() {
            int count = 0;
            for (MeshUpdate update : updates) {
                if (update.dirty(MeshDirtyMask.BLAS)) count++;
            }
            return count;
        }

        private void validate(List<StableIdentitySlots.SlotWrite<MeshAsset>> writes) {
            if (writes.size() != updates.size()) {
                throw new IllegalArgumentException("mesh update classification does not match sparse writes");
            }
            for (StableIdentitySlots.SlotWrite<MeshAsset> write : writes) {
                MeshUpdate update = get(write.id());
                if (update.slot() != write.slot()) {
                    throw new IllegalArgumentException("mesh update slot does not match sparse write");
                }
            }
        }
    }

    record DomainChange<T>(
            List<StableIdentitySlots.SlotWrite<T>> writes,
            long[] removedIdentities,
            int[] clearedSlots,
            DomainUpdateStatistics statistics
    ) {
        DomainChange {
            writes = List.copyOf(Objects.requireNonNull(writes, "writes"));
            removedIdentities = Objects.requireNonNull(removedIdentities, "removedIdentities").clone();
            clearedSlots = Objects.requireNonNull(clearedSlots, "clearedSlots").clone();
            statistics = Objects.requireNonNull(statistics, "statistics");
            if (statistics.writes() != writes.size()
                    || statistics.removals() != removedIdentities.length
                    || statistics.clears() != clearedSlots.length) {
                throw new IllegalArgumentException("resident domain statistics do not match sparse payload");
            }
        }

        @Override
        public long[] removedIdentities() {
            return removedIdentities.clone();
        }

        @Override
        public int[] clearedSlots() {
            return clearedSlots.clone();
        }
    }

    record DomainUpdateStatistics(int writes, int removals, int clears, int liveSlots, int slotUpperBound) {
        DomainUpdateStatistics {
            if (writes < 0 || removals < 0 || clears < 0 || liveSlots < 0 || slotUpperBound < liveSlots) {
                throw new IllegalArgumentException("resident domain update statistics are invalid");
            }
        }
    }

    record InstanceMotionWrite(
            int slot,
            long id,
            SceneInstance current,
            AffineTransform previousTransform
    ) {
        InstanceMotionWrite {
            if (slot < 0 || id < 0L) throw new IllegalArgumentException("instance motion identity is invalid");
            current = Objects.requireNonNull(current, "current");
            previousTransform = Objects.requireNonNull(previousTransform, "previousTransform");
            if (current.id() != id) throw new IllegalArgumentException("instance motion id does not match current instance");
        }
    }

    record SceneResidencyState(
            long revision,
            DomainState textures,
            DomainState materials,
            DomainState meshes,
            DomainState instances,
            DomainState lights
    ) {
    }

    record DomainState(int liveSlots, int slotUpperBound) {
        DomainState {
            if (liveSlots < 0 || slotUpperBound < liveSlots) {
                throw new IllegalArgumentException("resident domain counters are invalid");
            }
        }
    }
}
