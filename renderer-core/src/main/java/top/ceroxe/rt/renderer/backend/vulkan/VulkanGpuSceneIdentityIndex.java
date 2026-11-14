package top.ceroxe.rt.renderer.backend.vulkan;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.Objects;

/**
 * Incremental identity-to-stable-slot authority used while packing cross-resource references.
 */
final class VulkanGpuSceneIdentityIndex {
    private final DomainIndex textures = new DomainIndex("texture");
    private final DomainIndex materials = new DomainIndex("material");
    private final DomainIndex meshes = new DomainIndex("mesh");
    private final DomainIndex instances = new DomainIndex("instance");
    private final DomainIndex lights = new DomainIndex("light");
    private long revision = -1L;

    synchronized Prepared prepare(VulkanSceneResidency.SceneChangeSet changeSet) {
        VulkanSceneResidency.SceneChangeSet changes = Objects.requireNonNull(changeSet, "changeSet");
        if (changes.baseRevision() != revision) {
            throw new IllegalStateException("identity index revision does not match resident change set: index="
                    + revision + ", base=" + changes.baseRevision());
        }
        return new Prepared(
                this, revision, changes.revision(), changes.reset(),
                textures.prepare(changes.reset(), changes.textures()),
                materials.prepare(changes.reset(), changes.materials()),
                meshes.prepare(changes.reset(), changes.meshes()),
                instances.prepare(changes.reset(), changes.instances()),
                lights.prepare(changes.reset(), changes.lights())
        );
    }

    synchronized void validate(Prepared prepared) {
        Prepared checked = Objects.requireNonNull(prepared, "prepared");
        if (checked.owner != this || checked.committed || checked.baseRevision != revision) {
            throw new IllegalStateException("identity index prepared generation is stale or invalid");
        }
        textures.validate(checked.textures);
        materials.validate(checked.materials);
        meshes.validate(checked.meshes);
        instances.validate(checked.instances);
        lights.validate(checked.lights);
    }

    synchronized void commit(Prepared prepared) {
        validate(prepared);
        commitValidated(prepared);
    }

    /**
     * Publishes a plan already validated together with the memory and native transfer owners.
     */
    synchronized void commitValidated(Prepared prepared) {
        Prepared checked = Objects.requireNonNull(prepared, "prepared");
        textures.commit(checked.textures);
        materials.commit(checked.materials);
        meshes.commit(checked.meshes);
        instances.commit(checked.instances);
        lights.commit(checked.lights);
        revision = checked.revision;
        checked.committed = true;
    }

    synchronized long revision() {
        return revision;
    }

    synchronized int meshSlot(long identity) {
        return meshes.currentSlot(identity);
    }

    synchronized int instanceSlot(long identity) {
        return instances.currentSlot(identity);
    }

    static final class Prepared {
        private final VulkanGpuSceneIdentityIndex owner;
        private final long baseRevision;
        private final long revision;
        private final boolean reset;
        private final DomainDelta textures;
        private final DomainDelta materials;
        private final DomainDelta meshes;
        private final DomainDelta instances;
        private final DomainDelta lights;
        private boolean committed;

        private Prepared(
                VulkanGpuSceneIdentityIndex owner, long baseRevision, long revision, boolean reset,
                DomainDelta textures, DomainDelta materials, DomainDelta meshes,
                DomainDelta instances, DomainDelta lights
        ) {
            this.owner = owner;
            this.baseRevision = baseRevision;
            this.revision = revision;
            this.reset = reset;
            this.textures = textures;
            this.materials = materials;
            this.meshes = meshes;
            this.instances = instances;
            this.lights = lights;
        }

        long revision() {
            return revision;
        }

        int textureSlot(long id) {
            return textures.resolve(id, reset);
        }

        int materialSlot(long id) {
            return materials.resolve(id, reset);
        }

        int meshSlot(long id) {
            return meshes.resolve(id, reset);
        }

        int instanceSlot(long id) {
            return instances.resolve(id, reset);
        }

        int lightSlot(long id) {
            return lights.resolve(id, reset);
        }
    }

    private static final class DomainIndex {
        private final String label;
        private final Long2IntOpenHashMap slotByIdentity = new Long2IntOpenHashMap();
        private final Int2LongOpenHashMap identityBySlot = new Int2LongOpenHashMap();

        private DomainIndex(String label) {
            this.label = label;
            slotByIdentity.defaultReturnValue(-1);
            identityBySlot.defaultReturnValue(-1L);
        }

        private <T> DomainDelta prepare(boolean reset, VulkanSceneResidency.DomainChange<T> changes) {
            Long2IntOpenHashMap writes = new Long2IntOpenHashMap(changes.writes().size());
            writes.defaultReturnValue(-1);
            Int2LongOpenHashMap pendingIdentityBySlot = new Int2LongOpenHashMap(changes.writes().size());
            pendingIdentityBySlot.defaultReturnValue(-1L);
            LongOpenHashSet removals = new LongOpenHashSet(changes.removedIdentities());
            for (long id : removals) {
                if (!reset && slotByIdentity.get(id) < 0) {
                    throw new IllegalStateException(label + " identity index removal references missing id " + id);
                }
            }
            for (StableIdentitySlots.SlotWrite<T> write : changes.writes()) {
                if (writes.putIfAbsent(write.id(), write.slot()) >= 0) {
                    throw new IllegalStateException(label + " identity index received duplicate write id " + write.id());
                }
                if (pendingIdentityBySlot.putIfAbsent(write.slot(), write.id()) >= 0L) {
                    throw new IllegalStateException(label + " identity index received duplicate target slot " + write.slot());
                }
                if (!reset) {
                    long occupant = identityBySlot.get(write.slot());
                    if (occupant >= 0L && occupant != write.id() && !removals.contains(occupant)) {
                        throw new IllegalStateException(label + " target slot is still owned by identity " + occupant);
                    }
                }
            }
            return new DomainDelta(this, reset, writes, removals);
        }

        private void validate(DomainDelta delta) {
            if (delta.owner != this || delta.committed) {
                throw new IllegalStateException(label + " identity delta is invalid or already committed");
            }
        }

        private void commit(DomainDelta delta) {
            if (delta.reset) {
                slotByIdentity.clear();
                identityBySlot.clear();
            } else {
                for (long id : delta.removals) {
                    int slot = slotByIdentity.remove(id);
                    identityBySlot.remove(slot);
                }
            }
            for (var entry : delta.writes.long2IntEntrySet()) {
                long id = entry.getLongKey();
                int slot = entry.getIntValue();
                int previousSlot = slotByIdentity.put(id, slot);
                if (previousSlot >= 0 && previousSlot != slot) {
                    identityBySlot.remove(previousSlot);
                }
                identityBySlot.put(slot, id);
            }
            delta.committed = true;
        }

        private int currentSlot(long identity) {
            return slotByIdentity.get(identity);
        }

    }

    private static final class DomainDelta {
        private final DomainIndex owner;
        private final Long2IntOpenHashMap writes;
        private final LongOpenHashSet removals;
        private final boolean reset;
        private boolean committed;

        private DomainDelta(
                DomainIndex owner,
                boolean reset,
                Long2IntOpenHashMap writes,
                LongOpenHashSet removals
        ) {
            this.owner = owner;
            this.reset = reset;
            this.writes = writes;
            this.removals = removals;
        }

        private int resolve(long identity, boolean generationReset) {
            int written = writes.get(identity);
            if (written >= 0) return written;
            if (generationReset || removals.contains(identity)) return -1;
            return owner.slotByIdentity.get(identity);
        }
    }
}
