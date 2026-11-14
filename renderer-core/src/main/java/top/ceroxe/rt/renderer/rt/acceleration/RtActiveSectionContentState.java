package top.ceroxe.rt.renderer.rt.acceleration;

import top.ceroxe.rt.renderer.RendererFrameCausality;
import top.ceroxe.rt.renderer.SectionCausalitySnapshot;
import top.ceroxe.rt.renderer.SectionRevisionSnapshot;
import top.ceroxe.rt.renderer.scene.PackedSectionMembership;
import top.ceroxe.rt.renderer.scene.SectionKey;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Owns the revision/provenance pair represented by every live section BLAS.
 *
 * <p>Revision and causality are one publication, never two best-effort maps.  All mutation paths
 * enter through this owner, which both prevents eviction drift and gives TLAS consumers one cached
 * immutable publication.  A texture-only TLAS input miss can therefore reuse the same revision and
 * causality arrays instead of rescanning every active section.</p>
 */
final class RtActiveSectionContentState {
    private final Map<SectionKey, Long> revisions = new HashMap<>();
    private final Map<SectionKey, RendererFrameCausality> causalities = new HashMap<>();
    private long generation;
    private long publishedGeneration = -1L;
    private PackedSectionMembership publishedMembership = PackedSectionMembership.empty();
    private Publication publication = Publication.empty();

    void install(SectionKey key, long revision, RendererFrameCausality causality) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(causality, "causality");
        if (revision < 0L) {
            throw new IllegalArgumentException("active section content revision must not be negative");
        }
        assertPairShape(key);
        Long previousRevision = revisions.get(key);
        RendererFrameCausality previousCausality = causalities.get(key);
        if (Objects.equals(previousRevision, revision) && Objects.equals(previousCausality, causality)) {
            return;
        }
        long nextGeneration = Math.incrementExact(generation);
        revisions.put(key, revision);
        causalities.put(key, causality);
        generation = nextGeneration;
    }

    void remove(SectionKey key) {
        Objects.requireNonNull(key, "key");
        assertPairShape(key);
        if (!revisions.containsKey(key)) {
            throw new IllegalStateException("removed live BLAS has no active section provenance for " + key);
        }
        long nextGeneration = Math.incrementExact(generation);
        revisions.remove(key);
        causalities.remove(key);
        generation = nextGeneration;
        if (revisions.isEmpty()) {
            clearPublication();
        }
    }

    void clear() {
        assertGlobalShape();
        if (revisions.isEmpty()) {
            clearPublication();
            return;
        }
        long nextGeneration = Math.incrementExact(generation);
        revisions.clear();
        causalities.clear();
        generation = nextGeneration;
        clearPublication();
    }

    Long revision(SectionKey key) {
        return revisions.get(Objects.requireNonNull(key, "key"));
    }

    long revisionOrDefault(SectionKey key, long fallback) {
        return revisions.getOrDefault(Objects.requireNonNull(key, "key"), fallback);
    }

    RendererFrameCausality causality(SectionKey key) {
        return causalities.get(Objects.requireNonNull(key, "key"));
    }

    RendererFrameCausality causalityOrDefault(SectionKey key, RendererFrameCausality fallback) {
        Objects.requireNonNull(fallback, "fallback");
        return causalities.getOrDefault(Objects.requireNonNull(key, "key"), fallback);
    }

    boolean contains(SectionKey key) {
        return revisions.containsKey(Objects.requireNonNull(key, "key"));
    }

    int size() {
        return revisions.size();
    }

    /**
     * Locates the first broken publication member only after publication has failed.
     *
     * <p>This deliberately stays out of the healthy path: {@link SectionRevisionSnapshot#select}
     * already validates every requested key, so a successful frame must not pay for a second
     * ownership scan merely to feed diagnostics.</p>
     */
    SectionKey firstMissingRevision(PackedSectionMembership membership) {
        Objects.requireNonNull(membership, "membership");
        for (SectionKey key : membership) {
            if (!revisions.containsKey(key)) {
                return key;
            }
        }
        return null;
    }

    Publication publication(PackedSectionMembership membership) {
        Objects.requireNonNull(membership, "membership");
        if (publishedGeneration == generation && publishedMembership == membership) {
            return publication;
        }
        assertGlobalShape();
        SectionRevisionSnapshot revisionSnapshot = SectionRevisionSnapshot.select(
                membership,
                revisions,
                publication.revisions()
        );
        SectionCausalitySnapshot causalitySnapshot = SectionCausalitySnapshot.select(
                revisionSnapshot,
                causalities,
                publication.causalities()
        );
        publication = new Publication(revisionSnapshot, causalitySnapshot);
        publishedMembership = membership;
        publishedGeneration = generation;
        return publication;
    }

    long generation() {
        return generation;
    }

    private void clearPublication() {
        publication = Publication.empty();
        publishedMembership = PackedSectionMembership.empty();
        publishedGeneration = -1L;
    }

    private void assertPairShape(SectionKey key) {
        if (revisions.containsKey(key) != causalities.containsKey(key)) {
            throw new IllegalStateException("active section revision/causality ownership diverged for " + key);
        }
    }

    private void assertGlobalShape() {
        if (revisions.size() != causalities.size() || !revisions.keySet().equals(causalities.keySet())) {
            throw new IllegalStateException("active section revision/causality publications diverged");
        }
    }

    record Publication(
            SectionRevisionSnapshot revisions,
            SectionCausalitySnapshot causalities
    ) {
        Publication {
            Objects.requireNonNull(revisions, "revisions");
            Objects.requireNonNull(causalities, "causalities");
            if (causalities.revisions() != revisions) {
                throw new IllegalArgumentException("active revision and causality snapshots must cover the same keys");
            }
        }

        private static Publication empty() {
            return new Publication(SectionRevisionSnapshot.empty(), SectionCausalitySnapshot.empty());
        }
    }
}
