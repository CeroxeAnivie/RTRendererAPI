package top.ceroxe.rt.renderer.rt.device;

import top.ceroxe.rt.renderer.RendererFrameUpdate;
import top.ceroxe.rt.renderer.rt.acceleration.RtWorldTlasCache;
import top.ceroxe.rt.renderer.rt.material.RtSceneMaterialTable;

import java.util.Objects;

/**
 * Owns a world TLAS/material transaction and its immutable descriptor resource base.
 */
record PendingWorldSceneBind(
        RtScenePublication basePublication,
        RtWorldTlasCache.WorldTlasUpdate worldTlasUpdate,
        RtSceneMaterialTable.Snapshot materialSnapshot,
        long dynamicMaterialRevision,
        RtSceneMaterialTable.PendingUpload materialUpload,
        String bindReason,
        boolean urgentWorldSceneBind
) implements AutoCloseable {
    PendingWorldSceneBind {
        basePublication = Objects.requireNonNull(basePublication, "basePublication");
        worldTlasUpdate = Objects.requireNonNull(worldTlasUpdate, "worldTlasUpdate");
        materialSnapshot = Objects.requireNonNull(materialSnapshot, "materialSnapshot");
        materialUpload = Objects.requireNonNull(materialUpload, "materialUpload");
        bindReason = Objects.requireNonNull(bindReason, "bindReason");
        if (dynamicMaterialRevision < 0L) {
            throw new IllegalArgumentException("dynamic material revision must not be negative");
        }
        if (materialSnapshot.sectionCount() < worldTlasUpdate.terrainMaterialCount()) {
            throw new IllegalArgumentException("world bind material snapshot does not cover terrain slots");
        }
    }

    String bindReason(RendererFrameUpdate update) {
        Objects.requireNonNull(update, "update");
        return bindReason;
    }

    /**
     * Releases the material upload owned by this uncommitted transaction.
     */
    @Override
    public void close() {
        materialUpload.close();
    }
}
