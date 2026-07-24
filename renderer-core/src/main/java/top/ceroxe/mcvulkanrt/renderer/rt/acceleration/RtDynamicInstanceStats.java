package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import top.ceroxe.mcvulkanrt.renderer.RendererFrameCausality;

/** Compact scheduler statistics for the persistent dynamic-instance lane. */
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
