package top.ceroxe.rt.renderer;

import top.ceroxe.rt.renderer.diagnostics.RtFirstFrontCausalityRecorder;
import top.ceroxe.rt.renderer.diagnostics.RtSceneCausalityRecorder;
import top.ceroxe.rt.renderer.orchestration.takeover.RtForegroundAdmissionFlightRecorder;
import top.ceroxe.rt.renderer.scene.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Renderer 后端的帧末更新入口。
 *
 * <p>当前阶段还没有 Vulkan backend，但仍然要先固定 update contract：hook 层只写入
 * scene database，帧末只消费不可变 batch。未来 BLAS/TLAS builder 只能接这个 batch，
 * 不能直接窥探 pending 集合。</p>
 */
public final class RendererUpdateLoop implements AutoCloseable {
    private static final long INITIAL_VERBOSE_BATCHES = 8;
    private static final long UPDATE_LOG_INTERVAL = 256;
    private static final int DEFAULT_MAX_SECTION_SNAPSHOTS_PER_FRAME = 32;
    private static final int DEFAULT_MAX_COMPLETED_SECTION_BUILDS_PER_FRAME = 32;
    private static final long DEFAULT_MAX_COMPLETED_SECTION_BUILD_MESH_BYTES_PER_FRAME = 24L * 1024L * 1024L;
    private static final long DEFAULT_MAX_COMPLETED_SECTION_BUILD_TRIANGLES_PER_FRAME = 384_000L;
    private static final long DEFAULT_MAX_COMPLETED_SECTION_BUILD_DRAIN_MICROS_PER_FRAME = 500L;
    private static final int MAX_DEFAULT_SECTION_BUILD_THREADS = 8;
    private static final int RESERVED_SYSTEM_THREADS = 2;
    private static final int MIN_SECTION_SNAPSHOTS_PER_FRAME = 4;
    private static final int MIN_COMPLETED_SECTION_BUILDS_PER_FRAME = 1;
    // Pending-scene camera fairness is owned by RendererPendingSceneWork.
    private static final long TARGET_UPDATE_NANOS = 500_000L;
    private static final long HIGH_UPDATE_NANOS = 1_000_000L;
    private static final String MAX_SECTION_SNAPSHOTS_PER_FRAME_PROPERTY =
            "top.ceroxe.rt.renderer.maxSectionSnapshotsPerFrame";
    private static final String MAX_COMPLETED_SECTION_BUILDS_PER_FRAME_PROPERTY =
            "top.ceroxe.rt.renderer.maxCompletedSectionBuildsPerFrame";
    private static final String MAX_COMPLETED_SECTION_BUILD_MESH_BYTES_PER_FRAME_PROPERTY =
            "top.ceroxe.rt.renderer.maxCompletedSectionBuildMeshBytesPerFrame";
    private static final String MAX_COMPLETED_SECTION_BUILD_TRIANGLES_PER_FRAME_PROPERTY =
            "top.ceroxe.rt.renderer.maxCompletedSectionBuildTrianglesPerFrame";
    private static final String MAX_COMPLETED_SECTION_BUILD_DRAIN_MICROS_PER_FRAME_PROPERTY =
            "top.ceroxe.rt.renderer.maxCompletedSectionBuildDrainMicrosPerFrame";
    private static final String SECTION_BUILD_THREADS_PROPERTY =
            "top.ceroxe.rt.renderer.sectionBuildThreads";

    private final SceneDatabase sceneDatabase;
    private final SectionMaterialCache sectionMaterialCache;
    private final SectionGeometryCache sectionGeometryCache;
    private final SectionMeshCache sectionMeshCache;
    private final RendererPendingSceneWork pendingSceneWork;
    private final AsyncSectionBuildPipeline sectionBuildPipeline;
    private final Map<SectionKey, SectionBuildTicket> sectionBuildGenerations = new HashMap<>();
    private final PackedSectionMembership.Builder inFlightSourcePinBuilder =
            PackedSectionMembership.builder(0);
    private final Map<SectionKey, SceneDatabase.SectionNeighborhoodRevision> committedSectionBuildInputs =
            new HashMap<>();
    private final Map<SectionKey, Long> sectionContentRevisions = new HashMap<>();
    private final int maxSectionSnapshotsPerFrame;
    private final int maxCompletedSectionBuildsPerFrame;
    private final long maxCompletedSectionBuildMeshBytesPerFrame;
    private final long maxCompletedSectionBuildTrianglesPerFrame;
    private final long maxCompletedSectionBuildDrainNanosPerFrame;
    private final SceneCacheBudget budget;
    private final boolean logUpdates;
    private final AtomicLong drainedFrames = new AtomicLong();
    private final AtomicLong changedBatches = new AtomicLong();
    private final AtomicLong fullResyncBatches = new AtomicLong();
    private final AtomicLong budgetLimitedBatches = new AtomicLong();
    private final AtomicLong submittedSectionBuilds = new AtomicLong();
    private final AtomicLong completedSectionBuilds = new AtomicLong();
    private final AtomicLong staleCompletedSectionBuilds = new AtomicLong();
    private final AtomicLong coalescedSectionBuildInputs = new AtomicLong();
    private final AtomicReference<RendererFrameUpdate> lastChangedUpdate = new AtomicReference<>();
    private final AtomicReference<RendererFrameState> latestFrameState =
            new AtomicReference<>(RendererFrameState.unavailable());
    private final AtomicReference<Set<ChunkKey>> pendingBridgeChunkKeys =
            new AtomicReference<>(Set.of());
    private final AtomicReference<Set<SectionKey>> foregroundSectionKeys =
            new AtomicReference<>(Set.of());
    private final AtomicReference<DynamicRenderScene> pendingDynamicScene =
            new AtomicReference<>(DynamicRenderScene.empty());
    private final AtomicReference<LightmapPayload> latestLightmapPayload =
            new AtomicReference<>(LightmapPayload.unknown());
    private final DynamicRenderSceneCollector dynamicSceneCollector = new DynamicRenderSceneCollector();
    private final DynamicScenePublicationState dynamicScenePublicationState = new DynamicScenePublicationState();
    private final AtomicLong submittedDynamicScenes = new AtomicLong();
    private final AtomicLong submittedDynamicFacts = new AtomicLong();
    private final AtomicLong drainedDynamicScenes = new AtomicLong();
    private final AtomicLong drainedDynamicElements = new AtomicLong();
    private PackedSectionMembership inFlightSourcePins = PackedSectionMembership.empty();
    private long sectionBuildTicketRevision;
    private long sectionContentRevision;
    private long cachedPendingSceneMetadataRevision = -1L;
    private long cachedPendingQueueMetadataRevision = -1L;
    private long cachedSectionBuildTicketRevision = -1L;
    private long cachedSectionContentRevision = -1L;
    private PendingTerrainMetadata cachedPendingTerrainMetadata = PendingTerrainMetadata.empty();
    private int adaptiveSectionSnapshotsPerFrame;
    private int adaptiveCompletedSectionBuildsPerFrame;
    private long nextSectionBuildGeneration = 1L;

    /**
     * Creates a renderer update loop using system-configured scheduling limits.
     *
     * @param sceneDatabase        renderer-owned scene database
     * @param sectionMaterialCache material cache
     * @param sectionGeometryCache geometry cache
     * @param sectionMeshCache     mesh cache
     */
    public RendererUpdateLoop(
            SceneDatabase sceneDatabase,
            SectionMaterialCache sectionMaterialCache,
            SectionGeometryCache sectionGeometryCache,
            SectionMeshCache sectionMeshCache
    ) {
        this(
                sceneDatabase,
                sectionMaterialCache,
                sectionGeometryCache,
                sectionMeshCache,
                positiveIntProperty(MAX_SECTION_SNAPSHOTS_PER_FRAME_PROPERTY, DEFAULT_MAX_SECTION_SNAPSHOTS_PER_FRAME),
                positiveIntProperty(
                        MAX_COMPLETED_SECTION_BUILDS_PER_FRAME_PROPERTY,
                        DEFAULT_MAX_COMPLETED_SECTION_BUILDS_PER_FRAME
                ),
                positiveIntProperty(SECTION_BUILD_THREADS_PROPERTY, defaultSectionBuildThreads()),
                SceneCacheBudget.DEFAULT,
                true
        );
    }

    RendererUpdateLoop(
            SceneDatabase sceneDatabase,
            SectionMaterialCache sectionMaterialCache,
            SectionGeometryCache sectionGeometryCache,
            SectionMeshCache sectionMeshCache,
            int maxSectionSnapshotsPerFrame
    ) {
        this(
                sceneDatabase,
                sectionMaterialCache,
                sectionGeometryCache,
                sectionMeshCache,
                maxSectionSnapshotsPerFrame,
                Math.max(DEFAULT_MAX_COMPLETED_SECTION_BUILDS_PER_FRAME, maxSectionSnapshotsPerFrame),
                1,
                SceneCacheBudget.DEFAULT,
                true
        );
    }

    RendererUpdateLoop(
            SceneDatabase sceneDatabase,
            SectionMaterialCache sectionMaterialCache,
            SectionGeometryCache sectionGeometryCache,
            SectionMeshCache sectionMeshCache,
            int maxSectionSnapshotsPerFrame,
            boolean logUpdates
    ) {
        this(
                sceneDatabase,
                sectionMaterialCache,
                sectionGeometryCache,
                sectionMeshCache,
                maxSectionSnapshotsPerFrame,
                Math.max(DEFAULT_MAX_COMPLETED_SECTION_BUILDS_PER_FRAME, maxSectionSnapshotsPerFrame),
                1,
                SceneCacheBudget.DEFAULT,
                logUpdates
        );
    }

    RendererUpdateLoop(
            SceneDatabase sceneDatabase,
            SectionMaterialCache sectionMaterialCache,
            SectionGeometryCache sectionGeometryCache,
            SectionMeshCache sectionMeshCache,
            int maxSectionSnapshotsPerFrame,
            int maxCompletedSectionBuildsPerFrame,
            int sectionBuildThreads,
            boolean logUpdates
    ) {
        this(
                sceneDatabase,
                sectionMaterialCache,
                sectionGeometryCache,
                sectionMeshCache,
                maxSectionSnapshotsPerFrame,
                maxCompletedSectionBuildsPerFrame,
                sectionBuildThreads,
                SceneCacheBudget.DEFAULT,
                logUpdates
        );
    }

    RendererUpdateLoop(
            SceneDatabase sceneDatabase,
            SectionMaterialCache sectionMaterialCache,
            SectionGeometryCache sectionGeometryCache,
            SectionMeshCache sectionMeshCache,
            int maxSectionSnapshotsPerFrame,
            int maxCompletedSectionBuildsPerFrame,
            int sectionBuildThreads,
            SceneCacheBudget budget,
            boolean logUpdates
    ) {
        this.sceneDatabase = Objects.requireNonNull(sceneDatabase, "sceneDatabase");
        this.sectionMaterialCache = Objects.requireNonNull(sectionMaterialCache, "sectionMaterialCache");
        this.sectionGeometryCache = Objects.requireNonNull(sectionGeometryCache, "sectionGeometryCache");
        if (this.sectionGeometryCache.retainsSnapshots()) {
            throw new IllegalArgumentException(
                    "RendererUpdateLoop requires transient geometry staging; packed meshes are the only committed geometry authority"
            );
        }
        this.sectionMeshCache = Objects.requireNonNull(sectionMeshCache, "sectionMeshCache");
        this.pendingSceneWork = RendererPendingSceneWork.sceneBacked(sceneDatabase);
        this.budget = Objects.requireNonNull(budget, "budget");
        if (maxSectionSnapshotsPerFrame <= 0) {
            throw new IllegalArgumentException("maxSectionSnapshotsPerFrame must be positive");
        }
        if (maxCompletedSectionBuildsPerFrame <= 0) {
            throw new IllegalArgumentException("maxCompletedSectionBuildsPerFrame must be positive");
        }
        if (sectionBuildThreads <= 0) {
            throw new IllegalArgumentException("sectionBuildThreads must be positive");
        }
        this.maxSectionSnapshotsPerFrame = maxSectionSnapshotsPerFrame;
        this.maxCompletedSectionBuildsPerFrame = maxCompletedSectionBuildsPerFrame;
        this.maxCompletedSectionBuildMeshBytesPerFrame = positiveLongProperty(
                MAX_COMPLETED_SECTION_BUILD_MESH_BYTES_PER_FRAME_PROPERTY,
                DEFAULT_MAX_COMPLETED_SECTION_BUILD_MESH_BYTES_PER_FRAME
        );
        this.maxCompletedSectionBuildTrianglesPerFrame = positiveLongProperty(
                MAX_COMPLETED_SECTION_BUILD_TRIANGLES_PER_FRAME_PROPERTY,
                DEFAULT_MAX_COMPLETED_SECTION_BUILD_TRIANGLES_PER_FRAME
        );
        this.maxCompletedSectionBuildDrainNanosPerFrame = positiveLongProperty(
                MAX_COMPLETED_SECTION_BUILD_DRAIN_MICROS_PER_FRAME_PROPERTY,
                DEFAULT_MAX_COMPLETED_SECTION_BUILD_DRAIN_MICROS_PER_FRAME
        ) * 1_000L;
        this.logUpdates = logUpdates;
        this.adaptiveSectionSnapshotsPerFrame = maxSectionSnapshotsPerFrame;
        this.adaptiveCompletedSectionBuildsPerFrame = maxCompletedSectionBuildsPerFrame;
        this.sectionBuildPipeline = new AsyncSectionBuildPipeline(sectionBuildThreads);
    }

    private static long nextMetadataRevision(long revision, String owner) {
        if (revision == Long.MAX_VALUE) {
            throw new IllegalStateException(owner + " metadata revision space exhausted");
        }
        return revision + 1L;
    }

    private static boolean shouldLog(long count, long interval) {
        return count <= INITIAL_VERBOSE_BATCHES || count % interval == 0;
    }

    static int nextCompletedBuildCommitBudget(
            int currentBudget,
            int maximumBudget,
            int committedSections,
            long commitNanos
    ) {
        if (currentBudget <= 0 || maximumBudget <= 0 || currentBudget > maximumBudget) {
            throw new IllegalArgumentException("invalid completed-build commit budget");
        }
        if (committedSections < 0 || commitNanos < 0L) {
            throw new IllegalArgumentException("completed-build commit samples must not be negative");
        }
        if (committedSections == 0) {
            return currentBudget;
        }
        if (commitNanos > HIGH_UPDATE_NANOS) {
            return Math.max(MIN_COMPLETED_SECTION_BUILDS_PER_FRAME, currentBudget / 2);
        }
        if (commitNanos < TARGET_UPDATE_NANOS && currentBudget < maximumBudget) {
            return currentBudget + 1;
        }
        return currentBudget;
    }

    private static int positiveIntProperty(String name, int defaultValue) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static long positiveLongProperty(String name, long defaultValue) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0L ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static int defaultSectionBuildThreads() {
        int processors = Runtime.getRuntime().availableProcessors();
        int availableForRenderer = Math.max(1, processors - RESERVED_SYSTEM_THREADS);
        return Math.max(1, Math.min(MAX_DEFAULT_SECTION_BUILD_THREADS, availableForRenderer));
    }

    private static String sourceFlagsSummary(int flags) {
        if (flags == 0) return "none";
        List<String> sources = new ArrayList<>(6);
        if ((flags & SceneUpdateBatch.SOURCE_RENDER_DIRTY) != 0) sources.add("renderDirty");
        if ((flags & SceneUpdateBatch.SOURCE_BLOCK_MUTATION) != 0) sources.add("blockMutation");
        if ((flags & SceneUpdateBatch.SOURCE_CHUNK_STREAMING) != 0) sources.add("chunkStreaming");
        if ((flags & SceneUpdateBatch.SOURCE_SECTION_REMOVAL) != 0) sources.add("sectionRemoval");
        if ((flags & SceneUpdateBatch.SOURCE_FULL_RESYNC) != 0) sources.add("fullResync");
        if ((flags & SceneUpdateBatch.SOURCE_MATERIAL_ONLY) != 0) sources.add("materialOnly");
        if ((flags & SceneUpdateBatch.SOURCE_NEIGHBOR_DEPENDENCY) != 0) sources.add("neighborDependency");
        if ((flags & SceneUpdateBatch.SOURCE_DIRECT_CONTENT) != 0) sources.add("directContent");
        return String.join("+", sources);
    }

    /**
     * Drains one steady-state bounded renderer update.
     *
     * @return immutable frame update
     */
    public RendererFrameUpdate drainFrameUpdates() {
        return drainFrameUpdates(false);
    }

    /**
     * Drains a bounded, larger bootstrap batch while host still owns its loading UI.
     * The steady-state adaptive limits remain untouched after handoff, avoiding a permanent
     * frame-time regression from terrain streaming.
     *
     * @param bootstrapPrewarm whether bootstrap-specific batch limits may be used
     * @return immutable frame update
     */
    public RendererFrameUpdate drainFrameUpdates(boolean bootstrapPrewarm) {
        drainedFrames.incrementAndGet();
        RendererFrameState frameState = latestFrameState.get();
        DynamicRenderScene dynamicScene = drainDynamicScene(frameState);
        SceneUpdateBatch ingestedBatch = sceneDatabase.drainPendingUpdates();
        for (SectionVoxelSnapshot snapshot : ingestedBatch.sectionSnapshots().values()) {
            boolean recordAdmission = RtForegroundAdmissionFlightRecorder.acceptsSection(snapshot.key());
            boolean recordFirstFront = RtFirstFrontCausalityRecorder.acceptsTextEvent();
            if (recordAdmission || recordFirstFront) {
                String details = "batchSections=" + ingestedBatch.sectionSnapshotCount()
                        + ", sourceFlags=0x"
                        + Integer.toHexString(ingestedBatch.sourceFlagsForSection(snapshot.key()));
                if (recordAdmission) {
                    RtForegroundAdmissionFlightRecorder.recordSection(
                            snapshot.key(), "sceneDatabaseDrained", details
                    );
                }
                if (recordFirstFront) {
                    RtFirstFrontCausalityRecorder.recordSectionKey(
                            "rendererSceneDatabaseDrained", snapshot.key(), details
                    );
                }
            }
        }
        pendingSceneWork.ingest(ingestedBatch);
        publishInFlightSourcePins();
        int completedDrainBudgetThisFrame = completedBuildDrainBudgetThisFrame(bootstrapPrewarm);
        int sectionBudgetThisFrame = sectionSubmissionBudgetThisFrame(
                completedDrainBudgetThisFrame,
                bootstrapPrewarm
        );
        Set<SectionKey> foregroundKeys = foregroundSectionKeys.get();
        boolean coalesceForegroundNeighborWork = bootstrapPrewarm || !foregroundKeys.isEmpty();
        Set<SectionKey> directBuildKeysInFlight = directCpuBuildKeysInFlight();
        SceneUpdateBatch submissionBatch = pendingSceneWork.drainFrame(
                sectionBudgetThisFrame,
                frameState,
                foregroundKeys,
                coalesceForegroundNeighborWork,
                coalesceForegroundNeighborWork && hasDirectCpuBuildsInFlight(
                        foregroundKeys,
                        bootstrapPrewarm
                ),
                bootstrapPrewarm,
                pendingBridgeChunkKeys.get(),
                directBuildKeysInFlight
        );
        invalidateStaleBuilds(submissionBatch);
        CompletedBuildBatch completedBuildBatch = drainCompletedBuilds(
                completedDrainBudgetThisFrame,
                bootstrapPrewarm
        );
        SceneUpdateBatch batch = buildFrameBatch(submissionBatch, completedBuildBatch);
        int submittedBuilds = submitSectionBuilds(submissionBatch);
        boolean stillPending = pendingSceneWork.hasPendingWork() || sectionBuildPipeline.hasPendingWork();
        if (!batch.hasChanges()) {
            if (stillPending) {
                budgetLimitedBatches.incrementAndGet();
            }
            if (dynamicScene.hasSceneUpdate()) {
                RendererFrameUpdate update =
                        RendererFrameUpdate.dynamicOnly(batch, frameState, backlogSnapshot(), dynamicScene);
                lastChangedUpdate.set(update);
                long changed = changedBatches.incrementAndGet();
                drainedDynamicScenes.incrementAndGet();
                drainedDynamicElements.addAndGet(dynamicScene.totalElements());
                if (logUpdates && shouldLog(changed, UPDATE_LOG_INTERVAL)) {
                    top.ceroxe.rt.renderer.RendererLog.info(
                            "renderer dynamic-only update drained count={}, {}, {}, pendingSceneSections={}, pendingSceneRemovals={}, pendingCpuBuilds={}, completedCpuBuildsWaiting={}, budgetLimited={}",
                            changed,
                            dynamicScene.asLogFragment(),
                            update.commitPlan().asLogFragment(),
                            pendingSceneWork.pendingSectionSnapshots(),
                            pendingSceneWork.pendingRemovedSections(),
                            sectionBuildPipeline.pendingSectionBuilds(),
                            sectionBuildPipeline.completedSectionBuildsWaiting(),
                            stillPending
                    );
                }
                return update;
            }
            return RendererFrameUpdate.empty(batch, frameState, backlogSnapshot());
        }

        long updateStartNanos = System.nanoTime();
        SectionMaterialCache.MaterialFacts materialFacts = completedBuildBatch.materialFacts();
        for (SectionVoxelSnapshot snapshot : batch.sectionSnapshots().values()) {
            if (!completedBuildBatch.snapshots().containsKey(snapshot.key())) {
                materialFacts = materialFacts.plus(SectionMaterialCache.MaterialFacts.fromSnapshot(snapshot));
            }
        }
        SectionMaterialCache.ApplyResult cacheResult = sectionMaterialCache.applyMaterialUpdates(
                batch,
                batch.sectionSnapshots().keySet(),
                materialFacts
        );
        SectionGeometryCache.ApplyResult geometryResult = sectionGeometryCache.applyProducedFaceCounts(
                completedBuildBatch.faceCounts(),
                batch.removedSections(),
                batch.fullResyncRequested()
        );
        SectionMeshCache.ApplyResult meshResult = sectionMeshCache.applyPrepared(
                completedBuildBatch.meshes(),
                batch.removedSections(),
                batch.fullResyncRequested()
        );
        if (batch.fullResyncRequested()) {
            clearSectionContentRevisions();
            committedSectionBuildInputs.clear();
        }
        for (SectionKey key : batch.removedSections()) {
            removeSectionContentRevision(key);
            committedSectionBuildInputs.remove(key);
        }
        for (SectionKey key : meshResult.builtMeshes().keySet()) {
            Long contentRevision = completedBuildBatch.contentRevisions().get(key);
            if (contentRevision == null) {
                throw new IllegalStateException("completed mesh is missing its content revision: " + key);
            }
            putSectionContentRevision(key, contentRevision);
        }
        long updateNanos = System.nanoTime() - updateStartNanos;
        adjustSectionBudget(updateNanos, meshResult.builtInBatch() + submittedBuilds);
        adaptiveCompletedSectionBuildsPerFrame = nextCompletedBuildCommitBudget(
                adaptiveCompletedSectionBuildsPerFrame,
                maxCompletedSectionBuildsPerFrame,
                meshResult.builtInBatch(),
                updateNanos
        );

        RendererFrameUpdate update = new RendererFrameUpdate(
                batch,
                cacheResult,
                geometryResult,
                meshResult,
                frameState,
                backlogSnapshot(),
                dynamicScene,
                RendererFrameCommitPlan.from(
                        batch,
                        cacheResult,
                        meshResult,
                        dynamicScene,
                        sectionContentRevisionsFor(meshResult.builtMeshes().keySet())
                )
        );
        lastChangedUpdate.set(update);
        long changed = changedBatches.incrementAndGet();
        if (dynamicScene.hasSceneUpdate()) {
            drainedDynamicScenes.incrementAndGet();
            drainedDynamicElements.addAndGet(dynamicScene.totalElements());
        }
        if (batch.fullResyncRequested()) {
            fullResyncBatches.incrementAndGet();
        }
        if (stillPending) {
            budgetLimitedBatches.incrementAndGet();
        }
        if (logUpdates && shouldLog(changed, UPDATE_LOG_INTERVAL)) {
            top.ceroxe.rt.renderer.RendererLog.info(
                    "renderer update batch drained count={}, {}, {}, {}, encodedSections={}, removedMaterialSections={}, cachedSections={}, {}, builtGeometrySections={}, removedGeometrySections={}, facesInBatch={}, builtMeshSections={}, removedMeshSections={}, trianglesInBatch={}, updateMillis={}, submittedSectionBuilds={}, completedSectionBuilds={}, staleCompletedSectionBuilds={}, pendingSceneSections={}, pendingSceneRemovals={}, pendingCpuBuilds={}, completedCpuBuildsWaiting={}, sectionBudgetThisFrame={}, nextSectionBudget={}, maxSectionSnapshotsPerFrame={}, maxCompletedSectionBuildsPerFrame={}, budgetLimited={}",
                    changed,
                    batch.summary(),
                    dynamicScene.asLogFragment(),
                    update.commitPlan().asLogFragment(),
                    cacheResult.updatedInBatch(),
                    cacheResult.removedInBatch(),
                    cacheResult.cachedSections(),
                    cacheResult.materialFacts().asLogFragment(),
                    geometryResult.builtInBatch(),
                    geometryResult.removedInBatch(),
                    geometryResult.facesInBatch(),
                    meshResult.builtInBatch(),
                    meshResult.removedInBatch(),
                    meshResult.trianglesInBatch(),
                    updateNanos / 1_000_000L,
                    submittedBuilds,
                    completedBuildBatch.size(),
                    completedBuildBatch.staleResults(),
                    pendingSceneWork.pendingSectionSnapshots(),
                    pendingSceneWork.pendingRemovedSections(),
                    sectionBuildPipeline.pendingSectionBuilds(),
                    sectionBuildPipeline.completedSectionBuildsWaiting(),
                    sectionBudgetThisFrame,
                    adaptiveSectionSnapshotsPerFrame,
                    maxSectionSnapshotsPerFrame,
                    maxCompletedSectionBuildsPerFrame,
                    stillPending
            );
            if (cacheResult.overBudget() || geometryResult.overBudget() || meshResult.overBudget()) {
                top.ceroxe.rt.renderer.RendererLog.warn(
                        "renderer cache budget pressure: material={}/{}, geometry={}/{}, mesh={}/{}",
                        cacheResult.cachedEstimatedBytes(),
                        cacheResult.budgetBytes(),
                        geometryResult.cachedEstimatedBytes(),
                        geometryResult.budgetBytes(),
                        meshResult.cachedEstimatedBytes(),
                        meshResult.budgetBytes()
                );
            }
        }
        return update;
    }

    /**
     * Returns the last drained update that contained work.
     *
     * @return last changed update, or {@code null} before the first change
     */
    public RendererFrameUpdate lastChangedUpdate() {
        return lastChangedUpdate.get();
    }

    RendererFrameState latestFrameState() {
        return latestFrameState.get();
    }

    /**
     * Restores RT mesh work from the renderer-owned voxel cache after native
     * staging pressure released a CPU mesh. The next normal drain preserves
     * the existing player-centred scheduling and generation validation.
     */
    Set<SectionKey> requestResidentSectionRebuilds(Set<SectionKey> sectionKeys) {
        return sceneDatabase.requestResidentSectionRebuilds(sectionKeys);
    }

    /**
     * Publishes the exact dense inputs still owned by renderer scheduling.
     *
     * <p>The database remains the payload owner; this loop owns only pending/build identities.
     * Reusing the packed publication avoids a HashSet/SectionKey graph on every frame and gives
     * source eviction one coherent lifecycle fence.</p>
     */
    private void publishInFlightSourcePins() {
        int expected = Math.addExact(
                pendingSceneWork.pendingSectionSnapshots(),
                sectionBuildGenerations.size()
        );
        inFlightSourcePinBuilder.reset(expected);
        pendingSceneWork.appendPendingSectionKeys(inFlightSourcePinBuilder);
        for (SectionKey key : sectionBuildGenerations.keySet()) {
            inFlightSourcePinBuilder.add(key);
        }
        inFlightSourcePins = inFlightSourcePinBuilder.buildCanonical(inFlightSourcePins);
        sceneDatabase.updateInFlightSectionSourcePins(inFlightSourcePins);
    }

    /**
     * Accepts a complete dynamic-scene publication.
     *
     * @param dynamicScene immutable scene
     */
    public void submitDynamicScene(DynamicRenderScene dynamicScene) {
        DynamicRenderScene scene = Objects.requireNonNull(dynamicScene, "dynamicScene");
        pendingDynamicScene.set(scene);
        if (scene.hasSceneUpdate()) {
            submittedDynamicScenes.incrementAndGet();
        }
    }

    /**
     * Begins incremental dynamic-scene collection for one frame.
     */
    public void beginDynamicFrameCollection() {
        dynamicSceneCollector.beginFrameCollection();
    }

    /**
     * Ends incremental dynamic-scene collection for one frame.
     */
    public void endDynamicFrameCollection() {
        dynamicSceneCollector.endFrameCollection();
    }

    /**
     * Accepts one dynamic primitive.
     *
     * @param primitive observed primitive
     */
    public void submitDynamicPrimitive(DynamicRenderScene.DynamicPrimitive primitive) {
        dynamicSceneCollector.submitPrimitive(primitive);
        submittedDynamicFacts.incrementAndGet();
    }

    /**
     * Accepts one legacy dynamic model instance.
     *
     * @param instance observed model instance
     */
    public void submitDynamicModelInstance(DynamicRenderScene.DynamicModelInstance instance) {
        submitDynamicModelObservation(instance);
    }

    /**
     * Accepts one dynamic model observation.
     *
     * @param observation observed model
     */
    public void submitDynamicModelObservation(DynamicRenderScene.DynamicModelObservation observation) {
        dynamicSceneCollector.submitModelObservation(observation);
        submittedDynamicFacts.incrementAndGet();
    }

    /**
     * Accepts one billboard particle.
     *
     * @param particle observed particle
     */
    public void submitDynamicParticle(DynamicRenderScene.BillboardParticle particle) {
        dynamicSceneCollector.submitParticle(particle);
        submittedDynamicFacts.incrementAndGet();
    }

    /**
     * Accepts one beam primitive.
     *
     * @param beam observed beam
     */
    public void submitDynamicBeam(DynamicRenderScene.Beam beam) {
        dynamicSceneCollector.submitBeam(beam);
        submittedDynamicFacts.incrementAndGet();
    }

    /**
     * Accepts one block-aligned decal.
     *
     * @param decal observed decal
     */
    public void submitDynamicBlockDecal(DynamicRenderScene.BlockDecal decal) {
        dynamicSceneCollector.submitBlockDecal(decal);
        submittedDynamicFacts.incrementAndGet();
    }

    /**
     * Accepts one weather column.
     *
     * @param column observed column
     */
    public void submitDynamicWeatherColumn(DynamicRenderScene.WeatherColumn column) {
        dynamicSceneCollector.submitWeatherColumn(column);
        submittedDynamicFacts.incrementAndGet();
    }

    /**
     * Accepts one celestial body.
     *
     * @param body observed body
     */
    public void submitDynamicCelestialBody(DynamicRenderScene.CelestialBody body) {
        dynamicSceneCollector.submitCelestialBody(body);
        submittedDynamicFacts.incrementAndGet();
    }

    /**
     * Accepts one dynamic light.
     *
     * @param light observed light
     */
    public void submitDynamicLight(DynamicRenderScene.SceneLight light) {
        dynamicSceneCollector.submitLight(light);
        submittedDynamicFacts.incrementAndGet();
    }

    /**
     * Accepts dynamic environment state.
     *
     * @param state observed environment
     */
    public void submitDynamicEnvironmentState(DynamicRenderScene.EnvironmentState state) {
        dynamicSceneCollector.submitEnvironmentState(state);
        submittedDynamicFacts.incrementAndGet();
    }

    /**
     * Requests removal of all retained dynamic-scene content.
     */
    public void clearDynamicScene() {
        dynamicSceneCollector.requestClear();
        submittedDynamicScenes.incrementAndGet();
    }

    /**
     * Resets all world-scoped queues, caches and dynamic publications.
     */
    public void resetWorldState() {
        pendingSceneWork.clear();
        sectionBuildPipeline.cancelAll();
        clearSectionBuildTickets();
        inFlightSourcePins = PackedSectionMembership.empty();
        sceneDatabase.updateInFlightSectionSourcePins(inFlightSourcePins);
        pendingDynamicScene.set(DynamicRenderScene.empty());
        DynamicRenderSceneCollector.resetCapturedModelObservationsForWorld();
        dynamicSceneCollector.reset();
        dynamicScenePublicationState.reset();
        latestFrameState.set(RendererFrameState.unavailable());
        latestLightmapPayload.set(LightmapPayload.unknown());
    }

    private DynamicRenderScene drainDynamicScene(RendererFrameState frameState) {
        LightmapPayload lightmapPayload = latestLightmapPayload.get();
        DynamicRenderScene directScene = pendingDynamicScene.getAndSet(DynamicRenderScene.empty());
        if (directScene.hasSceneUpdate()) {
            dynamicSceneCollector.discardPending();
            DynamicRenderScene merged = dynamicScenePublicationState.publishSnapshot(directScene, lightmapPayload);
            dynamicSceneCollector.rememberRetainedRenderContent(merged.hasRenderContent());
            return merged;
        }
        DynamicRenderScene collectedScene = dynamicSceneCollector.drain(frameState);
        if (collectedScene.hasSceneUpdate()) {
            DynamicRenderScene merged = dynamicScenePublicationState.publishSnapshot(collectedScene, lightmapPayload);
            dynamicSceneCollector.rememberRetainedRenderContent(merged.hasRenderContent());
            return merged;
        }
        return dynamicScenePublicationState.publishLightmapUpdate(lightmapPayload);
    }

    /**
     * Updates the latest immutable camera and target state.
     *
     * @param frameState frame state
     */
    public void updateFrameState(RendererFrameState frameState) {
        latestFrameState.set(Objects.requireNonNull(frameState, "frameState"));
    }

    void updatePendingBridgeChunkKeys(Set<ChunkKey> pendingChunkKeys) {
        pendingBridgeChunkKeys.set(Set.copyOf(Objects.requireNonNull(pendingChunkKeys, "pendingChunkKeys")));
    }

    void updateForegroundSectionKeys(Set<SectionKey> sectionKeys) {
        Set<SectionKey> source = Objects.requireNonNull(sectionKeys, "sectionKeys");
        Set<SectionKey> immutableKeys = source instanceof PackedSectionMembership ? source : Set.copyOf(source);
        foregroundSectionKeys.set(immutableKeys);
        sectionBuildPipeline.prioritize(immutableKeys);
    }

    /**
     * Updates the latest immutable lighting lookup payload.
     *
     * @param lightmapPayload lightmap payload
     */
    public void updateLightmapPayload(LightmapPayload lightmapPayload) {
        latestLightmapPayload.set(Objects.requireNonNull(lightmapPayload, "lightmapPayload"));
    }

    /**
     * Returns a human-readable update-loop summary.
     *
     * @return stable diagnostic summary
     */
    public String summary() {
        return "drainedFrames=" + drainedFrames.get()
                + ", changedBatches=" + changedBatches.get()
                + ", fullResyncBatches=" + fullResyncBatches.get()
                + ", budgetLimitedBatches=" + budgetLimitedBatches.get()
                + ", submittedSectionBuilds=" + submittedSectionBuilds.get()
                + ", completedSectionBuilds=" + completedSectionBuilds.get()
                + ", staleCompletedSectionBuilds=" + staleCompletedSectionBuilds.get()
                + ", coalescedSectionBuildInputs=" + coalescedSectionBuildInputs.get()
                + ", committedSectionBuildInputs=" + committedSectionBuildInputs.size()
                + ", submittedDynamicScenes=" + submittedDynamicScenes.get()
                + ", submittedDynamicFacts=" + submittedDynamicFacts.get()
                + ", drainedDynamicScenes=" + drainedDynamicScenes.get()
                + ", drainedDynamicElements=" + drainedDynamicElements.get()
                + ", pendingDynamicElements=" + (pendingDynamicScene.get().totalElements()
                + dynamicSceneCollector.pendingElements())
                + ", " + dynamicSceneCollector.lastDrainSummary().asLogFragment()
                + ", " + dynamicSceneCollector.modelLaneSummary().asLogFragment()
                + ", " + latestLightmapPayload.get().asLogFragment()
                + ", pendingSceneSections=" + pendingSceneWork.pendingSectionSnapshots()
                + ", pendingSceneRemovals=" + pendingSceneWork.pendingRemovedSections()
                + ", " + latestFrameState.get().asLogFragment()
                + ", adaptiveSectionSnapshotsPerFrame=" + adaptiveSectionSnapshotsPerFrame
                + ", adaptiveCompletedSectionBuildsPerFrame=" + adaptiveCompletedSectionBuildsPerFrame
                + ", maxSectionSnapshotsPerFrame=" + maxSectionSnapshotsPerFrame
                + ", maxCompletedSectionBuildsPerFrame=" + maxCompletedSectionBuildsPerFrame
                + ", maxCompletedSectionBuildMeshBytesPerFrame=" + maxCompletedSectionBuildMeshBytesPerFrame
                + ", maxCompletedSectionBuildTrianglesPerFrame=" + maxCompletedSectionBuildTrianglesPerFrame
                + ", maxCompletedSectionBuildDrainMicrosPerFrame=" + maxCompletedSectionBuildDrainNanosPerFrame / 1_000L
                + ", maxCpuBuildsInFlight=" + budget.maxCpuBuildsInFlight()
                + ", maxCompletedBuildsWaiting=" + budget.maxCompletedBuildsWaiting()
                + ", " + sectionBuildPipeline.summary("sectionBuildPipeline")
                + ", " + sectionMaterialCache.summary().asLogFragment()
                + ", " + sectionGeometryCache.summary().asLogFragment()
                + ", " + sectionMeshCache.summary().asLogFragment();
    }

    /**
     * Returns an atomic backlog projection.
     *
     * @return immutable backlog snapshot
     */
    public BacklogSnapshot backlogSnapshot() {
        SectionMeshCache.Summary meshSummary = sectionMeshCache.summary();
        return new BacklogSnapshot(
                pendingSceneWork.pendingSectionSnapshots(),
                pendingSceneWork.pendingRemovedSections(),
                sectionBuildPipeline.pendingSectionBuilds(),
                sectionBuildPipeline.completedSectionBuildsWaiting(),
                adaptiveSectionSnapshotsPerFrame,
                maxSectionSnapshotsPerFrame,
                maxCompletedSectionBuildsPerFrame,
                budget.maxCpuBuildsInFlight(),
                budget.maxCompletedBuildsWaiting(),
                meshSummary.cachedRenderableSections(),
                meshSummary.knownRenderableSections(),
                meshSummary.totalEvictedSections()
        );
    }

    Set<SectionKey> snapshotPendingTerrainSectionKeys() {
        return snapshotPendingTerrainMetadata().sectionKeys();
    }

    /**
     * Returns the target content generation for every section whose CPU build
     * or queued snapshot can still replace a previously present RT front.
     */
    Map<SectionKey, Long> snapshotPendingTerrainSectionRevisions() {
        return snapshotPendingTerrainMetadata().revisions();
    }

    Map<SectionKey, Integer> snapshotPendingTerrainSectionSourceFlags() {
        return snapshotPendingTerrainMetadata().sourceFlags();
    }

    PendingTerrainMetadata snapshotPendingTerrainMetadata() {
        SceneDatabase.PendingSectionMetadata sceneMetadata = sceneDatabase.snapshotPendingSectionMetadata();
        long queueMetadataRevision = pendingSceneWork.metadataRevision();
        if (sceneMetadata.revision() == cachedPendingSceneMetadataRevision
                && queueMetadataRevision == cachedPendingQueueMetadataRevision
                && sectionBuildTicketRevision == cachedSectionBuildTicketRevision
                && sectionContentRevision == cachedSectionContentRevision) {
            return cachedPendingTerrainMetadata;
        }

        Map<SectionKey, Integer> pending = new HashMap<>();
        sceneMetadata.sourceFlags().forEach(
                (key, flags) -> pending.merge(key, flags, (left, right) -> left | right)
        );
        pendingSceneWork.snapshotPendingSectionSourceFlags().forEach(
                (key, flags) -> pending.merge(key, flags, (left, right) -> left | right)
        );
        sectionBuildGenerations.forEach(
                (key, ticket) -> pending.merge(key, ticket.sourceFlags(), (left, right) -> left | right)
        );
        Map<SectionKey, Long> revisions = new HashMap<>(pending.size());
        for (SectionKey key : pending.keySet()) {
            revisions.put(key, nextPendingRevision(key));
        }
        cachedPendingTerrainMetadata = PendingTerrainMetadata.freezeOwned(revisions, pending);
        cachedPendingSceneMetadataRevision = sceneMetadata.revision();
        cachedPendingQueueMetadataRevision = queueMetadataRevision;
        cachedSectionBuildTicketRevision = sectionBuildTicketRevision;
        cachedSectionContentRevision = sectionContentRevision;
        return cachedPendingTerrainMetadata;
    }

    void maybeDumpFirstFrontCpuFlightRecorder(Set<SectionKey> foregroundKeys) {
        Objects.requireNonNull(foregroundKeys, "foregroundKeys");
        if (!foregroundKeys.isEmpty()
                && sectionMeshCache.snapshotKnownRenderableSections().sectionKeys().containsAll(foregroundKeys)) {
            sectionBuildPipeline.dumpFirstFrontFlightRecorder(foregroundKeys);
            RtForegroundAdmissionFlightRecorder.dumpOnce(foregroundKeys);
        }
    }

    PendingTerrainProvenance pendingTerrainProvenance(Map<SectionKey, Long> completedRevisions) {
        Objects.requireNonNull(completedRevisions, "completedRevisions");
        Map<SectionKey, Integer> sceneFlags = sceneDatabase.snapshotPendingSectionSourceFlags();
        Map<SectionKey, Long> pendingRevisions = snapshotPendingTerrainSectionRevisions();
        int sceneOverlap = 0;
        int queuedOverlap = 0;
        int buildOverlap = 0;
        int sceneSources = 0;
        int queuedSources = 0;
        int buildSources = 0;
        int sceneNeighbor = 0;
        int queuedNeighbor = 0;
        int buildNeighbor = 0;
        int newer = 0;
        int equal = 0;
        int older = 0;
        for (Map.Entry<SectionKey, Long> completed : completedRevisions.entrySet()) {
            SectionKey key = completed.getKey();
            Integer sceneSource = sceneFlags.get(key);
            if (sceneSource != null) {
                sceneOverlap++;
                sceneSources |= sceneSource;
                if ((sceneSource & SceneUpdateBatch.SOURCE_NEIGHBOR_DEPENDENCY) != 0
                        && (sceneSource & SceneUpdateBatch.SOURCE_DIRECT_CONTENT) == 0) sceneNeighbor++;
            }
            int queuedSource = pendingSceneWork.sourceFlagsForSection(key);
            if (queuedSource != 0) {
                queuedOverlap++;
                queuedSources |= queuedSource;
                if ((queuedSource & SceneUpdateBatch.SOURCE_NEIGHBOR_DEPENDENCY) != 0
                        && (queuedSource & SceneUpdateBatch.SOURCE_DIRECT_CONTENT) == 0) queuedNeighbor++;
            }
            SectionBuildTicket ticket = sectionBuildGenerations.get(key);
            if (ticket != null) {
                buildOverlap++;
                buildSources |= ticket.sourceFlags();
                if ((ticket.sourceFlags() & SceneUpdateBatch.SOURCE_NEIGHBOR_DEPENDENCY) != 0
                        && (ticket.sourceFlags() & SceneUpdateBatch.SOURCE_DIRECT_CONTENT) == 0) buildNeighbor++;
            }
            Long pendingRevision = pendingRevisions.get(key);
            if (pendingRevision == null) {
                continue;
            }
            int relation = Long.compare(pendingRevision, completed.getValue());
            if (relation > 0) newer++;
            else if (relation == 0) equal++;
            else older++;
        }
        return new PendingTerrainProvenance(
                completedRevisions.size(), sceneOverlap, sceneNeighbor, sceneSources,
                queuedOverlap, queuedNeighbor, queuedSources,
                buildOverlap, buildNeighbor, buildSources,
                newer, equal, older
        );
    }

    private Map<SectionKey, Long> sectionContentRevisionsFor(Set<SectionKey> keys) {
        Map<SectionKey, Long> revisions = new LinkedHashMap<>();
        for (SectionKey key : keys) {
            Long revision = sectionContentRevisions.get(key);
            if (revision == null) {
                throw new IllegalStateException("missing committed section content revision for " + key);
            }
            revisions.put(key, revision);
        }
        return Map.copyOf(revisions);
    }

    private long nextPendingRevision(SectionKey key) {
        SectionBuildTicket ticket = sectionBuildGenerations.get(key);
        if (ticket != null) {
            return ticket.contentRevision();
        }
        return Math.addExact(sectionContentRevisions.getOrDefault(key, 0L), 1L);
    }

    private void putSectionBuildTicket(SectionKey key, SectionBuildTicket ticket) {
        SectionBuildTicket previous = sectionBuildGenerations.put(key, ticket);
        if (!Objects.equals(previous, ticket)) {
            sectionBuildTicketRevision = nextMetadataRevision(sectionBuildTicketRevision, "build ticket");
        }
    }

    private void removeSectionBuildTicket(SectionKey key) {
        if (sectionBuildGenerations.remove(key) != null) {
            sectionBuildTicketRevision = nextMetadataRevision(sectionBuildTicketRevision, "build ticket");
        }
    }

    private void clearSectionBuildTickets() {
        if (!sectionBuildGenerations.isEmpty()) {
            sectionBuildGenerations.clear();
            sectionBuildTicketRevision = nextMetadataRevision(sectionBuildTicketRevision, "build ticket");
        }
    }

    private void putSectionContentRevision(SectionKey key, long revision) {
        Long previous = sectionContentRevisions.put(key, revision);
        if (previous == null || previous.longValue() != revision) {
            sectionContentRevision = nextMetadataRevision(sectionContentRevision, "section content");
        }
    }

    private void removeSectionContentRevision(SectionKey key) {
        if (sectionContentRevisions.remove(key) != null) {
            sectionContentRevision = nextMetadataRevision(sectionContentRevision, "section content");
        }
    }

    private void clearSectionContentRevisions() {
        if (!sectionContentRevisions.isEmpty()) {
            sectionContentRevisions.clear();
            sectionContentRevision = nextMetadataRevision(sectionContentRevision, "section content");
        }
    }

    OwnershipSnapshot ownershipSnapshot() {
        return new OwnershipSnapshot(
                snapshotPendingTerrainSectionKeys(),
                sectionMeshCache.snapshotCachedSectionKeys(),
                sectionMeshCache.snapshotKnownRenderableSections().sectionKeys(),
                foregroundSectionKeys.get(),
                pendingBridgeChunkKeys.get()
        );
    }

    @Override
    public void close() {
        pendingSceneWork.clear();
        clearSectionBuildTickets();
        committedSectionBuildInputs.clear();
        sectionBuildPipeline.close();
    }

    private void invalidateStaleBuilds(SceneUpdateBatch submissionBatch) {
        if (submissionBatch.fullResyncRequested()) {
            sectionBuildPipeline.cancelAll();
            clearSectionBuildTickets();
            committedSectionBuildInputs.clear();
        }
        for (SectionKey key : submissionBatch.removedSections()) {
            sectionBuildPipeline.cancel(key);
            removeSectionBuildTicket(key);
            committedSectionBuildInputs.remove(key);
        }
    }

    private CompletedBuildBatch drainCompletedBuilds(int maxResults, boolean bootstrapPrewarm) {
        long deadlineNanos = System.nanoTime() + maxCompletedSectionBuildDrainNanosPerFrame;
        List<AsyncSectionBuildPipeline.CompletedSectionBuild> completed = sectionBuildPipeline.drainCompleted(
                maxResults,
                maxCompletedSectionBuildMeshBytesPerFrame,
                maxCompletedSectionBuildTrianglesPerFrame,
                deadlineNanos
        );
        Map<SectionKey, SectionVoxelSnapshot> snapshots = new LinkedHashMap<>();
        Map<SectionKey, Integer> faceCounts = new LinkedHashMap<>();
        Map<SectionKey, SectionTriangleMesh> meshes = new LinkedHashMap<>();
        Map<SectionKey, Long> contentRevisions = new LinkedHashMap<>();
        Map<SectionKey, Integer> sourceFlags = new LinkedHashMap<>();
        SectionMaterialCache.MaterialFacts materialFacts = SectionMaterialCache.MaterialFacts.empty();
        int staleResults = 0;

        for (AsyncSectionBuildPipeline.CompletedSectionBuild result : completed) {
            SectionBuildTicket ticket = sectionBuildGenerations.get(result.key());
            if (ticket == null || ticket.generation() != result.generation()) {
                SectionLifecycleFlightRecorder.record(
                        SectionLifecycleFlightRecorder.STAGE_CPU_COMMIT_STALE,
                        SectionLifecycleFlightRecorder.SOURCE_UNKNOWN,
                        SectionLifecycleFlightRecorder.OUTCOME_STALE,
                        result.snapshot(), result.generation(),
                        ticket == null ? -1L : ticket.contentRevision(),
                        ticket == null ? -1L : ticket.generation(),
                        ticket == null ? 0 : ticket.sourceFlags(),
                        completed.size(), result.mesh().triangleCount()
                );
                sectionBuildPipeline.recordDrained(
                        result.key(),
                        result.generation(),
                        ticket == null ? -1L : ticket.contentRevision(),
                        ticket == null ? 0 : ticket.sourceFlags(),
                        true
                );
                staleResults++;
                continue;
            }
            sectionBuildPipeline.recordDrained(
                    result.key(),
                    result.generation(),
                    ticket.contentRevision(),
                    ticket.sourceFlags(),
                    false
            );
            SectionLifecycleFlightRecorder.record(
                    SectionLifecycleFlightRecorder.STAGE_CPU_COMMIT,
                    SectionLifecycleFlightRecorder.SOURCE_UNKNOWN,
                    SectionLifecycleFlightRecorder.OUTCOME_ACCEPTED,
                    result.snapshot(), result.generation(), ticket.contentRevision(), -1L,
                    ticket.sourceFlags(), completed.size(), result.mesh().triangleCount()
            );
            RtFirstFrontCausalityRecorder.recordSection(
                    "cpuRenderCommit",
                    result.key(),
                    ticket.contentRevision(),
                    "generation=" + result.generation() + ", triangles=" + result.mesh().triangleCount()
            );
            RtSceneCausalityRecorder.recordSection(
                    RtSceneCausalityRecorder.CPU_MESH_COMMITTED,
                    result.key(),
                    ticket.contentRevision(),
                    result.generation(),
                    result.mesh().triangleCount(),
                    ticket.sourceFlags()
            );
            removeSectionBuildTicket(result.key());
            committedSectionBuildInputs.put(result.key(), ticket.inputRevision());
            snapshots.put(result.key(), result.snapshot());
            materialFacts = materialFacts.plus(result.materialFacts());
            faceCounts.put(result.key(), result.faceCount());
            meshes.put(result.key(), result.mesh());
            contentRevisions.put(result.key(), ticket.contentRevision());
            if (ticket.sourceFlags() != 0) {
                sourceFlags.put(result.key(), ticket.sourceFlags());
            }
        }

        Set<SectionKey> foregroundKeys = foregroundSectionKeys.get();
        if (!foregroundKeys.isEmpty()) {
            Set<SectionKey> completedForeground = new HashSet<>(
                    sectionMeshCache.snapshotKnownRenderableSections().sectionKeys()
            );
            completedForeground.addAll(meshes.keySet());
            if (completedForeground.containsAll(foregroundKeys)) {
                sectionBuildPipeline.dumpFirstFrontFlightRecorder(foregroundKeys);
            }
        }

        if (!snapshots.isEmpty()) {
            completedSectionBuilds.addAndGet(snapshots.size());
        }
        if (staleResults > 0) {
            staleCompletedSectionBuilds.addAndGet(staleResults);
        }
        return new CompletedBuildBatch(
                snapshots,
                materialFacts,
                faceCounts,
                meshes,
                contentRevisions,
                sourceFlags,
                staleResults
        );
    }

    private SceneUpdateBatch buildFrameBatch(SceneUpdateBatch submissionBatch, CompletedBuildBatch completedBuildBatch) {
        Map<SectionKey, SectionVoxelSnapshot> frameSnapshots = new LinkedHashMap<>();
        Map<SectionKey, Integer> frameSourceFlags = new HashMap<>();
        for (SectionVoxelSnapshot snapshot : submissionBatch.sectionSnapshots().values()) {
            if (submissionBatch.hasMaterialOnlySourceForSection(snapshot.key())) {
                frameSnapshots.put(snapshot.key(), snapshot);
                frameSourceFlags.put(snapshot.key(), submissionBatch.sourceFlagsForSection(snapshot.key()));
            }
        }
        frameSnapshots.putAll(completedBuildBatch.snapshots());
        frameSourceFlags.putAll(completedBuildBatch.sourceFlags());
        Set<SectionKey> dirtySections = new HashSet<>(frameSnapshots.keySet());
        Set<ChunkKey> dirtyChunks = new HashSet<>();
        for (SectionKey key : dirtySections) {
            dirtyChunks.add(key.chunkKey());
        }
        for (SectionKey key : submissionBatch.removedSections()) {
            int flags = submissionBatch.sourceFlagsForSection(key);
            if (flags != 0) {
                frameSourceFlags.put(key, flags);
            }
        }
        return new SceneUpdateBatch(
                dirtySections,
                dirtyChunks,
                submissionBatch.removedSections(),
                submissionBatch.unloadedChunks(),
                frameSnapshots,
                submissionBatch.fullResyncRequested(),
                submissionBatch.totalSectionDirtyMarks(),
                submissionBatch.totalBlockMutationMarks(),
                submissionBatch.totalChunkPacketReplacementMarks(),
                submissionBatch.totalChunkSnapshotReplacements(),
                submissionBatch.totalChunkUnloadMarks(),
                submissionBatch.totalSectionSnapshotRemovals(),
                submissionBatch.totalFullResyncRequests(),
                submissionBatch.batchSourceFlags(),
                frameSourceFlags
        );
    }

    private int submitSectionBuilds(SceneUpdateBatch submissionBatch) {
        int submitted = 0;
        List<SectionVoxelSnapshot> deferredSnapshots = new ArrayList<>();
        for (SectionVoxelSnapshot snapshot : submissionBatch.sectionSnapshots().values()) {
            if (submissionBatch.hasMaterialOnlySourceForSection(snapshot.key())) {
                RtForegroundAdmissionFlightRecorder.recordSection(
                        snapshot.key(), "cpuTicketSkipped", "reason=materialOnly"
                );
                continue;
            }
            SceneDatabase.SectionBuildInput buildInput = sceneDatabase.snapshotSectionBuildInput(snapshot.key());
            if (buildInput == null) {
                continue;
            }
            SceneDatabase.SectionNeighborhoodRevision inputRevision = buildInput.revision();
            SectionBuildTicket activeTicket = sectionBuildGenerations.get(snapshot.key());
            if (activeTicket != null && activeTicket.inputRevision().equals(inputRevision)) {
                int mergedFlags = activeTicket.sourceFlags()
                        | submissionBatch.sourceFlagsForSection(snapshot.key());
                if (mergedFlags != activeTicket.sourceFlags()) {
                    putSectionBuildTicket(
                            snapshot.key(),
                            new SectionBuildTicket(
                                    activeTicket.generation(),
                                    activeTicket.contentRevision(),
                                    mergedFlags,
                                    activeTicket.inputRevision()
                            )
                    );
                }
                recordCoalescedSectionInput(buildInput.snapshot(), activeTicket, mergedFlags, 1L);
                continue;
            }
            SceneDatabase.SectionNeighborhoodRevision committedInput =
                    committedSectionBuildInputs.get(snapshot.key());
            if (activeTicket == null && inputRevision.equals(committedInput)) {
                recordCoalescedSectionInput(
                        buildInput.snapshot(),
                        -1L,
                        sectionContentRevisions.getOrDefault(snapshot.key(), 0L),
                        submissionBatch.sourceFlagsForSection(snapshot.key()),
                        2L
                );
                continue;
            }
            if (!sectionBuildPipeline.acceptsMoreWork(
                    budget.maxCpuBuildsInFlight(),
                    budget.maxCompletedBuildsWaiting()
            )) {
                RtSceneCausalityRecorder.recordSection(
                        RtSceneCausalityRecorder.CPU_DEFERRED,
                        snapshot.key(),
                        -1L,
                        -1L,
                        sectionBuildPipeline.pendingSectionBuilds(),
                        submissionBatch.sourceFlagsForSection(snapshot.key())
                );
                RtForegroundAdmissionFlightRecorder.recordSection(
                        snapshot.key(),
                        "cpuTicketDeferred",
                        "reason=pipelineCapacity, pendingBuilds=" + sectionBuildPipeline.pendingSectionBuilds()
                                + ", completedWaiting=" + sectionBuildPipeline.completedSectionBuildsWaiting()
                                + ", maxInFlight=" + budget.maxCpuBuildsInFlight()
                                + ", maxCompletedWaiting=" + budget.maxCompletedBuildsWaiting()
                );
                deferredSnapshots.add(snapshot);
                continue;
            }
            long generation = nextSectionBuildGeneration();
            long contentRevision = nextPendingRevision(snapshot.key());
            putSectionBuildTicket(
                    snapshot.key(),
                    new SectionBuildTicket(
                            generation,
                            contentRevision,
                            submissionBatch.sourceFlagsForSection(snapshot.key()),
                            inputRevision
                    )
            );
            sectionBuildPipeline.recordTicket(
                    snapshot.key(),
                    generation,
                    contentRevision,
                    submissionBatch.sourceFlagsForSection(snapshot.key()),
                    foregroundSectionKeys.get().contains(snapshot.key())
            );
            SectionLifecycleFlightRecorder.record(
                    SectionLifecycleFlightRecorder.STAGE_CPU_TICKET,
                    SectionLifecycleFlightRecorder.SOURCE_UNKNOWN,
                    SectionLifecycleFlightRecorder.OUTCOME_ACCEPTED,
                    snapshot, generation, contentRevision, -1L,
                    submissionBatch.sourceFlagsForSection(snapshot.key()),
                    sectionBuildPipeline.pendingSectionBuilds(), 0L
            );
            RtForegroundAdmissionFlightRecorder.recordSection(
                    snapshot.key(),
                    "cpuTicket",
                    "generation=" + generation + ", contentRevision=" + contentRevision
                            + ", sourceFlags=0x"
                            + Integer.toHexString(submissionBatch.sourceFlagsForSection(snapshot.key()))
            );
            RtFirstFrontCausalityRecorder.recordSection(
                    "cpuTicket",
                    snapshot.key(),
                    contentRevision,
                    "generation=" + generation + ", sourceFlags=0x"
                            + Integer.toHexString(submissionBatch.sourceFlagsForSection(snapshot.key()))
            );
            RtSceneCausalityRecorder.recordSection(
                    RtSceneCausalityRecorder.CPU_TICKET,
                    snapshot.key(),
                    contentRevision,
                    generation,
                    0L,
                    submissionBatch.sourceFlagsForSection(snapshot.key())
            );
            sectionBuildPipeline.submit(
                    buildInput.snapshot(),
                    generation,
                    buildInput.neighborhood(),
                    foregroundSectionKeys.get().contains(snapshot.key()),
                    contentRevision
            );
            submitted++;
        }
        pendingSceneWork.requeueFront(deferredSnapshots, submissionBatch);
        if (submitted > 0) {
            submittedSectionBuilds.addAndGet(submitted);
        }
        return submitted;
    }

    private void recordCoalescedSectionInput(
            SectionVoxelSnapshot snapshot,
            SectionBuildTicket ticket,
            int sourceFlags,
            long reason
    ) {
        recordCoalescedSectionInput(
                snapshot, ticket.generation(), ticket.contentRevision(), sourceFlags, reason
        );
    }

    private void recordCoalescedSectionInput(
            SectionVoxelSnapshot snapshot,
            long generation,
            long contentRevision,
            int sourceFlags,
            long reason
    ) {
        coalescedSectionBuildInputs.incrementAndGet();
        SectionLifecycleFlightRecorder.record(
                SectionLifecycleFlightRecorder.STAGE_CPU_INPUT_COALESCED,
                SectionLifecycleFlightRecorder.SOURCE_UNKNOWN,
                SectionLifecycleFlightRecorder.OUTCOME_ACCEPTED,
                snapshot,
                generation,
                contentRevision,
                -1L,
                sourceFlags,
                sectionBuildPipeline.pendingSectionBuilds(),
                reason
        );
    }

    private boolean hasDirectCpuBuildsInFlight(
            Set<SectionKey> foregroundKeys,
            boolean includeBackground
    ) {
        for (Map.Entry<SectionKey, SectionBuildTicket> entry : sectionBuildGenerations.entrySet()) {
            if ((includeBackground || foregroundKeys.contains(entry.getKey()))
                    && (entry.getValue().sourceFlags() & SceneUpdateBatch.SOURCE_DIRECT_CONTENT) != 0) {
                return true;
            }
        }
        return false;
    }

    private Set<SectionKey> directCpuBuildKeysInFlight() {
        if (sectionBuildGenerations.isEmpty()) {
            return Set.of();
        }
        HashSet<SectionKey> directKeys = new HashSet<>();
        for (Map.Entry<SectionKey, SectionBuildTicket> entry : sectionBuildGenerations.entrySet()) {
            if ((entry.getValue().sourceFlags() & SceneUpdateBatch.SOURCE_DIRECT_CONTENT) != 0) {
                directKeys.add(entry.getKey());
            }
        }
        return directKeys.isEmpty() ? Set.of() : Set.copyOf(directKeys);
    }

    private int completedBuildDrainBudgetThisFrame(boolean bootstrapPrewarm) {
        int completedWaiting = sectionBuildPipeline.completedSectionBuildsWaiting();
        int budget = bootstrapPrewarm ? maxCompletedSectionBuildsPerFrame : adaptiveCompletedSectionBuildsPerFrame;
        if (completedWaiting <= 0) {
            return budget;
        }
        return Math.min(completedWaiting, budget);
    }

    private int sectionSubmissionBudgetThisFrame(int completedDrainBudgetThisFrame, boolean bootstrapPrewarm) {
        int availableSlots = sectionBuildPipeline.availableSubmissionSlotsAfterDrainingCompleted(
                budget.maxCpuBuildsInFlight(),
                budget.maxCompletedBuildsWaiting(),
                completedDrainBudgetThisFrame
        );
        if (availableSlots <= 0) {
            return 0;
        }
        int sectionBudget = bootstrapPrewarm ? maxSectionSnapshotsPerFrame : adaptiveSectionSnapshotsPerFrame;
        return Math.min(sectionBudget, availableSlots);
    }

    private long nextSectionBuildGeneration() {
        if (nextSectionBuildGeneration == Long.MAX_VALUE) {
            sectionBuildPipeline.cancelAll();
            clearSectionBuildTickets();
            nextSectionBuildGeneration = 1L;
        }
        return nextSectionBuildGeneration++;
    }

    private void adjustSectionBudget(long updateNanos, int builtSections) {
        if (builtSections <= 0 || maxSectionSnapshotsPerFrame <= MIN_SECTION_SNAPSHOTS_PER_FRAME) {
            return;
        }
        if (updateNanos > HIGH_UPDATE_NANOS) {
            adaptiveSectionSnapshotsPerFrame = Math.max(
                    MIN_SECTION_SNAPSHOTS_PER_FRAME,
                    Math.max(1, adaptiveSectionSnapshotsPerFrame / 2)
            );
            return;
        }
        if (updateNanos < TARGET_UPDATE_NANOS && adaptiveSectionSnapshotsPerFrame < maxSectionSnapshotsPerFrame) {
            adaptiveSectionSnapshotsPerFrame = Math.min(
                    maxSectionSnapshotsPerFrame,
                    adaptiveSectionSnapshotsPerFrame + MIN_SECTION_SNAPSHOTS_PER_FRAME
            );
        }
    }

    record OwnershipSnapshot(
            Set<SectionKey> pendingSectionKeys,
            Set<SectionKey> cachedMeshSectionKeys,
            Set<SectionKey> knownRenderableSectionKeys,
            Set<SectionKey> foregroundSectionKeys,
            Set<ChunkKey> pendingBridgeChunkKeys
    ) {
        OwnershipSnapshot {
            pendingSectionKeys = Set.copyOf(Objects.requireNonNull(pendingSectionKeys, "pendingSectionKeys"));
            cachedMeshSectionKeys = Set.copyOf(Objects.requireNonNull(cachedMeshSectionKeys, "cachedMeshSectionKeys"));
            knownRenderableSectionKeys = Set.copyOf(Objects.requireNonNull(
                    knownRenderableSectionKeys,
                    "knownRenderableSectionKeys"
            ));
            foregroundSectionKeys = Set.copyOf(Objects.requireNonNull(foregroundSectionKeys, "foregroundSectionKeys"));
            pendingBridgeChunkKeys = Set.copyOf(Objects.requireNonNull(pendingBridgeChunkKeys, "pendingBridgeChunkKeys"));
        }
    }

    private record CompletedBuildBatch(
            Map<SectionKey, SectionVoxelSnapshot> snapshots,
            SectionMaterialCache.MaterialFacts materialFacts,
            Map<SectionKey, Integer> faceCounts,
            Map<SectionKey, SectionTriangleMesh> meshes,
            Map<SectionKey, Long> contentRevisions,
            Map<SectionKey, Integer> sourceFlags,
            int staleResults
    ) {
        private CompletedBuildBatch {
            snapshots = Map.copyOf(Objects.requireNonNull(snapshots, "snapshots"));
            materialFacts = Objects.requireNonNull(materialFacts, "materialFacts");
            faceCounts = Map.copyOf(Objects.requireNonNull(faceCounts, "faceCounts"));
            meshes = Map.copyOf(Objects.requireNonNull(meshes, "meshes"));
            contentRevisions = Map.copyOf(Objects.requireNonNull(contentRevisions, "contentRevisions"));
            sourceFlags = Map.copyOf(Objects.requireNonNull(sourceFlags, "sourceFlags"));
            if (!snapshots.keySet().equals(faceCounts.keySet())
                    || !snapshots.keySet().equals(meshes.keySet())
                    || !snapshots.keySet().equals(contentRevisions.keySet())) {
                throw new IllegalArgumentException("completed build batch maps must share keys");
            }
            if (!snapshots.keySet().containsAll(sourceFlags.keySet())) {
                throw new IllegalArgumentException("completed build source flags require matching sections");
            }
            if (staleResults < 0) {
                throw new IllegalArgumentException("staleResults must be non-negative");
            }
        }

        int size() {
            return snapshots.size();
        }
    }

    record PendingTerrainProvenance(
            int completedSections,
            int sceneDatabaseOverlap,
            int sceneDatabaseNeighborOverlap,
            int sceneDatabaseSourceFlags,
            int pendingSceneWorkOverlap,
            int pendingSceneWorkNeighborOverlap,
            int pendingSceneWorkSourceFlags,
            int cpuBuildOverlap,
            int cpuBuildNeighborOverlap,
            int cpuBuildSourceFlags,
            int newerRevisions,
            int equalRevisions,
            int olderRevisions
    ) {
        String asLogFragment() {
            return "completed=" + completedSections
                    + ",sceneDatabase=" + sceneDatabaseOverlap
                    + "(neighbor:" + sceneDatabaseNeighborOverlap + ",direct:" + (sceneDatabaseOverlap - sceneDatabaseNeighborOverlap) + "):" + sourceFlagsSummary(sceneDatabaseSourceFlags)
                    + ",pendingSceneWork=" + pendingSceneWorkOverlap
                    + "(neighbor:" + pendingSceneWorkNeighborOverlap + ",direct:" + (pendingSceneWorkOverlap - pendingSceneWorkNeighborOverlap) + "):" + sourceFlagsSummary(pendingSceneWorkSourceFlags)
                    + ",cpuBuild=" + cpuBuildOverlap
                    + "(neighbor:" + cpuBuildNeighborOverlap + ",direct:" + (cpuBuildOverlap - cpuBuildNeighborOverlap) + "):" + sourceFlagsSummary(cpuBuildSourceFlags)
                    + ",revisionRelation=newer:" + newerRevisions
                    + ",equal:" + equalRevisions + ",older:" + olderRevisions;
        }

        long signature() {
            long signature = completedSections;
            signature = signature * 31L + sceneDatabaseOverlap;
            signature = signature * 31L + sceneDatabaseNeighborOverlap;
            signature = signature * 31L + sceneDatabaseSourceFlags;
            signature = signature * 31L + pendingSceneWorkOverlap;
            signature = signature * 31L + pendingSceneWorkNeighborOverlap;
            signature = signature * 31L + pendingSceneWorkSourceFlags;
            signature = signature * 31L + cpuBuildOverlap;
            signature = signature * 31L + cpuBuildNeighborOverlap;
            signature = signature * 31L + cpuBuildSourceFlags;
            signature = signature * 31L + newerRevisions;
            signature = signature * 31L + equalRevisions;
            return signature * 31L + olderRevisions;
        }
    }

    private record SectionBuildTicket(
            long generation,
            long contentRevision,
            int sourceFlags,
            SceneDatabase.SectionNeighborhoodRevision inputRevision
    ) {
        private SectionBuildTicket {
            if (generation <= 0L) {
                throw new IllegalArgumentException("section build generation must be positive");
            }
            if (contentRevision <= 0L) {
                throw new IllegalArgumentException("section content revision must be positive");
            }
            inputRevision = Objects.requireNonNull(inputRevision, "inputRevision");
        }
    }

    /**
     * Immutable renderer CPU-work backlog and capacity snapshot.
     *
     * @param pendingSceneSections              pending source sections
     * @param pendingSceneRemovals              pending section removals
     * @param pendingCpuBuilds                  queued CPU mesh builds
     * @param completedCpuBuildsWaiting         completed builds awaiting drain
     * @param adaptiveSectionSnapshotsPerFrame  current adaptive source-drain limit
     * @param maxSectionSnapshotsPerFrame       configured source-drain limit
     * @param maxCompletedSectionBuildsPerFrame completed-build drain limit
     * @param maxCpuBuildsInFlight              CPU build concurrency limit
     * @param maxCompletedBuildsWaiting         completed-build queue limit
     * @param cachedMeshSections                renderable meshes retained in CPU cache
     * @param knownRenderableMeshSections       authoritative renderable section count
     * @param totalEvictedMeshSections          cumulative CPU mesh evictions
     */
    public record BacklogSnapshot(
            int pendingSceneSections,
            int pendingSceneRemovals,
            int pendingCpuBuilds,
            int completedCpuBuildsWaiting,
            int adaptiveSectionSnapshotsPerFrame,
            int maxSectionSnapshotsPerFrame,
            int maxCompletedSectionBuildsPerFrame,
            int maxCpuBuildsInFlight,
            int maxCompletedBuildsWaiting,
            int cachedMeshSections,
            int knownRenderableMeshSections,
            long totalEvictedMeshSections
    ) {
        /**
         * Creates a compatibility snapshot without mesh-cache coverage counters.
         *
         * @param pendingSceneSections              pending source sections
         * @param pendingSceneRemovals              pending removals
         * @param pendingCpuBuilds                  queued CPU builds
         * @param completedCpuBuildsWaiting         completed builds awaiting drain
         * @param adaptiveSectionSnapshotsPerFrame  adaptive source-drain limit
         * @param maxSectionSnapshotsPerFrame       configured source-drain limit
         * @param maxCompletedSectionBuildsPerFrame completed-build drain limit
         * @param maxCpuBuildsInFlight              CPU concurrency limit
         * @param maxCompletedBuildsWaiting         completed-build queue limit
         */
        public BacklogSnapshot(
                int pendingSceneSections,
                int pendingSceneRemovals,
                int pendingCpuBuilds,
                int completedCpuBuildsWaiting,
                int adaptiveSectionSnapshotsPerFrame,
                int maxSectionSnapshotsPerFrame,
                int maxCompletedSectionBuildsPerFrame,
                int maxCpuBuildsInFlight,
                int maxCompletedBuildsWaiting
        ) {
            this(
                    pendingSceneSections,
                    pendingSceneRemovals,
                    pendingCpuBuilds,
                    completedCpuBuildsWaiting,
                    adaptiveSectionSnapshotsPerFrame,
                    maxSectionSnapshotsPerFrame,
                    maxCompletedSectionBuildsPerFrame,
                    maxCpuBuildsInFlight,
                    maxCompletedBuildsWaiting,
                    0,
                    0,
                    0L
            );
        }

        /**
         * Validates all backlog counters and capacity relationships.
         */
        public BacklogSnapshot {
            requireNonNegative(pendingSceneSections, "pendingSceneSections");
            requireNonNegative(pendingSceneRemovals, "pendingSceneRemovals");
            requireNonNegative(pendingCpuBuilds, "pendingCpuBuilds");
            requireNonNegative(completedCpuBuildsWaiting, "completedCpuBuildsWaiting");
            requirePositive(adaptiveSectionSnapshotsPerFrame, "adaptiveSectionSnapshotsPerFrame");
            requirePositive(maxSectionSnapshotsPerFrame, "maxSectionSnapshotsPerFrame");
            requirePositive(maxCompletedSectionBuildsPerFrame, "maxCompletedSectionBuildsPerFrame");
            requirePositive(maxCpuBuildsInFlight, "maxCpuBuildsInFlight");
            requirePositive(maxCompletedBuildsWaiting, "maxCompletedBuildsWaiting");
            requireNonNegative(cachedMeshSections, "cachedMeshSections");
            requireNonNegative(knownRenderableMeshSections, "knownRenderableMeshSections");
            if (cachedMeshSections > knownRenderableMeshSections) {
                throw new IllegalArgumentException("cachedMeshSections must not exceed knownRenderableMeshSections");
            }
            if (totalEvictedMeshSections < 0L) {
                throw new IllegalArgumentException("totalEvictedMeshSections must not be negative");
            }
        }

        /**
         * Returns an empty snapshot with valid minimum limits.
         *
         * @return empty backlog snapshot
         */
        public static BacklogSnapshot empty() {
            return new BacklogSnapshot(0, 0, 0, 0, 1, 1, 1, 1, 1, 0, 0, 0L);
        }

        private static void requireNonNegative(int value, String name) {
            if (value < 0) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
        }

        private static void requirePositive(int value, String name) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        }

        /**
         * Tests whether CPU build queues reached capacity.
         *
         * @return whether CPU work is backpressured
         */
        public boolean isCpuBackpressured() {
            return pendingCpuBuilds >= maxCpuBuildsInFlight
                    || completedCpuBuildsWaiting >= maxCompletedBuildsWaiting;
        }

        /**
         * Tests whether source backlog materially exceeds the frame budget.
         *
         * @return whether backlog is large
         */
        public boolean hasLargeSceneBacklog() {
            long sectionBudget = Math.max(maxSectionSnapshotsPerFrame, adaptiveSectionSnapshotsPerFrame);
            return pendingSceneSections >= sectionBudget * 4L;
        }

        /**
         * Tests whether any renderer work is pending.
         *
         * @return whether work remains
         */
        public boolean hasPendingRendererWork() {
            return pendingSceneSections > 0
                    || pendingSceneRemovals > 0
                    || pendingCpuBuilds > 0
                    || completedCpuBuildsWaiting > 0;
        }

        /**
         * Tests whether pending work can block presentation coverage.
         *
         * @return whether blocking work remains
         */
        public boolean hasPresentationBlockingRendererWork() {
            return pendingSceneSections > 0
                    || pendingSceneRemovals > 0
                    || pendingCpuBuilds > 0;
        }

        /**
         * Tests RT coverage against authoritative renderable sections.
         *
         * @param builtRtSections built RT sections
         * @return whether coverage is incomplete
         */
        public boolean hasRtCoverageGap(int builtRtSections) {
            if (builtRtSections < 0) {
                throw new IllegalArgumentException("builtRtSections must not be negative");
            }
            return rtCoverageReferenceSections() > builtRtSections;
        }

        /**
         * Tests whether built and pending sections still leave an RT coverage gap.
         *
         * @param builtRtSections        built RT sections
         * @param pendingRtSectionBuilds pending RT builds
         * @return whether an unaccounted gap remains
         */
        public boolean hasUnaccountedRtCoverageGap(int builtRtSections, int pendingRtSectionBuilds) {
            if (builtRtSections < 0) {
                throw new IllegalArgumentException("builtRtSections must not be negative");
            }
            if (pendingRtSectionBuilds < 0) {
                throw new IllegalArgumentException("pendingRtSectionBuilds must not be negative");
            }
            long accountedRtSections = (long) builtRtSections + pendingRtSectionBuilds;
            return rtCoverageReferenceSections() > accountedRtSections;
        }

        /**
         * Returns the authoritative RT coverage reference count.
         *
         * @return known renderable section count
         */
        public int rtCoverageReferenceSections() {
            // The RT scene must match the renderable world contract, not only the
            // still-cached CPU staging subset. Otherwise mesh eviction can hide TLAS holes.
            return knownRenderableMeshSections;
        }

        /**
         * Formats backlog state for diagnostics.
         *
         * @return stable single-line log fragment
         */
        public String asLogFragment() {
            return "rendererBacklog{pendingSceneSections=" + pendingSceneSections
                    + ", pendingSceneRemovals=" + pendingSceneRemovals
                    + ", pendingCpuBuilds=" + pendingCpuBuilds
                    + ", completedCpuBuildsWaiting=" + completedCpuBuildsWaiting
                    + ", presentationBlocking=" + hasPresentationBlockingRendererWork()
                    + ", cachedRenderableMeshSections=" + cachedMeshSections
                    + ", knownRenderableMeshSections=" + knownRenderableMeshSections
                    + ", rtCoverageReferenceSections=" + rtCoverageReferenceSections()
                    + ", totalEvictedMeshSections=" + totalEvictedMeshSections
                    + "}";
        }
    }

}
