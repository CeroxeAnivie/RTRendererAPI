package top.ceroxe.rt.renderer.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable atomic publication and retirement batch for generic resources.
 *
 * <p>The revision is non-negative here; the renderer session, not this value object, must reject
 * a revision that is not strictly greater than its last accepted revision. Every upsert retains
 * its exact buffer or texture generation. Retirement names an exact generation; broad retirement
 * by stable identity is deliberately not available because it could destroy a newer in-flight
 * allocation. A generation cannot be both upserted and retired in one transaction, and resource
 * kind changes are rejected within one batch.</p>
 */
public final class RenderResourceTransaction {
    private final long revision;
    private final List<BufferResource> buffers;
    private final List<TextureResource> textures;
    private final Set<ResourceGenerationKey> retiredGenerations;

    /** Creates and validates one immutable resource transaction. */
    public RenderResourceTransaction(
            long revision,
            List<? extends BufferResource> buffers,
            List<? extends TextureResource> textures,
            Collection<? extends ResourceGenerationKey> retiredGenerations
    ) {
        if (revision < 0L) throw new IllegalArgumentException("resource transaction revision must not be negative");
        this.revision = revision;
        this.buffers = copyResources(buffers, "buffers");
        this.textures = copyResources(textures, "textures");
        Objects.requireNonNull(retiredGenerations, "retiredGenerations");
        HashSet<ResourceGenerationKey> retired = new HashSet<>();
        for (ResourceGenerationKey generation : retiredGenerations) {
            if (!retired.add(Objects.requireNonNull(generation, "retiredGenerations element"))) {
                throw new IllegalArgumentException("retiredGenerations contains a duplicate generation");
            }
        }
        this.retiredGenerations = Set.copyOf(retired);
        validateConflicts(this.buffers, this.textures, this.retiredGenerations);
    }

    private static <T extends RenderResource> List<T> copyResources(List<? extends T> values, String name) {
        Objects.requireNonNull(values, name);
        ArrayList<T> copy = new ArrayList<>(values.size());
        for (T value : values) copy.add(Objects.requireNonNull(value, name + " element"));
        return List.copyOf(copy);
    }

    private static void validateConflicts(
            List<BufferResource> buffers,
            List<TextureResource> textures,
            Set<ResourceGenerationKey> retired
    ) {
        HashSet<RenderResourceId> upsertedIdentities = new HashSet<>();
        HashSet<ResourceGenerationKey> upsertedGenerations = new HashSet<>();
        for (BufferResource buffer : buffers) {
            if (!upsertedIdentities.add(buffer.id())) {
                throw new IllegalArgumentException("resource transaction contains duplicate buffer identity " + buffer.id());
            }
            upsertedGenerations.add(ResourceGenerationKey.of(buffer));
        }
        for (TextureResource texture : textures) {
            if (!upsertedIdentities.add(texture.id())) {
                throw new IllegalArgumentException("resource transaction contains duplicate or cross-kind identity " + texture.id());
            }
            upsertedGenerations.add(ResourceGenerationKey.of(texture));
        }
        for (ResourceGenerationKey generation : retired) {
            if (upsertedGenerations.contains(generation)) {
                throw new IllegalArgumentException("resource generation is both upserted and retired: " + generation);
            }
        }
    }

    /** @return non-negative revision whose strict ordering is session-owned */
    public long revision() { return revision; }

    /** @return immutable exact buffer generations */
    public List<BufferResource> buffers() { return buffers; }

    /** @return immutable exact texture generations */
    public List<TextureResource> textures() { return textures; }

    /** @return immutable exact resource generations explicitly retired by this transaction */
    public Set<ResourceGenerationKey> retiredGenerations() { return retiredGenerations; }

    /** @return exact generation keys in buffer-then-texture order */
    public List<ResourceGenerationKey> upsertGenerationKeys() {
        ArrayList<ResourceGenerationKey> keys = new ArrayList<>(buffers.size() + textures.size());
        buffers.forEach(resource -> keys.add(ResourceGenerationKey.of(resource)));
        textures.forEach(resource -> keys.add(ResourceGenerationKey.of(resource)));
        return List.copyOf(keys);
    }

    /** @return whether this transaction publishes or retires any identity */
    public boolean hasChanges() {
        return !buffers.isEmpty() || !textures.isEmpty() || !retiredGenerations.isEmpty();
    }

    /** Requires a candidate revision to be strictly newer than a session's accepted revision. */
    public static void requireStrictlyAfter(long previousRevision, long candidateRevision) {
        if (previousRevision < 0L || candidateRevision < 0L || candidateRevision <= previousRevision) {
            throw new IllegalArgumentException("resource transaction revision must strictly increase");
        }
    }

    /** Starts a single-thread-confined transaction builder. */
    public static Builder builder(long revision) { return new Builder(revision); }

    /** Single-thread-confined builder revalidated at immutable publication. */
    public static final class Builder {
        private final long revision;
        private final ArrayList<BufferResource> buffers = new ArrayList<>();
        private final ArrayList<TextureResource> textures = new ArrayList<>();
        private final ArrayList<ResourceGenerationKey> retiredGenerations = new ArrayList<>();

        private Builder(long revision) {
            if (revision < 0L) throw new IllegalArgumentException("resource transaction revision must not be negative");
            this.revision = revision;
        }

        /** Adds one exact buffer generation. */
        public Builder upsert(BufferResource resource) {
            buffers.add(Objects.requireNonNull(resource, "buffer"));
            return this;
        }

        /** Adds one exact texture generation. */
        public Builder upsert(TextureResource resource) {
            textures.add(Objects.requireNonNull(resource, "texture"));
            return this;
        }

        /** Retires one exact storage generation after its consumers have completed. */
        public Builder retire(ResourceGenerationKey generation) {
            retiredGenerations.add(Objects.requireNonNull(generation, "generation"));
            return this;
        }

        /** Builds a fully validated immutable transaction. */
        public RenderResourceTransaction build() {
            return new RenderResourceTransaction(revision, buffers, textures, retiredGenerations);
        }
    }
}
