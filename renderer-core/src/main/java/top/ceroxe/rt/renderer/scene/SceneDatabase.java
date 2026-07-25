package top.ceroxe.rt.renderer.scene;

import java.util.*;

/**
 * Renderer-owned scene database 的第一块地基。
 *
 * <p>这里不保存 host 对象引用，只保存 renderer 后端真正需要的稳定坐标键。
 * 这能防止未来 Vulkan resource 生命周期被 host world/chunk 对象生命周期拖着走。</p>
 */
public final class SceneDatabase {
    /**
     * Default maximum number of resident dense section snapshots.
     */
    public static final int DEFAULT_MAX_RESIDENT_SECTION_SNAPSHOTS =
            SceneCacheBudget.DEFAULT.maxRtCachedMeshSections();
    private static final Set<SectionKey> NO_RESOLVED_OUTPUT_SECTIONS = Set.of();
    private static final long MAX_SECTION_RANGE_EXPANSION = 4096L;
    private static final String REBUILD_ON_RENDER_DIRTY_PROPERTY =
            "top.ceroxe.rt.scene.rebuildOnRenderDirty.enabled";
    private static final String MAX_RESIDENT_SECTION_SNAPSHOTS_PROPERTY =
            "top.ceroxe.rt.scene.maxResidentSectionSnapshots";

    private final SceneDatabaseLock lock = new SceneDatabaseLock();
    private final Set<SectionKey> dirtySections = new HashSet<>();
    private final Set<ChunkKey> dirtyChunks = new HashSet<>();
    private final Set<SectionKey> removedSections = new HashSet<>();
    private final Set<ChunkKey> unloadedChunks = new HashSet<>();
    private final Map<SectionKey, SectionVoxelSnapshot> sectionSnapshots =
            new LinkedHashMap<>(16, 0.75f, true);
    /**
     * Exact source membership by chunk; chunk retirement must never scan the complete world.
     */
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Set<SectionKey>> sectionKeysByChunk =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private final Map<SectionKey, Long> sectionGeometryRevisions = new HashMap<>();
    private final Map<SectionKey, Integer> sectionSourceFlags = new HashMap<>();
    private final Set<SectionKey> pendingResidentSourceEntries = new LinkedHashSet<>();
    private final Set<SectionKey> pendingResidentSourceRetirements = new LinkedHashSet<>();
    /* Query-local scratch is serialized by the database lock; publications remain immutable. */
    private final PackedSectionMembership.Builder missingSourceMembershipBuilder =
            PackedSectionMembership.builder(0);
    /* Logical dense sources are palette-packed and bounded; they are never the RT generation authority. */
    private final int maxResidentSectionSnapshots;
    private final Set<ChunkKey> streamingResidentChunks = new HashSet<>();
    /*
     * Source presence is a persistent lifecycle publication. Retaining exact entered/retired
     * facts avoids reconstructing a complete packed membership for every chunk reuse query.
     */
    private PackedSectionMembership residentSourceMembership = PackedSectionMembership.empty();
    /*
     * Missing-source classification is a revisioned database publication.  The bridge asks the
     * same immutable extraction target more than once while a source generation is stable; doing
     * a fresh packed-index build for every query made a read-only ownership check the largest
     * allocation site in the renderer.  Identity keys are intentional: callers already publish
     * immutable memberships, and an equal-but-distinct request must still be classified.
     */
    private Set<SectionKey> cachedMissingSourceRequestIdentity = Set.of();
    private Set<SectionKey> cachedMissingSourceResolvedIdentity = Set.of();
    private long cachedMissingSourceRevision = -1L;
    private Set<SectionKey> cachedMissingSourcePublication = Set.of();
    private long pendingSectionMetadataRevision;
    private PendingSectionMetadata cachedPendingSectionMetadata = PendingSectionMetadata.empty();
    private Set<SectionKey> visibleResidentSections = Set.of();
    /*
     * Dense source payloads already transferred to the renderer scheduler have a different
     * lifetime from presentation pins.  Pending CPU work must retain its immutable input until
     * the mesh/compact-source publication completes; otherwise LRU pressure can evict the input,
     * make the scheduler resolve a null snapshot, and trigger an extract -> evict -> re-extract
     * loop.  The update loop publishes this bounded lane from its own lifecycle identities.
     */
    private Set<SectionKey> inFlightSectionSourcePins = Set.of();
    private Set<ChunkKey> visibleResidentChunks = Set.of();
    private long sectionDirtyMarks;
    private long nextSectionGeometryRevision = 1L;
    /* Monotonic producer revision for consumers that cache resident source membership. */
    private long residentSectionSourceRevision;
    /*
     * Ownership diagnostics are a publication, not a query-time world scan. Every fact represented
     * by OwnershipSnapshot advances this revision under the database lock; stable readers therefore
     * share one immutable object identity just like the renderer's scene generations.
     */
    private long ownershipPublicationRevision;
    private long cachedOwnershipPublicationRevision = -1L;
    private OwnershipSnapshot cachedOwnershipSnapshot;
    private long blockMutationMarks;
    private long chunkPacketReplacementMarks;
    private long chunkSnapshotReplacements;
    private long chunkUnloadMarks;
    private long sectionSnapshotRemovals;
    private long fullResyncRequests;
    private boolean fullResyncRequested;
    private int pendingSourceFlags;
    private long overCapacityEvictionPasses;
    private long evictedResidentSections;
    private long blockedEvictionPasses;
    private EvictionClassification lastBlockedEviction = EvictionClassification.empty();
    /* Removal provenance is aggregate-only and is emitted from smoke snapshots, never per event. */
    private long foregroundOmissionRemovals;
    private long columnReconciliationRemovals;
    private long explicitRenderDirtyRemovals;
    private long explicitBlockMutationRemovals;
    private long sourceEvictionRemovals;
    private long chunkUnloadRemovals;

    /**
     * Creates a scene database using the configured resident-section capacity.
     */
    public SceneDatabase() {
        this(configuredResidentSectionCapacity());
    }

    SceneDatabase(int maxResidentSectionSnapshots) {
        if (maxResidentSectionSnapshots <= 0) {
            throw new IllegalArgumentException("maxResidentSectionSnapshots must be positive");
        }
        this.maxResidentSectionSnapshots = maxResidentSectionSnapshots;
    }

    /**
     * Resolves the configured resident-section capacity.
     *
     * @return positive resident-section capacity
     */
    public static int configuredResidentSectionCapacity() {
        return positiveIntProperty(
                MAX_RESIDENT_SECTION_SNAPSHOTS_PROPERTY,
                DEFAULT_MAX_RESIDENT_SECTION_SNAPSHOTS
        );
    }

    private static int sanitizeSourceFlags(int sourceFlags) {
        return sourceFlags
                & (SceneUpdateBatch.SOURCE_RENDER_DIRTY
                | SceneUpdateBatch.SOURCE_BLOCK_MUTATION
                | SceneUpdateBatch.SOURCE_CHUNK_STREAMING
                | SceneUpdateBatch.SOURCE_SECTION_REMOVAL
                | SceneUpdateBatch.SOURCE_FULL_RESYNC
                | SceneUpdateBatch.SOURCE_MATERIAL_ONLY
                | SceneUpdateBatch.SOURCE_NEIGHBOR_DEPENDENCY
                | SceneUpdateBatch.SOURCE_DIRECT_CONTENT);
    }

    private static Map<FaceDirection, SectionVoxelSnapshot> faceNeighbors(SectionNeighborhood neighborhood) {
        Map<FaceDirection, SectionVoxelSnapshot> neighbors = new HashMap<>();
        SectionKey center = neighborhood.centerKey();
        for (FaceDirection direction : FaceDirection.values()) {
            SectionVoxelSnapshot snapshot = neighborhood.snapshots().get(new SectionKey(
                    center.x() + direction.stepX(),
                    center.y() + direction.stepY(),
                    center.z() + direction.stepZ()
            ));
            if (snapshot != null) {
                neighbors.put(direction, snapshot);
            }
        }
        return Map.copyOf(neighbors);
    }

    private static long axisLength(int minInclusive, int maxInclusive) {
        return (long) maxInclusive - minInclusive + 1L;
    }

    private static boolean sectionRangeExceedsExpansionLimit(int minX, int minY, int minZ,
                                                             int maxX, int maxY, int maxZ) {
        long xLength = axisLength(minX, maxX);
        long yLength = axisLength(minY, maxY);
        long zLength = axisLength(minZ, maxZ);

        if (xLength > MAX_SECTION_RANGE_EXPANSION) {
            return true;
        }
        if (yLength > MAX_SECTION_RANGE_EXPANSION / xLength) {
            return true;
        }
        long xyLength = xLength * yLength;
        return zLength > MAX_SECTION_RANGE_EXPANSION / xyLength;
    }

    private static long cappedSectionRangeVolume(int minX, int minY, int minZ,
                                                 int maxX, int maxY, int maxZ) {
        long xLength = axisLength(minX, maxX);
        long yLength = axisLength(minY, maxY);
        long zLength = axisLength(minZ, maxZ);
        if (xLength > MAX_SECTION_RANGE_EXPANSION) {
            return MAX_SECTION_RANGE_EXPANSION;
        }
        long xy = xLength * Math.min(yLength, Math.max(1L, MAX_SECTION_RANGE_EXPANSION / xLength));
        if (xy >= MAX_SECTION_RANGE_EXPANSION) {
            return MAX_SECTION_RANGE_EXPANSION;
        }
        return Math.min(MAX_SECTION_RANGE_EXPANSION, xy * zLength);
    }

    private static boolean rebuildOnRenderDirtyEnabled() {
        return Boolean.getBoolean(REBUILD_ON_RENDER_DIRTY_PROPERTY);
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

    /**
     * Reports whether any scene update is pending publication.
     *
     * @return {@code true} when the next update batch contains work
     */
    public boolean hasPendingUpdates() {
        long stamp = lock.acquire(SceneDatabaseLock.Stage.QUERY);
        try {
            return fullResyncRequested
                    || !dirtySections.isEmpty()
                    || !dirtyChunks.isEmpty()
                    || !removedSections.isEmpty()
                    || !unloadedChunks.isEmpty();
        } finally {
            lock.release(SceneDatabaseLock.Stage.QUERY, stamp);
        }
    }

    /**
     * Marks one section dirty because its renderable content may have changed.
     *
     * @param sectionX section X coordinate
     * @param sectionY section Y coordinate
     * @param sectionZ section Z coordinate
     */
    public void markSectionDirty(int sectionX, int sectionY, int sectionZ) {
        long stamp = lock.acquire(SceneDatabaseLock.Stage.MUTATION);
        try {
            markSectionDirtyLocked(sectionX, sectionY, sectionZ, SceneUpdateBatch.sourceFlagsForRenderDirty());
        } finally {
            lock.release(SceneDatabaseLock.Stage.MUTATION, stamp);
        }
    }

    /**
     * Records a host render-dirty observation, rebuilding only when that compatibility mode is enabled.
     *
     * @param sectionX section X coordinate
     * @param sectionY section Y coordinate
     * @param sectionZ section Z coordinate
     */
    public void markRenderSectionDirty(int sectionX, int sectionY, int sectionZ) {
        long stamp = lock.acquire(SceneDatabaseLock.Stage.MUTATION);
        try {
            if (rebuildOnRenderDirtyEnabled()) {
                markSectionDirtyLocked(sectionX, sectionY, sectionZ, SceneUpdateBatch.sourceFlagsForRenderDirty());
                return;
            }
            sectionDirtyMarks++;
        } finally {
            lock.release(SceneDatabaseLock.Stage.MUTATION, stamp);
        }
    }

    /**
     * Marks an inclusive section-coordinate range dirty.
     *
     * @param sectionMinX first section X coordinate
     * @param sectionMinY first section Y coordinate
     * @param sectionMinZ first section Z coordinate
     * @param sectionMaxX opposite section X coordinate
     * @param sectionMaxY opposite section Y coordinate
     * @param sectionMaxZ opposite section Z coordinate
     */
    public void markSectionRangeDirty(int sectionMinX, int sectionMinY, int sectionMinZ,
                                      int sectionMaxX, int sectionMaxY, int sectionMaxZ) {
        int minX = Math.min(sectionMinX, sectionMaxX);
        int minY = Math.min(sectionMinY, sectionMaxY);
        int minZ = Math.min(sectionMinZ, sectionMaxZ);
        int maxX = Math.max(sectionMinX, sectionMaxX);
        int maxY = Math.max(sectionMinY, sectionMaxY);
        int maxZ = Math.max(sectionMinZ, sectionMaxZ);

        if (sectionRangeExceedsExpansionLimit(minX, minY, minZ, maxX, maxY, maxZ)) {
            /*
             * 异常巨大的 range 不能靠无限展开硬扛，否则一个畸形事件就能把 renderer
             * 的内存和日志拖垮。后续真实 extraction 会把这个标记升级为全量重建。
             */
            requestFullResync();
            return;
        }

        long stamp = lock.acquire(SceneDatabaseLock.Stage.MUTATION);
        try {
            for (long x = minX; x <= maxX; x++) {
                for (long y = minY; y <= maxY; y++) {
                    for (long z = minZ; z <= maxZ; z++) {
                        markSectionDirtyLocked(
                                (int) x,
                                (int) y,
                                (int) z,
                                SceneUpdateBatch.sourceFlagsForRenderDirty()
                        );
                    }
                }
            }
        } finally {
            lock.release(SceneDatabaseLock.Stage.MUTATION, stamp);
        }
    }

    /**
     * Records render-dirty observations for an inclusive section-coordinate range.
     *
     * @param sectionMinX first section X coordinate
     * @param sectionMinY first section Y coordinate
     * @param sectionMinZ first section Z coordinate
     * @param sectionMaxX opposite section X coordinate
     * @param sectionMaxY opposite section Y coordinate
     * @param sectionMaxZ opposite section Z coordinate
     */
    public void markRenderSectionRangeDirty(int sectionMinX, int sectionMinY, int sectionMinZ,
                                            int sectionMaxX, int sectionMaxY, int sectionMaxZ) {
        if (rebuildOnRenderDirtyEnabled()) {
            markSectionRangeDirty(sectionMinX, sectionMinY, sectionMinZ, sectionMaxX, sectionMaxY, sectionMaxZ);
            return;
        }

        int minX = Math.min(sectionMinX, sectionMaxX);
        int minY = Math.min(sectionMinY, sectionMaxY);
        int minZ = Math.min(sectionMinZ, sectionMaxZ);
        int maxX = Math.max(sectionMinX, sectionMaxX);
        int maxY = Math.max(sectionMinY, sectionMaxY);
        int maxZ = Math.max(sectionMinZ, sectionMaxZ);
        long stamp = lock.acquire(SceneDatabaseLock.Stage.MUTATION);
        try {
            sectionDirtyMarks += cappedSectionRangeVolume(minX, minY, minZ, maxX, maxY, maxZ);
        } finally {
            lock.release(SceneDatabaseLock.Stage.MUTATION, stamp);
        }
    }

    /**
     * Marks the section containing one mutated block dirty.
     *
     * @param blockX world block X coordinate
     * @param blockY world block Y coordinate
     * @param blockZ world block Z coordinate
     */
    public void markBlockMutation(int blockX, int blockY, int blockZ) {
        long stamp = lock.acquire(SceneDatabaseLock.Stage.MUTATION);
        try {
            markSectionDirtyLocked(
                    Math.floorDiv(blockX, 16),
                    Math.floorDiv(blockY, 16),
                    Math.floorDiv(blockZ, 16),
                    SceneUpdateBatch.sourceFlagsForBlockMutation()
            );
            blockMutationMarks++;
            pendingSourceFlags |= SceneUpdateBatch.sourceFlagsForBlockMutation();
        } finally {
            lock.release(SceneDatabaseLock.Stage.MUTATION, stamp);
        }
    }

    /**
     * Replaces one resident snapshot using direct block-mutation provenance.
     *
     * @param snapshot immutable replacement snapshot
     */
    public void replaceBlockMutationSectionSnapshot(SectionVoxelSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        long stamp = lock.acquire(SceneDatabaseLock.Stage.MUTATION);
        try {
            blockMutationMarks++;
            replaceSectionSnapshotLocked(snapshot, SceneUpdateBatch.sourceFlagsForBlockMutation());
        } finally {
            lock.release(SceneDatabaseLock.Stage.MUTATION, stamp);
        }
    }

    /**
     * Replaces one resident snapshot using render-dirty provenance.
     *
     * @param snapshot immutable replacement snapshot
     */
    public void replaceRenderDirtySectionSnapshot(SectionVoxelSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        long stamp = lock.acquire(SceneDatabaseLock.Stage.MUTATION);
        try {
            replaceSectionSnapshotLocked(snapshot, SceneUpdateBatch.sourceFlagsForRenderDirty());
        } finally {
            lock.release(SceneDatabaseLock.Stage.MUTATION, stamp);
        }
    }

    /**
     * Removes one resident snapshot using direct block-mutation provenance.
     *
     * @param key section identity to remove
     */
    public void removeBlockMutationSectionSnapshot(SectionKey key) {
        Objects.requireNonNull(key, "key");
        long stamp = lock.acquire(SceneDatabaseLock.Stage.MUTATION);
        try {
            blockMutationMarks++;
            removeSectionSnapshotLocked(key, SceneUpdateBatch.sourceFlagsForBlockMutation());
        } finally {
            lock.release(SceneDatabaseLock.Stage.MUTATION, stamp);
        }
    }

    /**
     * Removes one resident snapshot using render-dirty provenance.
     *
     * @param key section identity to remove
     */
    public void removeRenderDirtySectionSnapshot(SectionKey key) {
        Objects.requireNonNull(key, "key");
        long stamp = lock.acquire(SceneDatabaseLock.Stage.MUTATION);
        try {
            removeSectionSnapshotLocked(key, SceneUpdateBatch.sourceFlagsForRenderDirty());
        } finally {
            lock.release(SceneDatabaseLock.Stage.MUTATION, stamp);
        }
    }

    /**
     * Records replacement of one chunk packet.
     *
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     */
    public void markChunkPacketReplacement(int chunkX, int chunkZ) {
        long stamp = lock.acquire(SceneDatabaseLock.Stage.MUTATION);
        try {
            ChunkKey chunkKey = new ChunkKey(chunkX, chunkZ);
            if (!unloadedChunks.contains(chunkKey)) {
                dirtyChunks.add(chunkKey);
            }
            chunkPacketReplacementMarks++;
            pendingSourceFlags |= SceneUpdateBatch.sourceFlagsForChunkStreaming();
        } finally {
            lock.release(SceneDatabaseLock.Stage.MUTATION, stamp);
        }
    }

    /**
     * Replaces one authoritative chunk-column snapshot and removes stale sections in that column.
     *
     * @param snapshot immutable chunk snapshot
     */
    public void replaceChunkSnapshot(ChunkSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        long stamp = lock.acquire(SceneDatabaseLock.Stage.MUTATION);
        try {
            ChunkKey chunkKey = snapshot.chunkKey();
            unloadedChunks.remove(chunkKey);
            Set<SectionKey> incomingSections = new HashSet<>();
            boolean changed = false;
            for (SectionVoxelSnapshot section : snapshot.sections()) {
                incomingSections.add(section.key());
                changed |= replaceSectionSnapshotLocked(section, SceneUpdateBatch.sourceFlagsForChunkStreaming());
            }
            int removed = removeStaleChunkSectionsLocked(
                    chunkKey,
                    incomingSections,
                    SceneUpdateBatch.sourceFlagsForChunkStreaming()
            );
            sectionSnapshotRemovals += removed;
            if (changed || removed > 0) {
                dirtyChunks.add(chunkKey);
            }
            chunkSnapshotReplacements++;
        } finally {
            lock.release(SceneDatabaseLock.Stage.MUTATION, stamp);
        }
    }

    /**
     * Replaces one streaming chunk snapshot and applies chunk-streaming provenance.
     *
     * @param snapshot immutable chunk snapshot
     */
    public void replaceStreamingChunkSnapshot(ChunkSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        long stamp = lock.acquire(SceneDatabaseLock.Stage.MUTATION);
        try {
            ChunkKey chunkKey = snapshot.chunkKey();
            unloadedChunks.remove(chunkKey);
            Set<SectionKey> incomingSections = new HashSet<>();
            boolean changed = false;
            for (SectionVoxelSnapshot section : snapshot.sections()) {
                incomingSections.add(section.key());
                changed |= replaceSectionSnapshotLocked(section, SceneUpdateBatch.sourceFlagsForChunkStreaming());
            }
            int removed = removeStaleChunkSectionsLocked(
                    chunkKey,
                    incomingSections,
                    SceneUpdateBatch.sourceFlagsForChunkStreaming()
            );
            sectionSnapshotRemovals += removed;
            if (changed || removed > 0) {
                dirtyChunks.add(chunkKey);
            }
            chunkSnapshotReplacements++;
        } finally {
            lock.release(SceneDatabaseLock.Stage.MUTATION, stamp);
        }
    }

    /**
     * Replaces a streaming batch while coalescing neighbor invalidation and eviction work.
     *
     * @param snapshots immutable chunk snapshots to merge
     */
    public void replaceStreamingChunkSnapshots(Collection<ChunkSnapshot> snapshots) {
        Objects.requireNonNull(snapshots, "snapshots");
        if (snapshots.isEmpty()) {
            return;
        }
        long stamp = lock.acquire(SceneDatabaseLock.Stage.MUTATION);
        try {
            Set<SectionKey> neighborDirtySources = new HashSet<>();
            for (ChunkSnapshot snapshot : snapshots) {
                Objects.requireNonNull(snapshot, "snapshot");
                ChunkKey chunkKey = snapshot.chunkKey();
                unloadedChunks.remove(chunkKey);
                Set<SectionKey> incomingSections = new HashSet<>();
                boolean changed = false;
                for (SectionVoxelSnapshot section : snapshot.sections()) {
                    incomingSections.add(section.key());
                    changed |= replaceSectionSnapshotLocked(
                            section,
                            SceneUpdateBatch.sourceFlagsForChunkStreaming(),
                            false,
                            neighborDirtySources,
                            false
                    );
                }
                int removed = removeChunkSectionsLocked(
                        chunkKey,
                        incomingSections,
                        SceneUpdateBatch.sourceFlagsForChunkStreaming(),
                        false,
                        neighborDirtySources,
                        RemovalProducer.COLUMN_RECONCILIATION
                );
                sectionSnapshotRemovals += removed;
                if (changed || removed > 0) {
                    dirtyChunks.add(chunkKey);
                }
                chunkSnapshotReplacements++;
            }
            for (SectionKey key : neighborDirtySources) {
                markExistingNeighborSectionsDirtyLocked(key, SceneUpdateBatch.sourceFlagsForChunkStreaming());
            }
            evictNonResidentSourcesLocked();
        } finally {
            lock.release(SceneDatabaseLock.Stage.MUTATION, stamp);
        }
    }

    /**
     * Merges an authoritative foreground subset without applying full-column stale removal.
     *
     * <p>A host frustum publication names section coordinates, not whole vertical chunk columns.
     * Treating that subset as a complete {@link ChunkSnapshot} would delete resident sections that were
     * simply outside the current foreground transaction. Requested keys omitted from the extracted payload
     * are genuine air/removal results and are removed explicitly.</p>
     * The operation applies one foreground extraction transaction and returns its exact ownership outcome.
     *
     * <p>The result deliberately distinguishes an omitted requested section from an
     * unrequested resident section.  host publishes a frustum subset, while an
     * omitted member of that requested subset is affirmative extractor evidence that
     * the section no longer has source data.  Keeping that distinction at the database
     * boundary makes removal causality observable without teaching the RT backend about
     * mutable host objects.</p>
     *
     * @param snapshots            chunk snapshots containing the supplied foreground sections
     * @param requestedSectionKeys complete authoritative foreground request
     * @return exact foreground replacement and omission outcome
     */
    public ForegroundReplacementResult replaceForegroundSectionSnapshots(
            Collection<ChunkSnapshot> snapshots,
            Set<SectionKey> requestedSectionKeys
    ) {
        Objects.requireNonNull(snapshots, "snapshots");
        Objects.requireNonNull(requestedSectionKeys, "requestedSectionKeys");
        if (snapshots.isEmpty() && requestedSectionKeys.isEmpty()) {
            return ForegroundReplacementResult.empty();
        }
        long stamp = lock.acquire(SceneDatabaseLock.Stage.MUTATION);
        try {
            Set<SectionKey> suppliedSectionKeys = new HashSet<>();
            Set<SectionKey> neighborDirtySources = new HashSet<>();
            Set<ChunkKey> touchedChunks = new HashSet<>();
            for (ChunkSnapshot snapshot : snapshots) {
                Objects.requireNonNull(snapshot, "snapshot");
                ChunkKey chunkKey = snapshot.chunkKey();
                touchedChunks.add(chunkKey);
                unloadedChunks.remove(chunkKey);
                for (SectionVoxelSnapshot section : snapshot.sections()) {
                    if (!section.key().chunkKey().equals(chunkKey)) {
                        throw new IllegalArgumentException("foreground section must belong to its chunk snapshot");
                    }
                    if (!requestedSectionKeys.contains(section.key())) {
                        throw new IllegalArgumentException("foreground payload must be present in requestedSectionKeys");
                    }
                    suppliedSectionKeys.add(section.key());
                    replaceSectionSnapshotLocked(
                            section,
                            SceneUpdateBatch.sourceFlagsForChunkStreaming(),
                            false,
                            neighborDirtySources,
                            false
                    );
                }
            }
            Set<SectionKey> removedRequestedSectionKeys = new HashSet<>();
            for (SectionKey requestedKey : requestedSectionKeys) {
                if (!touchedChunks.contains(requestedKey.chunkKey())) {
                    throw new IllegalArgumentException("requested foreground section has no owning chunk snapshot");
                }
                if (!suppliedSectionKeys.contains(requestedKey)) {
                    boolean existedBeforeRemoval = sectionSnapshots.containsKey(requestedKey);
                    removeSectionSnapshotLocked(requestedKey, SceneUpdateBatch.sourceFlagsForChunkStreaming());
                    if (existedBeforeRemoval) {
                        foregroundOmissionRemovals++;
                        removedRequestedSectionKeys.add(requestedKey);
                    }
                }
            }
            for (SectionKey key : neighborDirtySources) {
                markExistingNeighborSectionsDirtyLocked(key, SceneUpdateBatch.sourceFlagsForChunkStreaming());
            }
            chunkSnapshotReplacements += touchedChunks.size();
            evictNonResidentSourcesLocked();
            return new ForegroundReplacementResult(
                    requestedSectionKeys.size(),
                    suppliedSectionKeys.size(),
                    Set.copyOf(removedRequestedSectionKeys),
                    sectionSnapshots.size()
            );
        } finally {
            lock.release(SceneDatabaseLock.Stage.MUTATION, stamp);
        }
    }

    /**
     * Retires one chunk column and publishes removal provenance for its resident sections.
     *
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     */
    public void unloadChunk(int chunkX, int chunkZ) {
        long stamp = lock.acquire(SceneDatabaseLock.Stage.MUTATION);
        try {
            ChunkKey chunkKey = new ChunkKey(chunkX, chunkZ);
            dirtyChunks.remove(chunkKey);
            removeDirtySectionsLocked(chunkKey);
            unloadedChunks.add(chunkKey);
            sectionSnapshotRemovals += removeChunkSectionsLocked(
                    chunkKey,
                    SceneUpdateBatch.sourceFlagsForChunkStreaming(),
                    RemovalProducer.CHUNK_UNLOAD
            );
            chunkUnloadMarks++;
            pendingSourceFlags |= SceneUpdateBatch.sourceFlagsForChunkStreaming();
        } finally {
            lock.release(SceneDatabaseLock.Stage.MUTATION, stamp);
        }
    }

    /**
     * Requests full scene reconciliation in the next update batch.
     */
    public void requestFullResync() {
        long stamp = lock.acquire(SceneDatabaseLock.Stage.MUTATION);
        try {
            fullResyncRequested = true;
            fullResyncRequests++;
            pendingSourceFlags = SceneUpdateBatch.sourceFlagsForFullResync(pendingSourceFlags);
        } finally {
            lock.release(SceneDatabaseLock.Stage.MUTATION, stamp);
        }
    }

    /**
     * Captures pending updates without consuming them.
     *
     * @return immutable snapshot of the current pending updates
     */
    public SceneUpdateBatch snapshotPendingUpdates() {
        long stamp = lock.acquire(SceneDatabaseLock.Stage.SNAPSHOT);
        try {
            return batchLocked();
        } finally {
            lock.release(SceneDatabaseLock.Stage.SNAPSHOT, stamp);
        }
    }

    /**
     * Captures the section identities represented by the pending update generation.
     *
     * @return immutable pending section identities
     */
    public Set<SectionKey> snapshotPendingSectionKeys() {
        return snapshotPendingSectionMetadata().sectionKeys();
    }

    /**
     * Freezes pending section membership and provenance under one database generation.
     * Stable visual-decision reads retain this publication by identity.
     *
     * @return immutable pending-section metadata publication
     */
    public PendingSectionMetadata snapshotPendingSectionMetadata() {
        long stamp = lock.acquire(SceneDatabaseLock.Stage.SNAPSHOT);
        try {
            if (cachedPendingSectionMetadata.revision() == pendingSectionMetadataRevision) {
                return cachedPendingSectionMetadata;
            }
            Map<SectionKey, Integer> pending = new HashMap<>();
            for (SectionKey key : dirtySections) {
                pending.put(key, sectionSourceFlags.getOrDefault(key, 0));
            }
            for (SectionKey key : removedSections) {
                pending.merge(
                        key,
                        SceneUpdateBatch.sourceFlagsForRemoval(sectionSourceFlags.getOrDefault(key, 0)),
                        (left, right) -> left | right
                );
            }
            cachedPendingSectionMetadata = new PendingSectionMetadata(
                    pendingSectionMetadataRevision,
                    Set.copyOf(pending.keySet()),
                    Map.copyOf(pending)
            );
            return cachedPendingSectionMetadata;
        } finally {
            lock.release(SceneDatabaseLock.Stage.SNAPSHOT, stamp);
        }
    }

    /**
     * Captures normalized provenance for each pending section.
     *
     * @return immutable per-section source masks
     */
    public Map<SectionKey, Integer> snapshotPendingSectionSourceFlags() {
        return snapshotPendingSectionMetadata().sourceFlags();
    }

    /**
     * Captures resident-source ownership, capacity pressure, and removal evidence atomically.
     *
     * @return immutable ownership snapshot
     */
    public OwnershipSnapshot ownershipSnapshot() {
        long stamp = lock.acquire(SceneDatabaseLock.Stage.SNAPSHOT);
        try {
            if (cachedOwnershipSnapshot != null
                    && cachedOwnershipPublicationRevision == ownershipPublicationRevision) {
                return cachedOwnershipSnapshot;
            }
            Set<SectionKey> residentKeys = Set.copyOf(sectionSnapshots.keySet());
            long primitivePayloadBytes = 0L;
            for (SectionVoxelSnapshot snapshot : sectionSnapshots.values()) {
                primitivePayloadBytes = Math.addExact(primitivePayloadBytes, snapshot.primitivePayloadBytes());
            }
            EvictionClassification classification = classifyEvictionOwnershipLocked();
            cachedOwnershipSnapshot = new OwnershipSnapshot(
                    ownershipPublicationRevision,
                    residentKeys,
                    visibleResidentChunks,
                    streamingResidentChunks,
                    maxResidentSectionSnapshots,
                    primitivePayloadBytes,
                    classification,
                    overCapacityEvictionPasses,
                    evictedResidentSections,
                    blockedEvictionPasses,
                    lastBlockedEviction,
                    new RemovalProvenance(
                            foregroundOmissionRemovals,
                            columnReconciliationRemovals,
                            explicitRenderDirtyRemovals,
                            explicitBlockMutationRemovals,
                            sourceEvictionRemovals,
                            chunkUnloadRemovals
                    )
            );
            cachedOwnershipPublicationRevision = ownershipPublicationRevision;
            return cachedOwnershipSnapshot;
        } finally {
            lock.release(SceneDatabaseLock.Stage.SNAPSHOT, stamp);
        }
    }

    /**
     * Captures lightweight lifecycle evidence for explicit-removal consumers.
     *
     * @return immutable removal-provenance counters
     */
    public RemovalProvenance removalProvenance() {
        long stamp = lock.acquire(SceneDatabaseLock.Stage.QUERY);
        try {
            return new RemovalProvenance(
                    foregroundOmissionRemovals,
                    columnReconciliationRemovals,
                    explicitRenderDirtyRemovals,
                    explicitBlockMutationRemovals,
                    sourceEvictionRemovals,
                    chunkUnloadRemovals
            );
        } finally {
            lock.release(SceneDatabaseLock.Stage.QUERY, stamp);
        }
    }

    /**
     * Replaces the presentation-owned section pins used by bounded source eviction.
     *
     * @param visibleSectionKeys complete visible section-pin publication
     */
    public void updateResidentSectionPins(Set<SectionKey> visibleSectionKeys) {
        Objects.requireNonNull(visibleSectionKeys, "visibleSectionKeys");
        requireBoundedSourcePins(visibleSectionKeys, "visibleSectionKeys");
        long stamp = lock.acquire(SceneDatabaseLock.Stage.MUTATION);
        try {
            Set<ChunkKey> visibleChunkKeys = new HashSet<>();
            for (SectionKey visibleSectionKey : visibleSectionKeys) {
                visibleChunkKeys.add(visibleSectionKey.chunkKey());
            }
            Set<SectionKey> nextVisibleSections = Set.copyOf(visibleSectionKeys);
            Set<ChunkKey> nextVisibleChunks = Set.copyOf(visibleChunkKeys);
            if (!visibleResidentSections.equals(nextVisibleSections)
                    || !visibleResidentChunks.equals(nextVisibleChunks)) {
                visibleResidentSections = nextVisibleSections;
                visibleResidentChunks = nextVisibleChunks;
                advanceOwnershipPublicationRevisionLocked();
            }
            evictNonResidentSourcesLocked();
        } finally {
            lock.release(SceneDatabaseLock.Stage.MUTATION, stamp);
        }
    }

    /**
     * Publishes immutable dense-source ownership held by pending/active CPU mesh work.
     *
     * <p>This is deliberately a separate lane from foreground presentation retention.  A source
     * is released from this lane as soon as its CPU result becomes renderer output, while the
     * foreground owner may independently retain or retire the corresponding visual generation.</p>
     *
     * @param sectionKeys complete section identities pinned by pending or active mesh work
     */
    public void updateInFlightSectionSourcePins(Set<SectionKey> sectionKeys) {
        Objects.requireNonNull(sectionKeys, "sectionKeys");
        requireBoundedSourcePins(sectionKeys, "inFlightSectionSourcePins");
        long stamp = lock.acquire(SceneDatabaseLock.Stage.MUTATION);
        try {
            Set<SectionKey> nextPins = sectionKeys instanceof PackedSectionMembership
                    ? sectionKeys
                    : Set.copyOf(sectionKeys);
            if (inFlightSectionSourcePins.equals(nextPins)) {
                return;
            }
            inFlightSectionSourcePins = nextPins;
            advanceOwnershipPublicationRevisionLocked();
            evictNonResidentSourcesLocked();
        } finally {
            lock.release(SceneDatabaseLock.Stage.MUTATION, stamp);
        }
    }

    /**
     * Replaces the authoritative streaming chunk-pin publication.
     *
     * @param residentChunkKeys complete resident chunk identities
     */
    public void updateResidentChunkPins(Set<ChunkKey> residentChunkKeys) {
        Objects.requireNonNull(residentChunkKeys, "residentChunkKeys");
        long stamp = lock.acquire(SceneDatabaseLock.Stage.MUTATION);
        try {
            if (!streamingResidentChunks.equals(residentChunkKeys)) {
                streamingResidentChunks.clear();
                streamingResidentChunks.addAll(residentChunkKeys);
                advanceOwnershipPublicationRevisionLocked();
                /*
                 * A full bridge publication repairs a missed delta.  Chunk residency is renderer
                 * world authority, not merely a CPU-source eviction hint: any source column absent
                 * from this authoritative window must emit the same section-removal transaction as
                 * an explicit client chunk unload, so mesh, material, BLAS and TLAS owners converge.
                 */
                sectionSnapshotRemovals += retireSourceColumnsOutsideResidentWindowLocked();
            }
            evictNonResidentSourcesLocked();
        } finally {
            lock.release(SceneDatabaseLock.Stage.MUTATION, stamp);
        }
    }

    /**
     * Applies a complete terrain-lifetime publication as one ownership transaction.
     *
     * <p>The authoritative geometry retirement is deliberately independent from
     * {@link #sectionSnapshots}. Dense snapshots are a bounded, recoverable CPU cache; a section
     * may have been evicted from that cache while its mesh, material slot and native acceleration
     * structures remain live. Gating world retirement on source-cache membership leaves those
     * downstream owners permanently reachable after the player leaves the render window.</p>
     *
     * @param residentChunkKeys       complete authoritative resident chunk identities
     * @param retiredGeometrySections exact geometry sections retired from renderer-world ownership
     */
    public void reconcileTerrainResidency(
            Set<ChunkKey> residentChunkKeys,
            Set<SectionKey> retiredGeometrySections
    ) {
        Objects.requireNonNull(residentChunkKeys, "residentChunkKeys");
        Objects.requireNonNull(retiredGeometrySections, "retiredGeometrySections");
        long stamp = lock.acquire(SceneDatabaseLock.Stage.MUTATION);
        try {
            if (!streamingResidentChunks.equals(residentChunkKeys)) {
                streamingResidentChunks.clear();
                streamingResidentChunks.addAll(residentChunkKeys);
                advanceOwnershipPublicationRevisionLocked();
            }
            retireAuthoritativeGeometrySectionsLocked(retiredGeometrySections);
            sectionSnapshotRemovals += retireSourceColumnsOutsideResidentWindowLocked();
            evictNonResidentSourcesLocked();
        } finally {
            lock.release(SceneDatabaseLock.Stage.MUTATION, stamp);
        }
    }

    /**
     * Applies the bridge's packet/cache membership delta without rebuilding a
     * full render-distance set.  Source eviction remains under the database
     * lock, so a section cannot be observed between its chunk leaving the
     * window and the corresponding ownership cleanup.
     *
     * @param enteredChunks chunk identities entering streaming residency
     * @param leftChunks    chunk identities leaving streaming residency
     */
    public void applyResidentChunkPinDelta(Set<ChunkKey> enteredChunks, Set<ChunkKey> leftChunks) {
        Objects.requireNonNull(enteredChunks, "enteredChunks");
        Objects.requireNonNull(leftChunks, "leftChunks");
        if (enteredChunks.isEmpty() && leftChunks.isEmpty()) {
            return;
        }
        long stamp = lock.acquire(SceneDatabaseLock.Stage.MUTATION);
        try {
            boolean membershipChanged = streamingResidentChunks.addAll(enteredChunks);
            membershipChanged |= streamingResidentChunks.removeAll(leftChunks);
            if (membershipChanged) {
                advanceOwnershipPublicationRevisionLocked();
            }
            /*
             * Do not wait for the bounded source cache to overflow.  A player crossing the 32-chunk
             * boundary has removed these columns from the authoritative world now; publishing their
             * section removals is the only path that retires CPU meshes and native RT resources.
             */
            sectionSnapshotRemovals += retireDepartedResidentChunkColumnsLocked(leftChunks);
            evictNonResidentSourcesLocked();
        } finally {
            lock.release(SceneDatabaseLock.Stage.MUTATION, stamp);
        }
    }

    /**
     * Applies an in-order terrain-lifetime delta atomically across world and source ownership.
     *
     * <p>{@code retiredGeometrySections} is the renderer-world fact. {@code leftChunks} is only
     * the source-column cleanup fact. Keeping them separate prevents CPU-cache pressure from
     * deciding whether a BLAS/TLAS/material retirement is published.</p>
     *
     * @param enteredChunks           chunk identities entering streaming residency
     * @param leftChunks              chunk identities leaving streaming residency
     * @param retiredGeometrySections exact geometry sections retired from renderer-world ownership
     */
    public void applyTerrainResidencyDelta(
            Set<ChunkKey> enteredChunks,
            Set<ChunkKey> leftChunks,
            Set<SectionKey> retiredGeometrySections
    ) {
        Objects.requireNonNull(enteredChunks, "enteredChunks");
        Objects.requireNonNull(leftChunks, "leftChunks");
        Objects.requireNonNull(retiredGeometrySections, "retiredGeometrySections");
        if (enteredChunks.isEmpty() && leftChunks.isEmpty() && retiredGeometrySections.isEmpty()) {
            return;
        }
        long stamp = lock.acquire(SceneDatabaseLock.Stage.MUTATION);
        try {
            boolean membershipChanged = streamingResidentChunks.addAll(enteredChunks);
            membershipChanged |= streamingResidentChunks.removeAll(leftChunks);
            if (membershipChanged) {
                advanceOwnershipPublicationRevisionLocked();
            }
            retireAuthoritativeGeometrySectionsLocked(retiredGeometrySections);
            sectionSnapshotRemovals += retireDepartedResidentChunkColumnsLocked(leftChunks);
            evictNonResidentSourcesLocked();
        } finally {
            lock.release(SceneDatabaseLock.Stage.MUTATION, stamp);
        }
    }

    /**
     * Counts resident dense section snapshots.
     *
     * @return current resident section count
     */
    public int residentSectionCount() {
        long stamp = lock.acquire(SceneDatabaseLock.Stage.QUERY);
        try {
            return sectionSnapshots.size();
        } finally {
            lock.release(SceneDatabaseLock.Stage.QUERY, stamp);
        }
    }

    /**
     * Returns whether the renderer still owns a source payload for this exact
     * section.  This is deliberately narrower than chunk residency: bridge
     * scheduling uses it only to diagnose duplicate foreground extraction.
     *
     * @param key section identity to inspect
     * @return {@code true} when the exact section source is resident
     */
    public boolean hasResidentSection(SectionKey key) {
        Objects.requireNonNull(key, "key");
        long stamp = lock.acquire(SceneDatabaseLock.Stage.QUERY);
        try {
            return sectionSnapshots.containsKey(key);
        } finally {
            lock.release(SceneDatabaseLock.Stage.QUERY, stamp);
        }
    }

    /**
     * Returns the immutable exact source-membership publication without rescanning source maps.
     *
     * @return immutable resident-source membership publication
     */
    public PackedSectionMembership residentSectionSourceMembership() {
        long stamp = lock.acquire(SceneDatabaseLock.Stage.QUERY);
        try {
            if (!pendingResidentSourceEntries.isEmpty() || !pendingResidentSourceRetirements.isEmpty()) {
                residentSourceMembership = residentSourceMembership.withDelta(
                        pendingResidentSourceEntries,
                        pendingResidentSourceRetirements
                );
                pendingResidentSourceEntries.clear();
                pendingResidentSourceRetirements.clear();
            }
            return residentSourceMembership;
        } finally {
            lock.release(SceneDatabaseLock.Stage.QUERY, stamp);
        }
    }

    /**
     * Classifies a complete source request under one database generation.
     *
     * <p>The host bridge evaluates thousands of visible sections at a
     * time. Acquiring this database's query lock once per section both obscures
     * the generation boundary and turns a read-only classification into a
     * main-thread lock/hash hot path. Returning the exact missing subset keeps
     * the bridge's scheduling policy outside the database while giving that
     * policy one coherent resident-source view.</p>
     *
     * @param requestedSectionKeys complete section-source request
     * @return immutable subset whose dense source is not resident
     */
    public Set<SectionKey> missingResidentSectionSources(Set<SectionKey> requestedSectionKeys) {
        return missingResidentSectionSourcesWithoutResolvedOutput(
                requestedSectionKeys,
                NO_RESOLVED_OUTPUT_SECTIONS
        );
    }

    /**
     * Exact bootstrap query under one source generation. A resolved output is
     * authoritative even when its dense CPU source was evicted; this includes
     * intentionally empty sections that have no renderable mesh membership.
     *
     * @param requestedSectionKeys      complete section-source request
     * @param resolvedOutputSectionKeys sections already represented by resolved renderer output
     * @return immutable subset lacking both resident source and resolved output
     */
    public Set<SectionKey> missingResidentSectionSourcesWithoutResolvedOutput(
            Set<SectionKey> requestedSectionKeys,
            Set<SectionKey> resolvedOutputSectionKeys
    ) {
        Objects.requireNonNull(requestedSectionKeys, "requestedSectionKeys");
        Objects.requireNonNull(resolvedOutputSectionKeys, "resolvedOutputSectionKeys");
        if (requestedSectionKeys.isEmpty()) {
            return Set.of();
        }
        long stamp = lock.acquire(SceneDatabaseLock.Stage.QUERY);
        try {
            boolean cacheable = requestedSectionKeys instanceof PackedSectionMembership
                    && (resolvedOutputSectionKeys == NO_RESOLVED_OUTPUT_SECTIONS
                    || resolvedOutputSectionKeys instanceof PackedSectionMembership);
            if (cacheable
                    && cachedMissingSourceRequestIdentity == requestedSectionKeys
                    && cachedMissingSourceResolvedIdentity == resolvedOutputSectionKeys
                    && cachedMissingSourceRevision == residentSectionSourceRevision) {
                return cachedMissingSourcePublication;
            }
            missingSourceMembershipBuilder.reset(requestedSectionKeys.size());
            int missingCount = 0;
            for (SectionKey key : requestedSectionKeys) {
                Objects.requireNonNull(key, "requested section key");
                if (!sectionSnapshots.containsKey(key) && !resolvedOutputSectionKeys.contains(key)) {
                    missingCount += missingSourceMembershipBuilder.add(key) ? 1 : 0;
                }
            }
            Set<SectionKey> previousPublication = cacheable
                    ? cachedMissingSourcePublication
                    : Set.of();
            Set<SectionKey> nextPublication;
            if (missingCount == 0) {
                nextPublication = Set.of();
            } else {
                PackedSectionMembership previousPacked = previousPublication instanceof PackedSectionMembership packed
                        ? packed
                        : null;
                nextPublication = missingSourceMembershipBuilder.buildCanonical(previousPacked);
            }
            if (cacheable) {
                cachedMissingSourceRequestIdentity = requestedSectionKeys;
                cachedMissingSourceResolvedIdentity = resolvedOutputSectionKeys;
                cachedMissingSourceRevision = residentSectionSourceRevision;
                cachedMissingSourcePublication = nextPublication;
            }
            return nextPublication;
        } finally {
            lock.release(SceneDatabaseLock.Stage.QUERY, stamp);
        }
    }

    /**
     * Returns the latest renderer-owned generation for deferred worker submission.
     * The pending scheduler stores only section identity and ordering metadata, so
     * this lookup prevents a second long-lived copy of every 16x16x16 voxel payload.
     *
     * @param key section identity to inspect
     * @return latest resident snapshot, or {@code null} when no source is resident
     */
    public SectionVoxelSnapshot snapshotResidentSection(SectionKey key) {
        Objects.requireNonNull(key, "key");
        long stamp = lock.acquire(SceneDatabaseLock.Stage.QUERY);
        try {
            return sectionSnapshots.get(key);
        } finally {
            lock.release(SceneDatabaseLock.Stage.QUERY, stamp);
        }
    }

    /**
     * Requeues only source snapshots still owned by this database. This is the
     * recovery path for a bounded RT CPU-mesh staging cache: dropping a mesh
     * payload must request a fresh build from immutable voxel data, never be
     * represented as a world-section removal.
     *
     * @param sectionKeys section identities whose mesh payloads require recovery
     * @return immutable subset successfully requeued from resident sources
     */
    public Set<SectionKey> requestResidentSectionRebuilds(Set<SectionKey> sectionKeys) {
        Objects.requireNonNull(sectionKeys, "sectionKeys");
        if (sectionKeys.isEmpty()) {
            return Set.of();
        }
        long stamp = lock.acquire(SceneDatabaseLock.Stage.MUTATION);
        try {
            Set<SectionKey> accepted = new HashSet<>();
            for (SectionKey key : sectionKeys) {
                Objects.requireNonNull(key, "section key");
                if (!sectionSnapshots.containsKey(key)) {
                    continue;
                }
                dirtySections.add(key);
                dirtyChunks.add(key.chunkKey());
                mergeSectionSourceFlags(key, SceneUpdateBatch.sourceFlagsForChunkStreaming());
                accepted.add(key);
            }
            return Set.copyOf(accepted);
        } finally {
            lock.release(SceneDatabaseLock.Stage.MUTATION, stamp);
        }
    }

    /**
     * Returns the renderer-owned section sources that are still inside the
     * authoritative client-cache window.  A raster frustum is intentionally
     * not consulted here: it is direction dependent and may omit a resident
     * section until host has compiled it for that particular view.
     *
     * @param residentChunkKeys authoritative resident chunk identities
     * @return immutable resident section identities inside those chunks
     */
    public Set<SectionKey> snapshotResidentSectionKeys(Set<ChunkKey> residentChunkKeys) {
        Objects.requireNonNull(residentChunkKeys, "residentChunkKeys");
        long stamp = lock.acquire(SceneDatabaseLock.Stage.SNAPSHOT);
        try {
            Set<SectionKey> result = new HashSet<>();
            for (SectionKey key : sectionSnapshots.keySet()) {
                if (residentChunkKeys.contains(key.chunkKey())) {
                    result.add(key);
                }
            }
            return Set.copyOf(result);
        } finally {
            lock.release(SceneDatabaseLock.Stage.SNAPSHOT, stamp);
        }
    }

    /**
     * Identifies source membership/content changes without materializing an
     * ownership snapshot. Coverage consumers use it to suppress direction-only
     * frustum work.
     *
     * @return current resident-source publication revision
     */
    public long residentSectionSourceRevision() {
        long stamp = lock.acquire(SceneDatabaseLock.Stage.QUERY);
        try {
            return residentSectionSourceRevision;
        } finally {
            lock.release(SceneDatabaseLock.Stage.QUERY, stamp);
        }
    }

    /**
     * Returns the configured upper bound, distinct from the current resident section count.
     *
     * @return maximum resident dense section snapshots
     */
    public int residentSectionCapacityLimit() {
        long stamp = lock.acquire(SceneDatabaseLock.Stage.QUERY);
        try {
            return maxResidentSectionSnapshots;
        } finally {
            lock.release(SceneDatabaseLock.Stage.QUERY, stamp);
        }
    }

    /**
     * Indicates whether a chunk still owns any renderer source after bounded LRU
     * eviction. Packet history alone is insufficient to reuse a source generation.
     *
     * @param chunkKey chunk identity to inspect
     * @return {@code true} when at least one source remains resident in the chunk
     */
    public boolean hasResidentChunkSource(ChunkKey chunkKey) {
        Objects.requireNonNull(chunkKey, "chunkKey");
        long stamp = lock.acquire(SceneDatabaseLock.Stage.QUERY);
        try {
            for (SectionKey key : sectionSnapshots.keySet()) {
                if (chunkKey.equals(key.chunkKey())) {
                    return true;
                }
            }
            return false;
        } finally {
            lock.release(SceneDatabaseLock.Stage.QUERY, stamp);
        }
    }

    /**
     * Tests source reuse against the exact section ticket published by
     * host. Chunk-column presence is deliberately insufficient: a source
     * at one Y coordinate must not authorize a different requested Y in the
     * same X/Z column.
     *
     * @param requestedSectionKeys exact section identities required for reuse
     * @return {@code true} when every requested section source is resident
     */
    public boolean hasResidentSectionSources(Set<SectionKey> requestedSectionKeys) {
        Objects.requireNonNull(requestedSectionKeys, "requestedSectionKeys");
        long stamp = lock.acquire(SceneDatabaseLock.Stage.QUERY);
        try {
            for (SectionKey key : requestedSectionKeys) {
                if (!sectionSnapshots.containsKey(key)) {
                    return false;
                }
            }
            return true;
        } finally {
            lock.release(SceneDatabaseLock.Stage.QUERY, stamp);
        }
    }

    /**
     * Returns an atomic immutable snapshot of available neighboring sections.
     *
     * @param key center section identity
     * @return immutable neighborhood around the requested center
     */
    public SectionNeighborhood snapshotSectionNeighborhood(SectionKey key) {
        Objects.requireNonNull(key, "key");
        long stamp = lock.acquire(SceneDatabaseLock.Stage.SNAPSHOT);
        try {
            Map<SectionKey, SectionVoxelSnapshot> neighbors = new HashMap<>();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        SectionKey neighborKey = new SectionKey(key.x() + dx, key.y() + dy, key.z() + dz);
                        SectionVoxelSnapshot snapshot = sectionSnapshots.get(neighborKey);
                        if (snapshot != null) {
                            neighbors.put(neighborKey, snapshot);
                        }
                    }
                }
            }
            return SectionNeighborhood.fromSnapshots(key, neighbors);
        } finally {
            lock.release(SceneDatabaseLock.Stage.SNAPSHOT, stamp);
        }
    }

    /**
     * Atomically captures the center payload, its immutable 3x3x3 neighborhood,
     * and the exact source revisions represented by that input.
     *
     * <p>The revision vector is the renderer equivalent of a production renderer's persistent
     * primitive dirty state. It lets downstream scheduling prove that a later
     * neighbor notification describes the exact input already built, without
     * retaining old voxel snapshots or relying on a collision-prone hash.</p>
     *
     * @param key center section identity
     * @return coherent build input, or {@code null} when the center source is absent
     */
    public SectionBuildInput snapshotSectionBuildInput(SectionKey key) {
        Objects.requireNonNull(key, "key");
        long stamp = lock.acquire(SceneDatabaseLock.Stage.SNAPSHOT);
        try {
            SectionVoxelSnapshot center = sectionSnapshots.get(key);
            if (center == null) {
                return null;
            }
            Map<SectionKey, SectionVoxelSnapshot> neighbors = new HashMap<>();
            long[] revisions = new long[SectionNeighborhoodRevision.ENTRY_COUNT];
            boolean coherentCompiledBoundary = center.capturedBoundary() != null;
            int index = 0;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        SectionKey sourceKey = new SectionKey(key.x() + dx, key.y() + dy, key.z() + dz);
                        revisions[index++] = coherentCompiledBoundary && (dx != 0 || dy != 0 || dz != 0)
                                ? 0L
                                : sectionGeometryRevisions.getOrDefault(sourceKey, 0L);
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        if (!coherentCompiledBoundary) {
                            SectionVoxelSnapshot neighbor = sectionSnapshots.get(sourceKey);
                            if (neighbor != null) {
                                neighbors.put(sourceKey, neighbor);
                            }
                        }
                    }
                }
            }
            return new SectionBuildInput(
                    center,
                    SectionNeighborhood.fromSnapshots(key, neighbors, center.capturedBoundary()),
                    new SectionNeighborhoodRevision(revisions)
            );
        } finally {
            lock.release(SceneDatabaseLock.Stage.SNAPSHOT, stamp);
        }
    }

    /**
     * Captures the available face-adjacent section snapshots.
     *
     * @param key center section identity
     * @return immutable map of face direction to available neighboring snapshot
     */
    public Map<FaceDirection, SectionVoxelSnapshot> snapshotNeighborSections(SectionKey key) {
        return faceNeighbors(snapshotSectionNeighborhood(key));
    }

    /**
     * Atomically captures and consumes the current pending update generation.
     *
     * @return immutable drained update batch
     */
    public SceneUpdateBatch drainPendingUpdates() {
        long stamp = lock.acquire(SceneDatabaseLock.Stage.DRAIN);
        try {
            SceneUpdateBatch batch = batchLocked();
            boolean hadPendingSectionMetadata = !dirtySections.isEmpty()
                    || !removedSections.isEmpty()
                    || !unloadedChunks.isEmpty()
                    || fullResyncRequested;
            dirtySections.clear();
            dirtyChunks.clear();
            removedSections.clear();
            unloadedChunks.clear();
            sectionSourceFlags.clear();
            fullResyncRequested = false;
            pendingSourceFlags = 0;
            if (hadPendingSectionMetadata) {
                advancePendingSectionMetadataRevision();
            }
            return batch;
        } finally {
            lock.release(SceneDatabaseLock.Stage.DRAIN, stamp);
        }
    }

    /**
     * Clears pending work, resident sources, ownership pins, and lifecycle evidence.
     */
    public void clear() {
        long stamp = lock.acquire(SceneDatabaseLock.Stage.RESET);
        try {
            boolean hadPendingSectionMetadata = !dirtySections.isEmpty()
                    || !removedSections.isEmpty()
                    || !unloadedChunks.isEmpty()
                    || fullResyncRequested;
            dirtySections.clear();
            dirtyChunks.clear();
            removedSections.clear();
            unloadedChunks.clear();
            if (!sectionSnapshots.isEmpty()) {
                advanceResidentSectionSourceRevisionLocked();
            }
            sectionSnapshots.clear();
            residentSourceMembership = PackedSectionMembership.empty();
            pendingResidentSourceEntries.clear();
            pendingResidentSourceRetirements.clear();
            sectionKeysByChunk.clear();
            sectionGeometryRevisions.clear();
            sectionSourceFlags.clear();
            visibleResidentSections = Set.of();
            inFlightSectionSourcePins = Set.of();
            cachedMissingSourceRequestIdentity = Set.of();
            cachedMissingSourceResolvedIdentity = Set.of();
            cachedMissingSourceRevision = -1L;
            cachedMissingSourcePublication = Set.of();
            visibleResidentChunks = Set.of();
            streamingResidentChunks.clear();
            fullResyncRequested = false;
            pendingSourceFlags = 0;
            nextSectionGeometryRevision = 1L;
            lastBlockedEviction = EvictionClassification.empty();
            foregroundOmissionRemovals = 0L;
            columnReconciliationRemovals = 0L;
            explicitRenderDirtyRemovals = 0L;
            explicitBlockMutationRemovals = 0L;
            sourceEvictionRemovals = 0L;
            chunkUnloadRemovals = 0L;
            advanceOwnershipPublicationRevisionLocked();
            if (hadPendingSectionMetadata) {
                advancePendingSectionMetadataRevision();
            }
        } finally {
            lock.release(SceneDatabaseLock.Stage.RESET, stamp);
        }
    }

    /**
     * Formats the pending update and lock telemetry as a stable diagnostic line.
     *
     * @return stable scene-database summary
     */
    public String summary() {
        return snapshotPendingUpdates().summary() + ", " + lock.snapshot().asLogFragment();
    }

    SceneDatabaseLock.Snapshot lockTelemetrySnapshot() {
        return lock.snapshot();
    }

    private void markSectionDirtyLocked(int sectionX, int sectionY, int sectionZ, int sourceFlags) {
        SectionKey key = new SectionKey(sectionX, sectionY, sectionZ);
        ChunkKey chunkKey = key.chunkKey();
        sectionDirtyMarks++;
        pendingSourceFlags |= sourceFlags;
        if (unloadedChunks.contains(chunkKey)) {
            return;
        }
        dirtySections.add(key);
        dirtyChunks.add(chunkKey);
        mergeSectionSourceFlags(key, sourceFlags);
    }

    private boolean replaceSectionSnapshotLocked(SectionVoxelSnapshot snapshot, int sourceFlags) {
        return replaceSectionSnapshotLocked(snapshot, sourceFlags, true, null, true);
    }

    private boolean replaceSectionSnapshotLocked(
            SectionVoxelSnapshot snapshot,
            int sourceFlags,
            boolean markNeighborsImmediately,
            Set<SectionKey> deferredNeighborDirtySources,
            boolean evictAfterReplace
    ) {
        SectionKey key = snapshot.key();
        ChunkKey chunkKey = key.chunkKey();
        if (unloadedChunks.contains(chunkKey)) {
            return false;
        }
        SectionVoxelSnapshot previous = sectionSnapshots.get(key);
        if (previous != null
                && previous.capturedBoundary() != null
                && snapshot.capturedBoundary() == null
                && previous.hasSameCenterVoxelContent(snapshot)) {
            /*
             * Packet/chunk source publication can race a compiled-region source
             * for the same center generation. Do not discard the stronger
             * coherent halo when the center payload is byte-for-byte identical.
             * A real center change still drops the old halo and returns to
             * explicit SceneDatabase neighbor dependencies until host
             * publishes a new immutable region for that center.
             */
            snapshot = snapshot.withCapturedBoundary(previous.capturedBoundary());
        }
        if (previous != null && previous.hasSameVoxelContent(snapshot)) {
            return false;
        }
        int effectiveSourceFlags = SceneUpdateBatch.sourceFlagsForDirectContent(sourceFlags);
        boolean materialOnly = previous != null && previous.hasSameGeometryContent(snapshot);
        if (materialOnly) {
            effectiveSourceFlags = SceneUpdateBatch.sourceFlagsForMaterialOnly(effectiveSourceFlags);
        }
        pendingSourceFlags |= effectiveSourceFlags;
        sectionSnapshots.put(key, snapshot);
        if (previous == null) {
            recordResidentSourceEntryLocked(key);
            sectionKeysByChunk.computeIfAbsent(
                    ChunkKey.pack(key.x(), key.z()),
                    ignored -> new HashSet<>()
            ).add(key);
        }
        advanceResidentSectionSourceRevisionLocked();
        if (!materialOnly) {
            sectionGeometryRevisions.put(key, nextSectionGeometryRevisionLocked());
        }
        dirtySections.add(key);
        dirtyChunks.add(chunkKey);
        removedSections.remove(key);
        mergeSectionSourceFlags(key, effectiveSourceFlags);
        if (!materialOnly) {
            if (markNeighborsImmediately) {
                markExistingNeighborSectionsDirtyLocked(key, sourceFlags);
            } else {
                Objects.requireNonNull(deferredNeighborDirtySources, "deferredNeighborDirtySources").add(key);
            }
        }
        if (evictAfterReplace) {
            evictNonResidentSourcesLocked();
        }
        return true;
    }

    private void evictNonResidentSourcesLocked() {
        if (sectionSnapshots.size() > maxResidentSectionSnapshots) {
            overCapacityEvictionPasses++;
            advanceOwnershipPublicationRevisionLocked();
        }
        while (sectionSnapshots.size() > maxResidentSectionSnapshots) {
            boolean evicted = false;
            Iterator<Map.Entry<SectionKey, SectionVoxelSnapshot>> iterator = sectionSnapshots.entrySet().iterator();
            while (iterator.hasNext()) {
                SectionKey key = iterator.next().getKey();
                if (visibleResidentSections.contains(key)
                        || inFlightSectionSourcePins.contains(key)
                        || dirtySections.contains(key)) {
                    continue;
                }
                iterator.remove();
                recordResidentSourceRetirementLocked(key);
                unindexSectionKeyLocked(key);
                advanceResidentSectionSourceRevisionLocked();
                sectionGeometryRevisions.remove(key);
                dirtySections.remove(key);
                sectionSourceFlags.remove(key);
                /*
                 * This is CPU-source cache pressure, not a host world
                 * lifecycle event. The committed mesh/BLAS/TLAS can still
                 * render this section and the bridge can re-extract its source
                 * later if a new build requires it. Publishing a removal here
                 * used to make an LRU decision tear down valid RT world slots.
                 */
                sourceEvictionRemovals++;
                evicted = true;
                evictedResidentSections++;
                break;
            }
            if (!evicted) {
                blockedEvictionPasses++;
                lastBlockedEviction = classifyEvictionOwnershipLocked();
                advanceOwnershipPublicationRevisionLocked();
                return;
            }
        }
    }

    /**
     * Reconciles a complete resident-window publication with source ownership.  Iterating immutable
     * chunk identities before mutation keeps removal atomic while {@link #removeChunkSectionsLocked}
     * updates the direct source index.
     */
    private int retireSourceColumnsOutsideResidentWindowLocked() {
        Set<ChunkKey> departedChunks = new HashSet<>();
        for (SectionKey key : sectionSnapshots.keySet()) {
            ChunkKey chunkKey = key.chunkKey();
            if (!streamingResidentChunks.contains(chunkKey)) {
                departedChunks.add(chunkKey);
            }
        }
        return retireDepartedResidentChunkColumnsLocked(departedChunks);
    }

    /**
     * Converts an authoritative resident-window departure into normal section-removal work.
     * A column that re-entered in the same publication is deliberately retained.
     */
    private int retireDepartedResidentChunkColumnsLocked(Set<ChunkKey> departedChunks) {
        int removed = 0;
        int sourceFlags = SceneUpdateBatch.sourceFlagsForChunkStreaming();
        for (ChunkKey chunkKey : departedChunks) {
            if (!streamingResidentChunks.contains(chunkKey)) {
                removed += removeChunkSectionsLocked(
                        chunkKey,
                        sourceFlags,
                        RemovalProducer.COLUMN_RECONCILIATION
                );
            }
        }
        return removed;
    }

    /**
     * Publishes renderer-world retirement even when the recoverable dense source is already gone.
     *
     * <p>This is the ownership firewall between authoritative terrain lifetime and the bounded CPU
     * source cache. Every newly retired key becomes normal {@link #removedSections} work, while
     * source payload cleanup remains conditional and idempotent.</p>
     */
    private void retireAuthoritativeGeometrySectionsLocked(Set<SectionKey> retiredGeometrySections) {
        if (retiredGeometrySections.isEmpty()) {
            return;
        }
        int sourceFlags = SceneUpdateBatch.sourceFlagsForChunkStreaming();
        int removalFlags = SceneUpdateBatch.sourceFlagsForRemoval(sourceFlags);
        pendingSourceFlags |= removalFlags;
        boolean sourceOwnershipChanged = false;
        boolean worldRemovalChanged = false;
        for (SectionKey key : retiredGeometrySections) {
            Objects.requireNonNull(key, "retired geometry section");
            ChunkKey chunkKey = key.chunkKey();
            dirtySections.remove(key);
            SectionVoxelSnapshot removedSource = sectionSnapshots.remove(key);
            if (removedSource != null) {
                recordResidentSourceRetirementLocked(key);
                unindexSectionKeyLocked(key);
                advanceResidentSectionSourceRevisionLocked();
                sectionSnapshotRemovals++;
                sourceOwnershipChanged = true;
            }
            sectionGeometryRevisions.remove(key);
            if (removedSections.add(key)) {
                recordChunkRemovalLocked(RemovalProducer.COLUMN_RECONCILIATION);
                worldRemovalChanged = true;
            }
            dirtyChunks.add(chunkKey);
            mergeSectionSourceFlags(key, removalFlags);
            markExistingNeighborSectionsDirtyLocked(key, sourceFlags);
        }
        if (worldRemovalChanged && !sourceOwnershipChanged) {
            /* Refresh cached provenance even when no dense-source membership changed. */
            advanceOwnershipPublicationRevisionLocked();
        }
    }

    private void requireBoundedSourcePins(Set<SectionKey> sectionKeys, String name) {
        if (sectionKeys.size() > maxResidentSectionSnapshots) {
            throw new IllegalArgumentException(
                    name + " exceeds resident source cache capacity: "
                            + sectionKeys.size() + " > " + maxResidentSectionSnapshots
            );
        }
    }

    private EvictionClassification classifyEvictionOwnershipLocked() {
        int evictable = 0;
        int visibleOnly = 0;
        int streamingOnly = 0;
        int both = 0;
        for (SectionKey key : sectionSnapshots.keySet()) {
            boolean visible = visibleResidentSections.contains(key)
                    || inFlightSectionSourcePins.contains(key);
            boolean streaming = streamingResidentChunks.contains(key.chunkKey());
            if (visible && streaming) {
                both++;
            } else if (visible) {
                visibleOnly++;
            } else if (streaming) {
                streamingOnly++;
            } else {
                evictable++;
            }
        }
        return new EvictionClassification(evictable, visibleOnly, streamingOnly, both);
    }

    private void advanceResidentSectionSourceRevisionLocked() {
        if (residentSectionSourceRevision == Long.MAX_VALUE) {
            throw new IllegalStateException("resident section source revision space exhausted");
        }
        residentSectionSourceRevision++;
        advanceOwnershipPublicationRevisionLocked();
    }

    private void recordResidentSourceEntryLocked(SectionKey key) {
        if (!pendingResidentSourceRetirements.remove(key)) {
            pendingResidentSourceEntries.add(key);
        }
    }

    private void recordResidentSourceRetirementLocked(SectionKey key) {
        if (!pendingResidentSourceEntries.remove(key)) {
            pendingResidentSourceRetirements.add(key);
        }
    }

    private void advanceOwnershipPublicationRevisionLocked() {
        if (ownershipPublicationRevision == Long.MAX_VALUE) {
            throw new IllegalStateException("scene ownership publication revision space exhausted");
        }
        ownershipPublicationRevision++;
        cachedOwnershipSnapshot = null;
    }

    private void removeSectionSnapshotLocked(SectionKey key, int sourceFlags) {
        ChunkKey chunkKey = key.chunkKey();
        pendingSourceFlags |= SceneUpdateBatch.sourceFlagsForRemoval(sourceFlags);
        if (unloadedChunks.contains(chunkKey)) {
            return;
        }
        dirtySections.remove(key);
        if (sectionSnapshots.remove(key) != null) {
            recordResidentSourceRetirementLocked(key);
            unindexSectionKeyLocked(key);
            advanceResidentSectionSourceRevisionLocked();
            sectionGeometryRevisions.remove(key);
            recordExplicitSectionRemovalLocked(sourceFlags);
            removedSections.add(key);
            dirtyChunks.add(chunkKey);
            mergeSectionSourceFlags(key, SceneUpdateBatch.sourceFlagsForRemoval(sourceFlags));
            sectionSnapshotRemovals++;
            markExistingNeighborSectionsDirtyLocked(key, sourceFlags);
        }
    }

    private int removeChunkSectionsLocked(ChunkKey chunkKey) {
        return removeChunkSectionsLocked(chunkKey, Set.of(), 0, RemovalProducer.COLUMN_RECONCILIATION);
    }

    private int removeStaleChunkSectionsLocked(
            ChunkKey chunkKey,
            Set<SectionKey> retainedSections,
            int sourceFlags
    ) {
        return removeChunkSectionsLocked(chunkKey, retainedSections, sourceFlags, RemovalProducer.COLUMN_RECONCILIATION);
    }

    private int removeChunkSectionsLocked(ChunkKey chunkKey, int sourceFlags) {
        return removeChunkSectionsLocked(chunkKey, Set.of(), sourceFlags, RemovalProducer.COLUMN_RECONCILIATION);
    }

    private int removeChunkSectionsLocked(ChunkKey chunkKey, int sourceFlags, RemovalProducer producer) {
        return removeChunkSectionsLocked(chunkKey, Set.of(), sourceFlags, producer);
    }

    private int removeChunkSectionsLocked(ChunkKey chunkKey, Set<SectionKey> retainedSections, int sourceFlags) {
        return removeChunkSectionsLocked(
                chunkKey, retainedSections, sourceFlags, true, null, RemovalProducer.COLUMN_RECONCILIATION
        );
    }

    private int removeChunkSectionsLocked(
            ChunkKey chunkKey,
            Set<SectionKey> retainedSections,
            int sourceFlags,
            boolean markNeighborsImmediately,
            Set<SectionKey> deferredNeighborDirtySources,
            RemovalProducer producer
    ) {
        int removedCount = 0;
        List<SectionKey> removedKeys = new ArrayList<>();
        long packedChunk = chunkKey.packed();
        Set<SectionKey> indexedSections = sectionKeysByChunk.get(packedChunk);
        if (indexedSections == null || indexedSections.isEmpty()) {
            return 0;
        }
        Iterator<SectionKey> iterator = indexedSections.iterator();
        while (iterator.hasNext()) {
            SectionKey sectionKey = iterator.next();
            if (retainedSections.contains(sectionKey)) {
                continue;
            }
            iterator.remove();
            if (sectionSnapshots.remove(sectionKey) == null) {
                throw new IllegalStateException("section source chunk index diverged for " + sectionKey);
            }
            recordResidentSourceRetirementLocked(sectionKey);
            advanceResidentSectionSourceRevisionLocked();
            sectionGeometryRevisions.remove(sectionKey);
            recordChunkRemovalLocked(producer);
            dirtySections.remove(sectionKey);
            removedSections.add(sectionKey);
            mergeSectionSourceFlags(sectionKey, SceneUpdateBatch.sourceFlagsForRemoval(sourceFlags));
            removedKeys.add(sectionKey);
            removedCount++;
        }
        if (indexedSections.isEmpty()) {
            sectionKeysByChunk.remove(packedChunk);
        }
        if (removedCount > 0) {
            pendingSourceFlags |= SceneUpdateBatch.sourceFlagsForRemoval(sourceFlags);
        }
        if (markNeighborsImmediately) {
            for (SectionKey removedKey : removedKeys) {
                markExistingNeighborSectionsDirtyLocked(removedKey, sourceFlags);
            }
        } else if (!removedKeys.isEmpty()) {
            Objects.requireNonNull(deferredNeighborDirtySources, "deferredNeighborDirtySources").addAll(removedKeys);
        }
        return removedCount;
    }

    private void unindexSectionKeyLocked(SectionKey key) {
        long packedChunk = ChunkKey.pack(key.x(), key.z());
        Set<SectionKey> indexedSections = sectionKeysByChunk.get(packedChunk);
        if (indexedSections == null || !indexedSections.remove(key)) {
            throw new IllegalStateException("section source chunk index missing " + key);
        }
        if (indexedSections.isEmpty()) {
            sectionKeysByChunk.remove(packedChunk);
        }
    }

    private int removeChunkSectionsLocked(
            ChunkKey chunkKey,
            Set<SectionKey> retainedSections,
            int sourceFlags,
            RemovalProducer producer
    ) {
        return removeChunkSectionsLocked(chunkKey, retainedSections, sourceFlags, true, null, producer);
    }

    private void markExistingNeighborSectionsDirtyLocked(SectionKey key, int sourceFlags) {
        int neighborSourceFlags = SceneUpdateBatch.sourceFlagsForNeighborDependency(sourceFlags);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    SectionKey neighborKey = new SectionKey(key.x() + dx, key.y() + dy, key.z() + dz);
                    SectionVoxelSnapshot neighbor = sectionSnapshots.get(neighborKey);
                    if (neighbor != null && neighbor.capturedBoundary() == null) {
                        dirtySections.add(neighborKey);
                        dirtyChunks.add(neighborKey.chunkKey());
                        pendingSourceFlags |= neighborSourceFlags;
                        mergeSectionSourceFlags(neighborKey, neighborSourceFlags);
                    }
                }
            }
        }
    }

    private long nextSectionGeometryRevisionLocked() {
        if (nextSectionGeometryRevision == Long.MAX_VALUE) {
            throw new IllegalStateException("section geometry revision space exhausted");
        }
        return nextSectionGeometryRevision++;
    }

    private void removeDirtySectionsLocked(ChunkKey chunkKey) {
        Iterator<SectionKey> iterator = dirtySections.iterator();
        boolean removed = false;
        while (iterator.hasNext()) {
            SectionKey key = iterator.next();
            if (key.x() == chunkKey.x() && key.z() == chunkKey.z()) {
                iterator.remove();
                removed = true;
            }
        }
        if (removed) {
            advancePendingSectionMetadataRevision();
        }
    }

    private void mergeSectionSourceFlags(SectionKey key, int sourceFlags) {
        int sanitized = sanitizeSourceFlags(sourceFlags);
        if (sanitized == 0) {
            return;
        }
        int previous = sectionSourceFlags.getOrDefault(key, 0);
        int merged = sanitizeSourceFlags(previous | sanitized);
        if (merged != previous) {
            sectionSourceFlags.put(key, merged);
        }
        /* Membership may have changed even when this key already retained the same provenance bits. */
        advancePendingSectionMetadataRevision();
    }

    private void advancePendingSectionMetadataRevision() {
        if (pendingSectionMetadataRevision == Long.MAX_VALUE) {
            throw new IllegalStateException("pending section metadata revision space exhausted");
        }
        pendingSectionMetadataRevision++;
    }

    private void recordExplicitSectionRemovalLocked(int sourceFlags) {
        if ((sourceFlags & SceneUpdateBatch.SOURCE_BLOCK_MUTATION) != 0) {
            explicitBlockMutationRemovals++;
        } else if ((sourceFlags & SceneUpdateBatch.SOURCE_RENDER_DIRTY) != 0) {
            explicitRenderDirtyRemovals++;
        }
    }

    private void recordChunkRemovalLocked(RemovalProducer producer) {
        if (producer == RemovalProducer.CHUNK_UNLOAD) {
            chunkUnloadRemovals++;
        } else {
            columnReconciliationRemovals++;
        }
    }

    private SceneUpdateBatch batchLocked() {
        Map<SectionKey, SectionVoxelSnapshot> changedSnapshots = new HashMap<>();
        Map<SectionKey, Integer> changedSourceFlags = new HashMap<>();
        for (SectionKey key : dirtySections) {
            SectionVoxelSnapshot snapshot = sectionSnapshots.get(key);
            if (snapshot != null) {
                changedSnapshots.put(key, snapshot);
            }
            int sourceFlags = sectionSourceFlags.getOrDefault(key, 0);
            if (sourceFlags != 0) {
                changedSourceFlags.put(key, sourceFlags);
            }
        }
        for (SectionKey key : removedSections) {
            int sourceFlags = sectionSourceFlags.getOrDefault(key, 0);
            if (sourceFlags != 0) {
                changedSourceFlags.put(key, sourceFlags);
            }
        }

        return new SceneUpdateBatch(
                dirtySections,
                dirtyChunks,
                removedSections,
                unloadedChunks,
                changedSnapshots,
                fullResyncRequested,
                sectionDirtyMarks,
                blockMutationMarks,
                chunkPacketReplacementMarks,
                chunkSnapshotReplacements,
                chunkUnloadMarks,
                sectionSnapshotRemovals,
                fullResyncRequests,
                pendingSourceFlags,
                changedSourceFlags
        );
    }

    private enum RemovalProducer {
        COLUMN_RECONCILIATION,
        CHUNK_UNLOAD
    }

    /**
     * Immutable foreground ownership evidence used only by bounded smoke causality logs.
     *
     * @param requestedSectionCount     requested authoritative sections
     * @param suppliedSectionCount      supplied section payloads
     * @param removedResidentSections   immutable resident sections removed by omission
     * @param residentSectionCountAfter resident section count after replacement
     */
    public record ForegroundReplacementResult(
            int requestedSectionCount,
            int suppliedSectionCount,
            Set<SectionKey> removedResidentSections,
            int residentSectionCountAfter
    ) {
        /**
         * Validates and freezes foreground replacement evidence.
         */
        public ForegroundReplacementResult {
            if (requestedSectionCount < 0 || suppliedSectionCount < 0 || residentSectionCountAfter < 0) {
                throw new IllegalArgumentException("foreground replacement counts must not be negative");
            }
            if (suppliedSectionCount > requestedSectionCount) {
                throw new IllegalArgumentException("foreground supplied sections must not exceed requested sections");
            }
            removedResidentSections = Set.copyOf(Objects.requireNonNull(
                    removedResidentSections,
                    "removedResidentSections"
            ));
        }

        static ForegroundReplacementResult empty() {
            return new ForegroundReplacementResult(0, 0, Set.of(), 0);
        }

        /**
         * Counts requested sections for which no payload was supplied.
         *
         * @return omitted requested-section count
         */
        public int omittedRequestedSectionCount() {
            return requestedSectionCount - suppliedSectionCount;
        }
    }

    /**
     * Atomic pending-section membership and provenance publication.
     *
     * @param revision    monotonically increasing publication revision
     * @param sectionKeys immutable pending section identities
     * @param sourceFlags immutable source mask for every pending section
     */
    public record PendingSectionMetadata(
            long revision,
            Set<SectionKey> sectionKeys,
            Map<SectionKey, Integer> sourceFlags
    ) {
        /**
         * Validates and freezes pending-section metadata.
         */
        public PendingSectionMetadata {
            if (revision < 0L) {
                throw new IllegalArgumentException("pending section metadata revision must not be negative");
            }
            sectionKeys = Set.copyOf(Objects.requireNonNull(sectionKeys, "sectionKeys"));
            sourceFlags = Map.copyOf(Objects.requireNonNull(sourceFlags, "sourceFlags"));
            if (!sourceFlags.keySet().equals(sectionKeys)) {
                throw new IllegalArgumentException("pending section source flags must cover every pending section");
            }
        }

        private static PendingSectionMetadata empty() {
            return new PendingSectionMetadata(0L, Set.of(), Map.of());
        }
    }

    /**
     * Atomic source ownership, capacity, eviction, and removal diagnostics.
     *
     * @param revision                   ownership publication revision
     * @param residentSectionKeys        immutable resident section identities
     * @param visibleChunkPins           immutable presentation-owned chunk pins
     * @param streamingChunkPins         immutable streaming-owned chunk pins
     * @param sectionCapacity            configured dense-source capacity
     * @param primitivePayloadBytes      total resident primitive payload bytes
     * @param currentClassification      current eviction ownership classification
     * @param overCapacityEvictionPasses cumulative over-capacity eviction passes
     * @param evictedSections            cumulative sections evicted from the dense-source cache
     * @param blockedEvictionPasses      cumulative eviction passes blocked by ownership pins
     * @param lastBlockedClassification  ownership classification from the latest blocked pass
     * @param removalProvenance          cumulative exact-removal producer counters
     */
    public record OwnershipSnapshot(
            long revision,
            Set<SectionKey> residentSectionKeys,
            Set<ChunkKey> visibleChunkPins,
            Set<ChunkKey> streamingChunkPins,
            int sectionCapacity,
            long primitivePayloadBytes,
            EvictionClassification currentClassification,
            long overCapacityEvictionPasses,
            long evictedSections,
            long blockedEvictionPasses,
            EvictionClassification lastBlockedClassification,
            RemovalProvenance removalProvenance
    ) {
        /**
         * Validates and freezes ownership evidence.
         */
        public OwnershipSnapshot {
            if (revision < 0L) {
                throw new IllegalArgumentException("scene ownership revision must not be negative");
            }
            residentSectionKeys = Set.copyOf(Objects.requireNonNull(residentSectionKeys, "residentSectionKeys"));
            visibleChunkPins = Set.copyOf(Objects.requireNonNull(visibleChunkPins, "visibleChunkPins"));
            streamingChunkPins = Set.copyOf(Objects.requireNonNull(streamingChunkPins, "streamingChunkPins"));
            currentClassification = Objects.requireNonNull(currentClassification, "currentClassification");
            lastBlockedClassification = Objects.requireNonNull(lastBlockedClassification, "lastBlockedClassification");
            removalProvenance = Objects.requireNonNull(removalProvenance, "removalProvenance");
            if (sectionCapacity <= 0 || primitivePayloadBytes < 0L || overCapacityEvictionPasses < 0L
                    || evictedSections < 0L || blockedEvictionPasses < 0L) {
                throw new IllegalArgumentException("scene ownership values must be valid");
            }
            if (currentClassification.totalSections() != residentSectionKeys.size()) {
                throw new IllegalArgumentException("scene ownership classification must cover every resident section");
            }
        }

        private static int intersectionSize(Set<ChunkKey> left, Set<ChunkKey> right) {
            Set<ChunkKey> smaller = left.size() <= right.size() ? left : right;
            Set<ChunkKey> larger = smaller == left ? right : left;
            int matches = 0;
            for (ChunkKey key : smaller) {
                if (larger.contains(key)) {
                    matches++;
                }
            }
            return matches;
        }

        /**
         * Counts resident sources beyond the configured capacity.
         *
         * @return non-negative over-capacity section count
         */
        public int overCapacitySections() {
            return Math.max(0, residentSectionKeys.size() - sectionCapacity);
        }

        /**
         * Formats stable single-line ownership diagnostics.
         *
         * @return stable ownership log fragment
         */
        public String asLogFragment() {
            return "sceneOwnership{revision=" + revision
                    + ", residentSections=" + residentSectionKeys.size()
                    + "/" + sectionCapacity
                    + ", payloadBytes=" + primitivePayloadBytes
                    + ", visiblePins=" + visibleChunkPins.size()
                    + ", streamingPins=" + streamingChunkPins.size()
                    + ", pinOverlap=" + intersectionSize(visibleChunkPins, streamingChunkPins)
                    + ", overCapacitySections=" + overCapacitySections()
                    + ", current=" + currentClassification.asLogFragment()
                    + ", evictionPasses=" + overCapacityEvictionPasses
                    + ", evicted=" + evictedSections
                    + ", blocked=" + blockedEvictionPasses
                    + ", lastBlocked=" + lastBlockedClassification.asLogFragment()
                    + ", removals=" + removalProvenance.asLogFragment()
                    + "}";
        }
    }

    /**
     * Exact source ownership for every SceneDatabase removal producer.
     *
     * @param foregroundOmission   removals caused by authoritative foreground omission
     * @param columnReconciliation removals caused by column reconciliation
     * @param renderDirtyAir       removals caused by empty render-dirty observations
     * @param blockMutationAir     removals caused by empty block mutations
     * @param sourceEviction       removals caused by source eviction
     * @param chunkUnload          removals caused by chunk unload
     */
    public record RemovalProvenance(
            long foregroundOmission,
            long columnReconciliation,
            long renderDirtyAir,
            long blockMutationAir,
            long sourceEviction,
            long chunkUnload
    ) {
        /**
         * Validates removal-provenance counters.
         */
        public RemovalProvenance {
            if (foregroundOmission < 0L || columnReconciliation < 0L || renderDirtyAir < 0L
                    || blockMutationAir < 0L || sourceEviction < 0L || chunkUnload < 0L) {
                throw new IllegalArgumentException("removal provenance counters must not be negative");
            }
        }

        /**
         * Formats stable single-line removal diagnostics.
         *
         * @return stable removal-provenance log fragment
         */
        public String asLogFragment() {
            return "foregroundOmission:" + foregroundOmission
                    + ",columnReconciliation:" + columnReconciliation
                    + ",renderDirtyAir:" + renderDirtyAir
                    + ",blockMutationAir:" + blockMutationAir
                    + ",sourceEviction:" + sourceEviction
                    + ",chunkUnload:" + chunkUnload;
        }
    }

    /**
     * Classifies resident sources by the ownership pins that protect them from eviction.
     *
     * @param evictable     sections protected by neither ownership lane
     * @param visibleOnly   sections protected only by presentation ownership
     * @param streamingOnly sections protected only by streaming ownership
     * @param bothPinned    sections protected by both ownership lanes
     */
    public record EvictionClassification(int evictable, int visibleOnly, int streamingOnly, int bothPinned) {
        /**
         * Validates eviction-classification counters.
         */
        public EvictionClassification {
            if (evictable < 0 || visibleOnly < 0 || streamingOnly < 0 || bothPinned < 0) {
                throw new IllegalArgumentException("eviction ownership counts must not be negative");
            }
        }

        static EvictionClassification empty() {
            return new EvictionClassification(0, 0, 0, 0);
        }

        /**
         * Sums all ownership classes.
         *
         * @return total classified section count
         */
        public int totalSections() {
            return evictable + visibleOnly + streamingOnly + bothPinned;
        }

        /**
         * Formats stable single-line eviction diagnostics.
         *
         * @return stable eviction-classification log fragment
         */
        public String asLogFragment() {
            return "evictable:" + evictable
                    + ",visibleOnly:" + visibleOnly
                    + ",streamingOnly:" + streamingOnly
                    + ",both:" + bothPinned;
        }
    }

    /**
     * Coherent immutable source generation consumed by one section build.
     *
     * @param snapshot     center section snapshot
     * @param neighborhood immutable 3x3x3 neighborhood view
     * @param revision     exact source-revision identity for the neighborhood
     */
    public record SectionBuildInput(
            SectionVoxelSnapshot snapshot,
            SectionNeighborhood neighborhood,
            SectionNeighborhoodRevision revision
    ) {
        /**
         * Validates the center identity shared by the snapshot and neighborhood.
         */
        public SectionBuildInput {
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            neighborhood = Objects.requireNonNull(neighborhood, "neighborhood");
            revision = Objects.requireNonNull(revision, "revision");
            if (!snapshot.key().equals(neighborhood.centerKey())) {
                throw new IllegalArgumentException("section build input center keys must match");
            }
        }
    }

    /**
     * Fixed-size exact identity for the source state consumed by one section build.
     */
    public static final class SectionNeighborhoodRevision {
        static final int ENTRY_COUNT = 27;
        private final long[] entries;

        private SectionNeighborhoodRevision(long[] entries) {
            if (entries == null || entries.length != ENTRY_COUNT) {
                throw new IllegalArgumentException("section neighborhood revision requires 27 entries");
            }
            this.entries = entries.clone();
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                    || other instanceof SectionNeighborhoodRevision revision
                    && Arrays.equals(entries, revision.entries);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(entries);
        }
    }
}
