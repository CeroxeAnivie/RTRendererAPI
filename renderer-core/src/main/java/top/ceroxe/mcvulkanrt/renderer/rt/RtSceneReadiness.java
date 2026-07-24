package top.ceroxe.mcvulkanrt.renderer.rt;

import top.ceroxe.mcvulkanrt.renderer.RendererUpdateLoop;

import java.util.Objects;

/**
 * Structured readiness signal for presenting a world RT frame.
 *
 * <p>Renderer-side presentation must not scrape human log strings to decide
 * whether it is safe to hide sourceEngine world rendering. This immutable value
 * captures the backend facts that matter for avoiding partial-TLAS frames with
 * invisible chunk gaps.</p>
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

    public int builtInstances() {
        return builtSectionInstances + builtDynamicInstances;
    }

    public static RtSceneReadiness unavailable() {
        return UNAVAILABLE;
    }

    public boolean hasPendingRtBuilds() {
        return hasPendingRtSectionBuilds() || pendingRtDynamicBuild;
    }

    public boolean hasPendingRtSectionBuilds() {
        return pendingRtSectionBuilds > 0 || pendingRtTriangles > 0L;
    }

    public String pendingRtBuildBlockReason() {
        if (hasPendingRtSectionBuilds()) {
            return RT_SECTION_BUILDS_PENDING_REASON;
        }
        if (pendingRtDynamicBuild) {
            return RT_DYNAMIC_BUILD_PENDING_REASON;
        }
        return READY_REASON;
    }

    public boolean hasWorldInstances() {
        return observedInstances > 0 && builtInstances() > 0 && cachedRtTriangles > 0L;
    }

    public boolean builtRevisionIsCurrent() {
        return worldTlasReady && builtRevision >= 0L && builtRevision == latestRevision;
    }

    public boolean isFrameDispatchEligible(RendererUpdateLoop.BacklogSnapshot rendererBacklog) {
        return READY_REASON.equals(frameDispatchBlockReason(rendererBacklog));
    }

    public String frameProductionBlockReason(RendererUpdateLoop.BacklogSnapshot rendererBacklog) {
        Objects.requireNonNull(rendererBacklog, "rendererBacklog");
        String strictReason = frameDispatchBlockReason(rendererBacklog);
        if (READY_REASON.equals(strictReason) || isWorldTlasDispatchUseful()) {
            return READY_REASON;
        }
        return strictReason;
    }

    public boolean isFrameProductionEligible(RendererUpdateLoop.BacklogSnapshot rendererBacklog) {
        return READY_REASON.equals(frameProductionBlockReason(rendererBacklog));
    }

    public boolean isWorldTlasDispatchUseful() {
        return worldTlasReady
                && hasWorldInstances()
                && builtRevision >= 0L;
    }

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

    public boolean isStreamingFrameDispatchUseful(RendererUpdateLoop.BacklogSnapshot rendererBacklog) {
        Objects.requireNonNull(rendererBacklog, "rendererBacklog");
        return isWorldTlasDispatchUseful()
                && !rendererBacklog.hasUnaccountedRtCoverageGap(builtSectionInstances, pendingRtSectionBuilds);
    }

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
