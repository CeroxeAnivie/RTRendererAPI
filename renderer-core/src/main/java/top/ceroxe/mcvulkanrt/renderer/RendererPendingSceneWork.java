package top.ceroxe.mcvulkanrt.renderer;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import top.ceroxe.mcvulkanrt.renderer.orchestration.takeover.RtForegroundAdmissionFlightRecorder;
import top.ceroxe.mcvulkanrt.renderer.diagnostics.RtSceneCausalityRecorder;
import top.ceroxe.mcvulkanrt.renderer.scene.ChunkKey;
import top.ceroxe.mcvulkanrt.renderer.scene.SceneDatabase;
import top.ceroxe.mcvulkanrt.renderer.scene.PackedSectionMembership;
import top.ceroxe.mcvulkanrt.renderer.scene.SceneUpdateBatch;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionKey;
import top.ceroxe.mcvulkanrt.renderer.scene.SectionVoxelSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Owns pending scene state and the bounded drain policy for renderer updates.
 *
 * <p>The outer update loop only schedules immutable batches; this owner keeps
 * coalescing, source resolution, camera priority, and fairness decisions together.</p>
 */
class RendererPendingSceneWork {
    private static final int CAMERA_PRIORITY_FAIR_DRAIN_INTERVAL = 8;
    private static final int CAMERA_PRIORITY_FAIR_QUOTA_DIVISOR = 8;

    private static final long STREAMING_NEIGHBOR_QUIET_NANOS = 32_000_000L;
    private static final long STREAMING_NEIGHBOR_MAX_DEFER_NANOS = 250_000_000L;

    private final SectionSnapshotLookup snapshotLookup;
    private final LongSupplier nanoClock;
    private final LinkedHashMap<SectionKey, PendingSnapshot> pendingSnapshots = new LinkedHashMap<>();
    /*
     * A drained snapshot is not necessarily accepted by the CPU mesh lane: downstream capacity
     * can reject it synchronously and ask this owner to requeue it. Retain only that bounded
     * drain's scheduling identity until the caller makes its decision. This is deliberately not a
     * second payload queue; scene-backed production entries retain no voxel snapshot here.
     */
    private final LinkedHashMap<SectionKey, PendingSnapshot> drainedAwaitingDisposition =
            new LinkedHashMap<>();
    private final LinkedHashMap<SectionKey, Integer> pendingRemovedSections = new LinkedHashMap<>();
    private final LinkedHashMap<ChunkKey, Integer> pendingUnloadedChunks = new LinkedHashMap<>();
    private long metadataRevision;
    private long nextPendingSequence = 1L;
    private long cameraPriorityDrainFrames;
    private boolean pendingFullResync;
    private int pendingFullResyncSourceFlags;
    private long totalSectionDirtyMarks;
    private long totalBlockMutationMarks;
    private long totalChunkPacketReplacementMarks;
    private long totalChunkSnapshotReplacements;
    private long totalChunkUnloadMarks;
    private long totalSectionSnapshotRemovals;
    private long totalFullResyncRequests;

    RendererPendingSceneWork() {
        this(null, System::nanoTime);
    }

    RendererPendingSceneWork(SceneDatabase sceneDatabase) {
        this(Objects.requireNonNull(sceneDatabase, "sceneDatabase")::snapshotResidentSection);
    }

    private RendererPendingSceneWork(SectionSnapshotLookup snapshotLookup) {
        this(snapshotLookup, System::nanoTime);
    }

    RendererPendingSceneWork(LongSupplier nanoClock) {
        this(null, nanoClock);
    }

    private RendererPendingSceneWork(SectionSnapshotLookup snapshotLookup, LongSupplier nanoClock) {
        this.snapshotLookup = snapshotLookup;
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    static RendererPendingSceneWork sceneBacked(SceneDatabase sceneDatabase) {
        Objects.requireNonNull(sceneDatabase, "sceneDatabase");
        return new RendererPendingSceneWork(sceneDatabase::snapshotResidentSection);
    }

    void ingest(SceneUpdateBatch batch) {
        Objects.requireNonNull(batch, "batch");
        captureTotals(batch);
        if (!batch.hasChanges()) {
            return;
        }
        /*
         * Every pending lane participates in the metadata cache key.  In
         * particular, an unload-only batch changes the terrain ownership
         * contract even when it contains no section snapshot, and a full
         * resync is observable even when the old queues are already empty.
         * Omitting either lane lets RendererUpdateLoop reuse a stale pending
         * terrain publication and delays removal/recovery until an unrelated
         * section update arrives.
         */
        boolean metadataMutated = !batch.sectionSnapshots().isEmpty()
                || !batch.removedSections().isEmpty()
                || !batch.unloadedChunks().isEmpty()
                || batch.fullResyncRequested();
        if (batch.fullResyncRequested()) {
            pendingSnapshots.clear();
            drainedAwaitingDisposition.clear();
            pendingRemovedSections.clear();
            pendingUnloadedChunks.clear();
            pendingFullResync = true;
            pendingFullResyncSourceFlags = SceneUpdateBatch.sourceFlagsForFullResync(batch.batchSourceFlags());
        }

        for (SectionKey key : batch.removedSections()) {
            pendingSnapshots.remove(key);
            drainedAwaitingDisposition.remove(key);
            pendingRemovedSections.merge(
                    key,
                    SceneUpdateBatch.sourceFlagsForRemoval(batch.sourceFlagsForSection(key)),
                    (left, right) -> left | right
            );
        }
        for (ChunkKey key : batch.unloadedChunks()) {
            pendingUnloadedChunks.merge(key, batch.batchSourceFlags(), (left, right) -> left | right);
        }

        long updateNanos = batch.sectionSnapshots().isEmpty() ? 0L : nanoClock.getAsLong();
        for (SectionVoxelSnapshot snapshot : batch.sectionSnapshots().values()) {
            pendingRemovedSections.remove(snapshot.key());
            PendingSnapshot previous = pendingSnapshots.get(snapshot.key());
            long sequence = previous == null ? nextPendingSequence() : previous.sequence();
            int sourceFlags = batch.batchSourceFlags();
            int sectionSourceFlags = batch.sourceFlagsForSection(snapshot.key());
            if (sectionSourceFlags != 0) {
                sourceFlags = sectionSourceFlags;
            }
            if (previous != null) {
                sourceFlags |= previous.sourceFlags();
            }
            pendingSnapshots.put(
                    snapshot.key(),
                    new PendingSnapshot(
                            snapshotLookup == null ? snapshot : null,
                            sequence,
                            sourceFlags,
                            previous == null ? updateNanos : previous.firstQueuedNanos(),
                            updateNanos
                    )
            );
            if (RtForegroundAdmissionFlightRecorder.acceptsSection(snapshot.key())) {
                RtForegroundAdmissionFlightRecorder.recordSection(
                        snapshot.key(),
                        "pendingSceneIngest",
                        "sequence=" + sequence + ", sourceFlags=0x" + Integer.toHexString(sourceFlags)
                                + ", pending=" + pendingSnapshots.size()
                );
            }
        }
        if (metadataMutated) {
            advanceMetadataRevision();
        }
    }

    SceneUpdateBatch drainFrame(int maxSectionSnapshots, RendererFrameState frameState) {
        return drainFrame(maxSectionSnapshots, frameState, Set.of());
    }

    SceneUpdateBatch drainFrame(
            int maxSectionSnapshots,
            RendererFrameState frameState,
            Set<SectionKey> foregroundKeys
    ) {
        return drainFrame(maxSectionSnapshots, frameState, foregroundKeys, false, false);
    }

    SceneUpdateBatch drainFrame(
            int maxSectionSnapshots,
            RendererFrameState frameState,
            Set<SectionKey> foregroundKeys,
            boolean directFirst,
            boolean directBuildInFlight
    ) {
        return drainFrame(
                maxSectionSnapshots,
                frameState,
                foregroundKeys,
                directFirst,
                directBuildInFlight,
                directFirst
        );
    }

    SceneUpdateBatch drainFrame(
            int maxSectionSnapshots,
            RendererFrameState frameState,
            Set<SectionKey> foregroundKeys,
            boolean directFirst,
            boolean directBuildInFlight,
            boolean includeBackgroundDirect
    ) {
        return drainFrame(
                maxSectionSnapshots,
                frameState,
                foregroundKeys,
                directFirst,
                directBuildInFlight,
                includeBackgroundDirect,
                Set.of(),
                Set.of()
        );
    }

    SceneUpdateBatch drainFrame(
            int maxSectionSnapshots,
            RendererFrameState frameState,
            Set<SectionKey> foregroundKeys,
            boolean directFirst,
            boolean directBuildInFlight,
            boolean includeBackgroundDirect,
            Set<ChunkKey> unsettledStreamingChunks,
            Set<SectionKey> directBuildKeysInFlight
    ) {
        if (maxSectionSnapshots < 0) {
            throw new IllegalArgumentException("maxSectionSnapshots must not be negative");
        }
        Objects.requireNonNull(foregroundKeys, "foregroundKeys");
        Objects.requireNonNull(unsettledStreamingChunks, "unsettledStreamingChunks");
        Objects.requireNonNull(directBuildKeysInFlight, "directBuildKeysInFlight");

        /*
         * The previous drain has now been accepted except for keys explicitly returned through
         * requeueFront(). Dropping those terminal identities here bounds retention to one caller
         * transaction and makes a forgotten acknowledgement fail as ordinary work completion,
         * never as a permanent shadow queue.
         */
        drainedAwaitingDisposition.clear();

        Map<SectionKey, SectionVoxelSnapshot> selectedSnapshots = new LinkedHashMap<>();
        Map<SectionKey, Integer> selectedSectionSourceFlags = new LinkedHashMap<>();
        int selectedSourceFlags = 0;
        List<SectionKey> selectedKeys = selectPendingSnapshotKeys(
                maxSectionSnapshots,
                frameState,
                foregroundKeys,
                directFirst,
                directBuildInFlight,
                includeBackgroundDirect,
                unsettledStreamingChunks,
                directBuildKeysInFlight
        );
        if (RtSceneCausalityRecorder.enabled()) {
            Set<SectionKey> selectedKeySet = Set.copyOf(selectedKeys);
            for (SectionKey foregroundKey : foregroundKeys) {
                PendingSnapshot pending = pendingSnapshots.get(foregroundKey);
                if (pending != null && !selectedKeySet.contains(foregroundKey)) {
                    RtSceneCausalityRecorder.recordSection(
                            RtSceneCausalityRecorder.PENDING_DEFERRED,
                            foregroundKey,
                            -1L,
                            pending.sequence(),
                            maxSectionSnapshots,
                            pending.sourceFlags()
                    );
                    if (RtForegroundAdmissionFlightRecorder.acceptsSection(foregroundKey)) {
                        RtForegroundAdmissionFlightRecorder.recordSection(
                                foregroundKey,
                                "pendingSceneDeferred",
                                "reason=sectionBudget, budget=" + maxSectionSnapshots
                                        + ", pending=" + pendingSnapshots.size()
                                        + ", sourceFlags=0x" + Integer.toHexString(pending.sourceFlags())
                        );
                    }
                }
            }
        }
        for (SectionKey key : selectedKeys) {
            PendingSnapshot pending = pendingSnapshots.remove(key);
            if (pending != null) {
                drainedAwaitingDisposition.put(key, pending);
                RtSceneCausalityRecorder.recordSection(
                        RtSceneCausalityRecorder.PENDING_SELECTED,
                        key,
                        -1L,
                        pending.sequence(),
                        maxSectionSnapshots,
                        pending.sourceFlags()
                );
                if (RtForegroundAdmissionFlightRecorder.acceptsSection(key)) {
                    RtForegroundAdmissionFlightRecorder.recordSection(
                            key,
                            "pendingSceneSelected",
                            "budget=" + maxSectionSnapshots
                                    + ", pendingBeforeResolve=" + (pendingSnapshots.size() + 1)
                                    + ", sourceFlags=0x" + Integer.toHexString(pending.sourceFlags())
                    );
                }
                SectionVoxelSnapshot snapshot = pending.resolve(key, snapshotLookup);
                if (snapshot == null) {
                    RtSceneCausalityRecorder.recordSection(
                            RtSceneCausalityRecorder.PENDING_REJECTED,
                            key,
                            -1L,
                            pending.sequence(),
                            0L,
                            pending.sourceFlags()
                    );
                    if (RtForegroundAdmissionFlightRecorder.acceptsSection(key)) {
                        RtForegroundAdmissionFlightRecorder.recordSection(
                                key, "pendingSceneResolveRejected", "reason=residentSnapshotMissing"
                        );
                    }
                    continue;
                }
                if (RtForegroundAdmissionFlightRecorder.acceptsSection(key)) {
                    RtForegroundAdmissionFlightRecorder.recordSection(
                            key, "pendingSceneResolved", "result=available"
                    );
                }
                selectedSnapshots.put(key, snapshot);
                selectedSourceFlags |= pending.sourceFlags();
                selectedSectionSourceFlags.put(key, pending.sourceFlags());
            }
        }

        Set<SectionKey> removedSections = Set.copyOf(pendingRemovedSections.keySet());
        for (Map.Entry<SectionKey, Integer> entry : pendingRemovedSections.entrySet()) {
            selectedSourceFlags |= entry.getValue();
            selectedSectionSourceFlags.put(entry.getKey(), entry.getValue());
        }
        pendingRemovedSections.clear();
        Set<ChunkKey> unloadedChunks = Set.copyOf(pendingUnloadedChunks.keySet());
        for (int sourceFlags : pendingUnloadedChunks.values()) {
            selectedSourceFlags |= sourceFlags;
        }
        pendingUnloadedChunks.clear();
        boolean fullResyncRequested = pendingFullResync;
        pendingFullResync = false;
        if (fullResyncRequested) {
            selectedSourceFlags |= pendingFullResyncSourceFlags;
            pendingFullResyncSourceFlags = 0;
        }
        boolean metadataMutated = !selectedKeys.isEmpty()
                || !removedSections.isEmpty()
                || !unloadedChunks.isEmpty()
                || fullResyncRequested;
        if (metadataMutated) {
            advanceMetadataRevision();
        }

        Set<SectionKey> dirtySections = new HashSet<>(selectedSnapshots.keySet());
        Set<ChunkKey> dirtyChunks = new HashSet<>();
        for (SectionKey key : dirtySections) {
            dirtyChunks.add(key.chunkKey());
        }

        return new SceneUpdateBatch(
                dirtySections,
                dirtyChunks,
                removedSections,
                unloadedChunks,
                selectedSnapshots,
                fullResyncRequested,
                totalSectionDirtyMarks,
                totalBlockMutationMarks,
                totalChunkPacketReplacementMarks,
                totalChunkSnapshotReplacements,
                totalChunkUnloadMarks,
                totalSectionSnapshotRemovals,
                totalFullResyncRequests,
                selectedSourceFlags,
                selectedSectionSourceFlags
        );
    }

    boolean hasPendingWork() {
        return pendingFullResync
                || !pendingSnapshots.isEmpty()
                || !pendingRemovedSections.isEmpty()
                || !pendingUnloadedChunks.isEmpty();
    }

    int pendingSectionSnapshots() {
        return pendingSnapshots.size();
    }

    /** Appends scheduling identities without materializing another pending-key collection. */
    void appendPendingSectionKeys(PackedSectionMembership.Builder target) {
        Objects.requireNonNull(target, "target");
        for (SectionKey key : pendingSnapshots.keySet()) {
            target.add(key);
        }
        for (SectionKey key : drainedAwaitingDisposition.keySet()) {
            target.add(key);
        }
    }

    int pendingRemovedSections() {
        return pendingRemovedSections.size();
    }

    void requeueFront(List<SectionVoxelSnapshot> snapshots, int sourceFlags) {
        requeueFront(snapshots, key -> sourceFlags);
    }

    void requeueFront(List<SectionVoxelSnapshot> snapshots, SceneUpdateBatch sourceBatch) {
        Objects.requireNonNull(sourceBatch, "sourceBatch");
        requeueFront(snapshots, sourceBatch::sourceFlagsForSection);
    }

    private void requeueFront(List<SectionVoxelSnapshot> snapshots, SectionSourceLookup sourceLookup) {
        Objects.requireNonNull(snapshots, "snapshots");
        if (snapshots.isEmpty()) {
            return;
        }
        LinkedHashMap<SectionKey, SectionVoxelSnapshot> requeued = new LinkedHashMap<>(snapshots.size());
        for (SectionVoxelSnapshot snapshot : snapshots) {
            Objects.requireNonNull(snapshot, "snapshot");
            requeued.put(snapshot.key(), snapshot);
        }
        long updateNanos = nanoClock.getAsLong();
        List<Map.Entry<SectionKey, SectionVoxelSnapshot>> ordered = new ArrayList<>(requeued.entrySet());
        /* putFirst reverses insertion order, so replay the bounded retry batch from tail to head. */
        for (int index = ordered.size() - 1; index >= 0; index--) {
            Map.Entry<SectionKey, SectionVoxelSnapshot> entry = ordered.get(index);
            PendingSnapshot drained = drainedAwaitingDisposition.remove(entry.getKey());
            PendingSnapshot previous = pendingSnapshots.get(entry.getKey());
            int mergedSourceFlags = sourceLookup.sourceFlagsForSection(entry.getKey());
            if (drained != null) {
                mergedSourceFlags |= drained.sourceFlags();
            }
            if (previous != null) {
                mergedSourceFlags |= previous.sourceFlags();
            }
            long sequence = drained != null && previous != null
                    ? Math.min(drained.sequence(), previous.sequence())
                    : drained != null ? drained.sequence()
                    : previous != null ? previous.sequence() : nextPendingSequence();
            long firstQueuedNanos = drained != null && previous != null
                    ? Math.min(drained.firstQueuedNanos(), previous.firstQueuedNanos())
                    : drained != null ? drained.firstQueuedNanos()
                    : previous != null ? previous.firstQueuedNanos() : updateNanos;
            /*
             * A newer ingress may arrive after drain but before downstream backpressure returns
             * the older ticket. Preserve the oldest fairness age, but retain the newest mutation
             * time and payload so neighborhood quieting cannot publish stale block/lighting data.
             */
            long lastUpdatedNanos = previous == null
                    ? drained == null ? updateNanos : drained.lastUpdatedNanos()
                    : drained == null
                    ? previous.lastUpdatedNanos()
                    : Math.max(drained.lastUpdatedNanos(), previous.lastUpdatedNanos());
            /*
             * Only a pending value that appeared while a drained generation was
             * awaiting disposition is provably newer than the returned retry.
             * Without that drained identity, requeueFront itself is the latest
             * authoritative payload and must replace the pending value.
             */
            SectionVoxelSnapshot retrySnapshot = snapshotLookup == null
                    && drained != null
                    && previous != null
                    ? previous.snapshot()
                    : entry.getValue();
            pendingSnapshots.putFirst(
                    entry.getKey(),
                    new PendingSnapshot(
                            snapshotLookup == null ? retrySnapshot : null,
                            sequence,
                            mergedSourceFlags,
                            firstQueuedNanos,
                            lastUpdatedNanos
                    )
            );
        }
        advanceMetadataRevision();
    }

    void clear() {
        boolean metadataMutated = pendingFullResync
                || !pendingSnapshots.isEmpty()
                || !pendingRemovedSections.isEmpty()
                || !pendingUnloadedChunks.isEmpty();
        pendingSnapshots.clear();
        drainedAwaitingDisposition.clear();
        pendingRemovedSections.clear();
        pendingUnloadedChunks.clear();
        pendingFullResync = false;
        pendingFullResyncSourceFlags = 0;
        nextPendingSequence = 1L;
        cameraPriorityDrainFrames = 0L;
        if (metadataMutated) {
            advanceMetadataRevision();
        }
    }

    long metadataRevision() {
        return metadataRevision;
    }

    Set<SectionKey> snapshotPendingSectionKeys() {
        Set<SectionKey> pendingKeys = new HashSet<>(pendingSnapshots.keySet());
        pendingKeys.addAll(pendingRemovedSections.keySet());
        return Set.copyOf(pendingKeys);
    }

    Map<SectionKey, Integer> snapshotPendingSectionSourceFlags() {
        Map<SectionKey, Integer> pending = new HashMap<>();
        for (Map.Entry<SectionKey, PendingSnapshot> entry : pendingSnapshots.entrySet()) {
            pending.put(entry.getKey(), entry.getValue().sourceFlags());
        }
        for (Map.Entry<SectionKey, Integer> entry : pendingRemovedSections.entrySet()) {
            pending.merge(entry.getKey(), entry.getValue(), (left, right) -> left | right);
        }
        return Map.copyOf(pending);
    }

    /** Scalar provenance lookup for bounded completion batches; never copies the full backlog. */
    int sourceFlagsForSection(SectionKey key) {
        Objects.requireNonNull(key, "key");
        PendingSnapshot snapshot = pendingSnapshots.get(key);
        int sourceFlags = snapshot == null ? 0 : snapshot.sourceFlags();
        Integer removalFlags = pendingRemovedSections.get(key);
        return removalFlags == null ? sourceFlags : sourceFlags | removalFlags;
    }

    private void advanceMetadataRevision() {
        metadataRevision = nextMetadataRevision(metadataRevision, "pending scene queue");
    }

    private void captureTotals(SceneUpdateBatch batch) {
        totalSectionDirtyMarks = batch.totalSectionDirtyMarks();
        totalBlockMutationMarks = batch.totalBlockMutationMarks();
        totalChunkPacketReplacementMarks = batch.totalChunkPacketReplacementMarks();
        totalChunkSnapshotReplacements = batch.totalChunkSnapshotReplacements();
        totalChunkUnloadMarks = batch.totalChunkUnloadMarks();
        totalSectionSnapshotRemovals = batch.totalSectionSnapshotRemovals();
        totalFullResyncRequests = batch.totalFullResyncRequests();
    }

    private List<SectionKey> selectPendingSnapshotKeys(
            int maxSectionSnapshots,
            RendererFrameState frameState,
            Set<SectionKey> foregroundKeys,
            boolean directFirst,
            boolean directBuildInFlight,
            boolean includeBackgroundDirect,
            Set<ChunkKey> unsettledStreamingChunks,
            Set<SectionKey> directBuildKeysInFlight
    ) {
        int selectedCount = Math.min(maxSectionSnapshots, pendingSnapshots.size());
        if (selectedCount <= 0) {
            return List.of();
        }
        long selectionNanos = nanoClock.getAsLong();
        StreamingDependencyIndex streamingDependencies = StreamingDependencyIndex.capture(
                unsettledStreamingChunks,
                directBuildKeysInFlight,
                pendingSnapshots
        );

        /*
         * The initial RT front is a transaction over the host-published
         * frustum. Camera distance alone lets already-loaded background
         * sections consume the bounded ingestion slots while a newly visible
         * uncompiled neighbor waits until after the loading screen closes.
         * Drain these authoritative keys first; FIFO fairness still applies
         * once the finite foreground transaction is complete.
         */
        LinkedHashSet<SectionKey> selected = new LinkedHashSet<>(selectedCount);
        if (directFirst) {
            addPendingDirectSnapshotKeys(selected, foregroundKeys, selectedCount);
            if (includeBackgroundDirect) {
                addPendingDirectSnapshotKeys(selected, pendingSnapshots.keySet(), selectedCount);
            }
            if (!selected.isEmpty() || directBuildInFlight) {
                return new ArrayList<>(selected);
            }
        }
        if (!foregroundKeys.isEmpty()) {
            for (SectionKey key : foregroundKeys) {
                if (selected.size() >= selectedCount) {
                    break;
                }
                PendingSnapshot pending = pendingSnapshots.get(key);
                if (pending != null && !deferStreamingNeighbor(
                        key,
                        pending,
                        streamingDependencies,
                        selectionNanos
                )) {
                    selected.add(key);
                }
            }
            if (selected.size() >= selectedCount) {
                return new ArrayList<>(selected);
            }
        }

        RendererFrameState effectiveFrameState = frameState == null ? RendererFrameState.unavailable() : frameState;
        if (!effectiveFrameState.valid() || pendingSnapshots.size() <= selectedCount) {
            addOldestPendingSnapshotKeys(
                    selected,
                    selectedCount,
                    streamingDependencies,
                    selectionNanos
            );
            return new ArrayList<>(selected);
        }

        cameraPriorityDrainFrames++;
        // Keep a small FIFO escape hatch so distant terrain still converges while the camera keeps moving.
        addOldestPendingSnapshotKeys(
                selected,
                fairDrainSlots(selectedCount),
                streamingDependencies,
                selectionNanos
        );
        if (selected.size() >= selectedCount) {
            return new ArrayList<>(selected);
        }

        int remainingSlots = selectedCount - selected.size();
        Comparator<Map.Entry<SectionKey, PendingSnapshot>> priority =
                cameraPriorityComparator(effectiveFrameState);
        Comparator<Map.Entry<SectionKey, PendingSnapshot>> worstFirst = priority.reversed();
        PriorityQueue<Map.Entry<SectionKey, PendingSnapshot>> prioritized =
                new PriorityQueue<>(Math.max(1, remainingSlots), worstFirst);
        for (Map.Entry<SectionKey, PendingSnapshot> entry : pendingSnapshots.entrySet()) {
            if (!selected.contains(entry.getKey()) && !deferStreamingNeighbor(
                    entry.getKey(),
                    entry.getValue(),
                    streamingDependencies,
                    selectionNanos
            )) {
                if (prioritized.size() < remainingSlots) {
                    prioritized.offer(entry);
                } else if (priority.compare(entry, prioritized.peek()) < 0) {
                    prioritized.poll();
                    prioritized.offer(entry);
                }
            }
        }
        List<Map.Entry<SectionKey, PendingSnapshot>> selectedPriority = new ArrayList<>(prioritized);
        selectedPriority.sort(priority);
        for (Map.Entry<SectionKey, PendingSnapshot> entry : selectedPriority) {
            if (selected.size() >= selectedCount) {
                break;
            }
            selected.add(entry.getKey());
        }
        return new ArrayList<>(selected);
    }

    private void addPendingDirectSnapshotKeys(
            Set<SectionKey> selected,
            Iterable<SectionKey> candidates,
            int selectedCount
    ) {
        for (SectionKey key : candidates) {
            if (selected.size() >= selectedCount) {
                return;
            }
            PendingSnapshot pending = pendingSnapshots.get(key);
            if (pending != null
                    && (pending.sourceFlags() & SceneUpdateBatch.SOURCE_DIRECT_CONTENT) != 0) {
                selected.add(key);
            }
        }
    }

    private List<SectionKey> oldestPendingSnapshotKeys(int count) {
        LinkedHashSet<SectionKey> selected = new LinkedHashSet<>(count);
        addOldestPendingSnapshotKeys(selected, count);
        return new ArrayList<>(selected);
    }

    private void addOldestPendingSnapshotKeys(Set<SectionKey> selected, int count) {
        addOldestPendingSnapshotKeys(selected, count, Set.of(), Set.of(), nanoClock.getAsLong());
    }

    private void addOldestPendingSnapshotKeys(
            Set<SectionKey> selected,
            int count,
            Set<ChunkKey> unsettledStreamingChunks,
            Set<SectionKey> directBuildKeysInFlight,
            long selectionNanos
    ) {
        addOldestPendingSnapshotKeys(
                selected,
                count,
                StreamingDependencyIndex.capture(
                        unsettledStreamingChunks,
                        directBuildKeysInFlight,
                        pendingSnapshots
                ),
                selectionNanos
        );
    }

    private void addOldestPendingSnapshotKeys(
            Set<SectionKey> selected,
            int count,
            StreamingDependencyIndex streamingDependencies,
            long selectionNanos
    ) {
        if (count <= 0) {
            return;
        }
        for (Map.Entry<SectionKey, PendingSnapshot> entry : pendingSnapshots.entrySet()) {
            if (selected.size() >= count || selected.size() >= pendingSnapshots.size()) {
                return;
            }
            if (!deferStreamingNeighbor(
                    entry.getKey(),
                    entry.getValue(),
                    streamingDependencies,
                    selectionNanos
            )) {
                selected.add(entry.getKey());
            }
        }
    }

    /**
     * Streaming inserts arrive as a wave of immutable section snapshots.
     * A neighbor-only rebuild is useful only after its local 3x3x3 source
     * neighborhood reaches the latest revision. Keep one replaceable slot
     * while adjacent bridge/direct work is unresolved; direct mutations do
     * not satisfy the pure-streaming predicate and remain immediate.
     */
    private boolean deferStreamingNeighbor(
            SectionKey key,
            PendingSnapshot pending,
            StreamingDependencyIndex streamingDependencies,
            long selectionNanos
    ) {
        int sourceFlags = pending.sourceFlags();
        int pureStreamingNeighborFlags = SceneUpdateBatch.SOURCE_CHUNK_STREAMING
                | SceneUpdateBatch.SOURCE_NEIGHBOR_DEPENDENCY;
        if ((sourceFlags & pureStreamingNeighborFlags) != pureStreamingNeighborFlags
                || (sourceFlags & ~pureStreamingNeighborFlags) != 0) {
            return false;
        }
        if (elapsedNanos(selectionNanos, pending.firstQueuedNanos())
                >= STREAMING_NEIGHBOR_MAX_DEFER_NANOS) {
            return false;
        }
        if (streamingDependencies.intersects(key)) {
            return true;
        }
        return elapsedNanos(selectionNanos, pending.lastUpdatedNanos())
                < STREAMING_NEIGHBOR_QUIET_NANOS;
    }

    static long streamingNeighborQuietNanosForTesting() {
        return STREAMING_NEIGHBOR_QUIET_NANOS;
    }

    static long streamingNeighborMaxDeferNanosForTesting() {
        return STREAMING_NEIGHBOR_MAX_DEFER_NANOS;
    }

    private static long elapsedNanos(long nowNanos, long thenNanos) {
        long elapsed = nowNanos - thenNanos;
        return elapsed < 0L ? 0L : elapsed;
    }

    /**
     * One drain-local primitive dependency index shared by every streaming-neighbor decision.
     * The previous implementation rescanned every pending direct section and every unsettled
     * chunk for every candidate, making a streaming wave quadratic exactly when the queue was
     * largest. A fixed 3x3/3x3x3 probe preserves the same neighborhood contract in bounded time.
     */
    private static final class StreamingDependencyIndex {
        private final LongOpenHashSet unsettledChunks;
        private final LongOpenHashSet directSections;

        private StreamingDependencyIndex(LongOpenHashSet unsettledChunks, LongOpenHashSet directSections) {
            this.unsettledChunks = unsettledChunks;
            this.directSections = directSections;
        }

        private static StreamingDependencyIndex capture(
                Set<ChunkKey> unsettledStreamingChunks,
                Set<SectionKey> directBuildKeysInFlight,
                Map<SectionKey, PendingSnapshot> pendingSnapshots
        ) {
            LongOpenHashSet chunks = new LongOpenHashSet(unsettledStreamingChunks.size());
            for (ChunkKey chunk : unsettledStreamingChunks) {
                chunks.add(packChunk(chunk.x(), chunk.z()));
            }
            LongOpenHashSet sections = new LongOpenHashSet(
                    directBuildKeysInFlight.size() + Math.min(pendingSnapshots.size(), 64)
            );
            for (SectionKey key : directBuildKeysInFlight) {
                sections.add(key.packed());
            }
            for (Map.Entry<SectionKey, PendingSnapshot> entry : pendingSnapshots.entrySet()) {
                if ((entry.getValue().sourceFlags() & SceneUpdateBatch.SOURCE_DIRECT_CONTENT) != 0) {
                    SectionKey key = entry.getKey();
                    sections.add(key.packed());
                }
            }
            return new StreamingDependencyIndex(chunks, sections);
        }

        private boolean intersects(SectionKey center) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    if (unsettledChunks.contains(packChunk(center.x() + x, center.z() + z))) {
                        return true;
                    }
                }
            }
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        if (directSections.contains(SectionKey.pack(
                                center.x() + x,
                                center.y() + y,
                                center.z() + z
                        ))) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private static long packChunk(int x, int z) {
            return Integer.toUnsignedLong(x) | Integer.toUnsignedLong(z) << Integer.SIZE;
        }
    }

    private int fairDrainSlots(int selectedCount) {
        if (cameraPriorityDrainFrames % CAMERA_PRIORITY_FAIR_DRAIN_INTERVAL != 0L) {
            return 0;
        }
        if (selectedCount <= 1) {
            return selectedCount;
        }
        return Math.max(1, selectedCount / CAMERA_PRIORITY_FAIR_QUOTA_DIVISOR);
    }

    private long nextPendingSequence() {
        if (nextPendingSequence == Long.MAX_VALUE) {
            renumberPendingSnapshots();
        }
        return nextPendingSequence++;
    }

    private void renumberPendingSnapshots() {
        long sequence = 1L;
        for (Map.Entry<SectionKey, PendingSnapshot> entry : pendingSnapshots.entrySet()) {
            entry.setValue(new PendingSnapshot(
                    entry.getValue().snapshot(),
                    sequence++,
                    entry.getValue().sourceFlags(),
                    entry.getValue().firstQueuedNanos(),
                    entry.getValue().lastUpdatedNanos()
            ));
        }
        nextPendingSequence = sequence;
    }
    private record PendingSnapshot(
            SectionVoxelSnapshot snapshot,
            long sequence,
            int sourceFlags,
            long firstQueuedNanos,
            long lastUpdatedNanos
    ) {
        private PendingSnapshot {
            if (sequence <= 0L) {
                throw new IllegalArgumentException("sequence must be positive");
            }
        }

        private SectionVoxelSnapshot resolve(SectionKey key, SectionSnapshotLookup lookup) {
            return snapshot != null ? snapshot : lookup == null ? null : lookup.snapshot(key);
        }
    }

    @FunctionalInterface
    private interface SectionSnapshotLookup {
        SectionVoxelSnapshot snapshot(SectionKey key);
    }

    @FunctionalInterface
    private interface SectionSourceLookup {
        int sourceFlagsForSection(SectionKey key);
    }

    private record CameraSection(int x, int y, int z) {
        static CameraSection from(RendererFrameState frameState) {
            return new CameraSection(
                    blockToSectionCoord(frameState.cameraX()),
                    blockToSectionCoord(frameState.cameraY()),
                    blockToSectionCoord(frameState.cameraZ())
            );
        }
    }

    private static Comparator<Map.Entry<SectionKey, PendingSnapshot>> cameraPriorityComparator(
            RendererFrameState frameState
    ) {
        CameraPriorityContext context = CameraPriorityContext.from(frameState);
        return Comparator
                .comparingLong((Map.Entry<SectionKey, PendingSnapshot> entry) ->
                        forwardVisibilityBand(entry.getKey(), context))
                .thenComparingLong(entry -> sectionManhattanDistance(entry.getKey(), context.cameraSection()))
                .thenComparingDouble(entry -> -sectionForwardProjection(entry.getKey(), context))
                .thenComparingLong(entry -> entry.getValue().sequence())
                .thenComparingInt(entry -> entry.getKey().x())
                .thenComparingInt(entry -> entry.getKey().y())
                .thenComparingInt(entry -> entry.getKey().z());
    }

    private static long sectionManhattanDistance(SectionKey key, CameraSection cameraSection) {
        return absDifference(key.x(), cameraSection.x())
                + absDifference(key.y(), cameraSection.y())
                + absDifference(key.z(), cameraSection.z());
    }

    private static long absDifference(int first, int second) {
        return Math.abs((long) first - second);
    }

    private static int blockToSectionCoord(double blockCoord) {
        double sectionCoord = Math.floor(blockCoord / SectionVoxelSnapshot.SECTION_SIZE);
        if (sectionCoord <= Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        if (sectionCoord >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) sectionCoord;
    }

    private static long forwardVisibilityBand(SectionKey key, CameraPriorityContext context) {
        CameraSection cameraSection = context.cameraSection();
        if (key.x() == cameraSection.x() && key.y() == cameraSection.y() && key.z() == cameraSection.z()) {
            return 0L;
        }
        return sectionForwardProjection(key, context) >= 0.0D ? 0L : 1L;
    }

    private static double sectionForwardProjection(SectionKey key, CameraPriorityContext context) {
        double dx = sectionCenterBlockCoord(key.x()) - context.cameraX();
        double dy = sectionCenterBlockCoord(key.y()) - context.cameraY();
        double dz = sectionCenterBlockCoord(key.z()) - context.cameraZ();
        return dx * context.forwardX() + dy * context.forwardY() + dz * context.forwardZ();
    }

    private static double sectionCenterBlockCoord(int sectionCoord) {
        return sectionCoord * (double) SectionVoxelSnapshot.SECTION_SIZE
                + SectionVoxelSnapshot.SECTION_SIZE * 0.5D;
    }

    private record CameraPriorityContext(
            CameraSection cameraSection,
            double cameraX,
            double cameraY,
            double cameraZ,
            float forwardX,
            float forwardY,
            float forwardZ
    ) {
        static CameraPriorityContext from(RendererFrameState frameState) {
            return new CameraPriorityContext(
                    CameraSection.from(frameState),
                    frameState.cameraX(),
                    frameState.cameraY(),
                    frameState.cameraZ(),
                    frameState.cameraForwardX(),
                    frameState.cameraForwardY(),
                    frameState.cameraForwardZ()
            );
        }
    }
    private static long nextMetadataRevision(long revision, String owner) {
        if (revision == Long.MAX_VALUE) {
            throw new IllegalStateException(owner + " metadata revision space exhausted");
        }
        return revision + 1L;
    }
}
