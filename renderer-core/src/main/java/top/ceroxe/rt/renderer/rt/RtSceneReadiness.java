package top.ceroxe.rt.renderer.rt;

import top.ceroxe.rt.renderer.RendererUpdateLoop;

import java.util.Objects;

/**
 * Structured readiness signal for presenting a world RT frame.
 *
 * <p>Renderer-side presentation must not scrape human log strings to decide
 * whether it is safe to replace fallback world rendering. This immutable value
 * captures the backend facts that matter for avoiding partial-TLAS frames with
 * invisible chunk gaps.</p>
 *
 * @param worldTlasReady           whether an executable world TLAS exists
 * @param observedInstances        total observed instances
 * @param observedSectionInstances observed section instances
 * @param observedDynamicInstances observed dynamic instances
 * @param builtSectionInstances    resident section instances
 * @param builtDynamicInstances    resident dynamic instances
 * @param pendingRtSectionBuilds   pending section build count
 * @param pendingRtDynamicBuild    whether a dynamic build is pending
 * @param pendingRtTriangles       pending triangle count
 * @param cachedRtTriangles        cached triangle count
 * @param builtRevision            built world revision
 * @param latestRevision           latest requested world revision
 * @param deferredByPendingBacklog whether dispatch is deferred by backlog
 */
public record RtSceneReadiness(
        boolean worldTlasReady,
        int observedInstances,
        int observedSectionInstances,
        int observedDynamicInstances,
        int builtSectionInstances,
        int builtDynamicInstances,
        int pendingRtSectionBuilds,
        boolean pendingRtDynamicBuild,
        long pendingRtTriangles,
        long cachedRtTriangles,
        long builtRevision,
        long latestRevision,
        boolean deferredByPendingBacklog
) {
    public static final String READY_REASON = "ready";
    public static final String WORLD_TLAS_NOT_READY_REASON = "worldTlasNotReady";
    public static final String WORLD_TLAS_HAS_NO_WORLD_GEOMETRY_REASON = "worldTlasHasNoWorldGeometry";
    public static final String WORLD_TLAS_REVISION_NOT_CURRENT_REASON = "worldTlasRevisionNotCurrent";
    public static final String RT_SECTION_BUILDS_PENDING_REASON = "rtSectionBuildsPending";
    public static final String RT_DYNAMIC_BUILD_PENDING_REASON = "rtDynamicBuildPending";
    public static final String RENDERER_BACKLOG_PENDING_REASON = "rendererBacklogPending";
    public static final String RT_SCENE_COVERAGE_GAP_REASON = "rtSceneCoverageGap";
    private static final long UNBUILT_REVISION = -1L;
    private static final RtSceneReadiness UNAVAILABLE = new RtSceneReadiness(
            false,
            0,
            0,
            0,
            0,
            0,
            0,
            false,
            0L,
            0L,
            UNBUILT_REVISION,
            UNBUILT_REVISION,
            false
    );

    /**
     * Creates a section-only readiness snapshot for compatibility callers.
     *
     * @param worldTlasReady           whether an executable world TLAS exists
     * @param observedInstances        observed section instances
     * @param pendingRtSectionBuilds   pending section build count
     * @param pendingRtTriangles       pending triangle count
     * @param cachedRtTriangles        cached triangle count
     * @param builtRevision            built world revision
     * @param latestRevision           latest requested world revision
     * @param deferredByPendingBacklog whether dispatch is deferred by backlog
     */
    public RtSceneReadiness(
            boolean worldTlasReady,
            int observedInstances,
            int pendingRtSectionBuilds,
            long pendingRtTriangles,
            long cachedRtTriangles,
            long builtRevision,
            long latestRevision,
            boolean deferredByPendingBacklog
    ) {
        this(
                worldTlasReady,
                observedInstances,
                observedInstances,
                0,
                observedInstances,
                0,
                pendingRtSectionBuilds,
                false,
                pendingRtTriangles,
                cachedRtTriangles,
                builtRevision,
                latestRevision,
                deferredByPendingBacklog
        );
    }

    /**
     * Validates cross-field readiness invariants for the canonical record.
     */
    public RtSceneReadiness {
        if (observedInstances < 0) {
            throw new IllegalArgumentException("observedInstances must not be negative");
        }
        if (observedSectionInstances < 0) {
            throw new IllegalArgumentException("observedSectionInstances must not be negative");
        }
        if (observedDynamicInstances < 0) {
            throw new IllegalArgumentException("observedDynamicInstances must not be negative");
        }
        if (builtSectionInstances < 0) {
            throw new IllegalArgumentException("builtSectionInstances must not be negative");
        }
        if (builtDynamicInstances < 0) {
            throw new IllegalArgumentException("builtDynamicInstances must not be negative");
        }
        long splitObservedInstances = (long) observedSectionInstances + observedDynamicInstances;
        if (splitObservedInstances != observedInstances) {
            throw new IllegalArgumentException("observedInstances must equal section plus dynamic instances");
        }
        long splitBuiltInstances = (long) builtSectionInstances + builtDynamicInstances;
        if (splitBuiltInstances > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("built instance split exceeds supported count");
        }
        if (!worldTlasReady && splitBuiltInstances != 0L) {
            throw new IllegalArgumentException("unready world TLAS must not report built instances");
        }
        if (worldTlasReady && splitBuiltInstances == 0L) {
            throw new IllegalArgumentException("ready world TLAS must report built instances");
        }
        if (pendingRtSectionBuilds < 0) {
            throw new IllegalArgumentException("pendingRtSectionBuilds must not be negative");
        }
        if (pendingRtTriangles < 0L) {
            throw new IllegalArgumentException("pendingRtTriangles must not be negative");
        }
        if (cachedRtTriangles < 0L) {
            throw new IllegalArgumentException("cachedRtTriangles must not be negative");
        }
        if (builtRevision < UNBUILT_REVISION) {
            throw new IllegalArgumentException("builtRevision must be -1 or greater");
        }
        if (latestRevision < UNBUILT_REVISION) {
            throw new IllegalArgumentException("latestRevision must be -1 or greater");
        }
    }

    /**
     * Returns the shared snapshot representing unavailable RT state.
     *
     * @return unavailable snapshot
     */
    public static RtSceneReadiness unavailable() {
        return UNAVAILABLE;
    }

    /**
     * Returns the total number of built section and dynamic instances.
     *
     * @return built instance count
     */
    public int builtInstances() {
        return builtSectionInstances + builtDynamicInstances;
    }

    /**
     * Reports whether section or dynamic RT work remains.
     *
     * @return whether RT work is pending
     */
    public boolean hasPendingRtBuilds() {
        return hasPendingRtSectionBuilds() || pendingRtDynamicBuild;
    }

    /**
     * Reports whether section build counts or triangles remain.
     *
     * @return whether section work is pending
     */
    public boolean hasPendingRtSectionBuilds() {
        return pendingRtSectionBuilds > 0 || pendingRtTriangles > 0L;
    }

    /**
     * Returns the stable reason describing pending RT work.
     *
     * @return pending-work reason token
     */
    public String pendingRtBuildBlockReason() {
        if (hasPendingRtSectionBuilds()) {
            return RT_SECTION_BUILDS_PENDING_REASON;
        }
        if (pendingRtDynamicBuild) {
            return RT_DYNAMIC_BUILD_PENDING_REASON;
        }
        return READY_REASON;
    }

    /**
     * Reports whether executable world geometry is populated.
     *
     * @return whether world instances exist
     */
    public boolean hasWorldInstances() {
        return observedInstances > 0 && builtInstances() > 0 && cachedRtTriangles > 0L;
    }

    /**
     * Reports whether the built TLAS revision matches the latest request.
     *
     * @return revision-current state
     */
    public boolean builtRevisionIsCurrent() {
        return worldTlasReady && builtRevision >= 0L && builtRevision == latestRevision;
    }

    /**
     * Tests strict frame-dispatch eligibility.
     *
     * @param rendererBacklog current renderer backlog and coverage evidence
     * @return whether strict dispatch is eligible
     */
    public boolean isFrameDispatchEligible(RendererUpdateLoop.BacklogSnapshot rendererBacklog) {
        return READY_REASON.equals(frameDispatchBlockReason(rendererBacklog));
    }

    /**
     * Returns the production block reason, allowing a useful already-built TLAS.
     *
     * @param rendererBacklog current renderer backlog and coverage evidence
     * @return stable production block reason
     */
    public String frameProductionBlockReason(RendererUpdateLoop.BacklogSnapshot rendererBacklog) {
        Objects.requireNonNull(rendererBacklog, "rendererBacklog");
        String strictReason = frameDispatchBlockReason(rendererBacklog);
        if (READY_REASON.equals(strictReason) || isWorldTlasDispatchUseful()) {
            return READY_REASON;
        }
        return strictReason;
    }

    /**
     * Tests whether any useful frame may be produced.
     *
     * @param rendererBacklog current renderer backlog and coverage evidence
     * @return whether frame production is eligible
     */
    public boolean isFrameProductionEligible(RendererUpdateLoop.BacklogSnapshot rendererBacklog) {
        return READY_REASON.equals(frameProductionBlockReason(rendererBacklog));
    }

    /**
     * Reports whether the existing world TLAS remains useful for dispatch.
     *
     * @return useful-dispatch state
     */
    public boolean isWorldTlasDispatchUseful() {
        return worldTlasReady
                && hasWorldInstances()
                && builtRevision >= 0L;
    }

    /**
     * Returns the strict frame-dispatch block reason.
     *
     * @param rendererBacklog current renderer backlog and coverage evidence
     * @return {@link #READY_REASON} or a stable blocking reason
     */
    public String frameDispatchBlockReason(RendererUpdateLoop.BacklogSnapshot rendererBacklog) {
        Objects.requireNonNull(rendererBacklog, "rendererBacklog");
        if (!worldTlasReady) {
            return WORLD_TLAS_NOT_READY_REASON;
        }
        if (!hasWorldInstances()) {
            return WORLD_TLAS_HAS_NO_WORLD_GEOMETRY_REASON;
        }
        if (!builtRevisionIsCurrent()) {
            return WORLD_TLAS_REVISION_NOT_CURRENT_REASON;
        }
        if (hasPendingRtBuilds()) {
            return pendingRtBuildBlockReason();
        }
        if (rendererBacklog.hasPresentationBlockingRendererWork()) {
            return RENDERER_BACKLOG_PENDING_REASON;
        }
        if (rendererBacklog.hasRtCoverageGap(builtSectionInstances)) {
            return RT_SCENE_COVERAGE_GAP_REASON;
        }
        return READY_REASON;
    }

    /**
     * Tests whether streaming can safely use the current world TLAS.
     *
     * @param rendererBacklog current renderer backlog and coverage evidence
     * @return whether streaming dispatch remains useful without an unaccounted coverage gap
     */
    public boolean isStreamingFrameDispatchUseful(RendererUpdateLoop.BacklogSnapshot rendererBacklog) {
        Objects.requireNonNull(rendererBacklog, "rendererBacklog");
        return isWorldTlasDispatchUseful()
                && !rendererBacklog.hasUnaccountedRtCoverageGap(builtSectionInstances, pendingRtSectionBuilds);
    }

    /**
     * Returns a stable structured log fragment for this snapshot.
     *
     * @return diagnostic log fragment
     */
    public String asLogFragment() {
        return "rtSceneReadiness{worldTlasReady=" + worldTlasReady
                + ", observedInstances=" + observedInstances
                + ", observedSectionInstances=" + observedSectionInstances
                + ", observedDynamicInstances=" + observedDynamicInstances
                + ", builtInstances=" + builtInstances()
                + ", builtSectionInstances=" + builtSectionInstances
                + ", builtDynamicInstances=" + builtDynamicInstances
                + ", pendingRtSectionBuilds=" + pendingRtSectionBuilds
                + ", pendingRtDynamicBuild=" + pendingRtDynamicBuild
                + ", pendingRtTriangles=" + pendingRtTriangles
                + ", cachedRtTriangles=" + cachedRtTriangles
                + ", builtRevision=" + builtRevision
                + ", latestRevision=" + latestRevision
                + ", deferredByPendingBacklog=" + deferredByPendingBacklog
                + "}";
    }
}
