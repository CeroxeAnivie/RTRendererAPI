package top.ceroxe.rt.renderer.rt.acceleration;

import top.ceroxe.rt.renderer.RtBuildTelemetrySink;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Owns active-view cache counters, one-second baselines, and their text publication.
 *
 * <p>The active-view controller emits typed events only.  Sampling external cache totals happens
 * once per telemetry window, so disabled diagnostics and stable high-FPS frames allocate nothing.</p>
 */
final class RtSectionActiveViewTelemetry {
    private static final long WINDOW_NANOS = 1_000_000_000L;

    private final RtBuildTelemetrySink sink;
    private final LongSupplier nanoTime;
    private long cacheHits;
    private long rebuilds;
    private long geometryInvalidations;
    private long materialInvalidations;
    private long rendererViewInvalidations;
    private long admissionMicros;
    private long farFieldMicros;
    private long collectSortMicros;
    private long coverageMicros;
    private long identityChanges;
    private long identityAdded;
    private long identityRemoved;
    private long warmupPlanInvocations;
    private long tlasBuildStatsCalls;

    private long windowStartNanos;
    private long pendingSampleNanos;
    private boolean windowStarted;
    private boolean samplePending;
    private Totals previousTotals = Totals.empty();
    private Sample previousSample = Sample.empty();

    RtSectionActiveViewTelemetry(RtBuildTelemetrySink sink) {
        this(sink, System::nanoTime);
    }

    RtSectionActiveViewTelemetry(RtBuildTelemetrySink sink, LongSupplier nanoTime) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    private static long micros(long nanos) {
        if (nanos < 0L) {
            throw new IllegalArgumentException("active-view telemetry duration must not be negative");
        }
        return nanos / 1_000L;
    }

    void cacheHit() {
        cacheHits++;
    }

    void materialOnlyRefresh() {
        materialInvalidations++;
    }

    void rebuild(boolean geometryChanged, boolean materialChanged, boolean admissionInputsChanged) {
        rebuilds++;
        if (geometryChanged) {
            geometryInvalidations++;
        }
        if (materialChanged) {
            materialInvalidations++;
        }
        if (admissionInputsChanged) {
            rendererViewInvalidations++;
        }
    }

    void addAdmissionNanos(long elapsedNanos) {
        admissionMicros = Math.addExact(admissionMicros, micros(elapsedNanos));
    }

    void addFarFieldNanos(long elapsedNanos) {
        farFieldMicros = Math.addExact(farFieldMicros, micros(elapsedNanos));
    }

    void assembly(
            long collectSortNanos,
            long coverageNanos,
            boolean identityChanged,
            int identityAdded,
            int identityRemoved
    ) {
        collectSortMicros = Math.addExact(collectSortMicros, micros(collectSortNanos));
        coverageMicros = Math.addExact(coverageMicros, micros(coverageNanos));
        if (!identityChanged) {
            return;
        }
        if (identityAdded < 0 || identityRemoved < 0) {
            throw new IllegalArgumentException("active-view identity deltas must not be negative");
        }
        identityChanges++;
        this.identityAdded = Math.addExact(this.identityAdded, identityAdded);
        this.identityRemoved = Math.addExact(this.identityRemoved, identityRemoved);
    }

    void warmupPlanInvoked() {
        warmupPlanInvocations++;
    }

    void tlasBuildStatsRequested() {
        tlasBuildStatsCalls++;
    }

    /**
     * Returns true only when the caller should construct the once-per-window external sample.
     */
    boolean sampleDue() {
        if (!sink.enabled()) {
            return false;
        }
        long now = nanoTime.getAsLong();
        if (windowStarted && now - windowStartNanos < WINDOW_NANOS) {
            return false;
        }
        pendingSampleNanos = now;
        samplePending = true;
        return true;
    }

    void publish(Sample sample) {
        Objects.requireNonNull(sample, "sample");
        if (!samplePending) {
            throw new IllegalStateException("active-view telemetry publish requires a due sample");
        }
        long now = pendingSampleNanos;
        samplePending = false;
        Totals totals = totals();
        if (!windowStarted) {
            windowStartNanos = now;
            windowStarted = true;
            previousTotals = totals;
            previousSample = sample;
            return;
        }
        RtSectionTlasBuildInputCache.Stats tlas = sample.tlasInputStats();
        RtSectionTlasBuildInputCache.Stats previousTlas = previousSample.tlasInputStats();
        sink.aggregate(
                "activeViewSnapshot",
                "windowMs=" + (now - windowStartNanos) / 1_000_000L
                        + ", hits=" + (totals.cacheHits() - previousTotals.cacheHits())
                        + ", rebuilds=" + (totals.rebuilds() - previousTotals.rebuilds())
                        + ", invalidations={geometry="
                        + (totals.geometryInvalidations() - previousTotals.geometryInvalidations())
                        + ", material="
                        + (totals.materialInvalidations() - previousTotals.materialInvalidations())
                        + ", view="
                        + (totals.rendererViewInvalidations() - previousTotals.rendererViewInvalidations()) + "}"
                        + ", micros={admission="
                        + (totals.admissionMicros() - previousTotals.admissionMicros())
                        + ", farField=" + (totals.farFieldMicros() - previousTotals.farFieldMicros())
                        + ", collectSort=" + (totals.collectSortMicros() - previousTotals.collectSortMicros())
                        + ", coverage=" + (totals.coverageMicros() - previousTotals.coverageMicros()) + "}"
                        + ", admissionPlanCache={hits="
                        + (sample.admissionPlanCacheHits() - previousSample.admissionPlanCacheHits())
                        + ", misses="
                        + (sample.admissionPlanCacheMisses() - previousSample.admissionPlanCacheMisses())
                        + ", invocations="
                        + (sample.admissionPlanInvocations() - previousSample.admissionPlanInvocations())
                        + ", builds="
                        + (sample.admissionPlanBuilds() - previousSample.admissionPlanBuilds()) + "}"
                        + ", admissionMembershipSnapshot={hits="
                        + (sample.admissionMembershipHits() - previousSample.admissionMembershipHits())
                        + ", misses="
                        + (sample.admissionMembershipMisses() - previousSample.admissionMembershipMisses()) + "}"
                        + ", admissionConsumers={warmupPlanInvocations="
                        + (totals.warmupPlanInvocations() - previousTotals.warmupPlanInvocations())
                        + ", warmupPlanBuilds="
                        + (sample.warmupPlanBuilds() - previousSample.warmupPlanBuilds())
                        + ", tlasBuildStats="
                        + (totals.tlasBuildStatsCalls() - previousTotals.tlasBuildStatsCalls())
                        + ", tlasBuildInput=" + (tlas.calls() - previousTlas.calls()) + "}"
                        + ", persistentTlasInput={hits=" + (tlas.hits() - previousTlas.hits())
                        + ",misses=" + (tlas.misses() - previousTlas.misses())
                        + ",missReasons={cold=" + (tlas.coldMisses() - previousTlas.coldMisses())
                        + ",activeView=" + (tlas.activeViewMisses() - previousTlas.activeViewMisses())
                        + ",scene=" + (tlas.sceneMisses() - previousTlas.sceneMisses())
                        + ",geometry=" + (tlas.geometryMisses() - previousTlas.geometryMisses())
                        + ",material=" + (tlas.materialMisses() - previousTlas.materialMisses())
                        + ",texture=" + (tlas.textureMisses() - previousTlas.textureMisses())
                        + ",pendingBuilds=" + (tlas.pendingBuildMisses() - previousTlas.pendingBuildMisses())
                        + ",pendingTriangles="
                        + (tlas.pendingTriangleMisses() - previousTlas.pendingTriangleMisses())
                        + ",cachedTriangles="
                        + (tlas.cachedTriangleMisses() - previousTlas.cachedTriangleMisses())
                        + ",activeContent="
                        + (tlas.activeContentMisses() - previousTlas.activeContentMisses()) + "}}"
                        + ", identityDelta={changes="
                        + (totals.identityChanges() - previousTotals.identityChanges())
                        + ", added=" + (totals.identityAdded() - previousTotals.identityAdded())
                        + ", removed=" + (totals.identityRemoved() - previousTotals.identityRemoved()) + "}"
                        + ", admissionState={sourceSections=" + sample.sourceSectionCount()
                        + ", baseCapacity=" + sample.baseCapacity()
                        + ", requiresView=" + sample.admissionRequiresView()
                        + ", cachedRequiresView=" + sample.cachedAdmissionRequiresView()
                        + ", geometryChanged=" + sample.geometryChanged()
                        + ", materialChanged=" + sample.materialChanged()
                        + ", inputsChanged=" + sample.admissionInputsChanged() + "}"
        );
        windowStartNanos = now;
        previousTotals = totals;
        previousSample = sample;
    }

    private Totals totals() {
        return new Totals(
                cacheHits,
                rebuilds,
                geometryInvalidations,
                materialInvalidations,
                rendererViewInvalidations,
                admissionMicros,
                farFieldMicros,
                collectSortMicros,
                coverageMicros,
                identityChanges,
                identityAdded,
                identityRemoved,
                warmupPlanInvocations,
                tlasBuildStatsCalls
        );
    }

    record Sample(
            long admissionPlanCacheHits,
            long admissionPlanCacheMisses,
            long admissionMembershipHits,
            long admissionMembershipMisses,
            long admissionPlanInvocations,
            long admissionPlanBuilds,
            long warmupPlanBuilds,
            RtSectionTlasBuildInputCache.Stats tlasInputStats,
            int sourceSectionCount,
            int baseCapacity,
            boolean admissionRequiresView,
            boolean cachedAdmissionRequiresView,
            boolean geometryChanged,
            boolean materialChanged,
            boolean admissionInputsChanged
    ) {
        Sample {
            Objects.requireNonNull(tlasInputStats, "tlasInputStats");
            if (admissionPlanCacheHits < 0L || admissionPlanCacheMisses < 0L
                    || admissionMembershipHits < 0L || admissionMembershipMisses < 0L
                    || admissionPlanInvocations < 0L || admissionPlanBuilds < 0L
                    || warmupPlanBuilds < 0L || sourceSectionCount < 0 || baseCapacity < 0) {
                throw new IllegalArgumentException("active-view telemetry sample values must not be negative");
            }
        }

        private static Sample empty() {
            return new Sample(
                    0L, 0L, 0L, 0L, 0L, 0L, 0L,
                    RtSectionTlasBuildInputCache.Stats.empty(),
                    0, 0, false, false, false, false, false
            );
        }
    }

    private record Totals(
            long cacheHits,
            long rebuilds,
            long geometryInvalidations,
            long materialInvalidations,
            long rendererViewInvalidations,
            long admissionMicros,
            long farFieldMicros,
            long collectSortMicros,
            long coverageMicros,
            long identityChanges,
            long identityAdded,
            long identityRemoved,
            long warmupPlanInvocations,
            long tlasBuildStatsCalls
    ) {
        private static Totals empty() {
            return new Totals(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        }
    }
}
