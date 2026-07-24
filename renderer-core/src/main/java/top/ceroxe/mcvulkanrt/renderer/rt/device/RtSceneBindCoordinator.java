package top.ceroxe.mcvulkanrt.renderer.rt.device;

import top.ceroxe.mcvulkanrt.renderer.RendererFrameCausality;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameUpdate;
import top.ceroxe.mcvulkanrt.renderer.RendererRtDiagnostics;
import top.ceroxe.mcvulkanrt.renderer.RendererViewState;
import top.ceroxe.mcvulkanrt.renderer.rt.RtSceneReadiness;
import top.ceroxe.mcvulkanrt.renderer.rt.acceleration.RtDynamicTlasCache;
import top.ceroxe.mcvulkanrt.renderer.rt.acceleration.RtWorldTlasCache;
import top.ceroxe.mcvulkanrt.renderer.rt.material.RtSceneMaterialTable;
import top.ceroxe.mcvulkanrt.renderer.rt.pipeline.RtRayTracingPipeline;

import java.util.Objects;

/**
 * Single mutable owner of the descriptor-visible scene publication and its pending bind lanes.
 *
 * <p>A publication replacement is allowed only through this coordinator. Pending world, material,
 * dynamic, and deferred transactions remain in the adjacent {@link RtSceneBindState}, ensuring
 * debugger inspection has one authoritative location for both the visible front and competing
 * descriptor-commit work.</p>
 */
final class RtSceneBindCoordinator {
    private final RtSceneBindState state = new RtSceneBindState();
    private RtScenePublication publication;

    /** Result returned only after a world scene is descriptor-visible. */
    record WorldBindCompletion(
            RtWorldTlasCache.WorldTlasUpdate worldTlasUpdate,
            String bindReason
    ) {
        WorldBindCompletion {
            worldTlasUpdate = Objects.requireNonNull(worldTlasUpdate, "worldTlasUpdate");
            bindReason = Objects.requireNonNull(bindReason, "bindReason");
        }
    }

    enum WorldBindStatus {
        ABSENT,
        STALE_DISCARDED,
        DESCRIPTOR_DEFERRED,
        UPLOAD_PENDING,
        COMPLETED
    }

    enum MaterialBindStatus {
        ABSENT,
        STALE_DISCARDED,
        DESCRIPTOR_DEFERRED,
        UPLOAD_PENDING,
        COMPLETED
    }

    record WorldBindResult(WorldBindStatus status, WorldBindCompletion completion) {
        private static final WorldBindResult ABSENT = new WorldBindResult(WorldBindStatus.ABSENT, null);
        private static final WorldBindResult STALE_DISCARDED =
                new WorldBindResult(WorldBindStatus.STALE_DISCARDED, null);
        private static final WorldBindResult DESCRIPTOR_DEFERRED =
                new WorldBindResult(WorldBindStatus.DESCRIPTOR_DEFERRED, null);
        private static final WorldBindResult UPLOAD_PENDING =
                new WorldBindResult(WorldBindStatus.UPLOAD_PENDING, null);

        WorldBindResult {
            status = Objects.requireNonNull(status, "status");
            if ((status == WorldBindStatus.COMPLETED) != (completion != null)) {
                throw new IllegalArgumentException("only a completed world bind may carry a completion");
            }
        }

        static WorldBindResult status(WorldBindStatus status) {
            return switch (Objects.requireNonNull(status, "status")) {
                case ABSENT -> ABSENT;
                case STALE_DISCARDED -> STALE_DISCARDED;
                case DESCRIPTOR_DEFERRED -> DESCRIPTOR_DEFERRED;
                case UPLOAD_PENDING -> UPLOAD_PENDING;
                case COMPLETED -> throw new IllegalArgumentException("completed world bind requires a completion");
            };
        }

        static WorldBindResult completed(WorldBindCompletion completion) {
            return new WorldBindResult(WorldBindStatus.COMPLETED, completion);
        }
    }

    RtSceneBindCoordinator(RtScenePublication bootstrapPublication) {
        publication = Objects.requireNonNull(bootstrapPublication, "bootstrapPublication");
    }

    RtScenePublication publication() {
        return publication;
    }

    private void replacePublication(RtScenePublication nextPublication) {
        publication = Objects.requireNonNull(nextPublication, "nextPublication");
    }

    void promoteWorldView(RendererViewState promotedView, RendererFrameCausality causality) {
        replacePublication(publication.promoteWorldView(
                Objects.requireNonNull(promotedView, "promotedView"),
                Objects.requireNonNull(causality, "causality")
        ));
    }

    void publishMaterialMetadata(
            RtSceneMaterialTable.Snapshot materialSnapshot,
            long sectionMaterialRevision,
            long dynamicMaterialRevision,
            RtScenePublication.Reason reason,
            RendererFrameCausality causality
    ) {
        replacePublication(publication.publishMaterial(
                materialSnapshot,
                sectionMaterialRevision,
                dynamicMaterialRevision,
                publication.descriptorGeneration(),
                reason,
                causality
        ));
    }

    RtSceneBindView state() {
        return state;
    }

    void publishPendingDynamic(PendingDynamicTlasBind pending) {
        state.publishPendingDynamic(pending);
    }

    void publishPendingMaterial(PendingMaterialOnlyBind pending) {
        state.publishPendingMaterial(pending);
    }

    void publishDeferredWorld(DeferredWorldSceneBind deferred) {
        state.publishDeferredWorld(deferred);
    }

    void replaceDeferredWorld(DeferredWorldSceneBind expected, DeferredWorldSceneBind replacement) {
        state.replaceDeferredWorld(expected, replacement);
    }

    void clearDeferredWorld(DeferredWorldSceneBind expected) {
        state.clearDeferredWorld(expected);
    }

    void closePendingWorld() {
        PendingWorldSceneBind pending = state.takePendingWorld();
        if (pending != null) {
            pending.close();
        }
    }

    void closePendingMaterial() {
        PendingMaterialOnlyBind pending = state.takePendingMaterial();
        if (pending != null) {
            pending.close();
        }
    }

    /**
     * Returns whether an asynchronous transaction captured resources which are no longer the
     * descriptor-visible base. A publication generation is intentionally not sufficient here:
     * view-only promotion may advance logical metadata while retaining the same GPU resources.
     */
    static boolean capturedPublicationIsStale(
            RtScenePublication capturedPublication,
            RtScenePublication currentPublication
    ) {
        Objects.requireNonNull(capturedPublication, "capturedPublication");
        Objects.requireNonNull(currentPublication, "currentPublication");
        return !capturedPublication.hasSameDescriptorResourceBase(currentPublication);
    }

    void bindDynamicTlasForDispatch(
            RtDynamicTlasCache.Update update,
            long dynamicMaterialRevision,
            RtRayTracingPipeline pipeline,
            RtSceneMaterialTable materialTable,
            RtDynamicTlasCache dynamicTlasCache,
            RtWorldTlasCache worldTlasCache,
            RendererRtDiagnostics diagnostics
    ) {
        Objects.requireNonNull(update, "update");
        dynamicTlasCache.validateCommitBound(update, publication.descriptorGeneration());
        long nextDescriptorGeneration = Math.addExact(pipeline.activeDescriptorGeneration(), 1L);
        RtScenePublication nextPublication = publication.publishDynamic(
                update, publication.materialSnapshot(), dynamicMaterialRevision, nextDescriptorGeneration,
                RtScenePublication.Reason.DYNAMIC_TLAS, update.causality());
        RtRayTracingPipeline.DescriptorGenerationBinding binding = pipeline.bindWorldScene(
                publication.worldTlas(), update.topLevelAccelerationStructure(), materialTable,
                publication.terrainMaterialCount(), false);
        if (!binding.advanced() || binding.activeGeneration() != nextPublication.descriptorGeneration()) {
            throw new IllegalStateException("dynamic TLAS descriptor generation diverged from its publication");
        }
        replacePublication(nextPublication);
        dynamicTlasCache.commitBound(update, binding.previousGeneration());
        worldTlasCache.commitBoundDynamicLane(
                update.revision(), update.topologyRevision(), update.geometryRevision(), update.activeInstances());
        diagnostics.edges().edge(
                "dynamicTlasBound",
                "revision=" + update.revision() + ", transformRevision=" + update.transformRevision()
                        + ", activeInstances=" + update.activeInstances() + ", capacity=" + update.capacity());
    }

    /**
     * Completes the coupled material-upload and dynamic-TLAS transaction. The descriptor bind is
     * its linearization point: before it, both candidate resources can be discarded; after it,
     * neither may be rolled back because an active descriptor can reference them.
     */
    void completePendingDynamicTlasBindIfReady(
            boolean descriptorsCanBeUpdated,
            RtRayTracingPipeline pipeline,
            RtSceneMaterialTable materialTable,
            RtDynamicTlasCache dynamicTlasCache,
            RtWorldTlasCache worldTlasCache,
            RendererRtDiagnostics diagnostics
    ) {
        PendingDynamicTlasBind pending = state.pendingDynamic();
        if (pending == null) {
            return;
        }
        if (capturedPublicationIsStale(pending.basePublication(), publication)) {
            discardPendingDynamicTlasBind(pending, "staleBasePublication", dynamicTlasCache, diagnostics);
            return;
        }
        dynamicTlasCache.validateCommitBound(
                pending.dynamicUpdate(), pending.basePublication().descriptorGeneration()
        );
        if (!descriptorsCanBeUpdated) {
            diagnostics.edges().edgeOnce(
                    "dynamicTlasAtomicCompletionDeferred:" + pending.dynamicUpdate().revision(),
                    "dynamicTlasAtomicCompletionDeferred",
                    "revision=" + pending.dynamicUpdate().revision() + ", reason=descriptorBusy"
            );
            return;
        }
        RtSceneMaterialTable.ActivatedUpload activated = materialTable.activateUploadIfReady(pending.materialUpload());
        if (activated == null) {
            diagnostics.edges().edgeOnce(
                    "dynamicTlasAtomicUploadPending:" + pending.dynamicUpdate().revision(),
                    "dynamicTlasAtomicUploadPending",
                    "revision=" + pending.dynamicUpdate().revision()
            );
            return;
        }
        boolean descriptorBound = false;
        try {
            long nextDescriptorGeneration = Math.addExact(pipeline.activeDescriptorGeneration(), 1L);
            /* A metadata-only successor retains the resource base and must not regress view metadata. */
            RtScenePublication basePublication = publication;
            RtScenePublication nextPublication = basePublication.publishDynamic(
                    pending.dynamicUpdate(),
                    pending.materialSnapshot(),
                    pending.dynamicMaterialRevision(),
                    nextDescriptorGeneration,
                    RtScenePublication.Reason.DYNAMIC_TLAS_WITH_MATERIAL,
                    pending.dynamicUpdate().causality()
            );
            RtRayTracingPipeline.DescriptorGenerationBinding binding = pipeline.bindWorldScene(
                    basePublication.worldTlas(),
                    pending.dynamicUpdate().topLevelAccelerationStructure(),
                    materialTable,
                    basePublication.terrainMaterialCount(),
                    activated.materialBuffersChanged()
            );
            if (!binding.advanced()) {
                throw new IllegalStateException("dynamic TLAS atomic bind did not advance descriptor generation");
            }
            if (binding.activeGeneration() != nextPublication.descriptorGeneration()) {
                throw new IllegalStateException("dynamic TLAS descriptor generation diverged from its publication");
            }
            descriptorBound = true;
            replacePublication(nextPublication);
            activated.commit(binding.previousGeneration());
            dynamicTlasCache.commitBound(pending.dynamicUpdate(), binding.previousGeneration());
            worldTlasCache.commitBoundDynamicLane(
                    pending.dynamicUpdate().revision(),
                    pending.dynamicUpdate().topologyRevision(),
                    pending.dynamicUpdate().geometryRevision(),
                    pending.dynamicUpdate().activeInstances()
            );
            state.clearPendingDynamic(pending);
            diagnostics.edges().edge(
                    "dynamicTlasAtomicBound",
                    "revision=" + pending.dynamicUpdate().revision()
                            + ", descriptorGeneration=" + binding.activeGeneration()
                            + ", materialLayoutHash=0x"
                            + Integer.toHexString(pending.materialSnapshot().instanceLayoutHash())
            );
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            state.clearPendingDynamic(pending);
            if (!descriptorBound) {
                try {
                    activated.rollback();
                } catch (RuntimeException rollbackFailure) {
                    ex.addSuppressed(rollbackFailure);
                }
                try {
                    dynamicTlasCache.discardUnbound(pending.dynamicUpdate());
                } catch (RuntimeException discardFailure) {
                    ex.addSuppressed(discardFailure);
                }
            }
            throw ex;
        }
    }

    private void discardPendingDynamicTlasBind(
            PendingDynamicTlasBind pending,
            String reason,
            RtDynamicTlasCache dynamicTlasCache,
            RendererRtDiagnostics diagnostics
    ) {
        Objects.requireNonNull(pending, "pending");
        Objects.requireNonNull(reason, "reason");
        state.clearPendingDynamic(pending);
        RuntimeException failure = null;
        try {
            pending.close();
        } catch (RuntimeException closeFailure) {
            failure = closeFailure;
        }
        try {
            dynamicTlasCache.discardUnbound(pending.dynamicUpdate());
        } catch (RuntimeException discardFailure) {
            if (failure == null) {
                failure = discardFailure;
            } else {
                failure.addSuppressed(discardFailure);
            }
        }
        diagnostics.edges().edge(
                "dynamicTlasAtomicBindDiscarded",
                "revision=" + pending.dynamicUpdate().revision()
                        + ", basePublication=" + pending.basePublication().generation()
                        + ", currentPublication=" + publication.generation()
                        + ", reason=" + reason
        );
        if (failure != null) {
            throw failure;
        }
    }

    void discardPendingDynamicTlasBindIfPresent(
            String reason,
            RtDynamicTlasCache dynamicTlasCache,
            RendererRtDiagnostics diagnostics
    ) {
        PendingDynamicTlasBind pending = state.pendingDynamic();
        if (pending != null) {
            discardPendingDynamicTlasBind(pending, reason, dynamicTlasCache, diagnostics);
        }
    }

    /**
     * Completes an asynchronous world/material transaction and returns its visible front. Scene
     * scheduling remains outside this class; this method owns only resource publication and the
     * rollback boundary associated with that publication.
     */
    WorldBindResult completePendingWorldSceneBindIfReady(
            RendererFrameUpdate update,
            boolean descriptorsCanBeUpdated,
            RtRayTracingPipeline pipeline,
            RtSceneMaterialTable materialTable,
            RtWorldTlasCache worldTlasCache,
            RendererRtDiagnostics diagnostics,
            long pendingUploadPollNumber
    ) {
        Objects.requireNonNull(update, "update");
        if (pendingUploadPollNumber <= 0L) {
            throw new IllegalArgumentException("pending upload poll number must be positive");
        }
        PendingWorldSceneBind pending = state.pendingWorld();
        if (pending == null) {
            return WorldBindResult.status(WorldBindStatus.ABSENT);
        }
        if (capturedPublicationIsStale(pending.basePublication(), publication)) {
            discardPendingWorldSceneBind(pending, "staleBasePublication", worldTlasCache, diagnostics);
            return WorldBindResult.status(WorldBindStatus.STALE_DISCARDED);
        }
        if (!descriptorsCanBeUpdated) {
            diagnostics.materials().materialBindDeferred(
                    "worldScene",
                    "descriptorGenerationBusy",
                    pending.materialSnapshot().revision(),
                    pending.materialSnapshot().textureSnapshot().revision()
            );
            return WorldBindResult.status(WorldBindStatus.DESCRIPTOR_DEFERRED);
        }

        RtSceneMaterialTable.ActivatedUpload activatedUpload = materialTable.activateUploadIfReady(pending.materialUpload());
        if (activatedUpload == null) {
            diagnostics.edges().edgeOnce(
                    "materialUploadNotReady:" + pending.worldTlasUpdate().revision(),
                    "materialUploadPollNotReady",
                    "worldTlasRevision=" + pending.worldTlasUpdate().revision()
                            + ", materialRevision=" + pending.materialSnapshot().revision()
                            + ", pollsNotReady=" + pendingUploadPollNumber
            );
            diagnostics.materials().materialUploadAwaiting(
                    "worldScene",
                    pending.materialSnapshot().revision(),
                    pending.materialSnapshot().textureSnapshot().revision()
            );
            return WorldBindResult.status(WorldBindStatus.UPLOAD_PENDING);
        }

        boolean descriptorBound = false;
        try {
            String bindReason = pending.bindReason(update);
            long nextDescriptorGeneration = Math.addExact(pipeline.activeDescriptorGeneration(), 1L);
            worldTlasCache.validateCommitBoundWorldTlas(
                    pending.worldTlasUpdate(), bindReason, pipeline.activeDescriptorGeneration()
            );
            RtScenePublication nextPublication = publication.publishWorld(
                    pending.worldTlasUpdate(),
                    pending.materialSnapshot(),
                    pending.dynamicMaterialRevision(),
                    nextDescriptorGeneration,
                    RtScenePublication.Reason.WORLD_TLAS,
                    pending.worldTlasUpdate().causality()
            );
            RtRayTracingPipeline.DescriptorGenerationBinding descriptorBinding = pipeline.bindWorldScene(
                    pending.worldTlasUpdate().topLevelAccelerationStructure(),
                    publication.dynamicTlas(),
                    materialTable,
                    pending.worldTlasUpdate().terrainMaterialCount(),
                    activatedUpload.materialBuffersChanged()
            );
            if (!descriptorBinding.advanced()) {
                throw new IllegalStateException("world TLAS bind did not advance the descriptor generation");
            }
            if (descriptorBinding.activeGeneration() != nextPublication.descriptorGeneration()) {
                throw new IllegalStateException("world TLAS descriptor generation diverged from its publication");
            }
            descriptorBound = true;
            replacePublication(nextPublication);
            activatedUpload.commit(descriptorBinding.previousGeneration());
            worldTlasCache.commitBoundWorldTlas(
                    pending.worldTlasUpdate(), bindReason, descriptorBinding.previousGeneration()
            );
            state.clearPendingWorld(pending);
            diagnostics.edges().edge(
                    "worldSceneBound",
                    "worldTlasRevision=" + pending.worldTlasUpdate().revision()
                            + ", materialRevision=" + pending.materialSnapshot().revision()
                            + ", descriptorGeneration=" + descriptorBinding.activeGeneration()
                            + ", sections=" + publication.worldSectionKeys().size()
            );
            return WorldBindResult.completed(new WorldBindCompletion(pending.worldTlasUpdate(), bindReason));
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            state.clearPendingWorld(pending);
            diagnostics.materials().materialFailure(
                    "completePendingWorldSceneBind",
                    pending.materialSnapshot().revision(),
                    pending.materialSnapshot().textureSnapshot().revision(),
                    ex.getClass().getSimpleName()
            );
            if (!descriptorBound) {
                try {
                    activatedUpload.rollback();
                } catch (RuntimeException rollbackFailure) {
                    ex.addSuppressed(rollbackFailure);
                }
                worldTlasCache.rollbackUnboundWorldTlas(pending.worldTlasUpdate(), ex);
            }
            throw ex;
        }
    }

    private void discardPendingWorldSceneBind(
            PendingWorldSceneBind pending,
            String reason,
            RtWorldTlasCache worldTlasCache,
            RendererRtDiagnostics diagnostics
    ) {
        state.clearPendingWorld(pending);
        RuntimeException failure = null;
        try {
            pending.close();
        } catch (RuntimeException closeFailure) {
            failure = closeFailure;
        }
        try {
            worldTlasCache.discardUnboundWorldTlas(pending.worldTlasUpdate(), reason);
        } catch (RuntimeException discardFailure) {
            if (failure == null) {
                failure = discardFailure;
            } else {
                failure.addSuppressed(discardFailure);
            }
        }
        diagnostics.edges().edge(
                "worldSceneBindDiscarded",
                "worldTlasRevision=" + pending.worldTlasUpdate().revision()
                        + ", basePublication=" + pending.basePublication().generation()
                        + ", currentPublication=" + publication.generation()
                        + ", reason=" + reason
        );
        if (failure != null) {
            throw failure;
        }
    }

    MaterialBindStatus completePendingMaterialOnlyBindIfReady(
            boolean descriptorsCanBeUpdated,
            RtRayTracingPipeline pipeline,
            RtSceneMaterialTable materialTable,
            RendererRtDiagnostics diagnostics
    ) {
        PendingMaterialOnlyBind pending = state.pendingMaterial();
        if (pending == null) {
            return MaterialBindStatus.ABSENT;
        }
        if (capturedPublicationIsStale(pending.basePublication(), publication)) {
            discardPendingMaterialOnlyBind(pending, "staleBasePublication", diagnostics);
            return MaterialBindStatus.STALE_DISCARDED;
        }
        if (!descriptorsCanBeUpdated) {
            diagnostics.materials().materialBindDeferred(
                    "materialOnly",
                    "descriptorGenerationBusy",
                    pending.materialSnapshot().revision(),
                    pending.materialSnapshot().textureSnapshot().revision()
            );
            return MaterialBindStatus.DESCRIPTOR_DEFERRED;
        }

        boolean materialBuffersChanged = pending.materialUpload().materialBuffersChanged();
        if (materialBuffersChanged && publication.worldTlas() == null) {
            throw new IllegalStateException("cannot refresh material descriptors before a world TLAS is bound");
        }
        long nextDescriptorGeneration = materialBuffersChanged
                ? Math.addExact(pipeline.activeDescriptorGeneration(), 1L)
                : pipeline.activeDescriptorGeneration();
        RtScenePublication nextPublication = publication.publishMaterial(
                pending.materialSnapshot(),
                pending.sectionMaterialRevision(),
                pending.dynamicMaterialRevision(),
                nextDescriptorGeneration,
                RtScenePublication.Reason.MATERIAL_ONLY,
                pending.causality()
        );
        RtSceneMaterialTable.ActivatedUpload activatedUpload = materialTable.activateUploadIfReady(pending.materialUpload());
        if (activatedUpload == null) {
            diagnostics.materials().materialUploadAwaiting(
                    "materialOnly",
                    pending.materialSnapshot().revision(),
                    pending.materialSnapshot().textureSnapshot().revision()
            );
            return MaterialBindStatus.UPLOAD_PENDING;
        }

        boolean publicationVisible = false;
        try {
            long retiredDescriptorGeneration = 0L;
            if (materialBuffersChanged) {
                RtRayTracingPipeline.DescriptorGenerationBinding descriptorBinding = pipeline.bindWorldScene(
                        publication.worldTlas(),
                        publication.dynamicTlas(),
                        materialTable,
                        publication.terrainMaterialCount(),
                        true
                );
                if (!descriptorBinding.advanced()) {
                    throw new IllegalStateException("material bind did not advance the descriptor generation");
                }
                retiredDescriptorGeneration = descriptorBinding.previousGeneration();
                if (descriptorBinding.activeGeneration() != nextPublication.descriptorGeneration()) {
                    throw new IllegalStateException("material descriptor generation diverged from its publication");
                }
            }
            /* Publication is the rollback boundary for both in-place and copy-on-write uploads. */
            replacePublication(nextPublication);
            publicationVisible = true;
            activatedUpload.commit(retiredDescriptorGeneration);
            state.clearPendingMaterial(pending);
            diagnostics.materials().materialOnlyDecision(
                    "bindCompleted",
                    publication.materialSnapshot().revision(),
                    pending.materialSnapshot().revision(),
                    publication.materialSnapshot().textureSnapshot().revision(),
                    pending.materialSnapshot().textureSnapshot().revision()
            );
            return MaterialBindStatus.COMPLETED;
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            state.clearPendingMaterial(pending);
            diagnostics.materials().materialFailure(
                    "completePendingMaterialOnlyBind",
                    pending.materialSnapshot().revision(),
                    pending.materialSnapshot().textureSnapshot().revision(),
                    ex.getClass().getSimpleName()
            );
            if (!publicationVisible) {
                try {
                    activatedUpload.rollback();
                } catch (RuntimeException rollbackFailure) {
                    ex.addSuppressed(rollbackFailure);
                }
            }
            throw ex;
        }
    }

    private void discardPendingMaterialOnlyBind(
            PendingMaterialOnlyBind pending,
            String reason,
            RendererRtDiagnostics diagnostics
    ) {
        state.clearPendingMaterial(pending);
        pending.close();
        diagnostics.edges().edge(
                "materialOnlyBindDiscarded",
                "materialRevision=" + pending.materialSnapshot().revision()
                        + ", basePublication=" + pending.basePublication().generation()
                        + ", currentPublication=" + publication.generation()
                        + ", reason=" + reason
        );
    }

    /** Publishes an already material-compatible world TLAS without an asynchronous upload. */
    WorldBindCompletion bindWorldSceneForDispatch(
            RtWorldTlasCache.WorldTlasUpdate update,
            RtSceneMaterialTable.Snapshot materialSnapshot,
            long dynamicMaterialRevision,
            boolean materialBuffersChanged,
            String bindReason,
            RtRayTracingPipeline pipeline,
            RtSceneMaterialTable materialTable,
            RtWorldTlasCache worldTlasCache
    ) {
        Objects.requireNonNull(update, "update");
        Objects.requireNonNull(materialSnapshot, "materialSnapshot");
        Objects.requireNonNull(bindReason, "bindReason");
        boolean descriptorBound = false;
        try {
            long previousDescriptorGeneration = pipeline.activeDescriptorGeneration();
            long nextDescriptorGeneration = Math.addExact(previousDescriptorGeneration, 1L);
            worldTlasCache.validateCommitBoundWorldTlas(update, bindReason, previousDescriptorGeneration);
            RtScenePublication nextPublication = publication.publishWorld(
                    update,
                    materialSnapshot,
                    dynamicMaterialRevision,
                    nextDescriptorGeneration,
                    RtScenePublication.Reason.WORLD_TLAS,
                    update.causality()
            );
            RtRayTracingPipeline.DescriptorGenerationBinding descriptorBinding = pipeline.bindWorldScene(
                    update.topLevelAccelerationStructure(),
                    publication.dynamicTlas(),
                    materialTable,
                    update.terrainMaterialCount(),
                    materialBuffersChanged
            );
            if (!descriptorBinding.advanced()) {
                throw new IllegalStateException("world TLAS bind did not advance the descriptor generation");
            }
            if (descriptorBinding.activeGeneration() != nextPublication.descriptorGeneration()) {
                throw new IllegalStateException("world TLAS descriptor generation diverged from its publication");
            }
            descriptorBound = true;
            replacePublication(nextPublication);
            worldTlasCache.commitBoundWorldTlas(update, bindReason, descriptorBinding.previousGeneration());
            return new WorldBindCompletion(update, bindReason);
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            if (!descriptorBound) {
                worldTlasCache.rollbackUnboundWorldTlas(update, ex);
            }
            throw ex;
        }
    }

    /** Replaces the pending world transaction after fully retiring its superseded resources. */
    boolean replacePendingWorldSceneBind(
            PendingWorldSceneBind replacement,
            String reason,
            RtWorldTlasCache worldTlasCache
    ) {
        Objects.requireNonNull(replacement, "replacement");
        Objects.requireNonNull(reason, "reason");
        PendingWorldSceneBind previous = state.takePendingWorld();
        if (previous != null) {
            RuntimeException failure = null;
            try {
                previous.close();
            } catch (RuntimeException closeFailure) {
                failure = closeFailure;
            }
            try {
                worldTlasCache.discardUnboundWorldTlas(previous.worldTlasUpdate(), reason);
            } catch (RuntimeException discardFailure) {
                if (failure == null) {
                    failure = discardFailure;
                } else {
                    failure.addSuppressed(discardFailure);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
        state.replacePendingWorld(replacement);
        return previous != null;
    }

    /** Publishes a transform-only world refit while preserving the existing material generation. */
    WorldBindCompletion bindTransformOnlyWorldScene(
            RtWorldTlasCache.WorldTlasUpdate update,
            RtRayTracingPipeline pipeline,
            RtSceneMaterialTable materialTable,
            RtWorldTlasCache worldTlasCache
    ) {
        Objects.requireNonNull(update, "update");
        long nextDescriptorGeneration = Math.addExact(pipeline.activeDescriptorGeneration(), 1L);
        RtScenePublication nextPublication = publication.publishWorld(
                update,
                nextDescriptorGeneration,
                RtScenePublication.Reason.WORLD_TRANSFORM_REFIT,
                update.causality()
        );
        RtRayTracingPipeline.DescriptorGenerationBinding descriptorBinding = pipeline.bindWorldSceneLazily(
                update.topLevelAccelerationStructure(),
                publication.dynamicTlas(),
                materialTable,
                update.terrainMaterialCount()
        );
        if (!descriptorBinding.advanced()) {
            throw new IllegalStateException("transform-only TLAS bind did not advance the descriptor generation");
        }
        if (descriptorBinding.activeGeneration() != nextPublication.descriptorGeneration()) {
            throw new IllegalStateException("transform-only descriptor generation diverged from its publication");
        }
        replacePublication(nextPublication);
        worldTlasCache.commitBoundWorldTlas(
                update, RtSceneReadiness.READY_REASON, descriptorBinding.previousGeneration()
        );
        return new WorldBindCompletion(update, "dynamicTransformRefit");
    }
}
