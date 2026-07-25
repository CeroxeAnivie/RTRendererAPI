package top.ceroxe.rt.renderer.rt.device;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import top.ceroxe.rt.diagnostics.VulkanRtCapabilityProbe;
import top.ceroxe.rt.renderer.*;
import top.ceroxe.rt.renderer.rt.RtSceneReadiness;
import top.ceroxe.rt.renderer.rt.acceleration.*;
import top.ceroxe.rt.renderer.rt.material.RtSceneMaterialTable;
import top.ceroxe.rt.renderer.rt.material.RtTextureCatalog;
import top.ceroxe.rt.renderer.rt.pipeline.RtFrameSnapshot;
import top.ceroxe.rt.renderer.rt.pipeline.RtGBufferSnapshot;
import top.ceroxe.rt.renderer.rt.pipeline.RtRayTracingPipeline;
import top.ceroxe.rt.renderer.rt.pipeline.RtRayTracingPipelineProperties;
import top.ceroxe.rt.renderer.rt.runtime.GuardedRtCore;
import top.ceroxe.rt.renderer.rt.runtime.RtCore;
import top.ceroxe.rt.renderer.scene.SceneUpdateBatch;
import top.ceroxe.rt.renderer.scene.SectionKey;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Streaming-scene Vulkan RT orchestration context.
 *
 * <p>Native resource graph construction belongs to {@link VulkanRtBackendResources}; this class
 * coordinates accepted scene changes, asynchronous acceleration-structure convergence, atomic
 * descriptor publication, and frame dispatch against that immutable resource graph.</p>
 */
public final class VulkanRtDeviceContext implements GuardedRtCore.NativeBackend {
    private static final ThreadMXBean THREAD_MX_BEAN = ManagementFactory.getThreadMXBean();
    private static final boolean TAKEOVER_FLIGHT_RECORDER_ENABLED =
            Boolean.getBoolean("top.ceroxe.rt.takeoverFlightRecorder.enabled");
    private final VkDevice device;
    private final RtDeviceQueueContexts queueContexts;
    private final long allocator;
    private final RtGpuBuffer bootstrapBuffer;
    private final RtAccelerationStructure bootstrapBlas;
    private final RtAccelerationStructure bootstrapTlas;
    private final RtSceneMaterialTable sceneMaterialTable;
    private final RtSectionBlasCache sectionBlasCache;
    private final RtDynamicBlasCache dynamicBlasCache;
    private final RtDynamicTlasCache dynamicTlasCache;
    private final RtWorldTlasCache worldTlasCache;
    private final RtRayTracingPipelineProperties rayTracingPipelineProperties;
    private final RtRayTracingPipeline rayTracingPipeline;
    private final String deviceName;
    private final int accelerationStructureScratchAlignment;
    private final List<String> enabledExtensions;
    private final VulkanRtExternalFrameInterop externalFrameInterop;
    private final VulkanRtSchedulingConfig scheduling = VulkanRtSchedulingConfig.fromSystemProperties();
    private final RtSceneBindCoordinator sceneBindCoordinator;
    private final RtDynamicSceneBindLane dynamicSceneBindLane;
    private final RtWorldSceneMaterialUploadLane worldSceneMaterialUploadLane;
    private final RtAcceptedSceneAuthority acceptedSceneAuthority = new RtAcceptedSceneAuthority();
    /*
     * Explicit scene-removal provenance survives the asynchronous TLAS build
     * and descriptor-bind boundary. A mere set contraction is never deletion
     * evidence: successor views legitimately omit old front sections while
     * they are still required for fail-closed presentation.
     */
    private final RtWorldSceneCoverageAuthority worldCoverageAuthority =
            new RtWorldSceneCoverageAuthority();
    private final RtInteractiveTerrainPublicationTracker interactiveTerrain =
            new RtInteractiveTerrainPublicationTracker();
    private final RtWorldSceneBindStatistics worldSceneBindStatistics = new RtWorldSceneBindStatistics();
    private final VulkanRtDeviceTelemetry telemetry = new VulkanRtDeviceTelemetry();
    private final VulkanAcceptFrameTiming acceptFrameTiming;
    private final VulkanAcceptFrameStallRecorder acceptFrameStallRecorder = new VulkanAcceptFrameStallRecorder();
    private final RendererRtDiagnostics diagnostics;

    private VulkanRtDeviceContext(VulkanRtBackendResources resources, RendererRtDiagnostics diagnostics) {
        VulkanRtBackendResources nativeResources = Objects.requireNonNull(resources, "resources");
        this.device = nativeResources.device();
        this.queueContexts = nativeResources.queueContexts();
        this.allocator = nativeResources.allocator();
        this.bootstrapBuffer = nativeResources.bootstrapBuffer();
        this.bootstrapBlas = nativeResources.bootstrapBlas();
        this.bootstrapTlas = nativeResources.bootstrapTlas();
        this.sceneMaterialTable = nativeResources.sceneMaterialTable();
        this.sectionBlasCache = nativeResources.sectionBlasCache();
        this.dynamicBlasCache = nativeResources.dynamicBlasCache();
        this.dynamicTlasCache = nativeResources.dynamicTlasCache();
        this.worldTlasCache = nativeResources.worldTlasCache();
        this.rayTracingPipelineProperties = nativeResources.rayTracingPipelineProperties();
        this.rayTracingPipeline = nativeResources.rayTracingPipeline();
        this.sceneBindCoordinator = new RtSceneBindCoordinator(RtScenePublication.bootstrap(
                bootstrapTlas,
                rayTracingPipeline.activeDescriptorGeneration(),
                diagnostics.causality()
        ));
        this.deviceName = nativeResources.deviceName();
        this.accelerationStructureScratchAlignment = nativeResources.accelerationStructureScratchAlignment();
        this.enabledExtensions = nativeResources.enabledExtensions();
        this.externalFrameInterop = nativeResources.externalFrameInterop();
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.acceptFrameTiming = new VulkanAcceptFrameTiming(diagnostics.builds());
        this.dynamicSceneBindLane = new RtDynamicSceneBindLane(
                dynamicBlasCache,
                dynamicTlasCache,
                worldTlasCache,
                rayTracingPipeline,
                sceneMaterialTable,
                queueContexts,
                sceneBindCoordinator,
                diagnostics,
                scheduling.maxConvergenceVisualStalenessNanos()
        );
        this.worldSceneMaterialUploadLane = new RtWorldSceneMaterialUploadLane(
                sceneBindCoordinator,
                worldTlasCache,
                sectionBlasCache,
                dynamicBlasCache,
                sceneMaterialTable,
                queueContexts.buildCommands(),
                diagnostics,
                worldSceneBindStatistics
        );
    }

    /**
     * Opens an independently owned context with no-op diagnostics.
     *
     * @param capability  selected capability
     * @param parentScope lifetime parent
     * @return owned context
     */
    public static VulkanRtDeviceContext open(VulkanRtCapabilityProbe.Result capability, RtResourceScope parentScope) {
        return open(capability, parentScope, RendererRtDiagnostics.noop());
    }

    /**
     * Opens a renderer-owned Vulkan instance, logical device, queues, allocator, and RT resources.
     * The core never borrows an embedding application's device or command encoder.
     *
     * @param capability  selected hardware-ready capability result
     * @param parentScope parent that assumes ownership of the new context scope
     * @param diagnostics non-null diagnostics sinks
     * @return context whose native resources are retained by {@code parentScope}
     */
    public static VulkanRtDeviceContext open(
            VulkanRtCapabilityProbe.Result capability,
            RtResourceScope parentScope,
            RendererRtDiagnostics diagnostics
    ) {
        return openIndependentUnsafe(capability, parentScope, diagnostics);
    }

    /**
     * Independent device entry retained under its historical name for diagnostic callers.
     *
     * @param capability  selected capability
     * @param parentScope lifetime parent
     * @return owned context
     */
    public static VulkanRtDeviceContext openIndependentUnsafe(VulkanRtCapabilityProbe.Result capability, RtResourceScope parentScope) {
        return openIndependentUnsafe(capability, parentScope, RendererRtDiagnostics.noop());
    }

    /**
     * Opens an independent device context and transfers its child scope to the supplied parent.
     *
     * @param capability  selected capability
     * @param parentScope lifetime parent
     * @param diagnostics diagnostics sinks
     * @return owned context
     */
    public static VulkanRtDeviceContext openIndependentUnsafe(
            VulkanRtCapabilityProbe.Result capability,
            RtResourceScope parentScope,
            RendererRtDiagnostics diagnostics
    ) {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(parentScope, "parentScope");
        Objects.requireNonNull(diagnostics, "diagnostics");
        RtResourceScope contextScope = new RtResourceScope();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanRtBackendResources nativeResources = VulkanRtBackendResources.create(
                    stack, contextScope, capability, diagnostics);
            VulkanRtDeviceContext context = new VulkanRtDeviceContext(nativeResources, diagnostics);
            contextScope.retain("pending world scene bind", context::closePendingWorldSceneBind);
            contextScope.retain("pending material-only bind", context::closePendingMaterialOnlyBind);
            contextScope.retain("pending dynamic TLAS bind", context::closePendingDynamicTlasBind);
            contextScope.retain("deferred world scene bind", context::closeDeferredWorldSceneBind);
            parentScope.retain("vulkan RT device context", contextScope);
            return context;
        } catch (RuntimeException | LinkageError | OutOfMemoryError ex) {
            contextScope.close();
            throw ex;
        }
    }

    static boolean shouldContinueForWorldTlasConvergence(
            RtSceneReadiness readiness,
            boolean pendingWorldSceneBindPresent,
            boolean deferredWorldSceneBindPresent
    ) {
        return shouldContinueForWorldTlasConvergence(
                readiness,
                pendingWorldSceneBindPresent,
                deferredWorldSceneBindPresent,
                false
        );
    }

    static boolean shouldContinueForWorldTlasConvergence(
            RtSceneReadiness readiness,
            boolean pendingWorldSceneBindPresent,
            boolean deferredWorldSceneBindPresent,
            boolean materialRevisionPending
    ) {
        return RtWorldSceneConvergencePolicy.shouldContinue(
                readiness,
                pendingWorldSceneBindPresent,
                deferredWorldSceneBindPresent,
                materialRevisionPending
        );
    }

    static boolean sectionMaterialRevisionOutranBoundSnapshot(
            long sectionMaterialRevision,
            RtSceneMaterialTable.Snapshot boundMaterialSnapshot
    ) {
        return RtWorldSceneConvergencePolicy.sectionMaterialRevisionOutranBoundSnapshot(
                sectionMaterialRevision,
                boundMaterialSnapshot
        );
    }

    static boolean shouldRunWorldTlasScheduler(
            boolean pendingWorldSceneBindPresent,
            boolean deferredWorldSceneBindPresent
    ) {
        return RtWorldSceneConvergencePolicy.shouldRunWorldTlasScheduler(
                pendingWorldSceneBindPresent,
                deferredWorldSceneBindPresent
        );
    }

    static boolean shouldRunDynamicTlasScheduler(boolean pendingDynamicTlasBindPresent) {
        return RtWorldSceneConvergencePolicy.shouldRunDynamicTlasScheduler(pendingDynamicTlasBindPresent);
    }

    static boolean capturedDescriptorGenerationIsStale(
            long capturedDescriptorGeneration,
            long currentDescriptorGeneration
    ) {
        if (capturedDescriptorGeneration <= 0L || currentDescriptorGeneration <= 0L) {
            throw new IllegalArgumentException("descriptor generations must be positive");
        }
        return capturedDescriptorGeneration != currentDescriptorGeneration;
    }

    static boolean dynamicTlasCanReuseBoundMaterialSnapshot(
            RtSceneMaterialTable.Snapshot boundSnapshot,
            RtSceneMaterialTable.Snapshot candidateSnapshot
    ) {
        return RtDynamicSceneBindLane.canReuseBoundMaterialSnapshot(boundSnapshot, candidateSnapshot);
    }

    static boolean initialWorldFrontBuildReady(
            boolean currentWorldTlasPresent,
            boolean authoritativeViewEstablished,
            boolean authoritativeForegroundEstablished,
            boolean foregroundCoverageIncomplete
    ) {
        return RtWorldSceneConvergencePolicy.initialWorldFrontBuildReady(
                currentWorldTlasPresent,
                authoritativeViewEstablished,
                authoritativeForegroundEstablished,
                foregroundCoverageIncomplete
        );
    }

    /**
     * Dirty terrain frames belong to the scene scheduler, never the stable-frame path.
     */
    static boolean shouldEvaluateStableFramePath(boolean terrainWork) {
        return RtWorldSceneConvergencePolicy.shouldEvaluateStableFramePath(terrainWork);
    }

    static boolean shouldApplyStableFrameSubmissionBackpressure(
            boolean hasSubmissionCapacity,
            boolean pendingFrame,
            long pendingFrameAgeMillis,
            long maxPendingFrameAgeBeforeBuildMillis
    ) {
        return RtWorldSceneConvergencePolicy.shouldApplyStableFrameSubmissionBackpressure(
                hasSubmissionCapacity,
                pendingFrame,
                pendingFrameAgeMillis,
                maxPendingFrameAgeBeforeBuildMillis
        );
    }

    static boolean textureCatalogRevisionOutranBoundMaterialSnapshot(
            RtSceneMaterialTable.Snapshot boundMaterialSnapshot,
            long textureCatalogRevision
    ) {
        return RtWorldSceneConvergencePolicy.textureCatalogRevisionOutranBoundSnapshot(
                boundMaterialSnapshot,
                textureCatalogRevision
        );
    }

    static boolean shouldDispatchBeforeBuildBudget(RendererFrameUpdate update, RtSceneReadiness readiness) {
        return RtWorldSceneConvergencePolicy.shouldDispatchBeforeBuildBudget(update, readiness);
    }

    private static long currentThreadCpuTimeNanos() {
        return THREAD_MX_BEAN.isCurrentThreadCpuTimeSupported()
                && THREAD_MX_BEAN.isThreadCpuTimeEnabled()
                ? THREAD_MX_BEAN.getCurrentThreadCpuTime()
                : -1L;
    }

    private static long cpuDurationNanos(long startNanos, long endNanos) {
        return startNanos < 0L || endNanos < startNanos ? -1L : endNanos - startNanos;
    }

    static boolean preBuildDispatchRequiresReadiness(RendererFrameUpdate update) {
        return RtWorldSceneConvergencePolicy.preBuildDispatchRequiresReadiness(update);
    }

    static boolean isBoundWorldTlasSafeForForegroundStreaming(
            RendererFrameUpdate update,
            RtSceneReadiness readiness
    ) {
        return RtWorldSceneConvergencePolicy.isBoundWorldTlasSafeForForegroundStreaming(update, readiness);
    }

    static boolean shouldDeferBackgroundConvergence(
            RendererFrameUpdate update,
            RtSceneReadiness readiness,
            boolean dispatchedBeforeBuildBudget,
            boolean pendingFrame,
            long pendingFrameAgeMillis,
            long maxPendingFrameAgeBeforeBuildMillis,
            long elapsedNanos,
            long foregroundFrameBudgetNanos
    ) {
        Objects.requireNonNull(update, "update");
        Objects.requireNonNull(readiness, "readiness");
        if (pendingFrameAgeMillis < 0L) {
            throw new IllegalArgumentException("pendingFrameAgeMillis must not be negative");
        }
        if (maxPendingFrameAgeBeforeBuildMillis < 0L) {
            throw new IllegalArgumentException("maxPendingFrameAgeBeforeBuildMillis must not be negative");
        }
        if (elapsedNanos < 0L) {
            throw new IllegalArgumentException("elapsedNanos must not be negative");
        }
        if (foregroundFrameBudgetNanos < 0L) {
            throw new IllegalArgumentException("foregroundFrameBudgetNanos must not be negative");
        }

        /*
         * A stale streaming TLAS is useful for player-visible continuity, but it
         * is not a reason to starve BLAS/TLAS convergence. The native fluid gate
         * caught the failure mode here: dispatching the same partial 32/96 scene
         * forever kept the probe-visible water section out of the world TLAS.
         *
         * Once the scene is current, foreground pacing may protect an in-flight
         * frame. Before that, every pump must continue feeding bounded background
         * resource work so streaming, block edits and fluid replacements converge.
         */
        if (!readiness.builtRevisionIsCurrent() || readiness.hasPendingRtBuilds()) {
            return false;
        }
        boolean foregroundSensitive = dispatchedBeforeBuildBudget
                || update.backlogSnapshot().hasPresentationBlockingRendererWork()
                || readiness.isFrameDispatchEligible(update.backlogSnapshot());
        if (!foregroundSensitive) {
            return false;
        }
        if (pendingFrame && pendingFrameAgeMillis >= maxPendingFrameAgeBeforeBuildMillis) {
            return true;
        }
        if (pendingFrame) {
            return true;
        }
        return elapsedNanos >= foregroundFrameBudgetNanos;
    }

    static boolean shouldProtectBoundWorldSceneCoverage(
            boolean hasBoundWorldScene,
            long latestViewRevision,
            long publishedWorldViewRevision,
            Set<SectionKey> publishedSectionKeys,
            Set<SectionKey> candidateSectionKeys,
            List<SectionKey> candidateViewSectionKeys,
            boolean hasExplicitWorldRemoval
    ) {
        Objects.requireNonNull(publishedSectionKeys, "publishedSectionKeys");
        Objects.requireNonNull(candidateSectionKeys, "candidateSectionKeys");
        Objects.requireNonNull(candidateViewSectionKeys, "candidateViewSectionKeys");
        if (!hasBoundWorldScene
                || publishedSectionKeys.isEmpty()
                || hasExplicitWorldRemoval
                || latestViewRevision < 0L
                || publishedWorldViewRevision < 0L
                || candidateSectionKeys.containsAll(publishedSectionKeys)) {
            return false;
        }
        return candidateViewSectionKeys.isEmpty()
                || !candidateSectionKeys.containsAll(candidateViewSectionKeys);
    }

    static boolean materialOnlyBindSettlesInteractiveUrgency(
            RtSceneReadiness readiness,
            boolean pendingWorldSceneBindPresent,
            boolean deferredWorldSceneBindPresent,
            long sectionMaterialRevision,
            long boundMaterialRevision
    ) {
        Objects.requireNonNull(readiness, "readiness");
        if (sectionMaterialRevision < 0L || boundMaterialRevision < 0L) {
            throw new IllegalArgumentException("material revisions must not be negative");
        }
        return !pendingWorldSceneBindPresent
                && !deferredWorldSceneBindPresent
                && readiness.worldTlasReady()
                && readiness.builtRevisionIsCurrent()
                && !readiness.hasPendingRtBuilds()
                && boundMaterialRevision >= sectionMaterialRevision;
    }

    private static RtCore.GpuStageTiming gpuStageTiming(RtCommandContext context, String label) {
        RtGpuTimestampPool.StageSnapshot snapshot = context.gpuStageTimestampSnapshot(label);
        return new RtCore.GpuStageTiming(
                snapshot.enabled(),
                snapshot.acquiredCaptures(),
                snapshot.completedCaptures(),
                snapshot.droppedCaptures(),
                snapshot.failedCaptures(),
                snapshot.lastNanos(),
                snapshot.averageNanos(),
                snapshot.maxNanos()
        );
    }

    private static RtSceneReadiness unboundWorldSceneReadiness(
            RtSceneReadiness readiness,
            RtWorldTlasCache.WorldTlasUpdate unboundUpdate
    ) {
        return new RtSceneReadiness(
                unboundUpdate.previousTopLevelAccelerationStructure() != null,
                readiness.observedInstances(),
                readiness.observedSectionInstances(),
                readiness.observedDynamicInstances(),
                unboundUpdate.previousSectionInstanceCount(),
                unboundUpdate.previousDynamicInstanceCount(),
                readiness.pendingRtSectionBuilds(),
                readiness.pendingRtDynamicBuild(),
                readiness.pendingRtTriangles(),
                readiness.cachedRtTriangles(),
                unboundUpdate.previousRevision(),
                readiness.latestRevision(),
                true
        );
    }

    /**
     * Accepts a view state without retaining any presentation sections.
     *
     * @param viewState immutable view state to accept
     * @throws NullPointerException if {@code viewState} is {@code null}
     */
    @Override
    public void acceptViewState(RendererViewState viewState) {
        acceptViewState(viewState, Set.of());
    }

    /**
     * Accepts a view state and the terrain sections that presentation still requires.
     *
     * @param viewState                    immutable view state to accept
     * @param retainedPresentationSections sections whose native geometry must remain resident
     * @throws NullPointerException if either argument is {@code null}
     */
    @Override
    public void acceptViewState(
            RendererViewState viewState,
            Set<SectionKey> retainedPresentationSections
    ) {
        acceptForegroundWork(RendererForegroundWork.untraced(viewState, retainedPresentationSections));
    }

    /**
     * Publishes foreground residency work to the native terrain cache.
     *
     * @param work foreground work and its authority revision
     * @throws NullPointerException if {@code work} is {@code null}
     */
    @Override
    public void acceptForegroundWork(RendererForegroundWork work) {
        RendererForegroundWork acceptedWork = Objects.requireNonNull(work, "work");
        acceptedSceneAuthority.acceptViewRevision(acceptedWork.authorityRevision());
        sectionBlasCache.acceptForegroundWork(acceptedWork);
    }

    /**
     * Accepts an untraced frame update using the compatibility submission path.
     *
     * @param update immutable frame update to consume
     * @throws NullPointerException if {@code update} is {@code null}
     */
    @Override
    public void acceptFrameUpdate(RendererFrameUpdate update) {
        acceptFrameSubmission(RendererFrameSubmission.untraced(update));
    }

    /**
     * Applies a causally traced frame submission to native scene and pipeline state.
     *
     * @param submission immutable update and causality envelope
     * @throws NullPointerException if {@code submission} is {@code null}
     * @throws RuntimeException     if native resource preparation or queue submission fails
     */
    @Override
    public void acceptFrameSubmission(RendererFrameSubmission submission) {
        Objects.requireNonNull(submission, "submission");
        RendererFrameUpdate update = submission.update();
        RendererFrameCausality causality = submission.causality();
        long acceptStartNanos = System.nanoTime();
        acceptFrameTiming.beginFrame();
        long stageStartNanos = acceptStartNanos;
        long detailStartNanos;
        RendererFrameCommitPlan commitPlan = update.commitPlan();
        boolean invalidatesCommittedFront = RtCommittedFrontPolicy.invalidatesCommittedFront(
                update,
                sceneBindCoordinator.publication().worldSectionKeys()
        );
        if (commitPlan.fullResyncRequested()) {
            interactiveTerrain.clear();
        }
        if (commitPlan.fullResyncRequested()
                || !commitPlan.removedSections().isEmpty()
                || !commitPlan.unloadedChunks().isEmpty()) {
            worldCoverageAuthority.authorizeContraction();
        }
        detailStartNanos = System.nanoTime();
        rayTracingPipeline.beginFrameCompletionPoll(update.frameState().sequence());
        acceptFrameTiming.framePoll.record(System.nanoTime() - detailStartNanos);
        if (update.hasDynamicSceneUpdate()) {
            acceptedSceneAuthority.acceptDynamic(causality);
            detailStartNanos = System.nanoTime();
            rayTracingPipeline.acceptDynamicSceneUpdate(update.dynamicScene());
            acceptFrameTiming.dynamicPipelineIngest.record(System.nanoTime() - detailStartNanos);
            detailStartNanos = System.nanoTime();
            dynamicBlasCache.acceptDynamicScene(update.dynamicScene(), causality);
            acceptFrameTiming.dynamicBlasIngest.record(System.nanoTime() - detailStartNanos);
            acceptFrameTiming.recordDynamicLaneShadow(
                    sceneBindCoordinator.publication().worldTlas() != null,
                    worldTlasCache.boundDynamicRevision(),
                    worldTlasCache.boundDynamicTopologyRevision(),
                    worldTlasCache.boundDynamicGeometryRevision(),
                    dynamicBlasCache.revision(),
                    dynamicBlasCache.topologyRevision(),
                    dynamicBlasCache.geometryRevision()
            );
        }
        if (commitPlan.hasTerrainWork()) {
            acceptedSceneAuthority.acceptTerrain(causality);
            detailStartNanos = System.nanoTime();
            sectionBlasCache.enqueue(
                    commitPlan.sectionMeshes(),
                    commitPlan.sectionContentRevisions(),
                    commitPlan.sectionSourceFlags(),
                    commitPlan.removedSections(),
                    commitPlan.fullResyncRequested(),
                    acceptedSceneAuthority.terrainCausality()
            );
            captureInteractiveTerrainTargets(update);
            acceptFrameTiming.sectionEnqueue.record(System.nanoTime() - detailStartNanos);
            if (invalidatesCommittedFront) {
                worldCoverageAuthority.recordCommittedFrontInvalidation(
                        sectionBlasCache.snapshotTlasBuildStats().revision()
                );
            }
        }
        if (RtInteractiveTerrainUpdatePolicy.shouldPreserveCurrentWorldTlasForAcceptedTerrainUpdate(
                update,
                scheduling.interactiveMutationRadiusSections(),
                scheduling.maxInteractiveMutationSections()
        )
                && !hasPendingInteractiveTerrainTargets()) {
            interactiveTerrain.markUrgent("acceptedInteractiveTerrain");
        }
        acceptFrameTiming.ingest.record(System.nanoTime() - stageStartNanos);
        stageStartNanos = System.nanoTime();
        detailStartNanos = System.nanoTime();
        boolean worldSceneDescriptorsCanBeUpdated = rayTracingPipeline.canUpdateWorldSceneDescriptors();
        acceptFrameTiming.descriptorPoll.record(System.nanoTime() - detailStartNanos);
        detailStartNanos = System.nanoTime();
        releaseRetiredFrameBoundResourcesIfFrameIdle();
        acceptFrameTiming.preBuildRetirement.record(System.nanoTime() - detailStartNanos);
        /*
         * Completed section BLAS builds hold the ordered Vulkan submission window until their
         * results are applied. Presentation pacing below may return early, so this maintenance
         * phase must run before every such return while remaining forbidden from admitting new
         * recording or GPU work.
         */
        detailStartNanos = System.nanoTime();
        sectionBlasCache.pumpCompletedAsyncBuilds();
        acceptFrameTiming.buildSectionCompletionPump.record(System.nanoTime() - detailStartNanos);
        detailStartNanos = System.nanoTime();
        completePendingWorldSceneBindIfReady(update, worldSceneDescriptorsCanBeUpdated);
        boolean completedMaterialOnlyThisFrame =
                completePendingMaterialOnlyBindIfReady(update, worldSceneDescriptorsCanBeUpdated);
        sceneBindCoordinator.completePendingDynamicTlasBindIfReady(
                worldSceneDescriptorsCanBeUpdated,
                rayTracingPipeline,
                sceneMaterialTable,
                dynamicTlasCache,
                worldTlasCache,
                diagnostics
        );
        long pendingBindCompletionNanos = System.nanoTime() - detailStartNanos;
        /*
         * A deferred world publication represents terrain work that has already waited behind a
         * descriptor owner. Give it the first opportunity to acquire the now-free commit token.
         * Replenishing the continuously changing dynamic lane first can otherwise starve terrain
         * forever; attempting both transactions is worse because it violates atomic descriptor
         * publication and makes the guarded backend fail closed.
         */
        detailStartNanos = System.nanoTime();
        worldSceneDescriptorsCanBeUpdated = rayTracingPipeline.canUpdateWorldSceneDescriptors();
        submitDeferredWorldSceneBindIfReady(update, worldSceneDescriptorsCanBeUpdated);
        long deferredBindSubmitNanos = System.nanoTime() - detailStartNanos;
        detailStartNanos = System.nanoTime();
        worldSceneDescriptorsCanBeUpdated = rayTracingPipeline.canUpdateWorldSceneDescriptors();
        advanceDynamicTlasLane(
                worldSceneDescriptorsCanBeUpdated,
                RtWorldSceneConvergencePolicy.shouldPrioritizeSceneConvergence(observedSceneReadiness()),
                acceptedSceneAuthority.dynamicCausality()
        );
        acceptFrameTiming.pendingBindCompletion.record(
                pendingBindCompletionNanos + System.nanoTime() - detailStartNanos
        );
        acceptFrameTiming.deferredBindSubmit.record(deferredBindSubmitNanos);
        if (textureCatalogRevisionOutranBoundMaterialSnapshot(sceneBindCoordinator.publication().materialSnapshot(), RtTextureCatalog.revision())) {
            detailStartNanos = System.nanoTime();
            submitMaterialOnlyUploadIfNeeded(
                    update,
                    rayTracingPipeline.canUpdateMaterialBuffersInPlace(),
                    causality
            );
            completedMaterialOnlyThisFrame |= completePendingMaterialOnlyBindIfReady(
                    update,
                    rayTracingPipeline.canUpdateWorldSceneDescriptors()
            );
            acceptFrameTiming.textureCatchUp.record(System.nanoTime() - detailStartNanos);
        }
        detailStartNanos = System.nanoTime();
        releaseRetiredFrameBoundResourcesIfFrameIdle();
        acceptFrameTiming.preBuildRetirement.record(System.nanoTime() - detailStartNanos);
        detailStartNanos = System.nanoTime();
        boolean skipStableFrame = shouldSkipStableFrameWhileGpuFramePending(update);
        acceptFrameTiming.stableBackpressure.record(System.nanoTime() - detailStartNanos);
        if (skipStableFrame) {
            recordNativeDispatchDecision(update, "accept", "stableFrameGpuBackpressure");
            finishAcceptStage(acceptFrameTiming.preBuild, stageStartNanos, acceptStartNanos);
            maybeLogSlowPreBuildDiagnostic(update);
            return;
        }
        detailStartNanos = System.nanoTime();
        boolean stableFastPathDispatched = tryDispatchStableFrameFastPath(update, causality);
        acceptFrameTiming.stableFastPath.record(System.nanoTime() - detailStartNanos);
        if (stableFastPathDispatched) {
            finishAcceptStage(acceptFrameTiming.preBuild, stageStartNanos, acceptStartNanos);
            maybeLogSlowPreBuildDiagnostic(update);
            return;
        }
        detailStartNanos = System.nanoTime();
        boolean allowPreBuildDispatch = shouldDispatchBeforeBuildBudget(update);
        acceptFrameTiming.preBuildPolicy.record(System.nanoTime() - detailStartNanos);
        detailStartNanos = System.nanoTime();
        boolean dispatchedBeforeBuildBudget = allowPreBuildDispatch
                && dispatchCurrentFrameIfReady(update, causality);
        acceptFrameTiming.preBuildDispatch.record(System.nanoTime() - detailStartNanos);
        acceptFrameTiming.recordPreBuildDispatchOutcome(dispatchedBeforeBuildBudget, allowPreBuildDispatch);

        if (allowPreBuildDispatch && !worldSceneDescriptorsCanBeUpdated && !dispatchedBeforeBuildBudget) {
            detailStartNanos = System.nanoTime();
            worldSceneDescriptorsCanBeUpdated = rayTracingPipeline.canUpdateWorldSceneDescriptors();
            completePendingWorldSceneBindIfReady(update, worldSceneDescriptorsCanBeUpdated);
            completedMaterialOnlyThisFrame |=
                    completePendingMaterialOnlyBindIfReady(update, worldSceneDescriptorsCanBeUpdated);
            submitDeferredWorldSceneBindIfReady(update, worldSceneDescriptorsCanBeUpdated);
            if (dispatchCurrentFrameIfReady(update, causality)) {
                dispatchedBeforeBuildBudget = true;
            }
            acceptFrameTiming.preBuildRetry.record(System.nanoTime() - detailStartNanos);
        }
        acceptFrameTiming.preBuild.record(System.nanoTime() - stageStartNanos);
        maybeLogSlowPreBuildDiagnostic(update);
        stageStartNanos = System.nanoTime();

        detailStartNanos = System.nanoTime();
        if (shouldDeferBackgroundConvergence(update, acceptStartNanos, dispatchedBeforeBuildBudget)) {
            acceptFrameTiming.buildDeferPolicy.record(System.nanoTime() - detailStartNanos);
            acceptFrameTiming.recordBuildBudgetDeferral();
            releaseRetiredBlasesThroughProtectedRevisions();
            recordNativeDispatchDecision(update, "accept", "backgroundConvergenceDeferred");
            finishAcceptWithCommittedFront(
                    update,
                    causality,
                    acceptFrameTiming.buildBudget,
                    stageStartNanos,
                    acceptStartNanos,
                    dispatchedBeforeBuildBudget
            );
            return;
        }
        acceptFrameTiming.buildDeferPolicy.record(System.nanoTime() - detailStartNanos);

        detailStartNanos = System.nanoTime();
        long remainingForegroundBudgetNanos = remainingForegroundBudgetNanos(acceptStartNanos);
        acceptFrameTiming.buildRemainingBudget.record(System.nanoTime() - detailStartNanos);
        detailStartNanos = System.nanoTime();
        sectionBlasCache.recordFirstFrontProgress(
                sceneBindCoordinator.publication().worldSectionKeys(),
                rayTracingPipeline.runtimeActivity()
        );
        acceptFrameTiming.buildFirstFront.record(System.nanoTime() - detailStartNanos);
        detailStartNanos = System.nanoTime();
        boolean firstWorldFrontPending =
                !sectionBlasCache.boundFrontCoversAuthoritativeForeground(sceneBindCoordinator.publication().worldSectionKeys());
        acceptFrameTiming.buildCoverage.record(System.nanoTime() - detailStartNanos);
        detailStartNanos = System.nanoTime();
        sectionBlasCache.processFrameBudget(
                remainingForegroundBudgetNanos,
                firstWorldFrontPending
        );
        promoteBoundWorldViewIfCovered(causality);
        acceptFrameTiming.buildSection.record(System.nanoTime() - detailStartNanos);
        detailStartNanos = System.nanoTime();
        if (foregroundBudgetExhausted(acceptStartNanos) && !shouldContinueForWorldTlasConvergence()) {
            acceptFrameTiming.buildPostSectionPolicy.record(System.nanoTime() - detailStartNanos);
            acceptFrameTiming.recordBuildBudgetDeferral();
            releaseRetiredBlasesThroughProtectedRevisions();
            recordNativeDispatchDecision(update, "accept", "foregroundBudgetExhaustedBeforeDynamicBuild");
            finishAcceptWithCommittedFront(
                    update,
                    causality,
                    acceptFrameTiming.buildBudget,
                    stageStartNanos,
                    acceptStartNanos,
                    dispatchedBeforeBuildBudget
            );
            return;
        }
        acceptFrameTiming.buildPostSectionPolicy.record(System.nanoTime() - detailStartNanos);
        detailStartNanos = System.nanoTime();
        dynamicBlasCache.processFrameBudget();
        acceptFrameTiming.buildDynamic.record(System.nanoTime() - detailStartNanos);
        detailStartNanos = System.nanoTime();
        if (foregroundBudgetExhausted(acceptStartNanos)
                && !update.commitPlan().dynamicTlasGeometryContent()
                && !shouldContinueForWorldTlasConvergence()) {
            acceptFrameTiming.buildPostDynamicPolicy.record(System.nanoTime() - detailStartNanos);
            acceptFrameTiming.recordBuildBudgetDeferral();
            releaseRetiredBlasesThroughProtectedRevisions();
            recordNativeDispatchDecision(update, "accept", "foregroundBudgetExhaustedAfterDynamicBuild");
            finishAcceptWithCommittedFront(
                    update,
                    causality,
                    acceptFrameTiming.buildBudget,
                    stageStartNanos,
                    acceptStartNanos,
                    dispatchedBeforeBuildBudget
            );
            return;
        }
        acceptFrameTiming.buildPostDynamicPolicy.record(System.nanoTime() - detailStartNanos);
        acceptFrameTiming.buildBudget.record(System.nanoTime() - stageStartNanos);
        stageStartNanos = System.nanoTime();
        worldSceneDescriptorsCanBeUpdated = rayTracingPipeline.canUpdateWorldSceneDescriptors();
        releaseRetiredFrameBoundResourcesIfFrameIdle();
        completePendingWorldSceneBindIfReady(update, worldSceneDescriptorsCanBeUpdated);
        completedMaterialOnlyThisFrame |=
                completePendingMaterialOnlyBindIfReady(update, worldSceneDescriptorsCanBeUpdated);
        submitDeferredWorldSceneBindIfReady(update, worldSceneDescriptorsCanBeUpdated);
        releaseRetiredFrameBoundResourcesIfFrameIdle();
        worldSceneDescriptorsCanBeUpdated =
                worldSceneDescriptorsCanBeUpdated
                        && !sceneBindCoordinator.state().hasPendingWorld()
                        && !sceneBindCoordinator.state().hasPendingMaterial()
                        && !sceneBindCoordinator.state().hasDeferredWorld();
        boolean interactiveTargetsReady = promoteInteractiveTerrainUrgencyIfReady();
        boolean forceCurrentWorldTlas = consumeWorldTlasUrgency(update);
        if (hasPendingInteractiveTerrainTargets() && !interactiveTargetsReady) {
            /* Never consume interactive urgency with a TLAS that still references pre-edit BLAS ownership. */
            forceCurrentWorldTlas = false;
        }
        if (forceCurrentWorldTlas && update.commitPlan().hasTerrainWork()) {
            interactiveTerrain.markUrgent("consumedInteractiveTerrain");
        }
        boolean forceWorldSceneBind = RtInteractiveTerrainUpdatePolicy.shouldSubmitUrgentWorldSceneBind(
                forceCurrentWorldTlas,
                interactiveTerrain.urgent(),
                sceneBindCoordinator.state().hasPendingWorld()
        );
        if (forceWorldSceneBind && sceneBindCoordinator.state().hasDeferredWorld()) {
            submitDeferredWorldSceneBindIfReady(
                    update,
                    rayTracingPipeline.canUpdateWorldSceneDescriptors(),
                    true
            );
            worldSceneDescriptorsCanBeUpdated = rayTracingPipeline.canUpdateWorldSceneDescriptors()
                    && !sceneBindCoordinator.state().hasPendingWorld()
                    && !sceneBindCoordinator.state().hasPendingMaterial()
                    && !sceneBindCoordinator.state().hasDeferredWorld();
        }
        acceptFrameTiming.postBuild.record(System.nanoTime() - stageStartNanos);
        stageStartNanos = System.nanoTime();
        RtWorldTlasCache.WorldTlasUpdate updatedWorldTlas =
                shouldRunWorldTlasScheduler(sceneBindCoordinator.state().hasPendingWorld(), sceneBindCoordinator.state().hasDeferredWorld())
                        ? worldTlasCache.processFrameBudget(
                        sectionBlasCache,
                        dynamicBlasCache,
                        update.backlogSnapshot(),
                        worldSceneDescriptorsCanBeUpdated,
                        forceWorldSceneBind,
                        interactiveTerrain.urgencySource(),
                        worldCoverageAuthority.acceptedContractionGeneration(),
                        worldBuildCausality(update, causality)
                )
                        : null;
        acceptFrameTiming.worldTlasScheduler.record(System.nanoTime() - stageStartNanos);
        detailStartNanos = System.nanoTime();
        if (updatedWorldTlas != null) {
            bindWorldTlasForRtDispatch(
                    updatedWorldTlas,
                    update,
                    forceWorldSceneBind,
                    worldSceneDescriptorsCanBeUpdated
            );
            releaseRetiredFrameBoundResourcesIfFrameIdle();
        } else if (!completedMaterialOnlyThisFrame) {
            submitMaterialOnlyUploadIfNeeded(
                    update,
                    rayTracingPipeline.canUpdateMaterialBuffersInPlace(),
                    causality
            );
        }
        acceptFrameTiming.worldTlasBind.record(System.nanoTime() - detailStartNanos);
        acceptFrameTiming.worldTlas.record(System.nanoTime() - stageStartNanos);
        stageStartNanos = System.nanoTime();
        releaseRetiredBlasesThroughProtectedRevisions();

        if (!dispatchedBeforeBuildBudget) {
            dispatchVisibleFrontAfterResourceProgress(update, causality);
        }
        finishAcceptStage(acceptFrameTiming.dispatch, stageStartNanos, acceptStartNanos);
    }

    /**
     * Publishes a completed logical successor without rebuilding an identical
     * persistent terrain TLAS. Coverage and content proof stays in the BLAS
     * owner; this device method owns only the atomic scene-publication swap.
     */
    private void promoteBoundWorldViewIfCovered(RendererFrameCausality causality) {
        if (sceneBindCoordinator.publication().worldTlas() == null) {
            return;
        }
        RendererViewState promotedView = sectionBlasCache.boundWorldViewPromotion(
                sceneBindCoordinator.publication().worldSectionKeys(),
                sceneBindCoordinator.publication().worldSectionContentRevisions(),
                sceneBindCoordinator.publication().worldViewRevision()
        );
        if (promotedView == null) {
            return;
        }
        sceneBindCoordinator.promoteWorldView(promotedView, causality);
        diagnostics.edges().edge(
                "worldViewMetadataPromoted",
                "viewRevision=" + promotedView.revision()
                        + ", sections=" + sceneBindCoordinator.publication().worldSectionKeys().size()
                        + ", worldTlasRevision=" + sceneBindCoordinator.publication().worldTlasRevision()
        );
    }

    private RendererFrameCausality worldBuildCausality(
            RendererFrameUpdate update,
            RendererFrameCausality currentCausality
    ) {
        return acceptedSceneAuthority.worldBuildCausality(
                update.commitPlan().hasTerrainWork(),
                update.commitPlan().dynamicSceneUpdate(),
                currentCausality
        );
    }

    private void finishAcceptStage(
            VulkanAcceptNanoTiming stage,
            long stageStartNanos,
            long acceptStartNanos
    ) {
        stage.record(System.nanoTime() - stageStartNanos);
        long elapsedNanos = System.nanoTime() - acceptStartNanos;
        acceptFrameTiming.total.record(elapsedNanos);
        acceptFrameStallRecorder.record(
                elapsedNanos,
                stage.name(),
                observedSceneReadiness(),
                rayTracingPipeline.runtimeActivity(),
                acceptFrameTiming.frameBreakdown()
        );
        acceptFrameTiming.maybeLogSmokeWindow();
    }

    private void finishAcceptWithCommittedFront(
            RendererFrameUpdate update,
            RendererFrameCausality causality,
            VulkanAcceptNanoTiming completedStage,
            long completedStageStartNanos,
            long acceptStartNanos,
            boolean frameAlreadyDispatched
    ) {
        completedStage.record(System.nanoTime() - completedStageStartNanos);
        long dispatchStartNanos = System.nanoTime();
        if (!frameAlreadyDispatched) {
            dispatchVisibleFrontAfterResourceProgress(update, causality);
        }
        finishAcceptStage(acceptFrameTiming.dispatch, dispatchStartNanos, acceptStartNanos);
    }

    /**
     * Emits one bounded causality vector only after the already-measured
     * pre-build stage breaches its high-FPS budget. This keeps normal frames
     * allocation- and I/O-free while making a real client stall attributable
     * to bind completion, dynamic descriptors, or dispatch policy rather than
     * inferring a cause from an aggregate counter.
     */
    private void maybeLogSlowPreBuildDiagnostic(RendererFrameUpdate update) {
        long preBuildNanos = acceptFrameTiming.preBuild.lastMicros * 1_000L;
        if (!telemetry.shouldLogSlowPreBuild(preBuildNanos, System.nanoTime())) {
            return;
        }
        RtCore.RuntimeActivity activity = rayTracingPipeline.runtimeActivity();
        RtSceneReadiness readiness = observedSceneReadiness();
        top.ceroxe.rt.renderer.RendererLog.info(
                "rt pre-build budget breach: frameSequence={}, preBuildUs={}, detailUs={pendingBind={}, deferredBind={}, textureCatchUp={}, stableBackpressure={}, stableFastPath={}, policy={}, dispatch={}, retry={}}, pending={world={}, material={}, dynamic={}, deferred={}}, descriptorsReady={}, framePending={}, dynamic={revision={}, topology={}, transform={}, geometry={}, material={}, boundMaterial={}}, scene={builtCurrent={}, pendingSections={}, pendingTriangles={}, observedSections={}, builtSections={}}",
                update.frameState().sequence(),
                acceptFrameTiming.preBuild.lastMicros,
                acceptFrameTiming.pendingBindCompletion.lastMicros,
                acceptFrameTiming.deferredBindSubmit.lastMicros,
                acceptFrameTiming.textureCatchUp.lastMicros,
                acceptFrameTiming.stableBackpressure.lastMicros,
                acceptFrameTiming.stableFastPath.lastMicros,
                acceptFrameTiming.preBuildPolicy.lastMicros,
                acceptFrameTiming.preBuildDispatch.lastMicros,
                acceptFrameTiming.preBuildRetry.lastMicros,
                sceneBindCoordinator.state().hasPendingWorld(),
                sceneBindCoordinator.state().hasPendingMaterial(),
                sceneBindCoordinator.state().hasPendingDynamic(),
                sceneBindCoordinator.state().hasDeferredWorld(),
                rayTracingPipeline.canUpdateWorldSceneDescriptors(),
                activity.pendingFrame(),
                dynamicBlasCache.revision(),
                dynamicBlasCache.topologyRevision(),
                dynamicBlasCache.transformRevision(),
                dynamicBlasCache.geometryRevision(),
                dynamicBlasCache.materialRevision(),
                sceneBindCoordinator.publication().dynamicMaterialRevision(),
                readiness.builtRevisionIsCurrent(),
                readiness.pendingRtSectionBuilds(),
                readiness.pendingRtTriangles(),
                readiness.observedSectionInstances(),
                readiness.builtSectionInstances()
        );
    }

    private long remainingForegroundBudgetNanos(long acceptStartNanos) {
        long elapsed = System.nanoTime() - acceptStartNanos;
        if (elapsed >= scheduling.foregroundFrameBudgetNanos()) {
            return 1L;
        }
        return scheduling.foregroundFrameBudgetNanos() - elapsed;
    }

    private boolean foregroundBudgetExhausted(long acceptStartNanos) {
        return System.nanoTime() - acceptStartNanos >= scheduling.foregroundFrameBudgetNanos();
    }

    private boolean shouldContinueForWorldTlasConvergence() {
        RtSceneReadiness readiness = observedSceneReadiness();
        long observedMaterialRevision = worldTlasCache.latestObservedSectionMaterialRevision();
        return shouldContinueForWorldTlasConvergence(
                readiness,
                sceneBindCoordinator.state().hasPendingWorld(),
                sceneBindCoordinator.state().hasDeferredWorld(),
                observedMaterialRevision >= 0L
                        && sectionMaterialRevisionOutranBoundSnapshot(
                        observedMaterialRevision,
                        sceneBindCoordinator.publication().materialSnapshot()
                )
        );
    }

    private boolean tryDispatchStableFrameFastPath(
            RendererFrameUpdate update,
            RendererFrameCausality causality
    ) {
        /*
         * Once the world scene is current, most high-FPS camera frames only need
         * a fresh frame-state/dynamic SSBO dispatch. Re-entering the BLAS/TLAS
         * scheduler on those frames duplicates work that persistent GPU-scene paths
         * keep behind dirty-resource gates, and it was visible as fixed
         * frame-end CPU cost during camera motion.
         */
        if (!shouldEvaluateStableFramePath(update.commitPlan().hasTerrainWork())
                || !isStableFrameFastPathEligible(update, sceneReadiness())) {
            return false;
        }
        if (!dispatchCurrentFrameIfReady(update, causality)) {
            return false;
        }
        acceptFrameTiming.recordStableFrameFastPathDispatch();
        return true;
    }

    private boolean dispatchVisibleFrontAfterResourceProgress(
            RendererFrameUpdate update,
            RendererFrameCausality causality
    ) {
        /*
         * Resource convergence receives the queue prefix for this frame before
         * this method runs. The immutable front remains a valid read generation,
         * so it must continue rendering current camera/dynamic state while its
         * successor builds. This is the same separation maintained between scene
         * updates and rendering from the committed GPUScene generation.
         */
        RtCommittedFrontPolicy.Decision decision = RtCommittedFrontPolicy.classify(
                worldCoverageAuthority.committedFrontGenerationIsCurrent(),
                sceneBindCoordinator.publication().worldTlas() != null,
                RtWorldSceneConvergencePolicy.shouldPrioritizeSceneConvergence(observedSceneReadiness())
        );
        if (decision != RtCommittedFrontPolicy.Decision.CONVERGED
                && decision != RtCommittedFrontPolicy.Decision.ELIGIBLE) {
            acceptFrameTiming.recordCommittedFrontDecision(decision);
            return false;
        }
        if (!dispatchCurrentFrameIfReadyAllowingBoundDynamicGeneration(update, causality)) {
            acceptFrameTiming.recordCommittedFrontDecision(RtCommittedFrontPolicy.Decision.DISPATCH_REJECTED);
            return false;
        }
        acceptFrameTiming.recordCommittedFrontDecision(decision);
        if (decision == RtCommittedFrontPolicy.Decision.ELIGIBLE) {
            acceptFrameTiming.recordCommittedFrontDispatch();
        }
        return true;
    }

    private boolean shouldSkipStableFrameWhileGpuFramePending(RendererFrameUpdate update) {
        /*
         * Preserve bounded render/driver frame overlap: pending work is normal until
         * the configured submission window or resident output ring is full.
         * Only a genuinely saturated queue may bypass the full scene scheduler.
         */
        if (!shouldEvaluateStableFramePath(update.commitPlan().hasTerrainWork())
                || !isStableFrameFastPathEligible(update, sceneReadiness())) {
            return false;
        }
        RtCore.RuntimeActivity activity = rayTracingPipeline.runtimeActivity();
        if (!shouldApplyStableFrameSubmissionBackpressure(
                rayTracingPipeline.hasFrameSubmissionCapacity(),
                activity.pendingFrame(),
                activity.pendingFrameAgeMillis(),
                scheduling.maxPendingFrameAgeBeforeBuildMillis()
        )) {
            return false;
        }
        acceptFrameTiming.recordStableFramePendingGpuSkip();
        return true;
    }

    private boolean isStableFrameFastPathEligible(RendererFrameUpdate update, RtSceneReadiness readiness) {
        Objects.requireNonNull(update, "update");
        Objects.requireNonNull(readiness, "readiness");
        if (update.commitPlan().hasTerrainWork()) {
            return false;
        }
        if (!RtSceneReadiness.READY_REASON.equals(readiness.frameDispatchBlockReason(update.backlogSnapshot()))) {
            return false;
        }
        if (interactiveTerrain.urgent()
                || sceneBindCoordinator.state().hasPendingWorld()
                || sceneBindCoordinator.state().hasPendingMaterial()
                || sceneBindCoordinator.state().hasDeferredWorld()) {
            return false;
        }
        if (sectionMaterialRevisionOutranBoundSnapshot(
                sectionBlasCache.snapshotTlasBuildStats().materialRevision(),
                sceneBindCoordinator.publication().materialSnapshot()
        ) || dynamicBlasCache.materialRevision() != sceneBindCoordinator.publication().dynamicMaterialRevision()
                || textureCatalogRevisionOutranBoundMaterialSnapshot(
                sceneBindCoordinator.publication().materialSnapshot(),
                RtTextureCatalog.revision()
        )) {
            return false;
        }
        return worldTlasCache.boundDynamicRevisionIsCurrent(dynamicBlasCache.revision())
                && rayTracingPipeline.canUpdateWorldSceneDescriptors();
    }

    private boolean shouldDispatchBeforeBuildBudget(RendererFrameUpdate update) {
        Objects.requireNonNull(update, "update");
        if (!diagnostics.edges().enabled()) {
            return shouldDispatchBeforeBuildBudgetWithoutDiagnostics(update);
        }

        long wallStartNanos = System.nanoTime();
        long cpuStartNanos = currentThreadCpuTimeNanos();
        boolean requiresReadiness = preBuildDispatchRequiresReadiness(update);
        long preconditionNanos = System.nanoTime();
        if (!requiresReadiness) {
            return shouldDispatchBeforeBuildBudgetWithoutDiagnostics(update);
        }
        RtSceneReadiness readiness = observedSceneReadiness();
        long readinessNanos = System.nanoTime();
        boolean dispatch = shouldDispatchBeforeBuildBudget(update, readiness);
        long decisionNanos = System.nanoTime();
        maybeLogSlowPreBuildPolicyDiagnostic(
                update,
                readiness,
                dispatch,
                preconditionNanos - wallStartNanos,
                readinessNanos - preconditionNanos,
                decisionNanos - readinessNanos,
                decisionNanos - wallStartNanos,
                cpuDurationNanos(cpuStartNanos, currentThreadCpuTimeNanos())
        );
        return dispatch;
    }

    private boolean shouldDispatchBeforeBuildBudgetWithoutDiagnostics(RendererFrameUpdate update) {
        if (!preBuildDispatchRequiresReadiness(update)) {
            RendererFrameCommitPlan commitPlan = update.commitPlan();
            if (!commitPlan.hasTerrainWork()) {
                return true;
            }
            if (commitPlan.fullResyncRequested()) {
                return false;
            }
            SceneUpdateBatch batch = update.batch();
            return !batch.hasBatchBlockMutationSource() || batch.hasBatchChunkStreamingSource();
        }
        /*
         * Removal batches are common while the scheduler snapshots BLAS and
         * material state.  This policy needs the last committed coverage fact,
         * not a synchronous refresh that can contend with that transaction.
         */
        return shouldDispatchBeforeBuildBudget(update, observedSceneReadiness());
    }

    /**
     * A policy decision must be CPU-only and sub-microsecond in the steady
     * state.  When a smoke frame says otherwise, distinguish actual CPU work
     * from a JVM safepoint or OS deschedule before changing renderer logic.
     */
    private void maybeLogSlowPreBuildPolicyDiagnostic(
            RendererFrameUpdate update,
            RtSceneReadiness readiness,
            boolean dispatch,
            long preconditionNanos,
            long readinessNanos,
            long decisionNanos,
            long totalNanos,
            long cpuNanos
    ) {
        if (!telemetry.shouldLogSlowPreBuildPolicy(totalNanos, System.nanoTime())) {
            return;
        }
        RendererFrameCommitPlan plan = update.commitPlan();
        SceneUpdateBatch batch = update.batch();
        top.ceroxe.rt.renderer.RendererLog.info(
                "rt pre-build policy causality: frameSequence={}, wallUs={}, cpuUs={}, descheduledUs={}, stagesUs={precondition={}, readiness={}, decision={}}, result={}, plan={terrain={}, fullResync={}, removed={}, unloaded={}, sectionMeshes={}, materialSections={}}, sources={removal={}, streaming={}, mutation={}}, coverage={builtSections={}, pendingRtSections={}, referenceSections={}, gap={}}",
                update.frameState().sequence(),
                totalNanos / 1_000L,
                cpuNanos < 0L ? -1L : cpuNanos / 1_000L,
                cpuNanos < 0L ? -1L : Math.max(0L, totalNanos - cpuNanos) / 1_000L,
                preconditionNanos / 1_000L,
                readinessNanos / 1_000L,
                decisionNanos / 1_000L,
                dispatch,
                plan.hasTerrainWork(),
                plan.fullResyncRequested(),
                plan.removedSectionCount(),
                plan.unloadedChunks().size(),
                plan.sectionMeshCount(),
                plan.materialSectionCount(),
                batch.hasBatchSectionRemovalSource(),
                batch.hasBatchChunkStreamingSource(),
                batch.hasBatchBlockMutationSource(),
                readiness.builtSectionInstances(),
                readiness.pendingRtSectionBuilds(),
                update.backlogSnapshot().rtCoverageReferenceSections(),
                update.backlogSnapshot().hasUnaccountedRtCoverageGap(
                        readiness.builtSectionInstances(),
                        readiness.pendingRtSectionBuilds()
                )
        );
    }

    private void releaseRetiredFrameBoundResourcesIfFrameIdle() {
        long completedDescriptorGeneration = rayTracingPipeline.frameBoundResourceRetirementGeneration();
        worldTlasCache.releaseRetiredWorldTlasesThrough(completedDescriptorGeneration);
        dynamicTlasCache.releaseRetiredThrough(completedDescriptorGeneration);
        sceneMaterialTable.releaseRetiredMaterialBuffersThrough(completedDescriptorGeneration);
    }

    /**
     * Advances completed dynamic-TLAS work before the stable-frame fast path may return.
     */
    private void advanceDynamicTlasLane(
            boolean descriptorsCanBeUpdated,
            boolean resourceConvergencePending,
            RendererFrameCausality causality
    ) {
        dynamicSceneBindLane.advance(descriptorsCanBeUpdated, resourceConvergencePending, causality);
    }

    private void captureInteractiveTerrainTargets(RendererFrameUpdate update) {
        SceneUpdateBatch batch = update.batch();
        if (!RtInteractiveTerrainUpdatePolicy.isInteractiveBlockMutationBatch(
                batch,
                update.frameState(),
                scheduling.interactiveMutationRadiusSections(),
                scheduling.maxInteractiveMutationSections()
        )) {
            return;
        }
        interactiveTerrain.capture(update);
    }

    private boolean hasPendingInteractiveTerrainTargets() {
        return interactiveTerrain.hasPendingTargets();
    }

    private boolean promoteInteractiveTerrainUrgencyIfReady() {
        return interactiveTerrain.promoteIfReady(sectionBlasCache, sceneBindCoordinator.publication());
    }

    private void settlePublishedInteractiveTerrainTargets() {
        interactiveTerrain.settle(sceneBindCoordinator.publication());
    }

    private boolean consumeWorldTlasUrgency(RendererFrameUpdate update) {
        if (!update.commitPlan().hasTerrainWork()) {
            /* Dynamic geometry owns an independent TLAS and descriptor lane. */
            return false;
        }
        SceneUpdateBatch batch = update.batch();
        boolean blockMutationSource = batch.hasBatchBlockMutationSource();
        boolean chunkStreamingSource = batch.hasBatchChunkStreamingSource();
        boolean removalSource = batch.hasBatchSectionRemovalSource() || !batch.removedSections().isEmpty();
        boolean terrainTopologyChanged = blockMutationSource || removalSource;
        if (!terrainTopologyChanged) {
            return false;
        }
        /*
         * host may merge a direct block edit and unrelated chunk streaming
         * into one frame batch. The persistent interactive target owner already
         * fences the exact edited section revision, so batch-wide streaming must
         * not demote that section after its own BLAS is ready. Pure streaming and
         * far/oversized mutation bursts still remain on the coalesced lane.
         */
        boolean interactiveBlockMutation = RtInteractiveTerrainUpdatePolicy.isInteractiveBlockMutationBatch(
                batch,
                update.frameState(),
                scheduling.interactiveMutationRadiusSections(),
                scheduling.maxInteractiveMutationSections()
        );
        if (!RtInteractiveTerrainUpdatePolicy.shouldForceWorldTlasForTerrainMutation(
                terrainTopologyChanged,
                blockMutationSource,
                chunkStreamingSource,
                interactiveBlockMutation,
                update.backlogSnapshot()
        )) {
            return false;
        }
        return true;
    }

    private boolean shouldDeferBackgroundConvergence(
            RendererFrameUpdate update,
            long acceptStartNanos,
            boolean dispatchedBeforeBuildBudget
    ) {
        Objects.requireNonNull(update, "update");
        long observedMaterialRevision = worldTlasCache.latestObservedSectionMaterialRevision();
        if (interactiveTerrain.urgent()
                || observedMaterialRevision >= 0L
                && sectionMaterialRevisionOutranBoundSnapshot(
                observedMaterialRevision,
                sceneBindCoordinator.publication().materialSnapshot()
        )) {
            return false;
        }
        RtCore.RuntimeActivity activity = rayTracingPipeline.runtimeActivity();
        RtSceneReadiness readiness = observedSceneReadiness();
        boolean defer = shouldDeferBackgroundConvergence(
                update,
                readiness,
                dispatchedBeforeBuildBudget,
                activity.pendingFrame(),
                activity.pendingFrameAgeMillis(),
                scheduling.maxPendingFrameAgeBeforeBuildMillis(),
                System.nanoTime() - acceptStartNanos,
                scheduling.foregroundFrameBudgetNanos()
        );
        if (defer) {
            acceptFrameTiming.recordForegroundBudgetDeferral();
        }
        return defer;
    }

    private boolean dispatchCurrentFrameIfReady(
            RendererFrameUpdate update,
            RendererFrameCausality causality
    ) {
        /*
         * The active world TLAS is an immutable scene generation. Dynamic
         * collection commonly starts its successor every host frame, so
         * requiring that successor before dispatching the active generation
         * starves first presentation indefinitely.
         */
        return dispatchCurrentFrameIfReady(update, causality, true);
    }

    private boolean dispatchCurrentFrameIfReadyAllowingBoundDynamicGeneration(
            RendererFrameUpdate update,
            RendererFrameCausality causality
    ) {
        return dispatchCurrentFrameIfReady(update, causality, true);
    }

    private boolean dispatchCurrentFrameIfReady(
            RendererFrameUpdate update,
            RendererFrameCausality causality,
            boolean allowBoundDynamicGeneration
    ) {
        acceptFrameTiming.dispatchAttempts++;
        long dispatchStageStartNanos = System.nanoTime();
        RtSceneReadiness readiness = observedSceneReadiness();
        acceptFrameTiming.dispatchReadiness.record(System.nanoTime() - dispatchStageStartNanos);
        /*
         * A staged descriptor generation is a write-side concern. The active
         * generation remains immutable and valid until every frame that uses it
         * retires, so a streaming material/TLAS upload must not stall dispatch
         * through that already-bound scene. This follows a graph/driver resource lifetime:
         * stage the next resource generation independently while the current
         * generation continues producing frames from its bounded output ring.
         */
        dispatchStageStartNanos = System.nanoTime();
        if (!rayTracingPipeline.hasFrameSubmissionCapacity()) {
            acceptFrameTiming.dispatchCapacityRejects++;
            acceptFrameTiming.dispatchCapacity.record(System.nanoTime() - dispatchStageStartNanos);
            recordNativeDispatchDecision(update, "context", "frameSubmissionCapacity");
            return false;
        }
        acceptFrameTiming.dispatchCapacity.record(System.nanoTime() - dispatchStageStartNanos);
        dispatchStageStartNanos = System.nanoTime();
        if (!currentDescriptorGenerationCanDispatch()) {
            acceptFrameTiming.dispatchDescriptorRejects++;
            acceptFrameTiming.dispatchDescriptor.record(System.nanoTime() - dispatchStageStartNanos);
            recordNativeDispatchDecision(update, "context", "descriptorGenerationNotCurrent");
            return false;
        }
        acceptFrameTiming.dispatchDescriptor.record(System.nanoTime() - dispatchStageStartNanos);
        dispatchStageStartNanos = System.nanoTime();
        String readinessBlockReason = readiness.frameProductionBlockReason(update.backlogSnapshot());
        if (!RtSceneReadiness.READY_REASON.equals(readinessBlockReason)) {
            acceptFrameTiming.dispatchReadinessRejects++;
            acceptFrameTiming.dispatchReadinessPolicy.record(System.nanoTime() - dispatchStageStartNanos);
            acceptFrameTiming.recordDynamicWorldTlasDispatchBlock(readinessBlockReason);
            recordNativeDispatchDecision(update, "context", "frameProduction:" + readinessBlockReason);
            return false;
        }
        acceptFrameTiming.dispatchReadinessPolicy.record(System.nanoTime() - dispatchStageStartNanos);
        dispatchStageStartNanos = System.nanoTime();
        String dynamicBlockReason = allowBoundDynamicGeneration
                ? dynamicWorldTlasCommittedFrontBlockReason()
                : dynamicWorldTlasDispatchBlockReason();
        if (!RtSceneReadiness.READY_REASON.equals(dynamicBlockReason)) {
            acceptFrameTiming.dispatchDynamicRejects++;
            acceptFrameTiming.dispatchDynamicPolicy.record(System.nanoTime() - dispatchStageStartNanos);
            acceptFrameTiming.recordDynamicWorldTlasDispatchBlock(dynamicBlockReason);
            recordNativeDispatchDecision(update, "context", "dynamicWorldTlas:" + dynamicBlockReason);
            return false;
        }
        acceptFrameTiming.dispatchDynamicPolicy.record(System.nanoTime() - dispatchStageStartNanos);
        dispatchStageStartNanos = System.nanoTime();
        if (RtDynamicSceneDispatchPolicy.shouldBlockForInteractiveWorldSceneBind(
                sceneBindCoordinator.publication().worldTlas() != null,
                interactiveWorldSceneBindPending()
        )) {
            acceptFrameTiming.dispatchInteractiveRejects++;
            acceptFrameTiming.dispatchInteractivePolicy.record(System.nanoTime() - dispatchStageStartNanos);
            recordNativeDispatchDecision(update, "context", "interactiveWorldSceneBindPendingWithoutCommittedFront");
            return false;
        }
        acceptFrameTiming.dispatchInteractivePolicy.record(System.nanoTime() - dispatchStageStartNanos);
        long previousDispatches = rayTracingPipeline.runtimeActivity().frameDispatches();
        dispatchStageStartNanos = System.nanoTime();
        DynamicRenderScene dispatchDynamicScene = shouldDispatchCurrentDynamicSsboScene(
                update,
                allowBoundDynamicGeneration
        ) ? update.dynamicScene() : sceneBindCoordinator.publication().dynamicScene();
        rayTracingPipeline.dispatchFrameIfDue(
                queueContexts.frameCommands(),
                causality,
                sceneBindCoordinator.publication().snapshot(),
                update.frameState(),
                dispatchDynamicScene,
                sceneBindCoordinator.publication().dynamicScene().revision(),
                sceneBindCoordinator.publication().worldSectionKeys(),
                sceneBindCoordinator.publication().worldSectionContentRevisions(),
                sceneBindCoordinator.publication().worldViewState(),
                true
        );
        acceptFrameTiming.dispatchPipeline.record(System.nanoTime() - dispatchStageStartNanos);
        dispatchStageStartNanos = System.nanoTime();
        maybeLogWorldTlasDispatch(previousDispatches, true, readiness);
        long dispatchesAfterAttempt = rayTracingPipeline.runtimeActivity().frameDispatches();
        diagnostics.causality().firstFrontFrame(
                "nativeDispatchAttempt",
                update.frameState().sequence(),
                "boundWorldScene=" + (sceneBindCoordinator.publication().worldTlas() != null)
                        + ", sections=" + sceneBindCoordinator.publication().worldSectionKeys().size()
                        + ", submitted=" + (dispatchesAfterAttempt > previousDispatches)
        );
        diagnostics.causality().frame(
                top.ceroxe.rt.renderer.RtCausalitySink.Stage.GPU_DISPATCH,
                update.frameState().sequence(),
                sceneBindCoordinator.publication().worldViewRevision(),
                sceneBindCoordinator.publication().worldSectionKeys().size(),
                dispatchesAfterAttempt > previousDispatches ? 1 : 0
        );
        diagnostics.causality().dispatch(
                acceptedSceneAuthority.terrainCausality(),
                update.frameState().sequence(),
                sceneBindCoordinator.publication().generation(),
                sceneBindCoordinator.publication().descriptorGeneration(),
                dispatchesAfterAttempt,
                dispatchesAfterAttempt > previousDispatches
                        ? top.ceroxe.rt.renderer.RtCausalitySink.Reason.SUBMITTED
                        : top.ceroxe.rt.renderer.RtCausalitySink.Reason.NONE
        );
        acceptFrameTiming.dispatchRecorders.record(System.nanoTime() - dispatchStageStartNanos);
        if (dispatchesAfterAttempt > previousDispatches) {
            acceptFrameTiming.dispatchSubmissions++;
        } else {
            acceptFrameTiming.dispatchPipelineNoSubmits++;
        }
        return dispatchesAfterAttempt > previousDispatches;
    }

    /**
     * The dynamic SSBO is frame-slot-local, while the dynamic TLAS and material
     * table are descriptor-visible generations. Pure analytic primitives,
     * particles, weather, sky, and their clear transition therefore do not
     * need to wait for a descriptor bind. Passing the published scene back to
     * the pipeline in this case would overwrite the update it already accepted
     * at ingress and visibly resurrect stale dynamic content.
     *
     * <p>Once either side contains TLAS geometry, this optimization is forbidden:
     * the published scene remains the sole dispatch source until the matching
     * TLAS/material transaction commits.</p>
     */
    private boolean shouldDispatchCurrentDynamicSsboScene(
            RendererFrameUpdate update,
            boolean allowBoundDynamicGeneration
    ) {
        return RtDynamicSceneDispatchPolicy.shouldDispatchCurrentSsboScene(
                allowBoundDynamicGeneration,
                sceneBindCoordinator.publication().dynamicScene().hasTlasGeometryContent(),
                update.commitPlan().dynamicTlasGeometryContent()
        );
    }

    private String dynamicWorldTlasCommittedFrontBlockReason() {
        return RtDynamicSceneDispatchPolicy.committedFrontBlockReason(
                sceneBindCoordinator.publication().worldTlas() != null,
                sceneBindCoordinator.publication().dynamicScene().hasTlasGeometryContent(),
                worldTlasCache.publishedReadiness().builtDynamicInstances()
        );
    }

    private boolean currentDescriptorGenerationCanDispatch() {
        return RtDynamicSceneDispatchPolicy.descriptorGenerationCanDispatch(
                sceneBindCoordinator.publication().worldTlas() != null,
                !sceneBindCoordinator.state().hasPendingWorld()
                        || sceneBindCoordinator.state().pendingWorld().materialUpload().materialBuffersChanged(),
                !sceneBindCoordinator.state().hasPendingMaterial()
                        || sceneBindCoordinator.state().pendingMaterial().materialUpload().materialBuffersChanged(),
                !sceneBindCoordinator.state().hasPendingDynamic()
                        || sceneBindCoordinator.state().pendingDynamic().materialUpload().materialBuffersChanged()
        );
    }

    private void recordNativeDispatchDecision(RendererFrameUpdate update, String stage, String reason) {
        if (!TAKEOVER_FLIGHT_RECORDER_ENABLED) {
            return;
        }
        RendererFrameState frameState = update.frameState();
        rayTracingPipeline.recordExternalDispatchDecision(
                frameState.valid() ? frameState.sequence() : -1L,
                stage,
                reason
        );
    }

    private boolean interactiveWorldSceneBindPending() {
        PendingWorldSceneBind pending = sceneBindCoordinator.state().pendingWorld();
        return interactiveTerrain.urgent()
                || (pending != null && pending.urgentWorldSceneBind());
    }

    private String dynamicWorldTlasDispatchBlockReason() {
        if (dynamicBlasCache.activeSceneHasTlasGeometryContent()
                && worldTlasCache.publishedReadiness().builtDynamicInstances() <= 0) {
            return RtSceneReadiness.RT_DYNAMIC_BUILD_PENDING_REASON;
        }
        return RtDynamicSceneDispatchPolicy.blockReason(
                sceneBindCoordinator.publication().worldTlas() != null,
                worldTlasCache.hasBoundDynamicLane(),
                dynamicBlasCache.activeSceneHasTlasGeometryContent(),
                worldTlasCache.boundDynamicRevisionIsCurrent(dynamicBlasCache.revision()),
                worldTlasCache.boundDynamicStructureIsCurrent(
                        dynamicBlasCache.topologyRevision(),
                        dynamicBlasCache.geometryRevision()
                )
        );
    }

    private void bindWorldTlasForRtDispatch(
            RtWorldTlasCache.WorldTlasUpdate updatedWorldTlas,
            RendererFrameUpdate update,
            boolean forceCurrentWorldTlas,
            boolean worldSceneDescriptorsCanBeUpdated
    ) {
        boolean hasExplicitWorldRemoval = worldCoverageAuthority.contractionAuthorizedFor(
                updatedWorldTlas.coverageContractionAuthorizationGeneration()
        );
        if (shouldProtectBoundWorldSceneCoverage(
                sceneBindCoordinator.publication().worldTlas() != null,
                acceptedSceneAuthority.viewRevision(),
                sceneBindCoordinator.publication().worldViewRevision(),
                sceneBindCoordinator.publication().worldSectionKeys(),
                updatedWorldTlas.sectionKeys(),
                updatedWorldTlas.viewState().visibleSectionKeys(),
                hasExplicitWorldRemoval
        )) {
            worldTlasCache.discardUnboundWorldTlas(updatedWorldTlas, "frontCoverageIncomplete");
            worldSceneBindStatistics.coverageProtectedDiscarded();
            return;
        }
        if (sceneBindCoordinator.state().hasPendingDynamic()) {
            deferWorldSceneBind(updatedWorldTlas, "dynamicDescriptorBindPending");
            return;
        }
        if (RtWorldSceneConvergencePolicy.isTransformOnlyWorldTlasUpdate(
                updatedWorldTlas,
                sceneBindCoordinator.publication().worldTlas(),
                sceneBindCoordinator.publication().worldSectionKeys(),
                sceneBindCoordinator.publication().materialSnapshot()
        )) {
            if (sceneBindCoordinator.state().hasPendingMaterial()) {
                deferWorldSceneBind(updatedWorldTlas, "dynamicTransformMaterialBindPending");
                return;
            }
            bindTransformOnlyWorldScene(updatedWorldTlas);
            return;
        }
        if (!worldSceneDescriptorsCanBeUpdated || sceneBindCoordinator.state().hasPendingMaterial()) {
            deferWorldSceneBind(updatedWorldTlas, "worldSceneDescriptorBusy");
            return;
        }
        RtSceneReadiness readiness = worldTlasCache.readiness();
        String bindReason = readiness.frameDispatchBlockReason(update.backlogSnapshot());
        boolean streamingWorldSceneUpdate = RtWorldSceneConvergencePolicy.isStreamingWorldSceneUpdate(
                updatedWorldTlas,
                bindReason,
                scheduling.immediateStreamingBindMaxFaces()
        );
        if (RtWorldSceneConvergencePolicy.shouldDeferWorldSceneMaterialUpload(
                forceCurrentWorldTlas || shouldBindStreamingWorldSceneImmediately(updatedWorldTlas),
                streamingWorldSceneUpdate,
                update.backlogSnapshot(),
                sceneBindCoordinator.state().hasPendingWorld(),
                System.nanoTime(),
                telemetry.nextStreamingSceneBindNanos(),
                0L,
                scheduling.maxStreamingSceneBindDeferrals()
        )) {
            if (streamingWorldSceneUpdate) {
                worldSceneBindStatistics.streamingIntervalDeferred();
            }
            deferWorldSceneBind(updatedWorldTlas, bindReason);
            return;
        }
        rememberStreamingSceneBindWindow(streamingWorldSceneUpdate);
        submitWorldSceneMaterialUpload(updatedWorldTlas, bindReason, forceCurrentWorldTlas);
    }

    private void rememberStreamingSceneBindWindow(boolean streamingWorldSceneUpdate) {
        telemetry.rememberStreamingSceneBind(
                streamingWorldSceneUpdate,
                scheduling.minStreamingSceneBindIntervalNanos(),
                System.nanoTime()
        );
    }

    private void submitWorldSceneMaterialUpload(
            RtWorldTlasCache.WorldTlasUpdate updatedWorldTlas,
            String bindReason,
            boolean urgentWorldSceneBind
    ) {
        RtWorldSceneMaterialUploadLane.WorldSubmission submission = worldSceneMaterialUploadLane.submitWorld(
                updatedWorldTlas,
                bindReason,
                urgentWorldSceneBind,
                rayTracingPipeline.canUpdateMaterialBuffersInPlace()
        );
        switch (submission.status()) {
            case DEFERRED -> {
                deferWorldSceneBind(updatedWorldTlas, bindReason);
                return;
            }
            case IMMEDIATE_BIND -> bindWorldSceneDescriptors(
                    updatedWorldTlas,
                    submission.materialSnapshot(),
                    submission.dynamicMaterialRevision(),
                    false,
                    bindReason
            );
            case PENDING_BIND -> {
                // The upload lane transferred ownership into RtSceneBindCoordinator.
            }
        }
        if (urgentWorldSceneBind) {
            interactiveTerrain.clearUrgency();
        }
    }

    private void submitMaterialOnlyUploadIfNeeded(
            RendererFrameUpdate update,
            boolean allowInPlaceMaterialUpdate,
            RendererFrameCausality causality
    ) {
        Objects.requireNonNull(update, "update");
        worldSceneMaterialUploadLane.submitMaterialOnly(allowInPlaceMaterialUpdate, causality);
    }

    private boolean shouldBindStreamingWorldSceneImmediately(RtWorldTlasCache.WorldTlasUpdate update) {
        Objects.requireNonNull(update, "update");
        return RtWorldSceneConvergencePolicy.shouldBindStreamingImmediately(
                update, scheduling.immediateStreamingBindMaxFaces()
        );
    }

    private void deferWorldSceneBind(RtWorldTlasCache.WorldTlasUpdate updatedWorldTlas, String bindReason) {
        sceneBindCoordinator.publishDeferredWorld(new DeferredWorldSceneBind(updatedWorldTlas, bindReason, 0L));
        worldSceneBindStatistics.deferredWorldCreated();
    }

    private void submitDeferredWorldSceneBindIfReady(
            RendererFrameUpdate update,
            boolean worldSceneDescriptorsCanBeUpdated
    ) {
        submitDeferredWorldSceneBindIfReady(update, worldSceneDescriptorsCanBeUpdated, false);
    }

    private void submitDeferredWorldSceneBindIfReady(
            RendererFrameUpdate update,
            boolean worldSceneDescriptorsCanBeUpdated,
            boolean forceCurrentWorldTlas
    ) {
        DeferredWorldSceneBind deferred = sceneBindCoordinator.state().deferredWorld();
        if (deferred == null) {
            return;
        }
        RtDeferredWorldSceneBindScheduler.Decision decision = RtDeferredWorldSceneBindScheduler.decide(
                deferred,
                sceneBindCoordinator.publication(),
                worldSceneDescriptorsCanBeUpdated,
                sceneBindCoordinator.state().hasDescriptorTransaction(),
                update,
                forceCurrentWorldTlas,
                scheduling.immediateStreamingBindMaxFaces(),
                System.nanoTime(),
                telemetry.nextStreamingSceneBindNanos(),
                scheduling.maxStreamingSceneBindDeferrals()
        );
        switch (decision.action()) {
            case DESCRIPTOR_DEFERRED -> {
                worldSceneBindStatistics.worldDescriptorDeferred();
                return;
            }
            case BIND_TRANSFORM_ONLY -> {
                sceneBindCoordinator.clearDeferredWorld(deferred);
                worldSceneBindStatistics.deferredWorldSubmitted();
                bindTransformOnlyWorldScene(deferred.worldTlasUpdate());
                return;
            }
            case RETAIN_DEFERRED -> {
                if (decision.streamingWorldSceneUpdate()) {
                    worldSceneBindStatistics.streamingIntervalDeferred();
                }
                worldSceneBindStatistics.deferredWorldDeferred();
                sceneBindCoordinator.replaceDeferredWorld(deferred, deferred.withAdditionalDeferral());
                return;
            }
            case SUBMIT_MATERIAL_UPLOAD -> {
                sceneBindCoordinator.clearDeferredWorld(deferred);
                worldSceneBindStatistics.deferredWorldSubmitted();
                rememberStreamingSceneBindWindow(decision.streamingWorldSceneUpdate());
                submitWorldSceneMaterialUpload(
                        deferred.worldTlasUpdate(), deferred.bindReason(update), forceCurrentWorldTlas
                );
                return;
            }
        }
        throw new IllegalStateException("unhandled deferred world-scene bind action: " + decision.action());
    }

    private void bindTransformOnlyWorldScene(RtWorldTlasCache.WorldTlasUpdate updatedWorldTlas) {
        RtSceneBindCoordinator.WorldBindCompletion completion = sceneBindCoordinator.bindTransformOnlyWorldScene(
                updatedWorldTlas,
                rayTracingPipeline,
                sceneMaterialTable,
                worldTlasCache
        );
        rememberBoundCoverageContractionAuthorization(completion.worldTlasUpdate());
        recordBoundWorldTlas(completion.worldTlasUpdate());
        maybeLogWorldTlasBound(
                completion.worldTlasUpdate().revision(),
                completion.worldTlasUpdate().instanceCount(),
                completion.bindReason()
        );
    }

    private void completePendingWorldSceneBindIfReady(
            RendererFrameUpdate update,
            boolean worldSceneDescriptorsCanBeUpdated
    ) {
        RtSceneBindCoordinator.WorldBindResult result = sceneBindCoordinator.completePendingWorldSceneBindIfReady(
                update,
                worldSceneDescriptorsCanBeUpdated,
                rayTracingPipeline,
                sceneMaterialTable,
                worldTlasCache,
                diagnostics,
                worldSceneBindStatistics.nextWorldPollOrdinal()
        );
        switch (result.status()) {
            case ABSENT, STALE_DISCARDED -> {
                return;
            }
            case DESCRIPTOR_DEFERRED -> {
                worldSceneBindStatistics.worldDescriptorDeferred();
                return;
            }
            case UPLOAD_PENDING -> {
                worldSceneBindStatistics.worldPollNotReady();
                return;
            }
            case COMPLETED -> {
                RtSceneBindCoordinator.WorldBindCompletion completion = result.completion();
                rememberBoundCoverageContractionAuthorization(completion.worldTlasUpdate());
                settlePublishedInteractiveTerrainTargets();
                worldSceneBindStatistics.worldCompleted();
                recordBoundWorldTlas(completion.worldTlasUpdate());
                maybeLogWorldTlasBound(
                        completion.worldTlasUpdate().revision(),
                        completion.worldTlasUpdate().instanceCount(),
                        completion.bindReason()
                );
            }
        }
    }

    private boolean completePendingMaterialOnlyBindIfReady(
            RendererFrameUpdate update,
            boolean worldSceneDescriptorsCanBeUpdated
    ) {
        Objects.requireNonNull(update, "update");
        RtSceneBindCoordinator.MaterialBindStatus status =
                sceneBindCoordinator.completePendingMaterialOnlyBindIfReady(
                        worldSceneDescriptorsCanBeUpdated,
                        rayTracingPipeline,
                        sceneMaterialTable,
                        diagnostics
                );
        switch (status) {
            case ABSENT, STALE_DISCARDED -> {
                return false;
            }
            case DESCRIPTOR_DEFERRED -> {
                worldSceneBindStatistics.materialDescriptorDeferred();
                return false;
            }
            case UPLOAD_PENDING -> {
                worldSceneBindStatistics.materialPollNotReady();
                return false;
            }
            case COMPLETED -> {
                if (interactiveTerrain.urgent()
                        && materialOnlyBindSettlesInteractiveUrgency(
                        sceneReadiness(),
                        sceneBindCoordinator.state().hasPendingWorld(),
                        sceneBindCoordinator.state().hasDeferredWorld(),
                        sectionBlasCache.snapshotTlasBuildStats().materialRevision(),
                        sceneBindCoordinator.publication().materialSnapshot().revision()
                )) {
                    interactiveTerrain.clearUrgency();
                }
                worldSceneBindStatistics.materialCompleted();
                return true;
            }
        }
        throw new IllegalStateException("unhandled material bind status: " + status);
    }

    private void bindWorldSceneDescriptors(
            RtWorldTlasCache.WorldTlasUpdate updatedWorldTlas,
            RtSceneMaterialTable.Snapshot materialSnapshot,
            long dynamicMaterialRevision,
            boolean materialBuffersChanged,
            String bindReason
    ) {
        RtSceneBindCoordinator.WorldBindCompletion completion = sceneBindCoordinator.bindWorldSceneForDispatch(
                updatedWorldTlas,
                materialSnapshot,
                dynamicMaterialRevision,
                materialBuffersChanged,
                bindReason,
                rayTracingPipeline,
                sceneMaterialTable,
                worldTlasCache
        );
        rememberBoundCoverageContractionAuthorization(completion.worldTlasUpdate());
        settlePublishedInteractiveTerrainTargets();
        recordBoundWorldTlas(completion.worldTlasUpdate());
        maybeLogWorldTlasBound(
                completion.worldTlasUpdate().revision(),
                completion.worldTlasUpdate().instanceCount(),
                completion.bindReason()
        );
    }

    private void recordBoundWorldTlas(RtWorldTlasCache.WorldTlasUpdate updatedWorldTlas) {
        diagnostics.causality().firstFrontWorld(
                "worldTlasBound",
                updatedWorldTlas.revision(),
                "sections=" + sceneBindCoordinator.publication().worldSectionKeys().size()
                        + ", viewRevision=" + sceneBindCoordinator.publication().worldViewRevision()
        );
        diagnostics.causality().world(
                updatedWorldTlas.revision(),
                sceneBindCoordinator.publication().worldSectionKeys().size(),
                sceneBindCoordinator.publication().worldViewRevision(),
                0
        );
        for (SectionKey key : sceneBindCoordinator.publication().worldSectionKeys()) {
            long contentRevision = sceneBindCoordinator.publication().worldSectionContentRevisions().getOrDefault(key, -1L);
            diagnostics.causality().section(
                    top.ceroxe.rt.renderer.RtCausalitySink.Stage.SECTION_TLAS_BOUND,
                    key,
                    contentRevision,
                    updatedWorldTlas.revision(),
                    sceneBindCoordinator.publication().worldViewRevision(),
                    0
            );
            SectionLifecycleFlightRecorder.record(
                    SectionLifecycleFlightRecorder.STAGE_TLAS_BOUND,
                    SectionLifecycleFlightRecorder.SOURCE_UNKNOWN,
                    SectionLifecycleFlightRecorder.OUTCOME_ACCEPTED,
                    key,
                    0,
                    updatedWorldTlas.revision(),
                    contentRevision,
                    sceneBindCoordinator.publication().worldViewRevision(),
                    0,
                    sceneBindCoordinator.publication().worldSectionKeys().size(),
                    sceneBindCoordinator.publication().generation()
            );
        }
    }

    private void rememberBoundCoverageContractionAuthorization(
            RtWorldTlasCache.WorldTlasUpdate updatedWorldTlas
    ) {
        worldCoverageAuthority.recordBoundGeneration(
                updatedWorldTlas.coverageContractionAuthorizationGeneration(),
                updatedWorldTlas.sectionRevision()
        );
    }

    private void maybeLogWorldTlasBound(long revision, int instances, String bindReason) {
        if (!telemetry.shouldLogWorldTlasBound()) {
            return;
        }
        top.ceroxe.rt.renderer.RendererLog.info(
                "rt world TLAS bound for dispatch: revision={}, instances={}, bindReason={}, {}",
                revision,
                instances,
                bindReason,
                worldTlasCache.readiness().asLogFragment()
        );
    }

    private long protectedSectionRevision() {
        long protectedRevision = worldTlasCache.protectedSectionRevision();
        PendingWorldSceneBind pending = sceneBindCoordinator.state().pendingWorld();
        if (pending != null) {
            protectedRevision = Math.min(
                    protectedRevision,
                    pending.worldTlasUpdate().previousSectionResourceRevision()
            );
        }
        DeferredWorldSceneBind deferred = sceneBindCoordinator.state().deferredWorld();
        if (deferred != null) {
            protectedRevision = Math.min(
                    protectedRevision,
                    deferred.worldTlasUpdate().previousSectionResourceRevision()
            );
        }
        return protectedRevision;
    }

    private long protectedDynamicRevision() {
        long protectedRevision = worldTlasCache.protectedDynamicRevision();
        PendingWorldSceneBind pending = sceneBindCoordinator.state().pendingWorld();
        if (pending != null) {
            protectedRevision = Math.min(protectedRevision, pending.worldTlasUpdate().previousDynamicRevision());
        }
        DeferredWorldSceneBind deferred = sceneBindCoordinator.state().deferredWorld();
        if (deferred != null) {
            protectedRevision = Math.min(protectedRevision, deferred.worldTlasUpdate().previousDynamicRevision());
        }
        return protectedRevision;
    }

    private long protectedSectionSceneRevision() {
        long protectedRevision = worldTlasCache.protectedSectionSceneRevision();
        PendingWorldSceneBind pending = sceneBindCoordinator.state().pendingWorld();
        if (pending != null) {
            protectedRevision = Math.min(
                    protectedRevision,
                    pending.worldTlasUpdate().previousSectionRevision()
            );
        }
        DeferredWorldSceneBind deferred = sceneBindCoordinator.state().deferredWorld();
        if (deferred != null) {
            protectedRevision = Math.min(
                    protectedRevision,
                    deferred.worldTlasUpdate().previousSectionRevision()
            );
        }
        return protectedRevision;
    }

    private void releaseRetiredBlasesThroughProtectedRevisions() {
        sectionBlasCache.releaseRetiredBlasesThrough(
                protectedSectionRevision(),
                protectedSectionSceneRevision()
        );
        dynamicBlasCache.releaseRetiredBlasesThrough(protectedDynamicRevision());
    }

    private void closePendingWorldSceneBind() {
        sceneBindCoordinator.closePendingWorld();
    }

    private void closePendingMaterialOnlyBind() {
        sceneBindCoordinator.closePendingMaterial();
    }

    private void closePendingDynamicTlasBind() {
        sceneBindCoordinator.discardPendingDynamicTlasBindIfPresent(
                "contextClosed", dynamicTlasCache, diagnostics
        );
    }

    private void discardDeferredWorldSceneBind(String reason) {
        DeferredWorldSceneBind deferred = sceneBindCoordinator.state().deferredWorld();
        if (deferred == null) {
            return;
        }
        sceneBindCoordinator.clearDeferredWorld(deferred);
        worldSceneBindStatistics.deferredWorldDiscarded();
        worldTlasCache.discardUnboundWorldTlas(deferred.worldTlasUpdate(), reason);
    }

    private void closeDeferredWorldSceneBind() {
        discardDeferredWorldSceneBind("closed");
    }

    private void maybeLogWorldTlasDispatch(
            long previousDispatches,
            boolean frameDispatchUseful,
            RtSceneReadiness readiness
    ) {
        RtCore.RuntimeActivity activity = rayTracingPipeline.runtimeActivity();
        if (!telemetry.shouldLogWorldTlasDispatch(
                activity.frameDispatches(),
                previousDispatches,
                readiness.worldTlasReady(),
                frameDispatchUseful
        )) {
            return;
        }
        top.ceroxe.rt.renderer.RendererLog.info(
                "rt world TLAS dispatch active: {}, frameDispatchUseful={}, {}",
                activity.asLogFragment(),
                frameDispatchUseful,
                readiness.asLogFragment()
        );
    }

    /**
     * Returns the most recently completed diagnostic frame snapshot.
     *
     * @return latest completed snapshot, or {@code null} before the first completion
     */
    @Override
    public RtFrameSnapshot latestFrameSnapshot() {
        return rayTracingPipeline.latestFrameSnapshot();
    }

    /**
     * Requests capture of the next eligible G-buffer output.
     *
     * @return {@code true} when the request was admitted; {@code false} when one is already pending
     */
    @Override
    public boolean requestGBufferCapture() {
        return rayTracingPipeline.requestGBufferCapture();
    }

    /**
     * Returns the most recently completed G-buffer capture.
     *
     * @return latest capture, or {@code null} when no capture has completed
     */
    @Override
    public RtGBufferSnapshot latestGBufferSnapshot() {
        return rayTracingPipeline.latestGBufferSnapshot();
    }

    /**
     * Returns the sequence of the latest shared frame state.
     *
     * @return latest shared-frame sequence, or the pipeline's unavailable sentinel
     */
    @Override
    public long latestSharedFrameSequence() {
        return rayTracingPipeline.latestSharedFrameSequence();
    }

    /**
     * Returns the latest externally observable shared-frame state.
     *
     * @return immutable shared-frame state snapshot
     */
    @Override
    public RtCore.SharedFrameState latestSharedFrameState() {
        return rayTracingPipeline.latestSharedFrameState();
    }

    /**
     * Exports the latest presentable shared image when native presentation is ready.
     *
     * @return exported image ownership envelope, or {@code null} when no image is presentable
     */
    @Override
    public RtCore.SharedFrameImage exportLatestSharedFrameImage() {
        if (!sharedPresentationReady()) {
            return null;
        }
        return rayTracingPipeline.exportLatestSharedFrameImage();
    }

    /**
     * Exports the shared image associated with an exact frame-state sequence.
     *
     * @param requiredFrameStateSequence sequence the exported image must match
     * @return matching exported image, or {@code null} when unavailable or presentation is not ready
     */
    @Override
    public RtCore.SharedFrameImage exportSharedFrameImage(long requiredFrameStateSequence) {
        if (!sharedPresentationReady()) {
            return null;
        }
        return rayTracingPipeline.exportSharedFrameImage(requiredFrameStateSequence);
    }

    /**
     * Acknowledges that presentation consumed a shared Vulkan image.
     *
     * @param frameStateSequence sequence previously exported to presentation
     * @param vulkanImage        native image handle that was presented
     * @return {@code true} when the acknowledgement matched the active publication
     */
    @Override
    public boolean acknowledgeSharedFramePresented(long frameStateSequence, long vulkanImage) {
        return rayTracingPipeline.acknowledgeSharedFramePresented(frameStateSequence, vulkanImage);
    }

    /**
     * Returns current dispatch counters augmented with native GPU-stage timing.
     *
     * @return immutable runtime activity snapshot
     */
    @Override
    public RtCore.RuntimeActivity runtimeActivity() {
        RtCommandContext buildCommands = queueContexts.buildCommands();
        RtCommandContext sectionCommands = queueContexts.sectionBlasCommands();
        return rayTracingPipeline.runtimeActivity().withGpuWorkTiming(new RtCore.GpuWorkTiming(
                gpuStageTiming(sectionCommands, RtGpuWorkLabels.SECTION_BLAS),
                gpuStageTiming(buildCommands, RtGpuWorkLabels.DYNAMIC_BLAS),
                gpuStageTiming(buildCommands, RtGpuWorkLabels.DYNAMIC_TLAS),
                gpuStageTiming(buildCommands, RtGpuWorkLabels.WORLD_TLAS),
                gpuStageTiming(buildCommands, RtGpuWorkLabels.MATERIAL_UPLOAD)
        ));
    }

    /**
     * Returns CPU timing for the most recent native frame-accept path.
     *
     * @return immutable native frame timing snapshot
     */
    @Override
    public RtCore.NativeFrameTiming nativeFrameTiming() {
        return acceptFrameTiming.snapshot();
    }

    /**
     * Returns the pipeline's latest native dispatch admission decision.
     *
     * @return immutable dispatch decision snapshot
     */
    @Override
    public RtCore.NativeDispatchDecision nativeDispatchDecision() {
        return rayTracingPipeline.nativeDispatchDecision();
    }

    /**
     * Returns end-to-end generation progress for the dynamic scene.
     *
     * @return immutable dynamic generation snapshot
     */
    @Override
    public RtCore.DynamicGenerationState dynamicGenerationState() {
        RtDynamicResidencyState residency = dynamicBlasCache.snapshotResidencyState();
        return new RtCore.DynamicGenerationState(
                dynamicBlasCache.latestObservedSceneRevision(),
                dynamicBlasCache.revision(),
                dynamicBlasCache.topologyRevision(),
                dynamicBlasCache.geometryRevision(),
                dynamicBlasCache.latestCausality(),
                residency.pendingAssetBuilds(),
                residency.queuedAssetBuilds(),
                residency.inactiveAssetSlots(),
                residency.replacementAssetSlots(),
                worldTlasCache.boundDynamicRevision(),
                worldTlasCache.boundDynamicTopologyRevision(),
                worldTlasCache.boundDynamicGeometryRevision(),
                rayTracingPipeline.activeDescriptorGeneration(),
                rayTracingPipeline.latestDynamicSceneRevision(),
                rayTracingPipeline.latestDispatchedDynamicSceneRevision(),
                rayTracingPipeline.latestCompletedDynamicSceneRevision()
        );
    }

    /**
     * Returns cache, publication, and frame progress for one terrain section.
     *
     * @param key section identity to inspect
     * @return immutable section generation snapshot
     * @throws NullPointerException if {@code key} is {@code null}
     */
    @Override
    public RtCore.SectionGenerationState sectionGenerationState(SectionKey key) {
        Objects.requireNonNull(key, "key");
        RtSectionDebugState cache = sectionBlasCache.snapshotSectionDebugState(key);
        long publishedRevision = sceneBindCoordinator.publication().worldSectionContentRevisions().getOrDefault(key, -1L);
        boolean published = publishedRevision >= 0L && sceneBindCoordinator.publication().worldSectionKeys().contains(key);
        RtCore.FrameGenerationProgress progress = published
                ? rayTracingPipeline.sectionFrameProgress(key, publishedRevision)
                : RtCore.FrameGenerationProgress.unavailable();
        return new RtCore.SectionGenerationState(
                key,
                cache.desiredContentRevision(),
                cache.activeContentRevision(),
                publishedRevision,
                cache.geometryGeneration(),
                cache.materialGeneration(),
                cache.buildSequence(),
                cache.queued(),
                cache.recording(),
                cache.gpuInFlight(),
                cache.active(),
                published,
                published,
                published ? sceneBindCoordinator.publication().worldTlasRevision() : -1L,
                published ? sceneBindCoordinator.publication().generation() : -1L,
                progress,
                cache.causality(),
                published ? sceneBindCoordinator.publication().worldSectionCausality(key) : RendererFrameCausality.untraced(0L)
        );
    }

    /**
     * Returns cache, publication, and frame progress for one dynamic entity.
     *
     * @param entityId stable entity identity to inspect
     * @return immutable entity generation snapshot, possibly marked unavailable
     */
    @Override
    public RtCore.DynamicEntityGenerationState dynamicEntityGenerationState(long entityId) {
        RtDynamicEntityDebugState cache = dynamicBlasCache.snapshotEntityDebugState(entityId);
        int publishedPrimitiveCount = 0;
        long publishedNewestAssetRevision = -1L;
        for (DynamicRenderScene.DynamicModelInstance instance : sceneBindCoordinator.publication().dynamicScene().modelInstances()) {
            if (DynamicModelIdentity.entityIdFromPrimitiveId(instance.id()) == entityId) {
                publishedPrimitiveCount++;
                publishedNewestAssetRevision = Math.max(
                        publishedNewestAssetRevision,
                        instance.asset().revision()
                );
            }
        }
        boolean published = publishedPrimitiveCount > 0;
        if (!cache.observed() && !published) {
            return RtCore.DynamicEntityGenerationState.unavailable(entityId);
        }
        long publishedSceneRevision = published ? sceneBindCoordinator.publication().dynamicScene().revision() : -1L;
        RtCore.FrameGenerationProgress progress = published
                ? rayTracingPipeline.dynamicFrameProgress(publishedSceneRevision)
                : RtCore.FrameGenerationProgress.unavailable();
        return new RtCore.DynamicEntityGenerationState(
                entityId,
                cache.sceneRevision(),
                cache.primitiveCount(),
                cache.assetCount(),
                cache.queuedAssetCount(),
                cache.pendingAssetCount(),
                cache.residentAssetCount(),
                cache.newestAssetRevision(),
                cache.observed() ? dynamicBlasCache.revision() : -1L,
                worldTlasCache.boundDynamicRevision(),
                published,
                publishedPrimitiveCount,
                publishedNewestAssetRevision,
                published ? sceneBindCoordinator.publication().generation() : -1L,
                published ? sceneBindCoordinator.publication().dynamicTlasRevision() : -1L,
                publishedSceneRevision,
                progress,
                cache.causality(),
                published ? sceneBindCoordinator.publication().causality() : RendererFrameCausality.untraced(0L)
        );
    }

    /**
     * Returns the descriptor-visible scene publication state.
     *
     * @return immutable publication snapshot
     */
    @Override
    public RtCore.ScenePublicationState scenePublicationState() {
        return sceneBindCoordinator.publication().snapshot();
    }

    /**
     * Returns native terrain ownership relative to the currently published world TLAS.
     *
     * @return immutable native terrain ownership snapshot
     */
    @Override
    public NativeTerrainOwnership nativeTerrainOwnership() {
        return sectionBlasCache.terrainOwnership(
                sceneBindCoordinator.publication().worldSectionKeys(),
                sceneBindCoordinator.publication().worldTlasRevision()
        );
    }

    /**
     * Returns the generation of native terrain ownership visible to presentation.
     *
     * @return monotonically advancing ownership generation
     */
    @Override
    public long nativeTerrainOwnershipGeneration() {
        return sectionBlasCache.terrainOwnershipGeneration(sceneBindCoordinator.publication().worldTlasRevision());
    }

    /**
     * Returns scene readiness with pending, not-yet-bound world updates accounted for.
     *
     * @return immutable readiness snapshot
     */
    @Override
    public RtSceneReadiness sceneReadiness() {
        RtSceneReadiness readiness = worldTlasCache.readiness(sectionBlasCache, dynamicBlasCache);
        return applyPendingWorldSceneReadiness(readiness);
    }

    private RtSceneReadiness observedSceneReadiness() {
        return applyPendingWorldSceneReadiness(worldTlasCache.publishedReadiness());
    }

    private RtSceneReadiness applyPendingWorldSceneReadiness(RtSceneReadiness readiness) {
        PendingWorldSceneBind pending = sceneBindCoordinator.state().pendingWorld();
        if (pending != null) {
            return unboundWorldSceneReadiness(readiness, pending.worldTlasUpdate());
        }
        DeferredWorldSceneBind deferred = sceneBindCoordinator.state().deferredWorld();
        if (deferred != null) {
            return unboundWorldSceneReadiness(readiness, deferred.worldTlasUpdate());
        }
        return readiness;
    }

    /**
     * Returns terrain sections referenced by the latest shared frame.
     *
     * @return immutable section-key set
     */
    @Override
    public Set<SectionKey> latestSharedFrameSectionKeys() {
        return rayTracingPipeline.latestSharedFrameSectionKeys();
    }

    /**
     * Probes Win32 external-memory export against the active Vulkan device.
     *
     * @return structured probe result without transferring resource ownership
     */
    @Override
    public RtCore.ExternalMemoryInteropProbe probeExternalMemoryInterop() {
        return externalFrameInterop.probeExternalMemoryInterop();
    }

    /**
     * Probes Win32 external-semaphore export against the active Vulkan device.
     *
     * @return structured probe result without transferring resource ownership
     */
    @Override
    public RtCore.ExternalSemaphoreInteropProbe probeExternalSemaphoreInterop() {
        return externalFrameInterop.probeExternalSemaphoreInterop();
    }

    /**
     * Builds a compact diagnostic description of device, queues, and publication state.
     *
     * @return single-line diagnostic summary
     */
    @Override
    public String summary() {
        return "nativeBackend=vulkanDevice"
                + ", device=" + deviceName
                + ", queueFamily=" + queueContexts.queueFamilyIndex()
                + ", requestedQueueCount=" + queueContexts.requestedQueueCount()
                + ", queueHandle=0x" + Long.toHexString(queueContexts.frameQueue().address())
                + ", frameQueueHandle=0x" + Long.toHexString(queueContexts.frameQueue().address())
                + ", buildQueueHandle=" + "0x" + Long.toHexString(queueContexts.buildQueue().address())
                + ", queuesSeparated=" + queueContexts.queuesSeparated()
                + ", buildTimelineEnabled=" + queueContexts.timelineEnabled()
                + ", completedBuildTimelineValue=" + queueContexts.completedBuildTimelineValue()
                + ", allocator=0x" + Long.toHexString(allocator)
                + ", outputOwnership=rendererSharedImage"
                + ", gpuSharedReady=" + sharedPresentationReady()
                + ", pendingWorldSceneBind=" + sceneBindCoordinator.state().hasPendingWorld()
                + ", pendingWorldSceneBindRevision=" + (!sceneBindCoordinator.state().hasPendingWorld()
                ? -1L
                : sceneBindCoordinator.state().pendingWorld().worldTlasUpdate().revision())
                + ", pendingMaterialOnlyBind=" + sceneBindCoordinator.state().hasPendingMaterial()
                + ", pendingDynamicTlasBind=" + sceneBindCoordinator.state().hasPendingDynamic()
                + ", boundDynamicTlas=" + (sceneBindCoordinator.publication().dynamicTlas() != null)
                + ", latestViewRevision=" + acceptedSceneAuthority.viewRevision()
                + ", publishedWorldViewRevision=" + sceneBindCoordinator.publication().worldViewRevision()
                + ", boundMaterialRevision=" + sceneBindCoordinator.publication().materialSnapshot().revision()
                + ", boundMaterialTextureRevision=" + sceneBindCoordinator.publication().materialSnapshot().textureSnapshot().revision()
                + ", deferredWorldSceneBind=" + sceneBindCoordinator.state().hasDeferredWorld()
                + ", deferredWorldSceneBindRevision=" + (!sceneBindCoordinator.state().hasDeferredWorld()
                ? -1L
                : sceneBindCoordinator.state().deferredWorld().worldTlasUpdate().revision())
                + ", " + worldSceneBindStatistics.summary()
                + ", minStreamingSceneBindIntervalMillis="
                + scheduling.minStreamingSceneBindIntervalNanos() / 1_000_000L
                + ", maxStreamingSceneBindDeferrals=" + scheduling.maxStreamingSceneBindDeferrals()
                + ", immediateStreamingBindMaxFaces=" + scheduling.immediateStreamingBindMaxFaces()
                + ", pendingInteractiveWorldSceneBindUrgency=" + interactiveTerrain.urgent()
                + ", interactivePresentTargets=" + interactiveTerrain.presentTargetCount()
                + ", interactiveAbsentTargets=" + interactiveTerrain.absentTargetCount()
                + ", " + acceptFrameTiming.admissionSummary()
                + ", maxPendingFrameAgeBeforeBuildMillis="
                + scheduling.maxPendingFrameAgeBeforeBuildMillis()
                + ", maxConvergenceVisualStalenessMillis="
                + scheduling.maxConvergenceVisualStalenessNanos() / 1_000_000L
                + ", foregroundFrameBudgetMicros=" + scheduling.foregroundFrameBudgetNanos() / 1_000L
                + ", " + acceptFrameTiming.summary()
                + ", " + queueContexts.frameCommands().summary("commandContext")
                + ", " + queueContexts.buildCommands().summary("buildCommandContext")
                + ", " + queueContexts.sectionBlasCommands().summary("sectionBlasCommandContext")
                + ", " + bootstrapBuffer.summary("bootstrapBuffer")
                + ", asScratchAlign=" + accelerationStructureScratchAlignment
                + ", " + bootstrapBlas.summary("bootstrapBlas")
                + ", " + bootstrapTlas.summary("bootstrapTlas")
                + ", " + sceneMaterialTable.summary("sceneMaterialTable")
                + ", " + sectionBlasCache.summary("sectionBlasCache")
                + ", " + dynamicBlasCache.summary("dynamicBlasCache")
                + ", " + dynamicTlasCache.summary()
                + ", " + worldTlasCache.summary("worldTlasCache")
                + ", " + rayTracingPipelineProperties.summary("rayTracingPipelineProperties")
                + ", " + rayTracingPipeline.summary("rayTracingPipeline")
                + ", " + externalFrameInterop.summary()
                + ", enabledExtensions=" + enabledExtensions;
    }

    private boolean sharedPresentationReady() {
        return externalFrameInterop.sharedPresentationReady();
    }

}
