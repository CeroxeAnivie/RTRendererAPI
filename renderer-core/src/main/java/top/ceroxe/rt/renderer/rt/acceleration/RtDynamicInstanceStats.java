package top.ceroxe.rt.renderer.rt.acceleration;

import top.ceroxe.rt.renderer.RendererFrameCausality;

/**
 * Compact scheduler statistics for the persistent dynamic-instance lane.
 *
 * @param revision            immutable table revision
 * @param topologyRevision    physical-slot topology revision
 * @param transformRevision   instance-transform revision
 * @param geometryRevision    resident geometry revision
 * @param materialRevision    material-table revision
 * @param latestSceneRevision latest incorporated scene revision
 * @param causality           immutable frame-causality identity
 * @param visibleInstances    number of visible instances
 * @param primitiveCount      incorporated primitive count
 * @param faceCount           incorporated face count
 * @param triangleCount       incorporated triangle count
 */
public record RtDynamicInstanceStats(
        long revision,
        long topologyRevision,
        long transformRevision,
        long geometryRevision,
        long materialRevision,
        long latestSceneRevision,
        RendererFrameCausality causality,
        int visibleInstances,
        long primitiveCount,
        long faceCount,
        long triangleCount
) {
    /**
     * Validates monotonically non-negative revisions and scheduler counts.
     */
    public RtDynamicInstanceStats {
        if (revision < 0L || topologyRevision < 0L || transformRevision < 0L
                || geometryRevision < 0L || materialRevision < 0L
                || latestSceneRevision < 0L) {
            throw new IllegalArgumentException("dynamic instance revisions must not be negative");
        }
        causality = java.util.Objects.requireNonNull(causality, "causality");
        if (visibleInstances < 0) {
            throw new IllegalArgumentException("visible dynamic instances must not be negative");
        }
        if (primitiveCount < 0L || faceCount < 0L || triangleCount < 0L) {
            throw new IllegalArgumentException("dynamic instance counts must not be negative");
        }
    }
}
