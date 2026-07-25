package top.ceroxe.rt.renderer.api;

import java.nio.LongBuffer;
import java.util.*;
import java.util.function.ToLongFunction;

/**
 * Atomic persistent-scene mutation batch.
 *
 * <p>Providers apply dependencies in texture -> material -> mesh -> instance/light
 * order, and removals in the reverse order. A reset discards the previous scene
 * before applying this transaction's upserts, so a reset transaction cannot also
 * contain removals. Revisions must be strictly monotonic for a renderer instance.</p>
 *
 */
public final class SceneTransaction {
    private final long revision;
    private final boolean reset;
    private final Upserts upserts;
    private final Removals removals;

    /**
     * Validates and creates an atomic transaction.
     *
     * @param revision non-negative scene revision
     * @param reset    whether the previous persistent scene is discarded first
     * @param upserts  immutable objects to publish
     * @param removals immutable identifiers to remove
     */
    private SceneTransaction(long revision, boolean reset, Upserts upserts, Removals removals) {
        if (revision < 0L) {
            throw new IllegalArgumentException("scene revision must not be negative");
        }
        this.upserts = Objects.requireNonNull(upserts, "upserts");
        this.removals = Objects.requireNonNull(removals, "removals");
        if (reset && removals.hasChanges()) {
            throw new IllegalArgumentException(
                    "reset transaction cannot remove identities from the discarded scene"
            );
        }
        requireUniqueAndDisjoint(upserts.textures(), TextureAsset::id, removals.textureIds, "texture");
        requireUniqueAndDisjoint(upserts.materials(), MaterialAsset::id, removals.materialIds, "material");
        requireUniqueAndDisjoint(upserts.meshes(), MeshAsset::id, removals.meshIds, "mesh");
        requireUniqueAndDisjoint(upserts.instances(), SceneInstance::id, removals.instanceIds, "instance");
        requireUniqueAndDisjoint(upserts.lights(), SceneLight::id, removals.lightIds, "light");
        this.revision = revision;
        this.reset = reset;
    }

    /**
     * Creates a no-op transaction at a specified revision.
     *
     * @param revision non-negative scene revision
     * @return immutable empty transaction
     */
    public static SceneTransaction empty(long revision) {
        return builder(revision).build();
    }

    /**
     * Starts a type-safe transaction builder for one exact revision.
     *
     * @param revision non-negative scene revision
     * @return mutable single-thread-confined builder
     */
    public static Builder builder(long revision) {
        return new Builder(revision);
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

    /**
     * Returns the strictly increasing scene revision.
     *
     * @return non-negative scene revision
     */
    public long revision() {
        return revision;
    }

    /**
     * Reports whether the preceding persistent scene is discarded first.
     *
     * @return whether this transaction resets the scene
     */
    public boolean reset() {
        return reset;
    }

    /**
     * Returns the immutable dependency-ordered upserts.
     *
     * @return immutable upsert snapshot
     */
    public Upserts upserts() {
        return upserts;
    }

    /**
     * Returns the immutable reverse-dependency removals.
     *
     * @return immutable removal snapshot
     */
    public Removals removals() {
        return removals;
    }

    /**
     * Reports whether this transaction changes logical scene state.
     *
     * @return {@code true} for a reset, upsert, or removal
     */
    public boolean hasChanges() {
        return reset || upserts.hasChanges() || removals.hasChanges();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SceneTransaction transaction)) return false;
        return revision == transaction.revision
                && reset == transaction.reset
                && upserts.equals(transaction.upserts)
                && removals.equals(transaction.removals);
    }

    @Override
    public int hashCode() {
        return Objects.hash(revision, reset, upserts, removals);
    }

    @Override
    public String toString() {
        return "SceneTransaction["
                + "revision=" + revision
                + ", reset=" + reset
                + ", upserts=" + upserts
                + ", removals=" + removals
                + ']';
    }

    /**
     * Single-thread-confined convenience builder for dependency-safe scene mutations.
     *
     * <p>Overloaded {@code upsert} methods prevent assets from entering the wrong collection, and
     * domain-named removal methods make raw identifiers locally unambiguous. {@link #build()} still
     * delegates every uniqueness, disjointness, identifier, and immutability check to the final
     * immutable transaction boundary.</p>
     */
    public static final class Builder {
        private final long revision;
        private final ArrayList<TextureAsset> textures = new ArrayList<>();
        private final ArrayList<MaterialAsset> materials = new ArrayList<>();
        private final ArrayList<MeshAsset> meshes = new ArrayList<>();
        private final ArrayList<SceneInstance> instances = new ArrayList<>();
        private final ArrayList<SceneLight> lights = new ArrayList<>();
        private final ArrayList<Long> textureRemovals = new ArrayList<>();
        private final ArrayList<Long> materialRemovals = new ArrayList<>();
        private final ArrayList<Long> meshRemovals = new ArrayList<>();
        private final ArrayList<Long> instanceRemovals = new ArrayList<>();
        private final ArrayList<Long> lightRemovals = new ArrayList<>();
        private boolean reset;

        private Builder(long revision) {
            if (revision < 0L) throw new IllegalArgumentException("scene revision must not be negative");
            this.revision = revision;
        }

        private static void addRemoval(ArrayList<Long> destination, long id, String name) {
            MaterialAsset.requireId(id, name);
            destination.add(id);
        }

        private static void addRemovals(ArrayList<Long> destination, long[] ids, String name) {
            Objects.requireNonNull(ids, name + "s");
            for (long id : ids) addRemoval(destination, id, name);
        }

        private static long[] toLongArray(ArrayList<Long> values) {
            long[] result = new long[values.size()];
            for (int index = 0; index < result.length; index++) result[index] = values.get(index);
            return result;
        }

        /**
         * Discards the preceding persistent scene before applying this builder.
         *
         * @return this builder
         */
        public Builder resetScene() {
            reset = true;
            return this;
        }

        /**
         * Adds or replaces a texture.
         *
         * @param texture immutable texture generation
         * @return this builder
         */
        public Builder upsert(TextureAsset texture) {
            textures.add(Objects.requireNonNull(texture, "texture"));
            return this;
        }

        /**
         * Adds or replaces a material.
         *
         * @param material immutable material generation
         * @return this builder
         */
        public Builder upsert(MaterialAsset material) {
            materials.add(Objects.requireNonNull(material, "material"));
            return this;
        }

        /**
         * Adds or replaces a mesh.
         *
         * @param mesh immutable mesh generation
         * @return this builder
         */
        public Builder upsert(MeshAsset mesh) {
            meshes.add(Objects.requireNonNull(mesh, "mesh"));
            return this;
        }

        /**
         * Adds or replaces an instance.
         *
         * @param instance immutable scene instance
         * @return this builder
         */
        public Builder upsert(SceneInstance instance) {
            instances.add(Objects.requireNonNull(instance, "instance"));
            return this;
        }

        /**
         * Adds or replaces a light.
         *
         * @param light immutable analytic light
         * @return this builder
         */
        public Builder upsert(SceneLight light) {
            lights.add(Objects.requireNonNull(light, "light"));
            return this;
        }

        /**
         * Adds or replaces every texture in iteration order.
         *
         * @param values non-null texture generations
         * @return this builder
         */
        public Builder upsertTextures(Iterable<? extends TextureAsset> values) {
            Objects.requireNonNull(values, "textures").forEach(this::upsert);
            return this;
        }

        /**
         * Adds or replaces every material in iteration order.
         *
         * @param values non-null material generations
         * @return this builder
         */
        public Builder upsertMaterials(Iterable<? extends MaterialAsset> values) {
            Objects.requireNonNull(values, "materials").forEach(this::upsert);
            return this;
        }

        /**
         * Adds or replaces every mesh in iteration order.
         *
         * @param values non-null mesh generations
         * @return this builder
         */
        public Builder upsertMeshes(Iterable<? extends MeshAsset> values) {
            Objects.requireNonNull(values, "meshes").forEach(this::upsert);
            return this;
        }

        /**
         * Adds or replaces every instance in iteration order.
         *
         * @param values non-null scene instances
         * @return this builder
         */
        public Builder upsertInstances(Iterable<? extends SceneInstance> values) {
            Objects.requireNonNull(values, "instances").forEach(this::upsert);
            return this;
        }

        /**
         * Adds or replaces every light in iteration order.
         *
         * @param values non-null analytic lights
         * @return this builder
         */
        public Builder upsertLights(Iterable<? extends SceneLight> values) {
            Objects.requireNonNull(values, "lights").forEach(this::upsert);
            return this;
        }

        /**
         * Removes a texture generation.
         *
         * @param textureId non-negative texture identifier
         * @return this builder
         */
        public Builder removeTexture(long textureId) {
            addRemoval(textureRemovals, textureId, "textureId");
            return this;
        }

        /**
         * Removes a material generation.
         *
         * @param materialId non-negative material identifier
         * @return this builder
         */
        public Builder removeMaterial(long materialId) {
            addRemoval(materialRemovals, materialId, "materialId");
            return this;
        }

        /**
         * Removes a mesh generation.
         *
         * @param meshId non-negative mesh identifier
         * @return this builder
         */
        public Builder removeMesh(long meshId) {
            addRemoval(meshRemovals, meshId, "meshId");
            return this;
        }

        /**
         * Removes an instance.
         *
         * @param instanceId non-negative instance identifier
         * @return this builder
         */
        public Builder removeInstance(long instanceId) {
            addRemoval(instanceRemovals, instanceId, "instanceId");
            return this;
        }

        /**
         * Removes a light.
         *
         * @param lightId non-negative light identifier
         * @return this builder
         */
        public Builder removeLight(long lightId) {
            addRemoval(lightRemovals, lightId, "lightId");
            return this;
        }

        /**
         * Removes every supplied texture identity.
         *
         * @param ids texture identifiers
         * @return this builder
         */
        public Builder removeTextures(long... ids) {
            addRemovals(textureRemovals, ids, "textureId");
            return this;
        }

        /**
         * Removes every supplied material identity.
         *
         * @param ids material identifiers
         * @return this builder
         */
        public Builder removeMaterials(long... ids) {
            addRemovals(materialRemovals, ids, "materialId");
            return this;
        }

        /**
         * Removes every supplied mesh identity.
         *
         * @param ids mesh identifiers
         * @return this builder
         */
        public Builder removeMeshes(long... ids) {
            addRemovals(meshRemovals, ids, "meshId");
            return this;
        }

        /**
         * Removes every supplied instance identity.
         *
         * @param ids instance identifiers
         * @return this builder
         */
        public Builder removeInstances(long... ids) {
            addRemovals(instanceRemovals, ids, "instanceId");
            return this;
        }

        /**
         * Removes every supplied light identity.
         *
         * @param ids light identifiers
         * @return this builder
         */
        public Builder removeLights(long... ids) {
            addRemovals(lightRemovals, ids, "lightId");
            return this;
        }

        /**
         * Builds an immutable transaction snapshot. The builder remains reusable; later mutations
         * cannot change previously built transactions.
         *
         * @return validated immutable transaction
         */
        public SceneTransaction build() {
            return new SceneTransaction(
                    revision,
                    reset,
                    new Upserts(textures, materials, meshes, instances, lights),
                    new Removals(
                            toLongArray(textureRemovals),
                            toLongArray(materialRemovals),
                            toLongArray(meshRemovals),
                            toLongArray(instanceRemovals),
                            toLongArray(lightRemovals)
                    )
            );
        }
    }

    /**
     * Immutable dependency-ordered objects to insert or replace.
     *
     */
    public static final class Upserts {
        private final List<TextureAsset> textures;
        private final List<MaterialAsset> materials;
        private final List<MeshAsset> meshes;
        private final List<SceneInstance> instances;
        private final List<SceneLight> lights;

        /**
         * Defensively copies and validates all upsert collections.
         *
         * @param textures  texture generations
         * @param materials material generations
         * @param meshes    mesh generations
         * @param instances scene instances
         * @param lights    analytic lights
         */
        private Upserts(
                List<TextureAsset> textures,
                List<MaterialAsset> materials,
                List<MeshAsset> meshes,
                List<SceneInstance> instances,
                List<SceneLight> lights
        ) {
            this.textures = immutableNonNull(textures, "textures");
            this.materials = immutableNonNull(materials, "materials");
            this.meshes = immutableNonNull(meshes, "meshes");
            this.instances = immutableNonNull(instances, "instances");
            this.lights = immutableNonNull(lights, "lights");
        }

        /**
         * Returns the texture generations.
         *
         * @return immutable texture list
         */
        public List<TextureAsset> textures() {
            return textures;
        }

        /**
         * Returns the material generations.
         *
         * @return immutable material list
         */
        public List<MaterialAsset> materials() {
            return materials;
        }

        /**
         * Returns the mesh generations.
         *
         * @return immutable mesh list
         */
        public List<MeshAsset> meshes() {
            return meshes;
        }

        /**
         * Returns the scene instances.
         *
         * @return immutable instance list
         */
        public List<SceneInstance> instances() {
            return instances;
        }

        /**
         * Returns the analytic lights.
         *
         * @return immutable light list
         */
        public List<SceneLight> lights() {
            return lights;
        }

        /**
         * Reports whether any upsert exists.
         *
         * @return {@code true} when any dependency collection is non-empty
         */
        public boolean hasChanges() {
            return !textures.isEmpty() || !materials.isEmpty() || !meshes.isEmpty()
                    || !instances.isEmpty() || !lights.isEmpty();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Upserts upserts)) return false;
            return textures.equals(upserts.textures)
                    && materials.equals(upserts.materials)
                    && meshes.equals(upserts.meshes)
                    && instances.equals(upserts.instances)
                    && lights.equals(upserts.lights);
        }

        @Override
        public int hashCode() {
            return Objects.hash(textures, materials, meshes, instances, lights);
        }

        @Override
        public String toString() {
            return "Upserts["
                    + "textures=" + textures
                    + ", materials=" + materials
                    + ", meshes=" + meshes
                    + ", instances=" + instances
                    + ", lights=" + lights
                    + ']';
        }
    }

    /**
     * Immutable dependency identifiers to remove in reverse dependency order.
     */
    public static final class Removals {
        private final long[] textureIds;
        private final long[] materialIds;
        private final long[] meshIds;
        private final long[] instanceIds;
        private final long[] lightIds;

        /**
         * Creates immutable removal sets by defensively copying every array.
         *
         * @param textureIds  texture identifiers
         * @param materialIds material identifiers
         * @param meshIds     mesh identifiers
         * @param instanceIds instance identifiers
         * @param lightIds    light identifiers
         */
        private Removals(
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

        /**
         * Returns texture removals.
         *
         * @return new read-only texture-id buffer
         */
        public LongBuffer textureIds() {
            return LongBuffer.wrap(textureIds).asReadOnlyBuffer();
        }

        /**
         * Returns material removals.
         *
         * @return new read-only material-id buffer
         */
        public LongBuffer materialIds() {
            return LongBuffer.wrap(materialIds).asReadOnlyBuffer();
        }

        /**
         * Returns mesh removals.
         *
         * @return new read-only mesh-id buffer
         */
        public LongBuffer meshIds() {
            return LongBuffer.wrap(meshIds).asReadOnlyBuffer();
        }

        /**
         * Returns instance removals.
         *
         * @return new read-only instance-id buffer
         */
        public LongBuffer instanceIds() {
            return LongBuffer.wrap(instanceIds).asReadOnlyBuffer();
        }

        /**
         * Returns light removals.
         *
         * @return new read-only light-id buffer
         */
        public LongBuffer lightIds() {
            return LongBuffer.wrap(lightIds).asReadOnlyBuffer();
        }

        /**
         * Reports whether any removal exists.
         *
         * @return {@code true} when any removal collection is non-empty
         */
        public boolean hasChanges() {
            return textureIds.length != 0 || materialIds.length != 0 || meshIds.length != 0
                    || instanceIds.length != 0 || lightIds.length != 0;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Removals removals)) return false;
            return java.util.Arrays.equals(textureIds, removals.textureIds)
                    && java.util.Arrays.equals(materialIds, removals.materialIds)
                    && java.util.Arrays.equals(meshIds, removals.meshIds)
                    && java.util.Arrays.equals(instanceIds, removals.instanceIds)
                    && java.util.Arrays.equals(lightIds, removals.lightIds);
        }

        @Override
        public int hashCode() {
            int result = java.util.Arrays.hashCode(textureIds);
            result = 31 * result + java.util.Arrays.hashCode(materialIds);
            result = 31 * result + java.util.Arrays.hashCode(meshIds);
            result = 31 * result + java.util.Arrays.hashCode(instanceIds);
            return 31 * result + java.util.Arrays.hashCode(lightIds);
        }

        @Override
        public String toString() {
            return "Removals["
                    + "textureIds=" + java.util.Arrays.toString(textureIds)
                    + ", materialIds=" + java.util.Arrays.toString(materialIds)
                    + ", meshIds=" + java.util.Arrays.toString(meshIds)
                    + ", instanceIds=" + java.util.Arrays.toString(instanceIds)
                    + ", lightIds=" + java.util.Arrays.toString(lightIds)
                    + ']';
        }
    }
}
