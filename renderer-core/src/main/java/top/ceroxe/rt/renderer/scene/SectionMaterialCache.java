package top.ceroxe.rt.renderer.scene;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 帧末 batch 消费后的 renderer material cache。
 *
 * <p>The production renderer stores only fixed-size per-section material
 * ownership summaries. Full encoded voxel palettes remain available through
 * the explicit snapshot entry points, but the RT frame path must not retain
 * a second copy of terrain already represented by SectionTriangleMesh and the
 * native material table.</p>
 */
public final class SectionMaterialCache {
    private final Object lock = new Object();
    private final SceneCacheBudget budget;
    private final Map<SectionKey, MaterialSectionOwnership> sections = new LinkedHashMap<>(16, 0.75f, true);
    private long appliedBatches;
    private long updatedSections;
    private long removedSections;
    private long evictedSections;
    private long cachedEstimatedBytes;
    private long peakCachedEstimatedBytes;
    private long fullResyncClears;
    private MaterialFacts latestBatchFacts = MaterialFacts.empty();

    /**
     * Creates a cache with the default scene budget.
     */
    public SectionMaterialCache() {
        this(SceneCacheBudget.DEFAULT);
    }

    /**
     * Creates a cache constrained by the supplied material budget.
     *
     * @param budget scene cache budget
     */
    public SectionMaterialCache(SceneCacheBudget budget) {
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    private static void requirePreparedKeys(Set<SectionKey> expected, Set<SectionKey> actual, String name) {
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(name + " keys must match batch section snapshots");
        }
    }

    /**
     * Encodes and applies one scene update batch.
     *
     * @param batch immutable scene update batch
     * @return exact material ownership result
     */
    public ApplyResult apply(SceneUpdateBatch batch) {
        Objects.requireNonNull(batch, "batch");
        Map<SectionKey, SectionEncodedSnapshot> encodedBatch = new LinkedHashMap<>();
        for (SectionVoxelSnapshot snapshot : batch.sectionSnapshots().values()) {
            encodedBatch.put(snapshot.key(), SectionEncodedSnapshot.encode(snapshot));
        }
        return applyPrepared(batch, encodedBatch, MaterialFacts.fromEncoded(encodedBatch.values()));
    }

    /**
     * Applies one batch with caller-prepared encoded sections.
     *
     * @param batch        immutable scene update batch
     * @param encodedBatch prepared encoded sections
     * @return exact material ownership result
     */
    public ApplyResult applyPrepared(
            SceneUpdateBatch batch,
            Map<SectionKey, SectionEncodedSnapshot> encodedBatch
    ) {
        return applyPrepared(batch, encodedBatch, MaterialFacts.fromEncoded(encodedBatch.values()));
    }

    /**
     * Applies one batch with prepared encodings and aggregate material facts.
     *
     * @param batch        immutable scene update batch
     * @param encodedBatch prepared encoded sections
     * @param batchFacts   authoritative aggregate material facts
     * @return exact material ownership result
     */
    public ApplyResult applyPrepared(
            SceneUpdateBatch batch,
            Map<SectionKey, SectionEncodedSnapshot> encodedBatch,
            MaterialFacts batchFacts
    ) {
        return applyPreparedInternal(batch, encodedBatch, batchFacts);
    }

    /**
     * Commits production material ownership without retaining encoded voxel palettes.
     *
     * @param batch           source scene update
     * @param updatedSections section identities with material updates
     * @param batchFacts      aggregate facts for the updated material payload
     * @return immutable cache application result
     */
    public ApplyResult applyMaterialUpdates(
            SceneUpdateBatch batch,
            Set<SectionKey> updatedSections,
            MaterialFacts batchFacts
    ) {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(updatedSections, "updatedSections");
        batchFacts = Objects.requireNonNull(batchFacts, "batchFacts");
        requirePreparedKeys(batch.sectionSnapshots().keySet(), updatedSections, "updatedSections");
        return applyInternal(batch, updatedSections, Map.of(), batchFacts, false);
    }

    private ApplyResult applyPreparedInternal(
            SceneUpdateBatch batch,
            Map<SectionKey, SectionEncodedSnapshot> encodedBatch,
            MaterialFacts batchFacts
    ) {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(encodedBatch, "encodedBatch");
        batchFacts = Objects.requireNonNull(batchFacts, "batchFacts");
        requirePreparedKeys(batch.sectionSnapshots().keySet(), encodedBatch.keySet(), "encodedBatch");
        return applyInternal(batch, encodedBatch.keySet(), encodedBatch, batchFacts, true);
    }

    private ApplyResult applyInternal(
            SceneUpdateBatch batch,
            Set<SectionKey> updatedBatch,
            Map<SectionKey, SectionEncodedSnapshot> diagnosticEncodedBatch,
            MaterialFacts batchFacts,
            boolean retainDiagnosticEncoding
    ) {
        int updatedInBatch = 0;
        int removedInBatch = 0;
        int evictedInBatch = 0;
        synchronized (lock) {
            if (batch.fullResyncRequested()) {
                sections.clear();
                cachedEstimatedBytes = 0L;
                fullResyncClears++;
            } else {
                removedInBatch = removeSections(batch.removedSections());
            }

            for (SectionKey key : updatedBatch) {
                SectionEncodedSnapshot encoded = diagnosticEncodedBatch.get(key);
                replaceSection(key, new MaterialSectionOwnership(
                        retainDiagnosticEncoding ? encoded : null,
                        retainDiagnosticEncoding ? encoded.estimatedBytes() : MaterialFacts.ESTIMATED_BYTES_PER_SECTION
                ));
                updatedInBatch++;
            }
            evictedInBatch = evictUntilWithinBudget(updatedBatch);

            if (batch.hasChanges()) {
                appliedBatches++;
            }
            updatedSections += updatedInBatch;
            removedSections += removedInBatch;
            latestBatchFacts = batchFacts;
            return new ApplyResult(
                    appliedBatches,
                    updatedInBatch,
                    removedInBatch,
                    sections.size(),
                    updatedSections,
                    removedSections,
                    evictedInBatch,
                    evictedSections,
                    cachedEstimatedBytes,
                    peakCachedEstimatedBytes,
                    budget.materialBytes(),
                    cachedEstimatedBytes > budget.materialBytes(),
                    fullResyncClears,
                    batchFacts,
                    updatedBatch,
                    diagnosticEncodedBatch
            );
        }
    }

    /**
     * Returns a retained diagnostic encoding when that explicit mode is active.
     *
     * @param key section identity
     * @return retained diagnostic encoding, or {@code null}
     */
    public SectionEncodedSnapshot snapshot(SectionKey key) {
        synchronized (lock) {
            MaterialSectionOwnership section = sections.get(key);
            return section == null ? null : section.diagnosticEncoded();
        }
    }

    /**
     * Returns immutable counters and current budget state.
     *
     * @return immutable cache summary
     */
    public Summary summary() {
        synchronized (lock) {
            return new Summary(
                    sections.size(),
                    appliedBatches,
                    updatedSections,
                    removedSections,
                    evictedSections,
                    cachedEstimatedBytes,
                    peakCachedEstimatedBytes,
                    budget.materialBytes(),
                    cachedEstimatedBytes > budget.materialBytes(),
                    fullResyncClears,
                    latestBatchFacts
            );
        }
    }

    private void replaceSection(SectionKey key, MaterialSectionOwnership section) {
        MaterialSectionOwnership previous = sections.put(key, section);
        if (previous != null) {
            cachedEstimatedBytes -= previous.estimatedBytes();
        }
        cachedEstimatedBytes += section.estimatedBytes();
        peakCachedEstimatedBytes = Math.max(peakCachedEstimatedBytes, cachedEstimatedBytes);
    }

    private int removeSections(Set<SectionKey> removedSectionKeys) {
        int removedInBatch = 0;
        for (SectionKey key : removedSectionKeys) {
            MaterialSectionOwnership removed = sections.remove(key);
            if (removed != null) {
                cachedEstimatedBytes -= removed.estimatedBytes();
                removedInBatch++;
            }
        }
        return removedInBatch;
    }

    private int evictUntilWithinBudget(Set<SectionKey> protectedKeys) {
        int evictedInBatch = 0;
        while (cachedEstimatedBytes > budget.materialBytes()) {
            boolean evicted = false;
            var iterator = sections.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<SectionKey, MaterialSectionOwnership> entry = iterator.next();
                if (protectedKeys.contains(entry.getKey())) {
                    continue;
                }
                MaterialSectionOwnership removed = entry.getValue();
                iterator.remove();
                cachedEstimatedBytes -= removed.estimatedBytes();
                evictedInBatch++;
                evicted = true;
                break;
            }
            if (!evicted) {
                break;
            }
        }
        evictedSections += evictedInBatch;
        return evictedInBatch;
    }

    /**
     * Immutable result and telemetry for one material-cache application.
     *
     * @param appliedBatches           cumulative applied batches
     * @param updatedInBatch           sections updated in this batch
     * @param removedInBatch           sections removed in this batch
     * @param cachedSections           currently retained ownership entries
     * @param totalUpdatedSections     cumulative updated sections
     * @param totalRemovedSections     cumulative removed sections
     * @param evictedInBatch           entries evicted in this batch
     * @param totalEvictedSections     cumulative evicted entries
     * @param cachedEstimatedBytes     current retained bytes
     * @param peakCachedEstimatedBytes peak retained bytes
     * @param budgetBytes              configured material budget
     * @param overBudget               whether protected payloads leave the cache over budget
     * @param fullResyncClears         cumulative full-resynchronization clears
     * @param materialFacts            aggregate facts for this batch
     * @param updatedSections          section identities updated by this batch
     * @param encodedSections          retained diagnostic encodings
     */
    public record ApplyResult(
            long appliedBatches,
            int updatedInBatch,
            int removedInBatch,
            int cachedSections,
            long totalUpdatedSections,
            long totalRemovedSections,
            int evictedInBatch,
            long totalEvictedSections,
            long cachedEstimatedBytes,
            long peakCachedEstimatedBytes,
            long budgetBytes,
            boolean overBudget,
            long fullResyncClears,
            MaterialFacts materialFacts,
            Set<SectionKey> updatedSections,
            Map<SectionKey, SectionEncodedSnapshot> encodedSections
    ) {
        /**
         * Validates and freezes the immutable material ownership result.
         */
        public ApplyResult {
            materialFacts = Objects.requireNonNull(materialFacts, "materialFacts");
            updatedSections = Set.copyOf(Objects.requireNonNull(updatedSections, "updatedSections"));
            encodedSections = Map.copyOf(Objects.requireNonNull(encodedSections, "encodedSections"));
            if (!updatedSections.containsAll(encodedSections.keySet())) {
                throw new IllegalArgumentException("diagnostic encoded sections must be updated sections");
            }
        }

        /**
         * Compatibility constructor for explicit encoded/test callers.
         *
         * @param appliedBatches           cumulative applied batches
         * @param updatedInBatch           sections updated in this batch
         * @param removedInBatch           sections removed in this batch
         * @param cachedSections           retained section count
         * @param totalUpdatedSections     cumulative updated sections
         * @param totalRemovedSections     cumulative removed sections
         * @param evictedInBatch           sections evicted in this batch
         * @param totalEvictedSections     cumulative evicted sections
         * @param cachedEstimatedBytes     current estimated retained bytes
         * @param peakCachedEstimatedBytes peak estimated retained bytes
         * @param budgetBytes              configured budget bytes
         * @param overBudget               whether retained ownership exceeds budget
         * @param fullResyncClears         cumulative full-resync clears
         * @param materialFacts            aggregate material facts
         * @param encodedSections          immutable prepared encodings
         */
        public ApplyResult(
                long appliedBatches, int updatedInBatch, int removedInBatch, int cachedSections,
                long totalUpdatedSections, long totalRemovedSections, int evictedInBatch,
                long totalEvictedSections, long cachedEstimatedBytes, long peakCachedEstimatedBytes,
                long budgetBytes, boolean overBudget, long fullResyncClears, MaterialFacts materialFacts,
                Map<SectionKey, SectionEncodedSnapshot> encodedSections
        ) {
            this(appliedBatches, updatedInBatch, removedInBatch, cachedSections, totalUpdatedSections,
                    totalRemovedSections, evictedInBatch, totalEvictedSections, cachedEstimatedBytes,
                    peakCachedEstimatedBytes, budgetBytes, overBudget, fullResyncClears, materialFacts,
                    encodedSections.keySet(), encodedSections);
        }

    }

    /**
     * Aggregate material-classification counters for a batch or snapshot set.
     *
     * @param sections                     represented sections
     * @param blocks                       represented voxels
     * @param visibleBlocks                visible voxels
     * @param airBlocks                    air voxels
     * @param fluidBlocks                  fluid-containing voxels
     * @param emissiveBlocks               emissive voxels
     * @param mapColorBlocks               voxels with captured map color
     * @param missingMapColorVisibleBlocks visible voxels missing map color
     */
    public record MaterialFacts(
            long sections,
            long blocks,
            long visibleBlocks,
            long airBlocks,
            long fluidBlocks,
            long emissiveBlocks,
            long mapColorBlocks,
            long missingMapColorVisibleBlocks
    ) {
        static final long ESTIMATED_BYTES_PER_SECTION = Long.BYTES * 8L;

        /**
         * Rejects negative counters before material facts enter cache accounting.
         */
        public MaterialFacts {
            if ((sections | blocks | visibleBlocks | airBlocks | fluidBlocks | emissiveBlocks
                    | mapColorBlocks | missingMapColorVisibleBlocks) < 0L) {
                throw new IllegalArgumentException("material fact counters must not be negative");
            }
        }

        /**
         * Returns the shared logical zero material facts.
         *
         * @return zero-valued material facts
         */
        public static MaterialFacts empty() {
            return new MaterialFacts(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        }

        /**
         * Aggregates material facts from encoded sections.
         *
         * @param encodedSections encoded section sequence
         * @return aggregate material facts
         */
        public static MaterialFacts fromEncoded(Iterable<SectionEncodedSnapshot> encodedSections) {
            Objects.requireNonNull(encodedSections, "encodedSections");
            Builder builder = new Builder();
            for (SectionEncodedSnapshot encoded : encodedSections) {
                builder.add(encoded);
            }
            return builder.build();
        }

        /**
         * Extracts material facts from one encoded section.
         *
         * @param encodedSection encoded section snapshot
         * @return section material facts
         */
        public static MaterialFacts fromEncoded(SectionEncodedSnapshot encodedSection) {
            Objects.requireNonNull(encodedSection, "encodedSection");
            Builder builder = new Builder();
            builder.add(encodedSection);
            return builder.build();
        }

        /**
         * Extracts material facts directly from one voxel snapshot.
         *
         * @param snapshot decoded voxel snapshot
         * @return section material facts
         */
        public static MaterialFacts fromSnapshot(SectionVoxelSnapshot snapshot) {
            Objects.requireNonNull(snapshot, "snapshot");
            Builder builder = new Builder();
            builder.add(snapshot);
            return builder.build();
        }

        /**
         * Returns an overflow-checked sum with another fact set.
         *
         * @param other material facts to add
         * @return overflow-checked aggregate facts
         */
        public MaterialFacts plus(MaterialFacts other) {
            Objects.requireNonNull(other, "other");
            return new MaterialFacts(
                    Math.addExact(sections, other.sections),
                    Math.addExact(blocks, other.blocks),
                    Math.addExact(visibleBlocks, other.visibleBlocks),
                    Math.addExact(airBlocks, other.airBlocks),
                    Math.addExact(fluidBlocks, other.fluidBlocks),
                    Math.addExact(emissiveBlocks, other.emissiveBlocks),
                    Math.addExact(mapColorBlocks, other.mapColorBlocks),
                    Math.addExact(missingMapColorVisibleBlocks, other.missingMapColorVisibleBlocks)
            );
        }

        /**
         * Returns the fraction of visible blocks with captured map color.
         *
         * @return visible map-color coverage in {@code [0, 1]}
         */
        public double visibleMapColorCoverage() {
            if (visibleBlocks == 0L) {
                return 1.0D;
            }
            return (double) (visibleBlocks - missingMapColorVisibleBlocks) / (double) visibleBlocks;
        }

        /**
         * Formats stable single-line material diagnostics.
         *
         * @return stable diagnostic fragment
         */
        public String asLogFragment() {
            return "materialFacts{sections=" + sections
                    + ", blocks=" + blocks
                    + ", visibleBlocks=" + visibleBlocks
                    + ", fluidBlocks=" + fluidBlocks
                    + ", emissiveBlocks=" + emissiveBlocks
                    + ", mapColorBlocks=" + mapColorBlocks
                    + ", missingMapColorVisibleBlocks=" + missingMapColorVisibleBlocks
                    + ", visibleMapColorCoverage=" + String.format(java.util.Locale.ROOT, "%.4f", visibleMapColorCoverage())
                    + "}";
        }

        private static final class Builder {
            private long sections;
            private long blocks;
            private long visibleBlocks;
            private long airBlocks;
            private long fluidBlocks;
            private long emissiveBlocks;
            private long mapColorBlocks;
            private long missingMapColorVisibleBlocks;

            private void add(SectionEncodedSnapshot encoded) {
                Objects.requireNonNull(encoded, "encoded");
                sections++;
                int[] runPaletteIndices = encoded.runPaletteIndices();
                int[] runLengths = encoded.runLengths();
                byte[] fluidPalette = encoded.mediumAmountPalette();
                int[] mapColorPalette = encoded.mapColorPalette();
                byte[] lightPalette = encoded.lightEmissionPalette();
                byte[] flagPalette = encoded.materialFlagPalette();
                for (int run = 0; run < runLengths.length; run++) {
                    int paletteIndex = runPaletteIndices[run];
                    addRepeatedBlock(
                            Byte.toUnsignedInt(fluidPalette[paletteIndex]),
                            mapColorPalette[paletteIndex],
                            Byte.toUnsignedInt(lightPalette[paletteIndex]),
                            Byte.toUnsignedInt(flagPalette[paletteIndex]),
                            runLengths[run]
                    );
                }
            }

            private void add(SectionVoxelSnapshot snapshot) {
                sections++;
                for (int index = 0; index < SectionVoxelSnapshot.BLOCKS_PER_SECTION; index++) {
                    addRepeatedBlock(
                            snapshot.mediumAmountAtLinearIndex(index),
                            snapshot.mapColorAtLinearIndex(index),
                            snapshot.lightEmissionAtLinearIndex(index),
                            snapshot.materialFlagsAtLinearIndex(index),
                            1
                    );
                }
            }

            private void addRepeatedBlock(
                    int mediumAmount,
                    int mapColor,
                    int lightEmission,
                    int materialFlags,
                    int count
            ) {
                if (count <= 0) {
                    throw new IllegalArgumentException("material fact run length must be positive");
                }
                blocks += count;
                boolean visible = (materialFlags & SectionVoxelSnapshot.FLAG_RENDER_SHAPE_VISIBLE) != 0 || mediumAmount > 0;
                if (visible) {
                    visibleBlocks += count;
                    if (mapColor == SectionVoxelSnapshot.NO_MAP_COLOR) {
                        missingMapColorVisibleBlocks += count;
                    }
                }
                if ((materialFlags & SectionVoxelSnapshot.FLAG_AIR) != 0) {
                    airBlocks += count;
                }
                if ((materialFlags & SectionVoxelSnapshot.FLAG_LIQUID) != 0 || mediumAmount > 0) {
                    fluidBlocks += count;
                }
                if (lightEmission > 0) {
                    emissiveBlocks += count;
                }
                if (mapColor != SectionVoxelSnapshot.NO_MAP_COLOR) {
                    mapColorBlocks += count;
                }
            }

            private MaterialFacts build() {
                return new MaterialFacts(
                        sections,
                        blocks,
                        visibleBlocks,
                        airBlocks,
                        fluidBlocks,
                        emissiveBlocks,
                        mapColorBlocks,
                        missingMapColorVisibleBlocks
                );
            }
        }
    }

    /**
     * Immutable material-cache counters and budget state.
     *
     * @param cachedSections           currently retained ownership entries
     * @param appliedBatches           cumulative applied batches
     * @param totalUpdatedSections     cumulative updated sections
     * @param totalRemovedSections     cumulative removed sections
     * @param totalEvictedSections     cumulative evicted entries
     * @param cachedEstimatedBytes     current retained bytes
     * @param peakCachedEstimatedBytes peak retained bytes
     * @param budgetBytes              configured material budget
     * @param overBudget               whether retained bytes exceed the budget
     * @param fullResyncClears         cumulative full-resynchronization clears
     * @param latestBatchFacts         material facts from the latest batch
     */
    public record Summary(
            int cachedSections,
            long appliedBatches,
            long totalUpdatedSections,
            long totalRemovedSections,
            long totalEvictedSections,
            long cachedEstimatedBytes,
            long peakCachedEstimatedBytes,
            long budgetBytes,
            boolean overBudget,
            long fullResyncClears,
            MaterialFacts latestBatchFacts
    ) {
        /**
         * Validates the latest immutable material fact snapshot.
         */
        public Summary {
            latestBatchFacts = Objects.requireNonNull(latestBatchFacts, "latestBatchFacts");
        }

        /**
         * Formats stable single-line cache diagnostics.
         *
         * @return stable diagnostic fragment
         */
        public String asLogFragment() {
            return "cachedSections=" + cachedSections
                    + ", appliedBatches=" + appliedBatches
                    + ", totalMaterialSectionUpdates=" + totalUpdatedSections
                    + ", totalRemovedSections=" + totalRemovedSections
                    + ", materialEvictedSections=" + totalEvictedSections
                    + ", materialCachedBytes=" + cachedEstimatedBytes
                    + ", materialPeakBytes=" + peakCachedEstimatedBytes
                    + ", materialBudgetBytes=" + budgetBytes
                    + ", materialOverBudget=" + overBudget
                    + ", fullResyncClears=" + fullResyncClears
                    + ", " + latestBatchFacts.asLogFragment();
        }
    }

    private record MaterialSectionOwnership(
            SectionEncodedSnapshot diagnosticEncoded,
            long estimatedBytes
    ) {
        private MaterialSectionOwnership {
            if (estimatedBytes <= 0L) {
                throw new IllegalArgumentException("material section estimate must be positive");
            }
        }
    }
}
