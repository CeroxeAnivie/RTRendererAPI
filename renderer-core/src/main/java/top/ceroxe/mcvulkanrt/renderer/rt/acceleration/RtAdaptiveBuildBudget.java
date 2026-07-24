package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

/** Public budget policy adapter independent from section BLAS ownership. */
public class RtAdaptiveBuildBudget {
    private final RtBlasBuildBudgetController controller;

    public RtAdaptiveBuildBudget(
            int maxBuilds,
            long maxTriangles,
            int minBuilds,
            long minTriangles,
            long targetNanos,
            long highNanos
    ) {
        controller = new RtBlasBuildBudgetController(
                maxBuilds, maxTriangles, minBuilds, minTriangles, targetNanos, highNanos
        );
    }

    public Limits currentLimits() {
        RtBlasBuildBudgetController.Limits limits = controller.currentLimits();
        return new Limits(limits.maxBuilds(), limits.maxTriangles());
    }

    public void recordBuild(long elapsedNanos, boolean hasBacklog) {
        controller.recordBuild(elapsedNanos, hasBacklog);
    }

    /**
     * Feeds one asynchronous batch back into the per-section throughput controller.
     *
     * <p>The worker records a complete batch outside the render-thread frame budget. Treating the
     * batch's aggregate duration as the cost of one section makes a healthy large batch look
     * overloaded and repeatedly halves the next batch. Normalizing here keeps the controller's
     * existing per-work-item thresholds meaningful while the caller's {@code maxElapsedNanos}
     * remains the independent render-thread latency fence.</p>
     */
    public void recordBatch(long elapsedNanos, int workItems, boolean hasBacklog) {
        if (elapsedNanos < 0L) {
            throw new IllegalArgumentException("elapsedNanos must not be negative");
        }
        if (workItems <= 0) {
            throw new IllegalArgumentException("workItems must be positive");
        }
        long normalizedNanos = elapsedNanos == 0L
                ? 0L
                : 1L + (elapsedNanos - 1L) / workItems;
        controller.recordBuild(normalizedNanos, hasBacklog);
    }

    public void recordIdle(boolean hasBacklog) {
        controller.recordIdle(hasBacklog);
    }

    public record Limits(int maxBuilds, long maxTriangles) {
        public Limits {
            if (maxBuilds <= 0) {
                throw new IllegalArgumentException("maxBuilds must be positive");
            }
            if (maxTriangles <= 0L) {
                throw new IllegalArgumentException("maxTriangles must be positive");
            }
        }
    }
}
