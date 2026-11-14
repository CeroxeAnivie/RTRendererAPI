package top.ceroxe.rt.renderer.rt.acceleration;

import top.ceroxe.rt.renderer.RendererFrameCausality;
import top.ceroxe.rt.renderer.rt.material.RtSceneMaterialTable;

import java.util.Objects;

/**
 * Immutable generation metadata captured when one section enters the BLAS queue.
 */
record RtSectionBlasBuildMetadata(
        long contentRevision,
        RendererFrameCausality causality,
        int sourceFlags,
        RtSceneMaterialTable.SectionMaterial material
) {
    RtSectionBlasBuildMetadata {
        if (contentRevision < 0L) {
            throw new IllegalArgumentException("queued BLAS content revision must not be negative");
        }
        causality = Objects.requireNonNull(causality, "causality");
        material = Objects.requireNonNull(material, "material");
    }
}
