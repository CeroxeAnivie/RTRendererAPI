package top.ceroxe.mcvulkanrt.renderer.rt.acceleration;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/** JFR payload for one section-BLAS ownership edge. */
@Name("top.ceroxe.mcvulkanrt.SectionBlasLifecycle")
@Label("MCVulkanRT Section BLAS Lifecycle")
@Category({"MCVulkanRT", "Acceleration", "BLAS"})
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

@Name("top.ceroxe.mcvulkanrt.SectionBlasCaptureLoss")
@Label("MCVulkanRT Section BLAS Capture Loss")
@Category({"MCVulkanRT", "Acceleration", "BLAS"})
@StackTrace(false)
final class RtSectionBlasCaptureLossEvent extends Event {
    long maxEvents;
    long attemptedEvents;
    long droppedEventsLowerBound;
    long overflowSampleStride;
}
