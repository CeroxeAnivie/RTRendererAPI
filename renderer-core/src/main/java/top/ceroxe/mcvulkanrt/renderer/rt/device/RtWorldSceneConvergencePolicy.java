package top.ceroxe.mcvulkanrt.renderer.rt.device;

import top.ceroxe.mcvulkanrt.renderer.RendererFrameCommitPlan;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameUpdate;
import top.ceroxe.mcvulkanrt.renderer.RendererUpdateLoop;
import top.ceroxe.mcvulkanrt.renderer.scene.SceneUpdateBatch;
import top.ceroxe.mcvulkanrt.renderer.rt.RtSceneReadiness;
import top.ceroxe.mcvulkanrt.renderer.rt.acceleration.RtAccelerationStructure;
import top.ceroxe.mcvulkanrt.renderer.rt.acceleration.RtWorldTlasCache;
import top.ceroxe.mcvulkanrt.renderer.rt.material.RtSceneMaterialTable;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;

import java.util.Objects;
import java.util.Set;

/**
 * Pure scheduling policy for converging the published world scene with accepted updates.
 *
 * <p>VulkanRtDeviceContext owns Vulkan resources and executes decisions. This component
 * owns only the decision table, so device lifetime, queue submission, and presentation
 * cannot accidentally become prerequisites for testing scene convergence.</p>
 */
final class RtWorldSceneConvergencePolicy {
    private RtWorldSceneConvergencePolicy() {
    }

    static boolean shouldContinue(
            RtSceneReadiness readiness,
            boolean pendingWorldSceneBind,
            boolean deferredWorldSceneBind,
            boolean materialRevisionPending
    ) {
        Objects.requireNonNull(readiness, "readiness");
        if (pendingWorldSceneBind || deferredWorldSceneBind || materialRevisionPending) {
            return true;
        }
        if (readiness.observedSectionInstances() <= 0) {
            return false;
        }
        return !readiness.worldTlasReady()
                || !readiness.builtRevisionIsCurrent()
                || readiness.builtSectionInstances() < readiness.observedSectionInstances();
    }

    static boolean sectionMaterialRevisionOutranBoundSnapshot(
            long sectionMaterialRevision,
            RtSceneMaterialTable.Snapshot boundMaterialSnapshot
    ) {
        Objects.requireNonNull(boundMaterialSnapshot, "boundMaterialSnapshot");
        if (sectionMaterialRevision < 0L) {
            throw new IllegalArgumentException("sectionMaterialRevision must not be negative");
        }
        return boundMaterialSnapshot.sectionCount() > 0
                && sectionMaterialRevision > boundMaterialSnapshot.revision();
    }

    static boolean textureCatalogRevisionOutranBoundSnapshot(
            RtSceneMaterialTable.Snapshot boundMaterialSnapshot,
            long textureCatalogRevision
    ) {
        Objects.requireNonNull(boundMaterialSnapshot, "boundMaterialSnapshot");
        if (textureCatalogRevision < 0L) {
            throw new IllegalArgumentException("textureCatalogRevision must not be negative");
        }
        return boundMaterialSnapshot.sectionCount() > 0
                && textureCatalogRevision > boundMaterialSnapshot.textureSnapshot().revision();
    }

    static boolean shouldRunWorldTlasScheduler(
            boolean pendingWorldSceneBind,
            boolean deferredWorldSceneBind
    ) {
        return !pendingWorldSceneBind && !deferredWorldSceneBind;
    }

    static boolean shouldRunDynamicTlasScheduler(boolean pendingDynamicTlasBind) {
        return !pendingDynamicTlasBind;
    }

    static boolean initialWorldFrontBuildReady(
            boolean currentWorldTlasPresent,
            boolean authoritativeViewEstablished,
            boolean authoritativeForegroundEstablished,
            boolean foregroundCoverageIncomplete
    ) {
        return currentWorldTlasPresent
                || !authoritativeViewEstablished
                || (authoritativeForegroundEstablished && !foregroundCoverageIncomplete);
    }

    static boolean shouldEvaluateStableFramePath(boolean terrainWork) {
        return !terrainWork;
    }

    static boolean shouldApplyStableFrameSubmissionBackpressure(
            boolean hasSubmissionCapacity,
            boolean pendingFrame,
            long pendingFrameAgeMillis,
            long maxPendingFrameAgeBeforeBuildMillis
    ) {
        if (pendingFrameAgeMillis < 0L || maxPendingFrameAgeBeforeBuildMillis < 0L) {
            throw new IllegalArgumentException("pending frame ages must not be negative");
        }
        return !hasSubmissionCapacity
                && pendingFrame
                && pendingFrameAgeMillis < maxPendingFrameAgeBeforeBuildMillis;
    }

    static boolean shouldDispatchBeforeBuildBudget(RendererFrameUpdate update, RtSceneReadiness readiness) {
        Objects.requireNonNull(update, "update");
        Objects.requireNonNull(readiness, "readiness");
        RendererFrameCommitPlan commitPlan = update.commitPlan();
        if (shouldPrioritizeSceneConvergence(readiness)) {
            return false;
        }
        if (!commitPlan.hasTerrainWork()) {
            return true;
        }
        SceneUpdateBatch batch = update.batch();
        if (commitPlan.fullResyncRequested()) {
            return false;
        }
        boolean removalOrUnload = !commitPlan.removedSections().isEmpty()
                || !commitPlan.unloadedChunks().isEmpty()
                || batch.hasBatchSectionRemovalSource();
        if (removalOrUnload) {
            return isBoundWorldTlasSafeForForegroundStreaming(update, readiness);
        }
        return !batch.hasBatchBlockMutationSource() || batch.hasBatchChunkStreamingSource();
    }

    static boolean preBuildDispatchRequiresReadiness(RendererFrameUpdate update) {
        Objects.requireNonNull(update, "update");
        RendererFrameCommitPlan commitPlan = update.commitPlan();
        return !commitPlan.fullResyncRequested();
    }

    static boolean shouldPrioritizeSceneConvergence(RtSceneReadiness readiness) {
        Objects.requireNonNull(readiness, "readiness");
        /*
         * Frame traces, section BLAS builds and TLAS builds share one ordered
         * Vulkan queue. A useful committed front may remain visible while its
         * successor builds, but inserting another full-resolution trace ahead
         * of dirty acceleration-structure work turns a millisecond recording
         * into hundreds of milliseconds of fence latency. Keep the queue's
         * resource prefix intact until the immutable world generation is
         * current; stable-frame dispatch resumes immediately afterwards.
         */
        return !readiness.builtRevisionIsCurrent() || readiness.hasPendingRtBuilds();
    }

    static boolean isBoundWorldTlasSafeForForegroundStreaming(
            RendererFrameUpdate update,
            RtSceneReadiness readiness
    ) {
        Objects.requireNonNull(update, "update");
        Objects.requireNonNull(readiness, "readiness");
        return readiness.isWorldTlasDispatchUseful()
                && !update.backlogSnapshot().hasUnaccountedRtCoverageGap(
                readiness.builtSectionInstances(),
                readiness.pendingRtSectionBuilds()
        );
    }

    static boolean isStreamingWorldSceneUpdate(
            RtWorldTlasCache.WorldTlasUpdate update,
            String bindReason,
            long immediateStreamingBindMaxFaces
    ) {
        Objects.requireNonNull(update, "update");
        return isStreamingWorldSceneUpdate(
                update.submittedWithPendingBacklog(),
                bindReason,
                update.materialSnapshot().faceCount(),
                immediateStreamingBindMaxFaces
        );
    }

    static boolean isStreamingWorldSceneUpdate(
            boolean submittedWithPendingBacklog,
            String bindReason,
            int materialFaceCount,
            long immediateStreamingBindMaxFaces
    ) {
        Objects.requireNonNull(bindReason, "bindReason");
        if (submittedWithPendingBacklog || !RtSceneReadiness.READY_REASON.equals(bindReason)) {
            return true;
        }
        if (materialFaceCount < 0 || immediateStreamingBindMaxFaces < 0L) {
            throw new IllegalArgumentException("streaming material limits must not be negative");
        }
        return materialFaceCount > immediateStreamingBindMaxFaces;
    }

    static boolean shouldDeferWorldSceneMaterialUpload(
            boolean forceCurrentWorldTlas,
            boolean streamingWorldSceneUpdate,
            RendererUpdateLoop.BacklogSnapshot rendererBacklog,
            boolean pendingWorldSceneBindPresent,
            long nowNanos,
            long nextStreamingSceneBindNanos,
            long deferredPolls,
            long maxDeferredPolls
    ) {
        Objects.requireNonNull(rendererBacklog, "rendererBacklog");
        if (deferredPolls < 0L || maxDeferredPolls <= 0L) {
            throw new IllegalArgumentException("deferred world-scene limits are invalid");
        }
        if (forceCurrentWorldTlas) {
            return false;
        }
        if (pendingWorldSceneBindPresent) {
            return true;
        }
        if (deferredPolls >= maxDeferredPolls) {
            return false;
        }
        return streamingWorldSceneUpdate && nowNanos < nextStreamingSceneBindNanos
                || rendererBacklog.hasPendingRendererWork();
    }

    static boolean shouldBindStreamingImmediately(
            RtWorldTlasCache.WorldTlasUpdate update,
            long immediateStreamingBindMaxFaces
    ) {
        Objects.requireNonNull(update, "update");
        return update.submittedWithPendingBacklog()
                && update.materialSnapshot().faceCount() <= immediateStreamingBindMaxFaces;
    }

    static boolean isTransformOnlyWorldTlasUpdate(
            RtWorldTlasCache.WorldTlasUpdate update,
            RtAccelerationStructure publishedWorldTlas,
            Set<SectionKey> publishedSectionKeys,
            RtSceneMaterialTable.Snapshot boundMaterialSnapshot
    ) {
        Objects.requireNonNull(update, "update");
        Objects.requireNonNull(publishedSectionKeys, "publishedSectionKeys");
        Objects.requireNonNull(boundMaterialSnapshot, "boundMaterialSnapshot");
        return publishedWorldTlas != null
                && update.previousTopLevelAccelerationStructure() == publishedWorldTlas
                && update.sectionRevision() == update.previousSectionRevision()
                && update.sectionResourceRevision() == update.previousSectionResourceRevision()
                && update.dynamicRevision() > update.previousDynamicRevision()
                && update.dynamicTopologyRevision() == update.previousDynamicTopologyRevision()
                && update.dynamicGeometryRevision() == update.previousDynamicGeometryRevision()
                && update.instanceTopologyHash() == update.previousInstanceTopologyHash()
                && update.instanceCount() == update.previousInstanceCount()
                && update.sectionInstanceCount() == update.previousSectionInstanceCount()
                && update.dynamicInstanceCount() == update.previousDynamicInstanceCount()
                && update.sectionKeys().equals(publishedSectionKeys)
                && update.materialSnapshot().instanceLayoutHash() == boundMaterialSnapshot.instanceLayoutHash()
                && update.materialSnapshot().signature().equals(boundMaterialSnapshot.signature());
    }

}
