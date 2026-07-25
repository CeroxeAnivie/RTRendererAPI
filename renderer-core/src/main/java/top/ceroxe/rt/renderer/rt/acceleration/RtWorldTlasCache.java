package top.ceroxe.rt.renderer.rt.acceleration;

import org.lwjgl.vulkan.VkDevice;
import top.ceroxe.rt.renderer.*;
import top.ceroxe.rt.renderer.rt.RtSceneReadiness;
import top.ceroxe.rt.renderer.rt.device.RtCommandContext;
import top.ceroxe.rt.renderer.rt.material.RtMaterialState;
import top.ceroxe.rt.renderer.rt.material.RtSceneMaterialTable;
import top.ceroxe.rt.renderer.scene.PackedSectionMembership;

import java.util.*;

/**
 * World-level TLAS cache built from the renderer-owned section BLAS set.
 *
 * <p>The section BLAS cache can change many times while host streams chunks.
 * Rebuilding the TLAS after every small BLAS batch would put a second synchronous
 * Vulkan build on the render path exactly when chunk loading is already hot. This
 * cache therefore separates two contracts: background dispatch may bind a
 * streaming TLAS once enough section BLASes exist, while presentation remains
 * guarded by {@link RtSceneReadiness}. That keeps RTX hardware busy during chunk
 * loading without letting partial-TLAS frames hide sourceEngine terrain.</p>
 */
public final class RtWorldTlasCache implements AutoCloseable {
    private static final long UNBUILT_REVISION = -1L;
    private static final long DEFAULT_MIN_REBUILD_INTERVAL_MILLIS = 100L;
    private static final long DEFAULT_MIN_STREAMING_REBUILD_INTERVAL_MILLIS = 16L;
    private static final int DEFAULT_MIN_INITIAL_INSTANCES = 1;
    private static final long DEFAULT_MIN_STREAMING_REVISION_DELTA = 256L;
    private static final int DEFAULT_MIN_STREAMING_INSTANCE_DELTA = 128;
    private static final int MIN_EMPTY_TRANSITION_STABLE_FRAMES = 120;
    /* Two slots preserve failure-atomic descriptor replacement without unbounded AS retention. */
    private static final int MAX_REUSABLE_WORLD_TLAS_SLOTS = 2;
    private static final boolean DEFAULT_ALLOW_BACKLOGGED_STREAMING_REBUILDS = true;
    private static final String MIN_REBUILD_INTERVAL_MILLIS_PROPERTY = "top.ceroxe.rt.rt.worldTlas.minRebuildIntervalMillis";
    private static final String MIN_STREAMING_REBUILD_INTERVAL_MILLIS_PROPERTY =
            "top.ceroxe.rt.rt.worldTlas.minStreamingRebuildIntervalMillis";
    private static final String MIN_INITIAL_INSTANCES_PROPERTY = "top.ceroxe.rt.rt.worldTlas.minInitialInstances";
    private static final String MIN_STREAMING_REVISION_DELTA_PROPERTY =
            "top.ceroxe.rt.rt.worldTlas.minStreamingRevisionDelta";
    private static final String MIN_STREAMING_INSTANCE_DELTA_PROPERTY =
            "top.ceroxe.rt.rt.worldTlas.minStreamingInstanceDelta";
    private static final String ALLOW_BACKLOGGED_STREAMING_REBUILDS_PROPERTY =
            "top.ceroxe.rt.rt.worldTlas.allowBackloggedStreamingRebuilds";

    private final VkDevice device;
    private final long allocator;
    private final RtCommandContext commandContext;
    private final int scratchAlignmentBytes;
    private final long placeholderBlasDeviceAddress;
    private final RtAccelerationStructure.TlasInstance inactiveInstance;
    private final long minRebuildIntervalNanos;
    private final long minStreamingRebuildIntervalNanos;
    private final int minInitialInstances;
    private final long minStreamingRevisionDelta;
    private final int minStreamingInstanceDelta;
    private final boolean allowBackloggedStreamingRebuilds;
    private final RtWorldTlasDestinationPool destinationPool =
            new RtWorldTlasDestinationPool(MAX_REUSABLE_WORLD_TLAS_SLOTS);
    private final RtMaterialState.FactStream dynamicMaterialFacts = new RtMaterialState.FactStream();
    private final RtMaterialState.Composer materialComposer = new RtMaterialState.Composer();
    private final RtMaterialState.FactStream boundDynamicMaterialFacts = new RtMaterialState.FactStream();
    private final RtMaterialState.Composer boundDynamicMaterialComposer = new RtMaterialState.Composer();
    private final RendererRtDiagnostics diagnostics;
    private RtAccelerationStructure currentTlas;
    /*
     * Render-thread display policy must never wait for the scheduler transaction
     * merely to inspect the already committed scene.  Publish one immutable
     * readiness value after each state transaction, just as GPUScene exposes a
     * committed resource generation separately from pending RDG work.
     */
    private volatile RtSceneReadiness publishedReadiness = RtSceneReadiness.unavailable();
    /* Mirrors the completed TLAS, not the latest requested view. */
    private RendererViewState latestBuiltViewState = RendererViewState.allResident();
    private RtPendingWorldTlasBuild pendingWorldTlasBuild;
    private RtSceneMaterialTable.Snapshot builtMaterialSnapshot = RtMaterialState.emptySnapshot();
    private RtSceneMaterialTable.Snapshot boundTerrainPrefix = RtMaterialState.emptySnapshot();
    private long boundTerrainPrefixMaterialRevision = UNBUILT_REVISION;
    private int boundTerrainPrefixSectionCount;
    private int boundTerrainPrefixLayoutHash;
    private int dynamicMaterialOffset;
    private long builtRevision = UNBUILT_REVISION;
    private long boundRevision = UNBUILT_REVISION;
    private long builtSectionRevision = UNBUILT_REVISION;
    private long boundSectionRevision = UNBUILT_REVISION;
    private long builtSectionResourceRevision = UNBUILT_REVISION;
    private long boundSectionResourceRevision = UNBUILT_REVISION;
    private long builtDynamicRevision = UNBUILT_REVISION;
    private long boundDynamicRevision = UNBUILT_REVISION;
    private long builtDynamicTopologyRevision = UNBUILT_REVISION;
    private long boundDynamicTopologyRevision = UNBUILT_REVISION;
    private long builtDynamicGeometryRevision = UNBUILT_REVISION;
    private long boundDynamicGeometryRevision = UNBUILT_REVISION;
    private long builtDynamicMaterialRevision = UNBUILT_REVISION;
    private long boundDynamicMaterialRevision = UNBUILT_REVISION;
    private int builtInstanceTopologyHash;
    private int builtInstances;
    private int builtSectionInstances;
    /* Physical count owned by the terrain TLAS transaction only. */
    private int builtDynamicInstances;
    /* Logical count owned by the independently bound dynamic TLAS descriptor lane. */
    private int boundDynamicLaneInstances;
    private int builtTlasInstanceCapacity;
    private long latestObservedRevision = UNBUILT_REVISION;
    private int latestObservedInstances;
    private int latestObservedSectionInstances;
    private int latestObservedPendingSectionBuilds;
    private long latestObservedPendingTriangles;
    private long latestObservedCachedTriangles;
    private long latestObservedSectionRevision = UNBUILT_REVISION;
    private long latestObservedSectionResourceRevision = UNBUILT_REVISION;
    private long latestObservedSectionMaterialRevision = UNBUILT_REVISION;
    private long latestObservedDynamicRevision = UNBUILT_REVISION;
    private long latestObservedDynamicTopologyRevision = UNBUILT_REVISION;
    private long latestObservedDynamicGeometryRevision = UNBUILT_REVISION;
    private long latestObservedDynamicMaterialRevision = UNBUILT_REVISION;
    private long latestObservedDynamicSceneRevision;
    private int latestObservedDynamicInstances;
    private long latestObservedDynamicPrimitives;
    private long latestObservedDynamicFaces;
    private long latestObservedDynamicTriangles;
    private long rebuildPasses;
    private long streamingRebuildPasses;
    private long deferredPasses;
    private long coalescedPasses;
    private long emptyTransitions;
    private long asyncBuildSubmissions;
    private long asyncBuildCompletions;
    private long asyncBuildPollsNotReady;
    private long asyncBuildCloseWaits;
    private long asyncBuildReplacements;
    private long streamingBoundWorldTlasUpdates;
    private long discardedUnboundWorldTlasUpdates;
    private long descriptorBusyWorldTlasDeferrals;
    private long staleWorldTlasCompletionsBoundForDispatch;
    private long emptyInputTransitionDeferrals;
    private long urgentRebuildRequests;
    private long urgentRebuildSubmissions;
    private long totalInstancesBuilt;
    private long lastRebuildMillis;
    private long maxRebuildMillis;
    private long totalRebuildMillis;
    private long lastAsyncBuildLatencyMillis;
    private long maxAsyncBuildLatencyMillis;
    private long totalAsyncBuildLatencyMillis;
    private long nextCoalescedRebuildNanos;
    private long nextStreamingRebuildNanos;
    private boolean deferredByPendingBacklog;
    private int consecutiveEmptyInputs;
    private String lastUnboundWorldTlasDiscardReason = "none";
    /* Fixed-window smoke evidence; counters are only touched under this cache's monitor. */
    private long smokeWindowStartNanos;
    private long smokeFullBuildSubmissions;
    private long smokeUpdateSubmissions;
    private long smokeFullBuildNoCurrentTlas;
    private long smokeFullBuildTopologyChanges;
    private long smokeTopologyStableUpdates;
    private int smokeLastBuiltTopologyHash;
    private int smokeLastCandidateTopologyHash;
    private int smokeLastBuiltInstances;
    private int smokeLastCandidateInstances;
    private int smokeLastBuiltSectionInstances;
    private int smokeLastCandidateSectionInstances;
    private int smokeLastBuiltDynamicInstances;
    private int smokeLastCandidateDynamicInstances;
    private long smokeCompletions;
    private long smokeCompletedUpdates;
    private long smokeDestinationReplacements;
    private long smokeTransientInstanceBytes;
    private long smokeTransientScratchBytes;
    private long smokeLatestSourceHandle;
    private long smokeLatestDestinationHandle;
    private long smokeLatestDestinationStorageBytes;
    private long smokeEmptyInputDeferrals;
    private long smokePendingSubmissionDeferrals;
    private long smokeInitialFrontGateDeferrals;
    private long smokeInitialBuildDeferrals;
    private long smokeStreamingDeferrals;
    private long smokeCoalescedDeferrals;
    private long smokeSectionRevisionChanges;
    private long smokeDynamicInstanceRevisionChanges;
    private long smokeDynamicTopologyRevisionChanges;
    private long smokeDynamicGeometryRevisionChanges;
    private long smokeDynamicSceneRevisionChanges;
    private long smokeSubmitSectionSceneChanged;
    private long smokeSubmitSectionResourceChanged;
    private long smokeSubmitDynamicRevisionChanged;
    private long smokeSubmitDynamicTopologyChanged;
    private long smokeSubmitDynamicGeometryChanged;
    private long smokeSubmitViewChanged;
    private long smokeSubmitInstanceTopologyChanged;
    private long smokeSubmitForced;
    private boolean closed;

    /**
     * Creates the world TLAS owner with no-op diagnostics.
     *
     * @param device                       logical Vulkan device
     * @param allocator                    VMA allocator handle
     * @param commandContext               submission owner
     * @param scratchAlignmentBytes        scratch alignment in bytes
     * @param placeholderBlasDeviceAddress live placeholder BLAS address
     */
    public RtWorldTlasCache(
            VkDevice device,
            long allocator,
            RtCommandContext commandContext,
            int scratchAlignmentBytes,
            long placeholderBlasDeviceAddress
    ) {
        this(device, allocator, commandContext, scratchAlignmentBytes,
                placeholderBlasDeviceAddress, RendererRtDiagnostics.noop());
    }

    /**
     * Creates the monitor-confined owner for all world TLAS generations.
     *
     * @param device                       logical Vulkan device
     * @param allocator                    VMA allocator handle
     * @param commandContext               submission owner
     * @param scratchAlignmentBytes        scratch alignment in bytes
     * @param placeholderBlasDeviceAddress live placeholder BLAS address
     * @param diagnostics                  telemetry sink
     */
    public RtWorldTlasCache(
            VkDevice device,
            long allocator,
            RtCommandContext commandContext,
            int scratchAlignmentBytes,
            long placeholderBlasDeviceAddress,
            RendererRtDiagnostics diagnostics
    ) {
        this(
                device,
                allocator,
                commandContext,
                scratchAlignmentBytes,
                placeholderBlasDeviceAddress,
                positiveLongProperty(
                        MIN_REBUILD_INTERVAL_MILLIS_PROPERTY,
                        DEFAULT_MIN_REBUILD_INTERVAL_MILLIS
                ),
                positiveLongProperty(
                        MIN_STREAMING_REBUILD_INTERVAL_MILLIS_PROPERTY,
                        DEFAULT_MIN_STREAMING_REBUILD_INTERVAL_MILLIS
                ),
                positiveIntProperty(MIN_INITIAL_INSTANCES_PROPERTY, DEFAULT_MIN_INITIAL_INSTANCES),
                strictlyPositiveLongProperty(
                        MIN_STREAMING_REVISION_DELTA_PROPERTY,
                        DEFAULT_MIN_STREAMING_REVISION_DELTA
                ),
                positiveIntProperty(
                        MIN_STREAMING_INSTANCE_DELTA_PROPERTY,
                        DEFAULT_MIN_STREAMING_INSTANCE_DELTA
                ),
                booleanProperty(
                        ALLOW_BACKLOGGED_STREAMING_REBUILDS_PROPERTY,
                        DEFAULT_ALLOW_BACKLOGGED_STREAMING_REBUILDS
                ),
                diagnostics
        );
    }

    RtWorldTlasCache(
            VkDevice device,
            long allocator,
            RtCommandContext commandContext,
            int scratchAlignmentBytes,
            long placeholderBlasDeviceAddress,
            long minRebuildIntervalMillis,
            long minStreamingRebuildIntervalMillis,
            int minInitialInstances,
            long minStreamingRevisionDelta,
            int minStreamingInstanceDelta,
            boolean allowBackloggedStreamingRebuilds
    ) {
        this(device, allocator, commandContext, scratchAlignmentBytes, placeholderBlasDeviceAddress,
                minRebuildIntervalMillis, minStreamingRebuildIntervalMillis, minInitialInstances,
                minStreamingRevisionDelta, minStreamingInstanceDelta, allowBackloggedStreamingRebuilds,
                RendererRtDiagnostics.noop());
    }

    private RtWorldTlasCache(
            VkDevice device,
            long allocator,
            RtCommandContext commandContext,
            int scratchAlignmentBytes,
            long placeholderBlasDeviceAddress,
            long minRebuildIntervalMillis,
            long minStreamingRebuildIntervalMillis,
            int minInitialInstances,
            long minStreamingRevisionDelta,
            int minStreamingInstanceDelta,
            boolean allowBackloggedStreamingRebuilds,
            RendererRtDiagnostics diagnostics
    ) {
        this.device = Objects.requireNonNull(device, "device");
        if (allocator == 0L) {
            throw new IllegalArgumentException("allocator must not be null");
        }
        this.allocator = allocator;
        this.commandContext = Objects.requireNonNull(commandContext, "commandContext");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        if (scratchAlignmentBytes <= 0) {
            throw new IllegalArgumentException("scratchAlignmentBytes must be positive");
        }
        this.scratchAlignmentBytes = scratchAlignmentBytes;
        if (placeholderBlasDeviceAddress == 0L) {
            throw new IllegalArgumentException("placeholder BLAS device address must not be null");
        }
        this.placeholderBlasDeviceAddress = placeholderBlasDeviceAddress;
        this.inactiveInstance = RtAccelerationStructure.TlasInstance.inactive(placeholderBlasDeviceAddress);
        if (minRebuildIntervalMillis < 0L) {
            throw new IllegalArgumentException("minRebuildIntervalMillis must not be negative");
        }
        if (minStreamingRebuildIntervalMillis < 0L) {
            throw new IllegalArgumentException("minStreamingRebuildIntervalMillis must not be negative");
        }
        if (minInitialInstances <= 0) {
            throw new IllegalArgumentException("minInitialInstances must be positive");
        }
        if (minStreamingRevisionDelta <= 0L) {
            throw new IllegalArgumentException("minStreamingRevisionDelta must be positive");
        }
        if (minStreamingInstanceDelta <= 0) {
            throw new IllegalArgumentException("minStreamingInstanceDelta must be positive");
        }
        this.minRebuildIntervalNanos = minRebuildIntervalMillis * 1_000_000L;
        this.minStreamingRebuildIntervalNanos = minStreamingRebuildIntervalMillis * 1_000_000L;
        this.minInitialInstances = minInitialInstances;
        this.minStreamingRevisionDelta = minStreamingRevisionDelta;
        this.minStreamingInstanceDelta = minStreamingInstanceDelta;
        this.allowBackloggedStreamingRebuilds = allowBackloggedStreamingRebuilds;
    }

    /**
     * Reconciles an independently completed terrain TLAS with the descriptor-visible dynamic lane.
     *
     * <p>Terrain streaming may be deferred while entity animation publishes a newer dynamic TLAS.
     * The physical TLAS resources are intentionally independent, but their material table is one
     * descriptor resource. Reusing the terrain candidate's old dynamic suffix would pair current
     * dynamic custom indices with stale records. Preserve the candidate's exact terrain prefix and
     * the committed publication's exact dynamic suffix, then publish that combined transaction.</p>
     *
     * @param worldUpdate                      completed candidate owning the terrain prefix
     * @param publishedMaterialSnapshot        descriptor-visible combined table
     * @param publishedTerrainMaterialCount    terrain prefix length in the published table
     * @param publishedDynamicMaterialRevision exact dynamic material revision to retain
     * @return coherent rebased material table for the candidate
     */
    public static RtSceneMaterialTable.Snapshot rebaseWorldMaterialSnapshot(
            WorldTlasUpdate worldUpdate,
            RtSceneMaterialTable.Snapshot publishedMaterialSnapshot,
            int publishedTerrainMaterialCount,
            long publishedDynamicMaterialRevision
    ) {
        Objects.requireNonNull(worldUpdate, "worldUpdate");
        Objects.requireNonNull(publishedMaterialSnapshot, "publishedMaterialSnapshot");
        return rebaseSplitWorldMaterialSnapshot(
                worldUpdate.materialSnapshot(),
                worldUpdate.terrainMaterialCount(),
                worldUpdate.sectionMaterialRevision(),
                worldUpdate.dynamicMaterialRevision(),
                publishedMaterialSnapshot,
                publishedTerrainMaterialCount,
                publishedDynamicMaterialRevision
        );
    }

    /**
     * Rebases independently owned terrain prefix and dynamic suffix into one descriptor table.
     *
     * @param worldCandidateMaterialSnapshot        candidate combined table
     * @param worldCandidateTerrainMaterialCount    candidate prefix length
     * @param worldCandidateSectionMaterialRevision candidate terrain revision
     * @param worldCandidateDynamicMaterialRevision candidate dynamic revision
     * @param publishedMaterialSnapshot             descriptor-visible combined table
     * @param publishedTerrainMaterialCount         published prefix length
     * @param publishedDynamicMaterialRevision      published dynamic revision
     * @return coherent rebased table
     */
    public static RtSceneMaterialTable.Snapshot rebaseSplitWorldMaterialSnapshot(
            RtSceneMaterialTable.Snapshot worldCandidateMaterialSnapshot,
            int worldCandidateTerrainMaterialCount,
            long worldCandidateSectionMaterialRevision,
            long worldCandidateDynamicMaterialRevision,
            RtSceneMaterialTable.Snapshot publishedMaterialSnapshot,
            int publishedTerrainMaterialCount,
            long publishedDynamicMaterialRevision
    ) {
        Objects.requireNonNull(worldCandidateMaterialSnapshot, "worldCandidateMaterialSnapshot");
        Objects.requireNonNull(publishedMaterialSnapshot, "publishedMaterialSnapshot");
        if (worldCandidateTerrainMaterialCount <= 0
                || worldCandidateTerrainMaterialCount > worldCandidateMaterialSnapshot.sectionCount()) {
            throw new IllegalArgumentException("world candidate terrain prefix exceeds its material snapshot");
        }
        if (worldCandidateSectionMaterialRevision < 0L || worldCandidateDynamicMaterialRevision < 0L) {
            throw new IllegalArgumentException("world candidate material revisions must not be negative");
        }
        if (publishedDynamicMaterialRevision < UNBUILT_REVISION) {
            throw new IllegalArgumentException("published dynamic material revision must be -1 or greater");
        }
        if (publishedTerrainMaterialCount < 0
                || publishedTerrainMaterialCount > publishedMaterialSnapshot.sectionCount()) {
            throw new IllegalArgumentException("published terrain prefix exceeds its material snapshot");
        }
        if (publishedDynamicMaterialRevision == UNBUILT_REVISION
                || worldCandidateDynamicMaterialRevision == publishedDynamicMaterialRevision) {
            return worldCandidateMaterialSnapshot;
        }

        int publishedDynamicLayoutHash = dynamicSplitLayoutHash(
                publishedTerrainMaterialCount,
                publishedMaterialSnapshot.instanceLayoutHash()
        );
        int rebasedLayoutHash = combinedSplitLayoutHash(
                worldCandidateTerrainMaterialCount,
                publishedDynamicLayoutHash
        );
        RtSceneMaterialTable.Snapshot terrainPrefix = worldCandidateMaterialSnapshot.prefix(
                worldCandidateTerrainMaterialCount,
                worldCandidateSectionMaterialRevision,
                rebasedLayoutHash
        );
        RtSceneMaterialTable.Snapshot dynamicSuffix = publishedMaterialSnapshot.suffix(
                publishedTerrainMaterialCount,
                publishedDynamicMaterialRevision,
                rebasedLayoutHash
        );
        return RtSceneMaterialTable.Snapshot.compose(
                terrainPrefix,
                dynamicSuffix,
                combinedRevision(worldCandidateSectionMaterialRevision, publishedDynamicMaterialRevision),
                rebasedLayoutHash
        );
    }

    /**
     * Combines independently owned namespace layout tokens.
     *
     * @param terrainMaterialCount terrain prefix length
     * @param dynamicLayoutHash    dynamic suffix layout token
     * @return combined token
     */
    public static int combinedSplitLayoutHash(int terrainMaterialCount, int dynamicLayoutHash) {
        if (terrainMaterialCount <= 0 || terrainMaterialCount > RtDynamicBlasCache.DYNAMIC_MATERIAL_INDEX_BIT) {
            throw new IllegalArgumentException("terrain material count must fit the split material namespace");
        }
        int result = 1;
        result = 31 * result + terrainMaterialCount;
        return 31 * result + dynamicLayoutHash;
    }

    /**
     * Recovers the independently owned dynamic layout token from the split namespace hash.
     *
     * @param terrainMaterialCount terrain prefix length
     * @param combinedLayoutHash   combined split layout token
     * @return dynamic suffix layout token
     */
    public static int dynamicSplitLayoutHash(int terrainMaterialCount, int combinedLayoutHash) {
        if (terrainMaterialCount <= 0 || terrainMaterialCount > RtDynamicBlasCache.DYNAMIC_MATERIAL_INDEX_BIT) {
            throw new IllegalArgumentException("terrain material count must fit the split material namespace");
        }
        return combinedLayoutHash - 31 * (31 + terrainMaterialCount);
    }

    /**
     * Tests exact material-to-built-geometry revision pairing for both physical lanes.
     *
     * @param sectionRevision      material terrain revision
     * @param builtSectionRevision built terrain revision
     * @param dynamicRevision      material dynamic revision
     * @param builtDynamicRevision built dynamic revision
     * @return exact pairing
     */
    public static boolean materialSnapshotRevisionsMatchBuiltGeometry(
            long sectionRevision,
            long builtSectionRevision,
            long dynamicRevision,
            long builtDynamicRevision
    ) {
        return sectionRevision == builtSectionRevision && dynamicRevision == builtDynamicRevision;
    }

    /**
     * The initial front may be built only after the authoritative foreground is
     * known complete. Keeping that decision with the world-TLAS owner prevents
     * the device lifecycle from reaching back into scene convergence policy.
     */
    private static boolean initialWorldFrontBuildReady(
            boolean currentWorldTlasPresent,
            boolean authoritativeViewEstablished,
            boolean authoritativeForegroundEstablished,
            boolean foregroundCoverageIncomplete
    ) {
        return currentWorldTlasPresent
                || !authoritativeViewEstablished
                || (authoritativeForegroundEstablished && !foregroundCoverageIncomplete);
    }

    /**
     * Applies initial-world backlog and minimum-instance admission policy.
     *
     * @param currentTlasMissing           whether no world is bound
     * @param instanceCount                candidate instances
     * @param pendingSectionBuilds         pending terrain builds
     * @param minInitialInstances          minimum startup population
     * @param allowBackloggedInitialBuilds whether backlog may be published
     * @return deferral decision
     */
    public static boolean shouldDeferInitialBuild(
            boolean currentTlasMissing,
            int instanceCount,
            int pendingSectionBuilds,
            int minInitialInstances,
            boolean allowBackloggedInitialBuilds
    ) {
        if (instanceCount < 0) {
            throw new IllegalArgumentException("instanceCount must not be negative");
        }
        if (pendingSectionBuilds < 0) {
            throw new IllegalArgumentException("pendingSectionBuilds must not be negative");
        }
        if (minInitialInstances <= 0) {
            throw new IllegalArgumentException("minInitialInstances must be positive");
        }
        if (!currentTlasMissing || pendingSectionBuilds == 0) {
            return false;
        }
        if (!allowBackloggedInitialBuilds) {
            return true;
        }
        return instanceCount < minInitialInstances;
    }

    /**
     * Applies streaming coalescing thresholds in revisions, instances, and nanoseconds.
     *
     * @param builtRevision                    built input revision
     * @param builtInstances                   built instances
     * @param latestRevision                   latest input revision
     * @param latestInstances                  latest instances
     * @param pendingSectionBuilds             pending terrain builds
     * @param nowNanos                         monotonic time in nanoseconds
     * @param nextStreamingRebuildNanos        deadline in nanoseconds
     * @param minStreamingRevisionDelta        revision threshold
     * @param minStreamingInstanceDelta        instance threshold
     * @param allowBackloggedStreamingRebuilds backlog policy
     * @return deferral decision
     */
    public static boolean shouldDeferStreamingRebuild(
            long builtRevision,
            int builtInstances,
            long latestRevision,
            int latestInstances,
            int pendingSectionBuilds,
            long nowNanos,
            long nextStreamingRebuildNanos,
            long minStreamingRevisionDelta,
            int minStreamingInstanceDelta,
            boolean allowBackloggedStreamingRebuilds
    ) {
        if (builtRevision < UNBUILT_REVISION) {
            throw new IllegalArgumentException("builtRevision must be -1 or greater");
        }
        if (builtInstances < 0) {
            throw new IllegalArgumentException("builtInstances must not be negative");
        }
        if (latestRevision < UNBUILT_REVISION) {
            throw new IllegalArgumentException("latestRevision must be -1 or greater");
        }
        if (latestInstances < 0) {
            throw new IllegalArgumentException("latestInstances must not be negative");
        }
        if (pendingSectionBuilds < 0) {
            throw new IllegalArgumentException("pendingSectionBuilds must not be negative");
        }
        if (minStreamingRevisionDelta <= 0L) {
            throw new IllegalArgumentException("minStreamingRevisionDelta must be positive");
        }
        if (minStreamingInstanceDelta <= 0) {
            throw new IllegalArgumentException("minStreamingInstanceDelta must be positive");
        }
        if (pendingSectionBuilds == 0 || builtRevision == UNBUILT_REVISION) {
            return false;
        }
        if (!allowBackloggedStreamingRebuilds) {
            return true;
        }

        long revisionDelta = latestRevision - builtRevision;
        int instanceDelta = Math.abs(latestInstances - builtInstances);
        if (revisionDelta <= 0L) {
            return true;
        }
        return nowNanos < nextStreamingRebuildNanos
                && revisionDelta < minStreamingRevisionDelta
                && instanceDelta < minStreamingInstanceDelta;
    }

    /**
     * Coalesces stable successor generations until the nanosecond deadline.
     *
     * @param currentTlasLive           bound-world presence
     * @param deferredByPendingBacklog  prior deferral state
     * @param minRebuildIntervalNanos   interval in nanoseconds
     * @param latestSectionRevision     latest terrain revision
     * @param builtSectionRevision      built terrain revision
     * @param latestDynamicRevision     latest dynamic revision
     * @param builtDynamicRevision      built dynamic revision
     * @param nowNanos                  monotonic time in nanoseconds
     * @param nextCoalescedRebuildNanos deadline in nanoseconds
     * @return coalescing decision
     */
    public static boolean shouldCoalesceStableUpdate(
            boolean currentTlasLive,
            boolean deferredByPendingBacklog,
            long minRebuildIntervalNanos,
            long latestSectionRevision,
            long builtSectionRevision,
            long latestDynamicRevision,
            long builtDynamicRevision,
            long nowNanos,
            long nextCoalescedRebuildNanos
    ) {
        if (minRebuildIntervalNanos < 0L) {
            throw new IllegalArgumentException("minRebuildIntervalNanos must not be negative");
        }
        if (latestSectionRevision < UNBUILT_REVISION || builtSectionRevision < UNBUILT_REVISION) {
            throw new IllegalArgumentException("section revisions must be -1 or greater");
        }
        if (latestDynamicRevision < UNBUILT_REVISION || builtDynamicRevision < UNBUILT_REVISION) {
            throw new IllegalArgumentException("dynamic revisions must be -1 or greater");
        }
        if (!currentTlasLive || deferredByPendingBacklog || minRebuildIntervalNanos == 0L) {
            return false;
        }
        return latestSectionRevision == builtSectionRevision
                && latestDynamicRevision == builtDynamicRevision
                && nowNanos < nextCoalescedRebuildNanos;
    }

    /**
     * Predicts presentation blocking without mutating world ownership.
     *
     * @param input           immutable world input
     * @param rendererBacklog current renderer backlog
     * @return block reason, or {@code null}
     */
    public static String predictedFrameDispatchBlockReason(
            RtSectionTlasBuildInput input,
            RendererUpdateLoop.BacklogSnapshot rendererBacklog
    ) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(rendererBacklog, "rendererBacklog");
        return new RtSceneReadiness(
                true,
                input.instances().size(),
                input.pendingSectionBuilds(),
                input.pendingTriangles(),
                input.cachedTriangles(),
                input.revision(),
                input.revision(),
                input.hasPendingSectionBuilds()
        ).frameDispatchBlockReason(rendererBacklog);
    }

    /**
     * Documents that presentation blocking never suppresses correctness rebuilds.
     *
     * @param predictedBlockReason predicted reason
     * @return always {@code false}
     */
    public static boolean shouldDeferPresentationBlockedRebuild(String predictedBlockReason) {
        Objects.requireNonNull(predictedBlockReason, "predictedBlockReason");
        return false;
    }

    /**
     * Documents that GPU completion is always polled, even while descriptors are busy.
     *
     * @param worldSceneDescriptorsCanBeUpdated descriptor availability
     * @return always {@code true}
     */
    public static boolean shouldPollCompletedWorldTlasBuild(boolean worldSceneDescriptorsCanBeUpdated) {
        return true;
    }

    /**
     * Documents that completion ownership is decoupled from descriptor availability.
     *
     * @param worldSceneDescriptorsCanBeUpdated descriptor availability
     * @param pendingWorldTlasBuildPresent      pending build presence
     * @return always {@code false}
     */
    public static boolean shouldDeferWorldTlasCompletionForDescriptor(
            boolean worldSceneDescriptorsCanBeUpdated,
            boolean pendingWorldTlasBuildPresent
    ) {
        return false;
    }

    /**
     * Prevents destroying the bound world while its descriptor cannot be rewritten.
     *
     * @param worldSceneDescriptorsCanBeUpdated descriptor availability
     * @param currentTlasLive                   bound-world presence
     * @return transition deferral
     */
    public static boolean shouldDeferEmptyWorldTransitionForDescriptor(
            boolean worldSceneDescriptorsCanBeUpdated,
            boolean currentTlasLive
    ) {
        return !worldSceneDescriptorsCanBeUpdated && currentTlasLive;
    }

    /**
     * Stabilizes empty-world transitions across pending renderer work and consecutive frames.
     *
     * @param currentTlasLive              bound-world presence
     * @param pendingWorldTlasBuildPresent pending build presence
     * @param rendererBacklog              renderer backlog
     * @param consecutiveEmptyInputs       consecutive empty captures
     * @param minStableEmptyFrames         stability threshold
     * @param forceCurrentRevision         force flag
     * @return transition deferral
     */
    public static boolean shouldDeferEmptyWorldTransition(
            boolean currentTlasLive,
            boolean pendingWorldTlasBuildPresent,
            RendererUpdateLoop.BacklogSnapshot rendererBacklog,
            int consecutiveEmptyInputs,
            int minStableEmptyFrames,
            boolean forceCurrentRevision
    ) {
        Objects.requireNonNull(rendererBacklog, "rendererBacklog");
        if (consecutiveEmptyInputs < 0) {
            throw new IllegalArgumentException("consecutiveEmptyInputs must not be negative");
        }
        if (minStableEmptyFrames <= 0) {
            throw new IllegalArgumentException("minStableEmptyFrames must be positive");
        }
        if (forceCurrentRevision) {
            return false;
        }
        return currentTlasLive
                && (pendingWorldTlasBuildPresent
                || rendererBacklog.hasPendingRendererWork()
                || consecutiveEmptyInputs < minStableEmptyFrames);
    }

    /**
     * Tests monotonic staleness of a completed world generation.
     *
     * @param completedRevision completed revision
     * @param latestRevision    latest input revision
     * @return staleness
     */
    public static boolean completedWorldTlasIsBehindLatestRevision(long completedRevision, long latestRevision) {
        if (completedRevision < 0L) {
            throw new IllegalArgumentException("completedRevision must not be negative");
        }
        if (latestRevision < 0L) {
            throw new IllegalArgumentException("latestRevision must not be negative");
        }
        return completedRevision < latestRevision;
    }

    /**
     * Chooses whether a stale completion can still bootstrap an otherwise empty bound world.
     *
     * @param staleCompletion       staleness
     * @param urgent                urgency
     * @param boundWorldTlasPresent bound-world presence
     * @return discard decision
     */
    public static boolean shouldDiscardStaleWorldTlasCompletion(
            boolean staleCompletion,
            boolean urgent,
            boolean boundWorldTlasPresent
    ) {
        return staleCompletion && !urgent && boundWorldTlasPresent;
    }

    /**
     * Computes the oldest revision protected across built, bound, and pending ownership.
     *
     * @param currentTlasLive          active-world presence
     * @param builtRevision            active build revision
     * @param boundRevision            descriptor-bound revision
     * @param pendingWorldTlasRevision pending revision
     * @return oldest protected revision
     */
    public static long protectedSectionRevision(
            boolean currentTlasLive,
            long builtRevision,
            long boundRevision,
            long pendingWorldTlasRevision
    ) {
        if (builtRevision < UNBUILT_REVISION) {
            throw new IllegalArgumentException("builtRevision must be -1 or greater");
        }
        if (boundRevision < UNBUILT_REVISION) {
            throw new IllegalArgumentException("boundRevision must be -1 or greater");
        }
        if (pendingWorldTlasRevision < UNBUILT_REVISION) {
            throw new IllegalArgumentException("pendingWorldTlasRevision must be -1 or greater");
        }
        if (currentTlasLive && builtRevision == UNBUILT_REVISION) {
            throw new IllegalArgumentException("live world TLAS must have a built revision");
        }
        if (!currentTlasLive && boundRevision != UNBUILT_REVISION) {
            throw new IllegalArgumentException("bound world TLAS revision requires a live TLAS");
        }

        long protectedRevision = Long.MAX_VALUE;
        if (currentTlasLive) {
            protectedRevision = Math.min(protectedRevision, builtRevision);
        }
        if (boundRevision != UNBUILT_REVISION) {
            protectedRevision = Math.min(protectedRevision, boundRevision);
        }
        if (pendingWorldTlasRevision != UNBUILT_REVISION) {
            protectedRevision = Math.min(protectedRevision, pendingWorldTlasRevision);
        }
        return protectedRevision;
    }

    private static long positiveLongProperty(String name, long defaultValue) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed >= 0L ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static long strictlyPositiveLongProperty(String name, long defaultValue) {
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

    private static boolean booleanProperty(String name, boolean defaultValue) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        return defaultValue;
    }

    private static void close(RtAccelerationStructure accelerationStructure) {
        if (accelerationStructure != null) {
            accelerationStructure.close();
        }
    }

    private static RuntimeException closeCollecting(
            RuntimeException failure,
            RtAccelerationStructure accelerationStructure
    ) {
        if (accelerationStructure == null) {
            return failure;
        }
        try {
            accelerationStructure.close();
            return failure;
        } catch (RuntimeException ex) {
            if (failure == null) {
                return ex;
            }
            failure.addSuppressed(ex);
            return failure;
        }
    }

    private static void closeSuppressing(Throwable failure, RtAccelerationStructure accelerationStructure) {
        if (accelerationStructure == null) {
            return;
        }
        try {
            accelerationStructure.close();
        } catch (RuntimeException ex) {
            failure.addSuppressed(ex);
        }
    }

    private static long combinedRevision(long sectionRevision, long dynamicRevision) {
        if (sectionRevision < 0L || dynamicRevision < 0L) {
            throw new IllegalArgumentException("combined revision inputs must not be negative");
        }
        return sectionRevision + dynamicRevision;
    }

    static int instanceLayoutHash(List<RtAccelerationStructure.TlasInstance> instances) {
        Objects.requireNonNull(instances, "instances");
        int result = 1;
        for (int index = 0; index < instances.size(); index++) {
            RtAccelerationStructure.TlasInstance instance = instances.get(index);
            result = 31 * result + index;
            result = 31 * result + instance.customIndex();
            result = 31 * result + Long.hashCode(instance.blasDeviceAddress());
        }
        return result;
    }

    static boolean shouldUpdateWorldTlas(
            boolean currentTlasAvailable,
            int boundInstanceTopologyHash,
            int candidateInstanceTopologyHash
    ) {
        return currentTlasAvailable && boundInstanceTopologyHash == candidateInstanceTopologyHash;
    }

    static int instanceTopologyHash(List<RtAccelerationStructure.TlasInstance> instances) {
        Objects.requireNonNull(instances, "instances");
        /*
         * Vulkan UPDATE requires the primitive capacity established by BUILD,
         * not stable BLAS addresses, transforms, masks, or shader custom data.
         * The list is a persistent slot table padded with mask-zero instances,
         * so its physical capacity is the complete topology contract.
         */
        return Integer.hashCode(instances.size());
    }

    static int persistentInstanceCapacity(int activeInstances, int builtCapacity) {
        if (activeInstances <= 0 || builtCapacity < 0) {
            throw new IllegalArgumentException("active instance count must be positive and built capacity non-negative");
        }
        if (builtCapacity >= activeInstances) {
            return builtCapacity;
        }
        int capacity = Math.max(1, builtCapacity);
        while (capacity < activeInstances) {
            if (capacity > (Integer.MAX_VALUE >>> 1)) {
                throw new IllegalArgumentException("TLAS instance capacity overflow: " + activeInstances);
            }
            capacity <<= 1;
        }
        return capacity;
    }

    /**
     * Advances world build state using untraced capture causality.
     *
     * @param sectionBlasCache                           terrain source owner
     * @param dynamicBlasCache                           dynamic source owner
     * @param rendererBacklog                            current renderer backlog
     * @param worldSceneDescriptorsCanBeUpdated          descriptor write availability
     * @param forceCurrentRevision                       whether to bypass coalescing
     * @param urgencySource                              diagnostic urgency source
     * @param coverageContractionAuthorizationGeneration contraction authorization
     * @return completed bind candidate, or {@code null}
     */
    public synchronized WorldTlasUpdate processFrameBudget(
            RtSectionBlasCache sectionBlasCache,
            RtDynamicBlasCache dynamicBlasCache,
            RendererUpdateLoop.BacklogSnapshot rendererBacklog,
            boolean worldSceneDescriptorsCanBeUpdated,
            boolean forceCurrentRevision,
            String urgencySource,
            long coverageContractionAuthorizationGeneration
    ) {
        return processFrameBudget(
                sectionBlasCache,
                dynamicBlasCache,
                rendererBacklog,
                worldSceneDescriptorsCanBeUpdated,
                forceCurrentRevision,
                urgencySource,
                coverageContractionAuthorizationGeneration,
                RendererFrameCausality.untraced(0L)
        );
    }

    /**
     * Advances completion, coalescing, submission, and empty-world transitions atomically.
     *
     * @param sectionBlasCache                           terrain source owner
     * @param dynamicBlasCache                           dynamic source owner
     * @param rendererBacklog                            current renderer backlog
     * @param worldSceneDescriptorsCanBeUpdated          descriptor write availability
     * @param forceCurrentRevision                       whether to bypass coalescing
     * @param urgencySource                              diagnostic urgency source
     * @param coverageContractionAuthorizationGeneration contraction authorization
     * @param causality                                  capture identity
     * @return completed bind candidate, or {@code null}
     */
    public synchronized WorldTlasUpdate processFrameBudget(
            RtSectionBlasCache sectionBlasCache,
            RtDynamicBlasCache dynamicBlasCache,
            RendererUpdateLoop.BacklogSnapshot rendererBacklog,
            boolean worldSceneDescriptorsCanBeUpdated,
            boolean forceCurrentRevision,
            String urgencySource,
            long coverageContractionAuthorizationGeneration,
            RendererFrameCausality causality
    ) {
        Objects.requireNonNull(sectionBlasCache, "sectionBlasCache");
        Objects.requireNonNull(dynamicBlasCache, "dynamicBlasCache");
        Objects.requireNonNull(rendererBacklog, "rendererBacklog");
        urgencySource = Objects.requireNonNull(urgencySource, "urgencySource");
        causality = Objects.requireNonNull(causality, "causality");
        if (coverageContractionAuthorizationGeneration < 0L) {
            throw new IllegalArgumentException("coverage contraction authorization generation must not be negative");
        }
        if (closed) {
            throw new IllegalStateException("RT world TLAS cache is already closed");
        }
        boolean recordPhases = RtSceneWorkFlightRecorder.worldTlasEnabled();
        long totalStartNanos = recordPhases ? System.nanoTime() : 0L;
        long phaseStartNanos = totalStartNanos;
        long smokeAggregateNanos = 0L;
        long statsSnapshotNanos = 0L;
        long observationNanos = 0L;
        long completionPollNanos = 0L;
        long gateNanos = 0L;
        long gateStartNanos = 0L;
        long inputSnapshotNanos = 0L;
        long submitNanos = 0L;
        int observedInstances = -1;
        int pendingSectionBuilds = -1;
        RtWorldTlasBuildStats stats = null;
        String outcome = "exception";
        try {
            emitSmokeAggregateIfDue();
            smokeAggregateNanos = recordPhases ? System.nanoTime() - phaseStartNanos : 0L;

            phaseStartNanos = recordPhases ? System.nanoTime() : 0L;
            stats = RtWorldTlasBuildStats.from(
                    sectionBlasCache.snapshotTlasBuildStats(),
                    dynamicBlasCache.snapshotInstanceStats()
            );
            statsSnapshotNanos = recordPhases ? System.nanoTime() - phaseStartNanos : 0L;
            observedInstances = stats.instances();
            pendingSectionBuilds = stats.pendingSectionBuilds();

            phaseStartNanos = recordPhases ? System.nanoTime() : 0L;
            if (diagnostics.edges().enabled()) {
                if (stats.sectionRevision() != latestObservedSectionRevision) {
                    smokeSectionRevisionChanges++;
                }
                if (stats.dynamicRevision() != latestObservedDynamicRevision) {
                    smokeDynamicInstanceRevisionChanges++;
                }
                if (stats.dynamicTopologyRevision() != latestObservedDynamicTopologyRevision) {
                    smokeDynamicTopologyRevisionChanges++;
                }
                if (stats.dynamicGeometryRevision() != latestObservedDynamicGeometryRevision) {
                    smokeDynamicGeometryRevisionChanges++;
                }
                if (stats.dynamicSceneRevision() != latestObservedDynamicSceneRevision) {
                    smokeDynamicSceneRevisionChanges++;
                }
            }
            observe(stats);
            if (forceCurrentRevision) {
                urgentRebuildRequests++;
            }
            observationNanos = recordPhases ? System.nanoTime() - phaseStartNanos : 0L;
            gateStartNanos = recordPhases ? System.nanoTime() : 0L;

            if (stats.instances() == 0) {
                recordSmokeEmptyInputDeferral();
                consecutiveEmptyInputs++;
                if (shouldDeferEmptyWorldTransition(
                        currentTlas != null,
                        pendingWorldTlasBuild != null,
                        rendererBacklog,
                        consecutiveEmptyInputs,
                        MIN_EMPTY_TRANSITION_STABLE_FRAMES,
                        forceCurrentRevision
                )) {
                    deferredPasses++;
                    emptyInputTransitionDeferrals++;
                    outcome = "deferredEmptyTransitionStability";
                    return null;
                }
                if (shouldDeferEmptyWorldTransitionForDescriptor(worldSceneDescriptorsCanBeUpdated, currentTlas != null)) {
                    descriptorBusyWorldTlasDeferrals++;
                    outcome = "deferredEmptyTransitionDescriptor";
                    return null;
                }
                transitionToEmpty(stats.revision());
                outcome = "transitionedToEmpty";
                return null;
            }
            consecutiveEmptyInputs = 0;

            if (recordPhases) {
                long now = System.nanoTime();
                gateNanos += now - gateStartNanos;
                phaseStartNanos = now;
            }
            WorldTlasUpdate completedUpdate = pollPendingWorldTlasBuild(stats.revision());
            completionPollNanos = recordPhases ? System.nanoTime() - phaseStartNanos : 0L;
            gateStartNanos = recordPhases ? System.nanoTime() : 0L;
            if (completedUpdate != null) {
                outcome = "completedBuild";
                return completedUpdate;
            }

            if (stats.revision() == builtRevision || pendingWorldTlasBuild != null) {
                if (pendingWorldTlasBuild != null) {
                    recordSmokePendingSubmissionDeferral();
                }
                outcome = pendingWorldTlasBuild != null ? "pendingBuild" : "revisionAlreadyBuilt";
                return null;
            }
            if (!initialWorldFrontBuildReady(
                    currentTlas != null,
                    sectionBlasCache.authoritativeViewEstablished(),
                    sectionBlasCache.authoritativeForegroundEstablished(),
                    sectionBlasCache.initialForegroundCoverageIncomplete()
            )) {
                recordSmokeInitialFrontGateDeferral();
                deferredPasses++;
                deferredByPendingBacklog = true;
                outcome = "deferredInitialForeground";
                return null;
            }
            if (shouldDeferInitialBuild(
                    currentTlas == null,
                    stats.instances(),
                    stats.pendingSectionBuilds(),
                    minInitialInstances,
                    allowBackloggedStreamingRebuilds
            )) {
                recordSmokeInitialBuildDeferral();
                deferredPasses++;
                deferredByPendingBacklog = true;
                outcome = "deferredInitialInstanceThreshold";
                return null;
            }

            if (!forceCurrentRevision
                    && currentTlas != null
                    && stats.hasPendingSectionBuilds()
                    && shouldDeferStreamingRebuild(stats)) {
                recordSmokeStreamingDeferral();
                deferredPasses++;
                deferredByPendingBacklog = true;
                outcome = "deferredStreaming";
                return null;
            }

            if (!forceCurrentRevision && shouldCoalesceStableUpdate(stats)) {
                recordSmokeCoalescedDeferral();
                coalescedPasses++;
                outcome = "coalescedStableUpdate";
                return null;
            }

            if (recordPhases) {
                long now = System.nanoTime();
                gateNanos += now - gateStartNanos;
                gateStartNanos = 0L;
                phaseStartNanos = now;
            }
            RtSectionTlasBuildInput sectionInput = sectionBlasCache.snapshotTlasBuildInput();
            RtWorldTlasBuildInput input = snapshotTlasBuildInput(
                    sectionInput,
                    dynamicBlasCache.snapshotInstanceState(),
                    coverageContractionAuthorizationGeneration
            );
            inputSnapshotNanos = recordPhases ? System.nanoTime() - phaseStartNanos : 0L;
            if (input.instances().isEmpty()) {
                outcome = "emptyBuildInput";
                return null;
            }
            phaseStartNanos = recordPhases ? System.nanoTime() : 0L;
            submitRebuild(input, forceCurrentRevision, urgencySource, causality);
            submitNanos = recordPhases ? System.nanoTime() - phaseStartNanos : 0L;
            outcome = "submittedBuild";
            return null;
        } finally {
            if (recordPhases && gateStartNanos != 0L) {
                gateNanos += System.nanoTime() - gateStartNanos;
            }
            phaseStartNanos = recordPhases ? System.nanoTime() : 0L;
            publishReadiness();
            long readinessPublishNanos = recordPhases ? System.nanoTime() - phaseStartNanos : 0L;
            if (recordPhases) {
                RtSceneWorkFlightRecorder.recordWorldTlasScheduler(
                        outcome,
                        System.nanoTime() - totalStartNanos,
                        smokeAggregateNanos,
                        statsSnapshotNanos,
                        observationNanos,
                        completionPollNanos,
                        gateNanos,
                        inputSnapshotNanos,
                        submitNanos,
                        readinessPublishNanos,
                        observedInstances,
                        stats == null ? -1 : stats.sectionInstances(),
                        stats == null ? -1 : stats.sectionActiveViewSections(),
                        stats == null ? -1 : stats.sectionExactInstances(),
                        stats == null ? -1 : stats.sectionFarFieldInstances(),
                        stats == null ? -1 : stats.dynamicInstances(),
                        pendingSectionBuilds,
                        stats != null && stats.sectionViewAuthoritative(),
                        stats != null && stats.sectionForegroundAuthoritative(),
                        forceCurrentRevision
                );
            }
        }
    }

    /**
     * Formats a monitor-consistent resource and scheduler snapshot.
     *
     * @param name diagnostic label
     * @return formatted snapshot
     */
    public synchronized String summary(String name) {
        return name
                + "{hasTlas=" + (currentTlas != null)
                + ", builtRevision=" + builtRevision
                + ", boundRevision=" + boundRevision
                + ", protectedSectionRevision=" + protectedSectionRevision()
                + ", protectedSectionSceneRevision=" + protectedSectionSceneRevision()
                + ", protectedDynamicRevision=" + protectedDynamicRevision()
                + ", builtSectionRevision=" + builtSectionRevision
                + ", boundSectionRevision=" + boundSectionRevision
                + ", builtSectionResourceRevision=" + builtSectionResourceRevision
                + ", boundSectionResourceRevision=" + boundSectionResourceRevision
                + ", builtDynamicRevision=" + builtDynamicRevision
                + ", boundDynamicRevision=" + boundDynamicRevision
                + ", builtDynamicTopologyRevision=" + builtDynamicTopologyRevision
                + ", boundDynamicTopologyRevision=" + boundDynamicTopologyRevision
                + ", builtDynamicGeometryRevision=" + builtDynamicGeometryRevision
                + ", boundDynamicGeometryRevision=" + boundDynamicGeometryRevision
                + ", builtInstanceTopologyHash=0x" + Integer.toHexString(builtInstanceTopologyHash)
                + ", latestRevision=" + latestObservedRevision
                + ", latestSectionRevision=" + latestObservedSectionRevision
                + ", latestSectionResourceRevision=" + latestObservedSectionResourceRevision
                + ", latestSectionMaterialRevision=" + latestObservedSectionMaterialRevision
                + ", latestDynamicRevision=" + latestObservedDynamicRevision
                + ", latestDynamicTopologyRevision=" + latestObservedDynamicTopologyRevision
                + ", latestDynamicGeometryRevision=" + latestObservedDynamicGeometryRevision
                + ", latestDynamicSceneRevision=" + latestObservedDynamicSceneRevision
                + ", builtInstances=" + builtInstances
                + ", builtSectionInstances=" + builtSectionInstances
                + ", builtDynamicInstances=" + builtDynamicInstances
                + ", boundDynamicLaneInstances=" + boundDynamicLaneInstances
                + ", observedInstances=" + latestObservedInstances
                + ", observedSectionInstances=" + latestObservedSectionInstances
                + ", observedDynamicInstances=" + latestObservedDynamicInstances
                + ", pendingSectionBuilds=" + latestObservedPendingSectionBuilds
                + ", pendingTriangles=" + latestObservedPendingTriangles
                + ", cachedTriangles=" + latestObservedCachedTriangles
                + ", dynamicPrimitives=" + latestObservedDynamicPrimitives
                + ", dynamicFaces=" + latestObservedDynamicFaces
                + ", dynamicTriangles=" + latestObservedDynamicTriangles
                + ", rebuildPasses=" + rebuildPasses
                + ", streamingRebuildPasses=" + streamingRebuildPasses
                + ", deferredPasses=" + deferredPasses
                + ", coalescedPasses=" + coalescedPasses
                + ", emptyTransitions=" + emptyTransitions
                + ", pendingWorldTlasBuild=" + (pendingWorldTlasBuild != null)
                + ", pendingWorldTlasRevision=" + (pendingWorldTlasBuild == null ? UNBUILT_REVISION : pendingWorldTlasBuild.revision())
                + ", pendingWorldTlasInstances=" + (pendingWorldTlasBuild == null ? 0 : pendingWorldTlasBuild.instanceCount())
                + ", asyncBuildSubmissions=" + asyncBuildSubmissions
                + ", asyncBuildCompletions=" + asyncBuildCompletions
                + ", asyncBuildPollsNotReady=" + asyncBuildPollsNotReady
                + ", asyncBuildCloseWaits=" + asyncBuildCloseWaits
                + ", asyncBuildReplacements=" + asyncBuildReplacements
                + ", streamingBoundWorldTlasUpdates=" + streamingBoundWorldTlasUpdates
                + ", discardedUnboundWorldTlasUpdates=" + discardedUnboundWorldTlasUpdates
                + ", descriptorBusyWorldTlasDeferrals=" + descriptorBusyWorldTlasDeferrals
                + ", staleWorldTlasCompletionsBoundForDispatch=" + staleWorldTlasCompletionsBoundForDispatch
                + ", emptyInputTransitionDeferrals=" + emptyInputTransitionDeferrals
                + ", urgentRebuildRequests=" + urgentRebuildRequests
                + ", urgentRebuildSubmissions=" + urgentRebuildSubmissions
                + ", retiredWorldTlasBatches=" + destinationPool.retiredBatches()
                + ", releasedRetiredWorldTlasBatches=" + destinationPool.releasedRetiredBatches()
                + ", pendingRetiredWorldTlases=" + destinationPool.retiredCount()
                + ", reusableWorldTlasSlots=" + destinationPool.reusableCount()
                + ", reusedWorldTlasDestinations=" + destinationPool.reusedDestinations()
                + ", newWorldTlasDestinations=" + destinationPool.newDestinations()
                + ", pooledWorldTlasDestinations=" + destinationPool.pooledDestinations()
                + ", poolCapacityWorldTlasReleases=" + destinationPool.poolCapacityReleases()
                + ", consecutiveEmptyInputs=" + consecutiveEmptyInputs
                + ", lastUnboundWorldTlasDiscardReason=" + lastUnboundWorldTlasDiscardReason
                + ", totalInstancesBuilt=" + totalInstancesBuilt
                + ", lastRebuildMillis=" + lastRebuildMillis
                + ", maxRebuildMillis=" + maxRebuildMillis
                + ", totalRebuildMillis=" + totalRebuildMillis
                + ", lastAsyncBuildLatencyMillis=" + lastAsyncBuildLatencyMillis
                + ", maxAsyncBuildLatencyMillis=" + maxAsyncBuildLatencyMillis
                + ", totalAsyncBuildLatencyMillis=" + totalAsyncBuildLatencyMillis
                + ", minRebuildIntervalMillis=" + minRebuildIntervalNanos / 1_000_000L
                + ", minStreamingRebuildIntervalMillis=" + minStreamingRebuildIntervalNanos / 1_000_000L
                + ", minInitialInstances=" + minInitialInstances
                + ", minStreamingRevisionDelta=" + minStreamingRevisionDelta
                + ", minStreamingInstanceDelta=" + minStreamingInstanceDelta
                + ", allowBackloggedStreamingRebuilds=" + allowBackloggedStreamingRebuilds
                + (currentTlas == null ? ", worldTlas=empty" : ", " + currentTlas.summary("worldTlas"))
                + "}";
    }

    /**
     * Recomputes and publishes readiness from owned state.
     *
     * @return immutable readiness
     */
    public synchronized RtSceneReadiness readiness() {
        RtSceneReadiness readiness = readinessFromOwnedState();
        publishedReadiness = readiness;
        return readiness;
    }

    /**
     * Returns the last fully published scheduler state without acquiring this
     * cache's monitor.  Callers may use it only for conservative display-policy
     * decisions; state mutation and telemetry refresh stay on the synchronized
     * path.
     *
     * @return last monitor-published immutable readiness state
     */
    public RtSceneReadiness publishedReadiness() {
        return publishedReadiness;
    }

    private RtSceneReadiness readinessFromOwnedState() {
        return new RtSceneReadiness(
                currentTlas != null,
                Math.addExact(latestObservedSectionInstances, latestObservedDynamicInstances),
                latestObservedSectionInstances,
                latestObservedDynamicInstances,
                currentTlas == null ? 0 : builtSectionInstances,
                currentTlas == null ? 0 : boundDynamicLaneInstances,
                latestObservedPendingSectionBuilds,
                false,
                latestObservedPendingTriangles,
                latestObservedCachedTriangles,
                builtRevision,
                latestObservedRevision,
                deferredByPendingBacklog
        );
    }

    /**
     * Returns the greatest section material revision observed by world scheduling.
     *
     * @return section material revision
     */
    public synchronized long latestObservedSectionMaterialRevision() {
        return latestObservedSectionMaterialRevision;
    }

    /**
     * Recomputes readiness after sampling current section and dynamic cache state.
     *
     * @param sectionBlasCache terrain source owner
     * @param dynamicBlasCache dynamic source owner
     * @return immutable readiness
     */
    public synchronized RtSceneReadiness readiness(
            RtSectionBlasCache sectionBlasCache,
            RtDynamicBlasCache dynamicBlasCache
    ) {
        Objects.requireNonNull(sectionBlasCache, "sectionBlasCache");
        Objects.requireNonNull(dynamicBlasCache, "dynamicBlasCache");
        if (closed) {
            throw new IllegalStateException("RT world TLAS cache is already closed");
        }
        observe(RtWorldTlasBuildStats.from(
                sectionBlasCache.snapshotTlasBuildStats(),
                dynamicBlasCache.snapshotInstanceStats()
        ));
        return readiness();
    }

    private void publishReadiness() {
        publishedReadiness = readinessFromOwnedState();
    }

    private void observe(RtWorldTlasBuildStats stats) {
        latestObservedRevision = stats.revision();
        latestObservedInstances = stats.instances();
        latestObservedSectionInstances = stats.sectionInstances();
        latestObservedPendingSectionBuilds = stats.pendingSectionBuilds();
        latestObservedPendingTriangles = stats.pendingTriangles();
        latestObservedCachedTriangles = stats.cachedTriangles();
        latestObservedSectionRevision = stats.sectionRevision();
        latestObservedSectionResourceRevision = stats.sectionResourceRevision();
        latestObservedSectionMaterialRevision = stats.sectionMaterialRevision();
        latestObservedDynamicRevision = stats.dynamicRevision();
        latestObservedDynamicTopologyRevision = stats.dynamicTopologyRevision();
        latestObservedDynamicGeometryRevision = stats.dynamicGeometryRevision();
        latestObservedDynamicMaterialRevision = stats.dynamicMaterialRevision();
        latestObservedDynamicSceneRevision = stats.dynamicSceneRevision();
        latestObservedDynamicInstances = stats.dynamicInstances();
        latestObservedDynamicPrimitives = stats.dynamicPrimitives();
        latestObservedDynamicFaces = stats.dynamicFaces();
        latestObservedDynamicTriangles = stats.dynamicTriangles();
        promoteUnreferencedSectionResourceRevision(stats);
    }

    private void promoteUnreferencedSectionResourceRevision(RtWorldTlasBuildStats stats) {
        if (currentTlas == null || pendingWorldTlasBuild != null || stats.sectionRevision() != builtSectionRevision) {
            return;
        }
        builtSectionResourceRevision = Math.max(builtSectionResourceRevision, stats.sectionResourceRevision());
        if (boundSectionRevision == stats.sectionRevision()) {
            boundSectionResourceRevision = Math.max(
                    boundSectionResourceRevision,
                    stats.sectionResourceRevision()
            );
        }
    }

    /**
     * Tests whether the descriptor-visible world uses an exact dynamic revision.
     *
     * @param dynamicRevision candidate dynamic revision
     * @return whether it is currently bound
     */
    public synchronized boolean boundDynamicRevisionIsCurrent(long dynamicRevision) {
        if (dynamicRevision < 0L) {
            throw new IllegalArgumentException("dynamicRevision must not be negative");
        }
        return currentTlas != null
                && boundDynamicRevision >= 0L
                && boundDynamicRevision == dynamicRevision;
    }

    /**
     * Reports whether a dynamic lane is descriptor-visible.
     *
     * @return bound-lane presence
     */
    public synchronized boolean hasBoundDynamicLane() {
        return currentTlas != null && boundDynamicRevision >= 0L;
    }

    /**
     * Returns the bound dynamic publication revision, or the unbuilt sentinel.
     *
     * @return dynamic revision
     */
    public synchronized long boundDynamicRevision() {
        return boundDynamicRevision;
    }

    /**
     * Returns the bound dynamic topology revision, or the unbuilt sentinel.
     *
     * @return topology revision
     */
    public synchronized long boundDynamicTopologyRevision() {
        return boundDynamicTopologyRevision;
    }

    /**
     * Returns the bound dynamic geometry revision, or the unbuilt sentinel.
     *
     * @return geometry revision
     */
    public synchronized long boundDynamicGeometryRevision() {
        return boundDynamicGeometryRevision;
    }

    synchronized boolean boundDynamicGeometryRevisionIsCurrent(long dynamicGeometryRevision) {
        if (dynamicGeometryRevision < 0L) {
            throw new IllegalArgumentException("dynamicGeometryRevision must not be negative");
        }
        return currentTlas != null
                && boundDynamicGeometryRevision >= 0L
                && boundDynamicGeometryRevision == dynamicGeometryRevision;
    }

    /**
     * Tests topology and geometry identity independent of transform/material changes.
     *
     * @param dynamicTopologyRevision candidate topology revision
     * @param dynamicGeometryRevision candidate geometry revision
     * @return structural currency
     */
    public synchronized boolean boundDynamicStructureIsCurrent(
            long dynamicTopologyRevision,
            long dynamicGeometryRevision
    ) {
        if (dynamicTopologyRevision < 0L || dynamicGeometryRevision < 0L) {
            throw new IllegalArgumentException("dynamic structure revisions must not be negative");
        }
        return currentTlas != null
                && boundDynamicTopologyRevision == dynamicTopologyRevision
                && boundDynamicGeometryRevision == dynamicGeometryRevision;
    }

    /**
     * Composes material records matching the currently built terrain and dynamic geometry.
     *
     * @param sectionBlasCache terrain material owner
     * @param dynamicBlasCache dynamic material owner
     * @return coherent combined snapshot
     */
    public synchronized RtSceneMaterialTable.Snapshot snapshotBuiltMaterialForCurrentGeometry(
            RtSectionBlasCache sectionBlasCache,
            RtDynamicBlasCache dynamicBlasCache
    ) {
        Objects.requireNonNull(sectionBlasCache, "sectionBlasCache");
        Objects.requireNonNull(dynamicBlasCache, "dynamicBlasCache");
        if (closed) {
            throw new IllegalStateException("RT world TLAS cache is already closed");
        }
        if (currentTlas == null) {
            return null;
        }
        if (sectionBlasCache.initialForegroundCoverageIncomplete()) {
            /*
             * A material snapshot is indexed by the active terrain instance
             * layout. Until the successor is complete, refreshing that layout
             * would manufacture a partial generation that cannot bind against
             * the committed TLAS. Keep both geometry and material ownership on
             * the committed generation and publish them together.
             */
            return null;
        }
        RtSectionTlasBuildInput sectionInput = sectionBlasCache.snapshotTlasBuildInput();
        if (sectionInput.revision() != builtSectionRevision) {
            return null;
        }
        RtDynamicInstanceSnapshot dynamicInput = dynamicBlasCache.snapshotInstanceState();
        /*
         * This path runs only after a material/topology generation changed.
         * Section input is persistent and cache-backed; dynamic instances are
         * materialized once for the atomic material+TLAS publication, never on
         * stable frames or texture-only animation ticks.
         */
        return snapshotMaterialPublication(sectionInput, dynamicInput);
    }

    /**
     * Composes the latest dynamic material suffix against the descriptor-visible terrain prefix.
     *
     * <p>The dynamic TLAS is an independent physical lane. Requiring the current terrain
     * successor to match the bound terrain revision couples entity animation to chunk streaming
     * and reduces animated transforms to the terrain convergence cadence. This path preserves
     * the exact bound terrain namespace and updates only the dynamic suffix.</p>
     *
     * @param boundMaterialSnapshot     descriptor-visible combined table
     * @param boundTerrainMaterialCount immutable terrain prefix length
     * @param dynamicBlasCache          dynamic source owner
     * @return coherent table preserving bound terrain and current dynamic material
     */
    public synchronized RtSceneMaterialTable.Snapshot snapshotBoundTerrainWithDynamic(
            RtSceneMaterialTable.Snapshot boundMaterialSnapshot,
            int boundTerrainMaterialCount,
            RtDynamicBlasCache dynamicBlasCache
    ) {
        Objects.requireNonNull(boundMaterialSnapshot, "boundMaterialSnapshot");
        Objects.requireNonNull(dynamicBlasCache, "dynamicBlasCache");
        RtDynamicInstanceSnapshot dynamicInput = dynamicBlasCache.snapshotInstanceState();
        return snapshotBoundTerrainWithDynamic(
                boundMaterialSnapshot,
                boundTerrainMaterialCount,
                boundMaterialSnapshot.revision(),
                dynamicInput
        );
    }

    /**
     * Composes the dynamic suffix belonging to one completed TLAS candidate generation.
     *
     * @param boundMaterialSnapshot        descriptor-visible combined table
     * @param boundTerrainMaterialCount    immutable terrain prefix length
     * @param boundTerrainMaterialRevision exact terrain material revision
     * @param dynamicInput                 candidate-owned dynamic instance snapshot
     * @return coherent split table
     */
    public synchronized RtSceneMaterialTable.Snapshot snapshotBoundTerrainWithDynamic(
            RtSceneMaterialTable.Snapshot boundMaterialSnapshot,
            int boundTerrainMaterialCount,
            long boundTerrainMaterialRevision,
            RtDynamicInstanceSnapshot dynamicInput
    ) {
        Objects.requireNonNull(boundMaterialSnapshot, "boundMaterialSnapshot");
        Objects.requireNonNull(dynamicInput, "dynamicInput");
        if (closed) {
            throw new IllegalStateException("RT world TLAS cache is already closed");
        }
        if (currentTlas == null || boundTerrainMaterialCount <= 0
                || boundTerrainMaterialCount > boundMaterialSnapshot.sectionCount()) {
            return null;
        }
        int dynamicLayoutHash = RtDynamicTlasCache.instanceLayoutHash(dynamicInput.instances());
        int combinedLayoutHash = combinedSplitLayoutHash(boundTerrainMaterialCount, dynamicLayoutHash);
        if (boundTerrainPrefixMaterialRevision != boundTerrainMaterialRevision
                || boundTerrainPrefixSectionCount != boundTerrainMaterialCount
                || boundTerrainPrefixLayoutHash != combinedLayoutHash) {
            boundTerrainPrefix = boundMaterialSnapshot.prefix(
                    boundTerrainMaterialCount,
                    boundMaterialSnapshot.revision(),
                    combinedLayoutHash
            );
            boundTerrainPrefixMaterialRevision = boundTerrainMaterialRevision;
            boundTerrainPrefixSectionCount = boundTerrainMaterialCount;
            boundTerrainPrefixLayoutHash = combinedLayoutHash;
        }
        /*
         * The completed TLAS generation may skip several producer revisions. Its dirty-slot lane
         * is relative to the immediately preceding capture generation, not necessarily the
         * descriptor-visible material suffix. Reconcile the candidate's complete persistent slot
         * table here so monotonic catch-up cannot pair old materials with new custom indices.
         */
        RtSceneMaterialTable.Snapshot dynamicSuffix = boundDynamicMaterialFacts.submit(
                dynamicInput.materials(),
                dynamicInput.materialRevision(),
                dynamicLayoutHash
        );
        return boundDynamicMaterialComposer.compose(
                boundTerrainPrefix,
                dynamicSuffix,
                combinedRevision(boundMaterialSnapshot.revision(), dynamicInput.materialRevision()),
                combinedLayoutHash
        );
    }

    /**
     * Returns the first dynamic material record in the bound combined descriptor table.
     *
     * @return dynamic material offset
     */
    public synchronized int dynamicMaterialOffset() {
        return dynamicMaterialOffset;
    }

    /**
     * Publishes the dynamic lane only after its descriptor generation is bound.
     * RtSceneReadiness represents the logical trace scene, while the terrain
     * and dynamic physical TLAS resources remain independently owned.
     *
     * @param revision         dynamic publication revision
     * @param topologyRevision physical-slot topology revision
     * @param geometryRevision resident geometry revision
     * @param activeInstances  descriptor-visible active instance count
     */
    public synchronized void commitBoundDynamicLane(
            long revision,
            long topologyRevision,
            long geometryRevision,
            int activeInstances
    ) {
        if (revision < 0L || topologyRevision < 0L || geometryRevision < 0L || activeInstances < 0) {
            throw new IllegalArgumentException("invalid bound dynamic lane state");
        }
        builtDynamicRevision = revision;
        boundDynamicRevision = revision;
        builtDynamicTopologyRevision = topologyRevision;
        boundDynamicTopologyRevision = topologyRevision;
        builtDynamicGeometryRevision = geometryRevision;
        boundDynamicGeometryRevision = geometryRevision;
        /*
         * The terrain TLAS completion/rollback record owns builtDynamicInstances.
         * After the split-TLAS migration that physical stream is normally zero;
         * overwriting it here made the next terrain completion serialize an
         * impossible previous split (terrain count + dynamic-lane count !=
         * terrain TLAS instance count). Keep descriptor-lane readiness separate.
         */
        boundDynamicLaneInstances = activeInstances;
        publishReadiness();
    }

    /**
     * Returns the greatest section resource revision protected by active/bound/pending TLAS ownership.
     *
     * @return oldest protected revision
     */
    public synchronized long protectedSectionRevision() {
        return protectedSectionRevision(
                currentTlas != null,
                builtSectionResourceRevision,
                boundSectionResourceRevision,
                pendingWorldTlasBuild == null ? UNBUILT_REVISION : pendingWorldTlasBuild.sectionResourceRevision()
        );
    }

    /**
     * Returns the protected logical section scene revision.
     *
     * @return oldest protected scene revision
     */
    public synchronized long protectedSectionSceneRevision() {
        return protectedSectionRevision(
                currentTlas != null,
                builtSectionRevision,
                boundSectionRevision,
                pendingWorldTlasBuild == null ? UNBUILT_REVISION : pendingWorldTlasBuild.sectionRevision()
        );
    }

    /**
     * Returns the dynamic revision protected by active/bound/pending world ownership.
     *
     * @return oldest protected dynamic revision
     */
    public synchronized long protectedDynamicRevision() {
        return protectedSectionRevision(
                currentTlas != null,
                builtDynamicRevision,
                boundDynamicRevision,
                pendingWorldTlasBuild == null ? UNBUILT_REVISION : pendingWorldTlasBuild.dynamicRevision()
        );
    }

    /**
     * Commits a descriptor-bound candidate with the bootstrap retirement generation.
     *
     * @param update     exact candidate
     * @param bindReason diagnostic reason
     */
    public synchronized void commitBoundWorldTlas(WorldTlasUpdate update, String bindReason) {
        commitBoundWorldTlas(update, bindReason, 0L);
    }

    /**
     * Atomically transfers the exact candidate into descriptor-visible ownership.
     *
     * @param update                      exact candidate
     * @param bindReason                  diagnostic reason
     * @param retiredDescriptorGeneration generation protecting predecessor
     */
    public synchronized void commitBoundWorldTlas(
            WorldTlasUpdate update,
            String bindReason,
            long retiredDescriptorGeneration
    ) {
        validateCommitBoundWorldTlas(update, bindReason, retiredDescriptorGeneration);
        boundRevision = update.revision();
        boundSectionRevision = update.sectionRevision();
        boundSectionResourceRevision = update.sectionResourceRevision();
        /*
         * The terrain transaction may upload a material table that already
         * contains slots for a pending dynamic generation, but it does not own
         * the dynamic TLAS descriptor. Publishing those revisions here made a
         * bootstrap placeholder look like the completed dynamic front. Only
         * commitBoundDynamicLane() may advance dynamic visibility ownership.
         */
        if (!RtSceneReadiness.READY_REASON.equals(bindReason)) {
            streamingBoundWorldTlasUpdates++;
        }
        destinationPool.assignUnboundRetirements(retiredDescriptorGeneration);
        destinationPool.retire(update.previousTopLevelAccelerationStructure(), retiredDescriptorGeneration);
        publishReadiness();
    }

    /**
     * Proves that a completed world candidate still owns the cache commit slot before descriptor
     * visibility changes.  The render thread is the sole commit owner, so a successful preflight
     * remains valid through the immediately following descriptor bind and primitive assignments.
     *
     * @param update                      exact candidate awaiting bind
     * @param bindReason                  non-null diagnostic reason
     * @param retiredDescriptorGeneration generation protecting predecessor descriptors
     */
    public synchronized void validateCommitBoundWorldTlas(
            WorldTlasUpdate update,
            String bindReason,
            long retiredDescriptorGeneration
    ) {
        Objects.requireNonNull(update, "update");
        Objects.requireNonNull(bindReason, "bindReason");
        if (retiredDescriptorGeneration < 0L) {
            throw new IllegalArgumentException("retiredDescriptorGeneration must not be negative");
        }
        if (closed) {
            throw new IllegalStateException("RT world TLAS cache is already closed");
        }
        if (currentTlas != update.topLevelAccelerationStructure()) {
            throw new IllegalStateException("cannot commit stale world TLAS update");
        }
    }

    synchronized void releaseRetiredWorldTlases() {
        releaseRetiredWorldTlasesThrough(Long.MAX_VALUE);
    }

    /**
     * Releases world TLAS allocations through an inclusive completed descriptor generation.
     *
     * @param completedDescriptorGeneration inclusive completed generation
     */
    public synchronized void releaseRetiredWorldTlasesThrough(long completedDescriptorGeneration) {
        if (completedDescriptorGeneration < 0L) {
            throw new IllegalArgumentException("completedDescriptorGeneration must not be negative");
        }
        RuntimeException failure = closeRetiredWorldTlasesThrough(null, completedDescriptorGeneration);
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Restores predecessor ownership after descriptor/material publication fails.
     *
     * @param update  exact failed candidate
     * @param failure primary publication failure
     */
    public synchronized void rollbackUnboundWorldTlas(WorldTlasUpdate update, Throwable failure) {
        Objects.requireNonNull(update, "update");
        Objects.requireNonNull(failure, "failure");
        if (currentTlas != update.topLevelAccelerationStructure()) {
            return;
        }
        currentTlas = update.previousTopLevelAccelerationStructure();
        latestBuiltViewState = update.previousViewState();
        builtMaterialSnapshot = update.previousMaterialSnapshot();
        builtRevision = update.previousRevision();
        builtSectionRevision = update.previousSectionRevision();
        builtSectionResourceRevision = update.previousSectionResourceRevision();
        builtDynamicRevision = update.previousDynamicRevision();
        builtDynamicTopologyRevision = update.previousDynamicTopologyRevision();
        builtDynamicGeometryRevision = update.previousDynamicGeometryRevision();
        builtDynamicMaterialRevision = update.previousDynamicMaterialRevision();
        builtInstanceTopologyHash = update.previousInstanceTopologyHash();
        builtInstances = update.previousInstanceCount();
        builtTlasInstanceCapacity = update.previousTlasInstanceCapacity();
        builtSectionInstances = update.previousSectionInstanceCount();
        builtDynamicInstances = update.previousDynamicInstanceCount();
        deferredByPendingBacklog = update.previousDeferredByPendingBacklog();
        closeSuppressing(failure, update.topLevelAccelerationStructure());
        publishReadiness();
    }

    /**
     * Recycles an exact unbound candidate without touching descriptor-visible ownership.
     *
     * @param update exact unbound candidate
     * @param reason diagnostic reason
     */
    public synchronized void discardUnboundWorldTlas(WorldTlasUpdate update, String reason) {
        Objects.requireNonNull(update, "update");
        reason = Objects.requireNonNull(reason, "reason");
        if (closed) {
            throw new IllegalStateException("RT world TLAS cache is already closed");
        }
        if (currentTlas != update.topLevelAccelerationStructure()) {
            return;
        }
        currentTlas = update.previousTopLevelAccelerationStructure();
        latestBuiltViewState = update.previousViewState();
        builtMaterialSnapshot = update.previousMaterialSnapshot();
        builtRevision = update.previousRevision();
        builtSectionRevision = update.previousSectionRevision();
        builtSectionResourceRevision = update.previousSectionResourceRevision();
        builtDynamicRevision = update.previousDynamicRevision();
        builtDynamicTopologyRevision = update.previousDynamicTopologyRevision();
        builtDynamicGeometryRevision = update.previousDynamicGeometryRevision();
        builtDynamicMaterialRevision = update.previousDynamicMaterialRevision();
        builtInstanceTopologyHash = update.previousInstanceTopologyHash();
        builtInstances = update.previousInstanceCount();
        builtTlasInstanceCapacity = update.previousTlasInstanceCapacity();
        builtSectionInstances = update.previousSectionInstanceCount();
        builtDynamicInstances = update.previousDynamicInstanceCount();
        deferredByPendingBacklog = update.previousDeferredByPendingBacklog();
        discardedUnboundWorldTlasUpdates++;
        lastUnboundWorldTlasDiscardReason = reason;
        close(update.topLevelAccelerationStructure());
        publishReadiness();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = closePendingWorldTlasBuildCollecting(null);
        if (currentTlas != null) {
            try {
                currentTlas.close();
            } catch (RuntimeException ex) {
                if (failure == null) {
                    failure = ex;
                } else {
                    failure.addSuppressed(ex);
                }
            }
            currentTlas = null;
        }
        failure = destinationPool.closeCollecting(failure);
        builtMaterialSnapshot = RtMaterialState.emptySnapshot();
        dynamicMaterialFacts.clear();
        materialComposer.clear();
        boundDynamicMaterialFacts.clear();
        boundDynamicMaterialComposer.clear();
        clearBoundTerrainPrefix();
        if (failure != null) {
            throw failure;
        }
    }

    private void transitionToEmpty(long revision) {
        closePendingWorldTlasBuild();
        if (currentTlas != null) {
            retireWorldTlas(currentTlas);
            currentTlas = null;
        }
        builtMaterialSnapshot = RtMaterialState.emptySnapshot();
        dynamicMaterialFacts.clear();
        materialComposer.clear();
        boundDynamicMaterialFacts.clear();
        boundDynamicMaterialComposer.clear();
        clearBoundTerrainPrefix();
        builtRevision = revision;
        boundRevision = UNBUILT_REVISION;
        builtSectionRevision = latestObservedSectionRevision;
        builtSectionResourceRevision = latestObservedSectionResourceRevision;
        boundSectionRevision = UNBUILT_REVISION;
        boundSectionResourceRevision = UNBUILT_REVISION;
        builtDynamicRevision = latestObservedDynamicRevision;
        boundDynamicRevision = UNBUILT_REVISION;
        builtDynamicTopologyRevision = latestObservedDynamicTopologyRevision;
        boundDynamicTopologyRevision = UNBUILT_REVISION;
        builtDynamicGeometryRevision = latestObservedDynamicGeometryRevision;
        boundDynamicGeometryRevision = UNBUILT_REVISION;
        builtDynamicMaterialRevision = latestObservedDynamicMaterialRevision;
        boundDynamicMaterialRevision = UNBUILT_REVISION;
        builtInstanceTopologyHash = 0;
        builtInstances = 0;
        builtTlasInstanceCapacity = 0;
        builtSectionInstances = 0;
        builtDynamicInstances = 0;
        boundDynamicLaneInstances = 0;
        deferredByPendingBacklog = false;
        consecutiveEmptyInputs = 0;
        nextCoalescedRebuildNanos = 0L;
        nextStreamingRebuildNanos = 0L;
        emptyTransitions++;
    }

    private void clearBoundTerrainPrefix() {
        boundTerrainPrefix = RtMaterialState.emptySnapshot();
        boundTerrainPrefixMaterialRevision = UNBUILT_REVISION;
        boundTerrainPrefixSectionCount = 0;
        boundTerrainPrefixLayoutHash = 0;
    }

    private boolean shouldDeferStreamingRebuild(RtWorldTlasBuildStats stats) {
        return shouldDeferStreamingRebuild(
                builtRevision,
                builtInstances,
                stats.revision(),
                stats.instances(),
                stats.pendingSectionBuilds(),
                System.nanoTime(),
                nextStreamingRebuildNanos,
                minStreamingRevisionDelta,
                minStreamingInstanceDelta,
                allowBackloggedStreamingRebuilds
        );
    }

    private boolean shouldCoalesceStableUpdate(RtWorldTlasBuildStats stats) {
        return shouldCoalesceStableUpdate(
                currentTlas != null,
                deferredByPendingBacklog,
                minRebuildIntervalNanos,
                stats.sectionRevision(),
                builtSectionRevision,
                stats.dynamicRevision(),
                builtDynamicRevision,
                System.nanoTime(),
                nextCoalescedRebuildNanos
        );
    }

    private RtWorldTlasBuildInput snapshotTlasBuildInput(
            RtSectionTlasBuildInput sectionInput,
            RtDynamicInstanceSnapshot dynamicInput,
            long coverageContractionAuthorizationGeneration
    ) {
        Objects.requireNonNull(sectionInput, "sectionInput");
        Objects.requireNonNull(dynamicInput, "dynamicInput");
        DynamicRenderScene dynamicScene = dynamicInput.dynamicScene();
        if (dynamicScene.revision() != dynamicInput.latestSceneRevision()) {
            throw new IllegalStateException("dynamic TLAS input and scene payload revisions must match");
        }
        /*
         * Terrain owns this TLAS. Dynamic ModelPart instances use their own
         * compact TLAS and only share this material namespace.  Appending them
         * here made every animated cube rewrite the complete terrain instance
         * table on the GPU.
         */
        int activeInstanceCount = sectionInput.instances().size();
        int tlasInstanceCapacity = persistentInstanceCapacity(activeInstanceCount, builtTlasInstanceCapacity);
        List<RtAccelerationStructure.TlasInstance> instances = new ImmutableTlasInstances(
                sectionInput.instances(),
                tlasInstanceCapacity,
                inactiveInstance
        );
        int instanceTopologyHash = instanceTopologyHash(instances);
        RtSceneMaterialTable.Snapshot sectionMaterialSnapshot = sectionInput.materialSnapshot();
        RtSceneMaterialTable.Snapshot materialSnapshot = snapshotMaterialPublication(
                sectionInput,
                dynamicInput
        );

        return new RtWorldTlasBuildInput(
                sectionInput.revision(),
                sectionInput.revision(),
                sectionInput.resourceRevision(),
                sectionInput.materialRevision(),
                sectionInput.viewState(),
                dynamicInput.revision(),
                dynamicInput.topologyRevision(),
                dynamicInput.geometryRevision(),
                dynamicInput.materialRevision(),
                dynamicScene,
                instanceTopologyHash,
                sectionInput.sectionMembership(),
                sectionInput.sectionContentRevisions(),
                sectionInput.sectionCausalities(),
                instances,
                activeInstanceCount,
                sectionInput.instances().size(),
                0,
                sectionMaterialSnapshot.sectionCount(),
                materialSnapshot,
                coverageContractionAuthorizationGeneration,
                sectionInput.pendingSectionBuilds(),
                sectionInput.pendingTriangles(),
                sectionInput.cachedTriangles() + dynamicInput.triangleCount()
        );
    }

    /**
     * Publishes material state without manufacturing a terrain TLAS input table.
     *
     * <p>Material-only catch-up is a descriptor/buffer generation, not an
     * acceleration-structure generation. Keeping this path independent prevents
     * animated textures or dynamic material slots from allocating and padding a
     * complete world instance list that will never be submitted to Vulkan.</p>
     */
    private RtSceneMaterialTable.Snapshot snapshotMaterialPublication(
            RtSectionTlasBuildInput sectionInput,
            RtDynamicInstanceSnapshot dynamicInput
    ) {
        DynamicRenderScene dynamicScene = dynamicInput.dynamicScene();
        if (dynamicScene.revision() != dynamicInput.latestSceneRevision()) {
            throw new IllegalStateException(
                    "dynamic material input and scene payload revisions must match"
            );
        }
        RtSceneMaterialTable.Snapshot sectionMaterialSnapshot = sectionInput.materialSnapshot();
        dynamicMaterialOffset = sectionMaterialSnapshot.sectionCount();
        long materialRevision = combinedRevision(
                sectionMaterialSnapshot.revision(),
                dynamicInput.materialRevision()
        );
        int instanceLayoutHash = combinedSplitLayoutHash(
                sectionMaterialSnapshot.sectionCount(),
                RtDynamicTlasCache.instanceLayoutHash(dynamicInput.instances())
        );
        RtSceneMaterialTable.Snapshot dynamicMaterialSnapshot = dynamicMaterialFacts.submit(
                dynamicInput.materials(),
                dynamicInput.materialDirtySlots(),
                dynamicInput.materialRevision(),
                sectionMaterialSnapshot.sectionCount() == 0 ? instanceLayoutHash : 0
        );
        RtSceneMaterialTable.Snapshot previousWorldMaterialSnapshot = materialComposer.current();
        RtSceneMaterialTable.Snapshot materialSnapshot = materialComposer.compose(
                sectionMaterialSnapshot,
                dynamicMaterialSnapshot,
                materialRevision,
                instanceLayoutHash
        );
        if (!materialSnapshot.signature().equals(previousWorldMaterialSnapshot.signature())) {
            logMaterialSnapshot("worldTlas", materialSnapshot);
        }
        return materialSnapshot;
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

    /**
     * Smoke-only causal ledger: revisions observed in a window are not proof
     * that they caused a Vulkan submission. Record the exact delta against the
     * built world before changing any scheduling policy.
     */
    private void recordSmokeSubmissionReasons(RtWorldTlasBuildInput input, boolean forced) {
        if (!diagnostics.edges().enabled()) {
            return;
        }
        if (input.sectionRevision() != builtSectionRevision) {
            smokeSubmitSectionSceneChanged++;
        }
        if (input.sectionResourceRevision() != builtSectionResourceRevision) {
            smokeSubmitSectionResourceChanged++;
        }
        if (input.dynamicRevision() != builtDynamicRevision) {
            smokeSubmitDynamicRevisionChanged++;
        }
        if (input.dynamicTopologyRevision() != builtDynamicTopologyRevision) {
            smokeSubmitDynamicTopologyChanged++;
        }
        if (input.dynamicGeometryRevision() != builtDynamicGeometryRevision) {
            smokeSubmitDynamicGeometryChanged++;
        }
        if (!input.viewState().equals(builtViewState())) {
            smokeSubmitViewChanged++;
        }
        if (input.instanceTopologyHash() != builtInstanceTopologyHash) {
            smokeSubmitInstanceTopologyChanged++;
        }
        if (forced) {
            smokeSubmitForced++;
        }
    }

    private RendererViewState builtViewState() {
        return currentTlas == null ? RendererViewState.allResident() : latestBuiltViewState;
    }

    private void submitRebuild(
            RtWorldTlasBuildInput input,
            boolean urgent,
            String urgencySource,
            RendererFrameCausality causality
    ) {
        long rebuildStart = System.nanoTime();
        recordSmokeSubmissionReasons(input, urgent);
        boolean currentTlasAvailable = currentTlas != null;
        boolean updateExistingTlas = shouldUpdateWorldTlas(
                currentTlasAvailable,
                builtInstanceTopologyHash,
                input.instanceTopologyHash()
        );
        recordSmokeTopologyDecision(currentTlasAvailable, updateExistingTlas, input);
        RtAccelerationStructure.WorldTlasBuildSubmission submission =
                updateExistingTlas
                        ? RtAccelerationStructure.submitWorldTlasUpdateAsync(
                        device,
                        allocator,
                        commandContext,
                        scratchAlignmentBytes,
                        currentTlas,
                        destinationPool.takeReusableDestination(),
                        input.instances()
                )
                        : RtAccelerationStructure.submitWorldTlasAsync(
                        device,
                        allocator,
                        commandContext,
                        scratchAlignmentBytes,
                        input.instances()
                );

        pendingWorldTlasBuild = new RtPendingWorldTlasBuild(submission, input, causality, urgent);
        if (updateExistingTlas) {
            smokeUpdateSubmissions++;
            smokeLatestSourceHandle = currentTlas.handle();
        } else {
            smokeFullBuildSubmissions++;
            smokeLatestSourceHandle = 0L;
        }
        rebuildPasses++;
        if (urgent) {
            urgentRebuildSubmissions++;
        }
        if (input.hasPendingSectionBuilds()) {
            streamingRebuildPasses++;
        }
        recordRebuildTelemetry(System.nanoTime() - rebuildStart);
        asyncBuildSubmissions++;
        diagnostics.edges().edge(
                "worldTlasSubmitted",
                "revision=" + input.revision()
                        + ", sectionRevision=" + input.sectionRevision()
                        + ", instances=" + input.instances().size()
                        + ", foregroundSections=" + input.sectionKeys().size()
                        + ", pendingSectionBuilds=" + input.pendingSectionBuilds()
                        + ", urgent=" + urgent
                        + ", urgencySource=" + (urgent ? urgencySource : "none")
        );
        deferredByPendingBacklog = input.hasPendingSectionBuilds();
        nextCoalescedRebuildNanos = System.nanoTime() + minRebuildIntervalNanos;
        nextStreamingRebuildNanos = System.nanoTime() + minStreamingRebuildIntervalNanos;
    }

    private WorldTlasUpdate pollPendingWorldTlasBuild(long latestRevision) {
        if (latestRevision < 0L) {
            throw new IllegalArgumentException("latestRevision must not be negative");
        }
        RtPendingWorldTlasBuild pending = pendingWorldTlasBuild;
        if (pending == null) {
            return null;
        }

        RtAccelerationStructure.CompletedWorldTlasBuild completed = pending.completeIfReady();
        if (completed == null) {
            asyncBuildPollsNotReady++;
            diagnostics.edges().edgeOnce(
                    "worldTlasNotReady:" + pending.revision(),
                    "worldTlasPollNotReady",
                    "revision=" + pending.revision()
                            + ", instances=" + pending.instanceCount()
                            + ", pollsNotReady=" + asyncBuildPollsNotReady
            );
            return null;
        }

        pendingWorldTlasBuild = null;
        asyncBuildCompletions++;
        smokeCompletions++;
        if (completed.update()) {
            smokeCompletedUpdates++;
        }
        if (completed.recycledDestination()) {
            destinationPool.recordCompletedDestination(true);
        } else {
            destinationPool.recordCompletedDestination(false);
        }
        smokeTransientInstanceBytes += completed.instanceBufferBytes();
        smokeTransientScratchBytes += completed.scratchBufferBytes();
        smokeLatestSourceHandle = completed.sourceHandle();
        smokeLatestDestinationHandle = completed.accelerationStructure().handle();
        smokeLatestDestinationStorageBytes = completed.accelerationStructure().storageBytes();
        if (completed.sourceHandle() != 0L && completed.sourceHandle() != smokeLatestDestinationHandle) {
            smokeDestinationReplacements++;
        }
        recordAsyncBuildLatencyTelemetry(completed.elapsedNanos());
        diagnostics.edges().edge(
                "worldTlasCompleted",
                "revision=" + pending.revision()
                        + ", instances=" + pending.instanceCount()
                        + ", gpuLatencyMs=" + completed.elapsedNanos() / 1_000_000L
        );
        boolean staleCompletion = completedWorldTlasIsBehindLatestRevision(pending.revision(), latestRevision);
        if (staleCompletion && pending.urgent()) {
            staleWorldTlasCompletionsBoundForDispatch++;
        }

        RtAccelerationStructure previous = currentTlas;
        RendererViewState previousViewState = latestBuiltViewState;
        RtSceneMaterialTable.Snapshot previousMaterialSnapshot = builtMaterialSnapshot;
        long previousRevision = builtRevision;
        long previousSectionRevision = builtSectionRevision;
        long previousSectionResourceRevision = builtSectionResourceRevision;
        long previousDynamicRevision = builtDynamicRevision;
        long previousDynamicTopologyRevision = builtDynamicTopologyRevision;
        long previousDynamicGeometryRevision = builtDynamicGeometryRevision;
        long previousDynamicMaterialRevision = builtDynamicMaterialRevision;
        int previousInstanceTopologyHash = builtInstanceTopologyHash;
        int previousInstanceCount = builtInstances;
        int previousTlasInstanceCapacity = builtTlasInstanceCapacity;
        int previousSectionInstanceCount = builtSectionInstances;
        int previousDynamicInstanceCount = builtDynamicInstances;
        boolean previousDeferredByPendingBacklog = deferredByPendingBacklog;
        try {
            currentTlas = completed.accelerationStructure();
            latestBuiltViewState = pending.viewState();
            builtMaterialSnapshot = pending.materialSnapshot();
            builtRevision = pending.revision();
            builtSectionRevision = pending.sectionRevision();
            builtSectionResourceRevision = pending.sectionResourceRevision();
            builtDynamicRevision = pending.dynamicRevision();
            builtDynamicTopologyRevision = pending.dynamicTopologyRevision();
            builtDynamicGeometryRevision = pending.dynamicGeometryRevision();
            builtDynamicMaterialRevision = pending.dynamicMaterialRevision();
            builtInstanceTopologyHash = pending.instanceTopologyHash();
            builtInstances = pending.instanceCount();
            builtTlasInstanceCapacity = pending.tlasInstanceCapacity();
            builtSectionInstances = pending.sectionInstanceCount();
            builtDynamicInstances = pending.dynamicInstanceCount();
            totalInstancesBuilt += pending.instanceCount();
            deferredByPendingBacklog = pending.submittedWithPendingBacklog();
            WorldTlasUpdate update = new WorldTlasUpdate(
                    currentTlas,
                    builtRevision,
                    builtSectionRevision,
                    builtSectionResourceRevision,
                    pending.sectionMaterialRevision(),
                    pending.viewState(),
                    builtDynamicRevision,
                    builtDynamicTopologyRevision,
                    builtDynamicGeometryRevision,
                    builtDynamicMaterialRevision,
                    pending.dynamicScene(),
                    builtInstanceTopologyHash,
                    pending.sectionMembership(),
                    pending.sectionContentRevisions(),
                    pending.sectionCausalities(),
                    builtInstances,
                    builtTlasInstanceCapacity,
                    builtSectionInstances,
                    builtDynamicInstances,
                    pending.terrainMaterialCount(),
                    pending.materialSnapshot(),
                    pending.causality(),
                    pending.coverageContractionAuthorizationGeneration(),
                    pending.submittedWithPendingBacklog(),
                    previous,
                    previousViewState,
                    previousMaterialSnapshot,
                    previousRevision,
                    previousSectionRevision,
                    previousSectionResourceRevision,
                    previousDynamicRevision,
                    previousDynamicTopologyRevision,
                    previousDynamicGeometryRevision,
                    previousDynamicMaterialRevision,
                    previousInstanceTopologyHash,
                    previousInstanceCount,
                    previousTlasInstanceCapacity,
                    previousSectionInstanceCount,
                    previousDynamicInstanceCount,
                    previousDeferredByPendingBacklog
            );
            if (shouldDiscardStaleWorldTlasCompletion(
                    staleCompletion,
                    pending.urgent(),
                    previous != null
            )) {
                discardUnboundWorldTlas(update, "staleStreamingCompletion");
                return null;
            }
            return update;
        } catch (RuntimeException ex) {
            currentTlas = previous;
            latestBuiltViewState = previousViewState;
            builtMaterialSnapshot = previousMaterialSnapshot;
            builtRevision = previousRevision;
            builtSectionRevision = previousSectionRevision;
            builtSectionResourceRevision = previousSectionResourceRevision;
            builtDynamicRevision = previousDynamicRevision;
            builtDynamicTopologyRevision = previousDynamicTopologyRevision;
            builtDynamicGeometryRevision = previousDynamicGeometryRevision;
            builtDynamicMaterialRevision = previousDynamicMaterialRevision;
            builtInstanceTopologyHash = previousInstanceTopologyHash;
            builtInstances = previousInstanceCount;
            builtTlasInstanceCapacity = previousTlasInstanceCapacity;
            builtSectionInstances = previousSectionInstanceCount;
            builtDynamicInstances = previousDynamicInstanceCount;
            deferredByPendingBacklog = previousDeferredByPendingBacklog;
            completed.accelerationStructure().close();
            throw ex;
        }
    }

    private void closePendingWorldTlasBuild() {
        RuntimeException failure = closePendingWorldTlasBuildCollecting(null);
        if (failure != null) {
            throw failure;
        }
    }

    private RuntimeException closePendingWorldTlasBuildCollecting(RuntimeException failure) {
        RtPendingWorldTlasBuild pending = pendingWorldTlasBuild;
        if (pending == null) {
            return failure;
        }
        pendingWorldTlasBuild = null;
        asyncBuildCloseWaits++;
        try {
            pending.close();
        } catch (RuntimeException ex) {
            if (failure == null) {
                return ex;
            }
            failure.addSuppressed(ex);
        }
        return failure;
    }

    private void recordRebuildTelemetry(long elapsedNanos) {
        long elapsedMillis = elapsedNanos / 1_000_000L;
        lastRebuildMillis = elapsedMillis;
        maxRebuildMillis = Math.max(maxRebuildMillis, elapsedMillis);
        totalRebuildMillis += elapsedMillis;
    }

    private void recordAsyncBuildLatencyTelemetry(long elapsedNanos) {
        long elapsedMillis = elapsedNanos / 1_000_000L;
        lastAsyncBuildLatencyMillis = elapsedMillis;
        maxAsyncBuildLatencyMillis = Math.max(maxAsyncBuildLatencyMillis, elapsedMillis);
        totalAsyncBuildLatencyMillis += elapsedMillis;
    }

    private void emitSmokeAggregateIfDue() {
        if (!diagnostics.builds().enabled()) {
            return;
        }
        long now = System.nanoTime();
        if (smokeWindowStartNanos == 0L) {
            smokeWindowStartNanos = now;
            return;
        }
        if (now - smokeWindowStartNanos < 1_000_000_000L) {
            return;
        }
        diagnostics.builds().aggregate(
                "worldTlas",
                "windowMs=" + (now - smokeWindowStartNanos) / 1_000_000L
                        + ", fullBuildSubmissions=" + smokeFullBuildSubmissions
                        + ", updateSubmissions=" + smokeUpdateSubmissions
                        + ", fullBuildNoCurrentTlas=" + smokeFullBuildNoCurrentTlas
                        + ", fullBuildTopologyChanges=" + smokeFullBuildTopologyChanges
                        + ", topologyStableUpdates=" + smokeTopologyStableUpdates
                        + ", builtTopologyHash=0x" + Integer.toHexString(smokeLastBuiltTopologyHash)
                        + ", candidateTopologyHash=0x" + Integer.toHexString(smokeLastCandidateTopologyHash)
                        + ", builtInstances=" + smokeLastBuiltInstances
                        + ", candidateInstances=" + smokeLastCandidateInstances
                        + ", builtSectionInstances=" + smokeLastBuiltSectionInstances
                        + ", candidateSectionInstances=" + smokeLastCandidateSectionInstances
                        + ", builtDynamicInstances=" + smokeLastBuiltDynamicInstances
                        + ", candidateDynamicInstances=" + smokeLastCandidateDynamicInstances
                        + ", completions=" + smokeCompletions
                        + ", completedUpdates=" + smokeCompletedUpdates
                        + ", destinationReplacements=" + smokeDestinationReplacements
                        + ", reusedDestinationSlots=" + destinationPool.reusedDestinations()
                        + ", newDestinationSlots=" + destinationPool.newDestinations()
                        + ", reusableSlotsAvailable=" + destinationPool.reusableCount()
                        + ", pooledSlots=" + destinationPool.pooledDestinations()
                        + ", poolCapacityReleases=" + destinationPool.poolCapacityReleases()
                        + ", transientInstanceBytes=" + smokeTransientInstanceBytes
                        + ", transientScratchBytes=" + smokeTransientScratchBytes
                        + ", latestSourceHandle=0x" + Long.toHexString(smokeLatestSourceHandle)
                        + ", latestDestinationHandle=0x" + Long.toHexString(smokeLatestDestinationHandle)
                        + ", latestDestinationStorageBytes=" + smokeLatestDestinationStorageBytes
                        + ", pending=" + (pendingWorldTlasBuild != null)
                        + ", retired=" + destinationPool.retiredCount()
                        + ", observedInstances=" + latestObservedInstances
                        + ", observedDynamicInstances=" + latestObservedDynamicInstances
                        + ", emptyInputDeferrals=" + smokeEmptyInputDeferrals
                        + ", pendingSubmissionDeferrals=" + smokePendingSubmissionDeferrals
                        + ", initialFrontGateDeferrals=" + smokeInitialFrontGateDeferrals
                        + ", initialBuildDeferrals=" + smokeInitialBuildDeferrals
                        + ", streamingDeferrals=" + smokeStreamingDeferrals
                        + ", coalescedDeferrals=" + smokeCoalescedDeferrals
                        + ", revisionSources={section=" + smokeSectionRevisionChanges
                        + ", dynamicInstance=" + smokeDynamicInstanceRevisionChanges
                        + ", dynamicTopology=" + smokeDynamicTopologyRevisionChanges
                        + ", dynamicGeometry=" + smokeDynamicGeometryRevisionChanges
                        + ", dynamicScene=" + smokeDynamicSceneRevisionChanges + "}"
                        + ", submissionReasons={sectionScene=" + smokeSubmitSectionSceneChanged
                        + ", sectionResource=" + smokeSubmitSectionResourceChanged
                        + ", dynamicRevision=" + smokeSubmitDynamicRevisionChanged
                        + ", dynamicTopology=" + smokeSubmitDynamicTopologyChanged
                        + ", dynamicGeometry=" + smokeSubmitDynamicGeometryChanged
                        + ", view=" + smokeSubmitViewChanged
                        + ", instanceTopology=" + smokeSubmitInstanceTopologyChanged
                        + ", forced=" + smokeSubmitForced + "}"
        );
        smokeWindowStartNanos = now;
        smokeFullBuildSubmissions = 0L;
        smokeUpdateSubmissions = 0L;
        smokeFullBuildNoCurrentTlas = 0L;
        smokeFullBuildTopologyChanges = 0L;
        smokeTopologyStableUpdates = 0L;
        smokeCompletions = 0L;
        smokeCompletedUpdates = 0L;
        smokeDestinationReplacements = 0L;
        smokeTransientInstanceBytes = 0L;
        smokeTransientScratchBytes = 0L;
        smokeEmptyInputDeferrals = 0L;
        smokePendingSubmissionDeferrals = 0L;
        smokeInitialFrontGateDeferrals = 0L;
        smokeInitialBuildDeferrals = 0L;
        smokeStreamingDeferrals = 0L;
        smokeCoalescedDeferrals = 0L;
        smokeSectionRevisionChanges = 0L;
        smokeDynamicInstanceRevisionChanges = 0L;
        smokeDynamicTopologyRevisionChanges = 0L;
        smokeDynamicGeometryRevisionChanges = 0L;
        smokeDynamicSceneRevisionChanges = 0L;
        smokeSubmitSectionSceneChanged = 0L;
        smokeSubmitSectionResourceChanged = 0L;
        smokeSubmitDynamicRevisionChanged = 0L;
        smokeSubmitDynamicTopologyChanged = 0L;
        smokeSubmitDynamicGeometryChanged = 0L;
        smokeSubmitViewChanged = 0L;
        smokeSubmitInstanceTopologyChanged = 0L;
        smokeSubmitForced = 0L;
    }

    private void recordSmokeEmptyInputDeferral() {
        if (diagnostics.edges().enabled()) {
            smokeEmptyInputDeferrals++;
        }
    }

    /**
     * Records the exact branch that selected BUILD or UPDATE without placing
     * per-rebuild I/O on host's render thread.  A fixed-capacity slot
     * design is only valid if this proves the current variable cardinality is
     * the source of topology churn rather than a hidden address/material hash.
     */
    private void recordSmokeTopologyDecision(
            boolean currentTlasAvailable,
            boolean updateExistingTlas,
            RtWorldTlasBuildInput input
    ) {
        if (!diagnostics.edges().enabled()) {
            return;
        }
        smokeLastBuiltTopologyHash = builtInstanceTopologyHash;
        smokeLastCandidateTopologyHash = input.instanceTopologyHash();
        smokeLastBuiltInstances = builtInstances;
        smokeLastCandidateInstances = input.instances().size();
        smokeLastBuiltSectionInstances = builtSectionInstances;
        smokeLastCandidateSectionInstances = input.sectionInstanceCount();
        smokeLastBuiltDynamicInstances = builtDynamicInstances;
        smokeLastCandidateDynamicInstances = input.dynamicInstanceCount();
        if (updateExistingTlas) {
            smokeTopologyStableUpdates++;
        } else if (!currentTlasAvailable) {
            smokeFullBuildNoCurrentTlas++;
        } else {
            smokeFullBuildTopologyChanges++;
        }
    }

    private void recordSmokePendingSubmissionDeferral() {
        if (diagnostics.edges().enabled()) {
            smokePendingSubmissionDeferrals++;
        }
    }

    private void recordSmokeInitialFrontGateDeferral() {
        if (diagnostics.edges().enabled()) {
            smokeInitialFrontGateDeferrals++;
        }
    }

    private void recordSmokeInitialBuildDeferral() {
        if (diagnostics.edges().enabled()) {
            smokeInitialBuildDeferrals++;
        }
    }

    private void recordSmokeStreamingDeferral() {
        if (diagnostics.edges().enabled()) {
            smokeStreamingDeferrals++;
        }
    }

    private void recordSmokeCoalescedDeferral() {
        if (diagnostics.edges().enabled()) {
            smokeCoalescedDeferrals++;
        }
    }

    private void retireWorldTlas(RtAccelerationStructure accelerationStructure) {
        retireWorldTlas(accelerationStructure, -1L);
    }

    private void retireWorldTlas(
            RtAccelerationStructure accelerationStructure,
            long descriptorGeneration
    ) {
        destinationPool.retire(accelerationStructure, descriptorGeneration);
    }

    private RuntimeException closeRetiredWorldTlasesThrough(
            RuntimeException failure,
            long completedDescriptorGeneration
    ) {
        return destinationPool.releaseThrough(failure, completedDescriptorGeneration, closed);
    }

    /**
     * One immutable, array-backed publication for an actual terrain TLAS build.
     */
    private static final class ImmutableTlasInstances
            extends AbstractList<RtAccelerationStructure.TlasInstance>
            implements RandomAccess, RtImmutableTlasInstances {
        private final RtAccelerationStructure.TlasInstance[] instances;

        private ImmutableTlasInstances(
                List<RtAccelerationStructure.TlasInstance> activeInstances,
                int capacity,
                RtAccelerationStructure.TlasInstance inactiveInstance
        ) {
            Objects.requireNonNull(activeInstances, "activeInstances");
            Objects.requireNonNull(inactiveInstance, "inactiveInstance");
            if (capacity < activeInstances.size()) {
                throw new IllegalArgumentException("TLAS capacity must cover every active instance");
            }
            instances = activeInstances.toArray(
                    new RtAccelerationStructure.TlasInstance[capacity]
            );
            Arrays.fill(instances, activeInstances.size(), capacity, inactiveInstance);
        }

        @Override
        public RtAccelerationStructure.TlasInstance get(int index) {
            return instances[index];
        }

        @Override
        public int size() {
            return instances.length;
        }
    }

    /**
     * Completed coherent world generation awaiting descriptor/material transaction ownership.
     *
     * @param topLevelAccelerationStructure              candidate TLAS owner
     * @param revision                                   combined world revision
     * @param sectionRevision                            logical section revision
     * @param sectionResourceRevision                    section BLAS resource revision
     * @param sectionMaterialRevision                    section material revision
     * @param viewState                                  candidate renderer view
     * @param dynamicRevision                            dynamic publication revision
     * @param dynamicTopologyRevision                    dynamic topology revision
     * @param dynamicGeometryRevision                    dynamic geometry revision
     * @param dynamicMaterialRevision                    dynamic material revision
     * @param dynamicScene                               candidate dynamic scene
     * @param instanceTopologyHash                       combined instance topology token
     * @param sectionKeys                                exact section membership
     * @param sectionContentRevisions                    exact section content revisions
     * @param sectionCausalities                         exact section causality identities
     * @param instanceCount                              combined active instance count
     * @param tlasInstanceCapacity                       physical TLAS capacity
     * @param sectionInstanceCount                       active terrain instance count
     * @param dynamicInstanceCount                       active dynamic instance count
     * @param terrainMaterialCount                       material prefix length
     * @param materialSnapshot                           candidate combined material table
     * @param causality                                  candidate capture identity
     * @param coverageContractionAuthorizationGeneration authorization generation
     * @param submittedWithPendingBacklog                whether submission observed section backlog
     * @param previousTopLevelAccelerationStructure      descriptor-visible predecessor, or {@code null}
     * @param previousViewState                          predecessor view
     * @param previousMaterialSnapshot                   predecessor material table
     * @param previousRevision                           predecessor world revision
     * @param previousSectionRevision                    predecessor logical section revision
     * @param previousSectionResourceRevision            predecessor BLAS resource revision
     * @param previousDynamicRevision                    predecessor dynamic revision
     * @param previousDynamicTopologyRevision            predecessor topology revision
     * @param previousDynamicGeometryRevision            predecessor geometry revision
     * @param previousDynamicMaterialRevision            predecessor material revision
     * @param previousInstanceTopologyHash               predecessor topology token
     * @param previousInstanceCount                      predecessor combined instance count
     * @param previousTlasInstanceCapacity               predecessor physical capacity
     * @param previousSectionInstanceCount               predecessor terrain instance count
     * @param previousDynamicInstanceCount               predecessor dynamic instance count
     * @param previousDeferredByPendingBacklog           predecessor scheduling state
     */
    public record WorldTlasUpdate(
            RtAccelerationStructure topLevelAccelerationStructure,
            long revision,
            long sectionRevision,
            long sectionResourceRevision,
            long sectionMaterialRevision,
            RendererViewState viewState,
            long dynamicRevision,
            long dynamicTopologyRevision,
            long dynamicGeometryRevision,
            long dynamicMaterialRevision,
            DynamicRenderScene dynamicScene,
            int instanceTopologyHash,
            PackedSectionMembership sectionKeys,
            SectionRevisionSnapshot sectionContentRevisions,
            SectionCausalitySnapshot sectionCausalities,
            int instanceCount,
            int tlasInstanceCapacity,
            int sectionInstanceCount,
            int dynamicInstanceCount,
            int terrainMaterialCount,
            RtSceneMaterialTable.Snapshot materialSnapshot,
            RendererFrameCausality causality,
            long coverageContractionAuthorizationGeneration,
            boolean submittedWithPendingBacklog,
            RtAccelerationStructure previousTopLevelAccelerationStructure,
            RendererViewState previousViewState,
            RtSceneMaterialTable.Snapshot previousMaterialSnapshot,
            long previousRevision,
            long previousSectionRevision,
            long previousSectionResourceRevision,
            long previousDynamicRevision,
            long previousDynamicTopologyRevision,
            long previousDynamicGeometryRevision,
            long previousDynamicMaterialRevision,
            int previousInstanceTopologyHash,
            int previousInstanceCount,
            int previousTlasInstanceCapacity,
            int previousSectionInstanceCount,
            int previousDynamicInstanceCount,
            boolean previousDeferredByPendingBacklog
    ) {
        /**
         * Validates native ownership, revision monotonicity, and lane/material cardinality.
         */
        public WorldTlasUpdate {
            topLevelAccelerationStructure = Objects.requireNonNull(
                    topLevelAccelerationStructure,
                    "topLevelAccelerationStructure"
            );
            materialSnapshot = Objects.requireNonNull(materialSnapshot, "materialSnapshot");
            causality = Objects.requireNonNull(causality, "causality");
            dynamicScene = Objects.requireNonNull(dynamicScene, "dynamicScene");
            viewState = Objects.requireNonNull(viewState, "viewState");
            previousViewState = Objects.requireNonNull(previousViewState, "previousViewState");
            sectionContentRevisions = Objects.requireNonNull(
                    sectionContentRevisions,
                    "sectionContentRevisions"
            );
            sectionCausalities = Objects.requireNonNull(sectionCausalities, "sectionCausalities");
            sectionKeys = Objects.requireNonNull(sectionKeys, "sectionKeys");
            if (sectionContentRevisions.membership() != sectionKeys) {
                throw new IllegalArgumentException(
                        "world TLAS section revisions must retain the exact section membership publication"
                );
            }
            if (sectionCausalities.membership() != sectionKeys) {
                throw new IllegalArgumentException(
                        "pending TLAS section causalities must retain the exact section membership publication"
                );
            }
            previousMaterialSnapshot = Objects.requireNonNull(previousMaterialSnapshot, "previousMaterialSnapshot");
            if (revision < 0L) {
                throw new IllegalArgumentException("revision must not be negative");
            }
            if (coverageContractionAuthorizationGeneration < 0L) {
                throw new IllegalArgumentException("coverage contraction authorization generation must not be negative");
            }
            if (sectionRevision < 0L || sectionResourceRevision < 0L || sectionMaterialRevision < 0L
                    || dynamicRevision < 0L
                    || dynamicTopologyRevision < 0L
                    || dynamicGeometryRevision < 0L || dynamicMaterialRevision < 0L) {
                throw new IllegalArgumentException("world TLAS stream revisions must not be negative");
            }
            if (instanceCount <= 0 || tlasInstanceCapacity < instanceCount) {
                throw new IllegalArgumentException("world TLAS update instance count must be positive");
            }
            if (sectionInstanceCount < 0 || dynamicInstanceCount < 0) {
                throw new IllegalArgumentException("world TLAS update split instance counts must not be negative");
            }
            if ((long) sectionInstanceCount + dynamicInstanceCount != instanceCount) {
                throw new IllegalArgumentException("world TLAS update instance count must equal section plus dynamic instances");
            }
            if (terrainMaterialCount < sectionInstanceCount
                    || terrainMaterialCount > RtDynamicBlasCache.DYNAMIC_MATERIAL_INDEX_BIT
                    || terrainMaterialCount > materialSnapshot.sectionCount()) {
                throw new IllegalArgumentException("world TLAS terrain material count does not cover terrain instances");
            }
            if (materialSnapshot.sectionCount() <= 0) {
                throw new IllegalArgumentException("world TLAS material snapshot must not be empty");
            }
            if (materialSnapshot.sectionCount() < instanceCount) {
                throw new IllegalArgumentException("world TLAS material slots must cover active instances");
            }
            if (previousRevision < UNBUILT_REVISION) {
                throw new IllegalArgumentException("previousRevision must be -1 or greater");
            }
            if (previousSectionRevision < UNBUILT_REVISION
                    || previousSectionResourceRevision < UNBUILT_REVISION
                    || previousDynamicRevision < UNBUILT_REVISION
                    || previousDynamicTopologyRevision < UNBUILT_REVISION
                    || previousDynamicGeometryRevision < UNBUILT_REVISION
                    || previousDynamicMaterialRevision < UNBUILT_REVISION) {
                throw new IllegalArgumentException("previous stream revisions must be -1 or greater");
            }
            if (previousInstanceCount < 0 || previousTlasInstanceCapacity < previousInstanceCount) {
                throw new IllegalArgumentException("previousInstanceCount must not be negative");
            }
            if (previousSectionInstanceCount < 0 || previousDynamicInstanceCount < 0) {
                throw new IllegalArgumentException("previous split instance counts must not be negative");
            }
            if ((long) previousSectionInstanceCount + previousDynamicInstanceCount != previousInstanceCount) {
                throw new IllegalArgumentException(
                        "previous terrain TLAS split is inconsistent: revision=" + previousRevision
                                + ", terrainInstances=" + previousInstanceCount
                                + ", sectionInstances=" + previousSectionInstanceCount
                                + ", terrainDynamicInstances=" + previousDynamicInstanceCount
                                + ", expected=" + ((long) previousSectionInstanceCount + previousDynamicInstanceCount)
                );
            }
        }
    }
}
