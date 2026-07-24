package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;

import java.util.Objects;

/**
 * Failure-only proof of one section's state across the terrain publication owners.
 *
 * <p>The renderer intentionally does not maintain a diagnostic union in normal operation. Such a
 * union would duplicate lifecycle state and recreate the allocation pressure this architecture is
 * designed to remove. The cache captures this immutable scalar proof only after the real
 * publication invariant has failed, while it still holds the cache monitor and all owner states
 * therefore belong to the same observation point.</p>
 */
record RtSectionOwnershipProof(
        Classification classification,
        SectionKey key,
        Presence presence,
        Cardinality cardinality,
        Revisions revisions,
        Source source,
        Work work
) {
    RtSectionOwnershipProof {
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(presence, "presence");
        Objects.requireNonNull(cardinality, "cardinality");
        Objects.requireNonNull(revisions, "revisions");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(work, "work");
    }

    static Classification classify(Presence presence) {
        Objects.requireNonNull(presence, "presence");
        if (!presence.covered()) {
            return Classification.PUBLICATION_INPUT_CHANGED;
        }
        if (!presence.baseEntry() && !presence.farFieldCoverage()) {
            return Classification.COVERAGE_WITHOUT_INSTANCE_OWNER;
        }
        if (presence.farFieldCoverage() && !presence.baseEntry() && !presence.activeContent()) {
            return Classification.FAR_FIELD_COVERAGE_WITHOUT_CONTENT_PUBLICATION;
        }
        if (presence.baseEntry() && !presence.residentResource()) {
            return Classification.STALE_BASE_ENTRY_WITHOUT_RESIDENT;
        }
        if (presence.residentResource() != presence.residentMembership()) {
            return Classification.RESIDENT_MEMBERSHIP_DIVERGENCE;
        }
        if (presence.baseEntry() && !presence.activeMembership()) {
            return Classification.ACTIVE_VIEW_WITHOUT_ACTIVE_MEMBERSHIP;
        }
        if (presence.activeMembership() && !presence.activeContent()) {
            return Classification.ACTIVE_MEMBERSHIP_WITHOUT_CONTENT;
        }
        if (presence.baseEntry() && !presence.materialSlot()) {
            return Classification.BASE_WITHOUT_MATERIAL_SLOT;
        }
        return Classification.CONTENT_PUBLICATION_DIVERGENCE;
    }

    enum Classification {
        PUBLICATION_INPUT_CHANGED,
        COVERAGE_WITHOUT_INSTANCE_OWNER,
        FAR_FIELD_COVERAGE_WITHOUT_CONTENT_PUBLICATION,
        STALE_BASE_ENTRY_WITHOUT_RESIDENT,
        RESIDENT_MEMBERSHIP_DIVERGENCE,
        ACTIVE_VIEW_WITHOUT_ACTIVE_MEMBERSHIP,
        ACTIVE_MEMBERSHIP_WITHOUT_CONTENT,
        BASE_WITHOUT_MATERIAL_SLOT,
        CONTENT_PUBLICATION_DIVERGENCE
    }

    record Presence(
            boolean foregroundView,
            boolean covered,
            boolean baseEntry,
            boolean farFieldCoverage,
            boolean residentResource,
            boolean residentMembership,
            boolean activeMembership,
            boolean activeContent,
            boolean sourcePublication,
            boolean sourcePayload,
            boolean farFieldPayload,
            boolean materialSlot
    ) {
    }

    record Cardinality(
            int foregroundView,
            int covered,
            int baseEntries,
            int farFieldCells,
            int residentResources,
            int residentMembership,
            int activeMembership,
            int activeContent,
            int sourcePublications,
            int materialSlots
    ) {
    }

    record Revisions(
            long view,
            long scene,
            long geometry,
            long material,
            long residentMembership,
            long activeMembership,
            long activeContentGeneration,
            long sourceMembership,
            long sourceGeometryPublication,
            long sourceMaterialPublication
    ) {
    }

    record Source(long geometryGeneration, long materialGeneration) {
    }

    record Work(
            boolean intentPresent,
            long desiredRevision,
            long activeRevision,
            int sourceFlags,
            boolean queued,
            boolean recording,
            boolean gpu,
            long asyncSequence
    ) {
    }
}
