package top.ceroxe.rt.renderer.rt.acceleration;

/**
 * Owns monotonic exact-geometry and active-scene publication revisions.
 */
final class RtSectionSceneRevisionState {
    private long geometry;
    private long scene;
    private long sceneGeometry;

    long geometry() {
        return geometry;
    }

    long scene() {
        return scene;
    }

    long nextGeometry() {
        return Math.incrementExact(geometry);
    }

    long advanceGeometry() {
        geometry = nextGeometry();
        return geometry;
    }

    void commitGeometry(long expectedNext) {
        long next = nextGeometry();
        if (expectedNext != next) {
            throw new IllegalStateException(
                    "geometry revision changed during publication: expected=" + expectedNext + ", actual=" + next
            );
        }
        geometry = next;
    }

    long advanceScene() {
        scene = Math.incrementExact(scene);
        return scene;
    }

    /**
     * Advances the cheap scheduler-visible topology token once for the current geometry
     * generation. Expensive active-view materialization may happen later without publishing the
     * same ownership mutation twice.
     */
    long publishGeometryToScene() {
        if (sceneGeometry == geometry) {
            return scene;
        }
        if (sceneGeometry > geometry) {
            throw new IllegalStateException("scene geometry revision cannot exceed live geometry");
        }
        sceneGeometry = geometry;
        return advanceScene();
    }
}
