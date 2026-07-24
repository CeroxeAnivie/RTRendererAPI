package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import java.util.Objects;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameCausality;
import top.ceroxe.mcvulkanrt.renderer.rt.material.RtSceneMaterialTable;

/** Immutable generation metadata captured when one section enters the BLAS queue. */
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
