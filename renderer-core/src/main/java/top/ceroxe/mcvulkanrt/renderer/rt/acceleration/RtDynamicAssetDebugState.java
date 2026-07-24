package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import top.ceroxe.mcvulkanrt.renderer.RendererFrameCausality;

import java.util.Objects;

/** Immutable diagnostic projection of one dynamic asset generation. */
public record RtDynamicAssetDebugState(
        long assetId,
        long assetRevision,
        Phase phase,
        long sceneRevision,
        RendererFrameCausality causality
) {
    public RtDynamicAssetDebugState {
        if (assetId < 0L || assetRevision < 0L || sceneRevision < 0L) {
            throw new IllegalArgumentException("dynamic asset debug revisions must not be negative");
        }
        phase = Objects.requireNonNull(phase, "phase");
        causality = Objects.requireNonNull(causality, "causality");
    }

    public enum Phase {
        QUEUED,
        PENDING_BLAS,
        ACTIVE_BLAS
    }
}
