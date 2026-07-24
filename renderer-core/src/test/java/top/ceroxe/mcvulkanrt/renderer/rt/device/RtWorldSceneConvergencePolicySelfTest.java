package top.ceroxe.mcvulkanrt.renderer.rt.device;

import top.ceroxe.mcvulkanrt.renderer.RendererUpdateLoop;
import top.ceroxe.mcvulkanrt.renderer.rt.RtSceneReadiness;
import top.ceroxe.mcvulkanrt.renderer.rt.acceleration.RtWorldTlasCache;

/** Focused dependency-free checks for ordered GPU scene convergence. */
public final class RtWorldSceneConvergencePolicySelfTest {
    private RtWorldSceneConvergencePolicySelfTest() {
    }

    public static void main(String[] args) {
        prioritizesSectionBlasAndWorldTlasGenerations();
        isolatesInteractiveTerrainUrgencyFromStreaming();
        System.out.println("RtWorldSceneConvergencePolicySelfTest passed");
    }

    private static void prioritizesSectionBlasAndWorldTlasGenerations() {
        RtSceneReadiness sectionBlasPending = readiness(31L, 32L, 24, 18_000L);
        require(
                RtWorldSceneConvergencePolicy.shouldPrioritizeSceneConvergence(sectionBlasPending),
                "section BLAS backlog must outrank another full-resolution frame"
        );
        RtSceneReadiness worldTlasPending = readiness(31L, 32L, 0, 0L);
        require(
                RtWorldSceneConvergencePolicy.shouldPrioritizeSceneConvergence(worldTlasPending),
                "world TLAS publication must retain queue priority after BLAS completion"
        );

        RtSceneReadiness converged = readiness(32L, 32L, 0, 0L);
        require(
                !RtWorldSceneConvergencePolicy.shouldPrioritizeSceneConvergence(converged),
                "a current immutable world must leave convergence mode"
        );
    }

    private static RtSceneReadiness readiness(
            long builtRevision,
            long latestRevision,
            int pendingSections,
            long pendingTriangles
    ) {
        return new RtSceneReadiness(
                true,
                96,
                96,
                0,
                96,
                0,
                pendingSections,
                false,
                pendingTriangles,
                10_000L,
                builtRevision,
                latestRevision,
                pendingSections > 0
        );
    }

    private static void isolatesInteractiveTerrainUrgencyFromStreaming() {
        RendererUpdateLoop.BacklogSnapshot emptyBacklog = RendererUpdateLoop.BacklogSnapshot.empty();
        RendererUpdateLoop.BacklogSnapshot streamingBacklog =
                new RendererUpdateLoop.BacklogSnapshot(8, 0, 2, 0, 4, 4, 32, 16, 384, 64, 128, 0L);

        require(
                RtInteractiveTerrainUpdatePolicy.shouldForceWorldTlasForTerrainMutation(
                        true,
                        true,
                        false,
                        true,
                        emptyBacklog
                ),
                "an isolated nearby block edit must request an urgent terrain generation"
        );
        require(
                RtInteractiveTerrainUpdatePolicy.shouldForceWorldTlasForTerrainMutation(
                        true,
                        false,
                        false,
                        false,
                        emptyBacklog
                ),
                "an isolated section removal must urgently contract the terrain TLAS"
        );
        require(
                RtInteractiveTerrainUpdatePolicy.shouldForceWorldTlasForTerrainMutation(
                        true,
                        true,
                        true,
                        true,
                        emptyBacklog
                ),
                "an exact interactive mutation fence must retain urgency even when the batch also carries streaming"
        );
        require(
                !RtInteractiveTerrainUpdatePolicy.shouldForceWorldTlasForTerrainMutation(
                        true,
                        false,
                        false,
                        false,
                        streamingBacklog
                ),
                "terrain removal must not bypass unfinished renderer ownership"
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
