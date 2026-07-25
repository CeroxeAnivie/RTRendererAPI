package top.ceroxe.rt.renderer.rt.acceleration;

import top.ceroxe.rt.renderer.scene.PackedSectionMembership;
import top.ceroxe.rt.renderer.scene.SectionKey;

import java.util.Objects;

/**
 * Incremental proof of the overlap between two immutable section-membership publications.
 *
 * <p>The proof owns the previous publications and matched count as one state machine.  Callers
 * cannot accidentally reuse a count with only one side updated, while stable publications remain
 * an identity comparison instead of a full set intersection on every frame.</p>
 */
class RtSectionCoverageProof {
    private PackedSectionMembership authoritative = PackedSectionMembership.empty();
    private long authoritativeRevision = Long.MIN_VALUE;
    private PackedSectionMembership stage = PackedSectionMembership.empty();
    private long stageRevision = Long.MIN_VALUE;
    private int matchedCount;
    private long recomputations;

    private static int matchingKeyCount(
            PackedSectionMembership required,
            PackedSectionMembership available
    ) {
        PackedSectionMembership iterated = required.size() <= available.size() ? required : available;
        PackedSectionMembership probed = iterated == required ? available : required;
        int matches = 0;
        for (SectionKey key : iterated) {
            if (probed.containsPacked(key.packed())) {
                matches++;
            }
        }
        return matches;
    }

    int matchedCount(
            PackedSectionMembership nextAuthoritative,
            long nextAuthoritativeRevision,
            PackedSectionMembership nextStage,
            long nextStageRevision
    ) {
        Objects.requireNonNull(nextAuthoritative, "nextAuthoritative");
        Objects.requireNonNull(nextStage, "nextStage");
        if (authoritative == nextAuthoritative && stage == nextStage) {
            // Revisions still advance for diagnostics, but immutable identity proves that the
            // actual overlap cannot have changed.
            if (nextAuthoritativeRevision < authoritativeRevision
                    || nextStageRevision < stageRevision) {
                throw new IllegalStateException("section coverage publication revision regressed");
            }
            authoritativeRevision = nextAuthoritativeRevision;
            stageRevision = nextStageRevision;
            return matchedCount;
        }
        int transitionedCount = nextAuthoritative.transitionIntersectionCount(
                nextStage,
                authoritative,
                stage,
                matchedCount
        );
        matchedCount = transitionedCount >= 0
                ? transitionedCount
                : matchingKeyCount(nextAuthoritative, nextStage);
        authoritative = nextAuthoritative;
        authoritativeRevision = nextAuthoritativeRevision;
        stage = nextStage;
        stageRevision = nextStageRevision;
        recomputations++;
        return matchedCount;
    }

    void clear() {
        authoritative = PackedSectionMembership.empty();
        authoritativeRevision = Long.MIN_VALUE;
        stage = PackedSectionMembership.empty();
        stageRevision = Long.MIN_VALUE;
        matchedCount = 0;
    }

    long recomputations() {
        return recomputations;
    }
}
