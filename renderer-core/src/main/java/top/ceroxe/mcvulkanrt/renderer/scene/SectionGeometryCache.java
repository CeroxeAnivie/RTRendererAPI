package top.ceroxe.mcvulkanrt.renderer.scene;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class SectionGeometryCache {
    private final Object lock = new Object();
    private final SectionMesher mesher;
    private final SceneCacheBudget budget;
    private final boolean retainSnapshots;
    private final Map<SectionKey, SectionGeometrySnapshot> sections = new LinkedHashMap<>(16, 0.75f, true);
    private long appliedBatches;
    private long builtSections;
    private long removedSections;
    private long evictedSections;
    private long cachedEstimatedBytes;
    private long peakCachedEstimatedBytes;
    private long totalFacesBuilt;
    private long fullResyncClears;

    public SectionGeometryCache() {
        this(new SectionMesher(), SceneCacheBudget.DEFAULT, true);
    }

    public SectionGeometryCache(SectionMesher mesher) {
        this(mesher, SceneCacheBudget.DEFAULT, true);
    }

    public SectionGeometryCache(SectionMesher mesher, SceneCacheBudget budget) {
        this(mesher, budget, true);
    }

    private SectionGeometryCache(SectionMesher mesher, SceneCacheBudget budget, boolean retainSnapshots) {
        this.mesher = Objects.requireNonNull(mesher, "mesher");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.retainSnapshots = retainSnapshots;
    }

    /**
     * Creates the production staging policy used after worker threads have already converted
     * section faces into packed triangle meshes. The geometry map returned by the current
     * {@link ApplyResult} remains available to that commit, but the cache does not retain a
     * second, object-heavy copy after the packed mesh has become the RT backfill authority.
     */
    public static SectionGeometryCache transientProductionStaging() {
        return new SectionGeometryCache(new SectionMesher(), SceneCacheBudget.DEFAULT, false);
    }

    /**
     * Renderer frame commits consume packed meshes, not object geometry. A retained cache is
     * deliberately reserved for direct geometry inspection and must not be wired into that
     * production commit path, where it would create a second resident representation.
     */
    public boolean retainsSnapshots() {
        return retainSnapshots;
    }

    public ApplyResult apply(
            Map<SectionKey, SectionEncodedSnapshot> encodedSections,
            Set<SectionKey> removedSectionKeys,
            boolean fullResyncRequested
    ) {
        Objects.requireNonNull(encodedSections, "encodedSections");
        Objects.requireNonNull(removedSectionKeys, "removedSectionKeys");
        Map<SectionKey, SectionGeometrySnapshot> geometryBatch = new LinkedHashMap<>();
        for (SectionEncodedSnapshot encoded : encodedSections.values()) {
            SectionGeometrySnapshot geometry = mesher.build(encoded);
            geometryBatch.put(geometry.key(), geometry);
        }
        return applyPrepared(geometryBatch, removedSectionKeys, fullResyncRequested);
    }

    public ApplyResult applyPrepared(
            Map<SectionKey, SectionGeometrySnapshot> geometryBatch,
            Set<SectionKey> removedSectionKeys,
            boolean fullResyncRequested
    ) {
        Objects.requireNonNull(geometryBatch, "geometryBatch");
        Objects.requireNonNull(removedSectionKeys, "removedSectionKeys");

        int builtInBatch = 0;
        int removedInBatch = 0;
        int evictedInBatch = 0;
        int facesInBatch = 0;
        synchronized (lock) {
            if (fullResyncRequested) {
                sections.clear();
                cachedEstimatedBytes = 0L;
                fullResyncClears++;
            } else if (retainSnapshots) {
                removedInBatch = removeSections(removedSectionKeys);
            }

            for (Map.Entry<SectionKey, SectionGeometrySnapshot> entry : geometryBatch.entrySet()) {
                SectionGeometrySnapshot geometry = entry.getValue();
                if (!entry.getKey().equals(geometry.key())) {
                    throw new IllegalArgumentException("geometryBatch key must match geometry key");
                }
                if (retainSnapshots) {
                    replaceSection(entry.getKey(), geometry);
                }
                builtInBatch++;
                facesInBatch += geometry.faceCount();
            }
            if (retainSnapshots) {
                evictedInBatch = evictUntilWithinBudget(geometryBatch.keySet());
            }

            if (fullResyncRequested || !geometryBatch.isEmpty() || !removedSectionKeys.isEmpty()) {
                appliedBatches++;
            }
            builtSections += builtInBatch;
            removedSections += removedInBatch;
            totalFacesBuilt += facesInBatch;
            return new ApplyResult(
                    appliedBatches,
                    builtInBatch,
                    removedInBatch,
                    facesInBatch,
                    sections.size(),
                    removedSections,
                    evictedInBatch,
                    evictedSections,
                    cachedEstimatedBytes,
                    peakCachedEstimatedBytes,
                    budget.geometryBytes(),
                    cachedEstimatedBytes > budget.geometryBytes(),
                    totalFacesBuilt,
                    fullResyncClears,
                    geometryBatch
            );
        }
    }

    /**
     * Records production worker output after its geometry staging has been consumed.
     *
     * <p>The production path publishes packed meshes. Keeping only scalar face counts preserves
     * diagnostics without retaining a second object-heavy section representation beside them.</p>
     */
    public ApplyResult applyProducedFaceCounts(
            Map<SectionKey, Integer> faceCounts,
            Set<SectionKey> removedSectionKeys,
            boolean fullResyncRequested
    ) {
        Objects.requireNonNull(faceCounts, "faceCounts");
        Objects.requireNonNull(removedSectionKeys, "removedSectionKeys");
        if (retainSnapshots) {
            throw new IllegalStateException("retained geometry cache requires geometry snapshots");
        }
        int facesInBatch = 0;
        for (Map.Entry<SectionKey, Integer> entry : faceCounts.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "face-count section key");
            Integer faceCount = Objects.requireNonNull(entry.getValue(), "face count");
            if (faceCount < 0) {
                throw new IllegalArgumentException("face count must not be negative");
            }
            facesInBatch = Math.addExact(facesInBatch, faceCount);
        }
        synchronized (lock) {
            if (fullResyncRequested) {
                sections.clear();
                cachedEstimatedBytes = 0L;
                fullResyncClears++;
            }
            if (fullResyncRequested || !faceCounts.isEmpty() || !removedSectionKeys.isEmpty()) {
                appliedBatches++;
            }
            builtSections += faceCounts.size();
            totalFacesBuilt += facesInBatch;
            return new ApplyResult(
                    appliedBatches,
                    faceCounts.size(),
                    0,
                    facesInBatch,
                    0,
                    removedSections,
                    0,
                    evictedSections,
                    0L,
                    peakCachedEstimatedBytes,
                    budget.geometryBytes(),
                    false,
                    totalFacesBuilt,
                    fullResyncClears,
                    Map.of()
            );
        }
    }

    public SectionGeometrySnapshot snapshot(SectionKey key) {
        synchronized (lock) {
            return sections.get(key);
        }
    }

    public Summary summary() {
        synchronized (lock) {
            return new Summary(
                    sections.size(),
                    appliedBatches,
                    builtSections,
                    removedSections,
                    evictedSections,
                    cachedEstimatedBytes,
                    peakCachedEstimatedBytes,
                    budget.geometryBytes(),
                    cachedEstimatedBytes > budget.geometryBytes(),
                    totalFacesBuilt,
                    fullResyncClears
            );
        }
    }

    private void replaceSection(SectionKey key, SectionGeometrySnapshot geometry) {
        SectionGeometrySnapshot previous = sections.put(key, geometry);
        if (previous != null) {
            cachedEstimatedBytes -= previous.estimatedBytes();
        }
        cachedEstimatedBytes += geometry.estimatedBytes();
        peakCachedEstimatedBytes = Math.max(peakCachedEstimatedBytes, cachedEstimatedBytes);
    }

    private int removeSections(Set<SectionKey> removedSectionKeys) {
        int removedInBatch = 0;
        for (SectionKey key : removedSectionKeys) {
            SectionGeometrySnapshot removed = sections.remove(key);
            if (removed != null) {
                cachedEstimatedBytes -= removed.estimatedBytes();
                removedInBatch++;
            }
        }
        return removedInBatch;
    }

    private int evictUntilWithinBudget(Set<SectionKey> protectedKeys) {
        int evictedInBatch = 0;
        while (cachedEstimatedBytes > budget.geometryBytes()) {
            boolean evicted = false;
            var iterator = sections.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<SectionKey, SectionGeometrySnapshot> entry = iterator.next();
                if (protectedKeys.contains(entry.getKey())) {
                    continue;
                }
                SectionGeometrySnapshot removed = entry.getValue();
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

    public record ApplyResult(
            long appliedBatches,
            int builtInBatch,
            int removedInBatch,
            int facesInBatch,
            int cachedSections,
            long totalRemovedSections,
            int evictedInBatch,
            long totalEvictedSections,
            long cachedEstimatedBytes,
            long peakCachedEstimatedBytes,
            long budgetBytes,
            boolean overBudget,
            long totalFacesBuilt,
            long fullResyncClears,
            Map<SectionKey, SectionGeometrySnapshot> geometrySections
    ) {
        public ApplyResult {
            geometrySections = Map.copyOf(Objects.requireNonNull(geometrySections, "geometrySections"));
        }
    }

    public record Summary(
            int cachedSections,
            long appliedBatches,
            long builtSections,
            long totalRemovedSections,
            long totalEvictedSections,
            long cachedEstimatedBytes,
            long peakCachedEstimatedBytes,
            long budgetBytes,
            boolean overBudget,
            long totalFacesBuilt,
            long fullResyncClears
    ) {
        public String asLogFragment() {
            return "geometryCachedSections=" + cachedSections
                    + ", geometryAppliedBatches=" + appliedBatches
                    + ", geometryBuiltSections=" + builtSections
                    + ", geometryRemovedSections=" + totalRemovedSections
                    + ", geometryEvictedSections=" + totalEvictedSections
                    + ", geometryCachedBytes=" + cachedEstimatedBytes
                    + ", geometryPeakBytes=" + peakCachedEstimatedBytes
                    + ", geometryBudgetBytes=" + budgetBytes
                    + ", geometryOverBudget=" + overBudget
                    + ", totalFacesBuilt=" + totalFacesBuilt
                    + ", geometryFullResyncClears=" + fullResyncClears;
        }
    }
}
