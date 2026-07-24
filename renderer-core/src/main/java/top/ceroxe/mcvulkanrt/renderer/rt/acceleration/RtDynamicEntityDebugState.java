package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import top.ceroxe.mcvulkanrt.renderer.RendererFrameCausality;

import java.util.Objects;

/** Immutable cache-owned projection for one sourceEngine entity's current model primitives. */
public record RtDynamicEntityDebugState(
        long entityId,
        long sceneRevision,
        int primitiveCount,
        int assetCount,
        int queuedAssetCount,
        int pendingAssetCount,
        int residentAssetCount,
        long newestAssetRevision,
        RendererFrameCausality causality
) {
    public RtDynamicEntityDebugState {
        if (entityId < 0L || entityId > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException("entity id must be an unsigned 32-bit value");
        }
        if (sceneRevision < -1L || newestAssetRevision < -1L) {
            throw new IllegalArgumentException("entity debug revisions must be -1 or greater");
        }
        if (primitiveCount < 0 || assetCount < 0 || queuedAssetCount < 0
                || pendingAssetCount < 0 || residentAssetCount < 0
                || queuedAssetCount > assetCount || pendingAssetCount > assetCount
                || residentAssetCount > assetCount) {
            throw new IllegalArgumentException("entity debug counts are inconsistent");
        }
        causality = Objects.requireNonNull(causality, "causality");
        if (primitiveCount == 0 && (assetCount != 0 || sceneRevision >= 0L || newestAssetRevision >= 0L)) {
            throw new IllegalArgumentException("absent entity debug state must not claim an active generation");
        }
    }

    public static RtDynamicEntityDebugState absent(long entityId) {
        return new RtDynamicEntityDebugState(
                entityId, -1L, 0, 0, 0, 0, 0, -1L,
                RendererFrameCausality.untraced(0L)
        );
    }

    public boolean observed() {
        return primitiveCount > 0;
    }
}
