package top.ceroxe.rt.renderer.rt.acceleration;

import java.util.Objects;

/**
 * Read-only section and dynamic-work evidence used by world-TLAS scheduling.
 */
record RtWorldTlasBuildStats(
        long revision,
        long sectionRevision,
        long sectionResourceRevision,
        long sectionMaterialRevision,
        long dynamicRevision,
        long dynamicTopologyRevision,
        long dynamicGeometryRevision,
        long dynamicMaterialRevision,
        long dynamicSceneRevision,
        int instances,
        int sectionInstances,
        int sectionActiveViewSections,
        int sectionExactInstances,
        int sectionFarFieldInstances,
        boolean sectionViewAuthoritative,
        boolean sectionForegroundAuthoritative,
        int dynamicInstances,
        int pendingSectionBuilds,
        long pendingTriangles,
        long cachedTriangles,
        long dynamicPrimitives,
        long dynamicFaces,
        long dynamicTriangles
) {
    RtWorldTlasBuildStats {
        if (revision < 0L || sectionRevision < 0L || sectionResourceRevision < 0L
                || sectionMaterialRevision < 0L || dynamicRevision < 0L
                || dynamicTopologyRevision < 0L || dynamicGeometryRevision < 0L
                || dynamicMaterialRevision < 0L || dynamicSceneRevision < 0L) {
            throw new IllegalArgumentException("world TLAS stats revisions must not be negative");
        }
        if (instances < 0 || sectionInstances < 0 || sectionActiveViewSections < 0
                || sectionExactInstances < 0 || sectionFarFieldInstances < 0
                || dynamicInstances < 0 || pendingSectionBuilds < 0) {
            throw new IllegalArgumentException("world TLAS stats counts must not be negative");
        }
        if (sectionInstances != Math.addExact(sectionExactInstances, sectionFarFieldInstances)) {
            throw new IllegalArgumentException("section instance breakdown must equal total section instances");
        }
        if (pendingTriangles < 0L || cachedTriangles < 0L
                || dynamicPrimitives < 0L || dynamicFaces < 0L || dynamicTriangles < 0L) {
            throw new IllegalArgumentException("world TLAS stats triangle counts must not be negative");
        }
    }

    static RtWorldTlasBuildStats from(
            RtSectionTlasBuildStats sectionStats,
            RtDynamicInstanceStats dynamicStats
    ) {
        Objects.requireNonNull(sectionStats, "sectionStats");
        Objects.requireNonNull(dynamicStats, "dynamicStats");
        /* Dynamic transforms are scheduled by RtDynamicTlasCache, not terrain. */
        return new RtWorldTlasBuildStats(
                sectionStats.revision(),
                sectionStats.revision(),
                sectionStats.resourceRevision(),
                sectionStats.materialRevision(),
                dynamicStats.revision(),
                dynamicStats.topologyRevision(),
                dynamicStats.geometryRevision(),
                dynamicStats.materialRevision(),
                dynamicStats.latestSceneRevision(),
                sectionStats.instances(),
                sectionStats.instances(),
                sectionStats.activeViewSections(),
                sectionStats.activeExactInstances(),
                sectionStats.farFieldInstances(),
                sectionStats.authoritativeView(),
                sectionStats.authoritativeForeground(),
                dynamicStats.visibleInstances(),
                sectionStats.pendingSectionBuilds(),
                sectionStats.pendingTriangles(),
                sectionStats.cachedTriangles(),
                dynamicStats.primitiveCount(),
                dynamicStats.faceCount(),
                dynamicStats.triangleCount()
        );
    }

    boolean hasPendingSectionBuilds() {
        return pendingSectionBuilds > 0;
    }
}
