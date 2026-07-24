package top.ceroxe.mcvulkanrt.renderer.rt.device;

import top.ceroxe.mcvulkanrt.renderer.RendererFrameCausality;
import top.ceroxe.mcvulkanrt.renderer.rt.material.RtSceneMaterialTable;

import java.util.Objects;

/** Owns a material upload and the immutable descriptor resource base it was derived from. */
record PendingMaterialOnlyBind(
        RtScenePublication basePublication,
        RtSceneMaterialTable.Snapshot materialSnapshot,
        RtSceneMaterialTable.PendingUpload materialUpload,
        long sectionMaterialRevision,
        long dynamicMaterialRevision,
        RendererFrameCausality causality
) implements AutoCloseable {
    PendingMaterialOnlyBind {
        basePublication = Objects.requireNonNull(basePublication, "basePublication");
        materialSnapshot = Objects.requireNonNull(materialSnapshot, "materialSnapshot");
        materialUpload = Objects.requireNonNull(materialUpload, "materialUpload");
        causality = Objects.requireNonNull(causality, "causality");
        if (sectionMaterialRevision < 0L || dynamicMaterialRevision < 0L) {
            throw new IllegalArgumentException("material revisions must not be negative");
        }
    }

    @Override
    public void close() {
        materialUpload.close();
    }
}
