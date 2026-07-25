package top.ceroxe.rt.renderer.scene;

import top.ceroxe.rt.renderer.SectionLifecycleFlightRecorder;

import java.util.*;
import java.util.function.Predicate;

/**
 * Thread-safe, budgeted owner of immutable CPU section-mesh staging payloads.
 */
public final class SectionMeshCache {
    private final Object lock = new Object();
    private final SectionMeshBuilder builder;
    private final SceneCacheBudget budget;
    private final Map<SectionKey, SectionTriangleMesh> sections = new LinkedHashMap<>(16, 0.75f, true);
    private final Set<SectionKey> knownRenderableSections = new LinkedHashSet<>();
    /*
     * A built zero-triangle mesh is a completed topology result, not missing
     * work. Keep that fact after CPU staging eviction/transfer so generation
     * admission can omit the section from TLAS membership without rebuilding it.
     */
    private final Set<SectionKey> knownResolvedSections = new LinkedHashSet<>();
    private final Set<SectionKey> pendingRenderableEntries = new LinkedHashSet<>();
    private final Set<SectionKey> pendingRenderableRetirements = new LinkedHashSet<>();
    private final Set<SectionKey> pendingResolvedEntries = new LinkedHashSet<>();
    private final Set<SectionKey> pendingResolvedRetirements = new LinkedHashSet<>();
    /* Lazily frozen once per consumed topology revision, coalescing producer batches. */
    private RenderableSectionsSnapshot publishedRenderableSections =
            new RenderableSectionsSnapshot(0L, Set.of(), Set.of());
    private long appliedBatches;
    private long builtSections;
    private long removedSections;
    private long evictedSections;
    private long totalTrianglesBuilt;
    private long totalEstimatedBytes;
    private long cachedEstimatedBytes;
    private long peakCachedEstimatedBytes;
    private long fullResyncClears;
    private long knownTopologyRevision;
    private int cachedRenderableSectionCount;

    /**
     * Creates a cache with the production builder and default memory budget.
     */
    public SectionMeshCache() {
        this(new SectionMeshBuilder(), SceneCacheBudget.DEFAULT);
    }

    /**
     * Creates a cache with an explicit mesh builder.
     *
     * @param builder mesh builder used for geometry snapshots
     */
    public SectionMeshCache(SectionMeshBuilder builder) {
        this(builder, SceneCacheBudget.DEFAULT);
    }

    /**
     * Creates a cache with an explicit mesh builder and retained CPU budget.
     *
     * @param builder mesh builder used for geometry snapshots
     * @param budget  retained CPU mesh budget
     */
    public SectionMeshCache(SectionMeshBuilder builder, SceneCacheBudget budget) {
        this.builder = Objects.requireNonNull(builder, "builder");
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    static PackedSectionMembership immutableBackfillMembership(Collection<SectionKey> sectionKeys) {
        return PackedSectionMembership.copyOf(Objects.requireNonNull(sectionKeys, "sectionKeys"));
    }

    private static void recordEntry(
            SectionKey key,
            Set<SectionKey> entries,
            Set<SectionKey> retirements
    ) {
        if (!retirements.remove(key)) {
            entries.add(key);
        }
    }

    private static void recordRetirement(
            SectionKey key,
            Set<SectionKey> entries,
            Set<SectionKey> retirements
    ) {
        if (!entries.remove(key)) {
            retirements.add(key);
        }
    }

    /**
     * Builds and atomically applies one scene-geometry batch.
     *
     * @param geometrySections    replacement geometry keyed by section
     * @param removedSectionKeys  sections retired by this batch
     * @param fullResyncRequested whether all previous cache and topology state must be discarded
     * @return immutable application result and built meshes
     */
    public ApplyResult apply(
            Map<SectionKey, SectionGeometrySnapshot> geometrySections,
            Set<SectionKey> removedSectionKeys,
            boolean fullResyncRequested
    ) {
        Objects.requireNonNull(geometrySections, "geometrySections");
        Objects.requireNonNull(removedSectionKeys, "removedSectionKeys");
        Map<SectionKey, SectionTriangleMesh> builtMeshesInBatch = new LinkedHashMap<>();
        for (SectionGeometrySnapshot geometry : geometrySections.values()) {
            SectionTriangleMesh mesh = builder.build(geometry);
            builtMeshesInBatch.put(mesh.key(), mesh);
        }
        return applyPrepared(builtMeshesInBatch, removedSectionKeys, fullResyncRequested);
    }

    /**
     * Atomically applies meshes already built by an asynchronous worker stage.
     *
     * @param builtMeshesInBatch  replacement meshes keyed by matching section identity
     * @param removedSectionKeys  sections retired by this batch
     * @param fullResyncRequested whether all previous cache and topology state must be discarded
     * @return immutable application result and applied meshes
     */
    public ApplyResult applyPrepared(
            Map<SectionKey, SectionTriangleMesh> builtMeshesInBatch,
            Set<SectionKey> removedSectionKeys,
            boolean fullResyncRequested
    ) {
        Objects.requireNonNull(builtMeshesInBatch, "builtMeshesInBatch");
        Objects.requireNonNull(removedSectionKeys, "removedSectionKeys");
        for (Map.Entry<SectionKey, SectionTriangleMesh> entry : builtMeshesInBatch.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().key())) {
                throw new IllegalArgumentException("builtMeshesInBatch key must match mesh key");
            }
        }

        int builtInBatch = 0;
        int removedInBatch = 0;
        int evictedInBatch = 0;
        int trianglesInBatch = 0;
        long estimatedBytesInBatch = 0L;
        synchronized (lock) {
            if (fullResyncRequested) {
                sections.clear();
                knownTopologyRevision++;
                knownRenderableSections.clear();
                knownResolvedSections.clear();
                pendingRenderableEntries.clear();
                pendingRenderableRetirements.clear();
                pendingResolvedEntries.clear();
                pendingResolvedRetirements.clear();
                publishedRenderableSections = new RenderableSectionsSnapshot(
                        knownTopologyRevision,
                        PackedSectionMembership.empty(),
                        PackedSectionMembership.empty()
                );
                cachedEstimatedBytes = 0L;
                cachedRenderableSectionCount = 0;
                fullResyncClears++;
            } else {
                removedInBatch = removeSections(removedSectionKeys);
            }

            for (Map.Entry<SectionKey, SectionTriangleMesh> entry : builtMeshesInBatch.entrySet()) {
                SectionTriangleMesh mesh = entry.getValue();
                replaceSection(entry.getKey(), mesh);
                builtInBatch++;
                trianglesInBatch += mesh.triangleCount();
                estimatedBytesInBatch += mesh.estimatedBytes();
            }
            evictedInBatch = evictUntilWithinBudget(builtMeshesInBatch.keySet());

            if (fullResyncRequested || !builtMeshesInBatch.isEmpty() || !removedSectionKeys.isEmpty()) {
                appliedBatches++;
            }
            builtSections += builtInBatch;
            removedSections += removedInBatch;
            totalTrianglesBuilt += trianglesInBatch;
            totalEstimatedBytes += estimatedBytesInBatch;
            return new ApplyResult(
                    appliedBatches,
                    builtInBatch,
                    removedInBatch,
                    trianglesInBatch,
                    estimatedBytesInBatch,
                    sections.size(),
                    knownRenderableSections.size(),
                    removedSections,
                    evictedInBatch,
                    evictedSections,
                    cachedEstimatedBytes,
                    peakCachedEstimatedBytes,
                    budget.meshBytes(),
                    cachedEstimatedBytes > budget.meshBytes(),
                    totalTrianglesBuilt,
                    totalEstimatedBytes,
                    fullResyncClears,
                    builtMeshesInBatch
            );
        }
    }

    /**
     * Returns the retained mesh for one section.
     *
     * @param key section identity
     * @return retained mesh, or {@code null} when not cached
     */
    public SectionTriangleMesh snapshot(SectionKey key) {
        synchronized (lock) {
            return sections.get(key);
        }
    }

    /**
     * Returns the current monotonic topology revision.
     *
     * @return current monotonic topology revision
     */
    public long knownTopologyRevision() {
        synchronized (lock) {
            return knownTopologyRevision;
        }
    }

    /**
     * Returns current renderable and resolved section membership.
     *
     * @return immutable current renderable and resolved section membership
     */
    public RenderableSectionsSnapshot snapshotKnownRenderableSections() {
        synchronized (lock) {
            if (publishedRenderableSections.revision() != knownTopologyRevision) {
                publishRenderableSections();
            }
            return publishedRenderableSections;
        }
    }

    /**
     * Returns section keys with retained CPU payloads.
     *
     * @return immutable snapshot of section keys with retained CPU payloads
     */
    public Set<SectionKey> snapshotCachedSectionKeys() {
        synchronized (lock) {
            return Set.copyOf(sections.keySet());
        }
    }

    /**
     * Selects sections whose renderer output cannot be recovered without a new voxel source.
     *
     * <p>{@code knownRenderableSections} is a topology ledger, not retained geometry. After a
     * mesh has transferred to native BLAS ownership, {@link #releaseTransferredToRt(Set)} keeps
     * that ledger entry while intentionally releasing the Java mesh payload. Treating the ledger
     * as reusable output after the native owner later drops the BLAS creates an unrecoverable
     * section: neither the CPU cache nor native owns geometry, yet source extraction is suppressed.
     *
     * <p>A resolved empty section is different: its zero-triangle result is the complete reusable
     * output and requires neither a mesh payload nor a BLAS. The supplied predicate represents the
     * other legitimate payload owner (normally native terrain ownership).</p>
     *
     * @param requestedSectionKeys sections for which recoverable output is required
     * @param retainedOutputOwner  predicate identifying an exact non-cache output owner
     * @return requested sections requiring a fresh voxel source
     */
    public Set<SectionKey> sectionsRequiringSourceRecovery(
            Set<SectionKey> requestedSectionKeys,
            Predicate<SectionKey> retainedOutputOwner
    ) {
        Objects.requireNonNull(requestedSectionKeys, "requestedSectionKeys");
        Objects.requireNonNull(retainedOutputOwner, "retainedOutputOwner");
        if (requestedSectionKeys.isEmpty()) {
            return Set.of();
        }
        synchronized (lock) {
            LinkedHashSet<SectionKey> missing = null;
            for (SectionKey key : requestedSectionKeys) {
                Objects.requireNonNull(key, "requested section key");
                boolean cachedPayload = sections.containsKey(key);
                boolean resolvedEmpty = knownResolvedSections.contains(key)
                        && !knownRenderableSections.contains(key);
                if (cachedPayload || resolvedEmpty || retainedOutputOwner.test(key)) {
                    continue;
                }
                if (missing == null) {
                    missing = new LinkedHashSet<>();
                }
                missing.add(key);
            }
            return missing == null ? Set.of() : Set.copyOf(missing);
        }
    }

    /**
     * Allocation-free coverage query for chunk admission. The supplied predicate represents
     * immutable source/native ownership captured before entering this cache.
     *
     * @param requestedSectionKeys sections requiring reusable output
     * @param retainedOutputOwner  predicate identifying an exact non-cache output owner
     * @return whether every requested section has cached, resolved-empty, or retained output
     */
    public boolean hasReusableOutputForAll(
            Set<SectionKey> requestedSectionKeys,
            Predicate<SectionKey> retainedOutputOwner
    ) {
        Objects.requireNonNull(requestedSectionKeys, "requestedSectionKeys");
        Objects.requireNonNull(retainedOutputOwner, "retainedOutputOwner");
        synchronized (lock) {
            for (SectionKey key : requestedSectionKeys) {
                Objects.requireNonNull(key, "requested section key");
                boolean cachedPayload = sections.containsKey(key);
                boolean resolvedEmpty = knownResolvedSections.contains(key)
                        && !knownRenderableSections.contains(key);
                if (!cachedPayload && !resolvedEmpty && !retainedOutputOwner.test(key)) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Releases CPU geometry after native exact-BLAS ownership is observable.
     * The renderable-key ledger intentionally remains: it records that the
     * section has a valid renderer representation, while RendererCore uses
     * native ownership (rather than this cache) to decide whether a rebuild is
     * required after eviction or device loss.
     *
     * @param activeSectionKeys sections whose exact native ownership is observable
     * @return number of CPU mesh payloads released
     */
    public int releaseTransferredToRt(Set<SectionKey> activeSectionKeys) {
        Objects.requireNonNull(activeSectionKeys, "activeSectionKeys");
        if (activeSectionKeys.isEmpty()) {
            return 0;
        }
        synchronized (lock) {
            int released = 0;
            for (SectionKey key : activeSectionKeys) {
                SectionTriangleMesh removed = sections.remove(key);
                if (removed != null) {
                    cachedEstimatedBytes = Math.max(0L, cachedEstimatedBytes - removed.estimatedBytes());
                    if (removed.triangleCount() > 0) {
                        cachedRenderableSectionCount--;
                    }
                    released++;
                }
            }
            return released;
        }
    }

    /**
     * Releases staging payloads only after an exact-geometry lifecycle owner accepts them.
     *
     * <p>Native source membership may retain only a compact FarField proxy after the full mesh is
     * gone. A predicate keeps that proxy identity from masquerading as exact BLAS ownership and
     * avoids allocating a six-stage union merely to perform this transfer.</p>
     *
     * @param exactGeometryOwner predicate proving exact native geometry ownership
     * @return number of CPU mesh payloads released
     */
    public int releaseTransferredToRt(Predicate<SectionKey> exactGeometryOwner) {
        Objects.requireNonNull(exactGeometryOwner, "exactGeometryOwner");
        synchronized (lock) {
            int released = 0;
            var entries = sections.entrySet().iterator();
            while (entries.hasNext()) {
                Map.Entry<SectionKey, SectionTriangleMesh> entry = entries.next();
                if (!exactGeometryOwner.test(entry.getKey())) {
                    continue;
                }
                SectionTriangleMesh removed = entry.getValue();
                entries.remove();
                cachedEstimatedBytes = Math.max(0L, cachedEstimatedBytes - removed.estimatedBytes());
                if (removed.triangleCount() > 0) {
                    cachedRenderableSectionCount--;
                }
                released++;
            }
            return released;
        }
    }

    /**
     * Selects a bounded backfill batch excluding downstream-owned sections.
     *
     * @param excludedSectionKeys sections already owned by downstream lifecycle stages
     * @param maxSections         maximum selected meshes
     * @param maxEstimatedBytes   maximum estimated selected bytes
     * @return bounded immutable backfill batch
     */
    public BackfillBatch snapshotRenderableBackfill(
            Set<SectionKey> excludedSectionKeys,
            int maxSections,
            long maxEstimatedBytes
    ) {
        return snapshotRenderableBackfill(excludedSectionKeys, Set.of(), maxSections, maxEstimatedBytes);
    }

    /**
     * Selects a bounded backfill batch with explicit eligibility membership.
     *
     * @param excludedSectionKeys sections already owned downstream
     * @param eligibleSectionKeys optional eligibility filter; empty selects any non-excluded mesh
     * @param maxSections         maximum selected meshes
     * @param maxEstimatedBytes   maximum estimated selected bytes
     * @return bounded immutable backfill batch
     */
    public BackfillBatch snapshotRenderableBackfill(
            Set<SectionKey> excludedSectionKeys,
            Set<SectionKey> eligibleSectionKeys,
            int maxSections,
            long maxEstimatedBytes
    ) {
        Objects.requireNonNull(excludedSectionKeys, "excludedSectionKeys");
        Objects.requireNonNull(eligibleSectionKeys, "eligibleSectionKeys");
        if (maxSections <= 0) {
            throw new IllegalArgumentException("maxSections must be positive");
        }
        if (maxEstimatedBytes <= 0L) {
            throw new IllegalArgumentException("maxEstimatedBytes must be positive");
        }

        PackedSectionMembership excluded = immutableBackfillMembership(excludedSectionKeys);
        PackedSectionMembership eligible = immutableBackfillMembership(eligibleSectionKeys);
        return snapshotRenderableBackfill(excluded::contains, eligible, maxSections, maxEstimatedBytes);
    }

    /**
     * Selects retained CPU meshes using a read-only ownership predicate.
     *
     * <p>The RT backend has several disjoint immutable lifecycle sets. Its backfill path needs
     * only membership in their union, not a newly allocated union set. Keeping that distinction
     * here prevents a full section-key copy on every coverage repair frame.</p>
     *
     * @param excludedSection     read-only downstream ownership predicate
     * @param eligibleSectionKeys optional eligibility filter
     * @param maxSections         maximum selected meshes
     * @param maxEstimatedBytes   maximum estimated selected bytes
     * @return bounded immutable backfill batch
     */
    public BackfillBatch snapshotRenderableBackfill(
            Predicate<SectionKey> excludedSection,
            Set<SectionKey> eligibleSectionKeys,
            int maxSections,
            long maxEstimatedBytes
    ) {
        Objects.requireNonNull(excludedSection, "excludedSection");
        Objects.requireNonNull(eligibleSectionKeys, "eligibleSectionKeys");
        if (maxSections <= 0) {
            throw new IllegalArgumentException("maxSections must be positive");
        }
        if (maxEstimatedBytes <= 0L) {
            throw new IllegalArgumentException("maxEstimatedBytes must be positive");
        }

        PackedSectionMembership eligible = immutableBackfillMembership(eligibleSectionKeys);
        synchronized (lock) {
            Map<SectionKey, SectionTriangleMesh> selected = new LinkedHashMap<>();
            int trianglesInBatch = 0;
            long estimatedBytesInBatch = 0L;
            /*
             * RT backfill only needs renderable meshes. Iterating the full LRU
             * map repeatedly makes stable worlds pay for empty and stale staging
             * entries every frame. Keep the authority in the mesh map, but drive
             * the scan from the compact renderable-key set.
             */
            for (SectionKey key : knownRenderableSections) {
                if (excludedSection.test(key)) {
                    continue;
                }
                if (!eligible.isEmpty() && !eligible.contains(key)) {
                    continue;
                }

                SectionTriangleMesh mesh = sections.get(key);
                if (mesh == null || mesh.triangleCount() <= 0) {
                    continue;
                }

                long meshBytes = mesh.estimatedBytes();
                if (!selected.isEmpty()
                        && (selected.size() >= maxSections
                        || estimatedBytesInBatch + meshBytes > maxEstimatedBytes)) {
                    break;
                }

                selected.put(key, mesh);
                trianglesInBatch += mesh.triangleCount();
                estimatedBytesInBatch += meshBytes;
                if (selected.size() >= maxSections || estimatedBytesInBatch >= maxEstimatedBytes) {
                    break;
                }
            }
            return new BackfillBatch(selected, trianglesInBatch, estimatedBytesInBatch, summaryLocked());
        }
    }

    /**
     * Returns immutable cache counters and budget state.
     *
     * @return immutable cache counters and budget state
     */
    public Summary summary() {
        synchronized (lock) {
            return summaryLocked();
        }
    }

    private void replaceSection(SectionKey key, SectionTriangleMesh mesh) {
        SectionTriangleMesh previous = sections.put(key, mesh);
        if (previous != null) {
            cachedEstimatedBytes -= previous.estimatedBytes();
        }
        boolean wasRenderable = knownRenderableSections.contains(key);
        boolean isRenderable = mesh.triangleCount() > 0;
        boolean wasCachedRenderable = previous != null && previous.triangleCount() > 0;
        if (wasCachedRenderable != isRenderable) {
            cachedRenderableSectionCount += isRenderable ? 1 : -1;
        }
        boolean topologyChanged = knownResolvedSections.add(key);
        if (topologyChanged) {
            recordEntry(key, pendingResolvedEntries, pendingResolvedRetirements);
        }
        if (isRenderable) {
            knownRenderableSections.add(key);
        } else {
            knownRenderableSections.remove(key);
        }
        if (wasRenderable != isRenderable) {
            topologyChanged = true;
            if (isRenderable) {
                recordEntry(key, pendingRenderableEntries, pendingRenderableRetirements);
            } else {
                recordRetirement(key, pendingRenderableEntries, pendingRenderableRetirements);
            }
        }
        if (topologyChanged) {
            knownTopologyRevision++;
        }
        cachedEstimatedBytes += mesh.estimatedBytes();
        peakCachedEstimatedBytes = Math.max(peakCachedEstimatedBytes, cachedEstimatedBytes);
    }

    private int removeSections(Set<SectionKey> removedSectionKeys) {
        int removedInBatch = 0;
        boolean topologyChanged = false;
        for (SectionKey key : removedSectionKeys) {
            SectionTriangleMesh removed = sections.remove(key);
            boolean resolved = knownResolvedSections.contains(key);
            if (removed != null) {
                cachedEstimatedBytes -= removed.estimatedBytes();
                if (removed.triangleCount() > 0) {
                    cachedRenderableSectionCount--;
                }
                removedInBatch++;
            }
            if (knownRenderableSections.remove(key)) {
                topologyChanged = true;
                recordRetirement(key, pendingRenderableEntries, pendingRenderableRetirements);
            }
            if (knownResolvedSections.remove(key)) {
                topologyChanged = true;
                recordRetirement(key, pendingResolvedEntries, pendingResolvedRetirements);
            }
            /*
             * A retirement request can legitimately reach the CPU cache before this section ever
             * completed a mesh build.  Preserve that distinction in JFR; otherwise an absent mesh
             * is indistinguishable from a cache that ignored the request.
             */
            SectionLifecycleFlightRecorder.record(
                    SectionLifecycleFlightRecorder.STAGE_CPU_MESH_RETIRED,
                    SectionLifecycleFlightRecorder.SOURCE_UNKNOWN,
                    removed != null || resolved
                            ? SectionLifecycleFlightRecorder.OUTCOME_ACCEPTED
                            : SectionLifecycleFlightRecorder.OUTCOME_STALE,
                    key,
                    0,
                    knownTopologyRevision,
                    -1L,
                    -1L,
                    0,
                    sections.size(),
                    removed == null ? 0L : removed.estimatedBytes()
            );
        }
        if (topologyChanged) {
            knownTopologyRevision++;
        }
        return removedInBatch;
    }

    private void publishRenderableSections() {
        PackedSectionMembership previousRenderable = (PackedSectionMembership) publishedRenderableSections.sectionKeys();
        PackedSectionMembership previousResolved =
                (PackedSectionMembership) publishedRenderableSections.resolvedSectionKeys();
        PackedSectionMembership nextRenderable = previousRenderable.withDelta(
                pendingRenderableEntries,
                pendingRenderableRetirements
        );
        PackedSectionMembership nextResolved = previousResolved.withDelta(
                pendingResolvedEntries,
                pendingResolvedRetirements
        );
        publishedRenderableSections = new RenderableSectionsSnapshot(
                knownTopologyRevision,
                nextRenderable,
                nextResolved
        );
        pendingRenderableEntries.clear();
        pendingRenderableRetirements.clear();
        pendingResolvedEntries.clear();
        pendingResolvedRetirements.clear();
    }

    private int evictUntilWithinBudget(Set<SectionKey> protectedKeys) {
        int evictedInBatch = 0;
        /*
         * LinkedHashMap already exposes LRU order. The map owns only recoverable CPU staging,
         * not renderer topology or native BLAS lifetime. Retaining every non-empty mesh here
         * turns the nominal byte budget into a no-op during streaming because the expensive
         * entries are precisely the entries which were previously skipped.
         *
         * The current batch is protected until its ApplyResult reaches the RT transaction. An
         * older entry may be released regardless of triangle count: its lightweight topology
         * fact stays in knownRenderableSections, and a later exact-owner loss requests a fresh
         * voxel source rather than treating that fact as geometry ownership.
         */
        var iterator = sections.entrySet().iterator();
        while (cachedEstimatedBytes > budget.meshBytes() && iterator.hasNext()) {
            Map.Entry<SectionKey, SectionTriangleMesh> entry = iterator.next();
            if (protectedKeys.contains(entry.getKey())) {
                continue;
            }
            SectionTriangleMesh removed = entry.getValue();
            iterator.remove();
            cachedEstimatedBytes -= removed.estimatedBytes();
            if (removed.triangleCount() > 0) {
                cachedRenderableSectionCount--;
            }
            evictedInBatch++;
        }
        evictedSections += evictedInBatch;
        return evictedInBatch;
    }

    private Summary summaryLocked() {
        return new Summary(
                sections.size(),
                cachedRenderableSectionCount,
                knownRenderableSections.size(),
                appliedBatches,
                builtSections,
                removedSections,
                evictedSections,
                cachedEstimatedBytes,
                peakCachedEstimatedBytes,
                budget.meshBytes(),
                cachedEstimatedBytes > budget.meshBytes(),
                totalTrianglesBuilt,
                totalEstimatedBytes,
                fullResyncClears
        );
    }

    /**
     * Immutable bounded backfill selection and its observed cache state.
     *
     * @param meshes                selected immutable mesh map
     * @param trianglesInBatch      total selected triangles
     * @param estimatedBytesInBatch total estimated selected bytes
     * @param cacheSummary          cache state observed during selection
     */
    public record BackfillBatch(
            Map<SectionKey, SectionTriangleMesh> meshes,
            int trianglesInBatch,
            long estimatedBytesInBatch,
            Summary cacheSummary
    ) {
        /**
         * Validates and freezes the selected backfill payload.
         */
        public BackfillBatch {
            meshes = Map.copyOf(Objects.requireNonNull(meshes, "meshes"));
            cacheSummary = Objects.requireNonNull(cacheSummary, "cacheSummary");
            if (trianglesInBatch < 0) {
                throw new IllegalArgumentException("trianglesInBatch must not be negative");
            }
            if (estimatedBytesInBatch < 0L) {
                throw new IllegalArgumentException("estimatedBytesInBatch must not be negative");
            }
            for (Map.Entry<SectionKey, SectionTriangleMesh> entry : meshes.entrySet()) {
                if (!entry.getKey().equals(entry.getValue().key())) {
                    throw new IllegalArgumentException("backfill mesh key must match mesh payload key");
                }
            }
        }

        /**
         * Reports whether no meshes were selected.
         *
         * @return whether no meshes were selected
         */
        public boolean isEmpty() {
            return meshes.isEmpty();
        }
    }

    /**
     * One immutable topology publication: renderable is always a subset of resolved.
     *
     * @param revision            topology publication revision
     * @param sectionKeys         immutable renderable section keys
     * @param resolvedSectionKeys immutable resolved section keys
     */
    public record RenderableSectionsSnapshot(
            long revision,
            Set<SectionKey> sectionKeys,
            Set<SectionKey> resolvedSectionKeys
    ) {
        /**
         * Validates and freezes the topology membership snapshot.
         */
        public RenderableSectionsSnapshot {
            if (revision < 0L) {
                throw new IllegalArgumentException("renderable section revision must not be negative");
            }
            sectionKeys = PackedSectionMembership.copyOf(Objects.requireNonNull(sectionKeys, "sectionKeys"));
            resolvedSectionKeys = PackedSectionMembership.copyOf(Objects.requireNonNull(
                    resolvedSectionKeys,
                    "resolvedSectionKeys"
            ));
            if (!resolvedSectionKeys.containsAll(sectionKeys)) {
                throw new IllegalArgumentException("renderable sections must be resolved");
            }
        }
    }

    /**
     * Result and telemetry for one applied mesh batch.
     *
     * @param appliedBatches           cumulative applied batches
     * @param builtInBatch             meshes built in this batch
     * @param removedInBatch           meshes removed in this batch
     * @param trianglesInBatch         triangles built in this batch
     * @param estimatedBytesInBatch    estimated bytes built in this batch
     * @param cachedSections           retained CPU meshes
     * @param knownRenderableSections  known non-empty topology entries
     * @param totalRemovedSections     cumulative removed meshes
     * @param evictedInBatch           meshes evicted in this batch
     * @param totalEvictedSections     cumulative evictions
     * @param cachedEstimatedBytes     current retained bytes
     * @param peakCachedEstimatedBytes peak retained bytes
     * @param budgetBytes              configured retained-byte budget
     * @param overBudget               whether protected current-batch payloads leave the cache over budget
     * @param totalTrianglesBuilt      cumulative built triangles
     * @param totalEstimatedBytes      cumulative estimated built bytes
     * @param fullResyncClears         cumulative full-resync clears
     * @param builtMeshes              meshes applied by this batch
     */
    public record ApplyResult(
            long appliedBatches,
            int builtInBatch,
            int removedInBatch,
            int trianglesInBatch,
            long estimatedBytesInBatch,
            int cachedSections,
            int knownRenderableSections,
            long totalRemovedSections,
            int evictedInBatch,
            long totalEvictedSections,
            long cachedEstimatedBytes,
            long peakCachedEstimatedBytes,
            long budgetBytes,
            boolean overBudget,
            long totalTrianglesBuilt,
            long totalEstimatedBytes,
            long fullResyncClears,
            Map<SectionKey, SectionTriangleMesh> builtMeshes
    ) {
        /**
         * Freezes the immutable mesh payload published by this batch.
         */
        public ApplyResult {
            builtMeshes = Map.copyOf(Objects.requireNonNull(builtMeshes, "builtMeshes"));
        }
    }

    /**
     * Immutable mesh-cache counters and budget state.
     *
     * @param cachedSections           retained CPU meshes
     * @param cachedRenderableSections retained non-empty CPU meshes
     * @param knownRenderableSections  known non-empty topology entries
     * @param appliedBatches           cumulative applied batches
     * @param builtSections            cumulative built sections
     * @param totalRemovedSections     cumulative removed sections
     * @param totalEvictedSections     cumulative evicted payloads
     * @param cachedEstimatedBytes     current retained bytes
     * @param peakCachedEstimatedBytes peak retained bytes
     * @param budgetBytes              configured retained-byte budget
     * @param overBudget               whether current retained bytes exceed the budget
     * @param totalTrianglesBuilt      cumulative built triangles
     * @param totalEstimatedBytes      cumulative estimated built bytes
     * @param fullResyncClears         cumulative full-resync clears
     */
    public record Summary(
            int cachedSections,
            int cachedRenderableSections,
            int knownRenderableSections,
            long appliedBatches,
            long builtSections,
            long totalRemovedSections,
            long totalEvictedSections,
            long cachedEstimatedBytes,
            long peakCachedEstimatedBytes,
            long budgetBytes,
            boolean overBudget,
            long totalTrianglesBuilt,
            long totalEstimatedBytes,
            long fullResyncClears
    ) {
        /**
         * Validates mesh-cache membership counters.
         */
        public Summary {
            if (cachedSections < 0) {
                throw new IllegalArgumentException("cachedSections must not be negative");
            }
            if (cachedRenderableSections < 0 || cachedRenderableSections > cachedSections) {
                throw new IllegalArgumentException("cachedRenderableSections must be in range 0..cachedSections");
            }
            if (knownRenderableSections < 0) {
                throw new IllegalArgumentException("knownRenderableSections must not be negative");
            }
        }

        /**
         * Formats stable renderer diagnostics.
         *
         * @return stable key-value fragment suitable for renderer diagnostics
         */
        public String asLogFragment() {
            return "meshCachedSections=" + cachedSections
                    + ", meshCachedRenderableSections=" + cachedRenderableSections
                    + ", meshKnownRenderableSections=" + knownRenderableSections
                    + ", meshAppliedBatches=" + appliedBatches
                    + ", meshBuiltSections=" + builtSections
                    + ", meshRemovedSections=" + totalRemovedSections
                    + ", meshEvictedSections=" + totalEvictedSections
                    + ", meshCachedBytes=" + cachedEstimatedBytes
                    + ", meshPeakBytes=" + peakCachedEstimatedBytes
                    + ", meshBudgetBytes=" + budgetBytes
                    + ", meshOverBudget=" + overBudget
                    + ", totalTrianglesBuilt=" + totalTrianglesBuilt
                    + ", totalEstimatedBytes=" + totalEstimatedBytes
                    + ", meshFullResyncClears=" + fullResyncClears;
        }
    }
}
