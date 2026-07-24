package top.ceroxe.mcvulkanrt.renderer.rt.device;

import top.ceroxe.mcvulkanrt.renderer.RendererFrameCausality;
import top.ceroxe.mcvulkanrt.renderer.RendererRtDiagnostics;
import top.ceroxe.mcvulkanrt.renderer.rt.acceleration.RtDynamicBlasCache;
import top.ceroxe.mcvulkanrt.renderer.rt.acceleration.RtDynamicInstanceSnapshot;
import top.ceroxe.mcvulkanrt.renderer.rt.acceleration.RtDynamicInstanceStats;
import top.ceroxe.mcvulkanrt.renderer.rt.acceleration.RtDynamicTlasCache;
import top.ceroxe.mcvulkanrt.renderer.rt.acceleration.RtWorldTlasCache;
import top.ceroxe.mcvulkanrt.renderer.rt.material.RtSceneMaterialTable;
import top.ceroxe.mcvulkanrt.renderer.rt.pipeline.RtRayTracingPipeline;

import java.util.Objects;

/**
 * Advances the dynamic acceleration-structure lane up to the descriptor-commit boundary.
 *
 * <p>BLAS snapshot selection, TLAS candidate creation, material compatibility and asynchronous
 * upload submission form one scheduling responsibility. Final descriptor publication remains in
 * {@link RtSceneBindCoordinator}, which serializes this lane against world and material commits.</p>
 */
final class RtDynamicSceneBindLane {
    private final RtDynamicBlasCache dynamicBlasCache;
    private final RtDynamicTlasCache dynamicTlasCache;
    private final RtWorldTlasCache worldTlasCache;
    private final RtRayTracingPipeline pipeline;
    private final RtSceneMaterialTable materialTable;
    private final RtDeviceQueueContexts queueContexts;
    private final RtSceneBindCoordinator bindCoordinator;
    private final RendererRtDiagnostics diagnostics;
    private final long maxConvergenceVisualStalenessNanos;

    RtDynamicSceneBindLane(
            RtDynamicBlasCache dynamicBlasCache,
            RtDynamicTlasCache dynamicTlasCache,
            RtWorldTlasCache worldTlasCache,
            RtRayTracingPipeline pipeline,
            RtSceneMaterialTable materialTable,
            RtDeviceQueueContexts queueContexts,
            RtSceneBindCoordinator bindCoordinator,
            RendererRtDiagnostics diagnostics,
            long maxConvergenceVisualStalenessNanos
    ) {
        this.dynamicBlasCache = Objects.requireNonNull(dynamicBlasCache, "dynamicBlasCache");
        this.dynamicTlasCache = Objects.requireNonNull(dynamicTlasCache, "dynamicTlasCache");
        this.worldTlasCache = Objects.requireNonNull(worldTlasCache, "worldTlasCache");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.materialTable = Objects.requireNonNull(materialTable, "materialTable");
        this.queueContexts = Objects.requireNonNull(queueContexts, "queueContexts");
        this.bindCoordinator = Objects.requireNonNull(bindCoordinator, "bindCoordinator");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        if (maxConvergenceVisualStalenessNanos <= 0L) {
            throw new IllegalArgumentException("maximum convergence visual staleness must be positive");
        }
        this.maxConvergenceVisualStalenessNanos = maxConvergenceVisualStalenessNanos;
    }

    void advance(
            boolean descriptorsCanBeUpdated,
            boolean resourceConvergencePending,
            RendererFrameCausality causality
    ) {
        if (!RtWorldSceneConvergencePolicy.shouldRunDynamicTlasScheduler(bindCoordinator.state().hasPendingDynamic())) {
            diagnostics.edges().edgeOnce(
                    "dynamicTlasAdvanceDeferredForDescriptorOwner",
                    "dynamicTlasAdvanceDeferred",
                    "reason=pendingDescriptorTransaction"
            );
            return;
        }
        RtDynamicInstanceStats instanceStats = dynamicBlasCache.snapshotInstanceStats();
        RtDynamicTlasCache.Update update = dynamicTlasCache.pollCompletedCandidate(instanceStats);
        if (update == null) {
            boolean snapshotRequired = resourceConvergencePending
                    ? dynamicTlasCache.snapshotRequired(instanceStats, maxConvergenceVisualStalenessNanos)
                    : dynamicTlasCache.snapshotRequired(instanceStats);
            if (!snapshotRequired) {
                return;
            }
            RtDynamicInstanceSnapshot snapshot = dynamicBlasCache.snapshotInstanceState();
            update = resourceConvergencePending
                    ? dynamicTlasCache.process(snapshot, maxConvergenceVisualStalenessNanos, causality)
                    : dynamicTlasCache.process(snapshot, causality);
        }
        if (update == null) {
            return;
        }
        if (bindCoordinator.publication().worldTlas() != null
                && descriptorsCanBeUpdated
                && !bindCoordinator.state().hasPendingWorld()
                && !bindCoordinator.state().hasPendingMaterial()
                && dynamicBlasCache.materialRevision() == bindCoordinator.publication().dynamicMaterialRevision()) {
            bindCoordinator.bindDynamicTlasForDispatch(
                    update,
                    update.instanceSnapshot().materialRevision(),
                    pipeline,
                    materialTable,
                    dynamicTlasCache,
                    worldTlasCache,
                    diagnostics
            );
            return;
        }
        submitAtomicBindIfNeeded(update, descriptorsCanBeUpdated);
    }

    private void submitAtomicBindIfNeeded(RtDynamicTlasCache.Update update, boolean descriptorsCanBeUpdated) {
        if (bindCoordinator.publication().worldTlas() == null
                || bindCoordinator.state().hasPendingDynamic()
                || bindCoordinator.state().hasPendingWorld()
                || bindCoordinator.state().hasPendingMaterial()
                || !descriptorsCanBeUpdated) {
            diagnostics.edges().edgeOnce(
                    "dynamicTlasAtomicSubmitDeferred:" + update.revision(),
                    "dynamicTlasAtomicSubmitDeferred",
                    "revision=" + update.revision()
                            + ", boundWorld=" + (bindCoordinator.publication().worldTlas() != null)
                            + ", pending=" + bindCoordinator.state().hasPendingDynamic()
                            + ", descriptorTransaction=" + bindCoordinator.state().hasDescriptorTransaction()
                            + ", descriptors=" + descriptorsCanBeUpdated
            );
            return;
        }
        RtScenePublication basePublication = bindCoordinator.publication();
        RtSceneMaterialTable.Snapshot snapshot = worldTlasCache.snapshotBoundTerrainWithDynamic(
                basePublication.materialSnapshot(),
                basePublication.terrainMaterialCount(),
                basePublication.sectionMaterialRevision(),
                update.instanceSnapshot()
        );
        if (snapshot == null || snapshot.sectionCount() <= 0) {
            diagnostics.edges().edgeOnce(
                    "dynamicTlasAtomicSnapshotRejected:" + update.revision(),
                    "dynamicTlasAtomicSnapshotRejected",
                    "revision=" + update.revision()
                            + ", snapshot=" + (snapshot == null ? "null" : snapshot.revision())
                            + ", sections=" + (snapshot == null ? 0 : snapshot.sectionCount())
                            + ", sameBoundMaterial=" + (snapshot != null
                            && canReuseBoundMaterialSnapshot(basePublication.materialSnapshot(), snapshot))
            );
            return;
        }
        if (canReuseBoundMaterialSnapshot(basePublication.materialSnapshot(), snapshot)) {
            bindCoordinator.bindDynamicTlasForDispatch(
                    update,
                    update.instanceSnapshot().materialRevision(),
                    pipeline,
                    materialTable,
                    dynamicTlasCache,
                    worldTlasCache,
                    diagnostics
            );
            diagnostics.edges().edge(
                    "dynamicTlasBoundReusingMaterial",
                    "revision=" + update.revision()
                            + ", materialRevision=" + dynamicBlasCache.materialRevision()
                            + ", layoutHash=0x" + Integer.toHexString(snapshot.instanceLayoutHash())
            );
            return;
        }
        RtSceneMaterialTable.PendingUpload upload = materialTable.submitUploadAsync(
                queueContexts.buildCommands(),
                snapshot,
                RtDescriptorTransactionPolicy.allowInPlaceMaterialUpload(
                        bindCoordinator.publication().worldTlas() != null,
                        pipeline.canUpdateMaterialBuffersInPlace()
                )
        );
        if (upload == null) {
            diagnostics.edges().edgeOnce(
                    "dynamicTlasAtomicUploadDeduplicated:" + update.revision(),
                    "dynamicTlasAtomicUploadDeduplicated",
                    "revision=" + update.revision()
            );
            return;
        }
        bindCoordinator.publishPendingDynamic(new PendingDynamicTlasBind(
                basePublication,
                update,
                snapshot,
                upload,
                update.instanceSnapshot().materialRevision()
        ));
        diagnostics.edges().edge(
                "dynamicTlasAtomicBindSubmitted",
                "revision=" + update.revision()
                        + ", materialRevision=" + snapshot.revision()
                        + ", layoutHash=0x" + Integer.toHexString(snapshot.instanceLayoutHash())
        );
    }

    static boolean canReuseBoundMaterialSnapshot(
            RtSceneMaterialTable.Snapshot boundSnapshot,
            RtSceneMaterialTable.Snapshot candidateSnapshot
    ) {
        Objects.requireNonNull(boundSnapshot, "boundSnapshot");
        Objects.requireNonNull(candidateSnapshot, "candidateSnapshot");
        return candidateSnapshot.sectionCount() > 0
                && candidateSnapshot.instanceLayoutHash() == boundSnapshot.instanceLayoutHash()
                && candidateSnapshot.signature().equals(boundSnapshot.signature());
    }
}
