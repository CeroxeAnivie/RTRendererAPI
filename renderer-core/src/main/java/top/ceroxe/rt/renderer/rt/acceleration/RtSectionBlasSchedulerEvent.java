package top.ceroxe.rt.renderer.rt.acceleration;

import jdk.jfr.*;

/**
 * Captures scheduler admission state without pretending that a deferred page is a resource
 * lifecycle transition. This is deliberately separate from {@link RtSectionBlasLifecycleEvent}:
 * one answers why a page was not submitted, the other proves ownership after submission.
 */
@Name("top.ceroxe.rt.SectionBlasScheduler")
@Label("RTRenderer Section BLAS Scheduler")
@Category({"RTRenderer", "Acceleration", "BLAS"})
@StackTrace(false)
final class RtSectionBlasSchedulerEvent extends Event {
    String outcome;
    long passSequence;
    int pendingBuilds;
    int recordingBatches;
    int gpuBatches;
    int incompleteGpuBatches;
    boolean foregroundPending;
    boolean foregroundCoverageIncomplete;
    boolean firstWorldFrontPending;
    long frameBudgetNanos;
    long elapsedNanos;
}
