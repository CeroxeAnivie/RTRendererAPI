package top.ceroxe.rt.renderer.rt.acceleration;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import top.ceroxe.rt.renderer.RendererViewState;
import top.ceroxe.rt.renderer.scene.PackedSectionMembership;
import top.ceroxe.rt.renderer.scene.SectionKey;

import java.util.*;

/**
 * Stateful Base/FarField admission for persistent section BLAS resources.
 *
 * <p>The resident scene is deliberately not the active ray tracing scene. Production persistent
 * coverage assigns Base and FarField topology from stable resource membership; camera state only
 * prioritizes missing work outside this owner. Compatibility admission can still rank a
 * view-owned scene by distance. This separation prevents camera motion from rewriting TLAS slots
 * that remain backed by the same immutable BLAS generation.</p>
 */
public final class RtSectionInstanceAdmission {
    private static final int NEAR_FAR_FIELD_SPAN = 2;
    private static final int MID_FAR_FIELD_SPAN = 4;
    private static final int DISTANT_FAR_FIELD_SPAN = 8;
    private static final int MID_FAR_FIELD_START_SECTIONS = 16;
    private static final int DISTANT_FAR_FIELD_START_SECTIONS = 32;

    private final int initialBaseCapacity;
    private final int initialFarFieldCellCapacity;
    private final int retentionMargin;
    private final boolean persistentResidentCoverage;
    private final boolean growFarFieldCapacityToFit;
    private final boolean growBaseCapacityToAuthoritativeView;
    private final boolean farFieldProxyEnabled;
    private final LongOpenHashSet baseSelectionScratch = new LongOpenHashSet();
    private int baseCapacityHighWater;
    private int farFieldCellHighWater;
    private Set<SectionKey> retainedBaseSections = Set.of();
    private Set<FarFieldCellKey> retainedFarFieldCells = Set.of();
    /*
     * The renderer publishes source and completed-BLAS membership revisions.
     * Do not use Collection identity as a cache key here: HashMap.keySet()
     * keeps its identity while its membership changes.  This is the RT
     * equivalent of cached instance handles: stable slots are retained
     * until an explicit ownership mutation invalidates them.
     */
    private Admission cachedAdmission;
    private long cachedResidentMembershipRevision = -1L;
    private long cachedBaseAvailabilityRevision = -1L;
    /*
     * Membership ownership is revisioned by RtSectionBlasCache.  Snapshot it
     * once at that boundary instead of defensively rebuilding Set.copyOf on
     * every camera publication.  The source collections are mutable map views,
     * so retaining them directly would let a later enqueue mutate an in-flight
     * admission.  These immutable snapshots are therefore both the allocation
     * fix and the renderer/submission lifetime boundary.
     */
    private PackedSectionMembership cachedResidentSections = PackedSectionMembership.empty();
    private PackedSectionMembership cachedBaseAvailableSections = PackedSectionMembership.empty();
    private MembershipSnapshot cachedMembershipSnapshot =
            new MembershipSnapshot(cachedResidentSections, cachedBaseAvailableSections);
    private long cachedResidentSnapshotRevision = -1L;
    private long cachedBaseAvailabilitySnapshotRevision = -1L;
    private long cachedMembershipSnapshotHits;
    private long cachedMembershipSnapshotMisses;
    private boolean cachedSubsetRelationValidated;
    private long fullMembershipValidations;
    private long deltaMembershipValidations;
    /*
     * A sampled JFR stack proves only that a caller entered the planner. These
     * counters make the ownership result explicit: invocation, immutable-plan
     * cache hit, and real Set/sort build are separate facts.
     */
    private long planInvocations;
    private long planBuilds;
    private int cachedBaseCapacity = -1;
    private boolean cachedViewSensitive;
    private RendererViewState cachedViewState = RendererViewState.allResident();
    private long cachedPlanHits;
    private long cachedPlanMisses;

    /**
     * Creates deterministic admission with persistent coverage defaults.
     *
     * @param baseCapacity         exact-section capacity
     * @param farFieldCellCapacity proxy-cell capacity
     * @param retentionMargin      hysteresis margin
     */
    public RtSectionInstanceAdmission(int baseCapacity, int farFieldCellCapacity, int retentionMargin) {
        this(baseCapacity, farFieldCellCapacity, retentionMargin, false, false, false);
    }

    /**
     * Creates configurable base/far-field admission capacity.
     *
     * @param baseCapacity               exact-section capacity
     * @param farFieldCellCapacity       proxy-cell capacity
     * @param retentionMargin            hysteresis margin
     * @param persistentResidentCoverage persistent coverage policy
     * @param growFarFieldCapacityToFit  whether proxy capacity may grow
     */
    public RtSectionInstanceAdmission(
            int baseCapacity,
            int farFieldCellCapacity,
            int retentionMargin,
            boolean persistentResidentCoverage,
            boolean growFarFieldCapacityToFit
    ) {
        this(
                baseCapacity,
                farFieldCellCapacity,
                retentionMargin,
                persistentResidentCoverage,
                growFarFieldCapacityToFit,
                false,
                true
        );
    }

    /**
     * Creates the fully configurable, monitor-confined admission planner.
     *
     * @param baseCapacity                        exact-section capacity
     * @param farFieldCellCapacity                proxy-cell capacity
     * @param retentionMargin                     hysteresis margin
     * @param persistentResidentCoverage          persistent coverage policy
     * @param growFarFieldCapacityToFit           whether proxy capacity may grow
     * @param growBaseCapacityToAuthoritativeView whether base capacity may grow
     */
    public RtSectionInstanceAdmission(
            int baseCapacity,
            int farFieldCellCapacity,
            int retentionMargin,
            boolean persistentResidentCoverage,
            boolean growFarFieldCapacityToFit,
            boolean growBaseCapacityToAuthoritativeView
    ) {
        this(
                baseCapacity,
                farFieldCellCapacity,
                retentionMargin,
                persistentResidentCoverage,
                growFarFieldCapacityToFit,
                growBaseCapacityToAuthoritativeView,
                true
        );
    }

    RtSectionInstanceAdmission(
            int baseCapacity,
            int farFieldCellCapacity,
            int retentionMargin,
            boolean persistentResidentCoverage,
            boolean growFarFieldCapacityToFit,
            boolean growBaseCapacityToAuthoritativeView,
            boolean farFieldProxyEnabled
    ) {
        if (baseCapacity <= 0) {
            throw new IllegalArgumentException("baseCapacity must be positive");
        }
        if (farFieldCellCapacity <= 0) {
            throw new IllegalArgumentException("farFieldCellCapacity must be positive");
        }
        if (retentionMargin < 0) {
            throw new IllegalArgumentException("retentionMargin must not be negative");
        }
        this.initialBaseCapacity = baseCapacity;
        this.initialFarFieldCellCapacity = farFieldCellCapacity;
        this.retentionMargin = retentionMargin;
        this.persistentResidentCoverage = persistentResidentCoverage;
        this.growFarFieldCapacityToFit = growFarFieldCapacityToFit;
        this.growBaseCapacityToAuthoritativeView = growBaseCapacityToAuthoritativeView;
        this.farFieldProxyEnabled = farFieldProxyEnabled;
        this.baseCapacityHighWater = baseCapacity;
        this.farFieldCellHighWater = farFieldCellCapacity;
    }

    private static int nextPowerOfTwo(int value) {
        if (value <= 1) {
            return 1;
        }
        if (value > 1 << 30) {
            return Integer.MAX_VALUE;
        }
        return 1 << (32 - Integer.numberOfLeadingZeros(value - 1));
    }

    private static int saturatedAdd(int left, int right) {
        long result = (long) left + right;
        return result >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }

    private static List<SectionKey> visibleResidentSections(
            RendererViewState viewState,
            Set<SectionKey> residentSections
    ) {
        List<SectionKey> result = new ArrayList<>();
        for (SectionKey key : viewState.visibleSectionKeys()) {
            if (residentSections.contains(key)) {
                result.add(key);
            }
        }
        return List.copyOf(result);
    }

    private static List<SectionKey> rankVisibleSections(
            RendererViewState viewState,
            List<SectionKey> visibleSections
    ) {
        List<SectionKey> ranked = new ArrayList<>(visibleSections);
        if (viewState.cameraValid()) {
            ranked.sort(Comparator
                    .comparingLong((SectionKey key) -> sectionDistanceSquared(key, viewState))
                    .thenComparing(stableSectionOrder()));
        }
        return List.copyOf(ranked);
    }

    private static List<FarFieldCell> buildFarFieldCells(
            RendererViewState viewState,
            List<SectionKey> rankedSections,
            Set<SectionKey> baseSections,
            boolean stablePersistentCoverage
    ) {
        Map<FarFieldCellKey, FarFieldCellBuilder> builders = new LinkedHashMap<>();
        for (SectionKey key : rankedSections) {
            if (baseSections.contains(key)) {
                continue;
            }
            /*
             * Persistent coverage must not regroup proxy cells when the camera rotates. A stable
             * 8x8 section cell covers a 65x65-column render-distance authority in at most 81
             * horizontal cells, leaving headroom inside the default 256-instance FarField budget.
             * The exact Base authority remains camera-local and preserves nearby detail.
             */
            int span = stablePersistentCoverage ? DISTANT_FAR_FIELD_SPAN : farFieldSpan(key, viewState);
            FarFieldCellKey cellKey = new FarFieldCellKey(
                    Math.floorDiv(key.x(), span) * span,
                    Math.floorDiv(key.z(), span) * span,
                    span
            );
            builders.computeIfAbsent(cellKey, FarFieldCellBuilder::new)
                    .add(key, viewState.cameraValid() ? sectionDistanceSquared(key, viewState) : 0L);
        }

        List<FarFieldCell> cells = new ArrayList<>(builders.size());
        for (FarFieldCellBuilder builder : builders.values()) {
            cells.add(builder.build());
        }
        cells.sort(farFieldCellOrder());
        return List.copyOf(cells);
    }

    private static int farFieldSpan(SectionKey key, RendererViewState viewState) {
        if (!viewState.cameraValid()) {
            return NEAR_FAR_FIELD_SPAN;
        }
        long deltaX = Math.abs((long) key.x() - viewState.cameraSectionX());
        long deltaZ = Math.abs((long) key.z() - viewState.cameraSectionZ());
        long horizontalDistance = Math.max(deltaX, deltaZ);
        if (horizontalDistance >= DISTANT_FAR_FIELD_START_SECTIONS) {
            return DISTANT_FAR_FIELD_SPAN;
        }
        if (horizontalDistance >= MID_FAR_FIELD_START_SECTIONS) {
            return MID_FAR_FIELD_SPAN;
        }
        return NEAR_FAR_FIELD_SPAN;
    }

    private static Comparator<FarFieldCell> farFieldCellOrder() {
        return Comparator.comparingLong(FarFieldCell::distanceSquared)
                .thenComparingInt(cell -> cell.key().spanSections())
                .thenComparingInt(cell -> cell.key().originSectionX())
                .thenComparingInt(cell -> cell.key().originSectionZ());
    }

    private static Comparator<SectionKey> stableSectionOrder() {
        return Comparator.comparingInt(SectionKey::x)
                .thenComparingInt(SectionKey::y)
                .thenComparingInt(SectionKey::z);
    }

    private static List<SectionKey> stableSectionList(Collection<SectionKey> sectionKeys) {
        if (sectionKeys instanceof PackedSectionMembership packed && packed.canonicalOrder()) {
            return packed.orderedKeys();
        }
        List<SectionKey> stable = new ArrayList<>(sectionKeys);
        stable.sort(stableSectionOrder());
        return List.copyOf(stable);
    }

    private static long sectionDistanceSquared(SectionKey key, RendererViewState viewState) {
        long deltaX = (long) key.x() - viewState.cameraSectionX();
        long deltaY = (long) key.y() - viewState.cameraSectionY();
        long deltaZ = (long) key.z() - viewState.cameraSectionZ();
        return saturatedSquareSum(deltaX, deltaY, deltaZ);
    }

    private static long saturatedSquareSum(long first, long second, long third) {
        try {
            long firstSquare = Math.multiplyExact(first, first);
            long secondSquare = Math.multiplyExact(second, second);
            long thirdSquare = Math.multiplyExact(third, third);
            return Math.addExact(Math.addExact(firstSquare, secondSquare), thirdSquare);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Freezes one membership generation in the primitive representation shared
     * by admission and native terrain ownership. Reusing the same object keeps
     * a completed-BLAS transition from materializing two independent large
     * Set.copyOf graphs for the exact same section domain.
     */
    static PackedSectionMembership immutableMembershipSnapshot(Collection<SectionKey> sectionKeys) {
        return PackedSectionMembership.copyOf(Objects.requireNonNull(sectionKeys, "sectionKeys"));
    }

    static PackedSectionMembership immutableMembershipSnapshot(
            Collection<SectionKey> sectionKeys,
            PackedSectionMembership previous
    ) {
        return PackedSectionMembership.copyOf(
                Objects.requireNonNull(sectionKeys, "sectionKeys"),
                Objects.requireNonNull(previous, "previous")
        );
    }

    /**
     * Plans and memoizes one versioned membership generation.
     *
     * @param viewState                  current renderer view
     * @param residentSectionKeys        resident membership
     * @param baseAvailableSectionKeys   exact BLAS availability
     * @param residentMembershipRevision resident revision
     * @param baseAvailabilityRevision   availability revision
     * @return immutable admission
     */
    public synchronized Admission plan(
            RendererViewState viewState,
            Collection<SectionKey> residentSectionKeys,
            Collection<SectionKey> baseAvailableSectionKeys,
            long residentMembershipRevision,
            long baseAvailabilityRevision
    ) {
        Objects.requireNonNull(viewState, "viewState");
        Objects.requireNonNull(residentSectionKeys, "residentSectionKeys");
        Objects.requireNonNull(baseAvailableSectionKeys, "baseAvailableSectionKeys");
        if (residentMembershipRevision < 0L || baseAvailabilityRevision < 0L) {
            throw new IllegalArgumentException("admission membership revisions must not be negative");
        }
        planInvocations++;

        MembershipSnapshot membership = snapshotMembership(
                residentSectionKeys,
                baseAvailableSectionKeys,
                residentMembershipRevision,
                baseAvailabilityRevision
        );
        int baseCapacity = effectiveBaseCapacity(viewState, membership.residentSections().size());
        boolean viewSensitive = viewAffectsAdmission(
                viewState,
                membership.residentSections(),
                membership.baseAvailableSections(),
                baseCapacity
        );
        if (cachedAdmission != null
                && cachedResidentMembershipRevision == residentMembershipRevision
                && cachedBaseAvailabilityRevision == baseAvailabilityRevision
                && cachedBaseCapacity == baseCapacity
                && cachedViewSensitive == viewSensitive
                && (!viewSensitive || sameAdmissionView(cachedViewState, viewState))) {
            cachedPlanHits++;
            return cachedAdmission;
        }

        cachedPlanMisses++;
        Admission admission = buildPlan(
                viewState,
                membership.residentSections(),
                membership.baseAvailableSections(),
                baseCapacity,
                true
        );
        cachedAdmission = admission;
        cachedResidentMembershipRevision = residentMembershipRevision;
        cachedBaseAvailabilityRevision = baseAvailabilityRevision;
        cachedBaseCapacity = baseCapacity;
        cachedViewSensitive = viewSensitive;
        cachedViewState = viewState;
        return admission;
    }

    private Admission buildPlan(
            RendererViewState viewState,
            Collection<SectionKey> residentSectionKeys,
            Collection<SectionKey> baseAvailableSectionKeys
    ) {
        return buildPlan(viewState, residentSectionKeys, baseAvailableSectionKeys, false);
    }

    private Admission buildPlan(
            RendererViewState viewState,
            Collection<SectionKey> residentSectionKeys,
            Collection<SectionKey> baseAvailableSectionKeys,
            boolean membershipRelationValidated
    ) {
        return buildPlan(
                Objects.requireNonNull(viewState, "viewState"),
                Objects.requireNonNull(residentSectionKeys, "residentSectionKeys"),
                Objects.requireNonNull(baseAvailableSectionKeys, "baseAvailableSectionKeys"),
                effectiveBaseCapacity(viewState, residentSectionKeys.size()),
                membershipRelationValidated
        );
    }

    private Admission buildPlan(
            RendererViewState viewState,
            Collection<SectionKey> residentSectionKeys,
            Collection<SectionKey> baseAvailableSectionKeys,
            int baseCapacity,
            boolean membershipRelationValidated
    ) {
        planBuilds++;
        Set<SectionKey> residentSections = Set.copyOf(residentSectionKeys);
        Set<SectionKey> baseAvailableSections = Set.copyOf(baseAvailableSectionKeys);
        if (!membershipRelationValidated && !residentSections.containsAll(baseAvailableSections)) {
            throw new IllegalArgumentException("Base-available sections must be resident source sections");
        }
        boolean completeBaseCoverage = residentSections.size() == baseAvailableSections.size();
        if (!viewState.authoritative()) {
            List<SectionKey> allResident = stableSectionList(baseAvailableSections);
            retainedBaseSections = PackedSectionMembership.copyOf(allResident);
            retainedFarFieldCells = Set.of();
            return Admission.trusted(allResident, List.of(), allResident.size(), 0);
        }
        if (!persistentResidentCoverage
                && residentSections.size() <= baseCapacity
                && completeBaseCoverage) {
            /*
             * A production GPU-scene design keeps persistent primitives resident and treats view
             * relevance as a consumer-side selection. Rebuilding the TLAS from
             * host's rotating frustum when every resident section already
             * fits the Base budget turns camera motion into topology churn.
             * Keep the complete resident set in stable section order; only real
             * section residency changes may alter this compact TLAS.
             */
            List<SectionKey> allResident = stableSectionList(residentSections);
            retainedBaseSections = PackedSectionMembership.copyOf(allResident);
            retainedFarFieldCells = Set.of();
            return Admission.trusted(allResident, List.of(), allResident.size(), 0);
        }

        if (persistentResidentCoverage && residentSections.size() <= baseCapacity) {
            /*
             * GPUScene keeps persistent primitive identities independent from
             * per-view relevance. While host's complete resident section
             * set fits the exact BLAS/TLAS capacity, making Base membership a
             * function of the rotating frustum needlessly converts camera motion
             * into FarField topology churn. Admit every completed exact BLAS in
             * canonical order and use FarField only as temporary coverage for
             * source sections whose exact BLAS is still building.
             */
            List<SectionKey> baseSections = stableSectionList(baseAvailableSections);
            Set<SectionKey> baseSet = baseAvailableSections;
            if (completeBaseCoverage) {
                /*
                 * Every resident primitive already has an exact Base slot.
                 * Its active TLAS identity is therefore independent from the
                 * camera and raster-frustum order; ranking the complete 32
                 * distance set here merely manufactures CPU work.  This is the
                 * direct GPUScene case: persistent slot ownership is complete,
                 * so view relevance has no topology work left to perform.
                 */
                retainedBaseSections = baseSet;
                retainedFarFieldCells = Set.of();
                return Admission.trusted(baseSections, List.of(), residentSections.size(), 0);
            }
            if (!farFieldProxyEnabled) {
                /*
                 * Persistent coverage is the normal host application RT path. Do not let its warm-up
                 * fast path bypass the exact-fidelity contract: a not-yet-built section must
                 * remain an explicit coverage deficit, never become an expanded proxy quad.
                 */
                retainedBaseSections = baseSet;
                retainedFarFieldCells = Set.of();
                return Admission.trusted(
                        baseSections,
                        List.of(),
                        residentSections.size(),
                        residentSections.size() - baseSections.size()
                );
            }
            List<SectionKey> rankedResidentSections = stableSectionList(residentSections);
            List<FarFieldCell> farFieldCells = selectFarFieldCellsWithRetention(
                    buildFarFieldCells(RendererViewState.allResident(), rankedResidentSections, baseSet, true)
            );
            int coveredSections = baseSections.size();
            for (FarFieldCell cell : farFieldCells) {
                coveredSections = Math.addExact(coveredSections, cell.sourceSections().size());
            }
            retainedBaseSections = baseSet;
            retainedFarFieldCells = farFieldCells.stream()
                    .map(FarFieldCell::key)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            return Admission.trusted(
                    baseSections,
                    farFieldCells,
                    residentSections.size(),
                    residentSections.size() - coveredSections
            );
        }

        List<SectionKey> coverageSections = persistentResidentCoverage
                ? stableSectionList(residentSections)
                : visibleResidentSections(viewState, residentSections);
        if (coverageSections.isEmpty()) {
            retainedBaseSections = Set.of();
            retainedFarFieldCells = Set.of();
            return Admission.empty();
        }

        List<SectionKey> rankedSections = persistentResidentCoverage
                ? coverageSections
                : rankVisibleSections(viewState, coverageSections);
        List<SectionKey> rankedBaseCandidates = persistentResidentCoverage
                ? stableSectionList(baseAvailableSections)
                : rankedSections;
        List<SectionKey> rankedBaseAvailableSections = persistentResidentCoverage
                ? rankedBaseCandidates
                : rankedBaseCandidates.stream().filter(baseAvailableSections::contains).toList();
        List<SectionKey> baseSections = selectWithRetention(
                rankedBaseAvailableSections,
                retainedBaseSections,
                baseCapacity,
                retentionMargin
        );
        if (!farFieldProxyEnabled) {
            /*
             * Exact parity consumers must leave missing sections uncovered until their exact
             * BLAS installs. A proxy cell is a different geometry/material model, so publishing
             * it here would turn resource pressure into visible fake terrain.
             */
            PackedSectionMembership previousBase = retainedBaseSections instanceof PackedSectionMembership packed
                    ? packed : PackedSectionMembership.empty();
            retainedBaseSections = PackedSectionMembership.copyOf(baseSections, previousBase);
            retainedFarFieldCells = Set.of();
            return Admission.trusted(
                    baseSections,
                    List.of(),
                    coverageSections.size(),
                    coverageSections.size() - baseSections.size()
            );
        }
        PackedSectionMembership previousBase = retainedBaseSections instanceof PackedSectionMembership packed
                ? packed
                : PackedSectionMembership.empty();
        Set<SectionKey> baseSet = PackedSectionMembership.copyOf(baseSections, previousBase);
        List<FarFieldCell> rankedFarFieldCells = buildFarFieldCells(
                persistentResidentCoverage ? RendererViewState.allResident() : viewState,
                rankedSections,
                baseSet,
                persistentResidentCoverage
        );
        List<FarFieldCell> farFieldCells = selectFarFieldCellsWithRetention(rankedFarFieldCells);

        int coveredSections = baseSections.size();
        for (FarFieldCell cell : farFieldCells) {
            coveredSections = Math.addExact(coveredSections, cell.sourceSections().size());
        }
        retainedBaseSections = baseSet;
        retainedFarFieldCells = farFieldCells.stream()
                .map(FarFieldCell::key)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return Admission.trusted(
                baseSections,
                farFieldCells,
                coverageSections.size(),
                coverageSections.size() - coveredSections
        );
    }

    /**
     * Returns whether camera/frustum data owns active instance topology for this planner.
     * Persistent resident coverage always returns false; its resource publications are the only
     * topology authority, including while exact BLASes are still warming up.
     *
     * @param viewState           candidate camera/frustum generation
     * @param residentSectionKeys currently resident sections
     * @return whether the view changes admission topology
     */
    public synchronized boolean viewAffectsAdmission(
            RendererViewState viewState,
            Collection<SectionKey> residentSectionKeys
    ) {
        Objects.requireNonNull(viewState, "viewState");
        Objects.requireNonNull(residentSectionKeys, "residentSectionKeys");
        return viewAffectsAdmission(
                viewState,
                residentSectionKeys,
                residentSectionKeys,
                effectiveBaseCapacity(viewState, residentSectionKeys.size())
        );
    }

    /**
     * Reports whether a view changes topology for explicit base availability.
     *
     * @param viewState                candidate view
     * @param residentSectionKeys      resident membership
     * @param baseAvailableSectionKeys exact availability
     * @return topology impact
     */
    public synchronized boolean viewAffectsAdmission(
            RendererViewState viewState,
            Collection<SectionKey> residentSectionKeys,
            Collection<SectionKey> baseAvailableSectionKeys
    ) {
        Objects.requireNonNull(viewState, "viewState");
        Objects.requireNonNull(residentSectionKeys, "residentSectionKeys");
        Objects.requireNonNull(baseAvailableSectionKeys, "baseAvailableSectionKeys");
        return viewAffectsAdmission(
                viewState,
                residentSectionKeys,
                baseAvailableSectionKeys,
                effectiveBaseCapacity(viewState, residentSectionKeys.size())
        );
    }

    private boolean viewAffectsAdmission(
            RendererViewState viewState,
            Collection<SectionKey> residentSectionKeys,
            Collection<SectionKey> baseAvailableSectionKeys,
            int baseCapacity
    ) {
        if (!viewState.authoritative()) {
            return false;
        }
        if (persistentResidentCoverage) {
            /*
             * Persistent GPU-scene ownership makes camera data a scheduling
             * hint, not an instance-topology owner. Missing exact BLASes use
             * stable spatial proxy cells until their resource revision
             * advances; rotating the camera cannot regroup those cells.
             */
            return false;
        }
        return residentSectionKeys.size() > baseCapacity
                || baseAvailableSectionKeys.size() != residentSectionKeys.size();
    }

    synchronized int baseCapacityHighWater() {
        return baseCapacityHighWater;
    }

    /**
     * Returns admission-plan cache hits.
     *
     * @return hit count
     */
    public synchronized long cachedPlanHits() {
        return cachedPlanHits;
    }

    /**
     * Returns admission-plan cache misses.
     *
     * @return miss count
     */
    public synchronized long cachedPlanMisses() {
        return cachedPlanMisses;
    }

    /**
     * Returns canonical membership snapshot cache hits.
     *
     * @return hit count
     */
    public synchronized long cachedMembershipSnapshotHits() {
        return cachedMembershipSnapshotHits;
    }

    /**
     * Returns canonical membership snapshot cache misses.
     *
     * @return miss count
     */
    public synchronized long cachedMembershipSnapshotMisses() {
        return cachedMembershipSnapshotMisses;
    }

    synchronized long fullMembershipValidations() {
        return fullMembershipValidations;
    }

    synchronized long deltaMembershipValidations() {
        return deltaMembershipValidations;
    }

    synchronized long planInvocations() {
        return planInvocations;
    }

    synchronized long planBuilds() {
        return planBuilds;
    }

    private MembershipSnapshot snapshotMembership(
            Collection<SectionKey> residentSectionKeys,
            Collection<SectionKey> baseAvailableSectionKeys,
            long residentMembershipRevision,
            long baseAvailabilityRevision
    ) {
        boolean residentStable = cachedResidentSnapshotRevision == residentMembershipRevision;
        boolean baseAvailableStable = cachedBaseAvailabilitySnapshotRevision == baseAvailabilityRevision;
        if (residentStable && baseAvailableStable) {
            if (residentSectionKeys instanceof PackedSectionMembership packedResident
                    && packedResident != cachedResidentSections
                    || baseAvailableSectionKeys instanceof PackedSectionMembership packedBase
                    && packedBase != cachedBaseAvailableSections) {
                throw new IllegalArgumentException(
                        "packed membership identity changed without advancing its producer revision"
                );
            }
            cachedMembershipSnapshotHits++;
            return cachedMembershipSnapshot;
        }

        PackedSectionMembership previousResident = cachedResidentSections;
        PackedSectionMembership previousBaseAvailable = cachedBaseAvailableSections;
        boolean packedProducerBoundary = residentSectionKeys instanceof PackedSectionMembership
                && baseAvailableSectionKeys instanceof PackedSectionMembership;

        /*
         * Rebuild only the ownership domain whose producer revision changed.
         * Base availability is allowed to change independently while section
         * source membership remains stable during asynchronous BLAS warm-up.
         */
        PackedSectionMembership nextResident = residentStable
                ? cachedResidentSections
                : PackedSectionMembership.copyOf(residentSectionKeys, cachedResidentSections);
        PackedSectionMembership nextBaseAvailable = baseAvailableStable
                ? cachedBaseAvailableSections
                : PackedSectionMembership.copyOf(
                baseAvailableSectionKeys,
                cachedBaseAvailableSections
        );
        boolean incrementallyValidated = packedProducerBoundary
                && cachedSubsetRelationValidated
                && nextBaseAvailable.provesSubsetTransition(
                nextResident,
                previousBaseAvailable,
                previousResident
        );
        if (incrementallyValidated) {
            deltaMembershipValidations++;
        } else {
            fullMembershipValidations++;
            if (!nextResident.containsAll(nextBaseAvailable)) {
                throw new IllegalArgumentException("Base-available sections must be resident source sections");
            }
        }
        cachedResidentSections = nextResident;
        cachedBaseAvailableSections = nextBaseAvailable;
        cachedResidentSnapshotRevision = residentMembershipRevision;
        cachedBaseAvailabilitySnapshotRevision = baseAvailabilityRevision;
        cachedSubsetRelationValidated = true;
        cachedMembershipSnapshotMisses++;
        cachedMembershipSnapshot = new MembershipSnapshot(cachedResidentSections, cachedBaseAvailableSections);
        return cachedMembershipSnapshot;
    }

    private boolean sameAdmissionView(RendererViewState first, RendererViewState second) {
        return first.authoritative() == second.authoritative()
                && first.cameraValid() == second.cameraValid()
                && first.cameraSectionX() == second.cameraSectionX()
                && first.cameraSectionY() == second.cameraSectionY()
                && first.cameraSectionZ() == second.cameraSectionZ()
                /*
                 * Persistent coverage ranks the renderer-owned resident
                 * membership, never host's rotating raster render list.
                 * Keeping that list in this key made yaw-only updates rebuild
                 * an identical Base/FarField plan and allocate its complete
                 * Set/sort graph. Legacy view-owned admission still needs the
                 * list because it deliberately selects only visible sections.
                 */
                && (persistentResidentCoverage
                || first.visibleSectionKeys().equals(second.visibleSectionKeys()));
    }

    private int effectiveBaseCapacity(RendererViewState viewState, int residentSectionCount) {
        if (residentSectionCount < 0) {
            throw new IllegalArgumentException("resident section count must not be negative");
        }
        if (!growBaseCapacityToAuthoritativeView || !viewState.authoritative()) {
            return baseCapacityHighWater;
        }
        int requiredCapacity = persistentResidentCoverage
                ? residentSectionCount
                : viewState.visibleSectionKeys().size();
        baseCapacityHighWater = Math.max(baseCapacityHighWater, requiredCapacity);
        return baseCapacityHighWater;
    }

    private List<FarFieldCell> selectFarFieldCellsWithRetention(List<FarFieldCell> rankedCells) {
        Map<FarFieldCellKey, FarFieldCell> cellsByKey = new HashMap<>();
        for (FarFieldCell cell : rankedCells) {
            cellsByKey.put(cell.key(), cell);
        }

        int farFieldCellCapacity = effectiveFarFieldCellCapacity(rankedCells.size());
        int retentionLimit = Math.min(rankedCells.size(), saturatedAdd(farFieldCellCapacity, retentionMargin));
        Set<FarFieldCellKey> retentionCandidates = new HashSet<>();
        for (int index = 0; index < retentionLimit; index++) {
            retentionCandidates.add(rankedCells.get(index).key());
        }
        LinkedHashSet<FarFieldCellKey> selected = new LinkedHashSet<>();
        for (FarFieldCell cell : rankedCells) {
            if (retainedFarFieldCells.contains(cell.key()) && retentionCandidates.contains(cell.key())) {
                selected.add(cell.key());
                if (selected.size() == farFieldCellCapacity) {
                    break;
                }
            }
        }
        for (FarFieldCell cell : rankedCells) {
            if (selected.size() == farFieldCellCapacity) {
                break;
            }
            selected.add(cell.key());
        }

        List<FarFieldCell> result = new ArrayList<>(selected.size());
        for (FarFieldCellKey key : selected) {
            result.add(cellsByKey.get(key));
        }
        result.sort(farFieldCellOrder());
        return List.copyOf(result);
    }

    private int effectiveFarFieldCellCapacity(int requiredCells) {
        if (!growFarFieldCapacityToFit || requiredCells <= farFieldCellHighWater) {
            return farFieldCellHighWater;
        }
        farFieldCellHighWater = nextPowerOfTwo(requiredCells);
        return farFieldCellHighWater;
    }

    private List<SectionKey> selectWithRetention(
            List<SectionKey> rankedSections,
            Set<SectionKey> retainedSections,
            int capacity,
            int retentionMargin
    ) {
        int retentionLimit = Math.min(rankedSections.size(), capacity + retentionMargin);
        baseSelectionScratch.clear();
        baseSelectionScratch.ensureCapacity(Math.min(capacity, rankedSections.size()));
        List<SectionKey> selected = new ArrayList<>(Math.min(capacity, rankedSections.size()));
        for (int index = 0; index < retentionLimit; index++) {
            SectionKey key = rankedSections.get(index);
            if (retainedSections.contains(key)
                    && baseSelectionScratch.add(key.packed())) {
                selected.add(key);
                if (selected.size() == capacity) {
                    break;
                }
            }
        }
        for (SectionKey key : rankedSections) {
            if (selected.size() == capacity) {
                break;
            }
            if (baseSelectionScratch.add(key.packed())) {
                selected.add(key);
            }
        }
        return List.copyOf(selected);
    }

    private record MembershipSnapshot(
            PackedSectionMembership residentSections,
            PackedSectionMembership baseAvailableSections
    ) {
    }

    /**
     * Immutable exact and far-field coverage decision for one view generation.
     */
    public static final class Admission {
        private final List<SectionKey> baseSections;
        private final List<FarFieldCell> farFieldCells;
        private final int visibleResidentSections;
        private final int uncoveredSections;

        /**
         * Public construction validates arbitrary caller-provided topology in full.
         *
         * @param baseSections            exact-section admissions
         * @param farFieldCells           coarse proxy admissions
         * @param visibleResidentSections visible resident population
         * @param uncoveredSections       visible population lacking representation
         */
        public Admission(
                List<SectionKey> baseSections,
                List<FarFieldCell> farFieldCells,
                int visibleResidentSections,
                int uncoveredSections
        ) {
            this(baseSections, farFieldCells, visibleResidentSections, uncoveredSections, false);
        }

        private Admission(
                List<SectionKey> baseSections,
                List<FarFieldCell> farFieldCells,
                int visibleResidentSections,
                int uncoveredSections,
                boolean trustedTopology
        ) {
            this.baseSections = List.copyOf(baseSections);
            this.farFieldCells = List.copyOf(farFieldCells);
            this.visibleResidentSections = visibleResidentSections;
            this.uncoveredSections = uncoveredSections;
            validateCounts(visibleResidentSections, uncoveredSections);
            int coveredSections = trustedTopology
                    ? coveredSectionCount(this.baseSections, this.farFieldCells)
                    : validateCoveredSections(this.baseSections, this.farFieldCells);
            if (coveredSections + uncoveredSections != visibleResidentSections) {
                throw new IllegalArgumentException("admission coverage must equal visible resident section count");
            }
        }

        private static Admission trusted(
                List<SectionKey> baseSections,
                List<FarFieldCell> farFieldCells,
                int visibleResidentSections,
                int uncoveredSections
        ) {
            return new Admission(baseSections, farFieldCells, visibleResidentSections, uncoveredSections, true);
        }

        static Admission empty() {
            return trusted(List.of(), List.of(), 0, 0);
        }

        private static int validateCoveredSections(
                List<SectionKey> baseSections,
                List<FarFieldCell> farFieldCells
        ) {
            /*
             * JFR proved the old HashSet<SectionKey> validation allocated a
             * HashMap node per section on the ACTIVE path.  Keep the same
             * duplicate/overlap invariant, but validate packed section
             * coordinates: the admission boundary cares about host's
             * section identity, not record-object allocation.
             */
            int expectedSize = coveredSectionCount(baseSections, farFieldCells);
            LongOpenHashSet covered = new LongOpenHashSet(expectedSize);
            for (SectionKey section : baseSections) {
                if (!covered.add(packedSection(section))) {
                    throw new IllegalArgumentException("base admission must not contain duplicate sections");
                }
            }
            for (FarFieldCell cell : farFieldCells) {
                for (SectionKey sourceSection : cell.sourceSections()) {
                    if (!covered.add(packedSection(sourceSection))) {
                        throw new IllegalArgumentException("section admitted to multiple Base/FarField layers");
                    }
                }
            }
            return covered.size();
        }

        private static int coveredSectionCount(
                List<SectionKey> baseSections,
                List<FarFieldCell> farFieldCells
        ) {
            int count = baseSections.size();
            for (FarFieldCell cell : farFieldCells) {
                count = Math.addExact(count, cell.sourceSections().size());
            }
            return count;
        }

        private static void validateCounts(int visibleResidentSections, int uncoveredSections) {
            if (visibleResidentSections < 0 || uncoveredSections < 0 || uncoveredSections > visibleResidentSections) {
                throw new IllegalArgumentException("invalid admission coverage counts");
            }
        }

        private static long packedSection(SectionKey key) {
            return key.packed();
        }

        /**
         * Returns covered visible resident sections.
         *
         * @return covered count
         */
        public int coveredSections() {
            return visibleResidentSections - uncoveredSections;
        }

        /**
         * Returns immutable exact-section admissions.
         *
         * @return base sections
         */
        public List<SectionKey> baseSections() {
            return baseSections;
        }

        /**
         * Returns immutable coarse proxy cell admissions.
         *
         * @return proxy cells
         */
        public List<FarFieldCell> farFieldCells() {
            return farFieldCells;
        }

        /**
         * Returns visible resident input population.
         *
         * @return visible count
         */
        public int visibleResidentSections() {
            return visibleResidentSections;
        }

        /**
         * Returns visible resident sections not represented due to capacity.
         *
         * @return uncovered count
         */
        public int uncoveredSections() {
            return uncoveredSections;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Admission admission)) {
                return false;
            }
            return visibleResidentSections == admission.visibleResidentSections
                    && uncoveredSections == admission.uncoveredSections
                    && baseSections.equals(admission.baseSections)
                    && farFieldCells.equals(admission.farFieldCells);
        }

        @Override
        public int hashCode() {
            int result = baseSections.hashCode();
            result = 31 * result + farFieldCells.hashCode();
            result = 31 * result + Integer.hashCode(visibleResidentSections);
            return 31 * result + Integer.hashCode(uncoveredSections);
        }

        @Override
        public String toString() {
            return "Admission[baseSections=" + baseSections
                    + ", farFieldCells=" + farFieldCells
                    + ", visibleResidentSections=" + visibleResidentSections
                    + ", uncoveredSections=" + uncoveredSections + ']';
        }
    }

    /**
     * One coarse proxy cell and its exact contributing sections.
     *
     * @param key             aligned cell footprint
     * @param sourceSections  exact contributing membership
     * @param distanceSquared squared section-space priority distance
     */
    public record FarFieldCell(FarFieldCellKey key, List<SectionKey> sourceSections, long distanceSquared) {
        /**
         * Freezes source membership and validates spatial containment.
         */
        public FarFieldCell {
            key = Objects.requireNonNull(key, "key");
            sourceSections = List.copyOf(sourceSections);
            if (sourceSections.isEmpty()) {
                throw new IllegalArgumentException("far-field cell must contain source sections");
            }
            if (distanceSquared < 0L) {
                throw new IllegalArgumentException("distanceSquared must not be negative");
            }
            for (SectionKey sourceSection : sourceSections) {
                if (!key.contains(sourceSection)) {
                    throw new IllegalArgumentException("far-field source section lies outside its cell");
                }
            }
        }
    }

    /**
     * Aligned power-of-two XZ footprint for one far-field proxy.
     *
     * @param originSectionX aligned minimum section X
     * @param originSectionZ aligned minimum section Z
     * @param spanSections   positive power-of-two side length
     */
    public record FarFieldCellKey(int originSectionX, int originSectionZ, int spanSections) {
        /**
         * Validates origin alignment and power-of-two span.
         */
        public FarFieldCellKey {
            if (spanSections <= 0 || (spanSections & (spanSections - 1)) != 0) {
                throw new IllegalArgumentException("far-field span must be a positive power of two");
            }
            if (Math.floorMod(originSectionX, spanSections) != 0
                    || Math.floorMod(originSectionZ, spanSections) != 0) {
                throw new IllegalArgumentException("far-field origin must align to its span");
            }
        }

        /**
         * Reports XZ containment independent of section height.
         *
         * @param key section identity
         * @return containment
         */
        public boolean contains(SectionKey key) {
            Objects.requireNonNull(key, "key");
            return key.x() >= originSectionX
                    && key.x() < (long) originSectionX + spanSections
                    && key.z() >= originSectionZ
                    && key.z() < (long) originSectionZ + spanSections;
        }
    }

    private static final class FarFieldCellBuilder {
        private final FarFieldCellKey key;
        private final List<SectionKey> sourceSections = new ArrayList<>();
        private long distanceSquared = Long.MAX_VALUE;

        private FarFieldCellBuilder(FarFieldCellKey key) {
            this.key = Objects.requireNonNull(key, "key");
        }

        private void add(SectionKey sourceSection, long sourceDistanceSquared) {
            sourceSections.add(Objects.requireNonNull(sourceSection, "sourceSection"));
            distanceSquared = Math.min(distanceSquared, sourceDistanceSquared);
        }

        private FarFieldCell build() {
            sourceSections.sort(stableSectionOrder());
            return new FarFieldCell(key, sourceSections, distanceSquared);
        }
    }
}
