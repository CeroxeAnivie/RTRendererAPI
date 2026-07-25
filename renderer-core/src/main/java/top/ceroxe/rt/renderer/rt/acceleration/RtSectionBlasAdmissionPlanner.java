package top.ceroxe.rt.renderer.rt.acceleration;

import java.util.Objects;

/**
 * Pure capacity policy for foreground-reserved asynchronous section BLAS work.
 */
final class RtSectionBlasAdmissionPlanner {
    private static final int BACKGROUND_CAPACITY_DIVISOR = 4;
    private static final int INTERACTIVE_SUBMISSION_MAX_SECTIONS = 1;
    private static final long INTERACTIVE_SUBMISSION_MAX_TRIANGLES = 96_000L;
    private static final int BACKGROUND_SUBMISSION_MAX_SECTIONS = 32;
    private static final long BACKGROUND_SUBMISSION_MAX_TRIANGLES = 384_000L;
    private static final int FOREGROUND_SUBMISSION_MAX_SECTIONS = 16;
    private static final long FOREGROUND_SUBMISSION_MAX_TRIANGLES = 384_000L;

    private RtSectionBlasAdmissionPlanner() {
    }

    static int backgroundCapacity(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        return Math.max(1, capacity / BACKGROUND_CAPACITY_DIVISOR);
    }

    static int remainingBackgroundCapacity(int capacity, int retained) {
        if (retained < 0) {
            throw new IllegalArgumentException("retained must not be negative");
        }
        return Math.max(0, backgroundCapacity(capacity) - retained);
    }

    static int effectiveSubmissionWindow(int configuredCapacity, int orderedQueueCount) {
        if (configuredCapacity <= 0) {
            throw new IllegalArgumentException("configuredCapacity must be positive");
        }
        if (orderedQueueCount <= 0) {
            throw new IllegalArgumentException("orderedQueueCount must be positive");
        }
        return configuredCapacity;
    }

    /**
     * Maximum number of section-BLAS pages submitted but not yet completed on the ordered queue.
     *
     * <p>A Vulkan queue is FIFO and submitted work cannot be reprioritized. Keeping twelve CPU
     * recording transactions is useful, but submitting all twelve to one native queue turns a
     * later block mutation into seconds of non-preemptible queue wait. One page per ordered queue
     * keeps the device fed while leaving the next submission boundary available to newer
     * interactive work. CPU recording remains independently pipelined by
     * {@link #effectiveSubmissionWindow(int, int)}.</p>
     */
    static int gpuSubmissionWindow(int orderedQueueCount) {
        if (orderedQueueCount <= 0) {
            throw new IllegalArgumentException("orderedQueueCount must be positive");
        }
        return orderedQueueCount;
    }

    static int backgroundBatchCapacity(int capacity) {
        return Math.min(2, backgroundCapacity(capacity));
    }

    static long backgroundCapacity(long capacity) {
        if (capacity <= 0L) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        return Math.max(1L, capacity / BACKGROUND_CAPACITY_DIVISOR);
    }

    static long remainingBackgroundCapacity(long capacity, long retained) {
        if (retained < 0L) {
            throw new IllegalArgumentException("retained must not be negative");
        }
        return Math.max(0L, backgroundCapacity(capacity) - retained);
    }

    static RtAdaptiveBuildBudget.Limits submissionLimits(
            RtAdaptiveBuildBudget.Limits frameLimits,
            boolean foregroundSubmission,
            boolean interactiveSubmission
    ) {
        Objects.requireNonNull(frameLimits, "frameLimits");
        if (interactiveSubmission && !foregroundSubmission) {
            throw new IllegalArgumentException("interactive BLAS submission must also be foreground");
        }
        int sectionLimit = interactiveSubmission
                ? INTERACTIVE_SUBMISSION_MAX_SECTIONS
                : foregroundSubmission
                  ? FOREGROUND_SUBMISSION_MAX_SECTIONS
                  : BACKGROUND_SUBMISSION_MAX_SECTIONS;
        long triangleLimit = interactiveSubmission
                ? INTERACTIVE_SUBMISSION_MAX_TRIANGLES
                : foregroundSubmission
                  ? FOREGROUND_SUBMISSION_MAX_TRIANGLES
                  : BACKGROUND_SUBMISSION_MAX_TRIANGLES;
        return new RtAdaptiveBuildBudget.Limits(
                Math.min(frameLimits.maxBuilds(), sectionLimit),
                Math.min(frameLimits.maxTriangles(), triangleLimit)
        );
    }

    static RtAdaptiveBuildBudget.Limits foregroundBootstrapLimits(
            RtAdaptiveBuildBudget.Limits adaptiveLimits,
            int configuredMaxBuilds,
            long configuredMaxTriangles
    ) {
        Objects.requireNonNull(adaptiveLimits, "adaptiveLimits");
        if (configuredMaxBuilds <= 0 || configuredMaxTriangles <= 0L) {
            throw new IllegalArgumentException("configured bootstrap BLAS limits must be positive");
        }
        /* Bootstrap changes priority, never the measured per-frame work budget. */
        return adaptiveLimits;
    }

    static boolean shouldApplyCompletedResults(boolean withinFrameBudget) {
        return withinFrameBudget;
    }
}
