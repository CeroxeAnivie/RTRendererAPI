package top.ceroxe.mcvulkanrt.renderer.rt.device;

import top.ceroxe.mcvulkanrt.renderer.rt.acceleration.RtDynamicTlasCache;
import top.ceroxe.mcvulkanrt.renderer.rt.material.RtSceneMaterialTable;

import java.util.Objects;

/** Couples an asynchronous material upload to the dynamic TLAS whose custom indices it describes. */
record PendingDynamicTlasBind(
        RtScenePublication basePublication,
        RtDynamicTlasCache.Update dynamicUpdate,
        RtSceneMaterialTable.Snapshot materialSnapshot,
        RtSceneMaterialTable.PendingUpload materialUpload,
        long dynamicMaterialRevision
) implements AutoCloseable {
    PendingDynamicTlasBind {
        basePublication = Objects.requireNonNull(basePublication, "basePublication");
        dynamicUpdate = Objects.requireNonNull(dynamicUpdate, "dynamicUpdate");
        materialSnapshot = Objects.requireNonNull(materialSnapshot, "materialSnapshot");
        materialUpload = Objects.requireNonNull(materialUpload, "materialUpload");
        if (dynamicMaterialRevision < 0L) {
            throw new IllegalArgumentException("dynamic material revision must not be negative");
        }
    }

    @Override
    public void close() {
        materialUpload.close();
    }
}
