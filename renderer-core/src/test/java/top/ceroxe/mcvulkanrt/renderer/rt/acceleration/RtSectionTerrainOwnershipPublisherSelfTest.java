package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import top.ceroxe.mcvulkanrt.renderer.scene.FaceDirection;
import top.ceroxe.mcvulkanrt.renderer.scene.PackedSectionMembership;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionTriangleMesh;

/** Verifies revision-vector native terrain ownership publication without producer mutation. */
public final class RtSectionTerrainOwnershipPublisherSelfTest {
    private RtSectionTerrainOwnershipPublisherSelfTest() {
    }

    public static void main(String[] arguments) {
        stableRevisionVectorReusesSnapshotIdentity();
        invalidBoundRevisionFailsBeforePublication();
        System.out.println("RtSectionTerrainOwnershipPublisherSelfTest passed");
    }

    private static void stableRevisionVectorReusesSnapshotIdentity() {
        RtSectionSourceStore sources = new RtSectionSourceStore(1_000_000L);
        RtPendingBlasBuildQueue<Void> queued = new RtPendingBlasBuildQueue<>(8, 1_000_000L);
        RtSectionAsyncBuildInventory async = new RtSectionAsyncBuildInventory();
        RtSectionLifecycleMembershipState lifecycle = new RtSectionLifecycleMembershipState();
        RtSectionForegroundState foreground = new RtSectionForegroundState();
        RtSectionTerrainOwnershipPublisher publisher = new RtSectionTerrainOwnershipPublisher(
                sources,
                queued,
                async,
                lifecycle,
                foreground
        );
        try {
            NativeTerrainOwnership first = publisher.snapshot(PackedSectionMembership.empty(), -1L, true);
            NativeTerrainOwnership reused = publisher.snapshot(PackedSectionMembership.empty(), -1L, true);
            require(first == reused, "stable revision vector rebuilt the immutable ownership snapshot");
            require(first.ownershipGeneration() == 0L && publisher.generation(-1L) == 0L,
                    "initial ownership generation drifted");

            SectionTriangleMesh mesh = mesh(4);
            queued.enqueue(mesh);
            require(publisher.generation(-1L) == 1L,
                    "queued membership revision did not advance ownership generation");
            NativeTerrainOwnership queuedSnapshot = publisher.snapshot(
                    PackedSectionMembership.empty(),
                    -1L,
                    true
            );
            require(queuedSnapshot.queuedSectionKeys().contains(mesh.key()),
                    "queued ownership was omitted from the native snapshot");
            require(queuedSnapshot.ownershipGeneration() == 1L,
                    "snapshot did not reuse the already-observed scalar generation");

            lifecycle.addActive(mesh.key());
            PackedSectionMembership bound = PackedSectionMembership.copyOf(java.util.Set.of(mesh.key()));
            NativeTerrainOwnership activeAndBound = publisher.snapshot(bound, 7L, true);
            require(activeAndBound.activeSectionKeys().contains(mesh.key()),
                    "active lifecycle publication was not projected");
            require(activeAndBound.boundSectionKeys().contains(mesh.key()),
                    "bound lifecycle publication was not projected");
            require(activeAndBound.ownershipGeneration() == 2L,
                    "one composite revision-vector change advanced generation more than once");
        } finally {
            RuntimeException failure = async.closeCollecting(null);
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static void invalidBoundRevisionFailsBeforePublication() {
        RtSectionAsyncBuildInventory async = new RtSectionAsyncBuildInventory();
        try {
            RtSectionTerrainOwnershipPublisher publisher = new RtSectionTerrainOwnershipPublisher(
                    new RtSectionSourceStore(1_000_000L),
                    new RtPendingBlasBuildQueue<>(),
                    async,
                    new RtSectionLifecycleMembershipState(),
                    new RtSectionForegroundState()
            );
            expectIllegalArgument(() -> publisher.generation(-2L));
            expectIllegalArgument(() -> publisher.snapshot(PackedSectionMembership.empty(), -2L, false));
        } finally {
            RuntimeException failure = async.closeCollecting(null);
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static SectionTriangleMesh mesh(int sectionX) {
        return new SectionTriangleMesh(
                new SectionKey(sectionX, 0, 0),
                new short[]{0, 0, 0, 16, 0, 0, 16, 16, 0, 0, 16, 0},
                new int[]{0, 1, 2, 0, 2, 3},
                new int[]{42},
                new byte[]{0},
                new byte[]{(byte) FaceDirection.POSITIVE_Z.ordinal()}
        );
    }

    private static void expectIllegalArgument(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("expected invalid bound revision to fail");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
