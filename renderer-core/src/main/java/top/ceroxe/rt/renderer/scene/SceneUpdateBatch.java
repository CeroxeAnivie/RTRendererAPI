package top.ceroxe.rt.renderer.scene;

import java.util.*;

/**
 * Renderer backend 每帧可以消费的 scene update 快照。
 *
 * <p>batch 是不可变的，避免后端在异步构建 BLAS/TLAS 时读到仍在变化的 hook 输入。
 * 这里记录的是 host 对象之外的稳定坐标键，后续真正 extraction 阶段再按这些键
 * 去拉取 chunk section 数据。</p>
 *
 * @param dirtySections                    immutable dirty section keys
 * @param dirtyChunks                      immutable dirty chunk keys
 * @param removedSections                  immutable removed section keys
 * @param unloadedChunks                   immutable unloaded chunk keys
 * @param sectionSnapshots                 immutable section payloads
 * @param fullResyncRequested              whether full scene reconciliation is required
 * @param totalSectionDirtyMarks           cumulative section dirty marks
 * @param totalBlockMutationMarks          cumulative block mutation marks
 * @param totalChunkPacketReplacementMarks cumulative chunk replacement marks
 * @param totalChunkSnapshotReplacements   cumulative chunk snapshot replacements
 * @param totalChunkUnloadMarks            cumulative chunk unload marks
 * @param totalSectionSnapshotRemovals     cumulative section snapshot removals
 * @param totalFullResyncRequests          cumulative full-resync requests
 * @param batchSourceFlags                 aggregate source flags
 * @param sectionSourceFlags               immutable per-section source flags
 */
public record SceneUpdateBatch(
        Set<SectionKey> dirtySections,
        Set<ChunkKey> dirtyChunks,
        Set<SectionKey> removedSections,
        Set<ChunkKey> unloadedChunks,
        Map<SectionKey, SectionVoxelSnapshot> sectionSnapshots,
        boolean fullResyncRequested,
        long totalSectionDirtyMarks,
        long totalBlockMutationMarks,
        long totalChunkPacketReplacementMarks,
        long totalChunkSnapshotReplacements,
        long totalChunkUnloadMarks,
        long totalSectionSnapshotRemovals,
        long totalFullResyncRequests,
        int batchSourceFlags,
        Map<SectionKey, Integer> sectionSourceFlags
) {
    /**
     * Render-dirty source bit.
     */
    public static final int SOURCE_RENDER_DIRTY = 1;
    /**
     * Direct block-mutation source bit.
     */
    public static final int SOURCE_BLOCK_MUTATION = 1 << 1;
    /**
     * Chunk-streaming source bit.
     */
    public static final int SOURCE_CHUNK_STREAMING = 1 << 2;
    /**
     * Section-removal source bit.
     */
    public static final int SOURCE_SECTION_REMOVAL = 1 << 3;
    /**
     * Full-resynchronization source bit.
     */
    public static final int SOURCE_FULL_RESYNC = 1 << 4;
    /**
     * Material-only change source bit.
     */
    public static final int SOURCE_MATERIAL_ONLY = 1 << 5;
    /**
     * Neighbor-dependency source bit.
     */
    public static final int SOURCE_NEIGHBOR_DEPENDENCY = 1 << 6;
    /**
     * Direct-content source bit.
     */
    public static final int SOURCE_DIRECT_CONTENT = 1 << 7;

    /**
     * Creates a batch whose aggregate source flags are inferred from its counters.
     *
     * @param dirtySections                    immutable dirty section keys
     * @param dirtyChunks                      immutable dirty chunk keys
     * @param removedSections                  immutable removed section keys
     * @param unloadedChunks                   immutable unloaded chunk keys
     * @param sectionSnapshots                 immutable section payloads
     * @param fullResyncRequested              whether full scene reconciliation is required
     * @param totalSectionDirtyMarks           cumulative section dirty marks
     * @param totalBlockMutationMarks          cumulative block mutation marks
     * @param totalChunkPacketReplacementMarks cumulative chunk replacement marks
     * @param totalChunkSnapshotReplacements   cumulative chunk snapshot replacements
     * @param totalChunkUnloadMarks            cumulative chunk unload marks
     * @param totalSectionSnapshotRemovals     cumulative section snapshot removals
     * @param totalFullResyncRequests          cumulative full-resynchronization requests
     */
    public SceneUpdateBatch(
            Set<SectionKey> dirtySections,
            Set<ChunkKey> dirtyChunks,
            Set<SectionKey> removedSections,
            Set<ChunkKey> unloadedChunks,
            Map<SectionKey, SectionVoxelSnapshot> sectionSnapshots,
            boolean fullResyncRequested,
            long totalSectionDirtyMarks,
            long totalBlockMutationMarks,
            long totalChunkPacketReplacementMarks,
            long totalChunkSnapshotReplacements,
            long totalChunkUnloadMarks,
            long totalSectionSnapshotRemovals,
            long totalFullResyncRequests
    ) {
        this(
                dirtySections,
                dirtyChunks,
                removedSections,
                unloadedChunks,
                sectionSnapshots,
                fullResyncRequested,
                totalSectionDirtyMarks,
                totalBlockMutationMarks,
                totalChunkPacketReplacementMarks,
                totalChunkSnapshotReplacements,
                totalChunkUnloadMarks,
                totalSectionSnapshotRemovals,
                totalFullResyncRequests,
                inferSourceFlags(
                        fullResyncRequested,
                        totalSectionDirtyMarks,
                        totalBlockMutationMarks,
                        totalChunkPacketReplacementMarks,
                        totalChunkSnapshotReplacements,
                        totalChunkUnloadMarks,
                        totalSectionSnapshotRemovals
                ),
                Map.of()
        );
    }

    /**
     * Creates a batch with an explicit aggregate source mask and derives per-section masks.
     *
     * @param dirtySections                    immutable dirty section keys
     * @param dirtyChunks                      immutable dirty chunk keys
     * @param removedSections                  immutable removed section keys
     * @param unloadedChunks                   immutable unloaded chunk keys
     * @param sectionSnapshots                 immutable section payloads
     * @param fullResyncRequested              whether full scene reconciliation is required
     * @param totalSectionDirtyMarks           cumulative section dirty marks
     * @param totalBlockMutationMarks          cumulative block mutation marks
     * @param totalChunkPacketReplacementMarks cumulative chunk replacement marks
     * @param totalChunkSnapshotReplacements   cumulative chunk snapshot replacements
     * @param totalChunkUnloadMarks            cumulative chunk unload marks
     * @param totalSectionSnapshotRemovals     cumulative section snapshot removals
     * @param totalFullResyncRequests          cumulative full-resynchronization requests
     * @param batchSourceFlags                 aggregate source mask applied to affected sections
     */
    public SceneUpdateBatch(
            Set<SectionKey> dirtySections,
            Set<ChunkKey> dirtyChunks,
            Set<SectionKey> removedSections,
            Set<ChunkKey> unloadedChunks,
            Map<SectionKey, SectionVoxelSnapshot> sectionSnapshots,
            boolean fullResyncRequested,
            long totalSectionDirtyMarks,
            long totalBlockMutationMarks,
            long totalChunkPacketReplacementMarks,
            long totalChunkSnapshotReplacements,
            long totalChunkUnloadMarks,
            long totalSectionSnapshotRemovals,
            long totalFullResyncRequests,
            int batchSourceFlags
    ) {
        this(
                dirtySections,
                dirtyChunks,
                removedSections,
                unloadedChunks,
                sectionSnapshots,
                fullResyncRequested,
                totalSectionDirtyMarks,
                totalBlockMutationMarks,
                totalChunkPacketReplacementMarks,
                totalChunkSnapshotReplacements,
                totalChunkUnloadMarks,
                totalSectionSnapshotRemovals,
                totalFullResyncRequests,
                batchSourceFlags,
                sourceFlagsForSections(dirtySections, removedSections, sectionSnapshots.keySet(), batchSourceFlags)
        );
    }

    /**
     * Freezes all collections and normalizes source flags.
     */
    public SceneUpdateBatch {
        dirtySections = Set.copyOf(Objects.requireNonNull(dirtySections, "dirtySections"));
        dirtyChunks = Set.copyOf(Objects.requireNonNull(dirtyChunks, "dirtyChunks"));
        removedSections = Set.copyOf(Objects.requireNonNull(removedSections, "removedSections"));
        unloadedChunks = Set.copyOf(Objects.requireNonNull(unloadedChunks, "unloadedChunks"));
        sectionSnapshots = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(sectionSnapshots, "sectionSnapshots")
        ));
        batchSourceFlags = sanitizeSourceFlags(batchSourceFlags);
        sectionSourceFlags = sanitizeSectionSourceFlags(sectionSourceFlags);
    }

    /**
     * Returns the canonical chunk-streaming source mask.
     *
     * @return chunk-streaming source mask
     */
    public static int sourceFlagsForChunkStreaming() {
        return SOURCE_CHUNK_STREAMING;
    }

    /**
     * Returns the canonical block-mutation source mask.
     *
     * @return block-mutation source mask
     */
    public static int sourceFlagsForBlockMutation() {
        return SOURCE_BLOCK_MUTATION;
    }

    /**
     * Returns the canonical render-dirty source mask.
     *
     * @return render-dirty source mask
     */
    public static int sourceFlagsForRenderDirty() {
        return SOURCE_RENDER_DIRTY;
    }

    /**
     * Adds the removal bit to a source mask.
     *
     * @param baseFlags source mask to augment
     * @return normalized source mask containing the removal bit
     */
    public static int sourceFlagsForRemoval(int baseFlags) {
        return sanitizeSourceFlags(baseFlags | SOURCE_SECTION_REMOVAL);
    }

    /**
     * Adds the full-resynchronization bit to a source mask.
     *
     * @param baseFlags source mask to augment
     * @return normalized source mask containing the full-resynchronization bit
     */
    public static int sourceFlagsForFullResync(int baseFlags) {
        return sanitizeSourceFlags(baseFlags | SOURCE_FULL_RESYNC);
    }

    /**
     * Adds the material-only bit to a source mask.
     *
     * @param baseFlags source mask to augment
     * @return normalized source mask containing the material-only bit
     */
    public static int sourceFlagsForMaterialOnly(int baseFlags) {
        return sanitizeSourceFlags(baseFlags | SOURCE_MATERIAL_ONLY);
    }

    /**
     * Adds the neighbor-dependency bit to a source mask.
     *
     * @param baseFlags source mask to augment
     * @return normalized source mask containing the neighbor-dependency bit
     */
    public static int sourceFlagsForNeighborDependency(int baseFlags) {
        return sanitizeSourceFlags(baseFlags | SOURCE_NEIGHBOR_DEPENDENCY);
    }

    /**
     * Adds the direct-content bit to a source mask.
     *
     * @param baseFlags source mask to augment
     * @return normalized source mask containing the direct-content bit
     */
    public static int sourceFlagsForDirectContent(int baseFlags) {
        return sanitizeSourceFlags(baseFlags | SOURCE_DIRECT_CONTENT);
    }

    private static int inferSourceFlags(
            boolean fullResyncRequested,
            long totalSectionDirtyMarks,
            long totalBlockMutationMarks,
            long totalChunkPacketReplacementMarks,
            long totalChunkSnapshotReplacements,
            long totalChunkUnloadMarks,
            long totalSectionSnapshotRemovals
    ) {
        int flags = 0;
        if (totalSectionDirtyMarks > 0L) {
            flags |= SOURCE_RENDER_DIRTY;
        }
        if (totalBlockMutationMarks > 0L) {
            flags |= SOURCE_BLOCK_MUTATION;
        }
        if (totalChunkPacketReplacementMarks > 0L
                || totalChunkSnapshotReplacements > 0L
                || totalChunkUnloadMarks > 0L) {
            flags |= SOURCE_CHUNK_STREAMING;
        }
        if (totalSectionSnapshotRemovals > 0L) {
            flags |= SOURCE_SECTION_REMOVAL;
        }
        if (fullResyncRequested) {
            flags |= SOURCE_FULL_RESYNC;
        }
        return flags;
    }

    private static int sanitizeSourceFlags(int flags) {
        int knownFlags = SOURCE_RENDER_DIRTY
                | SOURCE_BLOCK_MUTATION
                | SOURCE_CHUNK_STREAMING
                | SOURCE_SECTION_REMOVAL
                | SOURCE_FULL_RESYNC
                | SOURCE_MATERIAL_ONLY
                | SOURCE_NEIGHBOR_DEPENDENCY
                | SOURCE_DIRECT_CONTENT;
        return flags & knownFlags;
    }

    private static Map<SectionKey, Integer> sourceFlagsForSections(
            Set<SectionKey> dirtySections,
            Set<SectionKey> removedSections,
            Set<SectionKey> snapshotSections,
            int sourceFlags
    ) {
        int sanitizedFlags = sanitizeSourceFlags(sourceFlags);
        if (sanitizedFlags == 0) {
            return Map.of();
        }
        LinkedHashMap<SectionKey, Integer> flagsBySection = new LinkedHashMap<>();
        putSourceFlags(flagsBySection, dirtySections, sanitizedFlags);
        putSourceFlags(flagsBySection, removedSections, sanitizedFlags);
        putSourceFlags(flagsBySection, snapshotSections, sanitizedFlags);
        return flagsBySection;
    }

    private static void putSourceFlags(
            Map<SectionKey, Integer> flagsBySection,
            Iterable<SectionKey> sections,
            int sourceFlags
    ) {
        for (SectionKey key : sections) {
            flagsBySection.merge(Objects.requireNonNull(key, "section source key"), sourceFlags, (left, right) -> {
                int merged = sanitizeSourceFlags(left | right);
                return merged == 0 ? null : merged;
            });
        }
    }

    private static Map<SectionKey, Integer> sanitizeSectionSourceFlags(Map<SectionKey, Integer> sourceFlags) {
        Objects.requireNonNull(sourceFlags, "sectionSourceFlags");
        if (sourceFlags.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<SectionKey, Integer> sanitized = new LinkedHashMap<>();
        for (Map.Entry<SectionKey, Integer> entry : sourceFlags.entrySet()) {
            SectionKey key = Objects.requireNonNull(entry.getKey(), "section source key");
            int flags = sanitizeSourceFlags(entry.getValue() == null ? 0 : entry.getValue());
            if (flags != 0) {
                sanitized.put(key, flags);
            }
        }
        return Collections.unmodifiableMap(sanitized);
    }

    private static void appendSource(StringBuilder builder, int flags, int flag, String label) {
        if ((flags & flag) == 0) {
            return;
        }
        if (builder.length() > 0) {
            builder.append('|');
        }
        builder.append(label);
    }

    /**
     * Reports whether the batch contains any scene change.
     *
     * @return {@code true} when the batch requires renderer work
     */
    public boolean hasChanges() {
        return fullResyncRequested
                || !dirtySections.isEmpty()
                || !dirtyChunks.isEmpty()
                || !removedSections.isEmpty()
                || !unloadedChunks.isEmpty();
    }

    /**
     * Counts dirty sections.
     *
     * @return number of dirty section keys
     */
    public int dirtySectionCount() {
        return dirtySections.size();
    }

    /**
     * Counts dirty chunks.
     *
     * @return number of dirty chunk keys
     */
    public int dirtyChunkCount() {
        return dirtyChunks.size();
    }

    /**
     * Counts removed sections.
     *
     * @return number of removed section keys
     */
    public int removedSectionCount() {
        return removedSections.size();
    }

    /**
     * Counts unloaded chunks.
     *
     * @return number of unloaded chunk keys
     */
    public int unloadedChunkCount() {
        return unloadedChunks.size();
    }

    /**
     * Counts attached section snapshots.
     *
     * @return number of attached section payloads
     */
    public int sectionSnapshotCount() {
        return sectionSnapshots.size();
    }

    /**
     * Tests one aggregate source bit.
     *
     * @param sourceFlag source bit to test
     * @return {@code true} when the bit is present in the aggregate source mask
     */
    public boolean hasBatchSource(int sourceFlag) {
        return (batchSourceFlags & sourceFlag) != 0;
    }

    /**
     * Reports whether block mutation contributed to the batch.
     *
     * @return {@code true} when block mutation contributed to the batch
     */
    public boolean hasBatchBlockMutationSource() {
        return hasBatchSource(SOURCE_BLOCK_MUTATION);
    }

    /**
     * Reports whether chunk streaming contributed to the batch.
     *
     * @return {@code true} when chunk streaming contributed to the batch
     */
    public boolean hasBatchChunkStreamingSource() {
        return hasBatchSource(SOURCE_CHUNK_STREAMING);
    }

    /**
     * Reports whether section removal contributed to the batch.
     *
     * @return {@code true} when section removal contributed to the batch
     */
    public boolean hasBatchSectionRemovalSource() {
        return hasBatchSource(SOURCE_SECTION_REMOVAL);
    }

    /**
     * Tests whether a section changed only its material payload.
     *
     * @param key section identity to inspect
     * @return {@code true} when the section source mask contains the material-only bit
     */
    public boolean hasMaterialOnlySourceForSection(SectionKey key) {
        return (sourceFlagsForSection(key) & SOURCE_MATERIAL_ONLY) != 0;
    }

    /**
     * Returns normalized source flags for one section.
     *
     * @param key section identity to inspect
     * @return normalized source mask, or {@code 0} when the section has no recorded source
     */
    public int sourceFlagsForSection(SectionKey key) {
        Integer sourceFlags = sectionSourceFlags.get(Objects.requireNonNull(key, "key"));
        return sourceFlags == null ? 0 : sourceFlags;
    }

    /**
     * Formats the aggregate source flags as a stable diagnostic value.
     *
     * @return stable aggregate source summary
     */
    public String batchSourceSummary() {
        if (batchSourceFlags == 0) {
            return "none";
        }
        StringBuilder builder = new StringBuilder();
        appendSource(builder, batchSourceFlags, SOURCE_RENDER_DIRTY, "renderDirty");
        appendSource(builder, batchSourceFlags, SOURCE_BLOCK_MUTATION, "blockMutation");
        appendSource(builder, batchSourceFlags, SOURCE_CHUNK_STREAMING, "chunkStreaming");
        appendSource(builder, batchSourceFlags, SOURCE_SECTION_REMOVAL, "sectionRemoval");
        appendSource(builder, batchSourceFlags, SOURCE_FULL_RESYNC, "fullResync");
        appendSource(builder, batchSourceFlags, SOURCE_MATERIAL_ONLY, "materialOnly");
        appendSource(builder, batchSourceFlags, SOURCE_NEIGHBOR_DEPENDENCY, "neighborDependency");
        appendSource(builder, batchSourceFlags, SOURCE_DIRECT_CONTENT, "directContent");
        return builder.toString();
    }

    /**
     * Formats stable single-line batch diagnostics.
     *
     * @return stable single-line batch summary
     */
    public String summary() {
        return "dirtySections=" + dirtySectionCount()
                + ", dirtyChunks=" + dirtyChunkCount()
                + ", removedSections=" + removedSectionCount()
                + ", unloadedChunks=" + unloadedChunkCount()
                + ", sectionSnapshots=" + sectionSnapshotCount()
                + ", batchSources=" + batchSourceSummary()
                + ", sectionMarks=" + totalSectionDirtyMarks
                + ", blockMutations=" + totalBlockMutationMarks
                + ", chunkPackets=" + totalChunkPacketReplacementMarks
                + ", chunkSnapshots=" + totalChunkSnapshotReplacements
                + ", chunkUnloads=" + totalChunkUnloadMarks
                + ", sectionRemovals=" + totalSectionSnapshotRemovals
                + ", fullResync=" + fullResyncRequested
                + "(" + totalFullResyncRequests + ")";
    }
}
