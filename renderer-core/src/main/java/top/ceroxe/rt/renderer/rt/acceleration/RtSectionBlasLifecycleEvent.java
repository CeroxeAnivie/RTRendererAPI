package top.ceroxe.rt.renderer.rt.acceleration;

import jdk.jfr.*;

/**
 * JFR payload for one section-BLAS ownership edge.
 */
@Name("top.ceroxe.rt.SectionBlasLifecycle")
@Label("RTRenderer Section BLAS Lifecycle")
@Category({"RTRenderer", "Acceleration", "BLAS"})
@StackTrace(false)
final class RtSectionBlasLifecycleEvent extends Event {
    String stage;
    int sectionX;
    int sectionY;
    int sectionZ;
    long contentRevision;
    long buildSequence;
    long resourceRevision;
    long safeAfterRevision;
    String outcome;
    String retirementReason;
    long traceId;
    String causalitySource;
    long frameSequence;
    long captureSequence;
    long droppedBefore;
    int batchSectionCount;
    long batchTriangleCount;
    int interactiveSectionCount;
    int preferredSectionCount;
    int gpuBatchesAhead;
    int gpuSubmissionWindow;
    long elapsedNanos;
    long gpuExecutionNanos;
    long lastNotReadyToObservationNanos;
    long fenceNotReadyPolls;
}
