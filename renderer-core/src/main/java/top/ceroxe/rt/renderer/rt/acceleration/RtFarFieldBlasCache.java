package top.ceroxe.rt.renderer.rt.acceleration;

import org.lwjgl.vulkan.VkDevice;
import top.ceroxe.rt.renderer.RtMaterialTelemetrySink;
import top.ceroxe.rt.renderer.rt.device.RtCommandContext;
import top.ceroxe.rt.renderer.rt.material.RtMaterialState;
import top.ceroxe.rt.renderer.rt.material.RtSceneMaterialTable;
import top.ceroxe.rt.renderer.scene.SectionKey;

import java.util.*;

/**
 * Persistent FarField proxy BLAS ownership, independent from Base section BLASes.
 *
 * <p>View admission only changes the desired cell set. Completed proxy BLASes
 * remain cached across camera movement, while source geometry changes enqueue a
 * replacement and keep the old allocation alive until a newer TLAS resource
 * revision is protected. This ownership split separates
 * persistent ray-tracing instances and per-view layer visibility.</p>
 */
public final class RtFarFieldBlasCache implements AutoCloseable {
    private static final int DEFAULT_MAX_BUILDS_PER_FRAME = 4;
    private static final int DEFAULT_MAX_BUILDS_IN_FLIGHT = 8;
    private static final int DEFAULT_MAX_CACHED_CELLS = 512;
    private static final long DEFAULT_MAX_CACHED_BYTES = 512L * 1024L * 1024L;
    private static final String MAX_BUILDS_PER_FRAME_PROPERTY =
            "top.ceroxe.rt.rt.farField.maxBuildsPerFrame";
    private static final String MAX_BUILDS_IN_FLIGHT_PROPERTY =
            "top.ceroxe.rt.rt.farField.maxBuildsInFlight";
    private static final String MAX_CACHED_CELLS_PROPERTY =
            "top.ceroxe.rt.rt.farField.maxCachedCells";
    private static final String MAX_CACHED_BYTES_PROPERTY =
            "top.ceroxe.rt.rt.farField.maxCachedBytes";

    private final VkDevice device;
    private final long allocator;
    private final RtCommandContext commandContext;
    private final int scratchAlignmentBytes;
    private final int maxBuildsPerFrame;
    private final int maxBuildsInFlight;
    private final int maxCachedCells;
    private final long maxCachedBytes;
    private final RtFarFieldProxyMeshBuilder proxyMeshBuilder = new RtFarFieldProxyMeshBuilder();
    private final RtMaterialState<RtSectionInstanceAdmission.FarFieldCellKey> materialState;
    private final LinkedHashMap<RtSectionInstanceAdmission.FarFieldCellKey, CachedCell> cachedCells =
            new LinkedHashMap<>(16, 0.75F, true);
    private final LinkedHashMap<RtSectionInstanceAdmission.FarFieldCellKey, QueuedCell> queuedCells =
            new LinkedHashMap<>();
    private final List<PendingCell> pendingCells = new ArrayList<>();
    private final List<RetiredCellBlas> retiredBlases = new ArrayList<>();
    private Map<RtSectionInstanceAdmission.FarFieldCellKey, DesiredCell> desiredCells = Map.of();
    private long cachedBlasBytes;
    private long retiredBlasBytes;
    private long peakCachedBlasBytes;
    private long peakRetiredBlasBytes;
    private long submittedBuilds;
    private long completedBuilds;
    private long discardedBuilds;
    private long materialOnlyUpdates;
    private long evictedCells;
    private long pollsNotReady;
    private long sourceIncompleteDeferrals;
    private boolean closed;

    RtFarFieldBlasCache(
            VkDevice device,
            long allocator,
            RtCommandContext commandContext,
            int scratchAlignmentBytes
    ) {
        this(device, allocator, commandContext, scratchAlignmentBytes, RtMaterialTelemetrySink.NOOP);
    }

    RtFarFieldBlasCache(
            VkDevice device,
            long allocator,
            RtCommandContext commandContext,
            int scratchAlignmentBytes,
            RtMaterialTelemetrySink materialTelemetry
    ) {
        this(
                device,
                allocator,
                commandContext,
                scratchAlignmentBytes,
                positiveIntProperty(MAX_BUILDS_PER_FRAME_PROPERTY, DEFAULT_MAX_BUILDS_PER_FRAME),
                positiveIntProperty(MAX_BUILDS_IN_FLIGHT_PROPERTY, DEFAULT_MAX_BUILDS_IN_FLIGHT),
                positiveIntProperty(MAX_CACHED_CELLS_PROPERTY, DEFAULT_MAX_CACHED_CELLS),
                positiveLongProperty(MAX_CACHED_BYTES_PROPERTY, DEFAULT_MAX_CACHED_BYTES),
                materialTelemetry
        );
    }

    RtFarFieldBlasCache(
            VkDevice device,
            long allocator,
            RtCommandContext commandContext,
            int scratchAlignmentBytes,
            int maxBuildsPerFrame,
            int maxBuildsInFlight,
            int maxCachedCells,
            long maxCachedBytes
    ) {
        this(device, allocator, commandContext, scratchAlignmentBytes, maxBuildsPerFrame,
                maxBuildsInFlight, maxCachedCells, maxCachedBytes, RtMaterialTelemetrySink.NOOP);
    }

    private RtFarFieldBlasCache(
            VkDevice device,
            long allocator,
            RtCommandContext commandContext,
            int scratchAlignmentBytes,
            int maxBuildsPerFrame,
            int maxBuildsInFlight,
            int maxCachedCells,
            long maxCachedBytes,
            RtMaterialTelemetrySink materialTelemetry
    ) {
        this.device = Objects.requireNonNull(device, "device");
        if (allocator == 0L) {
            throw new IllegalArgumentException("allocator must not be null");
        }
        this.allocator = allocator;
        this.commandContext = Objects.requireNonNull(commandContext, "commandContext");
        this.materialState = new RtMaterialState<>(materialTelemetry);
        if (scratchAlignmentBytes <= 0) {
            throw new IllegalArgumentException("scratchAlignmentBytes must be positive");
        }
        if (maxBuildsPerFrame <= 0 || maxBuildsInFlight <= 0 || maxCachedCells <= 0 || maxCachedBytes <= 0L) {
            throw new IllegalArgumentException("FarField BLAS budgets must be positive");
        }
        this.scratchAlignmentBytes = scratchAlignmentBytes;
        this.maxBuildsPerFrame = maxBuildsPerFrame;
        this.maxBuildsInFlight = maxBuildsInFlight;
        this.maxCachedCells = maxCachedCells;
        this.maxCachedBytes = maxCachedBytes;
    }

    private static boolean activeFor(DesiredCell desired, CachedCell cached) {
        return desired != null
                && cached != null
                && cached.geometrySignature().equals(desired.geometrySignature())
                && cached.materialSignature().equals(desired.materialSignature());
    }

    /**
     * Tests retirement against independently protected resource and scene revision domains.
     *
     * @param safeAfterResourceRevision safe resource revision
     * @param safeAfterSceneRevision    safe scene revision
     * @param protectedResourceRevision oldest protected resource revision
     * @param protectedSceneRevision    oldest protected scene revision
     * @return whether neither protection domain can reference the BLAS
     */
    public static boolean retiredBlasIsReleasable(
            long safeAfterResourceRevision,
            long safeAfterSceneRevision,
            long protectedResourceRevision,
            long protectedSceneRevision
    ) {
        return RtSectionBlasCache.retiredBlasIsReleasable(
                safeAfterResourceRevision,
                protectedResourceRevision
        ) && RtSectionBlasCache.retiredBlasIsReleasable(
                safeAfterSceneRevision,
                protectedSceneRevision
        );
    }

    /**
     * Returns whether a proxy can be built from one coherent source-mesh snapshot.
     *
     * <p>The proxy builder intentionally rejects missing input because direct callers
     * handed it a malformed mesh map. The cache, however, calls it across an
     * asynchronous CPU-to-native handoff, where a missing source is only a deferred
     * publication. Keeping that distinction here prevents error handling from
     * weakening the builder's data-integrity contract.</p>
     *
     * @param cell               proxy cell whose exact source membership is required
     * @param sourcePublications authoritative publication lookup
     * @return whether every source retains a far-field payload
     */
    public static boolean sourcePublicationsCoverCell(
            RtSectionInstanceAdmission.FarFieldCell cell,
            Map<SectionKey, RtSectionSourcePublication> sourcePublications
    ) {
        Objects.requireNonNull(cell, "cell");
        Objects.requireNonNull(sourcePublications, "sourcePublications");
        for (SectionKey sourceSection : cell.sourceSections()) {
            RtSectionSourcePublication publication = sourcePublications.get(sourceSection);
            if (publication == null || !publication.hasFarFieldPayload()) {
                return false;
            }
        }
        return true;
    }

    private static RuntimeException closeCollecting(RuntimeException failure, AutoCloseable closeable) {
        try {
            closeable.close();
            return failure;
        } catch (Exception ex) {
            RuntimeException wrapped = ex instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException("failed to close FarField BLAS resource", ex);
            if (failure == null) {
                return wrapped;
            }
            failure.addSuppressed(wrapped);
            return failure;
        }
    }

    private static int positiveIntProperty(String name, int defaultValue) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
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
            long parsed = Long.parseLong(value.trim());
            return parsed > 0L ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static List<SourceContent> sourceContent(
            RtSectionInstanceAdmission.FarFieldCell cell,
            Map<SectionKey, RtSectionSourcePublication> sourcePublications
    ) {
        List<SourceContent> content = new ArrayList<>(cell.sourceSections().size());
        for (SectionKey key : cell.sourceSections()) {
            RtSectionSourcePublication publication = sourcePublications.get(key);
            if (publication == null) {
                /* Error text is intentionally constructed only on the exceptional path. */
                throw new IllegalStateException("missing FarField source publication for " + key);
            }
            content.add(new SourceContent(key, publication.contentRevision(), publication.causality()));
        }
        return List.copyOf(content);
    }

    boolean reconcile(
            List<RtSectionInstanceAdmission.FarFieldCell> admittedCells,
            Map<SectionKey, RtSectionSourcePublication> sourcePublications,
            RevisionSink revisionSink
    ) {
        ensureOpen();
        Objects.requireNonNull(admittedCells, "admittedCells");
        Objects.requireNonNull(sourcePublications, "sourcePublications");
        Objects.requireNonNull(revisionSink, "revisionSink");

        Map<RtSectionInstanceAdmission.FarFieldCellKey, DesiredCell> previousDesired = desiredCells;
        LinkedHashMap<RtSectionInstanceAdmission.FarFieldCellKey, DesiredCell> nextDesired =
                new LinkedHashMap<>();
        for (RtSectionInstanceAdmission.FarFieldCell cell : admittedCells) {
            DesiredCell desired = DesiredCell.capture(
                    cell,
                    sourcePublications,
                    previousDesired.get(cell.key())
            );
            DesiredCell previous = nextDesired.put(
                    cell.key(),
                    desired
            );
            if (previous != null) {
                throw new IllegalArgumentException("duplicate FarField cell admission " + cell.key());
            }
        }
        /* nextDesired is method-local and is never mutated after publication. */
        desiredCells = Collections.unmodifiableMap(nextDesired);
        boolean activeMembershipChanged = false;
        for (Map.Entry<RtSectionInstanceAdmission.FarFieldCellKey, DesiredCell> previous
                : previousDesired.entrySet()) {
            if (!nextDesired.containsKey(previous.getKey())
                    && activeFor(previous.getValue(), cachedCells.get(previous.getKey()))) {
                activeMembershipChanged = true;
            }
        }
        queuedCells.entrySet().removeIf(entry -> {
            DesiredCell desired = desiredCells.get(entry.getKey());
            return desired == null || !entry.getValue().geometrySignature().equals(desired.geometrySignature());
        });

        for (DesiredCell desired : nextDesired.values()) {
            CachedCell cached = cachedCells.get(desired.cell().key());
            boolean previouslyActive = activeFor(
                    previousDesired.get(desired.cell().key()),
                    cached
            );
            if (cached != null && cached.geometrySignature().equals(desired.geometrySignature())) {
                if (!cached.materialSignature().equals(desired.materialSignature())) {
                    if (!sourcePublicationsCoverCell(desired.cell(), sourcePublications)) {
                        /*
                         * The compact generation identity intentionally outlives its heavy CPU
                         * payload. Keep the old cache generation inactive until the authoritative
                         * payload is republished; relabeling stale material or failing the backend
                         * would violate generation atomicity.
                         */
                        sourceIncompleteDeferrals++;
                        activeMembershipChanged |= previouslyActive;
                        continue;
                    }
                    RtSceneMaterialTable.SectionMaterial material =
                            RtSceneMaterialTable.SectionMaterial.fromFarFieldProxy(
                                    cached.proxyMesh(), sourcePublications
                            );
                    if (!material.equals(cached.material())) {
                        materialState.submit(desired.cell().key(), material);
                        revisionSink.advanceMaterialRevision();
                        materialOnlyUpdates++;
                    }
                    cachedCells.put(
                            desired.cell().key(),
                            cached.withMaterial(desired.materialSignature(), material)
                    );
                }
                queuedCells.remove(desired.cell().key());
                activeMembershipChanged |= previouslyActive != activeFor(
                        desired,
                        cachedCells.get(desired.cell().key())
                );
                continue;
            }
            activeMembershipChanged |= previouslyActive;
            if (!hasPendingBuild(desired.cell().key(), desired.geometrySignature())) {
                queuedCells.put(
                        desired.cell().key(),
                        new QueuedCell(desired.cell(), desired.geometrySignature())
                );
            }
        }
        return activeMembershipChanged;
    }

    void processFrameBudget(
            Map<SectionKey, RtSectionSourcePublication> sourcePublications,
            RevisionSink revisionSink,
            long maxElapsedNanos
    ) {
        ensureOpen();
        Objects.requireNonNull(sourcePublications, "sourcePublications");
        Objects.requireNonNull(revisionSink, "revisionSink");
        if (maxElapsedNanos <= 0L) {
            throw new IllegalArgumentException("maxElapsedNanos must be positive");
        }

        long startedNanos = System.nanoTime();
        pollCompletedBuilds(revisionSink);
        int submittedThisFrame = 0;
        while (submittedThisFrame < maxBuildsPerFrame
                && pendingCells.size() < maxBuildsInFlight
                && !queuedCells.isEmpty()
                && (submittedThisFrame == 0 || System.nanoTime() - startedNanos < maxElapsedNanos)) {
            Iterator<Map.Entry<RtSectionInstanceAdmission.FarFieldCellKey, QueuedCell>> iterator =
                    queuedCells.entrySet().iterator();
            Map.Entry<RtSectionInstanceAdmission.FarFieldCellKey, QueuedCell> next = null;
            DesiredCell desired = null;
            while (iterator.hasNext()) {
                Map.Entry<RtSectionInstanceAdmission.FarFieldCellKey, QueuedCell> candidate = iterator.next();
                DesiredCell candidateDesired = desiredCells.get(candidate.getKey());
                if (candidateDesired == null
                        || !candidateDesired.geometrySignature().equals(candidate.getValue().geometrySignature())) {
                    iterator.remove();
                    continue;
                }
                if (!sourcePublicationsCoverCell(candidateDesired.cell(), sourcePublications)) {
                    /*
                     * CPU mesh publication and FarField admission are deliberately
                     * asynchronous. A cell may therefore be admitted before every
                     * source mesh has crossed the render-thread ownership boundary.
                     * Keep it queued and continue scanning: treating this normal
                     * streaming state as a malformed proxy would disable the entire
                     * native backend during first-front warmup.
                     */
                    sourceIncompleteDeferrals++;
                    continue;
                }
                next = candidate;
                desired = candidateDesired;
                iterator.remove();
                break;
            }
            if (next == null) {
                break;
            }
            if (desired == null) {
                throw new IllegalStateException("ready FarField queue entry is missing desired state");
            }
            if (!sourcePublicationsCoverCell(desired.cell(), sourcePublications)) {
                throw new IllegalStateException("ready FarField queue entry lost a source publication before build");
            }
            if (!desired.geometrySignature().equals(next.getValue().geometrySignature())) {
                throw new IllegalStateException("ready FarField queue entry has mismatched geometry signature");
            }
            RtFarFieldProxyMeshBuilder.ProxyMesh proxyMesh = proxyMeshBuilder.build(
                    desired.cell(),
                    sourcePublications
            );
            RtSceneMaterialTable.SectionMaterial material =
                    RtSceneMaterialTable.SectionMaterial.fromFarFieldProxy(proxyMesh, sourcePublications);
            RtAccelerationStructure.FarFieldBlasBuildSubmission submission =
                    RtAccelerationStructure.submitFarFieldBlasAsync(
                            device,
                            allocator,
                            commandContext,
                            scratchAlignmentBytes,
                            proxyMesh
                    );
            pendingCells.add(new PendingCell(
                    desired.cell().key(),
                    desired.geometrySignature(),
                    desired.materialSignature(),
                    material,
                    submission
            ));
            submittedBuilds++;
            submittedThisFrame++;
        }
        trimInactiveCache(revisionSink);
    }

    List<ActiveCell> activeCells() {
        ensureOpen();
        List<ActiveCell> result = new ArrayList<>();
        for (DesiredCell desired : desiredCells.values()) {
            CachedCell cached = cachedCells.get(desired.cell().key());
            if (cached == null
                    || !cached.geometrySignature().equals(desired.geometrySignature())
                    || !cached.materialSignature().equals(desired.materialSignature())) {
                continue;
            }
            Integer materialSlot = materialState.slotFor(desired.cell().key());
            if (materialSlot == null) {
                throw new IllegalStateException("missing FarField material slot for " + desired.cell().key());
            }
            result.add(new ActiveCell(
                    desired.cell(),
                    cached.proxyMesh(),
                    cached.blas(),
                    cached.material(),
                    materialSlot,
                    desired.sourceContent()
            ));
        }
        return List.copyOf(result);
    }

    RtSceneMaterialTable.Snapshot materialSnapshot(long materialRevision) {
        ensureOpen();
        return materialState.snapshot(materialRevision, 0);
    }

    int pendingDesiredBuilds() {
        ensureOpen();
        int pending = 0;
        for (DesiredCell desired : desiredCells.values()) {
            CachedCell cached = cachedCells.get(desired.cell().key());
            if (cached == null || !cached.geometrySignature().equals(desired.geometrySignature())) {
                pending++;
            }
        }
        return pending;
    }

    long activeTriangles() {
        long triangles = 0L;
        for (ActiveCell cell : activeCells()) {
            triangles = Math.addExact(triangles, cell.proxyMesh().triangleCount());
        }
        return triangles;
    }

    int materialSlotCount() {
        ensureOpen();
        return materialState.slotCount();
    }

    void releaseRetiredBlasesThrough(long protectedResourceRevision, long protectedSceneRevision) {
        ensureOpen();
        RuntimeException failure = null;
        Iterator<RetiredCellBlas> iterator = retiredBlases.iterator();
        while (iterator.hasNext()) {
            RetiredCellBlas retired = iterator.next();
            if (!retiredBlasIsReleasable(
                    retired.safeAfterResourceRevision(),
                    retired.safeAfterSceneRevision(),
                    protectedResourceRevision,
                    protectedSceneRevision
            )) {
                continue;
            }
            failure = closeRetiredCollecting(failure, retired);
            iterator.remove();
        }
        if (failure != null) {
            throw failure;
        }
    }

    void clear(RevisionSink revisionSink) {
        ensureOpen();
        Objects.requireNonNull(revisionSink, "revisionSink");
        desiredCells = Map.of();
        queuedCells.clear();
        if (!cachedCells.isEmpty()) {
            long safeAfterRevision = revisionSink.advanceResourceRevision();
            for (CachedCell cell : cachedCells.values()) {
                retire(safeAfterRevision, 0L, cell.blas());
            }
            cachedCells.clear();
            cachedBlasBytes = 0L;
        }
        if (materialState.slotCount() > 0) {
            materialState.clear();
            revisionSink.advanceMaterialRevision();
        }
    }

    String summary() {
        return "farFieldBlasCache{desired=" + desiredCells.size()
                + ", active=" + activeCells().size()
                + ", cached=" + cachedCells.size()
                + ", queued=" + queuedCells.size()
                + ", inFlight=" + pendingCells.size()
                + ", pendingDesired=" + pendingDesiredBuilds()
                + ", materialSlots=" + materialState.slotCount()
                + ", cachedBlasBytes=" + cachedBlasBytes
                + ", retiredBlasBytes=" + retiredBlasBytes
                + ", peakCachedBlasBytes=" + peakCachedBlasBytes
                + ", peakRetiredBlasBytes=" + peakRetiredBlasBytes
                + ", submittedBuilds=" + submittedBuilds
                + ", completedBuilds=" + completedBuilds
                + ", discardedBuilds=" + discardedBuilds
                + ", materialOnlyUpdates=" + materialOnlyUpdates
                + ", evictedCells=" + evictedCells
                + ", pollsNotReady=" + pollsNotReady
                + ", sourceIncompleteDeferrals=" + sourceIncompleteDeferrals
                + '}';
    }

    private void pollCompletedBuilds(RevisionSink revisionSink) {
        Iterator<PendingCell> iterator = pendingCells.iterator();
        while (iterator.hasNext()) {
            PendingCell pending = iterator.next();
            RtAccelerationStructure.CompletedFarFieldBlasBuild completed = pending.submission().completeIfReady();
            if (completed == null) {
                pollsNotReady++;
                break;
            }
            iterator.remove();
            DesiredCell desired = desiredCells.get(pending.cellKey());
            if (desired == null || !desired.geometrySignature().equals(pending.geometrySignature())) {
                completed.accelerationStructure().close();
                discardedBuilds++;
                continue;
            }

            CachedCell existing = cachedCells.get(pending.cellKey());
            if (existing != null && existing.geometrySignature().equals(pending.geometrySignature())) {
                completed.accelerationStructure().close();
                discardedBuilds++;
                continue;
            }
            long safeAfterRevision = revisionSink.advanceResourceRevision();
            if (existing != null) {
                cachedBlasBytes -= existing.blas().storageBytes();
                retire(safeAfterRevision, 0L, existing.blas());
            }
            cachedCells.put(
                    pending.cellKey(),
                    new CachedCell(
                            pending.geometrySignature(),
                            pending.materialSignature(),
                            completed.mesh(),
                            completed.accelerationStructure(),
                            pending.material()
                    )
            );
            cachedBlasBytes += completed.accelerationStructure().storageBytes();
            peakCachedBlasBytes = Math.max(peakCachedBlasBytes, cachedBlasBytes);
            materialState.submit(pending.cellKey(), pending.material());
            revisionSink.advanceMaterialRevision();
            completedBuilds++;
        }
    }

    private boolean hasPendingBuild(
            RtSectionInstanceAdmission.FarFieldCellKey key,
            CellSignature geometrySignature
    ) {
        for (PendingCell pending : pendingCells) {
            if (pending.cellKey().equals(key) && pending.geometrySignature().equals(geometrySignature)) {
                return true;
            }
        }
        return false;
    }

    private void trimInactiveCache(RevisionSink revisionSink) {
        while (cachedCells.size() > maxCachedCells || cachedBlasBytes > maxCachedBytes) {
            Iterator<Map.Entry<RtSectionInstanceAdmission.FarFieldCellKey, CachedCell>> iterator =
                    cachedCells.entrySet().iterator();
            Map.Entry<RtSectionInstanceAdmission.FarFieldCellKey, CachedCell> eviction = null;
            while (iterator.hasNext()) {
                Map.Entry<RtSectionInstanceAdmission.FarFieldCellKey, CachedCell> candidate = iterator.next();
                DesiredCell desired = desiredCells.get(candidate.getKey());
                if (desired == null
                        || !candidate.getValue().geometrySignature().equals(desired.geometrySignature())) {
                    eviction = candidate;
                    iterator.remove();
                    break;
                }
            }
            if (eviction == null) {
                return;
            }
            CachedCell removed = eviction.getValue();
            cachedBlasBytes -= removed.blas().storageBytes();
            retire(
                    revisionSink.currentResourceRevision(),
                    revisionSink.currentSceneRevision(),
                    removed.blas()
            );
            if (materialState.remove(eviction.getKey())) {
                revisionSink.advanceMaterialRevision();
            }
            evictedCells++;
        }
    }

    private void retire(
            long safeAfterResourceRevision,
            long safeAfterSceneRevision,
            RtAccelerationStructure blas
    ) {
        retiredBlases.add(new RetiredCellBlas(safeAfterResourceRevision, safeAfterSceneRevision, blas));
        retiredBlasBytes += blas.storageBytes();
        peakRetiredBlasBytes = Math.max(peakRetiredBlasBytes, retiredBlasBytes);
    }

    private RuntimeException closeRetiredCollecting(RuntimeException failure, RetiredCellBlas retired) {
        try {
            retired.accelerationStructure().close();
            return failure;
        } catch (RuntimeException ex) {
            if (failure == null) {
                return ex;
            }
            failure.addSuppressed(ex);
            return failure;
        } finally {
            retiredBlasBytes = Math.max(0L, retiredBlasBytes - retired.accelerationStructure().storageBytes());
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("RT FarField BLAS cache is already closed");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        for (PendingCell pending : pendingCells) {
            failure = closeCollecting(failure, pending.submission());
        }
        for (CachedCell cached : cachedCells.values()) {
            failure = closeCollecting(failure, cached.blas());
        }
        for (RetiredCellBlas retired : retiredBlases) {
            failure = closeRetiredCollecting(failure, retired);
        }
        pendingCells.clear();
        queuedCells.clear();
        cachedCells.clear();
        retiredBlases.clear();
        desiredCells = Map.of();
        materialState.clear();
        cachedBlasBytes = 0L;
        retiredBlasBytes = 0L;
        if (failure != null) {
            throw failure;
        }
    }

    interface RevisionSink {
        long advanceResourceRevision();

        void advanceMaterialRevision();

        long currentResourceRevision();

        long currentSceneRevision();
    }

    record ActiveCell(
            RtSectionInstanceAdmission.FarFieldCell cell,
            RtFarFieldProxyMeshBuilder.ProxyMesh proxyMesh,
            RtAccelerationStructure blas,
            RtSceneMaterialTable.SectionMaterial material,
            int materialSlot,
            List<SourceContent> sourceContent
    ) {
        ActiveCell {
            cell = Objects.requireNonNull(cell, "cell");
            proxyMesh = Objects.requireNonNull(proxyMesh, "proxyMesh");
            blas = Objects.requireNonNull(blas, "blas");
            material = Objects.requireNonNull(material, "material");
            sourceContent = List.copyOf(sourceContent);
            if (materialSlot < 0) {
                throw new IllegalArgumentException("FarField material slot must not be negative");
            }
        }
    }

    private record DesiredCell(
            RtSectionInstanceAdmission.FarFieldCell cell,
            CellSignature geometrySignature,
            CellSignature materialSignature,
            List<SourceContent> sourceContent
    ) {
        private DesiredCell {
            cell = Objects.requireNonNull(cell, "cell");
            geometrySignature = Objects.requireNonNull(geometrySignature, "geometrySignature");
            materialSignature = Objects.requireNonNull(materialSignature, "materialSignature");
            sourceContent = List.copyOf(sourceContent);
        }

        /**
         * Retains the complete immutable FarField cell publication when every source generation
         * and content fact is unchanged. Reconcile runs from the TLAS frame budget, while source
         * generations advance only on terrain content changes; rebuilding three object lists on
         * every stable frame was therefore pure publication churn and a directly sampled GC owner.
         */
        private static DesiredCell capture(
                RtSectionInstanceAdmission.FarFieldCell cell,
                Map<SectionKey, RtSectionSourcePublication> sourcePublications,
                DesiredCell previous
        ) {
            if (previous != null && previous.sourceStateMatches(cell, sourcePublications)) {
                /* Distance is camera-relative and may change without a source generation change. */
                return previous.cell.equals(cell)
                        ? previous
                        : new DesiredCell(
                        cell,
                        previous.geometrySignature,
                        previous.materialSignature,
                        previous.sourceContent
                );
            }
            return new DesiredCell(
                    cell,
                    CellSignature.from(cell, sourcePublications, true),
                    CellSignature.from(cell, sourcePublications, false),
                    RtFarFieldBlasCache.sourceContent(cell, sourcePublications)
            );
        }

        private boolean sourceStateMatches(
                RtSectionInstanceAdmission.FarFieldCell candidate,
                Map<SectionKey, RtSectionSourcePublication> sourcePublications
        ) {
            if (!cell.key().equals(candidate.key())
                    || !cell.sourceSections().equals(candidate.sourceSections())) {
                return false;
            }
            List<SectionKey> sourceSections = candidate.sourceSections();
            if (geometrySignature.sources().size() != sourceSections.size()
                    || materialSignature.sources().size() != sourceSections.size()
                    || sourceContent.size() != sourceSections.size()) {
                return false;
            }
            for (int index = 0; index < sourceSections.size(); index++) {
                SectionKey key = sourceSections.get(index);
                RtSectionSourcePublication publication = sourcePublications.get(key);
                if (publication == null) {
                    throw new IllegalStateException("missing FarField source publication for " + key);
                }
                SourceGeneration geometry = geometrySignature.sources().get(index);
                SourceGeneration material = materialSignature.sources().get(index);
                SourceContent content = sourceContent.get(index);
                if (!geometry.sectionKey().equals(key)
                        || geometry.generation() != publication.geometryGeneration()
                        || !material.sectionKey().equals(key)
                        || material.generation() != publication.materialGeneration()
                        || !content.key().equals(key)
                        || content.revision() != publication.contentRevision()
                        || !content.causality().equals(publication.causality())) {
                    return false;
                }
            }
            return true;
        }
    }

    record SourceContent(
            SectionKey key,
            long revision,
            top.ceroxe.rt.renderer.RendererFrameCausality causality
    ) {
        SourceContent {
            key = Objects.requireNonNull(key, "key");
            if (revision < 0L) {
                throw new IllegalArgumentException("FarField source content revision must not be negative");
            }
            causality = Objects.requireNonNull(causality, "causality");
        }
    }

    private record QueuedCell(
            RtSectionInstanceAdmission.FarFieldCell cell,
            CellSignature geometrySignature
    ) {
        private QueuedCell {
            cell = Objects.requireNonNull(cell, "cell");
            geometrySignature = Objects.requireNonNull(geometrySignature, "geometrySignature");
        }
    }

    private record CachedCell(
            CellSignature geometrySignature,
            CellSignature materialSignature,
            RtFarFieldProxyMeshBuilder.ProxyMesh proxyMesh,
            RtAccelerationStructure blas,
            RtSceneMaterialTable.SectionMaterial material
    ) {
        private CachedCell {
            geometrySignature = Objects.requireNonNull(geometrySignature, "geometrySignature");
            materialSignature = Objects.requireNonNull(materialSignature, "materialSignature");
            proxyMesh = Objects.requireNonNull(proxyMesh, "proxyMesh");
            blas = Objects.requireNonNull(blas, "blas");
            material = Objects.requireNonNull(material, "material");
        }

        private CachedCell withMaterial(
                CellSignature nextMaterialSignature,
                RtSceneMaterialTable.SectionMaterial nextMaterial
        ) {
            return new CachedCell(geometrySignature, nextMaterialSignature, proxyMesh, blas, nextMaterial);
        }
    }

    private record PendingCell(
            RtSectionInstanceAdmission.FarFieldCellKey cellKey,
            CellSignature geometrySignature,
            CellSignature materialSignature,
            RtSceneMaterialTable.SectionMaterial material,
            RtAccelerationStructure.FarFieldBlasBuildSubmission submission
    ) {
        private PendingCell {
            cellKey = Objects.requireNonNull(cellKey, "cellKey");
            geometrySignature = Objects.requireNonNull(geometrySignature, "geometrySignature");
            materialSignature = Objects.requireNonNull(materialSignature, "materialSignature");
            material = Objects.requireNonNull(material, "material");
            submission = Objects.requireNonNull(submission, "submission");
        }
    }

    private record RetiredCellBlas(
            long safeAfterResourceRevision,
            long safeAfterSceneRevision,
            RtAccelerationStructure accelerationStructure
    ) {
        private RetiredCellBlas {
            if (safeAfterResourceRevision < 0L || safeAfterSceneRevision < 0L) {
                throw new IllegalArgumentException("FarField retirement revisions must not be negative");
            }
            accelerationStructure = Objects.requireNonNull(accelerationStructure, "accelerationStructure");
        }
    }

    private record CellSignature(
            RtSectionInstanceAdmission.FarFieldCellKey cellKey,
            List<SourceGeneration> sources
    ) {
        private CellSignature {
            cellKey = Objects.requireNonNull(cellKey, "cellKey");
            sources = List.copyOf(sources);
            if (sources.isEmpty()) {
                throw new IllegalArgumentException("FarField signature must contain source sections");
            }
        }

        private static CellSignature from(
                RtSectionInstanceAdmission.FarFieldCell cell,
                Map<SectionKey, RtSectionSourcePublication> sourcePublications,
                boolean geometry
        ) {
            List<SourceGeneration> sources = new ArrayList<>(cell.sourceSections().size());
            for (SectionKey sourceSection : cell.sourceSections()) {
                RtSectionSourcePublication publication = sourcePublications.get(sourceSection);
                if (publication == null) {
                    throw new IllegalStateException("missing FarField source publication for " + sourceSection);
                }
                long generation = geometry
                        ? publication.geometryGeneration()
                        : publication.materialGeneration();
                sources.add(new SourceGeneration(sourceSection, generation));
            }
            return new CellSignature(cell.key(), sources);
        }
    }

    private record SourceGeneration(SectionKey sectionKey, long generation) {
        private SourceGeneration {
            sectionKey = Objects.requireNonNull(sectionKey, "sectionKey");
            if (generation < 0L) {
                throw new IllegalArgumentException("source generation must not be negative");
            }
        }
    }
}
