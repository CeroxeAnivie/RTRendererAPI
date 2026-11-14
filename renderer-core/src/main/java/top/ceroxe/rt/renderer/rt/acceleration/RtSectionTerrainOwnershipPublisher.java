package top.ceroxe.rt.renderer.rt.acceleration;

import top.ceroxe.rt.renderer.scene.PackedSectionMembership;

import java.util.Objects;

/**
 * Publishes the immutable native terrain ownership read model from its authoritative owners.
 *
 * <p>This component owns only revision-vector caching and snapshot composition. Source, queued,
 * recording, GPU, active, bound, and foreground memberships remain owned by their respective
 * lifecycle components. Callers serialize publication with the section cache monitor; this class
 * never mutates a producer or triggers admission.</p>
 */
final class RtSectionTerrainOwnershipPublisher {
    private final RtSectionSourceStore sourceStore;
    private final RtPendingBlasBuildQueue<?> pendingBuilds;
    private final RtSectionAsyncBuildInventory asyncBuilds;
    private final RtSectionLifecycleMembershipState lifecycleMemberships;
    private final RtSectionForegroundState foreground;
    private final RtNativeTerrainOwnershipCache publication = new RtNativeTerrainOwnershipCache();

    RtSectionTerrainOwnershipPublisher(
            RtSectionSourceStore sourceStore,
            RtPendingBlasBuildQueue<?> pendingBuilds,
            RtSectionAsyncBuildInventory asyncBuilds,
            RtSectionLifecycleMembershipState lifecycleMemberships,
            RtSectionForegroundState foreground
    ) {
        this.sourceStore = Objects.requireNonNull(sourceStore, "sourceStore");
        this.pendingBuilds = Objects.requireNonNull(pendingBuilds, "pendingBuilds");
        this.asyncBuilds = Objects.requireNonNull(asyncBuilds, "asyncBuilds");
        this.lifecycleMemberships = Objects.requireNonNull(lifecycleMemberships, "lifecycleMemberships");
        this.foreground = Objects.requireNonNull(foreground, "foreground");
    }

    private static void validateBoundRevision(long boundWorldRevision) {
        if (boundWorldRevision < -1L) {
            throw new IllegalArgumentException("bound world revision must be -1 or non-negative");
        }
    }

    NativeTerrainOwnership snapshot(
            PackedSectionMembership boundSectionKeys,
            long boundWorldRevision,
            boolean foregroundCoverageComplete
    ) {
        Objects.requireNonNull(boundSectionKeys, "boundSectionKeys");
        validateBoundRevision(boundWorldRevision);
        PackedSectionMembership authority = foreground.authority();
        RevisionVector vector = revisionVector(boundWorldRevision);
        long generation = publication.observeGeneration(
                vector.source(),
                vector.queued(),
                vector.recording(),
                vector.gpu(),
                vector.active(),
                vector.bound(),
                vector.authority()
        );
        if (publication.isCurrent(
                vector.source(),
                vector.queued(),
                vector.recording(),
                vector.gpu(),
                vector.active(),
                vector.bound(),
                vector.authority()
        )) {
            return publication.snapshot();
        }

        NativeTerrainOwnership snapshot = NativeTerrainOwnership.fromFrozenSets(
                generation,
                sourceStore.membership(),
                pendingBuilds.snapshotMembership(),
                asyncBuilds.recordingMembership(),
                asyncBuilds.gpuMembership(),
                lifecycleMemberships.active(),
                lifecycleMemberships.bound(boundSectionKeys, boundWorldRevision),
                authority,
                !authority.isEmpty() && foregroundCoverageComplete
        );
        publication.publish(
                snapshot,
                vector.source(),
                vector.queued(),
                vector.recording(),
                vector.gpu(),
                vector.active(),
                vector.bound(),
                vector.authority()
        );
        return snapshot;
    }

    long generation(long boundWorldRevision) {
        validateBoundRevision(boundWorldRevision);
        RevisionVector vector = revisionVector(boundWorldRevision);
        return publication.observeGeneration(
                vector.source(),
                vector.queued(),
                vector.recording(),
                vector.gpu(),
                vector.active(),
                vector.bound(),
                vector.authority()
        );
    }

    private RevisionVector revisionVector(long boundWorldRevision) {
        return new RevisionVector(
                sourceStore.membershipRevision(),
                pendingBuilds.membershipRevision(),
                asyncBuilds.recordingRevision(),
                asyncBuilds.gpuRevision(),
                lifecycleMemberships.activeRevision(),
                boundWorldRevision,
                foreground.authorityRevision()
        );
    }

    private record RevisionVector(
            long source,
            long queued,
            long recording,
            long gpu,
            long active,
            long bound,
            long authority
    ) {
    }
}
