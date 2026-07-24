package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

/** Verifies exact, single-owner section geometry and scene publication revisions. */
public final class RtSectionSceneRevisionStateSelfTest {
    private RtSectionSceneRevisionStateSelfTest() {
    }

    public static void main(String[] arguments) {
        reservationDoesNotPublishBeforeCommit();
        staleGeometryCommitFailsFast();
        directAdvancesRemainIndependentAndMonotonic();
        geometryPublishesOneCheapSceneTokenPerGeneration();
        System.out.println("RtSectionSceneRevisionStateSelfTest passed");
    }

    private static void reservationDoesNotPublishBeforeCommit() {
        RtSectionSceneRevisionState revisions = new RtSectionSceneRevisionState();
        require(revisions.geometry() == 0L, "geometry revision must start at zero");
        require(revisions.scene() == 0L, "scene revision must start at zero");

        long reserved = revisions.nextGeometry();
        require(reserved == 1L, "first reserved geometry revision must be one");
        require(revisions.geometry() == 0L, "reservation must not publish partial geometry state");

        revisions.commitGeometry(reserved);
        require(revisions.geometry() == reserved, "validated geometry commit was not published");
        require(revisions.scene() == 0L, "geometry commit must not mutate scene identity");
    }

    private static void staleGeometryCommitFailsFast() {
        RtSectionSceneRevisionState revisions = new RtSectionSceneRevisionState();
        long reserved = revisions.nextGeometry();
        require(revisions.advanceGeometry() == 1L, "direct geometry advance drifted");
        expectIllegalState(() -> revisions.commitGeometry(reserved));
        require(revisions.geometry() == 1L, "rejected stale commit changed the published revision");
    }

    private static void directAdvancesRemainIndependentAndMonotonic() {
        RtSectionSceneRevisionState revisions = new RtSectionSceneRevisionState();
        require(revisions.advanceGeometry() == 1L, "geometry advance must return its publication");
        require(revisions.advanceGeometry() == 2L, "geometry advance must remain monotonic");
        require(revisions.advanceScene() == 1L, "scene advance must return its publication");
        require(revisions.advanceScene() == 2L, "scene advance must remain monotonic");
        require(revisions.geometry() == 2L, "scene advance changed geometry ownership");
        require(revisions.scene() == 2L, "scene revision did not retain its latest publication");
    }

    private static void geometryPublishesOneCheapSceneTokenPerGeneration() {
        RtSectionSceneRevisionState revisions = new RtSectionSceneRevisionState();
        require(revisions.publishGeometryToScene() == 0L, "empty geometry must not publish topology work");
        revisions.advanceGeometry();
        require(revisions.publishGeometryToScene() == 1L, "first geometry generation was not published");
        require(revisions.publishGeometryToScene() == 1L, "same geometry generation published twice");
        revisions.advanceScene();
        revisions.advanceGeometry();
        require(revisions.publishGeometryToScene() == 3L,
                "independent scene changes must not suppress the next geometry generation");
    }

    private static void expectIllegalState(Runnable action) {
        try {
            action.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("expected stale geometry publication to fail");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
