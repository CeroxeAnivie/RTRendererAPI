package top.ceroxe.mcvulkanrt.renderer.backend.vulkan;

import top.ceroxe.mcvulkanrt.renderer.api.MaterialAsset;
import top.ceroxe.mcvulkanrt.renderer.api.MeshAsset;
import top.ceroxe.mcvulkanrt.renderer.api.SceneInstance;
import top.ceroxe.mcvulkanrt.renderer.api.SceneLight;
import top.ceroxe.mcvulkanrt.renderer.api.SceneTransaction;
import top.ceroxe.mcvulkanrt.renderer.api.TextureAsset;

import java.util.List;
import java.util.Objects;

/** Aggregates independently uploadable GPUScene domains behind one atomic scene generation. */
final class VulkanSceneResidency {
    private final StableIdentitySlots<TextureAsset> textures = new StableIdentitySlots<>(TextureAsset::id);
    private final StableIdentitySlots<MaterialAsset> materials = new StableIdentitySlots<>(MaterialAsset::id);
    private final StableIdentitySlots<MeshAsset> meshes = new StableIdentitySlots<>(MeshAsset::id);
    private final StableIdentitySlots<SceneInstance> instances = new StableIdentitySlots<>(SceneInstance::id);
    private final StableIdentitySlots<SceneLight> lights = new StableIdentitySlots<>(SceneLight::id);

    private long revision = -1L;

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
                        change(preparedInstances),
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

    synchronized int textureSlot(long id) { return textures.slot(id); }
    synchronized int materialSlot(long id) { return materials.slot(id); }
    synchronized int meshSlot(long id) { return meshes.slot(id); }
    synchronized int instanceSlot(long id) { return instances.slot(id); }
    synchronized int lightSlot(long id) { return lights.slot(id); }

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

        long baseRevision() { return baseRevision; }
        long revision() { return revision; }
        StableIdentitySlots.Prepared<TextureAsset> textures() { return textures; }
        StableIdentitySlots.Prepared<MaterialAsset> materials() { return materials; }
        StableIdentitySlots.Prepared<MeshAsset> meshes() { return meshes; }
        StableIdentitySlots.Prepared<SceneInstance> instances() { return instances; }
        StableIdentitySlots.Prepared<SceneLight> lights() { return lights; }
        SceneChangeSet changeSet() { return changeSet; }
    }

    /** Immutable sparse GPUScene payload given to native admission; it has no commit capability. */
    record SceneChangeSet(
            long baseRevision,
            long revision,
            boolean reset,
            DomainChange<TextureAsset> textures,
            DomainChange<MaterialAsset> materials,
            DomainChange<MeshAsset> meshes,
            DomainChange<SceneInstance> instances,
            DomainChange<SceneLight> lights
    ) {
        SceneChangeSet {
            if (baseRevision < -1L || revision < 0L || revision <= baseRevision) {
                throw new IllegalArgumentException("resident scene change-set revisions must advance");
            }
            textures = Objects.requireNonNull(textures, "textures");
            materials = Objects.requireNonNull(materials, "materials");
            meshes = Objects.requireNonNull(meshes, "meshes");
            instances = Objects.requireNonNull(instances, "instances");
            lights = Objects.requireNonNull(lights, "lights");
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
