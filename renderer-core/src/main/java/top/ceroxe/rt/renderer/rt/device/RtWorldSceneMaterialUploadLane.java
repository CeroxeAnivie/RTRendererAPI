package top.ceroxe.rt.renderer.rt.device;

import top.ceroxe.rt.renderer.RendererFrameCausality;
import top.ceroxe.rt.renderer.RendererRtDiagnostics;
import top.ceroxe.rt.renderer.rt.acceleration.RtDynamicBlasCache;
import top.ceroxe.rt.renderer.rt.acceleration.RtSectionBlasCache;
import top.ceroxe.rt.renderer.rt.acceleration.RtWorldTlasCache;
import top.ceroxe.rt.renderer.rt.material.RtSceneMaterialTable;
import top.ceroxe.rt.renderer.rt.material.RtTextureCatalog;

import java.util.Objects;

/**
 * Starts material publication transactions for world and material-only scene updates.
 *
 * <p>The lane owns upload admission, snapshot rebasing, async-upload ownership transfer and
 * pre-publication rollback. Descriptor binding remains in {@link RtSceneBindCoordinator}; the
 * device context only executes the explicit result and applies interactive-scene side effects.</p>
 */
final class RtWorldSceneMaterialUploadLane {
    private final RtSceneBindCoordinator coordinator;
    private final RtWorldTlasCache worldTlasCache;
    private final RtSectionBlasCache sectionBlasCache;
    private final RtDynamicBlasCache dynamicBlasCache;
    private final RtSceneMaterialTable materialTable;
    private final RtCommandContext buildCommands;
    private final RendererRtDiagnostics diagnostics;
    private final RtWorldSceneBindStatistics statistics;

    RtWorldSceneMaterialUploadLane(
            RtSceneBindCoordinator coordinator,
            RtWorldTlasCache worldTlasCache,
            RtSectionBlasCache sectionBlasCache,
            RtDynamicBlasCache dynamicBlasCache,
            RtSceneMaterialTable materialTable,
            RtCommandContext buildCommands,
            RendererRtDiagnostics diagnostics,
            RtWorldSceneBindStatistics statistics
    ) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.worldTlasCache = Objects.requireNonNull(worldTlasCache, "worldTlasCache");
        this.sectionBlasCache = Objects.requireNonNull(sectionBlasCache, "sectionBlasCache");
        this.dynamicBlasCache = Objects.requireNonNull(dynamicBlasCache, "dynamicBlasCache");
        this.materialTable = Objects.requireNonNull(materialTable, "materialTable");
        this.buildCommands = Objects.requireNonNull(buildCommands, "buildCommands");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.statistics = Objects.requireNonNull(statistics, "statistics");
    }

    static boolean shouldSubmitMaterialOnlyUpload(
            RtSceneMaterialTable.Snapshot bound,
            RtSceneMaterialTable.Snapshot next
    ) {
        Objects.requireNonNull(bound, "bound");
        Objects.requireNonNull(next, "next");
        return bound.sectionCount() > 0
                && next.sectionCount() > 0
                && bound.instanceLayoutHash() == next.instanceLayoutHash()
                && !next.signature().equals(bound.signature());
    }

    static boolean materialGenerationCanAdvanceWithoutUpload(
            RtSceneMaterialTable.Snapshot bound,
            RtSceneMaterialTable.Snapshot next
    ) {
        Objects.requireNonNull(bound, "bound");
        Objects.requireNonNull(next, "next");
        return bound.sectionCount() > 0
                && next.sectionCount() > 0
                && bound.instanceLayoutHash() == next.instanceLayoutHash()
                && next.signature().equals(bound.signature());
    }

    static boolean materialGenerationIsDirty(
            long sectionRevision,
            long dynamicRevision,
            long textureRevision,
            long publishedSectionRevision,
            long publishedDynamicRevision,
            long boundTextureRevision
    ) {
        if (sectionRevision < 0L || dynamicRevision < 0L || textureRevision < 0L) {
            throw new IllegalArgumentException("current material revisions must not be negative");
        }
        return sectionRevision != publishedSectionRevision
                || dynamicRevision != publishedDynamicRevision
                || textureRevision != boundTextureRevision;
    }

    WorldSubmission submitWorld(
            RtWorldTlasCache.WorldTlasUpdate update,
            String bindReason,
            boolean urgent,
            boolean allowInPlaceMaterialUpdate
    ) {
        Objects.requireNonNull(update, "update");
        Objects.requireNonNull(bindReason, "bindReason");
        if (coordinator.state().hasPendingDynamic()) {
            recordDeferredWorld(update, "dynamicDescriptorBindPending");
            return WorldSubmission.deferred();
        }
        if (coordinator.state().hasPendingMaterial()) {
            recordDeferredWorld(update, "materialOnlyBindPending");
            return WorldSubmission.deferred();
        }

        RtSceneMaterialTable.Snapshot targetSnapshot;
        long targetDynamicMaterialRevision;
        RtSceneMaterialTable.PendingUpload upload;
        try {
            targetDynamicMaterialRevision = coordinator.publication().dynamicMaterialRevision() >= 0L
                    ? coordinator.publication().dynamicMaterialRevision()
                    : update.dynamicMaterialRevision();
            targetSnapshot = RtWorldTlasCache.rebaseWorldMaterialSnapshot(
                    update,
                    coordinator.publication().materialSnapshot(),
                    coordinator.publication().terrainMaterialCount(),
                    coordinator.publication().dynamicMaterialRevision()
            );
            upload = materialTable.submitUploadAsync(
                    buildCommands,
                    targetSnapshot,
                    RtDescriptorTransactionPolicy.allowInPlaceMaterialUpload(
                            coordinator.publication().worldTlas() != null,
                            allowInPlaceMaterialUpdate
                    )
            );
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            worldTlasCache.rollbackUnboundWorldTlas(update, failure);
            recordWorldFailure(update, "submitWorldSceneMaterialUpload", failure);
            throw failure;
        }

        if (upload == null) {
            try {
                diagnostics.materials().materialOnlyDecision(
                        "worldSceneUploadDeduplicated",
                        coordinator.publication().materialSnapshot().revision(),
                        targetSnapshot.revision(),
                        coordinator.publication().materialSnapshot().textureSnapshot().revision(),
                        targetSnapshot.textureSnapshot().revision()
                );
            } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
                worldTlasCache.rollbackUnboundWorldTlas(update, failure);
                recordWorldFailure(update, "deduplicatedWorldScenePreflight", failure);
                throw failure;
            }
            return WorldSubmission.immediate(targetSnapshot, targetDynamicMaterialRevision);
        }

        try {
            PendingWorldSceneBind pending = new PendingWorldSceneBind(
                    coordinator.publication(),
                    update,
                    targetSnapshot,
                    targetDynamicMaterialRevision,
                    upload,
                    bindReason,
                    urgent
            );
            diagnostics.edges().edge(
                    "materialUploadSubmitted",
                    "worldTlasRevision=" + update.revision()
                            + ", materialRevision=" + targetSnapshot.revision()
                            + ", textureRevision=" + targetSnapshot.textureSnapshot().revision()
                            + ", instances=" + update.instanceCount()
            );
            if (coordinator.replacePendingWorldSceneBind(pending, "replacedByNewWorldScene", worldTlasCache)) {
                statistics.worldReplaced();
            }
            statistics.worldSubmitted();
            return WorldSubmission.pending();
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            try {
                upload.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            worldTlasCache.rollbackUnboundWorldTlas(update, failure);
            recordWorldFailure(update, "publishPendingWorldSceneBind", failure);
            throw failure;
        }
    }

    void submitMaterialOnly(boolean allowInPlaceMaterialUpdate, RendererFrameCausality causality) {
        Objects.requireNonNull(causality, "causality");
        RtScenePublication publication = coordinator.publication();
        if (coordinator.state().hasPendingDynamic()) {
            recordMaterialDeferred("dynamicDescriptorBindPending", publication);
            return;
        }
        if (coordinator.state().hasPendingMaterial()) {
            recordMaterialDeferred("materialOnlyBindAlreadyPending", publication);
            return;
        }
        if (coordinator.state().hasPendingWorld() || coordinator.state().hasDeferredWorld()) {
            statistics.materialSkippedPendingWorld();
            recordMaterialDeferred("worldSceneBindPending", publication);
            return;
        }
        if (publication.worldTlas() == null || publication.materialSnapshot().sectionCount() <= 0) {
            statistics.materialSkippedNoBoundWorld();
            recordMaterialDecision("skippedNoBoundWorld", publication.materialSnapshot(), publication.materialSnapshot());
            return;
        }

        long sectionRevision = sectionBlasCache.materialRevision();
        long dynamicRevision = dynamicBlasCache.materialRevision();
        long textureRevision = RtTextureCatalog.revision();
        if (!materialGenerationIsDirty(
                sectionRevision,
                dynamicRevision,
                textureRevision,
                publication.sectionMaterialRevision(),
                publication.dynamicMaterialRevision(),
                publication.materialSnapshot().textureSnapshot().revision()
        )) {
            statistics.materialSkippedUnchanged();
            return;
        }

        RtSceneMaterialTable.Snapshot next =
                worldTlasCache.snapshotBuiltMaterialForCurrentGeometry(sectionBlasCache, dynamicBlasCache);
        if (next == null) {
            statistics.materialSkippedUnchanged();
            diagnostics.materials().materialOnlyDecision(
                    "skippedNoGeometryMaterialChange",
                    publication.materialSnapshot().revision(),
                    publication.materialSnapshot().revision(),
                    publication.materialSnapshot().textureSnapshot().revision(),
                    textureRevision
            );
            return;
        }
        next = next.withCurrentTextureSnapshot();
        if (!shouldSubmitMaterialOnlyUpload(publication.materialSnapshot(), next)) {
            if (!materialGenerationCanAdvanceWithoutUpload(publication.materialSnapshot(), next)) {
                recordMaterialDecision("deferredForTlasLayoutChange", publication.materialSnapshot(), next);
                statistics.materialSkippedUnchanged();
                return;
            }
            recordMaterialDecision("skippedSignatureOrLayoutUnchanged", publication.materialSnapshot(), next);
            coordinator.publishMaterialMetadata(
                    next,
                    sectionRevision,
                    dynamicRevision,
                    RtScenePublication.Reason.MATERIAL_METADATA_ONLY,
                    causality
            );
            statistics.materialSkippedUnchanged();
            return;
        }

        RtSceneMaterialTable.PendingUpload upload = materialTable.submitUploadAsync(
                buildCommands,
                next,
                RtDescriptorTransactionPolicy.allowInPlaceMaterialUpload(
                        publication.worldTlas() != null,
                        allowInPlaceMaterialUpdate
                )
        );
        if (upload == null) {
            recordMaterialDecision("uploadDeduplicated", publication.materialSnapshot(), next);
            coordinator.publishMaterialMetadata(
                    next,
                    sectionRevision,
                    dynamicRevision,
                    RtScenePublication.Reason.MATERIAL_UPLOAD_DEDUPLICATED,
                    causality
            );
            statistics.materialSkippedUnchanged();
            return;
        }
        coordinator.publishPendingMaterial(new PendingMaterialOnlyBind(
                publication,
                next,
                upload,
                sectionRevision,
                dynamicRevision,
                causality
        ));
        statistics.materialSubmitted();
        recordMaterialDecision("uploadSubmitted", publication.materialSnapshot(), next);
    }

    private void recordDeferredWorld(RtWorldTlasCache.WorldTlasUpdate update, String reason) {
        diagnostics.materials().materialBindDeferred(
                "worldScene",
                reason,
                update.materialSnapshot().revision(),
                update.materialSnapshot().textureSnapshot().revision()
        );
    }

    private void recordMaterialDeferred(String reason, RtScenePublication publication) {
        diagnostics.materials().materialBindDeferred(
                "materialOnly",
                reason,
                publication.materialSnapshot().revision(),
                publication.materialSnapshot().textureSnapshot().revision()
        );
    }

    private void recordMaterialDecision(
            String decision,
            RtSceneMaterialTable.Snapshot bound,
            RtSceneMaterialTable.Snapshot next
    ) {
        diagnostics.materials().materialOnlyDecision(
                decision,
                bound.revision(),
                next.revision(),
                bound.textureSnapshot().revision(),
                next.textureSnapshot().revision()
        );
    }

    private void recordWorldFailure(
            RtWorldTlasCache.WorldTlasUpdate update,
            String stage,
            Throwable failure
    ) {
        try {
            diagnostics.materials().materialFailure(
                    stage,
                    update.materialSnapshot().revision(),
                    update.materialSnapshot().textureSnapshot().revision(),
                    failure.getClass().getSimpleName()
            );
        } catch (RuntimeException | LinkageError | OutOfMemoryError diagnosticFailure) {
            failure.addSuppressed(diagnosticFailure);
        }
    }

    enum WorldSubmissionStatus {
        DEFERRED,
        IMMEDIATE_BIND,
        PENDING_BIND
    }

    record WorldSubmission(
            WorldSubmissionStatus status,
            RtSceneMaterialTable.Snapshot materialSnapshot,
            long dynamicMaterialRevision
    ) {
        WorldSubmission {
            status = Objects.requireNonNull(status, "status");
            if (status == WorldSubmissionStatus.IMMEDIATE_BIND) {
                materialSnapshot = Objects.requireNonNull(materialSnapshot, "materialSnapshot");
                if (dynamicMaterialRevision < 0L) {
                    throw new IllegalArgumentException("dynamicMaterialRevision must not be negative");
                }
            } else if (materialSnapshot != null || dynamicMaterialRevision != -1L) {
                throw new IllegalArgumentException("only immediate world submissions carry material metadata");
            }
        }

        static WorldSubmission deferred() {
            return new WorldSubmission(WorldSubmissionStatus.DEFERRED, null, -1L);
        }

        static WorldSubmission immediate(RtSceneMaterialTable.Snapshot snapshot, long dynamicRevision) {
            return new WorldSubmission(WorldSubmissionStatus.IMMEDIATE_BIND, snapshot, dynamicRevision);
        }

        static WorldSubmission pending() {
            return new WorldSubmission(WorldSubmissionStatus.PENDING_BIND, null, -1L);
        }
    }
}
