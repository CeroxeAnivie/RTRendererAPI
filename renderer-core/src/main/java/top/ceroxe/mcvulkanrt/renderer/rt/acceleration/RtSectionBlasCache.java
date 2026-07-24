package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import org.lwjgl.vulkan.VkDevice;
import top.ceroxe.mcvulkanrt.renderer.RendererRtDiagnostics;
import top.ceroxe.mcvulkanrt.renderer.RendererForegroundWork;
import top.ceroxe.mcvulkanrt.renderer.RtCausalitySink;
import top.ceroxe.mcvulkanrt.renderer.scene.SceneUpdateBatch;
import top.ceroxe.mcvulkanrt.renderer.RendererViewState;
import top.ceroxe.mcvulkanrt.renderer.RendererFrameCausality;
import top.ceroxe.mcvulkanrt.renderer.SectionCausalitySnapshot;
import top.ceroxe.mcvulkanrt.renderer.SectionRevisionSnapshot;
import top.ceroxe.mcvulkanrt.renderer.rt.device.RtCommandContext;
import top.ceroxe.mcvulkanrt.renderer.rt.runtime.RtCore;
import top.ceroxe.mcvulkanrt.renderer.rt.material.RtMaterialState;
import top.ceroxe.mcvulkanrt.renderer.rt.material.RtSceneMaterialTable;
import top.ceroxe.mcvulkanrt.renderer.SectionLifecycleFlightRecorder;
import top.ceroxe.mcvulkanrt.renderer.orchestration.work.SectionWorkLane;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;
import top.ceroxe.mcvulkanrt.renderer.scene.PackedSectionMembership;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionTriangleMesh;
import top.ceroxe.mcvulkanrt.renderer.rt.material.RtTextureCatalog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Renderer section mesh 到 Vulkan BLAS 的第一层生产缓存。
 *
 * <p>这个缓存只持有已经 build 完成的 BLAS storage。vertex/index upload buffer 和 scratch
 * buffer 都是构建过程临时资源，会在同步提交完成后立即释放。入站 mesh 先进入有预算的
 * pending queue：世界初次加载可能一次带来数千个 section，直接在 render thread 上同步
 * build 会造成秒级卡死；分帧推进可以让当前同步 Vulkan 提交流程保持简单，同时把最危险的
 * stall 面积压到一个明确、可调的预算边界内。</p>
 */
public final class RtSectionBlasCache implements AutoCloseable {
    /*
     * Source lookahead admits work before it becomes the next immutable
     * successor. Once that work is promoted, the bounded recording executor
     * cannot merge its already-admitted tiny batches; an 8-section cap then
     * limits visible terrain convergence to roughly one small recording at a
     * time. Keep a bounded preemptible background window large enough to cover
     * one complete 64-section successor while amortizing command recording and
     * Vulkan submission for an RTX-class device.
     * Foreground ownership, adaptive frame limits and the async-capacity
     * reservation remain the hard latency and memory boundaries.
     */
    private static final int FOREGROUND_RECOVERY_MAX_INSPECTIONS_PER_FRAME = 128;
    private static final long FOREGROUND_RECOVERY_DIAGNOSTIC_INTERVAL_NANOS = 1_000_000_000L;
    private final VkDevice device;
    private final long allocator;
    private final RtCommandContext commandContext;
    private final int scratchAlignmentBytes;
    private final RtSectionBlasConfiguration configuration;
    private final int effectiveMaxAsyncBuildsInFlight;
    private final int gpuSubmissionWindow;
    private final RtAdaptiveBuildBudget adaptiveBuildBudget;
    private final RtPendingBlasBuildQueue<RtSectionBlasBuildMetadata> pendingBuilds;
    private final RtPendingBlasBuildOwnership pendingBuildOwnership = new RtPendingBlasBuildOwnership();
    private final RtSectionBlasResidentStore residentStore = new RtSectionBlasResidentStore();
    private final RtSectionMaterialReuseCache materialReuseCache = new RtSectionMaterialReuseCache();
    private final RtSectionSourceStore sourceStore;
    /* Exact BLAS slots remain resident after their CPU upload payload is released. */
    /** Requested source generation; may be ahead of the BLAS currently bound in a TLAS. */
    private final RtSectionBuildIntentState buildIntents = new RtSectionBuildIntentState();
    /** Revision and causality represented by each live BLAS generation. */
    private final RtActiveSectionContentState activeContentState = new RtActiveSectionContentState();
    private final RtSectionMaterialPublicationState materials;
    /*
     * These consumers observe different availability contracts.  Warm-up
     * chooses source meshes that still need an exact BLAS, while the active
     * TLAS can only admit completed BLASes.  Sharing one revision-keyed plan
     * made the two callers evict each other's immutable snapshot every frame,
     * which recreated the complete Set/sort pipeline in otherwise stable
     * scenes.  Keep the plans independent, like UE's persistent primitive
     * registration versus per-view RT instance consumption.
     */
    private final RtSectionInstanceAdmission warmupInstanceAdmission;
    private final RtSectionInstanceAdmission activeInstanceAdmission;
    private final boolean farFieldProxyEnabled;
    private final RtFarFieldBlasCache farFieldBlasCache;
    private final RtFarFieldBlasCache.RevisionSink farFieldRevisionSink =
            new RtFarFieldBlasCache.RevisionSink() {
                @Override
                public long advanceResourceRevision() {
                    return RtSectionBlasCache.this.advanceResourceRevision();
                }

                @Override
                public void advanceMaterialRevision() {
                    materials.advanceExternalRevision();
                }

                @Override
                public long currentResourceRevision() {
                    return revisions.geometry();
                }

                @Override
                public long currentSceneRevision() {
                    return revisions.scene();
                }
            };
    private final RtSectionAsyncBuildInventory asyncBuildInventory = new RtSectionAsyncBuildInventory();
    /*
     * Ownership is a diagnostic/readiness publication, not mutable scheduler
     * state. Keep its immutable seven-set snapshot until one owning lifecycle
     * generation advances; repeated frame diagnostics then remain O(1) and
     * allocation-free without exposing any backing map or queue view.
     */
    private final RtSectionTerrainOwnershipPublisher terrainOwnershipPublisher;
    private final RtSectionBlasTelemetry telemetry;
    private final RtSectionBlasStatistics statistics = new RtSectionBlasStatistics();
    private final RtSectionAsyncWorkerTelemetry asyncWorkerTelemetry =
            new RtSectionAsyncWorkerTelemetry(statistics);
    private final RtSectionForegroundState foregroundState = new RtSectionForegroundState();
    /*
     * A zero-triangle successor is a topology result, not an authoritative
     * host removal. Keep the committed BLAS alive until presentation
     * releases that generation; otherwise one empty successor can punch a hole
     * in every mixed TLAS built while the atomic successor is still incomplete.
     */
    private final RtDeferredEmptySectionState deferredEmptySections = new RtDeferredEmptySectionState();
    /** Interactive topology edits preempt streaming without bypassing global memory limits. */
    private final RtSectionBuildPriorityState buildPriorityState = new RtSectionBuildPriorityState();
    private final RtSectionForegroundBuildLedger foregroundBuildLedger =
            new RtSectionForegroundBuildLedger();
    private final RtSectionLifecycleMembershipState lifecycleMemberships =
            new RtSectionLifecycleMembershipState();
    private final RtSectionActiveViewAssembler activeViewAssembler = new RtSectionActiveViewAssembler();
    private final RtSectionActiveViewCache activeViewCache = new RtSectionActiveViewCache();
    private final RtSectionTlasBuildInputCache tlasBuildInputCache = new RtSectionTlasBuildInputCache();
    private final RtSectionSceneRevisionState revisions = new RtSectionSceneRevisionState();
    private final RtSectionActiveViewTelemetry activeViewTelemetry;
    private final RtSectionCoverageProof activeViewForegroundCoverage = new RtSectionCoverageProof();
    private final RendererRtDiagnostics diagnostics;
    private final RtFirstFrontBlasProgressTracker firstFrontProgress;
    /**
     * Optional JFR evidence for the native BLAS ownership chain.  This is a
     * sink only: the cache and retirement queue remain the resource owners.
     */
    private final RtSectionBlasLifecycleFlightRecorder blasLifecycleFlightRecorder =
            new RtSectionBlasLifecycleFlightRecorder();
    private final RtSectionBlasSchedulerFlightRecorder schedulerFlightRecorder =
            new RtSectionBlasSchedulerFlightRecorder();
    private final RtSectionBlasRetirementLifecycle retirementLifecycle =
            new RtSectionBlasRetirementLifecycle(blasLifecycleFlightRecorder);
    private boolean closed;
    private long nextForegroundRecoveryDiagnosticNanos;

    public RtSectionBlasCache(
            VkDevice device,
            long allocator,
            RtCommandContext commandContext,
            int scratchAlignmentBytes
    ) {
        this(device, allocator, commandContext, scratchAlignmentBytes, RendererRtDiagnostics.noop());
    }

    public RtSectionBlasCache(
            VkDevice device,
            long allocator,
            RtCommandContext commandContext,
            int scratchAlignmentBytes,
            RendererRtDiagnostics diagnostics
    ) {
        this(
                device,
                allocator,
                commandContext,
                scratchAlignmentBytes,
                RtSectionBlasConfiguration.fromSystemProperties(),
                diagnostics
        );
    }

    RtSectionBlasCache(
            VkDevice device,
            long allocator,
            RtCommandContext commandContext,
            int scratchAlignmentBytes,
            int maxBuildsPerFrame,
            long maxTrianglesPerFrame,
            int maxPendingSections,
            long maxPendingBytes,
            int maxCachedSections,
            long maxCachedBytes
    ) {
        this(
                device,
                allocator,
                commandContext,
                scratchAlignmentBytes,
                RtSectionBlasConfiguration.explicit(
                        maxBuildsPerFrame,
                        maxTrianglesPerFrame,
                        maxPendingSections,
                        maxPendingBytes,
                        maxCachedSections,
                        maxCachedBytes
                ),
                RendererRtDiagnostics.noop()
        );
    }

    RtSectionBlasCache(
            VkDevice device,
            long allocator,
            RtCommandContext commandContext,
            int scratchAlignmentBytes,
            int maxBuildsPerFrame,
            long maxTrianglesPerFrame,
            int maxAsyncBuildsInFlight,
            int maxAsyncBuildSectionsInFlight,
            long maxAsyncBuildBytesInFlight,
            int maxPendingSections,
            long maxPendingBytes,
            int maxCachedSections,
            long maxCachedBytes
    ) {
        this(
                device,
                allocator,
                commandContext,
                scratchAlignmentBytes,
                RtSectionBlasConfiguration.explicit(
                        maxBuildsPerFrame,
                        maxTrianglesPerFrame,
                        maxAsyncBuildsInFlight,
                        maxAsyncBuildSectionsInFlight,
                        maxAsyncBuildBytesInFlight,
                        maxPendingSections,
                        maxPendingBytes,
                        maxCachedSections,
                        maxCachedBytes
                ),
                RendererRtDiagnostics.noop()
        );
    }

    private RtSectionBlasCache(
            VkDevice device,
            long allocator,
            RtCommandContext commandContext,
            int scratchAlignmentBytes,
            RtSectionBlasConfiguration configuration,
            RendererRtDiagnostics diagnostics
    ) {
        this.device = Objects.requireNonNull(device, "device");
        if (allocator == 0L) {
            throw new IllegalArgumentException("allocator must not be null");
        }
        this.allocator = allocator;
        this.commandContext = Objects.requireNonNull(commandContext, "commandContext");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.farFieldProxyEnabled = configuration.farFieldProxyEnabled();
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.firstFrontProgress = new RtFirstFrontBlasProgressTracker(this.diagnostics);
        this.telemetry = new RtSectionBlasTelemetry(diagnostics.builds());
        this.activeViewTelemetry = new RtSectionActiveViewTelemetry(diagnostics.builds());
        this.materials = new RtSectionMaterialPublicationState(diagnostics.materials());
        if (scratchAlignmentBytes <= 0) {
            throw new IllegalArgumentException("scratchAlignmentBytes must be positive");
        }
        this.scratchAlignmentBytes = scratchAlignmentBytes;
        this.effectiveMaxAsyncBuildsInFlight =
                configuration.effectiveMaxAsyncBuildsInFlight(commandContext.orderedQueueCount());
        this.gpuSubmissionWindow = configuration.gpuSubmissionWindow(commandContext.orderedQueueCount());
        this.adaptiveBuildBudget = configuration.adaptiveBuildBudget();
        this.pendingBuilds = new RtPendingBlasBuildQueue<>(
                configuration.maxPendingSections(),
                configuration.maxPendingBytes()
        );
        this.sourceStore = new RtSectionSourceStore(configuration.maxCachedSourceMeshBytes());
        this.terrainOwnershipPublisher = new RtSectionTerrainOwnershipPublisher(
                sourceStore,
                pendingBuilds,
                asyncBuildInventory,
                lifecycleMemberships,
                foregroundState
        );
        this.warmupInstanceAdmission = new RtSectionInstanceAdmission(
                configuration.maxViewInstances(),
                configuration.maxFarFieldInstances(),
                configuration.viewInstanceRetentionMargin(),
                false,
                false,
                false,
                farFieldProxyEnabled
        );
        this.activeInstanceAdmission = new RtSectionInstanceAdmission(
                configuration.maxViewInstances(),
                configuration.maxFarFieldInstances(),
                configuration.viewInstanceRetentionMargin(),
                true,
                false,
                false,
                farFieldProxyEnabled
        );
        this.farFieldBlasCache = new RtFarFieldBlasCache(
                device,
                allocator,
                commandContext,
                scratchAlignmentBytes,
                diagnostics.materials()
        );
    }

    public synchronized void enqueue(
            Collection<SectionTriangleMesh> builtMeshes,
            Set<SectionKey> removedSectionKeys,
            boolean fullResyncRequested
    ) {
        LinkedHashMap<SectionKey, SectionTriangleMesh> meshesByKey = new LinkedHashMap<>();
        for (SectionTriangleMesh mesh : builtMeshes) {
            meshesByKey.put(mesh.key(), mesh);
        }
        enqueue(meshesByKey, removedSectionKeys, fullResyncRequested);
    }

    public synchronized void enqueue(
            Map<SectionKey, SectionTriangleMesh> builtMeshes,
            Set<SectionKey> removedSectionKeys,
            boolean fullResyncRequested
    ) {
        enqueue(builtMeshes, Map.of(), removedSectionKeys, fullResyncRequested);
    }

    public synchronized void enqueue(
            Map<SectionKey, SectionTriangleMesh> builtMeshes,
            Map<SectionKey, Long> contentRevisions,
            Set<SectionKey> removedSectionKeys,
            boolean fullResyncRequested
    ) {
        enqueue(builtMeshes, contentRevisions, Map.of(), removedSectionKeys, fullResyncRequested);
    }

    public synchronized void enqueue(
            Map<SectionKey, SectionTriangleMesh> builtMeshes,
            Map<SectionKey, Long> contentRevisions,
            Map<SectionKey, Integer> sourceFlags,
            Set<SectionKey> removedSectionKeys,
            boolean fullResyncRequested
    ) {
        enqueue(
                builtMeshes,
                contentRevisions,
                sourceFlags,
                removedSectionKeys,
                fullResyncRequested,
                RendererFrameCausality.untraced(0L)
        );
    }

    public synchronized void enqueue(
            Map<SectionKey, SectionTriangleMesh> builtMeshes,
            Map<SectionKey, Long> contentRevisions,
            Map<SectionKey, Integer> sourceFlags,
            Set<SectionKey> removedSectionKeys,
            boolean fullResyncRequested,
            RendererFrameCausality causality
    ) {
        Objects.requireNonNull(causality, "causality");
        boolean profileEnqueue = diagnostics.edges().enabled();
        long enqueueStartNanos = profileEnqueue ? System.nanoTime() : 0L;
        long lifecycleNanos = 0L;
        long priorityNanos = 0L;
        long sourceBookkeepingNanos = 0L;
        long materialCompatibilityNanos = 0L;
        long queueAdmissionNanos = 0L;
        int materialOnlyUpdates = 0;
        int queuedGeometryUpdates = 0;
        Objects.requireNonNull(builtMeshes, "builtMeshes");
        Objects.requireNonNull(contentRevisions, "contentRevisions");
        Objects.requireNonNull(sourceFlags, "sourceFlags");
        Objects.requireNonNull(removedSectionKeys, "removedSectionKeys");
        if (!contentRevisions.isEmpty() && !contentRevisions.keySet().equals(builtMeshes.keySet())) {
            throw new IllegalArgumentException("section content revisions must exactly cover submitted meshes");
        }
        if (!builtMeshes.keySet().containsAll(sourceFlags.keySet())) {
            throw new IllegalArgumentException("section source flags require matching submitted meshes");
        }
        if (closed) {
            throw new IllegalStateException("RT section BLAS cache is already closed");
        }

        long stageStartNanos = profileEnqueue ? System.nanoTime() : 0L;
        if (fullResyncRequested) {
            boolean hadCompletedBlases = !residentStore.isEmpty();
            invalidatePendingAsyncBuilds();
            pendingBuilds.clear();
            farFieldBlasCache.clear(revisionSink());
            long safeAfterRevision = advanceResourceRevision();
            retireAllSections(safeAfterRevision);
            residentStore.clearAfterExternalRelease();
            if (hadCompletedBlases) {
                lifecycleMemberships.clearActive();
            }
            activeViewAssembler.clear();
            activeViewCache.clear();
            materialReuseCache.clear();
            sourceStore.clear();
            deferredEmptySections.clear();
            buildPriorityState.clear();
            lifecycleMemberships.clearResidents();
            buildIntents.clear();
            activeContentState.clear();
            tlasBuildInputCache.invalidate();
            foregroundBuildLedger.clear();
            firstFrontProgress.reset();
            materials.clearAndAdvance();
            statistics.fullResyncClear();
        } else {
            removeSections(removedSectionKeys);
        }
        if (profileEnqueue) {
            lifecycleNanos += System.nanoTime() - stageStartNanos;
            stageStartNanos = System.nanoTime();
        }

        buildPriorityState.admitProvisional(
                !foregroundState.authority().isEmpty(),
                builtMeshes.keySet()
        );
        pendingBuilds.classify(buildPriorityState.interactiveKeys(), buildPriorityState.preferredKeys());
        if (profileEnqueue) {
            priorityNanos += System.nanoTime() - stageStartNanos;
        }

        for (SectionTriangleMesh mesh : builtMeshes.values()) {
            if (profileEnqueue) {
                stageStartNanos = System.nanoTime();
            }
            SectionTriangleMesh previousSource = sourceStore.mesh(mesh.key());
            int meshSourceFlags = sourceFlags.getOrDefault(mesh.key(), 0);
            RtSectionBuildIntentState.Intent buildIntent = buildIntents.publish(
                    mesh.key(),
                    contentRevisions.get(mesh.key()),
                    causality,
                    meshSourceFlags
            );
            if (RtDeferredEmptySectionState.interactiveTopologySource(meshSourceFlags)) {
                buildPriorityState.markInteractive(mesh.key());
            }
            firstFrontProgress.recordLifecycle(
                    "cpuGeneration",
                    mesh.key(),
                    -1L,
                    buildIntent.contentRevision(),
                    activeContentState.revisionOrDefault(mesh.key(), -1L),
                    buildIntent.sourceFlags(),
                    residentStore.contains(mesh.key()),
                    previousSource != null && baseGeometryMatches(previousSource, mesh),
                    false
            );
            diagnostics.causality().firstFrontSection(
                    "blasCpuGeneration",
                    mesh.key(),
                    buildIntent.contentRevision(),
                    "triangles=" + mesh.triangleCount()
            );
            diagnostics.causality().section(
                    RtCausalitySink.Stage.BLAS_GENERATION,
                    mesh.key(),
                    buildIntent.contentRevision(),
                    -1L,
                    mesh.triangleCount(),
                    buildIntent.sourceFlags()
            );
            SectionLifecycleFlightRecorder.record(
                    SectionLifecycleFlightRecorder.STAGE_BLAS_ENQUEUE,
                    SectionLifecycleFlightRecorder.SOURCE_UNKNOWN,
                    SectionLifecycleFlightRecorder.OUTCOME_ACCEPTED,
                    mesh.key(), 0, -1L,
                    buildIntent.contentRevision(),
                    activeContentState.revisionOrDefault(mesh.key(), -1L),
                    buildIntent.sourceFlags(),
                    pendingBuilds.size(), mesh.triangleCount()
            );
            if (profileEnqueue) {
                sourceBookkeepingNanos += System.nanoTime() - stageStartNanos;
                stageStartNanos = System.nanoTime();
            }
            if (updateMaterialForCompatibleResidentGeometry(mesh)) {
                materialOnlyUpdates++;
                if (profileEnqueue) {
                    materialCompatibilityNanos += System.nanoTime() - stageStartNanos;
                }
                continue;
            }
            if (profileEnqueue) {
                materialCompatibilityNanos += System.nanoTime() - stageStartNanos;
                stageStartNanos = System.nanoTime();
            }
            enqueueOrRemoveEmptyMesh(mesh);
            queuedGeometryUpdates++;
            if (profileEnqueue) {
                queueAdmissionNanos += System.nanoTime() - stageStartNanos;
            }
        }

        reconcileRequiredForegroundWork();
        trimSourceMeshesUntilWithinBudget(null);

        if (fullResyncRequested || !builtMeshes.isEmpty() || !removedSectionKeys.isEmpty()) {
            statistics.appliedBatch();
        }
        if (profileEnqueue) {
            telemetry.recordEnqueue(
                    System.nanoTime() - enqueueStartNanos,
                    lifecycleNanos,
                    priorityNanos,
                    sourceBookkeepingNanos,
                    materialCompatibilityNanos,
                    queueAdmissionNanos,
                    builtMeshes.size(),
                    removedSectionKeys.size(),
                    materialOnlyUpdates,
                    queuedGeometryUpdates,
                    fullResyncRequested
            );
        }
    }

    public synchronized void acceptViewState(RendererViewState nextViewState) {
        acceptViewState(nextViewState, Set.of());
    }

    public synchronized void acceptViewState(
            RendererViewState nextViewState,
            Set<SectionKey> nextRetainedPresentationSectionKeys
    ) {
        acceptForegroundWork(RendererForegroundWork.untraced(
                nextViewState,
                nextRetainedPresentationSectionKeys
        ));
    }

    public synchronized void acceptForegroundWork(RendererForegroundWork nextForegroundWork) {
        RtSectionForegroundState.Transition transition = foregroundState.accept(nextForegroundWork);
        if (!transition.changed()) {
            return;
        }
        releaseUnretainedEmptySections();
        if (!transition.reconciliationRequired()) {
            return;
        }
        RendererViewState nextViewState = foregroundState.view();
        PackedSectionMembership authority = foregroundState.authority();
        reconcileRequiredForegroundWork();
        reconcileActiveSlotsForAuthoritativeForeground();
        for (SectionKey key : authority) {
            firstFrontProgress.recordLifecycle(
                    "viewAuthority",
                    key,
                    nextViewState.revision(),
                    buildIntents.revisionOrDefault(key, -1L),
                    activeContentState.revisionOrDefault(key, -1L),
                    buildIntents.sourceFlagsOrDefault(key, 0),
                    residentStore.contains(key),
                    sourceStore.mesh(key) != null,
                    false
            );
        }
        buildPriorityState.publishAuthority(authority);
        pendingBuilds.classify(buildPriorityState.interactiveKeys(), buildPriorityState.preferredKeys());
        if (!buildPriorityState.preferredKeys().isEmpty()) {
            asyncBuildInventory.prioritizeRecordings(nextViewState.revision(), buildPriorityState.preferredKeys());
            asyncBuildInventory.prioritizeGpuBuilds(buildPriorityState.preferredKeys());
        }
        if (nextViewState.authoritative() && sourceStore.payloadCount() != 0) {
            activeViewTelemetry.warmupPlanInvoked();
            RtSectionInstanceAdmission.Admission desiredAdmission = warmupInstanceAdmission.plan(
                    nextViewState,
                    sourceStore.membership(),
                    sourceStore.membership(),
                    sourceStore.membershipRevision(),
                    sourceStore.membershipRevision()
            );
            for (SectionKey key : desiredAdmission.baseSections()) {
                if (!residentStore.contains(key)) {
                    SectionTriangleMesh sourceMesh = sourceStore.mesh(key);
                    if (sourceMesh != null) {
                        enqueuePendingBuildIfUnowned(sourceMesh);
                    }
                }
            }
        }
    }

    /**
     * Reserves dense active slots for the immutable successor before its missing BLAS work runs.
     *
     * <p>The descriptor-visible front owns its Vulkan resources through the TLAS/descriptor
     * retirement fence; it must not also occupy the successor's bounded active-slot namespace.
     * Waiting until a newly completed BLAS makes the cache exceed capacity creates a circular
     * dependency at a full window: foreground recordings need slots, while every slot remains
     * attributed to the previous front. Release exactly the number of non-successor slots needed
     * by missing successor members, preserving all remaining reusable BLAS entries.</p>
     */
    private void reconcileActiveSlotsForAuthoritativeForeground() {
        PackedSectionMembership authority = foregroundState.authority();
        if (authority.isEmpty() || residentStore.isEmpty()) {
            return;
        }
        int missingForegroundSections = 0;
        for (SectionKey key : authority) {
            if (!residentStore.contains(key)) {
                missingForegroundSections++;
            }
        }
        int slotsToRelease = activeSlotsToReleaseForSuccessor(
                configuration.configuredMaxCachedSections(),
                residentStore.size(),
                missingForegroundSections
        );
        if (slotsToRelease == 0) {
            return;
        }

        while (slotsToRelease > 0) {
            SectionKey key = residentStore.firstOutside(authority);
            if (key == null) {
                break;
            }
            RtAccelerationStructure retired = Objects.requireNonNull(
                    residentStore.get(key),
                    "selected foreground-reservation BLAS"
            );
            long nextGeometryRevision = revisions.nextGeometry();
            diagnostics.causality().section(
                    RtCausalitySink.Stage.BLAS_REMOVED,
                    key,
                    activeContentState.revisionOrDefault(key, -1L),
                    nextGeometryRevision,
                    retired.storageBytes(),
                    3
            );
            retireSectionBlas(
                    key,
                    nextGeometryRevision,
                    retired,
                    RtSectionBlasRetirementLifecycle.Reason.SUCCESSOR_RESERVATION
            );
            RtSectionBlasResidentStore.Removal removal = residentStore.remove(key);
            if (removal.blas() != retired) {
                throw new IllegalStateException("reserved section BLAS changed before resident removal: " + key);
            }
            lifecycleMemberships.removeActive(key);
            activeViewAssembler.removeSection(key);
            tombstoneMaterialSlot(key);
            removeActiveSectionContent(key);
            removeUnretainedSourcePublication(key);
            statistics.removedSection(true);
            revisions.commitGeometry(nextGeometryRevision);
            publishGeometryTopologyRevision();
            slotsToRelease--;
        }
        if (slotsToRelease != 0) {
            throw new IllegalStateException(
                    "authoritative foreground cannot reserve active BLAS slots: missing="
                            + missingForegroundSections
                            + ", active=" + residentStore.size()
                            + ", authority=" + authority.size()
                            + ", unreleased=" + slotsToRelease
            );
        }
    }

    public synchronized void processFrameBudget() {
        processFrameBudget(Long.MAX_VALUE, false);
    }

    public synchronized void processFrameBudget(long maxElapsedNanos) {
        processFrameBudget(maxElapsedNanos, false);
    }

    public synchronized void processFrameBudget(long maxElapsedNanos, boolean firstWorldFrontPending) {
        if (closed) {
            throw new IllegalStateException("RT section BLAS cache is already closed");
        }
        if (maxElapsedNanos <= 0L) {
            throw new IllegalArgumentException("maxElapsedNanos must be positive");
        }

        long passStartNanos = System.nanoTime();
        schedulerFlightRecorder.nextPass();
        long stageStartNanos = passStartNanos;
        recoverRequiredForegroundWork();
        pollPendingAsyncBuilds(passStartNanos, maxElapsedNanos, firstWorldFrontPending, false);
        telemetry.gpuPollApply(elapsedMicros(stageStartNanos));
        stageStartNanos = System.nanoTime();
        pollPendingAsyncRecordings(passStartNanos, maxElapsedNanos, firstWorldFrontPending);
        telemetry.recordingPoll(elapsedMicros(stageStartNanos));

        stageStartNanos = System.nanoTime();
        boolean foregroundCoverageIncomplete = hasIncompleteForegroundCoverage();
        boolean foregroundPendingAtPassStart = hasPendingForegroundBuilds();
        schedulerFlightRecorder.record(
                "pass",
                pendingBuilds.size(),
                asyncBuildInventory,
                foregroundPendingAtPassStart,
                foregroundCoverageIncomplete,
                firstWorldFrontPending,
                maxElapsedNanos,
                passStartNanos
        );
        RtAdaptiveBuildBudget.Limits limits = RtSectionBlasAdmissionPlanner.foregroundBootstrapLimits(
                adaptiveBuildBudget.currentLimits(),
                configuration.maxBuildsPerFrame(),
                configuration.maxTrianglesPerFrame()
        );
        telemetry.coverageLimits(elapsedMicros(stageStartNanos));
        int submittedInPass = 0;
        long trianglesSubmittedInPass = 0L;
        while (!pendingBuilds.isEmpty()
                && submittedInPass < limits.maxBuilds()
                && withinFrameBudget(passStartNanos, maxElapsedNanos, submittedInPass)) {
            boolean foregroundPending = hasPendingForegroundBuilds();
            stageStartNanos = System.nanoTime();
            if (!asyncBuildCapacityAvailable(foregroundPending)) {
                schedulerFlightRecorder.record(
                        "asyncCapacityFull",
                        pendingBuilds.size(),
                        asyncBuildInventory,
                        foregroundPending,
                        foregroundCoverageIncomplete,
                        firstWorldFrontPending,
                        maxElapsedNanos,
                        passStartNanos
                );
                telemetry.capacity(elapsedMicros(stageStartNanos));
                if (!foregroundPending) {
                    statistics.backgroundReservationDeferred();
                }
                break;
            }
            telemetry.capacity(elapsedMicros(stageStartNanos));
            stageStartNanos = System.nanoTime();
            List<RtPendingBlasBuildQueue.Work<RtSectionBlasBuildMetadata>> buildBatch = drainBuildBatch(
                    limits,
                    submittedInPass,
                    trianglesSubmittedInPass,
                    buildPriorityState.interactiveKeys(),
                    buildPriorityState.preferredKeys()
            );
            telemetry.drain(elapsedMicros(stageStartNanos));
            if (buildBatch.isEmpty()) {
                schedulerFlightRecorder.record(
                        "drainEmpty",
                        pendingBuilds.size(),
                        asyncBuildInventory,
                        foregroundPending,
                        foregroundCoverageIncomplete,
                        firstWorldFrontPending,
                        maxElapsedNanos,
                        passStartNanos
                );
                break;
            }
            stageStartNanos = System.nanoTime();
            List<RtPendingBlasBuildQueue.Work<RtSectionBlasBuildMetadata>> submittedWorks =
                    submitAsyncBuildBatch(buildBatch, foregroundPending);
            schedulerFlightRecorder.record(
                    submittedWorks.isEmpty() ? "recordingClaimLost" : "recordingQueued",
                    pendingBuilds.size(),
                    asyncBuildInventory,
                    foregroundPending,
                    foregroundCoverageIncomplete,
                    firstWorldFrontPending,
                    maxElapsedNanos,
                    passStartNanos
            );
            telemetry.recordingEnqueue(elapsedMicros(stageStartNanos));
            submittedInPass += submittedWorks.size();
            for (RtPendingBlasBuildQueue.Work<RtSectionBlasBuildMetadata> work : submittedWorks) {
                trianglesSubmittedInPass += work.mesh().triangleCount();
            }
        }
        stageStartNanos = System.nanoTime();
        if (submittedInPass == 0 && !asyncBuildInventory.hasGpuBuilds()) {
            adaptiveBuildBudget.recordIdle(hasAnyPendingSectionBuilds());
        }

        boolean pendingAfterPass = !pendingBuilds.isEmpty();
        statistics.frameBudgetPass(
                submittedInPass > 0,
                pendingAfterPass && !asyncBuildCapacityAvailable(hasPendingForegroundBuilds()),
                pendingAfterPass
        );
        telemetry.bookkeeping(elapsedMicros(stageStartNanos));

        long elapsedNanos = System.nanoTime() - passStartNanos;
        if (farFieldProxyEnabled && shouldProcessFarFieldBuilds(
                maxElapsedNanos,
                elapsedNanos,
                hasAnyPendingSectionBuilds()
        )) {
            stageStartNanos = System.nanoTime();
            /*
             * Exact BLAS completion above intentionally releases its Java source
             * mesh. Refresh admission before consuming the FarField queue so a
             * section promoted to persistent Base ownership cannot remain in a
             * stale proxy cell that still expects the released source. The later
             * TLAS snapshot observes the same cached ActiveView instance, so this
             * advances ordering without adding a second rebuild.
            */
            activeViewSnapshot();
            long farFieldElapsedNanos = System.nanoTime() - passStartNanos;
            long farFieldBudgetNanos = maxElapsedNanos == Long.MAX_VALUE
                    ? Long.MAX_VALUE
                    : maxElapsedNanos - farFieldElapsedNanos;
            if (farFieldBudgetNanos > 0L) {
                farFieldBlasCache.processFrameBudget(
                        sourceStore.publicationsView(),
                        revisionSink(),
                        farFieldBudgetNanos
                );
            }
            telemetry.farField(elapsedMicros(stageStartNanos));
        }
        telemetry.completePass(elapsedMicros(passStartNanos));
    }

    /**
     * Advances only work that has already crossed the Vulkan submission boundary.
     *
     * <p>This deliberately sits outside {@link #processFrameBudget(long, boolean)} because
     * completed native work owns one slot in the ordered submission window until its results
     * are installed and retired. Frame presentation pacing may defer <em>new</em> recording
     * and submission, but it must never defer that retirement indefinitely: doing so leaves an
     * already-completed fence occupying the one-page Vulkan window and prevents terrain
     * convergence despite an idle GPU.</p>
     *
     * <p>The method performs no CPU recording, no queue submission and no far-field admission.
     * It is intentionally unbudgeted only for results already known complete; the ordered window
     * bounds this maintenance work to the configured number of native queue submissions.</p>
     */
    public synchronized void pumpCompletedAsyncBuilds() {
        if (closed) {
            throw new IllegalStateException("RT section BLAS cache is already closed");
        }
        if (!asyncBuildInventory.hasGpuBuilds()) {
            return;
        }

        long passStartNanos = System.nanoTime();
        schedulerFlightRecorder.nextPass();
        pollPendingAsyncBuilds(passStartNanos, Long.MAX_VALUE, false, true);
        schedulerFlightRecorder.record(
                "completionPump",
                pendingBuilds.size(),
                asyncBuildInventory,
                hasPendingForegroundBuilds(),
                hasIncompleteForegroundCoverage(),
                false,
                Long.MAX_VALUE,
                passStartNanos
        );
    }

    private static long elapsedMicros(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000L;
    }

    public static boolean shouldProcessFarFieldBuilds(
            long maxElapsedNanos,
            long elapsedNanos,
            boolean foregroundBuildsPending
    ) {
        if (maxElapsedNanos <= 0L || elapsedNanos < 0L) {
            throw new IllegalArgumentException("frame budget must be positive and elapsed time must not be negative");
        }
        return !foregroundBuildsPending
                && (maxElapsedNanos == Long.MAX_VALUE || elapsedNanos < maxElapsedNanos);
    }

    private static boolean withinFrameBudget(long passStartNanos, long maxElapsedNanos, int submittedInPass) {
        /*
         * Always allow one tiny submission attempt so an already prepared queue
         * can make forward progress. After that, honour the caller's foreground
         * budget instead of turning camera movement into a render-thread build
         * pump.
         */
        return submittedInPass == 0 || System.nanoTime() - passStartNanos < maxElapsedNanos;
    }

    public synchronized String summary(String name) {
        /*
         * Diagnostics observe the last renderer-owned publication. They must
         * never trigger admission, FarField reconciliation, sorting, or scene
         * revision changes merely because a log line was requested.
        */
        RtSectionActiveViewAssembler.Snapshot activeView = activeViewCache.snapshot();
        RtSectionTlasBuildInputCache.Stats tlasInputStats = tlasBuildInputCache.stats();
        RtSectionAsyncBuildInventory.Metrics asyncMetrics =
                asyncBuildInventory.metrics(buildPriorityState.preferredKeys());
        return name
                + "{cachedSections=" + residentStore.size()
                + ", activeViewSections=" + activeView.coveredSections().size()
                + ", activeBaseInstances=" + activeView.baseEntries().size()
                + ", activeFarFieldInstances=" + activeView.farFieldCells().size()
                + ", uncoveredViewSections=" + currentUncoveredForegroundSections(activeView.coveredSections())
                + ", requestedViewSections=" + foregroundState.view().visibleSectionKeys().size()
                + ", authoritativeView=" + foregroundState.view().authoritative()
                + ", viewRevision=" + foregroundState.view().revision()
                + ", sceneRevision=" + revisions.scene()
                + ", persistentTlasInput={hits=" + tlasInputStats.hits()
                + ", misses=" + tlasInputStats.misses()
                + ", cached=" + tlasInputStats.cached()
                + ", textureRevision=" + tlasInputStats.cachedTextureRevision()
                + ", missReasons={cold=" + tlasInputStats.coldMisses()
                + ",activeView=" + tlasInputStats.activeViewMisses()
                + ",scene=" + tlasInputStats.sceneMisses()
                + ",geometry=" + tlasInputStats.geometryMisses()
                + ",material=" + tlasInputStats.materialMisses()
                + ",texture=" + tlasInputStats.textureMisses()
                + ",pendingBuilds=" + tlasInputStats.pendingBuildMisses()
                + ",pendingTriangles=" + tlasInputStats.pendingTriangleMisses()
                + ",cachedTriangles=" + tlasInputStats.cachedTriangleMisses()
                + ",activeContent=" + tlasInputStats.activeContentMisses() + "}}"
                + ", pendingSections=" + pendingBuilds.size()
                + ", authoritativeForegroundSections=" + foregroundState.authority().size()
                + ", requiredForegroundWork=" + foregroundBuildLedger.size()
                + ", buildPrioritySections=" + buildPriorityState.preferredKeys().size()
                + ", pendingForegroundSections=" + pendingBuilds.preferredCount(buildPriorityState.preferredKeys())
                + ", pendingAsyncBuildBatches=" + asyncMetrics.gpuBatches()
                + ", pendingAsyncBuildRecordings=" + asyncMetrics.recordingBatches()
                + ", pendingAsyncBuildSections=" + asyncMetrics.activeSections()
                + ", pendingForegroundAsyncSections=" + asyncMetrics.preferredSections()
                + ", pendingAsyncBuildTriangles=" + asyncMetrics.activeTriangles()
                + ", pendingAsyncRetainedSections=" + asyncMetrics.retainedSections()
                + ", pendingAsyncRetainedBytes=" + asyncMetrics.retainedBytes()
                + ", " + statistics.summary()
                + ", deferredEmptySectionRemovals=" + deferredEmptySections.size()
                + ", interactivePrioritySections=" + buildPriorityState.interactiveCount()
                + ", cachedTriangles=" + residentStore.cachedTriangles()
                + ", cachedBlasBytes=" + residentStore.cachedBytes()
                + ", sourcePayloads=" + sourceStore.payloadCount()
                + ", cachedSourceMeshBytes=" + sourceStore.payloadBytes()
                + ", materialSlots=" + materials.slotCount()
                + ", activeMaterialSlots=" + materials.activeSlotCount()
                + ", freeMaterialSlots=" + materials.freeSlotCount()
                + ", reusedMaterialSlots=" + materials.reusedSlotAllocations()
                + ", materialFaceCapacity=" + materials.faceCapacity()
                + ", materialFaceFreeRanges=" + materials.freeFaceRangeCount()
                + ", materialFaceFreeCapacity=" + materials.freeFaceCapacity()
                + ", reusedMaterialFaceRanges=" + materials.reusedFaceRangeAllocations()
                + ", movedMaterialFaceRanges=" + materials.movedFaceRangeAllocations()
                + ", tailExtendedMaterialFaceRanges=" + materials.tailExtendedFaceRangeAllocations()
                + ", cachedOverBudget=" + cachedResourcesOverBudget()
                + ", pendingTriangles=" + pendingSectionBuildTriangles()
                + ", pendingBytes=" + pendingSectionBuildBytes()
                + ", retiredSectionBlases=" + retirementLifecycle.size()
                + ", retiredSectionBlasesClosed=" + retirementLifecycle.closedCount()
                + ", retiredSectionBlasBytes=" + retirementLifecycle.retainedBytes()
                + ", peakRetiredSectionBlasBytes=" + retirementLifecycle.peakRetainedBytes()
                + ", asyncWorkerSubmissionMode=recordAndImmediateQueueSubmit"
                + ", geometryRevision=" + revisions.geometry()
                + ", materialRevision=" + materials.revision()
                + ", enqueuedSections=" + pendingBuilds.enqueuedSections()
                + ", replacedPendingSections=" + pendingBuilds.replacedSections()
                + ", cancelledPendingSections=" + pendingBuilds.cancelledSections()
                + ", evictedPendingSections=" + pendingBuilds.evictedSections()
                + ", maxBuildsPerFrame=" + configuration.maxBuildsPerFrame()
                + ", maxTrianglesPerFrame=" + configuration.maxTrianglesPerFrame()
                + ", configuredMaxAsyncBuildsInFlight=" + configuration.configuredMaxAsyncBuildsInFlight()
                + ", effectiveMaxAsyncBuildsInFlight=" + effectiveMaxAsyncBuildsInFlight
                + ", gpuSubmissionWindow=" + gpuSubmissionWindow
                + ", maxAsyncBuildSectionsInFlight=" + configuration.maxAsyncBuildSectionsInFlight()
                + ", maxAsyncBuildBytesInFlight=" + configuration.maxAsyncBuildBytesInFlight()
                + ", foregroundSubmissionLimits=adaptiveFrameBudget"
                + ", adaptiveMaxBuildsPerFrame=" + adaptiveBuildBudget.currentLimits().maxBuilds()
                + ", adaptiveMaxTrianglesPerFrame=" + adaptiveBuildBudget.currentLimits().maxTriangles()
                + ", configuredMaxPendingSections=" + configuration.maxPendingSections()
                + ", effectiveMaxPendingSections=" + pendingBuilds.sectionCapacity()
                + ", maxPendingBytes=" + configuration.maxPendingBytes()
                + ", configuredMaxCachedSections=" + configuration.configuredMaxCachedSections()
                + ", maxCachedBytes=" + configuration.maxCachedBytes()
                + ", maxCachedSourceMeshBytes=" + sourceStore.maxPayloadBytes()
                + ", farFieldProxyEnabled=" + farFieldProxyEnabled
                + ", " + farFieldBlasCache.summary()
                + "}";
    }

    public synchronized NativeTerrainOwnership terrainOwnership(
            PackedSectionMembership boundSectionKeys,
            long boundWorldRevision
    ) {
        return terrainOwnershipPublisher.snapshot(
                boundSectionKeys,
                boundWorldRevision,
                !hasIncompleteForegroundCoverage()
        );
    }

    /**
     * Returns the scalar generation of the native ownership revision vector without freezing any
     * section collections. Transaction admission uses this hot path as a lifetime anchor.
     */
    public synchronized long terrainOwnershipGeneration(long boundWorldRevision) {
        return terrainOwnershipPublisher.generation(boundWorldRevision);
    }

    /**
     * Captures one section's exact cache ownership chain on demand.  The query
     * walks the bounded async inventory instead of maintaining a second lifecycle
     * map which could drift from the native work owner's real state.
     */
    public synchronized RtSectionDebugState snapshotSectionDebugState(SectionKey key) {
        Objects.requireNonNull(key, "key");
        long desiredRevision = buildIntents.revisionOrDefault(key, -1L);
        long activeRevision = activeContentState.revisionOrDefault(key, -1L);
        boolean queued = pendingBuilds.contains(key);
        RtSectionAsyncBuildInventory.DebugState asyncState = asyncBuildInventory.debugState(key);
        RendererFrameCausality causality = buildIntents.causality(key);
        if (causality == null) {
            causality = activeContentState.causality(key);
        }
        if (causality == null) {
            return RtSectionDebugState.absent(key);
        }
        return new RtSectionDebugState(
                key,
                desiredRevision,
                activeRevision,
                sourceStore.geometryGeneration(key),
                sourceStore.materialGeneration(key),
                asyncState.latestSequence(),
                queued,
                asyncState.recording(),
                asyncState.gpu(),
                residentStore.contains(key),
                causality
        );
    }

    public synchronized void releaseRetiredBlasesThrough(
            long protectedResourceRevision,
            long protectedSceneRevision
    ) {
        if (closed) {
            throw new IllegalStateException("RT section BLAS cache is already closed");
        }
        closeRetiredBlasesThrough(protectedResourceRevision);
        farFieldBlasCache.releaseRetiredBlasesThrough(
                protectedResourceRevision,
                protectedSceneRevision
        );
    }

    synchronized RtSectionTlasBuildInput snapshotTlasBuildInput() {
        if (closed) {
            throw new IllegalStateException("RT section BLAS cache is already closed");
        }

        RtSectionActiveViewAssembler.Snapshot activeView = activeViewSnapshot();
        int pendingBuildCount = Math.addExact(
                pendingSectionBuildCount(),
                farFieldBlasCache.pendingDesiredBuilds()
        );
        int uncoveredSections = currentUncoveredForegroundSections(activeView.coveredSections());
        int pendingBuilds = Math.addExact(pendingBuildCount, uncoveredSections);
        long pendingTriangles = pendingSectionBuildTriangles();
        long totalCachedTriangles = Math.addExact(
                residentStore.cachedTriangles(),
                farFieldBlasCache.activeTriangles()
        );
        // Cache validation needs only the monotonic catalog revision. Taking a full
        // snapshot here copies every texture pixel array and turns stable TLAS input
        // checks into a whole-catalog allocation path.
        long textureRevision = RtTextureCatalog.revision();
        RtSectionTlasBuildInputCache.Key cacheKey = new RtSectionTlasBuildInputCache.Key(
                activeView,
                revisions.scene(),
                revisions.geometry(),
                materials.revision(),
                textureRevision,
                activeContentState.generation(),
                pendingBuilds,
                pendingTriangles,
                totalCachedTriangles
        );
        RtSectionTlasBuildInputCache.Lookup lookup = tlasBuildInputCache.probe(cacheKey);
        if (lookup.hit()) {
            return lookup.input();
        }
        List<RtAccelerationStructure.TlasInstance> instances = activeView.instances();
        /*
         * Active-view construction already proves a live material slot for
         * every base entry, and RtMaterialState owns the immutable upload
         * generation below. Re-scanning the parallel diagnostics map here made
         * every animated-texture revision an O(active sections) HashMap gate.
         */
        int instanceLayoutHash = activeView.instanceLayoutHash();
        RtSceneMaterialTable.Snapshot farFieldMaterialSnapshot =
                farFieldBlasCache.materialSnapshot(materials.revision());
        RtSectionMaterialPublicationState.Composition materialComposition =
                materials.compose(farFieldMaterialSnapshot, instanceLayoutHash);
        RtSceneMaterialTable.Snapshot materialSnapshot = materialComposition.snapshot();
        if (materialComposition.changed()) {
            logMaterialSnapshot("sectionBlas", materialSnapshot);
        }

        RtActiveSectionContentState.Publication activeContent = publishActiveContent(activeView);
        RtSectionTlasBuildInput input = new RtSectionTlasBuildInput(
                revisions.scene(),
                revisions.geometry(),
                materials.revision(),
                foregroundState.view(),
                activeView.coveredSections(),
                activeContent.revisions(),
                activeContent.causalities(),
                instances,
                materialSnapshot,
                pendingBuilds,
                pendingTriangles,
                totalCachedTriangles,
                activeView.baseEntries().size(),
                activeView.farFieldCells().size(),
                uncoveredSections
        );
        tlasBuildInputCache.publish(lookup, input);
        return input;
    }

    /**
     * Publishes active content and captures cross-owner state only when the invariant is already
     * broken. The synchronized caller makes every scalar in the proof one coherent observation;
     * healthy frames execute only the original publication call.
     */
    private RtActiveSectionContentState.Publication publishActiveContent(
            RtSectionActiveViewAssembler.Snapshot activeView
    ) {
        try {
            if (!activeView.farFieldCells().isEmpty()) {
                return composeBaseAndFarFieldContent(activeView);
            }
            return activeContentState.publication(activeView.coveredSections());
        } catch (IllegalStateException failure) {
            SectionKey key = activeContentState.firstMissingRevision(activeView.coveredSections());
            if (key == null) {
                throw failure;
            }
            RtSectionOwnershipProof proof = captureOwnershipProof(key, activeView);
            top.ceroxe.mcvulkanrt.renderer.RendererLog.error("RT section ownership invariant failed before TLAS publication: {}", proof);
            throw new IllegalStateException("RT section ownership invariant failed: " + proof, failure);
        }
    }

    /**
     * Joins the two terrain content lanes at the same boundary that joins their TLAS instances.
     * Base revisions come from installed exact BLAS ownership; FarField revisions come from the
     * immutable source generation captured by the active proxy cell. Reading current build intent
     * here would incorrectly relabel an older proxy after a later block update.
     */
    private RtActiveSectionContentState.Publication composeBaseAndFarFieldContent(
            RtSectionActiveViewAssembler.Snapshot activeView
    ) {
        Map<SectionKey, Long> contentRevisions = new HashMap<>(activeView.coveredSections().size());
        Map<SectionKey, RendererFrameCausality> contentCausalities =
                new HashMap<>(activeView.coveredSections().size());
        for (Map.Entry<SectionKey, RtAccelerationStructure> entry : activeView.baseEntries()) {
            SectionKey key = entry.getKey();
            Long revision = activeContentState.revision(key);
            RendererFrameCausality causality = activeContentState.causality(key);
            if (revision == null || causality == null) {
                throw new IllegalStateException("active Base section is missing content publication: " + key);
            }
            contentRevisions.put(key, revision);
            contentCausalities.put(key, causality);
        }
        for (RtFarFieldBlasCache.ActiveCell cell : activeView.farFieldCells()) {
            for (RtFarFieldBlasCache.SourceContent source : cell.sourceContent()) {
                Long previousRevision = contentRevisions.putIfAbsent(source.key(), source.revision());
                RendererFrameCausality previousCausality =
                        contentCausalities.putIfAbsent(source.key(), source.causality());
                if ((previousRevision != null && previousRevision.longValue() != source.revision())
                        || (previousCausality != null && !previousCausality.equals(source.causality()))) {
                    throw new IllegalStateException(
                            "Base/FarField content generations overlap inconsistently for " + source.key()
                    );
                }
            }
        }
        SectionRevisionSnapshot revisions = SectionRevisionSnapshot.select(
                activeView.coveredSections(),
                contentRevisions
        );
        SectionCausalitySnapshot causalities = SectionCausalitySnapshot.select(
                revisions,
                contentCausalities
        );
        return new RtActiveSectionContentState.Publication(revisions, causalities);
    }

    private RtSectionOwnershipProof captureOwnershipProof(
            SectionKey key,
            RtSectionActiveViewAssembler.Snapshot activeView
    ) {
        boolean baseEntry = false;
        for (Map.Entry<SectionKey, RtAccelerationStructure> entry : activeView.baseEntries()) {
            if (entry.getKey().equals(key)) {
                baseEntry = true;
                break;
            }
        }
        boolean farFieldCoverage = false;
        for (RtFarFieldBlasCache.ActiveCell cell : activeView.farFieldCells()) {
            if (cell.cell().sourceSections().contains(key)) {
                farFieldCoverage = true;
                break;
            }
        }
        RtSectionSourcePublication sourcePublication = sourceStore.publication(key);
        RtSectionBuildIntentState.Intent intent = buildIntents.intent(key);
        RtSectionAsyncBuildInventory.DebugState asyncState = asyncBuildInventory.debugState(key);
        RendererViewState view = foregroundState.view();
        RtSectionOwnershipProof.Presence presence = new RtSectionOwnershipProof.Presence(
                view.visibleSectionMembership().contains(key),
                activeView.coveredSections().contains(key),
                baseEntry,
                farFieldCoverage,
                residentStore.contains(key),
                lifecycleMemberships.containsResident(key),
                lifecycleMemberships.containsActive(key),
                activeContentState.contains(key),
                sourcePublication != null,
                sourcePublication != null && sourcePublication.hasPayload(),
                sourcePublication != null && sourcePublication.hasFarFieldPayload(),
                materials.slotFor(key) != null
        );
        return new RtSectionOwnershipProof(
                RtSectionOwnershipProof.classify(presence),
                key,
                presence,
                new RtSectionOwnershipProof.Cardinality(
                        view.visibleSectionMembership().size(),
                        activeView.coveredSections().size(),
                        activeView.baseEntries().size(),
                        activeView.farFieldCells().size(),
                        residentStore.size(),
                        lifecycleMemberships.residentSize(),
                        lifecycleMemberships.activeSize(),
                        activeContentState.size(),
                        sourceStore.publicationCount(),
                        materials.slotCount()
                ),
                new RtSectionOwnershipProof.Revisions(
                        view.revision(),
                        revisions.scene(),
                        revisions.geometry(),
                        materials.revision(),
                        lifecycleMemberships.residentRevision(),
                        lifecycleMemberships.activeRevision(),
                        activeContentState.generation(),
                        sourceStore.membershipRevision(),
                        sourceStore.geometryPublicationRevision(),
                        sourceStore.materialPublicationRevision()
                ),
                new RtSectionOwnershipProof.Source(
                        sourcePublication == null ? -1L : sourcePublication.geometryGeneration(),
                        sourcePublication == null ? -1L : sourcePublication.materialGeneration()
                ),
                new RtSectionOwnershipProof.Work(
                        intent != null,
                        intent == null ? -1L : intent.contentRevision(),
                        activeContentState.revisionOrDefault(key, -1L),
                        intent == null ? 0 : intent.sourceFlags(),
                        pendingBuilds.contains(key),
                        asyncState.recording(),
                        asyncState.gpu(),
                        asyncState.latestSequence()
                )
        );
    }

    synchronized long sceneRevision() {
        return revisions.scene();
    }

    private RtSectionActiveViewAssembler.Snapshot activeViewSnapshot() {
        PackedSectionMembership activeSectionKeys = lifecycleMemberships.active();
        PackedSectionMembership residentSections = lifecycleMemberships.resident();
        boolean admissionRequiresView = activeInstanceAdmission.viewAffectsAdmission(
                foregroundState.view(),
                residentSections,
                activeSectionKeys
        );
        RendererViewState admissionView = foregroundState.view();
        long residentMembershipRevision = lifecycleMemberships.residentRevision();
        long activeMembershipRevision = lifecycleMemberships.activeRevision();
        long sourceGeometryRevision = sourceStore.geometryPublicationRevision();
        long sourceMaterialRevision = sourceStore.materialPublicationRevision();
        boolean geometryChanged = activeViewCache.geometryRevision() != revisions.geometry();
        boolean materialChanged = activeViewCache.materialChanged(materials.revision(), sourceMaterialRevision);
        boolean admissionInputsChanged = activeViewCache.admissionInputsChanged(
                residentMembershipRevision,
                activeMembershipRevision,
                sourceGeometryRevision,
                admissionRequiresView,
                foregroundState.view()
        );
        RtSectionActiveViewCache.Refresh refresh = activeViewCache.refresh(
                revisions.geometry(),
                materials.revision(),
                residentMembershipRevision,
                activeMembershipRevision,
                sourceGeometryRevision,
                sourceMaterialRevision,
                admissionRequiresView,
                foregroundState.view()
        );
        RtSectionActiveViewAssembler.Snapshot currentView = activeViewCache.snapshot();
        if (refresh == RtSectionActiveViewCache.Refresh.HIT) {
            activeViewTelemetry.cacheHit();
            maybeLogActiveViewWindow();
            return currentView;
        }
        if (refresh == RtSectionActiveViewCache.Refresh.MATERIAL_ONLY) {
            long stageStartNanos = System.nanoTime();
            boolean farFieldMembershipChanged = farFieldBlasCache.reconcile(
                    currentView.admittedFarFieldCells(),
                    sourceStore.publicationsView(),
                    revisionSink()
            );
            activeViewTelemetry.addFarFieldNanos(System.nanoTime() - stageStartNanos);
            if (!farFieldMembershipChanged) {
                activeViewCache.publishMaterial(
                        materials.revision(),
                        sourceStore.materialPublicationRevision()
                );
                activeViewTelemetry.materialOnlyRefresh();
                maybeLogActiveViewWindow();
                return currentView;
            }
        }
        activeViewTelemetry.rebuild(geometryChanged, materialChanged, admissionInputsChanged);
        long stageStartNanos = System.nanoTime();
        RtSectionInstanceAdmission.Admission admission = activeInstanceAdmission.plan(
                admissionView,
                residentSections,
                activeSectionKeys,
                lifecycleMemberships.residentRevision(),
                lifecycleMemberships.activeRevision()
        );
        activeViewTelemetry.addAdmissionNanos(System.nanoTime() - stageStartNanos);
        stageStartNanos = System.nanoTime();
        farFieldBlasCache.reconcile(
                admission.farFieldCells(),
                sourceStore.publicationsView(),
                revisionSink()
        );
        activeViewTelemetry.addFarFieldNanos(System.nanoTime() - stageStartNanos);

        RtSectionActiveViewAssembler.Assembly assembly = activeViewAssembler.assemble(
                admission.baseSections(),
                residentStore.blases(),
                materials,
                farFieldBlasCache.activeCells(),
                admission.farFieldCells(),
                currentView.coveredSections()
        );
        if (assembly.identityChanged()) {
            if (geometryChanged) {
                revisions.publishGeometryToScene();
            } else {
                revisions.advanceScene();
            }
        }
        activeViewTelemetry.assembly(
                assembly.collectSortNanos(),
                assembly.coverageNanos(),
                assembly.identityChanged(),
                assembly.identityAdded(),
                assembly.identityRemoved()
        );
        int uncoveredSections = currentUncoveredForegroundSections(assembly.snapshot().coveredSections());
        RtSectionActiveViewAssembler.Snapshot nextView =
                assembly.snapshot().withUncoveredSections(uncoveredSections);
        activeViewCache.publishTopology(
                nextView,
                revisions.geometry(),
                materials.revision(),
                lifecycleMemberships.residentRevision(),
                lifecycleMemberships.activeRevision(),
                sourceStore.geometryPublicationRevision(),
                sourceStore.materialPublicationRevision(),
                admissionRequiresView,
                foregroundState.view()
        );
        maybeLogActiveViewWindow();
        return nextView;
    }

    private int currentUncoveredForegroundSections(PackedSectionMembership coveredSections) {
        PackedSectionMembership authority = foregroundState.authority();
        if (!foregroundState.view().authoritative() || authority.isEmpty()) {
            return 0;
        }
        int covered = activeViewForegroundCoverage.matchedCount(
                authority,
                foregroundState.authorityRevision(),
                coveredSections,
                Long.MIN_VALUE
        );
        return authority.size() - covered;
    }


    private void maybeLogActiveViewWindow() {
        if (!activeViewTelemetry.sampleDue()) {
            return;
        }
        boolean admissionRequiresView = activeInstanceAdmission.viewAffectsAdmission(
                foregroundState.view(),
                lifecycleMemberships.residentKeys(),
                residentStore.keys()
        );
        boolean geometryChanged = activeViewCache.geometryChanged(
                revisions.geometry(),
                lifecycleMemberships.residentRevision(),
                lifecycleMemberships.activeRevision(),
                sourceStore.geometryPublicationRevision()
        );
        boolean materialChanged = activeViewCache.materialChanged(
                materials.revision(),
                sourceStore.materialPublicationRevision()
        );
        boolean admissionInputsChanged = activeViewCache.admissionInputsChanged(
                lifecycleMemberships.residentRevision(),
                lifecycleMemberships.activeRevision(),
                sourceStore.geometryPublicationRevision(),
                admissionRequiresView,
                foregroundState.view()
        );
        activeViewTelemetry.publish(new RtSectionActiveViewTelemetry.Sample(
                activeInstanceAdmission.cachedPlanHits(),
                activeInstanceAdmission.cachedPlanMisses(),
                activeInstanceAdmission.cachedMembershipSnapshotHits(),
                activeInstanceAdmission.cachedMembershipSnapshotMisses(),
                activeInstanceAdmission.planInvocations(),
                activeInstanceAdmission.planBuilds(),
                warmupInstanceAdmission.planBuilds(),
                tlasBuildInputCache.stats(),
                sourceStore.payloadCount(),
                activeInstanceAdmission.baseCapacityHighWater(),
                admissionRequiresView,
                activeViewCache.admissionRequiresView(),
                geometryChanged,
                materialChanged,
                admissionInputsChanged
        ));
    }

    public synchronized RtSectionTlasBuildStats snapshotTlasBuildStats() {
        if (closed) {
            throw new IllegalStateException("RT section BLAS cache is already closed");
        }

        activeViewTelemetry.tlasBuildStatsRequested();
        /*
         * Stats are the scheduler's topology publication, not a presentation snapshot. Always
         * refresh them from currently resident BLAS ownership so a partially built foreground
         * can advance monotonically. The descriptor-bind boundary is the sole owner of coverage
         * contraction safety; freezing the stats here hid every additive successor revision from
         * the TLAS scheduler and formed a permanent coverage-completion dependency cycle.
         */
        RtSectionActiveViewAssembler.Snapshot activeView = activeViewCache.snapshot();
        int uncoveredSections = currentUncoveredForegroundSections(activeView.coveredSections());
        int activeExactInstances = lifecycleMemberships.activeSize();
        int farFieldInstances = activeView.farFieldCells().size();
        int activeInstances = Math.addExact(activeExactInstances, farFieldInstances);
        return new RtSectionTlasBuildStats(
                revisions.scene(),
                revisions.geometry(),
                materials.revision(),
                activeInstances,
                activeView.coveredSections().size(),
                activeExactInstances,
                farFieldInstances,
                foregroundState.view().authoritative(),
                !foregroundState.authority().isEmpty(),
                Math.addExact(
                        Math.addExact(pendingSectionBuildCount(), farFieldBlasCache.pendingDesiredBuilds()),
                        uncoveredSections
                ),
                pendingSectionBuildTriangles(),
                Math.addExact(residentStore.cachedTriangles(), farFieldBlasCache.activeTriangles())
        );
    }

    /**
     * Proves that interactive topology targets are represented by live BLAS ownership.
     * The device uses this before bypassing streaming TLAS coalescing, so urgency
     * can never be consumed by a rebuild that still references pre-edit geometry.
     */
    public synchronized boolean matchesActiveContentTargets(
            Map<SectionKey, Long> expectedPresentRevisions,
            Set<SectionKey> expectedAbsentSections
    ) {
        Objects.requireNonNull(expectedPresentRevisions, "expectedPresentRevisions");
        Objects.requireNonNull(expectedAbsentSections, "expectedAbsentSections");
        if (!Collections.disjoint(expectedPresentRevisions.keySet(), expectedAbsentSections)) {
            throw new IllegalArgumentException("interactive terrain targets must be disjoint");
        }
        for (Map.Entry<SectionKey, Long> target : expectedPresentRevisions.entrySet()) {
            if (target.getValue() < 0L) {
                throw new IllegalArgumentException("interactive content revisions must not be negative");
            }
            Long activeRevision = activeContentState.revision(target.getKey());
            if (!residentStore.contains(target.getKey())
                    || activeRevision == null
                    || activeRevision < target.getValue()) {
                return false;
            }
        }
        for (SectionKey key : expectedAbsentSections) {
            if (residentStore.contains(Objects.requireNonNull(key, "expected absent section"))) {
                return false;
            }
        }
        return true;
    }

    /** Returns the material generation token without materializing an active-view snapshot. */
    public synchronized long materialRevision() {
        if (closed) {
            throw new IllegalStateException("RT section BLAS cache is already closed");
        }
        return materials.revision();
    }

    /**
     * Returns the stable section-slot namespace size without materializing an
     * active view. Dynamic TLAS scheduling must not trigger a camera-dependent
     * Base/FarField admission rebuild merely to obtain its custom-index offset.
     */
    synchronized int materialSlotCount() {
        return materials.slotCount();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        pendingBuilds.clear();
        RuntimeException failure = null;
        failure = closePendingAsyncBuildCollecting(failure);
        try {
            farFieldBlasCache.close();
        } catch (RuntimeException ex) {
            failure = appendFailure(failure, ex);
        }
        RuntimeException beforeSectionClose = failure;
        int beforeSectionCloseSuppressed = suppressedCount(failure);
        failure = closeAllSectionsCollecting(failure);
        boolean sectionBlasCloseSucceeded = failureUnchanged(
                beforeSectionClose,
                beforeSectionCloseSuppressed,
                failure
        );
        failure = retirementLifecycle.closeAllCollecting(failure, sectionBlasCloseSucceeded);
        try {
            residentStore.clearAfterExternalRelease();
            activeViewAssembler.clear();
            activeViewCache.clear();
            materialReuseCache.clear();
            sourceStore.clear();
            deferredEmptySections.clear();
            buildPriorityState.clearInteractive();
            lifecycleMemberships.clearResidents();
            activeContentState.clear();
            tlasBuildInputCache.invalidate();
            materials.discard();
        } finally {
            if (failure != null) {
                throw failure;
            }
        }
    }

    private void enqueueOrRemoveEmptyMesh(SectionTriangleMesh mesh) {
        Objects.requireNonNull(mesh, "mesh");
        if (mesh.triangleCount() == 0) {
            invalidatePendingAsyncBuild(mesh.key());
            statistics.emptyMesh();
            pendingBuilds.cancel(mesh.key());
            int effectiveSourceFlags = buildIntents.sourceFlagsOrDefault(mesh.key(), 0);
            if (buildPriorityState.isInteractive(mesh.key())) {
                /* Preserve interactive authority across a later streaming snapshot of the same target. */
                effectiveSourceFlags |= SceneUpdateBatch.SOURCE_BLOCK_MUTATION;
            }
            if (deferEmptySectionRemoval(
                    residentStore.contains(mesh.key()),
                    foregroundState.retainedPresentationKeys().contains(mesh.key()),
                    effectiveSourceFlags
            )) {
                deferredEmptySections.defer(mesh.key());
                return;
            }
            deferredEmptySections.resolve(mesh.key());
            buildPriorityState.resolveInteractive(mesh.key());
            removeSection(mesh.key());
            return;
        }
        deferredEmptySections.resolve(mesh.key());
        RtSectionSourcePublication previousPublication = sourceStore.publication(mesh.key());
        SectionTriangleMesh previous = previousPublication == null ? null : previousPublication.mesh();
        RtSceneMaterialTable.SectionMaterial previousMaterial = previousPublication == null
                ? null
                : previousPublication.material();
        RtSceneMaterialTable.SectionMaterial material = materialForMesh(mesh, previousMaterial);
        boolean materialChanged = !material.equals(previousMaterial);
        if (previous != null && baseGeometryMatches(previous, mesh)) {
            /*
             * Exact BLAS ownership follows Base geometry, not the Java mesh object
             * or its independently changing material payload. host lighting,
             * tint and animated texture updates may replace the immutable source
             * object while an equivalent vertex/index generation is in flight.
             * Keep that GPU build valid, but still advance proxy/material generations
             * through recordSourceMesh when their stricter signatures changed.
             */
            recordSourceMesh(mesh, material, false, materialChanged);
            if (requiresExactBaseBuild(mesh.key())
                    && !residentStore.contains(mesh.key())
                    && !pendingBuildOwnership.ownsSection(mesh.key(), pendingBuilds)) {
                enqueuePendingBuild(mesh);
            }
            return;
        }
        invalidatePendingAsyncBuild(mesh.key());
        recordSourceMesh(mesh, material, false, materialChanged);
        if (requiresExactBaseBuild(mesh.key())) {
            enqueuePendingBuild(mesh);
        } else {
            /*
             * Background resident coverage needs only the bounded FarField publication. Retaining
             * or building the complete mesh here would recreate a 32K exact-BLAS queue and starve
             * the current successor. If this key later enters Base authority, renderer backfill or
             * source recovery republishes its full mesh into the exact lane.
             */
            releaseSourceMeshPayload(mesh.key());
        }
    }

    private boolean requiresExactBaseBuild(SectionKey key) {
        PackedSectionMembership authority = foregroundState.authority();
        return authority.isEmpty()
                || authority.contains(key)
                || residentStore.contains(key)
                || buildPriorityState.isInteractive(key);
    }

    private void reconcileRequiredForegroundWork() {
        PackedSectionMembership requiredSections = foregroundState.work().sectionKeys();
        if (requiredSections != foregroundState.authority()) {
            throw new IllegalStateException("foreground work identity must match accepted view authority");
        }
        foregroundBuildLedger.reconcile(requiredSections, key -> {
            long desiredRevision = buildIntents.revisionOrDefault(key, -1L);
            long activeRevision = activeContentState.revisionOrDefault(key, -1L);
            return !residentStore.contains(key) || activeRevision != desiredRevision;
        });
    }

    /**
     * Re-admits source meshes whose queue/async ownership was lost without overflowing the queue.
     *
     * <p>The old repair scan walked every required section before the 500-microsecond frame budget
     * was checked. Because all required keys were foreground, a successor larger than the pending
     * queue repeatedly evicted its own oldest entries; the next frame reconstructed the same churn.
     * This persistent round-robin queue inspects bounded work and admits only into real free slots.
     * Owned or source-missing keys rotate to the tail, while completed keys leave both owners.</p>
     */
    private void recoverRequiredForegroundWork() {
        if (!foregroundBuildLedger.hasRecoveryWork()) {
            return;
        }
        int availableQueueSlots = pendingBuilds.availableSectionCapacity();
        if (availableQueueSlots <= 0) {
            return;
        }
        int inspections = foregroundBuildLedger.inspectionBudget(
                FOREGROUND_RECOVERY_MAX_INSPECTIONS_PER_FRAME
        );
        int missingExactPayloads = 0;
        int alreadyOwned = 0;
        int requeued = 0;
        SectionKey firstMissingExactPayload = null;
        while (inspections-- > 0 && availableQueueSlots > 0) {
            SectionKey key = foregroundBuildLedger.pollRecoveryCandidate();
            if (key == null) {
                break;
            }
            long desiredRevision = buildIntents.revisionOrDefault(key, -1L);
            long activeRevision = activeContentState.revisionOrDefault(key, -1L);
            if (residentStore.contains(key) && activeRevision == desiredRevision) {
                foregroundBuildLedger.complete(key);
                continue;
            }
            SectionTriangleMesh sourceMesh = sourceStore.mesh(key);
            if (sourceMesh == null) {
                missingExactPayloads++;
                if (firstMissingExactPayload == null) {
                    firstMissingExactPayload = key;
                }
                foregroundBuildLedger.defer(key);
                continue;
            }
            if (pendingBuildOwnership.ownsSection(key, pendingBuilds)) {
                alreadyOwned++;
                foregroundBuildLedger.defer(key);
                continue;
            }
            boolean enqueued = enqueuePendingBuildIfUnowned(sourceMesh);
            foregroundBuildLedger.defer(key);
            if (enqueued) {
                requeued++;
                availableQueueSlots--;
            }
        }
        publishForegroundRecoveryDiagnostic(
                firstMissingExactPayload,
                missingExactPayloads,
                alreadyOwned,
                requeued,
                availableQueueSlots
        );
    }

    private void publishForegroundRecoveryDiagnostic(
            SectionKey firstMissingExactPayload,
            int missingExactPayloads,
            int alreadyOwned,
            int requeued,
            int remainingQueueSlots
    ) {
        if (firstMissingExactPayload == null || !diagnostics.edges().enabled()) {
            return;
        }
        long now = System.nanoTime();
        if (now < nextForegroundRecoveryDiagnosticNanos) {
            return;
        }
        nextForegroundRecoveryDiagnosticNanos = now + FOREGROUND_RECOVERY_DIAGNOSTIC_INTERVAL_NANOS;
        RtSectionBuildIntentState.Intent intent = buildIntents.intent(firstMissingExactPayload);
        top.ceroxe.mcvulkanrt.renderer.RendererLog.warn(
                "rt foreground recovery blocked: T={}ms, firstMissingExactPayload={}, "
                        + "missingExactPayloads={}, alreadyOwned={}, requeued={}, requiredLedger={}, "
                        + "remainingQueueSlots={}, authorityRevision={}, viewRevision={}, desiredRevision={}, "
                        + "activeRevision={}, sourceFlags={}, compactSourcePresent={}",
                diagnostics.edges().elapsedMillis(),
                firstMissingExactPayload,
                missingExactPayloads,
                alreadyOwned,
                requeued,
                foregroundBuildLedger.size(),
                remainingQueueSlots,
                foregroundState.authorityRevision(),
                foregroundState.view().revision(),
                intent == null ? -1L : intent.contentRevision(),
                activeContentState.revisionOrDefault(firstMissingExactPayload, -1L),
                intent == null ? 0 : intent.sourceFlags(),
                sourceStore.containsPublication(firstMissingExactPayload)
        );
        if (intent != null) {
            blasLifecycleFlightRecorder.record(
                    RtSectionBlasLifecycleFlightRecorder.Stage.RECOVERY_MISSING_EXACT_SOURCE.name(),
                    firstMissingExactPayload,
                    intent.contentRevision(),
                    -1L,
                    revisions.geometry(),
                    -1L,
                    sourceStore.containsPublication(firstMissingExactPayload)
                            ? "compactSourceOnly"
                            : "sourcePublicationAbsent",
                    intent.causality()
            );
        }
    }

    public static boolean sameExactBlasGeneration(
            SectionTriangleMesh previous,
            SectionTriangleMesh candidate
    ) {
        Objects.requireNonNull(candidate, "candidate");
        return previous != null
                && baseGeometryMatches(previous, candidate);
    }

    private boolean updateMaterialForCompatibleResidentGeometry(SectionTriangleMesh mesh) {
        RtSectionSourcePublication previousPublication = sourceStore.publication(mesh.key());
        SectionTriangleMesh previousMesh = previousPublication == null ? null : previousPublication.mesh();
        if (previousMesh == null
                || !residentStore.contains(mesh.key())
                || !baseGeometryMatches(previousMesh, mesh)) {
            return false;
        }
        RtSceneMaterialTable.SectionMaterial sourceMaterial = materialForMesh(
                mesh,
                previousPublication == null
                        ? residentStore.material(mesh.key())
                        : previousPublication.material()
        );
        RtSceneMaterialTable.SectionMaterial material = sourceMaterial.detachedPublication();
        invalidatePendingAsyncBuild(mesh.key());
        pendingBuilds.cancel(mesh.key());
        promoteCompatibleActiveContentRevision(mesh.key());
        boolean materialChanged = !material.equals(residentStore.material(mesh.key()));
        recordSourceMesh(mesh, sourceMaterial, false, materialChanged);
        if (!materialChanged) {
            buildPriorityState.resolveInteractive(mesh.key());
            return true;
        }
        RtSectionBlasResidentStore.MaterialUpdate materialUpdate =
                residentStore.prepareMaterialUpdate(mesh.key(), material);
        updateMaterialSlot(
                mesh.key(),
                material,
                activeContentState.revisionOrDefault(mesh.key(), -1L)
        );
        residentStore.publishMaterialUpdate(materialUpdate);
        buildPriorityState.resolveInteractive(mesh.key());
        return true;
    }

    private void promoteCompatibleActiveContentRevision(SectionKey key) {
        RtSectionBuildIntentState.Intent intent = buildIntents.require(key);
        long desiredRevision = intent.contentRevision();
        Long activeRevision = activeContentState.revision(key);
        if (activeRevision == null) {
            throw new IllegalStateException("compatible resident section is missing content ownership: " + key);
        }
        if (desiredRevision <= activeRevision) {
            return;
        }

        activeContentState.install(key, desiredRevision, intent.causality());
        tlasBuildInputCache.invalidate();
        if (buildPriorityState.isInteractive(key)
                || RtDeferredEmptySectionState.interactiveTopologySource(intent.sourceFlags())) {
            /*
             * A material-compatible block edit keeps the same BLAS address, but
             * its content revision still has to cross the atomic world-scene
             * publication boundary. Advance the publication generation so the
             * urgent TLAS transaction cannot be mistaken for an unchanged scene.
             */
            revisions.advanceScene();
        }
    }

    private RtSceneMaterialTable.SectionMaterial materialForMesh(
            SectionTriangleMesh mesh,
            RtSceneMaterialTable.SectionMaterial reusable
    ) {
        RtSceneMaterialTable.SectionMaterial published = mesh.packedMaterialPublication();
        if (published != null) {
            return published;
        }
        if (reusable != null && reusable.matchesMesh(mesh)) {
            return mesh.publishPackedMaterial(reusable);
        }
        return materialReuseCache.materialFor(mesh).material();
    }

    /**
     * Section material records are immutable and independent of section identity. The mesh owns
     * its selected publication while this bounded inter-section table only narrows candidates by
     * fingerprint. {@link RtSceneMaterialTable.SectionMaterial#matchesMesh(SectionTriangleMesh)}
     * remains the authority, so collisions cannot publish the wrong material or retain BLAS state.
     */
    private List<RtPendingBlasBuildQueue.Work<RtSectionBlasBuildMetadata>> submitAsyncBuildBatch(
            List<RtPendingBlasBuildQueue.Work<RtSectionBlasBuildMetadata>> workItems,
            boolean foregroundSubmission
    ) {
        List<SectionTriangleMesh> meshes = workItems.stream()
                .map(RtPendingBlasBuildQueue.Work::mesh)
                .toList();
        List<SectionTriangleMesh> claimedMeshes = pendingBuildOwnership.claimAsync(meshes);
        if (claimedMeshes.isEmpty()) {
            return List.of();
        }
        try {
            RtSectionBlasBuildBatch batch = RtSectionBlasBuildBatch.capture(workItems, claimedMeshes);
            RtPendingSectionBlasRecording recording = new RtPendingSectionBlasRecording(
                    batch,
                    foregroundSubmission,
                    asyncBuildInventory.allocateSequence(),
                    diagnostics.edges()
            );
            for (SectionTriangleMesh mesh : batch.meshes()) {
                firstFrontProgress.recordLifecycle(
                        "blasAdmission",
                        mesh.key(),
                        recording.sequence(),
                        buildIntents.revisionOrDefault(mesh.key(), -1L),
                        activeContentState.revisionOrDefault(mesh.key(), -1L),
                        buildIntents.sourceFlagsOrDefault(mesh.key(), 0),
                        residentStore.contains(mesh.key()),
                        true,
                        false
                );
            }
            // Count admission on the render thread. The worker must not acquire
            // this cache's monitor before recording; processFrameBudget owns the
            // same monitor and would otherwise starve the recording queue while
            // bootstrap pumps repeatedly enter the scheduler.
            asyncWorkerTelemetry.started(
                    recording,
                    foregroundState.view().revision(),
                    buildPriorityState.preferredKeys()
            );
            RtPendingSectionBlasRecording.PrioritizedTask task = RtPendingSectionBlasRecording.task(
                    foregroundSubmission,
                    recording.sequence(),
                    () -> {
                long submitStart = System.nanoTime();
                try {
                    RtAccelerationStructure.RecordedSectionBlasBuild recorded =
                            RtAccelerationStructure.recordSectionBlasBatch(
                                    device,
                                    allocator,
                                    commandContext,
                                    scratchAlignmentBytes,
                                    recording.meshes(),
                                    diagnostics.edges()
                            );
                    long elapsedNanos = System.nanoTime() - submitStart;
                    return new RtPendingSectionBlasRecording.SubmittedBuild(recorded, elapsedNanos);
                } catch (RuntimeException | Error ex) {
                    throw ex;
                }
                    }
            );
            asyncBuildInventory.startRecording(recording, task);
            statistics.asyncBuildSubmitted();
            return batch.workItems();
        } catch (RuntimeException | Error ex) {
            pendingBuildOwnership.releaseAsync(claimedMeshes);
            throw ex;
        }
    }

    private static boolean pureNeighborSuccessor(int sourceFlags) {
        int unsafeSources = SceneUpdateBatch.SOURCE_BLOCK_MUTATION
                | SceneUpdateBatch.SOURCE_SECTION_REMOVAL
                | SceneUpdateBatch.SOURCE_FULL_RESYNC
                | SceneUpdateBatch.SOURCE_DIRECT_CONTENT;
        return (sourceFlags & SceneUpdateBatch.SOURCE_NEIGHBOR_DEPENDENCY) != 0
                && (sourceFlags & unsafeSources) == 0;
    }

    public static boolean shouldInstallInvalidatedResultAsCurrent(
            boolean staleResult,
            boolean currentSourceExists,
            boolean activeExists,
            int successorSourceFlags
    ) {
        return staleResult
                && currentSourceExists
                && !activeExists
                && pureNeighborSuccessor(successorSourceFlags);
    }

    private void pollPendingAsyncRecordings(
            long passStartNanos,
            long maxElapsedNanos,
            boolean firstWorldFrontPending
    ) {
        RtInvalidatedSectionRecordingDisposer.discard(
                asyncBuildInventory,
                pendingBuildOwnership,
                asyncWorkerTelemetry,
                buildPriorityState.preferredKeys()
        );
        int completedRecordings = 0;
        while (asyncBuildInventory.hasRecordings()) {
            if (asyncBuildInventory.incompleteGpuBatchCount() >= gpuSubmissionWindow) {
                schedulerFlightRecorder.record(
                        "gpuWindowFull",
                        pendingBuilds.size(),
                        asyncBuildInventory,
                        hasPendingForegroundBuilds(),
                        hasIncompleteForegroundCoverage(),
                        firstWorldFrontPending,
                        maxElapsedNanos,
                        passStartNanos
                );
                break;
            }
            if (!withinFrameBudget(passStartNanos, maxElapsedNanos, completedRecordings)) {
                schedulerFlightRecorder.record(
                        "recordingBudgetExhausted",
                        pendingBuilds.size(),
                        asyncBuildInventory,
                        hasPendingForegroundBuilds(),
                        hasIncompleteForegroundCoverage(),
                        firstWorldFrontPending,
                        maxElapsedNanos,
                        passStartNanos
                );
                break;
            }
            boolean foregroundCoverageIncomplete = hasIncompleteForegroundCoverage();
            boolean foregroundBuildQueued = hasPendingForegroundBuilds();
            boolean releaseCompletedRecordingForForegroundProgress =
                    RtSectionAsyncSubmissionSelector.shouldReleaseCompletedRecordingForForegroundProgress(
                            foregroundSubmissionGateActive(
                                    foregroundState.view().authoritative(),
                                    foregroundCoverageIncomplete,
                                    firstWorldFrontPending
                            ),
                            foregroundBuildQueued
                    );
            long detailStartNanos = System.nanoTime();
            int recordingIndex = RtSectionAsyncSubmissionSelector.select(
                    asyncBuildInventory,
                    foregroundState.view().revision(),
                    buildPriorityState.preferredKeys(),
                    buildPriorityState.interactiveKeys(),
                    foregroundState.view().authoritative(),
                    foregroundCoverageIncomplete,
                    firstWorldFrontPending,
                    releaseCompletedRecordingForForegroundProgress
            );
            telemetry.recordingScan(elapsedMicros(detailStartNanos));
            if (recordingIndex < 0) {
                schedulerFlightRecorder.record(
                        asyncBuildInventory.hasCompletedRecording() ? "foregroundSelectorDeferred" : "recordingNotReady",
                        pendingBuilds.size(),
                        asyncBuildInventory,
                        foregroundBuildQueued,
                        foregroundCoverageIncomplete,
                        firstWorldFrontPending,
                        maxElapsedNanos,
                        passStartNanos
                );
                if (foregroundSubmissionGateActive(
                        foregroundState.view().authoritative(),
                        foregroundCoverageIncomplete,
                        firstWorldFrontPending
                )
                        && asyncBuildInventory.hasCompletedDeferredBackgroundRecording(
                        buildPriorityState.preferredKeys()
                )) {
                    statistics.backgroundQueueHeadDeferred();
                }
                break;
            }
            RtPendingSectionBlasRecording recording = asyncBuildInventory.recordingAt(recordingIndex);
            int gpuBatchesAhead = asyncBuildInventory.incompleteGpuBatchCount();
            int interactiveSections = recording.activePreferredSectionCount(buildPriorityState.interactiveKeys());
            int preferredSections = recording.activePreferredSectionCount(buildPriorityState.preferredKeys());
            boolean foregroundProgressDependencyRelease =
                    releaseCompletedRecordingForForegroundProgress && preferredSections == 0;
            RtPendingSectionBlasRecording.SubmittedBuild submitted;
            detailStartNanos = System.nanoTime();
            try {
                submitted = recording.complete();
            } catch (RuntimeException | Error ex) {
                asyncBuildInventory.removeRecordingAt(recordingIndex);
                asyncWorkerTelemetry.failed(recording);
                pendingBuildOwnership.releaseAsync(recording.meshes());
                throw ex;
            }
            telemetry.recordingComplete(elapsedMicros(detailStartNanos));
            asyncWorkerTelemetry.completed(
                    recording,
                    submitted.submitElapsedNanos(),
                    buildPriorityState.preferredKeys()
            );
            detailStartNanos = System.nanoTime();
            RtPendingSectionBlasBuild pending = RtSectionBlasSubmissionTransaction.promote(
                    asyncBuildInventory,
                    pendingBuildOwnership,
                    commandContext,
                    recordingIndex,
                    recording,
                    submitted
            );
            schedulerFlightRecorder.record(
                    "gpuSubmitted",
                    pendingBuilds.size(),
                    asyncBuildInventory,
                    foregroundBuildQueued,
                    foregroundCoverageIncomplete,
                    firstWorldFrontPending,
                    maxElapsedNanos,
                    passStartNanos
            );
            if (foregroundProgressDependencyRelease) {
                statistics.foregroundProgressDependencyReleased();
            }
            telemetry.recordingNativeSubmit(elapsedMicros(detailStartNanos));
            blasLifecycleFlightRecorder.recordPending(
                    pending,
                    RtSectionBlasLifecycleFlightRecorder.Stage.BLAS_RECORD_SUBMIT,
                    "submitted",
                    new RtSectionBlasLifecycleFlightRecorder.BatchEvidence(
                            pending.activeSectionCount(),
                            pending.activeTriangleCount(),
                            interactiveSections,
                            preferredSections,
                            gpuBatchesAhead,
                            gpuSubmissionWindow,
                            submitted.submitElapsedNanos(),
                            -1L,
                            -1L,
                            -1L
                    )
            );
            recordPendingBuildEdge(pending, "gpuSubmitted", buildPriorityState.preferredKeys(), 0L);
            recordBuildBatchTelemetry(recording.meshes(), submitted.submitElapsedNanos());
            adaptiveBuildBudget.recordBatch(
                    submitted.submitElapsedNanos(),
                    recording.retainedSectionCount(),
                    hasAnyPendingSectionBuilds()
            );
            completedRecordings++;
        }
    }

    public static boolean shouldSubmitRecordedBuild(
            boolean authoritativeViewEstablished,
            boolean foregroundCoverageIncomplete,
            boolean firstWorldFrontPending,
            int activeForegroundSections
    ) {
        return RtSectionAsyncSubmissionSelector.shouldSubmit(
                authoritativeViewEstablished,
                foregroundCoverageIncomplete,
                firstWorldFrontPending,
                activeForegroundSections
        );
    }

    private static boolean foregroundSubmissionGateActive(
            boolean authoritativeViewEstablished,
            boolean foregroundCoverageIncomplete,
            boolean firstWorldFrontPending
    ) {
        return RtSectionAsyncSubmissionSelector.foregroundGateActive(
                authoritativeViewEstablished,
                foregroundCoverageIncomplete,
                firstWorldFrontPending
        );
    }

    private void pollPendingAsyncBuilds(
            long passStartNanos,
            long maxElapsedNanos,
            boolean firstWorldFrontPending,
            boolean forceApplyCompletedResults
    ) {
        int appliedResults = 0;
        int buildIndex = 0;
        while (buildIndex < asyncBuildInventory.gpuBatchCount()) {
            RtPendingSectionBlasBuild pending = asyncBuildInventory.gpuBuildAt(buildIndex);
            if (!pending.hasCompletedResults()) {
                long detailStartNanos = System.nanoTime();
                RtAccelerationStructure.CompletedSectionBlasBuild completed = pending.submission().completeIfReady();
                telemetry.gpuPoll(elapsedMicros(detailStartNanos));
                if (completed == null) {
                    statistics.asyncBuildPollNotReady();
                    buildIndex++;
                    continue;
                }
                pending.complete(completed);
                blasLifecycleFlightRecorder.recordPending(
                        pending,
                        RtSectionBlasLifecycleFlightRecorder.Stage.GPU_COMPLETE,
                        "completed",
                        new RtSectionBlasLifecycleFlightRecorder.BatchEvidence(
                                pending.activeSectionCount(),
                                pending.activeTriangleCount(),
                                -1,
                                -1,
                                -1,
                                gpuSubmissionWindow,
                                completed.elapsedNanos(),
                                completed.gpuExecutionNanos(),
                                completed.lastNotReadyToObservationNanos(),
                                completed.notReadyPolls()
                        )
                );
                recordPendingBuildEdge(
                        pending,
                        "gpuCompleted",
                        buildPriorityState.preferredKeys(),
                        completed.elapsedNanos()
                );
                recordAsyncBuildLatencyTelemetry(completed.elapsedNanos());
                statistics.asyncBuildCompleted();
            }

            while (pending.hasCompletedResults() && (forceApplyCompletedResults
                    || RtSectionBlasAdmissionPlanner.shouldApplyCompletedResults(
                    withinFrameBudget(passStartNanos, maxElapsedNanos, appliedResults)
            ))) {
                long detailStartNanos = System.nanoTime();
                try {
                    applyNextCompletedAsyncBuildResult(pending);
                } catch (RuntimeException ex) {
                    asyncBuildInventory.removeGpuBuildAt(buildIndex);
                    pendingBuildOwnership.releaseAsync(pending.meshes());
                    try {
                        pending.close();
                    } catch (RuntimeException closeFailure) {
                        ex.addSuppressed(closeFailure);
                    }
                    throw ex;
                }
                telemetry.gpuApply(elapsedMicros(detailStartNanos));
                appliedResults++;
            }
            if (pending.hasCompletedResults()) {
                break;
            }
            recordPendingBuildEdge(pending, "applied", buildPriorityState.preferredKeys(), 0L);
            asyncBuildInventory.removeConsumedGpuBuildAt(buildIndex, pending);
        }
    }

    private List<RtPendingBlasBuildQueue.Work<RtSectionBlasBuildMetadata>> drainBuildBatch(
            RtAdaptiveBuildBudget.Limits limits,
            int submittedInPass,
            long trianglesSubmittedInPass,
            Set<SectionKey> interactiveSectionKeys,
            Set<SectionKey> foregroundSectionKeys
    ) {
        boolean interactiveSubmission = pendingBuilds.hasPreferred(interactiveSectionKeys);
        boolean foregroundSubmission = interactiveSubmission || hasPendingPriorityBuilds(foregroundSectionKeys);
        RtAdaptiveBuildBudget.Limits submissionLimits =
                RtSectionBlasAdmissionPlanner.submissionLimits(
                        limits, foregroundSubmission, interactiveSubmission
                );
        RtSectionAsyncBuildInventory.Metrics asyncMetrics = pendingAsyncMetrics();
        int remainingBuilds = Math.min(
                submissionLimits.maxBuilds(),
                limits.maxBuilds() - submittedInPass
        );
        remainingBuilds = Math.min(
                remainingBuilds,
                configuration.maxAsyncBuildSectionsInFlight() - asyncMetrics.retainedSections()
        );
        if (!foregroundSubmission) {
            remainingBuilds = Math.min(
                    remainingBuilds,
                    remainingBackgroundCapacity(
                            configuration.maxAsyncBuildSectionsInFlight(),
                            asyncMetrics.backgroundRetainedSections()
                    )
            );
        }
        long remainingTriangles = Math.min(
                submissionLimits.maxTriangles(),
                limits.maxTriangles() - trianglesSubmittedInPass
        );
        long remainingBytes = configuration.maxAsyncBuildBytesInFlight() - asyncMetrics.retainedBytes();
        if (!foregroundSubmission) {
            remainingBytes = Math.min(
                    remainingBytes,
                    remainingBackgroundCapacity(
                            configuration.maxAsyncBuildBytesInFlight(),
                            asyncMetrics.backgroundRetainedBytes()
                    )
            );
        }
        if (remainingBuilds <= 0 || remainingTriangles <= 0L || remainingBytes <= 0L) {
            return List.of();
        }

        List<RtPendingBlasBuildQueue.Work<RtSectionBlasBuildMetadata>> buildBatch =
                new ArrayList<>(remainingBuilds);
        long trianglesInBatch = 0L;
        long bytesInBatch = 0L;
        while (buildBatch.size() < remainingBuilds) {
            if (interactiveSubmission && !pendingBuilds.hasPreferred(interactiveSectionKeys)) {
                break;
            }
            if (foregroundSubmission && !hasPendingPriorityBuilds(foregroundSectionKeys)) {
                break;
            }
            RtPendingBlasBuildQueue.Work<RtSectionBlasBuildMetadata> work = pendingBuilds.pollNextWork(
                    buildBatch.size(),
                    trianglesInBatch,
                    remainingBuilds,
                    remainingTriangles,
                    bytesInBatch,
                    remainingBytes
            );
            if (work == null) {
                break;
            }
            SectionTriangleMesh mesh = work.mesh();
            buildBatch.add(work);
            if (foregroundSectionKeys.contains(mesh.key())) {
                statistics.foregroundPriorityBuild();
            }
            trianglesInBatch += mesh.triangleCount();
            bytesInBatch += mesh.estimatedBytes();
        }
        return buildBatch;
    }

    private void applyNextCompletedAsyncBuildResult(RtPendingSectionBlasBuild pending) {
        RtAccelerationStructure.SectionBlasBuildResult result = pending.nextCompletedResult();
        SectionTriangleMesh currentSourceMesh = sourceStore.mesh(result.mesh().key());
        boolean invalidated = pending.resultWasInvalidated(result.mesh().key());
        boolean baseMatches = currentSourceMesh != null && baseGeometryMatches(currentSourceMesh, result.mesh());
        boolean staleResult = invalidated || currentSourceMesh == null || !baseMatches;
        boolean installAsCurrentGeneration = shouldInstallInvalidatedResultAsCurrent(
                staleResult,
                currentSourceMesh != null,
                residentStore.contains(result.mesh().key()),
                buildIntents.sourceFlagsOrDefault(result.mesh().key(), 0)
        );
        boolean discard = staleResult && !installAsCurrentGeneration;
        firstFrontProgress.recordLifecycle(
                discard ? "gpuResultDiscard" : "gpuResultApply",
                result.mesh().key(),
                pending.sequence(),
                buildIntents.revisionOrDefault(result.mesh().key(), -1L),
                activeContentState.revisionOrDefault(result.mesh().key(), -1L),
                buildIntents.sourceFlagsOrDefault(result.mesh().key(), 0),
                residentStore.contains(result.mesh().key()),
                baseMatches,
                invalidated
        );
        if (discard) {
            try {
                result.blas().close();
            } catch (RuntimeException ex) {
                blasLifecycleFlightRecorder.record(
                        RtSectionBlasLifecycleFlightRecorder.Stage.RELEASED.name(),
                        result.mesh().key(),
                        pending.contentRevision(result.mesh().key()),
                        pending.sequence(),
                        -1L,
                        -1L,
                        "discardCloseFailed",
                        pending.causality(result.mesh().key())
                );
                throw ex;
            }
            blasLifecycleFlightRecorder.record(
                    RtSectionBlasLifecycleFlightRecorder.Stage.RELEASED.name(),
                    result.mesh().key(),
                    pending.contentRevision(result.mesh().key()),
                    pending.sequence(),
                    -1L,
                    -1L,
                    "discarded",
                    pending.causality(result.mesh().key())
            );
            statistics.asyncBuildResultDiscarded();
        } else {
            replaceSectionBlas(
                    result.mesh(),
                    result.blas(),
                    pending.contentRevision(result.mesh().key()),
                    pending.material(result.mesh().key()),
                    pending.causality(result.mesh().key()),
                    pending.sequence()
            );
        }
        asyncBuildInventory.markGpuResultApplied(pending, result.mesh().key());
        pendingBuildOwnership.releaseAsync(result.mesh());
    }

    private void replaceSectionBlas(SectionTriangleMesh mesh, RtAccelerationStructure blas) {
        RtSectionBuildIntentState.Intent intent = buildIntents.require(mesh.key());
        replaceSectionBlas(
                mesh,
                blas,
                intent.contentRevision(),
                Objects.requireNonNull(sourceStore.publication(mesh.key()), "section source publication")
                        .requireMaterial(),
                intent.causality()
        );
    }

    private void replaceSectionBlas(
            SectionTriangleMesh mesh,
            RtAccelerationStructure blas,
            long contentRevision,
            RtSceneMaterialTable.SectionMaterial activeMaterial
    ) {
        replaceSectionBlas(mesh, blas, contentRevision, activeMaterial,
                Objects.requireNonNull(buildIntents.causality(mesh.key()), "section causality"));
    }

    private void replaceSectionBlas(
            SectionTriangleMesh mesh,
            RtAccelerationStructure blas,
            long contentRevision,
            RtSceneMaterialTable.SectionMaterial activeMaterial,
            RendererFrameCausality causality
    ) {
        replaceSectionBlas(mesh, blas, contentRevision, activeMaterial, causality, -1L);
    }

    private void replaceSectionBlas(
            SectionTriangleMesh mesh,
            RtAccelerationStructure blas,
            long contentRevision,
            RtSceneMaterialTable.SectionMaterial activeMaterial,
            RendererFrameCausality causality,
            long buildSequence
    ) {
        boolean recordPhases = RtSceneWorkFlightRecorder.sectionApplyEnabled();
        long totalStartNanos = recordPhases ? System.nanoTime() : 0L;
        long phaseStartNanos = totalStartNanos;
        SectionKey key = mesh.key();
        RtSceneMaterialTable.SectionMaterial material = activeMaterial == null
                ? null
                : activeMaterial.detachedPublication();
        if (material == null) {
            throw new IllegalStateException("missing RT section source material for " + key);
        }
        long nextGeometryRevision = revisions.nextGeometry();
        long prepareNanos = recordPhases ? System.nanoTime() - phaseStartNanos : 0L;

        phaseStartNanos = recordPhases ? System.nanoTime() : 0L;
        RtSectionBlasResidentStore.Install install = residentStore.prepareInstall(
                key,
                blas,
                mesh.triangleCount(),
                material
        );
        if (sourceStore.mesh(key) == null) {
            recordSourceMesh(mesh, material, true, !material.equals(install.previousMaterial()));
        }
        RtAccelerationStructure previous = install.previousBlas();
        if (previous != null) {
            diagnostics.causality().section(
                    RtCausalitySink.Stage.BLAS_REMOVED,
                    key,
                    activeContentState.revisionOrDefault(key, -1L),
                    nextGeometryRevision,
                    previous.storageBytes(),
                    1
            );
            retireSectionBlas(
                    key,
                    nextGeometryRevision,
                    previous,
                    RtSectionBlasRetirementLifecycle.Reason.REPLACEMENT
            );
        }
        residentStore.publish(install);
        long residentInstallNanos = recordPhases ? System.nanoTime() - phaseStartNanos : 0L;

        phaseStartNanos = recordPhases ? System.nanoTime() : 0L;
        lifecycleMemberships.addResident(key);
        if (previous == null) {
            lifecycleMemberships.addActive(key);
        }
        activeContentState.install(key, contentRevision, causality);
        diagnostics.causality().sectionGeneration(
                causality,
                key,
                contentRevision,
                nextGeometryRevision,
                sourceStore.materialGeneration(key),
                -1L,
                sourceStore.geometryGeneration(key),
                -1L,
                -1L,
                0
        );
        firstFrontProgress.recordLifecycle(
                "activeInstalled",
                key,
                -1L,
                buildIntents.revisionOrDefault(key, -1L),
                activeContentState.revisionOrDefault(key, -1L),
                buildIntents.sourceFlagsOrDefault(key, 0),
                previous != null,
                true,
                false
        );
        diagnostics.causality().firstFrontSection(
                "blasActiveInstalled",
                key,
                contentRevision,
                "triangles=" + mesh.triangleCount() + ", previousActive=" + (previous != null)
        );
        diagnostics.causality().section(
                RtCausalitySink.Stage.BLAS_ACTIVE,
                key,
                contentRevision,
                nextGeometryRevision,
                mesh.triangleCount(),
                previous == null ? 0 : 1
        );
        long activePublicationNanos = recordPhases ? System.nanoTime() - phaseStartNanos : 0L;

        phaseStartNanos = recordPhases ? System.nanoTime() : 0L;
        updateMaterialSlot(key, material, contentRevision);
        long materialSlotNanos = recordPhases ? System.nanoTime() - phaseStartNanos : 0L;

        phaseStartNanos = recordPhases ? System.nanoTime() : 0L;
        statistics.builtSection(mesh.triangleCount(), residentStore.cachedBytes());
        revisions.commitGeometry(nextGeometryRevision);
        publishGeometryTopologyRevision();
        blasLifecycleFlightRecorder.applied(
                blas,
                key,
                contentRevision,
                buildSequence,
                revisions.geometry(),
                causality,
                previous == null ? "installed" : "replaced"
        );
        if (contentRevision == buildIntents.revisionOrDefault(key, -1L)) {
            buildPriorityState.resolveInteractive(key);
        }
        long bookkeepingNanos = recordPhases ? System.nanoTime() - phaseStartNanos : 0L;

        phaseStartNanos = recordPhases ? System.nanoTime() : 0L;
        releaseCompletedSourceMesh(mesh);
        long sourceReleaseNanos = recordPhases ? System.nanoTime() - phaseStartNanos : 0L;

        phaseStartNanos = recordPhases ? System.nanoTime() : 0L;
        trimSourceMeshesUntilWithinBudget(null);
        long sourceTrimNanos = recordPhases ? System.nanoTime() - phaseStartNanos : 0L;

        phaseStartNanos = recordPhases ? System.nanoTime() : 0L;
        evictCachedSectionsUntilWithinBudget(key);
        long residentEvictionNanos = recordPhases ? System.nanoTime() - phaseStartNanos : 0L;
        if (recordPhases) {
            RtSceneWorkFlightRecorder.recordSectionApply(
                    key,
                    contentRevision,
                    buildSequence,
                    mesh.triangleCount(),
                    previous != null,
                    System.nanoTime() - totalStartNanos,
                    prepareNanos,
                    residentInstallNanos,
                    activePublicationNanos,
                    materialSlotNanos,
                    bookkeepingNanos,
                    sourceReleaseNanos,
                    sourceTrimNanos,
                    residentEvictionNanos
            );
        }
    }

    private void recordBuildBatchTelemetry(List<SectionTriangleMesh> meshes, long elapsedNanos) {
        long triangles = 0L;
        for (SectionTriangleMesh mesh : meshes) {
            triangles = Math.addExact(triangles, mesh.triangleCount());
        }
        statistics.recordBuildBatch(meshes.size(), triangles, elapsedNanos);
    }

    private void recordBuildBatchTelemetry(int sectionCount, long triangleCount, long elapsedNanos) {
        statistics.recordBuildBatch(sectionCount, triangleCount, elapsedNanos);
    }

    private void recordAsyncBuildLatencyTelemetry(long elapsedNanos) {
        statistics.recordAsyncBuildLatency(elapsedNanos);
    }

    private void recordPendingBuildEdge(
            RtPendingSectionBlasBuild pending,
            String edge,
            Set<SectionKey> preferredSectionKeys,
            long elapsedNanos
    ) {
        if (!diagnostics.edges().verboseIoEnabled()) {
            return;
        }
        int foregroundSections = pending.activePreferredSectionCount(preferredSectionKeys);
        if (!pending.foregroundSubmission() && foregroundSections == 0) {
            return;
        }
        top.ceroxe.mcvulkanrt.renderer.RendererLog.info(
                "rt section BLAS lifecycle: T={}ms, sequence={}, edge={}, activeSections={}, "
                        + "foregroundSections={}, elapsedMs={}",
                diagnostics.edges().elapsedMillis(),
                pending.sequence(),
                edge,
                pending.activeSectionCount(),
                foregroundSections,
                elapsedNanos / 1_000_000L
        );
    }

    private void updateMaterialSlot(
            SectionKey key,
            RtSceneMaterialTable.SectionMaterial material,
            long contentRevision
    ) {
        if (contentRevision < 0L) {
            throw new IllegalArgumentException("material slot content revision must not be negative");
        }
        materials.submit(key, material);
        Integer materialSlot = materials.slotFor(key);
        if (materialSlot == null) {
            throw new IllegalStateException("submitted section material has no stable slot: " + key);
        }
        int sourceFlags = buildIntents.sourceFlagsOrDefault(key, 0);
        SectionLifecycleFlightRecorder.record(
                SectionLifecycleFlightRecorder.STAGE_MATERIAL_SLOT_INSTALLED,
                (sourceFlags & SceneUpdateBatch.SOURCE_BLOCK_MUTATION) != 0
                        ? SectionLifecycleFlightRecorder.SOURCE_BLOCK_MUTATION
                        : SectionLifecycleFlightRecorder.SOURCE_UNKNOWN,
                SectionLifecycleFlightRecorder.OUTCOME_ACCEPTED,
                key,
                0,
                materials.revision(),
                contentRevision,
                Integer.toUnsignedLong(material.faceRecordHash()),
                sourceFlags,
                material.faceCount(),
                materialSlot
        );
    }

    private void enqueuePendingBuild(SectionTriangleMesh mesh) {
        pendingBuilds.enqueue(
                mesh,
                queuedSectionBuild(mesh),
                workLaneFor(mesh.key())
        );
    }

    private SectionWorkLane workLaneFor(SectionKey key) {
        if (buildPriorityState.isInteractive(key)) {
            return SectionWorkLane.INTERACTIVE;
        }
        if (buildPriorityState.preferredKeys().contains(key)) {
            return SectionWorkLane.FOREGROUND;
        }
        int sourceFlags = buildIntents.sourceFlagsOrDefault(key, 0);
        return SectionWorkLane.fromSourceFlags(sourceFlags, false);
    }

    private boolean enqueuePendingBuildIfUnowned(SectionTriangleMesh mesh) {
        Objects.requireNonNull(mesh, "mesh");
        if (pendingBuildOwnership.ownsSection(mesh.key(), pendingBuilds)) {
            return false;
        }
        enqueuePendingBuild(mesh);
        return true;
    }

    private RtSectionBlasBuildMetadata queuedSectionBuild(SectionTriangleMesh mesh) {
        RtSectionBuildIntentState.Intent intent = buildIntents.require(mesh.key());
        return new RtSectionBlasBuildMetadata(
                intent.contentRevision(),
                intent.causality(),
                intent.sourceFlags(),
                Objects.requireNonNull(sourceStore.publication(mesh.key()), "section source publication")
                        .requireMaterial()
                        .detachedPublication()
        );
    }

    private void recordSourceMesh(
            SectionTriangleMesh mesh,
            RtSceneMaterialTable.SectionMaterial material,
            boolean forceProxyGeometryGeneration,
            boolean materialChanged
    ) {
        RtSectionBuildIntentState.Intent intent = buildIntents.require(mesh.key());
        RtSectionSourceStore.Mutation mutation = sourceStore.publish(
                mesh,
                material,
                intent.contentRevision(),
                intent.causality(),
                forceProxyGeometryGeneration,
                materialChanged
        );
        if (mutation.publicationAdded()) {
            lifecycleMemberships.addResident(mesh.key());
        }
    }

    private static boolean baseGeometryMatches(SectionTriangleMesh first, SectionTriangleMesh second) {
        return first.hasSameBaseGeometry(second);
    }

    private void releaseSourceMeshPayload(SectionKey key) {
        sourceStore.releasePayload(key);
    }

    private void removeUnretainedSourcePublication(SectionKey key) {
        RtSectionSourceStore.Mutation mutation = sourceStore.removeIfUnretained(
                key,
                residentStore.contains(key),
                foregroundState.retainedPresentationKeys().contains(key)
        );
        if (mutation.publicationRemoved()) {
            lifecycleMemberships.removeResident(key);
        }
    }

    private void removeSourcePublication(SectionKey key) {
        long contentRevision = activeContentState.revisionOrDefault(key, -1L);
        int sourceFlags = buildIntents.sourceFlagsOrDefault(key, 0);
        RtSectionSourceStore.Mutation mutation = sourceStore.remove(key);
        if (mutation.publicationRemoved()) {
            lifecycleMemberships.removeResident(key);
        }
        SectionLifecycleFlightRecorder.record(
                SectionLifecycleFlightRecorder.STAGE_NATIVE_SOURCE_RETIRED,
                (sourceFlags & SceneUpdateBatch.SOURCE_BLOCK_MUTATION) != 0
                        ? SectionLifecycleFlightRecorder.SOURCE_BLOCK_MUTATION
                        : SectionLifecycleFlightRecorder.SOURCE_UNKNOWN,
                mutation.publicationRemoved()
                        ? SectionLifecycleFlightRecorder.OUTCOME_ACCEPTED
                        : SectionLifecycleFlightRecorder.OUTCOME_STALE,
                key,
                0,
                revisions.geometry(),
                contentRevision,
                -1L,
                sourceFlags,
                0,
                mutation.payloadBytesReleased()
        );
    }

    /**
     * A completed exact BLAS owns all position/index data on the native side;
     * keeping the same Java mesh merely to prove TLAS liveness was the direct
     * cause of the 32-chunk heap runaway. Do not release a newer source that
     * arrived while this result was recording or executing.
     */
    private void releaseCompletedSourceMesh(SectionTriangleMesh completedMesh) {
        sourceStore.releaseCompletedPayload(completedMesh);
    }

    /**
     * Bounds only CPU staging ownership. Pending recording/GPU submissions
     * retain their own mesh reference and are never invalidated here. A source
     * dropped before native ownership is established is re-requested from the
     * renderer-owned voxel snapshot by RendererCore, so an LRU decision cannot
     * silently become missing terrain.
     */
    private RtSectionSourceStore.TrimResult trimSourceMeshesUntilWithinBudget(SectionKey protectedKey) {
        if (!sourceStore.overBudget()) {
            return sourceStore.trimToBudget(protectedKey, Set.of());
        }
        Set<SectionKey> buildOwnedSectionKeys = new HashSet<>(pendingBuilds.snapshotMembership());
        asyncBuildInventory.addActiveKeysTo(buildOwnedSectionKeys);
        return sourceStore.trimToBudget(protectedKey, buildOwnedSectionKeys);
    }

    private long advanceResourceRevision() {
        return revisions.advanceGeometry();
    }

    private RtFarFieldBlasCache.RevisionSink revisionSink() {
        return farFieldRevisionSink;
    }

    static int grownMaterialSlotFaceCapacity(int currentCapacity, int requiredFaces) {
        return RtMaterialState.grownFaceCapacity(currentCapacity, requiredFaces);
    }

    private void tombstoneMaterialSlot(SectionKey key) {
        long contentRevision = activeContentState.revisionOrDefault(key, -1L);
        int sourceFlags = buildIntents.sourceFlagsOrDefault(key, 0);
        materials.remove(key);
        SectionLifecycleFlightRecorder.record(
                SectionLifecycleFlightRecorder.STAGE_NATIVE_MATERIAL_RETIRED,
                (sourceFlags & SceneUpdateBatch.SOURCE_BLOCK_MUTATION) != 0
                        ? SectionLifecycleFlightRecorder.SOURCE_BLOCK_MUTATION
                        : SectionLifecycleFlightRecorder.SOURCE_UNKNOWN,
                SectionLifecycleFlightRecorder.OUTCOME_ACCEPTED,
                key,
                0,
                materials.revision(),
                contentRevision,
                -1L,
                sourceFlags,
                materials.activeSlotCount(),
                materials.freeSlotCount()
        );
    }

    private void logMaterialSnapshot(String stage, RtSceneMaterialTable.Snapshot snapshot) {
        RtSceneMaterialTable.SnapshotSignature signature = snapshot.signature();
        diagnostics.materials().materialSnapshotChanged(
                stage,
                snapshot.revision(),
                signature.sectionCount(),
                signature.faceCount(),
                signature.fallbackColorFaceCount(),
                signature.fluidFaceCount(),
                signature.emissiveFaceCount(),
                signature.textureRecords(),
                signature.texturePixels(),
                signature.textureRevision(),
                snapshot.instanceLayoutHash(),
                signature.sectionRecordHash(),
                signature.faceRecordHash()
        );
    }

    private void removeSections(Set<SectionKey> removedSectionKeys) {
        for (SectionKey key : removedSectionKeys) {
            invalidatePendingAsyncBuild(key);
            pendingBuilds.cancel(key);
            deferredEmptySections.resolve(key);
            removeSection(key);
        }
    }

    public static boolean deferEmptySectionRemoval(boolean activeBlasPresent, boolean retainedByPresentation) {
        return deferEmptySectionRemoval(activeBlasPresent, retainedByPresentation, 0);
    }

    public static boolean deferEmptySectionRemoval(
            boolean activeBlasPresent,
            boolean retainedByPresentation,
            int sourceFlags
    ) {
        return RtDeferredEmptySectionState.shouldDefer(
                activeBlasPresent,
                retainedByPresentation,
                sourceFlags
        );
    }

    private void releaseUnretainedEmptySections() {
        for (SectionKey key : deferredEmptySections.releaseUnretained(
                foregroundState.retainedPresentationKeys()
        )) {
            removeSection(key);
        }
    }

    private void removeSection(SectionKey key) {
        RtAccelerationStructure removed = residentStore.get(key);
        long contentRevision = activeContentState.revisionOrDefault(key, -1L);
        int sourceFlags = buildIntents.sourceFlagsOrDefault(key, 0);
        long retiredBlasBytes = 0L;
        if (removed != null) {
            long nextGeometryRevision = revisions.nextGeometry();
            diagnostics.causality().section(
                    RtCausalitySink.Stage.BLAS_REMOVED,
                    key,
                    activeContentState.revisionOrDefault(key, -1L),
                    nextGeometryRevision,
                    removed.storageBytes(),
                    0
            );
            retireSectionBlas(
                    key,
                    nextGeometryRevision,
                    removed,
                    RtSectionBlasRetirementLifecycle.Reason.EXPLICIT_REMOVAL
            );
            retiredBlasBytes = removed.storageBytes();
            RtSectionBlasResidentStore.Removal removal = residentStore.remove(key);
            if (removal.blas() != removed) {
                throw new IllegalStateException("retired section BLAS changed before resident removal: " + key);
            }
            activeViewAssembler.removeSection(key);
            lifecycleMemberships.removeActive(key);
            removeActiveSectionContent(key);
            tombstoneMaterialSlot(key);
            statistics.removedSection(false);
            revisions.commitGeometry(nextGeometryRevision);
            publishGeometryTopologyRevision();
        }
        removeSourcePublication(key);
        /* A missing BLAS is an accounted state, not an invisible early return. */
        SectionLifecycleFlightRecorder.record(
                SectionLifecycleFlightRecorder.STAGE_NATIVE_BLAS_RETIRED,
                (sourceFlags & SceneUpdateBatch.SOURCE_BLOCK_MUTATION) != 0
                        ? SectionLifecycleFlightRecorder.SOURCE_BLOCK_MUTATION
                        : SectionLifecycleFlightRecorder.SOURCE_UNKNOWN,
                removed != null
                        ? SectionLifecycleFlightRecorder.OUTCOME_ACCEPTED
                        : SectionLifecycleFlightRecorder.OUTCOME_STALE,
                key,
                0,
                revisions.geometry(),
                contentRevision,
                -1L,
                sourceFlags,
                residentStore.size(),
                retiredBlasBytes
        );
        if (!residentStore.contains(key) && !sourceStore.containsPublication(key)) {
            lifecycleMemberships.removeResident(key);
        }
        buildIntents.remove(key);
        buildPriorityState.resolveInteractive(key);
    }

    private void evictCachedSectionsUntilWithinBudget(SectionKey protectedKey) {
        while (cachedBlasResourcesOverBudget() && residentStore.size() > 1) {
            SectionKey evictedKey = firstEvictableCachedSection(residentStore.keys(), protectedKey);
            if (evictedKey == null) {
                return;
            }
            RtAccelerationStructure evicted = Objects.requireNonNull(
                    residentStore.get(evictedKey),
                    "selected section BLAS"
            );
            long nextGeometryRevision = revisions.nextGeometry();
            lifecycleMemberships.removeActive(evictedKey);
            activeViewAssembler.removeSection(evictedKey);
            diagnostics.causality().section(
                    RtCausalitySink.Stage.BLAS_REMOVED,
                    evictedKey,
                    activeContentState.revisionOrDefault(evictedKey, -1L),
                    nextGeometryRevision,
                    evicted.storageBytes(),
                    2
            );
            retireSectionBlas(
                    evictedKey,
                    nextGeometryRevision,
                    evicted,
                    RtSectionBlasRetirementLifecycle.Reason.BUDGET_EVICTION
            );
            RtSectionBlasResidentStore.Removal removal = residentStore.remove(evictedKey);
            if (removal.blas() != evicted) {
                throw new IllegalStateException("evicted section BLAS changed before resident removal: " + evictedKey);
            }
            removeActiveSectionContent(evictedKey);
            tombstoneMaterialSlot(evictedKey);
            releaseSourceMeshPayload(evictedKey);
            statistics.removedSection(true);
            revisions.commitGeometry(nextGeometryRevision);
            publishGeometryTopologyRevision();
        }
    }

    /**
     * Publishes the scheduler token at the ownership mutation itself. A stats query can therefore
     * observe new topology without materializing the O(active sections) TLAS input; the latter is
     * built once, only after the world scheduler actually decides to submit a successor.
     */
    private void publishGeometryTopologyRevision() {
        revisions.publishGeometryToScene();
    }

    /**
     * Selects an active-cache slot without invalidating either the successor or an in-flight build.
     *
     * <p>The bound front keeps its own immutable TLAS/material publication, while removed BLAS
     * resources enter {@link #retirementQueue} until the protected resource revision passes.
     * Consequently a committed-front member that is absent from the current successor may release
     * its active cache slot immediately; retaining it in both owners would require an overlap budget
     * and let every camera step grow the dense active map beyond its configured limit.</p>
     */
    private SectionKey firstEvictableCachedSection(Iterable<SectionKey> sectionKeys, SectionKey protectedKey) {
        return firstEvictableSection(
                sectionKeys,
                protectedKey,
                foregroundState.retainedPresentationKeys(),
                foregroundState.authority()
        );
    }

    public static SectionKey firstEvictableSection(
            Iterable<SectionKey> sectionKeys,
            SectionKey protectedKey,
            Set<SectionKey> retainedPresentationSectionKeys,
            Set<SectionKey> successorSectionKeys
    ) {
        Objects.requireNonNull(sectionKeys, "sectionKeys");
        Objects.requireNonNull(retainedPresentationSectionKeys, "retainedPresentationSectionKeys");
        Objects.requireNonNull(successorSectionKeys, "successorSectionKeys");
        for (SectionKey key : sectionKeys) {
            Objects.requireNonNull(key, "section key");
            if (!key.equals(protectedKey)
                    && !retainedPresentationSectionKeys.contains(key)
                    && !successorSectionKeys.contains(key)) {
                return key;
            }
        }
        for (SectionKey key : sectionKeys) {
            if (!key.equals(protectedKey)
                    && retainedPresentationSectionKeys.contains(key)
                    && !successorSectionKeys.contains(key)) {
                return key;
            }
        }
        return null;
    }

    public static int activeSlotsToReleaseForSuccessor(
            int capacity,
            int activeSections,
            int missingSuccessorSections
    ) {
        if (capacity <= 0 || activeSections < 0 || activeSections > capacity
                || missingSuccessorSections < 0 || missingSuccessorSections > capacity) {
            throw new IllegalArgumentException("invalid active-slot successor accounting");
        }
        return Math.max(0, missingSuccessorSections - (capacity - activeSections));
    }

    private boolean cachedResourcesOverBudget() {
        return cachedBlasResourcesOverBudget() || sourceStore.overBudget();
    }

    /**
     * Removes the paired provenance owned by one live BLAS generation.
     *
     * <p>Revision and causality are one publication.  Allowing eviction paths to delete only one
     * map leaves inactive sections visible to debug/JFR queries and makes later TLAS snapshots
     * depend on which removal path ran.  Fail fast if an earlier mutation already split the pair.</p>
     */
    private void removeActiveSectionContent(SectionKey key) {
        activeContentState.remove(Objects.requireNonNull(key, "key"));
    }

    private boolean cachedBlasResourcesOverBudget() {
        return residentStore.size() > configuration.configuredMaxCachedSections()
                || residentStore.cachedBytes() > configuration.maxCachedBytes();
    }

    private void retireAllSections(long safeAfterRevision) {
        for (Map.Entry<SectionKey, RtAccelerationStructure> entry : residentStore.blases().entrySet()) {
            retireSectionBlas(
                    entry.getKey(),
                    safeAfterRevision,
                    entry.getValue(),
                    RtSectionBlasRetirementLifecycle.Reason.FULL_RESYNC
            );
        }
    }

    private void retireSectionBlas(
            SectionKey key,
            long safeAfterRevision,
            RtAccelerationStructure blas,
            RtSectionBlasRetirementLifecycle.Reason reason
    ) {
        retirementLifecycle.retire(
                key,
                safeAfterRevision,
                blas,
                revisions.geometry(),
                activeContentState.revisionOrDefault(key, -1L),
                -1L,
                activeContentState.causalityOrDefault(key, RendererFrameCausality.untraced(0L)),
                reason
        );
    }

    private void closeRetiredBlasesThrough(long protectedRevision) {
        retirementLifecycle.releaseThrough(protectedRevision);
    }

    public static boolean retiredBlasIsReleasable(long safeAfterRevision, long protectedRevision) {
        return RtSectionBlasRetirementLifecycle.isReleasable(safeAfterRevision, protectedRevision);
    }

    private void invalidatePendingAsyncBuild(SectionKey key) {
        RtSectionAsyncBuildInventory.Invalidation invalidation = asyncBuildInventory.invalidate(key);
        for (long sequence : invalidation.gpuSequences()) {
            recordAsyncInvalidation("gpuBuildInvalidated", key, sequence);
        }
        for (long sequence : invalidation.recordingSequences()) {
            recordAsyncInvalidation("cpuRecordingInvalidated", key, sequence);
        }
        pendingBuildOwnership.invalidateAsync(key);
    }

    private void recordAsyncInvalidation(String stage, SectionKey key, long sequence) {
        firstFrontProgress.recordLifecycle(
                stage,
                key,
                sequence,
                buildIntents.revisionOrDefault(key, -1L),
                activeContentState.revisionOrDefault(key, -1L),
                buildIntents.sourceFlagsOrDefault(key, 0),
                residentStore.contains(key),
                false,
                true
        );
    }

    private void invalidatePendingAsyncBuilds() {
        asyncBuildInventory.invalidateAll();
        pendingBuildOwnership.clearAsync();
    }

    private boolean hasAnyPendingSectionBuilds() {
        return !pendingBuilds.isEmpty() || asyncBuildInventory.hasActiveSections();
    }

    private int pendingSectionBuildCount() {
        return Math.addExact(pendingBuilds.size(), pendingAsyncSectionBuildCount());
    }

    private long pendingSectionBuildTriangles() {
        return Math.addExact(pendingBuilds.pendingTriangles(), pendingAsyncTriangleBuildCount());
    }

    private long pendingSectionBuildBytes() {
        return Math.addExact(pendingBuilds.pendingBytes(), pendingAsyncBuildBytes());
    }

    private int pendingAsyncSectionBuildCount() {
        return asyncBuildInventory.activeSectionCount();
    }

    private long pendingAsyncTriangleBuildCount() {
        return asyncBuildInventory.activeTriangleCount();
    }

    private long pendingAsyncBuildBytes() {
        return asyncBuildInventory.activeEstimatedBytes();
    }

    private RtSectionAsyncBuildInventory.Metrics pendingAsyncMetrics() {
        return asyncBuildInventory.metrics(buildPriorityState.preferredKeys());
    }

    private boolean hasPendingForegroundBuilds() {
        return hasPendingPriorityBuilds(buildPriorityState.preferredKeys());
    }

    private boolean hasPendingPriorityBuilds(Set<SectionKey> foregroundSectionKeys) {
        return pendingBuilds.hasPreferred(buildPriorityState.interactiveKeys())
                || pendingBuilds.hasPreferred(foregroundSectionKeys)
                || asyncBuildInventory.hasPendingPreferredRecording(buildPriorityState.interactiveKeys())
                || asyncBuildInventory.hasPendingPreferredRecording(foregroundSectionKeys);
    }

    private boolean hasIncompleteForegroundCoverage() {
        return firstFrontProgress.activeCoverageIncomplete(
                foregroundState.authority(),
                foregroundState.authorityRevision(),
                lifecycleMemberships.active(),
                lifecycleMemberships.activeRevision()
        );
    }

    synchronized boolean initialForegroundCoverageIncomplete() {
        return hasIncompleteForegroundCoverage();
    }

    synchronized boolean authoritativeViewEstablished() {
        return foregroundState.view().authoritative();
    }

    synchronized boolean authoritativeForegroundEstablished() {
        return !foregroundState.authority().isEmpty();
    }

    public synchronized boolean boundFrontCoversAuthoritativeForeground(Set<SectionKey> boundSectionKeys) {
        Objects.requireNonNull(boundSectionKeys, "boundSectionKeys");
        PackedSectionMembership bound = boundSectionKeys instanceof PackedSectionMembership packed
                ? packed
                : PackedSectionMembership.copyOf(boundSectionKeys);
        return firstFrontProgress.boundCovers(
                foregroundState.authority(),
                foregroundState.authorityRevision(),
                lifecycleMemberships.active(),
                lifecycleMemberships.activeRevision(),
                bound
        );
    }

    /**
     * Returns the immutable native view generation once the already-bound
     * world publication proves the same geometry and content.
     *
     * <p>A successor can become physically resident without changing the TLAS
     * topology revision: the persistent active-slot table may already contain
     * every successor primitive. In that case rebuilding an identical TLAS is
     * wasteful, but leaving the old view identity attached to the shared frame
     * prevents the presentation epoch from committing the completed work. The
     * BLAS owner is the only component that can prove both active content and
     * authoritative membership, so the device must not reconstruct this proof
     * from unrelated counters.</p>
     */
    public synchronized RendererViewState boundWorldViewPromotion(
            Set<SectionKey> boundSectionKeys,
            Map<SectionKey, Long> boundSectionContentRevisions,
            long boundViewRevision
    ) {
        Objects.requireNonNull(boundSectionKeys, "boundSectionKeys");
        Objects.requireNonNull(boundSectionContentRevisions, "boundSectionContentRevisions");
        RendererViewState view = foregroundState.view();
        PackedSectionMembership authority = foregroundState.authority();
        if (!view.authoritative()
                || view.revision() <= boundViewRevision
                || authority.isEmpty()
                || hasIncompleteForegroundCoverage()
                || boundSectionKeys.size() < authority.size()) {
            return null;
        }
        for (SectionKey key : authority) {
            if (!boundSectionKeys.contains(key)) {
                return null;
            }
            Long activeRevision = activeContentState.revision(key);
            if (activeRevision == null
                    || activeRevision.longValue()
                    != SectionRevisionSnapshot.valueOrDefault(
                            boundSectionContentRevisions,
                            key,
                            Long.MIN_VALUE
                    )) {
                return null;
            }
        }
        return view;
    }

    public synchronized void recordFirstFrontProgress(
            Set<SectionKey> boundSectionKeys,
            RtCore.RuntimeActivity runtimeActivity
    ) {
        Objects.requireNonNull(boundSectionKeys, "boundSectionKeys");
        Objects.requireNonNull(runtimeActivity, "runtimeActivity");
        PackedSectionMembership authority = foregroundState.authority();
        if (authority.isEmpty()) {
            return;
        }
        PackedSectionMembership boundMembership = boundSectionKeys instanceof PackedSectionMembership packed
                ? packed
                : PackedSectionMembership.copyOf(boundSectionKeys);
        firstFrontProgress.recordProgress(
                foregroundState.view().revision(),
                authority,
                foregroundState.authorityRevision(),
                sourceStore.membership(),
                sourceStore.membershipRevision(),
                pendingBuilds.snapshotMembership(),
                pendingBuilds.membershipRevision(),
                asyncBuildInventory.recordingMembership(),
                asyncBuildInventory.recordingRevision(),
                asyncBuildInventory.gpuMembership(),
                asyncBuildInventory.gpuRevision(),
                lifecycleMemberships.active(),
                lifecycleMemberships.activeRevision(),
                boundMembership,
                Long.MIN_VALUE,
                runtimeActivity
        );
    }

    public static boolean boundFrontCoversAuthoritativeForeground(
            Set<SectionKey> authoritativeForegroundSectionKeys,
            boolean foregroundCoverageIncomplete,
            Set<SectionKey> boundSectionKeys
    ) {
        Objects.requireNonNull(authoritativeForegroundSectionKeys, "authoritativeForegroundSectionKeys");
        Objects.requireNonNull(boundSectionKeys, "boundSectionKeys");
        return !authoritativeForegroundSectionKeys.isEmpty()
                && !foregroundCoverageIncomplete
                && boundSectionKeys.containsAll(authoritativeForegroundSectionKeys);
    }

    public static Set<SectionKey> provisionalBuildPriorityKeys(
            boolean authoritativeForegroundEstablished,
            Set<SectionKey> currentPriorityKeys,
            Collection<SectionKey> incomingMeshKeys
    ) {
        return RtSectionBuildPriorityState.provisionalKeys(
                authoritativeForegroundEstablished,
                currentPriorityKeys,
                incomingMeshKeys
        );
    }

    private boolean asyncBuildCapacityAvailable(boolean foregroundPending) {
        RtSectionAsyncBuildInventory.Metrics metrics = pendingAsyncMetrics();
        boolean totalCapacityAvailable = metrics.totalBatches() < effectiveMaxAsyncBuildsInFlight
                && metrics.retainedSections() < configuration.maxAsyncBuildSectionsInFlight()
                && metrics.retainedBytes() < configuration.maxAsyncBuildBytesInFlight();
        if (foregroundPending || !totalCapacityAvailable) {
            return totalCapacityAvailable;
        }
        return metrics.backgroundBatches() < backgroundBatchCapacity(effectiveMaxAsyncBuildsInFlight)
                && metrics.backgroundRetainedSections()
                < backgroundCapacity(configuration.maxAsyncBuildSectionsInFlight())
                && metrics.backgroundRetainedBytes()
                < backgroundCapacity(configuration.maxAsyncBuildBytesInFlight());
    }

    public static int backgroundCapacity(int capacity) {
        return RtSectionBlasAdmissionPlanner.backgroundCapacity(capacity);
    }

    public static int remainingBackgroundCapacity(int capacity, int retained) {
        return RtSectionBlasAdmissionPlanner.remainingBackgroundCapacity(capacity, retained);
    }

    public static int effectiveSubmissionWindow(int configuredCapacity, int orderedQueueCount) {
        return RtSectionBlasAdmissionPlanner.effectiveSubmissionWindow(configuredCapacity, orderedQueueCount);
    }

    public static int gpuSubmissionWindow(int orderedQueueCount) {
        return RtSectionBlasAdmissionPlanner.gpuSubmissionWindow(orderedQueueCount);
    }

    public static int backgroundBatchCapacity(int capacity) {
        return RtSectionBlasAdmissionPlanner.backgroundBatchCapacity(capacity);
    }

    public static long backgroundCapacity(long capacity) {
        return RtSectionBlasAdmissionPlanner.backgroundCapacity(capacity);
    }

    public static long remainingBackgroundCapacity(long capacity, long retained) {
        return RtSectionBlasAdmissionPlanner.remainingBackgroundCapacity(capacity, retained);
    }

    private RuntimeException closePendingAsyncBuildCollecting(RuntimeException failure) {
        int ownedBatches = asyncBuildInventory.totalBatchCount();
        for (int index = 0; index < ownedBatches; index++) {
            statistics.asyncBuildCloseWaited();
        }
        failure = asyncBuildInventory.closeCollecting(failure);
        pendingBuildOwnership.clearAsync();
        return failure;
    }

    private static RuntimeException appendFailure(RuntimeException failure, RuntimeException next) {
        if (failure == null) {
            return next;
        }
        failure.addSuppressed(next);
        return failure;
    }

    private static int suppressedCount(RuntimeException failure) {
        return failure == null ? 0 : failure.getSuppressed().length;
    }

    private static boolean failureUnchanged(
            RuntimeException before,
            int beforeSuppressedCount,
            RuntimeException after
    ) {
        return before == after && suppressedCount(after) == beforeSuppressedCount;
    }

    private RuntimeException closeAllSectionsCollecting(RuntimeException failure) {
        for (Map.Entry<SectionKey, RtAccelerationStructure> entry : residentStore.blases().entrySet()) {
            try {
                entry.getValue().close();
            } catch (RuntimeException ex) {
                RuntimeException wrapped = new IllegalStateException("failed to close section BLAS: " + entry.getKey(), ex);
                if (failure == null) {
                    failure = wrapped;
                } else {
                    failure.addSuppressed(wrapped);
                }
            }
        }
        return failure;
    }

    public static int compareBuildSubmissionPriority(
            boolean leftForeground,
            long leftSequence,
            boolean rightForeground,
            long rightSequence
    ) {
        return RtPendingSectionBlasRecording.comparePriority(
                leftForeground,
                leftSequence,
                rightForeground,
                rightSequence
        );
    }

}
