package top.ceroxe.rt.renderer.rt.acceleration;

import top.ceroxe.rt.renderer.RendererFrameCausality;
import top.ceroxe.rt.renderer.scene.SectionKey;

import java.util.Objects;

/**
 * Immutable section-cache projection captured without exposing native BLAS ownership.
 *
 * @param sectionKey             stable section identity
 * @param desiredContentRevision desired content revision, or {@code -1}
 * @param activeContentRevision  active content revision, or {@code -1}
 * @param geometryGeneration     geometry generation, or {@code -1}
 * @param materialGeneration     material generation, or {@code -1}
 * @param buildSequence          build sequence, or {@code -1}
 * @param queued                 whether a build is queued
 * @param recording              whether native build recording is active
 * @param gpuInFlight            whether a build is executing on the GPU
 * @param active                 whether a resident BLAS is active
 * @param causality              immutable frame-causality identity
 */
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
    /**
     * Validates optional generations and prevents absent sections from claiming native ownership.
     */
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

    /**
     * Creates the canonical no-generation projection.
     *
     * @param key section identity
     * @return absent projection
     */
    public static RtSectionDebugState absent(SectionKey key) {
        return new RtSectionDebugState(
                key, -1L, -1L, -1L, -1L, -1L,
                false, false, false, false, RendererFrameCausality.untraced(0L)
        );
    }

    /**
     * Reports whether any desired or active generation is known.
     *
     * @return whether the section was observed
     */
    public boolean observed() {
        return desiredContentRevision >= 0L || activeContentRevision >= 0L;
    }
}
