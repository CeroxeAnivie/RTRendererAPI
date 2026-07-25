package top.ceroxe.rt.renderer.rt.acceleration;

import top.ceroxe.rt.renderer.RendererFrameCausality;
import top.ceroxe.rt.renderer.rt.material.RtSceneMaterialTable;
import top.ceroxe.rt.renderer.scene.PackedSectionMembership;
import top.ceroxe.rt.renderer.scene.SectionKey;
import top.ceroxe.rt.renderer.scene.SectionTriangleMesh;

import java.util.*;

/**
 * Owns the coherent CPU source publication consumed by exact and FarField BLAS stages.
 *
 * <p>This owner deliberately stops at the Java-heap lifetime boundary. It never owns, retires,
 * or closes a native acceleration structure, and it does not call back into
 * {@link RtSectionBlasCache}. Mutations return small facts which let the cache update its
 * cross-owner residency union without exposing this store's mutable maps or counters.</p>
 *
 * <p>Candidate publications and every checked counter/revision are prepared before the first
 * state write. A malformed mesh, publication-construction failure, or arithmetic overflow cannot
 * leave the publication map and its byte/membership accounting on different generations.</p>
 */
final class RtSectionSourceStore {
    private final long maxPayloadBytes;
    private final Map<SectionKey, RtSectionSourcePublication> publications =
            new LinkedHashMap<>();
    /*
     * Publication reads include diagnostics and FarField planning; neither may perturb eviction.
     * Track heavyweight Base payload recency separately so only a real mesh consumer changes LRU.
     */
    private final LinkedHashMap<SectionKey, Boolean> payloadLru =
            new LinkedHashMap<>(16, 0.75f, true);
    private final Map<SectionKey, RtSectionSourcePublication> readOnlyPublications =
            Collections.unmodifiableMap(publications);
    private final PackedSectionMembership.Builder membershipBuilder = PackedSectionMembership.builder(0);

    private PackedSectionMembership membership = PackedSectionMembership.empty();
    private long publishedMembershipRevision = -1L;
    private long membershipRevision;
    private long sourceGeneration;
    private long geometryPublicationRevision;
    private long materialPublicationRevision;
    private long payloadBytes;
    private int payloadCount;

    RtSectionSourceStore(long maxPayloadBytes) {
        if (maxPayloadBytes <= 0L) {
            throw new IllegalArgumentException("maxPayloadBytes must be positive");
        }
        this.maxPayloadBytes = maxPayloadBytes;
    }

    private static long checkedPayloadBytesAfterReplacement(
            long currentBytes,
            long replacedBytes,
            long addedBytes
    ) {
        if (currentBytes < 0L || replacedBytes < 0L || addedBytes < 0L) {
            throw new IllegalStateException("section source payload byte accounting must not be negative");
        }
        long retainedBytes = Math.subtractExact(currentBytes, replacedBytes);
        if (retainedBytes < 0L) {
            throw new IllegalStateException("section source payload byte accounting underflow");
        }
        return Math.addExact(retainedBytes, addedBytes);
    }

    RtSectionSourcePublication publication(SectionKey key) {
        return publications.get(Objects.requireNonNull(key, "key"));
    }

    SectionTriangleMesh mesh(SectionKey key) {
        RtSectionSourcePublication publication = publication(key);
        if (publication == null || !publication.hasPayload()) {
            return null;
        }
        touchPayload(key);
        return publication.requireMesh();
    }

    long geometryGeneration(SectionKey key) {
        RtSectionSourcePublication publication = publication(key);
        return publication == null ? -1L : publication.geometryGeneration();
    }

    long materialGeneration(SectionKey key) {
        RtSectionSourcePublication publication = publication(key);
        return publication == null ? -1L : publication.materialGeneration();
    }

    boolean containsPublication(SectionKey key) {
        return publications.containsKey(Objects.requireNonNull(key, "key"));
    }

    /**
     * Exposes one stable, mutation-rejecting map facade to package-local FarField consumers.
     * Reads are observational and never affect the separate source-payload LRU; no consumer can
     * insert, replace, remove, or mutate an entry through this view.
     */
    Map<SectionKey, RtSectionSourcePublication> publicationsView() {
        return readOnlyPublications;
    }

    Mutation publish(
            SectionTriangleMesh mesh,
            RtSceneMaterialTable.SectionMaterial material,
            long contentRevision,
            RendererFrameCausality causality,
            boolean forceProxyGeometryGeneration,
            boolean materialChanged
    ) {
        Objects.requireNonNull(mesh, "mesh");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(causality, "causality");
        if (contentRevision < 0L) {
            throw new IllegalArgumentException("section source content revision must not be negative");
        }

        SectionKey key = mesh.key();
        RtSectionSourcePublication previousPublication = publications.get(key);
        SectionTriangleMesh previousMesh = previousPublication == null ? null : previousPublication.mesh();
        boolean payloadAdded = previousPublication == null || !previousPublication.hasPayload();
        int nextPayloadCount = payloadAdded ? Math.incrementExact(payloadCount) : payloadCount;

        boolean proxyGeometryChanged = forceProxyGeometryGeneration
                || previousMesh == null
                || !previousMesh.hasSameProxyGeometry(mesh);
        long nextSourceGeneration = sourceGeneration;
        long nextGeometryPublicationRevision = geometryPublicationRevision;
        long geometryGeneration;
        if (proxyGeometryChanged) {
            nextSourceGeneration = Math.incrementExact(nextSourceGeneration);
            geometryGeneration = nextSourceGeneration;
            nextGeometryPublicationRevision = Math.incrementExact(nextGeometryPublicationRevision);
        } else {
            geometryGeneration = previousPublication.geometryGeneration();
        }

        boolean sourceMaterialChanged = materialChanged || previousMesh == null;
        long nextMaterialPublicationRevision = materialPublicationRevision;
        long materialGeneration;
        if (sourceMaterialChanged) {
            nextSourceGeneration = Math.incrementExact(nextSourceGeneration);
            materialGeneration = nextSourceGeneration;
            nextMaterialPublicationRevision = Math.incrementExact(nextMaterialPublicationRevision);
        } else {
            materialGeneration = previousPublication.materialGeneration();
        }

        long nextMembershipRevision = previousPublication == null
                ? Math.incrementExact(membershipRevision)
                : membershipRevision;
        RtFarFieldSectionSource farFieldSource = proxyGeometryChanged || previousPublication == null
                ? RtFarFieldSectionSource.fromMesh(mesh)
                : previousPublication.requireFarFieldSource().refreshMaterials(mesh);
        RtSectionSourcePublication candidate = new RtSectionSourcePublication(
                mesh,
                material,
                farFieldSource,
                geometryGeneration,
                materialGeneration,
                contentRevision,
                causality
        );
        long previousPayloadBytes = previousPublication == null
                ? 0L
                : previousPublication.basePayloadEstimatedBytes();
        long candidatePayloadBytes = candidate.basePayloadEstimatedBytes();
        long nextPayloadBytes = checkedPayloadBytesAfterReplacement(
                payloadBytes,
                previousPayloadBytes,
                candidatePayloadBytes
        );
        Mutation mutation = new Mutation(
                previousPublication == null ? 1 : 0,
                0,
                payloadAdded ? 1 : 0,
                0,
                candidatePayloadBytes,
                previousPayloadBytes,
                proxyGeometryChanged,
                sourceMaterialChanged
        );

        /* Commit only after the complete candidate and all checked next-state values exist. */
        publications.put(key, candidate);
        touchPayload(key);
        payloadBytes = nextPayloadBytes;
        payloadCount = nextPayloadCount;
        sourceGeneration = nextSourceGeneration;
        geometryPublicationRevision = nextGeometryPublicationRevision;
        materialPublicationRevision = nextMaterialPublicationRevision;
        membershipRevision = nextMembershipRevision;
        return mutation;
    }

    Mutation releasePayload(SectionKey key) {
        Objects.requireNonNull(key, "key");
        RtSectionSourcePublication publication = publications.get(key);
        if (publication == null || !publication.hasPayload()) {
            return Mutation.NONE;
        }
        if (payloadCount <= 0) {
            throw new IllegalStateException("section source payload accounting underflow");
        }
        long releasedBytes = publication.basePayloadEstimatedBytes();
        long nextPayloadBytes = Math.subtractExact(payloadBytes, releasedBytes);
        if (nextPayloadBytes < 0L) {
            throw new IllegalStateException("section source payload byte accounting underflow");
        }
        RtSectionSourcePublication candidate = publication.withoutPayload();
        Mutation mutation = new Mutation(0, 0, 0, 1, 0L, releasedBytes, false, false);

        publications.put(key, candidate);
        payloadLru.remove(key);
        payloadCount--;
        payloadBytes = nextPayloadBytes;
        return mutation;
    }

    Mutation releaseCompletedPayload(SectionTriangleMesh completedMesh) {
        Objects.requireNonNull(completedMesh, "completedMesh");
        RtSectionSourcePublication publication = publications.get(completedMesh.key());
        return publication != null && publication.mesh() == completedMesh
                ? releasePayload(completedMesh.key())
                : Mutation.NONE;
    }

    Mutation removeIfUnretained(
            SectionKey key,
            boolean activeBlasPresent,
            boolean retainedByPresentation
    ) {
        Objects.requireNonNull(key, "key");
        RtSectionSourcePublication publication = publications.get(key);
        if (publication == null
                || publication.hasPayload()
                || activeBlasPresent
                || retainedByPresentation) {
            return Mutation.NONE;
        }
        return remove(key);
    }

    Mutation remove(SectionKey key) {
        Objects.requireNonNull(key, "key");
        RtSectionSourcePublication publication = publications.get(key);
        if (publication == null) {
            return Mutation.NONE;
        }

        int nextPayloadCount = payloadCount;
        long nextPayloadBytes = payloadBytes;
        long releasedBytes = 0L;
        int removedPayloads = 0;
        if (publication.hasPayload()) {
            if (payloadCount <= 0) {
                throw new IllegalStateException("section source payload accounting underflow");
            }
            releasedBytes = publication.basePayloadEstimatedBytes();
            nextPayloadBytes = Math.subtractExact(payloadBytes, releasedBytes);
            if (nextPayloadBytes < 0L) {
                throw new IllegalStateException("section source payload byte accounting underflow");
            }
            nextPayloadCount--;
            removedPayloads = 1;
        }
        long nextMembershipRevision = Math.incrementExact(membershipRevision);
        Mutation mutation = new Mutation(
                0,
                1,
                0,
                removedPayloads,
                0L,
                releasedBytes,
                false,
                false
        );

        publications.remove(key);
        payloadLru.remove(key);
        payloadCount = nextPayloadCount;
        payloadBytes = nextPayloadBytes;
        membershipRevision = nextMembershipRevision;
        return mutation;
    }

    /**
     * Drops every publication, including compact FarField-only identities.
     *
     * <p>Membership advances from map ownership, not {@code payloadCount}. This matters after all
     * heavyweight meshes have already been released: a full resync must still invalidate the
     * non-empty compact-source membership instead of leaving a stale frozen publication.</p>
     */
    Mutation clear() {
        assertAccountingShape();
        if (publications.isEmpty()) {
            return Mutation.NONE;
        }
        int removedPublications = publications.size();
        int removedPayloads = payloadCount;
        long releasedBytes = payloadBytes;
        long nextMembershipRevision = Math.incrementExact(membershipRevision);
        Mutation mutation = new Mutation(
                0,
                removedPublications,
                0,
                removedPayloads,
                0L,
                releasedBytes,
                false,
                false
        );

        publications.clear();
        payloadLru.clear();
        payloadCount = 0;
        payloadBytes = 0L;
        membershipRevision = nextMembershipRevision;
        return mutation;
    }

    TrimResult trimToBudget(SectionKey protectedKey, Set<SectionKey> buildOwnedSectionKeys) {
        Objects.requireNonNull(buildOwnedSectionKeys, "buildOwnedSectionKeys");
        int releasedPayloads = 0;
        long releasedBytes = 0L;
        while (payloadBytes > maxPayloadBytes) {
            SectionKey candidate = null;
            for (SectionKey key : payloadLru.keySet()) {
                if (key.equals(protectedKey)
                        || buildOwnedSectionKeys.contains(key)) {
                    continue;
                }
                candidate = key;
                break;
            }
            if (candidate == null) {
                break;
            }
            Mutation mutation = releasePayload(candidate);
            releasedPayloads = Math.addExact(releasedPayloads, mutation.payloadsRemoved());
            releasedBytes = Math.addExact(releasedBytes, mutation.payloadBytesReleased());
        }
        return new TrimResult(releasedPayloads, releasedBytes, payloadBytes, payloadBytes > maxPayloadBytes);
    }

    PackedSectionMembership membership() {
        if (publishedMembershipRevision != membershipRevision) {
            membershipBuilder.reset(publications.size());
            for (Map.Entry<SectionKey, RtSectionSourcePublication> entry : publications.entrySet()) {
                if (entry.getValue().hasFarFieldPayload()) {
                    SectionKey key = entry.getKey();
                    membershipBuilder.addPacked(key.packed());
                }
            }
            membership = membershipBuilder.buildCanonical(membership);
            publishedMembershipRevision = membershipRevision;
        }
        return membership;
    }

    long membershipRevision() {
        return membershipRevision;
    }

    long geometryPublicationRevision() {
        return geometryPublicationRevision;
    }

    long materialPublicationRevision() {
        return materialPublicationRevision;
    }

    int publicationCount() {
        return publications.size();
    }

    int payloadCount() {
        return payloadCount;
    }

    long payloadBytes() {
        return payloadBytes;
    }

    long maxPayloadBytes() {
        return maxPayloadBytes;
    }

    boolean overBudget() {
        return payloadBytes > maxPayloadBytes;
    }

    private void assertAccountingShape() {
        if (payloadCount < 0 || payloadBytes < 0L) {
            throw new IllegalStateException("section source payload accounting must not be negative");
        }
        if (payloadCount > publications.size()) {
            throw new IllegalStateException("section source payload count exceeds publication count");
        }
        if (payloadLru.size() != payloadCount) {
            throw new IllegalStateException("section source payload LRU diverged from payload count");
        }
        for (SectionKey key : payloadLru.keySet()) {
            RtSectionSourcePublication publication = publications.get(key);
            if (publication == null || !publication.hasPayload()) {
                throw new IllegalStateException("section source payload LRU contains no live payload for " + key);
            }
        }
        if (publications.isEmpty() && (payloadCount != 0 || payloadBytes != 0L)) {
            throw new IllegalStateException("empty section source store retains payload accounting");
        }
    }

    private void touchPayload(SectionKey key) {
        payloadLru.put(Objects.requireNonNull(key, "key"), Boolean.TRUE);
    }

    record Mutation(
            int publicationsAdded,
            int publicationsRemoved,
            int payloadsAdded,
            int payloadsRemoved,
            long payloadBytesAdded,
            long payloadBytesReleased,
            boolean geometryChanged,
            boolean materialChanged
    ) {
        private static final Mutation NONE = new Mutation(0, 0, 0, 0, 0L, 0L, false, false);

        Mutation {
            if (publicationsAdded < 0 || publicationsRemoved < 0
                    || payloadsAdded < 0 || payloadsRemoved < 0
                    || payloadBytesAdded < 0L || payloadBytesReleased < 0L) {
                throw new IllegalArgumentException("section source mutation counters must not be negative");
            }
        }

        boolean publicationAdded() {
            return publicationsAdded != 0;
        }

        boolean publicationRemoved() {
            return publicationsRemoved != 0;
        }
    }

    record TrimResult(
            int releasedPayloads,
            long releasedBytes,
            long retainedPayloadBytes,
            boolean overBudget
    ) {
        TrimResult {
            if (releasedPayloads < 0 || releasedBytes < 0L || retainedPayloadBytes < 0L) {
                throw new IllegalArgumentException("section source trim counters must not be negative");
            }
        }
    }
}
