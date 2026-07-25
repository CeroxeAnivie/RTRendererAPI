package top.ceroxe.rt.renderer.rt.acceleration;

/**
 * Public budget policy adapter independent from section BLAS ownership.
 */
public class RtAdaptiveBuildBudget {
    private final RtBlasBuildBudgetController controller;

    /**
     * Creates an adaptive per-section build budget.
     *
     * @param maxBuilds    upper build-count limit
     * @param maxTriangles upper triangle-count limit
     * @param minBuilds    lower build-count limit
     * @param minTriangles lower triangle-count limit
     * @param targetNanos  target per-build duration
     * @param highNanos    overload duration threshold
     */
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

    /**
     * Returns the current adaptive limits.
     *
     * @return immutable limits snapshot
     */
    public Limits currentLimits() {
        RtBlasBuildBudgetController.Limits limits = controller.currentLimits();
        return new Limits(limits.maxBuilds(), limits.maxTriangles());
    }

    /**
     * Records one completed build for adaptation.
     *
     * @param elapsedNanos nonnegative build duration
     * @param hasBacklog   whether queued work remains
     */
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
     *
     * @param elapsedNanos nonnegative aggregate batch duration
     * @param workItems    positive number of completed work items
     * @param hasBacklog   whether queued work remains
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

    /**
     * Records an idle scheduling pass.
     *
     * @param hasBacklog whether queued work remains despite the idle pass
     */
    public void recordIdle(boolean hasBacklog) {
        controller.recordIdle(hasBacklog);
    }

    /**
     * Immutable build-count and triangle-count limits.
     *
     * @param maxBuilds    positive build limit
     * @param maxTriangles positive triangle limit
     */
    public record Limits(int maxBuilds, long maxTriangles) {
        /**
         * Validates that both limits are positive.
         */
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
