package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import top.ceroxe.mcvulkanrt.renderer.RendererFrameCausality;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;

import java.util.Objects;

/** Immutable section-cache projection captured without exposing native BLAS ownership. */
public record RtSectionDebugState(
        SectionKey sectionKey,
        long desiredContentRevision,
        long activeContentRevision,
        long geometryGeneration,
        long materialGeneration,
        long buildSequence,
        boolean queued,
        boolean recording,
        boolean gpuInFlight,
        boolean active,
        RendererFrameCausality causality
) {
    public RtSectionDebugState {
        sectionKey = Objects.requireNonNull(sectionKey, "sectionKey");
        if (desiredContentRevision < -1L || activeContentRevision < -1L
                || geometryGeneration < -1L || materialGeneration < -1L || buildSequence < -1L) {
            throw new IllegalArgumentException("section debug generations must be -1 or greater");
        }
        causality = Objects.requireNonNull(causality, "causality");
        if (!observed() && (queued || recording || gpuInFlight || active)) {
            throw new IllegalArgumentException("unobserved section must not claim native ownership");
        }
    }

    public boolean observed() {
        return desiredContentRevision >= 0L || activeContentRevision >= 0L;
    }

    public static RtSectionDebugState absent(SectionKey key) {
        return new RtSectionDebugState(
                key, -1L, -1L, -1L, -1L, -1L,
                false, false, false, false, RendererFrameCausality.untraced(0L)
        );
    }
}
