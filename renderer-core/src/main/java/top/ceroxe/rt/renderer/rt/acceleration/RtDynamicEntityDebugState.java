package top.ceroxe.rt.renderer.rt.acceleration;

import top.ceroxe.rt.renderer.RendererFrameCausality;

import java.util.Objects;

/**
 * Immutable cache-owned projection for one dynamic object's current model primitives.
 *
 * @param entityId            unsigned 32-bit object identifier
 * @param sceneRevision       current scene revision, or {@code -1} when absent
 * @param primitiveCount      number of source primitives
 * @param assetCount          number of derived assets
 * @param queuedAssetCount    assets waiting for build admission
 * @param pendingAssetCount   assets with admitted builds
 * @param residentAssetCount  assets with active BLAS residency
 * @param newestAssetRevision newest asset revision, or {@code -1} when absent
 * @param causality           immutable frame-causality identity
 */
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
    /**
     * Validates count relationships so diagnostics cannot report an impossible lifecycle state.
     */
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

    /**
     * Creates the canonical projection for an entity not observed in the current dynamic scene.
     *
     * @param entityId unsigned 32-bit object identifier
     * @return a validated absent-state projection
     * @throws IllegalArgumentException if {@code entityId} does not fit an unsigned 32-bit value
     */
    public static RtDynamicEntityDebugState absent(long entityId) {
        return new RtDynamicEntityDebugState(
                entityId, -1L, 0, 0, 0, 0, 0, -1L,
                RendererFrameCausality.untraced(0L)
        );
    }

    /**
     * Reports whether this projection names a current scene entity.
     *
     * @return whether the entity is currently observed
     */
    public boolean observed() {
        return primitiveCount > 0;
    }
}
