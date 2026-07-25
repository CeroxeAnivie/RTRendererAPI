package top.ceroxe.rt.renderer.rt.acceleration;

import top.ceroxe.rt.renderer.RendererFrameCausality;

import java.util.Objects;

/**
 * Immutable diagnostic projection of one dynamic asset generation.
 *
 * @param assetId       non-negative asset identifier
 * @param assetRevision non-negative asset revision
 * @param phase         current residency phase
 * @param sceneRevision non-negative source scene revision
 * @param causality     immutable frame-causality identity
 */
public record RtDynamicAssetDebugState(
        long assetId,
        long assetRevision,
        Phase phase,
        long sceneRevision,
        RendererFrameCausality causality
) {
    /**
     * Validates that the projection names one concrete, non-negative asset generation.
     */
    public RtDynamicAssetDebugState {
        if (assetId < 0L || assetRevision < 0L || sceneRevision < 0L) {
            throw new IllegalArgumentException("dynamic asset debug revisions must not be negative");
        }
        phase = Objects.requireNonNull(phase, "phase");
        causality = Objects.requireNonNull(causality, "causality");
    }

    /**
     * Cache residency phase observed for an asset generation.
     */
    public enum Phase {
        /**
         * The generation is awaiting build admission.
         */
        QUEUED,
        /**
         * GPU BLAS construction has been submitted but is not yet publishable.
         */
        PENDING_BLAS,
        /**
         * A completed BLAS is resident and eligible for instance publication.
         */
        ACTIVE_BLAS
    }
}
