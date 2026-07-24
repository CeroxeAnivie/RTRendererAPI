package top.ceroxe.mcvulkanrt.renderer.api;

import java.nio.LongBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToLongFunction;

/**
 * Atomic persistent-scene mutation batch.
 *
 * <p>Providers apply dependencies in texture -> material -> mesh -> instance/light
 * order, and removals in the reverse order. A reset discards the previous scene
 * before applying this transaction's upserts. Revisions must be strictly
 * monotonic for a renderer instance.</p>
 */
public record SceneTransaction(long revision, boolean reset, Upserts upserts, Removals removals) {
    public SceneTransaction {
        if (revision < 0L) {
            throw new IllegalArgumentException("scene revision must not be negative");
        }
        upserts = Objects.requireNonNull(upserts, "upserts");
        removals = Objects.requireNonNull(removals, "removals");
        requireUniqueAndDisjoint(upserts.textures(), TextureAsset::id, removals.textureIds, "texture");
        requireUniqueAndDisjoint(upserts.materials(), MaterialAsset::id, removals.materialIds, "material");
        requireUniqueAndDisjoint(upserts.meshes(), MeshAsset::id, removals.meshIds, "mesh");
        requireUniqueAndDisjoint(upserts.instances(), SceneInstance::id, removals.instanceIds, "instance");
        requireUniqueAndDisjoint(upserts.lights(), SceneLight::id, removals.lightIds, "light");
    }

    public boolean hasChanges() {
        return reset || upserts.hasChanges() || removals.hasChanges();
    }

    public static SceneTransaction empty(long revision) {
        return new SceneTransaction(revision, false, Upserts.empty(), Removals.empty());
    }

    public record Upserts(
            List<TextureAsset> textures,
            List<MaterialAsset> materials,
            List<MeshAsset> meshes,
            List<SceneInstance> instances,
            List<SceneLight> lights
    ) {
        public Upserts {
            textures = immutableNonNull(textures, "textures");
            materials = immutableNonNull(materials, "materials");
            meshes = immutableNonNull(meshes, "meshes");
            instances = immutableNonNull(instances, "instances");
            lights = immutableNonNull(lights, "lights");
        }

        public static Upserts empty() {
            return new Upserts(List.of(), List.of(), List.of(), List.of(), List.of());
        }

        public boolean hasChanges() {
            return !textures.isEmpty() || !materials.isEmpty() || !meshes.isEmpty()
                    || !instances.isEmpty() || !lights.isEmpty();
        }
    }

    public static final class Removals {
        private final long[] textureIds;
        private final long[] materialIds;
        private final long[] meshIds;
        private final long[] instanceIds;
        private final long[] lightIds;

        public Removals(
                long[] textureIds,
                long[] materialIds,
                long[] meshIds,
                long[] instanceIds,
                long[] lightIds
        ) {
            this.textureIds = copyIds(textureIds, "textureIds");
            this.materialIds = copyIds(materialIds, "materialIds");
            this.meshIds = copyIds(meshIds, "meshIds");
            this.instanceIds = copyIds(instanceIds, "instanceIds");
            this.lightIds = copyIds(lightIds, "lightIds");
        }

        public static Removals empty() {
            return new Removals(new long[0], new long[0], new long[0], new long[0], new long[0]);
        }

        public LongBuffer textureIds() { return LongBuffer.wrap(textureIds).asReadOnlyBuffer(); }
        public LongBuffer materialIds() { return LongBuffer.wrap(materialIds).asReadOnlyBuffer(); }
        public LongBuffer meshIds() { return LongBuffer.wrap(meshIds).asReadOnlyBuffer(); }
        public LongBuffer instanceIds() { return LongBuffer.wrap(instanceIds).asReadOnlyBuffer(); }
        public LongBuffer lightIds() { return LongBuffer.wrap(lightIds).asReadOnlyBuffer(); }

        public boolean hasChanges() {
            return textureIds.length != 0 || materialIds.length != 0 || meshIds.length != 0
                    || instanceIds.length != 0 || lightIds.length != 0;
        }
    }

    private static <T> List<T> immutableNonNull(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        for (T value : values) {
            Objects.requireNonNull(value, name + " element");
        }
        return List.copyOf(values);
    }

    private static long[] copyIds(long[] values, String name) {
        long[] copy = Objects.requireNonNull(values, name).clone();
        Set<Long> unique = new HashSet<>(copy.length * 2);
        for (long id : copy) {
            MaterialAsset.requireId(id, name + " element");
            if (!unique.add(id)) {
                throw new IllegalArgumentException(name + " contains duplicate id " + id);
            }
        }
        return copy;
    }

    private static <T> void requireUniqueAndDisjoint(
            List<T> upserts,
            ToLongFunction<T> idFunction,
            long[] removals,
            String kind
    ) {
        Set<Long> ids = new HashSet<>(upserts.size() * 2 + removals.length * 2);
        for (T upsert : upserts) {
            long id = idFunction.applyAsLong(upsert);
            if (!ids.add(id)) {
                throw new IllegalArgumentException(kind + " upserts contain duplicate id " + id);
            }
        }
        for (long removal : removals) {
            if (!ids.add(removal)) {
                throw new IllegalArgumentException(kind + " id is both upserted and removed: " + removal);
            }
        }
    }
}
