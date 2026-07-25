package top.ceroxe.rt.renderer;

import jdk.jfr.*;
import top.ceroxe.rt.renderer.diagnostics.RtSceneCausalityRecorder;
import top.ceroxe.rt.renderer.orchestration.takeover.RtForegroundAdmissionFlightRecorder;
import top.ceroxe.rt.renderer.scene.*;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sole bridge owner for host world facts entering {@link SceneDatabase}.
 *
 * <p>This boundary performs state mutation and emits primitive JFR facts. It
 * does not own frame timing, rendering policy, BLAS/TLAS work or presentation.
 * Keeping writes out of {@link RendererTelemetry} makes an "observation"
 * failure incapable of silently hiding a required scene transition.</p>
 *
 * <p>This is the renderer-internal implementation behind
 * {@link RendererWorldPublicationIngress}.</p>
 */
final class RendererSceneIngress {
    /*
     * Ingress is deliberately independent from the render-frame owner.  A
     * negative frame/revision makes that missing edge explicit in JFR instead
     * of attaching a stale frame or fabricating a SceneDatabase revision.
     */
    private static final long UNKNOWN_FRAME_SEQUENCE = -1L;
    private static final long UNKNOWN_SECTION_REVISION = -1L;

    private static final int SECTION_DIRTY = 1;
    private static final int SECTION_RANGE_DIRTY = 2;
    private static final int BLOCK_MUTATION = 3;
    private static final int BLOCK_SNAPSHOT = 4;
    private static final int BLOCK_SNAPSHOT_REMOVED = 5;
    private static final int RENDER_SNAPSHOT = 6;
    private static final int RENDER_SNAPSHOT_REMOVED = 7;
    private static final int VISIBLE_PINS = 8;
    private static final int RESIDENT_CHUNK_PINS = 9;
    private static final int RESIDENT_CHUNK_DELTA = 10;
    private static final int CHUNK_PACKET = 11;
    private static final int CHUNK_SNAPSHOT = 12;
    private static final int STREAMING_CHUNK_SNAPSHOT = 13;
    private static final int STREAMING_CHUNK_BATCH = 14;
    private static final int FOREGROUND_SECTION_BATCH = 15;
    private static final int CHUNK_UNLOAD = 16;

    private final SceneDatabase sceneDatabase;
    /* One monotonic sequence for every state-changing ingress event. */
    private final AtomicLong ingressEvents = new AtomicLong();
    private final AtomicLong sectionDirtyEvents = new AtomicLong();
    private final AtomicLong sectionRangeDirtyEvents = new AtomicLong();
    private final AtomicLong blockMutationEvents = new AtomicLong();
    private final AtomicLong chunkPacketReplacementEvents = new AtomicLong();
    private final AtomicLong chunkUnloadEvents = new AtomicLong();
    private TerrainResidencySnapshot lastTerrainResidency = TerrainResidencySnapshot.empty();
    private boolean terrainResidencyInitialized;

    public RendererSceneIngress(SceneDatabase sceneDatabase) {
        this.sceneDatabase = Objects.requireNonNull(sceneDatabase, "sceneDatabase");
    }

    /**
     * Emits one ownership request per retired section rather than relying on the aggregate residency
     * counter.  The downstream CPU and native owners use the same key in their own events, which lets
     * a smoke recording prove where a moving render window stops retiring state.
     */
    private static void recordTerrainRetirements(
            TerrainResidencySnapshot snapshot,
            Set<SectionKey> authoritativeRetirements,
            long previousRevision
    ) {
        int sourceFlags = SceneUpdateBatch.sourceFlagsForRemoval(
                SceneUpdateBatch.sourceFlagsForChunkStreaming()
        );
        int liveSections = snapshot.geometrySections().size();
        long residentChunks = snapshot.residentChunks().size();
        for (SectionKey key : authoritativeRetirements) {
            SectionLifecycleFlightRecorder.record(
                    SectionLifecycleFlightRecorder.STAGE_RESIDENCY_RETIRE_REQUESTED,
                    SectionLifecycleFlightRecorder.SOURCE_UNKNOWN,
                    SectionLifecycleFlightRecorder.OUTCOME_ACCEPTED,
                    key,
                    0,
                    snapshot.revision(),
                    -1L,
                    previousRevision,
                    sourceFlags,
                    liveSections,
                    residentChunks
            );
        }
    }

    private static long sectionKeyFingerprint(Set<SectionKey> sectionKeys) {
        long fingerprint = 0xcbf29ce484222325L;
        for (SectionKey key : sectionKeys) {
            fingerprint ^= key.x();
            fingerprint *= 0x100000001b3L;
            fingerprint ^= key.y();
            fingerprint *= 0x100000001b3L;
            fingerprint ^= key.z();
            fingerprint *= 0x100000001b3L;
        }
        return fingerprint;
    }

    public void onSectionDirtyWithNeighbors(int sectionX, int sectionY, int sectionZ) {
        sceneDatabase.markRenderSectionDirty(sectionX, sectionY, sectionZ);
        long sequence = sectionDirtyEvents.incrementAndGet();
        record(SECTION_DIRTY, sectionX, sectionY, sectionZ, sequence, 0L);
    }

    public void onSectionRangeDirty(
            int sectionMinX,
            int sectionMinY,
            int sectionMinZ,
            int sectionMaxX,
            int sectionMaxY,
            int sectionMaxZ
    ) {
        sceneDatabase.markRenderSectionRangeDirty(
                sectionMinX,
                sectionMinY,
                sectionMinZ,
                sectionMaxX,
                sectionMaxY,
                sectionMaxZ
        );
        long sequence = sectionRangeDirtyEvents.incrementAndGet();
        long extent = ((long) sectionMaxX - sectionMinX + 1L)
                * ((long) sectionMaxY - sectionMinY + 1L)
                * ((long) sectionMaxZ - sectionMinZ + 1L);
        record(SECTION_RANGE_DIRTY, sectionMinX, sectionMinY, sectionMinZ, sequence, extent);
    }

    /**
     * Applies an already-authorized finite section set. The world-publication
     * ingress uses this for a host range invalidation after clipping it to the
     * immutable residency membership; expanding a raw host range here could
     * recreate sections that the same publication just retired.
     */
    public void onResidentRenderDirtySections(Set<SectionKey> sectionKeys) {
        Objects.requireNonNull(sectionKeys, "sectionKeys");
        for (SectionKey key : sectionKeys) {
            SectionKey checked = Objects.requireNonNull(key, "section key");
            sceneDatabase.markRenderSectionDirty(checked.x(), checked.y(), checked.z());
        }
        if (!sectionKeys.isEmpty()) {
            record(SECTION_RANGE_DIRTY, 0, 0, 0, sectionKeys.size(), sectionKeys.size());
        }
    }

    public void onBlockMutation(int blockX, int blockY, int blockZ, boolean changed) {
        if (changed) {
            sceneDatabase.markBlockMutation(blockX, blockY, blockZ);
        }
        long sequence = blockMutationEvents.incrementAndGet();
        record(BLOCK_MUTATION, blockX, blockY, blockZ, sequence, changed ? 1L : 0L);
    }

    public void onBlockMutationSectionSnapshot(SectionVoxelSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        sceneDatabase.replaceBlockMutationSectionSnapshot(snapshot);
        long sequence = blockMutationEvents.incrementAndGet();
        record(BLOCK_SNAPSHOT, snapshot.key(), sequence, snapshot.primitivePayloadBytes());
    }

    public void onBlockMutationSectionRemoved(SectionKey key) {
        Objects.requireNonNull(key, "key");
        sceneDatabase.removeBlockMutationSectionSnapshot(key);
        long sequence = blockMutationEvents.incrementAndGet();
        record(BLOCK_SNAPSHOT_REMOVED, key, sequence, 0L);
    }

    public void onRenderDirtySectionSnapshot(SectionVoxelSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        sceneDatabase.replaceRenderDirtySectionSnapshot(snapshot);
        long sequence = sectionDirtyEvents.incrementAndGet();
        record(RENDER_SNAPSHOT, snapshot.key(), sequence, snapshot.primitivePayloadBytes());
    }

    public void onRenderDirtySectionRemoved(SectionKey key) {
        Objects.requireNonNull(key, "key");
        sceneDatabase.removeRenderDirtySectionSnapshot(key);
        long sequence = sectionDirtyEvents.incrementAndGet();
        record(RENDER_SNAPSHOT_REMOVED, key, sequence, 0L);
    }

    public void onVisibleSectionKeys(Set<SectionKey> visibleSectionKeys) {
        Objects.requireNonNull(visibleSectionKeys, "visibleSectionKeys");
        /*
         * Visibility is renderer authority, not dense source-cache ownership.
         * RendererCore publishes the finite source-critical transaction after
         * the foreground epoch is resolved; pinning this full set made a
         * 32-chunk frustum retain tens of thousands of 4096-voxel payloads.
         */
        record(VISIBLE_PINS, 0, 0, 0, visibleSectionKeys.size(), 0L);
    }

    public void onResidentChunkKeys(Set<ChunkKey> residentChunkKeys) {
        Objects.requireNonNull(residentChunkKeys, "residentChunkKeys");
        sceneDatabase.updateResidentChunkPins(residentChunkKeys);
        record(RESIDENT_CHUNK_PINS, 0, 0, 0, residentChunkKeys.size(), 0L);
    }

    public synchronized ResidencyApplyResult onTerrainResidency(TerrainResidencySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (terrainResidencyInitialized && snapshot.revision() < lastTerrainResidency.revision()) {
            return ResidencyApplyResult.STALE;
        }
        if (terrainResidencyInitialized && snapshot.revision() == lastTerrainResidency.revision()) {
            if (!snapshot.equals(lastTerrainResidency)) {
                throw new IllegalStateException("terrain residency revision collision: " + snapshot.revision());
            }
            return ResidencyApplyResult.UNCHANGED;
        }

        boolean sequential = terrainResidencyInitialized
                && snapshot.revision() == lastTerrainResidency.revision() + 1L;
        Set<SectionKey> authoritativeRetirements;
        if (!terrainResidencyInitialized) {
            authoritativeRetirements = Set.of();
        } else if (sequential) {
            authoritativeRetirements = snapshot.retiredSections();
        } else {
            /*
             * A skipped bridge publication cannot be repaired from the latest delta alone. The
             * complete geometry sets are the authority, so derive every section owned by the last
             * accepted generation but absent from the replacement generation.
             */
            HashSet<SectionKey> reconciledRetirements = new HashSet<>(
                    lastTerrainResidency.geometrySections()
            );
            reconciledRetirements.removeAll(snapshot.geometryMembership());
            authoritativeRetirements = Set.copyOf(reconciledRetirements);
        }
        if (!sequential) {
            /* Full truth repairs a skipped or first bridge publication without replaying stale deltas. */
            sceneDatabase.reconcileTerrainResidency(
                    snapshot.residentChunks(),
                    authoritativeRetirements
            );
        } else if (snapshot.hasChunkMembershipDelta() || !authoritativeRetirements.isEmpty()) {
            sceneDatabase.applyTerrainResidencyDelta(
                    snapshot.enteredChunks(),
                    snapshot.retiredChunks(),
                    authoritativeRetirements
            );
        }
        long previousRevision = terrainResidencyInitialized ? lastTerrainResidency.revision() : -1L;
        lastTerrainResidency = snapshot;
        terrainResidencyInitialized = true;
        recordTerrainRetirements(snapshot, authoritativeRetirements, previousRevision);
        recordTerrainResidency(snapshot, !sequential);
        return ResidencyApplyResult.APPLIED;
    }

    public synchronized void resetTerrainResidency() {
        lastTerrainResidency = TerrainResidencySnapshot.empty();
        terrainResidencyInitialized = false;
    }

    public boolean hasResidentChunkSource(ChunkKey chunkKey) {
        return sceneDatabase.hasResidentChunkSource(Objects.requireNonNull(chunkKey, "chunkKey"));
    }

    public boolean hasResidentSectionSources(Set<SectionKey> requestedSectionKeys) {
        return sceneDatabase.hasResidentSectionSources(
                Objects.requireNonNull(requestedSectionKeys, "requestedSectionKeys")
        );
    }

    public Set<SectionKey> missingResidentSectionSources(Set<SectionKey> requestedSectionKeys) {
        return sceneDatabase.missingResidentSectionSources(
                Objects.requireNonNull(requestedSectionKeys, "requestedSectionKeys")
        );
    }

    public void onChunkPacketReplacement(int chunkX, int chunkZ) {
        sceneDatabase.markChunkPacketReplacement(chunkX, chunkZ);
        long sequence = chunkPacketReplacementEvents.incrementAndGet();
        record(CHUNK_PACKET, chunkX, 0, chunkZ, sequence, 0L);
    }

    public void onChunkSnapshotReplacement(ChunkSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        sceneDatabase.replaceChunkSnapshot(snapshot);
        record(CHUNK_SNAPSHOT, snapshot.chunkKey(), chunkPacketReplacementEvents.get(), snapshot.sectionCount());
    }

    public void onStreamingChunkSnapshotReplacement(ChunkSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        sceneDatabase.replaceStreamingChunkSnapshot(snapshot);
        RtForegroundAdmissionFlightRecorder.recordChunkSnapshot(snapshot, "sceneDatabasePublished", "mode=single");
        record(
                STREAMING_CHUNK_SNAPSHOT,
                snapshot.chunkKey(),
                chunkPacketReplacementEvents.get(),
                snapshot.sectionCount()
        );
    }

    public void onStreamingChunkSnapshotReplacements(List<ChunkSnapshot> snapshots) {
        Objects.requireNonNull(snapshots, "snapshots");
        if (snapshots.isEmpty()) {
            return;
        }
        for (ChunkSnapshot snapshot : snapshots) {
            sceneDatabase.markChunkPacketReplacement(snapshot.chunkKey().x(), snapshot.chunkKey().z());
        }
        sceneDatabase.replaceStreamingChunkSnapshots(snapshots);
        for (ChunkSnapshot snapshot : snapshots) {
            RtForegroundAdmissionFlightRecorder.recordChunkSnapshot(
                    snapshot,
                    "sceneDatabasePublished",
                    "mode=batch, batchChunks=" + snapshots.size()
            );
        }
        long sequence = chunkPacketReplacementEvents.addAndGet(snapshots.size());
        record(STREAMING_CHUNK_BATCH, 0, 0, 0, sequence, snapshots.size());
    }

    public void onForegroundSectionSnapshotReplacements(
            List<ChunkSnapshot> snapshots,
            Set<SectionKey> requestedSectionKeys
    ) {
        Objects.requireNonNull(snapshots, "snapshots");
        Objects.requireNonNull(requestedSectionKeys, "requestedSectionKeys");
        SceneDatabase.ForegroundReplacementResult result =
                sceneDatabase.replaceForegroundSectionSnapshots(snapshots, requestedSectionKeys);
        for (ChunkSnapshot snapshot : snapshots) {
            RtForegroundAdmissionFlightRecorder.recordChunkSnapshot(
                    snapshot,
                    "sceneDatabasePublished",
                    "mode=foregroundSections, requestedSections=" + requestedSectionKeys.size()
            );
        }
        chunkPacketReplacementEvents.addAndGet(snapshots.size());
        recordForegroundSectionBatch(requestedSectionKeys.size(), result);
    }

    public void onChunkUnload(int chunkX, int chunkZ) {
        sceneDatabase.unloadChunk(chunkX, chunkZ);
        long sequence = chunkUnloadEvents.incrementAndGet();
        record(CHUNK_UNLOAD, chunkX, 0, chunkZ, sequence, 0L);
    }

    public String summary() {
        return "sceneIngress{sectionDirty=" + sectionDirtyEvents.get()
                + ", sectionRangeDirty=" + sectionRangeDirtyEvents.get()
                + ", blockMutations=" + blockMutationEvents.get()
                + ", chunkPackets=" + chunkPacketReplacementEvents.get()
                + ", chunkUnloads=" + chunkUnloadEvents.get()
                + "}";
    }

    private void record(int operation, SectionKey key, long sequence, long value) {
        record(operation, key.x(), key.y(), key.z(), sequence, value);
    }

    private void record(int operation, ChunkKey key, long sequence, long value) {
        record(operation, key.x(), 0, key.z(), sequence, value);
    }

    private void record(int operation, int x, int y, int z, long operationSequence, long value) {
        SceneIngressEvent event = new SceneIngressEvent();
        if (!event.isEnabled()) {
            return;
        }
        long ingressSequence = ingressEvents.incrementAndGet();
        /*
         * The global event order is the join key for this boundary. Existing
         * per-operation counters remain separately observable so a consumer
         * can distinguish an event's causal order from its local metric.
         */
        event.sessionId = RtSceneCausalityRecorder.sessionId();
        event.ingressSequence = ingressSequence;
        event.frameSequence = UNKNOWN_FRAME_SEQUENCE;
        event.sectionRevision = UNKNOWN_SECTION_REVISION;
        event.operation = operation;
        event.x = x;
        event.y = y;
        event.z = z;
        event.operationSequence = operationSequence;
        event.value = value;
        event.commit();
    }

    /**
     * Emits the complete foreground replacement result as primitive values.
     *
     * <p>This replaces a formatted logger edge on a frequently hit admission
     * path. The fingerprint is intentionally computed only after JFR accepts
     * the event, so normal rendering performs no set walk or string work.</p>
     */
    private void recordForegroundSectionBatch(
            long requestedSectionCount,
            SceneDatabase.ForegroundReplacementResult result
    ) {
        SceneIngressEvent event = new SceneIngressEvent();
        if (!event.isEnabled()) {
            return;
        }
        long ingressSequence = ingressEvents.incrementAndGet();
        event.sessionId = RtSceneCausalityRecorder.sessionId();
        event.ingressSequence = ingressSequence;
        event.frameSequence = UNKNOWN_FRAME_SEQUENCE;
        event.sectionRevision = UNKNOWN_SECTION_REVISION;
        event.operation = FOREGROUND_SECTION_BATCH;
        event.operationSequence = requestedSectionCount;
        event.value = result.removedResidentSections().size();
        event.requestedSectionCount = result.requestedSectionCount();
        event.suppliedSectionCount = result.suppliedSectionCount();
        event.omittedRequestedSectionCount = result.omittedRequestedSectionCount();
        event.removedResidentSectionCount = result.removedResidentSections().size();
        event.residentSectionCountAfter = result.residentSectionCountAfter();
        event.removedSectionFingerprint = sectionKeyFingerprint(result.removedResidentSections());
        event.commit();
    }

    private void recordTerrainResidency(TerrainResidencySnapshot snapshot, boolean fullReconciliation) {
        SceneIngressEvent event = new SceneIngressEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.sessionId = RtSceneCausalityRecorder.sessionId();
        event.ingressSequence = ingressEvents.incrementAndGet();
        event.frameSequence = UNKNOWN_FRAME_SEQUENCE;
        event.sectionRevision = UNKNOWN_SECTION_REVISION;
        event.operation = RESIDENT_CHUNK_DELTA;
        event.terrainResidencyRevision = snapshot.revision();
        event.enteredChunkCount = snapshot.enteredChunks().size();
        event.retiredChunkCount = snapshot.retiredChunks().size();
        event.enteredSectionCount = snapshot.enteredSections().size();
        event.retiredSectionCount = snapshot.retiredSections().size();
        event.fullReconciliation = fullReconciliation;
        event.commit();
    }

    public enum ResidencyApplyResult {
        APPLIED,
        UNCHANGED,
        STALE
    }

    @Name("top.ceroxe.rt.SceneIngress")
    @Label("host Scene Ingress")
    @Category({"RTRenderer", "Causality"})
    @StackTrace(false)
    static final class SceneIngressEvent extends Event {
        int operation;
        int x;
        int y;
        int z;
        long sessionId;
        long frameSequence;
        long ingressSequence;
        long sectionRevision;
        long operationSequence;
        long value;
        long requestedSectionCount;
        long suppliedSectionCount;
        long omittedRequestedSectionCount;
        long removedResidentSectionCount;
        long residentSectionCountAfter;
        long removedSectionFingerprint;
        long terrainResidencyRevision;
        int enteredChunkCount;
        int retiredChunkCount;
        int enteredSectionCount;
        int retiredSectionCount;
        boolean fullReconciliation;
    }
}
